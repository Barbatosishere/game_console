package com.wzz.game_console.client.screens.games;

final class PuzzleElapsedTimer {
    private long startMillis;
    private long completedSeconds = -1;

    void restart(long nowMillis) {
        startMillis = nowMillis;
        completedSeconds = -1;
    }

    void complete(long nowMillis) {
        if (completedSeconds < 0) {
            completedSeconds = Math.max(0, nowMillis - startMillis) / 1_000;
        }
    }

    void offsetStart(long pausedMillis) {
        startMillis += Math.max(0, pausedMillis);
    }

    long elapsedSeconds(long nowMillis) {
        return completedSeconds >= 0
                ? completedSeconds
                : Math.max(0, nowMillis - startMillis) / 1_000;
    }
}
