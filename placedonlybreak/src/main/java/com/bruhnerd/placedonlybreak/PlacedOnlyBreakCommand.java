package com.bruhnerd.placedonlybreak;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

import org.bukkit.ChatColor;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;

public class PlacedOnlyBreakCommand implements CommandExecutor, TabCompleter {

    private final PlacedOnlyBreak plugin;

    public PlacedOnlyBreakCommand(PlacedOnlyBreak plugin) {
        this.plugin = plugin;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {

        if (!sender.hasPermission("placedonlybreak.admin")) {
            sender.sendMessage(color("&cYou don't have permission to use this command."));
            return true;
        }

        if (args.length == 0) {
            help(sender);
            return true;
        }

        String sub = args[0].toLowerCase();

        switch (sub) {
            case "reload" -> {
                plugin.reloadConfig();
                sender.sendMessage(color("&aPlacedOnlyBreak config reloaded."));
                return true;
            }

            case "arm" -> {
                if (args.length < 2) {
                    sender.sendMessage(color("&cUsage: /pob arm <region>"));
                    return true;
                }
                String region = args[1].toLowerCase();
                if (!isConfiguredRegion(region)) {
                    sender.sendMessage(color("&cRegion '&f" + region + "&c' is not in config.yml under 'regions:'."));
                    return true;
                }
                long now = Instant.now().getEpochSecond();
                plugin.getConfig().set("region-activated-at." + region, now);
                plugin.saveConfig();
                sender.sendMessage(color("&aArmed region '&f" + region + "&a' at epoch &f" + now + "&a."));
                return true;
            }

            case "addregion" -> {
                if (args.length < 2) {
                    sender.sendMessage(color("&cUsage: /pob addregion <region>"));
                    return true;
                }
                String region = args[1].toLowerCase();

                List<String> regions = plugin.getConfig().getStringList("regions");
                if (regions.stream().anyMatch(r -> r != null && r.equalsIgnoreCase(region))) {
                    sender.sendMessage(color("&eRegion '&f" + region + "&e' is already in the config."));
                    return true;
                }

                regions.add(region);
                plugin.getConfig().set("regions", regions);

                // Initialize activation entry if missing
                if (!plugin.getConfig().contains("region-activated-at." + region)) {
                    plugin.getConfig().set("region-activated-at." + region, 0L);
                }

                plugin.saveConfig();
                sender.sendMessage(color("&aAdded region '&f" + region + "&a' to config. (Tip: /pob arm " + region + ")"));
                return true;
            }

            case "delregion" -> {
                if (args.length < 2) {
                    sender.sendMessage(color("&cUsage: /pob delregion <region>"));
                    return true;
                }
                String region = args[1].toLowerCase();

                List<String> regions = plugin.getConfig().getStringList("regions");
                boolean removed = regions.removeIf(r -> r != null && r.equalsIgnoreCase(region));

                if (!removed) {
                    sender.sendMessage(color("&eRegion '&f" + region + "&e' was not in the config."));
                    return true;
                }

                plugin.getConfig().set("regions", regions);

                // Optional: also remove activation time entry (comment out if you prefer to keep it)
                // plugin.getConfig().set("region-activated-at." + region, null);

                plugin.saveConfig();
                sender.sendMessage(color("&aRemoved region '&f" + region + "&a' from config."));
                return true;
            }

            default -> {
                sender.sendMessage(color("&cUnknown subcommand."));
                help(sender);
                return true;
            }
        }
    }

    private void help(CommandSender sender) {
        sender.sendMessage(color("&e/pob reload"));
        sender.sendMessage(color("&e/pob arm <region>"));
        sender.sendMessage(color("&e/pob addregion <region>"));
        sender.sendMessage(color("&e/pob delregion <region>"));
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        List<String> out = new ArrayList<>();
        if (!sender.hasPermission("placedonlybreak.admin")) return out;

        if (args.length == 1) {
            String p = args[0].toLowerCase();
            if ("reload".startsWith(p)) out.add("reload");
            if ("arm".startsWith(p)) out.add("arm");
            if ("addregion".startsWith(p)) out.add("addregion");
            if ("delregion".startsWith(p)) out.add("delregion");
            return out;
        }

        if (args.length == 2 && (args[0].equalsIgnoreCase("arm") || args[0].equalsIgnoreCase("delregion"))) {
            String p = args[1].toLowerCase();
            for (String r : plugin.getConfig().getStringList("regions")) {
                if (r != null && r.toLowerCase().startsWith(p)) out.add(r);
            }
            return out;
        }

        return out;
    }

    private boolean isConfiguredRegion(String regionIdLower) {
        for (String r : plugin.getConfig().getStringList("regions")) {
            if (r != null && r.equalsIgnoreCase(regionIdLower)) return true;
        }
        return false;
    }

    private String color(String s) {
        return ChatColor.translateAlternateColorCodes('&', s == null ? "" : s);
    }
}
