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
 * Two-phase rollback orchestration.
 *
 * Phase A (async): list history versions, download the selected one to the
 * plugin temp dir. No login lock, no player kick.
 * Phase B (main thread): lock logins, evict players, save/unload the region's
 * chunks across ticks, back up the original, overwrite in place.
 */
public class RollbackService {

    private static final int UNLOADS_PER_TICK = 64;
    private static final int MAX_UNLOAD_ROUNDS = 100;

    private final ChunkDownloader plugin;
    private final RollbackFiles files;
    private final RollbackTokenStore tokens;
    private final AtomicBoolean inProgress = new AtomicBoolean(false);

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

    /** world 名映射成远端 region 目录（相对 ftpRoot）；未配置返回 null。 */
    public String remoteRegionDir(String worldName) {
        return AzureConfig.config.getRollbackWorlds().get(worldName);
    }

    /**
     * 本地 region 目录。新版世界格式把维度放在 dimensions/minecraft/&lt;维度&gt;/region，
     * 旧格式是 region / DIM-1 / DIM1。两种布局并存，按存在性探测。
     * getWorldFolder() 可能是相对路径（相对服务器工作目录），统一转绝对路径。
     */
    private static File resolveRegionDir(World world) {
        for (File dir : regionDirs(world)) {
            if (dir.isDirectory()) {
                return dir;
            }
        }
        return null;
    }

    private static List<File> regionDirs(World world) {
        File root = world.getWorldFolder().getAbsoluteFile();
        List<File> dirs = new ArrayList<File>();
        World.Environment env = world.getEnvironment();
        if (env == World.Environment.NETHER) {
            dirs.add(new File(root, "dimensions/minecraft/the_nether/region"));
            dirs.add(new File(root, "DIM-1/region"));
        } else if (env == World.Environment.THE_END) {
            dirs.add(new File(root, "dimensions/minecraft/the_end/region"));
            dirs.add(new File(root, "DIM1/region"));
        } else {
            dirs.add(new File(root, "dimensions/minecraft/overworld/region"));
            dirs.add(new File(root, "region"));
        }
        return dirs;
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
                List<String> stamps = new ArrayList<String>();
                try {
                    String historyDir = "/" + join(trimSlash(AzureConfig.config.getFtpRollbackRoot()), "_history", dir);
                    for (RemoteEntry e : client.list(historyDir)) {
                        String stamp = RegionPaths.versionStamp(fileName, e.getName());
                        if (stamp != null) {
                            stamps.add(stamp);
                        }
                    }
                    stamps = FtpListing.sortStampsDescending(stamps.toArray(new String[0]));
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

    /** 阶段 A-2：下载选中版本到插件临时目录。回调在主线程执行。 */
    public void download(final RollbackTokenStore.Entry entry, final String stamp,
                         final Callback<File> callback) {
        final String dir = remoteRegionDir(entry.getWorld());
        if (dir == null) {
            callback.done(null, "世界 " + entry.getWorld() + " 未配置 rollbackWorlds");
            return;
        }
        final FtpsClient client = new FtpsClient(AzureConfig.config);
        if (!client.isConfigured()) {
            callback.done(null, "未配置 FTP 回滚账号");
            return;
        }
        final File tmp = files.newTmpFile(entry.getRegionFileName());
        plugin.getServer().getScheduler().runTaskAsynchronously(plugin, new Runnable() {
            @Override
            public void run() {
                String error = null;
                try {
                    String path = "/" + join(trimSlash(AzureConfig.config.getFtpRollbackRoot()),
                            "_history", dir,
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

    /** 阶段 B：主线程临界区。必须在主线程调用；完成后回调（仍在主线程），error 为 null 表示成功。 */
    public void apply(final World world, final int regionX, final int regionZ,
                      final File tmp, final Callback<Void> callback) {
        if (!plugin.getServer().isPrimaryThread()) {
            throw new IllegalStateException("apply() 必须在主线程调用");
        }
        if (!inProgress.compareAndSet(false, true)) {
            callback.done(null, "已有回滚正在进行");
            return;
        }
        final File regionDir = resolveRegionDir(world);
        if (regionDir == null) {
            inProgress.set(false);
            callback.done(null, "找不到本地 region 目录，尝试过: " + regionDirs(world));
            return;
        }
        final File regionFile = new File(regionDir,
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
     * 分 tick 卸载：单 tick 最多 UNLOADS_PER_TICK 个区块，避免主线程长时间阻塞。
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
                files.clearPending(regionFile);
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
