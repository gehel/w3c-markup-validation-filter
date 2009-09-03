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

import javax.servlet.*;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import javax.servlet.http.HttpServletResponseWrapper;
import java.io.*;

/**
 * <p>Captures all rendered HTML pages, validates them using a W3C Markup Validation Service,
 * and injects a little box into the HTML page, which becomes green, if the HTML is valid,
 * or otherwise red. The injected box also contains a link to the validation result page,
 * where you can see a detailed description for each error.
 * </p>
 * <p>Init-Paramters:
 * <table>
 *     <thead>
 *     <tbody>
 *         <tr><th>Name</th><th>Description</th><th>Default Value</th></tr>
 *     </tbody>
 *     </thead>
 *     <tbody>
 *         <tr>
 *             <td valign="top">enabled</td>
 *             <td>true or false</code>. If set to false, the W3cMarkupValidationFilter does nothing.</td>
 *             <td valign="top">true</td>
 *         </tr>
 *         <tr>
 *             <td valign="top">checkUrl</td>
 *             <td>The URL of the W3C Markup Validation Service used for validating.</td>
 *             <td valign="top">http://validator.w3.org/check</td>
 *         </tr>
 *         <tr>
 *             <td valign="top">jqueryUrl</td>
 *             <td>The URL from which the jQuery JavaScript library shoud be retireved by a browser.
 *                 The jQuery library is used to inject the little box, which displays the validation
 *                 result, into the HTML page.
 *             </td>
 *             <td valign="top">http://ajax.googleapis.com/ajax/libs/jquery/1.3.2/jquery.min.js</td>
 *         </tr>
 *     </tbody>
 * </table>
 * </p>
 *
 * @author Michael Tamm
 */
public class W3cMarkupValidationFilter implements Filter {

    private static final int MAX_CACHED_RESULTS = 20;

    /**
     * Removes leading and trailing whitespaces from s and replaces all sequences
     * of whitespaces inside s with a single space.
     */
    static String normalizeSpace(String s) {
        final String result;
        if (s == null) {
            result = null;
        } else {
            final int n = s.length();
            final StringBuilder sb = new StringBuilder(n);
            boolean lastCharacterWasWhitespace = true;
            for (int i = 0; i < n; ++i) {
                final char c = s.charAt(i);
                if (Character.isWhitespace(c)) {
                    lastCharacterWasWhitespace = true;
                } else {
                    if (lastCharacterWasWhitespace && sb.length() > 0) {
                        sb.append(' ');
                    }
                    sb.append(c);
                    lastCharacterWasWhitespace = false;
                }
            }
            result = sb.toString();
        }
        return result;
    }

    private static String stackToString(StackTraceElement[] stack, String nameOfCalledMethod) {
        final StringBuilder sb = new StringBuilder();
        int j = 0;
        while (j < stack.length && !nameOfCalledMethod.equals(stack[j].getMethodName())) {
            ++j;
        }
        for (int i = 0; i < 15 && i + j < stack.length; ++j) {
            final StackTraceElement e = stack[i + j];
            sb.append("        ").append(e).append('\n');
        }
        return sb.toString();
    }

    /**
     * Writes to the given stream and into a buffer.
     */
    private static class TeeServletOutputStream extends ServletOutputStream {
        private final TeeHttpServletResponse _response;
        private final ServletOutputStream _stream;
        private final ByteArrayOutputStream _buffer;
        private StackTraceElement[] _strackTraceForClose;
        private boolean _isClosed;

        private TeeServletOutputStream(TeeHttpServletResponse response, ServletOutputStream stream) {
            _response = response;
            _stream = stream;
            _buffer = new ByteArrayOutputStream();
        }

        public void write(int b) throws IOException {
            if (_isClosed) {
                throw new IllegalArgumentException("Can't write after close() has been called, close() was called here:\n" + stackToString(_strackTraceForClose, "close"));
            }
            _response.beforeWrite();
            _buffer.write(b);
            _stream.write(b);
        }

        public void write(byte[] a) throws IOException {
            if (_isClosed) {
                throw new IllegalArgumentException("Can't write after close() has been called, close() was called here:\n" + stackToString(_strackTraceForClose, "close"));
            }
            _response.beforeWrite();
            _buffer.write(a);
            _stream.write(a);
        }

        public void write(byte[] a, int offset, int length) throws IOException {
            if (_isClosed) {
                throw new IllegalArgumentException("Can't write after close() has been called, close() was called here:\n" + stackToString(_strackTraceForClose, "close"));
            }
            _response.beforeWrite();
            _buffer.write(a, offset, length);
            _stream.write(a, offset, length);
        }

        public void flush() throws IOException {
            // We don't need to call _buffer.flush() here
            _stream.flush();
        }

        public void close() throws IOException {
            if (!_isClosed) {
                _response.beforeClose();
                // We don't need to call _buffer.close() here
                _stream.close();
                _strackTraceForClose = Thread.currentThread().getStackTrace();
                _isClosed = true;
            }
        }

        public void resetBuffer() {
            _buffer.reset();
        }

        public String getBuffer() {
            try {
                final String characterEncoding = _response.getCharacterEncoding();
                return _buffer.toString(characterEncoding);
            } catch (UnsupportedEncodingException e) {
                throw new RuntimeException(e.getMessage(), e);
            }
        }
    }

    /**
     * Writes to the given writer and into a buffer.
     */
    private static class TeePrintWriter extends PrintWriter {
        private static final Writer SHOULD_NOT_BE_USED = new Writer() {
            public void write(char[] a, int offset, int length) throws IOException {
                throw new RuntimeException("This method should not have been called.");
            }

            public void flush() throws IOException {
                throw new RuntimeException("This method should not have been called.");
            }

            public void close() throws IOException {
                throw new RuntimeException("This method should not have been called.");
            }
        };
        private static final String LINE_SEPARATOR = System.getProperty("line.separator");

        private final TeeHttpServletResponse _response;
        private final PrintWriter _writer;
        private final StringBuilder _buffer;
        private StackTraceElement[] _stackForClose;
        private boolean _isClosed;

        private TeePrintWriter(TeeHttpServletResponse response, PrintWriter writer) {
            super(SHOULD_NOT_BE_USED);
            _response = response;
            _writer = writer;
            _buffer = new StringBuilder(8000);
        }

        public void write(char[] a, int offset, int length) {
            if ((offset < 0) || (offset > a.length) || (length < 0) || ((offset + length) > a.length) || ((offset + length) < 0)) {
                throw new IndexOutOfBoundsException();
            } else if (length == 0) {
                return;
            }
            if (_isClosed) {
                throw new IllegalArgumentException("Can't write after close() has been called, close() was called here:\n" + stackToString(_stackForClose, "close"));
            }
            _response.beforeWrite();
            _buffer.append(a, offset, length);
            _writer.write(a, offset, length);
        }

        public void write(int c) {
            if (_isClosed) {
                throw new IllegalArgumentException("Can't write after close() has been called, close() was called here:\n" + stackToString(_stackForClose, "close"));
            }
            _response.beforeWrite();
            _buffer.append((char) c);
            _writer.write(c);
        }

        public void write(String s, int offset, int length)  {
            if (_isClosed) {
                throw new IllegalArgumentException("Can't write after close() has been called, close() was called here:\n" + stackToString(_stackForClose, "close"));
            }
            _response.beforeWrite();
            _buffer.append(s, offset, offset + length);
            _writer.write(s, offset, length);
        }

        public void println() {
            if (_isClosed) {
                throw new IllegalArgumentException("Can't write after close() has been called, close() was called here:\n" + stackToString(_stackForClose, "close"));
            }
            _response.beforeWrite();
            _buffer.append(LINE_SEPARATOR);
            _writer.write(LINE_SEPARATOR);
        }

        public void flush() {
            _writer.flush();
        }

        public void close() {
            if (!_isClosed) {
                _response.beforeClose();
                _writer.close();
                _stackForClose = Thread.currentThread().getStackTrace();
                _isClosed = true;
            }
        }

        public void resetBuffer() {
            _buffer.delete(0, _buffer.length());
        }

        public String getBuffer() {
            return _buffer.toString();
        }
    }

    private class TeeHttpServletResponse extends HttpServletResponseWrapper {
        private boolean _isHtml;
        private TeeServletOutputStream _stream;
        private TeePrintWriter _writer;
        private Integer _contentLength;
        private boolean _beforeCloseCalled;
        private StackTraceElement[] _strackForGetOutputStream;
        private StackTraceElement[] _strackForGetWriter;

        private TeeHttpServletResponse(boolean isHtml, HttpServletResponse response) {
            super(response);
            _isHtml = isHtml;
        }

        public void setContentLength(int length) {
            _contentLength = Integer.valueOf(length);
            // We don't call super.setContentLength(length) here,
            // but wait until the first call to beforeWrite()
        }

        public void resetBuffer() {
            if (_stream != null) {
                _stream.resetBuffer();
            } else if (_writer != null) {
                _writer.resetBuffer();
            }
            super.resetBuffer();
        }

        public void reset() {
            if (_stream != null) {
                _stream.resetBuffer();
            } else if (_writer != null) {
                _writer.resetBuffer();
            }
            super.reset();
        }

        public ServletOutputStream getOutputStream() throws IOException {
            if (_writer != null) {
                throw new IllegalStateException("Method getWriter() has already been called here:\n" + stackToString(_strackForGetWriter, "getWriter"));
            }
            if (_stream == null) {
                _strackForGetOutputStream = Thread.currentThread().getStackTrace();
                _stream = new TeeServletOutputStream(this, super.getOutputStream());
            }
            return _stream;
        }

        public PrintWriter getWriter() throws IOException {
            if (_stream != null) {
                throw new IllegalStateException("Method getOutputStream() has already been called here:\n" + stackToString(_strackForGetOutputStream, "getOutputStream"));
            }
            if (_writer == null) {
                _strackForGetWriter = Thread.currentThread().getStackTrace();
                _writer = new TeePrintWriter(this, super.getWriter());
            }
            return _writer;
        }

        /**
         * This method is called by {@link TeeServletOutputStream} or {@link TeePrintWriter} before a write method is called.
         */
        public void beforeWrite() {
            final String contentType = super.getContentType();
            if (contentType != null) {
                _isHtml = contentType.startsWith("text/html");
            }
            if (_isHtml) {
                // We don't set the content length, because we want to append "<script ..." to the response
            } else {
                super.setContentLength(_contentLength.intValue());
            }
        }

        /**
         * This method is called before the stream/writer is closed.
         */
        public void beforeClose() {
            if (_isHtml && !_beforeCloseCalled) {
                _beforeCloseCalled = true;
                String html = getHtml();
                if (html != null) {
                    // Only validate complete HTML pages, ignore HTML fragments ...
                    final String trimmedHtml = html.trim();
                    if (trimmedHtml.endsWith("</html>") || trimmedHtml.endsWith("</HTML>")) {
                        appendAndFlush(_appendix);
                        final StringBuilder sb = new StringBuilder(1000);
                        sb.append("<script type=\"text/javascript\">\n")
                          .append("    setTimeout(function() {\n");
                        try {
                            html = preProcessHtml(html);
                            final W3cMarkupValidationResult result = _validator.validate(html);
                            final int i;
                            synchronized (_cachedResultsLock) {
                                i = ++_numberOfNextResult;
                                _cachedResults[i % MAX_CACHED_RESULTS] = result;
                            }
                            final String backgroundColor = result.isValid() ? "#55B05A" : "#D23D24";
                            sb.append("        jQuery('#w3c-markup-validation-box p:eq(1)').html('").append(result.getMessage()).append("');\n")
                              .append("        jQuery('#w3c-markup-validation-box').append('<p style=\"margin:0;border:0;padding:0\"><a href=\"/view-w3c-markup-validation-result-").append(i).append("\" style=\"font-family:sans-serif;font-size:small;font-weight:normal;text-decoration:none;color:blue\" target=\"_blank\">View Result</a></p>');\n")
                              .append("        jQuery('#w3c-markup-validation-box').css('border-color', '").append(result.isValid() ? "green" : "red").append("').css('background-color', '").append(backgroundColor).append("').find('*').css('background-color', '").append(backgroundColor).append("');\n");

                        } catch (RuntimeException e) {
                            sb.append("        jQuery('#w3c-markup-validation-box p:eq(1)').html('W3C Markup Validation failed: ").append(normalizeSpace(e.getMessage()).replace("<", "&lt;").replace(">", "&gt;").replace("'", "\\'")).append("');\n")
                              .append("        jQuery('#w3c-markup-validation-box').css('border-color', 'red').css('background-color', 'D23D24').find('*').css('background-color', 'D23D24');\n");

                        }
                        sb.append("    }, 100);\n")
                          .append("</script>\n");
                        appendAndFlush(sb.toString());
                    }
                }
            }
        }

        private String getHtml() {
            final String html;
            if (_stream != null) {
                html = _stream.getBuffer();
            } else if (_writer != null) {
                html = _writer.getBuffer();
            } else {
                html = null;
            }
            return html;
        }

        private void appendAndFlush(String s) {
            if (_stream != null) {
                try {
                    final byte[] a = s.getBytes(getCharacterEncoding());
                    _stream.write(a);
                    _stream.flush();
                } catch (UnsupportedEncodingException e) {
                    // Should never happen
                    throw new RuntimeException(e.getMessage(), e);
                } catch (IOException e) {
                    throw new RuntimeException(e.getMessage(), e);
                }
            } else if (_writer != null) {
                _writer.write(s);
                _writer.flush();
            } else {
                throw new IllegalStateException("_stream == null && _writer == null");
            }
        }
    }

    private boolean _enabled = true;
    private W3cMarkupValidator _validator;
    private String _appendix;
    private W3cMarkupValidationResult[] _cachedResults;
    private final Object _cachedResultsLock = new Object();
    private int _numberOfNextResult;

    public void init(FilterConfig filterConfig) throws ServletException {
        final String enabledParam = filterConfig.getInitParameter("enabled");
        _enabled = (enabledParam == null || "true".equals(enabledParam));
        if (_enabled) {
            _validator = new W3cMarkupValidator();
            final String checkUrl = filterConfig.getInitParameter("checkUrl");
            if (checkUrl != null) {
                _validator.setCheckUrl(checkUrl);
            }
            _cachedResults = new W3cMarkupValidationResult[MAX_CACHED_RESULTS];
            String jqueryUrl = filterConfig.getInitParameter("jqueryUrl");
            if (jqueryUrl == null) {
                jqueryUrl = "http://ajax.googleapis.com/ajax/libs/jquery/1.3.2/jquery.min.js";
            }
            _appendix = "\n" +
                        "<script type=\"text/javascript\">\n" +
                        "    if (typeof jQuery == 'undefined') {\n" +
                        "        document.body.appendChild(document.createElement('script')).src = '" + jqueryUrl + "';\n" +
                        "    }\n" +
                        "</script>\n" +
                        "<script type=\"text/javascript\">\n" +
                        "     setTimeout(function() {\n" +
                        "        jQuery('body').append('<div id=\"w3c-markup-validation-box\" style=\"z-index:10000;position:fixed;top:33px;right:33px;width:250px;border:3px solid yellow;padding:3px;background-color:white;opacity:0.75\">" +
                                                   "<p style=\"position:absolute;top:3px;right:5px;margin:0;broder:0;padding:0;background-color:white\"><a href=\"javascript:closeW3cValidationBox()\" style=\"font-family:sans-serif;font-size:small;font-weight:bold;text-decoration:none;color:black\">X</a></p>" +
                                                   "<p style=\"margin:0;border:0;padding:0;padding-right:1.5em;font-family:sans-serif;font-size:small;font-weight:normal;text-decoration:none;color:black;background-color:white\">W3C Markup Validation is running ...</p>" +
                                                   "</div>');\n" +
                        "    }, 100);\n" +
                        "    function closeW3cValidationBox() { jQuery('#w3c-markup-validation-box').hide(); }\n" +
                        "</script>\n";
            _numberOfNextResult = 0;
        }
    }

    public void doFilter(ServletRequest servletRequest, ServletResponse servletResponse, FilterChain filterChain) throws IOException, ServletException {
        if (_enabled && servletRequest instanceof HttpServletRequest && servletResponse instanceof HttpServletResponse) {
            final HttpServletRequest request = (HttpServletRequest) servletRequest;
            final String attributeName = "inside " + getClass().getName();
            if (request.getAttribute(attributeName) == null) {
                request.setAttribute(attributeName, Boolean.TRUE);
                final String uri = request.getRequestURI();
                if (uri.contains("view-w3c-markup-validation-result-")) {
                    final int i = Integer.parseInt(uri.substring(uri.lastIndexOf("-") + 1));
                    final W3cMarkupValidationResult result;
                    synchronized (_cachedResultsLock) {
                        if (i < (_numberOfNextResult - MAX_CACHED_RESULTS)) {
                            throw new RuntimeException("W3C Markup Validation Result " + i + " is no longer in cache.");
                        } else {
                            result = _cachedResults[i % MAX_CACHED_RESULTS];
                        }
                    }
                    final HttpServletResponse response = (HttpServletResponse) servletResponse;
                    response.setContentType("text/html; charset=UTF-8");
                    final PrintWriter writer = response.getWriter();
                    writer.write(result.getResultPage());
                } else {
                    final boolean isHtml = uri.endsWith("/") || uri.endsWith(".html") || uri.endsWith(".htm");
                    final HttpServletResponse response = (HttpServletResponse) servletResponse;
                    final TeeHttpServletResponse responseWrapper = new TeeHttpServletResponse(isHtml, response);
                    filterChain.doFilter(request, responseWrapper);
                    responseWrapper.beforeClose();
                }
            } else {
                filterChain.doFilter(servletRequest, servletResponse);
            }
        } else {
            filterChain.doFilter(servletRequest, servletResponse);
        }
    }

    public void destroy() {
        // Nothing to do here.
    }

    /**
     * You can override this method to change the HTML before it is validated.
     *
     * @param html the HTML rendered for the current page
     * @return the HTML which should be send to the W3C Markup Validation Service
     */
    protected String preProcessHtml(String html) {
        return html;
    }
}
