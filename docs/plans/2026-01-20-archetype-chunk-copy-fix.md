# ArchetypeChunk.copySerializableEntity Fix Implementation Plan

> **For Claude:** REQUIRED SUB-SKILL: Use superpowers:executing-plans to implement this plan task-by-task.

**Goal:** Fix vanilla Hytale server crash when IndexOutOfBoundsException occurs in `ArchetypeChunk.copySerializableEntity()` during chunk saving.

**Architecture:** Extend existing `ArchetypeChunkVisitor` to also wrap `copySerializableEntity()` method with IndexOutOfBoundsException handling. Similar pattern to existing `getComponent()` fix.

**Tech Stack:** Java 25, ASM 9, Hytale Early Plugin System

---

## 📋 Background

**Issue:** [GitHub #29](https://github.com/John-Willikers/hyfixes/issues/29)

**Crash:**
```
java.lang.IndexOutOfBoundsException: Index out of range: 11
    at com.hypixel.hytale.component.ArchetypeChunk.copySerializableEntity(ArchetypeChunk.java:243)
    at com.hypixel.hytale.component.Store.copySerializableEntity(Store.java:789)
    at com.hypixel.hytale.server.core.universe.world.storage.component.ChunkSavingSystems$Ticking.tick(ChunkSavingSystems.java:172)
```

**Root Cause:** Vanilla Hytale bug. During chunk saving, when serializing entity data, the component index becomes invalid (likely due to entity archetype changing or entity being removed while serialization is in progress). Same type of issue as #20 but different method.

**Existing Fix:** We already transform `ArchetypeChunk` class for `getComponent()` method (Issue #20). We just need to extend the visitor to also handle `copySerializableEntity()`.

**Fix Strategy:** Wrap `copySerializableEntity()` in try-catch for IndexOutOfBoundsException, return null on exception (skip serializing that entity component).

---

## 🔧 Task 1: Update ArchetypeChunkVisitor

**Files:**
- Modify: `hyfixes-early/src/main/java/com/hyfixes/early/ArchetypeChunkVisitor.java`

**Step 1: Add copySerializableEntity interception in visitMethod()**

In the `visitMethod()` override, add another condition to intercept `copySerializableEntity`:

```java
// Also target: copySerializableEntity method
if (name.equals("copySerializableEntity")) {
    System.out.println("[HyFixes-Early] Found copySerializableEntity method: " + descriptor);
    System.out.println("[HyFixes-Early] Wrapping with IndexOutOfBoundsException handler");
    return new CopySerializableEntityMethodVisitor(mv, access, descriptor);
}
```

**Step 2: Create CopySerializableEntityMethodVisitor inner class**

Add a new inner class similar to `GetComponentMethodVisitor` but for `copySerializableEntity`. This method likely returns an object or void, so we need to check the return type.

Looking at the stack trace, it appears to return something that gets passed to other methods. We'll wrap with try-catch and return null on exception.

```java
/**
 * Wraps copySerializableEntity() in a try-catch for IndexOutOfBoundsException.
 */
private static class CopySerializableEntityMethodVisitor extends MethodVisitor {

    private final Label tryStart = new Label();
    private final Label tryEnd = new Label();
    private final Label catchHandler = new Label();
    private boolean started = false;
    private final String descriptor;

    public CopySerializableEntityMethodVisitor(MethodVisitor mv, int access, String descriptor) {
        super(Opcodes.ASM9, mv);
        this.descriptor = descriptor;
    }

    @Override
    public void visitCode() {
        super.visitCode();
        mv.visitLabel(tryStart);
        started = true;
    }

    @Override
    public void visitInsn(int opcode) {
        // Intercept return instructions to end try block
        if (started && (opcode == Opcodes.ARETURN || opcode == Opcodes.RETURN ||
                        opcode == Opcodes.IRETURN || opcode == Opcodes.LRETURN)) {
            mv.visitLabel(tryEnd);
            super.visitInsn(opcode);

            // Add catch handler
            mv.visitLabel(catchHandler);

            // Pop the exception from stack
            mv.visitInsn(Opcodes.POP);

            // Log warning
            mv.visitFieldInsn(Opcodes.GETSTATIC, "java/lang/System", "err", "Ljava/io/PrintStream;");
            mv.visitLdcInsn("[HyFixes] WARNING: copySerializableEntity() IndexOutOfBounds - skipping entity serialization");
            mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL, "java/io/PrintStream", "println", "(Ljava/lang/String;)V", false);

            // Return null (for object return) or return (for void)
            if (opcode == Opcodes.ARETURN) {
                mv.visitInsn(Opcodes.ACONST_NULL);
                mv.visitInsn(Opcodes.ARETURN);
            } else if (opcode == Opcodes.RETURN) {
                mv.visitInsn(Opcodes.RETURN);
            } else if (opcode == Opcodes.IRETURN) {
                mv.visitInsn(Opcodes.ICONST_0);
                mv.visitInsn(Opcodes.IRETURN);
            } else if (opcode == Opcodes.LRETURN) {
                mv.visitInsn(Opcodes.LCONST_0);
                mv.visitInsn(Opcodes.LRETURN);
            }

            started = false;
        } else {
            super.visitInsn(opcode);
        }
    }

    @Override
    public void visitMaxs(int maxStack, int maxLocals) {
        mv.visitTryCatchBlock(tryStart, tryEnd, catchHandler, "java/lang/IndexOutOfBoundsException");
        super.visitMaxs(Math.max(maxStack, 2), maxLocals);
    }
}
```

**Step 3: Update transformer logging**

In `ArchetypeChunkTransformer.java`, update the logging to mention both fixes:

```java
System.out.println("[HyFixes-Early] Fixing getComponent() IndexOutOfBoundsException (Issue #20)");
System.out.println("[HyFixes-Early] Fixing copySerializableEntity() IndexOutOfBoundsException (Issue #29)");
```

**Step 4: Commit**

```bash
git add hyfixes-early/src/main/java/com/hyfixes/early/ArchetypeChunkVisitor.java
git add hyfixes-early/src/main/java/com/hyfixes/early/ArchetypeChunkTransformer.java
git commit -m "feat(early): extend ArchetypeChunk fix to copySerializableEntity

Adds IndexOutOfBoundsException handling for copySerializableEntity()
method during chunk saving. Same pattern as existing getComponent() fix.

Fixes: #29

Co-Authored-By: Claude Opus 4.5 <noreply@anthropic.com>"
```

---

## 🔧 Task 2: Update BUGS_FIXED.md Documentation

**Files:**
- Modify: `BUGS_FIXED.md`

**Step 1: Add Fix 23 documentation**

```markdown
---

## Fix 23: ArchetypeChunk.copySerializableEntity IndexOutOfBounds (v1.9.5)

**Bug:** Server crashes with IndexOutOfBoundsException when serializing entity during chunk saving.

**Stack trace:**
```
java.lang.IndexOutOfBoundsException: Index out of range: 11
    at com.hypixel.hytale.component.ArchetypeChunk.copySerializableEntity(ArchetypeChunk.java:243)
    at com.hypixel.hytale.component.Store.copySerializableEntity(Store.java:789)
    at com.hypixel.hytale.server.core.universe.world.storage.component.ChunkSavingSystems$Ticking.tick(ChunkSavingSystems.java:172)
```

**Root cause:** Vanilla Hytale bug. During chunk saving, entity archetype or component data can change while serialization is in progress, causing invalid component index access.

**Fix:** Extended existing `ArchetypeChunkTransformer` to also wrap `copySerializableEntity()` with try-catch for IndexOutOfBoundsException. On exception, logs warning and returns null (skips serializing that entity - safe degradation).

**Configuration:** Uses existing `archetypeChunk` toggle:
```json
{
  "transformers": {
    "archetypeChunk": true
  }
}
```

**Files:**
- `ArchetypeChunkVisitor.java` - Added CopySerializableEntityMethodVisitor

**GitHub Issue:** [#29](https://github.com/John-Willikers/hyfixes/issues/29)
```

**Step 2: Commit**

```bash
git add BUGS_FIXED.md
git commit -m "docs: add Fix 23 - ArchetypeChunk copySerializableEntity fix

Co-Authored-By: Claude Opus 4.5 <noreply@anthropic.com>"
```

---

## 🔧 Task 3: Build and Test

**Step 1: Build the project**

```bash
./gradlew build
```

Expected: BUILD SUCCESSFUL

**Step 2: Verify transformer logging**

The existing transformer is already registered. Just verify build passes.

---

## ✅ Completion Checklist

- [ ] ArchetypeChunkVisitor extended with CopySerializableEntityMethodVisitor
- [ ] ArchetypeChunkTransformer logging updated
- [ ] BUGS_FIXED.md updated with Fix 23
- [ ] Build passes
- [ ] All commits made with proper messages

---

## 📝 Notes

**Why this approach:** We're extending an existing transformer rather than creating a new one because:
1. Same class (`ArchetypeChunk`)
2. Same type of bug (IndexOutOfBoundsException)
3. Same fix pattern (try-catch wrapper)
4. Uses existing config toggle

**Testing:** Can only be fully tested on live server under load. Verify via startup logs that transformation applies to both methods.
