package com.wzz.game_console.client.screens.games.gogame;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Random;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

@Timeout(30)
class CpuInferenceRegressionTest {
    @Test
    void unavailableGpuValuesMatchFullCpuAcrossInputsModelsThreadsAndWeightUpdates() throws Exception {
        String previous = System.getProperty("go.gpu");
        NeuralEvaluator first = new NeuralEvaluator();
        NeuralEvaluator second = new NeuralEvaluator();
        ExecutorService pool = Executors.newFixedThreadPool(4);
        try {
            System.setProperty("go.gpu", "false");
            NeuralEvaluator.ModelWeights changed = second.snapshot();
            Arrays.fill(changed.valueB1, 0.5);
            second.apply(changed);
            NeuralEvaluator[] evaluators = {first, second};
            GoPlayer[][][] boards = new GoPlayer[3][19][19];
            for (GoPlayer[][] board : boards) for (GoPlayer[] row : board) Arrays.fill(row, GoPlayer.NONE);
            boards[1][3][3] = GoPlayer.BLACK;
            boards[1][15][15] = GoPlayer.WHITE;
            for (int x = 0; x < 19; x++) for (int y = 0; y < 19; y++) {
                boards[2][x][y] = (x + y) % 3 == 0 ? GoPlayer.NONE
                        : (x + y) % 3 == 1 ? GoPlayer.BLACK : GoPlayer.WHITE;
            }
            int[][] lastMoves = {null, {3, 3}, {18, 18}};
            double[][] expected = new double[2][6];
            for (int model = 0; model < 2; model++) for (int position = 0; position < 6; position++) {
                GoPlayer player = position % 2 == 0 ? GoPlayer.BLACK : GoPlayer.WHITE;
                expected[model][position] = evaluators[model].forward(
                        evaluators[model].buildInputPlanes(boards[position % 3], player, lastMoves[position % 3]),
                        evaluators[model].extractAuxFeatures(boards[position % 3], player)).value;
            }
            assertNotEquals(expected[0][1], expected[1][1]);
            // Simulate a recorded initialization failure without relying on native drivers.
            var disabled = NeuralEvaluator.class.getDeclaredField("openclDisabled");
            disabled.setAccessible(true);
            disabled.setBoolean(first, true);
            disabled.setBoolean(second, true);
            var failureAt = NeuralEvaluator.class.getDeclaredField("openclFailureAt");
            failureAt.setAccessible(true);
            failureAt.setLong(first, System.nanoTime());
            failureAt.setLong(second, System.nanoTime());
            System.setProperty("go.gpu", "true");
            assertFalse(first.isGpuActive());
            assertFalse(second.isGpuActive());
            List<Future<Double>> futures = new ArrayList<>();
            for (int i = 0; i < 120; i++) {
                int model = i % 2, position = (i / 2) % 6;
                futures.add(pool.submit(() -> evaluators[model].forwardValue(boards[position % 3],
                        position % 2 == 0 ? GoPlayer.BLACK : GoPlayer.WHITE, lastMoves[position % 3])));
            }
            for (int i = 0; i < futures.size(); i++) {
                assertEquals(expected[i % 2][(i / 2) % 6], futures.get(i).get(10, TimeUnit.SECONDS), 1e-12);
            }
            first.apply(changed);
            assertEquals(expected[1][1], first.forwardValue(boards[1], GoPlayer.WHITE, lastMoves[1]), 1e-12);
            System.setProperty("go.gpu", "false");
            assertEquals(expected[1][1], first.forwardValue(boards[1], GoPlayer.WHITE, lastMoves[1]), 1e-12);
            System.setProperty("go.gpu", "true");
            try {
                Thread.currentThread().interrupt();
                assertEquals(0.0, first.forwardValue(boards[0], GoPlayer.BLACK, null));
                assertTrue(Thread.currentThread().isInterrupted());
            } finally {
                Thread.interrupted();
            }
            first.release();
            assertEquals(0.0, first.forwardValue(boards[0], GoPlayer.BLACK, null));
        } finally {
            pool.shutdownNow();
            pool.awaitTermination(10, TimeUnit.SECONDS);
            first.release();
            second.release();
            if (previous == null) System.clearProperty("go.gpu");
            else System.setProperty("go.gpu", previous);
        }
    }

    @Test
    void valueOnlyForwardMatchesFullForwardAndDoesNotExposeMutablePolicy() {
        String previous = System.getProperty("go.gpu");
        System.setProperty("go.gpu", "false");
        NeuralEvaluator evaluator = new NeuralEvaluator();
        try (GoGame game = GoGame.rulesOnly()) {
            int[][] moves = {{3, 3}, {15, 15}, {3, 15}, {10, 10}, {8, 8}};
            for (int[] move : moves) assertTrue(game.placeStone(move[0], move[1]));
            GoPlayer[][] board = game.getBoardCopy();
            double[][][] planes = evaluator.buildInputPlanes(board, game.getCurrentPlayer(), game.getLastMove());
            double[] aux = evaluator.extractAuxFeatures(board, game.getCurrentPlayer());
            double expected = evaluator.forward(planes, aux).value;
            for (int i = 0; i < 10; i++) {
                assertEquals(expected, evaluator.forwardValue(board, game.getCurrentPlayer(), game.getLastMove()), 1e-12);
            }
        } finally {
            evaluator.release();
            if (previous == null) System.clearProperty("go.gpu");
            else System.setProperty("go.gpu", previous);
        }
    }

    @Test
    void reusedBuffersKeepResultsIndependentAcrossInputsModelsAndThreads() throws Exception {
        String previous = System.getProperty("go.gpu");
        NeuralEvaluator first = new NeuralEvaluator();
        NeuralEvaluator second = new NeuralEvaluator();
        ExecutorService pool = Executors.newFixedThreadPool(8);
        try {
            System.setProperty("go.gpu", "false");
            NeuralEvaluator.ModelWeights changed = second.snapshot();
            changed.policyB[0] = 2.0;
            second.apply(changed);
            NeuralEvaluator[] evaluators = {first, second};
            double[][][][] inputs = new double[3][4][19][19];
            double[][] aux = new double[3][24];
            Random random = new Random(853);
            for (int n = 1; n < inputs.length; n++) {
                for (double[][] plane : inputs[n]) {
                    for (double[] row : plane) {
                        for (int i = 0; i < row.length; i++) row[i] = random.nextDouble();
                    }
                }
                for (int i = 0; i < aux[n].length; i++) aux[n][i] = random.nextDouble() - 0.5;
            }

            NeuralEvaluator.ForwardResult[][] expected = new NeuralEvaluator.ForwardResult[2][3];
            double[][][] savedPolicies = new double[2][3][];
            for (int model = 0; model < 2; model++) {
                for (int n = 0; n < 3; n++) {
                    expected[model][n] = evaluators[model].forward(inputs[n], aux[n]);
                    savedPolicies[model][n] = expected[model][n].policy.clone();
                    assertEquals(1.0, Arrays.stream(savedPolicies[model][n]).sum(), 1e-12);
                }
            }
            assertNotEquals(expected[0][0].policy[0], expected[1][0].policy[0]);

            List<Future<NeuralEvaluator.ForwardResult>> futures = new ArrayList<>();
            for (int i = 0; i < 240; i++) {
                int model = i % 2;
                int input = i % 3;
                futures.add(pool.submit(() -> evaluators[model].forward(inputs[input], aux[input])));
            }
            // Wait for every call before inspecting outputs: later calls must not overwrite them.
            List<NeuralEvaluator.ForwardResult> results = new ArrayList<>();
            for (Future<NeuralEvaluator.ForwardResult> future : futures) {
                results.add(future.get(10, TimeUnit.SECONDS));
            }
            for (int i = 0; i < results.size(); i++) {
                NeuralEvaluator.ForwardResult result = results.get(i);
                assertEquals(expected[i % 2][i % 3].value, result.value);
                assertArrayEquals(savedPolicies[i % 2][i % 3], result.policy);
                assertNotSame(expected[i % 2][i % 3].policy, result.policy);
            }
            for (int model = 0; model < 2; model++) {
                for (int n = 0; n < 3; n++) {
                    assertArrayEquals(savedPolicies[model][n], expected[model][n].policy);
                }
            }
            Arrays.fill(results.getFirst().policy, -1.0);
            assertArrayEquals(savedPolicies[0][0], first.forward(inputs[0], aux[0]).policy);

            // Applying new weights must take effect even when the caller has warmed buffers.
            first.apply(changed);
            assertArrayEquals(savedPolicies[1][0], first.forward(inputs[0], aux[0]).policy);
        } finally {
            pool.shutdownNow();
            pool.awaitTermination(10, TimeUnit.SECONDS);
            first.release();
            second.release();
            if (previous == null) System.clearProperty("go.gpu");
            else System.setProperty("go.gpu", previous);
        }
    }
}
