package com.wzz.game_console.client.screens.games.gogame;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Random;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

@Timeout(120)
class GoGpuStressTest {
    private String previousGpu;

    @BeforeEach
    void enableGpu() {
        previousGpu = System.getProperty("go.gpu");
        System.setProperty("go.gpu", "true");
    }

    @AfterEach
    void restoreGpu() {
        if (previousGpu == null) System.clearProperty("go.gpu");
        else System.setProperty("go.gpu", previousGpu);
    }

    @Test
    void replacingWeightsAtSameVersionRefreshesGpuCache() {
        NeuralEvaluator gpu = new NeuralEvaluator();
        NeuralEvaluator cpu = new NeuralEvaluator();
        try {
            assumeTrue(gpu.isGpuActive(), "OpenCL GPU unavailable");
            NeuralEvaluator.ModelWeights first = gpu.snapshot();
            NeuralEvaluator.ModelWeights second = gpu.snapshot();
            second.policyB[17] += 8.0;
            second.valueB1[0] += 1.0;
            assertEquals(first.version, second.version);
            double[][][] planes = randomPlanes(new Random(7031));
            double[] aux = new double[24];
            for (int i = 0; i < 20; i++) {
                NeuralEvaluator.ModelWeights weights = (i & 1) == 0 ? first : second;
                System.setProperty("go.gpu", "false");
                cpu.apply(weights);
                NeuralEvaluator.ForwardResult expected = cpu.forward(planes, aux);
                System.setProperty("go.gpu", "true");
                gpu.apply(weights);
                assertResult(expected, gpu.forward(planes, aux));
                assertTrue(gpu.isGpuActive());
            }
        } finally {
            gpu.release();
            cpu.release();
        }
    }

    @Test
    void failedWeightUploadCannotReusePartiallyOverwrittenCache() {
        try (OpenCLBackend backend = OpenCLBackend.acquireShared()) {
            assumeTrue(backend != null, "OpenCL GPU unavailable");
            NeuralEvaluator source = new NeuralEvaluator();
            try {
                NeuralEvaluator.ModelWeights original = source.snapshot();
                NeuralEvaluator.ModelWeights replacement = source.snapshot();
                replacement.policyB[17] += 8.0;
                double[][][][] planes = {randomPlanes(new Random(13823))};
                double[][] aux = new double[1][24];
                double[][] expectedPolicy = new double[1][362];
                double[] expectedValue = new double[1];
                assertTrue(infer(backend, original, original.valueW1, planes, aux, 1, expectedPolicy, expectedValue));
                // Fail after policy weights have reached the device, before value weights.
                assertFalse(infer(backend, replacement, new double[0][], planes, aux, 2,
                        new double[1][362], new double[1]));
                double[][] actualPolicy = new double[1][362];
                double[] actualValue = new double[1];
                assertTrue(infer(backend, original, original.valueW1, planes, aux, 1, actualPolicy, actualValue));
                assertArrayEquals(expectedPolicy[0], actualPolicy[0], 1e-10);
                assertArrayEquals(expectedValue, actualValue, 1e-10);
            } finally {
                source.release();
            }
        }
    }

    @Test
    void concurrentModelSwitchesKeepThreadLocalInputsAndResultsIsolated() throws Exception {
        NeuralEvaluator[] evaluators = {new NeuralEvaluator(), new NeuralEvaluator()};
        ExecutorService pool = Executors.newFixedThreadPool(24);
        try {
            assumeTrue(evaluators[0].isGpuActive() && evaluators[1].isGpuActive(), "OpenCL GPU unavailable");
            NeuralEvaluator.ModelWeights changed = evaluators[1].snapshot();
            changed.policyB[31] += 6.0;
            evaluators[1].apply(changed);
            GoPlayer[][][] boards = new GoPlayer[8][19][19];
            for (int i = 0; i < boards.length; i++) {
                Random random = new Random(1607 + i);
                for (GoPlayer[] row : boards[i]) {
                    for (int y = 0; y < row.length; y++) row[y] = GoPlayer.values()[random.nextInt(3)];
                }
            }
            NeuralEvaluator.ForwardResult[][] expected = new NeuralEvaluator.ForwardResult[2][8];
            System.setProperty("go.gpu", "false");
            for (int model = 0; model < 2; model++) for (int board = 0; board < 8; board++) {
                expected[model][board] = evaluators[model].forwardPosition(boards[board], GoPlayer.BLACK, null);
            }
            System.setProperty("go.gpu", "true");
            CountDownLatch start = new CountDownLatch(1);
            List<Future<?>> futures = new ArrayList<>();
            for (int worker = 0; worker < 24; worker++) {
                int index = worker;
                futures.add(pool.submit(() -> {
                    assertTrue(start.await(5, TimeUnit.SECONDS));
                    for (int iteration = 0; iteration < 128; iteration++) {
                        int model = (index + iteration) % 2;
                        int board = (index * 3 + iteration) % 8;
                        NeuralEvaluator.ForwardResult actual = evaluators[model].forwardPosition(
                                boards[board], GoPlayer.BLACK, null);
                        assertResult(expected[model][board], actual);
                        Arrays.fill(actual.policy, -1.0); // Returned policies must belong to each call.
                    }
                    return null;
                }));
            }
            start.countDown();
            for (Future<?> future : futures) future.get(90, TimeUnit.SECONDS);
            assertTrue(evaluators[0].isGpuActive() && evaluators[1].isGpuActive());
            System.out.println("GPU stress: 24 workers, 3072 inferences, 2 models, 8 positions");
        } finally {
            pool.shutdownNow();
            assertTrue(pool.awaitTermination(10, TimeUnit.SECONDS));
            for (NeuralEvaluator evaluator : evaluators) evaluator.release();
        }
    }

    @Test
    void repeatedGpuTrainingMatchesCpuIncludingCheckpointReload() {
        NeuralEvaluator gpu = new NeuralEvaluator();
        NeuralEvaluator cpu = new NeuralEvaluator();
        try {
            assumeTrue(gpu.isGpuActive(), "OpenCL GPU unavailable");
            NeuralEvaluator.ModelWeights initial = gpu.snapshot();
            cpu.apply(initial);
            Random random = new Random(7346);
            double[][][][] planes = new double[3][][][];
            double[][] aux = new double[3][24];
            double[] targets = {1.0, -1.0, 0.0};
            double[][] policies = new double[3][362];
            for (int n = 0; n < 3; n++) {
                planes[n] = randomPlanes(random);
                for (int i = 0; i < 24; i++) aux[n][i] = random.nextDouble();
                policies[n][17 + n] = 1.0;
            }
            for (int step = 0; step < 12; step++) {
                if (step == 6) {
                    gpu.apply(initial);
                    cpu.apply(initial);
                }
                double momentum = step < 6 ? 0.0 : 0.9;
                System.setProperty("go.gpu", "false");
                double cpuLoss = cpu.trainMiniBatch(planes, aux, targets, policies, 0.01, 1e-5, 5.0, momentum);
                NeuralEvaluator.ForwardResult expected = cpu.forward(planes[step % 3], aux[step % 3]);
                System.setProperty("go.gpu", "true");
                double gpuLoss = gpu.trainMiniBatch(planes, aux, targets, policies, 0.01, 1e-5, 5.0, momentum);
                assertEquals(cpuLoss, gpuLoss, 1e-5);
                assertResult(expected, gpu.forward(planes[step % 3], aux[step % 3]));
                assertEquals(cpu.getModelVersion(), gpu.getModelVersion());
                assertTrue(gpu.isGpuActive());
            }
            System.out.println("GPU stress: 12 training updates match CPU, SGD/momentum, checkpoint reload");
        } finally {
            gpu.release();
            cpu.release();
        }
    }

    @Test
    void expandingAndShrinkingBatchesSurviveRepeatedNativeTeardown() {
        NeuralEvaluator cpu = new NeuralEvaluator();
        try {
            System.setProperty("go.gpu", "false");
            NeuralEvaluator.ModelWeights weights = cpu.snapshot();
            int[] sizes = {1, 64, 65, 3, 129, 2};
            for (int cycle = 0; cycle < 6; cycle++) {
                try (OpenCLBackend backend = OpenCLBackend.acquireShared()) {
                    assumeTrue(backend != null, "OpenCL GPU unavailable");
                    for (int size : sizes) {
                        Random random = new Random(8843L + size + cycle * 1000);
                        double[][][][] planes = new double[size][][][];
                        double[][] aux = new double[size][24];
                        double[][] policy = new double[size][362];
                        double[] value = new double[size];
                        for (int n = 0; n < size; n++) {
                            planes[n] = randomPlanes(random);
                            for (int i = 0; i < 24; i++) aux[n][i] = random.nextDouble();
                        }
                        assertTrue(infer(backend, weights, weights.valueW1, planes, aux, 1, policy, value));
                        for (int n : new int[]{0, size / 2, size - 1}) {
                            NeuralEvaluator.ForwardResult expected = cpu.forward(planes[n], aux[n]);
                            assertResult(expected, new NeuralEvaluator.ForwardResult(value[n], policy[n]));
                        }
                    }
                }
                assertNull(OpenCLBackend.peekShared());
            }
            System.out.println("GPU stress: 6 native context lifecycles, 36 batches, sizes 1/64/65/3/129/2");
        } finally {
            cpu.release();
        }
    }

    private static boolean infer(OpenCLBackend backend, NeuralEvaluator.ModelWeights weights,
                                 double[][] valueWeights, double[][][][] planes, double[][] aux,
                                 int owner, double[][] policy, double[] value) {
        return backend.inferForward(planes, aux, planes.length, owner, weights.version,
                weights.subW1, weights.subB1, weights.blockW1, weights.blockB1,
                weights.topW1, weights.topB1, weights.policyW, weights.policyB,
                valueWeights, weights.valueB1, weights.valueW2, weights.valueB2, policy, value);
    }

    private static double[][][] randomPlanes(Random random) {
        double[][][] planes = new double[4][19][19];
        for (double[][] plane : planes) for (double[] row : plane) {
            for (int y = 0; y < row.length; y++) row[y] = random.nextDouble();
        }
        return planes;
    }

    private static void assertResult(NeuralEvaluator.ForwardResult expected, NeuralEvaluator.ForwardResult actual) {
        assertEquals(expected.value, actual.value, 1e-5);
        assertArrayEquals(expected.policy, actual.policy, 1e-5);
    }
}
