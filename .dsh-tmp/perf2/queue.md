# 域报告：队列与刷盘（写队列 → 区块任务 → 历史）

审查范围：`worldedit-core` 的 `queue/implementation/*`、`queue/implementation/chunk/*`、`queue/implementation/blocks/*`、`util/MemUtil.java`、`history/**`、`extent/filter/block/CharFilterBlock.java`，以及 `worldedit-bukkit` 侧真正落地 section 的 `PaperweightGetBlocks`（26.2 适配器，与 26.1/26.3 同构）。
基准口径沿用已有结论：96³=884,736 方块/36 区块，192³=7,077,888/144 区块；close≈113/270 ms（CI）。本报告只列**下一批**可落地项。

## 先说总账（回答简报里的四个问题）

一次 192³ 写入、默认配置（HISTORY 开启、SEND_BEFORE_HISTORY=true、STORE_REDO=true），队列层实际发生：

| 步骤 | 位置 | 192³ 次数/字节 |
| --- | --- | --- |
| 每个 chunk 一份 `ChunkHolder`（**不复用**，`recycle()` 只打日志） | SingleThreadQueueExtent.java:409-411 | 144 个 |
| 每个 chunk 一份 `CharSetBlocks` + `char[16][]` + 16 个 `Object` 锁（池从不回填，见第 8 条） | CharSetBlocks.java:29-53、66-69 | 144 × ≈19 个对象 |
| 每个 chunk 一份 `PaperweightGetBlocks`（2×`DataLayer[24]`+2 把锁+CHM） | PaperweightGetBlocks.java:119-125 | 144 个 |
| 每个 chunk 一份 `PaperweightGetBlocks_Copy`（`char[24][]`+HashMap+HashSet） | PaperweightGetBlocks_Copy.java:55-63 | 144 个 |
| **第 1 份拷贝**：整个 set 深拷贝进 `ThreadUnsafeCharBlocks`（非池化） | ChunkHolder.java:1050 → CharSetBlocks.java:358-399 | 1,728 × 8 KB = **13.8 MB** |
| **第 2 份拷贝**：每个写入 section 再拷一份 `setArr` | PaperweightGetBlocks.java:454-456 | 1,728 × 8 KB = **13.8 MB** |
| **第 3 份拷贝**：后处理（历史）用的旧数据 `copyArr` | PaperweightGetBlocks.java:469-477 | 1,728 × 8 KB = **13.8 MB** |
| 历史记录 | FaweStreamChangeSet.java:363-373 | 7,077,888 条 ≈ 71 MB 逻辑字节 / 约 8500 万次 `write` 调用 |
| 历史压缩 | MainUtil.java:291-329（默认 level=3） | 3 层 LZ4 串联 + 5 个 519 KB 缓冲 = **2.6 MB 缓冲/编辑** |

- **队列层每方块的无谓判断**：`//set`/`//replace` 走 `ParallelQueueExtent.apply`→`CharFilterBlock`，逐方块开销是 `filter.applyBlock` + `delegate.set`（`CharFilterBlock.java:176-184, 245-248`），没有按方块过 `getOrCreateChunk`；只有 `IChunkExtent.setBlock`（粘贴/API/插件逐方块写）才是每方块 1 次 `lastChunk` 原子读 + 2 次比较（`SingleThreadQueueExtent.java:332-336`）。
- **空 chunk 睡眠会不会反复走到**：会，而且是**每个**。只读或全被过滤的 chunk（`ChunkHolder.isEmpty()` = `chunkSet==null||empty`，ChunkHolder.java:969-971）在 `IQueueExtent.apply` 收尾处逐个 `submit`（IQueueExtent.java:200-203），每次睡 4~5 ms，`flush()` 再兜一遍，见第 1 条。
- **历史是否每方块装箱/数组拷贝**：不装箱（`MutableBlockChange` 单实例复用，FaweStreamChangeSet.java:445-493），但每方块 ~10 次单字节虚调用，见第 2 条。
- **压缩层是否反复压同一区块**：不会。每个 changeset 只有 6 条流，`getBlockOS` 有 `osBD != null` 快路径（DiskStorageHistory.java:303-306），每 chunk 只 `processSet` 一次。问题不是重复压，是**默认 3 层串联压缩**（第 3 条）。

---

[A] worldedit-core/.../queue/implementation/SingleThreadQueueExtent.java:239-255 空 chunk 提交时 `Thread.sleep(5 - age)`
  - 机制：`submitUnchecked` 先判 `chunk.isEmpty()`，是则 `long age = holder.initAge()`，`age < 5` 就 `Thread.sleep(5 - age)`，睡醒再判一次空、仍空才返回 `immediateFuture`。三条入口都会走到：(1) **主路径**——`IQueueExtent.apply(...)` 每处理完一个 chunk 就 `submit(toSubmit)`（IQueueExtent.java:200-203），即 `//copy`、`//count`、`//distr`、`//replace` 无匹配时**每个 chunk 都进一次**（不是只有 flush）；(2) `flush()`（SingleThreadQueueExtent.java:507-515，把 map 里每个剩余 chunk 再提交一遍）；(3) `getOrCreateChunk` 的队列溢出路径（:356-363）。空的定义是 `ChunkHolder.isEmpty()`=`chunkSet == null || chunkSet.isEmpty()`（ChunkHolder.java:969-971），即"只被读过、或被 mask 全过滤掉"的 chunk。
  - 省什么：每个空 chunk 4~5 ms 的**调用线程阻塞**（`Fawe.isMainThread()` 为真时就是主线程本身，见 :269-276；`instance == null` 也返回 true，Fawe.java:210-212，所以单测/CI 环境下全部 inline）。
  - 触发条件：只读操作（`//copy`、`//count`、`//distr`、`//replace` 无匹配、`//paste` 到已有方块）或任何 mask 全过滤的编辑；chunk 数越多越明显。96³（36 区块，单线程 inline）≈ 180 ms；192³（144 区块）≈ 720 ms；并行执行时按线程摊薄（4 线程 ≈ 45/180 ms），但纯属白等——它把只读操作变成"每 chunk 至少 5 ms"的串行下限。写入型 `//set` 不命中此路径。
  - 风险与理由：**中**。注释写明这是"给这个刚建出来的 chunk 留出被别人写入的时间"，本质是竞态兜底；不过 STQE 的 chunk 提交后已从 `chunks` map 移除、`ChunkHolder` 也不复用（`create(true)` 每次 `new`，`recycle()` 只 warn，:88-95、:409-411），实际窗口只剩"同一 chunk 被另一线程并发 `getOrCreateChunk`"。
  - 最小改法：把睡眠限制在跨线程场景：`if (age < 5 && Thread.currentThread() != currentThread)`（`currentThread` 是 `init()` 记录的初始化线程）；或整体换成一次 `Thread.onSpinWait()`/`yield()`，把 5 ms 降到微秒级。只读路径（`chunkSet == null`）可以直接跳过。

[A] worldedit-core/.../history/changeset/FaweStreamChangeSet.java:363-373（配合 :208-232、:113-118）历史每方块 8~12 次单字节写
  - 机制：`add(x,y,z,from,to)` 每方块做：`blockSize++`；`getBlockOS(x,y,z)`（虚调用 + null 快路径，DiskStorageHistory.java:303-306）；`posDel.write(...)` 里 **6 次 `stream.write((byte)…)`**（mode 3/4，:208-232）；`idDel.writeChange` 里 2 次 `writeVarInt`（:115-118），每次 `writeVarInt` 再拆成 2~3 次 `writeByte`。每次写都进 `FaweOutputStream.write`（:25-28，`out.write(b); written++`）再穿 `FastBufferedOutputStream`→3 层 `LZ4BlockOutputStream`（MainUtil.java:291-329）。单方块无分配，但有约 10 次虚调用 + 每次一层缓冲边界判断。
  - 省什么：96³ 884,736 方块 × ≈10 ≈ **880 万次写调用**；192³ ≈ **7,100 万次**。合并成每方块 1 次 `write(byte[],0,n)` 后调用数降一个数量级（≈88 万 / 710 万）。按每次 2~3 ns 粗估 ≈ 20~30 ms（96³）/ 170~250 ms（192³）——这是估算，需实测确认（标「待确认」的量化部分）。
  - 触发条件：HISTORY 开启（默认）+ 方块量。**默认配置下它在 `close()` 关键路径上**：`postProcess`→`addWriteTask`→`completeNow = Fawe.isMainThread()` 为真时**内联执行**（AbstractChangeSet.java:414-416、433-435），而主线程编辑时 chunk 也是内联 `chunk.call()`（SingleThreadQueueExtent.java:269-276）。只有异步/多线程编辑（`!isMainThread()`）才被丢到单一 drain worker 上。
  - 风险与理由：**低**。字节布局、mode 字节、读端 `getCompressedIS` 全部不变；只改"怎么写"。
  - 最小改法：在 `posDel.write` 里先算进一个 6 字节的局部 `byte[]`，`idDel.writeChange` 追加到同一数组尾部，最后 `out.write(buf, 0, len)` 一次；同时给 `FaweOutputStream` 补一个非同步的 `write(byte[],int,int)` 覆盖（`DataOutputStream` 的实现带 `synchronized`，:10-28 已经为 `write(int)` 做过同样的事）。

[B] worldedit-core/.../util/MainUtil.java:291-329 + Settings.java:539/545 默认 level=3 造成 3 层串联 LZ4 + 5×519 KB 缓冲
  - 机制：`HISTORY.COMPRESSION_LEVEL=3` → `fastAmount = 1 + ((3-1)%3) = 3`，套 **3 个** `LZ4BlockOutputStream`；前后各 1 个 `FastBufferedOutputStream(os, buffer)`（:293、:327），`HISTORY.BUFFER_SIZE=531441`（Settings.java:545）。每个流各自 `new byte[buffer]`，合计 5 × 519 KB ≈ **2.6 MB 常驻缓冲/变更集**。
  - 省什么：第 2、3 层压的是**已经被 LZ4 压过的数据**，压缩比几乎为 0，纯粹多走两遍 CPU；96³ 的 ~9 MB 明文 → 3 遍 ≈ 22 MB 压缩工作量（LZ4 fast ≈ 500 MB/s ⇒ ≈ 45 ms），level=1 约 15 ms。同时每并发编辑少 2.6 MB 缓冲（10 人同时编辑 ≈ 26 MB）。
  - 触发条件：任何写历史的编辑；并发编辑人数越多、单次编辑方块越多越明显。
  - 风险与理由：**低**。level 本身写在文件头（`os.write((byte) 10 + amount)`，:292；读端 `getCompressedIS` 按 mode 还原，:335-359），新旧历史都能读；代价是文件略大（需实测差距，第 2/3 层既然压不动，差距可能小于预期 —— 待实测）。
  - 最小改法：默认 `COMPRESSION_LEVEL` 3 → 1（或"1 + 仅 amount>6 时叠 ZSTD"），跑一次 96³/192³ 对比历史文件体积与 close 时间。

[B] worldedit-core/.../queue/implementation/chunk/ChunkHolder.java:1048-1059 每次提交都无条件 `createCopy()`
  - 机制：`call()` 一进来就 `IChunkSet copy = chunkSet.createCopy()`，再把 copy 交给 `call(extent, copy, …)`。`CharSetBlocks.createCopy()`（CharSetBlocks.java:358-399）逐 section `new char[4096]`+`arraycopy`，还 `new ThreadUnsafeCharBlocks(...)`（**非池化**，ThreadUnsafeCharBlocks.java:64-100）并复制 biomes/light/tiles/entities/heightMaps。
  - 省什么：96³ 36×6×8 KB ≈ 1.7 MB + 36 对象；192³ 144×12×8 KB ≈ **13.8 MB** + 144 对象 + 1,728 个 `char[4096]`（直接进老年代，等于放大 GC 压力）。
  - 触发条件：每个被提交的 chunk（即几乎所有写操作）；区块数越多越明显。
  - 风险与理由：**中**。copy 的目的是让 `get.call(...)` 返回后仍在被引用的 set（异步 finalizer → `postProcess(this, get.getCopy(copyKey), iChunkSet)`，ChunkHolder.java:1082-1089）不被池化回收。当 `getPostProcessor()` 是 `EmptyBatchProcessor`（没有历史/没有后处理）时，set 在 `get.call` 返回后不再被读，直传 `chunkSet` 是安全的；有后处理器时必须保留（或改成"提交后持有所有权、禁止 recycle"的显式转移）。
  - 最小改法：把 `call(owner,set,finalize)` 里已有的 `boolean postProcess = !(getExtent().getPostProcessor() instanceof EmptyBatchProcessor)`（:1077）提前到 `call()`，`postProcess == false` 时跳过 `createCopy()`，直接把 `chunkSet` 传下去。

[B] worldedit-bukkit/.../v26_2/PaperweightGetBlocks.java:454-456 与 :469-477 写入时再拷两份 section（合计 3 份）
  - 机制：`char[] tmp = set.load(layerNo); char[] setArr = new char[tmp.length]; System.arraycopy(tmp,0,setArr,0,len)` —— `setArr` 会被 `newChunkSection` 改写并成为 GET 缓存（:500/:567 → `updateGet` → `this.blocks[layer] = arr`，:795）。紧接着 `if (createCopy) { char[] tmpLoad = load(layerNo); char[] copyArr = new char[4096]; arraycopy(...); copy.storeSection(...) }` —— 又拷一份**旧数据**给历史/后处理。而 `createCopy` 就是上一条的 postProcess 标志（ChunkHolder.java:1078 → :213-220），默认配置下有历史 ⇒ 恒为 true。
  - 省什么：192³：`setArr` 13.8 MB + `copyArr` 13.8 MB = **27.6 MB memcpy + 3,456 个 char[]**；96³ 对应 3.4 MB。三份拷贝（set 副本 / setArr / copyArr）合起来 192³ ≈ 41.5 MB。
  - 触发条件：每次区块落地 + 有后处理器（历史默认开启）；大区域编辑 / 高度跨 section 多的编辑更明显。
  - 风险与理由：**中**。`setArr` 必须与池化的 set 数组解耦（set 会被 `CharSetBlocks.recycle()` 复用）；但既然第 4 条已经产生了独占的 `ThreadUnsafeCharBlocks copy`（该对象在本路径中从不 recycle，只被丢弃），可以让 `setArr` 直接**接管** copy 里的数组（`setArr = copy.load(layerNo)` 并把 copy 的该层置空），省掉第 2 份；`copyArr` 存的是旧数据，不能与 `setArr` 合并，只能靠第 4 条消除。
  - 最小改法：按上一条消除 `createCopy` 后，本处 `setArr` 直接取 `set.load(layerNo)` 且不再拷贝（所有权随 set 一起转移给 GET 缓存），把每 section 的拷贝从 3 份降到 2 份；`copyArr` 不能省（`load(layerNo)` 返回的是 GET 自有缓存数组，必须拷出来给后处理），但可以先确认「有后处理器时才拷」。两步分开提交，方便单独回滚。

[C] worldedit-core/.../history/changeset/AbstractChangeSet.java:126、:414-478 历史写入不可并行（单 worker + `synchronized`）
  - 机制：`processSet` 是 `final synchronized`（:126）；`postProcess` → `addWriteTask`（:230）→ `queue` + `triggerWorker()`（`Semaphore(1)`，:448-454）→ 一个 `drainQueue` 线程逐 chunk 跑 `processSet` 并逐方块 `add`。主线程编辑时更糟：`completeNow=true` 直接内联（:433-435），整段历史落在调用线程。
  - 省什么：96³ 88 万条、192³ 708 万条记录全部由 1 个核写；等于一次编辑的 CPU 下界被钉在单核，多核并行只加速了区块落地那一半。多人服上表现为"后处理追不上队列"，内存里持续堆着 `PaperweightGetBlocks_Copy` + set 副本（第 4/5 条的产物）。
  - 触发条件：大编辑（>1 万方块起可见）；同时 4 人以上编辑、或核数 ≥4 时最明显。
  - 风险与理由：**高**。LZ4 块流不是线程安全的，要并行就得每 chunk 独立 buffer 再按序拼接，属结构性改动，还会改变文件中的记录顺序（读端顺序无关，但 `RollbackOptimizedHistory` 的 min/max 统计与 `ChangeSetSummary` 要复核）。
  - 最小改法：分两步——先只把 `add()` 循环改成"写进一个每 chunk 复用的 `FastByteArrayOutputStream`（或复用 8 KB 数组）"，仍单线程 append 到 `osBD`（配合第 2 条）；验证稳定后再考虑按 chunk 并行。

[B] worldedit-core/.../queue/implementation/chunk/ChunkCache.java:28-40、:46-70 GET 缓存无上限，且 `trim(false)` 遇到第一个存活条目就 return
  - 机制：`get()` 是 `synchronized`，内部 `Long2ObjectLinkedOpenHashMap<Long, WeakReference<T>>`，**只在 trim 时**删条目（:52-68）。`trim(false)`（非激进）在遇到第一个非 null 引用时 `return false`（:62-64），它之后的所有已死弱引用条目永久留存；`getCache` 随"服务器启动以来被编辑过的不同 chunk 数"单调增长。
  - 省什么：每条约 40~56 B（entry + WeakReference）→ 10 万区块 ≈ 4~6 MB 常驻，外加每次 `getCachedGet` 都在**全局锁**下做一次 long hash 查找（虽然单次只有几十 ns）。
  - 触发条件：长时间运行 + 大量不同区块被编辑（大范围 `//set`、`//regen`、批量粘贴）；新开服看不出。
  - 风险与理由：**低**。删死条目不改语义（`WeakReference.get()==null` 本来就要重建）；要给上限则需挑一个值（会改变内存/命中率权衡）。
  - 最小改法：`trim(false)` 里把 `return false` 换成 `continue`（仍然扫完整个 map 清死引用，只把"是否全清空"作为返回值），或给 `getCache` 设容量上限（如 8192）并在 `put` 后 `removeFirst`。trim 的调度点本次未在 `worldedit-core/bukkit` 找到（`Trimable`/`QueueHandler.trim` 只有定义，未见 repeat 注册）——**待确认**。

[B] worldedit-core/.../queue/implementation/blocks/CharSetBlocks.java:29-53、:66-69 + ChunkHolder.java:933-955 `QUEUE.POOL=true` 对 SET 侧实际不生效：每个 chunk 都新建一个 `CharSetBlocks`
  - 机制：`CharSetBlocks` 唯一的入池动作是 `recycle()` → `POOL.offer(this)`（:72-75），而 `recycle()` 全仓库只被 `ChunkHolder.trim()` 调用（ChunkHolder.java:941）；`ChunkHolder.trim` 又只被自己的 `trim(aggressive, layer)`（:960）和 `WrapperChunk.trim`（WrapperChunk.java:587）调用，**这两条路径在队列里都没有调用者**（`SingleThreadQueueExtent.trim` 只 trim `cacheGet/cacheSet`，:290-308；`IQueueChunk.trim`/`recycle` 全局无调用点，已 grep）。所以池永远是空的：`POOL.poll()` 恒走 `new CharSetBlocks()`（`CharBlocks` 构造器 :55-64 再 `new char[16][]` + `new Object[16]` + 16 个 `new Object()` 锁）。
  - 省什么：每 chunk ≈ 19 个对象 + 2 个小数组（96³ 36 个 chunk ≈ 680 个；192³ 144 个 ≈ 2,700 个）。绝对量不大，但它意味着 `QUEUE.POOL` 这个默认开启的配置在 SET 侧**零收益**，而且 `ChunkHolder`/`PaperweightGetBlocks` 本身也从没池化（`create()` 每次 `new`，:409-411，`recycle()` 只 warn）。
  - 触发条件：每个被编辑到的 chunk；插件大量小编辑（每次 `EditSession` 每 chunk 一份）时按编辑次数线性放大。
  - 风险与理由：**中**。要真正回收必须在 `ChunkHolder.call()` 取完 `createCopy()` 之后 `chunkSet.recycle()`,并保证之后没人再读原 set（`isEmpty()` 在 `submitUnchecked` 里已先于 `call()` 执行过，:240/:251）。若判断生命周期不安全，退而求其次把默认 `POOL` 设为 false（少一次 CLQ 操作、代码与配置一致）。
  - 最小改法：在 `ChunkHolder.call()` 里 `createCopy()` 成功后立刻 `chunkSet.recycle(); chunkSet = null;`（同时把 `getChunkSet()` 的调用方改成接受 null），或先只修「起步范围」这条低风险项：`CharSetBlocks` 固定 `super(0,15)`，1.18+ 世界 -64..319 会在首个 y<0 / y>255 写入时各触发一次 `resizeSectionsArrays`（新 `char[][]`+`Object[]`+每 section 一个锁，:438-483），把 `SingleThreadQueueExtent.init` 的 `cacheSet` 默认值改成 `(x,z) -> CharSetBlocks.newInstance(x, z, minY>>4, maxY>>4)`（SingleThreadQueueExtent.java:197，`minY/maxY` 已在 :188-189 就绪）即可省掉每 chunk 1~2 次数组重建。

[C] worldedit-core/.../util/MemUtil.java:25-33、:85-92 内存吃紧时连调两次 `System.gc()`
  - 机制：`isMemoryLimitedSlow()` 在 `memory==true` 时 `System.gc(); System.gc(); calculateMemory();`；`memoryLimitedTask()` 同样两次 `System.gc()`，且它由 GC 阈值通知回调触发（Fawe.java:419-431）。`EditSessionBuilder.java:482` 每次 `build()` 都会调 `isMemoryLimitedSlow()`。
  - 省什么：堆越大越贵——8 GB 堆一次 full GC 约 100~500 ms；内存紧张期间"每个 EditSession 建一次 = 2 次 full GC"，等于对 10~50 人服持续制造秒级 STW；通知回调里再触发 GC 还有抖动风险。
  - 触发条件：堆使用率贴到 `max-memory-percent`（默认 95%），且玩家/插件持续创建 EditSession；平时完全不走。
  - 风险与理由：**中**。`System.gc()` 是"清完再判断是否真的紧张"的手段，删掉后 `memory` 标志只能靠 `calculateMemory()` 的`totalMemory()==maxMemory()` 分支复位，可能在紧张态停留更久（= 更慢但更安全）。
  - 最小改法：`isMemoryLimitedSlow()` 去掉两次 `System.gc()`，只返回 `memory.get()`（标志已由 GC 通知维护）；`memoryLimitedTask()` 里保留至多一次。

[D] worldedit-core/.../queue/implementation/QueueHandler.java:141-143 `isUnderutilized()` 用 `getActiveCount()`
  - 机制：`getActiveCount()` 会拿 `ThreadPoolExecutor.mainLock` 并遍历 worker 集合；调用点在 `SingleThreadQueueExtent.getOrCreateChunk` 的溢出分支（:356），条件是 `size > targetSize`（默认 8×核数，例如 64）。另外 `newBlockingExecutor` 用的是**无界** `LinkedBlockingQueue` + `CallerRunsPolicy`（FaweCache.java:646-657），所以"blocking"从不真正阻塞提交者，`CallerRunsPolicy` 也永不触发。
  - 省什么：192³ = 144 chunk，越过 target 之后约 80 次调用 × (加锁 + 扫描 ~32 worker) ≈ 数十微秒。**量级可忽略**。
  - 触发条件：一次编辑涉及 chunk 数 > target-size。
  - 风险与理由：**不建议动**。收益在微秒级；而无界队列 + CallerRunsPolicy 虽然是命名误导，但改成有界队列会改变过载时的行为（抛 RejectedExecution 或退回调用线程执行），在没有压力测试前属于纯风险。
  - 最小改法：不动（若要动，只改注释/命名，别动队列实现）。

## 顺手的正确性风险（一句话，不展开）

- `CharFilterBlock.java:363-365`：`getBlockAbove()` 的守卫写成 `if (y < 16)`，而 `y` 只取 0..15 ⇒ 恒真，`layer < maxLayer` 的跨 section 分支是死代码；y=15 时读 `getArr[index + 256]`（index 最大 4095，数组长 4096）必然越界 ⇒ 任何在 section 顶层走 `getStateAbove` 的 mask 会 `ArrayIndexOutOfBoundsException`（调用方是否规避，待确认；应为 `y < 15`）。同类：`:380` `getBlockRelativeY` 的 `layerAdd < 0 && layerAdd < (minLayer - layer)` 方向可疑（应为 `layer > minLayer`），向下跨 section 会返回 `__RESERVED__`。

## 本域结论

队列/刷盘这一层的"结构"问题已经清完，剩下的是**逐方块写历史**和**每 section 三次整段拷贝**两块实打实的常数开销：优先做第 1 条（空 chunk 4~5 ms 睡眠，只读操作上是纯白等，改动 2 行）和第 2 条（历史逐字节写合并成 1 次批量写，字节格式不变，主线程编辑时直接进 close 关键路径），两者都是低风险、可当场量化；第 3 条（默认 3 层 LZ4）是改默认配置就能拿到 2/3 的压缩 CPU，但要先实测文件体积。第 4/5 条（把 3 次 section 拷贝压到 2 次甚至 1 次）收益同样明确但涉及 set 的所有权/生命周期，必须在改完后用 192³ + 历史 + 后处理（光照）组合回归，属中风险。第 6 条（历史并行化）和 7 条（GET 缓存无界）建议排到下一轮，前者是结构性改动，后者只在长开服上体现。第 8 条顺带确认了一个"配置骗人"的点：`QUEUE.POOL` 默认开着，但 SET 侧唯一的回收入口 `ChunkHolder.trim()` 在队列里没有调用者，所以 `CharSetBlocks` 池永远为空、每 chunk 都新建（还带着 16 个 section 锁对象）——要么补上 `call()` 之后的 `recycle()`，要么把默认值改成 false 让配置与实现一致。第 9 条（`System.gc()`×2）只在内存逼近上限时才咬人，可以并到内存专项一起看。
