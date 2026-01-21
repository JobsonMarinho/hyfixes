package com.hyfixes.early;

import com.hyfixes.early.config.EarlyConfigManager;
import com.hypixel.hytale.plugin.early.ClassTransformer;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.ClassVisitor;

/**
 * HyFixes Early Plugin - InteractionManager Bytecode Transformer
 *
 * This transformer fixes the client timeout bug in InteractionManager.serverTick()
 * that kicks players when clients are slow to respond.
 *
 * The Bug:
 * In InteractionManager.serverTick(), when a client doesn't send clientData within
 * the timeout window, a RuntimeException is thrown, kicking the player.
 *
 * The Fix:
 * Wrap the serverTick() method in a try-catch to catch the timeout exception
 * and handle it gracefully instead of kicking the player.
 *
 * @see <a href="https://github.com/John-Willikers/hyfixes/issues/40">Issue #40</a>
 */
public class InteractionManagerTransformer implements ClassTransformer {

    private static final String TARGET_CLASS = "com.hypixel.hytale.server.core.entity.InteractionManager";

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
        if (!EarlyConfigManager.getInstance().isTransformerEnabled("interactionManager")) {
            System.out.println("[HyFixes-Early] InteractionManagerTransformer DISABLED by config");
            return classBytes;
        }

        System.out.println("[HyFixes-Early] ================================================");
        System.out.println("[HyFixes-Early] Transforming InteractionManager class...");
        System.out.println("[HyFixes-Early] Fixing serverTick() client timeout bug (Issue #40)");
        System.out.println("[HyFixes-Early] ================================================");

        try {
            ClassReader reader = new ClassReader(classBytes);
            ClassWriter writer = new ClassWriter(ClassWriter.COMPUTE_FRAMES | ClassWriter.COMPUTE_MAXS);
            ClassVisitor visitor = new InteractionManagerVisitor(writer);

            reader.accept(visitor, ClassReader.EXPAND_FRAMES);

            byte[] transformedBytes = writer.toByteArray();
            System.out.println("[HyFixes-Early] InteractionManager transformation COMPLETE!");
            System.out.println("[HyFixes-Early] Original size: " + classBytes.length + " bytes");
            System.out.println("[HyFixes-Early] Transformed size: " + transformedBytes.length + " bytes");

            return transformedBytes;

        } catch (Exception e) {
            System.err.println("[HyFixes-Early] ERROR: Failed to transform InteractionManager!");
            System.err.println("[HyFixes-Early] Returning original bytecode to prevent crash.");
            e.printStackTrace();
            return classBytes;
        }
    }
}
