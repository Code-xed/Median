// SPDX-License-Identifier: GPL-3.0-only
// Copyright (C) 2026 Median Browser contributors

package com.xinyv.median;

import static com.xinyv.median.ByteFormat.humanBytes;
import static com.xinyv.median.ByteFormat.humanSpeed;

import android.app.Activity;
import android.app.AlertDialog;
import android.app.DownloadManager;
import android.content.ClipData;
import android.content.DialogInterface;
import android.content.Intent;
import android.content.res.ColorStateList;
import android.database.Cursor;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.os.Handler;
import android.os.Looper;
import android.os.StatFs;
import android.os.SystemClock;
import android.provider.Settings;
import android.text.TextUtils;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.widget.BaseAdapter;
import android.widget.Button;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.Toast;
import android.webkit.CookieManager;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;

/** Compact, state-driven download center. No task action is shown unless it is valid for that state. */
public final class DownloadCenterActivity extends Activity {
    private static final int BG = Color.rgb(246, 248, 251);
    private static final int CARD = Color.WHITE;
    private static final int TEXT = Color.rgb(28, 32, 38);
    private static final int MUTED = Color.rgb(103, 112, 124);
    private static final int PRIMARY = Color.rgb(26, 115, 232);
    private static final int GREEN = Color.rgb(25, 128, 61);
    private static final int RED = Color.rgb(190, 48, 42);
    private static final int AMBER = Color.rgb(174, 101, 0);
    private static final int LINE = Color.rgb(222, 227, 234);

    private static final int FILTER_ALL = 0;
    private static final int FILTER_ACTIVE = 1;
    private static final int FILTER_COMPLETED = 2;
    private static final int FILTER_PROBLEM = 3;

    private static final int CLEAN_PROBLEMS = 0;
    private static final int CLEAN_COMPLETED = 1;
    private static final int CLEAN_TERMINAL = 2;
    private static final int DOWNLOAD_DIRECTORY_REQUEST = 412;

    private final Handler handler = new Handler(Looper.getMainLooper());
    private final Runnable refresher = new Runnable() {
        @Override public void run() { refreshData(); }
    };

    private final HashMap<Long, SystemState> systemStates = new HashMap<Long, SystemState>();
    private final HashMap<Long, Long> previousSystemBytes = new HashMap<Long, Long>();
    private final HashMap<Long, Long> previousSystemTimes = new HashMap<Long, Long>();
    private final Button[] filterButtons = new Button[4];

    private DownloadStore store;
    private DownloadAdapter adapter;
    private TextView summaryTitle;
    private TextView summaryDetail;
    private int filter = FILTER_ALL;
    private boolean resumed;

    private static final class SystemState {
        String status = DownloadStore.STATUS_PENDING;
        int reason;
        long downloadedBytes;
        long totalBytes;
        long bytesPerSecond;
    }

    @Override protected void onCreate(Bundle state) {
        super.onCreate(state);
        Window window = getWindow();
        window.setStatusBarColor(Color.WHITE);
        window.setNavigationBarColor(Color.WHITE);
        if (Build.VERSION.SDK_INT >= 23) window.getDecorView().setSystemUiVisibility(View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR);
        store = new DownloadStore(this);
        setContentView(buildUi());
    }

    @Override protected void onResume() {
        super.onResume();
        resumed = true;
        refreshData();
    }

    @Override protected void onPause() {
        resumed = false;
        handler.removeCallbacks(refresher);
        super.onPause();
    }

    private View buildUi() {
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(BG);
        root.addView(buildHeader(), new LinearLayout.LayoutParams(-1, dp(60)));
        root.addView(buildSummary(), new LinearLayout.LayoutParams(-1, dp(58)));
        root.addView(buildFilters(), new LinearLayout.LayoutParams(-1, dp(50)));

        FrameLayout body = new FrameLayout(this);
        ListView list = new ListView(this);
        list.setId(android.R.id.list);
        list.setBackgroundColor(BG);
        list.setDivider(new ColorDrawable(Color.TRANSPARENT));
        list.setDividerHeight(dp(9));
        list.setPadding(dp(12), dp(8), dp(12), dp(22));
        list.setClipToPadding(false);
        list.setVerticalScrollBarEnabled(false);
        adapter = new DownloadAdapter();
        list.setAdapter(adapter);
        body.addView(list, new FrameLayout.LayoutParams(-1, -1));

        TextView empty = text("No downloads yet", 15, MUTED, Gravity.CENTER);
        empty.setId(android.R.id.empty);
        empty.setPadding(dp(24), dp(40), dp(24), dp(40));
        FrameLayout.LayoutParams emptyParams = new FrameLayout.LayoutParams(-1, -2, Gravity.CENTER);
        body.addView(empty, emptyParams);
        list.setEmptyView(empty);
        root.addView(body, new LinearLayout.LayoutParams(-1, 0, 1f));
        return root;
    }

    private View buildHeader() {
        LinearLayout bar = new LinearLayout(this);
        bar.setGravity(Gravity.CENTER_VERTICAL);
        bar.setPadding(dp(8), 0, dp(10), 0);
        bar.setBackgroundColor(Color.WHITE);
        bar.setElevation(dp(2));

        Button back = button("‹", false, false);
        back.setTextSize(TypedValue.COMPLEX_UNIT_SP, 32);
        back.setContentDescription("Back");
        back.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View view) { finish(); }
        });
        bar.addView(back, new LinearLayout.LayoutParams(dp(48), dp(48)));

        TextView title = text("Downloads", 21, TEXT, Gravity.CENTER_VERTICAL);
        title.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        bar.addView(title, new LinearLayout.LayoutParams(0, -1, 1f));

        Button directory = button("Folder", false, false);
        directory.setContentDescription("Change downloads folder");
        directory.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View view) {
                DownloadDirectory.showPickerDialog(DownloadCenterActivity.this, DOWNLOAD_DIRECTORY_REQUEST, null);
            }
        });
        bar.addView(directory, new LinearLayout.LayoutParams(dp(64), dp(38)));

        Button clean = button("Clean", false, false);
        clean.setContentDescription("Clean up download history");
        clean.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View view) { showCleanupMenu(); }
        });
        bar.addView(clean, new LinearLayout.LayoutParams(dp(64), dp(38)));
        return bar;
    }

    @Override protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == DOWNLOAD_DIRECTORY_REQUEST)
            DownloadDirectory.handlePickerResult(this, resultCode, data, new Runnable() {
                @Override public void run() { refreshData(); }
            });
    }

    private View buildSummary() {
        LinearLayout summary = new LinearLayout(this);
        summary.setOrientation(LinearLayout.VERTICAL);
        summary.setGravity(Gravity.CENTER_VERTICAL);
        summary.setPadding(dp(16), dp(5), dp(16), dp(5));
        summary.setBackgroundColor(Color.WHITE);
        summaryTitle = text("Active 0 · Completed 0 · Issues 0", 14, TEXT, Gravity.LEFT);
        summaryTitle.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        summaryDetail = text("Calculating available space", 12, MUTED, Gravity.LEFT);
        summaryDetail.setPadding(0, dp(3), 0, 0);
        summary.addView(summaryTitle);
        summary.addView(summaryDetail);
        return summary;
    }

    private View buildFilters() {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(dp(12), dp(6), dp(12), dp(6));
        row.setBackgroundColor(BG);
        for (int i = 0; i < filterButtons.length; i++) {
            final int selected = i;
            Button button = button(filterName(i), true, i == filter);
            button.setOnClickListener(new View.OnClickListener() {
                @Override public void onClick(View view) {
                    if (filter == selected) return;
                    filter = selected;
                    refreshData();
                }
            });
            filterButtons[i] = button;
            LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(0, dp(38), 1f);
            if (i > 0) params.leftMargin = dp(7);
            row.addView(button, params);
        }
        return row;
    }

    private void refreshData() {
        handler.removeCallbacks(refresher);
        if (!resumed) return;
        List<DownloadStore.Item> all = store.getAll();
        recoverInterruptedTasks(all);
        refreshSystemStates(all);
        ArrayList<DownloadStore.Item> visible = new ArrayList<DownloadStore.Item>();
        int active = 0;
        int paused = 0;
        int completed = 0;
        int problems = 0;
        long speed = 0L;
        long remaining = 0L;
        for (DownloadStore.Item item : all) {
            String status = effectiveStatus(item);
            if (isActiveStatus(status)) {
                active++;
                long taskSpeed = bytesPerSecond(item);
                speed += taskSpeed;
                long total = totalBytes(item);
                long current = downloadedBytes(item);
                if (total > current) remaining += total - current;
            } else if (DownloadStore.STATUS_PAUSED.equals(status)) paused++;
            if (DownloadStore.STATUS_COMPLETED.equals(status)) completed++;
            if (isProblemStatus(status)) problems++;
            if (matchesFilter(status)) visible.add(item);
        }

        summaryTitle.setText("Active " + active + (paused > 0 ? " · Paused " + paused : "") +
                " · Completed " + completed + " · Issues " + problems);
        String detail = "Available " + availableStorage();
        if (speed > 0L) {
            detail = "Total speed " + humanSpeed(speed) + " · " + detail;
            if (remaining > 0L) detail += " · ~" + humanDuration(remaining / speed);
        }
        summaryDetail.setText(detail);
        int[] counts = new int[] { all.size(), active + paused, completed, problems };
        for (int i = 0; i < filterButtons.length; i++) {
            filterButtons[i].setText(filterName(i) + (counts[i] > 0 ? " " + counts[i] : ""));
            styleToggle(filterButtons[i], i == filter);
        }
        adapter.replace(visible);
        if (active > 0) handler.postDelayed(refresher, 1000L);
    }

    private void recoverInterruptedTasks(List<DownloadStore.Item> items) {
        long now = System.currentTimeMillis();
        for (DownloadStore.Item item : items) {
            if (!item.isAdaptive() || !DownloadCenterPolicy.isActive(item.status) ||
                    AdaptiveDownloadService.isTaskScheduled(item.id) ||
                    item.updatedAt <= 0L || now - item.updatedAt < 5000L) continue;
            item.status = DownloadStore.STATUS_PAUSED;
            item.reason = "Download was interrupted by the system; tap Resume to continue";
            item.updatedAt = now;
            store.updateAdaptiveTelemetry(item.id, item.status, item.reason,
                    item.downloadedBytes, item.totalBytes, 0L, null, item.etaSeconds, item.retryCount);
        }
    }

    /** Legacy DownloadManager entries are read in one query. New tasks never enter this path. */
    private void refreshSystemStates(List<DownloadStore.Item> items) {
        ArrayList<Long> idsList = new ArrayList<Long>();
        for (DownloadStore.Item item : items) if (!item.isAdaptive()) idsList.add(Long.valueOf(item.id));
        HashMap<Long, SystemState> next = new HashMap<Long, SystemState>();
        if (!idsList.isEmpty()) {
            long[] ids = new long[idsList.size()];
            for (int i = 0; i < ids.length; i++) ids[i] = idsList.get(i).longValue();
            DownloadManager manager = (DownloadManager) getSystemService(DOWNLOAD_SERVICE);
            Cursor cursor = null;
            try {
                cursor = manager == null ? null : manager.query(new DownloadManager.Query().setFilterById(ids));
                long now = SystemClock.elapsedRealtime();
                while (cursor != null && cursor.moveToNext()) {
                    long id = cursor.getLong(cursor.getColumnIndexOrThrow(DownloadManager.COLUMN_ID));
                    SystemState state = new SystemState();
                    state.status = mapSystemStatus(cursor.getInt(cursor.getColumnIndexOrThrow(DownloadManager.COLUMN_STATUS)));
                    state.reason = cursor.getInt(cursor.getColumnIndexOrThrow(DownloadManager.COLUMN_REASON));
                    state.downloadedBytes = Math.max(0L, cursor.getLong(cursor.getColumnIndexOrThrow(
                            DownloadManager.COLUMN_BYTES_DOWNLOADED_SO_FAR)));
                    state.totalBytes = Math.max(0L, cursor.getLong(cursor.getColumnIndexOrThrow(
                            DownloadManager.COLUMN_TOTAL_SIZE_BYTES)));
                    Long oldBytes = previousSystemBytes.get(Long.valueOf(id));
                    Long oldTime = previousSystemTimes.get(Long.valueOf(id));
                    if (DownloadStore.STATUS_DOWNLOADING.equals(state.status) && oldBytes != null && oldTime != null &&
                            state.downloadedBytes >= oldBytes.longValue() && now - oldTime.longValue() >= 250L) {
                        state.bytesPerSecond = (state.downloadedBytes - oldBytes.longValue()) * 1000L /
                                Math.max(1L, now - oldTime.longValue());
                    }
                    previousSystemBytes.put(Long.valueOf(id), Long.valueOf(state.downloadedBytes));
                    previousSystemTimes.put(Long.valueOf(id), Long.valueOf(now));
                    next.put(Long.valueOf(id), state);
                }
            } catch (RuntimeException ignored) {
            } finally { if (cursor != null) cursor.close(); }
        }
        systemStates.clear();
        systemStates.putAll(next);
        previousSystemBytes.keySet().retainAll(next.keySet());
        previousSystemTimes.keySet().retainAll(next.keySet());
    }

    private final class DownloadAdapter extends BaseAdapter {
        private final ArrayList<DownloadStore.Item> items = new ArrayList<DownloadStore.Item>();

        void replace(List<DownloadStore.Item> next) {
            items.clear();
            if (next != null) items.addAll(next);
            notifyDataSetChanged();
        }

        @Override public int getCount() { return items.size(); }
        @Override public DownloadStore.Item getItem(int position) { return items.get(position); }
        @Override public long getItemId(int position) { return items.get(position).id; }
        @Override public boolean hasStableIds() { return true; }

        @Override public View getView(int position, View convertView, ViewGroup parent) {
            TaskRow row;
            if (convertView == null || !(convertView.getTag() instanceof TaskRow)) {
                row = new TaskRow();
                convertView = row.root;
                convertView.setTag(row);
            } else row = (TaskRow) convertView.getTag();
            row.bind(getItem(position));
            return convertView;
        }
    }

    private final class TaskRow {
        final LinearLayout root;
        final TextView title;
        final TextView badge;
        final ProgressBar progress;
        final TextView telemetry;
        final TextView reason;
        final LinearLayout actions;
        final Button[] actionButtons = new Button[3];

        TaskRow() {
            root = new LinearLayout(DownloadCenterActivity.this);
            root.setOrientation(LinearLayout.VERTICAL);
            root.setPadding(dp(14), dp(12), dp(14), dp(11));
            root.setBackground(rounded(CARD, dp(14), LINE, 1));
            root.setElevation(dp(1));

            LinearLayout top = new LinearLayout(DownloadCenterActivity.this);
            top.setOrientation(LinearLayout.HORIZONTAL);
            top.setGravity(Gravity.TOP);
            title = text("", 15, TEXT, Gravity.LEFT);
            title.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
            title.setMaxLines(2);
            title.setEllipsize(TextUtils.TruncateAt.END);
            top.addView(title, new LinearLayout.LayoutParams(0, -2, 1f));
            badge = text("", 11, PRIMARY, Gravity.CENTER);
            badge.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
            badge.setPadding(dp(9), 0, dp(9), 0);
            LinearLayout.LayoutParams badgeParams = new LinearLayout.LayoutParams(-2, dp(25));
            badgeParams.leftMargin = dp(9);
            top.addView(badge, badgeParams);
            root.addView(top);

            progress = new ProgressBar(DownloadCenterActivity.this, null, android.R.attr.progressBarStyleHorizontal);
            progress.setMax(1000);
            progress.setProgressBackgroundTintList(ColorStateList.valueOf(LINE));
            LinearLayout.LayoutParams progressParams = new LinearLayout.LayoutParams(-1, dp(6));
            progressParams.topMargin = dp(9);
            root.addView(progress, progressParams);

            telemetry = text("", 12, MUTED, Gravity.LEFT);
            telemetry.setPadding(0, dp(7), 0, 0);
            root.addView(telemetry);

            reason = text("", 12, RED, Gravity.LEFT);
            reason.setPadding(0, dp(4), 0, 0);
            reason.setMaxLines(2);
            reason.setEllipsize(TextUtils.TruncateAt.END);
            root.addView(reason);

            actions = new LinearLayout(DownloadCenterActivity.this);
            actions.setOrientation(LinearLayout.HORIZONTAL);
            actions.setGravity(Gravity.LEFT);
            for (int i = 0; i < actionButtons.length; i++) {
                Button action = button("", false, false);
                action.setVisibility(View.GONE);
                actionButtons[i] = action;
                LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(-2, dp(35));
                if (i > 0) params.leftMargin = dp(7);
                actions.addView(action, params);
            }
            LinearLayout.LayoutParams actionParams = new LinearLayout.LayoutParams(-1, -2);
            actionParams.topMargin = dp(9);
            root.addView(actions, actionParams);
        }

        void bind(final DownloadStore.Item item) {
            String status = effectiveStatus(item);
            int color = statusColor(status);
            title.setText(item.filename.length() == 0 ? "Unnamed download" : item.filename);
            badge.setText(statusLabel(status));
            badge.setTextColor(color);
            badge.setBackground(rounded(withAlpha(color, 20), dp(13), withAlpha(color, 52), 1));

            long current = downloadedBytes(item);
            long total = totalBytes(item);
            boolean active = isActiveStatus(status);
            boolean showProgress = active || total > 0L || current > 0L || DownloadStore.STATUS_COMPLETED.equals(status);
            progress.setVisibility(showProgress ? View.VISIBLE : View.GONE);
            progress.setIndeterminate(active && total <= 0L);
            int progressValue = total > 0L ? DownloadCenterPolicy.progressPermille(current, total) :
                    (DownloadStore.STATUS_COMPLETED.equals(status) ? 1000 : 0);
            progress.setProgress(progressValue);
            progress.setProgressTintList(ColorStateList.valueOf(color));
            progress.setIndeterminateTintList(ColorStateList.valueOf(color));
            progress.setContentDescription(total > 0L ? "Download progress " + progressText(progressValue) :
                    (active ? "Downloading, total size unknown" : "Download progress unknown"));
            telemetry.setText(taskTelemetry(item, status, current, total));

            String why = taskReason(item);
            reason.setText(why);
            reason.setTextColor(color);
            reason.setVisibility(why.length() == 0 ? View.GONE : View.VISIBLE);
            configureTaskActions(this, item, status);
            root.setOnClickListener(new View.OnClickListener() {
                @Override public void onClick(View view) { showDetails(item); }
            });
            root.setContentDescription((item.filename.length() == 0 ? "Unnamed download" : item.filename) + ", " + statusLabel(status));
        }
    }

    private void configureTaskActions(TaskRow row, final DownloadStore.Item item, String status) {
        for (Button action : row.actionButtons) {
            action.setVisibility(View.GONE);
            action.setOnClickListener(null);
        }
        int slot = 0;
        if (DownloadStore.STATUS_COMPLETED.equals(status)) {
            showAction(row, slot++, "Open", true, new View.OnClickListener() {
                @Override public void onClick(View view) { openFile(item, false); }
            });
            showAction(row, slot++, "Share", false, new View.OnClickListener() {
                @Override public void onClick(View view) { openFile(item, true); }
            });
            showAction(row, slot, "Remove", false, new View.OnClickListener() {
                @Override public void onClick(View view) { removeRecord(item); }
            });
            return;
        }
        if (isActiveStatus(status)) {
            if (item.isAdaptive()) showAction(row, slot++, "Pause", false, new View.OnClickListener() {
                @Override public void onClick(View view) { sendService(item.id, AdaptiveDownloadService.ACTION_PAUSE, false); }
            });
            else showAction(row, slot++, "Move to Built-in", true, new View.OnClickListener() {
                @Override public void onClick(View view) { startMedianDownload(item); }
            });
            showAction(row, slot, "Cancel", false, new View.OnClickListener() {
                @Override public void onClick(View view) { confirmCancel(item); }
            });
            return;
        }
        if (DownloadCenterPolicy.canResume(status)) {
            showAction(row, slot++, DownloadStore.STATUS_PAUSED.equals(status) ? "Resume" : "Retry", true,
                    new View.OnClickListener() {
                @Override public void onClick(View view) { resume(item); }
            });
            if (DownloadStore.STATUS_PAUSED.equals(status)) {
                showAction(row, slot, "Cancel", false, new View.OnClickListener() {
                    @Override public void onClick(View view) { confirmCancel(item); }
                });
                return;
            }
        }
        showAction(row, slot, "Delete", false, new View.OnClickListener() {
            @Override public void onClick(View view) { removeRecord(item); }
        });
    }

    private void showAction(TaskRow row, int index, String label, boolean primary, View.OnClickListener listener) {
        if (index < 0 || index >= row.actionButtons.length) return;
        Button action = row.actionButtons[index];
        action.setText(label);
        styleToggle(action, primary);
        action.setOnClickListener(listener);
        action.setVisibility(View.VISIBLE);
    }

    private void resume(DownloadStore.Item item) {
        if (!item.isAdaptive()) { startMedianDownload(item); return; }
        sendService(item.id, AdaptiveDownloadService.ACTION_RESUME, true);
        toast("Resuming download");
    }

    private boolean startMedianDownload(DownloadStore.Item item) {
        long newId = store.addAdaptive(item.url, item.filename, item.mime,
                item.userAgent, item.headersJson, item.wifiOnly, item.allowRoaming,
                item.chargingOnly, false, item.totalBytes);
        try {
            Intent service = new Intent(this, AdaptiveDownloadService.class);
            service.setAction(AdaptiveDownloadService.ACTION_DOWNLOAD);
            service.putExtra(AdaptiveDownloadService.EXTRA_ID, newId);
            service.putExtra(AdaptiveDownloadService.EXTRA_URL, item.url);
            service.putExtra(AdaptiveDownloadService.EXTRA_NAME, item.filename);
            service.putExtra(AdaptiveDownloadService.EXTRA_MIME, item.mime);
            service.putExtra(AdaptiveDownloadService.EXTRA_USER_AGENT, item.userAgent);
            String cookie = CookieManager.getInstance().getCookie(item.url);
            service.putExtra(AdaptiveDownloadService.EXTRA_COOKIE, cookie == null ? "" : cookie);
            service.putExtra(AdaptiveDownloadService.EXTRA_HEADERS, item.headersJson);
            service.putExtra(AdaptiveDownloadService.EXTRA_WIFI_ONLY, item.wifiOnly);
            service.putExtra(AdaptiveDownloadService.EXTRA_ALLOW_ROAMING, item.allowRoaming);
            service.putExtra(AdaptiveDownloadService.EXTRA_CHARGING_ONLY, item.chargingOnly);
            service.putExtra(AdaptiveDownloadService.EXTRA_PUBLIC_ONLY, false);
            service.putExtra(AdaptiveDownloadService.EXTRA_TOTAL_BYTES, item.totalBytes);
            startForegroundService(service);
            DownloadManager manager = (DownloadManager) getSystemService(DOWNLOAD_SERVICE);
            if (manager != null) manager.remove(item.id);
            store.remove(item.id);
            toast("Moved to Median built-in downloader");
            refreshSoon();
            return true;
        } catch (Exception error) {
            store.remove(newId);
            toast("Failed to start: " + ErrorText.message(error, 80));
            return false;
        }
    }

    private void confirmCancel(final DownloadStore.Item item) {
        new AlertDialog.Builder(this).setTitle("Cancel download?")
                .setMessage("Temporary downloaded data will be deleted.")
                .setPositiveButton("Cancel task", new DialogInterface.OnClickListener() {
                    @Override public void onClick(DialogInterface dialog, int which) {
                        if (item.isAdaptive()) sendService(item.id, AdaptiveDownloadService.ACTION_CANCEL, false);
                        else {
                            DownloadManager manager = (DownloadManager) getSystemService(DOWNLOAD_SERVICE);
                            if (manager != null) manager.remove(item.id);
                            store.remove(item.id);
                        }
                        refreshSoon();
                    }
                }).setNegativeButton("Back", null).show();
    }

    private void removeRecord(final DownloadStore.Item item) {
        String status = effectiveStatus(item);
        if (item.isAdaptive() && !DownloadStore.STATUS_COMPLETED.equals(status)) cancelSilently(item.id);
        store.remove(item.id);
        refreshData();
    }

    private void sendService(long id, String action, boolean foreground) {
        Intent intent = new Intent(this, AdaptiveDownloadService.class);
        intent.setAction(action);
        intent.putExtra(AdaptiveDownloadService.EXTRA_ID, id);
        DownloadStore.Item previous = null;
        if (AdaptiveDownloadService.ACTION_RESUME.equals(action)) {
            previous = store.get(id);
            if (previous != null) store.updateAdaptiveTelemetry(id, DownloadStore.STATUS_PENDING,
                    "Resuming", previous.downloadedBytes, previous.totalBytes, 0L, null, 0L,
                    previous.retryCount);
        }
        try {
            if (foreground && Build.VERSION.SDK_INT >= 26) startForegroundService(intent);
            else startService(intent);
            refreshSoon();
        } catch (RuntimeException error) {
            if (previous != null) store.updateAdaptiveTelemetry(id, previous.status, previous.reason,
                    previous.downloadedBytes, previous.totalBytes, previous.bytesPerSecond,
                    previous.localUri, previous.etaSeconds, previous.retryCount);
            toast("Action failed: " + ErrorText.message(error, 80));
        }
    }

    private void cancelSilently(long id) {
        Intent intent = new Intent(this, AdaptiveDownloadService.class);
        intent.setAction(AdaptiveDownloadService.ACTION_CANCEL);
        intent.putExtra(AdaptiveDownloadService.EXTRA_ID, id);
        try { startService(intent); } catch (RuntimeException ignored) {}
    }

    private void refreshSoon() {
        handler.removeCallbacks(refresher);
        if (resumed) handler.postDelayed(refresher, 250L);
    }

    private void showCleanupMenu() {
        new AlertDialog.Builder(this).setTitle("Clean up download history")
                .setItems(new String[] {
                        "Clear failed and cancelled tasks",
                        "Clear completed records (keep files)",
                        "Clear all inactive records"
                }, new DialogInterface.OnClickListener() {
                    @Override public void onClick(DialogInterface dialog, int which) { confirmCleanup(which); }
                }).setNegativeButton("Close", null).show();
    }

    private void confirmCleanup(final int mode) {
        final ArrayList<DownloadStore.Item> candidates = cleanupCandidates(mode);
        if (candidates.isEmpty()) { toast("No records to clear"); return; }
        String message = "This will remove " + candidates.size() + " record(s)";
        if (mode == CLEAN_PROBLEMS || mode == CLEAN_TERMINAL) message += ", and delete incomplete temporary data";
        else message += ", without deleting downloaded files";
        new AlertDialog.Builder(this).setTitle("Confirm cleanup?").setMessage(message)
                .setPositiveButton("Clean", new DialogInterface.OnClickListener() {
                    @Override public void onClick(DialogInterface dialog, int which) {
                        ArrayList<Long> ids = new ArrayList<Long>();
                        for (DownloadStore.Item item : candidates) {
                            ids.add(Long.valueOf(item.id));
                            String status = effectiveStatus(item);
                            if (item.isAdaptive() && !DownloadStore.STATUS_COMPLETED.equals(status)) cancelSilently(item.id);
                        }
                        int removed = store.removeAll(ids);
                        toast("Cleared " + removed + " record(s)");
                        refreshData();
                    }
                }).setNegativeButton("Back", null).show();
    }

    private ArrayList<DownloadStore.Item> cleanupCandidates(int mode) {
        ArrayList<DownloadStore.Item> result = new ArrayList<DownloadStore.Item>();
        for (DownloadStore.Item item : store.getAll()) {
            String status = effectiveStatus(item);
            boolean match = mode == CLEAN_PROBLEMS ? isProblemStatus(status) :
                    mode == CLEAN_COMPLETED ? DownloadStore.STATUS_COMPLETED.equals(status) :
                            !isActiveStatus(status);
            if (match) result.add(item);
        }
        return result;
    }

    private void showDetails(final DownloadStore.Item original) {
        DownloadStore.Item fresh = store.get(original.id);
        final DownloadStore.Item item = fresh == null ? original : fresh;
        String status = effectiveStatus(item);
        long current = downloadedBytes(item);
        long total = totalBytes(item);
        StringBuilder message = new StringBuilder();
        message.append("Status: ").append(statusLabel(status));
        String why = taskReason(item);
        if (why.length() > 0) message.append("\nDetails: ").append(why);
        message.append("\n\nFile: ").append(item.filename.length() == 0 ? "Unnamed download" : item.filename);
        message.append("\nTotal size: ").append(total > 0L ? humanBytes(total) : "Not provided by server");
        message.append("\nDownloaded: ").append(humanBytes(current));
        if (bytesPerSecond(item) > 0L) message.append("\nSpeed: ").append(humanSpeed(bytesPerSecond(item)));
        if (item.retryCount > 0) message.append("\nRetries: ").append(item.retryCount).append(" time(s)");
        message.append("\nCreated: ").append(formatTime(item.createdAt));
        if (item.completedAt > 0L) message.append("\nCompleted: ").append(formatTime(item.completedAt));
        message.append("\nSource: ").append(urlHost(item.url));
        new AlertDialog.Builder(this).setTitle("Download Details").setMessage(message.toString())
                .setPositiveButton("Copy URL", new DialogInterface.OnClickListener() {
                    @Override public void onClick(DialogInterface dialog, int which) {
                        android.content.ClipboardManager clipboard =
                                (android.content.ClipboardManager) getSystemService(CLIPBOARD_SERVICE);
                        if (clipboard != null) clipboard.setPrimaryClip(
                                android.content.ClipData.newPlainText("Download URL", item.url));
                        toast("URL copied");
                    }
                }).setNegativeButton("Close", null).show();
    }

    private void openFile(DownloadStore.Item item, boolean share) {
        DownloadStore.Item fresh = store.get(item.id);
        if (fresh != null) item = fresh;
        try {
            Uri uri = downloadedUri(item);
            if (uri == null) { toast("File was moved, deleted, or is not yet complete"); return; }
            String mime = DownloadFileTypes.mimeForOpen(getContentResolver(), uri, item.filename, item.mime);
            if (!share && DownloadFileTypes.isApk(item.filename, mime) && Build.VERSION.SDK_INT >= 26 &&
                    !getPackageManager().canRequestPackageInstalls()) {
                new AlertDialog.Builder(this).setTitle("Allow APK Installation")
                        .setMessage("Android requires permission for Median to install unknown apps. Grant access and tap Open again.")
                        .setPositiveButton("Open Settings", new DialogInterface.OnClickListener() {
                            @Override public void onClick(DialogInterface dialog, int which) {
                                startActivity(new Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                                        Uri.parse("package:" + getPackageName())));
                            }
                        }).setNegativeButton("Cancel", null).show();
                return;
            }
            Intent intent;
            if (share) {
                intent = new Intent(Intent.ACTION_SEND);
                intent.setType(mime);
                intent.putExtra(Intent.EXTRA_STREAM, uri);
            } else {
                intent = new Intent(Intent.ACTION_VIEW);
                intent.setDataAndType(uri, mime);
            }
            intent.setClipData(ClipData.newRawUri(item.filename, uri));
            intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
            startActivity(share ? Intent.createChooser(intent, "Share downloaded file") : intent);
        } catch (Exception error) { toast("No app can open this file, or the file was moved"); }
    }

    private Uri downloadedUri(DownloadStore.Item item) {
        if (item.isAdaptive()) {
            if (!DownloadStore.STATUS_COMPLETED.equals(item.status) || item.localUri.length() == 0) return null;
            try { return Uri.parse(item.localUri); } catch (RuntimeException ignored) { return null; }
        }
        DownloadManager manager = (DownloadManager) getSystemService(DOWNLOAD_SERVICE);
        return manager == null ? null : manager.getUriForDownloadedFile(item.id);
    }

    private String taskTelemetry(DownloadStore.Item item, String status, long current, long total) {
        StringBuilder line = new StringBuilder();
        if (DownloadStore.STATUS_COMPLETED.equals(status)) {
            line.append(total > 0L ? "Total " + humanBytes(total) : "Download complete");
            if (item.completedAt > 0L) line.append(" · ").append(shortTime(item.completedAt));
            return line.toString();
        }
        if (total > 0L) {
            int progress = DownloadCenterPolicy.progressPermille(current, total);
            line.append(progressText(progress)).append(" · Downloaded ").append(humanBytes(current))
                    .append(" / Total ").append(humanBytes(total));
        }
        else if (current > 0L) line.append("Downloaded ").append(humanBytes(current)).append(" · Total size unknown");
        else if (isActiveStatus(status)) line.append("Connecting · Fetching file size");
        else if (DownloadStore.STATUS_PAUSED.equals(status)) line.append("Paused");
        else line.append("Not downloaded");
        long speed = bytesPerSecond(item);
        if (speed > 0L && isActiveStatus(status)) line.append(" · ").append(humanSpeed(speed));
        long eta = speed > 0L && total > current ? (total - current) / speed : item.etaSeconds;
        if (eta > 0L && isActiveStatus(status)) line.append(" · ").append(humanDuration(eta));
        return line.toString();
    }

    private static String progressText(int permille) {
        int safe = Math.max(0, Math.min(1000, permille));
        if (safe == 1000) return "100%";
        return (safe / 10) + "." + (safe % 10) + "%";
    }

    private String taskReason(DownloadStore.Item item) {
        String status = effectiveStatus(item);
        if (item.isAdaptive()) {
            if (DownloadStore.STATUS_FAILED.equals(status) || DownloadStore.STATUS_PAUSED.equals(status) ||
                    DownloadStore.STATUS_WAITING.equals(status)) return value(item.reason);
            return "";
        }
        SystemState state = systemStates.get(Long.valueOf(item.id));
        if (state == null || (!DownloadStore.STATUS_FAILED.equals(status) &&
                !DownloadStore.STATUS_PAUSED.equals(status))) return "";
        return downloadReason(state.reason);
    }

    private boolean matchesFilter(String status) {
        if (filter == FILTER_ALL) return true;
        if (filter == FILTER_ACTIVE) return isActiveStatus(status) || DownloadStore.STATUS_PAUSED.equals(status);
        if (filter == FILTER_COMPLETED) return DownloadStore.STATUS_COMPLETED.equals(status);
        return isProblemStatus(status);
    }

    private static boolean isActiveStatus(String status) {
        return DownloadCenterPolicy.isActive(status);
    }

    private static boolean isProblemStatus(String status) {
        return DownloadCenterPolicy.isProblem(status);
    }

    private String effectiveStatus(DownloadStore.Item item) {
        if (item.isAdaptive()) return value(item.status);
        SystemState state = systemStates.get(Long.valueOf(item.id));
        if (state != null) return state.status;
        String stored = value(item.status);
        if (DownloadStore.STATUS_PENDING.equals(stored) || DownloadStore.STATUS_WAITING.equals(stored) ||
                DownloadStore.STATUS_DOWNLOADING.equals(stored)) return DownloadStore.STATUS_CANCELLED;
        return stored;
    }

    private long downloadedBytes(DownloadStore.Item item) {
        SystemState state = item.isAdaptive() ? null : systemStates.get(Long.valueOf(item.id));
        return Math.max(0L, state == null ? item.downloadedBytes : state.downloadedBytes);
    }

    private long totalBytes(DownloadStore.Item item) {
        SystemState state = item.isAdaptive() ? null : systemStates.get(Long.valueOf(item.id));
        return Math.max(0L, state == null ? item.totalBytes : state.totalBytes);
    }

    private long bytesPerSecond(DownloadStore.Item item) {
        SystemState state = item.isAdaptive() ? null : systemStates.get(Long.valueOf(item.id));
        return Math.max(0L, state == null ? item.bytesPerSecond : state.bytesPerSecond);
    }

    private static String mapSystemStatus(int status) {
        if (status == DownloadManager.STATUS_SUCCESSFUL) return DownloadStore.STATUS_COMPLETED;
        if (status == DownloadManager.STATUS_FAILED) return DownloadStore.STATUS_FAILED;
        if (status == DownloadManager.STATUS_PAUSED) return DownloadStore.STATUS_PAUSED;
        if (status == DownloadManager.STATUS_RUNNING) return DownloadStore.STATUS_DOWNLOADING;
        return DownloadStore.STATUS_PENDING;
    }

    private static String downloadReason(int reason) {
        if (reason == DownloadManager.PAUSED_WAITING_TO_RETRY) return "Waiting for automatic retry";
        if (reason == DownloadManager.PAUSED_WAITING_FOR_NETWORK) return "Waiting for network";
        if (reason == DownloadManager.PAUSED_QUEUED_FOR_WIFI) return "Waiting for Wi-Fi";
        if (reason == DownloadManager.ERROR_CANNOT_RESUME) return "Server does not support resume";
        if (reason == DownloadManager.ERROR_DEVICE_NOT_FOUND) return "Storage unavailable";
        if (reason == DownloadManager.ERROR_FILE_ALREADY_EXISTS) return "File already exists";
        if (reason == DownloadManager.ERROR_FILE_ERROR) return "Cannot write file";
        if (reason == DownloadManager.ERROR_HTTP_DATA_ERROR) return "Network data interrupted";
        if (reason == DownloadManager.ERROR_INSUFFICIENT_SPACE) return "Insufficient storage space";
        if (reason >= 400 && reason <= 599) return "Server returned HTTP " + reason;
        return "Download interrupted";
    }

    private static String statusLabel(String status) {
        if (DownloadStore.STATUS_COMPLETED.equals(status)) return "Completed";
        if (DownloadStore.STATUS_FAILED.equals(status)) return "Failed";
        if (DownloadStore.STATUS_CANCELLED.equals(status)) return "Cancelled";
        if (DownloadStore.STATUS_PAUSED.equals(status)) return "Pause";
        if (DownloadStore.STATUS_WAITING.equals(status)) return "Waiting";
        if (DownloadStore.STATUS_PENDING.equals(status)) return "Pending";
        return "Downloading";
    }

    private static int statusColor(String status) {
        if (DownloadStore.STATUS_COMPLETED.equals(status)) return GREEN;
        if (DownloadStore.STATUS_FAILED.equals(status) || DownloadStore.STATUS_CANCELLED.equals(status)) return RED;
        if (DownloadStore.STATUS_PAUSED.equals(status) || DownloadStore.STATUS_WAITING.equals(status)) return AMBER;
        return PRIMARY;
    }

    private String availableStorage() {
        try {
            StatFs stat = new StatFs(Environment.getDataDirectory().getAbsolutePath());
            return humanBytes(stat.getAvailableBytes());
        } catch (RuntimeException ignored) { return "Unknown"; }
    }

    private String urlHost(String raw) {
        try { return NetworkSecurity.normalizedHost(NetworkSecurity.parseHttpUrl(raw)); }
        catch (Exception ignored) { return "Unknown"; }
    }

    private static String filterName(int index) {
        if (index == FILTER_ACTIVE) return "Active";
        if (index == FILTER_COMPLETED) return "Completed";
        if (index == FILTER_PROBLEM) return "Issues";
        return "All";
    }

    private Button button(String label, boolean compact, boolean selected) {
        Button button = new Button(this);
        button.setText(label);
        button.setTextSize(TypedValue.COMPLEX_UNIT_SP, compact ? 12 : 13);
        button.setTextColor(selected ? Color.WHITE : TEXT);
        button.setTypeface(Typeface.DEFAULT, selected ? Typeface.BOLD : Typeface.NORMAL);
        button.setAllCaps(false);
        button.setMinWidth(0);
        button.setMinimumWidth(0);
        button.setMinHeight(0);
        button.setMinimumHeight(0);
        button.setPadding(dp(compact ? 6 : 12), 0, dp(compact ? 6 : 12), 0);
        button.setBackground(rounded(selected ? PRIMARY : Color.WHITE, dp(11), selected ? PRIMARY : LINE, 1));
        return button;
    }

    private void styleToggle(Button button, boolean selected) {
        button.setTextColor(selected ? Color.WHITE : TEXT);
        button.setTypeface(Typeface.DEFAULT, selected ? Typeface.BOLD : Typeface.NORMAL);
        button.setBackground(rounded(selected ? PRIMARY : Color.WHITE, dp(11), selected ? PRIMARY : LINE, 1));
    }

    private TextView text(String value, int sp, int color, int gravity) {
        TextView view = new TextView(this);
        view.setText(value);
        view.setTextSize(TypedValue.COMPLEX_UNIT_SP, sp);
        view.setTextColor(color);
        view.setGravity(gravity);
        return view;
    }

    private GradientDrawable rounded(int color, int radius, int strokeColor, int strokeDp) {
        GradientDrawable drawable = new GradientDrawable();
        drawable.setColor(color);
        drawable.setCornerRadius(radius);
        if (strokeDp > 0) drawable.setStroke(dp(strokeDp), strokeColor);
        return drawable;
    }

    private int dp(float value) {
        return Math.round(TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP,
                value, getResources().getDisplayMetrics()));
    }

    private static int withAlpha(int color, int alpha) {
        return Color.argb(alpha, Color.red(color), Color.green(color), Color.blue(color));
    }

    private static String humanDuration(long seconds) {
        if (seconds < 60L) return Math.max(1L, seconds) + " sec";
        if (seconds < 3600L) return (seconds / 60L) + " min";
        return (seconds / 3600L) + " hr " + ((seconds % 3600L) / 60L) + " min";
    }

    private static String shortTime(long millis) {
        return new SimpleDateFormat("MM-dd HH:mm", Locale.getDefault()).format(new Date(millis));
    }

    private static String formatTime(long millis) {
        if (millis <= 0L) return "Unknown";
        return new SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault()).format(new Date(millis));
    }

    private static String value(String text) { return text == null ? "" : text; }
    private void toast(String message) { Toast.makeText(this, message, Toast.LENGTH_SHORT).show(); }
}
