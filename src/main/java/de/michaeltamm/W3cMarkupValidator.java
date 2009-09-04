/*
 * Copyright 2009 Michael Tamm
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package de.michaeltamm;

import org.apache.commons.httpclient.HttpClient;
import org.apache.commons.httpclient.HttpMethodBase;
import org.apache.commons.httpclient.MultiThreadedHttpConnectionManager;
import org.apache.commons.httpclient.methods.PostMethod;
import org.apache.commons.httpclient.methods.multipart.MultipartRequestEntity;
import org.apache.commons.httpclient.methods.multipart.Part;
import org.apache.commons.httpclient.methods.multipart.StringPart;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;

/**
 * Validates HTML documents using a W3V markup Validaton Service
 * like <a href="http://validator.w3.org">http://validator.w3.org</a>.
 *
 * @author Michael Tamm
 */
public class W3cMarkupValidator {

    private String _checkUrl = "http://validator.w3.org/check";
    private HttpClient _httpClient;

    /**
     * Setter method for dependency injection.
     */
    public void setCheckUrl(String checkUrl) {
        _checkUrl = checkUrl;
    }

    /**
     * Setter method for dependency injection.
     */
    public void setHttpClient(HttpClient httpClient) {
        _httpClient = httpClient;
    }

    /**
     * Validates the given <code>html</code>.
     *
     * @param html a complete HTML document
     */
    public W3cMarkupValidationResult validate(String html) {
        if (_httpClient == null) {
            final MultiThreadedHttpConnectionManager connectionManager = new MultiThreadedHttpConnectionManager();
            _httpClient = new HttpClient(connectionManager);
        }
        final PostMethod postMethod = new PostMethod(_checkUrl);
        final Part[] data = {
            // The HTML to validate ...
            new StringPart("fragment", html, "UTF-8"),
            new StringPart("prefill", "0", "UTF-8"),
            new StringPart("doctype", "Inline", "UTF-8"),
            new StringPart("prefill_doctype", "html401", "UTF-8"),
            // List Messages Sequentially | Group Error Messages by Type ...
            new StringPart("group", "0", "UTF-8"),
            // Show source ...
            new StringPart("ss", "1", "UTF-8"),
            // Verbose Output ...
            new StringPart("verbose", "1", "UTF-8"),
        };
        postMethod.setRequestEntity(new MultipartRequestEntity(data, postMethod.getParams()));
        try {
            final int status = _httpClient.executeMethod(postMethod);
            if (status != 200) {
                throw new RuntimeException(_checkUrl + " responded with " + status);
            }
            final String pathPrefix = _checkUrl.substring(0, _checkUrl.lastIndexOf('/') + 1);
            final String resultPage = getResponseBody(postMethod)
                                .replace("\"./", "\"" + pathPrefix)
                                .replace("src=\"images/", "src=\"" + pathPrefix + "images/")
                                .replace("<script type=\"text/javascript\" src=\"loadexplanation.js\">", "<script type=\"text/javascript\" src=\"" + pathPrefix + "loadexplanation.js\">");
            return new W3cMarkupValidationResult(resultPage);
        } catch (RuntimeException e) {
            throw e;
        } catch (Exception e) {
            throw new RuntimeException(e.getMessage(), e);
        } finally {
            postMethod.releaseConnection();
        }
    }

    private String getResponseBody(final HttpMethodBase httpMethod) throws IOException {
        final InputStream in = httpMethod.getResponseBodyAsStream();
        try {
            final ByteArrayOutputStream out = new ByteArrayOutputStream(1000000);
            final byte[] buffer = new byte[4096];
            int n;
            while ((n = in.read(buffer)) > 0) {
                out.write(buffer, 0, n);
            }
            return out.toString(httpMethod.getResponseCharSet());
        } finally {
            try {
                in.close();
            } catch (IOException ignored) {}
        }
    }
}
