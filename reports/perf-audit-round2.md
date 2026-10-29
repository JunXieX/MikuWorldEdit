# 性能审查（第二轮，只读）：全量代码可优化空间

仓库 `E:\WorkHome\MikuWorldEdit\fawe`，HEAD `070120bf8`（2.17.4）。四个域并行只读审查，原始报告：
[.dsh-tmp/perf2/writepath.md](.dsh-tmp/perf2/writepath.md)、[queue.md](.dsh-tmp/perf2/queue.md)、
[light.md](.dsh-tmp/perf2/light.md)、[pipeline.md](.dsh-tmp/perf2/pipeline.md)。
本文是汇总与取舍，代码依据在原始报告里逐条给出。

## 0. 基准与成本分布（先明确优化目标）

CI 托管 runner，96³ = 884,736 方块 / 36 区块：

| 阶段 | 耗时 | 说明 |
| --- | --- | --- |
| 入队（apply） | 7.5 ms | 遍历选区、往队列塞方块；约 8.5 ns/方块 |
| 区块落地（close） | 113 ms | 派发到区域线程 → section 调色板 → 高度图 → 光照 → 历史 → 发包 |
| 合计中位数 | 117 ms | 上一版 192 ms |

结论：**优化空间在 close 那 113 ms 里**，入队侧已经到底（每方块 2 次虚调用 + 1 次原子操作）。
按方块均摊 113 ms / 884,736 ≈ **128 ns/方块**，其中按 section 与按 chunk 的固定成本占大头。

## 1. 排序后的候选（收益 × 把握 ÷ 风险）

| # | 级别 | 位置 | 省什么 | 预计收益 | 风险 |
| --- | --- | --- | --- | --- | --- |
| 1 | A | `NMSAdapter.java:26-40 / 77-100` | 单值 section 白跑 `mapPalette` 的 4096 次映射（`bits==null` 时 `blocksCopy` 无人读） | 96³ 约 3.5M 次、192³ 约 14M 次数组访问 → 预计 3–5% | 低：单值分支不产出位打包数据 |
| 2 | A | `NMSAdapter.java:136-165` | Folia 区域线程恒走慢路径：每 section 2 次 `ConcurrentHashMap.compute` + StampedLock 获取/释放 | 96³ 约 1700 次、192³ 约 6900 次锁操作 → 预计 1–3% | 中：锁的作用是与并发发包互斥，Folia 上发包同线程，但需确认没有跨线程发包路径 |
| 3 | B | `NMSAdapter.java:26-40 + 94-98` | `createPalette` 与 `mapPalette` 两遍 4096 合并成一遍 | 每个 section 少 4096 次读 → 预计 2–4% | 中：必须保留 `__RESERVED__`→AIR 别名与 1.21.x 的全局调色板分支 |
| 4 | B | `PaperweightGetBlocks.java:934-965` | 光照写回是逐方块 4096 次 `DataLayer.set`（坐标计算 + 掩码），可降为 2048 次 nibble 直写 | 带光照的粘贴/撤销场景，按 section 计减半 | 中：nibble 边界易错，需 golden 对比 |
| 5 | B | `PaperweightGetBlocks.java:346` | 每区块 `new HashMap<>(getBlockEntities())` 全量复制 | 仓储区约 160 KB/区块的复制 | 低-中：先只读筛选再复制 |
| 6 | A | `SingleThreadQueueExtent.java:239-255` | 空 chunk 提交时 `Thread.sleep(5 - age)`，最坏 5 ms/区块，睡在调用线程上 | 空区块场景（`//set air`、无变化粘贴）可到数百 ms | 中：这是上游为"区块稍后还会被填"设计的让步，改成更短或按需跳过会放大 CPU 空转 |
| 7 | A | `SurfaceRandomOffsetPattern.java:83` | `#surfacespread` 每方块 moves×8 个候选，每个候选 `new MutableBlockVector3` + 内部 pattern 调用 | 仅 `#surfacespread`，moves≥4 时秒级→十秒级 | 低：复用 scratch 向量、把 `ThreadLocalRandom.current()` 提循环外，语义不变 |
| 8 | A | `MemUtil.java:27-28 / 86-87` | 低内存时 `System.gc()` ×2，且只有日志被节流，GC 没有 | 停顿问题，非吞吐 | 低：去掉显式 GC 只留通知 |
| 9 | B | `MainUtil.java:291-329` + `Settings.java:539/545` | 历史压缩默认 level=3 → 三层串联 LZ4 + 5×519 KB 缓冲 | 每区块历史压缩开销 | 低-中：调 `history.compress-level` 即可，属配置权衡 |
| 10 | B | `FaweStreamChangeSet.java:363-373` | 历史每方块 8–12 次单字节写（位置增量 + 前后状态） | 884k 方块 ≈ 数百万次写 | 高：动它等于改历史格式，必须保持回放兼容 |


## 1b. 队列域补充（已核实，含量化）

| 级别 | 位置 | 事实 | 量级（192³） | 风险 |
| --- | --- | --- | --- | --- |
| A | `FaweStreamChangeSet.java:363-373`+`:208-232` | 历史每方块 = `getBlockOS` + 6 次 `write(byte)` + 2 次 `writeVarInt` ≈ 10 次虚调用；`flush()` 里 `drainQueue(true)` 阻塞等 worker（`AbstractChangeSet.java:91-94`），所以这条**在关闭关键路径上** | 880 万次（96³）/ 7100 万次（192³）；估算 20–30 ms / 170–250 ms | **低**：字节流内容不变，只把逐字节 write 合成一次 `write(byte[],0,n)`，格式不动 |
| A | `SingleThreadQueueExtent.java:239-255` | 空 chunk 提交时 `Thread.sleep(5-age)`，睡在调用线程；只读/全过滤命令在 `IQueueExtent.apply` 收尾逐区块 submit | 96³≈180 ms、192³≈720 ms 纯等待（`//count`、`//copy`、无匹配 `//replace`） | 中：上游用它给"稍后还会被填"的区块让步，改成仅跨线程睡或 `onSpinWait` 需压测 CPU |
| B | `ChunkHolder.java:1048-1059` + `PaperweightGetBlocks.java:454-456`+`:469-477` | 同一段 section 数据被整段拷贝 **3 份**（`createCopy` / `setArr` / `copyArr`） | 41.5 MB、5184 个 `char[4096]` | 中：其中 `copyArr` 只在 `createCopy` 为真时需要 |
| B | `MainUtil.java:291-329`+`Settings.java:539/545` | 默认 `COMPRESSION_LEVEL=3` 串三层 LZ4（后两层压已压缩数据）+ 5×519 KB 缓冲 | 2.6 MB 缓冲/编辑 | 低：配置层面降到 1，需实测体积与 CPU 权衡 |
| B | `CharSetBlocks.java:29-53` + `ChunkHolder.java:933-955` | `QUEUE.POOL` 默认开，但 SET 侧唯一回收入口 `ChunkHolder.trim()` **全仓无调用者**（已 grep 确认），池永远空 | 每区块新建 `CharSetBlocks` + 16 个 section 锁 | 低：补回收入口或关掉池 |
| B | `ChunkCache.java:28-40/46-70` | GET 缓存无上限，`trim(false)` 遇第一个存活条目即 return | 长开服内存随编辑区块数增长 | 低-中 |

## 2. 已核实**不该动**的（避免下一轮重复误报）

- **`Pattern.apply` 默认实现**：看着每方块造 `BaseBlock`，实际 `BlockState.toBaseBlock()` 返回缓存的 `emptyBaseBlock`（`BlockState.java:423-425`），零分配。
- **extent 链长度**：`EditSessionBuilder:524-532/589-700` 把 history/region/disallowed/stripNBT 都注册成 **chunk 级** processor，默认路径每方块只剩 2 层转发。
- **`FaweTimer`**（20 次/秒）、**`InteractionDebouncer`**（每次点击 2 次哈希）：性能上不值得动；去抖器的裸 `HashMap` 是线程安全问题，不是性能问题。
- **heightmap 与光照重复扫描**：复用解码缓存 + 三重剪枝，典型只扫 1–2 个 section。
- **按玩家裁剪发包 / section 级增量包**：现状已是"组包 1 次/区块、发送 k 次/区块"（k=该区块可见玩家），继续细化要吃 Paper 内部 per-player 状态。
- **relight 按 chunk 并行**：会切断光传播造成接缝，方向错误。

## 3. 光照域的实测结论（供参考，非本轮改动项）

- 96³ 只触发 **1 次** relight，处理 **64 个区块列**（36 个被改列经 3×3 扩张），1024 上限一次吃完；放大 1.78×，单区块编辑放大 9×。
- 默认 `remove-first=true` 会把这些列**全部 24 个 section × 2 层**清零点亮：96³ 实际只需 6 个 section → 约 4× 的 section 数、1.78× 的列数，合计约 7× 多余清零。
- `removeLighting()` 在调用线程上逐列 `ensureLoaded(...).get()`：跨区域编辑时**区域线程阻塞等另一个区域线程**，这是 Folia 上的真实隐患（正确性/延迟，不是吞吐）。
- relighter 的锁是 **per-EditSession 实例**，两人相邻编辑会并发跑两批 starlight 覆盖同一批 `DataLayer`。

## 4. 建议的实施顺序（按收益 ÷ 风险重排）

0. **历史逐方块写合批**（字节流不变，只减少调用次数；估算 96³ 省 20–30 ms ≈ close 的 20%）。这是当前单项最大收益。
1. **#1 单值 section 跳过 mapPalette**（风险最低、可立即用 CI 基准验证）。
2. **#7 `#surfacespread` 候选循环**（路径极端且收益明确，与主基准无关，单独用命令压测）。
3. **#3 合并两遍 4096**（在 #1 之后做，两者共享同一段代码，一次验证）。
4. 光照 #4/#5 与 Folia 的 `removeLighting` 阻塞改为按区块派发——**这组要单独一轮**，因为涉及光照正确性，需要目视 + 灰度。
5. 历史（#9/#10）只建议先做配置层面的 `compress-level` 权衡，格式不动。

**预期总量**：#1+#3 约 5–8%，#2 约 1–3%，光照整理视场景 5–15%（带光照粘贴/撤销）。再往上就得动历史或 relight 结构，属另一档风险。
## 5. 测量方法（2026-10-29 实测教训）

同一份 2.17.5 的 jar，在两个区域测出的数字差 5.7 倍：

| 区域 | close 中位数 | 说明 |
| --- | --- | --- |
| x=20000（前几轮测试反复写过的区域） | 612 ms | 该区域区块常驻、留有光照 ticket、区域线程还要跑这些区块的 tick |
| x=40000（从未编辑） | 106–159 ms | 与历史基线一致 |

结论：**本地 A/B 必须每次换全新区域**，复用同一个 offset 会让基线随世界状态漂移，足以把"无变化"读成"5 倍回归"。
CI 的 `perf.yml` 本来就是每次换 offset（`i*1000`），所以同一 runner 内的数字一直稳定在 113–205 ms 区间。

也因此，`FaweStreamChangeSet` 的历史合批在 2.17.7 被回退：它的收益从未在有效基线上被证实，而风险面（改历史字节流写入粒度）是真实的。
## 6. 六项"未做项"的实测评估（2.18.0 定稿）

测量方式：`mweprobe <size> <offset> <baseline|nohistory|nolight|filtered>`，每次换全新区域（吸取 §5 的教训）。

| 项 | 实测 | 结论 |
| --- | --- | --- |
| 历史写入合批 | 96³ 里"有历史"与"无历史"相差 40–65 ms（占区块落地 20–28%）；合批后两者落在同一区间，`//undo` 回放 884,736 条记录、方块归零 | **已实施**（2.18.0） |
| 光照共享锁 | 无竞争时开销为 0；并发编辑时原实现会两批 starlight 同时写同一批 `DataLayer` | **已实施**（2.18.0，static 锁） |
| 只清相关 section 的光照 | `nolight` 比 baseline 少 80 ms，但清理本身只占几毫秒：64 列 × 24 section × 2 层 × 2 KB ≈ 6 MB 清零；成本主体是 starlight 重算与 3×3 扩张 | **未实施**：收益在别处，改清理范围不对等 |
| section 三份拷贝 → 两份 | 副本只在开启历史时产生，历史总成本 40–65 ms，去掉一份约 5–10 ms | **未实施**：`createCopy` 承担跨线程数据存活 |
| `CharSetBlocks` 池失效 | 每区块约 1–2 KB，96³ 合计 36–72 KB | **未实施**：回收入口被 `ChunkHolder.recycle()` 明确拒绝，改生命周期风险不对等 |
| 空 chunk `Thread.sleep(5-age)` | 批量写路径无法复现；`filtered` 探针跑出 1.8–2.1 s，但那是 `replaceBlocks` 的逐方块访问（约 2 µs/方块），`closeMs` 为 0 | **未实施**：与 sleep 无关，真正的慢点是逐方块 replace 路径 |
## 7. `//replace` 无匹配仍耗时 2.1 秒（2026-10-29 实测定位）

用 `mweprobe` 在同一台机器、同一世界、全新区域上隔离三段：

| 探针 | 96³（884,736 方块） | 说明 |
| --- | --- | --- |
| `read`（只调 `session.getBlock`） | 2107–2331 ms | 每个方块一次读取 = **2.4–2.6 µs** |
| `filtered`（`//replace` 无匹配） | 2105–2126 ms | 与 `read` 完全一致 → 无匹配 replace 的成本就是"每方块读一次" |
| `baseline`（`//set`） | 180–320 ms close + 3–20 ms apply | 批量路径按区块，不逐方块 |

结论：慢的不是 replace 逻辑，而是 **`EditSession.getBlock` 单方块读取要穿整条 extent 链，2.4 µs/次**。
区块级读取（`IChunkExtent.getBlock` → `CharGetBlocks` 的 char[]）本应是几十纳秒，理论上 40–100 倍空间。

尝试过两种短路方式，均未生效（实测读取耗时不变）：

1. `getExtent() instanceof IChunkExtent` —— 直接内层 extent 不是区块 extent；
2. `ExtentTraverser.findAndGet(IChunkExtent.class)` 沿链查找 —— 同样拿不到（链上不暴露队列 extent）。

因此这条要真正落地，必须改成结构性方案：把 `//replace`/`//count` 的逐方块循环下沉到持有队列的层（`IQueueExtent`）实现，
或者让 `EditSession` 对外暴露它持有的队列 extent。影响面在"命令层 ↔ 编辑会话"的边界，不在热路径内部，
所以本轮不改代码，仅记录定位结果与两条已排除的路径。
