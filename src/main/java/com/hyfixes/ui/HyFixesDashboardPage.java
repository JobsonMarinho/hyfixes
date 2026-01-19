package com.hyfixes.ui;

import com.hyfixes.HyFixes;
import com.hyfixes.config.ConfigManager;
import com.hyfixes.config.HyFixesConfig;
import com.hyfixes.util.ChatColorUtil;
import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.protocol.packets.interface_.CustomPageLifetime;
import com.hypixel.hytale.protocol.packets.interface_.CustomUIEventBindingType;
import com.hypixel.hytale.server.core.entity.entities.Player;
import com.hypixel.hytale.server.core.entity.entities.player.pages.InteractiveCustomUIPage;
import com.hypixel.hytale.server.core.ui.builder.EventData;
import com.hypixel.hytale.server.core.ui.builder.UICommandBuilder;
import com.hypixel.hytale.server.core.ui.builder.UIEventBuilder;
import com.hypixel.hytale.server.core.universe.PlayerRef;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;

import java.util.logging.Level;

/**
 * HyFixes Admin Dashboard - Visual UI for managing and monitoring HyFixes.
 *
 * Replaces chat-spamming /hyfixes commands with a clean CustomUIPage dashboard
 * showing statistics, sanitizer status, chunk info, and configuration options.
 *
 * Uses CommandListPage.ui as the base layout with tabbed navigation.
 */
public class HyFixesDashboardPage extends InteractiveCustomUIPage<DashboardEventData> {

    // Tab state
    private String currentTab = "overview";

    // Tab constants
    private static final String TAB_OVERVIEW = "overview";
    private static final String TAB_SANITIZERS = "sanitizers";
    private static final String TAB_CHUNKS = "chunks";
    private static final String TAB_CONFIG = "config";

    // Colors for UI elements
    private static final String COLOR_GREEN = "#3d913f";
    private static final String COLOR_YELLOW = "#c9a227";
    private static final String COLOR_RED = "#962f2f";
    private static final String COLOR_WHITE = "#ffffff";
    private static final String COLOR_GRAY = "#aaaaaa";
    private static final String COLOR_GOLD = "#ffaa00";

    public HyFixesDashboardPage(PlayerRef playerRef) {
        super(playerRef, CustomPageLifetime.CanDismiss, DashboardEventData.CODEC);
    }

    @Override
    public void build(Ref<EntityStore> ref, UICommandBuilder cmd, UIEventBuilder events, Store<EntityStore> store) {
        try {
            // Use CommandListPage.ui as base - it has tabs, content area, and good structure
            cmd.append("Pages/CommandListPage.ui");

            // Set the page title
            cmd.set("#CommandName.Text", "HyFixes Dashboard");
            cmd.set("#CommandDescription.Text", "Admin monitoring and configuration panel");

            // Clear default content
            cmd.clear("#List");

            // Build tab navigation
            buildTabNavigation(cmd, events);

            // Build content based on current tab
            switch (currentTab) {
                case TAB_OVERVIEW -> buildOverviewTab(cmd, events);
                case TAB_SANITIZERS -> buildSanitizersTab(cmd, events);
                case TAB_CHUNKS -> buildChunksTab(cmd, events);
                case TAB_CONFIG -> buildConfigTab(cmd, events);
                default -> buildOverviewTab(cmd, events);
            }

            // Add refresh button binding using EventData
            events.addEventBinding(CustomUIEventBindingType.Activating, "#RefreshButton",
                    EventData.of("action", "refresh"));

        } catch (Exception e) {
            HyFixes.getInstance().getLogger().at(Level.WARNING).withCause(e)
                    .log("[Dashboard] Error building dashboard page");
            cmd.set("#CommandDescription.Text", "Error loading dashboard: " + e.getMessage());
        }
    }

    /**
     * Build the tab navigation buttons at the top of the dashboard.
     */
    private void buildTabNavigation(UICommandBuilder cmd, UIEventBuilder events) {
        // Add tab buttons as inline UI
        StringBuilder tabs = new StringBuilder();
        tabs.append("Group { direction: horizontal; spacing: 10; margin: [10, 10, 10, 10]; children: [");

        // Overview tab
        String overviewStyle = currentTab.equals(TAB_OVERVIEW) ? COLOR_GOLD : COLOR_GRAY;
        tabs.append("Button { id: TabOverview; style: { textColor: \"").append(overviewStyle).append("\"; }; ");
        tabs.append("text: \"Overview\"; }");

        // Sanitizers tab
        String sanitizersStyle = currentTab.equals(TAB_SANITIZERS) ? COLOR_GOLD : COLOR_GRAY;
        tabs.append(", Button { id: TabSanitizers; style: { textColor: \"").append(sanitizersStyle).append("\"; }; ");
        tabs.append("text: \"Sanitizers\"; }");

        // Chunks tab
        String chunksStyle = currentTab.equals(TAB_CHUNKS) ? COLOR_GOLD : COLOR_GRAY;
        tabs.append(", Button { id: TabChunks; style: { textColor: \"").append(chunksStyle).append("\"; }; ");
        tabs.append("text: \"Chunks\"; }");

        // Config tab
        String configStyle = currentTab.equals(TAB_CONFIG) ? COLOR_GOLD : COLOR_GRAY;
        tabs.append(", Button { id: TabConfig; style: { textColor: \"").append(configStyle).append("\"; }; ");
        tabs.append("text: \"Config\"; }");

        // Refresh button
        tabs.append(", Button { id: RefreshButton; style: { textColor: \"").append(COLOR_GREEN).append("\"; }; ");
        tabs.append("text: \"[Refresh]\"; }");

        tabs.append("] }");

        cmd.appendInline("#TabContainer", tabs.toString());

        // Bind tab button events using EventData
        events.addEventBinding(CustomUIEventBindingType.Activating, "#TabOverview",
                EventData.of("action", "selectTab").put("value", TAB_OVERVIEW));
        events.addEventBinding(CustomUIEventBindingType.Activating, "#TabSanitizers",
                EventData.of("action", "selectTab").put("value", TAB_SANITIZERS));
        events.addEventBinding(CustomUIEventBindingType.Activating, "#TabChunks",
                EventData.of("action", "selectTab").put("value", TAB_CHUNKS));
        events.addEventBinding(CustomUIEventBindingType.Activating, "#TabConfig",
                EventData.of("action", "selectTab").put("value", TAB_CONFIG));
    }

    /**
     * Build the Overview tab with key statistics and system health.
     */
    private void buildOverviewTab(UICommandBuilder cmd, UIEventBuilder events) {
        DashboardStats stats = DashboardStats.collect();

        // Add overview content
        addStatItem(cmd, 0, "Total Crashes Prevented", String.valueOf(stats.getTotalCrashesPrevented()), COLOR_GREEN);
        addStatItem(cmd, 1, "Active Sanitizers", stats.getActiveSanitizers() + " / " + stats.getTotalSanitizers(), COLOR_WHITE);
        addStatItem(cmd, 2, "System Health", stats.getHealthStatus(), stats.getHealthColor());
        addStatItem(cmd, 3, "Memory Usage", stats.getMemoryUsage(), stats.getMemoryColor());
        addStatItem(cmd, 4, "Server Uptime", stats.getUptime(), COLOR_WHITE);
        addStatItem(cmd, 5, "Protected Chunks", String.valueOf(stats.getProtectedChunks()), COLOR_WHITE);
        addStatItem(cmd, 6, "Chunk Unload Rate", stats.getChunkUnloadRate(), COLOR_WHITE);
        addStatItem(cmd, 7, "Plugin Version", stats.getPluginVersion(), COLOR_GRAY);
    }

    /**
     * Build the Sanitizers tab with a list of all sanitizers and their status.
     */
    private void buildSanitizersTab(UICommandBuilder cmd, UIEventBuilder events) {
        DashboardStats stats = DashboardStats.collect();

        int index = 0;
        for (DashboardStats.SanitizerInfo sanitizer : stats.getSanitizerInfos()) {
            String statusColor = sanitizer.isEnabled() ? COLOR_GREEN : COLOR_RED;
            String statusText = sanitizer.isEnabled() ? "ON" : "OFF";

            addSanitizerItem(cmd, index++, sanitizer.getName(), statusText, statusColor,
                    "Fixes: " + sanitizer.getFixCount());
        }
    }

    /**
     * Build the Chunks tab with chunk management statistics.
     */
    private void buildChunksTab(UICommandBuilder cmd, UIEventBuilder events) {
        DashboardStats stats = DashboardStats.collect();

        addStatItem(cmd, 0, "Protected Chunks", String.valueOf(stats.getProtectedChunks()), COLOR_GREEN);
        addStatItem(cmd, 1, "Chunk Protection", stats.isChunkProtectionEnabled() ? "ENABLED" : "DISABLED",
                stats.isChunkProtectionEnabled() ? COLOR_GREEN : COLOR_RED);
        addStatItem(cmd, 2, "Map-Aware Mode", stats.isMapAwareModeEnabled() ? "ENABLED" : "DISABLED",
                stats.isMapAwareModeEnabled() ? COLOR_GREEN : COLOR_GRAY);
        addStatItem(cmd, 3, "Unload Attempts", String.valueOf(stats.getChunkUnloadAttempts()), COLOR_WHITE);
        addStatItem(cmd, 4, "Unload Successes", String.valueOf(stats.getChunkUnloadSuccesses()), COLOR_GREEN);
        addStatItem(cmd, 5, "Success Rate", stats.getChunkUnloadRate(), COLOR_WHITE);
        addStatItem(cmd, 6, "Last Cleanup", stats.getLastCleanupTime(), COLOR_GRAY);
        addStatItem(cmd, 7, "Cleanup Interval", stats.getCleanupInterval() + " ticks", COLOR_GRAY);
    }

    /**
     * Build the Config tab with settings display and toggle buttons.
     */
    private void buildConfigTab(UICommandBuilder cmd, UIEventBuilder events) {
        ConfigManager configManager = ConfigManager.getInstance();
        HyFixesConfig config = configManager.getConfig();

        int index = 0;

        // Verbose Logging toggle
        addToggleItem(cmd, events, index++, "Verbose Logging", config.isVerbose(),
                "toggleConfig", "verbose");

        // Chunk Unload toggle
        addToggleItem(cmd, events, index++, "Chunk Unload System", config.isChunkUnloadEnabled(),
                "toggleConfig", "chunkUnload");

        // Chunk Protection toggle
        addToggleItem(cmd, events, index++, "Chunk Protection", config.isChunkProtectionEnabled(),
                "toggleConfig", "chunkProtection");

        // Map-Aware Mode toggle
        addToggleItem(cmd, events, index++, "Map-Aware Mode", config.isMapAwareModeEnabled(),
                "toggleConfig", "mapAwareMode");

        // Log Sanitizer Actions toggle
        addToggleItem(cmd, events, index++, "Log Sanitizer Actions", config.logSanitizerActions(),
                "toggleConfig", "logSanitizerActions");

        // Log Chunk Protection toggle
        addToggleItem(cmd, events, index++, "Log Chunk Protection", config.logChunkProtectionEvents(),
                "toggleConfig", "logChunkProtection");

        // Reload Config button
        addActionButton(cmd, events, index++, "Reload Configuration", "reloadConfig", "",
                "Reload config.json from disk");

        // Config file location
        addStatItem(cmd, index++, "Config Location", "plugins/hyfixes/config.json", COLOR_GRAY);
        addStatItem(cmd, index++, "Loaded From File", configManager.isLoadedFromFile() ? "YES" : "NO (defaults)",
                configManager.isLoadedFromFile() ? COLOR_GREEN : COLOR_YELLOW);
    }

    /**
     * Add a statistic item to the list.
     */
    private void addStatItem(UICommandBuilder cmd, int index, String label, String value, String color) {
        cmd.append("#List", "Pages/ParameterItem.ui");
        cmd.set("#List[" + index + "] #Label.Text", label);
        cmd.set("#List[" + index + "] #Value.Text", value);
        cmd.set("#List[" + index + "] #Value.Style.TextColor", color);
    }

    /**
     * Add a sanitizer item to the list with name, status, and fix count.
     */
    private void addSanitizerItem(UICommandBuilder cmd, int index, String name, String status,
                                  String statusColor, String detail) {
        cmd.append("#List", "Pages/SubcommandCard.ui");
        cmd.set("#List[" + index + "] #Name.Text", name);
        cmd.set("#List[" + index + "] #Description.Text", detail);
        cmd.set("#List[" + index + "] #Status.Text", status);
        cmd.set("#List[" + index + "] #Status.Style.TextColor", statusColor);
    }

    /**
     * Add a toggle item for configuration settings.
     */
    private void addToggleItem(UICommandBuilder cmd, UIEventBuilder events, int index,
                               String label, boolean enabled, String action, String configKey) {
        String status = enabled ? "[ON]" : "[OFF]";
        String color = enabled ? COLOR_GREEN : COLOR_RED;

        cmd.append("#List", "Pages/BasicTextButton.ui");
        cmd.set("#List[" + index + "] #ButtonText.Text", label + ": " + status);
        cmd.set("#List[" + index + "] #ButtonText.Style.TextColor", color);

        // Bind toggle event using EventData
        String buttonId = "#List[" + index + "] #Button";
        events.addEventBinding(CustomUIEventBindingType.Activating, buttonId,
                EventData.of("action", action).put("value", configKey));
    }

    /**
     * Add an action button.
     */
    private void addActionButton(UICommandBuilder cmd, UIEventBuilder events, int index,
                                 String label, String action, String value, String description) {
        cmd.append("#List", "Pages/SubcommandCard.ui");
        cmd.set("#List[" + index + "] #Name.Text", label);
        cmd.set("#List[" + index + "] #Description.Text", description);
        cmd.set("#List[" + index + "] #Status.Text", "[Click]");
        cmd.set("#List[" + index + "] #Status.Style.TextColor", COLOR_GOLD);

        // Bind action event using EventData
        String cardId = "#List[" + index + "]";
        events.addEventBinding(CustomUIEventBindingType.Activating, cardId,
                EventData.of("action", action).put("value", value));
    }

    @Override
    public void handleDataEvent(Ref<EntityStore> ref, Store<EntityStore> store, DashboardEventData data) {
        if (data == null || data.action == null) {
            return;
        }

        try {
            switch (data.action) {
                case "selectTab" -> {
                    currentTab = data.value != null ? data.value : TAB_OVERVIEW;
                    rebuild();
                }
                case "refresh" -> rebuild();
                case "toggleConfig" -> {
                    toggleConfigSetting(data.value);
                    rebuild();
                }
                case "reloadConfig" -> {
                    ConfigManager.getInstance().reload();
                    notifyPlayer(ref, store, "Configuration reloaded from disk!");
                    rebuild();
                }
            }
        } catch (Exception e) {
            HyFixes.getInstance().getLogger().at(Level.WARNING).withCause(e)
                    .log("[Dashboard] Error handling event: " + data);
        }
    }

    /**
     * Toggle a configuration setting and save to config.json.
     */
    private void toggleConfigSetting(String configKey) {
        ConfigManager configManager = ConfigManager.getInstance();
        HyFixesConfig config = configManager.getConfig();

        switch (configKey) {
            case "verbose" -> config.setVerbose(!config.isVerbose());
            case "chunkUnload" -> config.setChunkUnloadEnabled(!config.isChunkUnloadEnabled());
            case "chunkProtection" -> config.setChunkProtectionEnabled(!config.isChunkProtectionEnabled());
            case "mapAwareMode" -> config.setMapAwareModeEnabled(!config.isMapAwareModeEnabled());
            case "logSanitizerActions" -> config.setLogSanitizerActions(!config.logSanitizerActions());
            case "logChunkProtection" -> config.setLogChunkProtectionEvents(!config.logChunkProtectionEvents());
        }

        // Save configuration
        configManager.saveConfig();
    }

    /**
     * Send a notification message to the player.
     */
    private void notifyPlayer(Ref<EntityStore> ref, Store<EntityStore> store, String message) {
        Player player = store.getComponent(ref, Player.getComponentType());
        if (player != null) {
            ChatColorUtil.sendMessage(player, "&a[HyFixes] " + message);
        }
    }
}
