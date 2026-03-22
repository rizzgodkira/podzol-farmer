# Podzol Farmer Mod

A Minecraft Fabric mod for **1.21.1** that automatically plants and harvests spruce trees on podzol blocks near you.

---

## How It Works

### Cycle 1 — Scan & Plant
Every second, the mod scans for **podzol blocks within 10 blocks** of the player.

For each podzol found:
- **If the block above is empty** → the mod takes a **Spruce Sapling** from your inventory and places it on the podzol.
- **If the block above already has a spruce sapling** → it enters the "waiting" phase.
- **If a spruce log is already above the podzol** → it harvests it immediately.

### Cycle 2 — Wait & Harvest
Once a sapling is planted, the mod watches that podzol every 5 ticks (¼ second).

- When the sapling **grows into a tree** (a spruce log appears above the podzol) → the mod **breaks the entire log column** and drops all the wood as items.
- Leaves are also cleared automatically.

The cycle then resets — on the next scan, the bare podzol will get a new sapling planted.

---

## Requirements

| Requirement   | Version         |
|---------------|-----------------|
| Minecraft      | 1.21.1          |
| Fabric Loader  | ≥ 0.16.9        |
| Fabric API     | 0.102.0+1.21.1  |
| Java           | 21              |

---

## Installation

1. Install [Fabric Loader 0.16.9+](https://fabricmc.net/use/) for Minecraft 1.21.1
2. Download [Fabric API](https://modrinth.com/mod/fabric-api) for 1.21.1
3. Drop both `fabric-api-*.jar` and `podzol-farmer-*.jar` into your `mods/` folder
4. Launch the game

---

## Building from Source

```bash
git clone <this-repo>
cd podzol-farmer-mod
./gradlew build
```

The built JAR will be at `build/libs/podzol-farmer-1.0.0.jar`.

---

## Usage Tips

- **Keep spruce saplings in your inventory** — the mod pulls from any inventory slot.
- The mod works on both **singleplayer and multiplayer** (server-side logic).
- Podzol naturally accelerates sapling growth, so trees will appear quickly.
- The harvest drops items **at the log's position**, so stand nearby to collect them.
- The scan range is **spherical 10 blocks**, not a cube.

---

## Configuration

Currently no config file. To adjust:
- **Scan range**: change `range = 10` in `PodzolFarmerMod.java`
- **Scan interval**: change `SCAN_INTERVAL = 20` (ticks)
- **Log check frequency**: change `tickCounter % 5` (ticks)
