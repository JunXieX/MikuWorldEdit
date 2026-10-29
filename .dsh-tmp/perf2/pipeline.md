# FAWE 编辑管线固定开销与全局热点（只读性能审查）

> 域：编辑管线固定开销（写完方块之前的一切）与全局热点。区块写入内部（section/palette/sendChunk）不在本文范围。
> 已完成且不重复：sendChunk 提前返回、归属判定去重、调色板收尾优化、section 替换前不再解包。
> 路径约定：相对 `fawe/`；每个文件首次给完整路径，其后用 `文件名:行`。
> 分级：A=收益可测且改动小，建议做；B=有收益但条件性；C=记录/顺手改；D=不改（含理由，防后续误报）。

---

## 0. 结论摘要（先看这段）

1. 每个方块的固定开销已经很小：默认路径（fastMode + combineStages）只有 2 层 extent 转发（EditSession → LimitExtent → queue），每方块 0 次 Settings 查表、0 次国际化、0 次事件总线、1 次 ThreadLocal、1 次 AtomicLong RMW、0 次 BlockState/BaseBlock 分配。这与「FAWE 慢在 extent 链太长」的直觉相反，先钉死，避免后续优化打空。
2. 真正可动的三处固定开销：LimitExtent 的 THROW_MAX_CHANGES() 无条件原子自减（每方块 1 次 lock 前缀指令，A-）、block.getNbtData() 与 getMaterial().isTile() 的重复虚调用（每方块 2-3 次，B）、MultiRegionExtent.contains 的逐方块 O(区域数) 扫描（B）。
3. 全局定时器类（FaweTimer / MemUtil / InteractionDebouncer）在 10/50 人时的每秒开销可忽略（<0.1% 单核），不值得为性能动；InteractionDebouncer 的非线程安全是正确性问题，不是性能问题。
4. 唯一像 O(n²) 的地方是 MultiRegionExtent 的区域线性扫描（n=方块数，系数=区域数）与 DisallowedBlocksExtent.processSet 的逐 section 全表遍历（系数=blocked 条目数）；两者都只在特定配置下进入。

---

## 1. 每方块固定成本：调用链与计数

### 1.1 默认路径到底是什么（先定住这条链）

//set 一类命令走 EditSession.setBlocks(Region, Pattern) → PassthroughExtent → Extent 默认循环（Extent.java:1028-1039）：

```
for (BlockVector3 pos : region)  setBlock(pos, block)     // Extent.java:1033-1037
  └─ EditSession.setBlock(BlockVector3,B)                  // EditSession.java:1063-1076   y 界检查 + changes++
       └─ getExtent().setBlock(BlockVector3, B)            // 字段读，无遍历（AbstractDelegateExtent.java:89-91）
            └─ LimitExtent.setBlock(BlockVector3,T)        // LimitExtent.java:445-456
                 ├─ limit.THROW_MAX_CHANGES()              // FaweLimit.java:168-172 → AtomicLong.decrementAndGet
                 ├─ block.getNbtData() != null             // 虚调用 + 材质查
                 └─ block.getBlockType().getMaterial().isTile()
            └─ IChunkExtent.setBlock(int,int,int,B)        // IChunkExtent.java:38-41
                 ├─ getOrCreateChunk(x>>4, z>>4)           // SingleThreadQueueExtent.java:332-336 → lastChunk.get() ThreadLocal
                 └─ chunk.setBlock(x&15, y, z&15, state)   // 进入区块写入域（本文不展开）
```

链是怎么被削到 2 层的，全在 EditSessionBuilder.compile()（EditSessionBuilder.java:442-703）：

- :533/:534/:700 的三次 wrapExtent 只有在有插件监听 EditSessionEvent 并替换 extent 时才多一层；无监听时 toReturn == extent，直接返回原 extent（:804/:835）。所以「extent 层数」不能按代码里 new 的个数算，要按这两次事件是否有订阅者算。
- :535 的整块（SlowExtent / HistoryExtent）在 fastMode 且未开 HISTORY 侧效时整段跳过。
- :589-685 的 region/disallowed/stripNBT 层：combineStages=true 时只 queue.addProcessor(...)，不进每方块链。
- :693-699 的 LimitExtent 唯一无条件进链（只要 !limit.isUnlimited()，玩家一定满足）。

combineStages 的默认值在 :524-532：HISTORY.COMBINE_STAGES && limit.FAST_PLACEMENT && blockBag == null。这决定了非 fastMode 玩家是否也要在每方块上多一次 getFullBlock（见 2.2）。

### 1.2 逐项计数（默认路径，每方块）

| 项目 | 次数 | 依据 |
| --- | --- | --- |
| extent 转发层 | 2（EditSession → LimitExtent → queue）；插件订阅 EditSessionEvent 时最多 +3 | EditSessionBuilder.java:533/534/700, 804/835 |
| Settings.settings() | 0 | 全部查表都在 compile() 期完成；settings() 本身也只是静态字段读（Settings.java:103-105） |
| 国际化 Caption.of | 0（仅异常路径） | LimitExtent 的 handleException 才建 Caption（:453/:467） |
| 事件总线 post | 0（仅 EditSession 创建/关闭各 1 次） | EditSessionBuilder.java:800 |
| ThreadLocal get | 1（queue 的 lastChunk）+ 至多 2（BlockVector3 重载互转时 MutableBlockVector3.get） | SingleThreadQueueExtent.java:333；OutputExtent.java:68-70 |
| AtomicLong RMW（lock 前缀） | 1（MAX_CHANGES） | FaweLimit.java:169 |
| BlockState/BaseBlock 分配 | 0 | BlockState.toBaseBlock 返回缓存字段（BlockState.java:423-425），applyBlock 同样（:281-284） |
| 虚调用（材质判定） | 2-3 | LimitExtent.java:461 |
| 边界/字段读写 | ~5 | EditSession.java:1081-1085 |

先记一个反直觉的结论：Pattern.apply(extent, get, set) 默认实现（Pattern.java:54-56）会 applyBlock(get) 拿一个 BaseBlock 再 setFullBlock 退回 setBlock，看着像每方块一次分配；但 BlockState.toBaseBlock() 直接返回 this.emptyBaseBlock（BlockState.java:423-425），BlockPattern.applyBlock 也返回同一实例（BlockPattern.java:66-69）。这条路径不分配。只有 state.toBaseBlock(nbt)（带 NBT 时 BlockState.java:442-447）才 new。

### 1.3 省什么、什么时候看得出来

- 默认路径每方块约 30-60 ns 的固定开销（2 次虚转发 + 1 次原子 + 1 次 ThreadLocal + chunk 缓存查找），区块写入本身通常是这个数的 3-10 倍。固定开销占比低，优化上限就在那里：把 LimitExtent 的原子自减去掉，理论上 1-3% 的区块写入吞吐；10 人以下小服肉眼不可见。
- 真正放大差异的是非默认路径：combineStages=false（或缺 FAST_PLACEMENT、或用 blockBag）会让每方块多一次 getFullBlock（等于每方块一次 chunk/section 定位 + BaseBlock 构造），这是 2 倍级差距，不是 5% 级。优化应优先保「不要把玩家挤进非默认路径」，而不是抠默认路径的原子操作。

---
## 2. 逐层审查（进链的每一层都要交代）

### 2.1 LimitExtent —— 每方块 1 次原子自减（分级 A-）

文件：`worldedit-core/src/main/java/com/fastasyncworldedit/core/extent/LimitExtent.java`

```java
// LimitExtent.java:459-470
public <T extends BlockStateHolder<T>> boolean setBlock(int x, int y, int z, T block) throws WorldEditException {
    limit.THROW_MAX_CHANGES();                                                       // :460
    if (block.getNbtData() != null || block.getBlockType().getMaterial().isTile()) {  // :461
        limit.THROW_MAX_BLOCKSTATES();                                               // :462
    }
    try { return extent.setBlock(x, y, z, block); }
    catch (FaweException e) { handleException(e); return false; }                    // :466-468
}
```

依据：`FaweLimit.MAX_CHANGES` 是 `AtomicLong`（`FaweLimit.java:14`），`THROW_MAX_CHANGES()` 首次调用即 `decrementAndGet()`（`FaweLimit.java:168-172`）。

- 省什么：每方块 1 次 `lock cmpxchg`（x86 上约 15-20 cycle，且是完整内存屏障，阻止前后指令重排）。单线程编辑时这个原子操作纯属浪费——limit 是 per-EditSession 的（`EditSessionBuilder.java:493` 的 `limit.copy()`），同一时刻只有一个线程写它。
- 什么条件下看得出来：大区域 `//set`（≥10 万方块）的吞吐、批量粘贴。10 人小服单次编辑绝对差异 <1%；50 人同时刷时才有可观测总量。
- 改动风险：低。
- 最小改法：把计数与判定拆开，热路径用普通 long，落盘时再同步到 AtomicLong：

```java
// FaweLimit.java 新增
private long localChanges;                  // 非原子，仅本线程编辑使用
public void countChange() { localChanges++; }
public void flushChanges() { if (localChanges != 0) THROW_MAX_CHANGES((int) localChanges); localChanges = 0; }

// LimitExtent.java:460 改为
limit.countChange();
```
再在 `LimitExtent.processSet` 入口（或 chunk 落盘处理器）调一次 `flushChanges()`。注意：这把「超限中断粒度」从每方块变成每区块，语义变化要写进文档。

### 2.1b LimitExtent 的 getNbtData()：每 tile 方块一次对象分配（分级 B）

`LimitExtent.java:461` 的 `block.getNbtData() != null`，对 **tile 类方块**（箱子/告示牌/熔炉/漏斗/刷怪笼）会走完整条默认素材链：

```
BlockState.getNbtData()            // BlockState.java:436-438
  └─ getBlockType().getMaterial().getDefaultTile()
       └─ BlockMaterial.getDefaultTile()      // BlockMaterial.java:189-195
            ├─ defaultTile()                  // 取 FaweCompoundTag
            └─ new CompoundTag(faweCompoundTag.linTag())   // ← 每次 new 一个包装对象
```

- 依据：`BlockMaterial.java:189-195` 每次调用都 `new CompoundTag(...)`，没有缓存；`BlockState.java:437` 对 tile 材质每次都转发过去。
- 省什么：每 tile 方块 1 次 `CompoundTag`（外加 `linTag()` 可能的包装）分配，约 24-40 B + 一次年轻代分配。`//set chest 100000` 就是 10 万个立即被丢弃的包装对象——纯 GC 压力，判定结果只用来判空。
- 什么条件下看得出来：编辑内容含 tile 方块时（建筑/内饰搬运最常见）。10 人小服不可见；50 人同时大范围粘贴时年轻代回收次数上升，表现为周期性卡顿而不是单次变慢。
- 改动风险：低（只调整 OR 的操作数顺序 + 缓存材质引用，真值表不变——`isTile()` 为真时原先的 `getNbtData()!=null` 结果不影响最终条件）。
- 最小改法：

```java
// LimitExtent.java:461
final com.sk89q.worldedit.world.registry.BlockMaterial material = block.getBlockType().getMaterial();
if (material.isTile() || block.getNbtData() != null) {   // 短路：tile 时不再调 getNbtData
    limit.THROW_MAX_BLOCKSTATES();
}
```

### 2.2 HistoryExtent —— 只有 combineStages=false 才每方块多一次读（分级 B）

文件：`worldedit-core/src/main/java/com/fastasyncworldedit/core/extent/HistoryExtent.java`

```java
// HistoryExtent.java:56-65
public <B extends BlockStateHolder<B>> boolean setBlock(int x, int y, int z, B block) throws WorldEditException {
    BaseBlock previous = getFullBlock(x, y, z);                        // :57  每方块一次读
    if (previous.getInternalId() == block.getInternalId()) {
        if (!previous.hasNbtData() && block instanceof BaseBlock && !block.hasNbtData()) return false;
    }
    this.changeSet.add(x, y, z, previous, block.toBaseBlock());         // :63
    return getExtent().setBlock(x, y, z, block);
}
```

- 依据：`getFullBlock(x,y,z)`（:57）经 `IChunkExtent.getFullBlock`（`IChunkExtent.java:68-71`）再走一次 `getOrCreateChunk` + `chunk.getFullBlock`。这个形态下每写一个方块要额外做一次完整区块定位与读取。
- 省什么：一半左右的每方块访问次数（读+写 变 只写）。
- 什么条件下看得出来：仅在 `combineStages=false`。默认 `combineStages=true` 时（`EditSessionBuilder.java:524-532`）history 被注册成批处理器（`Extent.enableHistory`，`Extent.java:1190-1196` → `queue.addProcessor(changeSet)`），flush 时才按区块记录，此时 get 已加载，无需额外定位。所以这条只在组合失效时出现：`HISTORY.COMBINE_STAGES=false`、`limit.FAST_PLACEMENT=false`、或 `blockBag != null`（`EditSessionBuilder.java:531`）。
- 改动风险：中（改历史语义容易踩 undo 一致性）。
- 最小改法：不动 HistoryExtent，改为在 compile() 里收紧并显式提示：`:524-532` 之后若 `combineStages == false && changeSet != null`，向 actor 打一条 debug 级一次性提示，让运维知道当前配置会让每方块多一次读。收益是「避免误配置导致 2 倍慢」，不是代码提速。

### 2.3 FaweRegionExtent / SingleRegionExtent —— 每方块一次 contains（分级 C）

文件：`worldedit-core/src/main/java/com/fastasyncworldedit/core/extent/FaweRegionExtent.java`、同目录 `SingleRegionExtent.java`

```java
// FaweRegionExtent.java:78-87
public <B extends BlockStateHolder<B>> boolean setBlock(int x, int y, int z, B block) throws WorldEditException {
    if (!contains(x, y, z)) {                                              // :80
        if (hasLimit && !limit.MAX_FAILS()) WEManager.weManager().cancelEditSafe(this, FaweCache.OUTSIDE_REGION);
        return false;
    }
    return super.setBlock(x, y, z, block);
}
```

- 依据：`SingleRegionExtent.contains` 直接委托 `region.contains(x,y,z)`（`SingleRegionExtent.java:29-31`），CuboidRegion 是 6 次比较，很便宜。而且这一层默认不在每方块链上（combineStages 时它是 processor，`EditSessionBuilder.java:678-685`），只在 combineStages=false 时生效。
- 分级 C：不必为它单独优化，知道它存在即可。

### 2.4 MultiRegionExtent —— 真正的逐方块线性扫描（分级 B）

文件：`worldedit-core/src/main/java/com/fastasyncworldedit/core/extent/MultiRegionExtent.java`

```java
// MultiRegionExtent.java:63-97（allowed 未命中时的兜底分支）
public boolean contains(int x, int y, int z) {
    if (region != null && region.contains(x, y, z)) {                                    // :64 最近命中缓存
        if (disallowed != null) for (Region d : disallowed) if (d.contains(x,y,z)) return false;  // :66-70
        return true;
    }
    boolean result = allowed == null;
    if (!result) {
        for (int i = 0; i < allowed.length; i++) {                                        // :76 逐个区域试
            if (i != index) { Region cur = allowed[i]; if (cur.contains(x,y,z)) { region = cur; index = i; result = true; break; } }
        }
    }
    if (!result || disallowed == null) return result;
    for (Region d : disallowed) if (d.contains(x,y,z)) return false;                      // :91-95
    return true;
}
```

- 依据：allowed 有 N 个区域时，落在「当前 region 之外」的每个方块要跑最多 N-1 次 `contains` + 全部 disallowed 判定。系数是区域数，所以是 O(方块数 × 区域数)。持有大量 WG 区域/地皮权限的玩家（`getAllowedRegions()` 返回数组）最容易命中。
- 省什么：把区域数这个乘数消掉。方块按 chunk 聚集，同一 chunk 内区域归属几乎不变，但 :64 的缓存只能记住一个 region；一旦编辑区域跨多个 region（玩家在相邻地皮交界处 `//set`），缓存来回抖动，退化成每方块 N 次。
- 什么条件下看得出来：allowed 区域数 ≥ 8 且编辑区域跨区域边界时明显；单区域（`SingleRegionExtent`）完全不受影响。
- 改动风险：低-中（纯查询缓存，无副作用）。
- 最小改法：加一层按 chunk 的区域索引缓存：

```java
// MultiRegionExtent.java 新增字段
private long lastChunk = Long.MIN_VALUE;
private int lastChunkRegion = -1;      // -1 = 该 chunk 无 allowed 区域

// contains(int x,int y,int z) 开头
final long chunk = ((long) (x >> 4) << 32) | ((z >> 4) & 0xffffffffL);
if (chunk != lastChunk) {
    lastChunk = chunk;
    lastChunkRegion = -1;
    if (allowed != null) {
        for (int i = 0; i < allowed.length; i++) {
            if (allowed[i].containsChunk(x >> 4, z >> 4)) { lastChunkRegion = i; break; }
        }
    }
}
if (lastChunkRegion >= 0 && allowed[lastChunkRegion].contains(x, y, z)) { /* 先试缓存，再查 disallowed */ }
```
注意 `containsChunk` 与 `contains(x,y,z)` 的边界语义（区域可能只覆盖 chunk 的一部分），所以缓存值只能当「优先尝试」，失败必须回落到原循环——正确性不变，热路径少 N 次判断。
### 2.5 SlowExtent —— 每方块一次 long 累加，阈值时才 sleep（分级 D）

文件：`worldedit-core/src/main/java/com/fastasyncworldedit/core/extent/SlowExtent.java`

- 依据：`:21-24` 每方块调 `delay()`；`:27` 只做 `increment += nanos`（纯 long 加法）。只有跨过 50ms 阈值才 `Thread.sleep`（`:28-38`）。
- 默认不启用：`Settings.java:428` 的 `SPEED_REDUCTION = 0`，`EditSessionBuilder.java:553` 的 `> 0` 判定为假（注意 `FaweLimit.java:29` 的字段默认是 `Integer.MAX_VALUE`，但 `Settings.getLimit` 会与配置取 min，`Settings.java:186`）。
- 分级 D：不要动。它本身就是故意放慢的节流阀，每方块一次加法可忽略；要注意的是它在**调用线程**上 sleep（`:32`），调用方若是 tick 线程会卡 tick——这属线程问题，已在交互审查覆盖。

### 2.6 DisallowedBlocksExtent —— 每方块一次 String 哈希（C）+ processSet 全 section 遍历（B）

文件：`worldedit-core/src/main/java/com/fastasyncworldedit/core/extent/DisallowedBlocksExtent.java`

```java
// DisallowedBlocksExtent.java:102-123
private <B extends BlockStateHolder<B>> B checkBlock(B block) {
    if (blockedBlocks != null) {
        if (blockedBlocks.contains(block.getBlockType().id())) return (B) RESERVED;   // :104  String hashCode（有缓存）
    }
    if (blockedStates == null) return block;
    for (FuzzyBlockState state : blockedStates) if (state.equalsFuzzy(block)) return (B) RESERVED;  // :111-115 逐条比
    for (PropertyRemap<?> remap : remaps) block = remap.apply(block);                // :119-121
    return block;
}
```

- 每方块成本：`blockedBlocks.contains(...)` 是 `HashSet<String>` 查（`BlockType.id()` 返回字段、无分配；String.hashCode 有缓存），约 5-10 ns。`blockedStates` 非空时退化为逐条 `equalsFuzzy`。
- 进链条件：`EditSessionBuilder.java:645-663`，仅当 `limitBlocks` 或 `remaps` 非空才创建。默认 `disallowed-blocks` 为空 → 不进链。
- 真正值得看的是 `processSet`（:126-168）：对每个**存在**的 section 遍历 4096 个 char（:136），每块查一次 `blockedBlocks`（:143）+ 可能遍历 `blockedStates`（:151）+ 遍历 `remaps`（:161）。这是 O(16³ × 条目数) 全表遍历，在 flush 期执行。
- 最小改法（B，可选）：把 `blockedBlocks` 预展开成按 `BlockType` 索引的位图/布尔数组，内层把 String 查换成数组索引；`blockedStates` 非空时保持原逻辑。收益只在「配了 disallowed-blocks 且大区域」时可见。
- 改动风险：低。

### 2.7 OncePerChunkExtent —— 每方块一次 synchronized（B，仅 //copy 带实体）

文件：`worldedit-core/src/main/java/com/fastasyncworldedit/core/extent/OncePerChunkExtent.java`

```java
// OncePerChunkExtent.java:54-69
private boolean shouldRun(int chunkX, int chunkZ) {
    synchronized (set) {                              // :55  每方块一次 monitor enter
        final long pair = (long) chunkX << 32 | chunkZ & 0xffffffffL;
        if (pair == lastPair) return false;           // :57-59  快路径
        lastPair = pair;
        return set.add(chunkX, chunkZ);               // :61
    }
}
// 由 getBlock/getFullBlock/getBiomeType/setBiome/setBlockLight... 每个都调 checkAndRun（:113-188）
```

- 依据：`lastPair` 已经是 `volatile long`（`:34`），快路径本可以先读它再决定是否进锁；现在无条件先进 `synchronized`。
- 进链范围：只有 `ForwardExtentCopy`（`worldedit-core/src/main/java/com/sk89q/worldedit/function/operation/ForwardExtentCopy.java:526-534`）在按区块收集实体时创建，即 `//copy` / `//cut` 带实体；`//set` 不受影响。
- 省什么：每方块一次无竞争 monitor enter/exit（约 10-20 cycle）+ 一次 volatile 写。对 100 万方块的 `//copy` 是 10-20 ms 量级，在总耗时里很低。
- 最小改法：双检——先读 volatile `lastPair`，命中直接返回；未命中再进锁复查：

```java
private boolean shouldRun(int chunkX, int chunkZ) {
    final long pair = ((long) chunkX << 32) | (chunkZ & 0xffffffffL);
    if (pair == lastPair) return false;               // 无锁快路径
    synchronized (set) {
        if (pair == lastPair) return false;
        lastPair = pair;
        return set.add(chunkX, chunkZ);
    }
}
```
- 改动风险：低。分级 B。

### 2.8 不进每方块链、但每编辑一次的东西（C）

- `EditSessionBuilder.compile()`（`:442-703`）每次 EditSession 一次：`MemUtil.isMemoryLimitedSlow()`（`:482`，内部可能 `System.gc()` × 2，见 3.2）、多次 `Settings.settings()`、`WorldEdit.getInstance().getPlatformManager().queryCapability(...)`（`:591-596` / `:606-610` / `:617-621`，每次线性扫平台列表）。绝对次数少（每命令一次），但 `System.gc()` 是毫秒级。
- `EditSession` 构造器（`EditSession.java:267`）`super(builder.compile().getExtent())`；构造后若调 `setMask`/`setSourceMask`/`addTransform` 等，每次都 `new ExtentTraverser<>(getExtent()).find(...)` 走一遍链（`:530` / `:551` / `:568` / `:589` / `:632` / `:635` / `:661` / `:795`）。链长 ≤5，成本可忽略，但这些是每命令调用而非每方块。
- `Settings.getLimit(actor)`（`Settings.java:112` 起）：每次新建 `FaweLimit` 并逐 section `hasPermission("fawe.limit." + key)`（`:118-131`）。brush 每次点击都会走 `Actor.getLimit()`（`Actor.java:228-230`）→ 每次交互一次对象分配 + 若干字符串拼接 + 权限判定。50 人高频刷时是可见的 GC 源，C 级（测法见第 7 节第 6 条）。

---

## 3. 全局热点：FaweTimer / MemUtil / InteractionDebouncer 量化

### 3.1 FaweTimer —— 20 次/秒，可忽略（分级 D）

文件：`worldedit-core/src/main/java/com/fastasyncworldedit/core/util/FaweTimer.java`（注册于 `Fawe.java:141` 的 `repeat(timer, 1)`）

- 依据：`run()`（`:16-35`）每 tick 一次：`tickStart = currentTimeMillis()`、`tick++`、`++tickMod` 比较后直接 return（`:19-23`）；只有每 5 tick（`tickInterval = 5`，`:11`）才做一次 TPS 计算：2 次 `currentTimeMillis()` + `Arrays.stream(history).sum()`（`:44`，20 个 double）。
- 每秒量：20 次调用 + 4 次求和（每次分配一个 DoubleStream 管道对象，约 4-6 个小对象）= **约 20-30 个小对象/秒**。`getTPS()` 自带 5 tick 缓存（`:40-48`），不会每次重算。
- 结论：**50 人时的开销 < 0.001% 单核**，不值得动。唯一值得记的是 `getTouch` 语义：`getTick()` 是全局区域/tick 计数，Folia 上不能代表某个区域的 tick（见交互审查 P3），那是正确性问题不是性能问题。
- 分级：**D**（不改）。

### 3.2 MemUtil —— 后台轮询可忽略，但 `System.gc()×2` 是真实停顿点（分级 A）

文件：`worldedit-core/src/main/java/com/fastasyncworldedit/core/util/MemUtil.java`

**后台轮询部分（D，可忽略）：**
- `checkAndSetApproachingLimit()`（`:62-68`）由 `repeatAsync(..., 1)` 驱动（`Fawe.java:139`）=> 20 次/秒。每次 2 个 `Runtime` 调用 + 1 次整除 + 1 次 `AtomicBoolean.set`。合计 **约 1-3 µs/秒**，可忽略。
- `isMemoryFree()`/`isMemoryLimited()`（`:17-23`）是 `AtomicBoolean.get()` 的 volatile 读；在 `SingleThreadQueueExtent.getOrCreateChunk`（`:351`）里每「新建 chunk 条目」读一次，不在每方块路径上（有 lastChunk 快路径）。可忽略。

**真正的停顿点（A，建议改）：**

```java
// MemUtil.java:25-33
public static boolean isMemoryLimitedSlow() {
    if (memory.get()) {
        System.gc();          // :27
        System.gc();          // :28   ← 再来一次
        calculateMemory();    // :30
        return memory.get();
    }
    return false;
}

// MemUtil.java:85-92
public static void memoryLimitedTask() {
    System.gc();              // :86
    System.gc();              // :87
    for (Runnable task : memoryLimitedTasks) task.run();
    memory.set(true);
}
```

- 依据：`isMemoryLimitedSlow()` 被 `EditSessionBuilder.java:482` 调用，触发条件是 `checkMemory = actor != null && !fastMode`（`EditSessionBuilder.java:478-480`）——**玩家关掉 fastmode（即开启 history）时，每次建 EditSession 都会走这条**。`memory` 由 `Fawe.java:430` 的通知监听器置 true（`setupMemoryListener`，`:410-431`，监听 JVM 内存池 collection-usage 阈值）。
- 更麻烦的是 `Fawe.java:426-430`：日志被 30 秒节流（`lastWarn`），但 **`MemUtil.memoryLimitedTask()` 没有节流**——每次越过阈值都执行两次强制 full GC。强制 GC 本身会产生一次 collection，从而可能再次触发 collection-usage 阈值 => 「GC 触发通知、通知再触发 GC」的反馈回路，内存越紧越频繁。
- 省什么：每次 1-2 次 stop-the-world（G1 下数百 ms 级；只有加了 `-XX:+ExplicitGCInvokesConcurrent` 才降为并发周期）。这是**卡顿/看门狗风险**，不是吞吐问题。
- 什么条件下看得出来：内存接近 `max-memory-percent`（默认 95）且玩家大量非 fastmode 编辑时；表现为整服周期性卡顿、`Can't keep up` 刷屏。50 人比 10 人更早触发（因为更早逼近阈值），但根因不是人数而是内存水位。
- 改动风险：低-中（要保住「低内存时拒绝编辑」的语义）。
- 最小改法：
  1. `Fawe.java:426-430` 把 GC 也节流：只有 `time > lastWarn + 30s` 时才调 `memoryLimitedTask()`（把 `lastWarn.set` 与 GC 放到同一个 if 里），避免 GC 反馈回路。
  2. `MemUtil.isMemoryLimitedSlow()` 去掉两次 `System.gc()`，改为「依赖 `memory` 标志 + 一次轻量 `calculateMemory()`」；若确实需要强制回收，最多留一次，且只在 `!memory` → `memory` 翻转时做。
  3. 长期：`checkMemory` 默认值（`EditSessionBuilder.java:478-480`）在 Folia 上应改为 false——因为这条 GC 发生在命令线程，而 Folia 的 AsyncScheduler 池很小，一次 200ms 的 full GC 会拖住全服异步任务。
- 分级：**A**。

### 3.3 InteractionDebouncer —— 每次点击 2 次哈希，可忽略（分级 D）

文件：`worldedit-core/src/main/java/com/sk89q/worldedit/internal/event/InteractionDebouncer.java`

- 依据：每次 `PlayerInteractEvent` 一次 `getDuplicateInteractionResult`（`:46-58`，1 次 `HashMap.get` + `platform.getTickCount()`）+ 一次 `setLastInteraction`（`:42-44`，1 次 `put`）。`getTickCount()` 是 `System.nanoTime() / 50_000_000`（`AbstractPlatform.java:59-61`）。
- 每秒量：50 人 × 5 次点击/秒 = 250 次/秒 => 500 次哈希操作 + 250 次 nanoTime ≈ **25-50 µs/秒（约 0.003% 单核）**。
- 结论：**性能上不值得动**。它的 `HashMap`（`:32`）在 Folia 上被多区域线程并发访问，是正确性问题（可能桶链成环），已在 `reports/deep-interaction.md` 的 R5 记录；这里只强调：修它是为了正确性，不要指望有性能收益。
- 分级：**D（性能维度）**。

### 3.4 KeyQueuedExecutorService / AsyncNotifyKeyedQueue —— 每次点击 3 个小对象 + 1 次全局锁（分级 C）

文件：`worldedit-core/src/main/java/com/fastasyncworldedit/core/util/task/KeyQueuedExecutorService.java`、`AsyncNotifyKeyedQueue.java`

- 依据：`AsyncNotifyKeyedQueue.call`（`:53-70`）每次提交分配 `new Future[1]`（`:54`）+ 包装 lambda（`:55`）+ `FutureTask`（`KeyQueuedExecutorService.java:83`）；`execute`（`:102-116`）在 **`synchronized (keyQueue)`** 里做一次 `HashMap` get/put。
- 每秒量：按 1000 次交互/秒算 = 3000 个小对象/秒 + 1000 次无竞争 `synchronized`。**GC 与锁都不构成瓶颈**（年轻代每秒可处理百万级小对象）。
- 唯一值得记的：`keyQueue` 是**全局单锁**，玩家数上升时理论上会争用；但临界区只有一个 map 操作，50 人规模下争用概率极低。若将来要扩到数百人并发刷，再考虑按 key 分段。
- 分级：**C**（记录即可，不改）。

---

## 4. Pattern 热路径（function/pattern/*）

### 4.1 SurfaceRandomOffsetPattern —— 每方块 O(moves×8) 次虚拟调用 + 每候选一次分配（分级 A）

文件：`worldedit-core/src/main/java/com/fastasyncworldedit/core/function/pattern/SurfaceRandomOffsetPattern.java`（触发词 `#surfacespread`，`SurfaceRandomOffsetPatternParser.java:24/46`）

```java
// :52-54  每次取方块都走整条路径
public BaseBlock applyBlock(BlockVector3 position) { return pattern.applyBlock(travel(position)); }

// :56-80
private BlockVector3 travel(BlockVector3 pos) {
    cur.setComponents(pos);
    for (int move = 0; move < moves; move++) {          // moves ≤ 255
        int index = 0;
        for (int i = 0; i < allowed.length; i++) {      // 8 个对角方向
            next = buffer[i];
            if (allowed(next)) allowed[index++] = next; // :69 每次候选都调用
        }
        if (index == 0) return cur;
        next = allowed[ThreadLocalRandom.current().nextInt(index)];   // :76 每次 move 一次 ThreadLocal 取
        cur.setComponents(next.x(), next.y(), next.z());
    }
    return cur;
}

// :82-125
private boolean allowed(BlockVector3 bv) {
    MutableBlockVector3 v = new MutableBlockVector3(bv);   // :83  ← 每候选一次对象分配（纯浪费）
    BaseBlock block = pattern.applyBlock(bv);              // :84  ← 再跑一遍内部 pattern
    ...
    if (y < maxY && canPassthrough(v)) ...                 // :92-93 → :127-129 又是 pattern.applyBlock
    ... 最多 5 次 canPassthrough ...
}
```

- **依据**：`travel` 每次 `applyBlock` 都从当前方块重新出发；每个候选还要跑一遍**内部 pattern**（`:84` 与 `:128`）。
- **每方块成本**：`O(moves × 8)` 次 `allowed()` 调用；每次 1 个 `new MutableBlockVector3` + 1..6 次虚拟调用。moves=2 时约 16 候选；moves=10 时约 80 候选、最多 480 次虚拟调用、80 次分配。**这是一个按方块数线性、按 moves 线性放大的乘性成本**，也是本域唯一真正「指数感」的地方。
- **省什么**：① `:83` 的每次分配（改为复用字段 scratch，`MutableBlockVector3` 已有 `setComponents`）；② `:76` 的 `ThreadLocalRandom.current()` 提到 `travel` 入口缓存一次；③ `:92-121` 反复 `pattern.applyBlock(v)` 可只算一次 `isMovementBlocker` 的 type 位图（若内部 pattern 是固定方块）。
- **什么条件下看得出来**：任何人用 `//set #surfacespread[...][n]` 且 n ≥ 4 时；n=2 时已比普通 `//set` 慢一个数量级。10 人小服只要有人用一次就明显（单次命令耗时从秒级到十秒级）。
- **改动风险**：低（纯局部变量/复用，不改变随机序列——注意 ③ 若改成缓存会改变结果，只能做 ①②）。
- **最小改法**（只做 ①②，语义完全不变）：

```java
private final MutableBlockVector3 scratch = new MutableBlockVector3();   // 新增字段

private boolean allowed(BlockVector3 bv) {
    MutableBlockVector3 v = scratch.setComponents(bv.x(), bv.y(), bv.z());  // 原 :83
    ...
}

private BlockVector3 travel(BlockVector3 pos) {
    final ThreadLocalRandom rnd = ThreadLocalRandom.current();   // 原 :76
    ...
    next = allowed[rnd.nextInt(index)];
    ...
}
```

### 4.2 PlacementStateProcessor —— 每方块 6-12 次分配（分级 B，仅非 fastMode）

文件：`worldedit-core/src/main/java/com/fastasyncworldedit/core/extent/processor/PlacementStateProcessor.java`

```java
// :423（getBlockOrdinal 内）
Set<String> states = state.getStates().keySet().stream().map(Property::getName).collect(Collectors.toSet());
```

- **依据**：`getStates()`（`BlockState.java:382-393`）每次调用都 `Maps.asMap(...)` + `Collections.unmodifiableMap(...)`（2 个包装对象）；再 `.keySet()` + `.stream()` + `.map()` + `.collect(toSet())` 又是 4 个对象。**每处理一个方块 6 个对象**，只为判断属性名里有没有 NORTH/EAST。
- 同时 `:421` 的 `Direction.getDirections(state)`（`Direction.java:371` 起）每次 `EnumSet.noneOf` + 对每个布尔属性 `property.getName().toUpperCase(Locale.ROOT)`（**每次新字符串**）+ `Direction.valueOf(...)`。
- **进链条件**：`:477-493` 的 `applyBlock(FilterBlock)` 是逐方块调用的；该处理器由 `EditSessionBuilder.java:602-611` 在 `sideEffectSet.shouldApply(SideEffect.NEIGHBORS)` 时加入，而 `FAST_SIDE_EFFECTS`（`EditSessionBuilder.java:94-97`）只含 HEIGHTMAPS + LIGHTING ⇒ **fastMode 下不加入，non-fastMode 下加入**。
- **省什么**：每个方块 6-12 次分配；`//set stairs 100000` 在关掉 fastmode 时是 60-120 万个短命对象。
- **什么条件下看得出来**：玩家关掉 fastmode（或用 `//fast` 之外的路径、或用带 NEIGHBORS 的 API 调用）且编辑含朝向类方块（楼梯/栅栏/墙/门/箱子）。
- **改动风险**：低。
- **最小改法**：把「该 BlockType 是否有 north/east 属性」按 `BlockType` 缓存成布尔（`BlockTypesCache` 已有 `values` 数组，可用 `internalId` 索引的 `byte[]`，懒填充），`:423` 整行替换成两次数组读：

```java
// PlacementStateProcessor 增加静态缓存
private static final byte[] HAS_NESW = new byte[BlockTypes.size()];   // 0=未知 1=有 2=无
boolean hasNorthEast = hasNorthEast(state.getBlockType());

// :424 改为
if (dirs.isEmpty() || hasNorthEast) {
```

### 4.3 BufferedPattern —— 每方块一次哈希插入（分级 C）

文件：`worldedit-core/src/main/java/com/fastasyncworldedit/core/function/pattern/BufferedPattern.java`

- 依据：`:64-70` 每方块 `actionTime[1] = timer.getTick()` + `set.add(pos)`（`LocalBlockVectorSet` / `BlockVector3Set`，`:53-55`）。即每方块一次哈希集合插入（约 20-50 ns）并占用内存（大小正比于编辑方块数）。
- 用途：brush 去重（避免同一方块在短窗口内被重复应用），典型规模是 brush 半径级（几千方块），成本可接受。
- 分级 **C**：不改。若将来要降低内存，可把 `BlockVector3Set` 换成按 chunk 位图，但收益只在大 brush 时可见。

### 4.4 RandomPattern → FastRandomCollection —— 数组下标，无分配（分级 D，正确做法）

文件：`worldedit-core/src/main/java/com/fastasyncworldedit/core/util/collection/FastRandomCollection.java`

- 依据：`RandomPattern.applyBlock`（`RandomPattern.java:108-111`）→ `collection.next(x,y,z)` → `FastRandomCollection.next`（`:62-65`）就是一个数组下标 + RNG。加权表在构造期展开成数组（`:31-60`）。
- 结论：这是「热路径预展开」的正确范例，**不要动**。可以作为 4.1/4.2 改法的参照。
- 分级 **D**。

### 4.5 `Pattern.apply(extent,get,set)` 默认实现 —— 无分配（分级 D）

- 依据：`Pattern.java:54-56` 里 `applyBlock(get)` 的返回值来自 `BlockState.toBaseBlock()` 的缓存字段（`BlockState.java:423-425`），`setFullBlock` 只是转调 `Extent.setBlock`（`BlockVector3.java:831-833`）。看起来像「每方块造一个 BaseBlock」，实际不造。**这条别改**，改成别的反而可能引入分配。
- 分级 **D**。

---

## 5. BlockState / BaseBlock / BlockTypesCache

### 5.1 BlockTypesCache —— 全部在类初始化期完成（分级 D，正确做法）

文件：`worldedit-core/src/main/java/com/sk89q/worldedit/world/block/BlockTypesCache.java`

- 依据：`static {}`（`:209-280`）一次性建 `values[]` / `states[]` / `ticking[]`，此后所有查询都是数组下标（`states[ordinal]`、`values[internalId]`）。`Settings` 内部类（`:37-150`）的 `queryCapability`/`getProperties` 只在这里跑。
- 结论：**每方块没有 map 查、没有字符串解析**。这是好设计，别动。
- 分级 **D**。

### 5.2 BlockState.getMaterial() —— 每个 state 首次一次平台查询（分级 C）

- 依据：`BlockState.java:455-473` 首次调用走 `WorldEdit.getInstance().getPlatformManager().queryCapability(Capability.GAME_HOOKS).getRegistries().getBlockRegistry().getMaterial(this)`，结果缓存在 `this.material`。之后是字段读。
- 什么条件下看得出来：只在**启动/首次触达某 state** 时；`states` 是 interned 的（`BlockTypesCache.states`），所以每个 state 一次，总量是 state 数（万级），一次性几十 ms。
- 分级 **C**（知道即可，不必改）。注意 `LimitExtent:461` 每方块调 `getBlockType().getMaterial()`（`BlockType.java:357-361`，纯字段读）+ `BlockState.getNbtData()`（见 2.1b），这两个才是每方块成本。

### 5.3 BlockState.getNbt() 的默认实现 —— 每次调用 5-6 次分配（分级 B）

```java
// NbtValued.java:99-116（BlockState 未覆盖 getNbt，只覆盖了 getNbtData）
default LazyReference<LinCompoundTag> getNbtReference() {
    CompoundTag nbtData = getNbtData();                        // BlockState.java:436-438 → new CompoundTag(...)（见 2.1b）
    return nbtData == null ? null : LazyReference.from(nbtData::toLinTag);
}
default LinCompoundTag getNbt() {
    LazyReference<LinCompoundTag> ref = getNbtReference();
    return ref == null ? null : ref.getValue();
}

// LazyReference.java:50-67  每次 from() 都 new RefInfo + new ReentrantLock
```

- 依据：在 **BlockState**（不是 BaseBlock）上调 `getNbt()`/`hasNbtData()`，每次都会：`new CompoundTag`（`BlockMaterial.java:189-195`）+ `LazyReference.from`（`LazyReference.java:31-33`）+ `RefInfo` + `ReentrantLock`（`:50-57`）+ `linTag()` 转换。**大约 5-6 个对象/次，且没有跨调用缓存**（因为 LazyReference 是每次新建的）。
- 每方块命中点：`PlacementStateProcessor.java:393` / `:396` / `:413`（`BlockTypesCache.states[ord].getNbt()`），即非 fastMode 下每处理一个放置方块一次。`BaseBlock.getNbt()`（`BaseBlock.java:177-179` 覆盖了 `getNbtReference`）不受影响，是字段读。
- 省什么：每个命中方块 5-6 个对象 + 一次锁构造。
- 什么条件下看得出来：非 fastMode + 含 tile 方块（箱子/告示牌）的编辑；与 4.2 叠加时是同一批方块上再加 5-6 个对象。
- 改动风险：低。
- 最小改法：给 BlockState 覆盖 `getNbt()`，返回按 state 缓存的 `LazyReference`：

```java
// BlockState.java 增加字段
private LazyReference<LinCompoundTag> nbtRef;      // 懒建一次

@Override
public LinCompoundTag getNbt() {
    if (!getBlockType().getMaterial().isTile()) return null;
    LazyReference<LinCompoundTag> ref = nbtRef;
    if (ref == null) nbtRef = ref = LazyReference.computed(getBlockType().getMaterial().getDefaultTile().toLinTag());
    return ref.getValue();
}
```
（更彻底的做法是在 `BlockTypesCache.Settings` 构造期就把每个 tile state 的 NBT 引用算好，`BlockState` 直接持有。）

### 5.4 BaseBlock.hashCode / equals —— 有 NBT 时会取一次锁（分级 C）

- 依据：`BaseBlock.java:302-306` 的 `hashCode` 用 `getOrdinal()<<3` + `getNbt().hashCode()`；`equals`（`:190-201`）用 `blockState.equalsFuzzy` + `Objects.equals(getNbt(), ...)`。`getNbt()` 走 `LazyReference.getValue()`（`LazyReference.java:71-88`），**首次会进 `ReentrantLock`**，之后是字段读。
- 影响面：`//replace <方块集合>` 时 `BlockMask` 每方块一次 `HashSet<BaseBlock>.contains`（1 次 hashCode，可能 1 次 equals）；无 NBT 的方块是纯算术。
- 分级 **C**：不改。若将来发现 mask 路径成瓶颈，可在 mask 里改用 ordinal 位图（FAWE 已有 `BlockTypeMask` 这类结构）。

---

## 6. 汇总表（按建议优先级）

| 编号 | 分级 | 位置 | 每方块/每事件成本 | 省什么 | 可测条件 | 风险 |
| --- | --- | --- | --- | --- | --- | --- |
| S1 | **A** | `SurfaceRandomOffsetPattern.java:83`（+ `:76`） | moves×8 次候选，每候选 1 次分配 + 1-6 次虚拟调用 | 每候选 1 个对象、每次 move 1 次 ThreadLocal | `#surfacespread` 且 n≥4 | 低 |
| S2 | **A** | `MemUtil.java:27-28`、`:86-87` + `Fawe.java:426-430` | 每次低内存告警 2 次 full GC（且不节流） | 数百 ms 级 STW；避免 GC 反馈回路 | 内存逼近 `max-memory-percent` | 低-中 |
| S3 | A- | `LimitExtent.java:460` | 每方块 1 次 AtomicLong RMW | 1 次 lock 前缀指令/方块 | ≥10 万方块编辑 | 低 |
| S4 | **B** | `LimitExtent.java:461`（+ `BlockMaterial.java:189-195`） | 每 tile 方块 1 次 `CompoundTag` 分配 | 1 个对象/方块 | 编辑含箱子/告示牌等 | 低 |
| S5 | **B** | `PlacementStateProcessor.java:423`（+ `BlockState.java:382-393`、`Direction.java:371+`） | 每方块 6-12 次分配 | 6-12 个对象/方块 | 非 fastMode + 朝向类方块 | 低 |
| S6 | **B** | `BlockState` 未覆盖 `getNbt()`（`NbtValued.java:99-116` + `LazyReference.java:50-67`） | 每次调用 5-6 个对象 | 5-6 个对象/次 | 非 fastMode + tile 方块 | 低 |
| S7 | **B** | `MultiRegionExtent.java:76-95` | 每方块 O(区域数) 次 contains | 区域数这个乘数 | allowed 区域 ≥8 且跨边界编辑 | 低-中 |
| S8 | **B** | `HistoryExtent.java:57` | 每方块 1 次额外区块读 | 约一半访问次数 | `combineStages=false` / `blockBag!=null` | 中 |
| S9 | **B** | `OncePerChunkExtent.java:55` | 每方块 1 次 synchronized | 10-20 cycle/方块 | `//copy` 带实体 | 低 |
| S10 | **C** | `DisallowedBlocksExtent.java:136-165` | 每 section 4096 次查表 | 把 String 查换位图 | 配了 disallowed-blocks | 低 |
| S11 | **C** | `BufferedPattern.java:64-70` | 每方块 1 次哈希插入 | 内存 | 大 brush | 低 |
| S12 | **C** | `Settings.getLimit()`（`Settings.java:112+`） | 每次交互 1 个 FaweLimit + 权限串拼接 | 每点击 1 个对象 | 50 人高频刷 | 低 |
| S13 | D | `FaweTimer`（20 次/秒）、`InteractionDebouncer`（每次点击 2 次哈希）、`AsyncNotifyKeyedQueue`（每次点击 3 个对象） | — | 无可测收益 | — | — |
| S14 | D | `BlockTypesCache`（数组下标）、`FastRandomCollection.next`（数组下标）、`Pattern.apply` 默认（无分配）、`SlowExtent`（默认不启用） | — | — | — | — |

**一句话**：可动的收益集中在 S1（`#surfacespread` 的乘性成本）、S2（`System.gc()` 的停顿）、S4-S6（tile/朝向方块在非 fastMode 下的对象分配）；默认 `//set stone` 路径已经没有可观的固定开销可抠。

---

## 7. 怎么测（10 人 / 50 人）

1. **基线**：同一世界、同一区域（建议 128×64×128 = 1,048,576 方块），分别跑 `//set stone`、`//set chest`（tile）、`//set oak_stairs[facing=east]`（朝向）、`//replace stone 50%stone,50%cobblestone`。记录 `worldedit.command.time-elapsed` 输出的耗时（`PlatformCommandManager.java:791-798` 已经会打印 >1000ms 的编辑）。
2. **对象分配**：加 `-XX:+UnlockDiagnosticVMOptions -XX:+DebugNonSafepoints`，用 JFR（`jcmd <pid> JFR.start` + `settings=profile`）采 60s，看 `jdk.ObjectAllocationInNewTLAB` 里 `com.sk89q.worldedit.world.block.BaseBlock` / `CompoundTag` / `java.util.HashSet` / `PlacementStateProcessor` 栈的占比。S4/S5/S6 会在同一个栈上一起出现，正好一次验证三条。
3. **每方块固定开销**：用 JMH 或临时 `System.nanoTime()` 包住 `EditSession.setBlock`（只测 wrapper 层，`chunk.setBlock` 之前），跑 100 万次空转（用 `NullExtent` 或 `limit` 设为 unlimited 绕开 LimitExtent），对比改动前后。预期 S3 可测出 15-20 cycle/方块的差。
4. **GC 停顿**：`-Xlog:gc*:file=gc.log:time,uptime` 开日志，做一次「把堆压到 `max-memory-percent` 附近 + 连续 `//set`」的实验，统计 `Pause Full` 次数与成因（是否来自 `System.gc()`）。S2 的验证只能这样测。
5. **区域扫描**：构造 16 个 allowed 区域、让编辑跨边界（`MultiRegionExtent`），对比 1 个区域 vs 16 个区域的耗时曲线；若呈线性放大即 S7 成立。
6. **人数维度**：S12/S13 这类「每次交互」的成本只在并发时体现——用 10/50 个假玩家（或直接多线程调用 `SessionManager.get` + `runAction`）压测命令入口，看 p99。按 3.3/3.4 的估算，这两项在 50 人下仍 <0.01% 单核，**预期测不出**，这也是结论的一部分。

---

## 8. 待确认（不做臆断）

1. `SideEffect.NEIGHBORS` 的 `getDefaultValue()` 是否为 ON（决定 S5 在非 fastMode 下是否真的进链；本文按 `SideEffectSet.DEFAULT` 由 `SideEffect::getDefaultValue` 计算、且 WE 语义上默认开邻居更新推断为 ON）。
2. 线上服的实际配置：`history.combine-stages`、`limits.default.fast-placement`、`disallowed-blocks`、`max-memory-percent`、`lighting.mode`。S4-S8 全部依赖这些开关；默认值下只有 S1/S2/S3 会命中。
3. `#surfacespread` 在线上是否有玩家在用（S1 的价值完全取决于此）。
