package com.wzz.game_console.client.screens.games.tictactoe;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 井字棋完整对局模拟（纯 JDK，零外部依赖）。
 * 覆盖：
 *  - 固定种子随机玩家 vs AI 跑满多局：终局标志、胜负一致性、回合守卫
 *  - 已占格重复落子必须被拒绝
 *  - 明知必胜局面下 AI（X）能抓住直接获胜着
 */
@Timeout(60)
class TicTacToeSimulationTest {

    @Test
    void twentyRandomGamesAllTerminateConsistently() {
        Random rnd = new Random(20260827L);
        for (int game = 0; game < 20; game++) {
            TicTacToeGame g = new TicTacToeGame(TicTacToeGame.GameMode.SINGLE_PLAYER);
            int moves = 0;
            while (!g.isGameOver()) {
                assertTrue(moves < 9, "超过 9 步仍未终局");
                if (g.isPlayerTurn()) {
                    List<int[]> empties = new ArrayList<>();
                    for (int r = 0; r < 3; r++)
                        for (int c = 0; c < 3; c++)
                            if (g.getCell(r, c) == TicTacToeGame.Player.NONE)
                                empties.add(new int[]{r, c});
                    assertFalse(empties.isEmpty(), "未终局但棋盘已满: 局 " + game);
                    int[] spot = empties.get(rnd.nextInt(empties.size()));
                    assertTrue(g.makeMove(spot[0], spot[1]), "空格落子被拒绝: 局 " + game);
                } else {
                    g.makeAIMove();
                }
                moves++;
            }
            TicTacToeGame.Player w = g.getWinner();
            // 平局时 getWinner() 返回 NONE（而非 null）
            assertTrue(w == TicTacToeGame.Player.X || w == TicTacToeGame.Player.O
                            || w == TicTacToeGame.Player.NONE,
                    "winner 只能为 X/O/NONE, 实际=" + w);
            if (w != null) {
                assertNotEquals("", g.getGameStatus(), "分出胜负后状态文案不得为空");
            }
        }
    }

    @Test
    void occupiedCellRejectsSecondPlacement() {
        TicTacToeGame g = new TicTacToeGame(TicTacToeGame.GameMode.SINGLE_PLAYER);
        assertTrue(g.makeMove(0, 0));
        assertFalse(g.makeMove(0, 0), "同格二次落子必须失败");
        assertEquals(1, countStones(g), "拒绝的落子不得改动棋盘");
    }

    @Test
    void aiRespondsImmediatelyWhenItsTurnArrives() {
        TicTacToeGame g = new TicTacToeGame(TicTacToeGame.GameMode.SINGLE_PLAYER);
        assertTrue(g.isPlayerTurn(), "单机模式玩家应先手");
        assertTrue(g.makeMove(1, 1));
        g.makeAIMove();
        assertEquals(2, countStones(g), "AI 回合结束后必须已落一手");
        int[] cell = aiCell(g);
        assertEquals(TicTacToeGame.Player.O, g.getCell(cell[0], cell[1]),
                "AI 落的必须是 O（玩家执 X）");
        assertTrue(g.isPlayerTurn(), "AI 落完必须轮回玩家");
    }

    @Test
    void aiSchedulingDuringPlayersTurnIsNoOp() {
        TicTacToeGame g = new TicTacToeGame(TicTacToeGame.GameMode.SINGLE_PLAYER);
        g.makeAIMove();                            // 玩家未动，AI 不得偷跑
        assertEquals(0, countStones(g), "玩家回合里 AI 调度不得改变棋盘");
        g.makeMove(2, 2);                          // 玩家落子后游戏翻转为 AI 方
    }

    @Test
    void playerCannotPlaceAgainWhileAiIsThinking() {
        TicTacToeGame g = new TicTacToeGame(TicTacToeGame.GameMode.SINGLE_PLAYER);
        assertTrue(g.makeMove(0, 0));
        assertFalse(g.makeMove(0, 1));
        assertEquals(TicTacToeGame.Player.NONE, g.getCell(0, 1));
        assertEquals(1, countStones(g));
        g.makeAIMove();
        assertEquals(2, countStones(g));
        assertTrue(g.isPlayerTurn());
    }

    @Test
    void localTwoPlayerAlternatesWithoutAiAndResets() {
        TicTacToeGame g = new TicTacToeGame(TicTacToeGame.GameMode.TWO_PLAYER);
        assertTrue(g.makeMove(0, 0));
        assertEquals(TicTacToeGame.Player.O, g.getCurrentPlayer());
        g.makeAIMove();
        assertEquals(1, countStones(g));
        assertTrue(g.makeMove(1, 0));
        assertTrue(g.makeMove(0, 1));
        assertTrue(g.makeMove(1, 1));
        assertTrue(g.makeMove(0, 2));
        assertEquals(TicTacToeGame.Player.X, g.getWinner());
        assertFalse(g.makeMove(2, 2));
        g.resetGame();
        assertEquals(0, countStones(g));
        assertEquals(TicTacToeGame.Player.X, g.getCurrentPlayer());
        assertEquals(TicTacToeGame.GameMode.TWO_PLAYER, g.getGameMode());
    }

    @Test
    void networkPlayerCannotTakeOpponentsTurn() {
        TicTacToeGame g = new TicTacToeGame(TicTacToeGame.GameMode.TWO_PLAYER);
        assertFalse(g.makeMove(0, 0, TicTacToeGame.Player.O));
        assertTrue(g.makeMove(0, 0, TicTacToeGame.Player.X));
        assertFalse(g.makeMove(0, 1, TicTacToeGame.Player.X));
        assertFalse(g.makeMove(0, 1, TicTacToeGame.Player.NONE));
        assertTrue(g.makeMove(0, 1, TicTacToeGame.Player.O));
        assertFalse(g.makeMove(0, 2, TicTacToeGame.Player.O));
        assertEquals(2, countStones(g));
    }

    @Test
    void rejectedTwoPlayerMovesPreserveTurn() {
        TicTacToeGame g = new TicTacToeGame(TicTacToeGame.GameMode.TWO_PLAYER);
        assertTrue(g.makeMove(1, 1));
        assertFalse(g.makeMove(1, 1));
        assertFalse(g.makeMove(-1, 0));
        assertFalse(g.makeMove(0, 3));
        assertFalse(g.makeMove(0, 0, null));
        assertEquals(TicTacToeGame.Player.O, g.getCurrentPlayer());
        assertEquals(1, countStones(g));
        assertTrue(g.makeMove(0, 0, TicTacToeGame.Player.O));
    }

    @Test
    void twoPlayerDrawRejectsFurtherMoves() {
        TicTacToeGame g = new TicTacToeGame(TicTacToeGame.GameMode.TWO_PLAYER);
        int[][] moves = {{0, 0}, {0, 1}, {0, 2}, {1, 1}, {1, 0},
                {1, 2}, {2, 1}, {2, 0}, {2, 2}};
        for (int[] move : moves) assertTrue(g.makeMove(move[0], move[1]));
        assertTrue(g.isGameOver());
        assertEquals(TicTacToeGame.Player.NONE, g.getWinner());
        assertEquals(9, countStones(g));
        assertFalse(g.makeMove(0, 0));
    }

    @Test
    void resetDuringAiTurnRestoresHumanTurn() {
        TicTacToeGame g = new TicTacToeGame(TicTacToeGame.GameMode.SINGLE_PLAYER);
        assertTrue(g.makeMove(0, 0));
        g.resetGame();
        g.makeAIMove();
        assertTrue(g.isPlayerTurn());
        assertEquals(0, countStones(g));
        assertTrue(g.makeMove(2, 2));
        assertEquals(TicTacToeGame.Player.X, g.getCell(2, 2));
    }

    /** 返回 AI 刚落下的格点（唯一的 O） */
    private static int[] aiCell(TicTacToeGame g) {
        for (int r = 0; r < 3; r++)
            for (int c = 0; c < 3; c++)
                if (g.getCell(r, c) == TicTacToeGame.Player.O) return new int[]{r, c};
        throw new AssertionError("找不到 AI 落下的 O 子");
    }

    private static int countStones(TicTacToeGame g) {
        int n = 0;
        for (int r = 0; r < 3; r++)
            for (int c = 0; c < 3; c++)
                if (g.getCell(r, c) != TicTacToeGame.Player.NONE) n++;
        return n;
    }
}
