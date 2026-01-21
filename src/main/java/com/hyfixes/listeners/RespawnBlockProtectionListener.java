package com.hyfixes.listeners;

import com.hyfixes.HyFixes;
import com.hyfixes.config.ConfigManager;
import com.hyfixes.data.BedChunkDatabase;
import com.hyfixes.systems.ChunkProtectionRegistry;
import com.hypixel.hytale.component.AddReason;
import com.hypixel.hytale.component.CommandBuffer;
import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.RemoveReason;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.component.query.Query;
import com.hypixel.hytale.component.system.RefSystem;
import com.hypixel.hytale.server.core.universe.Universe;
import com.hypixel.hytale.server.core.universe.PlayerRef;
import com.hypixel.hytale.server.core.universe.world.World;
import com.hypixel.hytale.server.core.universe.world.chunk.WorldChunk;
import com.hypixel.hytale.server.core.universe.world.meta.state.RespawnBlock;
import com.hypixel.hytale.server.core.universe.world.storage.ChunkStore;
import java.lang.reflect.Method;

import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.logging.Level;

/**
 * Listens for bed/respawn block creation and updates chunk protection.
 *
 * Issue #44 Fix: Only protects chunks for beds when the owner is currently online.
 *
 * Uses SQLite database to store bed -> chunk mappings:
 * - When a bed is placed: Store (playerUUID, worldName, chunkIndex) in DB
 * - When a bed is destroyed: Remove from DB
 * - Periodic sync: Check who's online and protect/unprotect accordingly
 */
public class RespawnBlockProtectionListener extends RefSystem<ChunkStore> {

    private final HyFixes plugin;
    private final ChunkProtectionRegistry registry;
    private final BedChunkDatabase database;

    private boolean loggedOnce = false;

    private int bedsDiscovered = 0;
    private int bedsWithOwners = 0;
    private int chunksCurrentlyProtected = 0;

    // Track currently protected chunks: (worldName:chunkIndex) -> ownerUUID
    private final Map<String, UUID> protectedChunks = new ConcurrentHashMap<>();

    // Track online players (updated by sync task)
    private final Set<UUID> onlinePlayers = ConcurrentHashMap.newKeySet();

    // Scheduled executor for periodic sync
    private ScheduledExecutorService scheduler;

    // Sync interval in seconds
    private static final int SYNC_INTERVAL_SECONDS = 5;

    public RespawnBlockProtectionListener(HyFixes plugin, ChunkProtectionRegistry registry, BedChunkDatabase database) {
        this.plugin = plugin;
        this.registry = registry;
        this.database = database;
    }

    /**
     * Start the periodic sync task.
     */
    public void startSyncTask() {
        scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "HyFixes-BedProtectionSync");
            t.setDaemon(true);
            return t;
        });

        scheduler.scheduleAtFixedRate(this::syncProtections, SYNC_INTERVAL_SECONDS, SYNC_INTERVAL_SECONDS, TimeUnit.SECONDS);

        plugin.getLogger().at(Level.INFO).log(
            "[RespawnBlockProtection] Started protection sync task (every %d seconds)", SYNC_INTERVAL_SECONDS
        );
    }

    /**
     * Stop the sync task.
     */
    public void stopSyncTask() {
        if (scheduler != null) {
            scheduler.shutdown();
            try {
                if (!scheduler.awaitTermination(5, TimeUnit.SECONDS)) {
                    scheduler.shutdownNow();
                }
            } catch (InterruptedException e) {
                scheduler.shutdownNow();
            }
        }
    }

    @Override
    public Query<ChunkStore> getQuery() {
        return RespawnBlock.getComponentType();
    }

    @Override
    public void onEntityAdded(
            Ref<ChunkStore> ref,
            AddReason reason,
            Store<ChunkStore> store,
            CommandBuffer<ChunkStore> commandBuffer
    ) {
        if (!loggedOnce) {
            plugin.getLogger().at(Level.INFO).log(
                "[RespawnBlockProtection] Active - monitoring bed/respawn block creation (SQLite + online-only mode)"
            );
            loggedOnce = true;
        }

        try {
            // Get the respawn block component
            RespawnBlock respawnBlock = store.getComponent(ref, RespawnBlock.getComponentType());
            if (respawnBlock == null) {
                return;
            }

            bedsDiscovered++;

            // Get owner UUID
            UUID ownerUUID = null;
            try {
                ownerUUID = respawnBlock.getOwnerUUID();
            } catch (Exception e) {
                plugin.getLogger().at(Level.FINE).log(
                    "[RespawnBlockProtection] Could not get owner UUID: %s", e.getMessage()
                );
            }

            // Skip beds without an owner - these are pre-generated or unclaimed
            if (ownerUUID == null) {
                plugin.getLogger().at(Level.INFO).log(
                    "[RespawnBlockProtection] Bed #%d has no owner (pre-generated)", bedsDiscovered
                );
                return;
            }

            bedsWithOwners++;
            String ownerInfo = ownerUUID.toString().substring(0, 8) + "...";
            plugin.getLogger().at(Level.INFO).log(
                "[RespawnBlockProtection] Bed #%d has owner: %s", bedsDiscovered, ownerInfo
            );

            // Get chunk index from RespawnBlock's position (like TeleporterProtectionListener)
            long chunkIndex = getChunkIndexFromRespawnBlock(respawnBlock, store, ref);
            WorldChunk worldChunk = null;

            // Try to get WorldChunk for keepLoaded flag
            try {
                worldChunk = store.getComponent(ref, WorldChunk.getComponentType());
            } catch (Exception ignored) {}

            if (chunkIndex == -1L) {
                plugin.getLogger().at(Level.WARNING).log(
                    "[RespawnBlockProtection] Bed #%d - could not determine chunk index!", bedsDiscovered
                );
                return;
            }

            long finalChunkIndex = chunkIndex;
            // Get world name
            String worldName = getWorldName(worldChunk);
            if (worldName == null) {
                worldName = "default";
            }

            plugin.getLogger().at(Level.INFO).log(
                "[RespawnBlockProtection] Bed #%d storing: owner=%s, world=%s, chunk=0x%X",
                bedsDiscovered, ownerInfo, worldName, finalChunkIndex
            );

            // Store in database
            database.setBedChunk(ownerUUID, worldName, finalChunkIndex);
            plugin.getLogger().at(Level.INFO).log(
                "[RespawnBlockProtection] Stored bed in DB: owner=%s, world=%s, chunk=0x%X",
                ownerInfo, worldName, finalChunkIndex
            );

            // If owner is currently online, protect immediately
            boolean online = isPlayerOnline(ownerUUID);
            plugin.getLogger().at(Level.INFO).log(
                "[RespawnBlockProtection] Owner %s online check: %s", ownerInfo, online
            );

            if (online) {
                protectBedChunk(ownerUUID, worldName, finalChunkIndex, worldChunk);
                plugin.getLogger().at(Level.INFO).log(
                    "[RespawnBlockProtection] Protected chunk for ONLINE player's bed: owner=%s, world=%s (chunk 0x%X)",
                    ownerInfo, worldName, finalChunkIndex
                );
            } else {
                plugin.getLogger().at(Level.INFO).log(
                    "[RespawnBlockProtection] Owner %s is OFFLINE - chunk not protected yet", ownerInfo
                );
            }

        } catch (Exception e) {
            plugin.getLogger().at(Level.WARNING).log(
                "[RespawnBlockProtection] Error on bed add: %s", e.getMessage()
            );
        }
    }

    @Override
    public void onEntityRemove(
            Ref<ChunkStore> ref,
            RemoveReason reason,
            Store<ChunkStore> store,
            CommandBuffer<ChunkStore> commandBuffer
    ) {
        // Don't process on chunk unload - the bed still exists
        if (reason == RemoveReason.UNLOAD) {
            return;
        }

        try {
            RespawnBlock respawnBlock = store.getComponent(ref, RespawnBlock.getComponentType());
            if (respawnBlock == null) {
                return;
            }

            UUID ownerUUID = null;
            try {
                ownerUUID = respawnBlock.getOwnerUUID();
            } catch (Exception ignored) {}

            if (ownerUUID == null) {
                return;
            }

            // Get chunk index using position-based approach (same as onEntityAdded)
            long chunkIndex = getChunkIndexFromRespawnBlock(respawnBlock, store, ref);

            // Try to get WorldChunk for world name
            WorldChunk worldChunk = null;
            try {
                worldChunk = store.getComponent(ref, WorldChunk.getComponentType());
            } catch (Exception ignored) {}

            String worldName = worldChunk != null ? getWorldName(worldChunk) : "default";
            if (worldName == null) worldName = "default";

            String ownerInfo = ownerUUID.toString().substring(0, 8) + "...";

            // Remove from database
            database.removeBedChunk(ownerUUID, worldName);

            // Unprotect the chunk
            String chunkKey = worldName + ":" + chunkIndex;
            if (protectedChunks.remove(chunkKey) != null) {
                unprotectBedChunk(chunkIndex);
                plugin.getLogger().at(Level.INFO).log(
                    "[RespawnBlockProtection] Bed destroyed - unprotected chunk: owner=%s, world=%s (chunk 0x%X)",
                    ownerInfo, worldName, chunkIndex
                );
            }

        } catch (Exception e) {
            plugin.getLogger().at(Level.WARNING).log(
                "[RespawnBlockProtection] Error on bed remove: %s", e.getMessage()
            );
        }
    }

    /**
     * Sync protections based on who's currently online.
     * Called periodically by the scheduler.
     */
    private void syncProtections() {
        try {
            // Get current online players
            Set<UUID> currentlyOnline = getCurrentOnlinePlayers();

            // Find players who just logged in
            Set<UUID> justLoggedIn = new HashSet<>(currentlyOnline);
            justLoggedIn.removeAll(onlinePlayers);

            // Find players who just logged out
            Set<UUID> justLoggedOut = new HashSet<>(onlinePlayers);
            justLoggedOut.removeAll(currentlyOnline);

            // Update tracking
            onlinePlayers.clear();
            onlinePlayers.addAll(currentlyOnline);

            // Handle logins - protect their bed chunks
            for (UUID playerUuid : justLoggedIn) {
                Map<String, Long> bedChunks = database.getAllBedChunks(playerUuid);
                for (Map.Entry<String, Long> entry : bedChunks.entrySet()) {
                    String worldName = entry.getKey();
                    long chunkIndex = entry.getValue();

                    String chunkKey = worldName + ":" + chunkIndex;
                    if (!protectedChunks.containsKey(chunkKey)) {
                        protectBedChunk(playerUuid, worldName, chunkIndex, null);
                        String ownerInfo = playerUuid.toString().substring(0, 8) + "...";
                        plugin.getLogger().at(Level.INFO).log(
                            "[RespawnBlockProtection] Player logged in - protected bed chunk: owner=%s, world=%s (chunk 0x%X)",
                            ownerInfo, worldName, chunkIndex
                        );
                    }
                }
            }

            // Handle logouts - unprotect their bed chunks
            for (UUID playerUuid : justLoggedOut) {
                // Find and unprotect all chunks owned by this player
                Set<String> keysToRemove = new HashSet<>();
                for (Map.Entry<String, UUID> entry : protectedChunks.entrySet()) {
                    if (entry.getValue().equals(playerUuid)) {
                        keysToRemove.add(entry.getKey());
                    }
                }

                for (String chunkKey : keysToRemove) {
                    protectedChunks.remove(chunkKey);
                    // Extract chunk index from key (format: "worldName:chunkIndex")
                    String[] parts = chunkKey.split(":");
                    if (parts.length == 2) {
                        try {
                            long chunkIndex = Long.parseLong(parts[1]);
                            unprotectBedChunk(chunkIndex);
                            String ownerInfo = playerUuid.toString().substring(0, 8) + "...";
                            if (ConfigManager.getInstance().isVerbose()) {
                                plugin.getLogger().at(Level.INFO).log(
                                    "[RespawnBlockProtection] Player logged out - unprotected bed chunk: owner=%s (chunk 0x%X)",
                                    ownerInfo, chunkIndex
                                );
                            }
                        } catch (NumberFormatException ignored) {}
                    }
                }
            }

        } catch (Exception e) {
            if (ConfigManager.getInstance().isVerbose()) {
                plugin.getLogger().at(Level.WARNING).log(
                    "[RespawnBlockProtection] Error in sync task: %s", e.getMessage()
                );
            }
        }
    }

    /**
     * Get the set of currently online player UUIDs.
     */
    private Set<UUID> getCurrentOnlinePlayers() {
        Set<UUID> online = new HashSet<>();
        try {
            Universe universe = Universe.get();
            if (universe == null) {
                return online;
            }

            // Try to get players from universe
            // Universe.getPlayers() returns collection of PlayerRef
            var players = universe.getPlayers();
            if (players != null) {
                for (Object playerObj : players) {
                    if (playerObj instanceof PlayerRef playerRef) {
                        if (playerRef.isValid()) {
                            UUID uuid = playerRef.getUuid();
                            if (uuid != null) {
                                online.add(uuid);
                            }
                        }
                    }
                }
            }
        } catch (Exception e) {
            // Silently fail - universe may not be ready
        }
        return online;
    }

    /**
     * Check if a player is currently online.
     */
    private boolean isPlayerOnline(UUID playerUUID) {
        try {
            PlayerRef playerRef = Universe.get().getPlayer(playerUUID);
            return playerRef != null && playerRef.isValid();
        } catch (Exception e) {
            return false;
        }
    }

    /**
     * Get chunk index from the ChunkStore.
     * The ChunkStore is the store for a specific WorldChunk - we need to find the chunk index.
     */
    private long getChunkIndexFromRespawnBlock(RespawnBlock respawnBlock, Store<ChunkStore> store, Ref<ChunkStore> ref) {
        // Try method 1: getExternalData() returns the ChunkStore, which has the WorldChunk
        try {
            Object externalData = store.getExternalData();
            if (externalData instanceof WorldChunk wc) {
                long idx = wc.getIndex();
                plugin.getLogger().at(Level.INFO).log(
                    "[RespawnBlockProtection] Got chunk from store.getExternalData(): index=0x%X", idx
                );
                return idx;
            } else if (externalData instanceof ChunkStore cs) {
                // ChunkStore - we need to find which chunk this bed is in
                // Try to get chunk info from ref.getIndex() - this is the entity index
                int refIndex = ref.getIndex();
                plugin.getLogger().at(Level.INFO).log(
                    "[RespawnBlockProtection] Ref index: %d (0x%X)", refIndex, refIndex
                );

                // The refIndex might encode chunk position - let's see if we can decode it
                // Common patterns: high bits = chunk coords, low bits = entity index within chunk
                int possibleChunkX = (refIndex >> 20) & 0xFFF;
                int possibleChunkZ = (refIndex >> 8) & 0xFFF;
                int possibleEntityIdx = refIndex & 0xFF;
                plugin.getLogger().at(Level.INFO).log(
                    "[RespawnBlockProtection] Ref index decoded: possibleChunkX=%d, possibleChunkZ=%d, entityIdx=%d",
                    possibleChunkX, possibleChunkZ, possibleEntityIdx
                );

                // Try another decoding: maybe it's simpler
                int altChunkX = refIndex >> 16;
                int altChunkZ = refIndex & 0xFFFF;
                plugin.getLogger().at(Level.INFO).log(
                    "[RespawnBlockProtection] Alt decode: chunkX=%d, chunkZ=%d",
                    altChunkX, altChunkZ
                );

                // Get chunk indexes from ChunkStore - when bed is discovered, its chunk should be loaded
                try {
                    Method getChunkIndexes = cs.getClass().getMethod("getChunkIndexes");
                    Object result = getChunkIndexes.invoke(cs);
                    if (result != null) {
                        Method size = result.getClass().getMethod("size");
                        int setSize = (Integer) size.invoke(result);

                        // Get first chunk index - when bed is discovered on chunk load, this is our chunk
                        if (setSize >= 1) {
                            Method iterator = result.getClass().getMethod("iterator");
                            Object iter = iterator.invoke(result);
                            Method hasNext = iter.getClass().getMethod("hasNext");
                            Method next = iter.getClass().getMethod("nextLong");

                            if ((Boolean) hasNext.invoke(iter)) {
                                long firstChunkIdx = (Long) next.invoke(iter);

                                // If only 1 chunk loaded, it's definitely our chunk
                                // If multiple, the bed's chunk should be the most recently loaded
                                // For now, use the first one and log for debugging
                                int chunkX = (int) (firstChunkIdx >> 32);
                                int chunkZ = (int) firstChunkIdx;

                                plugin.getLogger().at(Level.INFO).log(
                                    "[RespawnBlockProtection] Found chunk index 0x%X (chunk %d, %d) from %d loaded chunks",
                                    firstChunkIdx, chunkX, chunkZ, setSize
                                );

                                return firstChunkIdx;
                            }
                        }
                    }
                } catch (Exception e) {
                    plugin.getLogger().at(Level.WARNING).log(
                        "[RespawnBlockProtection] Could not get chunk indexes: %s", e.getMessage()
                    );
                }
            } else if (externalData != null) {
                // Try to get index from external data
                try {
                    Method getIndex = externalData.getClass().getMethod("getIndex");
                    Object idxObj = getIndex.invoke(externalData);
                    if (idxObj instanceof Long idx) {
                        plugin.getLogger().at(Level.INFO).log(
                            "[RespawnBlockProtection] Got chunk index from externalData.getIndex(): 0x%X", idx
                        );
                        return idx;
                    }
                } catch (Exception e) {
                    plugin.getLogger().at(Level.INFO).log(
                        "[RespawnBlockProtection] ExternalData type: %s", externalData.getClass().getName()
                    );
                }
            }
        } catch (Exception e) {
            plugin.getLogger().at(Level.FINE).log(
                "[RespawnBlockProtection] getExternalData failed: %s", e.getMessage()
            );
        }

        // Try method 2: Store might have getIndex() or getChunkIndex() method
        try {
            for (Method method : store.getClass().getMethods()) {
                String name = method.getName();
                if ((name.equals("getIndex") || name.equals("getChunkIndex")) && method.getParameterCount() == 0) {
                    Object result = method.invoke(store);
                    if (result instanceof Long idx) {
                        plugin.getLogger().at(Level.INFO).log(
                            "[RespawnBlockProtection] Got chunk index from store.%s(): 0x%X", name, idx
                        );
                        return idx;
                    } else if (result instanceof Integer idx) {
                        plugin.getLogger().at(Level.INFO).log(
                            "[RespawnBlockProtection] Got chunk index from store.%s(): 0x%X", name, (long) idx
                        );
                        return idx.longValue();
                    }
                }
            }
        } catch (Exception e) {
            plugin.getLogger().at(Level.FINE).log(
                "[RespawnBlockProtection] Store index method failed: %s", e.getMessage()
            );
        }

        // Try method 3: Store might have a reference to WorldChunk or chunk
        try {
            for (Method method : store.getClass().getMethods()) {
                String name = method.getName().toLowerCase();
                if ((name.contains("chunk") || name.contains("world")) && method.getParameterCount() == 0) {
                    Object result = method.invoke(store);
                    if (result instanceof WorldChunk wc) {
                        long idx = wc.getIndex();
                        plugin.getLogger().at(Level.INFO).log(
                            "[RespawnBlockProtection] Got chunk from store.%s(): index=0x%X", method.getName(), idx
                        );
                        return idx;
                    }
                    // Try to get index from the result
                    if (result != null) {
                        try {
                            Method getIndex = result.getClass().getMethod("getIndex");
                            Object idxObj = getIndex.invoke(result);
                            if (idxObj instanceof Long idx) {
                                plugin.getLogger().at(Level.INFO).log(
                                    "[RespawnBlockProtection] Got chunk index from store.%s().getIndex(): 0x%X", method.getName(), idx
                                );
                                return idx;
                            }
                        } catch (Exception ignored) {}
                    }
                }
            }
        } catch (Exception e) {
            plugin.getLogger().at(Level.FINE).log(
                "[RespawnBlockProtection] Store chunk method failed: %s", e.getMessage()
            );
        }

        // Debug: Log Ref class info (first time only) - DON'T invoke methods to avoid recursion
        if (bedsDiscovered <= 2) {
            plugin.getLogger().at(Level.INFO).log(
                "[RespawnBlockProtection] Ref class: %s", ref.getClass().getName()
            );

            try {
                plugin.getLogger().at(Level.INFO).log("[RespawnBlockProtection] Ref methods (not invoking to avoid recursion):");
                for (Method method : ref.getClass().getMethods()) {
                    if (method.getParameterCount() == 0 && !method.getName().equals("getClass") &&
                        !method.getName().startsWith("notify") && !method.getName().startsWith("wait") &&
                        !method.getName().equals("hashCode") && !method.getName().equals("toString")) {
                        plugin.getLogger().at(Level.INFO).log(
                            "[RespawnBlockProtection]   REF - %s() returns %s", method.getName(), method.getReturnType().getSimpleName()
                        );
                    }
                }
            } catch (Exception ignored) {}

            plugin.getLogger().at(Level.INFO).log(
                "[RespawnBlockProtection] Store class: %s", store.getClass().getName()
            );
        }

        return -1L;
    }

    /**
     * Get world name from a WorldChunk.
     */
    private String getWorldName(WorldChunk worldChunk) {
        try {
            World world = worldChunk.getWorld();
            return world != null ? world.getName() : null;
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * Protect a bed chunk in the registry.
     */
    private void protectBedChunk(UUID ownerUUID, String worldName, long chunkIndex, WorldChunk worldChunk) {
        String ownerInfo = ownerUUID.toString().substring(0, 8) + "...";
        String reason = "Bed/Respawn: " + ownerInfo + " (online) [" + worldName + "]";

        // Track this protection
        String chunkKey = worldName + ":" + chunkIndex;
        protectedChunks.put(chunkKey, ownerUUID);

        if (registry.protectChunk(chunkIndex, reason, System.currentTimeMillis() / 50)) {
            chunksCurrentlyProtected++;
        }

        // Also set keepLoaded on the WorldChunk if available
        if (worldChunk != null && !worldChunk.shouldKeepLoaded()) {
            worldChunk.setKeepLoaded(true);
        }
    }

    /**
     * Unprotect a bed chunk in the registry.
     */
    private void unprotectBedChunk(long chunkIndex) {
        if (registry.unprotectChunk(chunkIndex)) {
            chunksCurrentlyProtected = Math.max(0, chunksCurrentlyProtected - 1);
        }
    }

    public int getBedsDiscovered() {
        return bedsDiscovered;
    }

    public int getBedsWithOwners() {
        return bedsWithOwners;
    }

    public int getChunksCurrentlyProtected() {
        return chunksCurrentlyProtected;
    }

    public int getOnlinePlayersTracked() {
        return onlinePlayers.size();
    }

    public int getStoredBedCount() {
        return database.getCount();
    }

    public String getStatus() {
        return String.format(
            "RespawnBlockProtectionListener Status:\n" +
            "  Beds discovered: %d\n" +
            "  Beds with owners: %d\n" +
            "  Beds in database: %d\n" +
            "  Chunks currently protected: %d\n" +
            "  Online players tracked: %d\n" +
            "  Mode: SQLite + Online-only (Issue #44)",
            bedsDiscovered,
            bedsWithOwners,
            database.getCount(),
            chunksCurrentlyProtected,
            onlinePlayers.size()
        );
    }
}
