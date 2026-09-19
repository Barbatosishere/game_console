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
    void match3FindsVerticalArmOfTShape() {
        int[][] grid = new int[8][8];
        for (int x = 0; x < 8; x++) {
            for (int y = 0; y < 8; y++) {
                grid[x][y] = (x + 3 * y) % 7;
            }
        }
        // T：横杠 (0,0)(1,0)(2,0)，竖臂从横杠中点向下 (1,1)(1,2)
        grid[0][0] = grid[1][0] = grid[2][0] = 9;
        grid[1][1] = grid[1][2] = 9;
        // L：横杠 (5,5)(6,5)(7,5)，竖臂 (7,6)(7,7)
        grid[5][5] = grid[6][5] = grid[7][5] = 8;
        grid[7][6] = grid[7][7] = 8;

        boolean[][] hit = new boolean[8][8];
        for (int[] cell : Match3Matches.findAll(grid)) {
            hit[cell[0]][cell[1]] = true;
        }
        assertTrue(hit[0][0] && hit[1][0] && hit[2][0], "T 横杠");
        assertTrue(hit[1][1] && hit[1][2], "T 竖臂不得因交叉点已计入而漏消");
        assertTrue(hit[5][5] && hit[6][5] && hit[7][5], "L 横杠");
        assertTrue(hit[7][6] && hit[7][7], "L 竖臂不得漏消");
        assertFalse(hit[0][1], "T 旁无关格");
        assertFalse(hit[6][6], "L 旁无关格");
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
