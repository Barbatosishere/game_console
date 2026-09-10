package com.wzz.game_console.client.screens.games.gogame;

import org.junit.jupiter.api.Test;

import java.util.LinkedHashSet;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

class GoScoringProtocolTest {
    @Test
    void canonicalEncodingAndDigestAreOrderIndependent() {
        Set<Long> first = new LinkedHashSet<>(Set.of(
                GoScoringProtocol.key(10, 2), GoScoringProtocol.key(1, 18)));
        Set<Long> second = new LinkedHashSet<>(Set.of(
                GoScoringProtocol.key(1, 18), GoScoringProtocol.key(10, 2)));
        assertEquals("1,18;10,2", GoScoringProtocol.encodeMarks(first));
        assertEquals(GoScoringProtocol.digest(first), GoScoringProtocol.digest(second));
        assertEquals(first, GoScoringProtocol.parseMarks(GoScoringProtocol.encodeMarks(first)));
    }

    @Test
    void malformedAndDuplicateMarksAreRejected() {
        assertThrows(IllegalArgumentException.class, () -> GoScoringProtocol.parseMarks("1,1;1,1"));
        assertThrows(IllegalArgumentException.class, () -> GoScoringProtocol.parseMarks("19,0"));
        assertThrows(IllegalArgumentException.class, () -> GoScoringProtocol.parseMarks("1"));
        assertNull(GoScoringProtocol.parseEpoch("old-game"));
        assertNull(GoScoringProtocol.parseRevision("-1"));
    }

    @Test
    void komiMustBeFiniteAndCanonical() {
        assertEquals(7.5, GoScoringProtocol.parseKomi("7.5"));
        assertEquals(-2.5, GoScoringProtocol.parseKomi("-2.5"));
        assertEquals("7.5", GoScoringProtocol.formatKomi(7.5));
        assertNull(GoScoringProtocol.parseKomi("NaN"));
        assertNull(GoScoringProtocol.parseKomi("Infinity"));
        assertNull(GoScoringProtocol.parseKomi("100.1"));
        assertNull(GoScoringProtocol.parseKomi("7.50"));
    }

    @Test
    void digestRejectsTampering() {
        Set<Long> marks = Set.of(GoScoringProtocol.key(3, 4));
        String digest = GoScoringProtocol.digest(marks);
        assertTrue(GoScoringProtocol.hasValidDigest(marks, digest));
        assertFalse(GoScoringProtocol.hasValidDigest(Set.of(GoScoringProtocol.key(3, 5)), digest));
        assertFalse(GoScoringProtocol.hasValidDigest(marks, digest + "0"));
    }

    @Test
    void earlySnapshotsDoNotPreventLastPassAndStateBootstrap() {
        UUID peer = UUID.randomUUID();
        UUID epoch = UUID.randomUUID();
        GoScoringProtocol.Receiver receiver = new GoScoringProtocol.Receiver(true, peer, epoch,
                GoScoringProtocol.Phase.PLAYING, null);
        GoScoringProtocol.Snapshot snapshot = snapshot(epoch, 0, Set.of(), -2.5);
        try (GoGame game = GoGame.rulesOnly()) {
            game.pass();
            assertNull(receive(game, receiver, peer, snapshot, "BEGIN"));
            assertNull(receive(game, receiver, peer, snapshot, "STATE"));
            assertNull(receiver.current());
            assertEquals(1, game.moveHistorySize());
            assertFalse(game.isGameOver());
            game.pass();
            assertTrue(game.isGameOver());
            assertEquals(2, game.moveHistorySize());
            GoScoringProtocol.Snapshot accepted = receive(game, receiver, peer, snapshot, "STATE");
            assertEquals(snapshot, accepted);
            assertEquals(-2.5, accepted.komi());
            GoScoringProtocol.Receiver scoring = new GoScoringProtocol.Receiver(true, peer, epoch,
                    GoScoringProtocol.Phase.SCORING, accepted);
            assertNull(receive(game, scoring, peer, snapshot, "BEGIN"));
            assertEquals(accepted, receive(game, scoring, peer, snapshot, "STATE"));
        }
    }

    @Test
    void snapshotRejectsWrongPeerRolePhaseAndRound() {
        UUID peer = UUID.randomUUID();
        UUID epoch = UUID.randomUUID();
        GoScoringProtocol.Snapshot snapshot = snapshot(epoch, 0, Set.of(), 7.5);
        try (GoGame game = GoGame.rulesOnly()) {
            game.pass();
            game.pass();
            GoScoringProtocol.Receiver receiver = new GoScoringProtocol.Receiver(true, peer, epoch,
                    GoScoringProtocol.Phase.PLAYING, null);
            assertNull(receive(game, receiver, UUID.randomUUID(), snapshot, "STATE"));
            assertNull(receive(game, new GoScoringProtocol.Receiver(false, peer, epoch,
                    GoScoringProtocol.Phase.PLAYING, null), peer, snapshot, "STATE"));
            for (GoScoringProtocol.Phase phase : new GoScoringProtocol.Phase[]{
                    GoScoringProtocol.Phase.FINISHED, GoScoringProtocol.Phase.OTHER}) {
                assertNull(receive(game, new GoScoringProtocol.Receiver(true, peer, epoch, phase, null),
                        peer, snapshot, "STATE"));
            }
            assertNull(receive(game, receiver, peer, snapshot(UUID.randomUUID(), 0, Set.of(), 7.5), "BEGIN"));
            assertNull(receive(game, receiver, peer, snapshot(UUID.randomUUID(), 0, Set.of(), 7.5), "STATE"));
            assertNull(receive(game, receiver, peer, snapshot, "FINAL"));
        }
    }

    @Test
    void scoringRejectsOldRevisionOrChangedKomiButAcceptsNewRevision() {
        UUID peer = UUID.randomUUID();
        UUID epoch = UUID.randomUUID();
        GoScoringProtocol.Snapshot current = snapshot(epoch, 3, Set.of(), 7.5);
        GoScoringProtocol.Receiver receiver = new GoScoringProtocol.Receiver(true, peer, epoch,
                GoScoringProtocol.Phase.SCORING, current);
        try (GoGame game = GoGame.rulesOnly()) {
            game.pass();
            game.pass();
            assertNull(receive(game, receiver, peer, snapshot(epoch, 2, Set.of(), 7.5), "STATE"));
            assertNull(receive(game, receiver, peer, snapshot(epoch, 4, Set.of(), 6.5), "STATE"));
            assertNull(receive(game, receiver, peer, snapshot(UUID.randomUUID(), 4, Set.of(), 7.5), "STATE"));
            assertEquals(current, receive(game, receiver, peer, current, "STATE"));
            GoScoringProtocol.Snapshot next = snapshot(epoch, 4, Set.of(), 7.5);
            assertEquals(next, receive(game, receiver, peer, next, "STATE"));
            assertEquals(current, receiver.current());
        }
    }

    @Test
    void finalMustMatchCanonicalSnapshotAndCanBeRetriedAfterLoss() {
        UUID peer = UUID.randomUUID();
        UUID epoch = UUID.randomUUID();
        Set<Long> marks = new LinkedHashSet<>(Set.of(GoScoringProtocol.key(0, 0)));
        GoScoringProtocol.Snapshot current = snapshot(epoch, 3, marks, 7.5);
        marks.clear();
        assertEquals(Set.of(GoScoringProtocol.key(0, 0)), current.marks());
        GoScoringProtocol.Receiver receiver = new GoScoringProtocol.Receiver(true, peer, epoch,
                GoScoringProtocol.Phase.SCORING, current);
        try (GoGame game = GoGame.rulesOnly()) {
            assertTrue(game.placeStone(0, 0));
            game.pass();
            game.pass();
            assertNull(receive(game, receiver, peer, snapshot(epoch, 4, current.marks(), 7.5), "FINAL"));
            assertNull(receive(game, receiver, peer, snapshot(epoch, 3, Set.of(), 7.5), "FINAL"));
            assertNull(receive(game, receiver, peer, snapshot(epoch, 3, current.marks(), 6.5), "FINAL"));
            assertNull(receive(game, receiver, peer, snapshot(UUID.randomUUID(), 3, current.marks(), 7.5), "FINAL"));
            String original = current.encode("FINAL");
            assertEquals(original, current.encode("FINAL"));
            assertEquals(current, GoScoringProtocol.receiveSnapshot(game, receiver, peer, original.split("\\|", -1)));
            GoScoringProtocol.Receiver finished = new GoScoringProtocol.Receiver(true, peer, epoch,
                    GoScoringProtocol.Phase.FINISHED, current);
            assertNull(receive(game, finished, peer, current, "FINAL"));
        }
    }

    @Test
    void invalidSnapshotMarksDigestAndKomiNeverEstablishScoring() {
        UUID peer = UUID.randomUUID();
        UUID epoch = UUID.randomUUID();
        GoScoringProtocol.Receiver receiver = new GoScoringProtocol.Receiver(true, peer, epoch,
                GoScoringProtocol.Phase.PLAYING, null);
        try (GoGame game = GoGame.rulesOnly()) {
            game.pass();
            game.pass();
            assertNull(receive(game, receiver, peer, snapshot(epoch, 0,
                    Set.of(GoScoringProtocol.key(0, 0)), 7.5), "STATE"));
            String[] parts = snapshot(epoch, 0, Set.of(), 7.5).encode("STATE").split("\\|", -1);
            parts[5] = "bad-digest";
            assertNull(GoScoringProtocol.receiveSnapshot(game, receiver, peer, parts));
            parts[5] = GoScoringProtocol.digest(Set.of());
            for (String invalid : new String[]{"NaN", "Infinity", "100.1", "7.50"}) {
                parts[6] = invalid;
                assertNull(GoScoringProtocol.receiveSnapshot(game, receiver, peer, parts));
            }
            assertNull(receiver.current());
        }
    }

    private static GoScoringProtocol.Snapshot snapshot(UUID epoch, long revision, Set<Long> marks, double komi) {
        return new GoScoringProtocol.Snapshot(epoch, revision, marks, GoScoringProtocol.digest(marks), komi);
    }

    private static GoScoringProtocol.Snapshot receive(GoGame game, GoScoringProtocol.Receiver receiver,
                                                     UUID peer, GoScoringProtocol.Snapshot snapshot, String action) {
        return GoScoringProtocol.receiveSnapshot(game, receiver, peer, snapshot.encode(action).split("\\|", -1));
    }

    @Test
    void emptyMarkSetHasStableDigest() {
        assertEquals("", GoScoringProtocol.encodeMarks(Set.of()));
        assertEquals(64, GoScoringProtocol.digest(Set.of()).length());
        assertNotNull(UUID.randomUUID());
    }
}
