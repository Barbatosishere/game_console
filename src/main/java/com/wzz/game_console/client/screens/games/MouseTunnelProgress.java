package com.wzz.game_console.client.screens.games;

final class MouseTunnelProgress {
    static final int WIN_SCORE = 100;
    static final long SCORE_INTERVAL_MILLIS = 100;
    static final long DIFFICULTY_INTERVAL_MILLIS = 3_000;

    record Snapshot(int score, int difficultyIncreases, boolean won) {}

    private MouseTunnelProgress() {}

    static Snapshot calculate(long survivalMillis, long sinceLastDifficultyIncrease) {
        long elapsed = Math.max(0, survivalMillis);
        int score = (int) Math.min(Integer.MAX_VALUE, elapsed / SCORE_INTERVAL_MILLIS);
        long difficultyMillis = Math.min(Math.max(0, sinceLastDifficultyIncrease),
                WIN_SCORE * SCORE_INTERVAL_MILLIS);
        int increases = (int) (difficultyMillis / DIFFICULTY_INTERVAL_MILLIS);
        return new Snapshot(score, increases, score >= WIN_SCORE);
    }
}
