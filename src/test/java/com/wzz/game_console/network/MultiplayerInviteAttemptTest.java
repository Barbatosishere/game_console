package com.wzz.game_console.network;

import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

class MultiplayerInviteAttemptTest {
    @Test
    void roundTripAndExactMatchingUseOneAttemptNonce() {
        UUID nonce = UUID.fromString("12345678-1234-5678-9abc-def012345678");

        String encoded = MultiplayerInviteAttempt.encode(nonce);

        assertEquals(nonce, MultiplayerInviteAttempt.parse(encoded));
        assertTrue(MultiplayerInviteAttempt.matches(encoded, nonce));
        assertFalse(MultiplayerInviteAttempt.matches(encoded, UUID.randomUUID()));
    }

    @Test
    void malformedAndLegacyControlDataAreRejected() {
        assertNull(MultiplayerInviteAttempt.parse(null));
        assertNull(MultiplayerInviteAttempt.parse(""));
        assertNull(MultiplayerInviteAttempt.parse("busy"));
        assertNull(MultiplayerInviteAttempt.parse("INV1|bad"));
        assertNull(MultiplayerInviteAttempt.parse("INV1|" + UUID.randomUUID() + "|extra"));
        assertFalse(MultiplayerInviteAttempt.matches("INV1|bad", UUID.randomUUID()));
    }

    @Test
    void encodingRequiresNonce() {
        assertThrows(IllegalArgumentException.class, () -> MultiplayerInviteAttempt.encode(null));
    }
}
