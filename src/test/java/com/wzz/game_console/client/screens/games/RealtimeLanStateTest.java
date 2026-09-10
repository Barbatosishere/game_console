package com.wzz.game_console.client.screens.games;

import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

class RealtimeLanStateTest {
    private static final UUID FIRST = UUID.fromString("11111111-1111-1111-1111-111111111111");
    private static final UUID SECOND = UUID.fromString("22222222-2222-2222-2222-222222222222");
    private static final UUID THIRD = UUID.fromString("33333333-3333-3333-3333-333333333333");

    @Test
    void laterSnapshotRecoversWhenRestartNotificationIsLost() {
        RealtimeLanState.Receiver receiver = new RealtimeLanState.Receiver();

        assertNotNull(receiver.receive(state(FIRST, 1, colorPayload(1)), RealtimeLanState::parseColor));
        RealtimeLanState.Received<RealtimeLanState.Color> recovered =
                receiver.receive(state(SECOND, 3, colorPayload(2)), RealtimeLanState::parseColor);

        assertNotNull(recovered);
        assertTrue(recovered.newRound());
        assertEquals(2, recovered.snapshot().score1());
        assertFalse(receiver.restart("RESTART|" + SECOND + "|2"),
                "迟到的重开通知不能重置已经接受的新局快照");
    }

    @Test
    void restartReservesSequenceAndSupportsConsecutiveRounds() {
        RealtimeLanState.Receiver receiver = new RealtimeLanState.Receiver();
        assertNotNull(receiver.receive(state(FIRST, 1, colorPayload(1)), RealtimeLanState::parseColor));

        assertTrue(receiver.restart("RESTART|" + SECOND + "|2"));
        assertNotNull(receiver.receive(state(SECOND, 3, colorPayload(2)), RealtimeLanState::parseColor));

        assertTrue(receiver.restart("RESTART|" + THIRD + "|4"));
        assertNotNull(receiver.receive(state(THIRD, 5, colorPayload(3)), RealtimeLanState::parseColor));
        assertNull(receiver.receive(state(SECOND, 6, colorPayload(4)), RealtimeLanState::parseColor));
    }

    @Test
    void malformedSnapshotDoesNotConsumeSessionOrSequence() {
        RealtimeLanState.Receiver receiver = new RealtimeLanState.Receiver();
        assertNull(receiver.receive(state(FIRST, 1, "not-a-color-state"), RealtimeLanState::parseColor));
        assertNotNull(receiver.receive(state(FIRST, 1, colorPayload(1)), RealtimeLanState::parseColor));

        assertNull(receiver.receive(state(FIRST, 2, "0,0,0"), RealtimeLanState::parseColor));
        assertNotNull(receiver.receive(state(FIRST, 2, colorPayload(2)), RealtimeLanState::parseColor));
    }

    @Test
    void legacyStateIsAcceptedOnlyBeforeVersionedSession() {
        RealtimeLanState.Receiver receiver = new RealtimeLanState.Receiver();
        assertNotNull(receiver.receive(colorPayload(1), RealtimeLanState::parseColor));
        assertNotNull(receiver.receive(state(FIRST, 1, colorPayload(2)), RealtimeLanState::parseColor));
        assertNull(receiver.receive(colorPayload(3), RealtimeLanState::parseColor));
    }

    @Test
    void inputIsBoundToCurrentRound() {
        RealtimeLanState.Receiver receiver = new RealtimeLanState.Receiver();
        assertNotNull(receiver.receive(state(FIRST, 1, colorPayload(1)), RealtimeLanState::parseColor));

        assertEquals("INPUT|" + FIRST + "|0", receiver.input("0"));
        assertEquals("0", RealtimeLanState.decodeInput(FIRST, receiver.input("0")));
        assertNull(RealtimeLanState.decodeInput(SECOND, receiver.input("0")));
        assertNull(RealtimeLanState.decodeInput(FIRST, "0"));
    }

    @Test
    void colorParserKeepsGridIndexesAlignedAndRejectsInvalidFields() {
        String[] cells = new String[256];
        java.util.Arrays.fill(cells, "0");
        cells[17] = "invalid";
        cells[18] = "6";
        RealtimeLanState.Color parsed = RealtimeLanState.parseColor(
                "1,2,0,3,4,1,5,6,7,8,0;" + String.join(",", cells));
        assertNotNull(parsed);
        assertEquals(0, parsed.grid()[1][1]);
        assertNull(RealtimeLanState.parseColor(
                "1,2,0,3,4,1,5,-1,7,8,0;" + String.join(",", cells)));
        assertNull(RealtimeLanState.parseColor(
                "1,2,2,3,4,1,5,6,7,8,0;" + String.join(",", cells)));
        assertNull(RealtimeLanState.parseColor("1,2,0,3,4,1,5,6,7,8,0;0"));
    }

    @Test
    void iceParserValidatesRoundStateAndRemovedDiamonds() {
        RealtimeLanState.Ice parsed = RealtimeLanState.parseIce(
                "1,20,30,1,0,40,50,1,0,1,3,0,0,2;1_2|19_14", 1);
        assertNotNull(parsed);
        assertEquals(2, parsed.difficulty());
        assertEquals(2, parsed.removedDiamonds().size());
        assertNull(RealtimeLanState.parseIce("1,20,30,1,0,40,50,1,0,1,3,0,1,2", 1));
        assertNull(RealtimeLanState.parseIce("1,-1,30,1,0,40,50,1,0,1,3,0,0,2", 1));
        assertNull(RealtimeLanState.parseIce("1,20,30,1,0,40,50,1,0,1,3,0,0,2;20_14", 1));
    }

    @Test
    void consecutiveRestartsBeforeAnySnapshotKeepOnlyNewestRound() {
        RealtimeLanState.Receiver receiver = new RealtimeLanState.Receiver();
        assertNull(receiver.input("0"));
        assertTrue(receiver.restart("RESTART|" + FIRST + "|1"));
        assertTrue(receiver.restart("RESTART|" + SECOND + "|2"));
        assertTrue(receiver.restart("RESTART|" + THIRD + "|3"));
        assertFalse(receiver.restart("RESTART|" + FIRST + "|4"));
        assertFalse(receiver.restart("RESTART|" + SECOND + "|5"));
        assertFalse(receiver.restart("RESTART|" + THIRD + "|6"));
        assertNull(receiver.receive(state(SECOND, 4, colorPayload(1)), RealtimeLanState::parseColor));
        assertNotNull(receiver.receive(state(THIRD, 4, colorPayload(2)), RealtimeLanState::parseColor));
        assertEquals("0", RealtimeLanState.decodeInput(THIRD, receiver.input("0")));
    }

    @Test
    void invalidHigherSnapshotDoesNotRetireCurrentRoundOrConsumeSequence() {
        RealtimeLanState.Receiver receiver = new RealtimeLanState.Receiver();
        assertNotNull(receiver.receive(state(FIRST, 10, colorPayload(1)), RealtimeLanState::parseColor));
        assertNull(receiver.receive(state(SECOND, 100, "bad"), RealtimeLanState::parseColor));
        assertNotNull(receiver.receive(state(FIRST, 11, colorPayload(2)), RealtimeLanState::parseColor));
        assertNotNull(receiver.receive(state(SECOND, 12, colorPayload(3)), RealtimeLanState::parseColor));
        assertNull(receiver.receive(state(THIRD, 11, colorPayload(1)), RealtimeLanState::parseColor));
        assertNull(receiver.receive(state(SECOND, 12, colorPayload(3)), RealtimeLanState::parseColor));
        assertNull(receiver.receive(state(FIRST, 1000, colorPayload(4)), RealtimeLanState::parseColor));
    }

    @Test
    void malformedEnvelopeAndRestartDoNotBindReceiver() {
        RealtimeLanState.Receiver receiver = new RealtimeLanState.Receiver();
        for (String invalid : new String[]{"RESTART|bad|1", "RESTART|" + FIRST + "|-1",
                "RESTART|" + FIRST + "|invalid", "RESTART|" + FIRST, "RESTART|" + FIRST + "|1|extra"}) {
            assertFalse(receiver.restart(invalid));
        }
        for (String invalid : new String[]{"v2|bad|1|", "v2|" + FIRST + "|0|",
                "v2|" + FIRST + "|9223372036854775808|"}) {
            assertNull(receiver.receive(invalid + colorPayload(1), RealtimeLanState::parseColor));
        }
        assertNull(receiver.input("0"));
        assertNotNull(receiver.receive(state(SECOND, 1, colorPayload(1)), RealtimeLanState::parseColor));
        assertFalse(receiver.restart("RESTART"));
        assertNull(RealtimeLanState.decodeInput(SECOND, "INPUT|bad|0"));
        assertNull(RealtimeLanState.decodeInput(SECOND, "INPUT|" + SECOND + "|"));
    }

    @Test
    void iceSnapshotsAllowAboveMapJumpAndFinalFallingStep() {
        RealtimeLanState.Ice jumping = RealtimeLanState.parseIce(
                "1,20,-500,0,0,40,50,1,0,1,3,0,0,0;1_2", 1);
        assertNotNull(jumping);
        assertEquals(-50f, jumping.iceY());
        assertEquals(0, jumping.difficulty());
        RealtimeLanState.Ice falling = RealtimeLanState.parseIce(
                "1,20,2700,0,1,40,50,1,0,1,3,1,0,2;1_2", 1);
        assertNotNull(falling);
        assertTrue(falling.gameOver());
        assertTrue(falling.iceDead());
        assertEquals(270f, falling.iceY());
        assertNull(RealtimeLanState.parseIce("1,20,2147483647,0,1,40,50,1,0,1,3,1,0,2", 1));
    }

    @Test
    void iceMalformedFieldsNeverCommitReceiverState() {
        RealtimeLanState.Receiver receiver = new RealtimeLanState.Receiver();
        String[] fields = "1,20,30,1,0,40,50,1,0,1,3,0,0,2".split(",");
        for (int index : new int[]{0, 1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11, 12, 13}) {
            String[] invalid = fields.clone();
            invalid[index] = "invalid";
            assertNull(receiver.receive(state(FIRST, 100, String.join(",", invalid)),
                    payload -> RealtimeLanState.parseIce(payload, 1)));
        }
        assertNotNull(receiver.receive(state(SECOND, 1, String.join(",", fields)),
                payload -> RealtimeLanState.parseIce(payload, 1)));
        assertNull(RealtimeLanState.parseIce(null, 1));
        assertNull(RealtimeLanState.parseColor(null));
    }

    private static String state(UUID session, long sequence, String payload) {
        return "v2|" + session + "|" + sequence + "|" + payload;
    }

    private static String colorPayload(int score) {
        String[] cells = new String[256];
        java.util.Arrays.fill(cells, "0");
        return "1,2,0,3,4,0,5," + score + ",7,1,0;" + String.join(",", cells);
    }
}
