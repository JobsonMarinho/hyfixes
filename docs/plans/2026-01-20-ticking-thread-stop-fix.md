# TickingThread.stop() UnsupportedOperationException Fix

> **For Claude:** REQUIRED SUB-SKILL: Use superpowers:executing-plans to implement this plan task-by-task.

**Goal:** Fix crash when Hytale tries to force-stop a stuck thread using deprecated `Thread.stop()` on Java 21+.

**Architecture:** Create ASM bytecode transformer that intercepts `Thread.stop()` calls in `TickingThread.stop()` and wraps them in try-catch, falling back to `Thread.interrupt()` on `UnsupportedOperationException`.

**Tech Stack:** Java 25, ASM 9, Hytale Early Plugin System

---

## 📋 Background

**Issue:** [GitHub #32](https://github.com/John-Willikers/hyfixes/issues/32)

**Crash:**
```
java.lang.UnsupportedOperationException
    at java.base/java.lang.Thread.stop(Thread.java:1557)
    at com.hypixel.hytale.server.core.util.thread.TickingThread.stop(TickingThread.java:164)
```

**Root Cause:** Java 21+ removed `Thread.stop()` - it now throws `UnsupportedOperationException`. Hytale's `TickingThread.stop()` method calls `Thread.stop()` to force-kill stuck threads during instance world shutdown.

**Fix Strategy:** Intercept calls to `Thread.stop()` in `TickingThread` and wrap them in try-catch. On `UnsupportedOperationException`, fall back to `Thread.interrupt()` and log a warning.

---

## 🔧 Task 1: Add Config Toggle

**Files:**
- Modify: `hyfixes-early/src/main/java/com/hyfixes/early/config/EarlyPluginConfig.java`
- Modify: `hyfixes-early/src/main/java/com/hyfixes/early/config/EarlyConfigManager.java`
- Modify: `src/main/java/com/hyfixes/config/HyFixesConfig.java`

Add `tickingThread` toggle (default true) to TransformersConfig in all three files, and add case to switch statement in EarlyConfigManager.

**Commit:** `feat(config): add tickingThread transformer toggle`

---

## 🔧 Task 2: Create TickingThreadTransformer

**File:** `hyfixes-early/src/main/java/com/hyfixes/early/TickingThreadTransformer.java`

Standard transformer targeting `com.hypixel.hytale.server.core.util.thread.TickingThread`.

---

## 🔧 Task 3: Create TickingThreadVisitor

**File:** `hyfixes-early/src/main/java/com/hyfixes/early/TickingThreadVisitor.java`

ClassVisitor that intercepts the `stop()` method and returns a custom MethodVisitor.

---

## 🔧 Task 4: Create ThreadStopMethodVisitor

**File:** `hyfixes-early/src/main/java/com/hyfixes/early/ThreadStopMethodVisitor.java`

MethodVisitor that detects `INVOKEVIRTUAL java/lang/Thread.stop()V` and replaces it with:

```java
// Instead of just: thread.stop();
// We emit:
try {
    thread.stop();
} catch (UnsupportedOperationException e) {
    System.err.println("[HyFixes] Thread.stop() not supported on Java 21+, using interrupt()");
    thread.interrupt();
}
```

The bytecode transformation:
1. Before Thread.stop() call: DUP the thread reference (for interrupt fallback)
2. Set up try-catch block labels
3. Call Thread.stop()
4. If exception: pop exception, log warning, call Thread.interrupt() on the DUP'd reference

---

## 🔧 Task 5: Register Transformer in SPI

Add `com.hyfixes.early.TickingThreadTransformer` to SPI file.

---

## 🔧 Task 6: Update BUGS_FIXED.md

Add Fix 24 documentation.

---

## 🔧 Task 7: Build and Test

Verify build passes.

---

## ✅ Completion Checklist

- [ ] Config toggle added
- [ ] TickingThreadTransformer created
- [ ] TickingThreadVisitor created
- [ ] ThreadStopMethodVisitor created
- [ ] SPI registration
- [ ] Documentation updated
- [ ] Build passes
