# HyFixes Early Plugin

Deep bytecode fixes for Hytale networking and entity systems. Companion to HyFixes Runtime.

## What It Fixes (16 Bytecode Patches)

- **Sync Buffer Overflow** - Fixes combat/food/tool desync (400-2500 errors per session)
- **Sync Position Gap** - Fixes "out of order" exception that kicks players
- **Instance Portal Race** - Fixes "player already in world" crash
- **Null SpawnController** - Fixes world crashes when spawn beacons load
- **Null Spawn Parameters** - Fixes world crashes in volcanic/cave biomes
- **Duplicate Block Components** - Fixes player kicks when using teleporters
- **Null npcReferences** - ROOT CAUSE FIX for spawn marker crashes
- **BlockCounter Not Decrementing** - Fixes teleporter limit stuck at 5
- **WorldMapTracker Iterator Crash** - Fixes ~30 min crashes on high-pop servers
- **ArchetypeChunk Stale Entity** - Fixes NPC system IndexOutOfBoundsException
- **Operation Timeout** - Fixes player kicks from network timeouts
- **Null UUID on Entity Remove** - Fixes crash on entity removal
- And more...

## Installation

1. Download `hyfixes-early.jar`
2. Place in your server's `earlyplugins/` folder
3. Set `ACCEPT_EARLY_PLUGINS=1` or press Enter at startup prompt

**Requires:** HyFixes Runtime plugin installed

## Support

- **Discord:** https://discord.gg/6g7McTd27z
- **GitHub:** https://github.com/John-Willikers/hyfixes
