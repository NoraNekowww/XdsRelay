package com.nz.xdsrelay;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;

/**
 * 极简 FIT (Flexible and Interoperable Data Transfer) 写入器。
 *
 * 字段编号与标度完全对齐官方 FIT profile（也用 https://nzlab.online 的样例文件核对过）：
 *   record(20):  253 timestamp | 0 position_lat | 1 position_long | 5 distance(1/100 m)
 *                2 altitude(1/5 m, +500) | 78 enhanced_altitude | 31 gps_accuracy
 *                6 speed(1/1000 m/s) | 73 enhanced_speed | 4 cadence | 7 power
 *   lap(19)/session(18)/activity(34)/event(21)/file_id(0) 见下方常量
 *
 * 记录先攒在内存里，需要时用 {@link #build} 生成一个完整可解析的文件，
 * 这样"边骑边传"的时候随时都能拿到一份合法 FIT。
 */
public final class FitWriter {

    private FitWriter() {}

    /** FIT 纪元：1989-12-31T00:00:00Z */
    public static final long FIT_EPOCH_OFFSET = 631065600L;

    // ---------- base type ----------
    private static final int BT_ENUM    = 0x00;
    private static final int BT_UINT8   = 0x02;
    private static final int BT_UINT16  = 0x84;
    private static final int BT_SINT32  = 0x85;
    private static final int BT_UINT32  = 0x86;

    // ---------- 无效值 ----------
    public static final int  U8_INVALID  = 0xFF;
    public static final int  U16_INVALID = 0xFFFF;
    public static final long U32_INVALID = 0xFFFFFFFFL;
    public static final int  S32_INVALID = 0x7FFFFFFF;

    // ---------- 本地消息号 ----------
    private static final int L_FILE_ID  = 0;
    private static final int L_EVENT    = 1;
    private static final int L_RECORD   = 2;
    private static final int L_LAP      = 3;
    private static final int L_SESSION  = 4;
    private static final int L_ACTIVITY = 5;

    // ---------- 全局消息号 ----------
    private static final int M_FILE_ID  = 0;
    private static final int M_SESSION  = 18;
    private static final int M_LAP      = 19;
    private static final int M_RECORD   = 20;
    private static final int M_EVENT    = 21;
    private static final int M_ACTIVITY = 34;

    // ================================================================
    //  一帧记录
    // ================================================================

    public static final class Sample {
        public long   timestampSec;      // UTC 秒
        public double lat, lon;          // 度；NaN = 无定位
        public double altitudeM;         // 米；NaN = 无
        public double distanceM;         // 累计米
        public double speedMps;
        public int    cadence;           // -1 = 无效
        public int    power;             // -1 = 无效
        public int    gpsAccuracyM;      // -1 = 无效
    }

    /** 会话汇总 */
    public static final class Summary {
        public long   startEpochSec;
        public long   endEpochSec;
        public double totalElapsedSec;
        public double totalTimerSec;
        public double totalDistanceM;
        public double avgSpeedMps;
        public double maxSpeedMps;
        public int    avgCadence, maxCadence;
        public int    avgPower, maxPower, normalizedPower;
        public int    leftRightBalance   = -1;    // 左侧百分比，-1 = 无
        public double maxAltitude = Double.NaN, minAltitude = Double.NaN;
        public int    totalAscent, totalDescent;
        public int    totalCalories;
    }

    // ================================================================
    //  字节工具
    // ================================================================

    private static void u8(ByteArrayOutputStream o, int v) {
        o.write(v & 0xFF);
    }

    private static void u16(ByteArrayOutputStream o, int v) {
        o.write(v & 0xFF);
        o.write((v >> 8) & 0xFF);
    }

    private static void u32(ByteArrayOutputStream o, long v) {
        o.write((int) (v & 0xFF));
        o.write((int) ((v >> 8) & 0xFF));
        o.write((int) ((v >> 16) & 0xFF));
        o.write((int) ((v >> 24) & 0xFF));
    }

    /** 16 位 FIT CRC（官方查表算法的移位版本） */
    public static int crc16(int crc, int byteValue) {
        int[] table = CRC_TABLE;
        int tmp = table[crc & 0xF];
        crc = (crc >> 4) & 0x0FFF;
        crc = crc ^ tmp ^ table[byteValue & 0xF];
        tmp = table[crc & 0xF];
        crc = (crc >> 4) & 0x0FFF;
        crc = crc ^ tmp ^ table[(byteValue >> 4) & 0xF];
        return crc & 0xFFFF;
    }

    private static final int[] CRC_TABLE = {
            0x0000, 0xCC01, 0xD801, 0x1400, 0xF001, 0x3C00, 0x2800, 0xE401,
            0xA001, 0x6C00, 0x7800, 0xB401, 0x5000, 0x9C01, 0x8801, 0x4400
    };

    private static int crc16(int crc, byte[] buf, int off, int len) {
        for (int i = 0; i < len; i++) crc = crc16(crc, buf[off + i] & 0xFF);
        return crc;
    }

    // ================================================================
    //  定义消息 / 数据消息
    // ================================================================

    /** fields 每项为 {字段号, 字节数, 基础类型} */
    private static void define(ByteArrayOutputStream o, int local, int global, int[][] fields) {
        o.write(0x40 | (local & 0x0F));   // 定义消息头
        o.write(0);                       // reserved
        o.write(0);                       // 小端
        u16(o, global);
        u8(o, fields.length);
        for (int[] f : fields) {
            u8(o, f[0]);
            u8(o, f[1]);
            u8(o, f[2]);
        }
    }

    private static void dataHeader(ByteArrayOutputStream o, int local) {
        o.write(local & 0x0F);
    }

    private static int semicircles(double deg) {
        return (int) Math.round(deg * (2147483648.0 / 180.0));
    }

    private static int altRaw(double meters) {
        if (Double.isNaN(meters)) return U16_INVALID;
        long v = Math.round((meters + 500.0) * 5.0);
        if (v < 0) return 0;
        if (v > 65534) return 65534;
        return (int) v;
    }

    private static long altRaw32(double meters) {
        if (Double.isNaN(meters)) return U32_INVALID;
        long v = Math.round((meters + 500.0) * 5.0);
        if (v < 0) return 0;
        if (v > 4294967294L) return 4294967294L;
        return v;
    }

    private static int speedRaw16(double mps) {
        long v = Math.round(mps * 1000.0);
        if (v < 0) v = 0;
        if (v > 65534) v = 65534;
        return (int) v;
    }

    private static long speedRaw32(double mps) {
        long v = Math.round(mps * 1000.0);
        if (v < 0) v = 0;
        if (v > 4294967294L) v = 4294967294L;
        return v;
    }

    private static long distRaw(double meters) {
        long v = Math.round(meters * 100.0);
        if (v < 0) v = 0;
        if (v > 4294967294L) v = 4294967294L;
        return v;
    }

    private static long fitTs(long epochSec) {
        return epochSec - FIT_EPOCH_OFFSET;
    }

    // ================================================================
    //  生成完整文件
    // ================================================================

    /**
     * @param recordBytes  由 {@link #recordDefinition} + 每帧 {@link #recordData} 拼出来的记录区
     * @param s            会话汇总（可为 null，用于中途快照也要给一个"到目前为止"的汇总）
     */
    public static byte[] build(byte[] recordBytes, Summary s) {
        ByteArrayOutputStream data = new ByteArrayOutputStream(recordBytes.length + 512);

        writeFileId(data, s.startEpochSec);
        writeEvent(data, s.startEpochSec, 0);      // timer / start
        data.write(recordBytes, 0, recordBytes.length);
        writeEvent(data, s.endEpochSec, 1);        // timer / stop
        writeLap(data, s);
        writeSession(data, s);
        writeActivity(data, s);

        byte[] body = data.toByteArray();

        // ---- 14 字节文件头 ----
        byte[] header = new byte[14];
        header[0] = 14;                              // header size
        header[1] = 0x10;                            // protocol version 1.0
        header[2] = 0x34;                            // profile version 2100 = 0x0834
        header[3] = 0x08;
        header[4] = (byte) (body.length & 0xFF);
        header[5] = (byte) ((body.length >> 8) & 0xFF);
        header[6] = (byte) ((body.length >> 16) & 0xFF);
        header[7] = (byte) ((body.length >> 24) & 0xFF);
        header[8] = '.';
        header[9] = 'F';
        header[10] = 'I';
        header[11] = 'T';
        int headerCrc = crc16(0, header, 0, 12);
        header[12] = (byte) (headerCrc & 0xFF);
        header[13] = (byte) ((headerCrc >> 8) & 0xFF);

        byte[] out = new byte[header.length + body.length + 2];
        System.arraycopy(header, 0, out, 0, header.length);
        System.arraycopy(body, 0, out, header.length, body.length);

        int fileCrc = crc16(0, out, 0, header.length + body.length);
        out[out.length - 2] = (byte) (fileCrc & 0xFF);
        out[out.length - 1] = (byte) ((fileCrc >> 8) & 0xFF);
        return out;
    }

    public static void writeToFile(byte[] fit, File out) throws IOException {
        try (FileOutputStream fos = new FileOutputStream(out)) {
            fos.write(fit);
            fos.flush();
        }
    }

    // ---------------- 各消息 ----------------

    private static void writeFileId(ByteArrayOutputStream o, long createdSec) {
        define(o, L_FILE_ID, M_FILE_ID, new int[][]{
                {0, 1, BT_ENUM},      // type
                {1, 2, BT_UINT16},    // manufacturer
                {2, 2, BT_UINT16},    // product
                {4, 4, BT_UINT32},    // time_created
        });
        dataHeader(o, L_FILE_ID);
        u8(o, 4);                                  // file type = activity
        u16(o, 255);                               // manufacturer = development
        u16(o, 0);                                 // product
        u32(o, fitTs(createdSec));
    }

    private static void writeEvent(ByteArrayOutputStream o, long ts, int eventType) {
        define(o, L_EVENT, M_EVENT, new int[][]{
                {253, 4, BT_UINT32},
                {0, 1, BT_ENUM},      // event = timer
                {1, 1, BT_ENUM},      // event_type
        });
        dataHeader(o, L_EVENT);
        u32(o, fitTs(ts));
        u8(o, 0);                                  // event = timer
        u8(o, eventType);                          // 0 = start, 1 = stop
    }

    /** 记录区的定义消息（一次），之后每帧只写 {@link #recordData} */
    public static byte[] recordDefinition() {
        ByteArrayOutputStream o = new ByteArrayOutputStream(64);
        define(o, L_RECORD, M_RECORD, new int[][]{
                {253, 4, BT_UINT32},   // timestamp
                {0, 4, BT_SINT32},     // position_lat
                {1, 4, BT_SINT32},     // position_long
                {5, 4, BT_UINT32},     // distance (1/100 m)
                {2, 2, BT_UINT16},     // altitude (1/5 m, +500)
                {78, 4, BT_UINT32},    // enhanced_altitude
                {31, 1, BT_UINT8},     // gps_accuracy
                {6, 2, BT_UINT16},     // speed (1/1000 m/s)
                {73, 4, BT_UINT32},    // enhanced_speed
                {4, 1, BT_UINT8},      // cadence
                {7, 2, BT_UINT16},     // power
        });
        return o.toByteArray();
    }

    public static byte[] recordData(Sample p) {
        ByteArrayOutputStream o = new ByteArrayOutputStream(33);
        dataHeader(o, L_RECORD);
        u32(o, fitTs(p.timestampSec));
        if (Double.isNaN(p.lat) || Double.isNaN(p.lon)) {
            u32(o, S32_INVALID & 0xFFFFFFFFL);
            u32(o, S32_INVALID & 0xFFFFFFFFL);
        } else {
            u32(o, semicircles(p.lat) & 0xFFFFFFFFL);
            u32(o, semicircles(p.lon) & 0xFFFFFFFFL);
        }
        u32(o, distRaw(p.distanceM));
        u16(o, altRaw(p.altitudeM));
        u32(o, altRaw32(p.altitudeM));
        u8(o, p.gpsAccuracyM < 0 || p.gpsAccuracyM > 254 ? U8_INVALID : p.gpsAccuracyM);
        u16(o, speedRaw16(p.speedMps));
        u32(o, speedRaw32(p.speedMps));
        u8(o, p.cadence < 0 || p.cadence > 254 ? U8_INVALID : p.cadence);
        u16(o, p.power < 0 || p.power > 65534 ? U16_INVALID : p.power);
        return o.toByteArray();
    }

    private static void writeLap(ByteArrayOutputStream o, Summary s) {
        define(o, L_LAP, M_LAP, new int[][]{
                {253, 4, BT_UINT32},
                {2, 4, BT_UINT32},     // start_time
                {7, 4, BT_UINT32},     // total_elapsed_time
                {8, 4, BT_UINT32},     // total_timer_time
                {9, 4, BT_UINT32},     // total_distance
                {11, 2, BT_UINT16},    // total_calories
                {13, 2, BT_UINT16},    // avg_speed
                {14, 2, BT_UINT16},    // max_speed
                {110, 4, BT_UINT32},   // enhanced_avg_speed
                {111, 4, BT_UINT32},   // enhanced_max_speed
                {17, 1, BT_UINT8},     // avg_cadence
                {18, 1, BT_UINT8},     // max_cadence
                {19, 2, BT_UINT16},    // avg_power
                {20, 2, BT_UINT16},    // max_power
                {33, 2, BT_UINT16},    // normalized_power
                {34, 1, BT_UINT8},     // left_right_balance
                {43, 2, BT_UINT16},    // max_altitude
                {62, 2, BT_UINT16},    // min_altitude
                {114, 4, BT_UINT32},   // enhanced_max_altitude
                {113, 4, BT_UINT32},   // enhanced_min_altitude
                {21, 2, BT_UINT16},    // total_ascent
                {22, 2, BT_UINT16},    // total_descent
        });
        dataHeader(o, L_LAP);
        u32(o, fitTs(s.endEpochSec));
        u32(o, fitTs(s.startEpochSec));
        u32(o, Math.round(s.totalElapsedSec * 1000.0));
        u32(o, Math.round(s.totalTimerSec * 1000.0));
        u32(o, distRaw(s.totalDistanceM));
        u16(o, clampU16(s.totalCalories));
        u16(o, speedRaw16(s.avgSpeedMps));
        u16(o, speedRaw16(s.maxSpeedMps));
        u32(o, speedRaw32(s.avgSpeedMps));
        u32(o, speedRaw32(s.maxSpeedMps));
        u8(o, clampU8(s.avgCadence));
        u8(o, clampU8(s.maxCadence));
        u16(o, clampU16(s.avgPower));
        u16(o, clampU16(s.maxPower));
        u16(o, clampU16(s.normalizedPower));
        u8(o, s.leftRightBalance < 0 || s.leftRightBalance > 100 ? U8_INVALID : s.leftRightBalance);
        u16(o, altRaw(s.maxAltitude));
        u16(o, altRaw(s.minAltitude));
        u32(o, altRaw32(s.maxAltitude));
        u32(o, altRaw32(s.minAltitude));
        u16(o, clampU16(s.totalAscent));
        u16(o, clampU16(s.totalDescent));
    }

    private static void writeSession(ByteArrayOutputStream o, Summary s) {
        define(o, L_SESSION, M_SESSION, new int[][]{
                {253, 4, BT_UINT32},
                {2, 4, BT_UINT32},     // start_time
                {7, 4, BT_UINT32},     // total_elapsed_time
                {8, 4, BT_UINT32},     // total_timer_time
                {9, 4, BT_UINT32},     // total_distance
                {11, 2, BT_UINT16},    // total_calories
                {14, 2, BT_UINT16},    // avg_speed
                {15, 2, BT_UINT16},    // max_speed
                {124, 4, BT_UINT32},   // enhanced_avg_speed
                {125, 4, BT_UINT32},   // enhanced_max_speed
                {18, 1, BT_UINT8},     // avg_cadence
                {19, 1, BT_UINT8},     // max_cadence
                {20, 2, BT_UINT16},    // avg_power
                {21, 2, BT_UINT16},    // max_power
                {34, 2, BT_UINT16},    // normalized_power
                {37, 1, BT_UINT8},     // left_right_balance
                {50, 2, BT_UINT16},    // max_altitude
                {71, 2, BT_UINT16},    // min_altitude
                {128, 4, BT_UINT32},   // enhanced_max_altitude
                {127, 4, BT_UINT32},   // enhanced_min_altitude
                {22, 2, BT_UINT16},    // total_ascent
                {23, 2, BT_UINT16},    // total_descent
                {5, 1, BT_ENUM},       // sport
                {6, 1, BT_ENUM},       // sub_sport
        });
        dataHeader(o, L_SESSION);
        u32(o, fitTs(s.endEpochSec));
        u32(o, fitTs(s.startEpochSec));
        u32(o, Math.round(s.totalElapsedSec * 1000.0));
        u32(o, Math.round(s.totalTimerSec * 1000.0));
        u32(o, distRaw(s.totalDistanceM));
        u16(o, clampU16(s.totalCalories));
        u16(o, speedRaw16(s.avgSpeedMps));
        u16(o, speedRaw16(s.maxSpeedMps));
        u32(o, speedRaw32(s.avgSpeedMps));
        u32(o, speedRaw32(s.maxSpeedMps));
        u8(o, clampU8(s.avgCadence));
        u8(o, clampU8(s.maxCadence));
        u16(o, clampU16(s.avgPower));
        u16(o, clampU16(s.maxPower));
        u16(o, clampU16(s.normalizedPower));
        u8(o, s.leftRightBalance < 0 || s.leftRightBalance > 100 ? U8_INVALID : s.leftRightBalance);
        u16(o, altRaw(s.maxAltitude));
        u16(o, altRaw(s.minAltitude));
        u32(o, altRaw32(s.maxAltitude));
        u32(o, altRaw32(s.minAltitude));
        u16(o, clampU16(s.totalAscent));
        u16(o, clampU16(s.totalDescent));
        u8(o, 2);              // sport = cycling
        u8(o, 0);              // sub_sport = generic
    }

    private static void writeActivity(ByteArrayOutputStream o, Summary s) {
        define(o, L_ACTIVITY, M_ACTIVITY, new int[][]{
                {253, 4, BT_UINT32},
                {5, 4, BT_UINT32},     // local_timestamp
                {1, 2, BT_UINT16},     // num_sessions
        });
        dataHeader(o, L_ACTIVITY);
        u32(o, fitTs(s.endEpochSec));
        u32(o, fitTs(s.endEpochSec));
        u16(o, 1);
    }

    private static int clampU8(int v) {
        if (v < 0) return U8_INVALID;
        return Math.min(v, 254);
    }

    private static int clampU16(int v) {
        if (v < 0) return U16_INVALID;
        return Math.min(v, 65534);
    }
}
