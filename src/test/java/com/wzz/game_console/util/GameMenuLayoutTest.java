package com.wzz.game_console.util;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class GameMenuLayoutTest {
    @Test void translatedControlsFitNarrowWindowsAndTheirOwnHitboxes() {
        for (int width : new int[]{240, 320, 480, 960}) {
            var buttons = GameMenuLayout.wrap(width, 48, 14, 4, new int[]{34,46,52,52,52,100});
            assertEquals(6, buttons.size());
            for (var button : buttons) {
                assertTrue(button.x() >= 0 && button.x() + button.width() <= width);
                assertTrue(button.contains(button.x() + button.width() / 2.0, button.y() + 7));
                assertFalse(button.contains(button.x() + button.width(), button.y()));
                for (var other : buttons) if (button != other) {
                    assertFalse(other.contains(button.x() + button.width() / 2.0, button.y() + 7));
                }
            }
            assertTrue(buttons.get(5).y() >= buttons.get(0).y());
        }
    }
    @Test void overflowingRowWrapsAndEachRowIsCentered() {
        var buttons = GameMenuLayout.wrap(240,48,14,4,new int[]{100,100,100});
        assertEquals(18, buttons.get(0).x());
        assertEquals(122, buttons.get(1).x());
        assertEquals(70, buttons.get(2).x());
        assertEquals(66, buttons.get(2).y());
    }
}
