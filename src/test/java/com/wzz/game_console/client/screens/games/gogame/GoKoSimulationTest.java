package com.wzz.game_console.client.screens.games.gogame;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 围棋劫争（全局同型禁止 / positional super-ko）与提子时序无头模拟。
 * <p>
 * 劫形：黑孤子 X=(4,4) 的三面是白 (3,4),(5,4),(4,3)，唯一气是 Y=(4,5)；
 * 白孤子 Y=(4,5) 的三面是黑 (3,5),(5,5),(4,6)，唯一气是 X 落点。
 * 两颗单气子互咬，X/Y 两点成为劫争焦点。super-ko（positionHistory 全历史
 * hash 比对）语义验证：
 *  - "先提对方、再判自杀"：白落 Y 提黑 X 是合法着，不是自杀
 *  - 无劫材的立即回提 → 局面重现历史 → 拒绝，且拒绝不翻转轮次
 *  - 完整劫争循环：每方提劫前都须先下出新棋（劫材），
 *    否则提劫后的局面会与己方上一手前的局面同型而被拒
 */
@Timeout(60)
class GoKoSimulationTest {

    @Test
    void superKoRejectsImmediateRecaptureButAllowsKoThreatFirst() throws Exception {
        try (GoGame g = GoGame.rulesOnly()) {
            // 布子：X 的三面白、Y 的三面黑，黑白交替落下（黑先）
            int[][] build = {
                    {3, 5}, {3, 4},   // B(Y邻) W(X邻)
                    {5, 5}, {5, 4},   // B      W
                    {4, 6}, {4, 3},   // B      W
            };
            for (int[] s : build) {
                assertTrue(g.placeStone(s[0], s[1]), "布子手 (" + s[0] + "," + s[1] + ") 应成功");
            }

            // 黑落 X：唯一气=Y，存活
            assertTrue(g.placeStone(4, 4), "黑落 X 应成功");
            assertEquals(GoPlayer.BLACK, g.getStone(4, 4));

            // 白落 Y：先提无气的黑 X，白子自身借提子获得 1 气 → 合法
            assertTrue(g.placeStone(4, 5), "白落 Y 应先提对方获得气（不得误判自杀）");
            assertEquals(GoPlayer.NONE, g.getStone(4, 4), "黑 X 应被提走");
            assertEquals(GoPlayer.WHITE, g.getStone(4, 5), "白 Y 应留在盘上");

            // 黑无劫材立即回提：局面与 X 被提前完全相同 → super-ko 拒绝
            assertFalse(g.placeStone(4, 4), "无劫材的立即回提必须被 super-ko 拒绝");
            assertEquals(GoPlayer.NONE, g.getStone(4, 4), "被拒的落子不得改变棋盘");
            assertEquals(GoPlayer.WHITE, g.getStone(4, 5), "白 Y 应原样保留");
            assertEquals(GoPlayer.BLACK, g.getCurrentPlayer(), "拒绝后轮次不得翻转（仍黑）");

            // 黑找劫材、白应劫 → 黑提劫（提白 Y）合法：劫材改变全局盘面
            assertTrue(g.placeStone(16, 16), "黑劫材应成功");
            assertTrue(g.placeStone(0, 0), "白应劫应成功");
            assertTrue(g.placeStone(4, 4), "劫材之后黑提劫应合法");
            assertEquals(GoPlayer.NONE, g.getStone(4, 5), "白 Y 应被提走");
            assertEquals(GoPlayer.BLACK, g.getStone(4, 4));

            // 白回提前也必须先找新劫材——直接回提会重现"白应劫后"的局面
            assertTrue(g.placeStone(1, 1), "白新劫材应成功");
            assertTrue(g.placeStone(18, 18), "黑应劫应成功");
            assertTrue(g.placeStone(4, 5), "白回提应合法");
            assertEquals(GoPlayer.NONE, g.getStone(4, 4), "黑 X 应被提走");
            assertEquals(GoPlayer.WHITE, g.getStone(4, 5));

            // 黑再次无劫材立即回提 → 重现黑提劫后局面 → 再次拒绝
            assertFalse(g.placeStone(4, 4), "再次无劫材的立即回提仍须被 super-ko 拒绝");
            assertEquals(GoPlayer.BLACK, g.getCurrentPlayer(), "拒绝后轮次不得翻转（仍黑）");
            assertFalse(g.isGameOver(), "劫争往来不应终局");
        }
    }
}
