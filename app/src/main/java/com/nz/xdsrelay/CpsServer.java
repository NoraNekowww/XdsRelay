package com.nz.xdsrelay;

import android.Manifest;
import android.annotation.SuppressLint;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothGatt;
import android.bluetooth.BluetoothGattCharacteristic;
import android.bluetooth.BluetoothGattDescriptor;
import android.bluetooth.BluetoothGattServer;
import android.bluetooth.BluetoothGattServerCallback;
import android.bluetooth.BluetoothGattService;
import android.bluetooth.BluetoothManager;
import android.bluetooth.BluetoothProfile;
import android.bluetooth.le.AdvertiseCallback;
import android.bluetooth.le.AdvertiseData;
import android.bluetooth.le.AdvertiseSettings;
import android.bluetooth.le.BluetoothLeAdvertiser;
import android.content.Context;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.os.ParcelUuid;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * BLE 外设：把手机变成一个标准蓝牙功率计 + 定位传感器。
 *
 * 对外暴露的标准 GATT：
 *   0x1818 骑行功率服务
 *       0x2A63 骑行功率测量 (notify)   ← AD350 的功率/踏频/左右平衡
 *       0x2A65 骑行功率特性 (read)
 *       0x2A5B 传感器位置   (read)     = 左曲柄
 *   0x1819 定位与导航服务
 *       0x2A67 位置与速度   (notify)   ← 本机 GNSS 的经纬度/海拔/速度/航向
 *       0x2A6A LN 特性      (read)
 *   0x180F 电池服务
 *       0x2A19 电量         (read/notify)
 */
public class CpsServer {

    private static final String TAG = "XDS-P";

    public interface Listener {
        void onAdvertising(boolean running, String detail);
        void onClients(List<String> clients);
        void onSubscribed(UUID characteristic);
    }

    private final Context ctx;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private Listener listener;

    private BluetoothAdapter adapter;
    private BluetoothGattServer server;
    private BluetoothLeAdvertiser advertiser;

    private BluetoothGattCharacteristic cpsMeasurement;
    private BluetoothGattCharacteristic cpsFeature;
    private BluetoothGattCharacteristic sensorLocation;
    private BluetoothGattCharacteristic lnLocationSpeed;
    private BluetoothGattCharacteristic lnFeature;
    private BluetoothGattCharacteristic batteryLevel;

    private final Map<String, BluetoothDevice> clients = new LinkedHashMap<>();
    private final Map<String, Set<UUID>> subscriptions = new LinkedHashMap<>();
    /** 没有正常订阅、由我们主动替其打开通知的客户端 */
    private final Map<String, Set<UUID>> forced = new LinkedHashMap<>();
    /** CCCD 本地武装（兜底） */
    private int publishTick;
    /** 通知被拒只打前几次，避免刷屏 */
    private int notifyErrLogged;

    private boolean advertising;
    /** 广播是否已经真正生效（onStartSuccess 之后才为 true），用于识别残留连接 */
    private volatile boolean advertisingLive;
    private int lastMtu = 23;

    private byte[] lastCps = new byte[0];
    private byte[] lastLn = new byte[0];
    private int lastBattery = 100;

    private long lnSent, cpsSent;

    public CpsServer(Context ctx) {
        this.ctx = ctx.getApplicationContext();
        BluetoothManager bm = (BluetoothManager) this.ctx.getSystemService(Context.BLUETOOTH_SERVICE);
        adapter = bm != null ? bm.getAdapter() : BluetoothAdapter.getDefaultAdapter();
        if (adapter != null) advertiser = adapter.getBluetoothLeAdvertiser();
    }

    public void setListener(Listener l) { this.listener = l; }

    public boolean isAdvertising() { return advertising; }

    /**
     * 广播链路健康串（写进日志，也能上界面）：中继机的广播是仪表端的兜底通道，
     * 它要是悄悄停了，仪表那边只能干等 —— 所以这里如实报"上次成功刷新多久前 / 连错几次"。
     */
    public String advHealth() {
        if (!advertising) return "关";
        long ok = lastAdvOkMs;
        StringBuilder sb = new StringBuilder(advertisingLive ? "在播" : "未确认");
        if (ok > 0) sb.append(" · 上次刷新 ").append((System.currentTimeMillis() - ok) / 1000).append("s 前");
        if (advFailStreak > 0) sb.append(" · 连续失败 ").append(advFailStreak).append(" 次");
        sb.append(" · 累计 ").append(telemetrySent).append(" 次");
        return sb.toString();
    }
    public int clientCount() { return clients.size(); }
    public int getLastMtu() { return lastMtu; }
    public List<String> clientNames() {
        List<String> out = new ArrayList<>();
        for (Map.Entry<String, BluetoothDevice> e : clients.entrySet()) {
            out.add(name(e.getValue()) + "  " + e.getKey());
        }
        return out;
    }
    public long cpsSentCount() { return cpsSent; }
    public long lnSentCount() { return lnSent; }

    // ================================================================

    @SuppressLint("MissingPermission")
    public boolean start() {
        if (adapter == null) {
            log("没有蓝牙适配器");
            return false;
        }
        if (!adapter.isEnabled()) {
            log("蓝牙未开启");
            return false;
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S
                && ctx.checkSelfPermission(Manifest.permission.BLUETOOTH_ADVERTISE)
                != PackageManager.PERMISSION_GRANTED) {
            log("缺少 BLUETOOTH_ADVERTISE 权限");
            return false;
        }
        if (advertiser == null) {
            advertiser = adapter.getBluetoothLeAdvertiser();
        }
        if (advertiser == null) {
            log("本机不支持 BLE 外设广播");
            return false;
        }
        if (advertising) {
            log("广播已经在运行，忽略重复启动");
            return true;
        }

        clients.clear();
        subscriptions.clear();

        if (server == null) {
            BluetoothManager bm = (BluetoothManager) ctx.getSystemService(Context.BLUETOOTH_SERVICE);
            server = bm.openGattServer(ctx, serverCallback);
            if (server == null) {
                log("openGattServer 失败");
                return false;
            }
        }

        if (!addServices()) {
            log("添加 GATT 服务失败");
            return false;
        }

        AdvertiseSettings settings = new AdvertiseSettings.Builder()
                .setAdvertiseMode(AdvertiseSettings.ADVERTISE_MODE_LOW_LATENCY)
                .setTxPowerLevel(AdvertiseSettings.ADVERTISE_TX_POWER_HIGH)
                .setConnectable(true)
                .setTimeout(0)
                .build();

        AdvertiseData data = new AdvertiseData.Builder()
                .addServiceUuid(new ParcelUuid(BleUuids.CPS_SERVICE))
                .addServiceUuid(new ParcelUuid(BleUuids.LNS_SERVICE))
                .setIncludeDeviceName(false)
                .setIncludeTxPowerLevel(false)
                .build();

        // 设备名放进扫描响应，码表/手机扫描时能看到来源设备（使用手机自身的蓝牙名，不改名）
        AdvertiseData scanResponse = new AdvertiseData.Builder()
                .setIncludeDeviceName(true)
                .build();

        try {
            advertisingLive = false;
            advertiser.startAdvertising(settings, buildAdvData(), buildScanResponse(), advertiseCallback);
        } catch (SecurityException e) {
            log("开始广播被拒绝: " + e.getMessage());
            return false;
        } catch (Exception e) {
            log("开始广播异常: " + e);
            return false;
        }
        log("正在广播：0x1818 骑行功率 + 0x1819 定位导航 + 广播直连数据");
        startAdvRefresh();
        return true;
    }

    /** 中继是否还在跑（广播健康检查用它判断要不要把广播拉回来） */
    public interface ActiveCheck {
        boolean isActive();
    }

    private ActiveCheck activeCheck;

    public void setActiveCheck(ActiveCheck c) {
        this.activeCheck = c;
    }

    private boolean relayActive() {
        return activeCheck == null || activeCheck.isActive();
    }

    // ================================================================
    //  广播直连（把数据塞进广播包，显示端只扫描不连接）
    // ================================================================

    /** 厂商 ID：自家两个 App 之间约定，不参与任何注册表 */
    public static final int XDS_COMPANY_ID = 0x0A0C;

    /** 最近一次要广播出去的数据（最多 19 字节，见 buildTelemetry） */
    private volatile byte[] telemetry = new byte[0];
    private volatile boolean advRefreshRunning;
    private int telemetrySent;

    private AdvertiseData buildAdvData() {
        // 注意广播包 31 字节上限：
        //   flags(3) + 0x1818(4) + 厂商数据(4+19=23) = 30 ✓
        // 0x1819 挪到扫描响应里去（见 buildScanResponse），否则总量超限广播会直接被拒。
        AdvertiseData.Builder b = new AdvertiseData.Builder()
                .addServiceUuid(new ParcelUuid(BleUuids.CPS_SERVICE))
                .setIncludeDeviceName(false)
                .setIncludeTxPowerLevel(false);
        byte[] t = telemetry;
        if (t != null && t.length > 0) {
            try {
                b.addManufacturerData(XDS_COMPANY_ID, t);
            } catch (Exception e) {
                log("厂商数据装不进广播包: " + e);
            }
        }
        return b.build();
    }

    private AdvertiseData buildScanResponse() {
        // 0x1819(4) + 设备名(约 20) = 24 ✓
        AdvertiseData.Builder b = new AdvertiseData.Builder()
                .addServiceUuid(new ParcelUuid(BleUuids.LNS_SERVICE))
                .setIncludeDeviceName(true);
        return b.build();
    }

    /**
     * 更新要广播的遥测数据。
     * 刷新策略跟数据本身同步：数据一变就重新广播一次（最快 60ms 一次），
     * 数据没变就只按保活间隔播一遍 —— 既不浪费，延迟也最低。
     */
    public void publishTelemetry(byte[] payload) {
        telemetry = payload;
        maybeRefresh();
    }

    @SuppressLint("MissingPermission")
    private void startAdvRefresh() {
        if (advRefreshRunning) return;
        advRefreshRunning = true;
        lastAdvOkMs = System.currentTimeMillis();
        handler.removeCallbacks(advHealth);
        handler.postDelayed(advHealth, 5000L);
        handler.postDelayed(advRefreshTick, 300L);
    }

    private volatile long lastAdvOkMs;

    /**
     * 广播健康检查：HyperOS 后台偶尔会把广播掐掉。
     * 连续 5 秒没有一次成功刷新就整体重开广播链路，别让它悄悄死掉。
     */
    private final Runnable advHealth = () -> advHealthTick();

    private void advHealthTick() {
        if (!advertising || !advRefreshRunning) return;
        long now = System.currentTimeMillis();
        if (now - lastAdvOkMs > 5000) {
            log("⚠ 广播 5 秒没有成功刷新，整体重开广播");
            advRefreshRunning = false;
            try {
                advertiser.stopAdvertising(advRefreshCallback);
            } catch (Exception ignore) {}
            try {
                advertiser.stopAdvertising(advertiseCallback);
            } catch (Exception ignore) {}
            stop();
            handler.postDelayed(() -> {
                if (!relayActive()) return;
                if (start()) log("广播已重新拉起");
            }, 600L);
            return;
        }
        handler.postDelayed(advHealth, 5000L);
    }

    private static final long ADV_MIN_GAP_MS = 200L;       // 两次广播刷新之间的最短间隔
    private static final long ADV_KEEPALIVE_MS = 400L;     // 数据没变时的保活刷新间隔
    private static final long ADV_MAX_MS = 800L;           // 连续失败时的退让上限（别退太狠，否则刷新率掉到 1Hz 以下）
    private volatile long advIntervalMs = ADV_KEEPALIVE_MS;
    private int advFailStreak;
    private byte[] lastBroadcast;
    private long lastAdvAt;

    private void maybeRefresh() {
        if (!advertising || !advRefreshRunning) return;
        byte[] t = telemetry;
        if (t == null || t.length == 0) return;
        long now = System.currentTimeMillis();
        if (now - lastAdvAt < ADV_MIN_GAP_MS) return;
        boolean changed = lastBroadcast == null || !java.util.Arrays.equals(t, lastBroadcast);
        if (!changed && now - lastAdvAt < advIntervalMs) return;
        lastBroadcast = t.clone();
        lastAdvAt = now;
        restartAdvertising();
    }

    private void scheduleAdvRefresh(long delay) {
        handler.removeCallbacks(advRefreshTick);
        if (!advertising || !advRefreshRunning) return;
        handler.postDelayed(advRefreshTick, delay);
    }

    private final Runnable advRefreshTick = () -> {
        if (!advertising || !advRefreshRunning) return;
        maybeRefresh();
        scheduleAdvRefresh(100L);
    };

    @SuppressLint("MissingPermission")
    private void restartAdvertising() {
        if (advertiser == null) {
            scheduleAdvRefresh(advIntervalMs);
            return;
        }
        try {
            // 必须用"当初启动它的那个 callback"去停，否则 stop 是空操作，
            // 紧接着的 start 就会以 code=3 (ALREADY_STARTED) 失败（实测踩过）。
            advertiser.stopAdvertising(advRefreshCallback);
        } catch (Exception e) {
            // 停不掉就直接重开，下一轮再说
        }
        try {
            advertiser.stopAdvertising(advertiseCallback);
        } catch (Exception ignore) {}

        handler.postDelayed(() -> {
            if (!advertising || !advRefreshRunning) return;
            try {
                AdvertiseSettings settings = new AdvertiseSettings.Builder()
                        .setAdvertiseMode(AdvertiseSettings.ADVERTISE_MODE_LOW_LATENCY)
                        .setTxPowerLevel(AdvertiseSettings.ADVERTISE_TX_POWER_HIGH)
                        .setConnectable(true)
                        .setTimeout(0)
                        .build();
                advertiser.startAdvertising(settings, buildAdvData(), buildScanResponse(), advRefreshCallback);
            } catch (Exception e) {
                advFailStreak++;
                advIntervalMs = Math.min(ADV_MAX_MS, advIntervalMs * 2);
                scheduleAdvRefresh(advIntervalMs);
            }
        }, 30L);
    }

    /** 刷新用回调：只记统计，不改变"广播已就绪"的状态 */
    private final AdvertiseCallback advRefreshCallback = new AdvertiseCallback() {
        @Override
        public void onStartSuccess(AdvertiseSettings settingsInEffect) {
            advFailStreak = 0;
            advIntervalMs = ADV_KEEPALIVE_MS;
            lastAdvOkMs = System.currentTimeMillis();
            telemetrySent++;
            if (telemetrySent <= 3 || telemetrySent % 600 == 0) {
                log("广播直连刷新 " + telemetrySent + " 次 · 客户端 " + clients.size() + " 个");
            }
            scheduleAdvRefresh(ADV_MIN_GAP_MS);
        }

        @Override
        public void onStartFailure(int errorCode) {
            advFailStreak++;
            advIntervalMs = Math.min(ADV_MAX_MS, Math.max(ADV_KEEPALIVE_MS, advIntervalMs * 2));
            if (advFailStreak <= 5) log("广播刷新失败 code=" + errorCode + "，间隔放宽到 " + advIntervalMs + "ms");
            scheduleAdvRefresh(advIntervalMs);
        }
    };

    /**
     * 构造广播遥测负载（20 字节，小端）。
     *   [0]      标志：bit0 有功率 bit1 有定位 bit2 有踏频 bit3 有海拔
     *                    bit4 正在记录 bit5 记录已暂停（仪表端据此回显暂停按钮）
     *   [1..2]   功率 W (u16)
     *   [3]      踏频 RPM (u8)
     *   [4]      电量 % (u8)
     *   [5..6]   速度 m/s * 100 (u16, 0xFFFF=无效)
     *   [7..10]  纬度 deg*1e6 (i32)   ← 必须 4 字节：3 字节(i24)装不下经度会溢出
     *   [11..14] 经度 deg*1e6 (i32)
     *   [15..16] MSL 海拔 米 (i16)
     *   [17]     定位质量 (0=无 1=单点 2=差分 4=RTK固定 5=RTK浮动)
     *   [18]     参与解算卫星数
     *   [19]     左腿平衡 * 2 (u8, 255=无效)
     *
     * 账目：厂商数据 20B + 公司ID 2B + 长度/类型 2B = 24B，加上 0x1818 的 2B，共 26B ≤ 31B 上限。
     */
    public static byte[] buildTelemetry(boolean hasPower, int powerW, int cadence, int batteryPct,
                                        boolean hasGps, double lat, double lon, double altMsl,
                                        float speedMps, float heading, int fixQuality, int satsUsed,
                                        double balanceLeftPct,
                                        boolean recRecording, boolean recPaused) {
        byte[] b = new byte[20];
        int flags = 0;
        if (hasPower) flags |= 1;
        if (hasGps) flags |= 2;
        if (cadence > 0) flags |= 4;
        // bit4/bit5：记录状态。仪表端（另一台手机）按下暂停键时没有别的回执通道，
        // 就靠这两个 bit 把中继的真实记录状态回显回去。
        if (recRecording) flags |= 0x10;
        if (recRecording && recPaused) flags |= 0x20;
        b[0] = (byte) flags;
        putU16(b, 1, Math.max(0, Math.min(65535, powerW)));
        b[3] = (byte) Math.max(0, Math.min(255, cadence));
        b[4] = (byte) Math.max(0, Math.min(100, batteryPct));
        if (hasGps && speedMps >= 0) {
            putU16(b, 5, Math.min(65534, Math.round(speedMps * 100f)));
        } else {
            putU16(b, 5, 0xFFFF);
        }
        putI32(b, 7, hasGps ? Math.round(lat * 1e6) : 0L);
        putI32(b, 11, hasGps ? Math.round(lon * 1e6) : 0L);
        putI16(b, 15, hasGps ? (int) Math.round(altMsl) : 0);
        b[17] = (byte) Math.max(0, Math.min(255, fixQuality));
        b[18] = (byte) Math.max(0, Math.min(255, satsUsed));
        b[19] = balanceLeftPct < 0 ? (byte) 0xFF
                : (byte) Math.max(0, Math.min(254, Math.round(balanceLeftPct * 2.0)));
        return b;
    }

    private static void putU16(byte[] b, int o, int v) {
        b[o] = (byte) (v & 0xFF);
        b[o + 1] = (byte) ((v >> 8) & 0xFF);
    }

    private static void putI16(byte[] b, int o, int v) {
        putU16(b, o, v & 0xFFFF);
    }

    private static void putI32(byte[] b, int o, long v) {
        b[o] = (byte) (v & 0xFF);
        b[o + 1] = (byte) ((v >> 8) & 0xFF);
        b[o + 2] = (byte) ((v >> 16) & 0xFF);
        b[o + 3] = (byte) ((v >> 24) & 0xFF);
    }

    @SuppressLint("MissingPermission")
    private boolean addServices() {
        if (server.getService(BleUuids.CPS_SERVICE) != null) return true;

        // ---------- 骑行功率服务 ----------
        BluetoothGattService cps = new BluetoothGattService(BleUuids.CPS_SERVICE,
                BluetoothGattService.SERVICE_TYPE_PRIMARY);

        cpsMeasurement = new BluetoothGattCharacteristic(BleUuids.CPS_MEASUREMENT,
                BluetoothGattCharacteristic.PROPERTY_NOTIFY | BluetoothGattCharacteristic.PROPERTY_READ,
                BluetoothGattCharacteristic.PERMISSION_READ);
        BluetoothGattDescriptor cpsCccd = new BluetoothGattDescriptor(BleUuids.CCCD,
                BluetoothGattDescriptor.PERMISSION_READ | BluetoothGattDescriptor.PERMISSION_WRITE);
        cpsCccd.setValue(BluetoothGattDescriptor.DISABLE_NOTIFICATION_VALUE);
        cpsMeasurement.addDescriptor(cpsCccd);
        cpsMeasurement.setValue(new byte[2]);
        cps.addCharacteristic(cpsMeasurement);

        cpsFeature = new BluetoothGattCharacteristic(BleUuids.CPS_FEATURE,
                BluetoothGattCharacteristic.PROPERTY_READ,
                BluetoothGattCharacteristic.PERMISSION_READ);
        cpsFeature.setValue(BleCodec.cpsFeature());
        cps.addCharacteristic(cpsFeature);

        sensorLocation = new BluetoothGattCharacteristic(BleUuids.CPS_SENSOR_LOCATION,
                BluetoothGattCharacteristic.PROPERTY_READ,
                BluetoothGattCharacteristic.PERMISSION_READ);
        sensorLocation.setValue(BleCodec.sensorLocation());
        cps.addCharacteristic(sensorLocation);

        // ---------- 定位与导航服务 ----------
        BluetoothGattService lns = new BluetoothGattService(BleUuids.LNS_SERVICE,
                BluetoothGattService.SERVICE_TYPE_PRIMARY);

        lnLocationSpeed = new BluetoothGattCharacteristic(BleUuids.LN_LOCATION_SPEED,
                BluetoothGattCharacteristic.PROPERTY_NOTIFY | BluetoothGattCharacteristic.PROPERTY_READ,
                BluetoothGattCharacteristic.PERMISSION_READ);
        BluetoothGattDescriptor lnCccd = new BluetoothGattDescriptor(BleUuids.CCCD,
                BluetoothGattDescriptor.PERMISSION_READ | BluetoothGattDescriptor.PERMISSION_WRITE);
        lnCccd.setValue(BluetoothGattDescriptor.DISABLE_NOTIFICATION_VALUE);
        lnLocationSpeed.addDescriptor(lnCccd);
        lnLocationSpeed.setValue(new byte[2]);
        lns.addCharacteristic(lnLocationSpeed);

        lnFeature = new BluetoothGattCharacteristic(BleUuids.LN_FEATURE,
                BluetoothGattCharacteristic.PROPERTY_READ,
                BluetoothGattCharacteristic.PERMISSION_READ);
        lnFeature.setValue(BleCodec.lnFeature());
        lns.addCharacteristic(lnFeature);

        // ---------- 电池服务 ----------
        BluetoothGattService bas = new BluetoothGattService(BleUuids.BATTERY_SERVICE,
                BluetoothGattService.SERVICE_TYPE_PRIMARY);
        batteryLevel = new BluetoothGattCharacteristic(BleUuids.BATTERY_LEVEL,
                BluetoothGattCharacteristic.PROPERTY_READ | BluetoothGattCharacteristic.PROPERTY_NOTIFY,
                BluetoothGattCharacteristic.PERMISSION_READ);
        BluetoothGattDescriptor batCccd = new BluetoothGattDescriptor(BleUuids.CCCD,
                BluetoothGattDescriptor.PERMISSION_READ | BluetoothGattDescriptor.PERMISSION_WRITE);
        batCccd.setValue(BluetoothGattDescriptor.DISABLE_NOTIFICATION_VALUE);
        batteryLevel.addDescriptor(batCccd);
        batteryLevel.setValue(new byte[]{(byte) lastBattery});
        bas.addCharacteristic(batteryLevel);

        // ---------- 设备信息服务 ----------
        BluetoothGattService dis = new BluetoothGattService(BleUuids.DIS_SERVICE,
                BluetoothGattService.SERVICE_TYPE_PRIMARY);
        dis.addCharacteristic(readOnly(BleUuids.DIS_MANUFACTURER, "XDS-Realy"));
        dis.addCharacteristic(readOnly(BleUuids.DIS_MODEL, "AD350 Relay"));
        dis.addCharacteristic(readOnly(BleUuids.DIS_FIRMWARE, "1.0"));

        boolean ok = true;
        ok &= server.addService(cps);
        ok &= server.addService(lns);
        ok &= server.addService(bas);
        ok &= server.addService(dis);
        return ok;
    }

    private static BluetoothGattCharacteristic readOnly(UUID uuid, String value) {
        BluetoothGattCharacteristic c = new BluetoothGattCharacteristic(uuid,
                BluetoothGattCharacteristic.PROPERTY_READ,
                BluetoothGattCharacteristic.PERMISSION_READ);
        c.setValue(BleCodec.utf8(value, 24));
        return c;
    }

    @SuppressLint("MissingPermission")
    public void stop() {
        try {
            if (advertiser != null) advertiser.stopAdvertising(advertiseCallback);
        } catch (Exception ignore) {}
        advertising = false;
        try {
            if (server != null) {
                server.clearServices();
                server.close();
            }
        } catch (Exception ignore) {}
        server = null;
        clients.clear();
        subscriptions.clear();
        notifyAdvertising(false, "已停止");
        notifyClients();
        log("转发已停止");
    }

    private final AdvertiseCallback advertiseCallback = new AdvertiseCallback() {
        @Override
        public void onStartSuccess(AdvertiseSettings settingsInEffect) {
            advertising = true;
            advertisingLive = true;
            String mode = settingsInEffect != null
                    ? ("mode=" + settingsInEffect.getMode() + " tx=" + settingsInEffect.getTxPowerLevel())
                    : "";
            notifyAdvertising(true, mode);
            log("广播已启动 " + mode);
        }

        @Override
        public void onStartFailure(int errorCode) {
            advertising = false;
            notifyAdvertising(false, "失败 " + advertiseError(errorCode));
            log("广播启动失败: " + advertiseError(errorCode) + " (" + errorCode + ")");
        }
    };

    private static String advertiseError(int code) {
        switch (code) {
            case AdvertiseCallback.ADVERTISE_FAILED_ALREADY_STARTED: return "已经启动过";
            case AdvertiseCallback.ADVERTISE_FAILED_DATA_TOO_LARGE: return "广播数据过长";
            case AdvertiseCallback.ADVERTISE_FAILED_FEATURE_UNSUPPORTED: return "本机不支持该特性";
            case AdvertiseCallback.ADVERTISE_FAILED_INTERNAL_ERROR: return "内部错误";
            case AdvertiseCallback.ADVERTISE_FAILED_TOO_MANY_ADVERTISERS: return "广播实例已用满";
            default: return "未知错误";
        }
    }

    // ================================================================
    //  数据下发
    // ================================================================

    public void publishPower(byte[] payload) {
        lastCps = payload;
        if (cpsMeasurement != null) cpsMeasurement.setValue(payload);
        notifyAll(cpsMeasurement, payload);
        cpsSent++;
        if (++publishTick % 20 == 0) {
            log("已推送 功率 " + cpsSent + " 包 / 定位 " + lnSent + " 包 · 客户端 "
                    + clients.size() + " 个 · 订阅中 " + subscriptions.size()
                    + " / 兼容模式 " + forced.size());
        }
    }

    public void publishLocation(byte[] payload) {
        lastLn = payload;
        if (lnLocationSpeed != null) lnLocationSpeed.setValue(payload);
        notifyAll(lnLocationSpeed, payload);
        lnSent++;
        if (lnSent <= 3 || lnSent % 30 == 0) {
            log("已推送 定位 " + lnSent + " 包 · 客户端 " + clients.size()
                    + " 个（订阅 " + subscriptions.size() + " / 兼容 " + forced.size() + "）");
        }
    }

    public void publishBattery(int percent) {
        lastBattery = Math.max(0, Math.min(100, percent));
        byte[] v = new byte[]{(byte) lastBattery};
        if (batteryLevel != null) batteryLevel.setValue(v);
        notifyAll(batteryLevel, v);
    }

    @SuppressLint("MissingPermission")
    private void notifyAll(BluetoothGattCharacteristic ch, byte[] value) {
        if (server == null || ch == null || clients.isEmpty()) return;
        for (Map.Entry<String, BluetoothDevice> e : clients.entrySet()) {
            String addr = e.getKey();
            Set<UUID> subs = subscriptions.get(addr);
            Set<UUID> forcedSubs = forced.get(addr);
            boolean ok = (subs != null && subs.contains(ch.getUuid()))
                    || (forcedSubs != null && forcedSubs.contains(ch.getUuid()));
            if (!ok) continue;
            try {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    int rc = server.notifyCharacteristicChanged(e.getValue(), ch, false, value);
                    // 200 = ERROR_GATT_WRITE_NOT_ALLOWED，通常就是对方 CCCD 没打开
                    if (rc != 0 && notifyErrLogged < 6) {
                        notifyErrLogged++;
                        log("⚠ 通知发送被拒，返回状态 " + rc + "（特征 " + shortUuid(ch.getUuid()) + "）");
                    }
                } else {
                    ch.setValue(value);
                    server.notifyCharacteristicChanged(e.getValue(), ch, false);
                }
            } catch (Exception ex) {
                // 单个客户端异常不影响其它客户端
            }
        }
    }

    /**
     * 客户端连上后如果一直没完成 CCCD 订阅（有些协议栈组合下 descriptor 写请求
     * 根本到不了服务端，实测过），就由我们主动替它把通知打开：
     * 本地记下"发给它"，同时把 CCCD 的值设成 01 00，然后照常推送。
     * 这样链路不依赖那次订阅握手，也不会影响真正正常订阅的第三方码表。
     */
    @SuppressLint("MissingPermission")
    private void armIfNotSubscribed(String addr) {
        if (server == null) return;
        if (!clients.containsKey(addr)) return;
        Set<UUID> subs = subscriptions.get(addr);
        if (subs != null && !subs.isEmpty()) return;         // 正常订阅了，不动
        if (forced.containsKey(addr)) return;

        Set<UUID> f = new LinkedHashSet<>();
        f.add(BleUuids.CPS_MEASUREMENT);
        f.add(BleUuids.LN_LOCATION_SPEED);
        f.add(BleUuids.BATTERY_LEVEL);
        forced.put(addr, f);
        armCccd(cpsMeasurement);
        armCccd(lnLocationSpeed);
        armCccd(batteryLevel);
        log("客户端 " + addr + " 3 秒内未完成订阅，主动为其打开通知推送（兼容模式）");
        if (listener != null) listener.onClients(new ArrayList<>(clients.keySet()));
        notifyClients();
    }

    private void armCccd(BluetoothGattCharacteristic ch) {
        if (ch == null) return;
        BluetoothGattDescriptor d = ch.getDescriptor(BleUuids.CCCD);
        if (d != null) d.setValue(new byte[]{0x01, 0x00});
    }

    @SuppressLint("MissingPermission")
    private void sendCurrentTo(BluetoothDevice device, String address, UUID charUuid) {
        if (server == null) return;
        BluetoothGattCharacteristic target = null;
        byte[] value = null;
        if (cpsMeasurement != null && cpsMeasurement.getUuid().equals(charUuid)) {
            target = cpsMeasurement;
            value = lastCps.length > 0 ? lastCps : new byte[]{0x00, 0x00, 0x00, 0x00};
        } else if (lnLocationSpeed != null && lnLocationSpeed.getUuid().equals(charUuid)) {
            target = lnLocationSpeed;
            value = lastLn.length > 0 ? lastLn : new byte[]{0x00, 0x00, 0x00, 0x00};
        } else if (batteryLevel != null && batteryLevel.getUuid().equals(charUuid)) {
            target = batteryLevel;
            value = new byte[]{(byte) lastBattery};
        }
        if (target == null || value == null) return;
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                server.notifyCharacteristicChanged(device, target, false, value);
            } else {
                target.setValue(value);
                server.notifyCharacteristicChanged(device, target, false);
            }
        } catch (Exception ignore) {}
    }

    // ================================================================
    //  GATT Server 回调
    // ================================================================

    private final BluetoothGattServerCallback serverCallback = new BluetoothGattServerCallback() {

        @SuppressLint("MissingPermission")
        @Override
        public void onConnectionStateChange(BluetoothDevice device, int status, int newState) {
            try {
                android.util.Log.i("XdsSrv", "CB onConnectionStateChange newState=" + newState
                        + " status=" + status + " dev=" + (device == null ? "?" : device.getAddress()));
            } catch (Throwable ignore) {}
            String addr = device != null ? device.getAddress() : "?";
            if (newState == BluetoothProfile.STATE_CONNECTED) {
                if (!advertisingLive) {
                    // 本机还没真正开始广播就收到连接 = 上一次运行留下的残链。
                    // 杀掉 App 并不会断开蓝牙链路，链路上的 ATT 状态和新开的 GATT Server 对不上，
                    // 表现就是客户端订阅 CCCD 时"写请求发出去了但永远没有回音"。
                    // 主动踢掉，让客户端重新连一次。
                    log("收到残留连接（本机尚未广播）" + addr + "，主动断开让客户端重连");
                    try {
                        server.cancelConnection(device);
                    } catch (Exception e) {
                        log("踢掉残留连接失败: " + e);
                    }
                    return;
                }
                clients.put(addr, device);
                subscriptions.put(addr, new LinkedHashSet<>());
                forced.remove(addr);
                log("客户端接入 " + name(device) + "  " + addr);
                // 3 秒后检查它有没有真的完成订阅；没有就主动替它打开
                handler.postDelayed(() -> armIfNotSubscribed(addr), 3000L);
            } else if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                clients.remove(addr);
                subscriptions.remove(addr);
                forced.remove(addr);
                log("客户端断开 " + name(device) + "  " + addr);
            }
            notifyClients();
        }

        @Override
        public void onMtuChanged(BluetoothDevice device, int mtu) {
            try {
                android.util.Log.i("XdsSrv", "CB onMtuChanged mtu=" + mtu);
            } catch (Throwable ignore) {}
            lastMtu = mtu;
            log(name(device) + " 协商 MTU=" + mtu + "（单包最大 " + (mtu - 3) + " 字节）");
        }

        @Override
        public void onCharacteristicReadRequest(BluetoothDevice device, int requestId, int offset,
                                               BluetoothGattCharacteristic characteristic) {
            try {
                android.util.Log.i("XdsSrv", "CB onCharacteristicReadRequest "
                        + characteristic.getUuid() + " offset=" + offset);
            } catch (Throwable ignore) {}
            byte[] value = characteristic.getValue();
            if (value == null) value = new byte[0];
            byte[] slice;
            if (offset >= value.length) {
                slice = new byte[0];
            } else {
                slice = new byte[value.length - offset];
                System.arraycopy(value, offset, slice, 0, slice.length);
            }
            if (server != null) {
                server.sendResponse(device, requestId, BluetoothGatt.GATT_SUCCESS, offset, slice);
            }
        }

        @Override
        public void onCharacteristicWriteRequest(BluetoothDevice device, int requestId,
                                                BluetoothGattCharacteristic characteristic,
                                                boolean preparedWrite, boolean responseNeeded,
                                                int offset, byte[] value) {
            try {
                android.util.Log.i("XdsSrv", "CB onCharacteristicWriteRequest "
                        + characteristic.getUuid() + " len=" + (value == null ? 0 : value.length));
            } catch (Throwable ignore) {}
            // 标准 CPS 里客户端可以写控制点；这里只保证响应，不改变输出
            log("收到写入 " + shortUuid(characteristic.getUuid()) + " len="
                    + (value == null ? 0 : value.length) + " 来自 " + name(device));
            if (responseNeeded && server != null) {
                server.sendResponse(device, requestId, BluetoothGatt.GATT_SUCCESS, offset, null);
            }
        }

        @Override
        public void onDescriptorReadRequest(BluetoothDevice device, int requestId, int offset,
                                           BluetoothGattDescriptor descriptor) {
            try {
                android.util.Log.i("XdsSrv", "CB onDescriptorReadRequest "
                        + descriptor.getCharacteristic().getUuid());
            } catch (Throwable ignore) {}
            byte[] value = descriptor.getValue();
            if (value == null) value = new byte[]{0, 0};
            if (server != null) {
                server.sendResponse(device, requestId, BluetoothGatt.GATT_SUCCESS, offset, value);
            }
        }

        @Override
        public void onDescriptorWriteRequest(BluetoothDevice device, int requestId,
                                             BluetoothGattDescriptor descriptor,
                                             boolean preparedWrite, boolean responseNeeded,
                                             int offset, byte[] value) {
            try {
                android.util.Log.i("XdsSrv", "CB onDescriptorWriteRequest "
                        + descriptor.getCharacteristic().getUuid()
                        + " value=" + (value == null ? "null" : bytesToHex(value))
                        + " responseNeeded=" + responseNeeded);
            } catch (Throwable ignore) {}
            UUID chUuid = descriptor.getCharacteristic().getUuid();
            String addr = device.getAddress();
            boolean enable = value != null && value.length > 0 && value[0] == 0x01;
            Set<UUID> subs = subscriptions.get(addr);
            if (subs == null) {
                subs = new LinkedHashSet<>();
                subscriptions.put(addr, subs);
            }

            if (enable) {
                subs.add(chUuid);
                log(name(device) + " 订阅了 " + shortUuid(chUuid) + "，稍后补发一次当前值");
            } else {
                subs.remove(chUuid);
                log(name(device) + " 取消了 " + shortUuid(chUuid));
            }
            descriptor.setValue(value);

            // 必须先回响应，再发通知：在 descriptor 写请求里先 notify 会打乱 ATT 时序，
            // 客户端的 onDescriptorWrite 回调可能就一直不回来（实测踩过）。
            if (responseNeeded && server != null) {
                server.sendResponse(device, requestId, BluetoothGatt.GATT_SUCCESS, offset, null);
            }

            if (enable) {
                final BluetoothDevice dev = device;
                final UUID uuid = chUuid;
                handler.postDelayed(() -> {
                    sendCurrentTo(dev, addr, uuid);
                    if (listener != null) listener.onSubscribed(uuid);
                }, 250L);
            }
        }
    };

    // ================================================================

    private void notifyAdvertising(boolean running, String detail) {
        if (listener != null) listener.onAdvertising(running, detail);
    }

    private void notifyClients() {
        if (listener != null) listener.onClients(clientNames());
    }

    private void log(String msg) {
        Logs.add(TAG, msg);
    }

    @SuppressLint("MissingPermission")
    private static String name(BluetoothDevice d) {
        if (d == null) return "(未知)";
        try {
            String n = d.getName();
            return n == null || n.isEmpty() ? "(无名)" : n;
        } catch (Exception e) {
            return "(无名)";
        }
    }

    private static String bytesToHex(byte[] b) {
        if (b == null) return "null";
        StringBuilder sb = new StringBuilder(b.length * 2);
        for (byte x : b) sb.append(String.format("%02x", x));
        return sb.toString();
    }

    private static String shortUuid(UUID u) {
        String s = u.toString();
        if (s.startsWith("0000") && s.endsWith("-0000-1000-8000-00805f9b34fb")) {
            return "0x" + s.substring(4, 8).toUpperCase(java.util.Locale.ROOT);
        }
        return s;
    }
}
