# PlacedOnlyBreak (POB)

A small Paper plugin that restricts block breaking inside specific WorldGuard regions to **only blocks that players placed after the region was "armed."** Built for build-battle arenas, event maps, or any spot where players should be free to tear down what they (or others) built during a round, but shouldn't be able to dig into the surrounding terrain or a previous round's build.

CoreProtect is used as the source of truth for "was this block placed by a player," backed by a short in-memory cache so breaking a block you *just* placed always works instantly, without waiting on CoreProtect's async write.

## How it works

- Each configured region has an activation cutoff, `region-activated-at`, stored as a Unix epoch (seconds).
- When a player breaks a block inside a configured region:
  1. **OPs always bypass.** Anyone holding the configured bypass permission bypasses too.
  2. Otherwise, the plugin checks whether that exact block location was placed by a player at or after the region's cutoff:
     - **Fast path:** a 10-second in-memory cache of recent `BlockPlaceEvent`s — this exists purely to cover CoreProtect's async logging delay.
     - **Authoritative path:** a CoreProtect `blockLookup`, requiring the newest log entry for that block to be a placement (action code `1`) at or after the cutoff.
  3. If neither confirms a valid, in-window player placement, the break is cancelled and the deny message is sent.
- If a location falls inside more than one configured region, the **most restrictive** (latest) cutoff among them applies.

### What the cutoff actually means

`region-activated-at.<region>` has two distinct modes:

- **`0` (default / unset):** no time restriction — *any* block with a CoreProtect placement record is breakable, no matter when it was placed. Natural/world-generated terrain (no placement record) stays protected either way.
- **`> 0`:** only blocks placed **at or after** that timestamp are breakable. Everything placed earlier — including previous player builds — becomes locked.

Running `/pob arm <region>` sets the cutoff to *right now*, which is the intended way to reset an arena between rounds: it locks away everything currently standing (even player-built) and starts a fresh window where only new placements can be broken.

## Requirements

- Paper 1.21.5+ (Java 21, `api-version: 1.21`)
- [WorldGuard](https://enginehub.org/worldguard) 7.0.10+ (pulls in WorldEdit as a compile-time dependency)
- [CoreProtect](https://www.spigotmc.org/resources/coreprotect.8631/) — the server needs its own copy installed as a plugin. It's also required at **build time**: `libs/CoreProtect.jar` is referenced as a Maven `system`-scope dependency (CoreProtect isn't published to a public Maven repo), so that jar must exist in `libs/` before running a build. It's already included in this repo.

## Building

```bash
mvn clean package
```

Output: `target/placedonlybreak-1.0.0.jar`.

## Installation

1. Install WorldGuard and CoreProtect on the server if they aren't already present (both are hard dependencies — POB won't enable without them).
2. Drop `placedonlybreak-1.0.0.jar` into `plugins/`.
3. Start the server once to generate `config.yml`, or supply your own based on the template below.
4. Define the WorldGuard regions you want restricted, and list their IDs under `regions:` in POB's config.
5. Run `/pob arm <region>` when you want the "only new placements are breakable" window to start (e.g. right after resetting/rebuilding an arena).

## Commands

All subcommands require `placedonlybreak.admin`.

| Command | Description |
|---|---|
| `/pob reload` | Reload `config.yml` from disk. |
| `/pob arm <region>` | Set the region's cutoff to the current time — locks everything currently placed and starts a fresh breakable window. |
| `/pob addregion <region>` | Add a region ID to the `regions` list (and initialize its cutoff entry at `0` if missing). |
| `/pob delregion <region>` | Remove a region ID from the `regions` list. |

Tab-completion is provided for subcommands, and for region names on `arm` / `delregion`.

## Permissions

| Permission | Default | Description |
|---|---|---|
| `placedonlybreak.admin` | op | Use `/pob reload`, `arm`, `addregion`, `delregion` |
| `placedonlybreak.bypass` | op | Bypass break restrictions entirely inside configured regions |

## Configuration (`config.yml`)

```yaml
regions:
  - placedonly
  - arena_build

deny-message: "&cYou can only break player-placed blocks here."
bypass-permission: "placedonlybreak.bypass"
debug: false

# Epoch seconds. Only blocks placed AFTER this time count as "placed" for breaking.
# You can set these manually, or use: /pob arm <region>
region-activated-at:
  placedonly: 0
  arena_build: 0
```

| Key | Purpose |
|---|---|
| `regions` | WorldGuard region IDs POB enforces. Matched case-insensitively. |
| `deny-message` | Sent to a player when a break is blocked. Supports `&`-style color codes. |
| `bypass-permission` | Permission node that skips enforcement (independent of OP status, which always bypasses). |
| `debug` | Logs CoreProtect lookup results (row count, cutoff, first few rows) to console — useful when a cutoff isn't behaving as expected. |
| `region-activated-at` | Per-region cutoff; see [What the cutoff actually means](#what-the-cutoff-actually-means) above. |

## Known limitations / troubleshooting

- **Depends on CoreProtect actually logging placements.** If CoreProtect's own config excludes a world or block type, POB has no record to check and will treat the block as non-breakable (fails closed).
- **Region matching is ID-only.** Nested/overlapping non-configured regions are ignored; only regions whose ID appears in `regions:` are considered.
- **10-second cache TTL** is fixed in code (`CACHE_TTL_SECONDS`), not exposed in `config.yml`.
- **`src/main/java/com/bruhnerd/App.java`** is a leftover Maven-archetype "Hello World" placeholder, unrelated to the plugin (`PlacedOnlyBreak` is the actual entry point per `plugin.yml`). Safe to delete.

## Project structure

```
pom.xml
libs/CoreProtect.jar                              # system-scope build dependency
src/main/resources/plugin.yml                     # plugin metadata, command + permission registration
src/main/resources/config.yml                     # default config, copied out on first run
src/main/java/com/bruhnerd/placedonlybreak/
  PlacedOnlyBreak.java                             # onEnable: registers listener + command
  PlacedOnlyBreakListener.java                     # BlockPlaceEvent / BlockBreakEvent logic, CoreProtect + WorldGuard queries
  PlacedOnlyBreakCommand.java                      # /pob reload|arm|addregion|delregion
```
