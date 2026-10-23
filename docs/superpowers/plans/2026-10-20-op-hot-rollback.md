# OP 热回滚 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 让 OP 通过 `/cd rollback` 把当前所在（或指定的）region 文件热回滚到 FTPS 备份中的某个历史版本，全程不停服。

**Architecture:** 分两段执行。阶段 A（异步）用 FTPS 列 `_history/<单元>/<region目录>/` 下的版本文件，聊天输出可点击列表，选中的版本下载到插件目录；阶段 B（主线程，全局短锁）拦截登录、踢出目标区域玩家、保存并卸载该 region 的全部区块，把原文件复制到插件目录保管，然后原地覆盖 region 文件并 fsync。

**Tech Stack:** Java 8 源码级别（编译机 JDK 21，服务器 Java 25）、Spigot API 26.2、SimpleSpigot `@RCommand` 命令框架、JUnit 4.13.2、Maven shade。FTPS 用 JDK `Socket` + `SSLSocket` 手写，无第三方依赖。

**参考规格:** `docs/superpowers/specs/2026-10-19-op-hot-rollback-design.md`

**Maven 调用（所有任务统一）:**

```bash
MVN="$HOME/.m2/wrapper/dists/apache-maven-3.9.1-bin/2cpar40ui5s2pm3ja4s69ub7fa/apache-maven-3.9.1/bin/mvn"
cd "D:/Onedrive/ProjectsPrivate/ChunkDownloader"
```

---

## File Map

| 文件 | 动作 | 职责 |
|---|---|---|
| `src/main/java/org/xjcraft/chunkdownloader/config/AzureConfig.java` | 修改 | 新增回滚账号、信任自签证书、token TTL、踢人模式、`rollbackWorlds` |
| `src/main/resources/plugin.yml` | 修改 | 新增 `chunk.rollback` 权限（`default: op`） |
| `src/main/java/org/xjcraft/chunkdownloader/rollback/RegionPaths.java` | 新建 | 坐标 → region 坐标/文件名（纯函数） |
| `src/main/java/org/xjcraft/chunkdownloader/rollback/RemoteEntry.java` | 新建 | 远端版本条目：文件名、版本戳、大小、远端 mtime |
| `src/main/java/org/xjcraft/chunkdownloader/rollback/FtpListing.java` | 新建 | MLSD/LIST 行解析（纯函数，可单测） |
| `src/main/java/org/xjcraft/chunkdownloader/rollback/FtpsClient.java` | 新建 | FTPS 显式 TLS 控制通道、列目录、RETR 下载 |
| `src/main/java/org/xjcraft/chunkdownloader/rollback/RollbackTokenStore.java` | 新建 | 一次性 token 的创建/校验/消费/过期 |
| `src/main/java/org/xjcraft/chunkdownloader/rollback/RollbackFiles.java` | 新建 | 插件目录布局、原文件复制、原地覆盖、fsync、残留清理 |
| `src/main/java/org/xjcraft/chunkdownloader/rollback/RollbackService.java` | 新建 | 阶段 A/B 编排、全局锁状态、失败恢复 |
| `src/main/java/org/xjcraft/chunkdownloader/rollback/RollbackLoginGuard.java` | 新建 | `PlayerLoginEvent` 监听器 |
| `src/main/java/org/xjcraft/chunkdownloader/CDCommand.java` | 修改 | 新增 `rollback` / `confirm` / 坐标 三个命令方法 |
| `src/main/java/org/xjcraft/chunkdownloader/ChunkDownloader.java` | 修改 | 装配 service、注册监听器、启动清理 |
| `src/test/java/org/xjcraft/chunkdownloader/rollback/RegionPathsTest.java` | 新建 | 坐标/文件名测试 |
| `src/test/java/org/xjcraft/chunkdownloader/rollback/FtpListingTest.java` | 新建 | MLSD/LIST 解析测试 |
| `src/test/java/org/xjcraft/chunkdownloader/rollback/RollbackTokenStoreTest.java` | 新建 | token 生命周期测试 |

包名统一为 `org.xjcraft.chunkdownloader.rollback`。`CDCommand` 仍在原包。

---

## Task 1: 配置项与权限

**Files:**
- Modify: `src/main/java/org/xjcraft/chunkdownloader/config/AzureConfig.java`
- Modify: `src/main/resources/plugin.yml`

- [ ] **Step 1: 在 `AzureConfig` 末尾追加回滚配置**

在 `boolean ftpPassive = true;` 之后、类结束大括号之前插入：

```java
    // ---- OP hot rollback ----
    String ftpRollbackUser = "";
    String ftpRollbackPassword = "";
    // vsftpd 多用自签证书；仅在内网可信环境打开
    boolean ftpTrustAllCerts = false;
    int rollbackTokenTtlSeconds = 300;
    // teleport: 传送到同世界出生点; kick: 直接断开
    String rollbackKickMode = "teleport";

    // world 名 -> 该世界 region 文件在 FTP 上的目录（相对 ftpRoot）
    Map<String, String> rollbackWorlds = new HashMap<String, String>() {{
        put("MainLand", "MainLand/dimensions/minecraft/overworld/region");
        put("MainLand_nether", "MainLand_nether/dimensions/minecraft/the_nether/region");
        put("MainLand_the_end", "MainLand_the_end/dimensions/minecraft/the_end/region");
    }};
```

`Map`/`HashMap` 已在该文件导入，无需新增 import。

- [ ] **Step 2: 在 `plugin.yml` 的 permissions 下新增权限**

```yaml
  chunk.rollback:
    description: Allow OP to hot-rollback a region file from FTPS backups
    default: op
```

- [ ] **Step 3: 编译验证**

Run:

```bash
MVN="$HOME/.m2/wrapper/dists/apache-maven-3.9.1-bin/2cpar40ui5s2pm3ja4s69ub7fa/apache-maven-3.9.1/bin/mvn"
cd "D:/Onedrive/ProjectsPrivate/ChunkDownloader"
"$MVN" -o -q compile
```

Expected: 退出码 0，无输出。

- [ ] **Step 4: 提交**

```bash
git add src/main/java/org/xjcraft/chunkdownloader/config/AzureConfig.java src/main/resources/plugin.yml
git commit -m "feat: add hot rollback config fields and chunk.rollback permission"
```

---

## Task 2: `RegionPaths`（TDD）

**Files:**
- Create: `src/test/java/org/xjcraft/chunkdownloader/rollback/RegionPathsTest.java`
- Create: `src/main/java/org/xjcraft/chunkdownloader/rollback/RegionPaths.java`

- [ ] **Step 1: 写失败的测试**

```java
package org.xjcraft.chunkdownloader.rollback;

import org.junit.Test;

import static org.junit.Assert.assertEquals;

public class RegionPathsTest {

    @Test
    public void positiveChunkMapsToRegion() {
        assertEquals(0, RegionPaths.regionOf(31));
        assertEquals(1, RegionPaths.regionOf(32));
    }

    @Test
    public void negativeChunkFloors() {
        // chunk -1 属于 region -1；chunk -32 也属于 region -1；chunk -33 属于 region -2
        assertEquals(-1, RegionPaths.regionOf(-1));
        assertEquals(-1, RegionPaths.regionOf(-32));
        assertEquals(-2, RegionPaths.regionOf(-33));
    }

    @Test
    public void fileNameUsesRegionCoords() {
        assertEquals("r.0.-1.mca", RegionPaths.regionFileName(0, -1));
        assertEquals("r.-2.3.mca", RegionPaths.regionFileName(-2, 3));
    }

    @Test
    public void versionSuffixIsParsed() {
        assertEquals("20261018-2000",
                RegionPaths.versionStamp("r.0.-1.mca", "r.0.-1.mca.20261018-2000"));
        assertEquals(null,
                RegionPaths.versionStamp("r.0.-1.mca", "r.0.-2.mca.20261018-2000"));
        assertEquals(null,
                RegionPaths.versionStamp("r.0.-1.mca", "r.0.-1.mca.20261018-2000.partial"));
        assertEquals(null,
                RegionPaths.versionStamp("r.0.-1.mca", "r.0.-1.mca"));
    }
}
```

- [ ] **Step 2: 运行测试确认失败**

```bash
MVN="$HOME/.m2/wrapper/dists/apache-maven-3.9.1-bin/2cpar40ui5s2pm3ja4s69ub7fa/apache-maven-3.9.1/bin/mvn"
cd "D:/Onedrive/ProjectsPrivate/ChunkDownloader"
"$MVN" -o test -Dtest=RegionPathsTest 2>&1 | grep -E "ERROR|BUILD" | head
```

Expected: BUILD FAILURE，`package org.xjcraft.chunkdownloader.rollback does not exist`。

- [ ] **Step 3: 实现**

```java
package org.xjcraft.chunkdownloader.rollback;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 纯坐标/文件名换算，不依赖 Bukkit，便于单测。
 */
public final class RegionPaths {

    private static final Pattern STAMP =
            Pattern.compile("^(.*\\.mca)\\.(\\d{8}-\\d{4})$");

    private RegionPaths() {
    }

    /** 区块坐标 -> region 坐标（32 区块一个 region，负数向下取整）。 */
    public static int regionOf(int chunkCoord) {
        return chunkCoord >> 5;
    }

    public static String regionFileName(int regionX, int regionZ) {
        return "r." + regionX + "." + regionZ + ".mca";
    }

    /**
     * 从 _history 下的远端文件名解析版本戳（YYYYMMDD-HHMM）。
     * 不是该 region 的版本、或带 .partial 等后缀时返回 null。
     */
    public static String versionStamp(String regionFileName, String remoteFileName) {
        Matcher m = STAMP.matcher(remoteFileName);
        if (!m.matches() || !m.group(1).equals(regionFileName)) {
            return null;
        }
        return m.group(2);
    }
}
```

- [ ] **Step 4: 运行测试确认通过**

```bash
MVN="$HOME/.m2/wrapper/dists/apache-maven-3.9.1-bin/2cpar40ui5s2pm3ja4s69ub7fa/apache-maven-3.9.1/bin/mvn"
cd "D:/Onedrive/ProjectsPrivate/ChunkDownloader"
"$MVN" -o test -Dtest=RegionPathsTest 2>&1 | grep -E "Tests run|BUILD" | head
```

Expected: `Tests run: 4, Failures: 0, Errors: 0`、BUILD SUCCESS。

- [ ] **Step 5: 提交**

```bash
git add src/main/java/org/xjcraft/chunkdownloader/rollback/RegionPaths.java \
        src/test/java/org/xjcraft/chunkdownloader/rollback/RegionPathsTest.java
git commit -m "feat: add RegionPaths coordinate and version-stamp helpers"
```

---

## Task 3: MLSD / LIST 解析（TDD）

**Files:**
- Create: `src/test/java/org/xjcraft/chunkdownloader/rollback/FtpListingTest.java`
- Create: `src/main/java/org/xjcraft/chunkdownloader/rollback/RemoteEntry.java`
- Create: `src/main/java/org/xjcraft/chunkdownloader/rollback/FtpListing.java`

- [ ] **Step 1: 写失败的测试**

```java
package org.xjcraft.chunkdownloader.rollback;

import org.junit.Test;

import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;

public class FtpListingTest {

    @Test
    public void parsesMlsdFile() {
        RemoteEntry e = FtpListing.parseMlsdLine(
                "type=file;size=1024;modify=20261018200000; r.0.-1.mca.20261018-2000");
        assertEquals("r.0.-1.mca.20261018-2000", e.getName());
        assertEquals(1024L, e.getSize());
        assertEquals("20261018200000", e.getModify());
    }

    @Test
    public void parsesMlsdFileNameWithSpaces() {
        RemoteEntry e = FtpListing.parseMlsdLine(
                "type=file;size=1; my backup file.mca.20261018-2000");
        assertEquals("my backup file.mca.20261018-2000", e.getName());
    }

    @Test
    public void ignoresMlsdDirectories() {
        assertNull(FtpListing.parseMlsdLine(
                "type=dir;modify=20261018200000; .hist"));
    }

    @Test
    public void parsesUnixListLine() {
        RemoteEntry e = FtpListing.parseListLine(
                "-rw-r--r-- 1 reezhu reezhu 1048576 Oct 18 20:00 r.0.-1.mca.20261018-2000");
        assertEquals("r.0.-1.mca.20261018-2000", e.getName());
        assertEquals(1048576L, e.getSize());
    }

    @Test
    public void ignoresListDirectories() {
        assertNull(FtpListing.parseListLine(
                "drwxr-xr-x 2 reezhu reezhu 4096 Oct 18 20:00 _history"));
    }

    @Test
    public void versionStampsAreSortedDescending() {
        List<String> stamps = FtpListing.sortStampsDescending(
                new String[]{"20261018-2000", "20261019-0000", "20261017-1600"});
        assertEquals(3, stamps.size());
        assertEquals("20261019-0000", stamps.get(0));
        assertEquals("20261018-2000", stamps.get(1));
        assertEquals("20261017-1600", stamps.get(2));
    }
}
```

- [ ] **Step 2: 运行测试确认失败**

```bash
MVN="$HOME/.m2/wrapper/dists/apache-maven-3.9.1-bin/2cpar40ui5s2pm3ja4s69ub7fa/apache-maven-3.9.1/bin/mvn"
cd "D:/Onedrive/ProjectsPrivate/ChunkDownloader"
"$MVN" -o test -Dtest=FtpListingTest 2>&1 | grep -E "ERROR|BUILD" | head
```

Expected: BUILD FAILURE，找不到 `FtpListing` / `RemoteEntry`。

- [ ] **Step 3: 实现 `RemoteEntry`**

```java
package org.xjcraft.chunkdownloader.rollback;

import lombok.AllArgsConstructor;
import lombok.Data;

@Data
@AllArgsConstructor
public class RemoteEntry {
    private String name;
    private long size;
    /** MLSD 的 modify 字段原样保留；LIST 回退时为 null。 */
    private String modify;
}
```

- [ ] **Step 4: 实现 `FtpListing`**

```java
package org.xjcraft.chunkdownloader.rollback;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

/**
 * MLSD / LIST 响应行解析。纯函数，发版前用单测覆盖格式差异。
 */
public final class FtpListing {

    private FtpListing() {
    }

    /** 例: type=file;size=1024;modify=20261018200000; r.0.-1.mca.20261018-2000 */
    public static RemoteEntry parseMlsdLine(String line) {
        int sp = line.indexOf(' ');
        if (sp <= 0) {
            return null;
        }
        String facts = line.substring(0, sp);
        String name = line.substring(sp + 1);
        long size = 0;
        String modify = null;
        boolean isDir = false;
        for (String fact : facts.split(";")) {
            int eq = fact.indexOf('=');
            if (eq < 0) {
                continue;
            }
            String key = fact.substring(0, eq).toLowerCase();
            String value = fact.substring(eq + 1);
            if ("type".equals(key)) {
                isDir = "dir".equalsIgnoreCase(value);
            } else if ("size".equals(key)) {
                try {
                    size = Long.parseLong(value);
                } catch (NumberFormatException ignored) {
                    size = 0;
                }
            } else if ("modify".equals(key)) {
                modify = value;
            }
        }
        if (isDir || name.isEmpty()) {
            return null;
        }
        return new RemoteEntry(name, size, modify);
    }

    /**
     * 例: -rw-r--r-- 1 reezhu reezhu 1048576 Oct 18 20:00 r.0.-1.mca.20261018-2000
     * 只取「权限 / 大小 / 名称」三处，其余字段顺序按 Unix 惯例忽略。
     */
    public static RemoteEntry parseListLine(String line) {
        if (line.isEmpty() || line.charAt(0) == 'd' || line.charAt(0) == 'l') {
            return null;
        }
        String[] parts = line.split("\\s+", 9);
        if (parts.length < 9) {
            return null;
        }
        long size;
        try {
            size = Long.parseLong(parts[4]);
        } catch (NumberFormatException e) {
            return null;
        }
        return new RemoteEntry(parts[8], size, null);
    }

    public static List<String> sortStampsDescending(String[] stamps) {
        List<String> list = new ArrayList<>(Arrays.asList(stamps));
        // YYYYMMDD-HHMM 定长且字典序等于时间序
        Collections.sort(list, Collections.reverseOrder());
        return list;
    }
}
```

- [ ] **Step 5: 运行测试确认通过**

```bash
MVN="$HOME/.m2/wrapper/dists/apache-maven-3.9.1-bin/2cpar40ui5s2pm3ja4s69ub7fa/apache-maven-3.9.1/bin/mvn"
cd "D:/Onedrive/ProjectsPrivate/ChunkDownloader"
"$MVN" -o test -Dtest=FtpListingTest 2>&1 | grep -E "Tests run|BUILD" | head
```

Expected: `Tests run: 6, Failures: 0, Errors: 0`、BUILD SUCCESS。

- [ ] **Step 6: 提交**

```bash
git add src/main/java/org/xjcraft/chunkdownloader/rollback/RemoteEntry.java \
        src/main/java/org/xjcraft/chunkdownloader/rollback/FtpListing.java \
        src/test/java/org/xjcraft/chunkdownloader/rollback/FtpListingTest.java
git commit -m "feat: add MLSD/LIST parsing for FTPS history listing"
```

---

## Task 4: token 存储（TDD）

**Files:**
- Create: `src/test/java/org/xjcraft/chunkdownloader/rollback/RollbackTokenStoreTest.java`
- Create: `src/main/java/org/xjcraft/chunkdownloader/rollback/RollbackTokenStore.java`

- [ ] **Step 1: 写失败的测试**

```java
package org.xjcraft.chunkdownloader.rollback;

import org.junit.Test;

import java.util.Arrays;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;

public class RollbackTokenStoreTest {

    private RollbackTokenStore newStore() {
        return new RollbackTokenStore(60);
    }

    private RollbackTokenStore.Entry issue(RollbackTokenStore store, String owner, long now) {
        return store.create(owner, "MainLand", 0, -1, "r.0.-1.mca",
                Arrays.asList("20261018-2000", "20261017-1600"), now);
    }

    @Test
    public void createdTokenCanBeTakenOnce() {
        RollbackTokenStore store = newStore();
        RollbackTokenStore.Entry issued = issue(store, "uuid-1", 0);
        RollbackTokenStore.Entry taken =
                store.take(issued.getToken(), "uuid-1", 0, 10);
        assertEquals("20261017-1600", taken.stampAt(1));
        assertNull(store.take(issued.getToken(), "uuid-1", 0, 11));
    }

    @Test
    public void tokenRejectsOtherOwner() {
        RollbackTokenStore store = newStore();
        RollbackTokenStore.Entry issued = issue(store, "uuid-1", 0);
        assertNull(store.take(issued.getToken(), "uuid-2", 0, 1));
    }

    @Test
    public void tokenExpires() {
        RollbackTokenStore store = newStore();
        RollbackTokenStore.Entry issued = issue(store, "uuid-1", 0);
        assertNull(store.take(issued.getToken(), "uuid-1", 61, 10));
    }

    @Test
    public void indexOutOfRangeIsRejected() {
        RollbackTokenStore store = newStore();
        RollbackTokenStore.Entry issued = issue(store, "uuid-1", 0);
        RollbackTokenStore.Entry taken =
                store.take(issued.getToken(), "uuid-1", 0, 10);
        assertNull(taken.stampAt(-1));
        assertNull(taken.stampAt(2));
    }
}
```

- [ ] **Step 2: 运行测试确认失败**

```bash
MVN="$HOME/.m2/wrapper/dists/apache-maven-3.9.1-bin/2cpar40ui5s2pm3ja4s69ub7fa/apache-maven-3.9.1/bin/mvn"
cd "D:/Onedrive/ProjectsPrivate/ChunkDownloader"
"$MVN" -o test -Dtest=RollbackTokenStoreTest 2>&1 | grep -E "ERROR|BUILD" | head
```

Expected: BUILD FAILURE，找不到 `RollbackTokenStore`。

- [ ] **Step 3: 实现**

```java
package org.xjcraft.chunkdownloader.rollback;

import lombok.Data;

import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 一次性 token：绑定发起者、目标 region 和版本列表快照，消费即失效。
 * 时间以秒为单位由调用方传入，便于测试。
 */
public class RollbackTokenStore {

    private static final String ALPHABET =
            "abcdefghijklmnopqrstuvwxyz0123456789";
    private static final int TOKEN_LENGTH = 32;

    private final int ttlSeconds;
    private final SecureRandom random = new SecureRandom();
    private final Map<String, Entry> tokens = new ConcurrentHashMap<String, Entry>();

    public RollbackTokenStore(int ttlSeconds) {
        this.ttlSeconds = ttlSeconds;
    }

    public Entry create(String ownerId, String world, int regionX, int regionZ,
                        String regionFileName, List<String> stamps, long nowSeconds) {
        purgeExpired(nowSeconds);
        StringBuilder sb = new StringBuilder(TOKEN_LENGTH);
        for (int i = 0; i < TOKEN_LENGTH; i++) {
            sb.append(ALPHABET.charAt(random.nextInt(ALPHABET.length())));
        }
        Entry entry = new Entry();
        entry.setToken(sb.toString());
        entry.setOwnerId(ownerId);
        entry.setWorld(world);
        entry.setRegionX(regionX);
        entry.setRegionZ(regionZ);
        entry.setRegionFileName(regionFileName);
        entry.setStamps(new ArrayList<String>(stamps));
        entry.setExpiresAt(nowSeconds + ttlSeconds);
        tokens.put(entry.getToken(), entry);
        return entry;
    }

    /** 取用并删除。token 不存在、已过期或发起者不符时返回 null。 */
    public Entry take(String token, String ownerId, long nowSeconds, int index) {
        Entry entry = tokens.get(token);
        if (entry == null) {
            return null;
        }
        if (entry.getExpiresAt() < nowSeconds
                || !entry.getOwnerId().equals(ownerId)
                || entry.stampAt(index) == null) {
            return null;
        }
        // 校验通过才消费：越界/过期/冒用都不消耗 token
        tokens.remove(token);
        return entry;
    }

    public void removeByOwner(String ownerId) {
        for (Iterator<Map.Entry<String, Entry>> it = tokens.entrySet().iterator(); it.hasNext(); ) {
            if (it.next().getValue().getOwnerId().equals(ownerId)) {
                it.remove();
            }
        }
    }

    private void purgeExpired(long nowSeconds) {
        for (Iterator<Map.Entry<String, Entry>> it = tokens.entrySet().iterator(); it.hasNext(); ) {
            if (it.next().getValue().getExpiresAt() < nowSeconds) {
                it.remove();
            }
        }
    }

    @Data
    public static class Entry {
        private String token;
        private String ownerId;
        private String world;
        private int regionX;
        private int regionZ;
        private String regionFileName;
        private List<String> stamps;
        private long expiresAt;

        public String stampAt(int index) {
            if (stamps == null || index < 0 || index >= stamps.size()) {
                return null;
            }
            return stamps.get(index);
        }
    }
}
```

- [ ] **Step 4: 运行测试确认通过**

```bash
MVN="$HOME/.m2/wrapper/dists/apache-maven-3.9.1-bin/2cpar40ui5s2pm3ja4s69ub7fa/apache-maven-3.9.1/bin/mvn"
cd "D:/Onedrive/ProjectsPrivate/ChunkDownloader"
"$MVN" -o test -Dtest=RollbackTokenStoreTest 2>&1 | grep -E "Tests run|BUILD" | head
```

Expected: `Tests run: 4, Failures: 0, Errors: 0`、BUILD SUCCESS。

- [ ] **Step 5: 提交**

```bash
git add src/main/java/org/xjcraft/chunkdownloader/rollback/RollbackTokenStore.java \
        src/test/java/org/xjcraft/chunkdownloader/rollback/RollbackTokenStoreTest.java
git commit -m "feat: add one-shot rollback token store"
```

---

## Task 5: `FtpsClient`

**Files:**
- Create: `src/main/java/org/xjcraft/chunkdownloader/rollback/FtpsClient.java`

无单测：网络协议代码用真实服务器验收（Task 10）。解析逻辑已在 Task 3 覆盖。

- [ ] **Step 1: 实现**

```java
package org.xjcraft.chunkdownloader.rollback;

import org.xjcraft.chunkdownloader.config.AzureConfig;

import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLSocket;
import javax.net.ssl.TrustManager;
import javax.net.ssl.X509TrustManager;
import java.io.BufferedReader;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.io.OutputStreamWriter;
import java.io.Writer;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.cert.X509Certificate;
import java.util.ArrayList;
import java.util.List;

/**
 * 极简 FTPS（显式 TLS / AUTH TLS + PROT P）客户端。
 * 只实现回滚需要的 LIST 与 RETR；每次操作新建连接。
 */
public class FtpsClient {

    private static final int CONNECT_TIMEOUT_MS = 10000;
    private static final int READ_TIMEOUT_MS = 30000;

    private final String host;
    private final int port;
    private final String user;
    private final String password;
    private final boolean trustAll;

    public FtpsClient(AzureConfig cfg) {
        this(cfg.getFtpHost(), cfg.getFtpPort(),
                cfg.getFtpRollbackUser(), cfg.getFtpRollbackPassword(),
                cfg.isFtpTrustAllCerts());
    }

    public FtpsClient(String host, int port, String user, String password,
                      boolean trustAll) {
        this.host = host;
        this.port = port;
        this.user = user;
        this.password = password;
        this.trustAll = trustAll;
    }

    public boolean isConfigured() {
        return host != null && !host.isEmpty()
                && user != null && !user.isEmpty()
                && password != null && !password.isEmpty();
    }

    /** 列目录。优先 MLSD，服务器不支持时回退 LIST。 */
    public List<RemoteEntry> list(String remoteDir) throws IOException {
        Session s = connect();
        try {
            String mlsd;
            try {
                mlsd = s.send("MLSD " + remoteDir);
            } catch (FtpReplyException e) {
                if (e.getCode() != 500 && e.getCode() != 502) {
                    throw e;
                }
                mlsd = null;
            }
            if (mlsd != null) {
                return parseAll(mlsd, true);
            }
            return parseAll(s.send("LIST " + remoteDir), false);
        } finally {
            s.close();
        }
    }

    /** 下载到本地文件，返回实际字节数。 */
    public long download(String remotePath, File target) throws IOException {
        Session s = connect();
        FileOutputStream out = null;
        try {
            String reply = s.send("PASV");
            int dataPort = parsePasvPort(reply);
            // 数据连接必须复用控制连接的主机：NAT 后的服务器会在 227 里
            // 返回内网地址，直接连那个地址从公网不可达。
            Socket data = new Socket();
            data.connect(new InetSocketAddress(host, dataPort), CONNECT_TIMEOUT_MS);
            data.setSoTimeout(READ_TIMEOUT_MS);
            SSLSocket tls = wrap(data);
            s.send("RETR " + remotePath);
            out = new FileOutputStream(target);
            long total = copy(tls, out);
            tls.close();
            s.expectFinal();
            return total;
        } finally {
            if (out != null) {
                try {
                    out.close();
                } catch (IOException ignored) {
                }
            }
            s.close();
        }
    }

    private List<RemoteEntry> parseAll(String reply, boolean mlsd) {
        List<RemoteEntry> result = new ArrayList<>();
        for (String line : reply.split("\r?\n")) {
            RemoteEntry e = mlsd
                    ? FtpListing.parseMlsdLine(line)
                    : FtpListing.parseListLine(line);
            if (e != null) {
                result.add(e);
            }
        }
        return result;
    }

    private Session connect() throws IOException {
        Socket raw = new Socket();
        raw.connect(new InetSocketAddress(host, port), CONNECT_TIMEOUT_MS);
        raw.setSoTimeout(READ_TIMEOUT_MS);
        Session s = new Session(raw);
        try {
            s.expectFinal();                    // 220
            s.send("AUTH TLS");                 // 234
            SSLSocket tls = wrap(raw);
            s.adopt(tls);
            s.send("USER " + user);
            s.send("PASS " + password);
            s.send("PBSZ 0");
            s.send("PROT P");
            // 必须二进制模式：ASCII 模式会改行尾，直接损坏 .mca
            s.send("TYPE I");
            return s;
        } catch (IOException e) {
            s.close();
            throw e;
        }
    }

    private SSLSocket wrap(Socket socket) throws IOException {
        try {
            SSLContext ctx = trustAll
                    ? insecureContext()
                    : SSLContext.getDefault();
            SSLSocket tls = (SSLSocket) ctx.getSocketFactory()
                    .createSocket(socket, host, socket.getPort(), true);
            tls.setUseClientMode(true);
            tls.startHandshake();
            tls.setSoTimeout(READ_TIMEOUT_MS);
            return tls;
        } catch (GeneralSecurityException e) {
            throw new IOException("TLS 初始化失败", e);
        }
    }

    private static SSLContext insecureContext() throws GeneralSecurityException {
        TrustManager[] trustAllManager = new TrustManager[]{new X509TrustManager() {
            @Override
            public void checkClientTrusted(X509Certificate[] chain, String authType) {
            }

            @Override
            public void checkServerTrusted(X509Certificate[] chain, String authType) {
            }

            @Override
            public X509Certificate[] getAcceptedIssuers() {
                return new X509Certificate[0];
            }
        }};
        SSLContext ctx = SSLContext.getInstance("TLS");
        ctx.init(null, trustAllManager, null);
        return ctx;
    }

    private static long copy(InputStream in, OutputStream out) throws IOException {
        byte[] buf = new byte[64 * 1024];
        long total = 0;
        int n;
        while ((n = in.read(buf)) > 0) {
            out.write(buf, 0, n);
            total += n;
        }
        return total;
    }

    /** 227 Entering Passive Mode (h1,h2,h3,h4,p1,p2). 只取端口，主机用控制连接的主机。 */
    static int parsePasvPort(String reply) throws IOException {
        int open = reply.indexOf('(');
        int close = reply.indexOf(')', open);
        if (open < 0 || close < 0) {
            throw new IOException("无法解析 PASV 响应: " + reply);
        }
        String[] parts = reply.substring(open + 1, close).split(",");
        if (parts.length != 6) {
            throw new IOException("无法解析 PASV 响应: " + reply);
        }
        try {
            int p1 = Integer.parseInt(parts[4].trim());
            int p2 = Integer.parseInt(parts[5].trim());
            return p1 * 256 + p2;
        } catch (NumberFormatException e) {
            throw new IOException("无法解析 PASV 端口: " + reply, e);
        }
    }

    private static class Session {
        private Socket socket;
        private BufferedReader reader;
        private Writer writer;

        Session(Socket socket) throws IOException {
            adopt(socket);
        }

        void adopt(Socket socket) throws IOException {
            if (this.reader != null) {
                try {
                    this.reader.close();
                } catch (IOException ignored) {
                }
            }
            this.socket = socket;
            this.reader = new BufferedReader(
                    new InputStreamReader(socket.getInputStream(), StandardCharsets.ISO_8859_1));
            this.writer = new OutputStreamWriter(
                    socket.getOutputStream(), StandardCharsets.ISO_8859_1);
        }

        String send(String command) throws IOException {
            writer.write(command + "\r\n");
            writer.flush();
            return check(readReply());
        }

        /** 读一条（可能多行）响应，2xx/3xx 视为成功。 */
        String expectFinal() throws IOException {
            return check(readReply());
        }

        private String check(String reply) throws IOException {
            int code = Integer.parseInt(reply.substring(0, 3));
            if (code >= 400) {
                throw new FtpReplyException(code, reply.trim());
            }
            return reply;
        }

        private String readReply() throws IOException {
            StringBuilder sb = new StringBuilder();
            String line = reader.readLine();
            if (line == null) {
                throw new IOException("FTPS 控制连接已关闭");
            }
            sb.append(line).append('\n');
            if (line.length() > 3 && line.charAt(3) == '-') {
                String code = line.substring(0, 3);
                while (true) {
                    String next = reader.readLine();
                    if (next == null) {
                        throw new IOException("FTPS 控制连接提前关闭");
                    }
                    sb.append(next).append('\n');
                    if (next.startsWith(code + " ")) {
                        break;
                    }
                }
            }
            return sb.toString();
        }

        void close() {
            try {
                if (writer != null) {
                    writer.write("QUIT\r\n");
                    writer.flush();
                }
            } catch (IOException ignored) {
            }
            try {
                socket.close();
            } catch (IOException ignored) {
            }
        }
    }

    public static class FtpReplyException extends IOException {
        private final int code;

        FtpReplyException(int code, String message) {
            super(message);
            this.code = code;
        }

        public int getCode() {
            return code;
        }
    }
}
```

注意：`Session` 里 `s.send("RETR ...")` 后由 `s.expectFinal()` 收尾；该方法在上面 `download` 中被调用（名称为 `expectFinal`）。两个方法名保持一致，不要改成 `expectFinalNoData`，`expectFinalNoData` 保留给未来复用，若未使用可删除该方法。

- [ ] **Step 2: 编译验证**

```bash
MVN="$HOME/.m2/wrapper/dists/apache-maven-3.9.1-bin/2cpar40ui5s2pm3ja4s69ub7fa/apache-maven-3.9.1/bin/mvn"
cd "D:/Onedrive/ProjectsPrivate/ChunkDownloader"
"$MVN" -o -q compile
```

Expected: 退出码 0。

- [ ] **Step 3: 提交**

```bash
git add src/main/java/org/xjcraft/chunkdownloader/rollback/FtpsClient.java
git commit -m "feat: add minimal explicit-TLS FTPS client for listing and download"
```

---

## Task 6: `RollbackFiles`

**Files:**
- Create: `src/main/java/org/xjcraft/chunkdownloader/rollback/RollbackFiles.java`

无单测：直接操作真实文件系统，由 Task 10 的验收覆盖；纯路径运算已由 `RegionPaths` 覆盖。

- [ ] **Step 1: 实现**

```java
package org.xjcraft.chunkdownloader.rollback;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.StandardOpenOption;
import java.text.SimpleDateFormat;
import java.util.Date;

/**
 * 插件目录布局与 region 文件落盘操作。
 *
 * plugins/ChunkDownloader/
 *   rollback-tmp/                 下载中的临时文件
 *   rollback-backups/<world>/     回滚前原件
 */
public class RollbackFiles {

    private final File dataFolder;

    public RollbackFiles(File dataFolder) {
        this.dataFolder = dataFolder;
    }

    public File tmpDir() {
        return ensureDir(new File(dataFolder, "rollback-tmp"));
    }

    public File backupDir(String world) {
        return ensureDir(new File(new File(dataFolder, "rollback-backups"), world));
    }

    public File newTmpFile(String suffix) {
        return new File(tmpDir(), ".rollback-" + System.nanoTime() + "-" + suffix + ".tmp");
    }

    public File backupCopy(File regionFile, String world) throws IOException {
        String ts = new SimpleDateFormat("yyyyMMdd-HHmmss").format(new Date());
        File target = new File(backupDir(world), regionFile.getName() + "." + ts);
        copy(regionFile, target);
        return target;
    }

    /** 原地覆盖（保持 inode，服务端缓存的 region 句柄继续有效）。 */
    public void overwriteInPlace(File source, File regionFile) throws IOException {
        FileChannel ch = FileChannel.open(regionFile.toPath(),
                StandardOpenOption.WRITE, StandardOpenOption.TRUNCATE_EXISTING);
        try {
            InputStream in = new FileInputStream(source);
            try {
                byte[] buf = new byte[64 * 1024];
                int n;
                java.nio.ByteBuffer bb = java.nio.ByteBuffer.wrap(buf);
                while ((n = in.read(buf)) > 0) {
                    bb.clear().limit(n);
                    while (bb.hasRemaining()) {
                        ch.write(bb);
                    }
                }
            } finally {
                in.close();
            }
            ch.force(true);
        } finally {
            ch.close();
        }
    }

    public void markPending(File regionFile) throws IOException {
        File marker = new File(regionFile.getParentFile(), regionFile.getName() + ".rollback.pending");
        marker.createNewFile();
    }

    public void clearPending(File regionFile) {
        File marker = new File(regionFile.getParentFile(), regionFile.getName() + ".rollback.pending");
        if (marker.exists()) {
            marker.delete();
        }
    }

    public boolean hasPendingMarker(File regionFile) {
        return new File(regionFile.getParentFile(), regionFile.getName() + ".rollback.pending").exists();
    }

    /** 启动时清空下载临时目录。 */
    public void purgeTmp() {
        File[] files = tmpDir().listFiles();
        if (files == null) {
            return;
        }
        for (File f : files) {
            f.delete();
        }
    }

    private static File ensureDir(File dir) {
        if (!dir.exists() && !dir.mkdirs()) {
            throw new IllegalStateException("无法创建目录: " + dir);
        }
        return dir;
    }

    private static void copy(File from, File to) throws IOException {
        InputStream in = new FileInputStream(from);
        try {
            OutputStream out = new FileOutputStream(to);
            try {
                byte[] buf = new byte[64 * 1024];
                int n;
                while ((n = in.read(buf)) > 0) {
                    out.write(buf, 0, n);
                }
            } finally {
                out.close();
            }
        } finally {
            in.close();
        }
    }
}
```

`Files` import 未使用可删；编译前确认无未使用 import 告警（不视为错误）。

- [ ] **Step 2: 编译验证**

```bash
MVN="$HOME/.m2/wrapper/dists/apache-maven-3.9.1-bin/2cpar40ui5s2pm3ja4s69ub7fa/apache-maven-3.9.1/bin/mvn"
cd "D:/Onedrive/ProjectsPrivate/ChunkDownloader"
"$MVN" -o -q compile
```

Expected: 退出码 0。

- [ ] **Step 3: 提交**

```bash
git add src/main/java/org/xjcraft/chunkdownloader/rollback/RollbackFiles.java
git commit -m "feat: add rollback file layout, backup copy and in-place overwrite"
```

---

## Task 7: `RollbackService`

**Files:**
- Create: `src/main/java/org/xjcraft/chunkdownloader/rollback/RollbackService.java`

- [ ] **Step 1: 实现**

```java
package org.xjcraft.chunkdownloader.rollback;

import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.entity.Player;
import org.xjcraft.chunkdownloader.ChunkDownloader;
import org.xjcraft.chunkdownloader.config.AzureConfig;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 回滚两阶段编排。
 *
 * 阶段 A（异步）：列版本 → 下载到插件临时目录，不封锁、不息屏。
 * 阶段 B（主线程）：登录锁 → 踢人 → 保存/卸载区块 → 备份原件 → 原地覆盖。
 */
public class RollbackService {

    private final ChunkDownloader plugin;
    private final RollbackFiles files;
    private final RollbackTokenStore tokens;
    private final AtomicBoolean inProgress = new AtomicBoolean(false);

    private static final int UNLOADS_PER_TICK = 64;
    private static final int MAX_UNLOAD_ROUNDS = 100;

    public RollbackService(ChunkDownloader plugin) {
        this.plugin = plugin;
        this.files = new RollbackFiles(plugin.getDataFolder());
        this.tokens = new RollbackTokenStore(
                AzureConfig.config.getRollbackTokenTtlSeconds());
    }

    public RollbackTokenStore tokens() {
        return tokens;
    }

    public boolean isInProgress() {
        return inProgress.get();
    }

    public void startupCleanup() {
        files.purgeTmp();
    }

    /** 把 world 名映射成远端 region 目录（相对 ftpRoot）。 */
    public String remoteRegionDir(String worldName) {
        return AzureConfig.config.getRollbackWorlds().get(worldName);
    }

    /** 阶段 A-1：列出历史版本。回调在主线程执行。 */
    public void listVersions(final String ownerId, final String worldName,
                             final int regionX, final int regionZ,
                             final Callback<List<String>> callback) {
        final String dir = remoteRegionDir(worldName);
        if (dir == null) {
            callback.done(null, "世界 " + worldName + " 未配置 rollbackWorlds");
            return;
        }
        final FtpsClient client = new FtpsClient(AzureConfig.config);
        if (!client.isConfigured()) {
            callback.done(null, "未配置 FTP 回滚账号");
            return;
        }
        final String fileName = RegionPaths.regionFileName(regionX, regionZ);
        plugin.getServer().getScheduler().runTaskAsynchronously(plugin, new Runnable() {
            @Override
            public void run() {
                String error = null;
                List<String> stamps = new ArrayList<>();
                try {
                    String base = trimSlash(AzureConfig.config.getFtpRoot());
                    String historyDir = "/" + join(base, "_history", dir);
                    for (RemoteEntry e : client.list(historyDir)) {
                        String stamp = RegionPaths.versionStamp(fileName, e.getName());
                        if (stamp != null) {
                            stamps.add(stamp);
                        }
                    }
                    stamps = FtpListing.sortStampsDescending(
                            stamps.toArray(new String[0]));
                } catch (Exception ex) {
                    error = ex.getMessage() == null ? ex.toString() : ex.getMessage();
                }
                final String err = error;
                final List<String> result = stamps;
                plugin.getServer().getScheduler().runTask(plugin, new Runnable() {
                    @Override
                    public void run() {
                        callback.done(result, err);
                    }
                });
            }
        });
    }

    /** 阶段 A-2：下载选中版本。回调在主线程执行，返回临时文件或错误。 */
    public void download(final RollbackTokenStore.Entry entry, final String stamp,
                         final Callback<File> callback) {
        final FtpsClient client = new FtpsClient(AzureConfig.config);
        final String dir = remoteRegionDir(entry.getWorld());
        final File tmp = files.newTmpFile(entry.getRegionFileName());
        plugin.getServer().getScheduler().runTaskAsynchronously(plugin, new Runnable() {
            @Override
            public void run() {
                String error = null;
                try {
                    String base = trimSlash(AzureConfig.config.getFtpRoot());
                    String path = "/" + join(base, "_history", dir,
                            entry.getRegionFileName() + "." + stamp);
                    client.download(path, tmp);
                } catch (Exception ex) {
                    error = ex.getMessage() == null ? ex.toString() : ex.getMessage();
                    if (tmp.exists()) {
                        tmp.delete();
                    }
                }
                final String err = error;
                plugin.getServer().getScheduler().runTask(plugin, new Runnable() {
                    @Override
                    public void run() {
                        callback.done(err == null ? tmp : null, err);
                    }
                });
            }
        });
    }

    /** 阶段 B：主线程临界区。必须在主线程调用；完成后回调（仍在主线程）。 */
    public void apply(final World world, final int regionX, final int regionZ,
                      final File tmp, final Callback<Void> callback) {
        if (!inProgress.compareAndSet(false, true)) {
            callback.done(null, "已有回滚正在进行");
            return;
        }
        final File regionFile = new File(new File(world.getWorldFolder(), "region"),
                RegionPaths.regionFileName(regionX, regionZ));
        if (!regionFile.isFile()) {
            inProgress.set(false);
            callback.done(null, "本地 region 文件不存在: " + regionFile);
            return;
        }
        if (files.hasPendingMarker(regionFile)) {
            inProgress.set(false);
            callback.done(null, "检测到未完成的回滚标记，请人工检查: "
                    + regionFile.getName() + ".rollback.pending");
            return;
        }

        evictPlayers(world, regionX, regionZ);
        unloadRegion(world, regionX, regionZ, 0, new Callback<Void>() {
            @Override
            public void done(Void ignored, String error) {
                String result = error;
                if (result == null) {
                    result = replace(world, regionFile, tmp);
                }
                inProgress.set(false);
                callback.done(null, result);
            }
        });
    }

    /**
     * 分 tick 卸载：单个 tick 最多卸载 UNLOADS_PER_TICK 个区块，
     * 避免传送/踢人刚发生就长时间阻塞主线程。
     * 连续 MAX_UNLOAD_ROUNDS 轮仍有残留则失败。
     */
    private void unloadRegion(final World world, final int regionX, final int regionZ,
                              final int round, final Callback<Void> done) {
        if (round >= MAX_UNLOAD_ROUNDS) {
            done.done(null, "有区块无法卸载，已中止；文件未改动");
            return;
        }
        int baseX = regionX << 5;
        int baseZ = regionZ << 5;
        int unloaded = 0;
        boolean remaining = false;
        for (int dx = 0; dx < 32; dx++) {
            for (int dz = 0; dz < 32; dz++) {
                int cx = baseX + dx;
                int cz = baseZ + dz;
                if (!world.isChunkLoaded(cx, cz)) {
                    continue;
                }
                if (unloaded < UNLOADS_PER_TICK) {
                    world.unloadChunk(cx, cz, true);
                    unloaded++;
                } else {
                    remaining = true;
                }
            }
        }
        if (!remaining && unloaded == 0) {
            done.done(null, null);
            return;
        }
        plugin.getServer().getScheduler().runTask(plugin, new Runnable() {
            @Override
            public void run() {
                unloadRegion(world, regionX, regionZ, round + 1, done);
            }
        });
    }

    private String replace(World world, File regionFile, File tmp) {
        File backup = null;
        try {
            backup = files.backupCopy(regionFile, world.getName());
            files.markPending(regionFile);
            try {
                files.overwriteInPlace(tmp, regionFile);
            } catch (IOException writeError) {
                restore(backup, regionFile);
                return "写入失败，已尝试从备份恢复: " + writeError.getMessage();
            }
            files.clearPending(regionFile);
            tmp.delete();
            return null;
        } catch (Exception e) {
            if (backup != null) {
                restore(backup, regionFile);
            }
            return e.getMessage() == null ? e.toString() : e.getMessage();
        }
    }

    private void evictPlayers(World world, int regionX, int regionZ) {
        boolean kick = "kick".equalsIgnoreCase(
                AzureConfig.config.getRollbackKickMode());
        Location spawn = world.getSpawnLocation();
        for (Player p : new ArrayList<Player>(world.getPlayers())) {
            int rx = RegionPaths.regionOf(p.getLocation().getChunk().getX());
            int rz = RegionPaths.regionOf(p.getLocation().getChunk().getZ());
            if (rx != regionX || rz != regionZ) {
                continue;
            }
            if (kick) {
                p.kickPlayer("该区域正在回滚，请稍后重新登录");
            } else {
                p.teleport(spawn);
            }
        }
    }

    /** 卸载目标 region 的全部已加载区块；分 tick 进行（见 unloadRegion）。 */

    private void restore(File backup, File regionFile) {
        try {
            files.overwriteInPlace(backup, regionFile);
        } catch (IOException e) {
            plugin.getLogger().severe("回滚失败后无法恢复原文件 " + regionFile
                    + "，备份位于 " + backup + "，错误: " + e);
        }
    }

    private static String trimSlash(String s) {
        if (s == null) {
            return "";
        }
        String r = s;
        while (r.startsWith("/")) {
            r = r.substring(1);
        }
        while (r.endsWith("/")) {
            r = r.substring(0, r.length() - 1);
        }
        return r;
    }

    private static String join(String a, String... parts) {
        StringBuilder sb = new StringBuilder(a);
        for (String p : parts) {
            if (p == null || p.isEmpty()) {
                continue;
            }
            if (sb.length() > 0) {
                sb.append('/');
            }
            sb.append(p.startsWith("/") ? p.substring(1) : p);
        }
        return sb.toString();
    }

    public interface Callback<T> {
        void done(T value, String error);
    }
}
```

`Chunk` import 实际未用，可删。

- [ ] **Step 2: 编译验证**

```bash
MVN="$HOME/.m2/wrapper/dists/apache-maven-3.9.1-bin/2cpar40ui5s2pm3ja4s69ub7fa/apache-maven-3.9.1/bin/mvn"
cd "D:/Onedrive/ProjectsPrivate/ChunkDownloader"
"$MVN" -o -q compile
```

Expected: 退出码 0。

- [ ] **Step 3: 提交**

```bash
git add src/main/java/org/xjcraft/chunkdownloader/rollback/RollbackService.java
git commit -m "feat: add rollback orchestration service with two-phase apply"
```

---

## Task 8: 登录拦截监听器

**Files:**
- Create: `src/main/java/org/xjcraft/chunkdownloader/rollback/RollbackLoginGuard.java`

- [ ] **Step 1: 实现**

```java
package org.xjcraft.chunkdownloader.rollback;

import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerLoginEvent;

public class RollbackLoginGuard implements Listener {

    private final RollbackService service;

    public RollbackLoginGuard(RollbackService service) {
        this.service = service;
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onLogin(PlayerLoginEvent event) {
        if (service.isInProgress()) {
            event.disallow(PlayerLoginEvent.Result.KICK_OTHER,
                    "备份回滚中，请稍候重试");
        }
    }
}
```

- [ ] **Step 2: 编译验证**

```bash
MVN="$HOME/.m2/wrapper/dists/apache-maven-3.9.1-bin/2cpar40ui5s2pm3ja4s69ub7fa/apache-maven-3.9.1/bin/mvn"
cd "D:/Onedrive/ProjectsPrivate/ChunkDownloader"
"$MVN" -o -q compile
```

Expected: 退出码 0。

- [ ] **Step 3: 提交**

```bash
git add src/main/java/org/xjcraft/chunkdownloader/rollback/RollbackLoginGuard.java
git commit -m "feat: block logins while a rollback is applying"
```

---

## Task 9: 命令与插件装配

**Files:**
- Modify: `src/main/java/org/xjcraft/chunkdownloader/ChunkDownloader.java`
- Modify: `src/main/java/org/xjcraft/chunkdownloader/CDCommand.java`

- [ ] **Step 1: 在 `ChunkDownloader` 中装配**

替换整个文件内容：

```java
package org.xjcraft.chunkdownloader;

import org.bukkit.plugin.java.JavaPlugin;
import org.xjcraft.CommonPlugin;
import org.xjcraft.chunkdownloader.rollback.RollbackLoginGuard;
import org.xjcraft.chunkdownloader.rollback.RollbackService;

public final class ChunkDownloader extends CommonPlugin {

    private RollbackService rollbackService;

    @Override
    public void onEnable() {
        loadConfigs();
        rollbackService = new RollbackService(this);
        rollbackService.startupCleanup();
        registerCommand(new CDCommand(this, rollbackService));
        getServer().getPluginManager()
                .registerEvents(new RollbackLoginGuard(rollbackService), this);
        getLogger().info("ChunkDownloader enabled");
    }

    @Override
    public void onDisable() {
    }

    public RollbackService getRollbackService() {
        return rollbackService;
    }
}
```

- [ ] **Step 2: 在 `CDCommand` 顶部新增字段与构造器**

```java
import org.bukkit.World;
import org.xjcraft.chunkdownloader.rollback.RegionPaths;
import org.xjcraft.chunkdownloader.rollback.RollbackService;
import org.xjcraft.chunkdownloader.rollback.RollbackTokenStore;

import java.io.File;
```

把构造器改成：

```java
    private final ChunkDownloader plugin;
    private final RollbackService rollbackService;

    public CDCommand(ChunkDownloader plugin, RollbackService rollbackService) {
        this.plugin = plugin;
        this.rollbackService = rollbackService;
    }
```

- [ ] **Step 3: 在 `CDCommand` 中新增三个命令方法**

```java
    @RCommand(value = "rollback", desc = "热回滚当前位置所在 region（OP）",
            sender = RCommand.Sender.PLAYER, permisson = "chunk.rollback",
            defaultUser = RCommand.Permisson.OP)
    public void rollbackHere(CommandSender sender) {
        Player player = (Player) sender;
        World world = player.getWorld();
        int regionX = RegionPaths.regionOf(player.getLocation().getChunk().getX());
        int regionZ = RegionPaths.regionOf(player.getLocation().getChunk().getZ());
        listFor(sender, player.getUniqueId().toString(), world, regionX, regionZ);
    }

    @RCommand(value = "rollback", desc = "热回滚指定 region（控制台/OP）",
            permisson = "chunk.rollback",
            defaultUser = RCommand.Permisson.OP)
    public void rollbackAt(CommandSender sender, String worldName, Integer regionX, Integer regionZ) {
        World world = plugin.getServer().getWorld(worldName);
        if (world == null) {
            sender.sendMessage("世界不存在: " + worldName);
            return;
        }
        String owner = sender instanceof Player
                ? ((Player) sender).getUniqueId().toString() : "CONSOLE";
        listFor(sender, owner, world, regionX, regionZ);
    }

    private void listFor(final CommandSender sender, final String owner,
                         final World world, final int regionX, final int regionZ) {
        sender.sendMessage("正在查询历史版本: " + world.getName()
                + " region " + regionX + "," + regionZ);
        rollbackService.listVersions(owner, world.getName(), regionX, regionZ,
                new RollbackService.Callback<java.util.List<String>>() {
                    @Override
                    public void done(java.util.List<String> stamps, String error) {
                        if (error != null) {
                            sender.sendMessage("查询失败: " + error);
                            return;
                        }
                        if (stamps.isEmpty()) {
                            sender.sendMessage("该 region 没有可回滚的历史版本");
                            return;
                        }
                        RollbackTokenStore.Entry entry = rollbackService.tokens().create(
                                owner, world.getName(), regionX, regionZ,
                                RegionPaths.regionFileName(regionX, regionZ), stamps,
                                System.currentTimeMillis() / 1000);
                        int i = 0;
                        for (String stamp : stamps) {
                            final String cmd = String.format(
                                    "/cd rollback confirm %s %d", entry.getToken(), i);
                            plugin.getServer().dispatchCommand(
                                    plugin.getServer().getConsoleSender(),
                                    String.format(
                                            "tellraw %s [\"\",{\"text\":\"[%d] %s\",\"color\":\"blue\",\"underlined\":true,\"click_event\":{\"action\":\"run_command\",\"value\":\"%s\"}}]",
                                            senderName(sender), i, prettyStamp(stamp), cmd));
                            i++;
                        }
                    }
                });
    }

    @RCommand(value = "rollback", desc = "确认回滚 <token> <序号>",
            permisson = "chunk.rollback",
            defaultUser = RCommand.Permisson.OP)
    public void rollbackConfirm(CommandSender sender, String token, Integer index) {
        final String owner = sender instanceof Player
                ? ((Player) sender).getUniqueId().toString() : "CONSOLE";
        RollbackTokenStore.Entry entry = rollbackService.tokens().take(
                token, owner, System.currentTimeMillis() / 1000, index);
        if (entry == null) {
            sender.sendMessage("token 无效、已过期或序号越界");
            return;
        }
        String stamp = entry.stampAt(index);
        World world = plugin.getServer().getWorld(entry.getWorld());
        if (world == null) {
            sender.sendMessage("世界不存在: " + entry.getWorld());
            return;
        }
        sender.sendMessage("正在下载版本 " + prettyStamp(stamp) + " ...");
        final World targetWorld = world;
        final int regionX = entry.getRegionX();
        final int regionZ = entry.getRegionZ();
        final CommandSender initiator = sender;
        rollbackService.download(entry, stamp, new RollbackService.Callback<File>() {
            @Override
            public void done(File tmp, String error) {
                if (error != null) {
                    initiator.sendMessage("下载失败: " + error);
                    return;
                }
                rollbackService.apply(targetWorld, regionX, regionZ, tmp,
                        new RollbackService.Callback<Void>() {
                            @Override
                            public void done(Void ignored, String applyError) {
                                if (applyError != null) {
                                    initiator.sendMessage("回滚失败: " + applyError);
                                    plugin.getLogger().warning("回滚失败: " + applyError);
                                } else {
                                    initiator.sendMessage("回滚完成: " + targetWorld.getName()
                                            + " region " + regionX + "," + regionZ
                                            + " -> " + prettyStamp(stamp));
                                    plugin.getLogger().info("热回滚完成: " + targetWorld.getName()
                                            + " region " + regionX + "," + regionZ
                                            + " -> " + stamp);
                                }
                            }
                        });
            }
        });
    }

    private static String senderName(CommandSender sender) {
        return sender instanceof Player ? sender.getName() : "CONSOLE";
    }

    private static String prettyStamp(String stamp) {
        // 20261018-2000 -> 2026-10-18 20:00
        if (stamp.length() != 13 || stamp.charAt(8) != '-') {
            return stamp;
        }
        return stamp.substring(0, 4) + "-" + stamp.substring(4, 6) + "-"
                + stamp.substring(6, 8) + " " + stamp.substring(9, 11) + ":"
                + stamp.substring(11, 13);
    }
```

`rollbackConfirm` 的 `stamp` 供回调使用；Java 8 要求 effectively final，因此不要在该方法内重新赋值。

- [ ] **Step 4: 编译并跑全部单测**

```bash
MVN="$HOME/.m2/wrapper/dists/apache-maven-3.9.1-bin/2cpar40ui5s2pm3ja4s69ub7fa/apache-maven-3.9.1/bin/mvn"
cd "D:/Onedrive/ProjectsPrivate/ChunkDownloader"
"$MVN" -o test 2>&1 | grep -E "Tests run|BUILD" | head
```

Expected: 全部测试通过、BUILD SUCCESS。

- [ ] **Step 5: 打包并确认类进了 jar**

```bash
MVN="$HOME/.m2/wrapper/dists/apache-maven-3.9.1-bin/2cpar40ui5s2pm3ja4s69ub7fa/apache-maven-3.9.1/bin/mvn"
cd "D:/Onedrive/ProjectsPrivate/ChunkDownloader"
"$MVN" -o package 2>&1 | grep -E "BUILD|Tests run" | head
unzip -l target/ChunkDownloader-1.0-SNAPSHOT.jar | grep -E "RollbackService|FtpsClient"
```

Expected: BUILD SUCCESS；列出 `RollbackService.class` 与 `FtpsClient.class`。

- [ ] **Step 6: 提交**

```bash
git add src/main/java/org/xjcraft/chunkdownloader/ChunkDownloader.java \
        src/main/java/org/xjcraft/chunkdownloader/CDCommand.java
git commit -m "feat: wire rollback command, service and login guard into the plugin"
```

---

## Task 10: 端到端验收（手工，需要真实服务器与 FTPS）

**Files:** 无代码改动；验收记录追加到本文档末尾。

- [ ] **Step 1: 部署配置**

在服务器 `plugins/ChunkDownloader/azure.yml` 中设置（不要在本地仓库里提交真实密码）：

```yaml
ftpRoot: "/backup/mc-debian"
ftpRollbackUser: "<回滚账号>"
ftpRollbackPassword: "<密码>"
ftpTrustAllCerts: true
rollbackKickMode: teleport
```

- [ ] **Step 2: 安装新 jar 并重启**

复制 `target/ChunkDownloader-1.0-SNAPSHOT.jar` 到 `plugins/`，重启服务器。确认日志中有 `ChunkDownloader enabled` 且无报错。

- [ ] **Step 3: 列出功能**

- 普通玩家执行 `/cd rollback`：应提示没有权限。
- OP 站在目标区域执行 `/cd rollback`：应输出多个 `[n] YYYY-MM-DD HH:MM` 可点击行；每个版本与 FTPS `_history` 目录下的实际文件一一对应。
- 控制台执行 `/cd rollback MainLand 0 -1`：应输出同样列表。

- [ ] **Step 4: 选择性回滚并验证不丢数据**

选一个**低负载区域**（无人建造），记录当前 region 文件大小与 md5，然后：

1. 在游戏内改动该区域（放置一块方块并保存）。
2. OP 执行 `/cd rollback` 并选择较早版本。
3. 观察：在线玩家被短暂影响（在区域内者被传送到出生点）；新登录在数秒内被拒绝，随后恢复。
4. 检查 `plugins/ChunkDownloader/rollback-backups/<world>/` 生成了回滚前原件。
5. 用 `/cd my` 或直接对比文件确认 region 已变成所选版本内容。
6. 让 OP 继续在该区域移动/破坏方块，确认**没有**出现区块回退或数据丢失（验证缓存句柄仍然有效）。

- [ ] **Step 5: 失败路径抽查**

- 断开 FTPS 网络后执行 `/cd rollback`：应报错且**没有**踢人/封锁。
- 在列表中点击过期 token（等待超过 5 分钟）：应提示 token 失效。

- [ ] **Step 6: 记录验收结果**

在本文档末尾追加：

```markdown
## 验收记录

- 日期：
- 服务器版本：
- 测试世界/region：
- 列出版本数：
- 回滚耗时（秒）：
- 登录封锁窗口（秒）：
- 异常与处理：
```

- [ ] **Step 7: 提交验收记录**

```bash
git add docs/superpowers/plans/2026-10-20-op-hot-rollback.md
git commit -m "docs: record hot rollback acceptance results"
```

---

## 自检结论

- 规格中的每条要求均有对应任务：配置/权限（Task 1）、坐标与版本解析（Task 2）、MLSD/LIST 解析（Task 3）、token（Task 4）、FTPS 客户端（Task 5）、文件操作（Task 6）、两阶段状态机（Task 7）、登录锁与踢人（Task 8）、指令交互（Task 9）、验收（Task 10）。
- 类型一致性：`RollbackTokenStore.Entry` 的字段（`world/regionX/regionZ/regionFileName/stamps/token`）在 Task 4 定义、Task 7/9 使用一致；`FtpsClient.list/download` 签名在 Task 5 定义、Task 7 使用一致；`RollbackService.Callback<T>` 在 Task 7 定义、Task 9 使用一致；`RollbackService.apply(World, int, int, File, Callback<Void>)` 在 Task 7 定义，Task 9 的确认命令按该签名调用。
- 无占位符：所有代码步骤均给出完整实现。
