package com.nz.xdsrelay;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;

import java.io.BufferedWriter;
import java.io.File;
import java.io.FileOutputStream;
import java.io.OutputStreamWriter;
import java.text.SimpleDateFormat;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

/**
 * 极简日志环 + 主线程回调，供仪表盘显示；同时落盘一份。
 *
 * 落盘是刚需：2026-10-10 真骑 7km，「中继机显示正常转发，仪表那边数据冻住」——
 * 想复盘时 logcat 早滚没了。日志按天一个文件（xdsrelay-YYYYMMDD.log），超 512KB 滚成 -1。
 * 写盘走独立线程 + 有界队列，调用方永不阻塞，队列满宁可丢日志。
 */
public final class Logs {

    public interface Listener {
        void onLogs(List<String> lines);
    }

    private static final String LOG_TAG = "XdsRelay";
    private static final int MAX = 400;
    private static final long FILE_MAX = 512L * 1024L;
    private static final int QUEUE_MAX = 2000;

    private static final ArrayDeque<String> LINES = new ArrayDeque<>();
    private static final List<Listener> LISTENERS = new ArrayList<>();
    private static final Handler MAIN = new Handler(Looper.getMainLooper());
    private static final SimpleDateFormat FMT = new SimpleDateFormat("HH:mm:ss", Locale.CHINA);
    private static final SimpleDateFormat DAY = new SimpleDateFormat("yyyyMMdd", Locale.CHINA);

    private static final LinkedBlockingQueue<String> PENDING = new LinkedBlockingQueue<>(QUEUE_MAX);
    private static volatile File dir;
    private static volatile File file;
    private static volatile String day;
    private static volatile boolean writerStarted;

    private static final StringBuilder DUMP = new StringBuilder();
    private static Runnable pending;

    private Logs() {}

    /** 给落盘一个可写目录；在入口调一次即可，重复调无害 */
    public static void install(Context ctx) {
        if (ctx == null) return;
        if (dir == null) {
            try {
                dir = new File(ctx.getApplicationContext().getFilesDir(), "logs");
                if (!dir.exists() && !dir.mkdirs()) dir = null;
            } catch (Throwable t) {
                dir = null;
            }
        }
        if (dir != null && !writerStarted) startWriter();
    }

    /** 日志目录绝对路径（没装盘返回 null） */
    public static String dirPath() {
        File d = dir;
        return d == null ? null : d.getAbsolutePath();
    }

    public static void add(String tag, String msg) {
        String line = FMT.format(new Date()) + "  [" + tag + "] " + msg;
        synchronized (LINES) {
            LINES.addLast(line);
            while (LINES.size() > MAX) LINES.removeFirst();
        }
        System.out.println("XdsRelay " + line);
        try {
            android.util.Log.i(LOG_TAG, tag + " " + msg);
        } catch (Throwable ignore) {}
        toFile(line);
        notifySoon();
    }

    public static void clear() {
        synchronized (LINES) {
            LINES.clear();
        }
        notifySoon();
    }

    public static synchronized void register(Listener l) {
        if (!LISTENERS.contains(l)) LISTENERS.add(l);
    }

    public static synchronized void unregister(Listener l) {
        LISTENERS.remove(l);
    }

    private static void notifySoon() {
        if (pending != null) return;
        pending = new Runnable() {
            @Override
            public void run() {
                pending = null;
                List<String> snapshot = snapshot();
                List<Listener> copy;
                synchronized (Logs.class) {
                    copy = new ArrayList<>(LISTENERS);
                }
                for (Listener l : copy) l.onLogs(snapshot);
            }
        };
        MAIN.postDelayed(pending, 250);
    }

    public static List<String> snapshot() {
        synchronized (LINES) {
            return new ArrayList<>(LINES);
        }
    }

    public static String dump(boolean summaryOnly) {
        List<String> all = snapshot();
        List<String> use = all;
        if (summaryOnly && all.size() > 120) {
            use = all.subList(all.size() - 120, all.size());
        }
        synchronized (DUMP) {
            DUMP.setLength(0);
            for (String l : use) DUMP.append(l).append('\n');
            return DUMP.toString();
        }
    }

    // ================================================================
    //  落盘
    // ================================================================

    private static void toFile(String line) {
        if (dir == null) return;
        PENDING.offer(line);        // 满了就丢这一条，绝不阻塞调用方
    }

    private static void startWriter() {
        writerStarted = true;
        Thread t = new Thread(Logs::writerLoop, "xds-logfile");
        t.setDaemon(true);
        t.start();
    }

    private static void writerLoop() {
        BufferedWriter w = null;
        FileOutputStream fos = null;
        while (true) {
            String line;
            try {
                line = PENDING.poll(2, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                continue;
            }
            try {
                if (line != null) {
                    if (w == null || !isToday()) {          // 首次 / 跨天 → 换文件
                        closeQuietly(fos);
                        w = null;
                        File f = rollTo();
                        if (f == null) continue;
                        fos = new FileOutputStream(f, true);
                        w = new BufferedWriter(new OutputStreamWriter(fos, "UTF-8"));
                        w.write("=== " + f.getName() + " 会话开始 "
                                + new SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.CHINA).format(new Date())
                                + " ===\n");
                    }
                    w.write(line);
                    w.write('\n');
                }
                if (w != null) w.flush();
            } catch (Throwable e) {
                closeQuietly(fos);
                w = null;
                fos = null;
                sleep(1000);
            }
        }
    }

    /** 今天这个文件；超上限就滚到 -1 */
    private static File rollTo() {
        try {
            File d = dir;
            if (d == null) return null;
            String today = DAY.format(new Date());
            if (!today.equals(day) || file == null) {
                day = today;
                file = new File(d, "xdsrelay-" + today + ".log");
            }
            if (file.length() > FILE_MAX) {
                File old = new File(d, "xdsrelay-" + today + "-1.log");
                if (old.exists() && !old.delete()) { /* 删不掉就继续往后写 */ }
                if (file.renameTo(old)) {
                    file = new File(d, "xdsrelay-" + today + ".log");
                }
            }
            return file;
        } catch (Throwable t) {
            return null;
        }
    }

    private static boolean isToday() {
        String d = day;
        return d != null && d.equals(DAY.format(new Date()));
    }

    private static void closeQuietly(FileOutputStream fos) {
        try {
            if (fos != null) fos.close();
        } catch (Throwable ignore) {}
    }

    private static void sleep(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException ignore) {}
    }
}
