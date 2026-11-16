package com.finbridge.logging;

import java.util.regex.Pattern;

/**
 * Removes credentials from text before it is logged or stored, and caps its size.
 * Applied to every integration payload, so an API key, password or token can never be
 * read back from the integration_logs table or from the Integration Monitoring page.
 */
public final class PayloadMasker {

    public static final int MAX_LENGTH = 16_000;
    static final String MASK = "***";

    private static final String SECRET_NAMES =
            "authorization|api[_-]?key|x-sim-api-key|password|passwd|secret|access[_-]?token|token";

    // "password": "value"   (JSON, any spacing; the value may be a string, a number or a bare word)
    private static final Pattern JSON_FIELD = Pattern.compile(
            "(?i)(\"(?:" + SECRET_NAMES + ")\"\\s*:\\s*)(?:\"(?:[^\"\\\\]|\\\\.)*\"|[^,}\\s]+)");
    // <Password>abc</Password>
    private static final Pattern XML_ELEMENT = Pattern.compile(
            "(?i)(<(?:\\w+:)?(?:" + SECRET_NAMES + ")>)[^<]*(</)");
    // Authorization: Bearer abc   /   api_key=abc
    private static final Pattern HEADER_OR_PARAM = Pattern.compile(
            "(?i)(?<![\"\\w])((?:" + SECRET_NAMES + ")\\s*[:=]\\s*)(?:(?:bearer|basic)\\s+)?[^\\s,;&\"<]+");
    private static final Pattern BEARER = Pattern.compile("(?i)\\b(bearer\\s+)[A-Za-z0-9._~+/=-]{8,}");

    private PayloadMasker() {
    }

    public static String mask(String text) {
        if (text == null) {
            return null;
        }
        String masked = JSON_FIELD.matcher(text).replaceAll("$1\"" + MASK + "\"");
        masked = XML_ELEMENT.matcher(masked).replaceAll("$1" + MASK + "$2");
        masked = HEADER_OR_PARAM.matcher(masked).replaceAll("$1" + MASK);
        masked = BEARER.matcher(masked).replaceAll("$1" + MASK);
        return cap(masked, MAX_LENGTH);
    }

    public static String cap(String text, int maxLength) {
        if (text == null || text.length() <= maxLength) {
            return text;
        }
        String suffix = "... [truncated, " + text.length() + " characters in total]";
        return text.substring(0, Math.max(0, maxLength - suffix.length())) + suffix;
    }
}
