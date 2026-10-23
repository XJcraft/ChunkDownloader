package org.xjcraft.chunkdownloader;

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
    }

    @Override
    public void onDisable() {
    }

    public RollbackService getRollbackService() {
        return rollbackService;
    }
}
