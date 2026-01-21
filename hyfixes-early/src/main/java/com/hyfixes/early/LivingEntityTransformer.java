package com.hyfixes.early;

import com.hyfixes.early.config.EarlyConfigManager;
import com.hypixel.hytale.plugin.early.ClassTransformer;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.ClassVisitor;

/**
 * HyFixes Early Plugin - LivingEntity Bytecode Transformer
 *
 * This transformer fixes the inventory sharing bug where two players end up
 * with the same ItemContainer reference, causing inventories to sync in real-time.
 *
 * The Bug:
 * During instance transitions or gamemode changes, the same Inventory/ItemContainer
 * reference can be assigned to multiple players, causing them to share inventory state.
 *
 * The Fix:
 * Wrap LivingEntity.setInventory() to validate inventory ownership before assignment.
 * If an inventory is already owned by a different player, force deep-clone it.
 *
 * @see <a href="https://github.com/John-Willikers/hyfixes/issues/45">Issue #45</a>
 */
public class LivingEntityTransformer implements ClassTransformer {

    private static final String TARGET_CLASS = "com.hypixel.hytale.server.core.entity.LivingEntity";

    @Override
    public int priority() {
        return 100;
    }

    @Override
    public byte[] transform(String className, String packageName, byte[] classBytes) {
        if (!className.equals(TARGET_CLASS)) {
            return classBytes;
        }

        // Check if transformer is enabled via config
        if (!EarlyConfigManager.getInstance().isTransformerEnabled("livingEntity")) {
            System.out.println("[HyFixes-Early] LivingEntityTransformer DISABLED by config");
            return classBytes;
        }

        System.out.println("[HyFixes-Early] ================================================");
        System.out.println("[HyFixes-Early] Transforming LivingEntity class...");
        System.out.println("[HyFixes-Early] Fixing inventory sharing bug (Issue #45)");
        System.out.println("[HyFixes-Early] ================================================");

        try {
            ClassReader reader = new ClassReader(classBytes);
            ClassWriter writer = new ClassWriter(ClassWriter.COMPUTE_FRAMES | ClassWriter.COMPUTE_MAXS);
            ClassVisitor visitor = new LivingEntityVisitor(writer);

            reader.accept(visitor, ClassReader.EXPAND_FRAMES);

            byte[] transformedBytes = writer.toByteArray();
            System.out.println("[HyFixes-Early] LivingEntity transformation COMPLETE!");
            System.out.println("[HyFixes-Early] Original size: " + classBytes.length + " bytes");
            System.out.println("[HyFixes-Early] Transformed size: " + transformedBytes.length + " bytes");

            return transformedBytes;

        } catch (Exception e) {
            System.err.println("[HyFixes-Early] ERROR: Failed to transform LivingEntity!");
            System.err.println("[HyFixes-Early] Returning original bytecode to prevent crash.");
            e.printStackTrace();
            return classBytes;
        }
    }
}
