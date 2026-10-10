package com.wzz.game_console.util;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.UUID;
import java.util.concurrent.Executors;
import static org.junit.jupiter.api.Assertions.*;

class GameScoreStoreTest {
    @TempDir Path directory;
    private final UUID alice = UUID.fromString("12345678-1234-1234-1234-123456789abc");
    private final UUID bob = UUID.fromString("87654321-1234-1234-1234-123456789abc");

    @Test void recordsSurviveReopeningAndKeepPlayerGameAndModeSeparate() {
        Path file = directory.resolve("records.json");
        GameScoreStore store = new GameScoreStore(file);
        store.record(alice, "jump", "default", 90);
        store.record(alice, "jump", "hard", 30);
        store.record(alice, "mousetunnel", "default", 100);
        store.record(bob, "jump", "default", 15);
        assertTrue(store.save());
        GameScoreStore reopened = new GameScoreStore(file);
        assertEquals(90, reopened.best(alice, "jump", "default"));
        assertEquals(30, reopened.best(alice, "jump", "hard"));
        assertEquals(100, reopened.best(alice, "mousetunnel", "default"));
        assertEquals(15, reopened.best(bob, "jump", "default"));
        assertFalse(reopened.isDirty());
    }

    @Test void onlyAnImprovementChangesTheRecordOrWritesTheFile() throws Exception {
        Path file = directory.resolve("records.json");
        GameScoreStore store = new GameScoreStore(file);
        assertTrue(store.record(alice, "jump", "default", 50));
        assertTrue(store.save());
        var timestamp = Files.getLastModifiedTime(file);
        byte[] content = Files.readAllBytes(file);
        assertFalse(store.record(alice, "jump", "default", 49));
        assertFalse(store.record(alice, "jump", "default", 50));
        assertTrue(store.save());
        assertEquals(timestamp, Files.getLastModifiedTime(file));
        assertArrayEquals(content, Files.readAllBytes(file));
        assertEquals(50, store.best(alice, "jump", "default"));
    }

    @Test void invalidIdentitiesAndScoresAreRejected() {
        GameScoreStore store = new GameScoreStore(directory.resolve("records.json"));
        assertFalse(store.record(null, "jump", "default", 10));
        assertFalse(store.record(alice, "unknown", "default", 10));
        assertFalse(store.record(alice, "jump", "../hard", 10));
        assertFalse(store.record(alice, "jump", "default", -1));
        assertFalse(store.record(alice, "jump", "default", 0));
        assertFalse(store.isDirty());
        assertEquals(0, store.best(null, "jump", "default"));
    }

    @Test void malformedFileFallsBackWithoutWritingDuringLoad() throws Exception {
        Path file = directory.resolve("records.json");
        Files.writeString(file, "{broken json");
        GameScoreStore store = new GameScoreStore(file);
        assertEquals(0, store.best(alice, "jump", "default"));
        assertFalse(store.isDirty());
        assertEquals("{broken json", Files.readString(file));
    }

    @Test void invalidEntriesDoNotDiscardOtherPlayersOrValidRecords() throws Exception {
        Path file = directory.resolve("records.json");
        Files.writeString(file, """
                {"version":1,"players":{
                  "12345678-1234-1234-1234-123456789abc":{
                    "jump":{"default":12,"hard":1.5,"overflow":2147483648,"negative":-3,"text":"99","../mode":20},
                    "unknown":{"default":999}, "mousetunnel":null},
                  "not-a-uuid":{"jump":{"default":999}},
                  "87654321-1234-1234-1234-123456789abc":{"jump":{"default":20}}
                }}
                """);
        GameScoreStore store = new GameScoreStore(file);
        assertEquals(12, store.best(alice, "jump", "default"));
        assertEquals(20, store.best(bob, "jump", "default"));
        for (String mode : new String[]{"hard", "overflow", "negative", "text"}) assertEquals(0, store.best(alice, "jump", mode));
        assertEquals(0, store.best(alice, "unknown", "default"));
    }

    @Test void oversizedFileIsIgnored() throws Exception {
        Path file = directory.resolve("records.json");
        Files.writeString(file, " ".repeat(1024 * 1024 + 1));
        assertEquals(0, new GameScoreStore(file).best(alice, "jump", "default"));
        assertEquals(1024 * 1024 + 1, Files.size(file));
    }

    @Test void failedSaveKeepsTheRecordInMemoryAndDirtyForRetry() throws Exception {
        Path blocker = directory.resolve("not-a-directory");
        Files.writeString(blocker, "keep");
        GameScoreStore store = new GameScoreStore(blocker.resolve("records.json"));
        store.record(alice, "jump", "default", 42);
        assertFalse(store.save());
        assertTrue(store.isDirty());
        assertEquals(42, store.best(alice, "jump", "default"));
        assertEquals("keep", Files.readString(blocker));
    }

    @Test void concurrentUpdatesAndSavesCannotLoseTheHighestRecord() throws Exception {
        Path file = directory.resolve("records.json");
        GameScoreStore store = new GameScoreStore(file);
        try (var workers = Executors.newFixedThreadPool(6)) {
            var tasks = new ArrayList<java.util.concurrent.Future<?>>();
            for (int worker = 0; worker < 5; worker++) {
                int offset = worker * 100;
                tasks.add(workers.submit(() -> {
                    for (int i = 1; i <= 100; i++) store.record(alice, "jump", "default", offset + i);
                }));
            }
            tasks.add(workers.submit(() -> { for (int i = 0; i < 30; i++) assertTrue(store.save()); }));
            for (var task : tasks) task.get();
        }
        assertTrue(store.save());
        assertEquals(500, new GameScoreStore(file).best(alice, "jump", "default"));
        try (var files = Files.list(directory)) { assertEquals(1, files.count()); }
    }
}
