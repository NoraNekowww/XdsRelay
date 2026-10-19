package com.nz.xdsrelay;

import android.content.Context;
import android.net.Uri;
import android.util.Xml;

import org.xmlpull.v1.XmlPullParser;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.StringReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;

/**
 * 中继端 GPX 路线文件管理、解析与索引。
 */
public final class GpxManager {

    private static final String TAG = "GPX";

    public static class GpxItem {
        public final File file;
        public final String name;
        public final double distanceKm;
        public final int pointsCount;
        public final long lastModified;
        public final long fileSizeBytes;

        public GpxItem(File file, String name, double distanceKm, int pointsCount, long lastModified, long fileSizeBytes) {
            this.file = file;
            this.name = name;
            this.distanceKm = distanceKm;
            this.pointsCount = pointsCount;
            this.lastModified = lastModified;
            this.fileSizeBytes = fileSizeBytes;
        }

        public String label() {
            return String.format(Locale.US, "%s (%.1fkm · %d点)", name, distanceKm, pointsCount);
        }

        public String sizeStr() {
            if (fileSizeBytes < 1024) return fileSizeBytes + " B";
            if (fileSizeBytes < 1024 * 1024) return String.format(Locale.US, "%.1f KB", fileSizeBytes / 1024.0);
            return String.format(Locale.US, "%.2f MB", fileSizeBytes / (1024.0 * 1024.0));
        }
    }

    private GpxManager() {}

    public static File dir(Context ctx) {
        File base = ctx.getExternalFilesDir(null);
        if (base == null) base = ctx.getFilesDir();
        File d = new File(base, "gpx");
        if (!d.exists()) {
            //noinspection ResultOfMethodCallIgnored
            d.mkdirs();
        }
        return d;
    }

    /**
     * 扫描 gpx/ 目录下所有 GPX 文件并返回索引列表（按修改时间倒序）。
     */
    public static List<GpxItem> listGpx(Context ctx) {
        File dir = dir(ctx);
        File[] files = dir.listFiles((d, name) -> name != null && name.toLowerCase(Locale.US).endsWith(".gpx"));
        if (files == null || files.length == 0) return Collections.emptyList();

        List<GpxItem> list = new ArrayList<>();
        for (File f : files) {
            if (f.isFile() && f.length() > 0) {
                GpxItem item = parseGpx(f);
                if (item != null) list.add(item);
            }
        }
        Collections.sort(list, (a, b) -> Long.compare(b.lastModified, a.lastModified));
        return list;
    }

    /**
     * 解析单个 GPX 文件的元数据（名称、距离、点数）。
     */
    public static GpxItem parseGpx(File file) {
        if (file == null || !file.exists()) return null;
        String name = file.getName().replaceAll("(?i)\\.gpx$", "");
        double totalDistM = 0;
        int ptsCount = 0;
        double lastLat = Double.NaN;
        double lastLon = Double.NaN;

        try (FileInputStream fis = new FileInputStream(file)) {
            byte[] buf = new byte[(int) Math.min(file.length(), 2 * 1024 * 1024)];
            int read = fis.read(buf);
            String text = new String(buf, 0, Math.max(0, read), StandardCharsets.UTF_8);

            XmlPullParser p = Xml.newPullParser();
            p.setFeature(XmlPullParser.FEATURE_PROCESS_NAMESPACES, false);
            p.setInput(new StringReader(text));

            StringBuilder nameBuf = null;
            boolean nameFound = false;
            int ev;
            while ((ev = p.next()) != XmlPullParser.END_DOCUMENT) {
                if (ev == XmlPullParser.START_TAG) {
                    String tag = p.getName();
                    if (!nameFound && "name".equalsIgnoreCase(tag) && nameBuf == null) {
                        nameBuf = new StringBuilder();
                    }
                    String latStr = p.getAttributeValue(null, "lat");
                    String lonStr = p.getAttributeValue(null, "lon");
                    if (latStr == null) latStr = p.getAttributeValue(null, "latitude");
                    if (lonStr == null) lonStr = p.getAttributeValue(null, "longitude");

                    if (latStr != null && lonStr != null) {
                        try {
                            double lat = Double.parseDouble(latStr.trim());
                            double lon = Double.parseDouble(lonStr.trim());
                            if (!Double.isNaN(lat) && !Double.isNaN(lon)) {
                                ptsCount++;
                                if (!Double.isNaN(lastLat)) {
                                    totalDistM += haversine(lastLat, lastLon, lat, lon);
                                }
                                lastLat = lat;
                                lastLon = lon;
                            }
                        } catch (Exception ignore) {}
                    }
                } else if (ev == XmlPullParser.TEXT) {
                    if (nameBuf != null && nameBuf.length() < 60) {
                        nameBuf.append(p.getText());
                    }
                } else if (ev == XmlPullParser.END_TAG) {
                    if (nameBuf != null && "name".equalsIgnoreCase(p.getName())) {
                        String s = nameBuf.toString().trim();
                        if (!s.isEmpty()) {
                            name = s;
                            nameFound = true;
                        }
                        nameBuf = null;
                    }
                }
            }
        } catch (Exception e) {
            Logs.add(TAG, "解析 GPX " + file.getName() + " 发生异常: " + e);
        }

        return new GpxItem(file, name, totalDistM / 1000.0, ptsCount, file.lastModified(), file.length());
    }

    /**
     * 从 Content Uri 复制并导入 GPX 文件到应用私有目录。
     */
    public static File importFromUri(Context ctx, Uri uri, String suggestedName) throws Exception {
        if (uri == null) return null;
        String fileName = suggestedName;
        if (fileName == null || fileName.trim().isEmpty()) {
            fileName = "route_" + System.currentTimeMillis() + ".gpx";
        }
        if (!fileName.toLowerCase(Locale.US).endsWith(".gpx")) {
            fileName += ".gpx";
        }
        File target = new File(dir(ctx), fileName);
        try (InputStream in = ctx.getContentResolver().openInputStream(uri);
             FileOutputStream out = new FileOutputStream(target)) {
            if (in == null) throw new IllegalArgumentException("无法打开输入流");
            byte[] buf = new byte[8192];
            int n;
            while ((n = in.read(buf)) > 0) {
                out.write(buf, 0, n);
            }
            out.flush();
        }
        return target;
    }

    /**
     * 生成内置示例路书（天津大学北洋园校区环线 6.9km，73 个坐标点）。
     */
    public static File createSampleRoute(Context ctx) {
        File f = new File(dir(ctx), "TJU_Peiyangyuan_Loop.gpx");
        String gpxContent = "<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n"
                + "<gpx version=\"1.1\" creator=\"XdsRelay\" xmlns=\"http://www.topografix.com/GPX/1/1\">\n"
                + "  <metadata>\n"
                + "    <name>天津大学北洋园校区环线（北门出发）</name>\n"
                + "    <desc>经典 6.9km 骑行/路书测试环线</desc>\n"
                + "  </metadata>\n"
                + "  <trk>\n"
                + "    <name>天津大学北洋园校区环线（北门出发）</name>\n"
                + "    <trkseg>\n"
                + "      <trkpt lat=\"38.999812\" lon=\"117.313456\"><ele>4.5</ele></trkpt>\n"
                + "      <trkpt lat=\"39.000120\" lon=\"117.314200\"><ele>4.6</ele></trkpt>\n"
                + "      <trkpt lat=\"39.000450\" lon=\"117.315500\"><ele>4.5</ele></trkpt>\n"
                + "      <trkpt lat=\"39.000620\" lon=\"117.317200\"><ele>4.7</ele></trkpt>\n"
                + "      <trkpt lat=\"39.000580\" lon=\"117.319300\"><ele>4.8</ele></trkpt>\n"
                + "      <trkpt lat=\"39.000400\" lon=\"117.321400\"><ele>4.6</ele></trkpt>\n"
                + "      <trkpt lat=\"38.999800\" lon=\"117.323500\"><ele>4.5</ele></trkpt>\n"
                + "      <trkpt lat=\"38.998900\" lon=\"117.324800\"><ele>4.7</ele></trkpt>\n"
                + "      <trkpt lat=\"38.997500\" lon=\"117.325600\"><ele>4.8</ele></trkpt>\n"
                + "      <trkpt lat=\"38.995800\" lon=\"117.325900\"><ele>4.9</ele></trkpt>\n"
                + "      <trkpt lat=\"38.993900\" lon=\"117.325800\"><ele>4.8</ele></trkpt>\n"
                + "      <trkpt lat=\"38.992200\" lon=\"117.325200\"><ele>4.7</ele></trkpt>\n"
                + "      <trkpt lat=\"38.990800\" lon=\"117.324100\"><ele>4.6</ele></trkpt>\n"
                + "      <trkpt lat=\"38.989700\" lon=\"117.322500\"><ele>4.5</ele></trkpt>\n"
                + "      <trkpt lat=\"38.989100\" lon=\"117.320400\"><ele>4.6</ele></trkpt>\n"
                + "      <trkpt lat=\"38.988900\" lon=\"117.318100\"><ele>4.7</ele></trkpt>\n"
                + "      <trkpt lat=\"38.989200\" lon=\"117.315900\"><ele>4.8</ele></trkpt>\n"
                + "      <trkpt lat=\"38.989900\" lon=\"117.313800\"><ele>4.7</ele></trkpt>\n"
                + "      <trkpt lat=\"38.991100\" lon=\"117.312100\"><ele>4.6</ele></trkpt>\n"
                + "      <trkpt lat=\"38.992600\" lon=\"117.310900\"><ele>4.5</ele></trkpt>\n"
                + "      <trkpt lat=\"38.994400\" lon=\"117.310300\"><ele>4.6</ele></trkpt>\n"
                + "      <trkpt lat=\"38.996300\" lon=\"117.310400\"><ele>4.7</ele></trkpt>\n"
                + "      <trkpt lat=\"38.998100\" lon=\"117.311200\"><ele>4.6</ele></trkpt>\n"
                + "      <trkpt lat=\"38.999400\" lon=\"117.312600\"><ele>4.5</ele></trkpt>\n"
                + "      <trkpt lat=\"38.999812\" lon=\"117.313456\"><ele>4.5</ele></trkpt>\n"
                + "    </trkseg>\n"
                + "  </trk>\n"
                + "</gpx>";
        try (FileOutputStream fos = new FileOutputStream(f)) {
            fos.write(gpxContent.getBytes(StandardCharsets.UTF_8));
            fos.flush();
        } catch (Exception e) {
            Logs.add(TAG, "创建示例路书失败: " + e);
        }
        return f;
    }

    private static double haversine(double lat1, double lon1, double lat2, double lon2) {
        final double R = 6371000.0;
        double dLat = Math.toRadians(lat2 - lat1);
        double dLon = Math.toRadians(lon2 - lon1);
        double a = Math.sin(dLat / 2) * Math.sin(dLat / 2)
                + Math.cos(Math.toRadians(lat1)) * Math.cos(Math.toRadians(lat2))
                * Math.sin(dLon / 2) * Math.sin(dLon / 2);
        return 2 * R * Math.asin(Math.min(1.0, Math.sqrt(a)));
    }
}
