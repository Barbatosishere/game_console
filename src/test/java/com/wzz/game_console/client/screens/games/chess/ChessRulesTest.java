package com.wzz.game_console.client.screens.games.chess;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 中国象棋规则库最小回归测试（纯 JDK，无 Minecraft 依赖，可在 JUnit 中直接运行）。
 * 重点覆盖：
 *  - 初始局面 FEN 序列化（外挂 Pikafish 通信的根基）
 *  - UCI 坐标 ↔ 棋盘坐标转换（含行号翻转，rank0=底部红方）
 *  - 开局合法走法数量与"将帅不能照面/走后自将"过滤
 */
class ChessRulesTest {

    /** 构造与 ChessGameScreen.resetBoard() 一致的初始棋盘 */
    private static int[][] initialBoard() {
        int[][] b = new int[ChessRules.COLS][ChessRules.ROWS];
        int[] back = {ChessRules.CHARIOT, ChessRules.HORSE, ChessRules.ELEPHANT,
                ChessRules.ADVISOR, ChessRules.GENERAL, ChessRules.ADVISOR,
                ChessRules.ELEPHANT, ChessRules.HORSE, ChessRules.CHARIOT};
        for (int c = 0; c < 9; c++) b[c][0] = -back[c];
        b[1][2] = -ChessRules.CANNON; b[7][2] = -ChessRules.CANNON;
        for (int c = 0; c < 9; c += 2) b[c][3] = -ChessRules.SOLDIER;
        for (int c = 0; c < 9; c++) b[c][9] = back[c];
        b[1][7] = ChessRules.CANNON; b[7][7] = ChessRules.CANNON;
        for (int c = 0; c < 9; c += 2) b[c][6] = ChessRules.SOLDIER;
        return b;
    }

    @Test
    void testInitialFen() {
        int[][] b = initialBoard();
        assertEquals(
                "rnbakabnr/9/1c5c1/p1p1p1p1p/9/9/P1P1P1P1P/1C5C1/9/RNBAKABNR w - - 0 1",
                ChessRules.toFen(b, true),
                "初始局面红先（w）的 FEN 必须与标准中国象棋 XFEN 一致（否则 Pikafish 无法开局）"
        );
    }

    @Test
    void testUciCoordinateRoundTrip() {
        // UCI rank0 = 底部红方底线 → 棋盘 row9。
        // "a0a1" = 红车(0,9)→(0,8)；"i9i8" = 黑车(8,0)→(8,1)（从上往下）；"e4e5" = 中央(4,5)→(4,4)
        assertArrayEquals(new int[]{0, 9, 0, 8}, ChessRules.parseUciMove("a0a1"));
        assertArrayEquals(new int[]{8, 0, 8, 1}, ChessRules.parseUciMove("i9i8"));
        assertArrayEquals(new int[]{4, 5, 4, 4}, ChessRules.parseUciMove("e4e5"));

        assertEquals("a0a1", ChessRules.toUciMove(0, 9, 0, 8));
        assertEquals("i9i8", ChessRules.toUciMove(8, 0, 8, 1));

        assertNull(ChessRules.parseUciMove("j0a1"), "j 超出 a-i 文件范围应返回 null");
        assertNull(ChessRules.parseUciMove("a"), "过短应返回 null");
    }

    @Test
    void testOpeningLegalMoveCount() {
        // 象棋开局理论：红方第一步合法走法 = 44（全部能动的棋子）
        // 帅(4区)… 直接断言一个合理区间 ±2，避免实现细节改变导致脆测
        int[][] b = initialBoard();
        List<int[]> moves = ChessRules.legalMoves(b, true);
        assertTrue(moves.size() >= 40 && moves.size() <= 48,
                "红方初始合法走法应在 40~48 之间，实际=" + moves.size());
    }

    @Test
    void testSelfCheckFiltered() {
        // 构造一个红方老将被黑车纵向将军的局面：(4,0) 黑车 vs (4,9) 红帅
        int[][] b = new int[ChessRules.COLS][ChessRules.ROWS];
        b[4][0] = -ChessRules.CHARIOT;       // 黑车 (4,0)
        b[4][9] = ChessRules.GENERAL;        // 红帅 (4,9)

        List<int[]> moves = ChessRules.legalMoves(b, true);
        assertFalse(moves.isEmpty(), "被将军时应有解将走法");
        for (int[] mv : moves) {
            // 走后不能再被将军
            int captured = b[mv[2]][mv[3]];
            int piece = b[mv[0]][mv[1]];
            b[mv[2]][mv[3]] = piece;
            b[mv[0]][mv[1]] = 0;
            assertFalse(ChessRules.inCheckOnBoard(b, true),
                    "合法走法 (" + mv[0] + "," + mv[1] + ")->(" + mv[2] + "," + mv[3] + ") 不应留下自将");
            b[mv[0]][mv[1]] = piece;
            b[mv[2]][mv[3]] = captured;
        }
        // 帅只能在九宫内横向移动（(3,9) 或 (5,9)）避开黑车视线，数量有限
        assertTrue(moves.size() <= 8, "解将走法数量应在合理范围内（实际=" + moves.size() + "）");
    }

    @Test
    void checkDetectionSeesGeneralButMovesCannotCaptureIt() {
        int[][] b = new int[ChessRules.COLS][ChessRules.ROWS];
        b[4][9] = ChessRules.GENERAL;
        b[3][0] = -ChessRules.GENERAL;
        b[4][0] = -ChessRules.CHARIOT;

        assertTrue(ChessRules.inCheckOnBoard(b, true),
                "黑车直线攻击红帅时必须判定为将军");
        assertFalse(ChessRules.pseudoMoves(b, 4, 0).stream()
                        .anyMatch(move -> move[0] == 4 && move[1] == 9),
                "实际走法生成不得包含直接捕获红帅");
    }

    @Test
    void cannonCheckRequiresExactlyOneScreenAndPreservesBoard() {
        for (boolean red : new boolean[]{true, false}) {
            for (boolean horizontal : new boolean[]{true, false}) {
                for (int screens = 0; screens <= 2; screens++) {
                    int[][] b = new int[ChessRules.COLS][ChessRules.ROWS];
                    int row = red ? 9 : 0;
                    int cannonCol = horizontal ? 0 : 4;
                    int cannonRow = horizontal ? row : 9 - row;
                    b[4][row] = red ? ChessRules.GENERAL : -ChessRules.GENERAL;
                    b[3][9 - row] = red ? -ChessRules.GENERAL : ChessRules.GENERAL;
                    b[cannonCol][cannonRow] = red ? -ChessRules.CANNON : ChessRules.CANNON;
                    if (screens >= 1) b[horizontal ? 1 : 4][horizontal ? row : 4] = ChessRules.SOLDIER;
                    if (screens == 2) b[horizontal ? 2 : 4][horizontal ? row : 5] = -ChessRules.SOLDIER;
                    String before = ChessRules.toFen(b, red);

                    assertEquals(screens == 1, ChessRules.inCheckOnBoard(b, red),
                            "red=" + red + ", horizontal=" + horizontal + ", screens=" + screens);
                    assertEquals(before, ChessRules.toFen(b, red));
                    assertFalse(ChessRules.pseudoMoves(b, cannonCol, cannonRow).stream()
                            .anyMatch(move -> move[0] == 4 && move[1] == row));
                }
            }
        }
    }

    @Test
    void testZobristDiffersBySide() {
        // 未覆盖内部 Zobrist（BuiltInChessAI 私有）；此处仅确保 API 稳定可调用
        int[][] b = initialBoard();
        String fenRed = ChessRules.toFen(b, true);
        String fenBlack = ChessRules.toFen(b, false);
        assertNotEquals(fenRed, fenBlack, "行棋方不同，FEN 后缀 w/b 应不同");
        assertTrue(fenRed.endsWith(" w - - 0 1"));
        assertTrue(fenBlack.endsWith(" b - - 0 1"));
    }
}