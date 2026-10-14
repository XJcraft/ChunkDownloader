package org.xjcraft.chunkdownloader;

import org.bukkit.Chunk;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.xjcraft.annotation.RCommand;
import org.xjcraft.api.CommonCommandExecutor;
import org.xjcraft.chunkdownloader.config.AzureConfig;
import org.xjcraft.chunkdownloader.config.HttpConfig;
import org.xjcraft.utils.StringUtil;

import java.util.HashMap;
import java.util.List;

public class CDCommand implements CommonCommandExecutor {
    private ChunkDownloader plugin;

    public CDCommand(ChunkDownloader plugin) {
        this.plugin = plugin;
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
}
