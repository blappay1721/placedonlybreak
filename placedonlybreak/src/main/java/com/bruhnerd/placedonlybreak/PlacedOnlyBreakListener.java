package com.bruhnerd.placedonlybreak;

import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

import org.bukkit.ChatColor;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.plugin.Plugin;

import com.sk89q.worldedit.bukkit.BukkitAdapter;
import com.sk89q.worldguard.WorldGuard;
import com.sk89q.worldguard.protection.ApplicableRegionSet;
import com.sk89q.worldguard.protection.regions.RegionQuery;

import net.coreprotect.CoreProtect;
import net.coreprotect.CoreProtectAPI;

public class PlacedOnlyBreakListener implements Listener {

    private final PlacedOnlyBreak plugin;
    private final CoreProtectAPI cp;
    private final RegionQuery regionQuery;

    // Cache to eliminate CoreProtect async logging delay
    // key -> epoch seconds when placed
    private final Map<BlockKey, Long> recentPlacements = new ConcurrentHashMap<>();

    // How long to keep placements in cache (seconds)
    private static final long CACHE_TTL_SECONDS = 10 * 1; // 10 seconds

    public PlacedOnlyBreakListener(PlacedOnlyBreak plugin) {
        this.plugin = plugin;

        Plugin cpPlugin = plugin.getServer().getPluginManager().getPlugin("CoreProtect");
        if (!(cpPlugin instanceof CoreProtect)) {
            throw new IllegalStateException("CoreProtect not found or not enabled.");
        }

        CoreProtect coreProtect = (CoreProtect) cpPlugin;
        this.cp = coreProtect.getAPI();
        if (this.cp == null || !this.cp.isEnabled()) {
            throw new IllegalStateException("CoreProtect API not available.");
        }

        this.regionQuery = WorldGuard.getInstance().getPlatform().getRegionContainer().createQuery();
    }

    /**
     * Record placements immediately so breaking right after placing works
     * even if CoreProtect hasn't written the placement yet.
     */
    @EventHandler(ignoreCancelled = true)
    public void onPlace(BlockPlaceEvent event) {
        Location loc = event.getBlockPlaced().getLocation();

        // Only care if location is within configured regions
        if (!isInsideAnyConfiguredRegion(loc)) return;

        // Apply activation cutoff: only placements after cutoff should be considered "breakable"
        long cutoff = getMaxActivationCutoff(loc);
        long now = nowEpochSeconds();

        if (cutoff > 0 && now < cutoff) {
            // region not armed yet (or cutoff in future), don't cache
            return;
        }

        recentPlacements.put(BlockKey.from(loc), now);
        cleanupOldCacheEntries(now);
    }

    /**
     * Option A behavior: do NOT use WorldGuard's block-break deny flag.
     * Let this plugin decide allow/deny.
     */
    @EventHandler(ignoreCancelled = true)
    public void onBreak(BlockBreakEvent event) {
        Player player = event.getPlayer();

        // Bypass for OPs
        if (player.isOp()) return;

        // Optional bypass permission
        String bypassPerm = plugin.getConfig().getString("bypass-permission", "");
        if (!bypassPerm.isEmpty() && player.hasPermission(bypassPerm)) return;

        Location loc = event.getBlock().getLocation();

        // Only enforce inside configured regions
        if (!isInsideAnyConfiguredRegion(loc)) return;

        // Allow only blocks placed after region activation time
        if (!wasPlacedAfterActivation(loc)) {
            event.setCancelled(true);
            player.sendMessage(color(plugin.getConfig().getString(
                    "deny-message",
                    "&cYou can only break player-placed blocks here."
            )));
            return;
        }

        // If allowed, remove from cache (optional cleanup)
        recentPlacements.remove(BlockKey.from(loc));
    }

    private boolean isInsideAnyConfiguredRegion(Location loc) {
        List<String> regions = plugin.getConfig().getStringList("regions");
        if (regions == null || regions.isEmpty()) return false;

        Set<String> regionSet = new HashSet<>();
        for (String r : regions) {
            if (r != null && !r.isBlank()) regionSet.add(r.toLowerCase());
        }
        if (regionSet.isEmpty()) return false;

        ApplicableRegionSet applicable = regionQuery.getApplicableRegions(BukkitAdapter.adapt(loc));
        return applicable.getRegions().stream()
                .anyMatch(r -> regionSet.contains(r.getId().toLowerCase()));
    }

    /**
     * Returns true if the block is considered "player-placed" AFTER the activation cutoff.
     * We accept either:
     *  - cache hit (immediate placement), OR
     *  - CoreProtect newest record says placed (actionCode==1) AND timestamp >= cutoff.
     */
    private boolean wasPlacedAfterActivation(Location loc) {
        boolean debug = plugin.getConfig().getBoolean("debug", false);

        long cutoff = getMaxActivationCutoff(loc);
        long now = nowEpochSeconds();

        // 1) Fast path: cache (fixes CoreProtect write delay)
        BlockKey key = BlockKey.from(loc);
        Long cachedTime = recentPlacements.get(key);
        if (cachedTime != null) {
            if (cutoff == 0 || cachedTime >= cutoff) {
                // Also expire stale cache entries
                if (now - cachedTime <= CACHE_TTL_SECONDS) return true;
                recentPlacements.remove(key);
            }
        }

        // 2) CoreProtect authoritative path
        try {
            List<String[]> results = cp.blockLookup(loc.getBlock(), 0);
            if (results == null || results.isEmpty()) {
                if (debug) plugin.getLogger().info("CoreProtect lookup: no results");
                return false;
            }

            if (debug) {
                plugin.getLogger().info("CoreProtect lookup rows: " + results.size() + " cutoff=" + cutoff);
                int limit = Math.min(results.size(), 5);
                for (int i = 0; i < limit; i++) {
                    plugin.getLogger().info(Arrays.toString(results.get(i)));
                }
            }

            // Newest row
            String[] newest = results.get(0);

            long eventTime = parseLongSafe(getSafe(newest, 0), -1L);
            int actionCode = parseIntSafe(getSafe(newest, 7), -1);

            if (actionCode != 1) return false;
            if (cutoff > 0 && eventTime < cutoff) return false;

            return true;

        } catch (Throwable t) {
            if (debug) {
                plugin.getLogger().warning("CoreProtect lookup failed: " + t.getClass().getSimpleName() + ": " + t.getMessage());
            }
            return false;
        }
    }

    /**
     * If multiple configured regions overlap, use MOST restrictive cutoff (max activation time).
     */
    private long getMaxActivationCutoff(Location loc) {
        ApplicableRegionSet applicable = regionQuery.getApplicableRegions(BukkitAdapter.adapt(loc));

        long cutoff = 0L;
        for (var r : applicable.getRegions()) {
            String id = r.getId().toLowerCase();
            if (!isConfiguredRegion(id)) continue;

            long regionCutoff = plugin.getConfig().getLong("region-activated-at." + id, 0L);
            if (regionCutoff > cutoff) cutoff = regionCutoff;
        }
        return cutoff;
    }

    private boolean isConfiguredRegion(String regionIdLower) {
        List<String> regions = plugin.getConfig().getStringList("regions");
        if (regions == null) return false;
        for (String r : regions) {
            if (r != null && r.equalsIgnoreCase(regionIdLower)) return true;
        }
        return false;
    }

    private void cleanupOldCacheEntries(long nowEpochSeconds) {
        // cheap periodic cleanup triggered on place events
        for (var it = recentPlacements.entrySet().iterator(); it.hasNext();) {
            var e = it.next();
            if (nowEpochSeconds - e.getValue() > CACHE_TTL_SECONDS) {
                it.remove();
            }
        }
    }

    private static long nowEpochSeconds() {
        return System.currentTimeMillis() / 1000L;
    }

    private static String getSafe(String[] arr, int idx) {
        if (arr == null || idx < 0 || idx >= arr.length) return null;
        return arr[idx];
    }

    private static int parseIntSafe(String s, int def) {
        if (s == null) return def;
        try {
            return Integer.parseInt(s.trim());
        } catch (NumberFormatException e) {
            return def;
        }
    }

    private static long parseLongSafe(String s, long def) {
        if (s == null) return def;
        try {
            return Long.parseLong(s.trim());
        } catch (NumberFormatException e) {
            return def;
        }
    }

    private String color(String s) {
        return ChatColor.translateAlternateColorCodes('&', s == null ? "" : s);
    }

    /**
     * Compact key for a block position (world + x/y/z).
     */
    private static final class BlockKey {
        private final String world;
        private final int x, y, z;

        private BlockKey(String world, int x, int y, int z) {
            this.world = world;
            this.x = x;
            this.y = y;
            this.z = z;
        }

        static BlockKey from(Location loc) {
            World w = loc.getWorld();
            String name = (w == null) ? "null" : w.getName();
            return new BlockKey(name, loc.getBlockX(), loc.getBlockY(), loc.getBlockZ());
        }

        @Override
        public boolean equals(Object o) {
            if (this == o) return true;
            if (!(o instanceof BlockKey other)) return false;
            return x == other.x && y == other.y && z == other.z && world.equals(other.world);
        }

        @Override
        public int hashCode() {
            int h = world.hashCode();
            h = 31 * h + x;
            h = 31 * h + y;
            h = 31 * h + z;
            return h;
        }
    }
}
