package com.xinyv.median;

/** Small, deterministic HTTP retry policy for the internal downloader. */
final class DownloadRetryPolicy {
    private DownloadRetryPolicy() {}

    static boolean isRetryableHttp(int status) {
        return status == 408 || status == 425 || status == 429 ||
                (status >= 500 && status <= 599);
    }

    static String messageForHttp(int status) {
        if (status == 401 || status == 403) return "Server denied access (HTTP " + status + ")";
        if (status == 404 || status == 410) return "File not found or link expired (HTTP " + status + ")";
        if (status == 408) return "Server response timed out (HTTP 408)";
        if (status == 416) return "Server rejected range resume (HTTP 416)";
        if (status == 429) return "Too many requests (HTTP 429)";
        if (status >= 500 && status <= 599) return "Server temporarily unavailable (HTTP " + status + ")";
        return "Download request failed (HTTP " + status + ")";
    }
}
