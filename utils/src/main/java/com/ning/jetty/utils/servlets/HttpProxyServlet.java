/*
 * Copyright 2010-2011 Ning, Inc.
 *
 * Ning licenses this file to you under the Apache License, version 2.0
 * (the "License"); you may not use this file except in compliance with the
 * License.  You may obtain a copy of the License at:
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.  See the
 * License for the specific language governing permissions and limitations
 * under the License.
 */

package com.ning.jetty.utils.servlets;

import com.google.common.io.ByteStreams;
import com.google.inject.Inject;
import com.google.inject.Singleton;
import com.mogwee.executors.Executors;
import com.ning.http.client.AsyncHttpClient;
import com.ning.http.client.AsyncHttpClientConfig;
import com.ning.http.client.Request;
import com.ning.http.client.RequestBuilder;
import com.ning.http.client.Response;
import com.ning.http.client.generators.InputStreamBodyGenerator;

import javax.servlet.ServletConfig;
import javax.servlet.ServletException;
import javax.servlet.ServletOutputStream;
import javax.servlet.ServletRequest;
import javax.servlet.ServletResponse;
import javax.servlet.UnavailableException;
import javax.servlet.http.HttpServlet;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import javax.servlet.http.HttpServletResponseWrapper;
import java.io.IOException;
import java.io.InputStream;
import java.util.Collection;
import java.util.Collections;
import java.util.HashSet;
import java.util.concurrent.ExecutionException;

@Singleton
public class HttpProxyServlet extends HttpServlet
{
    protected HashSet<String> dontProxyHeaders = new HashSet<String>();

    {
        dontProxyHeaders.add("proxy-connection");
        dontProxyHeaders.add("connection");
        dontProxyHeaders.add("keep-alive");
        dontProxyHeaders.add("transfer-encoding");
        dontProxyHeaders.add("te");
        dontProxyHeaders.add("trailer");
        dontProxyHeaders.add("proxy-authorization");
        dontProxyHeaders.add("proxy-authenticate");
        dontProxyHeaders.add("upgrade");
    }

    @Inject
    private ServiceFinder serviceFinder;

    private ServletConfig config;
    private AsyncHttpClient client;

    @Override
    public void init(final ServletConfig config) throws ServletException
    {
        this.config = config;

        // Don't limit the number of connections per host
        // See https://github.com/ning/async-http-client/issues/issue/28
        final AsyncHttpClientConfig.Builder builder = new AsyncHttpClientConfig.Builder();
        builder.setMaximumConnectionsPerHost(-1);
        builder.setAllowPoolingConnection(true);
        builder.setExecutorService(Executors.newCachedThreadPool("HttpProxyServlet-AsyncHttpClient"));
        builder.setUserAgent("ning-service/1.0");
        client = new AsyncHttpClient(builder.build());

        config.getServletContext().log("Created new HttpProxyServlet");
    }

    @Override
    public ServletConfig getServletConfig()
    {
        return config;
    }

    private void proxyService(final ServletRequest req, ServletResponse res) throws ServletException, IOException
    {
        while (res instanceof HttpServletResponseWrapper) {
            res = ((HttpServletResponseWrapper) res).getResponse();
        }
        final HttpServletRequest request = (HttpServletRequest) req;
        final HttpServletResponse response = (HttpServletResponse) res;

        // Find the remote host to talk to
        final String remoteHost;
        try {
            remoteHost = serviceFinder.getRemoteHost();
        }
        catch (Exception e) {
            // The Servlet is temporarily unavailable
            throw new UnavailableException("The service finder couldn't find the remote host: " + (e.getCause() == null ? e.toString() : e.getCause().toString()), -1);
        }
        final String proxyTo = "http://" + remoteHost;

        // Create the new http request to the proxied host
        final Request newRequest = cloneRequest(request, proxyTo).build();
        final AsyncHttpClient.BoundRequestBuilder builder = client.prepareRequest(newRequest);

        Response proxiedResponse = null;
        try {
            // Me want Servlet 3.0 :(
            proxiedResponse = builder.execute().get();
        }
        catch (InterruptedException e) {
            config.getServletContext().log("Interrupted while waiting on the remote host", e);
            return;
        }
        catch (ExecutionException e) {
            config.getServletContext().log("Error talking to the remote host", e);
            return;
        }

        response.setStatus(proxiedResponse.getStatusCode());
        // Copy headers
        for (final String headerName : proxiedResponse.getHeaders().keySet()) {
            if (dontProxyHeaders.contains(headerName)) {
                continue;
            }

            for (final String headerValue : proxiedResponse.getHeaders().get(headerName)) {
                response.addHeader(headerName, headerValue);
            }
        }
        // Copy response body
        final ServletOutputStream responseOutputStream = response.getOutputStream();
        final InputStream stream = proxiedResponse.getResponseBodyAsStream();
        ByteStreams.copy(stream, responseOutputStream);
    }

    private RequestBuilder cloneRequest(final HttpServletRequest request, final String proxyTo) throws IOException
    {
        final RequestBuilder builder = new RequestBuilder();
        boolean hasContent = false;
        boolean hasXff = false;

        String connectionHdr = request.getHeader("Connection");
        if (connectionHdr != null) {
            connectionHdr = connectionHdr.toLowerCase();
            if (!connectionHdr.contains("keep-alive") && !connectionHdr.contains("close")) {
                connectionHdr = null;
            }
        }

        // We are guaranteed that headers are Strings
        @SuppressWarnings("unchecked")
        final Collection headerNames = Collections.list(request.getHeaderNames());
        for (final Object headerObjectName : headerNames) {
            final String headerName = (String) headerObjectName;

            // Don't copy headers on close
            if (connectionHdr != null && connectionHdr.contains(headerName)) {
                continue;
            }

            if (dontProxyHeaders.contains(headerName)) {
                continue;
            }

            if ("content-type".equalsIgnoreCase(headerName)) {
                hasContent = true;
            }
            if ("X-Forwarded-For".equalsIgnoreCase(headerName)) {
                hasXff = true;
            }

            @SuppressWarnings("unchecked")
            final Collection headerValues = Collections.list(request.getHeaders(headerName));
            for (final Object headerValue : headerValues) {
                builder.addHeader(headerName, (String) headerValue);
            }
        }
        builder.addHeader("Via", "Ning proxy");
        if (!hasXff) {
            builder.addHeader("X-Forwarded-For", request.getRemoteAddr());
        }

        // Need to set the Method before setting the body
        builder.setMethod(request.getMethod());
        if (hasContent) {
            final InputStream in = request.getInputStream();
            builder.setBody(new InputStreamBodyGenerator(in));
        }

        String uri = proxyTo + request.getRequestURI();
        if (request.getQueryString() != null) {
            uri += "?" + request.getQueryString();
        }
        builder.setUrl(uri);

        return builder;
    }

    @Override
    public String getServletInfo()
    {
        return "HttpProxyServlet";
    }

    @Override
    public void destroy()
    {
        if (client != null) {
            client.close();
        }
    }

    @Override
    protected void service(final HttpServletRequest req, final HttpServletResponse resp) throws ServletException, IOException
    {
        proxyService(req, resp);
    }

    @Override
    public void service(final ServletRequest req, final ServletResponse res) throws ServletException, IOException
    {
        proxyService(req, res);
    }

    @Override
    protected void doDelete(final HttpServletRequest req, final HttpServletResponse resp) throws ServletException, IOException
    {
        proxyService(req, resp);
    }

    @Override
    protected void doGet(final HttpServletRequest req, final HttpServletResponse resp) throws ServletException, IOException
    {
        proxyService(req, resp);
    }

    @Override
    protected void doHead(final HttpServletRequest req, final HttpServletResponse resp) throws ServletException, IOException
    {
        proxyService(req, resp);
    }

    @Override
    protected void doOptions(final HttpServletRequest req, final HttpServletResponse resp) throws ServletException, IOException
    {
        proxyService(req, resp);
    }

    @Override
    protected void doPost(final HttpServletRequest req, final HttpServletResponse resp) throws ServletException, IOException
    {
        proxyService(req, resp);
    }

    @Override
    protected void doPut(final HttpServletRequest req, final HttpServletResponse resp) throws ServletException, IOException
    {
        proxyService(req, resp);
    }

    @Override
    protected void doTrace(final HttpServletRequest req, final HttpServletResponse resp) throws ServletException, IOException
    {
        proxyService(req, resp);
    }
}
