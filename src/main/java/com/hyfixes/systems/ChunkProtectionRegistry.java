package com.hyfixes.systems;

import com.hyfixes.HyFixes;
import com.hyfixes.config.ConfigManager;

import java.util.Collections;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Level;

/**
 * ChunkProtectionRegistry - Thread-safe registry of protected chunk indexes.
 * 
 * Tracks chunks that contain important content (teleporters, portals, etc.)
 * and prevents them from being cleaned up by the chunk cleanup system.
 * 
 * This registry is accessed from both the main thread (during cleanup checks)
 * and background threads (during scanning), so all operations are thread-safe.
 */
public class ChunkProtectionRegistry {

    private final HyFixes plugin;
    
    // Thread-safe map of protected chunk indexes to protection info
    private final ConcurrentHashMap<Long, ProtectionInfo> protectedChunks = new ConcurrentHashMap<>();
    
    // Stats tracking
    private volatile long lastVerificationTick = 0;
    private volatile int totalProtections = 0;
    private volatile int totalUnprotections = 0;
    
    /**
     * Protection information for a chunk
     */
    public static class ProtectionInfo {
        public final long chunkIndex;
        public final String reason;
        public final long protectedAtTick;
        public volatile long lastVerifiedAtTick;
        
        public ProtectionInfo(long chunkIndex, String reason, long currentTick) {
            this.chunkIndex = chunkIndex;
            this.reason = reason;
            this.protectedAtTick = currentTick;
            this.lastVerifiedAtTick = currentTick;
        }
    }
    
    public ChunkProtectionRegistry(HyFixes plugin) {
        this.plugin = plugin;
    }
    
    /**
     * Check if a chunk is protected from cleanup.
     * 
     * @param chunkIndex The chunk index to check
     * @return true if the chunk is protected
     */
    public boolean isChunkProtected(long chunkIndex) {
        return protectedChunks.containsKey(chunkIndex);
    }
    
    /**
     * Protect a chunk from cleanup.
     * 
     * @param chunkIndex The chunk index to protect
     * @param reason The reason for protection (for logging/debugging)
     * @param currentTick The current server tick
     * @return true if newly protected, false if already protected
     */
    public boolean protectChunk(long chunkIndex, String reason, long currentTick) {
        ProtectionInfo existing = protectedChunks.get(chunkIndex);
        if (existing != null) {
            // Already protected - update verification time
            existing.lastVerifiedAtTick = currentTick;
            return false;
        }
        
        ProtectionInfo info = new ProtectionInfo(chunkIndex, reason, currentTick);
        ProtectionInfo prev = protectedChunks.putIfAbsent(chunkIndex, info);
        
        if (prev == null) {
            // Newly protected
            totalProtections++;
            if (ConfigManager.getInstance().logChunkProtectionEvents()) {
                plugin.getLogger().at(Level.INFO).log(
                    "[ChunkProtection] Protected chunk %d: %s", chunkIndex, reason
                );
            }
            return true;
        } else {
            // Someone else protected it first - update their verification time
            prev.lastVerifiedAtTick = currentTick;
            return false;
        }
    }
    
    /**
     * Remove protection from a chunk.
     * 
     * @param chunkIndex The chunk index to unprotect
     * @return true if was protected and now unprotected
     */
    public boolean unprotectChunk(long chunkIndex) {
        ProtectionInfo removed = protectedChunks.remove(chunkIndex);
        if (removed != null) {
            totalUnprotections++;
            if (ConfigManager.getInstance().logChunkProtectionEvents()) {
                plugin.getLogger().at(Level.INFO).log(
                    "[ChunkProtection] Unprotected chunk %d (was: %s)", chunkIndex, removed.reason
                );
            }
            return true;
        }
        return false;
    }
    
    /**
     * Clear all protections. Use with caution!
     * 
     * @return The number of chunks that were unprotected
     */
    public int clearAllProtections() {
        int count = protectedChunks.size();
        protectedChunks.clear();
        plugin.getLogger().at(Level.WARNING).log(
            "[ChunkProtection] Cleared all %d protected chunks!", count
        );
        return count;
    }
    
    /**
     * Remove protections that haven't been verified within the verification interval.
     * 
     * @param currentTick The current server tick
     * @param verificationIntervalTicks How old a protection can be before removal
     * @return The number of stale protections removed
     */
    public int removeStaleProtections(long currentTick, int verificationIntervalTicks) {
        int removed = 0;
        long cutoffTick = currentTick - verificationIntervalTicks;
        
        for (Map.Entry<Long, ProtectionInfo> entry : protectedChunks.entrySet()) {
            if (entry.getValue().lastVerifiedAtTick < cutoffTick) {
                if (protectedChunks.remove(entry.getKey()) != null) {
                    removed++;
                    if (ConfigManager.getInstance().logChunkProtectionEvents()) {
                        plugin.getLogger().at(Level.INFO).log(
                            "[ChunkProtection] Removed stale protection for chunk %d (was: %s)",
                            entry.getKey(), entry.getValue().reason
                        );
                    }
                }
            }
        }
        
        lastVerificationTick = currentTick;
        return removed;
    }
    
    /**
     * Get the count of protected chunks.
     */
    public int getProtectedChunkCount() {
        return protectedChunks.size();
    }
    
    /**
     * Get an unmodifiable view of all protected chunk indexes.
     */
    public Set<Long> getProtectedChunkIndexes() {
        return Collections.unmodifiableSet(protectedChunks.keySet());
    }
    
    /**
     * Get protection info for a specific chunk.
     */
    public ProtectionInfo getProtectionInfo(long chunkIndex) {
        return protectedChunks.get(chunkIndex);
    }
    
    /**
     * Get a status summary for admin commands.
     */
    public String getStatus() {
        StringBuilder sb = new StringBuilder();
        sb.append("=== Chunk Protection Status ===\n");
        sb.append("Enabled: ").append(ConfigManager.getInstance().isChunkProtectionEnabled()).append("\n");
        sb.append("Protected chunks: ").append(protectedChunks.size()).append("\n");
        sb.append("Total protections: ").append(totalProtections).append("\n");
        sb.append("Total unprotections: ").append(totalUnprotections).append("\n");
        sb.append("Last verification tick: ").append(lastVerificationTick).append("\n");
        
        // List entity/block keywords from config
        String[] entityKw = ConfigManager.getInstance().getProtectedEntityKeywords();
        String[] blockKw = ConfigManager.getInstance().getProtectedBlockKeywords();
        sb.append("Entity keywords: ").append(String.join(", ", entityKw)).append("\n");
        sb.append("Block keywords: ").append(String.join(", ", blockKw)).append("\n");
        sb.append("Protect growing plants: ").append(ConfigManager.getInstance().protectGrowingPlants()).append("\n");
        sb.append("Protect spawn beacons: ").append(ConfigManager.getInstance().protectSpawnBeacons()).append("\n");
        
        return sb.toString();
    }
    
    /**
     * Get a list view of protected chunks for admin commands.
     */
    public String getProtectedChunksList() {
        if (protectedChunks.isEmpty()) {
            return "No protected chunks.";
        }
        
        StringBuilder sb = new StringBuilder();
        sb.append("Protected chunks (").append(protectedChunks.size()).append(" total):\n");
        
        int shown = 0;
        for (Map.Entry<Long, ProtectionInfo> entry : protectedChunks.entrySet()) {
            if (shown >= 50) {
                sb.append("... and ").append(protectedChunks.size() - 50).append(" more\n");
                break;
            }
            ProtectionInfo info = entry.getValue();
            sb.append(String.format("  Chunk %d: %s (verified tick %d)\n", 
                entry.getKey(), info.reason, info.lastVerifiedAtTick));
            shown++;
        }
        
        return sb.toString();
    }
}
