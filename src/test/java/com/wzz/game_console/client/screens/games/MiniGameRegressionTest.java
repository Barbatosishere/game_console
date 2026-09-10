package com.wzz.game_console.client.screens.games;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class MiniGameRegressionTest {
    @Test
    void mouseTunnelAppliesAllScheduledDifficultyIncreasesBeforeVictory() {
        MouseTunnelProgress.Snapshot progress = MouseTunnelProgress.calculate(10_000, 10_000);

        assertEquals(100, progress.score());
        assertEquals(3, progress.difficultyIncreases());
        assertTrue(progress.won());
    }

    @Test
    void puzzleRestartClearsFrozenCompletionTime() {
        PuzzleElapsedTimer timer = new PuzzleElapsedTimer();
        timer.restart(1_000);
        timer.complete(6_900);
        assertEquals(5, timer.elapsedSeconds(20_000));

        timer.restart(30_000);

        assertEquals(2, timer.elapsedSeconds(32_100));
    }

    @Test
    void moleHitboxRejectsUndergroundAndHiddenPixels() {
        assertFalse(MoleHitbox.containsVisiblePart(10, 20, 48, 32,
                32, true, 30, 52));
        assertFalse(MoleHitbox.containsVisiblePart(10, 20, 48, 32,
                24, true, 30, 50));
        assertTrue(MoleHitbox.containsVisiblePart(10, 20, 48, 32,
                24, true, 30, 61));
        assertFalse(MoleHitbox.containsVisiblePart(10, 20, 48, 32,
                0, false, 30, 40));
    }
}
