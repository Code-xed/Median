package com.xinyv.median;

import java.util.Collections;
import java.util.HashMap;
import java.util.Map;

/** Pure task-state rules shared by the download center UI and its self-test. */
final class DownloadCenterPolicy {
    private static final Map<String, String> LEGACY_REASON_MAP;
    private static final String[][] LEGACY_HTTP_PREFIXES = new String[][] {
            { "服务器拒绝访问（HTTP ", "Server denied access (HTTP " },
            { "文件不存在或链接已失效（HTTP ", "File not found or link expired (HTTP " },
            { "服务器响应超时（HTTP ", "Server response timed out (HTTP " },
            { "服务器拒绝断点续传（HTTP ", "Server rejected range resume (HTTP " },
            { "请求过于频繁（HTTP ", "Too many requests (HTTP " },
            { "服务器暂时不可用（HTTP ", "Server temporarily unavailable (HTTP " },
            { "下载请求失败（HTTP ", "Download request failed (HTTP " }
    };

    static {
        HashMap<String, String> map = new HashMap<String, String>();
        map.put("已手动暂停", "Paused by user");
        map.put("已暂停", "Paused");
        map.put("正在取消", "Cancelling");
        map.put("已取消", "Cancelled");
        map.put("已完成", "Completed");
        map.put("正在恢复", "Resuming");
        map.put("Median 单连接", "Median single-connection");
        map.put("等待网络", "Waiting for network");
        map.put("等待非计费网络", "Waiting for unmetered network");
        map.put("等待非漫游网络", "Waiting for non-roaming network");
        map.put("等待充电", "Waiting for charging");
        map.put("系统不允许启动后台下载", "System disallowed starting background download");
        map.put("下载队列已满，请稍后重试", "Download queue is full; please try again later");
        map.put("系统内存紧张，已安全暂停", "System memory low; safely paused");
        map.put("系统内存不足，已安全暂停", "Insufficient system memory; safely paused");
        map.put("系统已达到后台下载时限，请返回应用后继续", "System background download time limit reached; return to app to resume");
        map.put("下载进程曾被系统中断，点击继续即可恢复", "Download process was interrupted by the system; tap Resume to continue");
        map.put("无法创建临时下载目录", "Unable to create temporary download directory");
        map.put("下载数据不完整", "Incomplete download data");
        map.put("无法创建下载文件", "Unable to create download file");
        map.put("无法写入下载目录", "Unable to write to download directory");
        map.put("无法创建应用下载目录", "Unable to create app download directory");
        map.put("本地或私有网络下载目标已拒绝", "Local or private network download target rejected");
        map.put("重定向次数过多", "Too many redirects");
        LEGACY_REASON_MAP = Collections.unmodifiableMap(map);
    }

    private DownloadCenterPolicy() {}

    static boolean isActive(String status) {
        return DownloadStore.STATUS_PENDING.equals(status) || DownloadStore.STATUS_WAITING.equals(status) ||
                DownloadStore.STATUS_DOWNLOADING.equals(status);
    }

    static boolean isProblem(String status) {
        return DownloadStore.STATUS_FAILED.equals(status) || DownloadStore.STATUS_CANCELLED.equals(status);
    }

    static boolean canResume(String status) {
        return DownloadStore.STATUS_PAUSED.equals(status) ||
                DownloadStore.STATUS_FAILED.equals(status);
    }

    static boolean canOpen(String status) { return DownloadStore.STATUS_COMPLETED.equals(status); }

    /** Keeps a previously discovered size when a later telemetry tick has no size information. */
    static long resolvedTotal(long knownTotal, long reportedTotal) {
        return reportedTotal > 0L ? reportedTotal : Math.max(0L, knownTotal);
    }

    /** Overflow-safe progress in tenths of one percent, clamped to 0..1000. */
    static int progressPermille(long downloaded, long total) {
        if (total <= 0L || downloaded <= 0L) return 0;
        if (downloaded >= total) return 1000;
        return (int) Math.min(999L, (long) Math.floor((downloaded / (double) total) * 1000d));
    }

    /** Maps legacy persisted Chinese download reason strings to English at display time. */
    static String displayReason(String raw) {
        if (raw == null || raw.length() == 0) return "";
        String mapped = LEGACY_REASON_MAP.get(raw);
        if (mapped != null) return mapped;
        if (raw.endsWith("）")) {
            for (String[] entry : LEGACY_HTTP_PREFIXES) {
                if (raw.startsWith(entry[0])) {
                    return entry[1] + raw.substring(entry[0].length(), raw.length() - 1) + ")";
                }
            }
        }
        return raw;
    }
}
