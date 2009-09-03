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

import java.util.regex.Pattern;
import java.util.regex.Matcher;

/**
 * Encapsulates the result of {@link W3cMarkupValidator#validate(String)}.
*
* @author Michael Tamm
*/
public class W3cMarkupValidationResult {

    private static final Pattern MESSAGE_PATTERN = Pattern.compile("<h2[^>]+class=\"(valid|invalid)\">(.*?)</h2>", Pattern.DOTALL);

    private final boolean _isValid;
    private final String _message;
    private final String _resultPage;

    public W3cMarkupValidationResult(String resultPage) {
        final Matcher m = MESSAGE_PATTERN.matcher(resultPage);
        if (!m.find()) {
            throw new RuntimeException("Did not find " + MESSAGE_PATTERN + " in " + resultPage);
        }
        _isValid = "valid".equals(m.group(1));
        _message = W3cMarkupValidationFilter.normalizeSpace(m.group(2));
        _resultPage = resultPage;
    }

    public boolean isValid() {
        return _isValid;
    }

    /**
     * Returns either message <code>"This Page Is Valid ..."</code> or
     * <code>"This page is <strong>not</strong> Valid ..."</code>.
     */
    public String getMessage() {
        return _message;
    }

    /**
     * Returns the HTML of the result page returned from the W3C Markup Validator.
     */
    public String getResultPage() {
        return _resultPage;
    }

    public String toString() {
        return getClass().getSimpleName() + ": " + _message;
    }
}
