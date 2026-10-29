# 域：适配器方块写入路径（NMS→FAWE 写侧）

范围：`adapter-26.3/.../v26_3/{PaperweightGetBlocks,PaperweightPlatformAdapter,PaperweightFaweAdapter}.java`、
`worldedit-bukkit/src/main/java/com/fastasyncworldedit/bukkit/adapter/{NMSAdapter,AbstractBukkitGetBlocks}.java`，
外加 `CharBlocks/CharGetBlocks`、`FaweBukkitWorld`、`PaperweightGetBlocks_Copy`（调用链必经）。
只读审查，未构建、未改源码。规模口径：96³ = 36 区块 / 216 section；192³ = 144 区块 / 1728 section（每 section 4096 方块）。

---

## 0. internalCall 全链路：哪步按方块 / 按 section / 按区块

`AbstractBukkitGetBlocks.call`（71-110）→ `PaperweightGetBlocks.internalCall`（331-767）。逐段归位：

| 步骤 | 位置 | 粒度 |
|---|---|---|
| ensureLoaded + addTicket | AbstractBukkitGetBlocks:77 → PlatformAdapter:262-353 | **每区块** |
| beacons/实体/tile 处理 | GetBlocks:346-376、595-741 | 每区块 + **每方块实体** |
| 逐 section 主循环 | GetBlocks:383-571 | **每 section**（外层 25 层，仅命中 set 的层进入） |
| set.load + arraycopy 4096 | GetBlocks:454-456 | 每 section，4096 char |
| createCopy 的旧内容解包 | GetBlocks:470-473 + `load`→`update`(817-882) | 每 section，4096 解包 + 4096 拷贝 |
| createPalette / mapPalette / fromRaw | NMSAdapter:18-100、PlatformAdapter:501-519 | 每 section，**各 4096 迭代** |
| PalettedContainer.unpack | PlatformAdapter:530-538 | 每 section，4096 二次打包（待确认内部实现） |
| heightmap 写回 | GetBlocks:573-576 → 152-159 | 每区块 × 类型数（≤5）× 256 |
| light 写回 | GetBlocks:577-586 → 934-965 | 每 section × 4096（逐方块 `DataLayer.set`） |
| 同步 tasks / 发包 | GetBlocks:588-765 → Abstract:145-195 | 每区块 |

**重复读取/重复分配清单**（本域新结论，非已知项）：
1. 每 section 取两次 `SENDING_CHUNKS` 表（setSectionAtomic 进/出各一次），每次都是"字符串查世界 + 全局锁定 compute"。
2. 每 section 跑两遍 4096 的"方块序号→调色板序号"映射（createPalette 一遍 + mapPalette 一遍），第二遍的 4096 次 `blockToPalette[ordinal]` 随机读与第一遍完全相同。
3. `num_palette == 1` 的 section 仍然跑完 mapPalette 的 4096 迭代，而它的输出 `blocksCopy` 根本没人读（`bits == null`）。
4. 每区块 `new HashMap<>(nmsChunk.getBlockEntities())` 全量复制方块实体表（GetBlocks:346）。
5. `updateGet` 在只换 1 个 section 时会 `reset()` 掉整块 25 个 section 缓存（GetBlocks:527-530），另加一次无意义的 2 次分配（790）。
6. `clearCounts` 对同一 section 调两次（466 与 514，各 2 次反射写）。
7. 每区块一次 `set.getHeightMaps()`，无高度图时也会 `new EnumMap`（CharSetBlocks:115）。
8. `PaperweightGetBlocks` 构造时每区块做 2 次 biome registry 查询 + `asHolderIdMap()` 包装 + 3 个 25 长数组 + **25 个 sectionLock 监视器对象**。

---

## 1. 文件：NMSAdapter.java / FaweBukkitWorld.java / PaperweightPlatformAdapter.java

[A] NMSAdapter.java:136-165（+138 行 与 155 行）每 section 两次全局锁 + 两次字符串查表去取 "发送锁表"
  - 机制：`setSectionAtomic` 在非主线程路径上，进入时 `FaweBukkitWorld.getWorldSendingChunksMap(worldName)`（137），finally 里又调一次（155）；`beginChunkPacketSend`(183)、`endChunkPacketSend`(204) 各自再来一次。而 `getWorldSendingChunksMap(String)`（FaweBukkitWorld:53-55）→ `of(worldName)`（41-47）→ `Bukkit.getWorld(name)` 字符串查表 → `CACHE.compute(...)`，`CACHE` 是 `Collections.synchronizedMap(new WeakHashMap<>())`（FaweBukkitWorld:18）。`synchronizedMap.compute` 直接 `synchronized(mutex)` —— **所有世界、所有 FAWE 工作线程共用一把全局监视器**，锁内还有 WeakHashMap 的 expungeStaleEntries。
  - 省什么：每 section 2 次全局监视器 + 2 次 String 哈希查世界 + 2 次 WeakHashMap 查找，降到 0（改为每区块取一次、按参数传入）。192³ = 1728 section → 3456 次；再叠加发包路径（每区块 begin/end 各 1 次 = 288 次），192³ 合计约 **3744 次全局串行点 → 0**。单线程绝对量约 0.2–0.4 ms/192³（≈0.1%，可忽略）；真正价值是删掉这条全局串行队列。
  - 触发条件：并发编辑 ≥2 个 / Folia 多 region 同时写；在线 10–50 人各自 //set、或多个插件调度异步编辑时，所有线程在这把锁上排队（锁持有期含 WeakHashMap 操作，比普通 CHM 更重）。单线程编辑时只是纯开销。
  - 风险与理由：低。纯"把一次查找提升到外层"，语义不变；只需保证世界卸载后不继续用旧表（GET 生命周期短于编辑，天然满足）。注意不要新增静态强引用世界名→表的永久缓存，否则世界重载后表会串。
  - 最小改法：`internalCall` 里 `ConcurrentHashMap<IntPair, NMSAdapter.ChunkSendLock> sending = FaweBukkitWorld.getWorldSendingChunksMap(nmsWorld.getWorld().getName());` 取一次，给 `setSectionAtomic`/`beginChunkPacketSend`/`endChunkPacketSend`/`sendChunkNow` 加"接收表"的重载；旧签名保留转调（其它 adapter 目录共用同一份 NMSAdapter）。

[B] NMSAdapter.java:138-148 + PaperweightPlatformAdapter.java:404-423 无人观看时 ChunkSendLock 永久滞留（内存随编辑区块数单调增长）
  - 机制：`setSectionAtomic` 的 finally 里 `computeIfPresent` 显式 `return lock;`（162-163，注释写明"Keep the lock ... as we're going to be accessing again later when sending"），只有 `endChunkPacketSend` 会 `return null` 删除。而 `sendChunkNow` 有三个早退分支在 `beginChunkPacketSend`(425) **之前/之外**返回：`chunkHolder == null`(406-408)、`levelChunk == null`(416-418)、`viewers.isEmpty()`(419-423)，以及 `lockHolder.chunkLock == null`(426-428)。这些路径都没有回收条目。
  - 省什么：每条 `IntPair(24B) + CHM.Node + ChunkSendLock(内含 StampedLock ≈ 40B)` ≈ **100 B/区块**，且永不释放（CHM 表也不缩容）。10 万区块 ≈ 10 MB 常驻；`//regen`、后台大面积 //set、编辑无人在线区域时最明显（regen 用的私有 level `getPlayers` 恒为空 → 必然走 viewers.isEmpty 分支）。
  - 触发条件：异步写（默认大编辑走队列工作线程，`Fawe.isMainThread()` 为 false 才会建条目）+ 该区块当前无旁观者。一次性编辑 1 万区块 ≈ 1 MB；长期跑后台任务的服会持续爬升，只能靠重启回收。
  - 风险与理由：低–中。回收条件必须与 `beginChunkPacketSend` 一致：`!lock.writeWaiting && !lock.lock.isWriteLocked() && lock.lock.getReadLockCount() == 0` 才允许置 null；否则会把同一区块的"写锁对象"拆成两个实例，读/写不再互斥（发包期间读到半更新的 section 数组）。
  - 最小改法：新增 `abandonChunkSendLock(map, pair)`，在 `sendChunkNow` 每个早退分支前调用（`computeIfPresent` 内做上述三条件判断，满足才 return null）；或在 `internalCall` 判定"本区块不会发包"时直接回收。

---

## 2. 文件：NMSAdapter.java（createPalette / mapPalette，写侧最热的一段）

[A] NMSAdapter.java:26-40 与 77-100 单值 section 仍白跑 mapPalette 的 4096 次映射
  - 机制：`createPalette` 末行无条件 `mapPalette(...)`（37 / 72）；而 `newChunkSection` 在 `num_palette == 1` 时把 `bits` 置 null（PlatformAdapter:506-509），`PackedData(palette, Optional.ofNullable(bits))`（530）不再携带 `blocksCopy`，`blocksCopy` 无人读取 —— 但 mapPalette 已经跑完 4096 次 `blockToPalette[set[i]]` 随机读 + 4096 次 `blocksCopy[i]` 写（94-98）。
  - 省什么：每个单值 section 省 **4096 次随机 int 读（数组为 `int[BlockTypesCache.states.length]`，万级项 ≈ 100 KB 级，L2 边界）+ 4096 次 int 写 + 1 次 `__RESERVED__` 别名存取**。均匀填充（`//set stone`、整层替换）时 96³ 里绝大多数 section 是单值：按 216 section 中 150 个单值算 ≈ 61 万次随机读；192³ 可达 600 万次量级。
  - 触发条件：任何"整段同种方块"的写入 —— `//set`/`//walls`/`//sphere` 内部、`//paste` 实心建筑内部、自然地形里的全石/全空气 section。方块量越大、区域越实心越明显。
  - 风险与理由：低。`bits == null` 路径不读 `blocksCopy`（PlatformAdapter:508-530），`blockToPalette` 的复位仍由 `newChunkSection` 的 finally 按 `paletteToBlock[0..num_palette)` 完成（550-552）。唯一要确认的是"没有别的调用方依赖 blocksCopy 在单值时被填满"—— `createPalette` 的两个调用点都在 `newChunkSection`（PlatformAdapter:501、503），全仓无其他调用（已 grep）。
  - 最小改法：`createPalette` 里改成 `if (numPaletteEntries != 1) { mapPalette(...); }`（两个重载各一处）。

[B] NMSAdapter.java:26-40 + 94-98 createPalette 与 mapPalette 两遍 4096 可合成一遍
  - 机制：第一遍（27-36）建 `blockToPalette/paletteToBlock` 映射，同时读了一次 `blockToPalette[ordinal]`；第二遍（94-98）又对每个方块**再读一次同一张表的同一项**，只为写 `blocksCopy[i]`。`blockToPalette[ordinal]` 一旦被赋值就永不改变，所以第一遍即可顺手写出 `blocksCopy[i] = palette`。
  - 省什么：**每 section 4096 次随机读**（192³ = 7,077,888 次，正好等于"每方块少一次 100 KB 级数组随机访问"）+ 一整个 4096 循环的循环开销。对非单值 section（山地、材质混杂、贴图边缘）是主收益项；与 [A] 叠加后 palette 映射从"3 遍"降到"1 遍"。
  - 触发条件：所有非单值 section，即任何带材质的编辑；192³ 全量放置时 1728 section 全部受益。
  - 风险与理由：中。`mapPalette` 有两条必须保留的边角逻辑：(a) `globalKindaDoesNotExist == false` 且 `bitsPerEntry > 8` 时用全局 id 表整体覆盖映射并重算 `blocksCopy`（88-91）——26.3 两个调用点都传 `true`（PlatformAdapter:501/503），此分支在 26.3 是死代码，但 1_21_4/1_21_5/1_21_6 传 `false`（各 adapter 同源文件），合并时**必须保留该分支**（触发时二次重算 `blocksCopy`）；(b) `__RESERVED__`(0) → `blockToPalette[0] = blockToPalette[AIR]` 别名（92-99）在"非 layer 变体"里必须保留，因为那条路径不回写 `set`（27-36 只做 `Math.max`，`set` 里仍是 0）。合并后若把"未解析的 0"直接当调色板下标，就会出现取到 AIR 以外序号 → 错误方块，属于不可接受的失败模式。
  - 最小改法：在 `createPalette` 两遍循环里就地写 `blocksCopy[i]`；`mapPalette` 保留但只负责"全局调色板覆盖"这一条支路（改成 `remapGlobalPalette(...)`，仅当 `!globalKindaDoesNotExist && bits > 8` 调用）。先在 26.3 上跑"含 0 序号的 set"与"1_21_6 单测"两组对照。

[C] PaperweightPlatformAdapter.java:506-538 + 519 传给 `unpack` 的 LongStream 造成第二个 4096 打包/拷贝
  - 机制：FAWE 先把 `blocksCopy` 打包进线程本地 `blockStates`（`bitArray.fromRaw`，518），再用 `Arrays.stream(blockStates, 0, end)`（519）包成 `LongStream` 交给 `PalettedContainer.unpack`（533）。`unpack` 需要 `long[]`，必然 `toArray()` 拷一份，再按调色板宽度决定是包装还是逐格 `set`（4096 次）。也就是说每 section 至少多一次 `long[]` 分配（1–2 KB）+ 4096 项拷贝，若 strategy 位宽不同还有一次 4096 重映射。
  - 省什么：每 section 1 次 `long[]` 分配 + 1 次 4096 拷贝（`Arrays.stream` 还额外分配 stream/spliterator 对象）；192³ = 1728 次分配、7M 项拷贝。若能用已解析但**当前完全未被使用**的 `dataConstructor`（PlatformAdapter:87/121-122，全仓仅声明无引用）直接构造 `PalettedContainer.Data`，可整体省掉这条 4096 重打包。
  - 触发条件：所有 section 写；收益按 section 数线性放大（192³ 比 96³ 多 8 倍）。
  - 风险与理由：不确定 → 需实测。`unpack` 的具体分支（是否等价包装 `long[]`、何时扩容调色板）在 26.3 上**待确认**（仓库里没有 Paper 的 NMS 源码，无法静态求证）；直接反射构造 `Data` 会绕过 strategy 的位宽/调色板合法性校验，一旦位宽与 `strategy` 期望不符就是崩溃或错误方块。
  - 最小改法：先只做一个可观测性改造——统计 `unpack` 入参与实际 `storage.getBits()` 是否恒等；若恒等，再尝试用 `dataConstructor` 走"直接包装"分支并保留回退到 `unpack` 的开关。

---

## 3. 文件：PaperweightGetBlocks.java

[B] PaperweightGetBlocks.java:934-965 light 写回是逐方块 4096 次 `DataLayer.set`，且全程持 `synchronized(dataLayer)`
  - 机制：`fillLightNibble` 对每个非空 section 走 16×16×16 三重循环，逐格 `dataLayer.set(x, y, z, light[Y][i])`（958）；每次调用都要重算 `index(x,y,z)`、做半字节掩码和边界处理。判空 `if (light[Y][i] < 16)` 对 0–15 恒真，等于无条件写。整个 4096 次循环在 `synchronized (dataLayer)` 内（952），block 层与 sky 层各来一遍（`setLightingToGet` + `setSkyLightingToGet`，GetBlocks:577-586）。
  - 省什么：每 section 每层从 4096 次"带坐标计算的 setter"降到 2048 次 `byte[]` 直接写（一个字节装两个 nibble）。带光照的粘贴/撤销：96³ 216 section × 2 层 ≈ **170 万次 setter → 88 万次字节写**；192³ 约 1416 万次。
  - 触发条件：`set.getLight() != null`，即带光照的 schematic 粘贴、`//undo` 回放带光照的编辑、`LIGHTING` 副作用开启时。纯 `//set` 不走这段。
  - 风险与理由：中。必须与 26.3 的 `DataLayer`(NibbleArray) 布局严格一致：`getData()` 的下标与半字节顺序（偶数 x 落低 4 位）需现场核对（标**待确认**）。写错半字节不会崩，只会让光照错乱且难以察觉，必须配一个"写后读回逐格比对"的自检。
  - 最小改法：取一次 `byte[] raw = dataLayer.getData()`，用 `i = (y << 8) | (z << 4) | x` 的同一索引在 `light[Y][i]` 上顺序读取，按 `raw[i >> 1]` 的低/高半字节写；保留原逐格实现在开关后以便对照。

[B] PaperweightGetBlocks.java:346 每区块全量复制方块实体表（BE 密集区块的主要浪费）
  - 机制：`new HashMap<>(nmsChunk.getBlockEntities())` 在进入 section 循环前无条件执行，之后才遍历判断哪些 tile 会被覆盖（349-376）。复制是为了在遍历中安全删除（370 `nmsChunk.removeBlockEntity`），但绝大多数区块根本没有任何 tile 需要删。
  - 省什么：每区块一次 `HashMap` 全量复制 = N 个 Node + N 次 rehash + N 次 `BlockPos` 引用搬运。仓库/漏斗阵列区块可达数百到 4096 个 BE：单区块 ≈160 KB 复制（4096 个 Node 32B + 8192 槽引用表）+ 上千次 `set.hasSection/getBlock` 判断；96³ 中若 36 个区块都是仓储区 ≈ 6 MB 瞬时复制，192³ 144 区块 ≈ 23 MB。
  - 触发条件：被编辑区块的方块实体密度高：大规模箱子/漏斗仓库重写、`//paste` 大型建筑（含大量箱子/告示牌）、`//removenear` 类操作。普通地形编辑 BE≈0，只有 1 个空 HashMap 的开销。
  - 风险与理由：低。只把"是否需要复制"的判断提前：先只读遍历活表，确认没有任何 BE 落在 set 覆盖的 section 内（355 的 `set.hasSection(layer)` 且 `set.getBlock(...) != __RESERVED__`）就完全跳过复制；一旦命中再复制并走原逻辑。遍历活表只读，不修改，不会触发 CME。
  - 最小改法：把 346 行改成惰性：先 `Map<BlockPos,BlockEntity> live = nmsChunk.getBlockEntities();` 做一遍筛选，命中才 `chunkTiles = new HashMap<>(live)` 并进入删除循环。
  - 待确认：26.3 的 `LevelChunk#getBlockEntities()` 是否本身已返回副本（若已返回不可变视图，则本项只剩"空转遍历"收益，价值降一档）。

[C] PaperweightGetBlocks.java:152-159 / 573-576 每区块把全部高度图拆包再重打包（5 类 × 256 条），并附带一次无谓 EnumMap 分配
  - 机制：`internalCall` 对 `set.getHeightMaps()` 的每个条目调用 `setHeightmapToGet`：新建 `BitArrayUnstretched(log2nlz(height+1), 256)`（154）、`fromRaw(int[256])`（155）逐条打包，再 `heightMap.setRawData(chunk, type, longs)`（158）拷进 NMS 现有 raw。`CharSetBlocks.getHeightMaps()`（114-115）在无高度图时返回 `new EnumMap<>(...)`，即每区块一次纯丢弃的分配。
  - 省什么：每区块 5 类 × 256 条 × (拆包 + 打包 + 拷贝) ≈ 3840 次位移运算 + 5 次 `BitArrayUnstretched` 分配；96³ = 36 区块 → 180 次、13.8 万次位移；192³ → 720 次、55 万次。相对 1728 个 section 的 700 万方块，量级约 1–2%，属于"顺手清掉"而非主战场。
  - 触发条件：任何一次区块写都会走（只要 set 带了高度图数据，FAWE 的 heightmap processor 默认会带上）。
  - 风险与理由：低（若只做"跳过与现状完全相同的高度图"）；中（若想省掉拆包/打包本身）。要判定"相同"必须先把 NMS raw 拆出来比对，可能比直接写更贵 → 只在能证明"整块未被改动"（例如高度图的 max/min 未跨过本次编辑的 y 范围）时才跳过。
  - 最小改法：先只在 `CharSetBlocks.getHeightMaps()` 返回 `Collections.emptyMap()` 而不是新建 EnumMap（消除每区块一次分配）；拆/打包优化留到有 profile 数据后再定。

[C] PaperweightGetBlocks.java:380-766 `synchronized (nmsChunk)` 覆盖了全部 palette 构建，并在持锁状态下阻塞等主线程
  - 机制：`synchronized (nmsChunk)` 从 380 一直开到 766，中间包含 216/1728 次 `newChunkSection`（4096×3 遍的 CPU 密集段）、heightmap/light 写回，以及 765 行的 `return handleCallFinalizer(...)` —— 后者在非 Folia 下会 `queueHandler.sync(chain)`（AbstractBukkitGetBlocks:175）**阻塞该工作线程直到主线程跑完**。即：持区块监视器等主线程，锁序为 chunk 监视器 → sectionLock → 全局 CACHE 锁。
  - 省什么：不是省计算，而是缩小临界区：单区块持锁时间 ≈ 每 section 构建 0.1–0.5 ms × section 数，即 96³ 场景 0.6–3 ms、192³ 单区块 1.2–6 ms。把 palette 构建挪出监视器后，同区块的多个 section 可与"主线程读该区块 / 另一 worker 发包"并行，且消除"worker 持锁等主线程"的结构性停顿。
  - 触发条件：同一区块被主线程或另一 worker 同时访问（多人同区域编辑、`//paste` 与玩家交互重叠、Folia 同 region 的其他任务）；单纯单线程大编辑看不出差别。
  - 风险与理由：中–高。监视器现在同时兼任"区块级互斥"，缩范围要逐个确认共享状态：`levelChunkSections` 数组元素靠 `setSectionAtomic` 的 CAS + StampedLock 保护（够用），但 `existingSection.getBiomes()/getStates()`、`clearCounts`、`applyLock` 的读取顺序需要重排；`handleCallFinalizer` 绝不能留在锁内（同步 tasks 会回调 NMS）。
  - 最小改法：只做两件低风险的事 —— 把 765 行的 `handleCallFinalizer` 移到 `synchronized` 块**之后**（返回值先存局部变量），以及把 `createCopy` 的 `load()`(470) 移出监视器。剩下的大改留作独立 spike。

---

## 4. 文件：AbstractBukkitGetBlocks.java / PaperweightGetBlocks.updateGet（缓存失效）

[D] PaperweightGetBlocks.java:517-537 与 769-796 单个 section 交换会 `reset()` 掉整块 25 个 section 缓存；790 行每 section 2 次无谓分配
  - 机制：(a) 523-530：`this.getChunk() != nmsChunk` 或 `existingSection != getSections(false)[getSectionIndex]` 时置 `sections = null` 并 `reset()`（两支互斥，只有一支执行）；`CharBlocks.reset()`（91-98）对**全部 25 层**逐个 `synchronized(sectionLocks[i]) { blocks[i] = null; }` —— 换 1 个 section 却清掉 25 个缓存数组。每区块首次写入触发 1 次 → 25 次监视器进出 + 随后 `updateGet`(784-787) 的 1 次 25 元素 `LevelChunkSection[]` 分配+arraycopy。(b) 790 行 `this.sections[layer] = new LevelChunkSection[]{section}.clone()[0]` —— 新建 1 元素数组再 `clone()`，结果与 `section` 恒等，纯属 2 次分配。(c) `updateGet`(778) 在 `sectionLock.writeLock()` 内还调了一次 `getChunk()`（levelChunk 已置位时是一次字段读，但首次可能进 `ensureLoaded(...).get()` 阻塞等待）。
  - 省什么：每区块约 25 次监视器进出 + 3 次小数组分配，量级 **≈0.5 µs/区块**（144 区块 ≈ 0.07 ms，对 192³ 的 270 ms 是 0.03%）。也就是说：**这里确实存在"多余整 chunk reset"，但它不值得为本轮优化动刀**——收益被 1728 个 section 的 4096 遍映射彻底淹没，而改动会碰到多线程下的 section 缓存可见性（`blocks[layer]` 与 `sections[layer]` 必须成对一致，否则读到旧 array 会写入被丢弃的缓存）。
  - 触发条件：只在区块首写/区块被外部替换时命中；编辑规模再大也不会放大（每区块一次）。
  - 风险与理由：高（相对收益）。`reset()` 的粗粒度是`update`/`load` 与 `setSectionAtomic` 之间"读缓存是否失效"的唯一保证；改成按层 reset 需要证明调用点不会拿着旧 `blocks[layer]` 继续写（`Section.update` 会直接覆盖 `blocks[layer]`，见 CharBlocks:211-225）。
  - 最小改法：只取零风险的 790 行 → `this.sections[layer] = section;`（语义完全等价，2 次分配归零）。`reset()` 粒度留作注释说明，不改。
  - D 附注（同判 D，不单列）：`clearCounts` 对同一 section 调两次（466 与 514，全路径下 466 先跑、514 再跑一遍，各 2 次反射 `Field.setShort`，192³ 1728 section → 3456 次冗余反射写，绝对值 <50 µs）；`PaperweightGetBlocks` 构造时每区块 new 25 个 `sectionLocks` 监视器（CharBlocks:59-63），192³ 144 区块 = 3600 个对象，但它们是并发读写的实际互斥点，动它风险远大于收益。

---

## 正确性雷（只标注，不展开）

1. PaperweightGetBlocks.java:232-236 建"天空光"数据层时传的是 `LightLayer.BLOCK`，随后把它缓存进 `skyLight[alayer]` —— 会把方块光当成天空光读，并污染 BLOCK 层。
2. NMSAdapter.java:141-143 已存在写锁时直接抛 `IllegalStateException`，被 `AbstractBukkitGetBlocks.tryInternalCall`(137-139) 的 `catch (Throwable)` 吞成 `return null` —— 该区块本次改动**静默丢弃**（方块缺失），日志只有一行 error。
3. PaperweightGetBlocks.java:939 用字段 `levelChunk` 而非 `getChunk()` 取 pos，构造后未取过区块时可能 NPE。
4. CharSetBlocks.java:115 无高度图时返回**可写的临时 EnumMap**，调用方一旦 `put` 会静默丢失（`internalCall` 只读所以当前无害）。
5. PaperweightPlatformAdapter.java:589 `new LinkedList<>(Set.of(biomes))`：`Set.of(E...)` 对 null 元素抛 NPE，而紧随其后的 590-592 就在处理 `biomeType == null`（说明该 64 格数组允许含 null）；即"部分指定 biome 的 section"会在此 NPE，被 `tryInternalCall` 吞成该区块改动静默丢弃。仅在 `existingSection == null`（区块缺失该 section）时可达。

---

本域结论：写侧真正值得动的只有两处成规模的开销 ——(1) palette 映射的三遍 4096 可以压到一遍，并且单值 section 根本不必跑 mapPalette（NMSAdapter:26-100，A/B 两条，192³ 量级是每方块少 1–2 次 100 KB 数组随机访问）；(2) 每 section 两次全局监视器取表 + 无人观看时不回收 ChunkSendLock，前者是并发编辑的串行点（A），后者是随编辑区块数单调增长的常驻内存（B）。light 的逐方块 4096 次 setter 只在带光照粘贴时放大约 30%，值得做但需要半字节布局核对。`reset()/updateGet` 的"多余整 chunk 失效"确认存在，量级仅 ≈0.5 µs/区块，判 D 不动；每区块的 BE 表复制与高度图拆打包属于"顺手清"级别（B/C）。`synchronized(nmsChunk)` 跨度覆盖 palette 构建且持锁等主线程，是本域唯一的结构性锁序隐患，但改动风险中–高，建议只先搬走 `handleCallFinalizer` 与 `createCopy` 的 `load()`。
