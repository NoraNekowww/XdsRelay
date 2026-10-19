package com.nz.xdsrelay;

import android.annotation.SuppressLint;
import android.content.Context;
import android.location.GnssStatus;
import android.location.Location;
import android.location.LocationListener;
import android.location.LocationManager;
import android.location.OnNmeaMessageListener;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;

/**
 * 高精度定位与海拔采集。
 *
 * 除了 Android 的 LocationManager 之外，还直接解析 GNSS 原始 NMEA，
 * 因为海拔有两个"高"必须区分清楚：
 *   · MSL 海拔（正交高，GGA 第 9 字段）        —— 码表/地图上显示的就是这个
 *   · 椭球高 H = MSL + 大地水准面差距 N（GGA 第 11 字段）—— 物理/测绘意义
 * 同时还拿到 HDOP / VDOP / PDOP、定位质量、参与解算的卫星数，用于判断"高精度"程度。
 */
public final class GnssTracker {

    public interface Listener {
        void onGnss(GnssTracker tracker);
    }

    private static final String TAG = "GNSS";

    private final Context appContext;
    private final LocationManager lm;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private Listener listener;

    // ---- 对外暴露的数据 ----
    public volatile boolean hasFix;
    public volatile double lat, lon;
    public volatile double altMsl = Double.NaN;
    public volatile double geoidSep = Double.NaN;
    public volatile double altEllipsoid = Double.NaN;
    public volatile double altFiltered = Double.NaN;
    public volatile float speedMps;
    public volatile float speedFiltered;
    public volatile float heading = -1f;
    public volatile float accH, accV;
    public volatile float hdop = Float.NaN, vdop = Float.NaN, pdop = Float.NaN;
    public volatile int satsVisible, satsUsed;
    public volatile int fixQuality = -1;   // NMEA GGA quality: 0=无效 1=单点 2=差分 4=RTK固定 5=RTK浮动
    public volatile int fixType = -1;      // NMEA GSA fix type: 1=未定位 2=2D 3=3D
    public volatile long fixTimeMs;
    public volatile String utcTime = "--:--:--";
    public volatile double distanceM;      // 累计里程（对速度积分）
    public volatile String constellations = "";

    private boolean hasAltFilter;
    private long lastTickNs;
    private GnssStatus.Callback statusCallback;
    private LocationListener locationListener;
    private OnNmeaMessageListener nmeaListener;
    private boolean running;
    private boolean staleLogged;
    private long lastFixStamp;

    /**
     * 每 5 秒检查一次定位是不是断了。
     * 之所以要专门记日志：想区分"真的搜不到星（室内/无星）"和"系统把后台定位掐了"，
     * 只能靠中断时长和发生时机来判断，事后看日志才说得清。
     */
    private final Runnable staleWatch = new Runnable() {
        @Override
        public void run() {
            if (!running) return;
            long age = fixAgeMs();
            if (hasFix && age > 10000 && !staleLogged) {
                staleLogged = true;
                Logs.add(TAG, "定位已 " + (age / 1000) + " 秒没有更新（室内无星 或 后台被系统限流）");
            }
            long stamp = fixTimeMs;
            if (stamp != lastFixStamp) {
                if (lastFixStamp != 0L) {
                    long gap = stamp - lastFixStamp;
                    if (gap > 8000) Logs.add(TAG, "定位中断 " + (gap / 1000) + " 秒后恢复（fix 回来了）");
                }
                lastFixStamp = stamp;
                staleLogged = false;
            }
            handler.postDelayed(this, 5000L);
        }
    };

    public GnssTracker(Context ctx) {
        this.appContext = ctx.getApplicationContext();
        this.lm = (LocationManager) appContext.getSystemService(Context.LOCATION_SERVICE);
    }

    public void setListener(Listener l) {
        this.listener = l;
    }

    public boolean isProviderEnabled() {
        try {
            return lm != null && lm.isProviderEnabled(LocationManager.GPS_PROVIDER);
        } catch (Exception e) {
            return false;
        }
    }

    public boolean isRunning() {
        return running;
    }

    public long fixAgeMs() {
        return fixTimeMs == 0 ? Long.MAX_VALUE : SystemClock.elapsedRealtime() - fixTimeMs;
    }

    @SuppressLint("MissingPermission")
    public void start() {
        if (running || lm == null) return;
        running = true;
        hasAltFilter = false;
        lastTickNs = 0L;

        locationListener = new LocationListener() {
            @Override
            public void onLocationChanged(Location location) {
                onAndroidLocation(location);
            }

            @Override
            public void onStatusChanged(String provider, int status, Bundle extras) {}

            @Override
            public void onProviderEnabled(String provider) {}

            @Override
            public void onProviderDisabled(String provider) {
                Logs.add(TAG, "GPS provider 被关闭");
            }
        };

        statusCallback = new GnssStatus.Callback() {
            @Override
            public void onSatelliteStatusChanged(GnssStatus status) {
                // 模拟定位期间，真实卫星状态不能覆盖模拟出来的值（否则会被判成"没星"）
                if (gnssSimulated) return;
                int used = 0, visible = status.getSatelliteCount();
                StringBuilder sb = new StringBuilder();
                try {
                    java.util.HashSet<Integer> types = new java.util.HashSet<>();
                    for (int i = 0; i < visible; i++) {
                        if (status.usedInFix(i)) used++;
                        int t = status.getConstellationType(i);
                        if (status.usedInFix(i)) types.add(t);
                    }
                    for (int t : types) sb.append(constellationName(t)).append(' ');
                } catch (Exception ignore) {}
                satsVisible = visible;
                satsUsed = used;          // 用当前值；以前取历史最大值，没星时也会显示一个虚高的数字
                constellations = sb.toString().trim();
                notifyListener();
            }
        };

        nmeaListener = (message, timestamp) -> parseNmea(message);

        try {
            lm.requestLocationUpdates(LocationManager.GPS_PROVIDER, 1000L, 0f, locationListener,
                    Looper.getMainLooper());
        } catch (SecurityException e) {
            Logs.add(TAG, "无定位权限: " + e.getMessage());
            running = false;
            return;
        } catch (Exception e) {
            Logs.add(TAG, "requestLocationUpdates 失败: " + e);
        }

        try {
            lm.registerGnssStatusCallback(statusCallback, handler);
        } catch (Throwable t) {
            Logs.add(TAG, "GnssStatus 回调不可用: " + t);
        }

        try {
            lm.addNmeaListener(nmeaListener, handler);
        } catch (Throwable t) {
            Logs.add(TAG, "NMEA 监听不可用: " + t);
        }

        Logs.add(TAG, "已开始采集：GPS_PROVIDER 1Hz + NMEA + GnssStatus");
        staleLogged = false;
        lastFixStamp = 0L;
        handler.postDelayed(staleWatch, 10000L);
    }

    public void stop() {
        if (!running) return;
        running = false;
        handler.removeCallbacks(staleWatch);
        try {
            if (locationListener != null) lm.removeUpdates(locationListener);
        } catch (Exception ignore) {}
        try {
            if (statusCallback != null) lm.unregisterGnssStatusCallback(statusCallback);
        } catch (Exception ignore) {}
        try {
            if (nmeaListener != null) lm.removeNmeaListener(nmeaListener);
        } catch (Exception ignore) {}
        Logs.add(TAG, "已停止采集");
    }

    // ================= 模拟定位（无人值守验证用） =================

    private boolean gnssSimulated;
    private double simLat = 39.90420;
    private double simLon = 116.40740;
    private double simAlt = 45.0;
    private double simHeading = 55.0;
    private long simLastMs;
    private long simTicks;

    /**
     * 模拟一条移动轨迹（约 25km/h，会缓慢转弯，海拔小幅起伏）。
     * 桌面测试用：室内搜不到星时，也能把"定位 → 广播 → 从机"整条链路跑通。
     */
    public void setSimulated(boolean on) {
        if (gnssSimulated == on) return;
        gnssSimulated = on;
        handler.removeCallbacks(simTask);
        if (on) {
            simLastMs = 0;
            simTicks = 0;
            hasFix = true;
            satsVisible = 11;
            satsUsed = 9;
            fixQuality = 1;
            fixType = 3;
            hdop = 0.8f;
            vdop = 1.2f;
            pdop = 1.4f;
            accH = 2.0f;
            accV = 3.0f;
            geoidSep = -9.0;
            constellations = "GPS BDS";
            Logs.add(TAG, "进入模拟定位：约 25km/h 的移动轨迹");
            handler.post(simTask);
        } else {
            Logs.add(TAG, "退出模拟定位");
        }
    }

    private final Runnable simTask = new Runnable() {
        @Override
        public void run() {
            if (!gnssSimulated) return;
            simStep();
            handler.postDelayed(this, 1000L);
        }
    };

    private void simStep() {
        long now = SystemClock.elapsedRealtime();
        double dt = simLastMs == 0 ? 1.0 : Math.min(2.0, (now - simLastMs) / 1000.0);
        simLastMs = now;
        simTicks++;

        double speed = 25.0 / 3.6;                       // 25 km/h
        double dist = speed * dt;

        simHeading += Math.sin(simTicks / 9.0) * 5.0;    // 缓慢转弯
        double rad = Math.toRadians(simHeading);
        simLat += (dist * Math.cos(rad)) / 111320.0;
        simLon += (dist * Math.sin(rad)) / (111320.0 * Math.cos(Math.toRadians(simLat)));
        simAlt += Math.sin(simTicks / 11.0) * 1.5;       // 海拔起伏

        lat = simLat;
        lon = simLon;
        altMsl = simAlt;
        altEllipsoid = simAlt + geoidSep;
        speedMps = (float) speed;
        heading = (float) (((simHeading % 360.0) + 360.0) % 360.0);
        hasFix = true;
        fixTimeMs = now;
        accH = 2.0f;
        accV = 3.0f;
        satsVisible = 11;
        satsUsed = 9;          // 每拍重新写一遍，防止被真实 GNSS 回调清掉
        fixQuality = 1;
        fixType = 3;
        geoidSep = -9.0;
        constellations = "GPS BDS";

        updateDistance(true);
        speedFiltered = (float) speed;                   // 模拟里不需要平滑
        updateFilters(altMsl);
        notifyListener();
    }

    public boolean isSimulated() {
        return gnssSimulated;
    }

    // ================= Android Location =================

    private void onAndroidLocation(Location loc) {
        if (loc == null || gnssSimulated) return;

        if (loc.hasAccuracy()) accH = loc.getAccuracy();
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && loc.hasVerticalAccuracy()) {
            accV = loc.getVerticalAccuracyMeters();
        } else if (loc.hasAltitude()) {
            // 没有垂直精度时给一个保守估计
            accV = Math.max(accV, accH > 0 ? accH * 1.6f : 0f);
        }

        boolean moved = false;
        if (loc.hasSpeed()) {
            speedMps = loc.getSpeed();
            moved = speedMps > 0.3f;
        }
        if (loc.hasBearing()) heading = loc.getBearing();

        // 若 NMEA 还没给到海拔，则先用 Location 的值兜底
        if (Double.isNaN(altMsl) && loc.hasAltitude()) {
            altMsl = loc.getAltitude();
            if (Build.VERSION.SDK_INT >= 34 && loc.hasMslAltitude()) {
                altMsl = loc.getMslAltitudeMeters();
            }
        }

        if (loc.getLatitude() != 0.0 || loc.getLongitude() != 0.0) {
            lat = loc.getLatitude();
            lon = loc.getLongitude();
            hasFix = true;
        }
        fixTimeMs = SystemClock.elapsedRealtime();

        updateFilters(altMsl);
        updateDistance(moved);
        notifyListener();
    }

    // ================= NMEA =================

    private void parseNmea(String message) {
        if (message == null || message.length() < 9 || gnssSimulated) return;
        int star = message.indexOf('*');
        String body = star > 0 ? message.substring(0, star) : message;
        if (star > 0 && star + 2 < message.length()) {
            try {
                int expect = Integer.parseInt(message.substring(star + 1, star + 3), 16);
                int calc = 0;
                for (int i = 1; i < body.length(); i++) calc ^= body.charAt(i);
                if (calc != expect) return;   // 校验失败直接丢弃
            } catch (Exception ignore) {}
        }
        if (body.endsWith("GGA")) {
            parseGga(body);
        } else if (body.endsWith("GSA")) {
            parseGsa(body);
        }
    }

    private void parseGga(String body) {
        String[] f = body.split(",", -1);
        if (f.length < 12) return;
        try {
            // 时间
            if (!f[1].isEmpty()) {
                String t = f[1];
                if (t.length() >= 6) {
                    utcTime = t.substring(0, 2) + ":" + t.substring(2, 4) + ":" + t.substring(4, 6)
                            + " UTC";
                }
            }
            // 定位质量 / 卫星数 / HDOP
            if (!f[6].isEmpty()) fixQuality = (int) Double.parseDouble(f[6]);
            if (!f[7].isEmpty()) {
                int s = (int) Double.parseDouble(f[7]);
                satsUsed = s;             // GGA 的"使用卫星数"= 当前值，不要取历史最大值
            }
            if (!f[8].isEmpty()) hdop = (float) Double.parseDouble(f[8]);

            // 经纬度：必须真的定位成功（fixQuality>0）才采信。
            // 无星时 GGA 里可能是空字段或半截数据，照单全收会存下完全错误的坐标。
            if (fixQuality > 0
                    && !f[2].isEmpty() && !f[3].isEmpty()
                    && !f[4].isEmpty() && !f[5].isEmpty()) {
                double la = nmeaDeg(f[2]);
                if ("S".equalsIgnoreCase(f[3])) la = -la;
                double lo = nmeaDeg(f[4]);
                if ("W".equalsIgnoreCase(f[5])) lo = -lo;
                if (Math.abs(la) <= 90.0 && Math.abs(lo) <= 180.0) {
                    lat = la;
                    lon = lo;
                }
            }

            // 海拔：MSL 与 大地水准面差距
            double msl = Double.NaN;
            if (!f[9].isEmpty()) msl = Double.parseDouble(f[9]);
            if (!f[11].isEmpty()) geoidSep = Double.parseDouble(f[11]);
            if (!Double.isNaN(msl)) {
                altMsl = msl;
                if (!Double.isNaN(geoidSep)) altEllipsoid = msl + geoidSep;
            }

            if (fixQuality > 0) {
                hasFix = true;
                fixTimeMs = SystemClock.elapsedRealtime();
            }
            updateFilters(altMsl);
            notifyListener();
        } catch (Exception ignore) {
        }
    }

    private void parseGsa(String body) {
        String[] f = body.split(",", -1);
        if (f.length < 18) return;
        try {
            if (!f[2].isEmpty()) fixType = (int) Double.parseDouble(f[2]);
            if (!f[15].isEmpty()) pdop = (float) Double.parseDouble(f[15]);
            if (!f[16].isEmpty()) hdop = (float) Double.parseDouble(f[16]);
            if (!f[17].isEmpty()) vdop = (float) Double.parseDouble(f[17]);
        } catch (Exception ignore) {
        }
    }

    /** ddmm.mmmm / dddmm.mmmm -> 十进制度 */
    private static double nmeaDeg(String v) {
        int dot = v.indexOf('.');
        int degDigits = dot - 2;
        if (degDigits <= 0) return Double.parseDouble(v);
        double deg = Double.parseDouble(v.substring(0, degDigits));
        double min = Double.parseDouble(v.substring(degDigits));
        return deg + min / 60.0;
    }

    // ================= 滤波 =================

    private void updateFilters(double alt) {
        if (Double.isNaN(alt)) return;
        if (!hasAltFilter) {
            altFiltered = alt;
            hasAltFilter = true;
            return;
        }
        double delta = Math.abs(alt - altFiltered);
        double alpha;
        if (delta > 40.0) {
            alpha = 0.8;             // 明显的爬升/下降，快速跟随
        } else if (accV > 0f) {
            alpha = Math.min(0.6, Math.max(0.05, 1.0 / (1.0 + accV)));
        } else {
            alpha = 0.2;
        }
        altFiltered += alpha * (alt - altFiltered);
    }

    private void updateDistance(boolean moved) {
        long now = SystemClock.elapsedRealtimeNanos();
        if (lastTickNs != 0L && moved) {
            double dt = (now - lastTickNs) / 1e9;
            if (dt > 0 && dt < 5.0) {
                speedFiltered = (float) (speedFiltered * 0.7 + speedMps * 0.3);
                distanceM += speedFiltered * dt;
            }
        } else if (!moved) {
            speedFiltered = (float) Math.max(0.0, speedFiltered * 0.6 + speedMps * 0.4);
        }
        lastTickNs = now;
    }

    private void notifyListener() {
        Listener l = listener;
        if (l != null) l.onGnss(this);
    }

    private static String constellationName(int type) {
        switch (type) {
            case GnssStatus.CONSTELLATION_GPS: return "GPS";
            case GnssStatus.CONSTELLATION_SBAS: return "SBAS";
            case GnssStatus.CONSTELLATION_GLONASS: return "GLO";
            case GnssStatus.CONSTELLATION_QZSS: return "QZSS";
            case GnssStatus.CONSTELLATION_BEIDOU: return "BDS";
            case GnssStatus.CONSTELLATION_GALILEO: return "GAL";
            case GnssStatus.CONSTELLATION_IRNSS: return "IRNSS";
            default: return "";
        }
    }
}
