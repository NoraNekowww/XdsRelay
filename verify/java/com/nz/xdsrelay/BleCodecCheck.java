package com.nz.xdsrelay;

import java.util.ArrayList;
import java.util.List;

/**
 * 独立校验标准蓝牙负载的字节布局与标度。
 * 这里按 Bluetooth SIG 规格手写"解码器"（不复用 BleCodec 的编码逻辑），
 * 对 BleCodec 编出来的字节做往返验证，避免自证。
 */
public class BleCodecCheck {

    private static int failures = 0;
    private static final List<String> failed = new ArrayList<>();

    public static void main(String[] args) {
        checkCpsMeasurement();
        checkCpsFeature();
        checkLnLocationAndSpeed();
        checkLnFeature();

        if (failures == 0) {
            System.out.println("ALL CODEC CHECKS PASSED");
        } else {
            System.out.println("FAILURES: " + failures);
            for (String f : failed) System.out.println("  - " + f);
            System.exit(1);
        }
    }

    // ---------------- Cycling Power Measurement (0x2A63) ----------------

    private static void checkCpsMeasurement() {
        int power = 200;
        double balanceLeft = 50.0;
        int cumRevs = 1234;
        int crankEvent = 5000;

        byte[] p = BleCodec.cpsMeasurement(power, balanceLeft, cumRevs, crankEvent);

        eq("cps length", 9, p.length);

        int flags = u16(p, 0);
        eq("cps flag crank rev (bit5)", 0x0020, flags & 0x0020);
        if (BleCodec.INCLUDE_PEDAL_BALANCE) {
            eq("cps flag balance present (bit0)", 0x0001, flags & 0x0001);
            eq("cps flag balance ref left (bit1)", 0x0002, flags & 0x0002);
        }

        eq("cps instantaneous power", power, s16(p, 2));

        int off = 4;
        if (BleCodec.INCLUDE_PEDAL_BALANCE) {
            // 0.5 % / bit -> 50% == 100
            eq("cps pedal balance raw", 100, p[4] & 0xFF);
            off = 5;
        }

        eq("cps cumulative crank revolutions", cumRevs, u16(p, off));
        eq("cps last crank event time", crankEvent, u16(p, off + 2));

        // 无左右数据时不应带平衡字节
        byte[] p2 = BleCodec.cpsMeasurement(150, -1.0, 7, 100);
        eq("cps no-balance length", 8, p2.length);
        eq("cps no-balance flags", 0x0020, u16(p2, 0));
        eq("cps no-balance power", 150, s16(p2, 2));
        eq("cps no-balance revs", 7, u16(p2, 4));
        eq("cps no-balance event", 100, u16(p2, 6));

        // 负功率与回绕
        byte[] p3 = BleCodec.cpsMeasurement(-30, -1.0, 0xFFFF, 0xFFFF);
        eq("cps negative power", -30, s16(p3, 2));
        eq("cps wrapped revs", 0xFFFF, u16(p3, 4));
        eq("cps wrapped event", 0xFFFF, u16(p3, 6));
    }

    private static void checkCpsFeature() {
        byte[] f = BleCodec.cpsFeature();
        eq("cps feature length", 4, f.length);
        int v = (u16(f, 0)) | (u16(f, 2) << 16);
        ok("cps feature crank rev supported (bit3)", (v & (1 << 3)) != 0);
    }

    // ---------------- Location and Speed (0x2A67) ----------------

    private static void checkLnLocationAndSpeed() {
        float speed = 5.5f;
        double lat = 39.9042, lon = 116.4074, elev = 43.21;
        float heading = 180.5f;

        byte[] p = BleCodec.lnLocationAndSpeed(speed, lat, lon, elev, heading,
                BleCodec.LN_POS_STATUS_OK, false, true);

        eq("ln length (18B, fits default 20B notification)", 18, p.length);

        int flags = u16(p, 0);
        ok("ln flag speed present (bit0)", (flags & (1 << 0)) != 0);
        ok("ln flag location present (bit2)", (flags & (1 << 2)) != 0);
        ok("ln flag elevation present (bit3)", (flags & (1 << 3)) != 0);
        ok("ln flag heading present (bit4)", (flags & (1 << 4)) != 0);
        eq("ln position status = OK (bits7-8)", BleCodec.LN_POS_STATUS_OK, (flags >> 7) & 0x03);
        eq("ln elevation source = GPS (bit11 = 0)", 0, (flags >> 11) & 0x01);

        // 瞬时速度 1/256 m/s
        int speedRaw = u16(p, 2);
        near("ln speed decode", speed, speedRaw / 256.0, 1.0 / 256.0 + 1e-9);

        // 经纬度 1e-7 度
        int latRaw = s32(p, 4);
        int lonRaw = s32(p, 8);
        near("ln latitude decode", lat, latRaw / 1e7, 1e-7 + 1e-12);
        near("ln longitude decode", lon, lonRaw / 1e7, 1e-7 + 1e-12);

        // 海拔 0.01 m
        int elevRaw = s32(p, 12);
        near("ln elevation decode", elev, elevRaw / 100.0, 0.01 + 1e-9);

        // 航向 0.01 度
        int headRaw = u16(p, 16);
        near("ln heading decode", heading, headRaw / 100.0, 0.01 + 1e-9);

        // 负海拔与南纬西经
        byte[] p2 = BleCodec.lnLocationAndSpeed(0f, -33.8688, -151.2093, -7.5, -1f,
                BleCodec.LN_POS_STATUS_OK, false, true);
        near("ln negative latitude", -33.8688, s32(p2, 4) / 1e7, 1e-7 + 1e-12);
        near("ln negative longitude", -151.2093, s32(p2, 8) / 1e7, 1e-7 + 1e-12);
        near("ln negative elevation", -7.5, s32(p2, 12) / 100.0, 0.01 + 1e-9);
        ok("ln heading absent flag cleared", (u16(p2, 0) & (1 << 4)) == 0);
        eq("ln length without heading", 16, p2.length);

        // 未定位：不带经纬度
        byte[] p3 = BleCodec.lnLocationAndSpeed(0f, 0, 0, 100, -1f,
                BleCodec.LN_POS_STATUS_NO_FIX, false, false);
        ok("ln location flag cleared when no fix", (u16(p3, 0) & (1 << 2)) == 0);
        eq("ln length without location", 8, p3.length);
        eq("ln no-fix position status", BleCodec.LN_POS_STATUS_NO_FIX, (u16(p3, 0) >> 7) & 0x03);
    }

    private static void checkLnFeature() {
        byte[] f = BleCodec.lnFeature();
        eq("ln feature length", 4, f.length);
        int v = (u16(f, 0)) | (u16(f, 2) << 16);
        ok("ln feature speed supported", (v & 1) != 0);
        ok("ln feature location supported", (v & (1 << 2)) != 0);
        ok("ln feature elevation supported", (v & (1 << 3)) != 0);
        ok("ln feature heading supported", (v & (1 << 4)) != 0);
    }

    // ---------------- 断言工具 ----------------

    private static void eq(String what, int expect, int actual) {
        if (expect != actual) fail(what + ": expect " + expect + " got " + actual);
    }

    private static void near(String what, double expect, double actual, double tol) {
        if (Math.abs(expect - actual) > tol) {
            fail(what + ": expect " + expect + " got " + actual + " (tol " + tol + ")");
        }
    }

    private static void ok(String what, boolean cond) {
        if (!cond) fail(what + ": false");
    }

    private static void fail(String msg) {
        failures++;
        failed.add(msg);
    }

    private static int u16(byte[] b, int o) {
        return (b[o] & 0xFF) | ((b[o + 1] & 0xFF) << 8);
    }

    private static int s16(byte[] b, int o) {
        return (short) u16(b, o);
    }

    private static int s32(byte[] b, int o) {
        return (b[o] & 0xFF) | ((b[o + 1] & 0xFF) << 8) | ((b[o + 2] & 0xFF) << 16)
                | ((b[o + 3] & 0xFF) << 24);
    }
}
