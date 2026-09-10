package com.wzz.game_console.client.screens.games.gogame;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class KataGoGoAITest {
    @Test
    void normalizesUnsupportedEngineNamesToMcts() {
        assertEquals("mcts", GoAI.normalizeEngine(null));
        assertEquals("mcts", GoAI.normalizeEngine(""));
        assertEquals("mcts", GoAI.normalizeEngine("unknown"));
        assertEquals("mcts", GoAI.normalizeEngine("MCTS"));
        assertEquals("katago", GoAI.normalizeEngine(" KataGo "));
    }

    @Test
    void labelsActualRuntimeEngineWithoutStartingIt() {
        assertEquals("MCTS", GoAI.runtimeEngineLabel(MCTSGoAI.class));
        assertEquals("KataGo", GoAI.runtimeEngineLabel(KataGoGoAI.class));
        assertNull(GoAI.runtimeEngineLabel(null));
    }

    @Test
    void parsesMoveAndSkipsGtpIColumn() {
        GoAI.MoveResult move = KataGoGoAI.parseMoveResult("J10");
        assertEquals(GoAI.MoveType.MOVE, move.type());
        assertEquals(8, move.x());
        assertEquals(9, move.y());
        assertEquals(GoAI.MoveType.ERROR, KataGoGoAI.parseMoveResult("I10").type());
    }

    @Test
    void keepsPassResignAndMalformedResponsesDistinct() {
        assertEquals(GoAI.MoveType.PASS, KataGoGoAI.parseMoveResult("pass").type());
        assertEquals(GoAI.MoveType.RESIGN, KataGoGoAI.parseMoveResult("resign").type());
        for (String invalid : new String[] {null, "", "not-a-move", "A0", "T20", "U1"}) {
            assertEquals(GoAI.MoveType.ERROR, KataGoGoAI.parseMoveResult(invalid).type());
        }
    }

    @Test
    void acknowledgedGenmoveIsNotReplayed() throws IOException {
        FakeTransport transport = new FakeTransport();
        KataGoGoAI.BoardSync sync = new KataGoGoAI.BoardSync(transport);
        List<GoMove> history = new ArrayList<>(List.of(move(0, 0, GoPlayer.BLACK)));
        GoAI.MoveResult result = sync.generate(history, GoPlayer.WHITE);
        assertEquals(GoAI.MoveResult.move(3, 3), result);
        assertEquals(List.of("clear_board", "play black a1", "genmove white"), transport.commands);

        history.add(move(3, 3, GoPlayer.WHITE));
        history.add(move(1, 0, GoPlayer.BLACK));
        transport.commands.clear();
        transport.response = "E5";
        sync.generate(history, GoPlayer.WHITE);
        assertEquals(List.of("play black b1", "genmove white"), transport.commands);
    }

    @Test
    void acknowledgedGeneratedPassIsNotReplayed() throws IOException {
        FakeTransport transport = new FakeTransport();
        transport.response = "pass";
        KataGoGoAI.BoardSync sync = new KataGoGoAI.BoardSync(transport);
        List<GoMove> history = new ArrayList<>(List.of(move(0, 0, GoPlayer.BLACK)));
        assertEquals(GoAI.MoveType.PASS, sync.generate(history, GoPlayer.WHITE).type());
        history.add(move(-1, -1, GoPlayer.WHITE));
        history.add(move(1, 0, GoPlayer.BLACK));
        transport.commands.clear();
        sync.generate(history, GoPlayer.WHITE);
        assertEquals(List.of("play black b1", "genmove white"), transport.commands);
    }

    @Test
    void unacknowledgedGenmoveRebuildIncludesPass() throws IOException {
        FakeTransport transport = new FakeTransport();
        KataGoGoAI.BoardSync sync = new KataGoGoAI.BoardSync(transport);
        List<GoMove> history = List.of(move(-1, -1, GoPlayer.BLACK));
        sync.generate(history, GoPlayer.WHITE);
        transport.commands.clear();
        sync.generate(history, GoPlayer.WHITE);
        assertEquals(List.of("clear_board", "play black pass", "genmove white"), transport.commands);
    }

    @Test
    void sameLengthDivergenceRebuildsInsteadOfTrustingMoveCount() throws IOException {
        FakeTransport transport = new FakeTransport();
        KataGoGoAI.BoardSync sync = new KataGoGoAI.BoardSync(transport);
        sync.generate(List.of(move(0, 0, GoPlayer.BLACK)), GoPlayer.WHITE);
        transport.commands.clear();
        // Same length as engine history, but the caller used a different move.
        sync.generate(List.of(move(0, 0, GoPlayer.BLACK), move(4, 4, GoPlayer.WHITE)), GoPlayer.BLACK);
        assertEquals(List.of("clear_board", "play black a1", "play white e5", "genmove black"), transport.commands);
    }

    @Test
    void partialReplayFailureForcesFullRebuildOnRetry() throws IOException {
        FakeTransport transport = new FakeTransport();
        KataGoGoAI.BoardSync sync = new KataGoGoAI.BoardSync(transport);
        List<GoMove> history = List.of(move(0, 0, GoPlayer.BLACK), move(-1, -1, GoPlayer.WHITE),
                move(1, 0, GoPlayer.BLACK));
        transport.failOn = "play black b1";
        assertThrows(IOException.class, () -> sync.generate(history, GoPlayer.WHITE));
        assertEquals(List.of("clear_board", "play black a1", "play white pass", "play black b1"), transport.commands);
        transport.commands.clear();
        sync.generate(history, GoPlayer.WHITE);
        assertEquals(List.of("clear_board", "play black a1", "play white pass", "play black b1", "genmove white"),
                transport.commands);
    }

    @Test
    void incrementalReplayFailureAlsoForcesFullRebuild() throws IOException {
        FakeTransport transport = new FakeTransport();
        KataGoGoAI.BoardSync sync = new KataGoGoAI.BoardSync(transport);
        sync.generate(List.of(move(0, 0, GoPlayer.BLACK)), GoPlayer.WHITE);
        List<GoMove> history = List.of(move(0, 0, GoPlayer.BLACK), move(3, 3, GoPlayer.WHITE),
                move(1, 0, GoPlayer.BLACK), move(-1, -1, GoPlayer.WHITE), move(2, 0, GoPlayer.BLACK));
        transport.commands.clear();
        transport.failOn = "play black c1";
        assertThrows(IOException.class, () -> sync.generate(history, GoPlayer.WHITE));
        assertEquals(List.of("play black b1", "play white pass", "play black c1"), transport.commands);
        transport.commands.clear();
        sync.generate(history, GoPlayer.WHITE);
        assertEquals(List.of("clear_board", "play black a1", "play white d4", "play black b1",
                "play white pass", "play black c1", "genmove white"), transport.commands);
    }

    @Test
    void genmoveFailureAndMalformedReplyInvalidateEngineHistory() throws IOException {
        for (boolean malformed : new boolean[] {false, true}) {
            FakeTransport transport = new FakeTransport();
            KataGoGoAI.BoardSync sync = new KataGoGoAI.BoardSync(transport);
            List<GoMove> history = List.of(move(0, 0, GoPlayer.BLACK));
            if (malformed) {
                transport.response = "garbage";
                assertEquals(GoAI.MoveType.ERROR, sync.generate(history, GoPlayer.WHITE).type());
            } else {
                transport.failOn = "genmove white";
                assertThrows(IOException.class, () -> sync.generate(history, GoPlayer.WHITE));
            }
            transport.commands.clear();
            transport.response = "D4";
            sync.generate(history, GoPlayer.WHITE);
            assertEquals(List.of("clear_board", "play black a1", "genmove white"), transport.commands);
        }
    }

    @Test
    void destroysProcessBeforeClosingEitherStream() {
        for (boolean requiresForce : new boolean[] {false, true}) {
            List<String> events = new ArrayList<>();
            Process process = new Process() {
                private boolean alive = true;
                @Override public java.io.OutputStream getOutputStream() { return java.io.OutputStream.nullOutputStream(); }
                @Override public java.io.InputStream getInputStream() { return java.io.InputStream.nullInputStream(); }
                @Override public java.io.InputStream getErrorStream() { return java.io.InputStream.nullInputStream(); }
                @Override public int waitFor() { return 0; }
                @Override public boolean waitFor(long timeout, java.util.concurrent.TimeUnit unit) { return !alive; }
                @Override public int exitValue() { return 0; }
                @Override public boolean isAlive() { return alive; }
                @Override public void destroy() {
                    events.add("destroy");
                    if (!requiresForce) alive = false;
                }
                @Override public Process destroyForcibly() {
                    events.add("force");
                    alive = false;
                    return this;
                }
            };
            KataGoGoAI.closeProcess(process, () -> {
                assertFalse(process.isAlive(), "writer close must follow process termination");
                events.add("writer");
            }, () -> {
                assertFalse(process.isAlive(), "reader close must follow process termination");
                events.add("reader");
            });
            assertEquals(requiresForce ? List.of("destroy", "force", "writer", "reader")
                    : List.of("destroy", "writer", "reader"), events);
        }
    }

    private static GoMove move(int x, int y, GoPlayer color) {
        return new GoMove(x, y, color, 0);
    }

    private static final class FakeTransport implements KataGoGoAI.CommandTransport {
        final List<String> commands = new ArrayList<>();
        String response = "D4";
        String failOn;

        @Override
        public String send(String command) throws IOException {
            commands.add(command);
            if (command.equals(failOn)) {
                failOn = null;
                throw new IOException("simulated GTP failure");
            }
            return command.startsWith("genmove ") ? response : "";
        }
    }
}
