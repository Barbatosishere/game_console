package com.wzz.game_console.client.screens.games.chess;

import org.junit.jupiter.api.Test;

import java.io.InputStream;
import java.io.OutputStream;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.*;

class PikafishChessAITest {
    @Test
    void informationalOutputDoesNotExtendHandshakeDeadline() {
        BlockingQueue<String> responses = new LinkedBlockingQueue<>() {
            @Override public String poll(long timeout, TimeUnit unit) throws InterruptedException {
                TimeUnit.MILLISECONDS.sleep(1);
                return "info string warming up";
            }
        };
        assertTimeoutPreemptively(Duration.ofSeconds(2), () -> {
            long started = System.nanoTime();
            assertFalse(PikafishChessAI.awaitResponse(responses, "uciok", 30));
            assertTrue(System.nanoTime() - started >= TimeUnit.MILLISECONDS.toNanos(30));
        });
    }

    @Test
    void acceptsHandshakeResponseAmongInformationalLines() throws Exception {
        for (String response : List.of("uciok", "readyok")) {
            BlockingQueue<String> responses = new LinkedBlockingQueue<>(List.of(
                    "info string starting", "  " + response + "  ", "info string later"));
            assertTrue(PikafishChessAI.awaitResponse(responses, response, 1000));
            assertEquals("info string later", responses.poll());
        }
    }

    @Test
    void eofEndsHandshakeWithoutWaitingForDeadline() {
        BlockingQueue<String> responses = new LinkedBlockingQueue<>(List.of("__PIKAFISH_EOF__"));
        assertTimeoutPreemptively(Duration.ofSeconds(2), () ->
                assertFalse(PikafishChessAI.awaitResponse(responses, "uciok", 15_000)));
        assertEquals("__PIKAFISH_EOF__", responses.poll());
    }

    @Test
    void interruptedHandshakePreservesInterruptFlag() {
        Thread.currentThread().interrupt();
        try {
            assertThrows(java.io.IOException.class, () ->
                    PikafishChessAI.awaitResponse(new LinkedBlockingQueue<>(), "uciok", 15_000));
            assertTrue(Thread.currentThread().isInterrupted());
        } finally {
            Thread.interrupted();
        }
    }

    @Test
    void destroysProcessBeforeClosingStreams() {
        for (boolean requiresForce : new boolean[]{false, true}) {
            List<String> events = new ArrayList<>();
            Process process = process(events, requiresForce, false);
            PikafishChessAI.closeProcess(process, () -> {
                assertFalse(process.isAlive(), "closing a writer must follow termination");
                events.add("writer");
            }, () -> {
                assertFalse(process.isAlive(), "closing a reader must follow termination");
                events.add("reader");
            });
            assertEquals(requiresForce ? List.of("destroy", "force", "writer", "reader")
                    : List.of("destroy", "writer", "reader"), events);
        }
    }

    @Test
    void interruptedWaitStillKillsProcessAndClosesStreams() {
        List<String> events = new ArrayList<>();
        Process process = process(events, true, true);
        try {
            PikafishChessAI.closeProcess(process, () -> events.add("writer"), () -> events.add("reader"));
            assertFalse(process.isAlive());
            assertTrue(Thread.currentThread().isInterrupted());
            assertEquals(List.of("destroy", "force", "writer", "reader"), events);
        } finally {
            Thread.interrupted();
        }
    }

    @Test
    void failedWriterCloseDoesNotSkipReaderClose() {
        List<String> events = new ArrayList<>();
        Process process = process(events, false, false);
        PikafishChessAI.closeProcess(process, () -> {
            throw new java.io.IOException("broken writer");
        }, () -> events.add("reader"));
        assertEquals(List.of("destroy", "reader"), events);
    }

    private static Process process(List<String> events, boolean requiresForce, boolean interrupted) {
        return new Process() {
            private boolean alive = true;
            @Override public OutputStream getOutputStream() { return OutputStream.nullOutputStream(); }
            @Override public InputStream getInputStream() { return InputStream.nullInputStream(); }
            @Override public InputStream getErrorStream() { return InputStream.nullInputStream(); }
            @Override public Stream<ProcessHandle> descendants() { return Stream.empty(); }
            @Override public int waitFor() { return 0; }
            @Override public boolean waitFor(long timeout, TimeUnit unit) throws InterruptedException {
                if (interrupted) throw new InterruptedException();
                return !alive;
            }
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
    }
}
