/*
 * JBoss, Home of Professional Open Source
 * Copyright 2011 Red Hat Inc. and/or its affiliates and other contributors
 * as indicated by the @author tags. All rights reserved.
 * See the copyright.txt in the distribution for a
 * full listing of individual contributors.
 *
 * This copyrighted material is made available to anyone wishing to use,
 * modify, copy, or redistribute it subject to the terms and conditions
 * of the GNU Lesser General Public License, v. 2.1.
 * This program is distributed in the hope that it will be useful, but WITHOUT A
 * WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS FOR A
 * PARTICULAR PURPOSE.  See the GNU Lesser General Public License for more details.
 * You should have received a copy of the GNU Lesser General Public License,
 * v.2.1 along with this distribution; if not, write to the Free Software
 * Foundation, Inc., 51 Franklin Street, Fifth Floor, Boston,
 * MA  02110-1301, USA.
 */

package org.jboss.dmr.client.dispatch.impl;

import static org.jboss.dmr.client.ModelDescriptionConstants.*;

import java.util.List;

import com.allen_sauer.gwt.log.client.Log;
import com.google.gwt.core.client.GWT;
import com.google.gwt.http.client.Request;
import com.google.gwt.http.client.RequestBuilder;
import com.google.gwt.http.client.RequestCallback;
import com.google.gwt.http.client.RequestException;
import com.google.gwt.http.client.Response;
import com.google.gwt.user.client.rpc.AsyncCallback;
import com.google.inject.Inject;
import org.jboss.dmr.client.ModelDescriptionConstants;
import org.jboss.dmr.client.Property;
import org.jboss.dmr.client.dispatch.ActionHandler;
import org.jboss.dmr.client.dispatch.Diagnostics;
import org.jboss.dmr.client.dispatch.DispatchRequest;
import org.jboss.dmr.client.ModelNode;

/**
 * @author Heiko Braun
 * @date 3/17/11
 */
public class DMRHandler implements ActionHandler<DMRAction, DMRResponse> {

    private static final String HEADER_CONTENT_TYPE = "Content-Type";
    private static final String HEADER_ACCEPT = "Accept";
    private static final String DMR_ENCODED = "application/dmr-encoded";
    private static final String HEADER_CONNECTION = "Connection";
    private static final String KEEP_ALIVE = "Keep-Alive";

    /**
     * The read reasource description supports the following parameters:
     * recursive, proxies, operations, inherited plus one not documented: locale.
     * See https://docs.jboss.org/author/display/AS72/Global+operations#Globaloperations-readresourcedescription
     * for a more detailed description
     */
    private static final String[] READ_RESOURCE_DESCRIPTION_OPTIONAL_PARAMETERS = new String[] {
            RECURSIVE, PROXIES, OPERATIONS, INHERITED, LOCALE};

    private static long idCounter = 0;

    private final RequestBuilder postRequestBuilder;
    private Diagnostics diagnostics = GWT.create(Diagnostics.class);
    private boolean trackInvocations = diagnostics.isEnabled();
    private DMREndpointConfig endpointConfig = GWT.create(DMREndpointConfig.class);

    @Inject
    public DMRHandler()
    {
        postRequestBuilder = new RequestBuilder(RequestBuilder.POST, endpointConfig.getUrl());
        postRequestBuilder.setHeader(HEADER_ACCEPT, DMR_ENCODED);
        postRequestBuilder.setHeader(HEADER_CONTENT_TYPE, DMR_ENCODED);
        postRequestBuilder.setIncludeCredentials(true);
    }

    private static native void redirect(String url)/*-{
        $wnd.location = url;
    }-*/;

    private static String getToken(ModelNode operation)
    {
        StringBuffer sb = new StringBuffer();
        if(operation.get(OP).asString().equals(COMPOSITE))
        {
            for(ModelNode step : operation.get(STEPS).asList())
            {
                sb.append(" _").append(getOpToken(step));
            }
        }
        else
        {
            sb.append(getOpToken(operation));
        }
        return sb.toString();
    }

    private static String getOpToken(ModelNode operation)
    {
        StringBuffer sb = new StringBuffer();
        sb.append(operation.get(ADDRESS).asString())
                .append(": ")
                .append(operation.get(OP))
                .append("; ")
                .append(operation.get(CHILD_TYPE).asString())
                .append("; ");

        if(operation.get(NAME).isDefined())
        {
            sb.append(operation.get(NAME).asString());
        }
        return sb.toString();
    }

    @Override
    public DispatchRequest execute(DMRAction action, final AsyncCallback<DMRResponse> resultCallback)
    {
        assert action.getOperation() != null;
        final ModelNode operation = action.getOperation();

        Request request = executeRequest(resultCallback, operation);
        return new DispatchRequestHandle(request);
    }

    @Override
    public DispatchRequest undo(DMRAction action, DMRResponse result, AsyncCallback<Void> callback)
    {
        throw new RuntimeException("Not implemented yet.");
    }

    private Request executeRequest(final AsyncCallback<DMRResponse> resultCallback, final ModelNode operation)
    {
        if (idCounter == Long.MAX_VALUE)
        {
            idCounter = 0;
        }

        Request request = null;
        try
        {
            final String id = String.valueOf(idCounter++);
            trace(Type.BEGIN, id, operation);

            final RequestBuilder requestBuilder = chooseRequestBuilder(operation);
            trace(Type.SERIALIZED, id, operation);

            final RequestCallback requestCallback = new RequestCallback()
            {
                @Override
                public void onResponseReceived(Request request, Response response)
                {
                    trace(Type.RECEIVE, id, operation);

                    int statusCode = response.getStatusCode();
                    if (200 == statusCode)
                    {
                        resultCallback.onSuccess(new DMRResponse(requestBuilder.getHTTPMethod(), response.getText(),
                                response.getHeader(HEADER_CONTENT_TYPE)));
                    }
                    else if (401 == statusCode || 0 == statusCode)
                    {
                        resultCallback.onFailure(new Exception("Authentication required."));
                    }
                    else if (307 == statusCode)
                    {
                        String location = response.getHeader("Location");
                        Log.error("Redirect '" + location + "'. Could not execute " + operation.toString());
                        redirect(location);
                    }
                    else if (503 == statusCode)
                    {
                        resultCallback.onFailure(
                                new Exception("Service temporarily unavailable. Is the server is still booting?"));
                    }
                    else
                    {
                        StringBuilder sb = new StringBuilder();
                        sb.append("Unexpected HTTP response").append(": ").append(statusCode);
                        sb.append("\n\n");
                        sb.append("Request\n");
                        sb.append(operation.toString());
                        sb.append("\n\nResponse\n\n");
                        sb.append(response.getStatusText()).append("\n");
                        String payload = response.getText().equals("") ? "No details" :
                                ModelNode.fromBase64(response.getText()).toString();
                        sb.append(payload);
                        resultCallback.onFailure(new Exception(sb.toString()));
                    }
                    trace(Type.END, id, operation);
                }

                @Override
                public void onError(Request request, Throwable e)
                {
                    trace(Type.RECEIVE, id, operation);
                    resultCallback.onFailure(e);
                    trace(Type.END, id, operation);
                }
            };
            requestBuilder.setCallback(requestCallback);
            request = requestBuilder.send();
            trace(Type.SEND, id, operation);
        }
        catch (RequestException e)
        {
            resultCallback.onFailure(e);
        }
        return request;
    }

    private RequestBuilder chooseRequestBuilder(final ModelNode operation)
    {
        RequestBuilder requestBuilder;
        final String op = operation.get(OP).asString();
        if (READ_RESOURCE_DESCRIPTION_OPERATION.equals(op))
        {
            String endpoint = endpointConfig.getUrl();
            if (endpoint.endsWith("/"))
            {
                endpoint = endpoint.substring(0, endpoint.length() - 1);
            }
            String descriptionUrl = endpoint + descriptionOperationToUrl(operation);
            requestBuilder = new RequestBuilder(RequestBuilder.GET,
                    com.google.gwt.http.client.URL.encode(descriptionUrl));
            requestBuilder.setHeader(HEADER_ACCEPT, DMR_ENCODED);
            requestBuilder.setHeader(HEADER_CONTENT_TYPE, DMR_ENCODED);
            requestBuilder.setIncludeCredentials(true);
            requestBuilder.setRequestData(null);
        }
        else
        {
            requestBuilder = postRequestBuilder;
            requestBuilder.setRequestData(operation.toBase64String());
        }
        return requestBuilder;
    }

    private String descriptionOperationToUrl(final ModelNode operation)
    {
        StringBuilder url = new StringBuilder();
        final List<Property> address = operation.get(ADDRESS).asPropertyList();
        for (Property property : address)
        {
            url.append("/").append(property.getName()).append("/").append(property.getValue().asString());
        }

        url.append("?operation=").append("resource-description");
        for (String parameter : READ_RESOURCE_DESCRIPTION_OPTIONAL_PARAMETERS)
        {
            if (operation.has(parameter))
            {
                url.append("&").append(parameter).append("=").append(operation.get(parameter).asString());
            }
        }
        return url.toString();
    }

    private void trace(Type type, String id, ModelNode operation)
    {
        if(!trackInvocations) return;
        if(Type.BEGIN.equals(type))
        {
            diagnostics.logRpc(type.getClassifier(), id, System.currentTimeMillis(), getToken(operation));
        }
        else
        {
            diagnostics.logRpc(type.getClassifier(), id, System.currentTimeMillis());
        }
    }

    private static enum Type
    {
        BEGIN("begin"),
        END("end"),
        SEND("requestSent"),
        RECEIVE("responseReceived"),
        SERIALIZED("requestSerialized"),
        DESERIALIZED("responseDeserialized");
        private String classifier;

        private Type(String classifier)
        {
            this.classifier = classifier;
        }

        public String getClassifier()
        {
            return classifier;
        }
    }


    class DispatchRequestHandle implements DispatchRequest
    {
        private Request delegate;

        DispatchRequestHandle(Request delegate)
        {
            this.delegate = delegate;
        }

        @Override
        public void cancel()
        {
            if (delegate != null)
            {
                delegate.cancel();
            }
        }

        @Override
        public boolean isPending()
        {
            return delegate != null ? delegate.isPending() : false;
        }
    }
}
