# Hytale Core Engine Bugs

This document catalogs bugs in Hytale's core engine that **cannot be fixed at the plugin level**. These require fixes from the Hytale development team.

Each bug includes:
- Detailed technical analysis
- Decompiled bytecode evidence
- Reproduction steps (where known)
- Suggested fixes for Hytale developers

**Last Updated:** 2026-01-21
**Hytale Version:** Early Access (2025+)
**Analysis Tool:** HyFixes Plugin Development

---

## Table of Contents

### Fixed via HyFixes Early Plugin (Bytecode Patches)
1. [InteractionChain Sync Buffer Overflow](#1-interactionchain-sync-buffer-overflow-critical) - **FIXED**
2. [World.addPlayer() Race Condition](#5-worldaddplayer-race-condition-critical) - **FIXED**
3. [SpawnReferenceSystems Null Controller](#6-spawnreferencesystems-null-controller-critical) - **FIXED**
4. [BeaconSpawnController Null Spawn](#7-beaconspawncontroller-null-spawn-critical) - **FIXED**
5. [BlockComponentChunk Duplicate Components](#8-blockcomponentchunk-duplicate-components-critical) - **FIXED**
6. [SpawnMarkerEntity Null npcReferences](#9-spawnmarkerentity-null-npcreferences-critical) - **FIXED** (ROOT CAUSE)
7. [TrackedPlacement BlockCounter](#10-trackedplacement-blockcounter-medium) - **FIXED**
8. [WorldMapTracker Iterator Crash](#11-worldmaptracker-iterator-crash-critical) - **FIXED**
9. [ArchetypeChunk Stale Entity Crash](#12-archetypechunk-stale-entity-crash-critical) - **FIXED**

### In Progress (HyFixes v1.10.0)
14. [InteractionChain Remove Out of Order](#14-interactionchain-remove-out-of-order-medium) - **PLANNED**
15. [InteractionManager Client Timeout](#15-interactionmanager-client-timeout-medium) - **PLANNED**

### Requires Hytale Developers
2. [Missing Replacement Interactions](#2-missing-replacement-interactions-medium)
3. [Client/Server Interaction Desync](#3-clientserver-interaction-desync-medium)
4. [World Task Queue Silent NPE](#4-world-task-queue-silent-npe-low)
13. [Client Fade Callback Race Condition](#13-client-fade-callback-race-condition-critical) - **CLIENT BUG**

---

## 1. InteractionChain Sync Buffer Overflow (CRITICAL)

> **STATUS: FIXED** - HyFixes Early Plugin v1.0.0+ patches this via bytecode transformation

### Summary

The `InteractionChain` class drops sync data when packets arrive out of order, causing combat, food consumption, and tool interactions to fail silently.

### Error Pattern

```
[SEVERE] [InteractionChain] Attempted to store sync data at 1. Offset: 3, Size: 0
[SEVERE] [InteractionChain] Attempted to store sync data at 5. Offset: 7, Size: 0
```

### Frequency

- **408-2,444 errors per 35-minute session** (varies by player activity)
- Spikes during: player login, combat, food consumption, tool use

### Affected Gameplay

| Feature | Impact |
|---------|--------|
| Combat damage | Hits may not register |
| Food consumption | Sound effects missing |
| Shield blocking | Defense may fail silently |
| Tool interactions | Chopping/mining desync |

### Technical Analysis

#### Affected Class
`com.hypixel.hytale.server.core.entity.InteractionChain`

#### Affected Method
`putInteractionSyncData(int index, InteractionSyncData data)`

#### Decompiled Bytecode

```java
public void putInteractionSyncData(int index, InteractionSyncData data) {
    // Line 1: Adjust index by current offset
    index = index - tempSyncDataOffset;

    // Line 2: BUG - If adjusted index is negative, data is silently dropped
    if (index < 0) {
        LOGGER.at(Level.SEVERE).log(
            "Attempted to store sync data at %d. Offset: %d, Size: %d",
            index + tempSyncDataOffset,  // Original index
            tempSyncDataOffset,
            tempSyncData.size()
        );
        return;  // <-- DATA LOST! No recovery attempt.
    }

    // Normal processing (only reached if index >= 0)
    if (index < tempSyncData.size()) {
        tempSyncData.set(index, data);
    } else if (index == tempSyncData.size()) {
        tempSyncData.add(data);
    } else {
        LOGGER.at(Level.WARNING).log("Gap in sequence: index=%d, size=%d",
            index, tempSyncData.size());
    }
}
```

#### Raw Bytecode Evidence

```
  public void putInteractionSyncData(int, InteractionSyncData);
    Code:
       0: iload_1
       1: aload_0
       2: getfield      #64    // Field tempSyncDataOffset:I
       5: isub                 // index = index - tempSyncDataOffset
       6: istore_1
       7: iload_1
       8: ifge          59     // if (index >= 0) goto normal_processing
      11: getstatic     #431   // LOGGER
      14: getstatic     #435   // Level.SEVERE
      ...
      56: goto          143    // return without storing data
```

### Root Cause

The `tempSyncDataOffset` field advances when sync data is consumed via `updateSyncPosition()`:

```java
public void updateSyncPosition(int position) {
    if (tempSyncDataOffset == position) {
        tempSyncDataOffset = position + 1;  // Advance offset
    } else if (position > tempSyncDataOffset) {
        throw new IllegalArgumentException("Gap detected");
    }
    // Note: position < tempSyncDataOffset is silently ignored
}
```

**The bug occurs when:**
1. Server processes sync data and advances `tempSyncDataOffset`
2. A new packet arrives with an index lower than current offset
3. The adjusted index becomes negative
4. Data is logged as error and silently dropped

**This is a network packet ordering/timing issue** - packets can arrive or be processed out of sequence, but the buffer doesn't handle this case.

### Why Plugin-Level Fix Is Impossible

| Approach | Why It Fails |
|----------|--------------|
| Hook via EntityTickingSystem | Error occurs inside method call, before any plugin tick |
| Reset buffers with reflection | Would cause worse desync - can't know correct state |
| Intercept method call | Called internally by Hytale code, no hook point |
| Pre-populate buffer | Can't predict needed size, doesn't fix timing |
| Replace component | Can't override Hytale's component registration |

### Suggested Fixes for Hytale

#### Option A: Handle Negative Index Gracefully

```java
public void putInteractionSyncData(int index, InteractionSyncData data) {
    int adjustedIndex = index - tempSyncDataOffset;

    if (adjustedIndex < 0) {
        // Instead of dropping, expand buffer backwards
        int expansion = -adjustedIndex;
        for (int i = 0; i < expansion; i++) {
            tempSyncData.add(0, null);  // Prepend nulls
        }
        tempSyncDataOffset = index;  // Reset offset to new base
        adjustedIndex = 0;
    }

    // Continue with normal processing...
}
```

#### Option B: Queue Out-of-Order Data

```java
private final Map<Integer, InteractionSyncData> pendingOutOfOrder = new HashMap<>();

public void putInteractionSyncData(int index, InteractionSyncData data) {
    int adjustedIndex = index - tempSyncDataOffset;

    if (adjustedIndex < 0) {
        // Queue for later processing
        pendingOutOfOrder.put(index, data);
        return;
    }

    // Normal processing...

    // After successful insert, check if any pending data can now be processed
    processPendingData();
}
```

#### Option C: Reset State on Invalid Condition

```java
public void putInteractionSyncData(int index, InteractionSyncData data) {
    int adjustedIndex = index - tempSyncDataOffset;

    if (adjustedIndex < 0) {
        // State is corrupt - reset and retry
        LOGGER.warning("Resetting sync buffer due to out-of-order data");
        tempSyncData.clear();
        tempSyncDataOffset = index;
        adjustedIndex = 0;
    }

    // Continue with normal processing...
}
```

### Log Evidence

Distribution from analyzed server session (2,444 total errors):

| Offset Value | Count | Percentage |
|--------------|-------|------------|
| Offset: 2 | ~1,777 | 72.7% |
| Offset: 7 | ~496 | 20.3% |
| Offset: 3 | ~117 | 4.8% |
| Other | ~54 | 2.2% |

---

## 2. Missing Replacement Interactions (MEDIUM)

### Summary

The interaction system fails to find replacement handlers for certain interaction variables, causing missing sound effects and potentially broken mechanics.

### Error Pattern

```
[SEVERE] [Hytale] Missing replacement interactions for interaction:
*Consume_Charge_Food_T1_Interactions_0 for var ConsumeSFX on item ItemStack{itemId=Plant_Fruit_Berries_Red...}
```

### Frequency

- **8-15 errors per session**
- Occurs during: eating, combat, shield use

### Affected Interactions

| Interaction | Variable | Context |
|-------------|----------|---------|
| `Consume_Charge_Food_T1` | `ConsumeSFX` | Eating berries/food |
| `Skeleton_Burnt_Soldier` | `Damage` | Combat with skeletons |
| `Shield_Block` | `Damage` | Blocking attacks |
| `NPC_Melee` | `Melee_Selector` | Goblin melee attacks |

### Technical Analysis

This appears to be a **content configuration issue** where interaction definitions reference variables that don't have replacement handlers configured.

#### Likely Location
- Interaction JSON/config files
- `InteractionManager.walkChain()` or related methods

### Root Cause

The interaction system uses a variable replacement mechanism where interaction templates can reference variables (like `ConsumeSFX`) that should be replaced with specific handlers. When no replacement is found, the error is logged.

### Why Plugin-Level Fix Is Impossible

- This is content/configuration data, not runtime code
- Interaction definitions are loaded at startup from game assets
- No plugin API to modify interaction configurations

### Suggested Fix for Hytale

1. Audit all interaction definitions for missing variable replacements
2. Add default/fallback handlers for common variables
3. Add validation at load time to catch missing replacements before runtime

---

## 3. Client/Server Interaction Desync (MEDIUM)

### Summary

The client and server interaction counters drift apart over time, causing action validation failures.

### Error Pattern

```
[WARN] [InteractionEntry] 2: Client/Server desync 3 != 0, 2987 != 2733
(for ****Empty_Interactions_Use_Interactions_0_Next_Failed)
```

### Frequency

- **20-30 warnings per session**
- Accumulates over play time

### Technical Analysis

The warning shows two pairs of mismatched values:
- `3 != 0` - Likely operation state mismatch
- `2987 != 2733` - Operation counter drift (254 operations behind)

This is **directly related to Bug #1** - when sync data is dropped, the counters drift apart.

### Root Cause

When `InteractionChain.putInteractionSyncData()` drops data (Bug #1), the server's state becomes inconsistent with what the client expects. Over time, this drift accumulates.

### Why Plugin-Level Fix Is Impossible

Same as Bug #1 - this is a consequence of the sync buffer overflow.

### Suggested Fix for Hytale

Fixing Bug #1 should significantly reduce or eliminate this issue. Additionally:

1. Add periodic client/server state reconciliation
2. Implement operation counter reset mechanism
3. Add drift detection with automatic resync

---

## 4. World Task Queue Silent NPE (LOW)

### Summary

The world task queue encounters NullPointerExceptions but doesn't provide stack traces, making diagnosis difficult.

### Error Pattern

```
[SEVERE] [World|default] Failed to run task!
java.lang.NullPointerException
```

### Frequency

- **10-15 errors per session**
- **60% correlate with player login events**

### Technical Analysis

The error is logged in what appears to be `World.consumeTaskQueue()` or similar, but **no stack trace is provided**, making it impossible to identify the specific task or null reference.

### Root Cause (Hypothesized)

Tasks are queued during player initialization that reference components not yet fully initialized. By the time the task executes, expected data is null.

### Why Plugin-Level Fix Is Impossible

- No stack trace means we can't identify which task fails
- Task queue is internal to World processing
- No plugin hook into task queue execution

### Suggested Fix for Hytale

1. **Add stack trace to error logging:**
```java
catch (NullPointerException e) {
    LOGGER.severe("Failed to run task!");
    LOGGER.severe(e);  // <-- Add this line
}
```

2. Add null checks in task execution
3. Defer tasks until component initialization is complete

---

## 5. World.addPlayer() Race Condition (CRITICAL)

> **STATUS: FIXED** - HyFixes Early Plugin v1.4.1+ patches this via bytecode transformation

### Summary

Hytale's `World.addPlayer()` throws an exception when a player enters an instance portal but hasn't been fully removed from their previous world yet.

### Error Pattern

```
java.lang.IllegalStateException: Player is already in a world
    at World.addPlayer(World.java:1008)
    at InstancesPlugin.teleportPlayerToLoadingInstance(InstancesPlugin.java:403)
```

### Root Cause

The `InstancesPlugin.teleportPlayerToLoadingInstance()` method uses async/CompletableFuture code that has a race condition between draining the player from one world and adding them to another.

### HyFixes Bytecode Fix

The early plugin transforms `World.addPlayer()` to log a warning and continue instead of throwing, allowing Hytale's drain logic to clean up the stale reference.

---

## 6. SpawnReferenceSystems Null Controller (CRITICAL)

> **STATUS: FIXED** - HyFixes Early Plugin v1.4.1+ patches this via bytecode transformation

### Summary

Hytale's `SpawnReferenceSystems$BeaconAddRemoveSystem.onEntityAdded()` crashes when `getSpawnController()` returns null.

### Error Pattern

```
java.lang.NullPointerException: Cannot invoke "SpawnController.registerBeacon(Ref)"
because "spawnController" is null
    at SpawnReferenceSystems$BeaconAddRemoveSystem.onEntityAdded(SpawnReferenceSystems.java:84)
```

### Root Cause

Spawn beacons can reference spawn controllers that don't exist or haven't loaded yet, causing `getSpawnController()` to return null.

### HyFixes Bytecode Fix

The early plugin injects a null check after the `getSpawnController()` call and returns early if null.

---

## 7. BeaconSpawnController Null Spawn (CRITICAL)

> **STATUS: FIXED** - HyFixes Early Plugin v1.4.4+ patches this via bytecode transformation

### Summary

Hytale's `BeaconSpawnController.createRandomSpawnJob()` crashes when `getRandomSpawn()` returns null.

### Error Pattern

```
java.lang.NullPointerException: Cannot invoke "RoleSpawnParameters.getId()"
because "spawn" is null
    at BeaconSpawnController.createRandomSpawnJob(BeaconSpawnController.java:110)
```

### Root Cause

Spawn beacons in volcanic/cave biomes can have misconfigured or missing spawn types. When `getRandomSpawn()` returns null, the subsequent `spawn.getId()` call crashes.

### HyFixes Bytecode Fix

The early plugin detects method calls returning `RoleSpawnParameters` and injects a null check after the result is stored to a local variable.

---

## 8. BlockComponentChunk Duplicate Components (CRITICAL)

> **STATUS: FIXED** - HyFixes Early Plugin v1.4.3+ patches this via bytecode transformation

### Summary

Hytale's `BlockComponentChunk.addEntityReference()` throws an exception when a duplicate block component is detected, instead of handling it gracefully.

### Error Pattern

```
java.lang.IllegalArgumentException: Duplicate block components at: 153349
    at BlockComponentChunk.addEntityReference(BlockComponentChunk.java:329)
    at BlockModule$BlockStateInfoRefSystem.onEntityAdded(BlockModule.java:334)
    at TeleporterSettingsPageSupplier.tryCreate(TeleporterSettingsPageSupplier.java:81)
```

### Root Cause

When interacting with teleporters, Hytale sometimes tries to add a block component entity reference that already exists. Instead of being idempotent, it throws.

### HyFixes Bytecode Fix

The early plugin transforms `addEntityReference()` to log a warning and return instead of throwing, making it idempotent.

---

## 9. SpawnMarkerEntity Null npcReferences (CRITICAL)

> **STATUS: FIXED** - HyFixes Early Plugin v1.5.0+ patches this via bytecode transformation (ROOT CAUSE FIX)

### Summary

The `SpawnMarkerEntity` class has an `npcReferences` field that is **never initialized** in the constructor, defaulting to `null`. This causes crashes when spawn markers are removed.

### Error Pattern

```
java.lang.NullPointerException: Cannot read the array length because "<local15>" is null
    at SpawnReferenceSystems$MarkerAddRemoveSystem.onEntityRemove(SpawnReferenceSystems.java:166)
```

### Frequency

- **7,853+ entities affected per session** on production servers
- Occurs every time spawn markers are created then removed
- Especially common in areas with mob spawners

### Root Cause

The `SpawnMarkerEntity` constructor doesn't initialize the `npcReferences` field:

```java
public class SpawnMarkerEntity {
    private InvalidatablePersistentRef<EntityStore>[] npcReferences;  // NEVER INITIALIZED!

    public SpawnMarkerEntity() {
        // npcReferences stays null!
    }

    public InvalidatablePersistentRef<EntityStore>[] getNpcReferences() {
        return npcReferences;  // Returns null
    }
}
```

When `MarkerAddRemoveSystem.onEntityRemove()` calls `getNpcReferences()` and tries to iterate:

```java
InvalidatablePersistentRef<EntityStore>[] refs = entity.getNpcReferences();
for (int i = 0; i < refs.length; i++) {  // CRASH: refs is null
    // ...
}
```

### Previous Fixes (Band-aids)

| Version | Fix | Approach |
|---------|-----|----------|
| v1.3.8 | SpawnMarkerReferenceSanitizer | Runtime: Check every tick, initialize if null |
| v1.4.6 | MarkerAddRemoveSystemTransformer | Bytecode: Null check in onEntityRemove() |

Both prevented the crash but didn't fix the root cause.

### HyFixes ROOT CAUSE Fix (v1.5.0)

The early plugin now transforms the `SpawnMarkerEntity` constructor to initialize the field:

```java
// Injected bytecode at constructor start
this.npcReferences = new InvalidatablePersistentRef[0];
```

**This is the definitive fix** - the array is never null because it's initialized when the entity is created.

### Performance Impact

| Approach | When It Runs | Performance |
|----------|--------------|-------------|
| Runtime Sanitizer | Every tick, every spawn marker | High overhead |
| Removal Null Check | On entity removal | Low overhead |
| Constructor Fix | Once at entity creation | **Negligible overhead** |

---

## 10. TrackedPlacement BlockCounter (MEDIUM)

> **STATUS: FIXED** - HyFixes Early Plugin v1.6.0+ patches this via bytecode transformation

### Summary

When teleporters are deleted, the `BlockCounter` placement count is not decremented, causing players to permanently hit the 5 teleporter limit.

### Error Pattern

No error is thrown - the bug is silent. Players simply cannot place new teleporters after reaching the limit, even after deleting all existing ones.

### Root Cause

`TrackedPlacement$OnAddRemove.onEntityRemove()` assumes the `TrackedPlacement` component is always present during entity removal, but due to component removal ordering, it may already be null.

### HyFixes Bytecode Fix

The early plugin transforms `onEntityRemove()` to add null checks and gracefully handle missing components.

---

## 11. WorldMapTracker Iterator Crash (CRITICAL)

> **STATUS: FIXED** - HyFixes Early Plugin v1.6.0+ patches this via bytecode transformation

### Summary

Hytale's `WorldMapTracker.unloadImages()` crashes due to FastUtil `LongOpenHashSet` iterator corruption during chunk unloading.

### Error Pattern

```
java.lang.NullPointerException
    at it.unimi.dsi.fastutil.longs.LongOpenHashSet$SetIterator.remove(LongOpenHashSet.java:...)
    at WorldMapTracker.unloadImages(WorldMapTracker.java:...)
```

### Frequency

- Crashes approximately every **30 minutes** on servers with **35+ players**
- Occurs during world map chunk unloading operations

### Root Cause

FastUtil's `LongOpenHashSet.iterator().remove()` can corrupt the iterator's internal state when the underlying hash set rehashes:

1. Iterator is created over the set
2. `remove()` is called which may trigger rehash
3. Internal position tracking becomes invalid
4. Next access throws NPE

### HyFixes Bytecode Fix

The early plugin wraps `unloadImages()` in a try-catch that catches `NullPointerException` and returns gracefully instead of crashing.

---

## 12. ArchetypeChunk Stale Entity Crash (CRITICAL)

> **STATUS: FIXED** - HyFixes Early Plugin v1.6.1+ patches this via bytecode transformation

### Summary

Hytale's `ArchetypeChunk.getComponent()` throws `IndexOutOfBoundsException` when NPC systems try to access components from entities that have already been removed from the archetype chunk.

### Error Pattern

```
java.lang.IndexOutOfBoundsException: Index out of range: 0
    at com.hypixel.hytale.component.ArchetypeChunk.getComponent(ArchetypeChunk.java:159)
    at com.hypixel.hytale.component.Store.__internal_getComponent(Store.java:1228)
    at com.hypixel.hytale.component.CommandBuffer.getComponent(CommandBuffer.java:115)
    at com.hypixel.hytale.server.npc.role.support.EntityList.add(EntityList.java:139)
    at com.hypixel.hytale.server.npc.systems.PositionCacheSystems$UpdateSystem.addEntities(PositionCacheSystems.java:384)
```

### Frequency

- Server crashes repeatedly during gameplay
- Occurs when NPC/flock systems access stale entity references
- More common on servers with many NPCs and active spawn beacons

### Technical Analysis

#### Root Cause

Entity references become stale but aren't cleaned up before being accessed:

1. Entity is added to position cache or entity list
2. Entity is removed from its archetype chunk (despawned)
3. NPC system still holds reference to the entity
4. System tries to get component from removed entity
5. `ArchetypeChunk.getComponent()` throws IndexOutOfBoundsException because chunk is empty

#### Related Errors

The crash is often preceded by:
```
[WARN] NPCEntity despawning due to lost marker: Ref{...}
[SEVERE] Failed to run task!
java.lang.IllegalArgumentException: Entity contains component type: StepComponent
```

### HyFixes Bytecode Fix

The early plugin wraps `getComponent()` in a try-catch for `IndexOutOfBoundsException`:

```java
// Transformed method
public Component getComponent(...) {
    try {
        // Original method body
        return component;
    } catch (IndexOutOfBoundsException e) {
        System.out.println("[HyFixes-Early] WARNING: getComponent() IndexOutOfBounds - returning null");
        return null;
    }
}
```

This prevents the crash and allows the calling code to handle null gracefully.

### Related Issues

- **GitHub Issue:** [#20](https://github.com/John-Willikers/hyfixes/issues/20)
- **Reporter:** Weark

---

## 13. Client Fade Callback Race Condition (CRITICAL)

> **STATUS: CLIENT BUG** - Cannot be fixed server-side. Requires Hytale client patch. Server-side mitigation may be possible.

### Summary

The Hytale client crashes when it receives a second `JoinWorldPacket` while still processing the fade animation from a previous world join. This primarily affects instance portal transitions in multiplayer.

### Error Pattern (Client-Side)

```
System.InvalidOperationException: Cannot start a fade out while a fade completion callback is pending.
   at HytaleClient!<BaseAddress>+0x1f22cf
   at HytaleClient!<BaseAddress>+0x1e7383
   at HytaleClient!<BaseAddress>+0x68448b
```

### Server-Side Symptoms

After the client crash, the server logs show cleanup failures:

```
[SEVERE] [ChunkStore] Failed to generate chunk! 87, -84
java.util.concurrent.CompletionException: java.lang.IllegalThreadStateException:
    World thread is not accepting tasks: instance-Portals_Taiga-...
```

### Frequency

- Common in **multiplayer** (2+ players online)
- Rare but possible in single player
- Occurs when entering instance portals (dungeons, caves)
- More likely during slow instance generation (~5+ seconds)

### Reproduction Steps

1. Join server with multiple players
2. Open an instance portal (taiga dungeon, etc.)
3. Enter the portal
4. Wait during the fade-to-black loading screen
5. Sometimes crashes with fade callback error

### Technical Analysis

#### Timeline from Logs

| Timestamp | Event |
|-----------|-------|
| 02:08:24.0038 | First `JoinWorldPacket` - FadeInOut starts |
| 02:08:24.1659 | `PrepareJoiningWorld()` begins |
| 02:08:44.4416 | `OnWorldJoined()` completes (**20 seconds later!**) |
| 02:08:44.7557 | **Second `JoinWorldPacket` received immediately** |
| 02:08:44.7786 | ProcessJoinWorldPacket tries to start another fade |
| 02:08:45.3345 | **CRASH** - Fade callback still pending |

#### Root Cause

The client's fade system is **not reentrant**:

1. First world join triggers fade-out animation with a completion callback
2. Client takes ~20 seconds to load instance world (generation, asset loading)
3. `OnWorldJoined()` fires, triggering fade-in
4. Player immediately enters portal in the instance
5. Server sends second `JoinWorldPacket`
6. Client tries to start new fade-out while previous callback is pending
7. `InvalidOperationException` crashes the client

#### Pseudocode of Client Bug

```csharp
// HytaleClient fade system (hypothetical)
public void StartFadeOut(Action onComplete) {
    if (pendingCallback != null) {
        // BUG: Should queue the fade or wait, not throw
        throw new InvalidOperationException(
            "Cannot start a fade out while a fade completion callback is pending."
        );
    }
    pendingCallback = onComplete;
    // Start fade animation...
}
```

### Why Plugin-Level Fix Is Impossible

| Approach | Why It Fails |
|----------|--------------|
| Intercept JoinWorldPacket | Packet is sent from server, but crash is client-side |
| Delay portal activation | Would need client mod - can't modify client |
| Modify fade system | Client code, not accessible to server plugins |

### Potential Server-Side Mitigation

While we cannot fix the client bug, we may be able to **reduce the likelihood** by:

1. **Rate-limit JoinWorldPackets per player** - Track last send time, add minimum delay
2. **Track player transfer state** - Block portal interactions while mid-transfer
3. **Delay ClientReady response** - Give client more time to finish fade

**Note:** These are mitigations, not fixes. The underlying client bug remains.

### Suggested Fix for Hytale (Client)

#### Option A: Queue Fade Requests

```csharp
public void StartFadeOut(Action onComplete) {
    if (pendingCallback != null) {
        // Queue this fade request instead of throwing
        pendingFadeQueue.Enqueue(new FadeRequest(FadeType.Out, onComplete));
        return;
    }
    pendingCallback = onComplete;
    // Start fade animation...
}
```

#### Option B: Cancel Previous Fade

```csharp
public void StartFadeOut(Action onComplete) {
    if (pendingCallback != null) {
        // Cancel previous fade and invoke its callback immediately
        var previousCallback = pendingCallback;
        pendingCallback = null;
        previousCallback?.Invoke();
    }
    pendingCallback = onComplete;
    // Start fade animation...
}
```

#### Option C: Wait for Pending Fade

```csharp
public async void StartFadeOut(Action onComplete) {
    // Wait for any pending fade to complete
    while (pendingCallback != null) {
        await Task.Yield();
    }
    pendingCallback = onComplete;
    // Start fade animation...
}
```

### Related Issues

- **GitHub Issue:** [#39](https://github.com/John-Willikers/hyfixes/issues/39)
- **Reporter:** Community bug report
- **Hytale Version:** 2026.01.17-4b0f30090

---

## 14. InteractionChain Remove Out of Order (MEDIUM)

> **STATUS: PLANNED** - Fix planned for HyFixes Early Plugin v1.10.0

### Summary

Hytale's `InteractionChain.removeInteractionEntry()` throws an `IllegalArgumentException` when trying to remove an interaction entry out of order. This kicks players during rapid disconnects or when interaction state becomes inconsistent.

### Error Pattern

```
java.lang.IllegalArgumentException: Trying to remove out of order
    at com.hypixel.hytale.server.core.entity.InteractionChain.removeInteractionEntry(InteractionChain.java:...)
```

### Frequency

- Sporadic, more common with high-latency connections
- Occurs during rapid player disconnects/reconnects
- Can happen during interaction chain cleanup

### Technical Analysis

#### Affected Class
`com.hypixel.hytale.server.core.entity.InteractionChain`

#### Affected Method
`removeInteractionEntry(InteractionManager manager, int index)`

#### Method Signature (from bytecode)
```
public void removeInteractionEntry(Lcom/hypixel/hytale/server/core/entity/InteractionManager;I)V
```

#### Root Cause

The method enforces strict FIFO (First-In-First-Out) removal order. When network issues or rapid state changes cause entries to be removed out of sequence, the validation throws:

```java
public void removeInteractionEntry(InteractionManager manager, int index) {
    // Validation that throws if removal isn't in expected order
    if (/* index doesn't match expected removal order */) {
        throw new IllegalArgumentException("Trying to remove out of order");
    }
    // ... actual removal logic
}
```

### Why Plugin-Level Fix Is Impossible

| Approach | Why It Fails |
|----------|--------------|
| Hook removal calls | Called internally by Hytale code, no plugin hook point |
| Track removal order | State is internal to InteractionChain |
| Reset on error | Would cause worse interaction desync |

### HyFixes Bytecode Fix (Planned)

The early plugin will wrap `removeInteractionEntry()` in a try-catch to catch `IllegalArgumentException` and log instead of crashing:

```java
// Transformed method
public void removeInteractionEntry(InteractionManager mgr, int index) {
    try {
        // Original method body
    } catch (IllegalArgumentException e) {
        LOGGER.warn("[HyFixes] Suppressed out-of-order removal: {}", e.getMessage());
        // Return gracefully instead of crashing
    }
}
```

### Related Issues

- **GitHub Issue:** [#40](https://github.com/John-Willikers/hyfixes/issues/40)
- **Related Bug:** Part of the broader InteractionChain sync issues (see Bug #1, #3)

---

## 15. InteractionManager Client Timeout (MEDIUM)

> **STATUS: PLANNED** - Fix planned for HyFixes Early Plugin v1.10.0

### Summary

Hytale's `InteractionManager.serverTick()` throws a `RuntimeException` when a client takes too long to send `clientData`. This kicks players during network lag spikes or when the client is busy processing.

### Error Pattern

```
java.lang.RuntimeException: Client took too long to send clientData for entity ...
    at com.hypixel.hytale.server.core.entity.InteractionManager.serverTick(InteractionManager.java:...)
```

### Frequency

- Common during lag spikes
- More frequent on busy servers with many players
- Occurs when clients are loading assets or processing world data

### Technical Analysis

#### Affected Class
`com.hypixel.hytale.server.core.entity.InteractionManager`

#### Affected Method
`serverTick(Ref<EntityStore> entityRef, InteractionChain chain, long currentTick)`

#### Method Signature (from bytecode)
```
private Lcom/hypixel/hytale/server/core/entity/InteractionSyncData;serverTick(Lcom/hypixel/hytale/component/Ref;Lcom/hypixel/hytale/server/core/entity/InteractionChain;J)Lcom/hypixel/hytale/server/core/entity/InteractionSyncData;
```

#### Root Cause

The `serverTick()` method enforces a strict timeout window for client responses. When the client doesn't respond in time (due to network latency, client-side lag, or slow processing), the method throws a `RuntimeException`:

```java
private InteractionSyncData serverTick(...) {
    // Check if client response is overdue
    if (/* timeout exceeded */) {
        throw new RuntimeException(
            "Client took too long to send clientData for entity " + entityId
        );
    }
    // ... normal processing
}
```

### Why Plugin-Level Fix Is Impossible

| Approach | Why It Fails |
|----------|--------------|
| Intercept tick | Internal method, not exposed to plugins |
| Extend timeout | Value is hardcoded, not configurable |
| Track client latency | No plugin API for individual interaction timeouts |

### HyFixes Bytecode Fix (Planned)

The early plugin will create a new `InteractionManagerTransformer` that wraps the timeout check in a try-catch:

```java
// Transformed method
private InteractionSyncData serverTick(...) {
    try {
        // Original timeout check
        if (/* timeout exceeded */) {
            throw new RuntimeException("Client took too long...");
        }
        // ... normal processing
    } catch (RuntimeException e) {
        if (e.getMessage() != null && e.getMessage().contains("Client took too long")) {
            LOGGER.warn("[HyFixes] Suppressed client timeout: {}", e.getMessage());
            // Cancel interaction gracefully instead of crashing
            return null;
        }
        throw e; // Re-throw if different exception
    }
}
```

### Related Issues

- **GitHub Issue:** [#40](https://github.com/John-Willikers/hyfixes/issues/40)
- **Related Bug:** Part of the broader InteractionChain sync issues (see Bug #1, #3)

---

## Appendix A: How to Reproduce

### Bug #1: InteractionChain Overflow

1. Have multiple players on server
2. Engage in rapid combat (melee + ranged)
3. Eat food while moving
4. Use tools rapidly (chop trees, mine)
5. Monitor logs for SEVERE InteractionChain messages

### Bug #2: Missing Replacements

1. Eat red berries (or other T1 food)
2. Block attacks with shield
3. Fight skeleton enemies
4. Monitor logs for "Missing replacement interactions"

### Bug #3: Client/Server Desync

1. Play normally for 30+ minutes
2. Perform many interactions (combat, tools, food)
3. Monitor logs for "Client/Server desync" warnings
4. Note: Frequency increases with playtime

### Bug #4: Task Queue NPE

1. Have players join/leave server
2. Monitor logs immediately after each join
3. Note correlation with login events

---

## Appendix B: Data Collection

To help Hytale developers, server admins can collect data:

### Log Collection Script

```bash
#!/bin/bash
# Extract Hytale core bugs from server log
LOG_FILE="$1"

echo "=== InteractionChain Overflow ==="
grep -c "Attempted to store sync data" "$LOG_FILE"

echo "=== Missing Replacements ==="
grep -c "Missing replacement interactions" "$LOG_FILE"

echo "=== Client/Server Desync ==="
grep -c "Client/Server desync" "$LOG_FILE"

echo "=== Task Queue NPE ==="
grep -c "Failed to run task" "$LOG_FILE"
```

### HyFixes Status Command

The `/interactionstatus` command (alias: `/hyfixes`) shows:
- Crashes prevented by HyFixes
- Known unfixable bug documentation
- Links to this document

---

## Appendix C: Version History

| Date | Hytale Version | Bugs Documented |
|------|----------------|-----------------|
| 2026-01-17 | Early Access | Initial documentation |

---

## Contributing

Found another Hytale core bug that can't be fixed at the plugin level?

1. Document the error pattern
2. Attempt to decompile and analyze
3. Confirm plugin-level fix is impossible
4. Open a PR to add to this document

**Repository:** [github.com/John-Willikers/hyfixes](https://github.com/John-Willikers/hyfixes)
