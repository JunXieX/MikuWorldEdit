# MikuWorldEdit · Folia 版

作者：JunXieX　|　MikuMC系列插件交流群：1105054380　|　基于 FastAsyncWorldEdit（GPLv3）修改，按 GPLv3 分发

插件名 `MikuWorldEdit`，产物 `MikuWorldEdit-Paper-<版本>.jar`，声明 `provides: WorldEdit`，
依赖 WorldEdit 的插件（WorldGuard 等）可照常工作。

这不是把 FastAsyncWorldEdit 换个名字重新打包：上游在 Folia 上加载即被拒或直接关服，本分支重做了调度、
区块读写、发包与重生成四条链路，并按实测数据做了若干性能修正。差异与依据逐条列在下面。

---

## 一、与 FastAsyncWorldEdit 的根本区别

| 维度 | FastAsyncWorldEdit（上游） | MikuWorldEdit（本分支） |
| --- | --- | --- |
| Folia 支持 | 不支持。加载即报「不是 Folia 兼容插件」，强行加载会在非主线程访问世界 | 原生支持；无人在线、多玩家、跨区域编辑均实测通过 |
| 线程模型 | 假设「只有一个主线程」，`Fawe.isMainThread()` 决定能否直接写世界 | 四类调度器（全局/区域/异步/实体），世界读写按**区块归属**派发 |
| 产物 | Paper + Spigot 双产物，Spigot 走重混淆 | 只出 Paper 单产物，Mojang 映射适配器，无重混淆环节 |
| 插件描述 | 旧版 `plugin.yml` | `paper-plugin.yml`（Paper 原生加载器） |
| `//regen` | 依赖临时世界 + 主线程泵，Folia 上不可用 | Folia 上可用：工作线程逐区块快照（方块 + 方块实体 NBT + 生物群系）后写回，内存恒为一个区块 |
| 实体操作 | `sync()` 回主线程执行 | 按实体所在区块投递；区域线程内的 `sync()` 就地执行 |
| 构建与验收 | 本地与 CI 各自为政 | 只在 GitHub Actions 构建，性能基准也在 Actions 上跑 |

### 落到代码

- `TaskManager` 新增 `isFolia / isTickingThread / ownsRegion / executeAt / submitAt / executeGlobal`，
  Bukkit 侧改用 `GlobalRegionScheduler` / `RegionScheduler` / `AsyncScheduler`。
- `SingleThreadQueueExtent.submitUnchecked` 在 Folia 上把每个区块的写入投递到拥有它的区域线程；
  `AbstractBukkitGetBlocks.handleCallFinalizer` 把光照、高度图、发包的收尾在该线程内就地执行。
- 9 个适配器的 `PaperweightPlatformAdapter` 增加区域化分支：区块加载、区块包发送、方块副作用、
  光照 ticket 回收；并为 `//regen` 的私有世界加了「不进入区域调度器」的守卫。
- 实体删除与实体 NBT 读取改为 `submitAt(实体所在区块)`，`//remove`、`//cut`、实体粘贴因此在 Folia 上可用。

## 二、性能优化（每条都有实测依据）

测量口径：Folia 26.3 build 5，超平坦，无玩家在线，**每次换全新区域**，入队与区块落地分开计时。
（复用同一区域会让世界状态漂移，实测足以把「无变化」读成「5 倍回归」，这条教训写在性能报告里。）

### 1. 区块落地：调色板收尾只清实际用到的序号

`PaperweightPlatformAdapter#newChunkSection` 原先把四个线程本地数组在每个 section 每次调用时整表重置
（`blockToPalette` 2.6 万项、`paletteToBlock` 65536 项、`blockStates` 2048 项、`blocksCopy` 4096 项）。
其中三个每次都被全量重写，`paletteToBlock` 只读 `[0, num_palette)`，重置是纯浪费。

现在只回收本次映射过的序号（通常 1–3 项）：每个 section 少约 9.5 万次数组写，96³ 用例少约 8 千万次。

### 2. 单值 section 不再跑 4096 次映射

单值调色板（`//set` 单一方块、`//walls`、平整）不写出位打包数据，`blocksCopy` 无人读取，
却仍跑了一遍 4096 项映射。现在直接跳过。

### 3. 替换 section 前不再全量解包旧 section

原逻辑把旧 section 完整解包进一个 4096 字符的新数组，只为和待写入数据比对一次然后丢弃；
旧缓存本就由随后的 `updateGet` 刷新。已删除这次比对与解包，换段失败时才显式失效。

### 4. 历史写入合批，且走真正的批量写

历史记录原本每方块 6 次（紧凑模式 4 次）单字节 `write`，每次都要穿过历史压缩链的每一层（默认三层 LZ4）。
实测历史占 96³ 区块落地的 40–65 ms（20–28%）；现在合成一次 `write(byte[], 0, n)`，
并给 `FaweOutputStream` 补上真正的批量转发（默认 `FilterOutputStream` 是逐字节循环，直接合批等于没合）。
字节流内容不变，`//undo` 回放 884,736 条记录后方块归零已验证。

### 5. 无旁观者时不组装区块包

每个被改动的区块都会组装一次 `ClientboundLevelChunkWithLightPacket`（含完整光照数据）再丢弃。
现在先查该区块的可见玩家，为空则直接返回，连带跳过两次锁操作。

### 6. 去掉每 tick 的空转与重复判定

- Folia 上不再注册 FAWE 自身的同步泵（`sync()` 本就投递到全局调度器，泵永远空转）。
- 全局任务不再包一层 `ThreadLocal`（每 tick 3 个任务 → 每 tick 少 6 次读写）。
- 每区块只做一次区域归属判定，世界包装对象不再重复创建；已注册世界做缓存，不再每区块查字符串表。
- 一次性延时任务执行后从登记表移除，长时间运行不再缓增调度对象。

### 7. 光照重计算的并发安全

relighter 的锁原本每个 EditSession 各一把，两人在相邻区域同时编辑会各拿一把锁、并发调用 starlight
写同一批 `DataLayer`，光照数据可能被交叉覆盖。现改为进程内共享锁：并发编辑时后者走既有的跳过分支，
比数据被写坏安全。

### 8. 一处越界修复（顺带）

`CharFilterBlock#getBlockAbove` 的守卫写成 `y < 16`，而 `y` 是 section 内局部坐标（0–15），条件恒真，
`y == 15` 时读 `index + 256` 越过 4096 长度的数组。任何在 section 顶端取「上方方块」的放置判定
（楼梯、植被、附着类方块）都会抛越界、整块编辑被丢弃。已改为 `y < 15` 并正确读取下一层。

### 当前开销分布（96³，884,736 方块）

| 阶段 | 实测 | 说明 |
| --- | --- | --- |
| 入队 | 3–20 ms | 遍历选区往队列塞方块，约 8 ns/方块，已到底 |
| 区块落地 | 150–230 ms | section 调色板、高度图、光照、历史、发包 |
| 其中：历史 | 40–65 ms | 已合批 |
| 其中：光照 | ≈80 ms | 主体是 starlight 重算与 3×3 邻域扩张，不是清理 |
| 纯读取 | 2.4–2.6 µs/方块 | 逐方块读要穿整条 extent 链，见「已知限制」 |

CI 基准（GitHub 托管 runner，96³，5 轮）：median 164 ms，min 154 ms，max 206 ms。
托管 runner 之间正常可差 30%，跨 runner 的绝对值不可直接比较。

## 三、环境要求

| 项目 | 要求 |
| --- | --- |
| 服务端 | Folia 26.3（build 5 及以上）；同版本 Paper 亦可 |
| Java | 25 |
| 内存 | 建议 2 GB 以上 |
| 内置适配器 | 1.21 / 1.21.4 / 1.21.5 / 1.21.6 / 1.21.9 / 1.21.11 / 26.1 / 26.2 / 26.3 |

运行期只在 Folia 26.3 与 Paper 26.3 上验证；其余适配器只验证编译通过。

## 四、安装

1. 下载 `MikuWorldEdit-Paper-*.jar` 放入 `plugins/`，同目录不要同时保留 WorldEdit 或 FastAsyncWorldEdit。
2. 启动服务端。首次启动生成 `plugins/MikuWorldEdit/`（`config.yml`、`worldedit-config.yml`、
   `schematics/`、`history/`、`sessions/`、`lang/`）。
3. 从旧版升级时，首次启动会自动把 `plugins/FastAsyncWorldEdit/` 的内容复制到新目录，配置与限制不会丢。
4. 控制台执行 `/fawe version` 确认加载；日志出现
   `Using ...v26_3.PaperweightFaweAdapter as the Bukkit adapter` 即适配器选择正确。

## 五、配置

配置项与上游 FAWE 一致，沿用原文档即可。Folia 下的行为差异（无需配置）：

- FAWE 自带的并行队列在 Folia 上关闭，改由服务端按区域并行；`queue.parallel-threads` 仍决定 FAWE 自身处理线程数。
- 区块写入一律投递到拥有该区块的区域线程，方块更新、光照、区块包都在该线程内完成。
- `//regen` 可用：`-b`（重生成生物群系）与指定单个生物群系均生效。
- 少数跨区域操作（如传送玩家）可能比 Paper 上晚一个 tick 生效。

## 六、命令与权限

命令与上游完全一致：`//set`、`//copy`、`//paste`、`//schem`、`//undo`、`//redo`、`//regen`，
以及 `/fawe version|threads|reload`。

权限节点与上游一致：`fawe.admin`、`fawe.bypass`、`fawe.limit.unlimited`、`worldedit.*`，
完整列表见 `paper-plugin.yml` 的 `permissions` 段（`fawe.permpack.basic` 是常用打包权限）。

## 七、已知限制

- **`//replace` / `//count` 这类逐方块扫描命令较慢**：单方块读取要穿整条 extent 链，实测 2.4–2.6 µs/方块，
  96³ 无匹配的 `//replace` 约 2.1 秒。已定位到 `EditSession` 拿不到队列 extent 的句柄；
  要修需把循环下沉到 `IQueueExtent`，属结构性改动，暂未实施。
- **光照仍是区块落地里最大的一块**（96³ 约 80 ms）：主体是 starlight 重算与 3×3 邻域扩张
  （36 列被改成 64 列，放大 1.78 倍；单区块编辑放大 9 倍）。改动需要可目视验证的场景。
- 旧适配器（1.21.x / 26.1 / 26.2）只验证编译；这些版本缺少 `isRegionised` 之类的守卫细节，
  `//regen` 在其上会安全失败而不是正常工作。
- 协议层未做优化：区块包按玩家可见性裁剪、section 级增量包均未实现。

## 八、性能基准怎么跑

性能数据不在本地采集，统一在 Actions 上跑：`Actions → Performance → Run workflow`，
可填立方体边长、测量轮数、适配器子集。流程为构建 → 下载 Folia → 编译基准插件 → 启动服务端 →
预热 3 轮 → 测量 → 结果写入运行 Summary 并上传日志。

测量口径固定为「连续写入、入队与区块落地分开计时」，**每次测量换全新区域**。

## 许可证

本项目继承上游 FastAsyncWorldEdit 的 GNU General Public License v3.0，见 `LICENSE.txt`。
修改后的源码同样以 GPLv3 分发；二次分发请保留版权与许可声明。
