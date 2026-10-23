package org.xjcraft.chunkdownloader;

import org.bukkit.Chunk;
import org.bukkit.World;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.xjcraft.annotation.RCommand;
import org.xjcraft.api.CommonCommandExecutor;
import org.xjcraft.chunkdownloader.config.AzureConfig;
import org.xjcraft.chunkdownloader.config.HttpConfig;
import org.xjcraft.chunkdownloader.rollback.RegionPaths;
import org.xjcraft.chunkdownloader.rollback.RollbackService;
import org.xjcraft.chunkdownloader.rollback.RollbackTokenStore;
import org.xjcraft.utils.StringUtil;

import java.io.File;
import java.util.HashMap;
import java.util.List;

public class CDCommand implements CommonCommandExecutor {
    private final ChunkDownloader plugin;
    private final RollbackService rollbackService;

    public CDCommand(ChunkDownloader plugin, RollbackService rollbackService) {
        this.plugin = plugin;
        this.rollbackService = rollbackService;
    }

    @RCommand(value = "my", desc = "获取我所在区块", sender = RCommand.Sender.PLAYER)
    public void get(CommandSender player) {
        Chunk chunk = ((Player) player).getLocation().getChunk();
        HashMap<String, String> placeholder = new HashMap<String, String>() {{
            put("x", (chunk.getX() >> 5) + "");
            put("y", (chunk.getZ() >> 5) + "");
        }};
        String worldName = chunk.getWorld().getName();

        plugin.getServer().getScheduler().runTaskAsynchronously(plugin, new Runnable() {
            @Override
            public void run() {
                if (AzureConfig.config.getShareMethod() == AzureConfig.ShareMethod.HTTP) {
                    String fname = StringUtil.applyPlaceHolder(HttpConfig.config.getFname(), placeholder);
                    String p = HttpConfig.config.getWorlds().getOrDefault(worldName, worldName);
                    String path = String.format(p, fname);
                    System.out.println("get url for: " + path);

                    String url = HttpUtil.buildUrl(path);
                    plugin.getServer().getScheduler().runTask(plugin, () ->
                            plugin.getServer().dispatchCommand(plugin.getServer().getConsoleSender(),
                                    String.format("tellraw %s [\"\",{\"text\":\"点此下载我所在的区块\",\"color\":\"blue\",\"bold\":false,\"underlined\":true,\"click_event\":{\"action\":\"open_url\",\"url\":\"%s\"}}]",
                                            player.getName(), url))
                    );
                    return;
                }

                String fname = StringUtil.applyPlaceHolder(AzureConfig.config.getFname(), placeholder);
                String p = AzureConfig.config.getWorlds().getOrDefault(worldName, worldName);
                final String path = String.format(p, fname);
                System.out.println("get url for: " + path);

                plugin.getServer().getScheduler().runTask(plugin, () ->
                        plugin.getServer().dispatchCommand(plugin.getServer().getConsoleSender(),
                                String.format("tellraw %s [\"\",{\"text\":\"正在查询文件：%s\",\"color\":\"white\"}]",
                                        player.getName(), fname)));

                if (AzureConfig.config.getShareMethod() == AzureConfig.ShareMethod.FTP) {
                    String url = FtpUtil.buildUrl(path);
                    plugin.getServer().getScheduler().runTask(plugin, () ->
                        plugin.getServer().dispatchCommand(plugin.getServer().getConsoleSender(),
                                String.format("tellraw %s [\"\",{\"text\":\"点此复制FTP下载地址\",\"color\":\"green\",\"bold\":false,\"underlined\":true,\"click_event\":{\"action\":\"copy_to_clipboard\",\"value\":\"%s\"}}]",
                                        player.getName(), url))
                    );
                    return;
                }

                String url = OneDriveUtil.getFileDownloadUrl(path);
                plugin.getServer().getScheduler().runTask(plugin, () -> {
                    plugin.getServer().dispatchCommand(plugin.getServer().getConsoleSender(),
                            String.format("tellraw %s [\"\",{\"text\":\"点此下载我所在的区块\",\"color\":\"blue\",\"bold\":false,\"underlined\":true,\"click_event\":{\"action\":\"open_url\",\"url\":\"%s\"}}]",
                                    player.getName(), url));

                });
                List<OneDriveUtil.VersionUrl> downloadUrls = OneDriveUtil.getFileVersionDownloadUrls(path);
                plugin.getServer().getScheduler().runTask(plugin, () -> {
                    plugin.getServer().dispatchCommand(plugin.getServer().getConsoleSender(),
                            String.format("tellraw %s [\"\",{\"text\":\"历史记录\",\"color\":\"dark_gray\",\"bold\":true}]",
                                    player.getName()));
                    for (OneDriveUtil.VersionUrl pair : downloadUrls) {
                        String time = pair.getTime();
                        String version = pair.getUrl();
                        plugin.getServer().dispatchCommand(plugin.getServer().getConsoleSender(),
                                String.format("tellraw %s [\"\",{\"text\":\"%s\",\"color\":\"blue\",\"bold\":false,\"underlined\":true,\"click_event\":{\"action\":\"open_url\",\"url\":\"%s\"}}]",
                                        player.getName(),time, version));
                    }
                });
            }
        });
    }

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
                new RollbackService.Callback<List<String>>() {
                    @Override
                    public void done(List<String> stamps, String error) {
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
                            // 确认命令必须是 2 个参数，才能命中 rollback@2；
                            // 若写成 "/cd rollback confirm <token> <index>" 会变成 3 个参数，
                            // 被 rollbackAt 抢走。
                            final String cmd = String.format(
                                    "/cd rollback %s %d", entry.getToken(), i);
                            outputVersionLine(sender, i, prettyStamp(stamp), cmd);
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
        final String stamp = entry.stampAt(index);
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

    private void outputVersionLine(CommandSender sender, int index,
                                   String stamp, String command) {
        if (sender instanceof Player) {
            // 版本时间戳与 token 都只含数字/字母/连字符，可安全嵌入 JSON
            plugin.getServer().dispatchCommand(plugin.getServer().getConsoleSender(),
                    String.format(
                            "tellraw %s [\"\",{\"text\":\"[%d] %s\",\"color\":\"blue\",\"underlined\":true,\"click_event\":{\"action\":\"run_command\",\"value\":\"%s\"}}]",
                            sender.getName(), index, stamp, command));
        } else {
            sender.sendMessage("[" + index + "] " + stamp + "  (" + command + ")");
        }
    }

    private static String prettyStamp(String stamp) {
        // 20261018-2000 -> 2026-10-18 20:00
        if (stamp == null || stamp.length() != 13 || stamp.charAt(8) != '-') {
            return String.valueOf(stamp);
        }
        return stamp.substring(0, 4) + "-" + stamp.substring(4, 6) + "-"
                + stamp.substring(6, 8) + " " + stamp.substring(9, 11) + ":"
                + stamp.substring(11, 13);
    }
}
