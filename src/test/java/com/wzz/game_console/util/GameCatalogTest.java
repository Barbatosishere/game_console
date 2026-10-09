package com.wzz.game_console.util;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.junit.jupiter.api.Test;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;

class GameCatalogTest {
    @Test void selectorAndLobbyShareTheExistingGameIdentitiesAndCapabilities() {
        assertEquals(32, GameCatalog.all().size());
        assertEquals(32, GameCatalog.all().stream().map(GameCatalog.Entry::id).distinct().count());
        assertEquals(List.of("gomoku","go","tictactoe","chess","icefire","colorchase","landlord","breakout","maze","snake","wchess"),
                GameCatalog.multiplayer().stream().map(GameCatalog.Entry::id).toList());
        for (var game : GameCatalog.multiplayer()) assertSame(GameCatalog.find(game.id()), game);
        assertFalse(GameCatalog.find("snake").supportsLAN());
        assertFalse(GameCatalog.find("icefire").supportsAI());
        assertTrue(GameCatalog.find("wchess").supportsLAN());
        assertNull(GameCatalog.find(null));
        assertThrows(UnsupportedOperationException.class, () -> GameCatalog.all().clear());
    }

    @Test void bothLanguagesCoverEveryCatalogEntryAndHaveMatchingFormatArguments() throws Exception {
        JsonObject chinese = language("zh_cn"), english = language("en_us");
        assertEquals(chinese.keySet(), english.keySet());
        for (var entry : GameCatalog.all()) {
            assertTrue(chinese.has(entry.nameKey()), entry.id());
            assertTrue(english.has(entry.descriptionKey()), entry.id());
        }
        for (var category : GameCatalog.Category.values()) assertTrue(english.has(category.translationKey()));
        for (String key : chinese.keySet()) {
            String zh = chinese.get(key).getAsString(), en = english.get(key).getAsString();
            assertFalse(zh.isBlank(), key);
            assertFalse(en.isBlank(), key);
            assertEquals(zh.chars().filter(c -> c == '%').count(), en.chars().filter(c -> c == '%').count(), key);
        }
    }

    private JsonObject language(String locale) throws Exception {
        try (var stream = getClass().getResourceAsStream("/assets/game_console/lang/" + locale + ".json")) {
            assertNotNull(stream);
            return JsonParser.parseReader(new InputStreamReader(stream, StandardCharsets.UTF_8)).getAsJsonObject();
        }
    }
}
