package com.hyfixes.data;

import com.hyfixes.HyFixes;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.sql.*;
import java.util.*;
import java.util.logging.Level;

/**
 * SQLite database for storing player bed chunk mappings.
 * Supports multiple worlds.
 *
 * Schema:
 *   bed_chunks(player_uuid TEXT, world_name TEXT, chunk_index INTEGER, updated_at INTEGER)
 *   PRIMARY KEY(player_uuid, world_name)
 */
public class BedChunkDatabase {

    private static final String DB_DIR = "mods/hyfixes/data";
    private static final String DB_FILE = "beds.db";
    private static final Path DB_PATH = Paths.get(DB_DIR, DB_FILE);

    private final HyFixes plugin;
    private Connection connection;

    public BedChunkDatabase(HyFixes plugin) {
        this.plugin = plugin;
    }

    /**
     * Initialize the database connection and create tables if needed.
     */
    public boolean initialize() {
        try {
            // Ensure directory exists
            Files.createDirectories(Paths.get(DB_DIR));

            // Load SQLite JDBC driver
            Class.forName("org.sqlite.JDBC");

            // Connect to database (creates file if not exists)
            connection = DriverManager.getConnection("jdbc:sqlite:" + DB_PATH);

            // Create table if not exists
            try (Statement stmt = connection.createStatement()) {
                stmt.execute("""
                    CREATE TABLE IF NOT EXISTS bed_chunks (
                        player_uuid TEXT NOT NULL,
                        world_name TEXT NOT NULL,
                        chunk_index INTEGER NOT NULL,
                        updated_at INTEGER NOT NULL,
                        PRIMARY KEY (player_uuid, world_name)
                    )
                """);

                // Create index for faster lookups by world
                stmt.execute("""
                    CREATE INDEX IF NOT EXISTS idx_bed_chunks_world
                    ON bed_chunks(world_name)
                """);
            }

            plugin.getLogger().at(Level.INFO).log(
                "[BedChunkDatabase] Initialized SQLite database at %s", DB_PATH
            );
            return true;

        } catch (Exception e) {
            plugin.getLogger().at(Level.SEVERE).log(
                "[BedChunkDatabase] Failed to initialize database: %s", e.getMessage()
            );
            e.printStackTrace();
            return false;
        }
    }

    /**
     * Store or update a player's bed chunk for a specific world.
     */
    public void setBedChunk(UUID playerUuid, String worldName, long chunkIndex) {
        if (connection == null) return;

        String sql = """
            INSERT INTO bed_chunks (player_uuid, world_name, chunk_index, updated_at)
            VALUES (?, ?, ?, ?)
            ON CONFLICT(player_uuid, world_name)
            DO UPDATE SET chunk_index = excluded.chunk_index, updated_at = excluded.updated_at
        """;

        try (PreparedStatement pstmt = connection.prepareStatement(sql)) {
            pstmt.setString(1, playerUuid.toString());
            pstmt.setString(2, worldName);
            pstmt.setLong(3, chunkIndex);
            pstmt.setLong(4, System.currentTimeMillis());
            pstmt.executeUpdate();
        } catch (SQLException e) {
            plugin.getLogger().at(Level.WARNING).log(
                "[BedChunkDatabase] Failed to set bed chunk: %s", e.getMessage()
            );
        }
    }

    /**
     * Remove a player's bed chunk for a specific world.
     */
    public void removeBedChunk(UUID playerUuid, String worldName) {
        if (connection == null) return;

        String sql = "DELETE FROM bed_chunks WHERE player_uuid = ? AND world_name = ?";

        try (PreparedStatement pstmt = connection.prepareStatement(sql)) {
            pstmt.setString(1, playerUuid.toString());
            pstmt.setString(2, worldName);
            pstmt.executeUpdate();
        } catch (SQLException e) {
            plugin.getLogger().at(Level.WARNING).log(
                "[BedChunkDatabase] Failed to remove bed chunk: %s", e.getMessage()
            );
        }
    }

    /**
     * Get a player's bed chunk for a specific world.
     * Returns null if not found.
     */
    public Long getBedChunk(UUID playerUuid, String worldName) {
        if (connection == null) return null;

        String sql = "SELECT chunk_index FROM bed_chunks WHERE player_uuid = ? AND world_name = ?";

        try (PreparedStatement pstmt = connection.prepareStatement(sql)) {
            pstmt.setString(1, playerUuid.toString());
            pstmt.setString(2, worldName);

            try (ResultSet rs = pstmt.executeQuery()) {
                if (rs.next()) {
                    return rs.getLong("chunk_index");
                }
            }
        } catch (SQLException e) {
            plugin.getLogger().at(Level.WARNING).log(
                "[BedChunkDatabase] Failed to get bed chunk: %s", e.getMessage()
            );
        }
        return null;
    }

    /**
     * Get all bed chunks for a player across all worlds.
     * Returns Map<worldName, chunkIndex>
     */
    public Map<String, Long> getAllBedChunks(UUID playerUuid) {
        Map<String, Long> result = new HashMap<>();
        if (connection == null) return result;

        String sql = "SELECT world_name, chunk_index FROM bed_chunks WHERE player_uuid = ?";

        try (PreparedStatement pstmt = connection.prepareStatement(sql)) {
            pstmt.setString(1, playerUuid.toString());

            try (ResultSet rs = pstmt.executeQuery()) {
                while (rs.next()) {
                    result.put(rs.getString("world_name"), rs.getLong("chunk_index"));
                }
            }
        } catch (SQLException e) {
            plugin.getLogger().at(Level.WARNING).log(
                "[BedChunkDatabase] Failed to get all bed chunks: %s", e.getMessage()
            );
        }
        return result;
    }

    /**
     * Get all players who have beds in a specific world.
     * Returns Map<playerUuid, chunkIndex>
     */
    public Map<UUID, Long> getPlayersByWorld(String worldName) {
        Map<UUID, Long> result = new HashMap<>();
        if (connection == null) return result;

        String sql = "SELECT player_uuid, chunk_index FROM bed_chunks WHERE world_name = ?";

        try (PreparedStatement pstmt = connection.prepareStatement(sql)) {
            pstmt.setString(1, worldName);

            try (ResultSet rs = pstmt.executeQuery()) {
                while (rs.next()) {
                    try {
                        UUID uuid = UUID.fromString(rs.getString("player_uuid"));
                        result.put(uuid, rs.getLong("chunk_index"));
                    } catch (IllegalArgumentException ignored) {
                        // Invalid UUID in database, skip
                    }
                }
            }
        } catch (SQLException e) {
            plugin.getLogger().at(Level.WARNING).log(
                "[BedChunkDatabase] Failed to get players by world: %s", e.getMessage()
            );
        }
        return result;
    }

    /**
     * Get all stored bed chunks.
     * Returns list of BedChunkEntry objects.
     */
    public List<BedChunkEntry> getAllEntries() {
        List<BedChunkEntry> result = new ArrayList<>();
        if (connection == null) return result;

        String sql = "SELECT player_uuid, world_name, chunk_index FROM bed_chunks";

        try (Statement stmt = connection.createStatement();
             ResultSet rs = stmt.executeQuery(sql)) {
            while (rs.next()) {
                try {
                    UUID uuid = UUID.fromString(rs.getString("player_uuid"));
                    String worldName = rs.getString("world_name");
                    long chunkIndex = rs.getLong("chunk_index");
                    result.add(new BedChunkEntry(uuid, worldName, chunkIndex));
                } catch (IllegalArgumentException ignored) {
                    // Invalid UUID, skip
                }
            }
        } catch (SQLException e) {
            plugin.getLogger().at(Level.WARNING).log(
                "[BedChunkDatabase] Failed to get all entries: %s", e.getMessage()
            );
        }
        return result;
    }

    /**
     * Get count of stored bed chunks.
     */
    public int getCount() {
        if (connection == null) return 0;

        try (Statement stmt = connection.createStatement();
             ResultSet rs = stmt.executeQuery("SELECT COUNT(*) FROM bed_chunks")) {
            if (rs.next()) {
                return rs.getInt(1);
            }
        } catch (SQLException e) {
            plugin.getLogger().at(Level.WARNING).log(
                "[BedChunkDatabase] Failed to get count: %s", e.getMessage()
            );
        }
        return 0;
    }

    /**
     * Close the database connection.
     */
    public void close() {
        if (connection != null) {
            try {
                connection.close();
                plugin.getLogger().at(Level.INFO).log("[BedChunkDatabase] Database connection closed");
            } catch (SQLException e) {
                plugin.getLogger().at(Level.WARNING).log(
                    "[BedChunkDatabase] Error closing database: %s", e.getMessage()
                );
            }
        }
    }

    /**
     * Data class for bed chunk entries.
     */
    public record BedChunkEntry(UUID playerUuid, String worldName, long chunkIndex) {}
}
