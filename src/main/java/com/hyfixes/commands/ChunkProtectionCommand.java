package com.hyfixes.commands;

import com.hyfixes.HyFixes;
import com.hyfixes.systems.ChunkProtectionRegistry;
import com.hyfixes.systems.ChunkProtectionScanner;
import com.hypixel.hytale.server.core.command.system.basecommands.AbstractPlayerCommand;
import com.hypixel.hytale.server.core.command.system.CommandContext;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.server.core.entity.entities.Player;
import com.hypixel.hytale.server.core.universe.PlayerRef;
import com.hypixel.hytale.server.core.universe.world.World;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.server.core.Message;

/**
 * Command: /chunkprotect
 * Shows and manages chunk protection status
 * 
 * Usage:
 * - /chunkprotect - Show protection status
 * - /chunkprotect list - List protected chunks
 * - /chunkprotect scan - Force scan all loaded chunks
 * - /chunkprotect clear - Clear all protection (dangerous)
 */
public class ChunkProtectionCommand extends AbstractPlayerCommand {

    private final HyFixes plugin;

    public ChunkProtectionCommand(HyFixes plugin) {
        super("chunkprotect", "hyfixes.command.chunkprotect.desc");
        this.plugin = plugin;
        addAliases("cp", "chunkprot");
        setAllowsExtraArguments(true);
    }

    @Override
    protected boolean canGeneratePermission() {
        return true;
    }

    @Override
    protected void execute(
            CommandContext context,
            Store<EntityStore> store,
            Ref<EntityStore> ref,
            PlayerRef playerRef,
            World world
    ) {
        Player player = store.getComponent(ref, Player.getComponentType());
        if (player == null) return;

        ChunkProtectionRegistry registry = plugin.getChunkProtectionRegistry();
        ChunkProtectionScanner scanner = plugin.getChunkProtectionScanner();

        if (registry == null) {
            sendMessage(player, "&c[HyFixes] Chunk protection system is not enabled");
            sendMessage(player, "&7Enable it in config.json: chunkProtection.enabled = true");
            return;
        }

        // Parse subcommand
        String inputString = context.getInputString();
        String[] parts = inputString.trim().split("\\s+");
        String subcommand = parts.length > 1 ? parts[1].toLowerCase() : "";

        switch (subcommand) {
            case "list":
                showProtectedChunksList(player, registry);
                break;
            case "scan":
                forceScan(player, world, registry, scanner);
                break;
            case "clear":
                clearAllProtection(player, registry);
                break;
            default:
                showStatus(player, registry, scanner);
                break;
        }
    }

    private void showStatus(Player player, ChunkProtectionRegistry registry, ChunkProtectionScanner scanner) {
        sendMessage(player, "&6=== Chunk Protection Status ===");
        
        String status = registry.getStatus();
        for (String line : status.split("\n")) {
            if (line.startsWith("===")) {
                continue; // Skip the header from registry
            }
            sendMessage(player, "&7" + line.trim());
        }
        
        if (scanner != null) {
            sendMessage(player, "&6Scanner: &7" + scanner.getStatus());
        }
        
        sendMessage(player, "&6Usage:");
        sendMessage(player, "&7  /chunkprotect list - List protected chunks");
        sendMessage(player, "&7  /chunkprotect scan - Force scan for protected content");
        sendMessage(player, "&7  /chunkprotect clear - Clear all protection (dangerous!)");
    }

    private void showProtectedChunksList(Player player, ChunkProtectionRegistry registry) {
        sendMessage(player, "&6=== Protected Chunks ===");
        
        String list = registry.getProtectedChunksList();
        for (String line : list.split("\n")) {
            sendMessage(player, "&7" + line);
        }
    }

    private void forceScan(Player player, World world, ChunkProtectionRegistry registry, ChunkProtectionScanner scanner) {
        if (scanner == null) {
            sendMessage(player, "&c[HyFixes] Scanner not available");
            return;
        }

        sendMessage(player, "&6[HyFixes] Starting chunk protection scan...");
        
        int before = registry.getProtectedChunkCount();
        long currentTick = System.currentTimeMillis() / 50; // Approximate tick
        
        try {
            int newlyProtected = scanner.scanWorld(world, currentTick);
            int after = registry.getProtectedChunkCount();
            
            sendMessage(player, "&a[HyFixes] Scan complete!");
            sendMessage(player, "&7  Newly protected: &e" + newlyProtected);
            sendMessage(player, "&7  Total protected: &e" + after);
            sendMessage(player, "&7  (was " + before + " before scan)");
        } catch (Exception e) {
            sendMessage(player, "&c[HyFixes] Scan error: " + e.getMessage());
        }
    }

    private void clearAllProtection(Player player, ChunkProtectionRegistry registry) {
        int count = registry.getProtectedChunkCount();
        
        if (count == 0) {
            sendMessage(player, "&7[HyFixes] No chunks are currently protected.");
            return;
        }
        
        sendMessage(player, "&c[HyFixes] WARNING: Clearing all chunk protection!");
        sendMessage(player, "&c[HyFixes] This may allow teleporters to be cleaned up!");
        
        int cleared = registry.clearAllProtections();
        
        sendMessage(player, "&6[HyFixes] Cleared protection from " + cleared + " chunks.");
        sendMessage(player, "&7Protection will be re-detected on next scan cycle.");
    }

    private void sendMessage(Player player, String message) {
        String formatted = message.replace("&", "\u00A7");
        player.sendMessage(Message.raw(formatted));
    }
}
