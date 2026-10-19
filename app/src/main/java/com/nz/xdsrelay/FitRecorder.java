package com.nz.xdsrelay;

import android.content.ContentValues;
import android.content.Context;
import android.net.Uri;
import android.os.Build;
import android.os.Environment;
import android.os.SystemClock;
import android.provider.MediaStore;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.text.SimpleDateFormat;
import java.util.Locale;

/**
 * 骑行记录：1 Hz 采样，攒成 FIT。
 * 数据来源：AD350 的功率/踏频 + 本机 GNSS 的经纬度/海拔/速度。
 */
public class FitRecorder {

    private static final String TAG = "FIT";

    private final Context ctx;

    private final ByteArrayOutputStream records = new ByteArrayOutputStream(64 * 1024);
    private byte[] recordDef;

    private volatile boolean recording;
    private long startEpochSec;
    private long endEpochSec;
    private long lastRecordEpochSec;
    private long lastSampleMs;

    private double distanceM;
    private double timerSec;
    private double elapsedSec;

    private double maxSpeed;
    private double speedSum;
    private long speedCount;

    private int maxCadence;
    private long cadenceSum;
    private long cadenceCount;

    private int maxPower;
    private long powerSum;
    private long powerCount;

    private double maxAlt = Double.NaN;
    private double minAlt = Double.NaN;
    private double prevAlt = Double.NaN;
    private int ascent, descent;

    private long leftSum;
    private long leftCount;

    private int[] powerSeries = new int[4096];
    private int powerSeriesLen;

    private File currentFile;
    private int recordCount;

    /** 暂停中：冻结本次记录（不采样/不计时/不计距离），完全不影响任何转发 */
    private boolean paused;
    /** 本次骑行的记录文件名，固定 REC_yyyyMMdd_序号.fit */
    private String sessionName;

    public FitRecorder(Context ctx) {
        this.ctx = ctx.getApplicationContext();
    }

    public boolean isRecording() { return recording; }
    public boolean isPaused() { return paused; }
    public String sessionName() { return sessionName; }
    public int recordCount() { return recordCount; }
    public double distanceM() { return distanceM; }
    public double timerSec() { return timerSec; }
    public double elapsedSec() { return elapsedSec; }
    public File currentFile() { return currentFile; }
    public long startEpochSec() { return startEpochSec; }

    public File dir() {
        File base = ctx.getExternalFilesDir(null);
        if (base == null) base = ctx.getFilesDir();
        File d = new File(base, "fit");
        //noinspection ResultOfMethodCallIgnored
        d.mkdirs();
        return d;
    }

    // ================================================================

    public synchronized void start(long nowEpochSec) {
        if (recording) return;
        records.reset();
        recordDef = FitWriter.recordDefinition();
        records.write(recordDef, 0, recordDef.length);

        recording = true;
        startEpochSec = nowEpochSec;
        endEpochSec = nowEpochSec;
        lastRecordEpochSec = 0;
        lastSampleMs = 0;
        distanceM = 0;
        timerSec = 0;
        elapsedSec = 0;
        maxSpeed = 0;
        speedSum = 0;
        speedCount = 0;
        maxCadence = 0;
        cadenceSum = 0;
        cadenceCount = 0;
        maxPower = 0;
        powerSum = 0;
        powerCount = 0;
        maxAlt = Double.NaN;
        minAlt = Double.NaN;
        prevAlt = Double.NaN;
        ascent = descent = 0;
        leftSum = leftCount = 0;
        powerSeriesLen = 0;
        recordCount = 0;
        paused = false;

        // 每次「启动一次记录」= 一次骑行，各自一个独立文件：REC_yyyyMMdd_序号.fit
        // （同一次骑行内的自动保存是覆写这个文件，下一次记录才另起新号）
        currentFile = new File(dir(), nextSessionName(nowEpochSec));
        sessionName = currentFile.getName();
        Logs.add(TAG, "开始记录（新文件）→ " + sessionName);
    }

    /** 暂停：冻结本次记录，不采样也不推进时间/距离。转发链路完全不受影响。 */
    public synchronized void pause() {
        if (!recording || paused) return;
        paused = true;
        lastSampleMs = 0;          // 重新基线，继续后不会把暂停的时长算进运动时间
        Logs.add(TAG, "已暂停记录（" + sessionName + "）");
    }

    /** 继续刚才那一次记录（同一个文件，继续覆写）。 */
    public synchronized void resume() {
        if (!recording || !paused) return;
        paused = false;
        lastSampleMs = 0;
        Logs.add(TAG, "继续记录（" + sessionName + "）");
    }

    /**
     * 生成本次骑行的文件名：REC_&lt;yyyyMMdd&gt;_&lt;两位序号&gt;.fit。
     * 序号按"当天已有文件（本地记录目录 + 已导出到下载目录的）最大值 +1"，所以
     * 杀掉 App / 清空记录目录重开也不会覆盖上一趟的文件。
     */
    private String nextSessionName(long epochSec) {
        String day = new SimpleDateFormat("yyyyMMdd", Locale.CHINA)
                .format(new java.util.Date(epochSec * 1000L));
        String prefix = "REC_" + day + "_";
        int max = 0;
        File[] fs = dir().listFiles();
        if (fs != null) {
            for (File f : fs) {
                max = Math.max(max, seqOf(f.getName(), prefix));
            }
        }
        max = Math.max(max, maxExportedSeq(prefix));
        return String.format(Locale.CHINA, "REC_%s_%02d.fit", day, max + 1);
    }

    private static int seqOf(String name, String prefix) {
        if (name == null || !name.startsWith(prefix) || !name.endsWith(".fit")) return 0;
        try {
            return Integer.parseInt(name.substring(prefix.length(), name.length() - 4));
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    /** 下载目录里已经导出过的同名 REC_ 文件的序号（防止序号回绕重名） */
    private int maxExportedSeq(String prefix) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return 0;
        int max = 0;
        try (android.database.Cursor c = ctx.getContentResolver().query(
                MediaStore.Downloads.EXTERNAL_CONTENT_URI,
                new String[]{MediaStore.Downloads.DISPLAY_NAME},
                MediaStore.Downloads.DISPLAY_NAME + " LIKE ?",
                new String[]{prefix + "%"}, null)) {
            if (c != null) {
                while (c.moveToNext()) max = Math.max(max, seqOf(c.getString(0), prefix));
            }
        } catch (Exception e) {
            // 查不到就只按本地记录目录算，不影响使用
        }
        return max;
    }

    public synchronized void stop(long nowEpochSec) {
        if (!recording) return;
        recording = false;
        paused = false;
        endEpochSec = Math.max(nowEpochSec, endEpochSec);
        File f = currentFile;
        if (f != null && recordCount > 0) {
            try {
                FitWriter.writeToFile(FitWriter.build(records.toByteArray(), summary()), f);
                Logs.add(TAG, "记录结束：" + f.getName() + "  " + recordCount + " 点  "
                        + (f.length() / 1024) + " KB");
                // 关键：把文件复制到用户能看见的地方。
                // 原来只写在自己的外部私有目录 /Android/data/... —— Android 11+ 之后
                // 文件管理器根本进不去，看起来就像"没有生成 FIT"。
                String where = exportToVisible(ctx, f);
                if (where != null) Logs.add(TAG, "FIT 已导出到：" + where);
            } catch (IOException e) {
                Logs.add(TAG, "写 FIT 失败: " + e);
            }
        } else {
            Logs.add(TAG, "记录结束：没有有效数据点");
        }
    }

    /**
     * 骑行中定时把当前记录落一份盘（覆盖同一个 _partial 文件）。
     * 目的是"自动保存"：中途被杀、断电、崩溃也不会丢掉整趟记录。
     */
    public synchronized boolean autoSave() {
        if (!recording || recordCount <= 0) return false;
        try {
            // 直接写当前这个文件（同一个文件覆写），不另外生成 _partial
            File f = currentFile != null ? currentFile
                    : new File(dir(), "XdsRelay_ride.fit");
            FitWriter.writeToFile(FitWriter.build(records.toByteArray(), summary()), f);
            return true;
        } catch (IOException e) {
            Logs.add(TAG, "自动保存失败: " + e);
            return false;
        }
    }

    /** 已保存的 FIT 文件（新 → 旧） */
    public java.util.List<File> savedFiles() {
        java.util.List<File> out = new java.util.ArrayList<>();
        File[] fs = dir().listFiles();
        if (fs != null) {
            for (File f : fs) {
                if (f.isFile() && f.getName().endsWith(".fit")) out.add(f);
            }
        }
        java.util.Collections.sort(out, (a, b) -> Long.compare(b.lastModified(), a.lastModified()));
        return out;
    }

    /** 把当前的记录（哪怕还在骑）导出成 FIT 到下载目录 */
    public synchronized String exportSnapshot() {
        if (recordCount <= 0) return null;
        String name = currentFile != null ? currentFile.getName()
                : ("XdsRelay_" + new SimpleDateFormat("yyyyMMdd_HHmmss", Locale.CHINA)
                        .format(new java.util.Date()) + ".fit");
        try {
            File tmp = new File(dir(), name);
            FitWriter.writeToFile(FitWriter.build(records.toByteArray(), summary()), tmp);
            String where = exportToVisible(ctx, tmp);
            if (where != null) Logs.add(TAG, "已导出 FIT 快照：" + where + "（" + recordCount + " 点）");
            return where;
        } catch (IOException e) {
            Logs.add(TAG, "导出 FIT 快照失败: " + e);
            return null;
        }
    }

    /** 导出到公共下载目录，返回可读的位置描述 */
    public static String exportToVisible(Context ctx, File src) {
        if (src == null || !src.exists()) return null;
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                ContentValues v = new ContentValues();
                v.put(MediaStore.Downloads.DISPLAY_NAME, src.getName());
                v.put(MediaStore.Downloads.MIME_TYPE, "application/octet-stream");
                v.put(MediaStore.Downloads.RELATIVE_PATH,
                        Environment.DIRECTORY_DOWNLOADS + "/XdsRelay");
                Uri uri = ctx.getContentResolver()
                        .insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, v);
                if (uri == null) return null;
                try (OutputStream os = ctx.getContentResolver().openOutputStream(uri)) {
                    if (os == null) return null;
                    copyTo(src, os);
                }
                return "Download/XdsRelay/" + src.getName();
            }
            File dir = new File(Environment.getExternalStoragePublicDirectory(
                    Environment.DIRECTORY_DOWNLOADS), "XdsRelay");
            //noinspection ResultOfMethodCallIgnored
            dir.mkdirs();
            File dst = new File(dir, src.getName());
            try (FileOutputStream fos = new FileOutputStream(dst)) {
                copyTo(src, fos);
            }
            return dst.getAbsolutePath();
        } catch (Exception e) {
            Logs.add(TAG, "导出到下载目录失败: " + e);
            return null;
        }
    }

    private static void copyTo(File src, OutputStream os) throws IOException {
        try (FileInputStream in = new FileInputStream(src)) {
            byte[] buf = new byte[8192];
            int n;
            while ((n = in.read(buf)) > 0) os.write(buf, 0, n);
        }
    }

    /**
     * 由中继定时器驱动（约 250 ms 一次），内部按整秒落一个点。
     */
    public synchronized void tick(long nowEpochSec, long nowElapsedMs,
                                  double lat, double lon, boolean hasFix,
                                  double altitudeM, double speedMps,
                                  int cadence, int power, int gpsAccuracyM,
                                  int leftPowerW, int rightPowerW, boolean hasLeftRight) {
        if (!recording) return;
        if (paused) { lastSampleMs = 0; return; }   // 暂停时冻结：不采样、不推进时间/距离
        endEpochSec = Math.max(endEpochSec, nowEpochSec);

        if (lastSampleMs == 0L) {
            lastSampleMs = nowElapsedMs;
            return;
        }
        double dt = (nowElapsedMs - lastSampleMs) / 1000.0;
        if (dt <= 0 || dt > 5.0) {
            lastSampleMs = nowElapsedMs;
            return;
        }
        lastSampleMs = nowElapsedMs;
        elapsedSec += dt;

        // 距离 / 有效运动时间
        if (speedMps > 0.8) {
            distanceM += speedMps * dt;
            timerSec += dt;
        }

        // 极值 / 均值
        if (speedMps > maxSpeed && speedMps < 60) maxSpeed = speedMps;
        speedSum += speedMps;
        speedCount++;
        if (power >= 0) {
            if (power > maxPower) maxPower = power;
            powerSum += power;
            powerCount++;
            if (powerSeriesLen < powerSeries.length) {
                powerSeries[powerSeriesLen++] = power;
            } else {
                System.arraycopy(powerSeries, 1, powerSeries, 0, powerSeries.length - 1);
                powerSeries[powerSeries.length - 1] = power;
            }
        }
        if (cadence > 0) {
            if (cadence > maxCadence) maxCadence = cadence;
            cadenceSum += cadence;
            cadenceCount++;
        }
        if (!Double.isNaN(altitudeM)) {
            if (Double.isNaN(maxAlt) || altitudeM > maxAlt) maxAlt = altitudeM;
            if (Double.isNaN(minAlt) || altitudeM < minAlt) minAlt = altitudeM;
            if (!Double.isNaN(prevAlt)) {
                double d = altitudeM - prevAlt;
                if (d > 1.0) ascent += (int) d;
                else if (d < -1.0) descent += (int) -d;
            }
            prevAlt = altitudeM;
        }
        if (hasLeftRight && (leftPowerW + rightPowerW) > 0) {
            leftSum += Math.round(100.0 * leftPowerW / (leftPowerW + rightPowerW));
            leftCount++;
        }

        // 整秒落点
        if (nowEpochSec > lastRecordEpochSec) {
            lastRecordEpochSec = nowEpochSec;
            FitWriter.Sample p = new FitWriter.Sample();
            p.timestampSec = nowEpochSec;
            p.lat = hasFix ? lat : Double.NaN;
            p.lon = hasFix ? lon : Double.NaN;
            p.altitudeM = altitudeM;
            p.distanceM = distanceM;
            p.speedMps = speedMps;
            p.cadence = cadence;
            p.power = power;
            p.gpsAccuracyM = gpsAccuracyM;
            byte[] d = FitWriter.recordData(p);
            records.write(d, 0, d.length);
            recordCount++;
        }
    }

    /** 当前进度的一份完整、可直接解析的 FIT 快照 */
    public synchronized byte[] snapshotBytes() {
        return FitWriter.build(records.toByteArray(), summary());
    }

    /** 快照写到临时文件，供上传 */
    public synchronized File writeSnapshot() throws IOException {
        File f = new File(dir(), "snapshot.fit");
        FitWriter.writeToFile(snapshotBytes(), f);
        return f;
    }

    public synchronized FitWriter.Summary summary() {
        FitWriter.Summary s = new FitWriter.Summary();
        s.startEpochSec = startEpochSec;
        s.endEpochSec = Math.max(endEpochSec, startEpochSec + 1);
        s.totalElapsedSec = Math.max(elapsedSec, s.endEpochSec - s.startEpochSec);
        s.totalTimerSec = timerSec;
        s.totalDistanceM = distanceM;
        s.avgSpeedMps = timerSec > 1 ? distanceM / timerSec : 0;
        s.maxSpeedMps = maxSpeed;
        s.avgCadence = cadenceCount > 0 ? (int) (cadenceSum / cadenceCount) : 0;
        s.maxCadence = maxCadence;
        s.avgPower = powerCount > 0 ? (int) (powerSum / powerCount) : 0;
        s.maxPower = maxPower;
        s.normalizedPower = normalizedPower();
        s.leftRightBalance = leftCount > 0 ? (int) (leftSum / leftCount) : -1;
        s.maxAltitude = maxAlt;
        s.minAltitude = minAlt;
        s.totalAscent = ascent;
        s.totalDescent = descent;
        s.totalCalories = (int) (s.totalTimerSec / 3600.0 * 520);   // 粗略估算
        return s;
    }

    /** 标准化功率：30 秒滑动平均的 4 次方均值再开 4 次方（1 Hz 采样，窗口 30 点） */
    private int normalizedPower() {
        if (powerSeriesLen < 30) {
            return powerSeriesLen > 0 ? (int) (powerSum / Math.max(1, powerCount)) : 0;
        }
        double sum4 = 0;
        int n = 0;
        long window = 0;
        for (int i = 0; i < powerSeriesLen; i++) {
            window += powerSeries[i];
            if (i >= 30) window -= powerSeries[i - 30];
            if (i >= 29) {
                double avg = window / 30.0;
                sum4 += avg * avg * avg * avg;
                n++;
            }
        }
        if (n == 0) return 0;
        return (int) Math.round(Math.pow(sum4 / n, 0.25));
    }

    public void reset() {
        synchronized (this) {
            recording = false;
            paused = false;
            records.reset();
            recordCount = 0;
            distanceM = 0;
            timerSec = 0;
            elapsedSec = 0;
            maxSpeed = 0;
            maxPower = 0;
            maxCadence = 0;
            powerSeriesLen = 0;
        }
    }
}
