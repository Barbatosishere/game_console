package com.wzz.game_console.util;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/** Personal records indexed by player, stable game ID and mode. No network authority is implied. */
public final class GameScoreStore {
    private static final Logger LOGGER = LoggerFactory.getLogger("GameConsole");
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static final long MAX_BYTES = 1024 * 1024;
    private final Path file;
    private final Object dataLock = new Object();
    private final Object saveLock = new Object();
    private final Map<UUID, Map<String, Map<String, Integer>>> players = new HashMap<>();
    private long revision;
    private long savedRevision;

    public GameScoreStore(Path file) {
        this.file = file;
        load();
    }

    public int best(UUID player, String game, String mode) {
        if (!valid(player, game, mode)) return 0;
        synchronized (dataLock) {
            return players.getOrDefault(player, Map.of()).getOrDefault(game, Map.of()).getOrDefault(mode, 0);
        }
    }

    /** Returns true only for a new record. Lower or invalid values never replace a record. */
    public boolean record(UUID player, String game, String mode, int score) {
        if (!valid(player, game, mode) || score <= 0) return false;
        synchronized (dataLock) {
            Map<String, Integer> modes = players.computeIfAbsent(player, unused -> new HashMap<>())
                    .computeIfAbsent(game, unused -> new HashMap<>());
            if (score <= modes.getOrDefault(mode, 0)) return false;
            modes.put(mode, score);
            revision++;
            return true;
        }
    }

    public boolean isDirty() { synchronized (dataLock) { return revision != savedRevision; } }

    /** Serializes a snapshot outside the data lock, then replaces the file atomically. */
    public boolean save() {
        if (file == null) return false;
        synchronized (saveLock) {
            JsonObject root = new JsonObject();
            JsonObject jsonPlayers = new JsonObject();
            long snapshotRevision;
            synchronized (dataLock) {
                if (revision == savedRevision) return true;
                snapshotRevision = revision;
                for (var player : players.entrySet()) {
                    JsonObject games = new JsonObject();
                    for (var game : player.getValue().entrySet()) {
                        JsonObject modes = new JsonObject();
                        game.getValue().forEach(modes::addProperty);
                        games.add(game.getKey(), modes);
                    }
                    jsonPlayers.add(player.getKey().toString(), games);
                }
            }
            root.addProperty("version", 1);
            root.add("players", jsonPlayers);
            Path temp = null;
            try {
                byte[] content = GSON.toJson(root).getBytes(StandardCharsets.UTF_8);
                if (content.length > MAX_BYTES) throw new IOException("Record file exceeds size limit");
                Path target = file.toAbsolutePath();
                Files.createDirectories(target.getParent());
                temp = Files.createTempFile(target.getParent(), "game-scores-", ".tmp");
                Files.write(temp, content);
                try {
                    Files.move(temp, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
                } catch (AtomicMoveNotSupportedException unsupported) {
                    Files.move(temp, target, StandardCopyOption.REPLACE_EXISTING);
                }
                synchronized (dataLock) { savedRevision = snapshotRevision; }
                return true;
            } catch (IOException | RuntimeException failure) {
                LOGGER.warn("Could not save personal game records: {}", failure.toString());
                return false;
            } finally {
                if (temp != null) try { Files.deleteIfExists(temp); } catch (IOException ignored) {}
            }
        }
    }

    private void load() {
        if (file == null || !Files.isRegularFile(file)) return;
        try {
            if (Files.size(file) > MAX_BYTES) throw new IOException("Record file exceeds size limit");
            JsonObject root = JsonParser.parseString(Files.readString(file, StandardCharsets.UTF_8)).getAsJsonObject();
            if (!root.has("version") || root.get("version").getAsInt() != 1) return;
            JsonObject jsonPlayers = root.getAsJsonObject("players");
            if (jsonPlayers == null) return;
            for (var player : jsonPlayers.entrySet()) {
                UUID uuid;
                try { uuid = UUID.fromString(player.getKey()); } catch (IllegalArgumentException invalid) { continue; }
                if (!player.getValue().isJsonObject()) continue;
                for (var game : player.getValue().getAsJsonObject().entrySet()) {
                    if (GameCatalog.find(game.getKey()) == null || !game.getValue().isJsonObject()) continue;
                    for (var mode : game.getValue().getAsJsonObject().entrySet()) {
                        int score = readScore(mode.getValue());
                        if (valid(uuid, game.getKey(), mode.getKey()) && score > 0) {
                            players.computeIfAbsent(uuid, unused -> new HashMap<>())
                                    .computeIfAbsent(game.getKey(), unused -> new HashMap<>()).put(mode.getKey(), score);
                        }
                    }
                }
            }
        } catch (IOException | RuntimeException failure) {
            LOGGER.warn("Could not load personal game records: {}", failure.toString());
        }
    }

    private static int readScore(JsonElement value) {
        try {
            return value.isJsonPrimitive() && value.getAsJsonPrimitive().isNumber()
                    ? value.getAsBigDecimal().intValueExact() : 0;
        } catch (ArithmeticException | NumberFormatException invalid) { return 0; }
    }

    private static boolean valid(UUID player, String game, String mode) {
        return player != null && GameCatalog.find(game) != null && mode != null
                && mode.matches("[a-z0-9_-]{1,32}");
    }
}
