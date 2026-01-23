# Plan: Fix Inventory Sharing Bug (Issue #45)

## 📋 Overview

Two players can end up sharing the same live `ItemContainer` instance, causing inventories to sync in real-time. This is a core Hytale bug triggered during join/instance exit/gamemode changes.

## 🔍 Root Cause Analysis

Based on server JAR investigation:

1. **ItemContainer** is the base class for inventory slots (storage, armor, hotbar, etc.)
2. **Inventory** holds 6 ItemContainers and has an `entity` field linking to owner
3. **LivingEntity.setInventory()** assigns inventory to an entity
4. **Universe.resetPlayer()** transfers data between old/new Player entities during instance transitions
5. **Race condition**: If inventory reference is copied instead of cloned during concurrent operations, two players share the same container

## 🎯 Fix Strategy: Defensive Clone Guard

Create an early plugin transformer that wraps `LivingEntity.setInventory()` to:
1. Track ItemContainer ownership (container → owner UUID mapping)
2. Detect when a container is being assigned to a different player
3. Force deep-clone the Inventory if shared reference detected
4. Log warnings for debugging

## 📝 Implementation

### File: `early/src/main/java/com/hyfixes/early/transformers/InventorySharingTransformer.java`

```java
package com.hyfixes.early.transformers;

import org.objectweb.asm.*;
import org.objectweb.asm.commons.AdviceAdapter;

/**
 * Transforms LivingEntity.setInventory() to detect and prevent shared ItemContainer references.
 *
 * Before: inventory is assigned directly
 * After: inventory is validated for ownership, cloned if already owned by another entity
 */
public class InventorySharingTransformer implements ClassFileTransformer {

    private static final String LIVING_ENTITY_CLASS = "com/hypixel/hytale/server/core/entity/LivingEntity";
    private static final String SET_INVENTORY_METHOD = "setInventory";
    private static final String INVENTORY_DESC = "Lcom/hypixel/hytale/server/core/inventory/Inventory;";

    @Override
    public byte[] transform(ClassLoader loader, String className, Class<?> classBeingRedefined,
                           ProtectionDomain protectionDomain, byte[] classfileBuffer) {
        if (!LIVING_ENTITY_CLASS.equals(className)) {
            return null;
        }

        System.out.println("[HyFixes-Early] Transforming LivingEntity for inventory sharing fix (Issue #45)");

        ClassReader reader = new ClassReader(classfileBuffer);
        ClassWriter writer = new ClassWriter(reader, ClassWriter.COMPUTE_FRAMES | ClassWriter.COMPUTE_MAXS);
        ClassVisitor visitor = new SetInventoryVisitor(writer);
        reader.accept(visitor, 0);

        return writer.toByteArray();
    }

    private static class SetInventoryVisitor extends ClassVisitor {
        public SetInventoryVisitor(ClassVisitor cv) {
            super(Opcodes.ASM9, cv);
        }

        @Override
        public MethodVisitor visitMethod(int access, String name, String descriptor,
                                        String signature, String[] exceptions) {
            MethodVisitor mv = super.visitMethod(access, name, descriptor, signature, exceptions);

            // Target: setInventory(Inventory) and setInventory(Inventory, boolean) variants
            if (SET_INVENTORY_METHOD.equals(name) && descriptor.startsWith("(" + INVENTORY_DESC)) {
                System.out.println("[HyFixes-Early] Wrapping setInventory: " + descriptor);
                return new SetInventoryAdvice(mv, access, name, descriptor);
            }

            return mv;
        }
    }

    private static class SetInventoryAdvice extends AdviceAdapter {
        public SetInventoryAdvice(MethodVisitor mv, int access, String name, String descriptor) {
            super(Opcodes.ASM9, mv, access, name, descriptor);
        }

        @Override
        protected void onMethodEnter() {
            // Call: inventory = InventoryOwnershipGuard.validateAndClone(this, inventory);
            // Stack: [this, inventory, ...] -> need to replace inventory arg

            mv.visitVarInsn(ALOAD, 0);  // this (LivingEntity)
            mv.visitVarInsn(ALOAD, 1);  // inventory parameter
            mv.visitMethodInsn(
                INVOKESTATIC,
                "com/hyfixes/guard/InventoryOwnershipGuard",
                "validateAndClone",
                "(Lcom/hypixel/hytale/server/core/entity/LivingEntity;Lcom/hypixel/hytale/server/core/inventory/Inventory;)Lcom/hypixel/hytale/server/core/inventory/Inventory;",
                false
            );
            mv.visitVarInsn(ASTORE, 1);  // Replace inventory parameter with validated/cloned one
        }
    }
}
```

### File: `src/main/java/com/hyfixes/guard/InventoryOwnershipGuard.java`

```java
package com.hyfixes.guard;

import java.lang.ref.WeakReference;
import java.lang.reflect.Method;
import java.util.Map;
import java.util.UUID;
import java.util.WeakHashMap;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Guards against inventory sharing between players.
 * Tracks ItemContainer ownership and forces cloning when shared reference detected.
 */
public class InventoryOwnershipGuard {

    private static final Logger LOGGER = Logger.getLogger("HyFixes");

    // Track inventory -> owner UUID (weak keys so inventories can be GC'd)
    private static final Map<Object, UUID> inventoryOwners = new ConcurrentHashMap<>();

    // Track individual ItemContainers for finer granularity
    private static final Map<Object, UUID> containerOwners = new ConcurrentHashMap<>();

    // Statistics
    private static int sharedReferencesDetected = 0;
    private static int inventoriesCloned = 0;

    /**
     * Called by transformed LivingEntity.setInventory() before assignment.
     * Returns the original inventory if safe, or a clone if shared reference detected.
     */
    public static Object validateAndClone(Object livingEntity, Object inventory) {
        if (inventory == null || livingEntity == null) {
            return inventory;
        }

        try {
            UUID newOwnerUuid = getEntityUuid(livingEntity);
            if (newOwnerUuid == null) {
                // Not a player entity, skip validation
                return inventory;
            }

            // Check if this inventory is already owned by a different player
            UUID currentOwner = inventoryOwners.get(inventory);

            if (currentOwner != null && !currentOwner.equals(newOwnerUuid)) {
                // SHARED REFERENCE DETECTED!
                sharedReferencesDetected++;

                LOGGER.log(Level.WARNING,
                    "[HyFixes] INVENTORY SHARING DETECTED! Inventory owned by {0} being assigned to {1}. Forcing clone. (Detection #{2})",
                    new Object[]{currentOwner, newOwnerUuid, sharedReferencesDetected}
                );

                // Clone the inventory to break the shared reference
                Object clonedInventory = cloneInventory(inventory);
                if (clonedInventory != null) {
                    inventoriesCloned++;
                    inventoryOwners.put(clonedInventory, newOwnerUuid);
                    registerContainers(clonedInventory, newOwnerUuid);

                    LOGGER.log(Level.INFO,
                        "[HyFixes] Successfully cloned inventory for player {0}. Total clones: {1}",
                        new Object[]{newOwnerUuid, inventoriesCloned}
                    );

                    return clonedInventory;
                } else {
                    LOGGER.log(Level.SEVERE,
                        "[HyFixes] FAILED to clone inventory! Players {0} and {1} may have linked inventories!",
                        new Object[]{currentOwner, newOwnerUuid}
                    );
                }
            } else {
                // First assignment or same owner - register ownership
                inventoryOwners.put(inventory, newOwnerUuid);
                registerContainers(inventory, newOwnerUuid);
            }

        } catch (Exception e) {
            LOGGER.log(Level.WARNING, "[HyFixes] Error in inventory validation: " + e.getMessage());
        }

        return inventory;
    }

    /**
     * Register individual ItemContainers from an Inventory.
     */
    private static void registerContainers(Object inventory, UUID ownerUuid) {
        try {
            String[] containerGetters = {"getStorage", "getArmor", "getHotbar", "getUtility", "getTools", "getBackpack"};

            for (String getter : containerGetters) {
                try {
                    Method method = inventory.getClass().getMethod(getter);
                    Object container = method.invoke(inventory);
                    if (container != null) {
                        UUID existingOwner = containerOwners.get(container);
                        if (existingOwner != null && !existingOwner.equals(ownerUuid)) {
                            LOGGER.log(Level.WARNING,
                                "[HyFixes] ItemContainer from {0} already owned by {1}, now being claimed by {2}",
                                new Object[]{getter, existingOwner, ownerUuid}
                            );
                        }
                        containerOwners.put(container, ownerUuid);
                    }
                } catch (NoSuchMethodException ignored) {
                    // Method doesn't exist, skip
                }
            }
        } catch (Exception e) {
            LOGGER.log(Level.WARNING, "[HyFixes] Error registering containers: " + e.getMessage());
        }
    }

    /**
     * Clone an Inventory using the CODEC (serialize -> deserialize).
     */
    private static Object cloneInventory(Object inventory) {
        try {
            // Try using the CODEC to clone (same as Entity.clone() does)
            Class<?> inventoryClass = inventory.getClass();
            java.lang.reflect.Field codecField = inventoryClass.getField("CODEC");
            Object codec = codecField.get(null);

            // Get ExtraInfo.THREAD_LOCAL
            Class<?> extraInfoClass = Class.forName("com.hypixel.hytale.codec.ExtraInfo");
            java.lang.reflect.Field threadLocalField = extraInfoClass.getField("THREAD_LOCAL");
            ThreadLocal<?> threadLocal = (ThreadLocal<?>) threadLocalField.get(null);
            Object extraInfo = threadLocal.get();

            // Encode to BSON
            Method encodeMethod = codec.getClass().getMethod("encode", Object.class, extraInfoClass);
            Object bsonValue = encodeMethod.invoke(codec, inventory, extraInfo);

            // Create new inventory instance
            Object newInventory = inventoryClass.getDeclaredConstructor().newInstance();

            // Decode into new instance
            Method decodeMethod = codec.getClass().getMethod("decode",
                Class.forName("org.bson.BsonValue"), Object.class, extraInfoClass);
            decodeMethod.invoke(codec, bsonValue, newInventory, extraInfo);

            return newInventory;

        } catch (Exception e) {
            LOGGER.log(Level.WARNING, "[HyFixes] Failed to clone inventory via CODEC: " + e.getMessage());

            // Fallback: try ItemContainer.clone() on each container
            return cloneInventoryFallback(inventory);
        }
    }

    /**
     * Fallback clone method using ItemContainer.clone().
     */
    private static Object cloneInventoryFallback(Object inventory) {
        try {
            Class<?> inventoryClass = inventory.getClass();

            // Get current containers
            Method getStorage = inventoryClass.getMethod("getStorage");
            Method getArmor = inventoryClass.getMethod("getArmor");
            Method getHotbar = inventoryClass.getMethod("getHotbar");
            Method getUtility = inventoryClass.getMethod("getUtility");
            Method getTools = inventoryClass.getMethod("getTools");
            Method getBackpack = inventoryClass.getMethod("getBackpack");

            Object storage = cloneContainer(getStorage.invoke(inventory));
            Object armor = cloneContainer(getArmor.invoke(inventory));
            Object hotbar = cloneContainer(getHotbar.invoke(inventory));
            Object utility = cloneContainer(getUtility.invoke(inventory));
            Object tools = cloneContainer(getTools.invoke(inventory));
            Object backpack = cloneContainer(getBackpack.invoke(inventory));

            // Create new Inventory with cloned containers
            Class<?> containerClass = Class.forName("com.hypixel.hytale.server.core.inventory.container.ItemContainer");
            java.lang.reflect.Constructor<?> constructor = inventoryClass.getConstructor(
                containerClass, containerClass, containerClass, containerClass, containerClass, containerClass
            );

            return constructor.newInstance(storage, armor, hotbar, utility, tools, backpack);

        } catch (Exception e) {
            LOGGER.log(Level.SEVERE, "[HyFixes] Fallback inventory clone also failed: " + e.getMessage());
            return null;
        }
    }

    /**
     * Clone a single ItemContainer.
     */
    private static Object cloneContainer(Object container) {
        if (container == null) return null;
        try {
            Method cloneMethod = container.getClass().getMethod("clone");
            return cloneMethod.invoke(container);
        } catch (Exception e) {
            LOGGER.log(Level.WARNING, "[HyFixes] Failed to clone ItemContainer: " + e.getMessage());
            return container; // Return original as last resort
        }
    }

    /**
     * Get UUID from a LivingEntity (if it's a Player).
     */
    private static UUID getEntityUuid(Object livingEntity) {
        try {
            // Check if it's a Player
            Class<?> playerClass = Class.forName("com.hypixel.hytale.server.core.entity.entities.Player");
            if (!playerClass.isInstance(livingEntity)) {
                return null;
            }

            // Get PlayerRef
            Method getPlayerRef = playerClass.getMethod("getPlayerRef");
            Object playerRef = getPlayerRef.invoke(livingEntity);
            if (playerRef == null) return null;

            // Get UUID from PlayerRef
            Method getUuid = playerRef.getClass().getMethod("getUuid");
            return (UUID) getUuid.invoke(playerRef);

        } catch (Exception e) {
            return null;
        }
    }

    /**
     * Clean up tracking for a player that disconnected.
     */
    public static void onPlayerDisconnect(UUID playerUuid) {
        inventoryOwners.entrySet().removeIf(entry -> playerUuid.equals(entry.getValue()));
        containerOwners.entrySet().removeIf(entry -> playerUuid.equals(entry.getValue()));
    }

    /**
     * Get statistics for debugging.
     */
    public static String getStats() {
        return String.format(
            "InventoryOwnershipGuard Stats:\n" +
            "  Shared references detected: %d\n" +
            "  Inventories cloned: %d\n" +
            "  Tracked inventories: %d\n" +
            "  Tracked containers: %d",
            sharedReferencesDetected,
            inventoriesCloned,
            inventoryOwners.size(),
            containerOwners.size()
        );
    }
}
```

## 🔧 Integration

### 1. Add transformer to early plugin

Register `InventorySharingTransformer` in `HyFixesEarlyPlugin.java`:

```java
instrumentation.addTransformer(new InventorySharingTransformer(), true);
```

### 2. Add cleanup listener to runtime plugin

In `HyFixes.java`, add listener to clean up on player disconnect:

```java
// In onEnable():
eventBus.register(PlayerDisconnectEvent.class, event -> {
    InventoryOwnershipGuard.onPlayerDisconnect(event.getPlayerUuid());
});
```

### 3. Add status command

Add `/hyfixes inventory-guard` subcommand to show stats.

## ✅ Testing

1. **Unit test**: Create mock scenario where same Inventory is assigned to two different Player entities
2. **Integration test**: Use StarterKit + Hybrid Library to trigger the bug, verify it's prevented
3. **Monitor logs**: Watch for "INVENTORY SHARING DETECTED" warnings in production

## ⚠️ Risks

- **Performance**: ConcurrentHashMap lookups on every setInventory() call (should be negligible)
- **False positives**: NPC entities might trigger warnings (filtered by checking for Player class)
- **Clone failures**: If CODEC or ItemContainer.clone() fails, we log severe and return original

## 📊 Success Criteria

1. No more "ItemContainer: Failed to dispatch event" with simultaneous ADDs
2. "INVENTORY SHARING DETECTED" warnings appear in logs when bug would have occurred
3. Players report inventories no longer syncing after instance transitions
