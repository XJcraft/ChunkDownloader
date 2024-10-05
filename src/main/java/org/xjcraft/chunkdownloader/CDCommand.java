package org.xjcraft.chunkdownloader;

import org.apache.commons.math3.util.Pair;
import org.bukkit.Chunk;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.xjcraft.annotation.RCommand;
import org.xjcraft.api.CommonCommandExecutor;
import org.xjcraft.chunkdownloader.config.AzureConfig;
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
        String fname = StringUtil.applyPlaceHolder(AzureConfig.config.getFname(), placeholder);
        String p = AzureConfig.config.getWorlds().getOrDefault(chunk.getWorld().getName(), chunk.getWorld().getName());
        final String path = String.format(p, fname);
        System.out.println("get url for: " + path);

        plugin.getServer().dispatchCommand(plugin.getServer().getConsoleSender(),
                String.format("tellraw %s [\"\",{\"text\":\"正在查询文件：%s\",\"color\":\"white\"}]",
                        player.getName(), fname));
        plugin.getServer().getScheduler().runTaskAsynchronously(plugin, new Runnable() {
            @Override
            public void run() {
                String url = OneDriveUtil.getFileDownloadUrl(path);
                plugin.getServer().getScheduler().runTask(plugin, () -> {
                    plugin.getServer().dispatchCommand(plugin.getServer().getConsoleSender(),
                            String.format("tellraw %s [\"\",{\"text\":\"点此下载我所在的区块\",\"color\":\"blue\",\"bold\":false,\"underlined\":true,\"clickEvent\":{\"action\":\"open_url\",\"value\":\"%s\"}}]",
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
                                String.format("tellraw %s [\"\",{\"text\":\"%s\",\"color\":\"blue\",\"bold\":false,\"underlined\":true,\"clickEvent\":{\"action\":\"open_url\",\"value\":\"%s\"}}]",
                                        player.getName(),time, version));
                    }
                });
            }
        });





    }
}
