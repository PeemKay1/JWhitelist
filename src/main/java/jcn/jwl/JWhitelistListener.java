package jcn.jwl;

import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.AsyncPlayerPreLoginEvent;
import org.bukkit.event.player.PlayerJoinEvent;

public class JWhitelistListener implements Listener {

    private final JWhitelist plugin;
    private final JWhitelistDatabase databaseManager;

    public JWhitelistListener(JWhitelist plugin) {
        this.plugin = plugin;
        this.databaseManager = plugin.getDatabaseManager();
    }

    @EventHandler
    public void onPlayerPreLogin(AsyncPlayerPreLoginEvent event) {
        if (event.getLoginResult() != AsyncPlayerPreLoginEvent.Result.ALLOWED) return;
        if (!plugin.isDatabaseReady()) {
            event.disallow(AsyncPlayerPreLoginEvent.Result.KICK_WHITELIST, plugin.getKickMessage());
            return;
        }
        if (databaseManager.isPlayerWhitelisted(event.getName())) {
            databaseManager.updateLastLogin(event.getName());
        } else {
            event.disallow(AsyncPlayerPreLoginEvent.Result.KICK_WHITELIST, plugin.getKickMessage());
        }
    }

    @EventHandler
    public void onPlayerJoin(PlayerJoinEvent event){
        Player player = event.getPlayer();
        if (!plugin.isLuckPermsEnabled()) return;
        if (!player.hasPlayedBefore() || !player.hasPermission("group." + plugin.getGroup())) {
            LuckPermsApi.addGroupPermission(player.getUniqueId());
        }
    }
}
