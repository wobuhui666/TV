package com.fongmi.android.tv.utils;

import android.content.Context;
import android.os.Environment;

import com.github.catvod.utils.Prefers;
import com.github.catvod.crawler.diagnostics.DiagnosticRedactor;

import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;

public class MpvLogCollector {

    private static final List<String> logs = new ArrayList<>();
    private static final int MAX_LOG_SIZE = 1000;
    private static final String ENABLED = "mpv_log_enabled";
    private static final SimpleDateFormat dateFormat = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.getDefault());

    public static void log(String tag, String message) {
        message = DiagnosticRedactor.redact(message);
        com.github.catvod.crawler.diagnostics.DiagnosticLog.record("mpv", tag + ": " + message);
        if (!isEnabled()) return;
        synchronized (logs) {
            String timestamp = dateFormat.format(new Date());
            String logEntry = String.format("[%s] %s: %s", timestamp, tag, message);
            logs.add(logEntry);

            // 限制日志数量，避免内存溢出
            if (logs.size() > MAX_LOG_SIZE) {
                logs.remove(0);
            }

            // 同时输出到 Logcat
            android.util.Log.d("MpvLogCollector", logEntry);
        }
    }

    public static void logError(String tag, String message) {
        message = DiagnosticRedactor.redact(message);
        com.github.catvod.crawler.diagnostics.DiagnosticLog.record("mpv", "error " + tag + ": " + message);
        if (!isEnabled()) return;
        synchronized (logs) {
            String timestamp = dateFormat.format(new Date());
            String logEntry = String.format("[%s] ERROR-%s: %s", timestamp, tag, message);
            logs.add(logEntry);

            if (logs.size() > MAX_LOG_SIZE) {
                logs.remove(0);
            }

            android.util.Log.e("MpvLogCollector", logEntry);
        }
    }

    public static boolean isEnabled() {
        return Prefers.getBoolean(ENABLED, true);
    }

    public static void putEnabled(boolean enabled) {
        Prefers.put(ENABLED, enabled);
    }

    public static void clear() {
        synchronized (logs) {
            logs.clear();
        }
    }

    public static String exportToFile(Context context) {
        synchronized (logs) {
            if (logs.isEmpty()) {
                return null;
            }

            try {
                // 使用外部存储的 Download 目录
                File downloadDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS);
                if (!downloadDir.exists()) {
                    downloadDir.mkdirs();
                }

                // 生成文件名
                String fileName = "mpv_log_" + new SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault()).format(new Date()) + ".txt";
                File logFile = new File(downloadDir, fileName);

                // 写入日志
                FileWriter writer = new FileWriter(logFile);
                writer.write("=== MPV 播放器日志 ===\n");
                writer.write("导出时间: " + dateFormat.format(new Date()) + "\n");
                writer.write("日志条数: " + logs.size() + "\n");
                writer.write("==================\n\n");

                for (String log : logs) {
                    writer.write(log + "\n");
                }

                writer.flush();
                writer.close();

                return logFile.getAbsolutePath();
            } catch (IOException e) {
                android.util.Log.e("MpvLogCollector", "导出日志失败: " + e.getMessage());
                return null;
            }
        }
    }

    public static List<String> getLogs() {
        synchronized (logs) {
            return new ArrayList<>(logs);
        }
    }

    public static int getLogCount() {
        synchronized (logs) {
            return logs.size();
        }
    }
}
