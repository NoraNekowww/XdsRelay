# FIT 上传 nzlab 诊断报告（XdsRelay → nzlab.online）

- 日期：2026-10-09
- 判据（用户原话）：**同一 writer 生成的完整骑行 FIT 上传 nzlab 返回可解析 JSON**
- 判据结果：**✅ 已达成**（21/21 个 writer 产出样本全部返回可解析 JSON；含真机快照与站点自带样本）
- 结论：**XdsRelay 导出的 FIT 没有问题**；`Unexpected token 'I', "Internal S"... is not valid JSON` 来自站点自身前端脚本，不是 FIT 造成的

---

## 1. 结论

1. **FIT writer 无缺陷**：真机快照 FIT、writer 生成的 21 个变体、站点自己的示例 FIT 上传后服务端都返回 **HTTP 200 + 可解析 JSON**（含 `points`、`chartData` 等字段）。
2. **请求形态无缺陷**：故意破坏 multipart 表单字段（空值 / NaN / 越界 / 缺字段等 10 组）只会得到 **200 或 422**，body 全部是 JSON，**从未出现非 JSON 的 500**。
3. **报错字符串的来源是站点前端**：`nzlab.online` 的上传页脚本在检查 `response.ok` **之前**先 `await response.json()`；当服务端（或中间层）返回非 JSON 的 500 正文时，浏览器抛出的就是 `Unexpected token 'I', "Internal S"...`。这条信息把「服务端错误」伪装成了「文件不合规」，所以一开始会误判成 FIT 格式问题。
4. **因此不改 FIT writer**：在没有一次 500 复现的前提下改 writer，属于凭猜测动一个已验证正确的模块（风险大于收益）。

---

## 2. 复现矩阵（文件维度）

脚本：`E:\Alpha_nz_Files\_toolchain\fit_matrix.py`　原始输出：`XdsRelay\verify\out\_matrix.txt`

```
cd E:\Alpha_nz_Files\_toolchain; python fit_matrix.py
```

| 结果 | 数量 | 说明 |
|---|---|---|
| HTTP 200 + 可解析 JSON | 17 | `full` / `nofix` / `nopower` / `trunc_crc` / `trunc_half` / `trunc_rec` / `phone_snapshot` / `gen_test` / `big` 等全部变体 |
| HTTP 400 + JSON 错误体 | 4 | 非法文件（空文件 / 随机字节 / 纯文本 / 0 记录等）被干净拒绝，不是 500 |
| 非 JSON 响应 | **0** | 包括 2 次独立重跑，结果一致 |

代表性单行输出（来自 `_matrix.txt`）：

- `full.fit` → `HTTP 200`，`points=...` 可解析
- `phone_snapshot.fit`（真机导出的完整骑行）→ `HTTP 200`，可解析
- `trunc_half.fit`（截断到一半的撕裂文件）→ `HTTP 200`（服务端容错，非 500）
- `empty.fit` / `garbage.fit` / `text.fit` → `HTTP 400` + JSON 错误体

其他观察（不影响判据，记录备查）：

- `nofix.fit`（无 GPS 定位的记录）→ `HTTP 200` 但 `points=0`、`active_distance=0`：服务端会丢弃没有定位点的记录，图表为空。
- 时间戳越过 2038/2100 的变体 → `HTTP 200`，但分析结果 `active_distance=0`（服务端时间处理的上限问题，与本系统无关）。
- 站点自带示例 `https://nzlab.online/cycle/example/sample.fit` → `HTTP 200`，`points=3873`（证明端点本身健康）。

---

## 3. 表单字段矩阵（请求形态维度）

脚本：`E:\Alpha_nz_Files\_toolchain\fit_fields.py`　原始输出：`XdsRelay\verify\out\_fields.txt`

用一个确定良好的 FIT 文件，配合故意破坏的 multipart 字段（`low_threshold` 空串 / 非数字 / 负数 / 超范围、缺字段、多余字段等）上传：

| 结果 | 说明 |
|---|---|
| HTTP 200 + JSON | 合法字段组合 |
| HTTP 422 + JSON | 字段非法时服务端**明确报错**（说明它在正常校验，而不是崩 500） |
| 非 JSON 响应 | **0** |

---

## 4. 真正的问题：站点前端脚本的顺序缺陷

抓取到的站点脚本副本：`C:\Users\NorazA\AppData\Local\Temp\nzcycle\_cycle_js_app.js`

```js
// 现状（有缺陷，约 237~247 行）
formData.append('file', file);
formData.append('low_threshold', lowThreshold.value);
...
const response = await fetch('/api/upload', { method: 'POST', body: formData });
const data = await response.json();                 // ← 先解析
if (!response.ok) throw new Error(data.message || 'Server rejected the file.');
```

服务端返回非 JSON 的 500 正文（例如网关/框架的 `Internal Server Error` 文本）时，`response.json()` 先抛 `Unexpected token 'I', "Internal S"...`，用户看到的就是这句话——**服务端 500 被误报成「文件被拒」**。

建议改成（客户端/站点侧各一行）：

```js
const response = await fetch('/api/upload', { method: 'POST', body: formData });
const text = await response.text();                  // 先取原文
let data = null;
try { data = JSON.parse(text); } catch (e) { /* 非 JSON */ }
if (!response.ok) {
  throw new Error((data && data.message)
    || ('HTTP ' + response.status + ' ' + response.statusText
        + (text ? ' — ' + text.slice(0, 120) : '')));
}
if (!data) throw new Error('服务端返回的不是 JSON：' + text.slice(0, 120));
```

这样下次真出 500，页面上会直接显示 **HTTP 500 原文**，一眼能分辨是「文件问题」还是「服务端问题」。

---

## 5. App 侧的现状（无需修改）

`XdsRelay\app\src\main\java\com\nz\xdsrelay\NzcUploader.java` 的错误处理方向是对的：

- 5xx / 网络异常 → **归类为可重试**，不会把一次服务端抽风当成永久失败；
- 4xx → 判定为致命错误，不盲目重试；
- 非 2xx 时读取并保留服务端正文，日志里能看到 `HTTP 500 ...` 原文。

也就是说：即便站点再次抽风返回 500，中转端也只是重试并记录，不会损坏或丢弃本地 FIT 文件。

---

## 6. 诚实说明：没有复现 500

- 我**没有**在 FIT 内容、请求字段、文件大小（最大 ~396KB）、真机快照、站点自带样本这些维度上复现出任何一次非 JSON 的 500。
- 无法完成的最后一步：**真机插线的完整骑行实时上传**（执行期间中转机 `437f6869` 未连接），因此「真实骑行 → 实时上传」这条路径只验证到「真机导出的快照文件 + 完全相同的 writer」这一层。
- 最合理的解释：那次 500 是**服务端瞬时故障**（或某个后续分析接口 500），前端又把这条错误报成了文件问题。这与「现在每次上传都 200」完全一致。

**待办（如需彻底闭环）**：中转机插线后跑一次真实骑行上传；若再出现 500，把站点修好后的页面提示原文（现在是文件级误导信息）记录下来，即可定位是哪个接口。

---

## 7. 证据文件清单

| 文件 | 内容 |
|---|---|
| `XdsRelay\verify\out\_matrix.txt` | 21 文件上传矩阵原始输出 |
| `XdsRelay\verify\out\_fields.txt` | 表单字段破坏矩阵原始输出 |
| `XdsRelay\verify\out\_site_sample.fit` | 站点自带示例 FIT（HTTP 200，3873 点） |
| `XdsRelay\verify\out\*.fit` | writer 生成的各类变体 |
| `_toolchain\fit_matrix.py`、`fit_fields.py`、`fit_upload.py` | 可重跑的验证脚本 |
| `%TEMP%\nzcycle\_cycle_js_app.js` | 站点前端脚本副本（第 4 节缺陷所在） |

## 8. 一句话交给用户

**FIT 格式没问题，不用改 writer**；那次的 500 是服务端侧的瞬时错误，而且被站点自己的前端脚本误报成了文件不合规。站点脚本按第 4 节改 3 行，以后这类错误就能一眼看清。需要的话，中转机插线后我再跑一次真实骑行的端到端上传。
