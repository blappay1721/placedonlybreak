package com.bruhnerd.placedonlybreak;

import org.bukkit.plugin.java.JavaPlugin;

public class PlacedOnlyBreak extends JavaPlugin {

    @Override
    public void onEnable() {
        saveDefaultConfig();

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
