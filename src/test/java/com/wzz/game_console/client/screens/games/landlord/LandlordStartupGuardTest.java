package com.wzz.game_console.client.screens.games.landlord;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class LandlordStartupGuardTest {
    @Test
    void hostSendsOnScheduleThenAbortsAfterFinalAckWindow() {
        assertEquals(LandlordStartupGuard.HostAction.NONE,
                LandlordStartupGuard.hostAction(4, Long.MIN_VALUE, 0));
        assertEquals(LandlordStartupGuard.HostAction.SEND,
                LandlordStartupGuard.hostAction(5, Long.MIN_VALUE, 0));
        assertEquals(LandlordStartupGuard.HostAction.NONE,
                LandlordStartupGuard.hostAction(104, 85, 5));
        assertEquals(LandlordStartupGuard.HostAction.ABORT,
                LandlordStartupGuard.hostAction(105, 85, 5));
    }

    @Test
    void hardTimeoutAbortsHostEvenBeforeAnotherRetryBoundary() {
        assertEquals(LandlordStartupGuard.HostAction.ABORT,
                LandlordStartupGuard.hostAction(LandlordStartupGuard.TIMEOUT_TICKS, 199, 1));
    }

    @Test
    void clientTimeoutOnlyAppliesWhileWaitingForInit() {
        assertFalse(LandlordStartupGuard.clientTimedOut(true,
                LandlordStartupGuard.TIMEOUT_TICKS - 1));
        assertTrue(LandlordStartupGuard.clientTimedOut(true,
                LandlordStartupGuard.TIMEOUT_TICKS));
        assertFalse(LandlordStartupGuard.clientTimedOut(false,
                LandlordStartupGuard.TIMEOUT_TICKS));
    }
}
