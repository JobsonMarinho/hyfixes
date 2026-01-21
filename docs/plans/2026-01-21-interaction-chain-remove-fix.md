# Plan: Fix InteractionChain Remove Out of Order & Client Timeout Bugs

## Overview

This plan addresses GitHub Issue #40 - two related bugs in the interaction system that kick players during normal gameplay.

**Target Release:** v1.10.0

## Bug Summary

### Bug A: InteractionChain.removeInteractionEntry() - "Trying to remove out of order"
- **Symptom:** Player kicked with "internal exception"
- **Error:** `IllegalArgumentException: Trying to remove out of order`
- **Cause:** The method enforces strict FIFO removal order, but rapid disconnects or network issues can cause entries to become stale and removal order violated
- **Frequency:** Sporadic, more common on high-latency connections

### Bug B: InteractionManager.serverTick() - "Client took too long to send clientData"
- **Symptom:** Player kicked with "internal exception"
- **Error:** `RuntimeException: Client took too long to send clientData`
- **Cause:** Strict timeout enforcement punishes network latency with a hard crash
- **Frequency:** Common during lag spikes, especially on busy servers

## Implementation Approach

### Approach: Wrap Exceptions (Recommended)

Both bugs involve hard exceptions that should be graceful failures. The fix pattern:

1. **Bug A:** Wrap `removeInteractionEntry()` exception in try-catch, log and continue instead of crash
2. **Bug B:** Wrap the timeout exception in try-catch, cancel interaction gracefully instead of crash

**Why this approach:**
- Minimal bytecode changes (just wrap in try-catch)
- Preserves original logic for debugging
- Graceful degradation - interaction fails silently rather than kicking player
- Consistent with existing HyFixes patterns (see `WorldMapTrackerTransformer`)

## Files to Create/Modify

### New Files:
1. `RemoveInteractionEntryMethodVisitor.java` - Wraps removeInteractionEntry() in try-catch
2. `InteractionManagerTransformer.java` - New transformer for InteractionManager class
3. `InteractionManagerVisitor.java` - Class visitor for InteractionManager
4. `ServerTickMethodVisitor.java` - Wraps serverTick() timeout exception

### Modified Files:
1. `InteractionChainVisitor.java` - Add handling for removeInteractionEntry method
2. `META-INF/services/com.hypixel.hytale.plugin.early.ClassTransformer` - Register new transformer

## Implementation Tasks

### Task 1: Add RemoveInteractionEntryMethodVisitor
Create a new method visitor that wraps the `removeInteractionEntry()` method body in a try-catch block to handle `IllegalArgumentException`.

**Pattern:**
```java
// Original:
public void removeInteractionEntry(InteractionManager mgr, int index) {
    // ... validation that throws IllegalArgumentException
}

// Fixed:
public void removeInteractionEntry(InteractionManager mgr, int index) {
    try {
        // ... original validation
    } catch (IllegalArgumentException e) {
        // Log warning and return gracefully
        LOGGER.warn("Suppressed out-of-order removal: {}", e.getMessage());
    }
}
```

### Task 2: Update InteractionChainVisitor
Modify the visitor to also intercept `removeInteractionEntry` method and apply the new visitor.

### Task 3: Create InteractionManagerTransformer
New transformer targeting `com.hypixel.hytale.server.core.entity.InteractionManager`.

### Task 4: Create InteractionManagerVisitor
Class visitor that intercepts the `serverTick` method.

### Task 5: Create ServerTickMethodVisitor
Method visitor that wraps the timeout exception handling.

**Pattern:**
```java
// Original serverTick throws:
throw new RuntimeException("Client took too long to send clientData for entity " + ...);

// Fixed - wrap the check in try-catch:
try {
    // original timeout check
} catch (RuntimeException e) {
    if (e.getMessage() != null && e.getMessage().contains("Client took too long")) {
        LOGGER.warn("Suppressed client timeout: {}", e.getMessage());
        // Cancel interaction gracefully
        return null; // or appropriate fallback
    }
    throw e; // Re-throw if different exception
}
```

### Task 6: Register New Transformer
Add `InteractionManagerTransformer` to the services file.

### Task 7: Add Config Toggle
Add `interactionManager` toggle to config for the new transformer.

## Testing Checklist

- [ ] Server starts without errors
- [ ] Transformers log successful patching
- [ ] Players can interact normally (combat, item pickup, etc.)
- [ ] No kicks during high-latency conditions
- [ ] Rapid disconnect/reconnect doesn't crash
- [ ] Existing InteractionChain fixes still work

## Risk Assessment

**Low Risk:**
- Try-catch wrapping is non-invasive
- Falls back to original behavior on unexpected issues
- Can be disabled via config if problematic

## Progress Tracking

- [ ] Task 1: RemoveInteractionEntryMethodVisitor
- [ ] Task 2: Update InteractionChainVisitor
- [ ] Task 3: InteractionManagerTransformer
- [ ] Task 4: InteractionManagerVisitor
- [ ] Task 5: ServerTickMethodVisitor
- [ ] Task 6: Register transformer
- [ ] Task 7: Config toggle
- [ ] Testing complete
