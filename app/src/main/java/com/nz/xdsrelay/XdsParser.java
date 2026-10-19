package com.nz.xdsrelay;

/**
 * 喜德盛 XDS 私有功率负载解析。
 *
 * 与参考固件 xds_protocol.c 逐字段一致（小端）:
 *   偏移 0   u16  总功率 W
 *   偏移 2   s16  左腿功率 W
 *   偏移 4   s16  右腿功率 W
 *   偏移 6   s16  踏频 RPM
 *   偏移 8   u16  曲柄角度 度
 *   偏移 10  u8   错误码
 * 负载更短时，后面的字段视为不存在（与参考实现相同的宽容策略）。
 */
public final class XdsParser {

    private XdsParser() {}

    public static final class Measurement {
        public int  totalPowerW;
        public int  leftPowerW;
        public int  rightPowerW;
        public int  cadenceRpm;
        public int  angleDeg;
        public int  errorCode;
        public boolean hasLeft;
        public boolean hasRight;
        public boolean hasCadence;
        public boolean hasAngle;
        public boolean hasError;
        public int  payloadLength;
        public long timestampMs;

        void reset() {
            totalPowerW = 0;
            leftPowerW = 0;
            rightPowerW = 0;
            cadenceRpm = 0;
            angleDeg = 0;
            errorCode = 0;
            hasLeft = hasRight = hasCadence = hasAngle = hasError = false;
            payloadLength = 0;
            timestampMs = 0L;
        }

        public Measurement copy() {
            Measurement m = new Measurement();
            m.totalPowerW = totalPowerW;
            m.leftPowerW = leftPowerW;
            m.rightPowerW = rightPowerW;
            m.cadenceRpm = cadenceRpm;
            m.angleDeg = angleDeg;
            m.errorCode = errorCode;
            m.hasLeft = hasLeft;
            m.hasRight = hasRight;
            m.hasCadence = hasCadence;
            m.hasAngle = hasAngle;
            m.hasError = hasError;
            m.payloadLength = payloadLength;
            m.timestampMs = timestampMs;
            return m;
        }

        public String hex() {
            return "";
        }
    }
    private static int u16(byte[] b, int o) {
        return (b[o] & 0xFF) | ((b[o + 1] & 0xFF) << 8);
    }

    private static int s16(byte[] b, int o) {
        return (short) ((b[o] & 0xFF) | ((b[o + 1] & 0xFF) << 8));
    }

    /**
     * @param payload 原始负载
     * @param offset  起始偏移
     * @param length  负载长度
     * @param out     解析结果
     * @return 长度足够则为 true
     */
    public static boolean parse(byte[] payload, int offset, int length, Measurement out) {
        if (payload == null || out == null || length < 2) return false;
        if (offset < 0 || offset + length > payload.length) return false;

        out.reset();
        out.payloadLength = length;
        out.totalPowerW = u16(payload, offset);

        if (length >= 4) {
            out.leftPowerW = s16(payload, offset + 2);
            out.hasLeft = true;
        }
        if (length >= 6) {
            out.rightPowerW = s16(payload, offset + 4);
            out.hasRight = true;
        }
        if (length >= 8) {
            int cadence = s16(payload, offset + 6);
            out.cadenceRpm = cadence > 0 ? cadence : 0;
            out.hasCadence = true;
        }
        if (length >= 10) {
            out.angleDeg = u16(payload, offset + 8);
            out.hasAngle = true;
        }
        if (length >= 11) {
            out.errorCode = payload[offset + 10] & 0xFF;
            out.hasError = true;
        }

        out.timestampMs = System.currentTimeMillis();
        return true;
    }

    public static String hex(byte[] data, int offset, int length) {
        if (data == null) return "";
        StringBuilder sb = new StringBuilder(length * 2);
        for (int i = 0; i < length; i++) {
            sb.append(String.format("%02X ", data[offset + i] & 0xFF));
        }
        return sb.toString().trim();
    }
}
