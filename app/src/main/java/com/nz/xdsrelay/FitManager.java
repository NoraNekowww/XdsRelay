package com.nz.xdsrelay;

import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.net.Uri;

import androidx.core.content.FileProvider;

import java.io.File;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Date;
import java.util.List;
import java.util.Locale;

/**
 * FIT 骑行记录文件扫描、索引与操作管理。
 */
public final class FitManager {

    private static final String PREFS_NAME = "xds_fit_upload_state";

    public static class FitItem {
        public final File file;
        public final String name;
        public final long lastModified;
        public final long sizeBytes;
        public final boolean uploaded;

        public FitItem(File file, String name, long lastModified, long sizeBytes, boolean uploaded) {
            this.file = file;
            this.name = name;
            this.lastModified = lastModified;
            this.sizeBytes = sizeBytes;
            this.uploaded = uploaded;
        }

        public String dateStr() {
            SimpleDateFormat sdf = new SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.CHINA);
            return sdf.format(new Date(lastModified));
        }

        public String sizeStr() {
            if (sizeBytes < 1024) return sizeBytes + " B";
            if (sizeBytes < 1024 * 1024) return String.format(Locale.US, "%.1f KB", sizeBytes / 1024.0);
            return String.format(Locale.US, "%.2f MB", sizeBytes / (1024.0 * 1024.0));
        }

        /**
         * 粗略估算数据点数（1Hz 记录每秒一条，FIT 头部约 14 字节 + 定义消息，数据记录每条约 30 字节）
         */
        public int estimatedRecords() {
            if (sizeBytes <= 100) return 0;
            return Math.max(1, (int) ((sizeBytes - 64) / 32));
        }

        public String durationStr() {
            int sec = estimatedRecords();
            if (sec < 60) return sec + "秒";
            int min = sec / 60;
            int remSec = sec % 60;
            if (min < 60) return min + "分" + remSec + "秒";
            int hr = min / 60;
            int remMin = min % 60;
            return hr + "小时" + remMin + "分";
        }
    }

    private FitManager() {}

    public static File dir(Context ctx) {
        File base = ctx.getExternalFilesDir(null);
        if (base == null) base = ctx.getFilesDir();
        File d = new File(base, "fit");
        if (!d.exists()) {
            //noinspection ResultOfMethodCallIgnored
            d.mkdirs();
        }
        return d;
    }

    public static List<FitItem> listFit(Context ctx) {
        File d = dir(ctx);
        File[] files = d.listFiles((dir, name) -> name != null && name.toLowerCase(Locale.US).endsWith(".fit"));
        if (files == null || files.length == 0) return Collections.emptyList();

        SharedPreferences sp = ctx.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
        List<FitItem> list = new ArrayList<>();
        for (File f : files) {
            if (f.isFile() && f.length() > 0) {
                boolean up = sp.getBoolean("up_" + f.getName(), false);
                list.add(new FitItem(f, f.getName(), f.lastModified(), f.length(), up));
            }
        }
        Collections.sort(list, (a, b) -> Long.compare(b.lastModified, a.lastModified));
        return list;
    }

    public static void markUploaded(Context ctx, String fileName) {
        ctx.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                .edit()
                .putBoolean("up_" + fileName, true)
                .apply();
    }

    public static void shareFile(Context ctx, File file) {
        if (file == null || !file.exists()) return;
        Uri uri = FileProvider.getUriForFile(ctx, ctx.getPackageName() + ".files", file);
        Intent intent = new Intent(Intent.ACTION_SEND);
        intent.setType("application/octet-stream");
        intent.putExtra(Intent.EXTRA_STREAM, uri);
        intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
        ctx.startActivity(Intent.createChooser(intent, "导出/分享 " + file.getName()));
    }
}
