package com.bruhnerd.placedonlybreak;

import org.bukkit.plugin.Plugin;
import org.bukkit.plugin.java.JavaPlugin;

public class PlacedOnlyBreak extends JavaPlugin {

    @Override
    public void onEnable() {
        saveDefaultConfig();

        // A dependency can be present but refuse to enable (e.g. CoreProtect on an MC version it doesn't support yet).
        // Touching its classes then throws NoClassDefFoundError, so bail out with a readable message instead.
        for (String dep : new String[]{"WorldGuard", "CoreProtect"}) {
            Plugin p = getServer().getPluginManager().getPlugin(dep);
            if (p == null || !p.isEnabled()) {
                getLogger().severe(dep + " is not enabled (check its startup log; it may not support this server version yet). Disabling PlacedOnlyBreak.");
                getServer().getPluginManager().disablePlugin(this);
                return;
            }
        }

        // Events
        getServer().getPluginManager().registerEvents(new PlacedOnlyBreakListener(this), this);

        // Commands
        PlacedOnlyBreakCommand cmd = new PlacedOnlyBreakCommand(this);
        if (getCommand("pob") != null) {
            getCommand("pob").setExecutor(cmd);
            getCommand("pob").setTabCompleter(cmd);
        } else {
            getLogger().severe("Command 'pob' not found. Check plugin.yml.");
        }

        getLogger().info("PlacedOnlyBreak enabled.");
    }
}
