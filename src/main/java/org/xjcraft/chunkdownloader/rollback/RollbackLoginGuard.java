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
