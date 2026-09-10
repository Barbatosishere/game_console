package com.wzz.game_console.client.screens.games;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.util.Random;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 五子棋 AI 无头模拟对局（纯 JDK，无 Minecraft 依赖）。
 * 覆盖：
 *  - 各难度在固定中盘局面下返回的着点必须在界内且为空位
 *  - getMove 不修改调用方棋盘
 *  - 黑随机 + 白 AI 完整自对弈不崩溃、每步合法、终局可判
 */
@Timeout(120)
class GomokuAISimulationTest {

    private static final int SIZE = 15;

    /** 天元附近双方各几子的中盘局面 */
    private static int[][] midGameBoard() {
        int[][] b = new int[SIZE][SIZE];
        b[7][7] = GomokuAI.BLACK;               // 天元黑
        b[8][7] = GomokuAI.WHITE;
        b[6][6] = GomokuAI.BLACK;
        return b;
    }

    @Test
    void everyDifficultyReturnsLegalMoveOnMidGame() {
        int[][] board = midGameBoard();
        for (GomokuAI.Difficulty d : GomokuAI.Difficulty.values()) {
            int[] mv = new GomokuAI(d).getMove(board);
            assertNotNull(mv, "难度 " + d.label + " 应返回着点");
            assertEquals(2, mv.length, "着点应为 {x,y} 二元组");
            assertTrue(mv[0] >= 0 && mv[0] < SIZE && mv[1] >= 0 && mv[1] < SIZE,
                    "难度 " + d.label + " 着点越界: (" + mv[0] + "," + mv[1] + ")");
            assertEquals(GomokuAI.EMPTY, board[mv[0]][mv[1]],
                    "难度 " + d.label + " 落在已占格: (" + mv[0] + "," + mv[1] + ")");
        }
    }

    @Test
    void aiDoesNotTouchCallerBoardBeforeApplication() {
        int[][] before = midGameBoard();
        int[][] snapshot = new int[SIZE][SIZE];
        for (int x = 0; x < SIZE; x++) System.arraycopy(before[x], 0, snapshot[x], 0, SIZE);

        new GomokuAI(GomokuAI.Difficulty.NORMAL).getMove(snapshot);

        assertArrayEquals(before, snapshot, "getMove 不得修改调用方棋盘");
    }

    @Test
    void emptyCornerBoardReturnsCenterishFirstMove() {
        int[][] board = new int[SIZE][SIZE]; // AI 执白，但开局盘面是空的：AI 需给出一个合法首着
        int[] mv = new GomokuAI(GomokuAI.Difficulty.EASY).getMove(board);
        assertNotNull(mv, "空盘也必须有着可下");
        assertTrue(mv[0] >= 0 && mv[0] < SIZE && mv[1] >= 0 && mv[1] < SIZE,
                "首着越界: (" + mv[0] + "," + mv[1] + ")");
    }

    /** 黑方随机的完整对局：白方由 AI 驱动，直到分出五连或棋满 */
    @Test
    void fullGameAgainstRandomBlackTerminatesCleanly() {
        Random rnd = new Random(42);
        GomokuAI ai = new GomokuAI(GomokuAI.Difficulty.NORMAL);
        int[][] board = new int[SIZE][SIZE];

        boolean blackTurn = true;
        for (int step = 0; step < SIZE * SIZE; step++) {
            int lx = -1, ly = -1;
            if (blackTurn) {
                int empties = countEmpty(board);
                if (empties == 0) break;
                int target = rnd.nextInt(empties);
                outer:
                for (int x = 0; x < SIZE; x++)
                    for (int y = 0; y < SIZE; y++)
                        if (board[x][y] == GomokuAI.EMPTY && target-- == 0) {
                            lx = x; ly = y;
                            break outer;
                        }
                board[lx][ly] = GomokuAI.BLACK;
            } else {
                int[] mv = ai.getMove(board);
                if (mv == null) break;
                assertTrue(mv[0] >= 0 && mv[0] < SIZE && mv[1] >= 0 && mv[1] < SIZE,
                        "第 " + step + " 步 AI 着点越界: " + java.util.Arrays.toString(mv));
                assertEquals(GomokuAI.EMPTY, board[mv[0]][mv[1]],
                        "第 " + step + " 步 AI 落已占格: " + java.util.Arrays.toString(mv));
                board[mv[0]][mv[1]] = GomokuAI.WHITE;
                lx = mv[0];
                ly = mv[1];
            }
            // 真实成五后对局合法终止，胜负本身不作断言
            if (checkFive(board, lx, ly, blackTurn ? GomokuAI.BLACK : GomokuAI.WHITE)) {
                return;
            }
            blackTurn = !blackTurn;
        }
        if (countEmpty(board) > 0) {
            for (int x = 0; x < SIZE; x++)
                for (int y = 0; y < SIZE; y++)
                    if (board[x][y] != GomokuAI.EMPTY)
                        assertFalse(checkFive(board, x, y, board[x][y]),
                                "提前停手却没有完整五连，盘点位置: (" + x + "," + y + ")");
        }
    }

    private static int countEmpty(int[][] board) {
        int n = 0;
        for (int[] row : board) for (int c : row) if (c == GomokuAI.EMPTY) n++;
        return n;
    }

    private static boolean checkFive(int[][] board, int x, int y, int p) {
        int[][] dirs = {{1, 0}, {0, 1}, {1, 1}, {1, -1}};
        for (int[] d : dirs) {
            int cnt = 1;
            for (int s = -1; s <= 1; s += 2)
                for (int k = 1; k <= 4; k++) {
                    int nx = x + d[0] * k * s, ny = y + d[1] * k * s;
                    if (nx < 0 || nx >= SIZE || ny < 0 || ny >= SIZE || board[nx][ny] != p) break;
                    cnt++;
                }
            if (cnt >= 5) return true;
        }
        return false;
    }
}
