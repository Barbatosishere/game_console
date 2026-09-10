package com.wzz.game_console.client.screens.games.gogame;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.StringReader;
import java.io.StringWriter;
import java.lang.reflect.InvocationTargetException;
import java.util.Arrays;

import static org.junit.jupiter.api.Assertions.*;

@Timeout(30)
class GoTrainingRegressionTest {
    @Test
    void fallbackCoordinatesAndPolicyMatchExecutedMove() {
        try (GoGame game = GoGame.rulesOnly()) {
            assertTrue(game.placeStone(0, 0));
            double[] oldPolicy = new double[362];
            oldPolicy[0] = 1.0;
            GoTrainingMove.Applied result = GoTrainingMove.apply(game, new int[]{0, 0}, oldPolicy);
            assertArrayEquals(new int[]{0, 1}, result.coordinates());
            assertArrayEquals(game.getLastMove(), result.coordinates());
            assertEquals(GoPlayer.WHITE, game.getStone(0, 1));
            assertEquals(1.0, result.policy()[1]);
            assertEquals(0.0, result.policy()[0]);
            assertEquals(1.0, Arrays.stream(result.policy()).sum());
            NeuralEvaluator evaluator = new NeuralEvaluator();
            try {
                double[][][] planes = evaluator.buildInputPlanes(game.getBoardCopy(), game.getCurrentPlayer(), result.coordinates());
                assertEquals(1.0, planes[3][0][1]);
                assertEquals(0.0, planes[3][0][0]);
            } finally {
                evaluator.release();
            }
        }
    }

    @Test
    void legalMovePreservesVisitPolicy() {
        try (GoGame game = GoGame.rulesOnly()) {
            double[] policy = new double[362];
            policy[2] = 0.4;
            policy[3] = 0.6;
            int[] suggested = new int[]{0, 3};
            GoTrainingMove.Applied result = GoTrainingMove.apply(game, suggested, policy);
            assertSame(policy, result.policy());
            assertArrayEquals(new int[]{0, 3}, result.coordinates());
            assertNotSame(suggested, result.coordinates());
        }
    }

    @Test
    void malformedSuggestionsFallBackToActualLegalMove() {
        for (int[] suggested : new int[][]{{}, {0}, {-1, 0}, {19, 0}, {0, 19}}) {
            try (GoGame game = GoGame.rulesOnly()) {
                GoTrainingMove.Applied result = GoTrainingMove.apply(game, suggested, new double[362]);
                assertArrayEquals(new int[]{0, 0}, result.coordinates());
                assertArrayEquals(game.getLastMove(), result.coordinates());
                assertEquals(1.0, result.policy()[0]);
                assertEquals(1.0, Arrays.stream(result.policy()).sum());
            }
        }
    }

    @Test
    void suggestedPassPreservesPolicyAndDoesNotPlaceStone() {
        try (GoGame game = GoGame.rulesOnly()) {
            assertTrue(game.placeStone(3, 3));
            long hash = game.getCurrentHash();
            double[] policy = new double[362];
            policy[0] = 0.25;
            policy[361] = 0.75;
            GoTrainingMove.Applied result = GoTrainingMove.apply(game, null, policy);
            assertNull(result.coordinates());
            assertNull(game.getLastMove());
            assertSame(policy, result.policy());
            assertEquals(hash, game.getCurrentHash());
            assertEquals(2, game.moveHistorySize());
            assertEquals(GoPlayer.BLACK, game.getCurrentPlayer());
            assertFalse(game.isGameOver());
        }
    }

    @Test
    void consecutiveSuggestedPassesFinishGameWithLegalPointsRemaining() {
        try (GoGame game = GoGame.rulesOnly()) {
            double[] policy = new double[362];
            policy[361] = 1.0;
            assertTrue(game.canPlaceStone(0, 0));
            GoTrainingMove.apply(game, null, policy);
            assertTrue(game.canPlaceStone(0, 0));
            GoTrainingMove.apply(game, null, policy);
            assertTrue(game.isGameOver());
            assertEquals(2, game.moveHistorySize());
            assertEquals(GoPlayer.NONE, game.getStone(0, 0));
        }
    }

    @Test
    void noLegalMoveProducesOnlyPassPolicy() throws Exception {
        try (GoGame game = GoGame.rulesOnly()) {
            var field = GoGame.class.getDeclaredField("board");
            field.setAccessible(true);
            GoPlayer[][] board = (GoPlayer[][]) field.get(game);
            for (GoPlayer[] column : board) Arrays.fill(column, GoPlayer.BLACK);
            double[] policy = new double[362];
            policy[0] = 1.0;
            GoTrainingMove.Applied result = GoTrainingMove.apply(game, new int[]{0, 0}, policy);
            assertNull(result.coordinates());
            assertEquals(1.0, result.policy()[361]);
            assertEquals(1.0, Arrays.stream(result.policy()).sum());
            assertEquals(1, game.moveHistorySize());
            assertEquals(GoPlayer.WHITE, game.getCurrentPlayer());
        }
    }

    @Test
    void truncatedSelfPlayGameDoesNotEnterReplayBuffer() {
        GoSelfPlayTrainer.Config config = new GoSelfPlayTrainer.Config();
        config.maxMoves = 1;
        config.maxIterations = 0;
        config.searchTimeMillis = 0;
        NeuralEvaluator evaluator = new NeuralEvaluator();
        try {
            GoSelfPlayTrainer trainer = new GoSelfPlayTrainer(config, evaluator);
            GoSelfPlayTrainer.Result result = trainer.runGeneration(1, 1, 0, 0.01, 42);
            assertEquals(0, result.completedGames);
            assertEquals(0, result.samples);
            assertEquals(0, trainer.getReplayBufferSize());
        } finally {
            evaluator.release();
        }
    }

    @Test
    void missingKataGoDoesNotCountAsCompletedGame() {
        NeuralEvaluator evaluator = new NeuralEvaluator();
        try {
            GoAdversarialTrainer trainer = new GoAdversarialTrainer(new GoAdversarialTrainer.Config(), evaluator);
            GoAdversarialTrainer.Result result = trainer.runGeneration(1, 1, 0, 0.01, 42);
            assertEquals(0, result.completedGames);
            assertEquals(0, result.samples);
            assertEquals(0, trainer.getReplayBufferSize());
        } finally {
            evaluator.release();
        }
    }

    @Test
    void cancelledGenerationPreservesInterruptAndDoesNotTrain() {
        NeuralEvaluator evaluator = new NeuralEvaluator();
        try {
            GoSelfPlayTrainer selfPlay = new GoSelfPlayTrainer(new GoSelfPlayTrainer.Config(), evaluator);
            GoAdversarialTrainer adversarial = new GoAdversarialTrainer(new GoAdversarialTrainer.Config(), evaluator);
            Thread.currentThread().interrupt();
            assertEquals(0, selfPlay.runGeneration(1, 1, 1, 0.01, 42).samples);
            assertTrue(Thread.currentThread().isInterrupted());
            assertEquals(0, adversarial.runGeneration(1, 1, 1, 0.01, 42).samples);
            assertTrue(Thread.currentThread().isInterrupted());
            assertEquals(0, selfPlay.getReplayBufferSize());
            assertEquals(0, adversarial.getReplayBufferSize());
        } finally {
            Thread.interrupted();
            evaluator.release();
        }
    }

    @Test
    void interruptDuringTrainingStopsBeforeNextBatch() throws Exception {
        for (boolean adversarial : new boolean[]{false, true}) {
            InterruptingEvaluator evaluator = new InterruptingEvaluator();
            try {
                Object trainer;
                if (adversarial) {
                    GoAdversarialTrainer.Config config = new GoAdversarialTrainer.Config();
                    config.batchSize = 1;
                    trainer = new GoAdversarialTrainer(config, evaluator);
                } else {
                    GoSelfPlayTrainer.Config config = new GoSelfPlayTrainer.Config();
                    config.batchSize = 1;
                    trainer = new GoSelfPlayTrainer(config, evaluator);
                }
                Class<?> sampleType = Class.forName(trainer.getClass().getName() + "$Sample");
                var constructor = sampleType.getDeclaredConstructor(GoPlayer[][].class,
                        GoPlayer.class, int[].class, double[].class);
                constructor.setAccessible(true);
                java.util.List<Object> samples = new java.util.ArrayList<>();
                try (GoGame game = GoGame.rulesOnly()) {
                    for (int i = 0; i < 2; i++) {
                        double[] policy = new double[362];
                        policy[0] = 1.0;
                        samples.add(constructor.newInstance(game.getBoardCopy(), GoPlayer.BLACK, null, policy));
                    }
                }
                var train = trainer.getClass().getDeclaredMethod("train", java.util.List.class,
                        int.class, double.class, long.class);
                train.setAccessible(true);
                assertEquals(2.0, (double) train.invoke(trainer, samples, 3, 0.01, 42L));
                assertEquals(1, evaluator.batches);
                assertTrue(Thread.currentThread().isInterrupted());
            } finally {
                Thread.interrupted();
                evaluator.release();
            }
        }
    }

    private static final class InterruptingEvaluator extends NeuralEvaluator {
        int batches;

        @Override
        public double trainMiniBatch(double[][][][] planes, double[][] auxFeatures,
                                     double[] values, double[][] policies, double learningRate,
                                     double l2, double gradientClip, double momentum) {
            batches++;
            Thread.currentThread().interrupt();
            return 2.0;
        }
    }

    @Test
    void gtpActionsUseStrictCoordinatesAndExactPassResign() {
        assertEquals(GoAI.MoveResult.move(8, 9), GoAdversarialTrainer.parseGTPAction("=12 J10\n"));
        assertEquals(GoAI.MoveType.PASS, GoAdversarialTrainer.parseGTPAction("= pass\n").type());
        assertEquals(GoAI.MoveType.RESIGN, GoAdversarialTrainer.parseGTPAction("=12 resign\n").type());
        for (String invalid : new String[]{null, "=", "= I10", "? illegal move", "= not pass", "= D4 extra", "=12D4"}) {
            assertEquals(GoAI.MoveType.ERROR, GoAdversarialTrainer.parseGTPAction(invalid).type(), invalid);
        }
    }

    @Test
    void rejectedGtpCommandThrowsInsteadOfReturningSuccess() throws Exception {
        NeuralEvaluator evaluator = new NeuralEvaluator();
        try {
            GoAdversarialTrainer trainer = new GoAdversarialTrainer(new GoAdversarialTrainer.Config(), evaluator);
            var method = GoAdversarialTrainer.class.getDeclaredMethod("sendGTP", java.io.Writer.class, java.io.Reader.class, String.class);
            method.setAccessible(true);
            StringWriter writer = new StringWriter();
            InvocationTargetException error = assertThrows(InvocationTargetException.class, () -> method.invoke(trainer,
                    writer, new BufferedReader(new StringReader("? illegal move\n\n")), "play black a1"));
            assertInstanceOf(IOException.class, error.getCause());
            assertEquals("play black a1\n", writer.toString());
        } finally {
            evaluator.release();
        }
    }

    /**
     * 回归：超时路径绝不能在主线程 close() reader——reader 线程可能仍持有
     * BufferedReader 内部锁阻塞在管道读上，同步 close 在 Windows 上永久死锁
     * （压力实测复现）。超时必须及时抛出且流保持打开，由进程销毁解除 reader。
     */
    @Test
    void gtpTimeoutThrowsPromptlyWithoutClosingReader() throws Exception {
        NeuralEvaluator evaluator = new NeuralEvaluator();
        try {
            GoAdversarialTrainer trainer = new GoAdversarialTrainer(new GoAdversarialTrainer.Config(), evaluator);
            var method = GoAdversarialTrainer.class.getDeclaredMethod("sendGTP",
                    java.io.Writer.class, java.io.Reader.class, String.class, long.class);
            method.setAccessible(true);
            CloseTrackingReader source = new CloseTrackingReader();
            BufferedReader reader = new BufferedReader(source);
            long start = System.nanoTime();
            InvocationTargetException error = assertThrows(InvocationTargetException.class,
                    () -> method.invoke(trainer, new StringWriter(), reader, "genmove b", 300L));
            long elapsedMillis = (System.nanoTime() - start) / 1_000_000L;
            assertInstanceOf(java.util.concurrent.TimeoutException.class, error.getCause());
            assertTrue(elapsedMillis < 3_000L, "timeout took " + elapsedMillis + "ms");
            assertFalse(source.closed, "timeout path must not close the reader");
        } finally {
            evaluator.release();
        }
    }

    @Test
    void gtpInterruptThrowsPromptlyWithoutClosingReader() throws Exception {
        NeuralEvaluator evaluator = new NeuralEvaluator();
        try {
            GoAdversarialTrainer trainer = new GoAdversarialTrainer(new GoAdversarialTrainer.Config(), evaluator);
            var method = GoAdversarialTrainer.class.getDeclaredMethod("sendGTP",
                    java.io.Writer.class, java.io.Reader.class, String.class, long.class);
            method.setAccessible(true);
            CloseTrackingReader source = new CloseTrackingReader();
            BufferedReader reader = new BufferedReader(source);
            Thread caller = new Thread(() -> {
                try {
                    method.invoke(trainer, new StringWriter(), reader, "genmove b", 60_000L);
                } catch (Exception ignored) {
                    // 断言只关心中断是否及时解除阻塞与流未关闭
                }
            });
            caller.setDaemon(true);
            caller.start();
            // reader 线程已进入 read 之后才中断，保证中断命中 await 分片而非启动竞态
            while (!source.readEntered) Thread.sleep(10L);
            long start = System.nanoTime();
            caller.interrupt();
            caller.join(3_000L);
            long elapsedMillis = (System.nanoTime() - start) / 1_000_000L;
            assertFalse(caller.isAlive(), "interrupt did not unblock sendGTP");
            assertTrue(elapsedMillis < 3_000L, "interrupt took " + elapsedMillis + "ms");
            assertFalse(source.closed, "interrupt path must not close the reader");
        } finally {
            evaluator.release();
        }
    }

    /** Blocking reader that records close() so tests can prove the failure path never closes it. */
    private static final class CloseTrackingReader extends java.io.Reader {
        volatile boolean closed;
        volatile boolean readEntered;

        @Override
        public int read(char[] cbuf, int off, int len) {
            readEntered = true;
            try {
                Thread.sleep(10_000L);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            return -1;
        }

        @Override
        public void close() {
            closed = true;
        }
    }
}
