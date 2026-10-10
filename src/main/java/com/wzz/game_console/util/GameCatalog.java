package com.wzz.game_console.util;

import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

/** Shared game identities and capabilities; safe to use without loading client screens. */
public final class GameCatalog {
    public enum Category {
        ALL(0xFF888888), BOARD(0xFFFF8800), ACTION(0xFFFF2244),
        PUZZLE(0xFF2288FF), CASUAL(0xFF44CC44);

        private final int color;
        Category(int color) { this.color = color; }
        public int color() { return color; }
        public String translationKey() { return "gui.game_console.category." + name().toLowerCase(java.util.Locale.ROOT); }
    }

    public record Entry(String id, String icon, Category category, boolean supportsAI,
                        boolean supportsLocal, boolean supportsLAN, int multiplayerOrder) {
        public String nameKey() { return "game.game_console." + id + ".name"; }
        public String descriptionKey() { return "game.game_console." + id + ".description"; }
    }

    private static final List<Entry> GAMES = List.of(
            multi("gomoku", "⚫", Category.BOARD, true, true, true, 0),
            multi("go", "⚪", Category.BOARD, true, true, true, 1),
            multi("tictactoe", "✖", Category.BOARD, true, true, true, 2),
            multi("chess", "♚", Category.BOARD, true, true, true, 3),
            multi("wchess", "♟", Category.BOARD, true, true, true, 10),
            multi("landlord", "🃏", Category.BOARD, true, true, true, 6),
            single("dice", "🎲", Category.BOARD),
            multi("snake", "🐍", Category.ACTION, false, true, false, 9),
            single("flappybird", "🐦", Category.ACTION),
            multi("breakout", "🧱", Category.ACTION, false, true, false, 7),
            single("platformer", "🏃", Category.ACTION),
            multi("icefire", "❄", Category.ACTION, false, true, true, 4),
            single("blackhole", "🕳", Category.ACTION),
            single("fruitninja", "🍉", Category.ACTION),
            multi("colorchase", "🎨", Category.ACTION, true, true, true, 5),
            single("jump", "⬆", Category.ACTION),
            single("minesweeper", "💣", Category.PUZZLE),
            single("tetris", "🟦", Category.PUZZLE),
            single("pipepuzzle", "🔧", Category.PUZZLE),
            single("sokoban", "📦", Category.PUZZLE),
            single("klotski", "🏯", Category.PUZZLE),
            single("puzzle", "🧩", Category.PUZZLE),
            single("sudoku", "🔢", Category.PUZZLE),
            multi("maze", "🌀", Category.CASUAL, false, true, false, 8),
            single("memorycard", "🎴", Category.CASUAL),
            single("match3", "💎", Category.CASUAL),
            single("towerdefense", "🏰", Category.CASUAL),
            single("memory", "🧠", Category.CASUAL),
            single("mousetunnel", "🖱", Category.CASUAL),
            single("minecraft2d", "⛏", Category.CASUAL),
            single("pianotiles", "🎵", Category.CASUAL),
            single("whackamole", "🔨", Category.CASUAL));
    private static final Map<String, Entry> BY_ID = GAMES.stream().collect(
            Collectors.toUnmodifiableMap(Entry::id, Function.identity()));
    private static final List<Entry> MULTIPLAYER = GAMES.stream()
            .filter(game -> game.multiplayerOrder() >= 0)
            .sorted(Comparator.comparingInt(Entry::multiplayerOrder)).toList();

    private GameCatalog() {}
    public static List<Entry> all() { return GAMES; }
    public static List<Entry> multiplayer() { return MULTIPLAYER; }
    public static Entry find(String id) { return id == null ? null : BY_ID.get(id); }
    private static Entry single(String id, String icon, Category category) {
        return multi(id, icon, category, false, false, false, -1);
    }
    private static Entry multi(String id, String icon, Category category, boolean ai, boolean local, boolean lan, int order) {
        return new Entry(id, icon, category, ai, local, lan, order);
    }
}
