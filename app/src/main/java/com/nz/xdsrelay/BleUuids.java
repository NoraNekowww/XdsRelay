package com.nz.xdsrelay;

import java.util.UUID;

/**
 * 本工程用到的所有蓝牙 UUID。
 *
 * 喜德盛 AD350 爪盘功率计（私有协议）:
 *   服务        0x1828  (非标，厂商私有)
 *   测量特征    0x2A63  (notify，负载为私有布局)
 *   控制特征    0x2A55  (write，用于下发启动命令 02 16 AA 10)
 *
 * 转发出去的目标格式（Bluetooth SIG 标准）:
 *   骑行功率服务   0x1818 / 骑行功率测量 0x2A63 / 骑行功率特性 0x2A65 / 传感器位置 0x2A5B
 *   定位与导航服务 0x1819 / 位置与速度   0x2A67 / LN 特性     0x2A6A
 *   电池服务       0x180F / 电池电量     0x2A19
 */
public final class BleUuids {

    private BleUuids() {}

    public static UUID uuid16(int shortUuid) {
        return UUID.fromString(String.format("0000%04x-0000-1000-8000-00805f9b34fb", shortUuid));
    }

    public static final UUID CCCD = uuid16(0x2902);

    // ---- XDS 私有功率计 (数据源) ----
    public static final int     XDS_SERVICE_SHORT    = 0x1828;
    public static final UUID    XDS_SERVICE          = uuid16(0x1828);
    public static final UUID    XDS_MEASUREMENT      = uuid16(0x2A63);
    public static final UUID    XDS_CONTROL_POINT    = uuid16(0x2A55);

    /** 启动功率通知的私有命令，与 nRF 固件一致 */
    public static final byte[]  XDS_START_COMMAND    = {0x02, 0x16, (byte) 0xAA, 0x10};

    // ---- 标准骑行功率服务 (转发目标) ----
    public static final int     CPS_SERVICE_SHORT    = 0x1818;
    public static final UUID    CPS_SERVICE          = uuid16(0x1818);
    public static final UUID    CPS_MEASUREMENT      = uuid16(0x2A63);
    public static final UUID    CPS_FEATURE          = uuid16(0x2A65);
    public static final UUID    CPS_SENSOR_LOCATION  = uuid16(0x2A5B);

    // ---- 标准定位与导航服务 (转发目标) ----
    public static final int     LNS_SERVICE_SHORT    = 0x1819;
    public static final UUID    LNS_SERVICE          = uuid16(0x1819);
    public static final UUID    LN_LOCATION_SPEED    = uuid16(0x2A67);
    public static final UUID    LN_FEATURE           = uuid16(0x2A6A);

    // ---- 标准电池服务 (转发目标) ----
    public static final UUID    BATTERY_SERVICE      = uuid16(0x180F);
    public static final UUID    BATTERY_LEVEL        = uuid16(0x2A19);

    // ---- 设备信息服务 (可选, 便于码表/App 识别) ----
    public static final UUID    DIS_SERVICE          = uuid16(0x180A);
    public static final UUID    DIS_MANUFACTURER     = uuid16(0x2A29);
    public static final UUID    DIS_MODEL            = uuid16(0x2A24);
    public static final UUID    DIS_FIRMWARE         = uuid16(0x2A26);

    /** GPX 路线文件蓝牙传输特征值（中继端 -> 码表端） */
    public static final UUID    CHR_GPX              = UUID.fromString("0000ad04-0000-1000-8000-00805f9b34fb");
}
