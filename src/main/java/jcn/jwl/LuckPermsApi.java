package jcn.jwl;

import net.luckperms.api.LuckPerms;
import net.luckperms.api.node.types.InheritanceNode;
import org.bukkit.Bukkit;
import java.util.UUID;

public final class LuckPermsApi {
    private static volatile LuckPerms api;
    private static volatile JWhitelist plugin;
    public static void initialize(JWhitelist owner) {
        var provider = Bukkit.getServicesManager().getRegistration(LuckPerms.class);
        if (provider != null) init(provider.getProvider(), owner);
        else owner.getLogger().warning("LuckPerms unavailable; group integration disabled.");
    }
    public static void init(LuckPerms service, JWhitelist owner) { plugin = owner; api = service; }
    public static void reset() { api = null; plugin = null; }
    public static void addGroupPermission(UUID uuid) { update(uuid, true); }
    public static void addGroupPermission(String name) { lookup(name, true); }
    public static void removeGroupPermission(String name) { lookup(name, false); }
    private static void lookup(String name, boolean add) {
        LuckPerms service = api;
        if (service == null) return;
        service.getUserManager().lookupUniqueId(name).thenAccept(uuid -> { if (uuid != null) update(uuid, add); });
    }
    private static void update(UUID uuid, boolean add) {
        LuckPerms service = api;
        JWhitelist owner = plugin;
        if (service == null || owner == null || !owner.isLuckPermsEnabled()) return;
        String group = owner.getGroup();
        if (group == null || group.equalsIgnoreCase("none") || group.isBlank()) return;
        service.getUserManager().modifyUser(uuid, user -> {
            var node = InheritanceNode.builder(group).build();
            if (add) user.data().add(node); else user.data().remove(node);
        }).exceptionally(error -> { owner.getLogger().warning("LuckPerms update failed: " + error.getMessage()); return null; });
    }
}
