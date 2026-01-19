package com.hyfixes.systems;

import com.hyfixes.HyFixes;
import com.hyfixes.config.ConfigManager;
import com.hypixel.hytale.component.ArchetypeChunk;
import com.hypixel.hytale.component.ComponentType;
import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.server.core.asset.type.blocktype.config.BlockType;
import com.hypixel.hytale.server.core.modules.entity.component.TransformComponent;
import com.hypixel.hytale.server.core.universe.world.World;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.HashSet;
import java.util.Set;
import java.util.logging.Level;

/**
 * ChunkProtectionScanner - Scans chunks for protected content.
 * 
 * Uses reflection patterns from CleanWarpsCommand to detect:
 * - Entities with archetypes matching protected keywords (teleporters, portals, etc.)
 * - Blocks with types matching protected keywords
 * 
 * Caches reflection handles for performance.
 */
public class ChunkProtectionScanner {

    private final HyFixes plugin;
    private final ChunkProtectionRegistry registry;
    
    // Cached reflection handles (initialized lazily)
    private volatile boolean reflectionInitialized = false;
    @SuppressWarnings("rawtypes")
    private ComponentType transformComponentType;
    private Method forEachChunkMethod;
    private Method getArchetypeMethod;
    private Method getPositionMethod;
    private Method getPosXMethod;
    private Method getPosYMethod;
    private Method getPosZMethod;
    private Field refsField;
    
    // Scan statistics
    private volatile int totalScans = 0;
    private volatile int entitiesScanned = 0;
    private volatile int protectedFound = 0;
    
    public ChunkProtectionScanner(HyFixes plugin, ChunkProtectionRegistry registry) {
        this.plugin = plugin;
        this.registry = registry;
    }
    
    /**
     * Scan a world for protected content and update the registry.
     * 
     * @param world The world to scan
     * @param currentTick The current server tick (for protection timestamps)
     * @return The number of chunks newly protected
     */
    public int scanWorld(World world, long currentTick) {
        if (!ConfigManager.getInstance().isChunkProtectionEnabled()) {
            return 0;
        }
        
        if (world == null) {
            return 0;
        }
        
        totalScans++;
        int newlyProtected = 0;
        
        try {
            Store<EntityStore> store = world.getEntityStore().getStore();
            if (store == null) {
                return 0;
            }
            
            // Initialize reflection handles if needed
            if (!reflectionInitialized) {
                initializeReflection(store);
            }
            
            // Get keywords from config
            String[] entityKeywords = ConfigManager.getInstance().getProtectedEntityKeywords();
            String[] blockKeywords = ConfigManager.getInstance().getProtectedBlockKeywords();
            
            // Track chunks we've found protected content in
            Set<Long> protectedChunkIndexes = new HashSet<>();
            
            // Scan entities using forEachChunk pattern
            if (forEachChunkMethod != null) {
                newlyProtected += scanEntities(store, entityKeywords, currentTick, protectedChunkIndexes);
            }
            
            // Block scanning is expensive - only do it sparingly
            // The entity scan should catch most cases since teleporters are entities
            
        } catch (Exception e) {
            plugin.getLogger().at(Level.WARNING).log(
                "[ChunkProtectionScanner] Scan error: %s", e.getMessage()
            );
        }
        
        return newlyProtected;
    }
    
    /**
     * Scan entities in all chunks for protected content.
     */
    @SuppressWarnings({"unchecked", "rawtypes"})
    private int scanEntities(Store<EntityStore> store, String[] keywords, long currentTick, Set<Long> protectedIndexes) {
        int newlyProtected = 0;
        
        if (forEachChunkMethod == null) {
            return 0;
        }
        
        try {
            Class<?> consumerType = forEachChunkMethod.getParameterTypes()[0];
            final int[] scannedCount = {0};
            final int[] protectedCount = {0};
            
            Object consumer = java.lang.reflect.Proxy.newProxyInstance(
                consumerType.getClassLoader(),
                new Class<?>[] { consumerType },
                (proxy, method, args) -> {
                    if (method.getName().equals("accept")) {
                        ArchetypeChunk chunk = (ArchetypeChunk) args[0];
                        scannedCount[0]++;
                        
                        // Process entities in this chunk
                        try {
                            if (refsField == null) {
                                refsField = chunk.getClass().getDeclaredField("refs");
                                refsField.setAccessible(true);
                            }
                            
                            Object refs = refsField.get(chunk);
                            if (refs != null && refs.getClass().isArray()) {
                                Object[] refArray = (Object[]) refs;
                                
                                for (Object refObj : refArray) {
                                    if (refObj instanceof Ref) {
                                        Ref<EntityStore> entityRef = (Ref<EntityStore>) refObj;
                                        entitiesScanned++;
                                        
                                        // Check if entity matches protected keywords
                                        String archetype = getEntityArchetype(store, entityRef);
                                        if (archetype != null && matchesKeywords(archetype, keywords)) {
                                            // Get entity position to determine chunk
                                            long chunkIndex = getEntityChunkIndex(store, entityRef);
                                            if (chunkIndex != -1 && !protectedIndexes.contains(chunkIndex)) {
                                                if (registry.protectChunk(chunkIndex, "Entity: " + archetype, currentTick)) {
                                                    protectedCount[0]++;
                                                    protectedIndexes.add(chunkIndex);
                                                    protectedFound++;
                                                }
                                            }
                                        }
                                    }
                                }
                            }
                        } catch (NoSuchFieldException e) {
                            // refs field not found - this chunk type doesn't have it
                        } catch (Exception e) {
                            // Skip this chunk on error
                        }
                    }
                    return null;
                }
            );
            
            forEachChunkMethod.invoke(store, consumer);
            newlyProtected = protectedCount[0];
            
            if (ConfigManager.getInstance().logChunkProtectionEvents() && protectedCount[0] > 0) {
                plugin.getLogger().at(Level.INFO).log(
                    "[ChunkProtectionScanner] Scanned %d chunks, found %d protected entities",
                    scannedCount[0], protectedCount[0]
                );
            }
            
        } catch (Exception e) {
            plugin.getLogger().at(Level.WARNING).log(
                "[ChunkProtectionScanner] Entity scan error: %s", e.getMessage()
            );
        }
        
        return newlyProtected;
    }
    
    /**
     * Get the archetype name for an entity.
     */
    @SuppressWarnings("unchecked")
    private String getEntityArchetype(Store<EntityStore> store, Ref<EntityStore> entityRef) {
        try {
            if (getArchetypeMethod == null) {
                // Find archetype method
                for (Method m : store.getClass().getMethods()) {
                    if (m.getName().contains("Archetype") && m.getParameterCount() == 1) {
                        getArchetypeMethod = m;
                        break;
                    }
                }
            }
            
            if (getArchetypeMethod != null) {
                Object archetype = getArchetypeMethod.invoke(store, entityRef);
                if (archetype != null) {
                    return archetype.toString();
                }
            }
            
            // Fallback to entityRef string
            return entityRef.toString();
        } catch (Exception e) {
            return null;
        }
    }
    
    /**
     * Get the chunk index for an entity based on its position.
     */
    private long getEntityChunkIndex(Store<EntityStore> store, Ref<EntityStore> entityRef) {
        try {
            if (transformComponentType == null) {
                transformComponentType = TransformComponent.getComponentType();
            }
            
            @SuppressWarnings("unchecked")
            TransformComponent transform = (TransformComponent) store.getComponent(entityRef, transformComponentType);
            if (transform == null) {
                return -1;
            }
            
            // Get position
            Object pos;
            if (getPositionMethod == null) {
                getPositionMethod = transform.getClass().getMethod("getPosition");
            }
            pos = getPositionMethod.invoke(transform);
            if (pos == null) {
                return -1;
            }
            
            // Get coordinates
            float x, y, z;
            try {
                if (getPosXMethod == null) {
                    getPosXMethod = pos.getClass().getMethod("getX");
                    getPosYMethod = pos.getClass().getMethod("getY");
                    getPosZMethod = pos.getClass().getMethod("getZ");
                }
                x = ((Number) getPosXMethod.invoke(pos)).floatValue();
                y = ((Number) getPosYMethod.invoke(pos)).floatValue();
                z = ((Number) getPosZMethod.invoke(pos)).floatValue();
            } catch (NoSuchMethodException e) {
                // Try x(), y(), z() methods
                Method xm = pos.getClass().getMethod("x");
                Method ym = pos.getClass().getMethod("y");
                Method zm = pos.getClass().getMethod("z");
                x = ((Number) xm.invoke(pos)).floatValue();
                y = ((Number) ym.invoke(pos)).floatValue();
                z = ((Number) zm.invoke(pos)).floatValue();
            }
            
            // Convert world position to chunk index
            // Chunks are typically 16x16 blocks horizontally
            // Chunk index is usually packed as (chunkX, chunkZ) or similar
            int chunkX = (int) Math.floor(x) >> 4; // Divide by 16
            int chunkZ = (int) Math.floor(z) >> 4;
            
            // Pack into a long (common pattern: upper 32 bits = x, lower 32 bits = z)
            return ((long) chunkX << 32) | (chunkZ & 0xFFFFFFFFL);
            
        } catch (Exception e) {
            return -1;
        }
    }
    
    /**
     * Check if a string matches any of the keywords (case-insensitive).
     */
    private boolean matchesKeywords(String value, String[] keywords) {
        if (value == null || keywords == null) {
            return false;
        }
        String lower = value.toLowerCase();
        for (String keyword : keywords) {
            if (lower.contains(keyword.toLowerCase())) {
                return true;
            }
        }
        return false;
    }
    
    /**
     * Scan a specific position for protected blocks.
     */
    public boolean scanBlocksAtPosition(World world, int x, int y, int z, long currentTick) {
        if (!ConfigManager.getInstance().isChunkProtectionEnabled()) {
            return false;
        }
        
        try {
            int blockId = world.getBlock(x, y, z);
            if (blockId == 0) {
                return false;
            }
            
            BlockType blockType = BlockType.getAssetMap().getAsset(blockId);
            String blockName = blockType != null ? blockType.getId() : "unknown";
            
            String[] blockKeywords = ConfigManager.getInstance().getProtectedBlockKeywords();
            if (matchesKeywords(blockName, blockKeywords)) {
                // Calculate chunk index
                int chunkX = x >> 4;
                int chunkZ = z >> 4;
                long chunkIndex = ((long) chunkX << 32) | (chunkZ & 0xFFFFFFFFL);
                
                return registry.protectChunk(chunkIndex, "Block: " + blockName, currentTick);
            }
        } catch (Exception e) {
            // Block check failed - ignore
        }
        
        return false;
    }
    
    /**
     * Initialize reflection handles.
     */
    @SuppressWarnings("unchecked")
    private void initializeReflection(Store<EntityStore> store) {
        try {
            // Find forEachChunk method
            for (Method m : store.getClass().getMethods()) {
                if (m.getName().equals("forEachChunk") && m.getParameterCount() == 1) {
                    forEachChunkMethod = m;
                    break;
                }
            }
            
            // Transform component type
            transformComponentType = TransformComponent.getComponentType();
            
            reflectionInitialized = true;
            
            plugin.getLogger().at(Level.INFO).log(
                "[ChunkProtectionScanner] Reflection initialized. forEachChunk: %s",
                forEachChunkMethod != null
            );
        } catch (Exception e) {
            plugin.getLogger().at(Level.WARNING).log(
                "[ChunkProtectionScanner] Reflection init error: %s", e.getMessage()
            );
        }
    }
    
    /**
     * Get scan statistics.
     */
    public String getStatus() {
        return String.format(
            "Total scans: %d, Entities scanned: %d, Protected found: %d, Reflection ready: %s",
            totalScans, entitiesScanned, protectedFound, reflectionInitialized
        );
    }
}
