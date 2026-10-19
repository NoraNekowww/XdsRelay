# XDS 功率中继 (XdsRelay)

把**喜德盛 AD350 爪盘功率计**的私有蓝牙数据，用**标准蓝牙 (Bluetooth SIG) 骑行功率格式**转发出去，
让任何支持标准 BLE 功率计的码表 / 手表 / 手机 App 都能直接读到功率与踏频；同时读取本机**高精度 GNSS
定位与海拔**，一并用标准格式转发，并在手机上用仪表盘显示全部数据。

参考实现：<https://github.com/DuoDuoJuZi/xds_forwarding>（nRF52832 + S332，BLE Central → ANT+ Bicycle Power）。
本项目只借用它对 XDS 私有协议的解析结论，转发目标是**标准 BLE**（不是 ANT+），并且不需要额外硬件。

---

## 1. 它到底做了什么

```
        ┌──────────────────────────── 手机 (本 App) ────────────────────────────┐
        │                                                                       │
 AD350  │   XdsCentral (BLE Central)          CpsServer (BLE Peripheral)        │  码表/手表
 爪盘 ══╬══ 扫描 0x1828 → 连接              广播 0x1818 + 0x1819      ══════════╬══ 当作标准
 功率计 │  订阅 0x2A63 → 写 0x2A55        ┌─ 0x2A63 骑行功率测量 (notify)        │  蓝牙功率计
        │  解析私有负载 → 功率/踏频/左右    ├─ 0x2A67 位置与速度   (notify)        │  读取
        │                                  └─ 0x2A19 电量         (notify)        │
        │                                                                       │
        │   GnssTracker (GPS + NMEA)  → 经纬度 / 海拔 MSL / 椭球高 / DOP / 卫星  │
        │                              → 本机仪表盘显示                          │
        └───────────────────────────────────────────────────────────────────────┘
```

一台手机同时扮演两个角色：**BLE 中央**（去读功率计）和 **BLE 外设**（对外广播标准功率计）。

---

## 2. 逆向出来的 XDS 私有协议

结论来自参考固件 `xds_ble_bridge.c` / `xds_protocol.c`，本工程按同样的字节序实现。

| 项目 | 值 |
| --- | --- |
| 广播里的服务 UUID | **0x1828**（私有，非标准 0x1818） |
| 测量特征 | **0x2A63**（notify，负载是私有布局） |
| 控制特征 | **0x2A55**（write，用于启动推送） |
| 启动命令 | **`02 16 AA 10`**（4 字节） |
| 连接参数 | 间隔 30–60 ms，supervision timeout 4 s |
| 数据超时 | 30 s 无数据 → 主动断开重连 |

### 0x2A63 负载布局（小端）

| 偏移 | 类型 | 字段 |
| --- | --- | --- |
| 0 | u16 | 总功率 W |
| 2 | s16 | 左腿功率 W |
| 4 | s16 | 右腿功率 W |
| 6 | s16 | 踏频 RPM |
| 8 | u16 | 曲柄角度 ° |
| 10 | u8 | 错误码 |

负载更短时，后面的字段视为不存在（与参考实现一样宽容）。

### 与固件一致的时序

1. 扫描 → 命中带 `0x1828` 的广播 → 停止扫描并连接
2. 发现服务 `0x1828` → 在服务范围内找 `0x2A63` / `0x2A55`（也支持全局兜底搜索）
3. 打开 `0x2A63` 的 CCCD
4. **800 ms 后**向 `0x2A55` 写 `02 16 AA 10`
5. 之后**每 2000 ms 重发一次，最多 5 次**
6. 收到 `0x2A63` 通知 → 解析 → 转发

---

## 3. 转发出去的标准格式

### 3.1 骑行功率服务 `0x1818`（Cycling Power）

| 特征 | 用途 |
| --- | --- |
| `0x2A63` Cycling Power Measurement | **notify**，标准负载 |
| `0x2A65` Cycling Power Feature | read，声明支持曲柄转数数据（+踩踏平衡） |
| `0x2A5B` Sensor Location | read，值 `5` = 左曲柄 |

`0x2A63` 标准负载结构（本工程实际下发）：

```
flags(2) | 瞬时功率(2, sint16, W) | [踩踏平衡(1, 0.5%/bit)] | 曲柄累计转数(2, uint16) | 曲柄事件时间(2, uint16, 1/1024 s)
```

- `flags` bit5 = Crank Revolution Data Present（始终置位）
- `flags` bit0/bit1 = Pedal Power Balance Present / Reference=Left（有左右瓦数时置位）
- **曲柄转数是由踏频合成的**：`revs += cadence/60 · dt`，每完成一整圈就把事件时间更新为当前时钟，
  码表据此 (`Δrevs / Δt`) 算出踏频，精度与传入的 RPM 一致。

### 3.2 定位与导航服务 `0x1819`（Location and Navigation）

| 特征 | 用途 |
| --- | --- |
| `0x2A67` Location and Speed | **notify**，1 Hz |
| `0x2A6A` LN Feature | read，声明支持速度/位置/海拔/航向/水平与垂直误差 |

`0x2A67` 标准负载结构（18 字节，默认 MTU 23 也能完整发出去）：

```
flags(2) | 瞬时速度(2, 1/256 m/s) | 纬度(4, 1e-7°) | 经度(4, 1e-7°) | 海拔(4, 0.01 m) | 航向(2, 0.01°)
```

- Position Status 放在 `flags` bit7..bit8：定位有效 = 1（Position OK）
- `flags` bit11 Elevation Source = 0（来自 GNSS，不是气压计）
- 海拔用的是 **MSL 正交高**，也就是码表/地图上显示的那个高度

### 3.3 其它

- `0x180F` 电池服务 / `0x2A19` 电量 — 每 5 秒读一次手机电量并以标准格式通知（码表上会显示"传感器电量"）
- `0x180A` 设备信息服务 — 厂商 / 型号 / 固件版本

---

## 4. 高精度定位与海拔

数据源是 `LocationManager.GPS_PROVIDER`（1 Hz）+ **直接解析原始 NMEA** + `GnssStatus`，因为海拔有三个概念必须分清：

| 量 | 来源 | 说明 |
| --- | --- | --- |
| **海拔 MSL** | NMEA `GGA` 第 9 字段 | 正交高，日常说的"海拔"，也是转发出去的值 |
| **大地水准面差距 N** | NMEA `GGA` 第 11 字段 | 椭球面与水准面的差（中国境内约 -10 ~ -40 m） |
| **椭球高 H** | `H = MSL + N` | 测绘/物理意义上的高度 |

同时拿到 **HDOP / VDOP / PDOP**（`GSA`）、定位质量（单点/差分/RTK 固定/RTK 浮动）、
参与解算的卫星数与可见卫星数、星座组成（GPS/BDS/GAL/GLO/QZSS），
并显示 Android 给出的水平/垂直精度（`±x.x m`）。

高原或起伏路段，UI 上的「平滑海拔」用**自适应 EMA**：
垂直精度差时平滑强（α≈1/(1+σ_v)），高度突变 > 40 m 时立刻跟随（α=0.8），避免把真实爬升也磨平。

---

## 5. 界面

深色仪表盘（竖屏一屏看完，可滚动）：

- **大号功率环形表**：0–1000 W 满量程，按区间变色（<45% 青 / <70% 橙 / <90% 黄 / ≥90% 红），带峰值
- **左右腿平衡条**：左右瓦数 + 百分比
- **踏频 / 曲柄角度 / 错误码** 三宫格
- **90 秒趋势图**：功率面积图 + 踏频折线
- **GNSS 面板**：纬度 / 经度 / 海拔 MSL / 椭球高 / 大地水准面差距 / 速度 / 航向 / 水平垂直精度 / 卫星 / DOP / UTC
- **转发面板**：广播设备名、暴露的标准服务与特征、已推送包数、客户端 MTU 与单包上限、已连接客户端
- **运行日志**：连接、GATT 清单、CCCD、启动命令、原始负载十六进制（前 3 包 + 之后每 50 包一次）
- 按钮：启动/停止中继、重新扫描、选设备、**模拟数据**、清空日志

前台服务常驻通知里直接能看到 `W · rpm · 海拔` 和客户端数，息屏不断连（持有 PARTIAL_WAKE_LOCK）。

---

## 6. 编译

工具链（本机已装好，位于 `E:\Alpha_nz_Files\_toolchain`）：

| 组件 | 版本 |
| --- | --- |
| JDK | Microsoft OpenJDK 17.0.13 |
| Android SDK | platform-tools + `platforms;android-34` + `build-tools;34.0.0` |
| Gradle | 8.7 |
| AGP | 8.4.0 |

```powershell
# 一条命令构建 debug APK
powershell -NoProfile -ExecutionPolicy Bypass -File E:\Alpha_nz_Files\_toolchain\build.ps1 assembleDebug

# 产物
E:\Alpha_nz_Files\XdsRelay\app\build\outputs\apk\debug\app-debug.apk
```

装到手机：

```powershell
adb install -r E:\Alpha_nz_Files\XdsRelay\app\build\outputs\apk\debug\app-debug.apk
```

minSdk 26 / targetSdk 34，Java 语言（无 Kotlin），依赖只有 appcompat + material + constraintlayout。

---

## 7. 使用

1. 打开 App，授予权限：**附近设备（扫描/连接/广播）**、**精确定位**、**通知**
2. 踩两下脚踏唤醒 AD350 功率计，点「启动中继」
3. 状态栏会依次变成：搜索中 → 握手中 → **已连接**，日志里能看到 `负载 len=11 [...]`
4. 在码表上搜索「蓝牙功率计 / Bluetooth Power」，会看到本机（广播名 = 手机蓝牙名，见"转发面板"）
5. 手机界面同步显示功率、踏频、左右平衡，以及 GPS 坐标与海拔

**没有功率计时**可以用「模拟数据」按钮，输出 60–400 W 三角波 / 70–95 rpm，
用来验证码表端能不能正确读到标准功率数据。

### 已知限制

- 喜德盛功率计通常**只允许一个 BLE 中央**连接：测试本 App 时请先断开原码表/官方 App，反之亦然
- 部分手机的 BLE 控制器不支持**同时**广播 + 扫描；如果广播启动失败，日志会给出 `ADVERTISE_FAILED_*` 原因
- 广播包里没有自定义设备名（Android 只能广播系统蓝牙名，改它会影响整机）；要改就改手机蓝牙名
- 仅转发**骑行功率**与**定位**；ANT+ 不在范围内（参考固件是 ANT+，本项目是标准 BLE）

---

## 8. 源码结构

```
app/src/main/java/com/nz/xdsrelay/
├── MainActivity.java      仪表盘界面、权限、按钮、设备选择
├── RelayService.java      前台服务，串起三者 + 曲柄转数合成 + 通知
├── XdsCentral.java        BLE 中央：扫描/连接/订阅/启动命令/重连/模拟
├── CpsServer.java         BLE 外设：GATT Server + 广播 + 订阅管理
├── GnssTracker.java       GPS + NMEA + GnssStatus + 海拔滤波
├── XdsParser.java         XDS 私有负载解析（对齐 xds_protocol.c）
├── BleCodec.java          标准 CPS / LNS 负载编码
├── BleUuids.java          全部 UUID 与协议常量
├── Logs.java              环形日志
├── PowerGaugeView.java    功率环形表
├── BalanceBarView.java    左右平衡条
└── SparklineView.java     功率/踏频趋势图
```
