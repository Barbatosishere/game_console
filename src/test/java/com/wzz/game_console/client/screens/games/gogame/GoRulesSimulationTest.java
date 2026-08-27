package com.wzz.game_console.client.screens.games.gogame;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 围棋规则引擎无头模拟（纯 JDK，rulesOnly 模式，不触碰 AI/GPU）。
 * 覆盖：
 *  - 轮流落子次序交替
 *  - 紧气提子：白方围死黑一子后该子消失
 *  - 双 pass 终局、落子后局面 hash 变化
 *  - 越界/已占格拒绝落子
 */
@Timeout(60)
class GoRulesSimulationTest {

    /** 黑废子填充轮次，保持"黑垫子→白紧气"的节奏 */
    @Test
    void whiteCapturesLoneBlackStoneWhenLastLibertyFilled() {
        try (GoGame g = GoGame.rulesOnly()) {
            // 手顺：黑垫一手废子，白每手紧 (5,5) 黑子的一口气
            assertTrue(g.placeStone(5, 5), "1: 黑(5,5)");      // B
            assertTrue(g.placeStone(4, 5), "2: 白(4,5)");      // W 紧左
            assertTrue(g.placeStone(0, 0), "3: 黑废(0,0)");    // B 垫
            assertTrue(g.placeStone(6, 5), "4: 白(6,5)");      // W 紧右
            assertTrue(g.placeStone(0, 2), "5: 黑废(0,2)");    // B 垫
            assertTrue(g.placeStone(5, 4), "6: 白(5,4)");      // W 紧上
            assertTrue(g.placeStone(0, 4), "7: 黑废(0,4)");    // B 垫

            assertEquals(GoPlayer.BLACK, g.getStone(5, 5),
                    "提子前 (5,5) 应仍是黑子（尚余最后一口气在下边）");

            assertTrue(g.placeStone(5, 6), "8: 白(5,6)");      // W 紧下 → 提子
            assertEquals(GoPlayer.NONE, g.getStone(5, 5),
                    "(5,5) 黑子四口气被填满后必须被提走");
        }
    }

    @Test
    void turnAlternatesBetweenBlackAndWhite() {
        try (GoGame g = GoGame.rulesOnly()) {
            assertEquals(GoPlayer.BLACK, g.getCurrentPlayer(), "围棋黑先");
            g.placeStone(4, 4);
            assertEquals(GoPlayer.WHITE, g.getCurrentPlayer(), "落子后应轮到白");
            g.placeStone(10, 10);
            assertEquals(GoPlayer.BLACK, g.getCurrentPlayer(), "再落子应轮回黑");
        }
    }

    @Test
    void doublePassEndsGame() {
        try (GoGame g = GoGame.rulesOnly()) {
            g.pass();
            assertFalse(g.isGameOver(), "单次 pass 不应终局");
            g.pass();
            assertTrue(g.isGameOver(), "连续两次 pass 必须终局");
        }
    }

    @Test
    void hashChangesAfterPlacement() {
        try (GoGame g = GoGame.rulesOnly()) {
            long before = g.getCurrentHash();
            g.placeStone(9, 9);
            assertNotEquals(before, g.getCurrentHash(), "落子后局面 hash 必须变化");
        }
    }

    @Test
    void illegalPlacementsAreRejected() {
        try (GoGame g = GoGame.rulesOnly()) {
            assertFalse(g.canPlaceStone(-1, 5), "负坐标不得可落");
            assertFalse(g.canPlaceStone(19, 5), "越界坐标不得可落");
            assertTrue(g.placeStone(3, 3));
            assertFalse(g.placeStone(3, 3), "已占格二次落子必须失败");
            assertFalse(g.canPlaceStone(0, -100), "远端越界不得可落");
        }
    }

    @Test
    void resignEndsGame() {
        try (GoGame g = GoGame.rulesOnly()) {
            assertFalse(g.isGameOver());
            g.resign();
            assertTrue(g.isGameOver(), "认输后必须终局");
        }
    }
}
