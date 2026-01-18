package com.hyfixes.commands;

import com.hyfixes.HyFixes;
import com.hypixel.hytale.server.core.command.system.basecommands.AbstractPlayerCommand;
import com.hypixel.hytale.server.core.command.system.CommandContext;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import com.hypixel.hytale.component.ComponentType;
import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.server.core.entity.entities.Player;
import com.hypixel.hytale.server.core.universe.PlayerRef;
import com.hypixel.hytale.server.core.universe.world.World;
import com.hypixel.hytale.server.core.Message;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.logging.Level;

/**
 * CleanInteractionsCommand - Removes orphaned interaction zones from deleted blocks
 *
 * GitHub Issue: https://github.com/John-Willikers/hyfixes/issues/11
 *
 * The Bug:
 * When teleporters (or other interactable blocks) are removed while the TrackedPlacement
 * component fails, the block gets removed but the interaction zone may remain.
 * This causes "Press F to interact" to appear at locations where blocks no longer exist.
 *
 * The Fix:
 * This command allows admins to scan for and remove orphaned interaction chains.
 *
 * Usage:
 *   /cleaninteractions         - Scan for orphaned interactions
 *   /cleaninteractions scan    - Same as above
 *   /cleaninteractions clean   - Remove all found orphaned interactions
 */
public class CleanInteractionsCommand extends AbstractPlayerCommand {

    private final HyFixes plugin;

    // Reflection fields - discovered at runtime
    private boolean initialized = false;
    private boolean initFailed = false;

    // InteractionManager access
    @SuppressWarnings("rawtypes")
    private ComponentType interactionManagerType = null;
    private Method getChainsMethod = null;
    private Field contextField = null;
    private Field targetEntityRefField = null;

    public CleanInteractionsCommand(HyFixes plugin) {
        super("cleaninteractions", "hyfixes.command.cleaninteractions.desc");
        this.plugin = plugin;
        addAliases("ci", "cleanint", "fixinteractions");
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

        // Parse arguments
        String inputString = context.getInputString();
        String[] parts = inputString.trim().split("\\s+");
        String[] args = parts.length > 1 ? java.util.Arrays.copyOfRange(parts, 1, parts.length) : new String[0];

        String action = args.length > 0 ? args[0].toLowerCase() : "scan";

        // Initialize reflection API if needed
        if (!initialized && !initFailed) {
            initializeApi(player);
        }

        if (initFailed) {
            sendMessage(player, "&c[HyFixes] CleanInteractions API not available - see server logs");
            return;
        }

        switch (action) {
            case "scan":
                scanForOrphans(player, store, ref, world, false);
                break;
            case "clean":
            case "remove":
            case "fix":
                scanForOrphans(player, store, ref, world, true);
                break;
            case "help":
            default:
                showHelp(player);
                break;
        }
    }

    private void showHelp(Player player) {
        sendMessage(player, "&6=== CleanInteractions Help ===");
        sendMessage(player, "&7/cleaninteractions scan &f- Scan for orphaned interactions");
        sendMessage(player, "&7/cleaninteractions clean &f- Remove orphaned interactions");
        sendMessage(player, "&7/cleaninteractions help &f- Show this help");
        sendMessage(player, "&7");
        sendMessage(player, "&eNote: Ghost 'Press F' prompts may require a rejoin to clear");
        sendMessage(player, "&eif they persist after cleaning.");
    }

    private void initializeApi(Player player) {
        try {
            plugin.getLogger().at(Level.INFO).log("[CleanInteractions] Discovering API...");

            // Get InteractionManager component type
            Class<?> interactionModuleClass = Class.forName("com.hypixel.hytale.server.core.modules.interaction.InteractionModule");
            Method getModuleMethod = interactionModuleClass.getMethod("get");
            Object interactionModule = getModuleMethod.invoke(null);
            Method getComponentTypeMethod = interactionModuleClass.getMethod("getInteractionManagerComponent");
            interactionManagerType = (ComponentType) getComponentTypeMethod.invoke(interactionModule);

            // Get InteractionManager.getChains()
            Class<?> interactionManagerClass = Class.forName("com.hypixel.hytale.server.core.entity.InteractionManager");
            getChainsMethod = interactionManagerClass.getMethod("getChains");

            // Get InteractionChain.context
            Class<?> interactionChainClass = Class.forName("com.hypixel.hytale.server.core.entity.InteractionChain");
            contextField = interactionChainClass.getDeclaredField("context");
            contextField.setAccessible(true);

            // Try to find InteractionContext fields for target entity
            Class<?> interactionContextClass = Class.forName("com.hypixel.hytale.server.core.entity.InteractionContext");

            // Look for target entity ref field
            String[] refFieldNames = {"targetEntity", "targetEntityRef", "target", "targetRef", "interactingWith"};
            for (String fieldName : refFieldNames) {
                try {
                    targetEntityRefField = interactionContextClass.getDeclaredField(fieldName);
                    targetEntityRefField.setAccessible(true);
                    plugin.getLogger().at(Level.INFO).log("[CleanInteractions] Found target entity field: " + fieldName);
                    break;
                } catch (NoSuchFieldException e) {
                    // Try next
                }
            }

            initialized = true;
            plugin.getLogger().at(Level.INFO).log("[CleanInteractions] API discovery successful!");

        } catch (Exception e) {
            plugin.getLogger().at(Level.WARNING).log("[CleanInteractions] API discovery failed: " + e.getMessage());
            e.printStackTrace();
            initFailed = true;
        }
    }

    private void scanForOrphans(Player player, Store<EntityStore> store, Ref<EntityStore> playerRef, World world, boolean remove) {
        try {
            sendMessage(player, "&6[HyFixes] Scanning for orphaned interactions...");

            int scanned = 0;
            int orphansFound = 0;
            int removed = 0;
            List<String> orphanDetails = new ArrayList<>();

            // Get the player's InteractionManager
            Object interactionManager = store.getComponent(playerRef, interactionManagerType);
            if (interactionManager != null) {
                @SuppressWarnings("unchecked")
                Map<Integer, Object> chains = (Map<Integer, Object>) getChainsMethod.invoke(interactionManager);

                if (chains != null && !chains.isEmpty()) {
                    List<Integer> toRemove = new ArrayList<>();

                    for (Map.Entry<Integer, Object> entry : chains.entrySet()) {
                        scanned++;
                        Object chain = entry.getValue();

                        if (chain == null) {
                            orphansFound++;
                            toRemove.add(entry.getKey());
                            orphanDetails.add("Chain " + entry.getKey() + " (null chain)");
                            continue;
                        }

                        Object context = contextField.get(chain);
                        if (context == null) {
                            orphansFound++;
                            toRemove.add(entry.getKey());
                            orphanDetails.add("Chain " + entry.getKey() + " (null context)");
                            continue;
                        }

                        // Check if target entity ref is valid
                        if (targetEntityRefField != null) {
                            try {
                                Object targetRef = targetEntityRefField.get(context);
                                if (targetRef instanceof Ref) {
                                    Ref<?> tRef = (Ref<?>) targetRef;
                                    if (!tRef.isValid()) {
                                        orphansFound++;
                                        toRemove.add(entry.getKey());
                                        orphanDetails.add("Chain " + entry.getKey() + " (invalid target ref)");
                                    }
                                }
                            } catch (Exception e) {
                                // Ignore field access errors
                            }
                        }
                    }

                    if (remove && !toRemove.isEmpty()) {
                        for (Integer chainId : toRemove) {
                            chains.remove(chainId);
                            removed++;
                        }
                    }
                }
            }

            // Report results
            sendMessage(player, "&6=== Scan Results ===");
            sendMessage(player, "&7Interaction chains scanned: &f" + scanned);
            sendMessage(player, "&7Orphaned interactions found: &e" + orphansFound);

            if (remove && removed > 0) {
                sendMessage(player, "&aOrphaned interactions removed: &f" + removed);
                plugin.getLogger().at(Level.INFO).log(
                    "[CleanInteractions] Removed %d orphaned interaction chains for player",
                    removed
                );
            } else if (!remove && orphansFound > 0) {
                sendMessage(player, "&7Run &f/cleaninteractions clean &7to remove them");
            }

            if (!orphanDetails.isEmpty() && orphanDetails.size() <= 5) {
                sendMessage(player, "&7Details:");
                for (String detail : orphanDetails) {
                    sendMessage(player, "&7  - " + detail);
                }
            }

            // Suggest rejoin if ghost interactions may remain
            if (orphansFound == 0) {
                sendMessage(player, "&7");
                sendMessage(player, "&eNo orphaned chains found in your InteractionManager.");
                sendMessage(player, "&7If you still see ghost 'Press F' prompts:");
                sendMessage(player, "&7  1. &fRejoin the server &7(clears client cache)");
                sendMessage(player, "&7  2. The ghost may be a client-side artifact");
            }

        } catch (Exception e) {
            sendMessage(player, "&c[HyFixes] Error during scan: " + e.getMessage());
            plugin.getLogger().at(Level.WARNING).log("[CleanInteractions] Scan error: " + e.getMessage());
            e.printStackTrace();
        }
    }

    private void sendMessage(Player player, String message) {
        String formatted = message.replace("&", "\u00A7");
        player.sendMessage(Message.raw(formatted));
    }
}
