# Fix Broken Early Plugin Transformers Implementation Plan

> **For Claude:** REQUIRED SUB-SKILL: Use superpowers:executing-plans to implement this plan task-by-task.

**Goal:** Fix two broken ASM bytecode transformers (PacketHandler and UUIDSystem) that have incorrect class paths and method descriptors, causing NoClassDefFoundError crashes on production servers.

**Architecture:** Update the hardcoded class paths and method descriptors in the ASM transformers to match the actual Hytale server bytecode structure. PacketHandler needs corrected paths for PongType (moved to protocol package) and PingInfo (is an inner class). UUIDSystem needs corrected package path and method descriptor for onEntityRemove().

**Tech Stack:** Java 21, ASM 9, Gradle

---

## Background

Production server crash analysis revealed two broken transformers:

1. **PacketHandler/OperationTimeoutMethodVisitor** - References non-existent class paths:
   - `PongType` wrong: `com/hypixel/hytale/server/core/io/PongType`
   - `PongType` actual: `com/hypixel/hytale/protocol/packets/connection/PongType`
   - `PingInfo` wrong: standalone class reference
   - `PingInfo` actual: `com/hypixel/hytale/server/core/io/PacketHandler$PingInfo` (inner class)
   - `PingMetricSet` wrong: `com/hypixel/hytale/server/core/io/PingMetricSet`
   - `PingMetricSet` actual: `com/hypixel/hytale/metrics/metric/HistoricMetric`

2. **UUIDSystem** - Wrong package and method descriptor:
   - Package wrong: `com.hypixel.hytale.server.entity.EntityStore$UUIDSystem`
   - Package actual: `com.hypixel.hytale.server.core.universe.world.storage.EntityStore$UUIDSystem`
   - Descriptor wrong: `(I)V`
   - Descriptor actual: `(Lcom/hypixel/hytale/component/Ref;Lcom/hypixel/hytale/component/RemoveReason;Lcom/hypixel/hytale/component/Store;Lcom/hypixel/hytale/component/CommandBuffer;)V`

---

### Task 1: Fix OperationTimeoutMethodVisitor Class Paths

**Files:**
- Modify: `hyfixes-early/src/main/java/com/hyfixes/early/OperationTimeoutMethodVisitor.java:21-24`

**Step 1: Update the class path constants**

Replace lines 21-24 with corrected paths:

```java
// Class/field references - CORRECTED paths from decompiled HytaleServer.jar
private static final String PONG_TYPE = "com/hypixel/hytale/protocol/packets/connection/PongType";
private static final String PING_INFO = "com/hypixel/hytale/server/core/io/PacketHandler$PingInfo";
private static final String HISTORIC_METRIC = "com/hypixel/hytale/metrics/metric/HistoricMetric";
private static final String TIME_UNIT = "java/util/concurrent/TimeUnit";
```

**Step 2: Update method calls to use corrected class names**

In `generateFixedMethod()`, update the `getPingInfo` return type and `getPingMetricSet` references:

Line 58-61 - Fix getPingInfo call:
```java
target.visitMethodInsn(Opcodes.INVOKEVIRTUAL, className, "getPingInfo",
        "(L" + PONG_TYPE + ";)L" + PING_INFO + ";", false);
```

Line 60-63 - Fix getPingMetricSet call (returns HistoricMetric, not PingMetricSet):
```java
target.visitMethodInsn(Opcodes.INVOKEVIRTUAL, PING_INFO, "getPingMetricSet",
        "()L" + HISTORIC_METRIC + ";", false);
target.visitInsn(Opcodes.ICONST_0);  // 0 for getAverage parameter
target.visitMethodInsn(Opcodes.INVOKEVIRTUAL, HISTORIC_METRIC, "getAverage", "(I)D", false);
```

Line 69 - Fix TIME_UNIT reference (it's a static field on PingInfo, not a separate class):
```java
target.visitFieldInsn(Opcodes.GETSTATIC, PING_INFO, "TIME_UNIT", "L" + TIME_UNIT + ";");
```

**Step 3: Build to verify no compile errors**

Run: `./gradlew :hyfixes-early:compileJava`
Expected: BUILD SUCCESSFUL

**Step 4: Commit**

```bash
git add hyfixes-early/src/main/java/com/hyfixes/early/OperationTimeoutMethodVisitor.java
git commit -m "fix(early): correct PacketHandler transformer class paths

- PongType moved to protocol.packets.connection package
- PingInfo is inner class PacketHandler\$PingInfo
- PingMetricSet renamed to HistoricMetric in metrics.metric package
- TIME_UNIT is static field on PingInfo, not TimeUnit class

Fixes production NoClassDefFoundError: PongType crash

Co-Authored-By: Claude Opus 4.5 <noreply@anthropic.com>"
```

---

### Task 2: Fix UUIDSystemTransformer Package Path

**Files:**
- Modify: `hyfixes-early/src/main/java/com/hyfixes/early/UUIDSystemTransformer.java:19`

**Step 1: Update the TARGET_CLASS constant**

Replace line 19:

```java
private static final String TARGET_CLASS = "com.hypixel.hytale.server.core.universe.world.storage.EntityStore$UUIDSystem";
```

**Step 2: Build to verify no compile errors**

Run: `./gradlew :hyfixes-early:compileJava`
Expected: BUILD SUCCESSFUL

**Step 3: Commit**

```bash
git add hyfixes-early/src/main/java/com/hyfixes/early/UUIDSystemTransformer.java
git commit -m "fix(early): correct UUIDSystem transformer package path

Changed from com.hypixel.hytale.server.entity to
com.hypixel.hytale.server.core.universe.world.storage

Co-Authored-By: Claude Opus 4.5 <noreply@anthropic.com>"
```

---

### Task 3: Fix UUIDSystemVisitor Method Descriptor

**Files:**
- Modify: `hyfixes-early/src/main/java/com/hyfixes/early/UUIDSystemVisitor.java:15`

**Step 1: Update the TARGET_DESCRIPTOR constant**

Replace line 15:

```java
private static final String TARGET_DESCRIPTOR = "(Lcom/hypixel/hytale/component/Ref;Lcom/hypixel/hytale/component/RemoveReason;Lcom/hypixel/hytale/component/Store;Lcom/hypixel/hytale/component/CommandBuffer;)V";
```

**Step 2: Build to verify no compile errors**

Run: `./gradlew :hyfixes-early:compileJava`
Expected: BUILD SUCCESSFUL

**Step 3: Commit**

```bash
git add hyfixes-early/src/main/java/com/hyfixes/early/UUIDSystemVisitor.java
git commit -m "fix(early): correct UUIDSystem onEntityRemove method descriptor

Changed from (I)V to full RefSystem signature with Ref, RemoveReason,
Store, and CommandBuffer parameters

Co-Authored-By: Claude Opus 4.5 <noreply@anthropic.com>"
```

---

### Task 4: Fix UUIDRemoveMethodVisitor for New Signature

**Files:**
- Modify: `hyfixes-early/src/main/java/com/hyfixes/early/UUIDRemoveMethodVisitor.java`

**Step 1: Read current implementation**

Read the file to understand what needs to change for the new method signature.

**Step 2: Update to handle RefSystem parameters**

The method signature changed from `(int entityId)` to `(Ref ref, RemoveReason reason, Store store, CommandBuffer commandBuffer)`. The null check injection needs to account for the new local variable slots:
- Slot 0: this
- Slot 1: ref (Ref)
- Slot 2: reason (RemoveReason)
- Slot 3: store (Store)
- Slot 4: commandBuffer (CommandBuffer)

The uuidComponent is obtained via `commandBuffer.getComponent(ref, UUIDComponent.getComponentType())`, so we need to inject a null check AFTER that call, not at method entry.

**Step 3: Build to verify no compile errors**

Run: `./gradlew :hyfixes-early:compileJava`
Expected: BUILD SUCCESSFUL

**Step 4: Commit**

```bash
git add hyfixes-early/src/main/java/com/hyfixes/early/UUIDRemoveMethodVisitor.java
git commit -m "fix(early): update UUIDRemoveMethodVisitor for RefSystem signature

Adjusted null check injection for new method parameters

Co-Authored-By: Claude Opus 4.5 <noreply@anthropic.com>"
```

---

### Task 5: Build Full Plugin and Test

**Files:**
- None (build and test only)

**Step 1: Clean build entire project**

Run: `./gradlew clean build -Pversion=1.9.7`
Expected: BUILD SUCCESSFUL with hyfixes-1.9.7.jar created

**Step 2: Verify JAR contains updated classes**

Run: `unzip -l build/libs/hyfixes-1.9.7.jar | grep -E "OperationTimeout|UUIDSystem"`
Expected: Shows the transformer classes in the JAR

**Step 3: Deploy to dev server and test**

Copy JAR to dev server earlyplugins folder and restart server.
Check logs for:
- `[HyFixes-Early] Transforming: com.hypixel.hytale.server.core.io.PacketHandler`
- `[HyFixes-Early] Transforming: com.hypixel.hytale.server.core.universe.world.storage.EntityStore$UUIDSystem`
- No NoClassDefFoundError or transformation warnings

**Step 4: Update version in manifest.json**

Modify `src/main/resources/manifest.json` line 4:
```json
"Version": "1.9.7",
```

**Step 5: Commit version bump**

```bash
git add src/main/resources/manifest.json
git commit -m "chore: bump version to 1.9.7

Co-Authored-By: Claude Opus 4.5 <noreply@anthropic.com>"
```

---

### Task 6: Update CHANGELOG and Release

**Files:**
- Modify: `CHANGELOG.md`

**Step 1: Add v1.9.7 changelog entry**

Add at the top of CHANGELOG.md:

```markdown
## [1.9.7] - 2026-01-20

### Fixed
- **PacketHandler transformer**: Corrected class paths for PongType (now in protocol.packets.connection), PingInfo (inner class), and HistoricMetric (renamed from PingMetricSet)
- **UUIDSystem transformer**: Fixed package path (server.core.universe.world.storage) and method descriptor for onEntityRemove()

### Technical Details
- PongType: `com/hypixel/hytale/protocol/packets/connection/PongType`
- PingInfo: `com/hypixel/hytale/server/core/io/PacketHandler$PingInfo`
- HistoricMetric: `com/hypixel/hytale/metrics/metric/HistoricMetric`
- UUIDSystem: `com.hypixel.hytale.server.core.universe.world.storage.EntityStore$UUIDSystem`
```

**Step 2: Commit changelog**

```bash
git add CHANGELOG.md
git commit -m "docs: add v1.9.7 changelog

Co-Authored-By: Claude Opus 4.5 <noreply@anthropic.com>"
```

**Step 3: Create release tag**

```bash
git tag -a v1.9.7 -m "v1.9.7 - Fix broken transformer class paths"
git push origin main --tags
```

---

## Verification Checklist

After completing all tasks, verify:

- [ ] `./gradlew clean build -Pversion=1.9.7` succeeds
- [ ] Dev server boots without NoClassDefFoundError
- [ ] Logs show successful transformation of PacketHandler
- [ ] Logs show successful transformation of UUIDSystem
- [ ] Players can connect without timeout kicks (PacketHandler fix)
- [ ] Entities can be removed without NPE (UUIDSystem fix)
- [ ] Production server can be updated with interactionTimeout re-enabled
