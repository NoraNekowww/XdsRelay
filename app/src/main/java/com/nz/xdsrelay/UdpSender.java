package com.nz.xdsrelay;

import android.content.Context;

import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.NetworkInterface;
import java.util.ArrayList;
import java.util.Enumeration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * WiFi 直连发送端（主机侧）。
 *
 * 把跟 BLE 广播里一模一样的 20 字节遥测负载，改用 UDP 发给从机：
 * 本机开热点 → 从机连上 → 从机每 2 秒报到一次 → 我们记下它的地址后单播给它。
 * 延迟比 BLE 广播低一个量级，且可以轻松跑 20Hz 以上。BLE 广播照常并行，互为兜底。
 *
 * 热点本身不用 App 开（Android 不给第三方 App 静默开热点的权限），
 * 用户在系统设置里打开即可，这边一旦有从机报到就自动开始发。
 */
public class UdpSender {

    private static final String TAG = "WIFI";
    public static final int PORT = 47820;

    private static final byte[] MAGIC_W = {'X', 'D', 'S', 'W'};
    private static final byte[] MAGIC_H = {'X', 'D', 'S', 'H'};
    /** 控制帧（从机 → 主机）：仪表端按暂停/继续/结束时下发 */
    private static final byte[] MAGIC_C = {'X', 'D', 'S', 'C'};
    private static final int VERSION = 1;
    private static final int PAYLOAD_LEN = 20;

    // ---- 从机下发的控制命令 ----
    /** 通路自检（从机启动时用来验证控制帧能收回来） */
    public static final int CMD_PING = 0;
    public static final int CMD_PAUSE = 1;
    public static final int CMD_RESUME = 2;
    public static final int CMD_TOGGLE = 3;
    public static final int CMD_END = 4;
    public static final int CMD_START = 5;

    public interface ControlListener {
        void onControl(int cmd);
    }

    private volatile ControlListener controlListener;
    private volatile long controlRxCount;

    public void setControlListener(ControlListener l) {
        controlListener = l;
    }

    public long controlRxCount() {
        return controlRxCount;
    }

    public static String cmdName(int cmd) {
        switch (cmd) {
            case CMD_PING: return "通路自检";
            case CMD_PAUSE: return "暂停记录";
            case CMD_RESUME: return "继续记录";
            case CMD_TOGGLE: return "暂停/继续";
            case CMD_END: return "结束记录并导出";
            case CMD_START: return "开始新记录";
            default: return "未知(" + cmd + ")";
        }
    }

    /** 数据没变时的保活间隔（和 BLE 那边一致） */
    private static final long KEEPALIVE_MS = 400L;
    /** 两次发送的最小间隔：撑到 50Hz 也够，但没必要更密 */
    private static final long MIN_GAP_MS = 20L;
    /** 多久没报到就把从机忘掉 */
    private static final long CLIENT_TIMEOUT_MS = 8000L;
    /** 一台从机都没报到时，用广播找人；有从机在册时也按这个节奏兜底广播一次 */
    private static final long BCAST_MS = 1000L;
    /** 从机多久没报到就在状态/日志里告警（单播没有回执，"已发 N 包"不等于它还收得到） */
    private static final long CLIENT_WARN_MS = 5000L;

    private static final class Client {
        InetAddress addr;
        int port;
        long lastSeenMs;
        long sent;
    }

    private final Context ctx;
    private final Map<String, Client> clients = new ConcurrentHashMap<>();
    private final List<InetAddress> bcastAddrs = new ArrayList<>();

    private DatagramSocket socket;
    private Thread rxThread;
    private volatile boolean running;

    private volatile byte[] lastPayload;
    private long lastSendMs;
    private long lastBcastMs;
    private long lastClientWarnMs;
    private volatile long sentPackets;
    private volatile String lastError = "";

    public UdpSender(Context c) {
        this.ctx = c.getApplicationContext();
    }

    public boolean isRunning() {
        return running;
    }

    public int clientCount() {
        pruneClients();
        return clients.size();
    }

    public long sentPackets() {
        return sentPackets;
    }

    public String lastError() {
        return lastError;
    }

    public synchronized boolean start() {
        if (running) return true;
        try {
            socket = new DatagramSocket(null);
            socket.setReuseAddress(true);
            socket.setBroadcast(true);
            socket.bind(new InetSocketAddress(PORT));
        } catch (Exception e) {
            lastError = String.valueOf(e);
            Logs.add(TAG, "WiFi 发送端启动失败：" + e);
            socket = null;
            return false;
        }
        running = true;
        bcastAddrs.clear();
        bcastAddrs.addAll(collectBroadcastAddrs());
        rxThread = new Thread(this::rxLoop, "xds-udp-ctrl");
        rxThread.setDaemon(true);
        rxThread.start();
        startControlSelfTest();
        Logs.add(TAG, "WiFi 直连发送端已就绪（UDP " + PORT + "），等从机报到");
        return true;
    }

    /**
     * 控制通路自检：往自己的监听端口发一帧控制包（CMD_PING，对记录没有任何副作用）。
     * 能收回来就说明"从机 → 主机"的控制帧收包+解析这条通路是通的 —— 和显示端那边的
     * UDP 回环自检是一个道理，省得怀疑半天到底是没收到还是没处理。
     */
    private void startControlSelfTest() {
        Thread t = new Thread(() -> {
            sleep(500);
            try {
                DatagramSocket s = socket;
                if (s == null) return;
                byte[] f = {MAGIC_C[0], MAGIC_C[1], MAGIC_C[2], MAGIC_C[3],
                        (byte) VERSION, (byte) CMD_PING};
                s.send(new DatagramPacket(f, f.length, InetAddress.getByName("127.0.0.1"), PORT));
            } catch (Exception e) {
                Logs.add(TAG, "控制通路自检发送失败：" + e);
            }
            sleep(1500);
            Logs.add(TAG, controlRxCount > 0
                    ? "控制通路自检通过：能收到并解析从机的控制帧"
                    : "⚠ 控制通路自检未通过：收不到控制帧（端口被占？）");
        }, "xds-udp-ctrltest");
        t.setDaemon(true);
        t.start();
    }

    public synchronized void stop() {
        running = false;
        try {
            if (socket != null) socket.close();
        } catch (Exception ignore) {
        }
        socket = null;
        clients.clear();
        Logs.add(TAG, "WiFi 发送端已停止");
    }

    /**
     * 发一帧遥测。由遥测 tick 每 50ms 调一次，内部自己判断要不要真发：
     * 数据变了立刻发，没变就按保活间隔发。
     */
    public void send(byte[] payload) {
        if (!running || payload == null || payload.length < PAYLOAD_LEN) return;
        long now = System.currentTimeMillis();
        boolean changed = lastPayload == null || !java.util.Arrays.equals(lastPayload, payload);
        if (!changed && now - lastSendMs < KEEPALIVE_MS) return;
        if (now - lastSendMs < MIN_GAP_MS) return;

        lastPayload = payload.clone();
        lastSendMs = now;

        byte[] frame = new byte[5 + PAYLOAD_LEN];
        frame[0] = MAGIC_W[0];
        frame[1] = MAGIC_W[1];
        frame[2] = MAGIC_W[2];
        frame[3] = MAGIC_W[3];
        frame[4] = (byte) VERSION;
        System.arraycopy(payload, 0, frame, 5, PAYLOAD_LEN);

        pruneClients();
        DatagramSocket s = socket;
        if (s == null) return;

        int ok = 0;
        if (!clients.isEmpty()) {
            // 优先单播：热点下广播经常被过滤，单播最可靠
            for (Client c : clients.values()) {
                try {
                    s.send(new DatagramPacket(frame, frame.length, c.addr, c.port));
                    c.sent++;
                    ok++;
                } catch (Exception ignore) {
                }
            }
            // 单播是"发出去就算成功"，UDP 没有回执：从机一旦换了 IP / 掉出热点，
            // 我们这边看起来照发不误。所以它多久没报到要说出来 —— 别让"已发 N 包"
            // 把人骗了（2026-10-10 真骑 bug：主机显示正常转发，仪表数据冻了整趟）。
            long silent = newestClientSilenceMs();
            if (silent >= CLIENT_WARN_MS) {
                if (now - lastClientWarnMs > 10000L) {
                    lastClientWarnMs = now;
                    Logs.add(TAG, "⚠ 从机已 " + (silent / 1000) + " 秒没报到（单播照发，但不保证它收得到）");
                }
            } else if (lastClientWarnMs != 0) {
                lastClientWarnMs = 0;       // 恢复过，下次再断还能再报一次
            }
        }
        // 兜底广播：就算有从机在册也照发（每 BCAST_MS 一次）——从机换 IP 之后
        // 单播目标早就失效，只有广播能把它重新找回来
        if (now - lastBcastMs > BCAST_MS) {
            lastBcastMs = now;
            for (InetAddress a : bcastAddrs) {
                try {
                    s.send(new DatagramPacket(frame, frame.length, a, PORT));
                    ok++;
                } catch (Exception ignore) {
                }
            }
            try {
                s.send(new DatagramPacket(frame, frame.length,
                        InetAddress.getByName("255.255.255.255"), PORT));
                ok++;
            } catch (Exception ignore) {
            }
        }
        if (ok > 0) sentPackets++;
    }

    /** 状态一行，给界面显示 */
    public String status() {
        if (!running) return lastError.isEmpty() ? "未启用" : ("启动失败 " + lastError);
        int n = clientCount();
        if (n == 0) return "无从机（等连接热点）";
        long silent = newestClientSilenceMs();
        if (silent >= CLIENT_WARN_MS) {
            // 从机没在报到 → 它多半已经收不到了（单播发出就算成功，UDP 没有回执）
            return "已连 " + n + " 台从机 · " + (silent / 1000) + " 秒没报到 ⚠";
        }
        return "已连 " + n + " 台从机 · 已发 " + sentPackets + " 包";
    }

    /** 最新那台从机多久没报到了（毫秒，-1 = 根本没从机）—— 界面和日志都用它说人话 */
    public long newestClientSilenceMs() {
        long newest = -1;
        for (Client c : clients.values()) {
            if (c.lastSeenMs > newest) newest = c.lastSeenMs;
        }
        return newest <= 0 ? -1L : System.currentTimeMillis() - newest;
    }

    // ================================================================

    private void rxLoop() {
        byte[] buf = new byte[128];
        while (running) {
            DatagramSocket s = socket;
            if (s == null) break;
            try {
                DatagramPacket p = new DatagramPacket(buf, buf.length);
                s.receive(p);
                int len = p.getLength();
                if (len < 5) continue;
                // 控制帧：仪表端（从机）下发暂停/继续/结束
                if (len >= 6 && buf[0] == MAGIC_C[0] && buf[1] == MAGIC_C[1]
                        && buf[2] == MAGIC_C[2] && buf[3] == MAGIC_C[3]) {
                    if ((buf[4] & 0xFF) != VERSION) continue;
                    int cmd = buf[5] & 0xFF;
                    controlRxCount++;
                    Logs.add(TAG, "收到从机控制命令：" + cmdName(cmd)
                            + "（来自 " + p.getAddress().getHostAddress() + "）");
                    ControlListener cl = controlListener;
                    if (cl != null) cl.onControl(cmd);
                    continue;
                }
                if (buf[0] != MAGIC_H[0] || buf[1] != MAGIC_H[1]
                        || buf[2] != MAGIC_H[2] || buf[3] != MAGIC_H[3]) continue;
                if ((buf[4] & 0xFF) != VERSION) continue;

                String key = p.getAddress().getHostAddress() + ":" + p.getPort();
                Client c = clients.get(key);
                boolean isNew = false;
                if (c == null) {
                    c = new Client();
                    c.addr = p.getAddress();
                    c.port = p.getPort();
                    clients.put(key, c);
                    isNew = true;
                }
                c.lastSeenMs = System.currentTimeMillis();
                if (isNew) Logs.add(TAG, "从机报到：" + key + "，开始给它发遥测");
            } catch (Exception e) {
                if (!running) break;
                sleep(300);
            }
        }
    }

    private void pruneClients() {
        long now = System.currentTimeMillis();
        for (Map.Entry<String, Client> e : clients.entrySet()) {
            if (now - e.getValue().lastSeenMs > CLIENT_TIMEOUT_MS) {
                clients.remove(e.getKey());
                Logs.add(TAG, "从机离线：" + e.getKey());
            }
        }
    }

    /** 找出各网卡的广播地址；Android 常把 getBroadcast() 抹掉，所以带几个兜底 */
    private List<InetAddress> collectBroadcastAddrs() {
        List<InetAddress> out = new ArrayList<>();
        try {
            Enumeration<NetworkInterface> e = NetworkInterface.getNetworkInterfaces();
            while (e != null && e.hasMoreElements()) {
                NetworkInterface ni = e.nextElement();
                if (ni == null || !ni.isUp() || ni.isLoopback()) continue;
                for (java.net.InterfaceAddress ia : ni.getInterfaceAddresses()) {
                    if (ia.getBroadcast() != null) out.add(ia.getBroadcast());
                }
            }
        } catch (Exception ignore) {
        }
        // 安卓热点默认网段（小米/一加都是这个）
        for (String s : new String[]{"192.168.43.255", "192.168.42.255"}) {
            try {
                out.add(InetAddress.getByName(s));
            } catch (Exception ignore) {
            }
        }
        return out;
    }

    private static void sleep(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException ignore) {
        }
    }
}
