# FTP 分享方式设计

## 背景

插件当前只支持 OneDrive 分享：`OneDriveUtil` 用 Azure AD client-credentials 换取 access token，调用 Microsoft Graph API 拿到某个文件的临时直链（`@microsoft.graph.downloadUrl`），再通过 `tellraw` 发一条可点击的链接给玩家。插件本身不存储文件，OneDrive 是唯一的“真实存储 + 分发”后端。

现在需要新增 FTP 作为第二种分享方式。FTP 服务本身由用户自行部署维护（不在本插件职责内），插件只负责在玩家查询区块时生成并发送对应的下载链接。

## 目标

- 新增 FTP 分享方式，与现有 OneDrive 分享方式二选一，通过配置切换。
- 玩家点击 `tellraw` 链接后，系统默认的 FTP 客户端（资源管理器 / FileZilla / WinSCP 等）尝试直接连接下载，链接中带用户名密码以争取免登录体验。
- 默认行为保持不变：不改配置的服务器,升级插件后依旧是 OneDrive 分享。

## 非目标 / 已知限制

- **不保证客户端自动登录**：`ftp://user:pass@host/path` 是否自动填充账号密码取决于玩家系统注册的协议处理程序。Windows 资源管理器在没有其他 FTP 客户端抢注协议时会自动登录；FileZilla 等专用客户端接管 `ftp://` 后，多数会忽略 URL 中的密码并弹框要求手动输入。这是客户端行为差异，插件无法控制。
- **不支持历史版本**：FTP 协议没有版本概念，FTP 模式下 `/cd my` 不显示历史版本列表（OneDrive 模式不受影响，继续显示）。
- **被动模式配置在服务端**：若 FTP 服务器部署在 NAT/云主机后，需要服务端自行开放被动模式端口范围，插件的 `ftpPassive` 配置项只影响生成链接的语义，不代表插件会去连接或校验 FTP 服务可达性。
- **密码明文暴露**：链接中的密码会出现在游戏聊天记录和客户端日志文件中。已确认该 FTP 账号为**只读账号**，服务端应确保该账号仅有列目录和下载权限，不具备写权限。

## 配置设计

在 `AzureConfig`（`src/main/java/org/xjcraft/chunkdownloader/config/AzureConfig.java`）中新增：

```java
public enum ShareMethod { ONEDRIVE, FTP }

ShareMethod shareMethod = ShareMethod.ONEDRIVE; // 默认保持现状，不破坏现有部署

String ftpHost = "";
int ftpPort = 21;
String ftpUser = "";
String ftpPassword = "";
String ftpRoot = "";       // FTP 上的路径前缀；留空表示与 OneDrive 使用相同的相对路径结构
boolean ftpPassive = true; // 语义标记，供未来扩展/文档说明用，当前实现不影响 URL 拼装
```

现有的 `worlds`（world 名 → 路径模板）与 `fname`（文件名模板）字段两种分享方式共用，不需要为 FTP 单独维护一套路径映射。

## 链接拼装：`FtpUtil`

新增 `src/main/java/org/xjcraft/chunkdownloader/FtpUtil.java`，职责仅为字符串拼装，不引入任何 FTP 客户端依赖（插件不需要真正连接 FTP 服务）：

```java
public static String buildUrl(String relativePath)
```

实现要点：

1. 用户名、密码做 URL 编码（`URLEncoder.encode(..., "UTF-8")`，并将编码结果中的 `+` 替换为 `%20`），避免密码中的 `@ : / # ?` 等字符破坏 URL 结构。
2. 拼接前统一处理斜杠：去除 `ftpRoot` 结尾多余的 `/`、去除 `relativePath` 开头多余的 `/`，避免拼出 `//`。
3. 最终格式：`ftp://<user>:<pass>@<host>:<port>/<root>/<relativePath>`（`ftpRoot` 为空时省略该段）。

## 命令改动：`CDCommand`

`CDCommand.get()`（`src/main/java/org/xjcraft/chunkdownloader/CDCommand.java`）保持现有的异步包装结构（`runTaskAsynchronously` + `runTask` 回调），在原第 40 行 `OneDriveUtil.getFileDownloadUrl(path)` 处按配置分叉：

- `ShareMethod.ONEDRIVE`：现有逻辑不变，包括历史版本查询与展示。
- `ShareMethod.FTP`：调用 `FtpUtil.buildUrl(path)`（同步计算，无需网络请求），跳过历史版本查询和展示部分。

选择保留统一的异步包装而非拆分为两个方法，原因：FTP 分支虽然不需要异步，但多开一个线程池任务对性能无实质影响，而保留统一结构能让本次改动的 diff 更小、更聚焦。

## 影响范围

- `AzureConfig.java`：新增字段，无破坏性变更。
- 新增 `FtpUtil.java`。
- `CDCommand.java`：`get()` 方法内增加一处分支判断。
- `OneDriveUtil.java`：不改动。
- 默认配置下（`shareMethod = ONEDRIVE`）行为与当前完全一致。
