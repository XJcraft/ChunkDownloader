package org.xjcraft.chunkdownloader;

import org.bukkit.plugin.java.JavaPlugin;
import org.xjcraft.CommonPlugin;

public final class ChunkDownloader extends CommonPlugin {

    @Override
    public void onEnable() {
        // Plugin startup logic
        loadConfigs();
        registerCommand(new CDCommand(this));
    }

    @Override
    public void onDisable() {
        // Plugin shutdown logic
    }
}
