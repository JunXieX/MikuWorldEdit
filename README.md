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

1. 下载 `FastAsyncWorldEdit-Paper-*.jar`，放入服务端 `plugins/`。
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
- `//regen`（区块重生成）在 Folia 上可用：源区块先在工作线程上快照为纯方块数组，再交给统一的按区域写入队列。
  限制：快照不含方块实体 NBT（箱子/告示牌/刷怪笼的内容不会随地形回填）；内存占用与该区域的区块数成正比
  （约 200 KB/区块），大范围重生成请分块执行。
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
- 适配器层：区块加载、区块包发送、方块副作用与区块重生成在各版本适配器中增加区域化分支。
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
| `//regen` 32³ 列（4 区块） | 通过，1.4 s，填入的 32,768 个石头全部被地形替换 |
| `//regen` 96³ 列（36 区块） | 通过，8.3 s，884,736 方块 |
| `//regen` 192³ 列（144 区块） | 通过，29.7 s，7,077,888 方块 |
| 全程日志 Exception / 跨区域错误 | 0 条 |

## 许可证

本项目继承上游 FastAsyncWorldEdit 的 GNU General Public License v3.0，见 `LICENSE.txt`。
修改后的源码同样以 GPLv3 分发；二次分发请保留版权与许可声明。
