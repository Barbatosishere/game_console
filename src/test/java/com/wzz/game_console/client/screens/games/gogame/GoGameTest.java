package com.wzz.game_console.client.screens.games.gogame;

import org.junit.jupiter.api.Test;
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
    void testResignation() {
        GoGame game = new GoGame();
        assertFalse(game.isGameOver(), "开局未结束");
        game.resign();
        assertTrue(game.isGameOver(), "认输后游戏应结束");
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