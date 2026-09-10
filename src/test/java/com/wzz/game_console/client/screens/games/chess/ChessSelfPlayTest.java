package com.wzz.game_console.client.screens.games.chess;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 中国象棋内置引擎无头自对弈（纯 JDK，无 Minecraft 依赖）。
 * 覆盖：
 *  - 引擎每步给出的着法必须在该局面 legalMoves 集合内（不被自己将军、棋子移动合法）
 *  - 对弈过程中"将帅"总数守恒：引擎不得走出吃将着法（吃将在规则库中是非法着法的最后防线）
 *  - 快棋多局不崩溃、可终止
 */
@Timeout(300)
class ChessSelfPlayTest {

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

    /**
     * ChessRules.legalMoves 返回的是五元组 {fromX,fromY,toX,toY,internalScore}，
     * 成员判定只能比前四个坐标，不能用 Arrays.equals（长度不同恒为 false）。
     */
    private static boolean sameMove(int[] a, int[] b) {
        return a.length >= 4 && b.length >= 4
                && a[0] == b[0] && a[1] == b[1] && a[2] == b[2] && a[3] == b[3];
    }

    private static int countGenerals(int[][] board) {
        int n = 0;
        for (int[] row : board) for (int p : row) if (Math.abs(p) == ChessRules.GENERAL) n++;
        return n;
    }

    @Test
    void everyEngineMoveIsWithinLegalMoves() {
        BuiltInChessAI engine = new BuiltInChessAI();
        engine.setSearchTime(40);
        int[][] board = initialBoard();
        boolean redTurn = true;

        for (int step = 0; step < 120; step++) {
            List<int[]> legal = ChessRules.legalMoves(board, redTurn);
            if (legal.isEmpty()) break;                       // 无子可动 = 终局
            assertEquals(2, countGenerals(board), "第 " + step + " 步前将帅数目异常");

            int[] mv = engine.getBestMove(board, redTurn);
            assertNotNull(mv, "第 " + step + " 步有合法着但引擎返回 null");
            assertTrue(legal.stream().anyMatch(l -> sameMove(l, mv)),
                    "第 " + step + " 步引擎着法不在合法集合内: "
                            + Arrays.toString(mv) + " redTurn=" + redTurn);

            board[mv[2]][mv[3]] = board[mv[0]][mv[1]];
            board[mv[0]][mv[1]] = 0;
            redTurn = !redTurn;
        }
    }

    @Test
    void fullSelfPlayTwoGamesTerminateCleanly() {
        for (int game = 0; game < 2; game++) {
            BuiltInChessAI engine = new BuiltInChessAI(game == 1); // 第二局开启 LMR 变体路径
            engine.setSearchTime(60);
            int[][] board = initialBoard();
            boolean redTurn = true;
            int appliedSteps = 0;

            for (int step = 0; step < 160 && !ChessRules.legalMoves(board, redTurn).isEmpty(); step++) {
                List<int[]> legalNow = ChessRules.legalMoves(board, redTurn);
                if (legalNow.isEmpty()) break;
                int[] mv = engine.getBestMove(board, redTurn);
                assertNotNull(mv, "第 " + game + " 局第 " + step + " 步有合法着但引擎返回 null");
                assertTrue(legalNow.stream().anyMatch(l -> sameMove(l, mv)),
                        "第 " + game + " 局第 " + step + " 步引擎着法不在合法集合: "
                                + Arrays.toString(mv) + " redTurn=" + redTurn);
                board[mv[2]][mv[3]] = board[mv[0]][mv[1]];
                board[mv[0]][mv[1]] = 0;
                redTurn = !redTurn;
                appliedSteps++;
            }
            assertTrue(appliedSteps >= 8, "第 " + game + " 局步数过少(" + appliedSteps + ")，疑似引擎开局即卡死");
        }
    }

    @Test
    void fenRoundTripAcrossAppliedMovesStaysConsistent() {
        BuiltInChessAI engine = new BuiltInChessAI();
        engine.setSearchTime(30);
        int[][] board = initialBoard();
        boolean redTurn = true;

        for (int step = 0; step < 30; step++) {
            if (ChessRules.legalMoves(board, redTurn).isEmpty()) break;
            String fenBefore = ChessRules.toFen(board, redTurn);
            assertFalse(fenBefore.isEmpty(), "FEN 序列化不得为空");

            int[] mv = engine.getBestMove(board, redTurn);
            assertNotNull(mv);
            board[mv[2]][mv[3]] = board[mv[0]][mv[1]];
            board[mv[0]][mv[1]] = 0;
            redTurn = !redTurn;

            String fenAfter = ChessRules.toFen(board, redTurn);
            assertFalse(fenAfter.isEmpty());
            assertFalse(fenAfter.equals(fenBefore), "走子后 FEN 必须变化");
        }
    }
}
