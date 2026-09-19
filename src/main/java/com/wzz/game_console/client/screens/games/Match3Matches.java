package com.wzz.game_console.client.screens.games;

import java.util.ArrayList;
import java.util.List;

/**
 * 消消乐三连判定。抽成无 Minecraft 依赖的纯函数，避免 T/L 交叉漏消。
 */
final class Match3Matches {
    private Match3Matches() {}

    static List<int[]> findAll(int[][] grid) {
        int n = grid.length;
        List<int[]> matches = new ArrayList<>();
        boolean[][] checked = new boolean[n][n];

        for (int y = 0; y < n; y++) {
            for (int x = 0; x < n - 2; x++) {
                if (!checked[x][y] && grid[x][y] == grid[x + 1][y]
                        && grid[x][y] == grid[x + 2][y] && grid[x][y] >= 0) {
                    int endX = x + 2;
                    while (endX + 1 < n && grid[x][y] == grid[endX + 1][y]) {
                        endX++;
                    }
                    for (int i = x; i <= endX; i++) {
                        if (!checked[i][y]) {
                            matches.add(new int[]{i, y});
                            checked[i][y] = true;
                        }
                    }
                }
            }
        }

        // 竖向扫描不得用 checked 门禁起点：T/L 形竖臂常从已计入的横三连格子出发。
        for (int x = 0; x < n; x++) {
            for (int y = 0; y < n - 2; y++) {
                if (grid[x][y] == grid[x][y + 1] && grid[x][y] == grid[x][y + 2] && grid[x][y] >= 0) {
                    int endY = y + 2;
                    while (endY + 1 < n && grid[x][y] == grid[x][endY + 1]) {
                        endY++;
                    }
                    for (int i = y; i <= endY; i++) {
                        if (!checked[x][i]) {
                            matches.add(new int[]{x, i});
                            checked[x][i] = true;
                        }
                    }
                }
            }
        }
        return matches;
    }
}
