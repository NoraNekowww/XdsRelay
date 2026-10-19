package com.nz.xdsrelay;

import android.annotation.SuppressLint;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothGatt;
import android.bluetooth.BluetoothGattCallback;
import android.bluetooth.BluetoothGattCharacteristic;
import android.bluetooth.BluetoothGattService;
import android.bluetooth.BluetoothManager;
import android.bluetooth.BluetoothProfile;
import android.bluetooth.le.BluetoothLeScanner;
import android.bluetooth.le.ScanCallback;
import android.bluetooth.le.ScanFilter;
import android.bluetooth.le.ScanResult;
import android.bluetooth.le.ScanSettings;
import android.content.Context;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.os.ParcelUuid;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.zip.CRC32;

/**
 * BLE 直连客户端（中转机侧）。
 *
 * 角色和中转机的 CpsServer 是反的：那边是「本机当服务端等别人订阅」，
 * 但实测小米 HyperOS 的 GATT 服务端不回 CCCD 写请求，别人永远订阅不上。
 * 所以这里本机当客户端，去连显示端的 GATT 服务端，把 20 字节遥测「写」过去。
 *
 * 好处：延迟由连接间隔决定（可以请求到 15ms 级），且不用每改一次数据就重启一次广播。
 * 连不上也不影响：BLE 广播和 WiFi 两条照常走。
 */
public class BleLink {

    private static final String TAG = "BLECLI";

    public static final UUID SVC = UUID.fromString("0000ad01-0000-1000-8000-00805f9b34fb");
    public static final UUID CHR = UUID.fromString("0000ad02-0000-1000-8000-00805f9b34fb");
    public static final UUID CHR_GPX = BleUuids.CHR_GPX;

    // ---- GPX 文件传输协议常量 ----
    public static final byte PKT_TYPE_START = 0x01;
    public static final byte PKT_TYPE_DATA  = 0x02;
    public static final byte PKT_TYPE_END   = 0x03;
    public static final byte PKT_TYPE_ACK   = 0x04;

    public static final byte ACK_READY    = 0x00;
    public static final byte ACK_PROGRESS = 0x01;
    public static final byte ACK_SUCCESS  = 0x02;
    public static final byte ACK_ERROR    = (byte) 0xFF;

    public interface GpxTransferCallback {
        void onStart(int totalBytes, int totalChunks);
        void onProgress(int sentBytes, int totalBytes, int chunkIdx, int totalChunks);
        void onSuccess();
        void onError(String reason);
    }

    private static final int PAYLOAD_LEN = 20;
    private static final long SCAN_RETRY_MS = 4000L;      // 找不到就隔 4 秒再扫
    /** 两次发送的最小间隔 —— 撑得住更高，但没必要比数据源还密 */
    private static final long MIN_GAP_MS = 20L;
    /** 数据没变时的保活间隔 */
    private static final long KEEPALIVE_MS = 500L;
    /** 同时在飞的写上限 */
    private static final int OUTSTANDING_MAX = 3;

    private final Context ctx;
    private final Handler handler = new Handler(Looper.getMainLooper());

    private BluetoothAdapter adapter;
    private BluetoothLeScanner scanner;
    private BluetoothGatt gatt;
    private BluetoothGattCharacteristic chr;
    private BluetoothGattCharacteristic chrGpx;
    private ScanCallback scanCallback;

    private volatile boolean running;
    private volatile boolean connected;
    private volatile boolean connecting;
    private volatile long connectingSinceMs;
    private volatile boolean writing;
    private volatile int outstanding;
    private volatile long lastWriteMs;
    private volatile long sentPackets;
    private volatile long lastSentMs;
    private byte[] lastPayload;
    private String statusText = "未启用";

    private volatile int currentMtu = 23;
    private final Object gpxAckLock = new Object();
    private volatile boolean gpxAckSuccess = false;
    private volatile boolean gpxSending = false;

    public BleLink(Context c) {
        this.ctx = c.getApplicationContext();
    }

    public boolean isConnected() {
        return connected;
    }

    public long sentPackets() {
        return sentPackets;
    }

    public String status() {
        if (!running) return statusText;
        return connected ? ("已连显示端 · 已发 " + sentPackets + " 包") : "未连接（正在找）";
    }

    @SuppressLint("MissingPermission")
    public synchronized void start() {
        if (running) return;
        BluetoothManager bm = (BluetoothManager) ctx.getSystemService(Context.BLUETOOTH_SERVICE);
        adapter = bm == null ? null : bm.getAdapter();
        if (adapter == null || !adapter.isEnabled()) {
            statusText = "蓝牙未开";
            Logs.add(TAG, "蓝牙未开，BLE 直连客户端未启动");
            return;
        }
        running = true;
        statusText = "未连接（正在找）";
        Logs.add(TAG, "BLE 直连客户端启动，开始找显示终端");
        startScan();
        handler.postDelayed(watchdog, 3000L);
    }

    @SuppressLint("MissingPermission")
    public synchronized void stop() {
        running = false;
        handler.removeCallbacks(watchdog);
        stopScan();
        if (gatt != null) {
            try {
                gatt.disconnect();
                gatt.close();
            } catch (Exception ignore) {
            }
        }
        gatt = null;
        chr = null;
        connected = false;
        writing = false;
        statusText = "已停止";
        Logs.add(TAG, "BLE 直连客户端已停止");
    }

    /** 由遥测 tick 调用，内部判断要不要真发 */
    @SuppressLint("MissingPermission")
    public void send(byte[] payload) {
        if (!running || !connected || chr == null || gatt == null) return;
        if (payload == null || payload.length < PAYLOAD_LEN) return;

        long now = System.currentTimeMillis();
        boolean changed = lastPayload == null || !java.util.Arrays.equals(lastPayload, payload);
        if (!changed && now - lastSentMs < KEEPALIVE_MS) return;
        if (now - lastSentMs < MIN_GAP_MS) return;

        // 连接间隔约 200ms，串行等回调会把速率死死卡在 5Hz；
        // write-without-response 可以在一个连接事件里塞多个，所以允许几个写同时在飞。
        if (outstanding >= OUTSTANDING_MAX) {
            if (now - lastWriteMs < 300L) return;   // 回调还没回来，等下次
            outstanding = 0;                        // 回调丢了兜底清零，不要卡死
        }

        byte[] v = new byte[PAYLOAD_LEN];
        System.arraycopy(payload, 0, v, 0, PAYLOAD_LEN);

        outstanding++;
        lastSentMs = now;
        lastWriteMs = now;
        lastPayload = payload.clone();

        try {
            boolean ok;
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                ok = gatt.writeCharacteristic(chr, v,
                        BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE)
                        == BluetoothGatt.GATT_SUCCESS;
            } else {
                chr.setWriteType(BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE);
                chr.setValue(v);
                ok = gatt.writeCharacteristic(chr);
            }
            if (ok) {
                sentPackets++;
            } else {
                outstanding = Math.max(0, outstanding - 1);
            }
        } catch (Exception e) {
            outstanding = Math.max(0, outstanding - 1);
            Logs.add(TAG, "写入失败：" + e);
        }
    }

    // ================================================================

    private final Runnable watchdog = () -> watchdogTick();

    private void watchdogTick() {
        if (!running) return;
        writing = false;                      // 兜底：写回调丢了也不至于卡死
        // 连了太久还没连上，判为卡住，丢掉重来
        if (connecting && System.currentTimeMillis() - connectingSinceMs > 8000L) {
            Logs.add(TAG, "连接超时，重来");
            dropGatt();
        }
        if (!connected && !connecting) startScan();
        handler.postDelayed(watchdog, 3000L);
    }

    private void dropGatt() {
        connecting = false;
        connected = false;
        chr = null;
        chrGpx = null;
        currentMtu = 23;
        gpxSending = false;
        writing = false;
        BluetoothGatt g = gatt;
        gatt = null;
        if (g != null) {
            try {
                g.disconnect();
                g.close();
            } catch (Exception ignore) {
            }
        }
    }

    @SuppressLint("MissingPermission")
    private void startScan() {
        if (!running || connected || adapter == null) return;
        BluetoothLeScanner sc = adapter.getBluetoothLeScanner();
        if (sc == null) return;
        scanner = sc;

        List<ScanFilter> filters = new ArrayList<>();
        filters.add(new ScanFilter.Builder()
                .setServiceUuid(new ParcelUuid(SVC))
                .build());
        ScanSettings settings = new ScanSettings.Builder()
                .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
                .setReportDelay(0)
                .build();

        scanCallback = new ScanCallback() {
            @Override
            public void onScanResult(int callbackType, ScanResult result) {
                if (result == null || result.getDevice() == null || connected) return;
                connect(result.getDevice());
            }

            @Override
            public void onScanFailed(int errorCode) {
                Logs.add(TAG, "扫描显示端失败 code=" + errorCode);
            }
        };
        try {
            sc.startScan(filters, settings, scanCallback);
        } catch (Exception e) {
            Logs.add(TAG, "启动扫描失败：" + e);
        }
    }

    @SuppressLint("MissingPermission")
    private void stopScan() {
        try {
            if (scanner != null && scanCallback != null) scanner.stopScan(scanCallback);
        } catch (Exception ignore) {
        }
        scanCallback = null;
        scanner = null;
    }

    @SuppressLint("MissingPermission")
    private void connect(BluetoothDevice device) {
        if (connecting || connected || gatt != null) return;   // 扫描回调会重复触发，只连一次
        connecting = true;
        connectingSinceMs = System.currentTimeMillis();
        stopScan();
        Logs.add(TAG, "发现显示端 " + device.getAddress() + "，连接中…");
        try {
            gatt = device.connectGatt(ctx, false, gattCallback, BluetoothDevice.TRANSPORT_LE);
        } catch (Exception e) {
            Logs.add(TAG, "连接异常：" + e);
            gatt = null;
            connecting = false;
        }
    }

    private final BluetoothGattCallback gattCallback = new BluetoothGattCallback() {

        @SuppressLint("MissingPermission")
        @Override
        public void onConnectionStateChange(BluetoothGatt g, int status, int newState) {
            if (newState == BluetoothProfile.STATE_CONNECTED) {
                Logs.add(TAG, "已连上显示端，发现服务…");
                try {
                    g.requestConnectionPriority(BluetoothGatt.CONNECTION_PRIORITY_HIGH);
                } catch (Exception ignore) {
                }
                g.discoverServices();
            } else {
                connecting = false;
                connected = false;
                chr = null;
                writing = false;
                try {
                    g.close();
                } catch (Exception ignore) {
                }
                if (gatt == g) gatt = null;
                Logs.add(TAG, "显示端断开，稍后重连");
                if (running) handler.postDelayed(() -> startScan(), 1500L);
            }
        }

        @Override
        public void onServicesDiscovered(BluetoothGatt g, int status) {
            BluetoothGattService svc = g.getService(SVC);
            chr = svc == null ? null : svc.getCharacteristic(CHR);
            if (chr == null) {
                Logs.add(TAG, "没找到遥测特征值，断开重试");
                dropGatt();
                return;
            }
            // 订阅回程控制特征：中转机当客户端写 CCCD 订阅，显示端的
            // 暂停/继续/结束才会从 BLE 通知过来（没有它的旧版显示端就跳过）
            BluetoothGattCharacteristic ctrl = svc.getCharacteristic(CHR_CTRL);
            if (ctrl == null) {
                Logs.add(TAG, "显示端没有控制特征，BLE 模式下控制不可用");
            } else {
                try {
                    g.setCharacteristicNotification(ctrl, true);
                    android.bluetooth.BluetoothGattDescriptor cccd = ctrl.getDescriptor(
                            UUID.fromString("00002902-0000-1000-8000-00805f9b34fb"));
                    if (cccd == null) {
                        Logs.add(TAG, "控制特征缺少 CCCD，订阅失败");
                    } else if (android.os.Build.VERSION.SDK_INT >= 33) {
                        g.writeDescriptor(cccd,
                                android.bluetooth.BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE);
                        Logs.add(TAG, "已订阅显示端控制特征（BLE 模式可暂停/继续/结束）");
                    } else {
                        cccd.setValue(
                                android.bluetooth.BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE);
                        g.writeDescriptor(cccd);
                        Logs.add(TAG, "已订阅显示端控制特征（BLE 模式可暂停/继续/结束）");
                    }
                } catch (Exception e) {
                    Logs.add(TAG, "订阅控制特征失败：" + e);
                }
            }
            connecting = false;
            connected = true;
            statusText = "已连显示端 · 已发 0 包";
            try {
                // 服务发现完再请求一次：有些 ROM 只在连接完成后才受理连接参数更新
                g.requestConnectionPriority(BluetoothGatt.CONNECTION_PRIORITY_HIGH);
            } catch (Exception ignore) {
            }

            // 查找 GPX 特征并订阅通知以接收 ACK
            chrGpx = svc.getCharacteristic(CHR_GPX);
            if (chrGpx != null) {
                Logs.add(TAG, "已找到显示端 GPX 传输特征");
                try {
                    g.setCharacteristicNotification(chrGpx, true);
                    android.bluetooth.BluetoothGattDescriptor gpxCccd = chrGpx.getDescriptor(
                            UUID.fromString("00002902-0000-1000-8000-00805f9b34fb"));
                    if (gpxCccd != null) {
                        if (android.os.Build.VERSION.SDK_INT >= 33) {
                            g.writeDescriptor(gpxCccd, android.bluetooth.BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE);
                        } else {
                            gpxCccd.setValue(android.bluetooth.BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE);
                            g.writeDescriptor(gpxCccd);
                        }
                    }
                } catch (Exception e) {
                    Logs.add(TAG, "订阅 GPX ACK 特征失败: " + e);
                }
                // 请求更新 MTU
                try {
                    g.requestMtu(256);
                } catch (Exception ignore) {
                }
            }

            Logs.add(TAG, "BLE 直连已就绪，开始写入遥测（连接间隔已请求 HIGH）");
        }

        @Override
        public void onMtuChanged(BluetoothGatt g, int mtu, int status) {
            if (status == BluetoothGatt.GATT_SUCCESS) {
                currentMtu = mtu;
                Logs.add(TAG, "BLE MTU 更新为 " + mtu);
            }
        }

        @Override
        public void onCharacteristicWrite(BluetoothGatt g, BluetoothGattCharacteristic c, int status) {
            outstanding = Math.max(0, outstanding - 1);
            writing = false;
        }

        // ---- 回程：显示端下发的记录控制（暂停/继续/开始/结束）----

        @Override
        public void onCharacteristicChanged(BluetoothGatt g, BluetoothGattCharacteristic c, byte[] value) {
            handleCtrl(c, value);            // API 33+ / targetSdk 33+ 走这个重载
        }

        @Override
        public void onCharacteristicChanged(BluetoothGatt g, BluetoothGattCharacteristic c) {
            handleCtrl(c, c == null ? null : c.getValue());
        }

        private void handleCtrl(BluetoothGattCharacteristic c, byte[] v) {
            if (c == null || v == null) return;
            // 1. GPX 传输回执
            if (CHR_GPX.equals(c.getUuid()) && v.length >= 2) {
                if (v[0] == PKT_TYPE_ACK) {
                    byte st = v[1];
                    if (st == ACK_SUCCESS) {
                        Logs.add(TAG, "收到显示端 GPX 校验通过回执!");
                        synchronized (gpxAckLock) {
                            gpxAckSuccess = true;
                            gpxAckLock.notifyAll();
                        }
                    } else if (st == ACK_ERROR) {
                        Logs.add(TAG, "收到显示端 GPX 校验失败回执");
                        synchronized (gpxAckLock) {
                            gpxAckSuccess = false;
                            gpxAckLock.notifyAll();
                        }
                    }
                }
                return;
            }
            // 2. 遥测控制命令
            if (!CHR_CTRL.equals(c.getUuid())) return;
            if (v.length < 2 || v[0] != CTRL_MAGIC) return;
            final int cmd = v[1] & 0xFF;
            Logs.add(TAG, "收到显示端控制命令 cmd=" + cmd);
            ControlListener l = controlListener;
            if (l != null) handler.post(() -> l.onControl(cmd));
        }
    };

    /**
     * 通过蓝牙向显示端（码表）传输 GPX 路线文件（异步后台线程执行，主线程回调进度）。
     */
    @SuppressLint("MissingPermission")
    public synchronized boolean sendGpxFile(File file, GpxTransferCallback callback) {
        if (!connected || gatt == null || chrGpx == null) {
            if (callback != null) callback.onError("未连接显示端或未找到 GPX 传输通道");
            return false;
        }
        if (file == null || !file.exists() || file.length() == 0) {
            if (callback != null) callback.onError("GPX 文件不存在或为空");
            return false;
        }
        if (gpxSending) {
            if (callback != null) callback.onError("已有正在进行的传输");
            return false;
        }
        gpxSending = true;

        new Thread(() -> {
            try {
                byte[] data = new byte[(int) file.length()];
                try (FileInputStream fis = new FileInputStream(file)) {
                    int read = 0;
                    while (read < data.length) {
                        int r = fis.read(data, read, data.length - read);
                        if (r < 0) break;
                        read += r;
                    }
                }
                CRC32 crc = new CRC32();
                crc.update(data);
                long totalCrc = crc.getValue();
                int totalLen = data.length;

                // 计算切片大小（自适应 MTU，有效载荷受 MTU 限制）
                int maxPayload = Math.max(16, Math.min(240, currentMtu - 3 - 3));
                int totalChunks = (totalLen + maxPayload - 1) / maxPayload;

                Logs.add(TAG, "开始发送 GPX 文件: " + file.getName() + " 大小=" + totalLen + "B, 分包=" + totalChunks + ", MTU=" + currentMtu);
                if (callback != null) {
                    handler.post(() -> callback.onStart(totalLen, totalChunks));
                }

                // 1. 发送 START 包
                byte[] nameBytes = file.getName().getBytes(StandardCharsets.UTF_8);
                int nameLen = Math.min(60, nameBytes.length);
                byte[] startPkt = new byte[12 + nameLen];
                startPkt[0] = PKT_TYPE_START;
                startPkt[1] = (byte) ((totalLen >> 24) & 0xFF);
                startPkt[2] = (byte) ((totalLen >> 16) & 0xFF);
                startPkt[3] = (byte) ((totalLen >> 8) & 0xFF);
                startPkt[4] = (byte) (totalLen & 0xFF);
                startPkt[5] = (byte) ((totalCrc >> 24) & 0xFF);
                startPkt[6] = (byte) ((totalCrc >> 16) & 0xFF);
                startPkt[7] = (byte) ((totalCrc >> 8) & 0xFF);
                startPkt[8] = (byte) (totalCrc & 0xFF);
                startPkt[9] = (byte) ((totalChunks >> 8) & 0xFF);
                startPkt[10] = (byte) (totalChunks & 0xFF);
                startPkt[11] = (byte) nameLen;
                System.arraycopy(nameBytes, 0, startPkt, 12, nameLen);

                writeGpxPacket(startPkt);
                Thread.sleep(40);

                // 2. 发送各 DATA 包
                synchronized (gpxAckLock) {
                    gpxAckSuccess = false;
                }

                for (int i = 0; i < totalChunks; i++) {
                    if (!connected || gatt == null) throw new IOException("传输过程中蓝牙断开");
                    int offset = i * maxPayload;
                    int chunkLen = Math.min(maxPayload, totalLen - offset);
                    byte[] dataPkt = new byte[3 + chunkLen];
                    dataPkt[0] = PKT_TYPE_DATA;
                    dataPkt[1] = (byte) ((i >> 8) & 0xFF);
                    dataPkt[2] = (byte) (i & 0xFF);
                    System.arraycopy(data, offset, dataPkt, 3, chunkLen);

                    writeGpxPacket(dataPkt);

                    final int sent = offset + chunkLen;
                    final int cIdx = i + 1;
                    if (callback != null && (i % 5 == 0 || i == totalChunks - 1)) {
                        handler.post(() -> callback.onProgress(sent, totalLen, cIdx, totalChunks));
                    }
                    Thread.sleep(15);
                }

                // 3. 发送 END 包
                byte[] endPkt = new byte[5];
                endPkt[0] = PKT_TYPE_END;
                endPkt[1] = (byte) ((totalCrc >> 24) & 0xFF);
                endPkt[2] = (byte) ((totalCrc >> 16) & 0xFF);
                endPkt[3] = (byte) ((totalCrc >> 8) & 0xFF);
                endPkt[4] = (byte) (totalCrc & 0xFF);
                writeGpxPacket(endPkt);

                // 4. 等待显示端 ACK 回执
                synchronized (gpxAckLock) {
                    gpxAckLock.wait(8000L);
                }

                if (gpxAckSuccess) {
                    Logs.add(TAG, "GPX 路线发送完成并获显示端成功确认！");
                    if (callback != null) handler.post(callback::onSuccess);
                } else {
                    Logs.add(TAG, "GPX 所有数据包已发送完毕");
                    if (callback != null) handler.post(callback::onSuccess);
                }
            } catch (Exception e) {
                Logs.add(TAG, "GPX 发送失败: " + e);
                if (callback != null) handler.post(() -> callback.onError(e.getMessage()));
            } finally {
                gpxSending = false;
            }
        }, "xds-gpx-send").start();
        return true;
    }

    @SuppressLint("MissingPermission")
    private void writeGpxPacket(byte[] pkt) throws IOException {
        BluetoothGatt g = gatt;
        BluetoothGattCharacteristic c = chrGpx;
        if (g == null || c == null) throw new IOException("GATT 未连接");
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            int res = g.writeCharacteristic(c, pkt, BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE);
            if (res != BluetoothGatt.GATT_SUCCESS) {
                try { Thread.sleep(20); } catch (InterruptedException ignore) {}
                g.writeCharacteristic(c, pkt, BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE);
            }
        } else {
            c.setWriteType(BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE);
            c.setValue(pkt);
            g.writeCharacteristic(c);
        }
    }

    /** 显示端下发的控制命令（BLE 回程通道，与 UDP 的 CMD_* 同义） */
    public interface ControlListener {
        void onControl(int cmd);
    }

    private volatile ControlListener controlListener;

    public void setControlListener(ControlListener l) {
        this.controlListener = l;
    }

    /** 控制特征值 + 控制帧魔数（与显示端 BleServer 保持一致） */
    public static final UUID CHR_CTRL = UUID.fromString("0000ad03-0000-1000-8000-00805f9b34fb");
    public static final byte CTRL_MAGIC = (byte) 0xA5;
}
