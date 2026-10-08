package com.wzz.game_console.client.screens.games;

import java.util.Random;

/** 从完成状态反向拉箱子，确保生成的关卡可以通过正常推动复原。 */
final class SokobanLevelGenerator {
    private static final int[] DX = {1, -1, 0, 0};
    private static final int[] DY = {0, 0, 1, -1};

    private SokobanLevelGenerator() {}

    static char[][] generate(int levelNum, long salt) {
        levelNum = Math.max(1, levelNum);
        int size = (int) Math.min(5 + (levelNum - 1) * 2L / 3, 14);
        int boxCount = Math.min(1 + (levelNum - 1) / 2, 6);
        int obstacleCount = Math.min(1 + (levelNum - 1) / 2, 8);
        for (int attempt = 0; attempt < 24; attempt++) {
            Random random = new Random(levelNum * 7919L + 271L + attempt * 104729L + salt);
            char[][] grid = generateOnce(size, boxCount, obstacleCount, levelNum, random);
            if (grid != null) return grid;
        }

        // 重试仍未产生未完成盘面时，使用可直接推完的一箱关卡。
        char[][] grid = emptyGrid(size);
        grid[1][2] = '$';
        grid[1][3] = '.';
        grid[2][1] = '@';
        return grid;
    }

    private static char[][] generateOnce(int size, int boxCount, int obstacleCount,
                                         int levelNum, Random random) {
        char[][] grid = emptyGrid(size);
        for (int i = 0; i < obstacleCount; i++) {
            for (int retry = 0; retry < 30; retry++) {
                int x = 1 + random.nextInt(size - 2);
                int y = 1 + random.nextInt(size - 2);
                if (grid[y][x] == ' ') { grid[y][x] = '#'; break; }
            }
        }

        int placed = 0;
        for (int retry = 0; retry < 500 && placed < boxCount; retry++) {
            int x = 1 + random.nextInt(size - 2);
            int y = 1 + random.nextInt(size - 2);
            if (grid[y][x] == ' ') { grid[y][x] = '+'; placed++; }
        }
        if (placed != boxCount) return null;

        int playerX = -1, playerY = -1;
        outer:
        for (int y = 1; y < size - 1; y++) {
            for (int x = 1; x < size - 1; x++) {
                if (grid[y][x] == ' ') { playerX = x; playerY = y; break outer; }
            }
        }
        if (playerX < 0) return null;
        grid[playerY][playerX] = '@';

        int pulls = 0;
        int desiredPulls = boxCount * 8 + Math.min(levelNum, 50) * 2;
        int maxSteps = 1500 + boxCount * 500;
        for (int step = 0; step < maxSteps && pulls < desiredPulls; step++) {
            int direction = random.nextInt(4);
            int nextX = playerX + DX[direction];
            int nextY = playerY + DY[direction];
            char destination = grid[nextY][nextX];
            if (destination != ' ' && destination != '.') continue;

            char floor = grid[playerY][playerX] == '*' ? '.' : ' ';
            int boxX = playerX - DX[direction];
            int boxY = playerY - DY[direction];
            char behind = grid[boxY][boxX];
            if (behind == '$' || behind == '+') {
                // 玩家走向空位，并把身后的箱子拉到原位置；逆序执行就是合法推动。
                grid[boxY][boxX] = behind == '+' ? '.' : ' ';
                grid[playerY][playerX] = floor == '.' ? '+' : '$';
                pulls++;
            } else {
                grid[playerY][playerX] = floor;
            }
            grid[nextY][nextX] = destination == '.' ? '*' : '@';
            playerX = nextX;
            playerY = nextY;
        }

        // 保留全部目标和箱子，要求开局至少有一个箱子未归位。
        for (char[] row : grid) {
            for (char cell : row) if (cell == '$') return grid;
        }
        return null;
    }

    private static char[][] emptyGrid(int size) {
        char[][] grid = new char[size][size];
        for (int y = 0; y < size; y++) {
            for (int x = 0; x < size; x++) {
                grid[y][x] = x == 0 || y == 0 || x == size - 1 || y == size - 1 ? '#' : ' ';
            }
        }
        return grid;
    }
}
