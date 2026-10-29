# FastAsyncWorldEdit · Folia 版

作者：JunXieX　|　MikuMC系列插件交流群：1105054380　|　基于 FastAsyncWorldEdit（GPLv3）修改，按 GPLv3 分发

面向 Folia 的 FastAsyncWorldEdit 分支。上游 FAWE 默认假设"服务器只有一个主线程"，
在 Folia 的区域化多线程下插件会被加载器跳过或直接关服；本分支把调度、区块读写与发包改造成按区域归属投递。

## 功能特性

- 与上游 FAWE 功能一致：批量方块操作、剪贴板、schematic、笔刷、历史撤销、异步编辑。
- 区域化调度：所有世界读写投递到拥有该区块的区域线程，不再依赖 Bukkit 主线程。
- 只构建 Paper 产物：使用 Mojang 映射适配器，适用于 Paper 及其派生服务端（含 Folia）。
- 未改动的上游行为：配置项、命令、权限节点与上游一致，可直接沿用原有配置与文档。

## 环境要求

| 项目 | 要求 |
| --- | --- |
| 服务端 | Folia 26.3（构建 5 及以上）或同版本 Paper |
| Java | 25 |
| 内存 | 建议 2 GB 以上（大范围编辑按区块缓冲，内存越大越快） |
| 适配器 | 构建产物内含 1.21 / 1.21.4 / 1.21.5 / 1.21.6 / 1.21.9 / 1.21.11 / 26.1 / 26.2 / 26.3 |

## 安装

1. 下载 `MikuWorldEdit-Paper-*.jar`，放入服务端 `plugins/`。
2. 启动服务端。首次启动会生成：
   - `plugins/FastAsyncWorldEdit/config.yml`
   - `plugins/FastAsyncWorldEdit/worldedit-config.yml`
   - `plugins/FastAsyncWorldEdit/schematics/`、`history/`、`clipboard/`
3. 控制台执行 `fawe version` 确认加载，日志中应出现：

   `Using com.sk89q.worldedit.bukkit.adapter.impl.fawe.v26_3.PaperweightFaweAdapter as the Bukkit adapter`

4. 若与其它 WorldEdit 系插件共存，请移除原 WorldEdit / FAWE，本插件 `provides: [WorldEdit]`。

## 配置说明

配置项与上游一致，主要文件：

- `config.yml`：队列线程 `queue.parallel-threads`、`queue.target-size`、历史与剪贴板压缩、限制组 `limits`。
- `worldedit-config.yml`：WorldEdit 本体配置（选区、导航、历史、快照等）。

Folia 相关的行为差异（无需配置）：

- 编辑器并行队列在 Folia 上关闭，改由服务端按区域并行；`queue.parallel-threads` 仍决定 FAWE 自身处理线程数。
- 区块写入一律投递到拥有该区块的区域线程，方块更新/光照/区块包都在该线程内完成。
- `//regen`（区块重生成）在 Folia 上可用：工作线程逐个区块读取源地形（方块、方块实体 NBT、生物群系），
  读完立即写入该区块并释放，内存占用恒为一个区块的规模；`-b`（重生成生物群系）与指定单个生物群系均生效。
- 实体相关的少数操作（如 `//removeabove` 的实体清理、传送）走全局区域调度，可能比 Paper 上晚一个 tick 生效。

## 命令

命令与上游 FAWE 完全一致，常用：

| 命令 | 说明 |
| --- | --- |
| `//set <方块>` | 选区填充 |
| `//copy` / `//paste` | 复制 / 粘贴剪贴板 |
| `//schem save <名称>` / `//schem load <名称>` | 保存 / 载入 schematic |
| `//undo` / `//redo` | 撤销 / 重做 |
| `//regen` | 区块重生成（Folia 上不执行） |
| `/fawe version` | `/fawe threads` | `/fawe reload` | 版本、线程状态、重载配置 |

## 权限节点

与上游一致，常用：`fawe.admin`、`fawe.bypass`、`fawe.limit.unlimited`、`worldedit.*`，
完整列表见插件内 `plugin.yml` 的 `permissions` 段（`fawe.permpack.basic` 为常用打包权限）。

## 相对上游的改动

- 调度层：`TaskManager` 增加区域归属查询与按区块投递（`isFolia/ownsRegion/executeAt/submitAt`），
  Bukkit 实现改用 `GlobalRegionScheduler` / `RegionScheduler` / `AsyncScheduler`。
- 写入层：区块落地由 FAWE 线程池改为投递到拥有该区块的区域线程；光照、高度图与区块包在该线程内完成。
- 适配器层：区块加载、区块包发送、方块副作用与区块重生成在各版本适配器中增加区域化分支；
  没有旁观者的区块不再组装区块包，私有（重生成用）世界不进入区域调度器。
- 实体操作：删除/读取实体 NBT 按实体所在区块投递到拥有它的区域线程；区域线程上的 `sync` 直接就地执行。
- 构建：只产出 Paper 单产物，移除 Spigot 重混淆路径；支持 `-Padapters=26.3` 限定构建的适配器子集。

## 验证记录

在 Folia 26.3（build 5）本机实测：

| 用例 | 结果 |
| --- | --- |
| 插件加载与适配器选择 | 通过（`PaperweightFaweAdapter` v26_3） |
| 48³ 选区 `//set` + 逐区块回读校验 | 12,288/12,288 通过，764 ms |
| 96³ 选区（36 区块） | 884,736/884,736 通过，837 ms |
| 192³ 选区（144 区块，跨多区域） | 7,077,888/7,077,888 通过，475 ms |
| `/fawe version`、`/fawe threads` | 通过 |
| `//regen` 32³ 列（4 区块） | 通过，1.2 s，填入的 32,768 个石头全部被地形替换 |
| `//regen` 96³ 列（36 区块） | 通过，7.7 s，884,736 方块 |
| `//regen` 192³ 列（144 区块） | 通过，29.7 s，7,077,888 方块 |
| `//regen` 指定生物群系（the_void） | 通过：重生成后该处群系由 plains 变为 the_void |
| `//regen -b`（重生成生物群系） | 通过，无异常 |
| 实体删除（WorldEdit 包装器，工作线程调用） | 通过：`removed=true gone=true` |
| 全程日志 Exception / 跨区域错误 | 0 条 |

性能（Folia 26.3 build 5，超平坦，无玩家在线；同一区域连续测量，丢弃前 6 次预热，取中位数）：

| 用例 | 优化前 | 优化后 |
| --- | --- | --- |
| `//set` 96³（884,736 方块） | 113 ms | 109 ms |
| `//set` 192³（7,077,888 方块） | 295 ms | 295 ms |
| `//regen` 96³ | 7.53 s | 7.51 s |

结论：本轮的调度层优化（去掉每区块重复的归属判定、缓存世界包装、无旁观者不发区块包、Folia 上不再注册空转的同步泵）
对这些用例处于测量噪声之内，没有可测的吞吐提升。时间分布实测为"入队 3 ms + 区块落地 ~106 ms"，
即成本集中在区块写管线（section 转换、光照、发包），不在调度层。

## 性能基准

性能数据不再在本地采集，统一在 GitHub Actions 上跑：`Actions → Performance → Run workflow`，
可选参数为立方体边长（默认 96）、测量轮数（默认 5）与适配器子集。流程为构建产物 → 下载 Folia →
编译基准插件 → 启动服务端 → 预热 3 轮 → 测量 → 结果写入本次运行的 Summary，并随日志一起作为产物上传。

测量口径固定为「同一区域的连续写入，入队与区块落地分开计时」，避免冷 JVM 与未生成区块污染结果。

CI 基线（GitHub 托管 runner，96³，5 轮测量）：

| run | 入队 ms | 区块落地 ms | 合计 ms |
| --- | --- | --- | --- |
| 1 | 17 | 175 | 192 |
| 2 | 16 | 196 | 212 |
| 3 | 15 | 155 | 170 |
| 4 | 16 | 176 | 192 |
| 5 | 17 | 140 | 157 |

托管 runner 的绝对值和物理机不可直接比较，看同一 runner 上的**相对变化**即可。

## 许可证

本项目继承上游 FastAsyncWorldEdit 的 GNU General Public License v3.0，见 `LICENSE.txt`。
修改后的源码同样以 GPLv3 分发；二次分发请保留版权与许可声明。