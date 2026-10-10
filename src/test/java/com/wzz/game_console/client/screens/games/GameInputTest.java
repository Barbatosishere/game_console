package com.wzz.game_console.client.screens.games;

import org.junit.jupiter.api.Test;
import java.util.Map;
import static com.wzz.game_console.client.screens.games.GameInput.Action.*;
import static org.junit.jupiter.api.Assertions.*;

class GameInputTest {
    private GameInput input() { return new GameInput(Map.of(1, LEFT, 2, LEFT, 3, PRIMARY, 4, RESTART)); }

    @Test void operatingSystemRepeatDoesNotRetriggerDiscreteActions() {
        GameInput input = input();
        assertEquals(PRIMARY, input.press(3));
        for (int i = 0; i < 100; i++) assertNull(input.press(3));
        input.release(3);
        assertEquals(PRIMARY, input.press(3));
    }

    @Test void releasingOneAliasKeepsTheOtherHeld() {
        GameInput input = input();
        assertEquals(LEFT, input.press(1));
        assertNull(input.press(2));
        input.release(1);
        assertTrue(input.isHeld(LEFT));
        input.release(2);
        assertFalse(input.isHeld(LEFT));
        input.release(2);
        assertEquals(LEFT, input.press(2));
    }

    @Test void heldMovementUsesTicksAndDoesNotDependOnKeyRepeatFrequency() {
        GameInput frequent = input(), quiet = input();
        frequent.press(1); quiet.press(1);
        for (int tick = 1; tick <= 30; tick++) {
            for (int repeat = 0; repeat < 15; repeat++) frequent.press(1);
            frequent.tick(); quiet.tick();
            boolean expected = tick >= 6 && (tick - 6) % 2 == 0;
            assertEquals(expected, frequent.repeats(LEFT, 6, 2));
            assertEquals(expected, quiet.repeats(LEFT, 6, 2));
        }
    }

    @Test void pauseAndRestartBlockHeldKeysUntilRelease() {
        GameInput input = input();
        input.press(1); input.press(4);
        input.clear();
        for (int i = 0; i < 100; i++) {
            input.tick();
            assertNull(input.press(1));
            assertNull(input.press(4));
            assertFalse(input.repeats(LEFT, 6, 2));
        }
        input.release(1); input.release(4);
        assertEquals(LEFT, input.press(1));
        assertEquals(RESTART, input.press(4));
    }

    @Test void releasesDuringFocusLossDoNotLeaveKeysPermanentlyBlocked() {
        GameInput input = input();
        input.press(1); input.press(3); input.clear();
        input.clearReleasedBlocks(key -> key == 3);
        assertEquals(LEFT, input.press(1));
        assertNull(input.press(3));
        input.clearReleasedBlocks(key -> false);
        assertEquals(PRIMARY, input.press(3));
    }

    @Test void repeatDelayResetsAfterTheLastAliasIsReleased() {
        GameInput input = input();
        input.press(1);
        for (int i = 0; i < 6; i++) input.tick();
        assertTrue(input.repeats(LEFT, 6, 2));
        input.release(1); input.press(2); input.tick();
        assertFalse(input.repeats(LEFT, 6, 2));
        assertNull(input.press(999));
        assertFalse(input.release(999));
        assertThrows(IllegalArgumentException.class, () -> input.repeats(LEFT, 0, 2));
    }
}
