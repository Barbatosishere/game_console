package com.wzz.game_console.client.screens.games.landlord;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;

import static org.junit.jupiter.api.Assertions.*;

class LandlordLanStateTest {
    @Test
    void validInitBindsSeatAndState() {
        LandlordGame host = new LandlordGame();
        LandlordGame client = new LandlordGame();
        LandlordLanState.Receiver receiver = new LandlordLanState.Receiver();
        String init = LandlordGame.encodeNetworkState(11L, 0L,
                "INIT:2|" + host.serializeFor(2));

        LandlordLanState.Applied applied = receiver.receive(client, init);

        assertNotNull(applied);
        assertTrue(applied.init());
        assertEquals(2, applied.seat());
        assertEquals(11L, applied.token());
        assertEquals(0L, applied.sequence());
        assertEquals(host.getGameState(), client.getGameState());
    }

    @Test
    void malformedInitDoesNotBindOrMutateClient() {
        LandlordGame client = new LandlordGame();
        LandlordLanState.Receiver receiver = new LandlordLanState.Receiver();
        String before = client.serializeFor(0);

        assertNull(receiver.receive(client, LandlordGame.encodeNetworkState(12L, 0L, "INIT:2|bad")));
        assertEquals(before, client.serializeFor(0));
        assertNotNull(receiver.receive(client, LandlordGame.encodeNetworkState(12L, 0L,
                "INIT:1|" + new LandlordGame().serializeFor(1))));
        assertNull(receiver.receive(client, LandlordGame.encodeNetworkState(12L, 1L,
                "INIT:2|" + new LandlordGame().serializeFor(2))));
    }

    @Test
    void stateCannotStartOrSwitchRound() {
        LandlordGame client = new LandlordGame();
        LandlordLanState.Receiver receiver = new LandlordLanState.Receiver();
        assertNull(receiver.receive(client, LandlordGame.encodeNetworkState(20L, 0L,
                "STATE:" + new LandlordGame().serializeFor(1))));

        assertNotNull(receiver.receive(client, LandlordGame.encodeNetworkState(20L, 0L,
                "INIT:1|" + new LandlordGame().serializeFor(1))));
        assertNull(receiver.receive(client, LandlordGame.encodeNetworkState(21L, 1L,
                "STATE:" + new LandlordGame().serializeFor(1))));
    }

    @Test
    void duplicateAndRetiredRoundsAreRejected() {
        LandlordGame client = new LandlordGame();
        LandlordLanState.Receiver receiver = new LandlordLanState.Receiver();
        String first = LandlordGame.encodeNetworkState(30L, 0L,
                "INIT:1|" + new LandlordGame().serializeFor(1));
        assertNotNull(receiver.receive(client, first));
        assertNull(receiver.receive(client, first));

        String second = LandlordGame.encodeNetworkState(31L, 1L,
                "INIT:1|" + new LandlordGame().serializeFor(1));
        assertNotNull(receiver.receive(client, second));
        assertNull(receiver.receive(client, LandlordGame.encodeNetworkState(30L, 2L,
                "INIT:1|" + new LandlordGame().serializeFor(1))));
    }

    @Test
    void malformedNewRoundAndStateLeaveSnapshotAndSequenceUnchanged() {
        LandlordGame host = new LandlordGame();
        LandlordGame client = new LandlordGame();
        LandlordLanState.Receiver receiver = new LandlordLanState.Receiver();
        assertNotNull(receiver.receive(client, LandlordGame.encodeNetworkState(35L, 0L,
                "INIT:1|" + host.serializeFor(1))));
        String before = client.serializeFor(1);
        assertNull(receiver.receive(client, LandlordGame.encodeNetworkState(36L, 100L, "INIT:1|bad")));
        assertNull(receiver.receive(client, LandlordGame.encodeNetworkState(35L, 100L, "STATE:bad")));
        assertEquals(before, client.serializeFor(1));
        assertTrue(host.bid(0, true));
        assertNotNull(receiver.receive(client, LandlordGame.encodeNetworkState(35L, 1L,
                "STATE:" + host.serializeFor(1))));
        assertEquals(host.serializeFor(1), client.serializeFor(1));
    }

    @Test
    void hostRestartPreservesScoresAndReinitializesBothClientSeats() {
        LandlordGame host = new LandlordGame();
        String[] fields = host.serializeFor(0).split("\\|", -1);
        fields[7] = "20,-10,-10";
        assertTrue(host.applyState(String.join("|", fields), 0, new ArrayList<>()));
        host.restart();
        LandlordGame[] clients = {new LandlordGame(), new LandlordGame()};
        LandlordLanState.Receiver[] receivers = {
                new LandlordLanState.Receiver(), new LandlordLanState.Receiver()};
        for (int i = 0; i < clients.length; i++) {
            assertNotNull(receivers[i].receive(clients[i], LandlordGame.encodeNetworkState(37L, i,
                    "INIT:" + (i + 1) + "|" + host.serializeFor(i + 1))));
        }
        assertTrue(host.bid(0, true));
        host.restart();
        assertArrayEquals(new int[]{20, -10, -10}, host.getScores());
        for (int i = 0; i < clients.length; i++) {
            assertNotNull(receivers[i].receive(clients[i], LandlordGame.encodeNetworkState(38L, i + 2L,
                    "INIT:" + (i + 1) + "|" + host.serializeFor(i + 1))));
            assertEquals(host.serializeFor(i + 1), clients[i].serializeFor(i + 1));
            assertEquals(17, clients[i].getPlayerHand(i + 1).size());
        }
    }

    @Test
    void actionEnvelopeRequiresCurrentRoundAndKnownAction() {
        String action = "BID:1:1";
        String encoded = LandlordLanState.encodeAction(41L, action);
        assertEquals(action, LandlordLanState.decodeAction(41L, encoded));
        assertNull(LandlordLanState.decodeAction(42L, encoded));
        assertNull(LandlordLanState.decodeAction(41L, action));
        assertNull(LandlordLanState.decodeAction(41L,
                LandlordGame.encodeNetworkState(41L, 0L, "INIT:1|x")));
    }

    @Test
    void retryInitWithHigherSequenceAppliesLatestSnapshot() {
        LandlordGame first = new LandlordGame();
        LandlordGame client = new LandlordGame();
        LandlordLanState.Receiver receiver = new LandlordLanState.Receiver();
        assertNotNull(receiver.receive(client, LandlordGame.encodeNetworkState(50L, 0L,
                "INIT:1|" + first.serializeFor(1))));

        assertTrue(first.bid(0, true));
        String retry = LandlordGame.encodeNetworkState(50L, 2L,
                "INIT:1|" + first.serializeFor(1));
        assertNotNull(receiver.receive(client, retry));
        assertEquals(first.getGameState(), client.getGameState());
        assertEquals(first.getCurrentPlayer(), client.getCurrentPlayer());
    }
}
