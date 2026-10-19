package com.nz.xdsrelay;

import android.Manifest;
import android.annotation.SuppressLint;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothManager;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.ServiceConnection;
import android.content.pm.PackageManager;
import android.content.res.ColorStateList;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.PowerManager;
import android.provider.Settings;
import android.view.View;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.app.ActivityCompat;
import androidx.core.content.FileProvider;

import com.google.android.material.dialog.MaterialAlertDialogBuilder;

import java.io.File;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;

public class MainActivity extends AppCompatActivity {

    private static final int REQ_PERMS = 0x501;
    private static final int REQ_BG_LOCATION = 0x502;
    private static final String TAG = "UI";

    private TextView txtParams;
    private BalanceBarView balance;
    private SparklineView spark;
    private float uiPeak;
    private TextView txtCadence, txtAngle, txtError, txtPeak, txtLog, txtClock;
    private TextView chipSensor, chipBroadcast, chipClients;
    private TextView txtFix, txtLat, txtLon, txtAltMsl, txtAltEll, txtGeoid, txtSpeed, txtAccH, txtAccV, txtGpsDetail;
    private TextView txtForward, txtRecord;
    private Button btnToggle, btnScan, btnPick, btnSim, btnClear, btnKeepAlive;
    private Button btnExportFit, btnSite;
    private Button btnRec;
    private TextView txtRecHint;

    // ===== 三栏页面与底栏 =====
    private ScrollView scrollWorkspace, scrollFitList, scrollGpxList;
    private View tabWorkspace, tabFit, tabGpx;
    private TextView txtTabWorkspace, txtTabFit, txtTabGpx;
    private View indTabWorkspace, indTabFit, indTabGpx;

    // ===== FIT 预览与管理 =====
    private TextView txtFitSummary;
    private Button btnFitRefresh;
    private View cardFitDetail;
    private TextView txtFitDetailName, txtFitDetailTime, txtFitDetailSize, txtFitDetailDuration, txtFitDetailStatus;
    private Button btnFitUpload, btnFitShare, btnFitDelete;
    private LinearLayout layoutFitContainer;
    private FitManager.FitItem selectedFit;

    // ===== GPX 路线与蓝牙传输 =====
    private Button btnGpxRefresh, btnGpxImport, btnGpxCreateSample;
    private View cardGpxDetail;
    private TextView txtGpxDetailName, txtGpxDetailDist, txtGpxDetailPoints, txtGpxDetailSize;
    private Button btnGpxSendBle, btnGpxShare, btnGpxDelete;
    private View layoutGpxProgress;
    private ProgressBar progGpxSend;
    private TextView txtGpxSendStatus;
    private LinearLayout layoutGpxContainer;
    private GpxManager.GpxItem selectedGpx;

    private ActivityResultLauncher<String> gpxPickerLauncher;

    private RelayService service;
    private boolean bound;
    private Logs.Listener logListener;
    private boolean pendingSim;

    private final Handler ui = new Handler(Looper.getMainLooper());
    private final SimpleDateFormat clock = new SimpleDateFormat("HH:mm:ss", Locale.CHINA);

    private long lastSparkTs;
    private int renderCount;

    private final ServiceConnection conn = new ServiceConnection() {
        @Override
        public void onServiceConnected(ComponentName name, IBinder binder) {
            service = ((RelayService.LocalBinder) binder).get();
            bound = true;
            Logs.add(TAG, "已连接中继服务");
            if (!service.relayRunning) {
                service.startRelay();
            }
            if (pendingSim) {
                pendingSim = false;
                service.setSimulation(true);
                Logs.add(TAG, "按启动参数进入模拟模式");
            }
            // 「每次打开软件 = 一次骑行」：服务已经在跑（比如上次划掉界面但服务还活着）时，
            // 这里补上一次新的记录（各自一个新文件）。
            if (service.recorder() != null && !service.recorder().isRecording()) {
                service.startRecording();
            }
            render();
        }

        @Override
        public void onServiceDisconnected(ComponentName name) {
            bound = false;
            service = null;
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        gpxPickerLauncher = registerForActivityResult(
                new ActivityResultContracts.GetContent(),
                uri -> {
                    if (uri != null) handleImportedGpx(uri);
                });

        txtParams = findViewById(R.id.txtParams);
        balance = findViewById(R.id.balance);
        spark = findViewById(R.id.spark);
        txtCadence = findViewById(R.id.txtCadence);
        txtAngle = findViewById(R.id.txtAngle);
        txtError = findViewById(R.id.txtError);
        txtPeak = findViewById(R.id.txtPeak);
        txtLog = findViewById(R.id.txtLog);
        txtClock = findViewById(R.id.txtClock);
        chipSensor = findViewById(R.id.chipSensor);
        chipBroadcast = findViewById(R.id.chipBroadcast);
        chipClients = findViewById(R.id.chipClients);
        txtFix = findViewById(R.id.txtFix);
        txtLat = findViewById(R.id.txtLat);
        txtLon = findViewById(R.id.txtLon);
        txtAltMsl = findViewById(R.id.txtAltMsl);
        txtAltEll = findViewById(R.id.txtAltEll);
        txtGeoid = findViewById(R.id.txtGeoid);
        txtSpeed = findViewById(R.id.txtSpeed);
        txtAccH = findViewById(R.id.txtAccH);
        txtAccV = findViewById(R.id.txtAccV);
        txtGpsDetail = findViewById(R.id.txtGpsDetail);
        txtForward = findViewById(R.id.txtForward);
        txtRecord = findViewById(R.id.txtRecord);
        btnExportFit = findViewById(R.id.btnExportFit);
        btnSite = findViewById(R.id.btnSite);
        btnToggle = findViewById(R.id.btnToggle);
        btnScan = findViewById(R.id.btnScan);
        btnPick = findViewById(R.id.btnPick);
        btnSim = findViewById(R.id.btnSim);
        btnClear = findViewById(R.id.btnClear);
        btnKeepAlive = findViewById(R.id.btnKeepAlive);
        btnRec = findViewById(R.id.btnRec);
        txtRecHint = findViewById(R.id.txtRecHint);

        Logs.add(TAG, "界面初始化完成");

        Logs.Listener logListener = lines -> {
            if (lines.isEmpty()) {
                txtLog.setText("就绪");
                return;
            }
            int from = Math.max(0, lines.size() - 60);
            StringBuilder sb = new StringBuilder();
            for (int i = from; i < lines.size(); i++) {
                sb.append(lines.get(i)).append('\n');
            }
            txtLog.setText(sb.toString().trim());
        };
        Logs.register(logListener);
        this.logListener = logListener;

        btnToggle.setOnClickListener(v -> {
            if (service == null) return;
            if (service.relayRunning) {
                service.stopRelay();
                Logs.add(TAG, "用户停止中继");
            } else {
                service.startRelay();
            }
            render();
        });

        btnScan.setOnClickListener(v -> {
            if (service == null) return;
            if (!service.relayRunning) service.startRelay();
            service.rescan();
            Toast.makeText(this, "正在重新扫描功率计…", Toast.LENGTH_SHORT).show();
        });

        btnPick.setOnClickListener(v -> showPicker());

        btnSim.setOnClickListener(v -> {
            if (service == null) return;
            boolean nowSim = !service.isSimulating();
            service.setSimulation(nowSim);
            Toast.makeText(this, nowSim ? "已开启模拟数据" : "已切回真实功率计", Toast.LENGTH_SHORT).show();
        });

        btnClear.setOnClickListener(v -> {
            Logs.clear();
            spark.reset();
            uiPeak = 0;
        });

        btnKeepAlive.setOnClickListener(v -> showKeepAliveDialog(false));

        btnExportFit.setOnClickListener(v -> exportFitNow());

        // 顶部大按钮：点按 = 开始 / 暂停 / 继续，长按 = 结束记录（写盘 + 导出）
        btnRec.setOnClickListener(v -> onRecButton());
        btnRec.setOnLongClickListener(v -> {
            endRecording();
            return true;
        });

        btnSite.setOnClickListener(v -> openSite());

        txtRecord.setOnLongClickListener(v -> {
            shareFit();
            return true;
        });

        // 允许用 adb 直接进入模拟模式，方便无人值守验证：
        //   adb shell am start -n com.nz.xdsrelay/.MainActivity --ez sim true
        pendingSim = getIntent() != null && getIntent().getBooleanExtra("sim", false);
        if (getIntent() != null && getIntent().getBooleanExtra("exportfit", false)) {
            ui.postDelayed(this::exportFitNow, 6000L);
        }
        // adb 验证钩子（跟 --ez sim 一个路子）：
        //   adb shell am start -n com.nz.xdsrelay/.MainActivity --ez stoprec true
        if (getIntent() != null && getIntent().getBooleanExtra("stoprec", false)) {
            ui.postDelayed(this::endRecording, 1500L);
        }

        initNavigationTabs();
        initFitPreviewSection();
        initGpxSection();

        ensurePermissions();
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        if (intent != null && intent.getBooleanExtra("exportfit", false)) {
            ui.postDelayed(this::exportFitNow, 1500L);
        }
        if (intent != null && intent.getBooleanExtra("stoprec", false)) {
            ui.postDelayed(this::endRecording, 1500L);
        }
        if (intent != null && intent.getBooleanExtra("sim", false)) {
            if (service != null) {
                service.setSimulation(true);
                Logs.add(TAG, "按启动参数进入模拟模式");
            } else {
                pendingSim = true;
            }
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        ui.removeCallbacks(renderTask);
        ui.post(renderTask);
        if (!bound) bindRelay();
        if (service != null) service.setAppForeground(true);
    }

    @Override
    protected void onPause() {
        super.onPause();
        ui.removeCallbacks(renderTask);
        if (service != null) service.setAppForeground(false);
    }

    @Override
    protected void onDestroy() {
        // 「关闭软件 = 这次骑行结束」：界面销毁时收尾，写盘 + 导出 FIT。
        // 数据转发不受影响（中继服务该怎么跑还怎么跑）。
        if (service != null) service.stopRecording();
        if (logListener != null) Logs.unregister(logListener);
        if (bound) {
            unbindService(conn);
            bound = false;
        }
        super.onDestroy();
    }

    // ================================================================
    //  权限
    // ================================================================

    private List<String> neededPermissions() {
        List<String> p = new ArrayList<>();
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            p.add(Manifest.permission.BLUETOOTH_SCAN);
            p.add(Manifest.permission.BLUETOOTH_CONNECT);
            p.add(Manifest.permission.BLUETOOTH_ADVERTISE);
        }
        p.add(Manifest.permission.ACCESS_FINE_LOCATION);
        if (Build.VERSION.SDK_INT >= 33) {
            p.add(Manifest.permission.POST_NOTIFICATIONS);
        }
        List<String> missing = new ArrayList<>();
        for (String s : p) {
            if (ActivityCompat.checkSelfPermission(this, s) != PackageManager.PERMISSION_GRANTED) {
                missing.add(s);
            }
        }
        return missing;
    }

    private void ensurePermissions() {
        List<String> missing = neededPermissions();
        if (missing.isEmpty()) {
            Logs.add(TAG, "权限齐备");
            bindRelay();
            ui.postDelayed(() -> showKeepAliveDialog(true), 2500L);
            requestBackgroundLocationIfNeeded();
            return;
        }
        Logs.add(TAG, "申请权限: " + missing);
        ActivityCompat.requestPermissions(this, missing.toArray(new String[0]), REQ_PERMS);
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, @NonNull String[] permissions,
                                           @NonNull int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == REQ_BG_LOCATION) {
            boolean granted = grantResults.length > 0
                    && grantResults[0] == PackageManager.PERMISSION_GRANTED;
            Logs.add(TAG, granted
                    ? "后台定位已设为「始终允许」"
                    : "后台定位仍是「仅在使用中」——息屏/后台时定位可能被系统掐断");
            return;
        }
        if (requestCode != REQ_PERMS) return;
        List<String> stillMissing = neededPermissions();
        if (stillMissing.isEmpty()) {
            Logs.add(TAG, "权限已全部授予");
            bindRelay();
            ui.postDelayed(() -> showKeepAliveDialog(true), 2500L);
            requestBackgroundLocationIfNeeded();
        } else {
            Logs.add(TAG, "仍有权限被拒绝: " + stillMissing);
            new MaterialAlertDialogBuilder(this)
                    .setTitle("权限不完整")
                    .setMessage("缺少权限：" + stillMissing
                            + "\n\n需要：附近设备(蓝牙扫描/连接/广播)、精确定位(高精度 GPS 与海拔)、通知。"
                            + "\n请到系统设置里手动授予后重新打开应用。")
                    .setPositiveButton("去设置", (d, w) -> {
                        try {
                            Intent i = new Intent(android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                                    android.net.Uri.parse("package:" + getPackageName()));
                            startActivity(i);
                        } catch (Exception ignore) {}
                    })
                    .setNegativeButton("稍后", null)
                    .show();
        }
    }

    // ================================================================
    //  服务绑定
    // ================================================================

    private void bindRelay() {
        if (bound) return;
        Intent i = new Intent(this, RelayService.class);
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) startForegroundService(i);
            else startService(i);
        } catch (Exception e) {
            Logs.add(TAG, "启动服务失败: " + e);
        }
        try {
            bindService(i, conn, Context.BIND_AUTO_CREATE);
        } catch (Exception e) {
            Logs.add(TAG, "绑定服务失败: " + e);
        }
    }

    // ================================================================
    //  渲染
    // ================================================================

    private final Runnable renderTask = new Runnable() {
        @Override
        public void run() {
            render();
            ui.postDelayed(this, 250L);
        }
    };

    /** 参数列表（取代原来的大功率圆圈）：一屏把关键量都列出来，monospace 对齐 */
    private String buildParamList(RelayService s, XdsParser.Measurement m, boolean fresh) {
        StringBuilder b = new StringBuilder();
        b.append(String.format(Locale.CHINA, "功率    %4d W      峰值 %4d W%n",
                fresh ? m.totalPowerW : 0, Math.round(uiPeak)));
        b.append(String.format(Locale.CHINA, "踏频    %4s rpm    左右 %s%n",
                fresh && m.hasCadence ? String.valueOf(m.cadenceRpm) : "--",
                fresh && m.hasLeft && m.hasRight
                        ? String.format(Locale.CHINA, "%d/%dW", m.leftPowerW, m.rightPowerW)
                        : "--"));
        GnssTracker g = s.gnss();
        if (g != null) {
            b.append(String.format(Locale.CHINA, "速度    %5.1f km/h  海拔 %4.0f m%n",
                    Math.max(0f, g.speedFiltered) * 3.6f, g.altMsl));
            b.append(String.format(Locale.CHINA, "定位    %.6f, %.6f%n", g.lat, g.lon));
            b.append(String.format(Locale.CHINA, "GPS     %2d 星  质量 %d  %s%n",
                    g.satsUsed, g.fixQuality, g.hasFix ? "已定位" : "搜星中"));
        } else {
            b.append("速度    --            海拔 --\n定位    --\nGPS     --\n");
        }

        FitRecorder r = s.recorder();
        if (r != null) {
            b.append(String.format(Locale.CHINA, "记录    %d 点  %.2f km  %s%n",
                    r.recordCount(), r.distanceM() / 1000.0, hms(r.timerSec())));
        }

        UdpSender u = s.udp();
        b.append(String.format(Locale.CHINA, "WiFi    %s%n", u == null ? "未启用" : u.status()));
        BleLink lk = s.link();
        b.append(String.format(Locale.CHINA, "BLE直连 %s", lk == null ? "未启用" : lk.status()));
        return b.toString();
    }

    private void render() {
        txtClock.setText(clock.format(new Date()));
        renderCount++;

        RelayService s = service;
        if (s == null) {
            chipSensor.setText("功率计 · 服务未连接");
            txtForward.setText("服务尚未绑定");
            renderRec(null);
            return;
        }

        XdsParser.Measurement m = s.lastMeasurement;
        long age = m.timestampMs == 0 ? Long.MAX_VALUE : System.currentTimeMillis() - m.timestampMs;
        boolean fresh = age < 5000;

        // ---- 参数列表（取代原来的大功率圆圈）----
        uiPeak = Math.max(uiPeak, fresh ? m.totalPowerW : 0f);
        balance.setBalance(m.leftPowerW, m.rightPowerW, fresh && m.hasLeft && m.hasRight);
        txtParams.setText(buildParamList(s, m, fresh));

        txtCadence.setText(fresh && m.hasCadence ? String.valueOf(m.cadenceRpm) : "--");
        txtAngle.setText(fresh && m.hasAngle ? String.valueOf(m.angleDeg) : "--");
        txtError.setText(fresh && m.hasError ? String.valueOf(m.errorCode) : "--");
        txtError.setTextColor(getColor(m.hasError && m.errorCode != 0 && fresh ? R.color.err : R.color.text));

        if (m.timestampMs != 0 && m.timestampMs != lastSparkTs && renderCount % 2 == 0) {
            lastSparkTs = m.timestampMs;
            spark.push(m.totalPowerW, m.cadenceRpm);
        }
        txtPeak.setText(String.format(Locale.CHINA, "峰值 %d W  ·  平均 %d W",
                Math.round(uiPeak), average(s)));

        // ---- 状态标签 ----
        String sensorText;
        int sensorColor;
        if (s.isSimulating()) {
            sensorText = "功率计 · 模拟数据";
            sensorColor = R.color.accent2;
        } else {
            switch (s.central().getState()) {
                case READY:
                    sensorText = "功率计 · 已连接 " + describeDevice(s);
                    sensorColor = R.color.ok;
                    break;
                case SCANNING:
                    sensorText = "功率计 · 搜索中";
                    sensorColor = R.color.warn;
                    break;
                case CONNECTING:
                case DISCOVERING:
                case SUBSCRIBING:
                    sensorText = "功率计 · 握手中";
                    sensorColor = R.color.warn;
                    break;
                case WAIT_RETRY:
                    sensorText = "功率计 · 等待重连";
                    sensorColor = R.color.warn;
                    break;
                case ERROR:
                    sensorText = "功率计 · 蓝牙异常";
                    sensorColor = R.color.err;
                    break;
                default:
                    sensorText = "功率计 · 未启动";
                    sensorColor = R.color.muted;
                    break;
            }
        }
        chipSensor.setText(sensorText);
        chipSensor.setTextColor(getColor(sensorColor));

        chipBroadcast.setText("广播 · " + (s.server().isAdvertising() ? "运行中" : "关闭"));
        chipBroadcast.setTextColor(getColor(s.server().isAdvertising() ? R.color.ok : R.color.muted));
        chipClients.setText("客户端 " + s.server().clientCount());

        renderGnss(s);
        renderForward(s);
        renderRecord(s);
    }

    private int average(RelayService s) {
        return s.averagePower();
    }

    private String describeDevice(RelayService s) {
        String label = s.central().getDeviceLabel();
        String addr = s.central().getDeviceAddress();
        if (label == null || label.isEmpty()) label = addr;
        return label == null ? "" : label;
    }

    private void renderGnss(RelayService s) {
        GnssTracker g = s.gnss();
        if (g == null) return;

        boolean fresh = g.hasFix && g.fixAgeMs() < 15000;
        boolean stale = g.hasFix && !fresh;      // 定位过，但此刻没有新数据
        long ageS = Math.min(g.fixAgeMs() / 1000L, 9999L);

        // 定位状态
        String quality;
        switch (g.fixQuality) {
            case 1: quality = "单点定位"; break;
            case 2: quality = "差分定位"; break;
            case 4: quality = "RTK 固定"; break;
            case 5: quality = "RTK 浮动"; break;
            case 6: quality = "估算"; break;
            default: quality = g.fixQuality < 0 ? "" : "无效"; break;
        }
        String dim;
        switch (g.fixType) {
            case 2: dim = "2D"; break;
            case 3: dim = "3D"; break;
            case 1: dim = "未定位"; break;
            default: dim = ""; break;
        }
        if (fresh) {
            txtFix.setText((quality.isEmpty() ? "已定位" : quality) + (dim.isEmpty() ? "" : " · " + dim));
            txtFix.setTextColor(getColor(R.color.ok));
        } else if (stale) {
            txtFix.setText("已定位但 " + ageS + "s 未更新 · 仍在搜星");
            txtFix.setTextColor(getColor(R.color.warn));
        } else {
            txtFix.setText(g.isProviderEnabled() ? "搜星中…" : "GPS 已关闭");
            txtFix.setTextColor(getColor(R.color.warn));
        }

        // 坐标：只要历史上定过位就一直显示（比 "--" 有用），过期状态由上面那行提示
        if (g.hasFix) {
            txtLat.setText(String.format(Locale.CHINA, "%.7f°  %s", g.lat, g.lat >= 0 ? "N" : "S"));
            txtLon.setText(String.format(Locale.CHINA, "%.7f°  %s", g.lon, g.lon >= 0 ? "E" : "W"));
        } else {
            txtLat.setText("--");
            txtLon.setText("--");
        }

        boolean hasAlt = !Double.isNaN(g.altMsl);
        txtAltMsl.setText(hasAlt
                ? (Double.isNaN(g.altFiltered)
                    ? String.format(Locale.CHINA, "%.1f m", g.altMsl)
                    : String.format(Locale.CHINA, "%.1f m  (平滑 %.0f)", g.altMsl, g.altFiltered))
                : "--");
        txtAltEll.setText(!Double.isNaN(g.altEllipsoid)
                ? String.format(Locale.CHINA, "%.1f m", g.altEllipsoid) : "--");
        txtGeoid.setText(!Double.isNaN(g.geoidSep)
                ? String.format(Locale.CHINA, "%.2f m", g.geoidSep) : "--");

        double kmh = g.speedFiltered * 3.6;
        txtSpeed.setText(g.hasFix
                ? String.format(Locale.CHINA, "%.1f km/h  /  %.0f°", kmh, g.heading >= 0 ? g.heading : 0f)
                : "--");

        txtAccH.setText(g.accH > 0 ? String.format(Locale.CHINA, "±%.1f m", g.accH) : "--");
        txtAccV.setText(g.accV > 0 ? String.format(Locale.CHINA, "±%.1f m", g.accV) : "--");

        txtGpsDetail.setText(String.format(Locale.CHINA,
                "卫星 %d 可见 / %d 参与   %s\nHDOP %s   VDOP %s   PDOP %s   %s",
                g.satsVisible, g.satsUsed,
                g.constellations == null || g.constellations.isEmpty() ? "" : g.constellations,
                fmt(g.hdop), fmt(g.vdop), fmt(g.pdop), g.utcTime));
    }

    private static String fmt(float v) {
        return Float.isNaN(v) || v <= 0 ? "--" : String.format(Locale.CHINA, "%.2f", v);
    }

    private void renderForward(RelayService s) {
        StringBuilder sb = new StringBuilder();
        sb.append("广播设备名：").append(bluetoothName()).append('\n');
        sb.append("标准服务：0x1818 骑行功率 · 0x1819 定位导航 · 0x180F 电量 · 0x180A 设备信息\n");
        sb.append("功率特性：0x2A63 测量(notify) / 0x2A65 特性 / 0x2A5B 位置=左曲柄")
                .append(BleCodec.INCLUDE_PEDAL_BALANCE ? " · 含左右平衡" : "").append('\n');
        sb.append("定位特性：0x2A67 位置与速度(notify) / 0x2A6A LN 特性\n");
        sb.append("已推送：功率 ").append(s.forwardedCps()).append(" 包 · 定位 ").append(s.forwardedLn()).append(" 包\n");
        sb.append("客户端 MTU：").append(s.server().getLastMtu())
                .append("（单包上限 ").append(Math.max(0, s.server().getLastMtu() - 3)).append(" 字节）\n");
        List<String> clients = s.server().clientNames();
        if (clients.isEmpty()) {
            sb.append("已连接客户端：无");
        } else {
            sb.append("已连接客户端：");
            for (int i = 0; i < clients.size(); i++) {
                if (i > 0) sb.append("，");
                sb.append(clients.get(i));
            }
        }
        txtForward.setText(sb.toString());
    }

    private String bluetoothName() {
        try {
            BluetoothManager bm = (BluetoothManager) getSystemService(Context.BLUETOOTH_SERVICE);
            BluetoothAdapter a = bm != null ? bm.getAdapter() : null;
            if (a == null) return "--";
            String n = a.getName();
            return n == null || n.isEmpty() ? "(未命名)" : n;
        } catch (Exception e) {
            return "--";
        }
    }

    // ================================================================
    //  记录 / 上报
    // ================================================================

    private void renderRecord(RelayService s) {
        FitRecorder r = s.recorder();
        renderRec(s);
        StringBuilder sb = new StringBuilder();

        if (r == null || !r.isRecording()) {
            sb.append("未在记录\n");
        } else {
            String name = r.currentFile() == null ? "-" : r.currentFile().getName();
            sb.append("文件：").append(name).append('\n');
            sb.append(String.format(Locale.CHINA, "已记录 %d 点 · %.2f km · 运动 %s\n",
                    r.recordCount(), r.distanceM() / 1000.0, hms(r.timerSec())));
        }

        sb.append("每次打开软件 = 一次骑行，各自一个 REC_日期_序号.fit；\n"
                + "本次骑行内的自动保存覆写同一个文件，下次另起新号。\n"
                + "结束/收车自动导出到 下载/XdsRelay/，也可随时点『导出FIT』\n");
        if (s.udp() != null) sb.append("WiFi 直连：").append(s.udp().status()).append('\n');
        if (s.link() != null) sb.append("BLE 直连：").append(s.link().status()).append('\n');

        // ---- 已保存的 FIT 列表（自动保存的 _partial 也在里面）----
        java.util.List<File> files = r == null ? null : r.savedFiles();
        if (files != null && !files.isEmpty()) {
            sb.append("已保存 FIT（").append(files.size()).append(" 个，点击选择分享）：\n");
            SimpleDateFormat df = new SimpleDateFormat("MM-dd HH:mm", Locale.CHINA);
            for (int i = 0; i < Math.min(5, files.size()); i++) {
                File f = files.get(i);
                sb.append(String.format(Locale.CHINA, "  %s   %.0fKB   %s%n",
                        f.getName(), f.length() / 1024.0f, df.format(new Date(f.lastModified()))));
            }
        } else {
            sb.append("已保存 FIT：暂无\n");
        }

        txtRecord.setText(sb.toString());
    }

    private static String hms(double seconds) {
        long t = (long) seconds;
        return String.format(Locale.CHINA, "%02d:%02d:%02d", t / 3600, (t % 3600) / 60, t % 60);
    }

    private void openSite() {
        try {
            startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(NzcUploader.SITE)));
        } catch (Exception e) {
            Toast.makeText(this, "没有可用的浏览器", Toast.LENGTH_SHORT).show();
        }
    }

    // ================================================================
    //  骑行记录大按钮（开始 / 暂停 / 继续 / 结束）—— 只动 FIT，不影响转发
    // ================================================================

    private void onRecButton() {
        RelayService s = service;
        if (s == null || s.recorder() == null) {
            Toast.makeText(this, "服务未就绪", Toast.LENGTH_SHORT).show();
            return;
        }
        FitRecorder r = s.recorder();
        if (!r.isRecording()) {
            s.startRecording();
            Toast.makeText(this, "开始记录：" + (r.sessionName() == null ? "" : r.sessionName()),
                    Toast.LENGTH_SHORT).show();
        } else if (r.isPaused()) {
            s.resumeRecording();
            Toast.makeText(this, "继续记录（仍是同一个文件）", Toast.LENGTH_SHORT).show();
        } else {
            s.pauseRecording();
            Toast.makeText(this, "已暂停记录（数据转发照旧）", Toast.LENGTH_SHORT).show();
        }
        render();
    }

    private void endRecording() {
        RelayService s = service;
        if (s == null || s.recorder() == null) return;
        FitRecorder r = s.recorder();
        if (!r.isRecording()) {
            Toast.makeText(this, "当前没有在记录", Toast.LENGTH_SHORT).show();
            return;
        }
        String name = r.sessionName();
        s.stopRecording();
        Toast.makeText(this, "记录已结束：" + name + "\n已导出到 下载/XdsRelay/", Toast.LENGTH_LONG).show();
        render();
    }

    /** 顶部大按钮的文案/配色 + 一行状态提示 */
    private void renderRec(RelayService s) {
        if (btnRec == null) return;
        FitRecorder r = s == null ? null : s.recorder();
        if (r == null) {
            btnRec.setEnabled(false);
            btnRec.setText("● 开始记录");
            txtRecHint.setText("记录模块未就绪");
            return;
        }
        btnRec.setEnabled(true);
        String state;
        int tint;
        if (!r.isRecording()) {
            btnRec.setText("● 开始记录");
            tint = R.color.muted;
            state = "未记录";
        } else if (r.isPaused()) {
            btnRec.setText("▶ 继续记录");
            tint = R.color.warn;
            state = "已暂停 · 已记录 " + hms(r.timerSec());
        } else {
            btnRec.setText("⏸ 暂停记录");
            tint = R.color.accent2;
            state = String.format(Locale.CHINA, "记录中 %s · %d 点 · %.2f km",
                    hms(r.timerSec()), r.recordCount(), r.distanceM() / 1000.0);
        }
        btnRec.setBackgroundTintList(ColorStateList.valueOf(getColor(tint)));
        String name = r.sessionName() == null ? "（未开始）" : r.sessionName();
        txtRecHint.setText(name + "\n" + state
                + "　点按：开始/暂停/继续 · 长按：结束并导出");
    }

    private void exportFitNow() {
        if (service == null || service.recorder() == null) {
            Toast.makeText(this, "服务未就绪", Toast.LENGTH_SHORT).show();
            return;
        }
        String where = service.recorder().exportSnapshot();
        Logs.add(TAG, "导出 FIT → " + where);
        Toast.makeText(this, where == null
                ? "还没有记录，先开始骑行"
                : "已导出到 " + where, Toast.LENGTH_LONG).show();
        ui.postDelayed(this::render, 400L);
    }

    private void shareFit() {
        if (service == null || service.recorder() == null) return;
        FitRecorder r = service.recorder();
        java.io.File f = r.currentFile();
        if (f == null || !f.exists()) {
            Toast.makeText(this, "还没有生成 FIT 文件", Toast.LENGTH_SHORT).show();
            return;
        }
        try {
            Uri uri = FileProvider.getUriForFile(this, getPackageName() + ".files", f);
            Intent i = new Intent(Intent.ACTION_SEND);
            i.setType("application/octet-stream");
            i.putExtra(Intent.EXTRA_STREAM, uri);
            i.putExtra(Intent.EXTRA_SUBJECT, f.getName());
            i.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
            startActivity(Intent.createChooser(i, "分享 FIT 文件"));
        } catch (Exception e) {
            Toast.makeText(this, "分享失败：" + e, Toast.LENGTH_LONG).show();
        }
    }

    // ================================================================
    //  后台保活（小米/MIUI 会杀后台，这里给出可一键跳转的设置入口）
    // ================================================================

    private boolean keepAlivePrompted;
    private boolean bgLocationAsked;

    private static final String KEY_KEEPALIVE_SHOWN = "keepalive_shown";
    private static final String KEY_BG_LOCATION_SHOWN = "bg_location_shown";

    private android.content.SharedPreferences prefs() {
        return getSharedPreferences("xdsrelay", MODE_PRIVATE);
    }

    /**
     * 单独申请「后台定位（始终允许）」。
     * 为什么必须单独要：Android 11+ 规定 ACCESS_BACKGROUND_LOCATION 不能和前台定位一起申请，
     * 必须等前台定位拿到之后再单独提一次（系统会直接跳到"始终允许"的授权页）。
     * 不拿这个权限的话，骑行时手机在口袋里（应用退到后台）定位更新会被系统掐掉，
     * 表现就是界面上"刚打开有定位、过一会变成搜星中"。
     */
    private void requestBackgroundLocationIfNeeded() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return;
        if (ActivityCompat.checkSelfPermission(this, Manifest.permission.ACCESS_BACKGROUND_LOCATION)
                == PackageManager.PERMISSION_GRANTED) {
            Logs.add(TAG, "后台定位已是「始终允许」");
            return;
        }
        if (bgLocationAsked) return;
        if (prefs().getBoolean(KEY_BG_LOCATION_SHOWN, false)) {
            Logs.add(TAG, "后台定位之前已经申请过，不再弹窗（可在系统设置里手动改成始终允许）");
            return;
        }
        bgLocationAsked = true;
        prefs().edit().putBoolean(KEY_BG_LOCATION_SHOWN, true).apply();
        ui.postDelayed(() -> {
            try {
                ActivityCompat.requestPermissions(this,
                        new String[]{Manifest.permission.ACCESS_BACKGROUND_LOCATION}, REQ_BG_LOCATION);
                Logs.add(TAG, "已申请后台定位（请选「始终允许」）");
            } catch (Exception e) {
                Logs.add(TAG, "后台定位申请失败: " + e);
            }
        }, 9000L);
    }

    private boolean ignoringBatteryOptimizations() {
        try {
            PowerManager pm = (PowerManager) getSystemService(Context.POWER_SERVICE);
            return pm != null && pm.isIgnoringBatteryOptimizations(getPackageName());
        } catch (Exception e) {
            return false;
        }
    }

    private boolean notificationsAllowed() {
        if (Build.VERSION.SDK_INT < 33) return true;
        return ActivityCompat.checkSelfPermission(this, "android.permission.POST_NOTIFICATIONS")
                == PackageManager.PERMISSION_GRANTED;
    }

    private void showKeepAliveDialog(boolean auto) {
        if (auto && keepAlivePrompted) return;
        keepAlivePrompted = true;
        // 自动弹出只在首次安装后弹一次；之后要看得点界面上的「保活设置」
        if (auto && prefs().getBoolean(KEY_KEEPALIVE_SHOWN, false)) return;
        if (auto) prefs().edit().putBoolean(KEY_KEEPALIVE_SHOWN, true).apply();

        boolean batteryOk = ignoringBatteryOptimizations();
        boolean notifOk = notificationsAllowed();

        String msg = "厂商：" + Build.MANUFACTURER + " " + Build.MODEL
                + "\n电池优化白名单：" + (batteryOk ? "已加入 ✓" : "未加入 ✗")
                + "\n通知权限：" + (notifOk ? "已授予 ✓" : "未授予 ✗")
                + "\n后台定位：「始终允许」才不掉定位（前台定位不算）"
                + "\n\n国产 ROM（MIUI / HyperOS / ColorOS）会额外限制后台：请再到"
                + "「自启动 / 后台运行」里把本应用设为**允许自动启动**、电量策略设为**无限制**，"
                + "否则息屏骑行时中继可能被系统杀掉。";

        MaterialAlertDialogBuilder b = new MaterialAlertDialogBuilder(this)
                .setTitle("后台保活设置")
                .setMessage(msg)
                .setNegativeButton("知道了", null);

        if (!batteryOk) {
            b.setPositiveButton("加入电池白名单", (d, w) -> requestIgnoreBatteryOptimizations());
            b.setNeutralButton("打开自启动设置", (d, w) -> openOemAutoStart());
        } else {
            b.setPositiveButton("打开自启动设置", (d, w) -> openOemAutoStart());
        }
        b.show();
    }

    @SuppressLint("BatteryLife")
    private void requestIgnoreBatteryOptimizations() {
        try {
            Intent i = new Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS);
            i.setData(Uri.parse("package:" + getPackageName()));
            startActivity(i);
            Logs.add(TAG, "已请求加入电池优化白名单");
        } catch (Exception e) {
            try {
                startActivity(new Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS));
            } catch (Exception e2) {
                Toast.makeText(this, "无法打开电池优化设置", Toast.LENGTH_SHORT).show();
            }
        }
    }

    /** 各家的自启动管理页面都不一样，逐个试过去，最后退回应用详情页。 */
    private void openOemAutoStart() {
        String[][] targets = {
                {"com.miui.securitycenter", "com.miui.permcenter.autostart.AutoStartManagementActivity"},
                {"com.miui.securitycenter", "com.miui.powercenter.PowerSettings"},
                {"com.huawei.systemmanager", "com.huawei.systemmanager.startupmgr.ui.StartupNormalAppListActivity"},
                {"com.huawei.systemmanager", "com.huawei.systemmanager.appcontrol.activity.StartupAppControlActivity"},
                {"com.coloros.safecenter", "com.coloros.safecenter.permission.startup.StartupAppListActivity"},
                {"com.coloros.safecenter", "com.coloros.safecenter.startupapp.StartupAppListActivity"},
                {"com.oppo.safe", "com.oppo.safe.permission.startup.StartupAppListActivity"},
                {"com.vivo.permissionmanager", "com.vivo.permissionmanager.activity.BgStartUpManagerActivity"},
                {"com.iqoo.secure", "com.iqoo.secure.ui.phoneoptimize.AddWhiteListActivity"},
                {"com.samsung.android.lool", "com.samsung.android.sm.ui.battery.BatteryActivity"},
                {"com.meizu.safe", "com.meizu.safe.security.SHOW_APPSEC"},
                {"com.letv.android.letvsafe", "com.letv.android.letvsafe.AutobootManageActivity"},
        };
        for (String[] t : targets) {
            try {
                Intent i = new Intent().setComponent(new ComponentName(t[0], t[1]));
                i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                startActivity(i);
                Logs.add(TAG, "已打开自启动设置：" + t[0] + "/" + t[1]);
                return;
            } catch (Exception ignore) {
            }
        }
        try {
            startActivity(new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                    Uri.parse("package:" + getPackageName())));
            Logs.add(TAG, "未找到厂商自启动页面，已打开应用详情页");
        } catch (Exception e) {
            Toast.makeText(this, "无法打开设置页面", Toast.LENGTH_SHORT).show();
        }
    }

    // ================================================================
    //  设备选择
    // ================================================================

    private void showPicker() {
        RelayService s = service;
        if (s == null) {
            Toast.makeText(this, "服务未就绪", Toast.LENGTH_SHORT).show();
            return;
        }
        XdsCentral central = s.central();
        List<BluetoothDevice> devices = central.candidateDevices();
        final String[] names = new String[devices.size()];
        for (int i = 0; i < devices.size(); i++) {
            BluetoothDevice d = devices.get(i);
            String n;
            try {
                n = d.getName();
            } catch (Exception e) {
                n = null;
            }
            names[i] = (n == null || n.isEmpty() ? "(无名设备)" : n) + "\n" + d.getAddress();
        }

        MaterialAlertDialogBuilder b = new MaterialAlertDialogBuilder(this)
                .setTitle("选择要连接的功率计")
                .setNegativeButton("取消", null);

        if (names.length == 0) {
            b.setMessage("还没扫到任何设备。\n请确认功率计已被踩踏唤醒，然后点『重新扫描』。");
        } else {
            b.setItems(names, (d, which) -> {
                BluetoothDevice dev = devices.get(which);
                s.connectTo(dev);
                Toast.makeText(this, "正在连接 " + names[which].split("\n")[0], Toast.LENGTH_SHORT).show();
            });
        }
        b.show();
    }

    // ================================================================
    //  底部三栏导航与切换
    // ================================================================

    private void initNavigationTabs() {
        scrollWorkspace = findViewById(R.id.scrollWorkspace);
        scrollFitList = findViewById(R.id.scrollFitList);
        scrollGpxList = findViewById(R.id.scrollGpxList);

        tabWorkspace = findViewById(R.id.tabWorkspace);
        tabFit = findViewById(R.id.tabFit);
        tabGpx = findViewById(R.id.tabGpx);

        txtTabWorkspace = findViewById(R.id.txtTabWorkspace);
        txtTabFit = findViewById(R.id.txtTabFit);
        txtTabGpx = findViewById(R.id.txtTabGpx);

        indTabWorkspace = findViewById(R.id.indTabWorkspace);
        indTabFit = findViewById(R.id.indTabFit);
        indTabGpx = findViewById(R.id.indTabGpx);

        tabWorkspace.setOnClickListener(v -> switchTab(0));
        tabFit.setOnClickListener(v -> switchTab(1));
        tabGpx.setOnClickListener(v -> switchTab(2));
    }

    private void switchTab(int index) {
        if (scrollWorkspace == null || scrollFitList == null || scrollGpxList == null) return;

        scrollWorkspace.setVisibility(index == 0 ? View.VISIBLE : View.GONE);
        scrollFitList.setVisibility(index == 1 ? View.VISIBLE : View.GONE);
        scrollGpxList.setVisibility(index == 2 ? View.VISIBLE : View.GONE);

        int cAccent = getResources().getColor(R.color.accent, getTheme());
        int cMuted = getResources().getColor(R.color.muted, getTheme());

        txtTabWorkspace.setTextColor(index == 0 ? cAccent : cMuted);
        txtTabFit.setTextColor(index == 1 ? cAccent : cMuted);
        txtTabGpx.setTextColor(index == 2 ? cAccent : cMuted);

        txtTabWorkspace.setTypeface(null, index == 0 ? android.graphics.Typeface.BOLD : android.graphics.Typeface.NORMAL);
        txtTabFit.setTypeface(null, index == 1 ? android.graphics.Typeface.BOLD : android.graphics.Typeface.NORMAL);
        txtTabGpx.setTypeface(null, index == 2 ? android.graphics.Typeface.BOLD : android.graphics.Typeface.NORMAL);

        indTabWorkspace.setVisibility(index == 0 ? View.VISIBLE : View.INVISIBLE);
        indTabFit.setVisibility(index == 1 ? View.VISIBLE : View.INVISIBLE);
        indTabGpx.setVisibility(index == 2 ? View.VISIBLE : View.INVISIBLE);

        if (index == 1) {
            renderFitList();
        } else if (index == 2) {
            renderGpxList();
        }
    }

    // ================================================================
    //  FIT 文件列表与预览
    // ================================================================

    private void initFitPreviewSection() {
        txtFitSummary = findViewById(R.id.txtFitSummary);
        btnFitRefresh = findViewById(R.id.btnFitRefresh);
        cardFitDetail = findViewById(R.id.cardFitDetail);

        txtFitDetailName = findViewById(R.id.txtFitDetailName);
        txtFitDetailTime = findViewById(R.id.txtFitDetailTime);
        txtFitDetailSize = findViewById(R.id.txtFitDetailSize);
        txtFitDetailDuration = findViewById(R.id.txtFitDetailDuration);
        txtFitDetailStatus = findViewById(R.id.txtFitDetailStatus);

        btnFitUpload = findViewById(R.id.btnFitUpload);
        btnFitShare = findViewById(R.id.btnFitShare);
        btnFitDelete = findViewById(R.id.btnFitDelete);
        layoutFitContainer = findViewById(R.id.layoutFitContainer);

        btnFitRefresh.setOnClickListener(v -> renderFitList());

        btnFitUpload.setOnClickListener(v -> {
            if (selectedFit == null || !selectedFit.file.exists()) {
                Toast.makeText(this, "请先选择要上传的 FIT 文件", Toast.LENGTH_SHORT).show();
                return;
            }
            btnFitUpload.setEnabled(false);
            btnFitUpload.setText("正在上传...");
            NzcUploader.upload(selectedFit.file, 1.0, 5.0, 5, 5, result -> runOnUiThread(() -> {
                btnFitUpload.setEnabled(true);
                btnFitUpload.setText("上传到网站");
                if (result.ok) {
                    FitManager.markUploaded(this, selectedFit.name);
                    txtFitDetailStatus.setText("已上传网站");
                    txtFitDetailStatus.setTextColor(getResources().getColor(R.color.ok, getTheme()));
                    Toast.makeText(this, "上传成功: " + result.summary(), Toast.LENGTH_LONG).show();
                    renderFitList();
                } else {
                    Toast.makeText(this, "上传失败: " + result.error, Toast.LENGTH_LONG).show();
                }
            }));
        });

        btnFitShare.setOnClickListener(v -> {
            if (selectedFit != null && selectedFit.file.exists()) {
                FitManager.shareFile(this, selectedFit.file);
            }
        });

        btnFitDelete.setOnClickListener(v -> {
            if (selectedFit == null || !selectedFit.file.exists()) return;
            new MaterialAlertDialogBuilder(this)
                    .setTitle("确认删除")
                    .setMessage("确定要删除记录 " + selectedFit.name + " 吗？")
                    .setNegativeButton("取消", null)
                    .setPositiveButton("删除", (dialog, which) -> {
                        if (selectedFit.file.delete()) {
                            Toast.makeText(this, "已删除", Toast.LENGTH_SHORT).show();
                            selectedFit = null;
                            renderFitList();
                        } else {
                            Toast.makeText(this, "删除失败", Toast.LENGTH_SHORT).show();
                        }
                    })
                    .show();
        });
    }

    private void renderFitList() {
        if (layoutFitContainer == null) return;
        layoutFitContainer.removeAllViews();
        List<FitManager.FitItem> list = FitManager.listFit(this);

        long totalSize = 0;
        for (FitManager.FitItem it : list) totalSize += it.sizeBytes;
        String totalSizeStr = totalSize < 1024 * 1024
                ? String.format(Locale.US, "%.1f KB", totalSize / 1024.0)
                : String.format(Locale.US, "%.2f MB", totalSize / (1024.0 * 1024.0));
        txtFitSummary.setText("共 " + list.size() + " 个记录 · 占用 " + totalSizeStr);

        if (list.isEmpty()) {
            TextView empty = new TextView(this);
            empty.setText("暂无 FIT 骑行记录。\n在工作区点击【开始记录】并在结束后即可生成。");
            empty.setTextColor(getResources().getColor(R.color.muted, getTheme()));
            empty.setPadding(0, 30, 0, 30);
            empty.setGravity(android.view.Gravity.CENTER);
            layoutFitContainer.addView(empty);
            updateFitDetail(null);
            return;
        }

        if (selectedFit == null || !selectedFit.file.exists()) {
            selectedFit = list.get(0);
        }
        updateFitDetail(selectedFit);

        int pad = (int) (12 * getResources().getDisplayMetrics().density);
        int margin = (int) (8 * getResources().getDisplayMetrics().density);

        for (FitManager.FitItem item : list) {
            com.google.android.material.card.MaterialCardView card =
                    new com.google.android.material.card.MaterialCardView(this);
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
            lp.bottomMargin = margin;
            card.setLayoutParams(lp);
            card.setRadius(14 * getResources().getDisplayMetrics().density);
            card.setCardElevation(0);
            card.setStrokeWidth((int) (1 * getResources().getDisplayMetrics().density));

            boolean isSel = selectedFit != null && selectedFit.file.equals(item.file);
            card.setCardBackgroundColor(getResources().getColor(isSel ? R.color.surface2 : R.color.surface, getTheme()));
            card.setStrokeColor(getResources().getColor(isSel ? R.color.accent : R.color.stroke, getTheme()));

            LinearLayout row = new LinearLayout(this);
            row.setOrientation(LinearLayout.VERTICAL);
            row.setPadding(pad, pad, pad, pad);

            TextView title = new TextView(this);
            title.setText(item.name);
            title.setTextColor(getResources().getColor(isSel ? R.color.accent : R.color.text, getTheme()));
            title.setTextSize(15);
            title.setTypeface(null, android.graphics.Typeface.BOLD);

            TextView sub = new TextView(this);
            sub.setText(item.dateStr() + "  ·  " + item.sizeStr() + "  ·  " + item.durationStr()
                    + (item.uploaded ? "  ·  [已传网站]" : ""));
            sub.setTextColor(getResources().getColor(item.uploaded ? R.color.ok : R.color.muted, getTheme()));
            sub.setTextSize(12);
            sub.setPadding(0, 6, 0, 0);

            row.addView(title);
            row.addView(sub);
            card.addView(row);

            card.setOnClickListener(v -> {
                selectedFit = item;
                updateFitDetail(item);
                renderFitList();
            });

            layoutFitContainer.addView(card);
        }
    }

    private void updateFitDetail(FitManager.FitItem item) {
        if (item == null) {
            cardFitDetail.setVisibility(View.GONE);
            return;
        }
        cardFitDetail.setVisibility(View.VISIBLE);
        txtFitDetailName.setText(item.name);
        txtFitDetailTime.setText("时间: " + item.dateStr());
        txtFitDetailSize.setText("大小: " + item.sizeStr());
        txtFitDetailDuration.setText("估算时长: " + item.durationStr() + " (~" + item.estimatedRecords() + " 点)");
        if (item.uploaded) {
            txtFitDetailStatus.setText("已上传网站");
            txtFitDetailStatus.setTextColor(getResources().getColor(R.color.ok, getTheme()));
        } else {
            txtFitDetailStatus.setText("未上传");
            txtFitDetailStatus.setTextColor(getResources().getColor(R.color.muted, getTheme()));
        }
    }

    // ================================================================
    //  GPX 路线索引、预览与蓝牙传输
    // ================================================================

    private void initGpxSection() {
        btnGpxRefresh = findViewById(R.id.btnGpxRefresh);
        btnGpxImport = findViewById(R.id.btnGpxImport);
        btnGpxCreateSample = findViewById(R.id.btnGpxCreateSample);
        cardGpxDetail = findViewById(R.id.cardGpxDetail);

        txtGpxDetailName = findViewById(R.id.txtGpxDetailName);
        txtGpxDetailDist = findViewById(R.id.txtGpxDetailDist);
        txtGpxDetailPoints = findViewById(R.id.txtGpxDetailPoints);
        txtGpxDetailSize = findViewById(R.id.txtGpxDetailSize);

        btnGpxSendBle = findViewById(R.id.btnGpxSendBle);
        layoutGpxProgress = findViewById(R.id.layoutGpxProgress);
        progGpxSend = findViewById(R.id.progGpxSend);
        txtGpxSendStatus = findViewById(R.id.txtGpxSendStatus);

        btnGpxShare = findViewById(R.id.btnGpxShare);
        btnGpxDelete = findViewById(R.id.btnGpxDelete);
        layoutGpxContainer = findViewById(R.id.layoutGpxContainer);

        btnGpxRefresh.setOnClickListener(v -> renderGpxList());

        btnGpxImport.setOnClickListener(v -> {
            try {
                gpxPickerLauncher.launch("*/*");
            } catch (Exception e) {
                Toast.makeText(this, "打开文件选择器失败: " + e.getMessage(), Toast.LENGTH_SHORT).show();
            }
        });

        btnGpxCreateSample.setOnClickListener(v -> {
            File sf = GpxManager.createSampleRoute(this);
            if (sf != null && sf.exists()) {
                selectedGpx = GpxManager.parseGpx(sf);
                renderGpxList();
                Toast.makeText(this, "已生成天大北洋园 6.9km 示例路书", Toast.LENGTH_SHORT).show();
            }
        });

        btnGpxShare.setOnClickListener(v -> {
            if (selectedGpx != null && selectedGpx.file.exists()) {
                FitManager.shareFile(this, selectedGpx.file);
            }
        });

        btnGpxDelete.setOnClickListener(v -> {
            if (selectedGpx == null || !selectedGpx.file.exists()) return;
            new MaterialAlertDialogBuilder(this)
                    .setTitle("确认删除")
                    .setMessage("确定要删除路书 " + selectedGpx.name + " 吗？")
                    .setNegativeButton("取消", null)
                    .setPositiveButton("删除", (d, w) -> {
                        if (selectedGpx.file.delete()) {
                            Toast.makeText(this, "路书已删除", Toast.LENGTH_SHORT).show();
                            selectedGpx = null;
                            renderGpxList();
                        } else {
                            Toast.makeText(this, "删除失败", Toast.LENGTH_SHORT).show();
                        }
                    })
                    .show();
        });

        // 核心：通过蓝牙将选中的 GPX 发送给码表端
        btnGpxSendBle.setOnClickListener(v -> sendSelectedGpxToDisplay());
    }

    private void renderGpxList() {
        if (layoutGpxContainer == null) return;
        layoutGpxContainer.removeAllViews();
        List<GpxManager.GpxItem> list = GpxManager.listGpx(this);

        if (list.isEmpty()) {
            TextView empty = new TextView(this);
            empty.setText("暂无 GPX 路线文件。\n点按上方【📥 导入 GPX】或【✨ 示例路书】即可添加。");
            empty.setTextColor(getResources().getColor(R.color.muted, getTheme()));
            empty.setPadding(0, 30, 0, 30);
            empty.setGravity(android.view.Gravity.CENTER);
            layoutGpxContainer.addView(empty);
            updateGpxDetail(null);
            return;
        }

        if (selectedGpx == null || !selectedGpx.file.exists()) {
            selectedGpx = list.get(0);
        }
        updateGpxDetail(selectedGpx);

        int pad = (int) (12 * getResources().getDisplayMetrics().density);
        int margin = (int) (8 * getResources().getDisplayMetrics().density);

        for (GpxManager.GpxItem item : list) {
            com.google.android.material.card.MaterialCardView card =
                    new com.google.android.material.card.MaterialCardView(this);
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
            lp.bottomMargin = margin;
            card.setLayoutParams(lp);
            card.setRadius(14 * getResources().getDisplayMetrics().density);
            card.setCardElevation(0);
            card.setStrokeWidth((int) (1 * getResources().getDisplayMetrics().density));

            boolean isSel = selectedGpx != null && selectedGpx.file.equals(item.file);
            card.setCardBackgroundColor(getResources().getColor(isSel ? R.color.surface2 : R.color.surface, getTheme()));
            card.setStrokeColor(getResources().getColor(isSel ? R.color.accent : R.color.stroke, getTheme()));

            LinearLayout row = new LinearLayout(this);
            row.setOrientation(LinearLayout.VERTICAL);
            row.setPadding(pad, pad, pad, pad);

            TextView title = new TextView(this);
            title.setText(item.name);
            title.setTextColor(getResources().getColor(isSel ? R.color.accent : R.color.text, getTheme()));
            title.setTextSize(15);
            title.setTypeface(null, android.graphics.Typeface.BOLD);

            TextView sub = new TextView(this);
            sub.setText(String.format(Locale.US, "%.1f km  ·  %d 个轨迹点  ·  %s", item.distanceKm, item.pointsCount, item.sizeStr()));
            sub.setTextColor(getResources().getColor(R.color.muted, getTheme()));
            sub.setTextSize(12);
            sub.setPadding(0, 6, 0, 0);

            row.addView(title);
            row.addView(sub);
            card.addView(row);

            card.setOnClickListener(v -> {
                selectedGpx = item;
                updateGpxDetail(item);
                renderGpxList();
            });

            layoutGpxContainer.addView(card);
        }
    }

    private void updateGpxDetail(GpxManager.GpxItem item) {
        if (item == null) {
            cardGpxDetail.setVisibility(View.GONE);
            return;
        }
        cardGpxDetail.setVisibility(View.VISIBLE);
        txtGpxDetailName.setText(item.name);
        txtGpxDetailDist.setText(String.format(Locale.US, "总距离: %.2f km", item.distanceKm));
        txtGpxDetailPoints.setText("点数: " + item.pointsCount + " 点");
        txtGpxDetailSize.setText("文件: " + item.file.getName() + " (" + item.sizeStr() + ")");
        layoutGpxProgress.setVisibility(View.GONE);
    }

    private void handleImportedGpx(Uri uri) {
        if (uri == null) return;
        try {
            String name = "imported_" + System.currentTimeMillis() + ".gpx";
            android.database.Cursor c = getContentResolver().query(uri, null, null, null, null);
            if (c != null && c.moveToFirst()) {
                int col = c.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME);
                if (col >= 0) name = c.getString(col);
                c.close();
            }
            File imported = GpxManager.importFromUri(this, uri, name);
            if (imported != null && imported.exists()) {
                selectedGpx = GpxManager.parseGpx(imported);
                switchTab(2); // 切到 GPX 栏
                renderGpxList();
                Toast.makeText(this, "成功导入路书: " + selectedGpx.name, Toast.LENGTH_SHORT).show();
            }
        } catch (Exception e) {
            Toast.makeText(this, "导入路书失败: " + e.getMessage(), Toast.LENGTH_SHORT).show();
        }
    }

    /**
     * 执行通过蓝牙将当前选中的 GPX 发送给码表端
     */
    private void sendSelectedGpxToDisplay() {
        if (selectedGpx == null || !selectedGpx.file.exists()) {
            Toast.makeText(this, "请先选择一条 GPX 路线", Toast.LENGTH_SHORT).show();
            return;
        }
        RelayService s = service;
        if (s == null || s.link() == null || !s.link().isConnected()) {
            new MaterialAlertDialogBuilder(this)
                    .setTitle("未连接码表端")
                    .setMessage("通过蓝牙传输路书需要先与码表端建立 BLE 直连。\n\n请在『中继工作区』启动中继，并确认码表端已打开正在搜寻中继。")
                    .setPositiveButton("知道了", null)
                    .show();
            return;
        }

        btnGpxSendBle.setEnabled(false);
        layoutGpxProgress.setVisibility(View.VISIBLE);
        progGpxSend.setProgress(0);
        txtGpxSendStatus.setText("正在准备发送 " + selectedGpx.name + "...");

        s.link().sendGpxFile(selectedGpx.file, new BleLink.GpxTransferCallback() {
            @Override
            public void onStart(int totalBytes, int totalChunks) {
                runOnUiThread(() -> txtGpxSendStatus.setText("正在通过蓝牙发送: 0/" + totalChunks + "包 (0%)"));
            }

            @Override
            public void onProgress(int sentBytes, int totalBytes, int chunkIdx, int totalChunks) {
                runOnUiThread(() -> {
                    int pct = (int) (sentBytes * 100.0 / Math.max(1, totalBytes));
                    progGpxSend.setProgress(pct);
                    txtGpxSendStatus.setText(String.format(Locale.CHINA, "正在发送: %d/%d包 (%d%%) · %d/%d B",
                            chunkIdx, totalChunks, pct, sentBytes, totalBytes));
                });
            }

            @Override
            public void onSuccess() {
                runOnUiThread(() -> {
                    btnGpxSendBle.setEnabled(true);
                    progGpxSend.setProgress(100);
                    txtGpxSendStatus.setText("✓ 发送完成！码表端已成功加载路书并在地图渲染！");
                    txtGpxSendStatus.setTextColor(getResources().getColor(R.color.ok, getTheme()));
                    Toast.makeText(MainActivity.this, "✓ 路书已成功传输至码表端并在地图渲染！", Toast.LENGTH_LONG).show();
                });
            }

            @Override
            public void onError(String reason) {
                runOnUiThread(() -> {
                    btnGpxSendBle.setEnabled(true);
                    txtGpxSendStatus.setText("传输失败: " + reason);
                    txtGpxSendStatus.setTextColor(getResources().getColor(R.color.err, getTheme()));
                    Toast.makeText(MainActivity.this, "蓝牙传输失败: " + reason, Toast.LENGTH_LONG).show();
                });
            }
        });
    }
}
