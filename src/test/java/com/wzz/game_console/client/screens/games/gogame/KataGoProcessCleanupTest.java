package com.wzz.game_console.client.screens.games.gogame;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.lang.reflect.InvocationTargetException;
import java.nio.file.Path;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

@Timeout(20)
class KataGoProcessCleanupTest {
    @Test
    void closingLauncherAlsoTerminatesItsEngineChild() throws Exception {
        try (EngineTree tree = EngineTree.start()) {
            KataGoGoAI.closeProcess(tree.wrapper, null, null);
            assertFalse(tree.wrapper.isAlive(), "launcher must terminate");
            tree.child.onExit().get(5, TimeUnit.SECONDS);
            assertFalse(tree.child.isAlive(), "engine must not survive its launcher");
        }
    }

    @Test
    void interruptedShutdownStillTerminatesTheEngineTree() throws Exception {
        try (EngineTree tree = EngineTree.start()) {
            Thread.currentThread().interrupt();
            try {
                KataGoGoAI.closeProcess(tree.wrapper, null, null);
                assertTrue(Thread.currentThread().isInterrupted());
            } finally {
                Thread.interrupted();
            }
            tree.child.onExit().get(5, TimeUnit.SECONDS);
            assertFalse(tree.child.isAlive(), "interruption must not leave an engine running");
        }
    }

    @Test
    void timedOutTrainingCommandTerminatesTheEngineTree() throws Exception {
        NeuralEvaluator evaluator = new NeuralEvaluator();
        try (EngineTree tree = EngineTree.start()) {
            GoAdversarialTrainer trainer = new GoAdversarialTrainer(new GoAdversarialTrainer.Config(), evaluator);
            var send = GoAdversarialTrainer.class.getDeclaredMethod("sendGTP", Process.class,
                    java.io.Writer.class, java.io.Reader.class, String.class, long.class);
            send.setAccessible(true);
            InvocationTargetException error = assertThrows(InvocationTargetException.class, () -> send.invoke(
                    trainer, tree.wrapper, new OutputStreamWriter(tree.wrapper.getOutputStream()),
                    new BufferedReader(new InputStreamReader(tree.wrapper.getInputStream())), "genmove white", 100L));
            assertInstanceOf(java.util.concurrent.TimeoutException.class, error.getCause());
            tree.child.onExit().get(5, TimeUnit.SECONDS);
            assertFalse(tree.child.isAlive(), "training timeout must terminate the actual engine");
        } finally {
            evaluator.release();
        }
    }

    private static final class EngineTree implements AutoCloseable {
        final Process wrapper;
        final ProcessHandle child;

        private EngineTree(Process wrapper, ProcessHandle child) {
            this.wrapper = wrapper;
            this.child = child;
        }

        static EngineTree start() throws Exception {
            String classes = Path.of(EngineProcessFixture.class.getProtectionDomain()
                    .getCodeSource().getLocation().toURI()).toString();
            Process wrapper = new ProcessBuilder(EngineProcessFixture.javaExecutable(), "-cp", classes,
                    EngineProcessFixture.class.getName(), "wrapper", classes)
                    .redirectError(ProcessBuilder.Redirect.INHERIT).start();
            try {
                BufferedReader output = new BufferedReader(new InputStreamReader(wrapper.getInputStream()));
                ProcessHandle child = ProcessHandle.of(Long.parseLong(output.readLine())).orElseThrow();
                return new EngineTree(wrapper, child);
            } catch (Throwable failure) {
                wrapper.descendants().forEach(ProcessHandle::destroyForcibly);
                wrapper.destroyForcibly();
                throw failure;
            }
        }

        @Override public void close() throws Exception {
            child.destroyForcibly();
            child.onExit().get(5, TimeUnit.SECONDS);
            wrapper.destroyForcibly();
            assertTrue(wrapper.waitFor(5, TimeUnit.SECONDS));
            wrapper.getOutputStream().close();
            wrapper.getInputStream().close();
            wrapper.getErrorStream().close();
        }
    }
}
