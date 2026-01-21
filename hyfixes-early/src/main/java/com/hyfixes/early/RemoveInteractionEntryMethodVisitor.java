package com.hyfixes.early;

import org.objectweb.asm.Label;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;

/**
 * ASM MethodVisitor that wraps InteractionChain.removeInteractionEntry() in a try-catch
 * to handle IllegalArgumentException when entries are removed out of order.
 *
 * The original method enforces strict FIFO removal order and throws when violated.
 * During rapid disconnects or network issues, entries can become stale and removal
 * order gets violated, causing player kicks.
 *
 * Fix: Catch IllegalArgumentException and log warning instead of crashing.
 *
 * @see <a href="https://github.com/John-Willikers/hyfixes/issues/40">Issue #40</a>
 */
public class RemoveInteractionEntryMethodVisitor extends MethodVisitor {

    private final String className;
    private final Label tryStart = new Label();
    private final Label tryEnd = new Label();
    private final Label catchHandler = new Label();
    private boolean visitedCode = false;

    public RemoveInteractionEntryMethodVisitor(MethodVisitor mv, String className) {
        super(Opcodes.ASM9, mv);
        this.className = className;
    }

    @Override
    public void visitCode() {
        super.visitCode();
        visitedCode = true;

        // Register the try-catch block for IllegalArgumentException
        mv.visitTryCatchBlock(tryStart, tryEnd, catchHandler, "java/lang/IllegalArgumentException");

        // Emit the try block start label
        mv.visitLabel(tryStart);
    }

    @Override
    public void visitInsn(int opcode) {
        // Intercept RETURN instructions to end the try block and add handler
        if (visitedCode && opcode == Opcodes.RETURN) {
            // End the try block before the return
            mv.visitLabel(tryEnd);

            // Jump over the catch handler to the actual return
            Label afterCatch = new Label();
            mv.visitJumpInsn(Opcodes.GOTO, afterCatch);

            // Emit the catch handler
            mv.visitLabel(catchHandler);
            // Stack has the exception on it

            // Log warning: System.out.println("[HyFixes-Early] WARNING: Suppressed out-of-order removal in InteractionChain (Issue #40)");
            mv.visitFieldInsn(Opcodes.GETSTATIC, "java/lang/System", "out", "Ljava/io/PrintStream;");
            mv.visitLdcInsn("[HyFixes-Early] WARNING: Suppressed out-of-order removal in InteractionChain (Issue #40)");
            mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL, "java/io/PrintStream", "println", "(Ljava/lang/String;)V", false);

            // Pop the exception from stack (it's consumed by entering handler)
            // Return normally (void method)
            mv.visitInsn(Opcodes.RETURN);

            // Label for normal flow after try block
            mv.visitLabel(afterCatch);
        }

        super.visitInsn(opcode);
    }

    @Override
    public void visitMaxs(int maxStack, int maxLocals) {
        // Increase stack size to accommodate our additions
        super.visitMaxs(Math.max(maxStack, 2), maxLocals);
    }
}
