package com.wzz.game_console.client;

import com.wzz.game_console.util.ExternalFileManager;
import com.wzz.game_console.util.GameScoreStore;
import net.minecraft.client.Minecraft;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.api.distmarker.OnlyIn;

import java.nio.file.Path;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;

/** Client personal records; disk writes run on one background thread and are coalesced. */
@OnlyIn(Dist.CLIENT)
public final class GameScores {
    private static final ExecutorService WRITER = Executors.newSingleThreadExecutor(task -> {
        Thread thread = new Thread(task, "GameConsole-RecordWriter");
        thread.setDaemon(true);
        return thread;
    });
    private static final AtomicBoolean SAVE_QUEUED = new AtomicBoolean();
    private static volatile GameScoreStore store;
    static {
        Runtime.getRuntime().addShutdownHook(new Thread(GameScores::flush, "GameConsole-RecordFlush"));
    }

    private GameScores() {}
    public static int best(String game) { return best(game, "default"); }
    public static int best(String game, String mode) { return getStore().best(playerId(), game, mode); }
    public static void record(String game, int score) { record(game, "default", score); }
    public static void record(String game, String mode, int score) {
        if (getStore().record(playerId(), game, mode, score)) requestSave();
    }

    private static UUID playerId() {
        var player = Minecraft.getInstance().player;
        return player == null ? null : player.getUUID();
    }

    private static GameScoreStore getStore() {
        if (store == null) synchronized (GameScores.class) {
            if (store == null) {
                Path dataDir = ExternalFileManager.getDataDir();
                store = new GameScoreStore(dataDir == null ? null : dataDir.resolve("game_scores.json"));
            }
        }
        return store;
    }

    private static void requestSave() {
        if (!SAVE_QUEUED.compareAndSet(false, true)) return;
        WRITER.execute(() -> {
            boolean failed = false;
            try {
                while (store.isDirty()) if (!store.save()) { failed = true; break; }
            } finally {
                SAVE_QUEUED.set(false);
                if (!failed && store.isDirty()) requestSave();
            }
        });
    }

    public static void flush() {
        GameScoreStore current = store;
        if (current != null && current.isDirty()) current.save();
    }
}
