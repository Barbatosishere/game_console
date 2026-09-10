package com.wzz.game_console.client.screens.games.gogame;

import org.junit.jupiter.api.Test;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 围棋对局规则的最小回归测试（纯 JDK，无 Minecraft 依赖，可在 JUnit 中直接运行）。
 * 重点覆盖：
 *  - 基本落子 / 占用位拒绝 / 越界拒绝 / 弃权 / 认输
 *  - 提子
 *  - 全局同型（super-ko）：构造真实劫形，验证立即回提被拒绝（本次修复的回归点）
 */
class GoGameTest {

    @Test
    void testBasicPlacementAndPass() {
        GoGame game = new GoGame();
        assertFalse(game.isGameOver(), "新开局不应结束");
        assertEquals(GoPlayer.BLACK, game.getCurrentPlayer(), "黑先");

        assertTrue(game.placeStone(3, 3), "黑(3,3) 应合法");
        assertEquals(GoPlayer.WHITE, game.getCurrentPlayer(), "轮到白");

        assertTrue(game.placeStone(3, 4), "白(3,4) 应合法");
        assertEquals(GoPlayer.BLACK, game.getCurrentPlayer(), "轮到黑");

        // 黑弃权 → 白弃权 → 连续两次弃权结束
        game.pass();
        assertEquals(GoPlayer.WHITE, game.getCurrentPlayer(), "黑弃权后轮到白");
        game.pass();
        assertTrue(game.isGameOver(), "连续两次弃权后游戏应结束");
    }

    @Test
    void testCapture() {
        GoGame game = new GoGame();
        placeKoSetup(game);

        // 此时白(4,4) 处于"只剩 (4,5) 一口气"的被打吃状态，轮到黑棋
        assertEquals(GoPlayer.BLACK, game.getCurrentPlayer(), "毕业后轮到黑棋... 实为 setup 后轮到黑");
        assertTrue(game.placeStone(4, 5), "黑(4,5) 应提掉白(4,4)");
        assertEquals(GoPlayer.NONE, game.getStone(4, 4), "白(4,4) 应被提掉");
        assertEquals(1, game.getWhiteCaptured(), "黑方应提了 1 颗白子（计入 whiteCaptured）");
    }

    @Test
    void testSuperKoBlocksImmediateRecapture() {
        // 回归测试：全局同型（super-ko）必须拒绝劫的立即回提
        GoGame game = new GoGame();
        placeKoSetup(game);

        // 黑提劫：黑(4,5) 提掉白(4,4)
        assertTrue(game.placeStone(4, 5), "黑(4,5) 提劫应成功");
        assertEquals(GoPlayer.BLACK, game.getStone(4, 5), "提劫点 (4,5) 应为黑子");

        // 白回提：(4,4) 已空，白从规则上可落子（提黑(4,5)），
        // 但该局面与提劫前完全相同，属于全局同型重复，必须被拒绝。
        assertFalse(game.placeStone(4, 4), "全局同型：白(4,4) 立即回提必须被拒绝");
    }

    @Test
    void testTypedAiActionsPreservePassResignAndErrorSemantics() {
        GoGame game = GoGame.rulesOnly();
        game.setAiMode(true);
        game.applyAiMoveResult(GoAI.MoveResult.error());
        assertFalse(game.isGameOver(), "AI error must not become a pass");
        assertEquals(0, game.moveHistorySize(), "AI error must not change history");

        game.applyAiMoveResult(GoAI.MoveResult.pass());
        assertFalse(game.isGameOver(), "one AI pass must not end the game");
        assertEquals(1, game.moveHistorySize());
        assertEquals(GoPlayer.WHITE, game.getCurrentPlayer());

        game.applyAiMoveResult(GoAI.MoveResult.resign());
        assertTrue(game.isGameOver(), "AI resignation must end the game");
        assertEquals(GoPlayer.WHITE, game.getResignedPlayer());
    }

    @Test
    void testAiPipelinePreservesResignationWithoutAddingAPass() {
        GoGame game = GoGame.rulesOnly();
        game.setAiMode(true);
        game.placeStone(3, 3);
        game.setAiEngine(new GoAI() {
            @Override public int[] getBestMove(GoGame ignored) { fail("typed pipeline must not use legacy API"); return null; }
            @Override public MoveResult getBestMoveResult(GoGame ignored) { return MoveResult.resign(); }
        });
        game.makeAiMove();
        assertTrue(game.isGameOver());
        assertEquals(GoPlayer.WHITE, game.getResignedPlayer());
        assertEquals(1, game.moveHistorySize());
        game.reset();
        assertEquals(GoPlayer.NONE, game.getResignedPlayer());
    }

    @Test
    void testLegacyAiNullIsPassButThrownExceptionIsError() {
        GoGame game = GoGame.rulesOnly();
        game.setAiMode(true);
        game.setAiEngine(ignored -> { throw new IllegalStateException("failed search"); });
        game.makeAiMove();
        assertEquals(0, game.moveHistorySize());
        game.setAiEngine(ignored -> null);
        game.makeAiMove();
        assertEquals(1, game.moveHistorySize());
        assertFalse(game.isGameOver());
    }

    @Test
    void staleAiMoveIsRejectedAfterPositionChanges() throws Exception {
        assertStaleAiResultRejected(GoAI.MoveResult.move(4, 4), game -> game.placeStone(3, 3));
    }

    @Test
    void staleAiPassIsRejectedAfterReset() throws Exception {
        assertStaleAiResultRejected(GoAI.MoveResult.pass(), GoGame::reset);
    }

    @Test
    void staleAiResignationIsRejectedAfterEngineReplacement() throws Exception {
        assertStaleAiResultRejected(GoAI.MoveResult.resign(),
                game -> game.setAiEngine(ignored -> new int[]{5, 5}));
    }

    @Test
    void staleAiResultIsRejectedAfterClose() throws Exception {
        assertStaleAiResultRejected(GoAI.MoveResult.pass(), GoGame::close);
    }

    @Test
    void testRulesOnlyGameDoesNotEnableAiLifecycle() {
        assertFalse(GoGame.rulesOnly().isAiEnabled());
        assertTrue(new GoGame().isAiEnabled());
    }

    @Test
    void testResignationRecordsCurrentPlayerAndIsIdempotent() {
        GoGame game = GoGame.rulesOnly();
        assertEquals(GoPlayer.BLACK, game.getCurrentPlayer());
        game.resign();
        assertTrue(game.isGameOver());
        assertEquals(GoPlayer.BLACK, game.getResignedPlayer());
        game.resign(GoPlayer.WHITE);
        assertEquals(GoPlayer.BLACK, game.getResignedPlayer());
    }

    @Test
    void testMalformedLegacyAiMoveIsAnError() {
        GoGame game = GoGame.rulesOnly();
        game.applyAiMove(new int[] {1});
        game.applyAiMove(new int[] {});
        game.applyAiMove(new int[] {-1, -1});
        game.applyAiMove(new int[] {1, -1});
        game.applyAiMove(new int[] {19, 0});
        assertFalse(game.isGameOver());
        assertEquals(0, game.moveHistorySize());
        assertEquals(GoPlayer.BLACK, game.getCurrentPlayer());
    }

    @Test
    void testLegacyNullStillCompilesAndMeansPass() {
        GoGame game = GoGame.rulesOnly();
        game.applyAiMove(null);
        assertEquals(1, game.moveHistorySize());
        assertEquals(GoPlayer.WHITE, game.getCurrentPlayer());
        assertFalse(game.isGameOver());
    }

    @Test
    void testOccupiedPositionRejected() {
        GoGame game = new GoGame();
        assertTrue(game.placeStone(3, 3), "黑(3,3) 首次落子");
        game.pass();
        assertFalse(game.placeStone(3, 3), "黑(3,3) 已占位，再下应拒绝");
    }

    @Test
    void testOutOfBoundsRejected() {
        GoGame game = new GoGame();
        assertFalse(game.placeStone(-1, 0), "负坐标应拒绝");
        assertFalse(game.placeStone(0, 19), "越界坐标应拒绝");
        assertFalse(game.placeStone(19, 19), "越界坐标应拒绝");
    }

    @Test
    void testUnmarkedDeadStoneRemainsInScore() throws Exception {
        GoGame game = new GoGame(false);
        java.lang.reflect.Field boardField = GoGame.class.getDeclaredField("board");
        boardField.setAccessible(true);
        GoPlayer[][] board = (GoPlayer[][]) boardField.get(game);
        // A white stone with its only remaining liberty surrounded by black.
        board[9][9] = GoPlayer.WHITE;
        board[8][9] = GoPlayer.BLACK;
        board[10][9] = GoPlayer.BLACK;
        board[9][8] = GoPlayer.BLACK;
        board[9][10] = GoPlayer.BLACK;
        int[] score = game.calcTerritory();
        assertEquals(1, score[1], "未标记棋子仍应计入白方数子分");
        int[] marked = game.calcTerritory(java.util.Set.of(GoGameTest.key(9, 9)));
        assertEquals(0, marked[1], "显式标记后白死子不计分");
        assertTrue(marked[0] >= 4, "黑方棋子仍应计入黑方数子分");
    }

    @Test
    void testMarkedGroupRemovesWholeConnectedGroupAndCanBeCancelled() throws Exception {
        GoGame game = new GoGame(false);
        java.lang.reflect.Field boardField = GoGame.class.getDeclaredField("board");
        boardField.setAccessible(true);
        GoPlayer[][] board = (GoPlayer[][]) boardField.get(game);
        board[9][9] = GoPlayer.WHITE;
        board[9][10] = GoPlayer.WHITE;
        board[8][9] = GoPlayer.BLACK;
        board[10][9] = GoPlayer.BLACK;
        board[9][8] = GoPlayer.BLACK;
        board[9][11] = GoPlayer.BLACK;
        board[8][10] = GoPlayer.BLACK;
        board[10][10] = GoPlayer.BLACK;
        int[] marked = game.calcTerritory(java.util.Set.of(key(9, 10)));
        assertEquals(0, marked[1], "标记组内一点应移除整组");
        assertEquals(2, game.calcTerritory().length, "计分结果应保持双方数组格式");
        assertEquals(GoPlayer.WHITE, game.getStone(9, 9), "计分副本不能修改实际棋盘");
    }

    @Test
    void testInvalidDeadMarkRejected() {
        GoGame game = new GoGame(false);
        assertThrows(IllegalArgumentException.class, () -> game.calcTerritory(java.util.Set.of(key(0, 0))));
        assertThrows(IllegalArgumentException.class, () -> game.calcTerritory(java.util.Set.of(key(19, 0))));
    }

    @Test
    void testExplicitKomiIsUsedForScoreAndMargin() {
        GoGame game = new GoGame(false);
        assertEquals(0.0, game.getScore(GoPlayer.BLACK, java.util.Collections.emptySet(), 7.5));
        assertEquals(7.5, game.getScore(GoPlayer.WHITE, java.util.Collections.emptySet(), 7.5));
        assertEquals(-7.5, game.getScoreMargin(GoPlayer.BLACK, java.util.Collections.emptySet(), 7.5));
        assertEquals(100.0, game.getScore(GoPlayer.WHITE, java.util.Collections.emptySet(), 250.0));
        assertEquals(-100.0, game.getScore(GoPlayer.WHITE, java.util.Collections.emptySet(), -250.0));
    }

    @Test
    void testEqualScoresHaveNoWinner() {
        try (GoGame game = GoGame.rulesOnly()) {
            assertTrue(game.placeStone(0, 0));
            assertTrue(game.placeStone(18, 18));
            game.pass();
            game.pass();
            GoGame.Score score = game.getScores(java.util.Collections.emptySet(), 0.0);
            assertEquals(1.0, score.black());
            assertEquals(score.black(), score.white());
            assertEquals(GoPlayer.NONE, score.winner());
            assertEquals(0.0, game.getScoreMargin(GoPlayer.BLACK, java.util.Collections.emptySet(), 0.0));
        }
    }

    @Test
    void testScoreWinnerUsesExplicitPositiveAndNegativeKomi() {
        try (GoGame game = GoGame.rulesOnly()) {
            game.pass();
            game.pass();
            assertEquals(GoPlayer.WHITE, game.getScores(java.util.Collections.emptySet(), 7.5).winner());
            assertEquals(GoPlayer.BLACK, game.getScores(java.util.Collections.emptySet(), -2.5).winner());
            assertEquals(GoPlayer.NONE, game.getScores(java.util.Collections.emptySet(), -0.0).winner());
        }
    }

    @Test
    void testScoringLeavesLivePositionUntouched() throws Exception {
        GoGame game = new GoGame(false);
        java.lang.reflect.Field boardField = GoGame.class.getDeclaredField("board");
        boardField.setAccessible(true);
        GoPlayer[][] board = (GoPlayer[][]) boardField.get(game);
        board[9][9] = GoPlayer.WHITE;
        board[8][9] = GoPlayer.BLACK;
        board[10][9] = GoPlayer.BLACK;
        board[9][8] = GoPlayer.BLACK;
        game.calcTerritory();
        assertEquals(GoPlayer.WHITE, game.getStone(9, 9), "计分不能修改实际棋盘");
    }

    /**
     * 构造一个标准单点劫（ko）局面，走完后轮到黑棋。
     * 布局（19x19 棋盘局部，SE 方向放大）：
     *    (3,4)(4,4)(5,4) 列上，白(4,4) 的上/左/下三面为黑：
     *      白(4,4)；黑(3,4)、(5,4)、(4,3)
     *    白(3,5)、(5,5)、(4,6) 用于包住将来黑方提劫点 (4,5) 的另外三面，
     *    使黑(4,5) 提白后自身无气、可被白下一手回提。
     *    白(4,4) 只剩 (4,5) 一口气（被打吃状态）。
     */
    private static void assertStaleAiResultRejected(GoAI.MoveResult result,
                                                    java.util.function.Consumer<GoGame> invalidate)
            throws Exception {
        try (GoGame game = GoGame.rulesOnly()) {
            game.setAiMode(true);
            CountDownLatch entered = new CountDownLatch(1);
            CountDownLatch release = new CountDownLatch(1);
            game.setAiEngine(new GoAI() {
                @Override public int[] getBestMove(GoGame ignored) { return null; }
                @Override public MoveResult getBestMoveResult(GoGame ignored) {
                    entered.countDown();
                    try {
                        if (!release.await(5, TimeUnit.SECONDS)) return MoveResult.error();
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                        return MoveResult.error();
                    }
                    return result;
                }
            });

            AtomicReference<GoGame.AiMoveComputation> computed = new AtomicReference<>();
            Thread worker = new Thread(() -> computed.set(game.computeAiMoveComputation()));
            worker.start();
            assertTrue(entered.await(5, TimeUnit.SECONDS));
            invalidate.accept(game);
            release.countDown();
            worker.join(5_000L);
            assertFalse(worker.isAlive());

            int historyBeforeApply = game.moveHistorySize();
            assertFalse(game.applyAiMoveComputation(computed.get()));
            assertEquals(historyBeforeApply, game.moveHistorySize());
            assertEquals(GoPlayer.NONE, game.getResignedPlayer());
        }
    }

    private static long key(int x, int y) {
        return ((long) x << 32) | (y & 0xffffffffL);
    }

    private static void placeKoSetup(GoGame game) {
        assertTrue(game.placeStone(3, 4), "黑(3,4)");
        assertTrue(game.placeStone(4, 4), "白(4,4) ← 劫形中心（被打吃）");
        assertTrue(game.placeStone(5, 4), "黑(5,4)");
        assertTrue(game.placeStone(3, 5), "白(3,5)");
        assertTrue(game.placeStone(4, 3), "黑(4,3)");
        assertTrue(game.placeStone(5, 5), "白(5,5)");
        assertTrue(game.placeStone(10, 10), "黑(10,10) ← 隔空落子保持回合");
        assertTrue(game.placeStone(4, 6), "白(4,6) ← 包住提劫点右侧");
    }
}