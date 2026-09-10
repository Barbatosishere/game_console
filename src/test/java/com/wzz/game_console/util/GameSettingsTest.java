package com.wzz.game_console.util;

import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;

class GameSettingsTest {
    @Test
    void iceFireDifficultyPreservesEasyAndClampsToSupportedModes() throws ReflectiveOperationException {
        Field settings = GameSettings.class.getDeclaredField("settings");
        Field loaded = GameSettings.class.getDeclaredField("loaded");
        settings.setAccessible(true);
        loaded.setAccessible(true);
        Object previousSettings = settings.get(null);
        boolean previousLoaded = loaded.getBoolean(null);
        try {
            loaded.setBoolean(null, true);
            int[] values = {-10, 0, 1, 2, 3, 10, Integer.MAX_VALUE};
            int[] expected = {0, 0, 1, 2, 2, 2, 2};
            for (int i = 0; i < values.length; i++) {
                settings.set(null, Map.of("icefire", Map.of("difficulty", values[i])));
                assertEquals(expected[i], GameSettings.getInt("icefire", "difficulty", 1));
            }
            settings.set(null, Map.of());
            assertEquals(0, GameSettings.getInt("icefire", "difficulty", 0));
        } finally {
            settings.set(null, previousSettings);
            loaded.setBoolean(null, previousLoaded);
        }
    }

    @Test
    void goSearchTimeUsesTheSameRangeAsTheSettingsUi() throws ReflectiveOperationException {
        Field settings = GameSettings.class.getDeclaredField("settings");
        Field loaded = GameSettings.class.getDeclaredField("loaded");
        settings.setAccessible(true);
        loaded.setAccessible(true);
        Object previousSettings = settings.get(null);
        boolean previousLoaded = loaded.getBoolean(null);
        try {
            loaded.setBoolean(null, true);
            int[] values = {-1, 100, 10_000, 60_000, 60_001, Integer.MAX_VALUE};
            int[] expected = {GameSettings.GO_SEARCH_TIME_MIN, 100, 10_000, 60_000,
                    GameSettings.GO_SEARCH_TIME_MAX, GameSettings.GO_SEARCH_TIME_MAX};
            for (int i = 0; i < values.length; i++) {
                settings.set(null, Map.of("go", Map.of("searchTime", values[i])));
                assertEquals(expected[i], GameSettings.getInt("go", "searchTime", 3_000));
            }
        } finally {
            settings.set(null, previousSettings);
            loaded.setBoolean(null, previousLoaded);
        }
    }
}
