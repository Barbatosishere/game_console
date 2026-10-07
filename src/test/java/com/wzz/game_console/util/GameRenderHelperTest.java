package com.wzz.game_console.util;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertSame;

class GameRenderHelperTest {
    @Test
    void cachedCircleScanlinesKeepTheOriginalPixelShape() {
        assertArrayEquals(new int[]{0}, GameRenderHelper.circleHalfWidths(0));
        assertArrayEquals(new int[]{0, 2, 2, 3, 2, 2, 0}, GameRenderHelper.circleHalfWidths(3));
        assertSame(GameRenderHelper.circleHalfWidths(3), GameRenderHelper.circleHalfWidths(3));
    }
}
