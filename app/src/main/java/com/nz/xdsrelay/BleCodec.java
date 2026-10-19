package com.nz.xdsrelay;

import java.nio.charset.StandardCharsets;

/**
 * 把 XDS 私有数据编码成 Bluetooth SIG 标准格式，供任何码表/手表/App 直接读取。
 *
 * 1) 骑行功率测量 Cycling Power Measurement (0x2A63)
 *      flags(2) + 瞬时功率(2) + [左右平衡(1)] + [曲柄转数(2) + 曲柄事件时间(2)]
 * 2) 定位与速度 Location and Speed (0x2A67)
 *      flags(2) + 瞬时速度(2) + 纬度(4) + 经度(4) + 海拔(4) + 航向(2)  = 18 字节，默认 MTU 下即可完整通知
 */
public final class BleCodec {

    private BleCodec() {}

    /** 是否把左/右腿瓦数换算成 0.5% 分辨率的踩踏平衡一起广播 */
    public static final boolean INCLUDE_PEDAL_BALANCE = true;

    // ==================== Cycling Power Measurement ====================

    public static final int CPS_FLAG_PEDAL_BALANCE_PRESENT   = 1 << 0;
    public static final int CPS_FLAG_PEDAL_BALANCE_LEFT_REF  = 1 << 1;
    public static final int CPS_FLAG_ACC_TORQUE_PRESENT      = 1 << 2;
    public static final int CPS_FLAG_WHEEL_REV_PRESENT       = 1 << 4;
    public static final int CPS_FLAG_CRANK_REV_PRESENT       = 1 << 5;
    public static final int CPS_FLAG_EXTREME_ANGLES_PRESENT  = 1 << 8;

    /**
     * @param powerW            瞬时功率 W
     * @param balanceLeftPct    左腿百分比 0..100，<0 表示未知
     * @param cumCrankRevs      累计曲柄转数 (uint16 回绕)
     * @param lastCrankEvent    最后一次曲柄事件时间，单位 1/1024 s
     */
    public static byte[] cpsMeasurement(int powerW,
                                        double balanceLeftPct,
                                        int cumCrankRevs,
                                        int lastCrankEvent) {
        boolean withBalance = INCLUDE_PEDAL_BALANCE && balanceLeftPct >= 0.0;

        int flags = CPS_FLAG_CRANK_REV_PRESENT;
        if (withBalance) {
            flags |= CPS_FLAG_PEDAL_BALANCE_PRESENT;
            flags |= CPS_FLAG_PEDAL_BALANCE_LEFT_REF;
        }

        int size = 2 + 2 + (withBalance ? 1 : 0) + 4;
        byte[] p = new byte[size];
        int i = 0;

        putU16(p, i, flags);                         i += 2;
        putS16(p, i, clampS16(powerW));              i += 2;

        if (withBalance) {
            // 0.5 % / bit
            int bal = (int) Math.round(Math.max(0.0, Math.min(100.0, balanceLeftPct)) * 2.0);
            p[i] = (byte) (bal & 0xFF);              i += 1;
        }

        putU16(p, i, cumCrankRevs & 0xFFFF);         i += 2;
        putU16(p, i, lastCrankEvent & 0xFFFF);       i += 2;

        return p;
    }

    /** Cycling Power Feature (0x2A65)：支持曲柄转数数据 */
    public static byte[] cpsFeature() {
        int feature = 1 << 3;   // Crank Revolution Data Supported
        if (INCLUDE_PEDAL_BALANCE) feature |= 1 << 0;  // Pedal Power Balance Supported
        byte[] p = new byte[4];
        putU32(p, 0, feature);
        return p;
    }

    /** Sensor Location (0x2A5B)：5 = 左曲柄 */
    public static byte[] sensorLocation() {
        return new byte[]{(byte) 5};
    }

    // ==================== Location and Speed ====================

    public static final int LN_FLAG_SPEED_PRESENT    = 1 << 0;
    public static final int LN_FLAG_DISTANCE_PRESENT = 1 << 1;
    public static final int LN_FLAG_LOCATION_PRESENT = 1 << 2;
    public static final int LN_FLAG_ELEVATION_PRESENT= 1 << 3;
    public static final int LN_FLAG_HEADING_PRESENT  = 1 << 4;
    public static final int LN_FLAG_ROLLING_TIME     = 1 << 5;
    public static final int LN_FLAG_UTC_TIME         = 1 << 6;
    public static final int LN_FLAG_ELEVATION_BARO   = 1 << 11;

    public static final int LN_POS_STATUS_NO_FIX   = 0;
    public static final int LN_POS_STATUS_OK       = 1;
    public static final int LN_POS_STATUS_ESTIMATED= 2;
    public static final int LN_POS_STATUS_LAST     = 3;

    /**
     * @param speedMps  瞬时速度 m/s
     * @param latDeg    纬度，度
     * @param lonDeg    经度，度
     * @param elevM     海拔，米
     * @param headingDeg航向，度
     * @param posStatus 定位状态
     * @param elevFromBarometer 海拔是否来自气压计
     * @param hasLocation 是否包含经纬度
     */
    public static byte[] lnLocationAndSpeed(float speedMps,
                                            double latDeg,
                                            double lonDeg,
                                            double elevM,
                                            float headingDeg,
                                            int posStatus,
                                            boolean elevFromBarometer,
                                            boolean hasLocation) {
        int flags = LN_FLAG_SPEED_PRESENT | LN_FLAG_ELEVATION_PRESENT;
        if (hasLocation) flags |= LN_FLAG_LOCATION_PRESENT;
        if (headingDeg >= 0f) flags |= LN_FLAG_HEADING_PRESENT;
        if (elevFromBarometer) flags |= LN_FLAG_ELEVATION_BARO;
        // Position Status 占 bit7..bit8
        flags |= ((posStatus & 0x03) << 7);

        int size = 2 + 2 + (hasLocation ? 8 : 0) + 4 + (headingDeg >= 0f ? 2 : 0);
        byte[] p = new byte[size];
        int i = 0;

        putU16(p, i, flags); i += 2;

        // 瞬时速度：m/s，分辨率 1/256
        int speedRaw = Math.round(Math.max(0f, speedMps) * 256f);
        putU16(p, i, Math.min(speedRaw, 0xFFFF)); i += 2;

        if (hasLocation) {
            putS32(p, i, (int) Math.round(latDeg * 1e7)); i += 4;
            putS32(p, i, (int) Math.round(lonDeg * 1e7)); i += 4;
        }

        // 海拔：0.01 m
        putS32(p, i, (int) Math.round(elevM * 100.0)); i += 4;

        if (headingDeg >= 0f) {
            int headRaw = Math.round(headingDeg * 100f) % 36000;
            if (headRaw < 0) headRaw += 36000;
            putU16(p, i, headRaw); i += 2;
        }

        return p;
    }

    /** LN Feature (0x2A6A)：支持瞬时速度 / 位置 / 海拔 / 航向 */
    public static byte[] lnFeature() {
        int f = LN_FLAG_SPEED_PRESENT | LN_FLAG_LOCATION_PRESENT
                | LN_FLAG_ELEVATION_PRESENT | LN_FLAG_HEADING_PRESENT;
        f |= 1 << 13;  // Estimated Horizontal Position Error Supported
        f |= 1 << 14;  // Estimated Vertical Position Error Supported
        f |= 1 << 20;  // Elevation Setting Supported
        byte[] p = new byte[4];
        putU32(p, 0, f);
        return p;
    }

    public static byte[] utf8(String s, int maxBytes) {
        byte[] raw = s.getBytes(StandardCharsets.UTF_8);
        if (raw.length <= maxBytes) return raw;
        byte[] cut = new byte[maxBytes];
        System.arraycopy(raw, 0, cut, 0, maxBytes);
        return cut;
    }

    // ==================== 小工具 ====================

    private static int clampS16(int v) {
        if (v > 32767) return 32767;
        if (v < -32768) return -32768;
        return v;
    }

    public static void putU16(byte[] b, int o, int v) {
        b[o] = (byte) (v & 0xFF);
        b[o + 1] = (byte) ((v >> 8) & 0xFF);
    }

    public static void putS16(byte[] b, int o, int v) {
        putU16(b, o, v & 0xFFFF);
    }

    public static void putU32(byte[] b, int o, long v) {
        b[o] = (byte) (v & 0xFF);
        b[o + 1] = (byte) ((v >> 8) & 0xFF);
        b[o + 2] = (byte) ((v >> 16) & 0xFF);
        b[o + 3] = (byte) ((v >> 24) & 0xFF);
    }

    public static void putS32(byte[] b, int o, int v) {
        putU32(b, o, v & 0xFFFFFFFFL);
    }
}
