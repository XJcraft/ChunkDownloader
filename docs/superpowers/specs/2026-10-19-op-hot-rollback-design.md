# OP 热回滚设计

## 背景

ChunkDownloader 目前只做"查询 + 分享"：玩家 `/cd my` 拿到自己所在区块对应 region 文件的下载链接（OneDrive / FTP / HTTP 三种分享方式）。FTP 服务器上的版本历史由运维侧的 `ftp_backup.py` 维护，插件从不读取它。

现在需要给 OP 增加一个能力：把某个 region 文件**热回滚**到 FTPS 备份中的某个历史版本，全程不停服。

参考实现：`D:\workspace\minecraft\tools\ftp_backup.py`（备份侧唯一事实来源）。关键约定：

```
/backup/mc-debian/<单元>/<相对路径>                      ← 最新版，稳定名，无版本后缀
/backup/mc-debian/_history/<单元>/<相对路径>.<YYYYMMDD-HHMM>  ← 被取代的历史版本
```

- 版本时间戳格式为 `YYYYMMDD-HHMM`，由备份时刻决定，不是源文件 mtime。
- 保留策略：每文件最近 20 个版本 / 60 天，先到为准，最新版无条件保留。
- 单文件超过 1GB 时只保留单副本、不留历史（`_history/` 中不存在该文件）。
- FTP 服务为 **FTPS 显式 TLS**（vsftpd，自签证书），端口 `15555`。

## 目标

- OP 通过**指令**完成"列出可回滚版本 → 选择 → 热回滚"的完整链路，交互形态等价于一个箱子 GUI，但只用聊天输出 + 可点击文本。
- 回滚只作用于**单个 region 文件**（32×32 区块）。
- 全程不停服，回滚窗口内该 region 不产生任何服务端写盘，且期间禁止玩家登录以避免"加载与覆盖并发"。

## 非目标

- 不做整世界回滚、不做区块级（单 chunk）回滚。
- 不解析或修改 `.mca` 内容，只做整文件替换。
- 不写远端 FTPS（插件只列目录 + 下载）。回滚只改本机磁盘。
- 不实现版本清理；保留策略仍由 `ftp_backup.py` 负责。
- 不提供 GUI 箱子/菜单。

## 不变量

1. **回滚窗口内目标 region 不产生服务端写盘**：先在主线程保存并卸载该 region 的全部已加载区块，再替换文件，最后解除封锁。
2. **登录封锁在文件替换完成前不解除**：全局短锁，`PlayerLoginEvent` 直接 deny。
3. **替换可恢复**：先下载并校验 → 把原文件复制到插件目录下的 `rollback-backups/` → 原地覆盖 → `fsync`。替换不是原子操作（原地覆盖是为让服务端缓存句柄继续有效），崩溃由备份文件写回兜底。
4. **同一时刻只允许一个回滚**（插件内全局状态位），避免并发操作同一或相邻 region。
5. 任何一步失败，**要么原文件未被触碰，要么已自动写回**；错误同时推送给执行者与 console。
6. **指令输入不可被构造**：回滚确认必须携带一次性 token，绑定执行者 UUID 与目标路径，5 分钟过期。

## 已知约束

- FTPS 上的"最新版"是 cron 每 8 小时的快照，可能落后活动世界数小时。回滚本质是**用旧快照覆盖当前世界**，被覆盖的近期改动只能从其他历史版本或本地备份找回。
- 回滚后，服务端下次备份会把新内容作为最新版上传，被回滚掉的版本仍留在 `_history/`，因此回滚本身可逆。
- 超阈值（>1GB）文件没有历史版本，`/cd rollback` 会明确报告"无可回滚版本"。
- 版本列表为空或 FTPS 不可达时，不进入任何封锁，直接报错。

## 交互设计（指令，等价于箱子）

### `/cd rollback`

- 玩家执行时以执行者**当前所在区块**为基准，解析出 region 文件与备份单元；控制台执行时需显式给出坐标（见下方控制台用法）。
- 异步列出 `_history/<单元>/<相对路径>.*` 的历史版本，按时间戳倒序。
- 聊天输出每个版本一行：`[序号] 2026-10-18-2000  ·  3.2 MB`，整行可点击，执行 `/cd rollback confirm <token> <序号>`。
- 生成一次性 token：随机 32 字符，内存保存 `{token → (发起者 UUID 或 CONSOLE, 单元, 相对路径, 版本列表快照, 过期时间)}`，有效期 5 分钟。
- 同时在聊天中给出当前文件信息（本地大小、mtime、远端最新版时间与大小），便于判断。

### `/cd rollback confirm <token> <序号>`

- 校验 token 存在、未过期、执行者 UUID 匹配、序号在快照范围内。
- 立即消费 token（无论后续成功失败），防止重放。
- 异步下载选中版本到 `plugins/ChunkDownloader/rollback-tmp/.rollback-<uuid>.tmp`，下载期间**不加锁、不踢人**。
- 下载完成并校验大小后，切回主线程执行阶段 B（见下）。

### `/cd rollback cancel`

清除该执行者当前的 token 与列表。

### 控制台用法

`/cd rollback <world> <regionX> <regionZ>` 直接列出该 region 的版本（token 的发起者记为 `CONSOLE`）；`/cd rollback confirm <token> <序号>` 同样可由控制台执行。玩家发起的 token 只能被该玩家消费，CONSOLE 发起的 token 只能被控制台消费。

## 回滚状态机

### 阶段 A：准备（异步，无封锁）

1. 校验配置：`shareMethod` 相关字段不参与，只要求 `ftpHost/ftpPort/ftpRollbackUser/ftpRollbackPassword/ftpRoot` 有效。
2. 解析目标：`world` → 备份目录（使用**独立的** `rollbackWorlds` 映射，见配置新增）；区块坐标 → `r.<x>.mca` / `r.<z>.mca`（`chunkX >> 5`，负数向下取整由 `>>` 保证）。

   不复用 `AzureConfig.worlds`：那是 OneDrive 分享布局（`map/MainLand/region/%s`），与备份侧不一致。备份脚本对 MainLand 系列只同步 Paper 的 `dimensions/` 子树，顶层 `region/`、`DIM-1`、`DIM1` 属于已废弃的遗留布局并被显式跳过（`ftp_backup.py` 的 `LEGACY_TOP_DIRS`），指向它们会永远列不到版本。
3. 通过 FTPS 列目录获取版本清单（`MLSD`，不支持时回退 `LIST`）。
4. 展示列表并生成 token（见交互设计）。
5. 确认后下载选中版本到 `plugins/ChunkDownloader/rollback-tmp/`，比对远端 `SIZE` 与下载结果。

### 阶段 B：替换（主线程，全局短锁）

1. 设置 `rollbackInProgress = true`（登录拦截生效）。
2. 踢出目标 region 内所有玩家：遍历在线玩家，比较 `location.getChunk().getX() >> 5` / `getZ() >> 5` 与目标 region 坐标；命中则按 `rollbackKickMode` 处理：`teleport`（默认）传送到**同一世界的出生点**，`kick` 直接断开。传送完成后再进入第 3 步。
3. 对该 region 覆盖的 32×32 个区块坐标逐个检查 `world.isChunkLoaded(cx, cz)`，已加载的执行 `world.unloadChunk(cx, cz, true)`（save=true）；每 tick 最多卸载 64 个区块，分批推进；遍历结束后复查，若仍有该 region 的已加载区块，**中止并恢复登录锁**，不触碰文件。
4. 复制当前 region 文件到插件目录：
   `plugins/ChunkDownloader/rollback-backups/<world>/r.<x>.<z>.mca.<YYYYMMDD-HHMMSS>`。
   用 copy 而非 move（跨目录、可能跨盘，且 region 文件要留在原位供原地覆盖）。
   每次回滚前 `mkdirs`，目录缺失不会导致失败。
5. 在 region 目录写入 `<name>.rollback.pending` 标记，然后用 `.tmp` 内容原地覆盖目标文件（`FileChannel.truncate(0)` + 写入 + `force(true)`）。
6. 删除 `.tmp` 与 `.pending` 标记；`rollback-backups/` 下的原件副本保留不删，由 OP 人工清理。
7. `rollbackInProgress = false`（登录拦截解除）。
8. 聊天与 console 输出结果：版本时间、大小、备份文件名。

### 失败恢复

| 失败点 | 处理 |
|---|---|
| 列目录失败/超时 | 不进入封锁，直接报错 |
| 下载中断/大小不符 | 删除 `.tmp`，token 已消费，需重新 `/cd rollback` |
| 阶段 B 前服务器重启 | token 与状态位随内存消失；启动时清空 `rollback-tmp/` 下的残留 `.tmp` |
| 卸载后仍有残留区块 | 中止并解除登录锁，不触碰文件 |
| 复制原文件失败 | 中止，不覆盖 |
| 覆盖中途失败 | 从 `rollback-backups/` 下的副本原地写回；写回也失败则 console 严重告警并保留副本供人工处理 |
| `fsync` 失败 | 视为失败并走写回路径 |
| 覆盖成功但进程崩溃 | 启动时发现 `.rollback.pending` 标记即告警，提示 OP 用 `rollback-backups/` 下的副本人工核对/恢复 |

**全局锁生命周期**：只在阶段 B 存在，毫秒到秒级；下载期间不锁登录。阶段 B 内所有耗时操作都是本地 IO，不做网络请求。

## 组件设计

| 组件 | 职责 | 依赖 |
|---|---|---|
| `RollbackCommand` | 解析 `/cd rollback` 子命令、校验权限、驱动状态机 | `RollbackService` |
| `RollbackService` | token 管理、阶段 A/B 编排、失败恢复、全局锁状态 | `FtpsClient`、`RollbackFileOps` |
| `FtpsClient` | FTPS 显式 TLS 控制通道、MLSD/LIST 解析、RETR 下载 | 无（JDK `Socket`/`SSLSocket`） |
| `RollbackFileOps` | 路径解析、区块卸载、原文件备份、原地覆盖、fsync | Bukkit API |
| `RollbackLoginGuard` | `PlayerLoginEvent` 监听器，全局锁生效时 deny | `RollbackService` |
| `RegionPaths` | 坐标 → 文件名、world → 单元映射（纯函数） | 无 |

### `FtpsClient`

JDK 的 `HttpClient` 不支持 FTP 控制协议，因此用 `Socket` + `SSLSocket` 手写：

- `AUTH TLS` 升级 TLS（`trustAll` 配置项，默认 false），`USER`/`PASS`，`PBSZ 0`，`PROT P`，`TYPE I`。
- 列目录：`MLSD <dir>`；服务器返回 500/502 时回退 `LIST` 并解析 Unix 格式，解析失败的行跳过。
- 下载：`PASV` → 数据连接同样 TLS → `RETR <path>`，流式写入文件。
- 每次操作新建连接：调用频率极低，不值得处理 vsftpd 的 TLS session reuse 问题。
- 所有 socket 设置连接/读超时；控制连接异常即整体失败。
- 凭据使用独立配置项 `ftpRollbackUser` / `ftpRollbackPassword`，不复用玩家分享用的账号（该账号凭据已知会出现在游戏聊天记录中）。

### `RollbackFileOps` 的替换策略

选择**原地覆盖**而非"原子 move + 反射清理缓存"：

- region 文件被服务端的 `RegionFileCache` 以打开的文件句柄持有；`ATOMIC_MOVE` 会换 inode，旧句柄继续指向旧文件，导致后续写盘进入被替换掉的文件。
- 原地覆盖保持 inode 不变，`truncate + write + force` 后缓存句柄读到的就是新内容。
- 代价是写入中途崩溃会留下损坏文件，由 `.rollback-backup` 兜底恢复。

### 配置新增

`AzureConfig` 增加：

```java
String ftpRollbackUser = "";
String ftpRollbackPassword = "";
boolean ftpTrustAllCerts = false;
int rollbackTokenTtlSeconds = 300;
String rollbackKickMode = "teleport";   // teleport | kick

// world 名 -> 该世界 region 文件在 FTP 上的目录（相对 ftpRoot）
Map<String, String> rollbackWorlds = new HashMap<String, String>() {{
    put("MainLand", "MainLand/dimensions/minecraft/overworld/region");
    put("MainLand_nether", "MainLand_nether/dimensions/minecraft/the_nether/region");
    put("MainLand_the_end", "MainLand_the_end/dimensions/minecraft/the_end/region");
}};
```

- `ftpRoot` 指向备份根 `/backup/mc-debian`（部署时配置）。
- 历史版本目录 = `ftpRoot/_history/<rollbackWorlds[world]>/`。
- **本地**文件路径不依赖该映射，一律用 `World.getWorldFolder()/region/r.x.z.mca` 推导，避免 Paper 的 `dimensions/` 布局与插件配置不一致。

### 权限

`chunk.rollback`，`default: op`。控制台不受权限限制。

## 插件目录布局

所有回滚产物集中在数据目录下，方便人工查找：

```
plugins/ChunkDownloader/
├── rollback-tmp/                      ← 下载中的临时文件，回滚结束即删
└── rollback-backups/
    └── <world>/
        └── r.<x>.<z>.mca.<YYYYMMDD-HHMMSS>   ← 回滚前的原件，永不自动删除
```

`.rollback.pending` 标记仍写在 region 文件同目录（用于判断替换是否完成），启动时检查。

## 测试策略

与现有 JUnit 4 风格一致：

- `RegionPaths`：负坐标取整、world → 单元映射、文件名生成。
- `FtpsClient` 的 MLSD/LIST 解析与路径拼接（纯函数，不连网）。
- token 生命周期：过期、消费、UUID 不匹配、序号越界。
- `RollbackService` 状态机：通过接口注入 fake 的 `FtpsClient` / `RollbackFileOps`，覆盖"下载失败不封锁"、"残留区块中止"、"覆盖失败写回"三个分支。
- 真实 FTPS 集成验证放在实现计划的人工验收步骤，不写进自动化测试。

## 影响范围

- 新增：`RollbackCommand`、`RollbackService`、`FtpsClient`、`RollbackFileOps`、`RollbackLoginGuard`、`RegionPaths` 及对应测试。
- `ChunkDownloader.java`：注册命令与登录监听器，启动时清空 `rollback-tmp/` 并检查残留 `.pending` 标记。
- `AzureConfig.java`：新增回滚相关配置项。
- `CDCommand.java`：不改动。
- 默认配置下（未配置回滚账号）行为与当前完全一致，`/cd rollback` 提示未启用。
