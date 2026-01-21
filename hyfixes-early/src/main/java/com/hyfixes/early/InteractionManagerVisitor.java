package com.hyfixes.early;

import org.objectweb.asm.ClassVisitor;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;

/**
 * ASM ClassVisitor for InteractionManager.
 *
 * This visitor intercepts the serverTick method and applies the timeout fix:
 * - serverTick - throws RuntimeException when client is too slow (Issue #40)
 */
public class InteractionManagerVisitor extends ClassVisitor {

    private static final String SERVER_TICK_METHOD = "serverTick";

    private String className;

    public InteractionManagerVisitor(ClassVisitor classVisitor) {
        super(Opcodes.ASM9, classVisitor);
    }

    @Override
    public void visit(int version, int access, String name, String signature, String superName, String[] interfaces) {
        this.className = name;
        super.visit(version, access, name, signature, superName, interfaces);
    }

    @Override
    public MethodVisitor visitMethod(int access, String name, String descriptor, String signature, String[] exceptions) {
        MethodVisitor mv = super.visitMethod(access, name, descriptor, signature, exceptions);

        if (name.equals(SERVER_TICK_METHOD)) {
            System.out.println("[HyFixes-Early] Found method: " + name + descriptor);
            System.out.println("[HyFixes-Early] Applying client timeout fix (Issue #40)...");
            return new ServerTickMethodVisitor(mv, className);
        }

        return mv;
    }
}
