// SPDX-License-Identifier: GPL-3.0-only
// Copyright (C) 2026 Median Browser contributors

package com.xinyv.median;

import java.nio.charset.StandardCharsets;
import java.util.Locale;

/** Validates local appearance-only CSS before it is embedded in the trusted homepage. */
final class CustomHomeCss {
    static final int MAX_BYTES = 32 * 1024;
    static final String EXAMPLE =
            "/* Example: larger rounded search bar and translucent shortcuts */\n" +
            ".search{border-radius:18px;box-shadow:0 8px 28px rgba(0,0,0,.12)}\n" +
            ".tile{border-radius:18px;background:rgba(255,255,255,.72)}\n" +
            ".brand{letter-spacing:2px}";

    private CustomHomeCss() {}

    static String clean(String value) {
        if (value == null) return "";
        String clean = value.trim();
        return clean.getBytes(StandardCharsets.UTF_8).length <= MAX_BYTES ? clean : "";
    }

    static String error(String value) {
        String clean = value == null ? "" : value.trim();
        if (clean.getBytes(StandardCharsets.UTF_8).length > MAX_BYTES) return "CSS cannot exceed 32 KB";
        String lower = clean.toLowerCase(Locale.US);
        if (lower.contains("</style")) return "CSS cannot contain </style>";
        if (lower.indexOf('\\') >= 0) return "CSS cannot use escape sequences";
        String compact = withoutComments(lower).replace(" ", "").replace("\n", "").replace("\r", "").replace("\t", "");
        if (compact.contains("</style")) return "CSS cannot contain </style>";
        if (compact.contains("@import")) return "CSS cannot use @import";
        if (compact.contains("url(") || compact.contains("http:") || compact.contains("https:") || compact.contains("//"))
            return "CSS cannot load external URLs";
        if (compact.contains("expression(")) return "CSS cannot use expression()";
        if (compact.contains("behavior:") || compact.contains("-moz-binding")) return "CSS contains unsafe legacy behaviors";
        return "";
    }

    static boolean valid(String value) { return error(value).length() == 0; }

    private static String withoutComments(String value) {
        StringBuilder out = new StringBuilder(value.length());
        for (int i = 0; i < value.length();) {
            if (i + 1 < value.length() && value.charAt(i) == '/' && value.charAt(i + 1) == '*') {
                int end = value.indexOf("*/", i + 2);
                if (end < 0) break;
                i = end + 2;
            } else out.append(value.charAt(i++));
        }
        return out.toString();
    }
}
