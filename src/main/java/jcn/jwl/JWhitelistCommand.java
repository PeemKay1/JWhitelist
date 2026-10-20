package jcn.jwl;

import org.bukkit.Bukkit;
import org.bukkit.command.*;
import org.bukkit.entity.Player;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Map;

public class JWhitelistCommand implements CommandExecutor, TabCompleter {
    private final JWhitelistDatabase databaseManager;
    private final JWhitelist plugin;
    private record Pending(List<String> names, long expiresAt) { }
    private final Map<String, Pending> pending = new java.util.concurrent.ConcurrentHashMap<>();
    private String senderKey(CommandSender sender) {
        return sender instanceof Player player ? player.getUniqueId().toString() : "console:" + sender.getName();
    }

    public JWhitelistCommand(JWhitelist plugin) {
        this.plugin = plugin;
        this.databaseManager = plugin.getDatabaseManager();
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        // Console automation captures the reply before dispatchCommand returns.
        // Keep its acknowledgement synchronous and only report committed writes.
        if (sender instanceof ConsoleCommandSender) return handle(sender, command, label, args);
        final String[] copiedArgs = args.clone();
        plugin.executeIo(() -> {
            try { handle(sender, command, label, copiedArgs); }
            catch (Exception exception) {
                plugin.getLogger().log(java.util.logging.Level.SEVERE, "Whitelist command failed", exception);
                plugin.reply(sender, "Whitelist operation failed; check the server log.");
            }
        });
        return true;
    }

    private boolean handle(CommandSender sender, Command command, String label, String[] args) {        if (args.length == 0) {
            return false;
        }

        String subCommand = args[0].toLowerCase(java.util.Locale.ROOT);

        switch (subCommand) {
            case "confirm":
            case "cancel": {
                if (!sender.hasPermission("jwhitelist.clear") && !(sender instanceof ConsoleCommandSender)) {
                    plugin.reply(sender, plugin.getMessage("no_permission"));
                    return true;
                }
                Pending request = pending.remove(senderKey(sender));
                if (request == null || request.expiresAt() < System.currentTimeMillis()) {
                    plugin.reply(sender, "No pending whitelist clear request.");
                    return true;
                }
                if (subCommand.equals("cancel")) {
                    plugin.reply(sender, plugin.getMessage("whitelist_clearing_canceled"));
                    return true;
                }
                java.util.List<String> removed = new java.util.ArrayList<>();
                for (String name : request.names()) {
                    if (Bukkit.getPlayerExact(name) == null) {
                        databaseManager.removePlayerFromWhitelist(name);
                        removed.add(name);
                    }
                }
                java.nio.file.Path log = plugin.getDataFolder().toPath().resolve("removed_players_" + System.currentTimeMillis() + ".txt");
                try { java.nio.file.Files.write(log, removed, java.nio.charset.StandardCharsets.UTF_8); }
                catch (java.io.IOException e) { throw new IllegalStateException("Cannot save removal report", e); }
                plugin.reply(sender, plugin.getMessage("whitelist_cleared"));
                return true;
            }
            case "reload":
                if (sender.hasPermission("jwhitelist.reload") || sender instanceof ConsoleCommandSender) {
                    plugin.reloadConfiguration();
                    plugin.reply(sender, plugin.getMessage("reload_success"));
                }
                else {
                    plugin.reply(sender, plugin.getMessage("no_permission"));
                }
                return true;

            case "clear":
                if (args.length < 2) {
                    plugin.reply(sender, "Usage: /jwhitelist clear <time>");
                    return true;
                }
                if (sender.hasPermission("jwhitelist.clear") || sender instanceof ConsoleCommandSender) {
                    long clearDuration = parseDuration(args[1]);
                    if (clearDuration == -1) {
                        plugin.reply(sender, plugin.getMessage("invalid_time_format"));
                        return true;
                    }
                    clearWhitelist(sender, clearDuration);
                }
                else {
                    plugin.reply(sender, plugin.getMessage("no_permission"));
                }
                return true;

            case "add":
                if (args.length < 2) {
                    plugin.reply(sender, "Usage: /jwhitelist add <player> [time]");
                    return true;
                }
                if (sender.hasPermission("jwhitelist.add") || sender instanceof ConsoleCommandSender) {
                    String playerName = args[1];
                    long expiry = (args.length == 3) ? parseDuration(args[2]) : -1;
                    if (expiry == -1 && args.length == 3) {
                        plugin.reply(sender, plugin.getMessage("invalid_time_format"));
                        return true;
                    }
                    databaseManager.addPlayerToWhitelist(playerName, expiry);
                    if (expiry == -1) {
                        plugin.reply(sender, plugin.getMessage("player_added").replace("%player%", playerName));
                    } else {
                        plugin.reply(sender, plugin.getMessage("player_added_temp").replace("%player%", playerName).replace("%time%", args[2]));
                    }
                } else {
                    plugin.reply(sender, plugin.getMessage("no_permission"));
                }
                return true;

            case "remove":
                if (args.length < 2) {
                    plugin.reply(sender, "Usage: /jwhitelist remove <player>");
                    return true;
                }
                if (sender.hasPermission("jwhitelist.remove") || sender instanceof ConsoleCommandSender) {
                    String playerName = args[1];
                    databaseManager.removePlayerFromWhitelist(playerName);
                    plugin.reply(sender, plugin.getMessage("player_removed").replace("%player%", playerName));
                    Player playerOnline = Bukkit.getPlayer(playerName);
                    if (playerOnline != null && playerOnline.isOnline()) {
                        plugin.runAtPlayer(playerOnline, () -> {
                            plugin.executeIo(() -> {
                                if (!databaseManager.isPlayerWhitelisted(playerName)) {
                                    plugin.runAtPlayer(playerOnline, () -> playerOnline.kickPlayer(plugin.getMessage("player_kicked")));
                                }
                            });
                        });
                    }
                } else {
                    plugin.reply(sender, plugin.getMessage("no_permission"));
                }
                return true;

            case "list":
                if (sender.hasPermission("jwhitelist.list") || sender instanceof ConsoleCommandSender) {
                    Map<String, Long> whitelistedPlayers = databaseManager.getAllWhitelistedPlayers();
                    if (whitelistedPlayers.isEmpty()) {
                        plugin.reply(sender, plugin.getMessage("whitelist_is_empty"));
                    } else {
                        plugin.reply(sender, plugin.getMessage("whitelisted_players"));
                        for (Map.Entry<String, Long> entry : whitelistedPlayers.entrySet()) {
                            String name = entry.getKey();
                            long remain = entry.getValue();
                            String timeString;
                            if (remain < -1) {
                                timeString = plugin.getMessage("expired");
                            } else if (remain == -1) {
                                timeString = plugin.getMessage("expires_never");
                            } else {
                                timeString = plugin.getMessage("expires_in").replace("%time%", formatDuration(remain));
                            }
                            plugin.reply(sender, "- " + name + " (" + timeString + ")");
                        }
                    }
                } else {
                    plugin.reply(sender, plugin.getMessage("no_permission"));
                }
                return true;
            case "info":
                if (args.length < 2) {
                    plugin.reply(sender, "Usage: /jwhitelist info <player>");
                    return true;
                }
                if (sender.hasPermission("jwhitelist.info") || sender instanceof ConsoleCommandSender) {
                    String playerName = args[1];
                    long timeLeft = databaseManager.getTimeLeftInWhitelist(playerName);
                    long lastLogin = databaseManager.getLastLoginTime(playerName);

                    if (timeLeft == -3) {
                        plugin.reply(sender, plugin.getMessage("no_player_info")
                                .replace("%player%", playerName));
                    }
                    else if (timeLeft == -2) {
                        plugin.reply(sender, plugin.getMessage("player_info")
                                .replace("%player%", playerName)
                                .replace("%time%", plugin.getMessage("expired"))
                                .replace("%last_login%", formatDuration(System.currentTimeMillis() - lastLogin)));
                    } else if (timeLeft == -1) {
                        plugin.reply(sender, plugin.getMessage("player_info")
                                .replace("%player%", playerName)
                                .replace("%time%", plugin.getMessage("expires_never"))
                                .replace("%last_login%", formatDuration(System.currentTimeMillis() - lastLogin)));
                    } else {
                        plugin.reply(sender, plugin.getMessage("player_info")
                                .replace("%player%", playerName)
                                .replace("%time%", formatDuration(timeLeft))
                                .replace("%last_login%", formatDuration(System.currentTimeMillis() - lastLogin)));
                    }
                } else {
                    plugin.reply(sender, plugin.getMessage("no_permission"));
                }
                return true;

            default:
                plugin.reply(sender, plugin.getMessage("unknown_subcommand").replace("%subcommand%", subCommand));
                return false;
        }
    }

    private String formatDuration(long durationMillis) {
        if (durationMillis == -1L) {
            return "Never expires";
        } else if (durationMillis <= 0) {
            return "Expired";
        }

        long seconds = durationMillis / 1000;
        long minutes = seconds / 60;
        long hours = minutes / 60;
        long days = hours / 24;
        long months = days / 30;

        if (months > 0) {
            return months + "m";
        } else if (days > 0) {
            return days + "d";
        } else if (hours > 0) {
            return hours + "h";
        } else if (minutes > 0) {
            return minutes + "min";
        } else {
            return seconds + "s";
        }
    }

    private long parseDuration(String duration) { return Durations.parse(duration); }

    private void clearWhitelist(CommandSender sender, long clearDuration) {
        List<String> playersToRemove = databaseManager.getPlayersToRemove(clearDuration);
        if (playersToRemove.isEmpty()) {
            plugin.reply(sender, plugin.getMessage("no_players_to_remove"));
            return;
        }

        plugin.reply(sender, plugin.getMessage("players_to_remove_found").replace("%count%", String.valueOf(playersToRemove.size())));
        plugin.reply(sender, plugin.getMessage("confirm_clear"));
        pending.put(senderKey(sender), new Pending(java.util.List.copyOf(playersToRemove), System.currentTimeMillis() + 60000));
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        if (args.length == 1) {
            return Arrays.asList("add", "remove", "reload", "clear", "list", "info");
        } else if (args.length == 2){
            if (args[0].equalsIgnoreCase("add")) return Arrays.asList(sender.getName());
            else if (args[0].equalsIgnoreCase("remove") || args[0].equalsIgnoreCase("info")) return databaseManager.getAllPlayerNameInWhitelist();
            return Collections.emptyList();
        } else {
            return Collections.emptyList();
        }
    }
}
