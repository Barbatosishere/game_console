package com.wzz.game_console.client.screens.games.landlord;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class LandlordNetworkStateTest {

    @Test
    void roundTripPreservesUnsignedTokenSequenceAndPayload() {
        String encoded = LandlordGame.encodeNetworkState(-1L, Long.MAX_VALUE, "STATE:a|b|c");
        LandlordGame.NetworkState decoded = LandlordGame.decodeNetworkState(encoded);

        assertNotNull(decoded);
        assertEquals(-1L, decoded.sessionToken());
        assertEquals(Long.MAX_VALUE, decoded.sequence());
        assertEquals("STATE:a|b|c", decoded.payload());
    }

    @Test
    void zeroSequenceRoundTrips() {
        LandlordGame.NetworkState decoded = LandlordGame.decodeNetworkState(
                LandlordGame.encodeNetworkState(1L, 0L, "INIT:data"));

        assertNotNull(decoded);
        assertEquals(0L, decoded.sequence());
    }

    @Test
    void encoderRejectsInvalidMetadataAndPayload() {
        assertThrows(IllegalArgumentException.class,
                () -> LandlordGame.encodeNetworkState(0L, 0L, "STATE:x"));
        assertThrows(IllegalArgumentException.class,
                () -> LandlordGame.encodeNetworkState(1L, -1L, "STATE:x"));
        assertThrows(IllegalArgumentException.class,
                () -> LandlordGame.encodeNetworkState(1L, 0L, null));
        assertThrows(IllegalArgumentException.class,
                () -> LandlordGame.encodeNetworkState(1L, 0L, ""));
    }

    @Test
    void malformedEnvelopeIsRejected() {
        assertNull(LandlordGame.decodeNetworkState(null));
        assertNull(LandlordGame.decodeNetworkState("STATE:x"));
        assertNull(LandlordGame.decodeNetworkState("LG1|1|0"));
        assertNull(LandlordGame.decodeNetworkState("LG1|0|0|STATE:x"));
        assertNull(LandlordGame.decodeNetworkState("LG1|1|-1|STATE:x"));
        assertNull(LandlordGame.decodeNetworkState("LG1|x|0|STATE:x"));
        assertNull(LandlordGame.decodeNetworkState("LG1|1|x|STATE:x"));
        assertNull(LandlordGame.decodeNetworkState("LG1|18446744073709551616|0|STATE:x"));
        assertNull(LandlordGame.decodeNetworkState("LG1|1|9223372036854775808|STATE:x"));
        assertNull(LandlordGame.decodeNetworkState("LG1|1|0|"));
    }
}
