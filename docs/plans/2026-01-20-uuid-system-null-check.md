# UUIDSystem Null Check Implementation Plan

> **For Claude:** REQUIRED SUB-SKILL: Use superpowers:executing-plans to implement this plan task-by-task.

**Goal:** Fix vanilla Hytale server crash when UUIDComponent is null during entity removal in chunk unload.

**Architecture:** Create ASM bytecode transformer that injects null check before `uuidComponent.getUuid()` call in `EntityStore$UUIDSystem.onEntityRemove()`. Follows established transformer pattern: Transformer → ClassVisitor → MethodVisitor.

**Tech Stack:** Java 25, ASM 9, Hytale Early Plugin System

---

## 📋 Background

**Issue:** [GitHub #28](https://github.com/John-Willikers/hyfixes/issues/28)

**Crash:**
```
java.lang.NullPointerException: Cannot invoke "UUIDComponent.getUuid()" because "uuidComponent" is null
    at EntityStore$UUIDSystem.onEntityRemove(EntityStore.java:201)
    at ChunkUnloadingSystem.lambda$tryUnload$1(ChunkUnloadingSystem.java:141)
```

**Root Cause:** Vanilla Hytale bug - entities can be removed during chunk unload before their UUIDComponent is initialized. The `onEntityRemove` method assumes `uuidComponent` is never null.

**Fix Strategy:** Inject null check bytecode that:
1. Checks if `uuidComponent` is null after it's loaded
2. If null, logs warning and returns early (safe no-op)
3. If not null, continues with normal UUID removal logic

---

## 🔧 Task 1: Add Config Toggle

**Files:**
- Modify: `hyfixes-early/src/main/java/com/hyfixes/early/config/EarlyPluginConfig.java`
- Modify: `hyfixes-early/src/main/java/com/hyfixes/early/config/EarlyConfigManager.java`
- Modify: `src/main/java/com/hyfixes/config/HyFixesConfig.java`

**Step 1: Add toggle to EarlyPluginConfig.TransformersConfig**

In `EarlyPluginConfig.java`, add new field to `TransformersConfig` class:

```java
public boolean uuidSystem = true;
```

**Step 2: Add case to EarlyConfigManager.isTransformerEnabled()**

In `EarlyConfigManager.java`, add new case in switch statement:

```java
case "uuidsystem" -> t.uuidSystem;
```

**Step 3: Sync toggle to HyFixesConfig.TransformersConfig**

In `HyFixesConfig.java`, add matching field:

```java
public boolean uuidSystem = true;
```

**Step 4: Commit**

```bash
git add hyfixes-early/src/main/java/com/hyfixes/early/config/EarlyPluginConfig.java
git add hyfixes-early/src/main/java/com/hyfixes/early/config/EarlyConfigManager.java
git add src/main/java/com/hyfixes/config/HyFixesConfig.java
git commit -m "feat(config): add uuidSystem transformer toggle

Co-Authored-By: Claude Opus 4.5 <noreply@anthropic.com>"
```

---

## 🔧 Task 2: Create UUIDSystemTransformer

**Files:**
- Create: `hyfixes-early/src/main/java/com/hyfixes/early/UUIDSystemTransformer.java`

**Step 1: Write the transformer**

```java
package com.hyfixes.early;

import com.hyfixes.early.config.EarlyConfigManager;
import com.hypixel.hytale.plugin.early.ClassTransformer;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;

/**
 * Transformer for EntityStore$UUIDSystem to fix NPE during chunk unload.
 *
 * The vanilla UUIDSystem.onEntityRemove() method can crash when uuidComponent
 * is null - which happens when entities are removed during chunk unload before
 * their UUID component is fully initialized.
 *
 * @see <a href="https://github.com/John-Willikers/hyfixes/issues/28">GitHub Issue #28</a>
 */
public class UUIDSystemTransformer implements ClassTransformer {

    private static final String TARGET_CLASS = "com.hypixel.hytale.server.entity.EntityStore$UUIDSystem";

    @Override
    public String targetClass() {
        return TARGET_CLASS;
    }

    @Override
    public byte[] transform(byte[] classBytes) {
        // Check if transformer is enabled
        if (!EarlyConfigManager.getInstance().isTransformerEnabled("uuidSystem")) {
            System.out.println("[HyFixes-Early] UUIDSystemTransformer is disabled, skipping");
            return classBytes;
        }

        try {
            System.out.println("[HyFixes-Early] Transforming: " + TARGET_CLASS);

            ClassReader reader = new ClassReader(classBytes);
            ClassWriter writer = new ClassWriter(reader, ClassWriter.COMPUTE_FRAMES | ClassWriter.COMPUTE_MAXS);

            UUIDSystemVisitor visitor = new UUIDSystemVisitor(writer);
            reader.accept(visitor, ClassReader.EXPAND_FRAMES);

            if (visitor.isTransformed()) {
                System.out.println("[HyFixes-Early] Successfully transformed UUIDSystem.onEntityRemove()");
                return writer.toByteArray();
            } else {
                System.err.println("[HyFixes-Early] WARNING: UUIDSystem transformation did not apply!");
                return classBytes;
            }
        } catch (Exception e) {
            System.err.println("[HyFixes-Early] Error transforming UUIDSystem: " + e.getMessage());
            e.printStackTrace();
            return classBytes;
        }
    }
}
```

**Step 2: Commit**

```bash
git add hyfixes-early/src/main/java/com/hyfixes/early/UUIDSystemTransformer.java
git commit -m "feat(early): add UUIDSystemTransformer for chunk unload NPE fix

Targets EntityStore\$UUIDSystem to fix vanilla NPE when uuidComponent
is null during entity removal in chunk unload.

Fixes: #28

Co-Authored-By: Claude Opus 4.5 <noreply@anthropic.com>"
```

---

## 🔧 Task 3: Create UUIDSystemVisitor

**Files:**
- Create: `hyfixes-early/src/main/java/com/hyfixes/early/UUIDSystemVisitor.java`

**Step 1: Write the class visitor**

```java
package com.hyfixes.early;

import org.objectweb.asm.ClassVisitor;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;

/**
 * ASM ClassVisitor for EntityStore$UUIDSystem.
 *
 * Intercepts the onEntityRemove method to inject null check for uuidComponent.
 */
public class UUIDSystemVisitor extends ClassVisitor {

    private static final String TARGET_METHOD = "onEntityRemove";
    private static final String TARGET_DESCRIPTOR = "(I)V"; // Takes entity ID (int), returns void

    private String className;
    private boolean transformed = false;

    public UUIDSystemVisitor(ClassVisitor classVisitor) {
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

        if (name.equals(TARGET_METHOD) && descriptor.equals(TARGET_DESCRIPTOR)) {
            System.out.println("[HyFixes-Early] Found method: " + className + "." + name + descriptor);
            System.out.println("[HyFixes-Early] Applying uuidComponent null check...");
            transformed = true;
            return new UUIDRemoveMethodVisitor(mv, className);
        }

        return mv;
    }

    /**
     * Check if the transformation was applied.
     */
    public boolean isTransformed() {
        return transformed;
    }
}
```

**Step 2: Commit**

```bash
git add hyfixes-early/src/main/java/com/hyfixes/early/UUIDSystemVisitor.java
git commit -m "feat(early): add UUIDSystemVisitor to intercept onEntityRemove

Co-Authored-By: Claude Opus 4.5 <noreply@anthropic.com>"
```

---

## 🔧 Task 4: Create UUIDRemoveMethodVisitor

**Files:**
- Create: `hyfixes-early/src/main/java/com/hyfixes/early/UUIDRemoveMethodVisitor.java`

**Step 1: Write the method visitor**

This is the core fix. We need to inject a null check after uuidComponent is loaded but before getUuid() is called.

The original bytecode flow is approximately:
1. Load uuidComponent from some field/method call
2. Call `uuidComponent.getUuid()`
3. Use the UUID to remove from map

We inject:
1. After uuidComponent is loaded onto stack, duplicate it
2. Check if null with IFNONNULL
3. If null: log warning, return early
4. If not null: continue normal execution

```java
package com.hyfixes.early;

import org.objectweb.asm.Label;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;

/**
 * ASM MethodVisitor that injects null check for uuidComponent in onEntityRemove.
 *
 * The original code does:
 *   UUIDComponent uuidComponent = ...;
 *   UUID uuid = uuidComponent.getUuid();  // NPE if uuidComponent is null!
 *   this.uuidToEntity.remove(uuid);
 *
 * We inject a null check before the getUuid() call:
 *   if (uuidComponent == null) {
 *       System.err.println("[HyFixes] Warning: uuidComponent is null for entity " + entityId);
 *       return;  // Safe early return
 *   }
 */
public class UUIDRemoveMethodVisitor extends MethodVisitor {

    private final String className;

    // Track state for injection
    private boolean injectedNullCheck = false;

    // UUIDComponent class internal name
    private static final String UUID_COMPONENT_TYPE = "com/hypixel/hytale/server/entity/components/UUIDComponent";
    private static final String GET_UUID_METHOD = "getUuid";

    public UUIDRemoveMethodVisitor(MethodVisitor methodVisitor, String className) {
        super(Opcodes.ASM9, methodVisitor);
        this.className = className;
    }

    @Override
    public void visitMethodInsn(int opcode, String owner, String name, String descriptor, boolean isInterface) {
        // Detect call to UUIDComponent.getUuid()
        if (!injectedNullCheck &&
            opcode == Opcodes.INVOKEVIRTUAL &&
            owner.equals(UUID_COMPONENT_TYPE) &&
            name.equals(GET_UUID_METHOD)) {

            System.out.println("[HyFixes-Early] Injecting null check before " + owner + "." + name);

            // At this point, uuidComponent is on the stack (ready for getUuid() call)
            // We need to: DUP it, check null, then continue

            // DUP the object reference
            mv.visitInsn(Opcodes.DUP);

            // Create label for "not null" continuation
            Label notNullLabel = new Label();

            // IFNONNULL - jump to notNullLabel if not null
            mv.visitJumpInsn(Opcodes.IFNONNULL, notNullLabel);

            // === NULL CASE ===
            // Pop the null reference (we DUP'd it)
            mv.visitInsn(Opcodes.POP);

            // Log warning: System.err.println("[HyFixes] uuidComponent null in onEntityRemove")
            mv.visitFieldInsn(Opcodes.GETSTATIC, "java/lang/System", "err", "Ljava/io/PrintStream;");
            mv.visitLdcInsn("[HyFixes] Warning: uuidComponent is null during entity removal - skipping UUID cleanup");
            mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL, "java/io/PrintStream", "println", "(Ljava/lang/String;)V", false);

            // Return early (method returns void)
            mv.visitInsn(Opcodes.RETURN);

            // === NOT NULL CASE ===
            mv.visitLabel(notNullLabel);
            // Stack still has original uuidComponent, continue with getUuid() call

            injectedNullCheck = true;
        }

        // Continue with original instruction
        super.visitMethodInsn(opcode, owner, name, descriptor, isInterface);
    }
}
```

**Step 2: Commit**

```bash
git add hyfixes-early/src/main/java/com/hyfixes/early/UUIDRemoveMethodVisitor.java
git commit -m "feat(early): add UUIDRemoveMethodVisitor with null check injection

Injects null check before uuidComponent.getUuid() call to prevent NPE
during chunk unload when entity has no UUID component initialized.

Co-Authored-By: Claude Opus 4.5 <noreply@anthropic.com>"
```

---

## 🔧 Task 5: Register Transformer in SPI

**Files:**
- Modify: `hyfixes-early/src/main/resources/META-INF/services/com.hypixel.hytale.plugin.early.ClassTransformer`

**Step 1: Add transformer to SPI file**

Add this line to the file:

```
com.hyfixes.early.UUIDSystemTransformer
```

**Step 2: Commit**

```bash
git add hyfixes-early/src/main/resources/META-INF/services/com.hypixel.hytale.plugin.early.ClassTransformer
git commit -m "feat(early): register UUIDSystemTransformer in SPI

Co-Authored-By: Claude Opus 4.5 <noreply@anthropic.com>"
```

---

## 🔧 Task 6: Update BUGS_FIXED.md Documentation

**Files:**
- Modify: `BUGS_FIXED.md`

**Step 1: Add Fix 22 documentation**

Add new section for Fix 22:

```markdown
---

## Fix 22: UUIDSystem Null Check During Chunk Unload (v1.9.5)

**Bug:** Server crashes with NPE when removing entities during chunk unload because `uuidComponent` is null.

**Stack trace:**
```
java.lang.NullPointerException: Cannot invoke "UUIDComponent.getUuid()" because "uuidComponent" is null
    at EntityStore$UUIDSystem.onEntityRemove(EntityStore.java:201)
    at ChunkUnloadingSystem.lambda$tryUnload$1(ChunkUnloadingSystem.java:141)
```

**Root cause:** Vanilla Hytale bug. During chunk unload, entities can be removed before their UUIDComponent is fully initialized. The `onEntityRemove` method assumes the component is never null.

**Fix:** ASM bytecode transformer (`UUIDSystemTransformer`) injects a null check before the `uuidComponent.getUuid()` call:
- If null: logs warning and returns early (safe no-op)
- If not null: continues with normal UUID cleanup

**Configuration:**
```json
{
  "transformers": {
    "uuidSystem": true
  }
}
```

**Files:**
- `UUIDSystemTransformer.java` - Main transformer
- `UUIDSystemVisitor.java` - Class visitor for onEntityRemove
- `UUIDRemoveMethodVisitor.java` - Null check injection

**GitHub Issue:** [#28](https://github.com/John-Willikers/hyfixes/issues/28)
```

**Step 2: Commit**

```bash
git add BUGS_FIXED.md
git commit -m "docs: add Fix 22 - UUIDSystem null check during chunk unload

Co-Authored-By: Claude Opus 4.5 <noreply@anthropic.com>"
```

---

## 🔧 Task 7: Build and Test

**Step 1: Build the project**

```bash
./gradlew build
```

Expected: BUILD SUCCESSFUL

**Step 2: Verify transformer is registered**

Check that the SPI file contains all transformers including the new one.

**Step 3: Commit if any fixes needed**

If build fails, fix issues and commit.

---

## ✅ Completion Checklist

- [ ] Config toggle added to EarlyPluginConfig, EarlyConfigManager, and HyFixesConfig
- [ ] UUIDSystemTransformer created
- [ ] UUIDSystemVisitor created
- [ ] UUIDRemoveMethodVisitor created with null check injection
- [ ] Transformer registered in SPI file
- [ ] BUGS_FIXED.md updated with Fix 22
- [ ] Build passes
- [ ] All commits made with proper messages

---

## 📝 Notes

**Testing:** This fix can only be fully tested on a live Hytale server experiencing the chunk unload crash. The transformation applies at class load time, so verify via startup logs showing the transformer applied successfully.

**Startup logs should show:**
```
[HyFixes-Early] Transforming: com.hypixel.hytale.server.entity.EntityStore$UUIDSystem
[HyFixes-Early] Found method: com/hypixel/hytale/server/entity/EntityStore$UUIDSystem.onEntityRemove(I)V
[HyFixes-Early] Applying uuidComponent null check...
[HyFixes-Early] Injecting null check before com/hypixel/hytale/server/entity/components/UUIDComponent.getUuid
[HyFixes-Early] Successfully transformed UUIDSystem.onEntityRemove()
```
