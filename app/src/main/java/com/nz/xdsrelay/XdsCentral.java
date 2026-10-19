package com.nz.xdsrelay;

import android.Manifest;
import android.annotation.SuppressLint;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothGatt;
import android.bluetooth.BluetoothGattCallback;
import android.bluetooth.BluetoothGattCharacteristic;
import android.bluetooth.BluetoothGattDescriptor;
import android.bluetooth.BluetoothGattService;
import android.bluetooth.BluetoothManager;
import android.bluetooth.BluetoothProfile;
import android.bluetooth.le.BluetoothLeScanner;
import android.bluetooth.le.ScanCallback;
import android.bluetooth.le.ScanFilter;
import android.bluetooth.le.ScanRecord;
import android.bluetooth.le.ScanResult;
import android.bluetooth.le.ScanSettings;
import android.content.Context;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.os.ParcelUuid;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

/**
 * BLE 中央设备：扫描并连接喜德盛 AD350 爪盘功率计，读取私有功率数据。
 *
 * 流程与参考固件 xds_ble_bridge.c 完全对齐：
 *   扫描(按广播里的 0x1828) → 连接 → 发现服务 0x1828 → 找到 0x2A63(notify)+0x2A55(write)
 *   → 打开 0x2A63 的 CCCD → 800ms 后向 0x2A55 写 02 16 AA 10 → 之后每 2000ms 重发，最多 5 次
 *   → 解析 0x2A63 通知；30 秒无数据则断开重连。
 */
public class XdsCentral {

    private static final String TAG = "XDS-C";

    public enum State {
        STOPPED, SCANNING, CONNECTING, DISCOVERING, SUBSCRIBING, READY, WAIT_RETRY, ERROR
    }

    public interface Listener {
        void onState(State state, String detail);
        void onMeasurement(XdsParser.Measurement m);
        void onCandidate(String address, String name, int rssi, boolean isXds);
    }

    /** 与固件一致的时序常量 */
    private static final long START_CMD_DELAY_MS   = 800L;
    private static final long START_CMD_RETRY_MS   = 2000L;
    private static final int  START_CMD_MAX_TRIES  = 5;
    private static final long DATA_TIMEOUT_MS      = 30000L;
    private static final long RECONNECT_DELAY_MS   = 1500L;

    private final Context ctx;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private Listener listener;

    private BluetoothAdapter adapter;
    private BluetoothLeScanner scanner;
    private BluetoothGatt gatt;
    private BluetoothDevice device;

    private State state = State.STOPPED;
    private String deviceLabel = "";
    private String deviceAddress = "";

    private BluetoothGattCharacteristic charMeasurement;
    private BluetoothGattCharacteristic charControl;
    private int startCommandTries = 0;
    private long lastPacketUptime = 0L;
    private boolean pendingStartCommand;

    private boolean running;
    private boolean manualConnect;
    private boolean simulate;

    /**
     * 界面是否在前台。无过滤器的扫描在 App 处于后台时会被系统限流到几分钟才回调一次，
     * 而中转手机通常放在口袋里，所以后台时改用「按服务 0x1828 过滤」的扫描
     * （带过滤器的扫描在后台仍能持续回调），回到前台再用无过滤器扫描以获得最大灵敏度。
     */
    private boolean foregroundMode = true;

    /**
     * 有客户端（显示终端）连着的时候，把扫描强度降下来：
     * 低延迟扫描会长时间占射频，和中转广播/GATT 服务抢资源，容易把连接搞断（实测 status=133）。
     */
    private boolean clientConnected;

    public void setClientConnected(boolean connected) {
        if (clientConnected == connected) return;
        clientConnected = connected;
        log("客户端" + (connected ? "已连接，扫描降为 BALANCED 模式" : "已断开，扫描恢复 LOW_LATENCY"));
        if (running && !simulate && state == State.SCANNING) {
            stopScan();
            handler.postDelayed(this::startScan, 400L);
        }
    }

    public void setForegroundMode(boolean foreground) {
        if (foregroundMode == foreground) return;
        foregroundMode = foreground;
        log("界面" + (foreground ? "回到前台" : "进入后台") + "，扫描模式切换为"
                + (foreground ? "全量扫描" : "带 0x1828 过滤的扫描"));
        if (running && !simulate && state == State.SCANNING) {
            stopScan();
            handler.postDelayed(this::startScan, 300L);
        }
    }

    // 模拟数据
    private long simStart;
    private Runnable simTask;

    private final Map<String, BluetoothDevice> candidates = new LinkedHashMap<>();

    private long packetsTotal;
    private long bytesTotal;
    private long packetsDropped;

    public XdsCentral(Context ctx) {
        this.ctx = ctx.getApplicationContext();
        BluetoothManager bm = (BluetoothManager) this.ctx.getSystemService(Context.BLUETOOTH_SERVICE);
        adapter = bm != null ? bm.getAdapter() : BluetoothAdapter.getDefaultAdapter();
        if (adapter != null) scanner = adapter.getBluetoothLeScanner();
    }

    public void setListener(Listener l) {
        this.listener = l;
    }

    public State getState() { return state; }
    public String getDeviceLabel() { return deviceLabel; }
    public String getDeviceAddress() { return deviceAddress; }
    public long getPacketsTotal() { return packetsTotal; }
    public long getBytesTotal() { return bytesTotal; }
    public long getPacketsDropped() { return packetsDropped; }
    public boolean isRunning() { return running; }
    public boolean isConnected() { return gatt != null && state == State.READY; }

    public List<String> candidateList() {
        List<String> out = new ArrayList<>();
        for (Map.Entry<String, BluetoothDevice> e : candidates.entrySet()) {
            BluetoothDevice d = e.getValue();
            String name = safeName(d);
            out.add((name.isEmpty() ? "(无名)" : name) + "  " + e.getKey());
        }
        return out;
    }

    public List<BluetoothDevice> candidateDevices() {
        return new ArrayList<>(candidates.values());
    }

    // ================================================================
    //  生命周期
    // ================================================================

    public boolean start() {
        if (running && !simulate) return true;
        if (adapter == null) {
            log("本机没有蓝牙适配器");
            setState(State.ERROR, "无蓝牙");
            return false;
        }
        if (!adapter.isEnabled()) {
            log("蓝牙未开启");
            setState(State.ERROR, "蓝牙未开启");
            return false;
        }
        running = true;
        simulate = false;
        manualConnect = false;
        candidates.clear();
        clearDelegate();
        startScan();
        return true;
    }

    public void stop() {
        running = false;
        simulate = false;
        stopSimulation();
        stopScan();
        cancelStartCommand();
        handler.removeCallbacks(connectHint);
        disconnect();
        // 立刻 close()：只调 disconnect() 要等回调才真正释放，
        // 万一进程随后被杀，功率计那边会一直挂着一条死链路（它就不肯重新广播了）。
        closeGatt();
        setState(State.STOPPED, "已停止");
        log("已停止。已彻底释放蓝牙连接；如要改用官方 App，等 10 秒左右再连。");
    }

    public void restartScan() {
        if (!running) return;
        cancelStartCommand();
        disconnect();
        stopScan();
        handler.removeCallbacksAndMessages(null);
        startScan();
    }

    public void connectTo(BluetoothDevice d) {
        if (d == null) return;
        running = true;
        simulate = false;
        manualConnect = true;
        stopScan();
        cancelStartCommand();
        disconnect();
        log("手动连接 " + safeName(d) + "  " + d.getAddress());
        connect(d);
    }

    // ================================================================
    //  模拟数据（没有功率计时也能验证转发链路与码表读取）
    // ================================================================

    public void startSimulation() {
        stop();
        running = true;
        simulate = true;
        setState(State.READY, "模拟数据");
        log("进入模拟模式：按 60rpm / 0-400W 三角波输出");
        simStart = System.currentTimeMillis();
        simTask = new Runnable() {
            @Override
            public void run() {
                if (!simulate) return;
                double t = (System.currentTimeMillis() - simStart) / 1000.0;
                double wave = (t % 12.0) / 12.0;                 // 0..1
                double tri = wave < 0.5 ? wave * 2 : (1 - wave) * 2;
                int power = (int) Math.round(60 + tri * 340);
                int cadence = (int) Math.round(70 + 25 * Math.sin(t * 0.7));
                XdsParser.Measurement m = new XdsParser.Measurement();
                m.totalPowerW = power;
                m.leftPowerW = (int) Math.round(power * 0.48);
                m.rightPowerW = power - m.leftPowerW;
                m.hasLeft = m.hasRight = true;
                m.cadenceRpm = cadence;
                m.hasCadence = true;
                m.angleDeg = (int) ((t * 180) % 360);
                m.hasAngle = true;
                m.errorCode = 0;
                m.hasError = true;
                m.payloadLength = 11;
                m.timestampMs = System.currentTimeMillis();
                lastPacketUptime = android.os.SystemClock.elapsedRealtime();
                dispatch(m);
                deviceLabel = "模拟";
                handler.postDelayed(this, 250L);
            }
        };
        handler.post(simTask);
    }

    public boolean isSimulating() { return simulate; }

    private void stopSimulation() {
        simulate = false;
        if (simTask != null) {
            handler.removeCallbacks(simTask);
            simTask = null;
        }
    }

    // ================================================================
    //  扫描
    // ================================================================

    private int connectHints;

    /**
     * 一直连不上功率计时，给点人话提示。
     * AD350 这类爪盘功率计有两个很坑的特性：
     *   1) 静止时基本不广播，要转动曲柄把它唤醒；
     *   2) 同一时刻只接受一个连接——如果上一次是被强制杀掉（没走断开流程），
     *      它会一直握着那条已经死掉的链路，直到自己的超时时间到了才肯重新广播。
     */
    private void scheduleConnectHint() {
        handler.removeCallbacks(connectHint);
        handler.postDelayed(connectHint, 45000L);
    }

    private final Runnable connectHint = () -> {
        if (!running || simulate) return;
        if (state == State.READY || state == State.SUBSCRIBING
                || state == State.DISCOVERING || state == State.CONNECTING) return;
        if (connectHints >= 3) return;
        connectHints++;
        log("⚠ 还没连上功率计？按顺序试："
                + "① 转动曲柄转几圈唤醒它（静止时它不广播）"
                + "② 关掉喜德盛官方 App 和其它蓝牙 App（它同时只允许一个连接）"
                + "③ 手机蓝牙关闭再打开，清掉残留链路"
                + "｜本机要停就点「停止」，别强杀进程（强杀会留死链路，功率计就不再广播）");
    };

    @SuppressLint("MissingPermission")
    private void startScan() {
        if (!running) return;
        if (!hasScanPermission()) {
            log("缺少蓝牙扫描权限");
            setState(State.ERROR, "缺少权限");
            return;
        }
        if (scanner == null) {
            scanner = adapter.getBluetoothLeScanner();
        }
        if (scanner == null) {
            log("扫描器不可用（蓝牙可能正在关闭）");
            setState(State.ERROR, "扫描器不可用");
            return;
        }

        scheduleConnectHint();

        ScanSettings settings = new ScanSettings.Builder()
                .setScanMode(clientConnected
                        ? ScanSettings.SCAN_MODE_BALANCED
                        : ScanSettings.SCAN_MODE_LOW_LATENCY)
                .setReportDelay(0)
                .build();

        List<ScanFilter> filters = null;
        if (!foregroundMode) {
            filters = new ArrayList<>();
            filters.add(new ScanFilter.Builder()
                    .setServiceUuid(new ParcelUuid(BleUuids.XDS_SERVICE))
                    .build());
        }

        try {
            scanner.startScan(filters, settings, scanCallback);
            setState(State.SCANNING, manualConnect ? "扫描中（手动）" : "扫描中");
            log("开始扫描" + (filters == null ? "（全量）" : "（过滤 0x1828）")
                    + "，等待广播里带 0x1828 的功率计…");
        } catch (SecurityException e) {
            log("扫描被拒绝: " + e.getMessage());
            setState(State.ERROR, "扫描被拒绝");
        } catch (Exception e) {
            log("扫描失败: " + e);
            setState(State.ERROR, "扫描失败");
        }
    }

    @SuppressLint("MissingPermission")
    private void stopScan() {
        if (scanner == null) return;
        try {
            scanner.stopScan(scanCallback);
        } catch (Exception ignore) {
        }
    }

    private final ScanCallback scanCallback = new ScanCallback() {
        @Override
        public void onScanResult(int callbackType, ScanResult result) {
            handleResult(result);
        }

        @Override
        public void onBatchScanResults(List<ScanResult> results) {
            for (ScanResult r : results) handleResult(r);
        }

        @Override
        public void onScanFailed(int errorCode) {
            log("扫描失败 errorCode=" + errorCode);
            setState(State.ERROR, "扫描失败 " + errorCode);
        }
    };

    @SuppressLint("MissingPermission")
    private void handleResult(ScanResult result) {
        if (result == null || result.getDevice() == null) return;
        BluetoothDevice d = result.getDevice();
        ScanRecord rec = result.getScanRecord();
        String name = rec != null && rec.getDeviceName() != null
                ? rec.getDeviceName()
                : safeName(d);
        if (name == null) name = "";

        boolean isXds = advHasService(rec, d, BleUuids.XDS_SERVICE_SHORT)
                || name.toUpperCase(Locale.ROOT).contains("AD350")
                || name.toUpperCase(Locale.ROOT).startsWith("XDS");

        boolean isNew = !candidates.containsKey(d.getAddress());
        candidates.put(d.getAddress(), d);
        if (isNew) {
            log("发现设备 " + (name.isEmpty() ? "(无名)" : name) + "  " + d.getAddress()
                    + "  RSSI=" + result.getRssi() + (isXds ? "  ★疑似功率计" : ""));
        }
        if (listener != null) {
            listener.onCandidate(d.getAddress(), name, result.getRssi(), isXds);
        }

        if (!manualConnect && isXds && state == State.SCANNING) {
            log("命中功率计，停止扫描并连接");
            stopScan();
            connect(d);
        }
    }

    /** 手工解析广播内容，等价于固件里的 xds_adv_has_service()，避免依赖系统解析。 */
    private boolean advHasService(ScanRecord rec, BluetoothDevice d, int shortUuid) {
        if (rec != null) {
            List<ParcelUuid> uuids = rec.getServiceUuids();
            if (uuids != null) {
                for (ParcelUuid pu : uuids) {
                    UUID u = pu.getUuid();
                    // 16 位 UUID 展开形如 0000xxxx-0000-1000-8000-00805f9b34fb
                    if (u.getLeastSignificantBits() == 0x800000805F9B34FBL
                            && (int) ((u.getMostSignificantBits() >>> 32) & 0xFFFF) == shortUuid) {
                        return true;
                    }
                }
            }
            byte[] raw = rec.getBytes();
            if (raw != null && advRawHasService(raw, shortUuid)) return true;
        }
        return false;
    }

    /** 与固件 xds_adv_has_service 相同：遍历 AD 结构，匹配 16 位服务 UUID 列表。 */
    private static boolean advRawHasService(byte[] data, int shortUuid) {
        int index = 0;
        int length = data.length;
        while (index < length) {
            int fieldLength = data[index] & 0xFF;
            if (fieldLength == 0) return false;
            if (index + fieldLength >= length) return false;
            int type = data[index + 1] & 0xFF;
            // 0x02 = 不完整 16 位服务列表, 0x03 = 完整 16 位服务列表
            if (type == 0x02 || type == 0x03) {
                int vi = index + 2;
                int ve = index + fieldLength + 1;
                while (vi + 1 < ve) {
                    int found = (data[vi] & 0xFF) | ((data[vi + 1] & 0xFF) << 8);
                    if (found == shortUuid) return true;
                    vi += 2;
                }
            }
            index += fieldLength + 1;
        }
        return false;
    }

    // ================================================================
    //  连接 / 发现
    // ================================================================

    @SuppressLint("MissingPermission")
    private void connect(BluetoothDevice d) {
        device = d;
        deviceLabel = safeName(d);
        deviceAddress = d.getAddress();
        setState(State.CONNECTING, "连接中 " + deviceLabel);
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                gatt = d.connectGatt(ctx, false, gattCallback, BluetoothDevice.TRANSPORT_LE);
            } else {
                gatt = d.connectGatt(ctx, false, gattCallback);
            }
        } catch (SecurityException e) {
            log("连接被拒绝: " + e.getMessage());
            setState(State.ERROR, "连接被拒绝");
            return;
        } catch (Exception e) {
            log("连接失败: " + e);
            setState(State.ERROR, "连接失败");
            return;
        }
        if (gatt == null) {
            log("connectGatt 返回 null（蓝牙可能已关闭）");
            setState(State.ERROR, "connectGatt 失败");
        }
    }

    private final BluetoothGattCallback gattCallback = new BluetoothGattCallback() {

        @SuppressLint("MissingPermission")
        @Override
        public void onConnectionStateChange(BluetoothGatt g, int status, int newState) {
            if (newState == BluetoothProfile.STATE_CONNECTED && status == BluetoothGatt.GATT_SUCCESS) {
                log("已连接，开始发现服务");
                setState(State.DISCOVERING, "发现服务");
                try {
                    g.requestConnectionPriority(BluetoothGatt.CONNECTION_PRIORITY_HIGH);
                } catch (Exception ignore) {}
                try {
                    g.discoverServices();
                } catch (SecurityException e) {
                    log("discoverServices 被拒绝: " + e.getMessage());
                }
            } else if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                log("连接断开 status=" + status);
                lastPacketUptime = 0L;
                charMeasurement = null;
                charControl = null;
                cancelStartCommand();
                closeGatt();
                if (running && !simulate) {
                    setState(State.WAIT_RETRY, "断开，稍后重连");
                    handler.postDelayed(() -> {
                        if (running && !simulate && state == State.WAIT_RETRY) startScan();
                    }, RECONNECT_DELAY_MS);
                }
            }
        }

        @SuppressLint("MissingPermission")
        @Override
        public void onServicesDiscovered(BluetoothGatt g, int status) {
            if (status != BluetoothGatt.GATT_SUCCESS) {
                log("服务发现失败 status=" + status);
                scheduleReconnect("服务发现失败");
                return;
            }

            BluetoothGattCharacteristic measurement = null;
            BluetoothGattCharacteristic control = null;

            // 首选：服务 0x1828 内部
            BluetoothGattService svc = g.getService(BleUuids.XDS_SERVICE);
            if (svc != null) {
                log("找到私有服务 0x1828");
                measurement = svc.getCharacteristic(BleUuids.XDS_MEASUREMENT);
                control = svc.getCharacteristic(BleUuids.XDS_CONTROL_POINT);
            } else {
                log("未找到 0x1828，退回全局搜索 0x2A63 / 0x2A55");
            }

            // 兜底：全局搜索
            if (measurement == null || control == null) {
                for (BluetoothGattService s : g.getServices()) {
                    for (BluetoothGattCharacteristic c : s.getCharacteristics()) {
                        UUID u = c.getUuid();
                        if (measurement == null && BleUuids.XDS_MEASUREMENT.equals(u)) measurement = c;
                        if (control == null && BleUuids.XDS_CONTROL_POINT.equals(u)) control = c;
                    }
                }
            }

            log("GATT 清单: " + describeServices(g));

            if (measurement == null) {
                log("没找到测量特征 0x2A63，无法读取功率");
                scheduleReconnect("缺少 0x2A63");
                return;
            }

            charMeasurement = measurement;
            charControl = control;
            if (control == null) {
                log("警告：没有 0x2A55 控制特征，将只订阅不发送启动命令");
            }

            setState(State.SUBSCRIBING, "打开通知");
            boolean ok = false;
            try {
                ok = g.setCharacteristicNotification(measurement, true);
            } catch (SecurityException e) {
                log("setCharacteristicNotification 被拒绝: " + e.getMessage());
            }
            if (!ok) log("setCharacteristicNotification 返回 false");

            BluetoothGattDescriptor cccd = measurement.getDescriptor(BleUuids.CCCD);
            if (cccd == null) {
                log("0x2A63 没有 CCCD 描述符，无法订阅");
                scheduleReconnect("缺少 CCCD");
                return;
            }
            try {
                cccd.setValue(BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE);
                boolean w = g.writeDescriptor(cccd);
                log("写 CCCD: " + (w ? "已提交" : "失败"));
                if (!w) scheduleReconnect("CCCD 写入失败");
            } catch (SecurityException e) {
                log("writeDescriptor 被拒绝: " + e.getMessage());
            }
        }

        @Override
        public void onDescriptorWrite(BluetoothGatt g, BluetoothGattDescriptor descriptor, int status) {
            if (!BleUuids.CCCD.equals(descriptor.getUuid())) return;
            if (status != BluetoothGatt.GATT_SUCCESS) {
                log("CCCD 写入失败 status=" + status);
                scheduleReconnect("CCCD 写入失败");
                return;
            }
            log("通知已打开，800ms 后下发启动命令 " + XdsParser.hex(BleUuids.XDS_START_COMMAND, 0, 4));
            pendingStartCommand = true;
            startCommandTries = 0;
            handler.postDelayed(startCommandTask, START_CMD_DELAY_MS);
            scheduleDataWatchdog();
        }

        @SuppressLint("MissingPermission")
        @Override
        public void onCharacteristicWrite(BluetoothGatt g, BluetoothGattCharacteristic c, int status) {
            if (charControl != null && charControl.getUuid().equals(c.getUuid())) {
                if (status == BluetoothGatt.GATT_SUCCESS) {
                    log("启动命令已写入 (第 " + startCommandTries + " 次)");
                    pendingStartCommand = false;
                } else {
                    log("启动命令写入失败 status=" + status + "，将重试");
                }
            }
        }

        @Override
        public void onCharacteristicChanged(BluetoothGatt g, BluetoothGattCharacteristic c) {
            onNotify(c.getUuid(), c.getValue());
        }

        @Override
        public void onCharacteristicChanged(BluetoothGatt g, BluetoothGattCharacteristic c, byte[] value) {
            onNotify(c.getUuid(), value);
        }
    };

    private void onNotify(UUID uuid, byte[] value) {
        if (value == null) return;
        if (charMeasurement != null && !charMeasurement.getUuid().equals(uuid)) return;

        lastPacketUptime = android.os.SystemClock.elapsedRealtime();
        packetsTotal++;
        bytesTotal += value.length;

        XdsParser.Measurement m = new XdsParser.Measurement();
        if (XdsParser.parse(value, 0, value.length, m)) {
            // 诊断用：前几包 + 之后每 50 包打印一次原始负载
            if (packetsTotal <= 3 || packetsTotal % 50 == 0) {
                log("负载 len=" + value.length + " [" + XdsParser.hex(value, 0, value.length) + "]"
                        + "  总功率=" + m.totalPowerW + "W"
                        + (m.hasLeft ? " 左=" + m.leftPowerW + "W" : "")
                        + (m.hasRight ? " 右=" + m.rightPowerW + "W" : "")
                        + (m.hasCadence ? " 踏频=" + m.cadenceRpm : "")
                        + (m.hasAngle ? " 角度=" + m.angleDeg : "")
                        + (m.hasError ? " 错误码=" + m.errorCode : ""));
            }
            if (state != State.READY) setState(State.READY, "已就绪");
            dispatch(m);
        } else {
            packetsDropped++;
        }
    }

    private void dispatch(XdsParser.Measurement m) {
        Listener l = listener;
        if (l != null) l.onMeasurement(m);
    }

    // ================================================================
    //  启动命令 / 看门狗
    // ================================================================

    private final Runnable startCommandTask = new Runnable() {
        @SuppressLint("MissingPermission")
        @Override
        public void run() {
            if (!running || gatt == null || charControl == null) return;
            if (state != State.READY && state != State.SUBSCRIBING) {
                // 还没就绪但通知已打开，也允许发命令
            }
            if (startCommandTries >= START_CMD_MAX_TRIES) {
                log("启动命令已重试 " + START_CMD_MAX_TRIES + " 次，停止重发");
                return;
            }
            startCommandTries++;
            try {
                charControl.setWriteType(BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT);
                charControl.setValue(BleUuids.XDS_START_COMMAND);
                boolean ok = gatt.writeCharacteristic(charControl);
                if (!ok) log("writeCharacteristic 提交失败（第 " + startCommandTries + " 次）");
            } catch (SecurityException e) {
                log("写启动命令被拒绝: " + e.getMessage());
                return;
            } catch (Exception e) {
                log("写启动命令异常: " + e);
                return;
            }
            handler.postDelayed(this, START_CMD_RETRY_MS);
        }
    };

    private void cancelStartCommand() {
        pendingStartCommand = false;
        startCommandTries = 0;
        handler.removeCallbacks(startCommandTask);
    }

    private final Runnable watchdog = new Runnable() {
        @Override
        public void run() {
            if (!running || simulate) return;
            long now = android.os.SystemClock.elapsedRealtime();
            if (lastPacketUptime != 0L && now - lastPacketUptime > DATA_TIMEOUT_MS) {
                log("超过 " + (DATA_TIMEOUT_MS / 1000) + " 秒没有功率数据，断开重连");
                lastPacketUptime = 0L;
                cancelStartCommand();
                disconnect();
                handler.postDelayed(() -> {
                    if (running && !simulate) startScan();
                }, RECONNECT_DELAY_MS);
                return;
            }
            handler.postDelayed(this, 5000L);
        }
    };

    private void scheduleDataWatchdog() {
        handler.removeCallbacks(watchdog);
        handler.postDelayed(watchdog, 10000L);
    }

    private void scheduleReconnect(String why) {
        log("重连原因：" + why);
        lastPacketUptime = 0L;
        cancelStartCommand();
        disconnect();
        if (running && !simulate) {
            setState(State.WAIT_RETRY, "重连中");
            handler.postDelayed(() -> {
                if (running && !simulate) startScan();
            }, RECONNECT_DELAY_MS);
        }
    }

    @SuppressLint("MissingPermission")
    private void disconnect() {
        if (gatt == null) return;
        try {
            gatt.disconnect();
        } catch (Exception ignore) {
        }
    }

    @SuppressLint("MissingPermission")
    private void closeGatt() {
        if (gatt == null) return;
        try {
            gatt.close();
        } catch (Exception ignore) {
        }
        gatt = null;
    }

    private void clearDelegate() {
        handler.removeCallbacksAndMessages(null);
        cancelStartCommand();
        closeGatt();
        charMeasurement = null;
        charControl = null;
        lastPacketUptime = 0L;
    }

    private void setState(State s, String detail) {
        state = s;
        Listener l = listener;
        if (l != null) l.onState(s, detail);
    }

    private void log(String msg) {
        Logs.add(TAG, msg);
    }

    @SuppressLint("MissingPermission")
    private String safeName(BluetoothDevice d) {
        try {
            String n = d.getName();
            return n == null ? "" : n;
        } catch (SecurityException e) {
            return "";
        }
    }

    private String describeServices(BluetoothGatt g) {
        StringBuilder sb = new StringBuilder();
        try {
            for (BluetoothGattService s : g.getServices()) {
                sb.append(shortUuid(s.getUuid())).append('[');
                for (BluetoothGattCharacteristic c : s.getCharacteristics()) {
                    sb.append(shortUuid(c.getUuid())).append(' ');
                }
                sb.append("] ");
            }
        } catch (Exception e) {
            return "(读取失败)";
        }
        return sb.toString().trim();
    }

    private static String shortUuid(UUID u) {
        String s = u.toString();
        if (s.startsWith("0000") && s.endsWith("-0000-1000-8000-00805f9b34fb")) {
            return "0x" + s.substring(4, 8).toUpperCase(Locale.ROOT);
        }
        return s;
    }

    private boolean hasScanPermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            return ctx.checkSelfPermission(Manifest.permission.BLUETOOTH_SCAN)
                    == PackageManager.PERMISSION_GRANTED;
        }
        return ctx.checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION)
                == PackageManager.PERMISSION_GRANTED;
    }
}
