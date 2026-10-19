package com.nz.xdsrelay;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.bluetooth.BluetoothDevice;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.os.BatteryManager;
import android.os.Binder;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.PowerManager;
import android.os.SystemClock;

import java.io.File;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

/**
 * 中继前台服务：把三件事串起来
 *   AD350 私有功率 (BLE Central)  →  标准蓝牙 CPS/LNS (BLE Peripheral)
 *   本机 GNSS → 标准 LNS 广播 + 本机 UI
 */
public class RelayService extends Service {

    private static final String TAG = "RELAY";
    private static final String CHANNEL_ID = "xds_relay";
    private static final int NOTIF_ID = 0x5D50;

    public class LocalBinder extends Binder {
        public RelayService get() { return RelayService.this; }
    }

    private final IBinder binder = new LocalBinder();
    private final Handler handler = new Handler(Looper.getMainLooper());

    private XdsCentral central;
    private CpsServer server;
    private UdpSender udp;
    private BleLink link;
    private GnssTracker gnss;
    private FitRecorder recorder;

    public UdpSender udp() {
        return udp;
    }

    public BleLink link() {
        return link;
    }

    // ---- 记录 / 上报 ----
    /** 网站只支持手动拖拽上传，自动上报已关掉；FIT 一律本地生成 + 导出到下载目录 */
    private long lastAutoSaveMs;
    private volatile boolean autoUpload = false;
    private volatile long uploadIntervalMs = 60000L;
    private volatile long lastUploadMs;
    private volatile boolean uploading;
    private volatile NzcUploader.Result lastUpload;
    private static final double UP_LOW = 5.0;
    private static final double UP_HIGH = 3.0;
    private static final int UP_BUFFER = 3;
    private static final int UP_SMOOTH = 3;

    private PowerManager.WakeLock wakeLock;

    // ---- 对外可读状态 ----
    public volatile boolean relayRunning;
    public volatile XdsParser.Measurement lastMeasurement = new XdsParser.Measurement();
    public volatile boolean lnForwarding = true;
    public volatile int lnRateHz = 1;

    // ---- 转发计数 ----
    private long forwardedCps, forwardedLn;

    // ---- 曲柄转数合成（标准 CPS 需要累计曲柄转数 + 事件时间） ----
    private double crankRevsFraction;
    private int cumCrankRevs;
    private int lastCrankEvent;      // 单位 1/1024 s
    private long lastCrankCalcMs;

    private long lastLnMs;
    private long lastBatteryMs;

    private int tickCount;

    private final int[] powerWindow = new int[20];
    private int powerWindowIdx;
    private int powerWindowCount;

    /** 最近 20 个采样点的平均功率 */
    public int averagePower() {
        synchronized (powerWindow) {
            if (powerWindowCount == 0) return 0;
            long sum = 0;
            for (int i = 0; i < powerWindowCount; i++) sum += powerWindow[i];
            return (int) (sum / powerWindowCount);
        }
    }

    @Override
    public void onCreate() {
        super.onCreate();
        Logs.install(getApplicationContext());     // 日志落盘：骑行回来能复盘（Logs.java）
        central = new XdsCentral(this);
        server = new CpsServer(this);
        server.setActiveCheck(() -> relayRunning || (central != null && central.isSimulating()));
        udp = new UdpSender(this);
        udp.setControlListener(this::onRemoteControl);
        link = new BleLink(this);
        // BLE 模式（没有 WiFi/UDP）下的记录控制：显示端走 GATT 通知发过来，
        // 和中继用同一套处理逻辑（只动 FitRecorder，不碰任何转发链路）
        link.setControlListener(this::onRemoteControl);
        gnss = new GnssTracker(this);
        recorder = new FitRecorder(this);

        central.setListener(centralListener);
        server.setListener(serverListener);
        gnss.setListener(t -> { /* UI 直接读 gnss 对象 */ });

        createChannel();
        Logs.add(TAG, "服务已创建");
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        safeStartForeground(buildNotification(relayRunning ? null : "空闲"));
        String action = intent != null ? intent.getAction() : null;
        if ("com.nz.xdsrelay.START".equals(action)) {
            startRelay();
        } else if ("com.nz.xdsrelay.STOP".equals(action)) {
            stopRelay();
        }
        return START_STICKY;
    }

    /** Android 14 起 FGS 类型权限不足会抛 SecurityException，这里兜底不让服务崩掉。 */
    private void safeStartForeground(Notification n) {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                startForeground(NOTIF_ID, n,
                        android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE
                                | android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION);
            } else {
                startForeground(NOTIF_ID, n);
            }
        } catch (Exception e) {
            try {
                startForeground(NOTIF_ID, n);
            } catch (Exception e2) {
                Logs.add(TAG, "startForeground 失败: " + e2);
            }
        }
    }

    @Override
    public IBinder onBind(Intent intent) {
        return binder;
    }

    @Override
    public void onDestroy() {
        stopRelay();
        Logs.add(TAG, "服务已销毁");
        super.onDestroy();
    }

    // ================================================================
    //  启动 / 停止
    // ================================================================

    public void startRelay() {
        if (relayRunning) return;
        relayRunning = true;

        acquireWakeLock();

        Logs.add(TAG, "启动中继");
        server.start();
        if (udp != null) udp.start();
        if (link != null) link.start();
        gnss.start();

        cumCrankRevs = 0;
        crankRevsFraction = 0;
        lastCrankCalcMs = 0;
        lastCrankEvent = (int) ((SystemClock.elapsedRealtime() * 1024L / 1000L) & 0xFFFF);
        forwardedCps = forwardedLn = 0;
        tickCount = 0;
        powerWindowIdx = 0;
        powerWindowCount = 0;

        central.start();
        handler.removeCallbacks(tick);
        handler.post(tick);
        handler.removeCallbacks(telemetryTick);
        handler.post(telemetryTick);
        safeStartForeground(buildNotification(null));

        recorder.start(System.currentTimeMillis() / 1000L);
        lastUploadMs = SystemClock.elapsedRealtime();
        lastUpload = null;
    }

    public void stopRelay() {
        if (!relayRunning && central == null) return;
        relayRunning = false;
        handler.removeCallbacks(tick);
        handler.removeCallbacks(telemetryTick);
        if (central != null) central.stop();
        if (server != null) server.stop();
        if (udp != null) udp.stop();
        if (link != null) link.stop();
        if (gnss != null) gnss.stop();
        if (recorder != null) recorder.stop(System.currentTimeMillis() / 1000L);
        releaseWakeLock();
        safeStartForeground(buildNotification("已停止"));
        Logs.add(TAG, "中继已停止");
    }

    public void rescan() {
        if (central != null) central.restartScan();
    }

    /** 由界面调用，切换前台/后台扫描策略（后台用过滤扫描，避免被系统限流）。 */
    public void setAppForeground(boolean foreground) {
        if (central != null) central.setForegroundMode(foreground);
    }

    public void connectTo(BluetoothDevice d) {
        if (central != null) central.connectTo(d);
    }

    public void setSimulation(boolean on) {
        if (on) {
            if (!relayRunning) {
                relayRunning = true;
                acquireWakeLock();
                server.start();
                gnss.start();
                handler.removeCallbacks(tick);
                handler.post(tick);
            }
            central.startSimulation();
            if (gnss != null) gnss.setSimulated(true);
        } else {
            if (gnss != null) gnss.setSimulated(false);
            central.start();
        }
    }

    public boolean isSimulating() { return central != null && central.isSimulating(); }
    public XdsCentral central() { return central; }
    public CpsServer server() { return server; }
    public GnssTracker gnss() { return gnss; }
    public FitRecorder recorder() { return recorder; }

    // ---- 骑行记录控制（只动 FIT 记录，完全不碰任何数据转发）----

    /** 用户手动开始一次新记录 → 另起一个新文件（REC_日期_序号.fit） */
    public void startRecording() {
        FitRecorder r = recorder;
        if (r == null || r.isRecording()) return;
        r.start(System.currentTimeMillis() / 1000L);
    }

    /** 暂停记录：冻结采样/计时/距离，转发照旧 */
    public void pauseRecording() {
        FitRecorder r = recorder;
        if (r != null) r.pause();
    }

    public void resumeRecording() {
        FitRecorder r = recorder;
        if (r != null) r.resume();
    }

    /** 结束记录：写盘 + 导出到下载目录，本次文件就此定稿 */
    public void stopRecording() {
        FitRecorder r = recorder;
        if (r == null || !r.isRecording()) return;
        r.stop(System.currentTimeMillis() / 1000L);
    }

    /**
     * 仪表端（显示终端）通过 WiFi 下发的记录控制。
     * 只动 FIT 记录，转发链路一律不碰。
     */
    private void onRemoteControl(int cmd) {
        FitRecorder r = recorder;
        if (r == null) return;
        switch (cmd) {
            case UdpSender.CMD_PAUSE:
                r.pause();
                break;
            case UdpSender.CMD_RESUME:
                r.resume();
                break;
            case UdpSender.CMD_TOGGLE:
                if (!r.isRecording()) r.start(System.currentTimeMillis() / 1000L);
                else if (r.isPaused()) r.resume();
                else r.pause();
                break;
            case UdpSender.CMD_END:
                stopRecording();
                break;
            case UdpSender.CMD_START:
                startRecording();
                break;
            default:
                break;
        }
    }
    public long forwardedCps() { return forwardedCps; }
    public long forwardedLn() { return forwardedLn; }

    // ================================================================
    //  记录 / 上报到 nzlab.online
    // ================================================================

    public boolean autoUploadEnabled() { return autoUpload; }
    public boolean isUploading() { return uploading; }
    public NzcUploader.Result lastUploadResult() { return lastUpload; }
    public long lastUploadAtMs() { return lastUploadMs; }

    public void setAutoUpload(boolean enabled) {
        autoUpload = enabled;
        Logs.add(TAG, "自动上报 " + (enabled ? "已开启（每 " + (uploadIntervalMs / 1000) + " 秒）" : "已关闭"));
    }

    public void setUploadInterval(long ms) {
        uploadIntervalMs = Math.max(15000L, ms);
    }

    /** 立刻把当前记录快照上报给网站解析 */
    public void uploadNow() {
        FitRecorder r = recorder;
        if (r == null || !r.isRecording()) {
            Logs.add(TAG, "还没在记录，无法上报");
            return;
        }
        if (uploading) {
            Logs.add(TAG, "上一次上报还没结束，跳过");
            return;
        }
        if (r.recordCount() < 5) {
            Logs.add(TAG, "数据点太少（" + r.recordCount() + "），等几秒再传");
            return;
        }
        uploading = true;
        lastUploadMs = SystemClock.elapsedRealtime();
        Logs.add(TAG, "上报 FIT 快照（" + r.recordCount() + " 点）到 " + NzcUploader.ENDPOINT);
        final File snap;
        try {
            snap = r.writeSnapshot();
        } catch (Exception e) {
            uploading = false;
            Logs.add(TAG, "生成快照失败: " + e);
            return;
        }
        NzcUploader.upload(snap, UP_LOW, UP_HIGH, UP_BUFFER, UP_SMOOTH, result -> {
            uploading = false;
            lastUpload = result;
            if (result.ok) {
                Logs.add(TAG, "网站解析完成（" + result.tookMs + "ms）：" + result.summary());
            } else {
                Logs.add(TAG, "上报失败：" + result.error);
            }
            updateNotification();
        });
    }

    // ================================================================
    //  XDS 数据 → 标准蓝牙
    // ================================================================

    private final XdsCentral.Listener centralListener = new XdsCentral.Listener() {
        @Override
        public void onState(XdsCentral.State state, String detail) {
            updateNotification();
        }

        @Override
        public void onMeasurement(XdsParser.Measurement m) {
            forwardPower(m);
        }

        @Override
        public void onCandidate(String address, String name, int rssi, boolean isXds) {
            // 交给 UI 通过 candidateDevices() 读取
        }
    };

    private final CpsServer.Listener serverListener = new CpsServer.Listener() {
        @Override
        public void onAdvertising(boolean running, String detail) {
            updateNotification();
        }

        @Override
        public void onClients(List<String> clients) {
            // 有客户端连着时降低自身扫描强度，避免抢射频导致连接不稳定
            if (central != null) central.setClientConnected(!clients.isEmpty());
            updateNotification();
        }

        @Override
        public void onSubscribed(UUID characteristic) {
            updateNotification();
        }
    };

    private void forwardPower(XdsParser.Measurement m) {
        synchronized (lastMeasurement) {
            lastMeasurement = m.copy();
        }

        // ---- 曲柄转数合成 ----
        long now = SystemClock.elapsedRealtime();
        int cadence = m.hasCadence ? m.cadenceRpm : 0;
        if (lastCrankCalcMs != 0L) {
            double dt = (now - lastCrankCalcMs) / 1000.0;
            if (dt > 0 && dt < 5.0) {
                crankRevsFraction += cadence * dt / 60.0;
                int guard = 0;
                while (crankRevsFraction >= 1.0 && guard++ < 40) {
                    crankRevsFraction -= 1.0;
                    cumCrankRevs = (cumCrankRevs + 1) & 0xFFFF;
                    // 事件时间戳以 1/1024 s 计
                    lastCrankEvent = (int) ((now * 1024L / 1000L) & 0xFFFF);
                }
            }
        }
        lastCrankCalcMs = now;

        // ---- 功率滑动平均 ----
        synchronized (powerWindow) {
            powerWindow[powerWindowIdx] = m.totalPowerW;
            powerWindowIdx = (powerWindowIdx + 1) % powerWindow.length;
            if (powerWindowCount < powerWindow.length) powerWindowCount++;
        }

        // ---- 左右平衡 ----
        double balance = -1.0;
        if (m.hasLeft && m.hasRight && (m.leftPowerW + m.rightPowerW) > 0) {
            balance = 100.0 * m.leftPowerW / (m.leftPowerW + m.rightPowerW);
        }

        // ---- 骑行功率测量 → 码表 ----
        byte[] cps = BleCodec.cpsMeasurement(m.totalPowerW, balance, cumCrankRevs, lastCrankEvent);
        server.publishPower(cps);
        forwardedCps++;
    }

    private void forwardLocation() {
        if (!lnForwarding) return;
        long now = SystemClock.elapsedRealtime();
        long interval = Math.max(200L, 1000L / Math.max(1, lnRateHz));
        if (now - lastLnMs < interval) return;
        lastLnMs = now;

        GnssTracker g = gnss;
        if (g == null) return;

        boolean hasLoc = g.hasFix && !(g.lat == 0.0 && g.lon == 0.0);
        double elev = !Double.isNaN(g.altFiltered) ? g.altFiltered
                : (!Double.isNaN(g.altMsl) ? g.altMsl : 0.0);
        float heading = g.heading >= 0f ? g.heading : -1f;
        int status = hasLoc ? BleCodec.LN_POS_STATUS_OK : BleCodec.LN_POS_STATUS_NO_FIX;

        byte[] ln = BleCodec.lnLocationAndSpeed(
                g.speedFiltered, g.lat, g.lon, elev, heading, status, false, hasLoc);
        server.publishLocation(ln);
        forwardedLn++;
    }

    // ================================================================
    //  定时任务
    // ================================================================

    private final Runnable tick = new Runnable() {
        @Override
        public void run() {
            if (!relayRunning) return;

            forwardLocation();
            recordSample();

            // 每 4 个 tick（约 1 秒）刷新一次通知与电量
            if (++tickCount % 4 == 0) {
                int bat = batteryPercent();
                if (bat >= 0) server.publishBattery(bat);
                updateNotification();

                // 链路心跳：每 30 秒一条（约 120 个 tick），写进日志文件 ——
                // 仪表那边再"卡住"，翻这几行就知道断的是哪一段（2026-10-10 真骑 bug）
                if (tickCount % 120 == 0) Logs.add(TAG, linkHealth());

                // 网站只支持手动拖拽上传，自动上报已移除；FIT 本地生成 + 导出到下载目录即可。
            }
            handler.postDelayed(this, 250L);
        }
    };

    /** 链路心跳串：一眼看出是哪一段（传感器 / 广播 / UDP / BLE直连）出问题 */
    private String linkHealth() {
        XdsParser.Measurement m = lastMeasurement;
        long nowMs = System.currentTimeMillis();
        String sensor = m.timestampMs == 0 ? "未接" : ((nowMs - m.timestampMs) / 1000) + "s 前";
        CpsServer s = server;
        UdpSender u = udp;
        BleLink b = link;
        XdsCentral c = central;
        String bleIn = b == null ? "关" : (b.isConnected() ? "已连" : b.status());
        String sens = c == null ? "关" : (c.isConnected() ? "已连" : c.getState().toString());
        return "链路心跳 · 传感器 " + sensor + "（" + sens + "）"
                + " · 广播 " + (s == null ? "关" : s.advHealth())
                + " · UDP " + (u == null ? "关" : u.status())
                + " · 仪表BLE " + bleIn;
    }

    /** 1 Hz 采样写入 FIT */
    private void recordSample() {
        FitRecorder r = recorder;
        if (r == null || !r.isRecording()) return;

        XdsParser.Measurement m = lastMeasurement;
        long nowMs = System.currentTimeMillis();
        boolean fresh = m.timestampMs != 0 && nowMs - m.timestampMs < 5000;
        int power = fresh ? m.totalPowerW : -1;
        int cadence = fresh && m.hasCadence ? m.cadenceRpm : -1;
        int left = m.hasLeft ? m.leftPowerW : 0;
        int right = m.hasRight ? m.rightPowerW : 0;
        boolean hasLr = fresh && m.hasLeft && m.hasRight;

        GnssTracker g = gnss;
        boolean hasFix = g.hasFix && g.fixAgeMs() < 10000;
        double alt = !Double.isNaN(g.altFiltered) ? g.altFiltered : g.altMsl;
        int acc = g.accH > 0 ? Math.round(g.accH) : -1;

        r.tick(nowMs / 1000L, SystemClock.elapsedRealtime(),
                g.lat, g.lon, hasFix, alt, g.speedFiltered,
                cadence, power, acc, left, right, hasLr);
    }

    private void updateNotification() {
        Notification n = buildNotification(null);
        NotificationManager nm = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
        if (nm != null) nm.notify(NOTIF_ID, n);
    }

    // ================================================================
    //  通知
    // ================================================================

    private void createChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationManager nm = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
            if (nm == null || nm.getNotificationChannel(CHANNEL_ID) != null) return;
            NotificationChannel ch = new NotificationChannel(CHANNEL_ID,
                    getString(R.string.notif_channel_name), NotificationManager.IMPORTANCE_LOW);
            ch.setDescription(getString(R.string.notif_channel_desc));
            ch.setShowBadge(false);
            nm.createNotificationChannel(ch);
        }
    }

    private Notification buildNotification(String override) {
        Intent open = new Intent(this, MainActivity.class);
        open.setFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP | Intent.FLAG_ACTIVITY_NEW_TASK);
        int piFlags = PendingIntent.FLAG_UPDATE_CURRENT;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) piFlags |= PendingIntent.FLAG_IMMUTABLE;
        PendingIntent pi = PendingIntent.getActivity(this, 0, open, piFlags);

        Intent stop = new Intent(this, RelayService.class).setAction("com.nz.xdsrelay.STOP");
        PendingIntent stopPi = PendingIntent.getService(this, 1, stop, piFlags);

        Notification.Builder b;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            b = new Notification.Builder(this, CHANNEL_ID);
        } else {
            b = new Notification.Builder(this);
        }

        String title;
        String text;

        if (override != null) {
            title = getString(R.string.app_name);
            text = override;
        } else if (!relayRunning) {
            title = getString(R.string.app_name);
            text = "已停止";
        } else {
            XdsParser.Measurement m = lastMeasurement;
            String sensor;
            if (isSimulating()) {
                sensor = "模拟数据";
            } else {
                switch (central.getState()) {
                    case READY: sensor = "功率计已连接"; break;
                    case SCANNING: sensor = "正在搜索功率计"; break;
                    case CONNECTING:
                    case DISCOVERING:
                    case SUBSCRIBING: sensor = "正在建立连接"; break;
                    case WAIT_RETRY: sensor = "等待重连"; break;
                    case ERROR: sensor = "蓝牙出错"; break;
                    default: sensor = "已停止"; break;
                }
            }
            String power = m.timestampMs == 0 ? "--" : String.valueOf(m.totalPowerW);
            String cad = m.hasCadence ? String.valueOf(m.cadenceRpm) : "--";
            String alt = Double.isNaN(gnss.altFiltered) && Double.isNaN(gnss.altMsl)
                    ? "--" : String.format(Locale.CHINA, "%.0fm", !Double.isNaN(gnss.altFiltered) ? gnss.altFiltered : gnss.altMsl);
            title = power + " W · " + cad + " rpm · " + alt;
            text = sensor + "  |  广播 " + (server.isAdvertising() ? "中" : "关")
                    + "  |  客户端 " + server.clientCount();
        }

        b.setSmallIcon(R.drawable.ic_stat_relay)
                .setContentTitle(title)
                .setContentText(text)
                .setOngoing(true)
                .setOnlyAlertOnce(true)
                .setContentIntent(pi)
                .addAction(new Notification.Action.Builder(null, "停止", stopPi).build())
                .setVisibility(Notification.VISIBILITY_PUBLIC);

        return b.build();
    }

    // ================================================================
    //  WakeLock
    // ================================================================

    private void acquireWakeLock() {
        if (wakeLock != null && wakeLock.isHeld()) return;
        PowerManager pm = (PowerManager) getSystemService(Context.POWER_SERVICE);
        if (pm == null) return;
        wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "xdsrelay:relay");
        wakeLock.setReferenceCounted(false);
        wakeLock.acquire();
        Logs.add(TAG, "已持有 PARTIAL_WAKE_LOCK，避免息屏断连");
    }

    private void releaseWakeLock() {
        if (wakeLock != null && wakeLock.isHeld()) {
            try {
                wakeLock.release();
            } catch (Exception ignore) {}
        }
        wakeLock = null;
    }

    // ================================================================
    //  供 UI 读取的汇总
    // ================================================================

    // ================================================================
    //  广播直连：把当前快照塞进广播包（显示端只扫描不连接）
    // ================================================================

    /** 广播刷新节拍：50Hz 取快照，广播那边再按"数据一变就播"的规则往外发 */
    private static final long TELEMETRY_MS = 50L;

    private final Runnable telemetryTick = new Runnable() {
        @Override
        public void run() {
            try {
                pushTelemetry();
            } catch (Exception ignore) {}
            if (relayRunning) handler.postDelayed(this, TELEMETRY_MS);
        }
    };

    private void pushTelemetry() {
        if (server == null) return;
        XdsParser.Measurement m = lastMeasurement;
        boolean hasPower = m != null && (m.totalPowerW > 0 || m.hasCadence);
        int powerW = m == null ? 0 : m.totalPowerW;
        int cadence = (m != null && m.hasCadence) ? m.cadenceRpm : 0;
        double balance = -1;
        if (m != null && m.hasLeft && m.hasRight && (m.leftPowerW + m.rightPowerW) > 0) {
            balance = 100.0 * m.leftPowerW / (m.leftPowerW + m.rightPowerW);
        }

        GnssTracker g = gnss;
        // 只在"真正有新鲜定位"时才播位置：否则会把上一次的旧坐标一直播出去，
        // 显示端的地图就会跳到完全不相干的地方（实测踩过）。
        boolean hasGps = g != null
                && g.hasFix
                && g.fixAgeMs() < 5000
                && g.satsUsed > 0
                && Math.abs(g.lat) <= 90.0
                && Math.abs(g.lon) <= 180.0
                && !(g.lat == 0.0 && g.lon == 0.0);
        FitRecorder rec = recorder;
        byte[] t = CpsServer.buildTelemetry(
                hasPower, powerW, cadence, batteryPercent(),
                hasGps,
                g == null ? 0 : g.lat,
                g == null ? 0 : g.lon,
                g == null ? 0 : g.altMsl,
                g == null ? -1f : g.speedFiltered,
                g == null ? -1f : g.heading,
                g == null ? 0 : g.fixQuality,
                g == null ? 0 : g.satsUsed,
                balance,
                rec != null && rec.isRecording(),
                rec != null && rec.isPaused());
        server.publishTelemetry(t);
        // FIT 自动保存：每 2 分钟落一次盘，中途被杀也不丢整趟记录
        long tNow = System.currentTimeMillis();
        if (tNow - lastAutoSaveMs > 120000L) {
            lastAutoSaveMs = tNow;
            if (rec != null && rec.isRecording()) rec.autoSave();
        }
        // 同一份负载同时走 WiFi（UDP）：两条通道并行，从机那边自动用更新鲜的那条
        UdpSender u = udp;
        if (u != null) u.send(t);
        // BLE 直连（本机当客户端写过去）：延迟最低的一条
        BleLink bl = link;
        if (bl != null) bl.send(t);

        // 广播内容也打到日志里，方便和从机收到的比对（端到端验证）
        long now = System.currentTimeMillis();
        if (telemetryLogged < 3 || now - lastTelemetryLogMs > 20000) {
            telemetryLogged++;
            lastTelemetryLogMs = now;
            android.util.Log.i("XdsRelay", String.format(java.util.Locale.CHINA,
                    "XDS-P 广播内容：功率=%dW 踏频=%d 速度=%.1fkm/h 位置=%.6f,%.6f 海拔=%dm 星=%d 质量=%d 电量=%d%% 记录=%s",
                    powerW, cadence,
                    (g == null || !hasGps) ? -1f : g.speedFiltered * 3.6f,
                    (g == null || !hasGps) ? 0.0 : g.lat,
                    (g == null || !hasGps) ? 0.0 : g.lon,
                    (g == null || !hasGps) ? 0 : (int) Math.round(g.altMsl),
                    (g == null || !hasGps) ? 0 : g.satsUsed,
                    (g == null || !hasGps) ? 0 : g.fixQuality,
                    batteryPercent(),
                    rec == null ? "-" : (rec.isRecording()
                            ? (rec.isPaused() ? "暂停" : "记录中") : "未记录")));
        }
    }

    private int telemetryLogged;
    private long lastTelemetryLogMs;

    public int batteryPercent() {
        try {
            BatteryManager bm = (BatteryManager) getSystemService(Context.BATTERY_SERVICE);
            if (bm == null) return -1;
            return bm.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY);
        } catch (Exception e) {
            return -1;
        }
    }

    public boolean isBatteryCharging() {
        try {
            IntentFilter f = new IntentFilter(Intent.ACTION_BATTERY_CHANGED);
            Intent i = registerReceiver(null, f);
            if (i == null) return false;
            int s = i.getIntExtra(BatteryManager.EXTRA_STATUS, -1);
            return s == BatteryManager.BATTERY_STATUS_CHARGING || s == BatteryManager.BATTERY_STATUS_FULL;
        } catch (Exception e) {
            return false;
        }
    }
}
