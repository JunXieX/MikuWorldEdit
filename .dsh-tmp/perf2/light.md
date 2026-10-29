# 性能审查（只读）：光照与发包（Folia / 26.3）

仓库：`E:\WorkHome\MikuWorldEdit\fawe`（HEAD `070120bf8`，工作树干净）。未改源码、未跑构建。
分级：`A`=收益明确风险低，`B`=收益明确风险中，`C`=需实测，`D`=不建议动。
不重复已做项：sendChunk 无旁观者提前返回、isRegionised 缓存、归属判定去重、同步泵移除、调色板收尾优化。

## 0. 结论速览（8 问 8 答）

1. **96³ 触发几次 relight**：**1 次**（64 个区块列都在同一个 32×32 批次内时）；若编辑跨 `x>>5/z>>5` 边界则 2~4 次、串行。（`StarlightRelighter.java:36-37, 124, 141-153`）
2. **每次多少区块**：**64 个区块列**（36 个被改列经 3×3 扩张），1024 上限**一次吃完**，不分批。（`StarlightRelighter.java:118-133`）
3. **单线程串行占比**：唯一不可并行的段是 `starlight$serverRelightChunks` 一次调用（1 线程 / 64 区块 / 一批最多 1024）。默认 `remove-first=true` 会先把 64×24×2 个 section 光照清零 → 重算量约为编辑本体的 **7.1×** 且单核（估算，§2.3）。**更严重的是 `removeLighting` 里的跨区域阻塞等待**（§2.1(a)）。
4. **能否按区域/chunk 分片**：按 **chunk** 分片**不行**（切断光传播，接缝）；按**区域自选分区**方向正确但 starlight 并发可重入性未知（C）。**当前真正该修的是"两批 relight 可并发"**（每 EditSession 各自持锁）→ A 级（§3.1, §6-A2）。
5. **10/50 人是否线性放大**：**不是 × 在线人数**。组包 **1 次/区块**、发送 **k 次/区块**（k = 该区块可见玩家，`chunkMap.getPlayers(pos,false)`）。50 人挤在编辑区 → 64×50=3,200 次编码；50 人分散 → 往往 k≤3。
6. **同一 chunk 是否重复组包**：**单次 flush 内不会**（延迟发包与 `callback.send()` 互斥，且 `beginChunkPacketSend` 有并发去重）。但会发**不必要的**邻块整列包：96³ 发 64 包而只需 36（**+78%**）；单区块编辑发 9 包而只需 1（**+800%**）。→ B 级（§4.3, §6-B1）
7. **能否按玩家裁剪**：现状已是区块级裁剪；再按玩家裁需要 Paper 内部 per-player chunk 状态，FAWE 直连 `connection.send` 没有这层 → **D**。
8. **heightmap 是否与光照重复遍历**：是"重复扫描同一批 section"，但**复用了解码结果**（`CharGetBlocks` 缓存），且高度图有"空 section 跳过 + ordinal<4 跳过 + 4×256 命中即停"三重剪枝，典型只扫 1~2 个 section（96³ ≈ 0.3M 次比较）→ 合并到历史 diff **收益/风险比差，D**（§5）。

**最高性价比三件事**：① `lighting.remove-first=false`（纯配置，一并消掉跨区域阻塞 + 全高度清零，B，需目视验证接缝）；② relight 全局串行化（A）；③ 邻块不发整列包（B）。

---

## 1. 一次 96³ 编辑触发多少次 relight、每次多少区块

### 1.1 登记阶段（每区块列一次，再 3×3 放大）

- `RelightProcessor.processSet` 在**每个区块列的 processSet** 里调一次 `addChunk(...)`（`worldedit-core/.../lighting/RelightProcessor.java:25-45`）。MODE=1（默认 Optimal）只有"存在被改 section"才登记（:28-42）；MODE=2 无条件登记（:26-27）。
  → **登记次数 = 区块列数**；96³ = 6×6 = **36 次**。
- `StarlightRelighter.addChunk` 对每个登记点**扩散 3×3 邻域**（`worldedit-bukkit/.../adapter/StarlightRelighter.java:118-133`，循环 :122-128）。`skipReason`/`bitmask` 两个入参**完全未被使用**（签名 :118，方法体无引用）——这是 §4"多余发包"的根因。
  → 96³：36 列扩散去重后 = **8×8 = 64 个区块列**。单区块编辑：1 → **9**。

### 1.2 触发阶段（一次 flush 一次）

- `EditSession.flushQueue()` 只调一次 `removeAndRelight(true)` 或 `fixLightingSafe(true)`（`worldedit-core/.../EditSession.java:1354-1367`；默认 `LIGHTING.REMOVE_FIRST=true`，`Settings.java:853`）。
- `fixLightingSafe` 只取**一个批次**（`regions.removeFirst()`），再递归下一批（`StarlightRelighter.java:141-153`）。
- 批次键 = `(x>>5, z>>5)`，即 **32×32 区块方块**（`StarlightRelighter.java:124`；`CHUNKS_PER_BATCH=1024`、`..._SQRT_LOG2=5`，:36-37）。

| 问 | 答（96³） | 依据 |
|---|---|---|
| relight 调用次数 | **1 次**（64 个列都落在同一个 32×32 批次内时） | `StarlightRelighter.java:141-153` |
| 跨 32 区块边界时 | 2（跨一边）~4（跨角）次，**串行**，每批各自一轮"加载→relight→发包→撤 ticket" | 同上 + :100-115 |
| 每次处理多少区块 | **64 个区块列** | §1.1 |
| 1024 上限是否一次吃完 | **是**，64 ≪ 1024。上限只在 ≥30 区块/轴（≈480 方块）时才分批 | `StarlightRelighter.java:36, 148` |
| 区块放大系数 | `(n+2)²/n²`：96³ → **1.78×**；单区块 → **9×**；192³（12×12）→ 196/144 = **1.36×** | 由 :122-128 推导 |

### 1.3 每批的实际动作（96³，64 区块）

1. `TaskManager.task(...)` → Folia **全局区域线程**（`StarlightRelighter.java:102`；`BukkitTaskManager.java:173-180`）。
2. 循环 64 次：`getChunkAtAsync(x,z)`，完成后 `addTicketAtLevel(FAWE_TICKET, LIGHT_LEVEL)`（`PaperweightStarlightRelighter.java:46-53`）→ **64 future + 64 次 ticket**。
3. `allOf(...).thenAccept` 在**完成线程（chunk 异步线程，非区域/全局线程）**调 `starlight$serverRelightChunks(64 coords, ...)`（`StarlightRelighter.java:106-113` + `PaperweightStarlightRelighter.java:55-65`）——**一次调用、64 区块、单线程**。
4. `postProcessCallback` → `TaskManager.task(postProcessChunks)`（全局线程，:81）→ 循环 64 次，每次**两个** `executeAt`：`sendChunk` 内部一次（`PaperweightStarlightRelighter.java:77` → `PaperweightPlatformAdapter.java:392-397`）+ `removeTicketAtLevel` 一次（:80-85）→ **128 个 RegionScheduler 任务**。
5. `TaskManager.async(andThen)` → 递归 `fixLightingSafe`，此时 `regions` 已空立即返回（:83, :145-147）。

> 可优化（§6-A1）：第 4 步两个 `executeAt` 合并成一次投递，任务数 128 → 64。

### 1.4 ticket 半径 = 隐式额外加载

`LIGHT_LEVEL = ChunkMap.MAX_VIEW_DISTANCE + LOADING_PYRAMID.getStepTo(FULL).getAccumulatedRadiusOf(LIGHT)`（`PaperweightStarlightRelighter.java:27-29`）——**视距级**加载半径，作用在 64 个区块上，未加载区块会触发磁盘/生成，且 ticket 直到 §1.3 第 4 步才撤（:84）。编辑落在未加载区域（离线玩家区、PS 清理）时，这一步是主要耗时，并与"区块数 × 视距面积"相关。→ **C**（收紧半径有正确性风险，须实测）。

---

## 2. 单线程串行段：真正卡在谁身上

### 2.1 flush 里的同步段（玩家区域线程上执行，且会阻塞）

默认 `REMOVE_FIRST=true`（`Settings.java:853`）→ `removeAndRelight(true)`（`Relighter.java:39-42`）= **先 `removeLighting()`（同步、当前线程）→ 再 `fixLightingSafe()`（异步派发）**。

`removeLighting()` 对每个已登记区块（96³ → 64 个）执行（`NMSRelighter.java:191-207`）：

```java
ChunkHolder<?> iChunk = (ChunkHolder<?>) queue.getOrCreateChunk(chunk.x, chunk.z);  // :198
if (!iChunk.isInit()) { iChunk.init(queue, chunk.x, chunk.z); }                    // :199-201
for (int i = minY >> 4; i <= maxY >> 4; i++) {                                     // :202
    iChunk.removeSectionLighting(i, true);                                         // :203
}
```

下沉链路：`ChunkHolder.removeSectionLighting` → `chunk.getOrCreateGet()`（`ChunkHolder.java:603-607`，建 `PaperweightGetBlocks`）→ `chunkExisting.removeSectionLighting`（:283）→ `PaperweightGetBlocks.removeSectionLighting`（`adapter-26.3/.../PaperweightGetBlocks.java:169-195`）：

```java
DataLayer dataLayer = ...getDataLayerData(sectionPos);                                  // :171-172 BLOCK
if (dataLayer != null) { synchronized (dataLayer) { Arrays.fill(bytes, (byte) 0); } }  // :175-178
// :180-193 对 LightLayer.SKY 再来一遍
```

**两个独立的重开销，都在调用线程（= 玩家所在区域线程）上同步发生：**

**(a) 跨区域阻塞等待。** 第一步就调 `getChunk()`（:170）→ `PaperweightGetBlocks.getChunk()`（:912-932）→ `ensureLoaded(...).get()`（:919，**阻塞 get()**）。Folia 下 `ensureLoaded` = `TaskManager.submitAt(world,cx,cz, () -> serverLevel.getChunk(...))`（`PaperweightPlatformAdapter.java:262-276`）：
- 本区域拥有该区块 → `submitAt` 就地执行（`BukkitTaskManager.java:129-130`），future 已完成，`.get()` 立即返回；
- **别的区域拥有 → 投递过去，当前区域线程 `.get()` 阻塞，等对方 tick 执行**。

96³ 的 64 个区块中，凡不在玩家所在区域的都会走第二条。跨区域编辑必然出现。这是"区域线程互相等待"的经典形态：一次 flush 最坏串行等 N 次其它区域线程。**本子系统最大的 Folia 风险，比光照算法本身重要。**

**(b) 全高度清零放大。** 循环边界是 `minY>>4 .. maxY>>4`（:202），不是"被改 section"。26.3 世界 -64..320 → **24 section × 2 层 = 48 次 DataLayer 操作/区块**；96³ 共 **64×48 = 3,072 次**。真正被改的只有 36 列 × 6 section = 216 个 section → **清零量约 14× 于必要量**，并迫使 starlight 重算这些区块的全部光照。

> 注意：这与 `NMSRelighter` 的"休眠"无关——`removeLighting` 在 Starlight 路径上**是活的**（`StarlightRelighter.java:166-168` 转发给 delegate）。

### 2.2 异步段（不在玩家线程，但仍是单线程）

| 段 | 线程 | 依据 | 可并行 |
|---|---|---|---|
| 64 次 `getChunkAtAsync` + 64 次 `addTicketAtLevel` | 全局区域线程 | `StarlightRelighter.java:102` + `PaperweightStarlightRelighter.java:46-53` + `BukkitTaskManager.java:173-180` | 否 |
| `starlight$serverRelightChunks(64)` | **一次调用、单线程**（`allOf` 完成线程 = chunk 异步线程） | `StarlightRelighter.java:106-113` + `PaperweightStarlightRelighter.java:55-65` | 否（当前未切分） |
| `postProcessChunks`：64 发包 + 64 撤 ticket | 全局线程发起，逐区块 `executeAt` 落到属主区域 | `PaperweightStarlightRelighter.java:71-87` | 可 |

### 2.3 单线程串行耗时占比（估算，需实测）

用循环边界取**下界**（非实测）：

- 编辑本体：96³ = **884,736** 方块，写入按区块列并行到各区域线程。
- 串行 relight 最小工作量：若 starlight 按 section 粒度重算，被清零的 section = 64 列 × 24 = **1,536 × 4,096 = 6.29M 个位置**，全在**一个线程**（另加传播本身）。
- 比值：**串行段 ≈ 编辑本体的 7.1× 方块访问量，且只用 1 个核**；若只看必要量（216 section ≈ 0.88M）则约 1.0×。
- 结论：`REMOVE_FIRST=true` 把"光照"从"与编辑同量级、可分摊"变成"7 倍量、单线程"。这就是 §2.1(b) 的量化后果。

> 可信度：section/方块数由循环边界精确得出；"starlight 按 section 重算"是**假设**（Paper 实现不在本仓库）。证伪只需在 `invokeRelight` 前后计时并统计被清零的 section 数（§7）。→ 归 **C**；但即使 starlight 只做增量，§2.1(a) 的跨区域阻塞与 (b) 的清零次数仍然成立。

### 2.4 附带：flushQueue 之后并不等 relight

`fixLightingSafe` 只做派发（`StarlightRelighter.java:142-153`），`isFinished()` 恒为 `false`（:190-193）。命令很快返回，光照在之后 tick 完成：**玩家看到光照更新滞后**，同一区域连续两次编辑会产生两批独立 relight（各自 ticket 生命周期）。评测"编辑后 TPS 抖动"必须按"延迟发生"采样。

---

## 3. 批次能否按区域 / chunk 分片

### 3.1 现状：一次调用、跨全部区域、无全局串行化

- 一次 relight = 一次 `starlight$serverRelightChunks(coords, ...)`（`PaperweightStarlightRelighter.java:55-65`），`coords` 最多 1024 个、跨几十个区域，执行线程是 `CompletableFuture.allOf(...).thenAccept` 的**完成线程**（`StarlightRelighter.java:106-113`），既不是区域线程也不是全局线程。→ 该线程会读写**所有**这些区域的光照数据（`DataLayer`），完全绕过区域归属。
- **没有跨 EditSession 的互斥**：`getLock()` 返回的是每个 relighter 实例自己的锁（`StarlightRelighter.java:39, 185-188`），而 relighter 是**每个 EditSession 新建**的（`EditSessionBuilder.java:589-596` → `EditSession.java:281`）。`EditSession` 只在"本次 flush 不要重复 relight"时 `tryLock` 自己的锁（`EditSession.java:1354-1367`）。
  → **两个玩家同时在不同/相邻区块编辑，会产生两批并发 relight**。相邻时它们的区块集重叠，同时对同一 `DataLayer`/`LayerLightEngine` 做重算，属于数据竞争（当前代码没有任何全局锁保护）。
- `fixLightingSafe` 的 `areaLock` 只保护 `regions` 这个 map 的 removeFirst（`StarlightRelighter.java:141-153`），不保护 starlight 调用。

### 3.2 分片方案与代价

| 方案 | 做法 | 正确性 | 代价 | 风险 |
|---|---|---|---|---|
| **按 chunk 分片**（每任务 1 区块） | 每个 chunk 单独调 starlight | **破坏跨区块传播**（天空光/方块光要出界 15 格），会出现明暗接缝 | 若每任务带 3×3 halo → 重复计算 **9×** | 高 → **D** |
| **按区域分片（自选分区）** | 先 `executeAt` 到一个区块，用它所在的区域线程把 `coords` 中 `Bukkit.isOwnedByCurrentRegion` 命中的子集取走，再对子集调 starlight；其余留给下一次投递 | 每个区域线程只碰自己的区块 —— **比现状更符合 Folia 模型**；分区之间需要 1 区块 halo 重叠，重叠处光会算两遍（结果收敛，不是错） | 每分区 +1 次投递 + 最多 n 次归属判定（n ≤ 1024）；halo 重复约 +2×边界区块 | 需要确认 starlight 对不同（不重叠+halo）区块集并发调用是否安全——**未知，必须实测** → **C** |
| **只做"串行化 + 线程收敛"** | 用一个静态锁把 `invokeRelight` 串起来；或固定到一个专用单线程 executor | 消除并发竞争（当前真实缺陷） | 串行化本来就串行，几乎无额外代价 | **低** → **A** |
| **维持现状** | — | 跨区域访问 + 并发竞争 | — | 风险高 |

### 3.3 结论

- "按 chunk 分片" 是**错的**（光传播跨界），不要做（D）。
- "按区域自选分区" 方向正确、能让每次 starlight 调用只碰本区域区块，但 starlight 的并发可重入性未知，**必须先实测**（C）。
- **优先级更高的是 §3.1 的并发竞争**：加一把全局锁串行化 `invokeRelight`，代价近乎为零（本来就单线程跑），收益是消除"多人同建时的光照数据竞争" —— 归 **A**，最小改法见 §6-A2。

---

## 4. 发包链路：人数放大、重复组包、按玩家裁剪

### 4.1 链路

```
StarlightRelighter.postProcessChunks(coords)          PaperweightStarlightRelighter.java:71-87（全局线程）
  └─ 每个 chunk 一次 PaperweightPlatformAdapter.sendChunk(pair, level, x, z)     :77
        └─ Folia: executeAt(区块) → sendChunkNow（属主区域线程，已拥有则就地）    PaperweightPlatformAdapter.java:385-401
              ├─ getPlayerChunk(...)  ← 反射 methodGetVisibleChunk.invoke        :376-383, :405
              ├─ getChunkAtIfLoadedImmediately(x,z)（Paper）                      :410-412
              ├─ nearbyPlayers → chunkMap.getPlayers(pos,false)                   :419, :464-466
              ├─ [已有] viewers.isEmpty() → return                                :420-423
              ├─ NMSAdapter.beginChunkPacketSend(...)   ← ConcurrentHashMap.compute + StampedLock 读锁；若已有发送在进行则放弃本次  :424-428
              ├─ packet = new ClientboundLevelChunkWithLightPacket(levelChunk, lightEngine, null, null)   ← 组包一次  :434-441
              └─ viewers.forEach(p -> p.connection.send(packet))                  :451
                    └─ NMSAdapter.endChunkPacketSend(...)                          :452-454
```

另有第二条入口（`NMSAdapter.sendChunk(IChunkGet,int,boolean)` → `AbstractBukkitGetBlocks.send()`，`NMSAdapter.java:102-108`、`PaperweightGetBlocks.java:798-803`），但它只被 `NMSRelighter.sendChunks` 使用（`NMSRelighter.java:922-947`），**本分支休眠**，不计入开销。

### 4.2 是否随人数 × 区块数线性放大

**结论：不是×总在线人数，而是 ×"编辑区块的可见玩家数"。**

| 环节 | 复杂度 | 依据 |
|---|---|---|
| 组包 | **1 次/区块**（不随玩家数） | 单个 `packet` 对象，`viewers.forEach(send)` 复用同一实例（`PaperweightPlatformAdapter.java:434-451`） |
| 发送/编码 | **k 次/区块**，k = 该区块的追踪玩家数（`chunkMap.getPlayers(pos,false)`，:464-466） | 每个 `connection.send` 在各自 netty 管线里编码一次（同一 packet 实例不会共享编码结果） |
| 可见性判定 | O(k)，不是 O(在线人数) | 同上；`getPlayers` 取的是区块玩家列表 |

具体量级（96³ → 64 次发包）：
- 10 人全部在编辑区附近：64 组包 + **640** 次编码/推送；
- 50 人全部在附近（出生点/活动广场）：64 组包 + **3,200** 次编码 → 这是唯一会"随人数线性放大"的部分，且放大倍数是"该区块的旁观者数"，与全服在线人数无直接关系；
- 50 人分散在大世界：单次编辑通常 k ≤ 3 → 64 组包 + ≤192 次编码。

→ 归类：组包侧 **D**（已是最优：一次组包、多人复用）；发送侧随 k 线性属**客户端协议固有**，唯一可省的是"不必发"，见 §4.3/4.4 → **C**。

### 4.3 同一 chunk 是否被重复组包

**同一次 flush 内：编辑过的区块只会发一次**——延迟发包时 `PaperweightGetBlocks` 的 callback 主动跳过发送：

```java
// PaperweightGetBlocks.java:753-759
if (!set.getSideEffectSet().shouldApply(SideEffect.LIGHTING)
    || !Settings.settings().LIGHTING.DELAY_PACKET_SENDING
    || finalMask == 0 && biomes != null) {
    this.send();          // 只有"不发延迟包"的情况才在这里发
}
```
与 `postProcessChunks` 里的 `if (delay) sendChunk(...)`（`PaperweightStarlightRelighter.java:72-78`）**互斥**，所以不会双发。并发重复发送也被 `NMSAdapter.beginChunkPacketSend` 挡掉（`NMSAdapter.java:182-196`：已在读/写锁时就放弃本次）。

**但存在"无效组包"（不是重复，而是不必要）**：`postProcessChunks` 遍历的是 `coords` = **3×3 扩张后的全部区块**，而 `StarlightRelighter.addChunk` 把 `skipReason`/`bitmask` 全丢了（`StarlightRelighter.java:118-133`），无法区分"真的改过"和"只是邻居"。
- 96³：发包 **64** 次，真正改过的只有 **36** 个 → **+78%**；
- 单区块编辑（`//set` 1 格）：**9** 次 vs 1 次 → **+800%**；
- 192³：196 vs 144 → +36%。

这些额外包的内容与客户端已有数据**基本相同**（只有光照可能变），完整 `ClientboundLevelChunkWithLightPacket` 含全部 section + 光照数组，单包体量随世界高度增长。

→ 归类 **B**（收益明确、可量化；风险中：邻块光照确实可能变，直接不发会留明暗接缝）。最小改法见 §6-B1。

### 4.4 能否按玩家裁剪

- 现状已是"区块级裁剪"：只发给该区块的追踪玩家（`getPlayers`），不是广播。
- 进一步"按玩家裁剪"需要知道"该玩家的客户端对这块 chunk 的哪部分已是最新"，即 Paper 内部的 `ChunkMap`/player chunk-send 状态。FAWE 绕过了 `ChunkMap` 的正常发送路径直接 `connection.send`（:451），**没有**这层状态。自己维护一份 per-player 脏标记 = 高复杂度 + 与 Paper 内部状态易失配。
- 有一个低风险的中间态：**按玩家视距裁剪**——`getPlayers(pos,false)` 返回的是"追踪该区块"的玩家，通常已含视距过滤；再叠加视距判断收益接近 0。
→ 归 **D**（不建议做）。真正的收益在 §4.3 的"不要发不必要的区块"。

### 4.5 每个区块的固定小开销（可白拿）

- `getPlayerChunk(...)` 是**反射调用**（`PaperweightPlatformAdapter.java:376-383`，`methodGetVisibleChunk.invoke`），但它的结果在 Paper 分支里**只用于判空**（:405-408，随后 :410-412 又用 `getChunkAtIfLoadedImmediately` 取真正的 chunk）。
  → Paper 上这次反射纯属多余：把判空换成 `getChunkAtIfLoadedImmediately(x,z) != null` 即可，省一次 `Method.invoke` + 异常包装/区块（:380-382）。
- `beginChunkPacketSend`/`endChunkPacketSend` 每次发包做 2 次 `ConcurrentHashMap.compute`（`NMSAdapter.java:184-195, 205-212`）。Folia 下同一区块的发送天然在属主区域线程串行，这把锁实际只在"并发重复发送"时才起作用；可保留（正确性保险），但要知道它是每区块 2 次 CHM 操作。
→ A 级但收益很小（每区块数十 ns 级），归 **A（微小）**。

---

## 5. heightmap 与光照是否重复遍历同一批方块

### 5.1 事实：三个独立的遍历，都在同一批 section 上

| 遍历 | 位置 | 范围 | 是否复用解码结果 |
|---|---|---|---|
| 历史 diff | `AbstractChangeSet.processSet`（`AbstractChangeSet.java:165-199`） | 每个被改 section：`set.loadIfPresent(layer)` + `get.load(layer)`，4096 次比较 | 首次解码 |
| 高度图 | `HeightmapProcessor.processSet`（`HeightmapProcessor.java:48-129`） | 从 `maxY>>4` 往下，每个非空 section：16×256 = 4096 次读取（:69-71） | **复用**：`set.loadIfPresent`（:60）、`get.load`（:83）命中同一份 `char[]` 缓存（`CharGetBlocks`），不会二次解码 |
| 光照 | starlight（服务端） | 每个登记区块**全部 24 个 section**（被 §2.1(b) 清零后重算） | 独立 |

→ 答案：**是"重复扫描"，但不是"重复解码"，也不是"重复计算"**。前两者在 `processSet` 阶段同一线程背靠背执行，第三次在写世界之后由服务端做。

### 5.2 高度图这一趟到底多贵（估算）

`HeightmapProcessor` 有两个关键剪枝：
1. 空 section 直接 `continue`（`HeightmapProcessor.java:55-59`），不进入 4096 循环；
2. `ordinal < 4`（空气/空）直接跳过（:97-99），不做 `includes()`；
3. 4 种 heightmap × 256 列全部命中即整体退出（:22 `NEEDED_UPDATES = 1024`，:116-118）。

所以典型地形下每个区块只需扫 **1~2 个 section**（地表附近即命中）：
- 96³：36 列 × ~2 section × 4096 ≈ **29 万次 char 读取 + 比较**（≈ 0.3M 次简单操作，属 μs~ms 量级）；
- 极端情况（全高度实心编辑，例如 `//set stone` 覆盖 -64..320 全高）：每列 24 section 全非空 → 36 × 24 × 4096 ≈ **350 万次**，此时它才与历史 diff 同量级。

### 5.3 能否合并？——结论 D（不建议）

- 理论上可以把高度图计算塞进历史 diff 的那一趟（diff 已有每方块的 `from/to`，顺带维护 4 个类型的"每列最高 y"即可）。
- 但收益有前提：
  - 只在**开启历史**时才有这一趟可"顺带"（`changeSetNull`/fastmode 下没有 diff）；
  - 由 §5.2 可知典型场景下高度图本来就只扫 1~2 个 section，收益是"省掉一次约 0.3M 次比较"，相对 §2 的光照量级可忽略；
  - 合并会让"历史记录"与"高度图"两个独立 `IBatchProcessor`（都是 `ProcessorScope.READING_BLOCKS`）互相耦合，改动 `AbstractChangeSet`（历史子系统的核心类）风险不成比例。
- 因此：**方向正确但收益/风险比很差 → D**。若要榨这点收益，更安全的位置是"在 `HeightmapProcessor` 里跳过完全没被改的 section"，但这已经是现状（`set.hasNonEmptySection` 剪枝，:55-59）。

> 顺带纠正一个常见误解：光照与高度图**不共享任何计算**。原版高度图是区块自带数据（`PaperweightGetBlocks.setHeightmapToGet` → `heightMap.setRawData(...)`，`PaperweightGetBlocks.java:152-159`），starlight 用的是自己的 section 遮挡追踪，两者无法互相推导。

---

## 6. 分级清单（省什么 / 何时看得出 / 风险 / 最小改法）

### A 级（收益明确、风险低）

**A1 —— 合并 `postProcessChunks` 里每区块的两次 `executeAt`**
- 省什么：每区块 1 次 RegionScheduler 投递（96³：128 → 64 个任务），以及全局线程循环里的 64 次入队/加锁。
- 何时看得出：区块数大（≥64）且全局线程有其它负载（50 人 + 多编辑并行）时；单次编辑的绝对收益约 0.1~0.3 ms，属于"看得见但不改变体感"。
- 依据：`PaperweightStarlightRelighter.java:77`（`sendChunk` 内部 `executeAt`，`PaperweightPlatformAdapter.java:392-397`）+ `:80-85`（又一次 `executeAt`）。
- 风险：**低**（只是把两个 lambda 合成一个；需要 `sendChunkNow` 的可见性）。
- 最小改法：给 `PaperweightPlatformAdapter` 加一个 `sendChunk(pair, level, x, z, Runnable after)` 重载（内部就地 `sendChunkNow` 再 `after.run()`），`postProcessChunks` 里去掉独立的 removeTicket 投递。

**A2 —— 给 `invokeRelight` 加全局串行化**
- 省什么：不是省时间，是**消除并发 relight 的数据竞争**（两个 EditSession 各自持锁，可同时进 starlight，见 §3.1）。
- 何时看得出：两个玩家同时在同一区域/相邻区域编辑（plot 服日常）；并发压测可复现异常或光照错乱。
- 依据：`StarlightRelighter.java:39, 185-188`（锁是每实例的）、`EditSessionBuilder.java:589-596`（每 EditSession 新建 relighter）、`StarlightRelighter.java:106-113`（在任意完成线程上直接调 starlight）。
- 风险：**低**（本来就是单线程跑，串行化不损失并行度）。
- 最小改法：`private static final Object RELIGHT_LOCK = new Object();`，`invokeRelight` 体包一层 `synchronized (RELIGHT_LOCK)`。

**A3 —— Paper 分支跳过 `getPlayerChunk` 反射**
- 省什么：每区块 1 次 `Method.invoke` + `try/catch` 包装；顺带去掉一个"结果只用于判空"的调用。
- 何时看得出：几乎看不出（ns 级/区块），属"顺手清掉"。
- 依据：`PaperweightPlatformAdapter.java:376-383, 405-412`（Paper 分支真正的 chunk 来自 `getChunkAtIfLoadedImmediately`）。
- 风险：**低**。最小改法：先 `getChunkAtIfLoadedImmediately` 判空返回，再取 holder（仅 `!PaperSupport.isPaper()` 分支需要）。

### B 级（收益明确、风险中）

**B1 —— 只给"真正改过 + 光照可能受影响的邻块"发包**
- 省什么：96³：64 → 36 次整列包（**-44%**）；单区块编辑：9 → 1（**-89%**）；192³：196 → 144（-28%）。每次是完整的 `ClientboundLevelChunkWithLightPacket`（全 section + 光照数组），并按旁观者数编码（§4.2）。
- 何时看得出：50 人集中在编辑区时最明显（每次都 ×k 编码）；小编辑（PS 单 plot 清理、`//set` 单格）时相对浪费最大。
- 依据：`StarlightRelighter.java:118-133`（`skipReason`/`bitmask` 被丢弃 → 无法区分"改过的"与"只是邻居"）、`PaperweightStarlightRelighter.java:71-87`（对全部 coords 无条件发包）。
- 风险：**中**。邻块天空光/方块光确实可能因跨边界传播而变；直接不发会在区块边界留明暗接缝。
- 最小改法（两步，先保守后激进）：
  1. 在 `addChunk` 里记录"这一列本身被改过"（`boolean` 集合 / `LongOpenHashSet`），`postProcessChunks` 对"改过的列"必发；对邻环只在"相邻的改过列其 `bitmask` 非 0"时发（保守版，收益约 -30%）；
  2. 若实测无接缝，再放开为"邻环一律不发"（激进版，-78%/~-89%）。**必须**用"编辑贴着区块边界 / 编辑在区块中心"两种用例目视验证。

**B2 —— `lighting.remove-first=false`（纯配置）**
- 省什么：§2.1 的**两件事一起消失**——
  (a) `removeLighting` 里每个区块的 `getChunk()` → `ensureLoaded().get()` **跨区域阻塞**（`PaperweightGetBlocks.java:919`）；
  (b) 每区块 24 section × 2 层的全高度清零（96³ 共 3,072 次 DataLayer 操作，§2.1(b)）。
  并且让 starlight 从现有光照出发做增量，而不是全量重算（§2.3 的 7.1× 放大随之消失）。
- 何时看得出：**最明显的一项**。跨区域编辑（96³ 常跨 2~4 个区域）时省掉 N 次跨区域阻塞；未加载区块的编辑省掉额外加载。
- 依据：`Settings.java:853`（默认 true）、`EditSession.java:1358-1362`、`Relighter.java:39-42`、`NMSRelighter.java:191-207`、`PaperweightGetBlocks.java:169-195, 912-932`。
- 风险：**中**（行为风险）：已有光照不被清除，若 starlight 的增量重算漏掉某些"变暗/变亮"的情况，会残留错误光照。默认值选 true 很可能就是为规避这一点。
- 最小改法：改配置即可（零代码）。建议灰度：先在测试服开 `remove-first=false`，用"挖洞/填实/贴边改光"三类用例目视验证；无接缝/残留则推广到生产。

### C 级（需实测）

**C1 —— 收紧 `LIGHT_LEVEL` ticket 半径**（`PaperweightStarlightRelighter.java:27-29`）
- 省什么：编辑落在未加载区域时的区块加载量（视距级半径 × 64 区块）。
- 何时看得出：离线玩家区、PS 清理、远处 `//set`。已加载区域无收益。
- 风险：**中-高**（半径不足会让 starlight 缺少邻域数据 → 光照错误）。必须实测。

**C2 —— 按区域自选分区并发 relight**（§3.2 方案二）
- 省什么：把单线程 relight 摊到各区域线程（96³ 跨 4 区域 → 理论上 4×）。
- 何时看得出：大范围编辑（≥64 区块）且跨多个区域时；小编辑无感（调度开销反噬）。
- 风险：**中-高**：需要 starlight 对"不相交区块集"并发调用安全（未知），还要处理 halo 重复计算。

**C3 —— heightmap 与历史 diff 合并**
- 省什么：典型场景每个区块 1~2 个 section 的扫描（96³ 约 0.3M 次比较）；全高度实心编辑才是 3.5M 次。
- 何时看得出：几乎看不出（相对光照可忽略，§5.2-5.3）。
- 风险：**中**（动 `AbstractChangeSet` 核心）。建议**不做**，列在此仅为闭环说明。

### D 级（不建议动）

- **D1 按 chunk 分片 relight**：每个区块单独调 starlight 会切断跨区块光照传播 → 明暗接缝；带 3×3 halo 则重复 9× 计算。
- **D2 按玩家裁剪发包**：需要 Paper 内部 per-player chunk 状态，FAWE 绕过 `ChunkMap` 直接 `connection.send`（`PaperweightPlatformAdapter.java:451`），自建状态易失配。现有 `getPlayers(pos,false)` 已是区块级裁剪。
- **D3 heightmap 与光照共享计算**：两者不共享可推导量（§5.3）。
- **D4 组包本身**：一区块一次组包、多人复用同一实例，已是最优（§4.2）。
- **D5 改 section 级增量包**：`ClientboundLevelChunkWithLightPacket` 就是整列包，协议固有限制。

---

## 7. 怎么把这些"需实测"变成数字

只读审查给不出 μs，需要短时插桩（临时、不入库），按顺序做：

1. **relight 段计时**：`StarlightRelighter.fixLighting` 里在 `invokeRelight` 前后 `System.nanoTime()`，同时打印 `coords.size()`。对比 `flushQueue` 总耗时 → 得到 §2.3 的真实占比。
2. **清零计数**：`NMSRelighter.removeLighting` 里计数 `removeSectionLighting` 调用次数（预期 96³ = 3,072），对比 `REMOVE_FIRST=true/false` 两种配置的 relight 耗时差 → 验证 §2.1(b) 的放大倍数。
3. **跨区域阻塞**：给 `PaperweightGetBlocks.getChunk()` 的 `.get()` 计时并记录 `Thread.currentThread().getName()`；若出现 >1ms 的样本即证实 §2.1(a)。或用 JFR 的 `jdk.JavaMonitorEnter`/`jdk.ThreadPark` 事件直接看区域线程等待。
4. **发包量**：在 `sendChunkNow` 入口计数 + 在 `:419` 的 viewers 处累计 k → 得到"组包次数 / 实际观察者数"两个序列，验证 §4.2 的线性关系与 §4.3 的浪费比例。
5. **并发竞争**：两个账号在相邻区域同时 `//set stone`（10 轮），观察日志异常、光照接缝；加 §6-A2 的锁后复跑对比。
6. **人数梯度**：10 人集中在编辑区 vs 50 人集中在编辑区 vs 50 人分散，各做 96³ `//set`，记录 §7-4 的两组计数与 MSPT/区域线程耗时 → 验证"放大的是 k 不是在线人数"。

