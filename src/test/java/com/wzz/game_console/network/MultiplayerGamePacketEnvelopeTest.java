package com.wzz.game_console.network;

import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

class MultiplayerGamePacketEnvelopeTest {

    private static final UUID SESSION = UUID.fromString("12345678-1234-5678-9abc-def012345678");

    @Test
    void roundTripPreservesMetadataAndBody() {
        String encoded = MultiplayerGameDataEnvelope.encode(
                MultiplayerGameDataEnvelope.of(SESSION, 42L, "落子|RESTART|测试"));
        MultiplayerGameDataEnvelope.Value decoded = MultiplayerGameDataEnvelope.parse(encoded);

        assertNotNull(decoded);
        assertFalse(decoded.legacy());
        assertEquals(SESSION, decoded.sessionId());
        assertEquals(42L, decoded.sequence());
        assertEquals("落子|RESTART|测试", decoded.body());
    }

    @Test
    void emptyBodyRoundTrips() {
        MultiplayerGameDataEnvelope.Value decoded = MultiplayerGameDataEnvelope.parse(
                MultiplayerGameDataEnvelope.encode(MultiplayerGameDataEnvelope.of(SESSION, 0L, "")));

        assertNotNull(decoded);
        assertEquals("", decoded.body());
        assertFalse(decoded.legacy());
    }

    @Test
    void bareAndNullDataRemainLegacyCompatible() {
        MultiplayerGameDataEnvelope.Value bare = MultiplayerGameDataEnvelope.parse("3,4");
        MultiplayerGameDataEnvelope.Value nullData = MultiplayerGameDataEnvelope.parse(null);

        assertTrue(bare.legacy());
        assertEquals("3,4", bare.body());
        assertNull(bare.sessionId());
        assertEquals(-1L, bare.sequence());
        assertTrue(nullData.legacy());
        assertEquals("", nullData.body());
    }

    @Test
    void malformedMgp1DataIsRejected() {
        assertNull(MultiplayerGameDataEnvelope.parse("MGP1|"));
        assertNull(MultiplayerGameDataEnvelope.parse("MGP1|bad-uuid|1|YQ"));
        assertNull(MultiplayerGameDataEnvelope.parse("MGP1|" + SESSION + "|-1|YQ"));
        assertNull(MultiplayerGameDataEnvelope.parse("MGP1|" + SESSION + "|x|YQ"));
        assertNull(MultiplayerGameDataEnvelope.parse("MGP1|" + SESSION + "|1|%%%"));
        assertNull(MultiplayerGameDataEnvelope.parse("MGP1|" + SESSION + "|1|YQ|extra"));
    }

    @Test
    void oversizedBodyTruncatesInsteadOfThrowing() {
        // 中英混排 + 表情，验证按码点边界截断（不产生半个字符）
        String oversizedBody = ("落子ABC" + "x").repeat(6_000) + "😀😀😀";

        String encoded = assertDoesNotThrow(() -> MultiplayerGameDataEnvelope.encode(
                MultiplayerGameDataEnvelope.of(SESSION, 0L, oversizedBody)));

        assertTrue(encoded.getBytes(java.nio.charset.StandardCharsets.UTF_8).length <= 32_767,
                "encoded envelope must fit MAX_DATA_BYTES");

        MultiplayerGameDataEnvelope.Value decoded = MultiplayerGameDataEnvelope.parse(encoded);
        assertNotNull(decoded);
        assertFalse(decoded.legacy());
        assertEquals(SESSION, decoded.sessionId());
        assertEquals(0L, decoded.sequence());
        assertTrue(oversizedBody.startsWith(decoded.body()), "truncated body must be a strict prefix");
        assertFalse(decoded.body().isEmpty());
        // 截断不得劈开代理对
        String tail = decoded.body().substring(decoded.body().length() - 2);
        assertFalse(Character.isHighSurrogate(tail.charAt(0)) && !Character.isLowSurrogate(tail.charAt(1)),
                "truncation must respect code-point boundaries");
    }

    @Test
    void bodyJustUnderBudgetRoundTripsExactly() {
        String body = "x".repeat(24_540);
        String encoded = MultiplayerGameDataEnvelope.encode(MultiplayerGameDataEnvelope.of(SESSION, 7L, body));
        assertTrue(encoded.getBytes(java.nio.charset.StandardCharsets.UTF_8).length <= 32_767);
        MultiplayerGameDataEnvelope.Value decoded = MultiplayerGameDataEnvelope.parse(encoded);
        assertNotNull(decoded);
        assertEquals(body, decoded.body());
    }

    @Test
    void factoryRejectsInvalidMetadata() {
        assertThrows(IllegalArgumentException.class,
                () -> MultiplayerGameDataEnvelope.of(null, 0L, "body"));
        assertThrows(IllegalArgumentException.class,
                () -> MultiplayerGameDataEnvelope.of(SESSION, -1L, "body"));
    }
}
