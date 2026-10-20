package jcn.jwl;

import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;
import java.lang.reflect.Method;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.*;
import java.util.function.Consumer;
import java.util.logging.Level;

public final class JWhitelist extends JavaPlugin {
    private JWhitelistDatabase databaseManager;
    private volatile String kickMessage;
    private volatile String group;
    private volatile boolean luckPermsEnabled;
    private volatile Map<String, String> messages = Map.of();
    private ScheduledExecutorService io;
    private ScheduledFuture<?> cleanup;
    private boolean folia;
    private volatile boolean databaseReady;
    public boolean isDatabaseReady() { return databaseReady; }

    @Override public void onEnable() {
        saveDefaultConfig();
        try { Class.forName("io.papermc.paper.threadedregions.RegionizedServer"); folia = true; }
        catch (ClassNotFoundException ignored) { folia = false; }
        io = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread thread = new Thread(r, "JWhitelist-database");
            thread.setDaemon(true);
            return thread;
        });
        loadConfigValues();
        databaseManager = new JWhitelistDatabase(this);
        getServer().getPluginManager().registerEvents(new JWhitelistListener(this), this);
        try { databaseManager.setupDatabase(); databaseReady = true; }
        catch (Exception e) { getLogger().log(Level.SEVERE, "Whitelist unavailable; logins will be denied until database recovery", e); }
        JWhitelistCommand command = new JWhitelistCommand(this);
        getCommand("jwhitelist").setExecutor(command);
        getCommand("jwhitelist").setTabCompleter(command);
        configureLuckPerms();
        startWhitelistCheckTask();
    }

    @Override public void onDisable() {
        if (cleanup != null) cleanup.cancel(false);
        if (io != null) {
            io.shutdown();
            try { if (!io.awaitTermination(20, TimeUnit.SECONDS)) io.shutdownNow(); }
            catch (InterruptedException e) { io.shutdownNow(); Thread.currentThread().interrupt(); }
        }
        if (getServer().getPluginManager().isPluginEnabled("LuckPerms")) LuckPermsApi.reset();
        if (databaseManager != null) databaseManager.closeDatabase();
    }

    public JWhitelistDatabase getDatabaseManager() { return databaseManager; }
    public String getKickMessage() { return kickMessage; }
    public String getGroup() { return group; }
    public boolean isLuckPermsEnabled() { return luckPermsEnabled; }
    public String getMessage(String key) { return messages.getOrDefault(key, "Message not found: " + key); }

    public void executeIo(Runnable action) {
        if (io != null && !io.isShutdown()) {
            try { io.execute(action); } catch (RejectedExecutionException ignored) { }
        }
    }

    public void reply(CommandSender sender, String message) {
        if (sender instanceof Player player) runAtPlayer(player, () -> sender.sendMessage(message));
        else sender.sendMessage(message);
    }

    /** Folia work affecting a player must execute on that player's owning region. */
    public void runAtPlayer(Player player, Runnable action) {
        if (!isEnabled()) return;
        if (!folia) {
            Bukkit.getScheduler().runTask(this, () -> { if (player.isOnline()) action.run(); });
            return;
        }
        try {
            Method getter = player.getClass().getMethod("getScheduler");
            Object scheduler = getter.invoke(player);
            getter.getReturnType().getMethod("run", org.bukkit.plugin.Plugin.class, Consumer.class, Runnable.class)
                    .invoke(scheduler, this, (Consumer<Object>) task -> {
                        if (player.isOnline()) action.run();
                    }, null);
        } catch (ReflectiveOperationException exception) {
            getLogger().log(Level.SEVERE, "Cannot schedule player action on Folia", exception);
        }
    }

    public void reloadConfiguration() {
        reloadConfig();
        loadConfigValues();
        synchronized (databaseManager) {
            databaseReady = false;
            databaseManager.closeDatabase();
            databaseManager.setupDatabase();
            databaseReady = true;
        }
        configureLuckPerms();
        startWhitelistCheckTask();
    }

    private void configureLuckPerms() {
        if (getServer().getPluginManager().isPluginEnabled("LuckPerms")) LuckPermsApi.reset();
        if (luckPermsEnabled && getServer().getPluginManager().isPluginEnabled("LuckPerms")) LuckPermsApi.initialize(this);
        else if (luckPermsEnabled) { luckPermsEnabled = false; getLogger().warning("LuckPerms unavailable; group integration disabled."); }
    }

    private void loadConfigValues() {
        kickMessage = ChatColor.translateAlternateColorCodes('&', getConfig().getString("whitelist.kick_message", "&cYou are not whitelisted."));
        group = getConfig().getString("whitelist.group", "none");
        luckPermsEnabled = getConfig().getBoolean("whitelist.luckperms_enable", false);
        Map<String, String> loaded = new HashMap<>();
        var section = getConfig().getConfigurationSection("messages");
        if (section != null) for (String key : section.getKeys(false)) {
            loaded.put(key, ChatColor.translateAlternateColorCodes('&', section.getString(key, key)));
        }
        messages = Map.copyOf(loaded);
    }

    private void startWhitelistCheckTask() {
        if (cleanup != null) cleanup.cancel(false);
        long interval = Durations.parse(getConfig().getString("whitelist.check_interval", "1h"));
        if (interval <= 0) { interval = TimeUnit.HOURS.toMillis(1); getLogger().warning("Invalid check_interval; using 1h."); }
        cleanup = io.scheduleAtFixedRate(() -> {
            try { if (databaseReady) databaseManager.cleanupWhitelist(); }
            catch (Exception e) { getLogger().log(Level.SEVERE, "Whitelist cleanup failed", e); }
        }, 1000, interval, TimeUnit.MILLISECONDS);
    }
}
