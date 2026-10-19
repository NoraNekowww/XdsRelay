package com.nz.xdsrelay;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.io.FileInputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.Locale;

/**
 * 把 FIT 快照 POST 到 https://nzlab.online/api/upload（网站同款接口，multipart 表单），
 * 拿回它的解析结果 {analysis, chartData} 并在本机显示 —— 也就是"实时交给网站解析"。
 */
public final class NzcUploader {

    private static final String TAG = "UP";

    public static final String ENDPOINT = "https://nzlab.online/api/upload";
    public static final String SITE = "https://nzlab.online/cycle/frontend/index.html";

    /** 单次上报的看门狗上限；DNS 在 VPN 下可能不返回，必须有个兜底 */
    private static final long WATCHDOG_MS = 30000L;
    private static final int MAX_ATTEMPTS = 2;

    private NzcUploader() {}

    public static final class Result {
        public boolean ok;
        public boolean fatal;
        public int attempt = 1;
        public String error = "";
        public double activeAvgKmh = -1;
        public double activeDistanceKm = -1;
        public String totalTime = "--";
        public String activeTime = "--";
        public String stoppedTime = "--";
        public int stoppedSegments = -1;
        public int points = -1;
        public long tookMs;
        public long atMs;

        public String summary() {
            if (!ok) return "上报失败：" + error;
            return String.format(Locale.CHINA,
                    "有效均速 %.1f km/h · 有效里程 %.2f km · 停止 %d 段（%s）· %d 点",
                    activeAvgKmh, activeDistanceKm, stoppedSegments, stoppedTime, points);
        }

        public String summaryWithAttempts() {
            return summary() + (attempt > 1 ? "  [第 " + attempt + " 次尝试]" : "");
        }
    }

    public interface Callback {
        void onDone(Result r);
    }

    public static void upload(final File fit,
                              final double lowThreshold,
                              final double highThreshold,
                              final int bufferSeconds,
                              final int smoothingWindow,
                              final Callback cb) {
        new Thread(() -> {
            final java.util.concurrent.atomic.AtomicBoolean delivered =
                    new java.util.concurrent.atomic.AtomicBoolean(false);

            // 看门狗：DNS 在 VPN/弱网下有可能长时间不返回，setConnectTimeout 管不住它。
            // 超时就先给出结论，避免"上报中"永远卡住、后续再也不发起新的上报。
            final Thread worker = Thread.currentThread();
            Thread watchdog = new Thread(() -> {
                try {
                    worker.join(WATCHDOG_MS);
                } catch (InterruptedException ignore) {
                }
                if (worker.isAlive() && delivered.compareAndSet(false, true)) {
                    Result r = new Result();
                    r.ok = false;
                    r.atMs = System.currentTimeMillis();
                    r.error = "超时（" + (WATCHDOG_MS / 1000) + " 秒无响应，多半是网络/DNS 问题）";
                    Logs.add(TAG, "上报超时，放弃这一次");
                    if (cb != null) cb.onDone(r);
                }
            }, "nzc-watchdog");
            watchdog.setDaemon(true);
            watchdog.start();

            Result last = null;
            for (int attempt = 1; attempt <= MAX_ATTEMPTS; attempt++) {
                Result r = attemptOnce(fit, lowThreshold, highThreshold,
                        bufferSeconds, smoothingWindow);
                r.attempt = attempt;
                last = r;
                if (r.ok) break;
                if (r.fatal) break;
                if (attempt < MAX_ATTEMPTS) {
                    Logs.add(TAG, "第 " + attempt + " 次上报失败（" + r.error + "），2 秒后重试");
                    try {
                        Thread.sleep(2000L);
                    } catch (InterruptedException ignore) {
                    }
                }
            }

            if (last != null && delivered.compareAndSet(false, true)) {
                if (cb != null) cb.onDone(last);
            }
        }, "nzc-upload").start();
    }

    private static Result attemptOnce(File fit, double low, double high, int buffer, int smooth) {
        Result r = new Result();
        long t0 = System.currentTimeMillis();
        HttpURLConnection conn = null;
        try {
            String boundary = "----XdsRelay" + System.currentTimeMillis();
            conn = (HttpURLConnection) new URL(ENDPOINT).openConnection();
            conn.setRequestMethod("POST");
            conn.setDoOutput(true);
            conn.setConnectTimeout(10000);
            conn.setReadTimeout(30000);
            conn.setRequestProperty("Content-Type", "multipart/form-data; boundary=" + boundary);
            conn.setRequestProperty("User-Agent", "XdsRelay/1.0 (Android)");
            conn.setRequestProperty("Connection", "close");

            try (OutputStream out = conn.getOutputStream()) {
                writeFilePart(out, boundary, "file", fit);
                writeField(out, boundary, "low_threshold", String.valueOf(low));
                writeField(out, boundary, "high_threshold", String.valueOf(high));
                writeField(out, boundary, "buffer_seconds", String.valueOf(buffer));
                writeField(out, boundary, "smoothing_window", String.valueOf(smooth));
                out.write(("--" + boundary + "--\r\n").getBytes(StandardCharsets.UTF_8));
                out.flush();
            }

            int code = conn.getResponseCode();
            InputStream in = code >= 200 && code < 300 ? conn.getInputStream() : conn.getErrorStream();
            String body = readAll(in);
            r.tookMs = System.currentTimeMillis() - t0;
            r.atMs = System.currentTimeMillis();

            if (code < 200 || code >= 300) {
                r.ok = false;
                r.fatal = code >= 400 && code < 500;      // 4xx 重试没意义
                r.error = "HTTP " + code + " " + truncate(body, 160);
                return r;
            }

            JSONObject root = new JSONObject(body);
            JSONObject a = root.optJSONObject("analysis");
            if (a != null) {
                r.activeAvgKmh = a.optDouble("active_avg", -1);
                r.activeDistanceKm = a.optDouble("active_distance", -1);
                r.totalTime = a.optString("total_time_str", "--");
                r.activeTime = a.optString("active_time_str", "--");
                r.stoppedTime = a.optString("stopped_time_str", "--");
                r.stoppedSegments = a.optInt("stopped_segments", -1);
            }
            JSONArray cd = root.optJSONArray("chartData");
            r.points = cd == null ? 0 : cd.length();
            r.ok = true;
        } catch (java.net.UnknownHostException e) {
            r.ok = false;
            r.tookMs = System.currentTimeMillis() - t0;
            r.atMs = System.currentTimeMillis();
            r.error = "域名解析失败（检查网络 / VPN 分流是否把本应用也代理了）";
        } catch (java.net.SocketTimeoutException e) {
            r.ok = false;
            r.tookMs = System.currentTimeMillis() - t0;
            r.atMs = System.currentTimeMillis();
            r.error = "连接超时（网络不通或太慢）";
        } catch (Exception e) {
            r.ok = false;
            r.tookMs = System.currentTimeMillis() - t0;
            r.atMs = System.currentTimeMillis();
            r.error = e.getClass().getSimpleName() + ": " + e.getMessage();
        } finally {
            if (conn != null) {
                try {
                    conn.disconnect();
                } catch (Exception ignore) {
                }
            }
        }
        return r;
    }

    private static void writeFilePart(OutputStream out, String boundary, String name, File f)
            throws Exception {
        StringBuilder h = new StringBuilder();
        h.append("--").append(boundary).append("\r\n");
        h.append("Content-Disposition: form-data; name=\"").append(name)
                .append("\"; filename=\"").append(f.getName()).append("\"\r\n");
        h.append("Content-Type: application/octet-stream\r\n\r\n");
        out.write(h.toString().getBytes(StandardCharsets.UTF_8));
        try (FileInputStream fis = new FileInputStream(f)) {
            byte[] buf = new byte[16 * 1024];
            int n;
            while ((n = fis.read(buf)) > 0) out.write(buf, 0, n);
        }
        out.write("\r\n".getBytes(StandardCharsets.UTF_8));
    }

    private static void writeField(OutputStream out, String boundary, String name, String value)
            throws Exception {
        String s = "--" + boundary + "\r\n"
                + "Content-Disposition: form-data; name=\"" + name + "\"\r\n\r\n"
                + value + "\r\n";
        out.write(s.getBytes(StandardCharsets.UTF_8));
    }

    private static String readAll(InputStream in) throws Exception {
        if (in == null) return "";
        StringBuilder sb = new StringBuilder();
        byte[] buf = new byte[8192];
        int n;
        while ((n = in.read(buf)) > 0) sb.append(new String(buf, 0, n, StandardCharsets.UTF_8));
        in.close();
        return sb.toString();
    }

    private static String truncate(String s, int max) {
        if (s == null) return "";
        return s.length() <= max ? s : s.substring(0, max) + "…";
    }
}
