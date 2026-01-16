package com.hyfixes.systems;

import com.hyfixes.HyFixes;
import com.hypixel.hytale.component.ArchetypeChunk;
import com.hypixel.hytale.component.CommandBuffer;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.component.query.Query;
import com.hypixel.hytale.component.system.tick.EntityTickingSystem;
import com.hypixel.hytale.server.core.entity.entities.Player;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;

import java.lang.reflect.Method;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.logging.Level;

/**
 * ChunkCleanupSystem - Runs chunk cleanup methods on the MAIN THREAD
 *
 * This system extends EntityTickingSystem to ensure our cleanup methods
 * are called from the main server thread, avoiding InvocationTargetExceptions.
 *
 * It runs every CLEANUP_INTERVAL_TICKS ticks (default: 600 = 30 seconds at 20 TPS)
 */
public class ChunkCleanupSystem extends EntityTickingSystem<EntityStore> {

    private final HyFixes plugin;

    // Configuration
    private static final int CLEANUP_INTERVAL_TICKS = 600; // 30 seconds at 20 TPS

    // State
    private final AtomicInteger tickCounter = new AtomicInteger(0);
    private final AtomicInteger cleanupCount = new AtomicInteger(0);
    private final AtomicInteger successCount = new AtomicInteger(0);
    private final AtomicLong lastCleanupTime = new AtomicLong(0);
    private boolean loggedOnce = false;
    private boolean hasRunOnce = false;

    // Cached method references (set by ChunkUnloadManager)
    private Object chunkStoreInstance = null;
    private Object chunkLightingInstance = null;
    private Method invalidateLoadedChunksMethod = null;
    private Method waitForLoadingChunksMethod = null;

    public ChunkCleanupSystem(HyFixes plugin) {
        this.plugin = plugin;
    }

    @Override
    public Query<EntityStore> getQuery() {
        // Query for Player entities - we just need something to tick on
        // We only run our logic once per cleanup interval anyway
        return Player.getComponentType();
    }

    @Override
    public void tick(
            float deltaTime,
            int entityIndex,
            ArchetypeChunk<EntityStore> chunk,
            Store<EntityStore> store,
            CommandBuffer<EntityStore> commandBuffer
    ) {
        // Only run on the first entity each tick to avoid duplicate processing
        if (entityIndex != 0) {
            return;
        }

        if (!loggedOnce) {
            plugin.getLogger().at(Level.INFO).log(
                "[ChunkCleanupSystem] Active on MAIN THREAD - will run cleanup every " +
                (CLEANUP_INTERVAL_TICKS / 20) + " seconds"
            );
            loggedOnce = true;
        }

        // Increment tick counter
        int currentTick = tickCounter.incrementAndGet();

        // Only run cleanup every CLEANUP_INTERVAL_TICKS
        if (currentTick % CLEANUP_INTERVAL_TICKS != 0) {
            return;
        }

        // Run cleanup on main thread
        runMainThreadCleanup();
    }

    /**
     * Run cleanup methods on the main thread.
     * This is called from tick() which runs on the main server thread.
     */
    private void runMainThreadCleanup() {
        cleanupCount.incrementAndGet();
        lastCleanupTime.set(System.currentTimeMillis());

        int successes = 0;

        // Try invalidateLoadedChunks()
        if (chunkLightingInstance != null && invalidateLoadedChunksMethod != null) {
            try {
                invalidateLoadedChunksMethod.invoke(chunkLightingInstance);
                successes++;
                plugin.getLogger().at(Level.INFO).log(
                    "[ChunkCleanupSystem] SUCCESS: Called invalidateLoadedChunks() on main thread"
                );
            } catch (Exception e) {
                String cause = e.getCause() != null ? e.getCause().getMessage() : e.getMessage();
                plugin.getLogger().at(Level.WARNING).log(
                    "[ChunkCleanupSystem] Failed invalidateLoadedChunks(): " +
                    e.getClass().getSimpleName() + " - " + cause
                );
            }
        }

        // Try waitForLoadingChunks()
        if (chunkStoreInstance != null && waitForLoadingChunksMethod != null) {
            try {
                waitForLoadingChunksMethod.invoke(chunkStoreInstance);
                successes++;
                plugin.getLogger().at(Level.INFO).log(
                    "[ChunkCleanupSystem] SUCCESS: Called waitForLoadingChunks() on main thread"
                );
            } catch (Exception e) {
                String cause = e.getCause() != null ? e.getCause().getMessage() : e.getMessage();
                plugin.getLogger().at(Level.WARNING).log(
                    "[ChunkCleanupSystem] Failed waitForLoadingChunks(): " +
                    e.getClass().getSimpleName() + " - " + cause
                );
            }
        }

        if (successes > 0) {
            successCount.addAndGet(successes);
        }

        if (!hasRunOnce) {
            plugin.getLogger().at(Level.INFO).log(
                "[ChunkCleanupSystem] First cleanup cycle complete. " +
                "ChunkStore: " + (chunkStoreInstance != null) + ", " +
                "ChunkLighting: " + (chunkLightingInstance != null)
            );
            hasRunOnce = true;
        }
    }

    /**
     * Set the ChunkStore instance for cleanup operations.
     * Called by ChunkUnloadManager after API discovery.
     */
    public void setChunkStoreInstance(Object instance) {
        this.chunkStoreInstance = instance;
        if (instance != null) {
            try {
                waitForLoadingChunksMethod = instance.getClass().getMethod("waitForLoadingChunks");
                waitForLoadingChunksMethod.setAccessible(true);
                plugin.getLogger().at(Level.INFO).log(
                    "[ChunkCleanupSystem] Registered ChunkStore with waitForLoadingChunks()"
                );
            } catch (NoSuchMethodException e) {
                plugin.getLogger().at(Level.WARNING).log(
                    "[ChunkCleanupSystem] ChunkStore does not have waitForLoadingChunks() method"
                );
            }
        }
    }

    /**
     * Set the ChunkLightingManager instance for cleanup operations.
     * Called by ChunkUnloadManager after API discovery.
     */
    public void setChunkLightingInstance(Object instance) {
        this.chunkLightingInstance = instance;
        if (instance != null) {
            try {
                invalidateLoadedChunksMethod = instance.getClass().getMethod("invalidateLoadedChunks");
                invalidateLoadedChunksMethod.setAccessible(true);
                plugin.getLogger().at(Level.INFO).log(
                    "[ChunkCleanupSystem] Registered ChunkLightingManager with invalidateLoadedChunks()"
                );
            } catch (NoSuchMethodException e) {
                plugin.getLogger().at(Level.WARNING).log(
                    "[ChunkCleanupSystem] ChunkLightingManager does not have invalidateLoadedChunks() method"
                );
            }
        }
    }

    /**
     * Get status for admin command.
     */
    public String getStatus() {
        long lastRun = lastCleanupTime.get();
        String lastRunStr = lastRun > 0 ?
            ((System.currentTimeMillis() - lastRun) / 1000) + "s ago" :
            "never";

        return String.format(
            "ChunkCleanupSystem Status (MAIN THREAD):\n" +
            "  ChunkStore Ready: %s\n" +
            "  ChunkLighting Ready: %s\n" +
            "  Total Cleanups: %d\n" +
            "  Successful Calls: %d\n" +
            "  Last Cleanup: %s\n" +
            "  Interval: %d seconds",
            chunkStoreInstance != null && waitForLoadingChunksMethod != null,
            chunkLightingInstance != null && invalidateLoadedChunksMethod != null,
            cleanupCount.get(),
            successCount.get(),
            lastRunStr,
            CLEANUP_INTERVAL_TICKS / 20
        );
    }
}
