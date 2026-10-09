package com.wzz.game_console.client.screens.games.gogame;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.lang.reflect.Field;
import java.util.Random;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.FutureTask;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.Supplier;

import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

@Timeout(15)
class OpenCLLifecycleRegressionTest {
    private Object previousShared;
    private Object previousFailure;
    private Object previousFailureAt;
    private String previousGpu;

    @BeforeEach
    void isolateSharedBackend() throws Exception {
        previousShared = backendField("shared").get(null);
        previousFailure = backendField("sharedFailure").get(null);
        previousFailureAt = backendField("sharedFailureAt").get(null);
        backendField("shared").set(null, null);
        backendField("sharedFailure").set(null, null);
        previousGpu = System.getProperty("go.gpu");
        System.setProperty("go.gpu", "true");
    }

    @AfterEach
    void restoreSharedBackend() throws Exception {
        OpenCLBackend backend = (OpenCLBackend) backendField("shared").get(null);
        if (backend != null) {
            var destroy = OpenCLBackend.class.getDeclaredMethod("destroyNative");
            destroy.setAccessible(true);
            destroy.invoke(backend);
        }
        backendField("shared").set(null, previousShared);
        backendField("sharedFailure").set(null, previousFailure);
        backendField("sharedFailureAt").set(null, previousFailureAt);
        if (previousGpu == null) System.clearProperty("go.gpu");
        else System.setProperty("go.gpu", previousGpu);
    }

    @Test
    void failedAcquisitionRetriesOnlyAfterCooldownAndClearsFailure() throws Exception {
        OpenCLBackend unavailable = new OpenCLBackend(false);
        OpenCLBackend recovered = availableBackend();
        AtomicInteger attempts = new AtomicInteger();
        Supplier<OpenCLBackend> factory = () -> attempts.incrementAndGet() == 1 ? unavailable : recovered;
        long start = System.nanoTime();
        assertNull(OpenCLBackend.acquireShared(factory, start));
        assertNotNull(OpenCLBackend.lastSharedFailure());
        assertTrue(backendField("closed").getBoolean(unavailable));
        for (int i = 0; i < 20; i++) {
            assertNull(OpenCLBackend.acquireShared(factory, start + OpenCLBackend.RETRY_DELAY_NANOS - 1));
        }
        assertEquals(1, attempts.get());
        assertSame(recovered, OpenCLBackend.acquireShared(factory, start + OpenCLBackend.RETRY_DELAY_NANOS));
        assertEquals(2, attempts.get());
        assertNull(OpenCLBackend.lastSharedFailure());
        recovered.close();
    }

    @Test
    void existingEvaluatorRecoversAndReleasedEvaluatorCannotReacquire() throws Exception {
        long start = System.nanoTime();
        assertNull(OpenCLBackend.acquireShared(() -> new OpenCLBackend(false), start));
        NeuralEvaluator evaluator = new NeuralEvaluator();
        OpenCLBackend recovered = availableBackend();
        try {
            assertFalse(evaluator.isGpuActive());
            assertSame(recovered, OpenCLBackend.acquireShared(() -> recovered,
                    start + OpenCLBackend.RETRY_DELAY_NANOS));
            assertFalse(evaluator.isGpuActive(), "the evaluator must respect its own cooldown");
            evaluatorField("openclFailureAt").setLong(evaluator,
                    System.nanoTime() - OpenCLBackend.RETRY_DELAY_NANOS);
            assertTrue(evaluator.isGpuActive());
            assertEquals("test GPU", evaluator.gpuDeviceName());
            evaluator.release();
            assertTrue(recovered.isAvailable(), "the pool's other owner still holds a reference");
            assertFalse(evaluator.isGpuActive());
            assertEquals("CPU", evaluator.gpuDeviceName());
            assertNull(evaluatorField("opencl").get(evaluator));
            recovered.close();
            assertFalse(recovered.isAvailable());
        } finally {
            evaluator.release();
            recovered.close();
        }
    }

    @Test
    void lastOwnerFreesBackendAndLaterAcquisitionCreatesNewContext() throws Exception {
        OpenCLBackend first = availableBackend();
        assertSame(first, OpenCLBackend.acquireShared(() -> first, 0));
        assertSame(first, OpenCLBackend.acquireShared(() -> fail("unexpected reinitialization"), 0));
        first.close();
        assertTrue(first.isAvailable());
        assertSame(first, OpenCLBackend.peekShared());
        first.close();
        assertFalse(first.isAvailable());
        assertTrue(backendField("closed").getBoolean(first));
        assertNull(OpenCLBackend.peekShared());
        OpenCLBackend second = availableBackend();
        assertSame(second, OpenCLBackend.acquireShared(() -> second, 0));
        first.close();
        assertTrue(second.isAvailable(), "closing an old context must not close its replacement");
        second.close();
        assertFalse(second.isAvailable());
    }

    @Test
    void finalCloseWaitsForActiveNativeBatch() throws Exception {
        OpenCLBackend backend = availableBackend();
        OpenCLBackend.acquireShared(() -> backend, 0);
        ReentrantLock nativeLock = (ReentrantLock) backendField("lifecycleLock").get(backend);
        CountDownLatch started = new CountDownLatch(1);
        FutureTask<Void> closing = new FutureTask<>(() -> {
            started.countDown();
            backend.close();
            return null;
        });
        Thread closer = new Thread(closing, "test-opencl-close");
        nativeLock.lock();
        try {
            closer.start();
            assertTrue(started.await(2, TimeUnit.SECONDS));
            assertThrows(TimeoutException.class, () -> closing.get(100, TimeUnit.MILLISECONDS));
            assertTrue(backend.isAvailable());
        } finally {
            nativeLock.unlock();
            closer.join(2_000);
        }
        closing.get(2, TimeUnit.SECONDS);
        assertFalse(backend.isAvailable());
        assertNull(OpenCLBackend.peekShared());
    }

    @Test
    void partialNativeInitializationStillReleasesCreatedHandles() throws Exception {
        for (int stage = 0; stage < 4; stage++) {
            OpenCLBackend initialized = new OpenCLBackend();
            OpenCLBackend partial = new OpenCLBackend(false);
            try {
                assumeTrue(initialized.isAvailable(), "OpenCL GPU unavailable; cleanup requires real handles");
                backendField("cl").set(partial, backendField("cl").get(initialized));
                String[] handles = {"context", "queue", "program"};
                // Transfer ownership to model failure after each native allocation.
                for (int i = 0; i < stage; i++) {
                    backendField(handles[i]).set(partial, backendField(handles[i]).get(initialized));
                    backendField(handles[i]).set(initialized, null);
                }
                initialized.close();
                partial.close();
                assertTrue(backendField("closed").getBoolean(partial));
                assertFalse(partial.isAvailable());
                assertNull(backendField("cl").get(partial));
                for (String handle : handles) assertNull(backendField(handle).get(partial));
            } finally {
                initialized.close();
                partial.close();
            }
        }
    }

    @Test
    void interruptedInFlightRequestKeepsInputsAliveAndReturnsCompletedResult() throws Exception {
        exerciseInterruptedRequest(true);
    }

    @Test
    void interruptedQueuedRequestIsRemovedWithoutWaitingForAnotherBatch() throws Exception {
        exerciseInterruptedRequest(false);
    }

    @SuppressWarnings("unchecked")
    private void exerciseInterruptedRequest(boolean inFlight) throws Exception {
        NeuralEvaluator evaluator = new NeuralEvaluator();
        ReentrantLock drain = (ReentrantLock) evaluatorField("gpuInferDrain").get(evaluator);
        ConcurrentLinkedQueue<Object> queue = (ConcurrentLinkedQueue<Object>) evaluatorField("gpuInferQueue").get(evaluator);
        var coalesce = NeuralEvaluator.class.getDeclaredMethod("coalesceGpuForward",
                OpenCLBackend.class, double[][][].class, double[].class);
        coalesce.setAccessible(true);
        double[][][] planes = new double[4][19][19];
        planes[0][0][0] = 1.0;
        AtomicBoolean interruptPreserved = new AtomicBoolean();
        FutureTask<NeuralEvaluator.ForwardResult> pending = new FutureTask<>(() -> {
            var result = (NeuralEvaluator.ForwardResult) coalesce.invoke(evaluator, null, planes, new double[24]);
            interruptPreserved.set(Thread.currentThread().isInterrupted());
            planes[0][0][0] = 2.0; // Caller reuses its thread-local input after returning.
            return result;
        });
        Thread caller = new Thread(pending, "test-gpu-waiter");
        caller.setDaemon(true);
        CompletableFuture<NeuralEvaluator.ForwardResult> completion = null;
        drain.lock();
        try {
            caller.start();
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2);
            while (queue.isEmpty() && System.nanoTime() < deadline) Thread.sleep(1);
            Object call = inFlight ? queue.poll() : queue.peek();
            assertNotNull(call, "request was not enqueued");
            var doneField = call.getClass().getDeclaredField("done");
            doneField.setAccessible(true);
            completion = (CompletableFuture<NeuralEvaluator.ForwardResult>) doneField.get(call);
            caller.interrupt();
            if (inFlight) {
                assertThrows(TimeoutException.class, () -> pending.get(100, TimeUnit.MILLISECONDS));
                assertFalse(completion.isDone(), "interrupt must not overwrite the consumer's result");
                assertEquals(1.0, planes[0][0][0], "input must remain owned by the batch");
                var expected = new NeuralEvaluator.ForwardResult(0.75, new double[362]);
                completion.complete(expected);
                assertSame(expected, pending.get(2, TimeUnit.SECONDS));
            } else {
                assertNull(pending.get(2, TimeUnit.SECONDS));
                assertTrue(completion.isCancelled());
                assertTrue(queue.isEmpty());
            }
            assertTrue(interruptPreserved.get());
            assertEquals(2.0, planes[0][0][0]);
            assertEquals(0, ((AtomicInteger) evaluatorField("gpuInferWaiters").get(evaluator)).get());
        } finally {
            if (completion != null) completion.complete(null);
            drain.unlock();
            caller.join(2_000);
            evaluator.release();
        }
    }

    @Test
    void compactSubWeightsMatchCpuAcrossBatchSizesAndModelSwitchesOnGpu() {
        try (OpenCLBackend backend = OpenCLBackend.acquireShared()) {
            assumeTrue(backend != null, "OpenCL GPU unavailable; native parity requires a device");
            System.setProperty("go.gpu", "false");
            NeuralEvaluator first = new NeuralEvaluator();
            NeuralEvaluator second = new NeuralEvaluator();
            try {
                Random random = new Random(72831);
                for (int pass = 0; pass < 4; pass++) {
                    NeuralEvaluator cpu = pass == 1 ? second : first;
                    NeuralEvaluator.ModelWeights weights = cpu.snapshot();
                    if (pass == 3) {
                        for (int block = 0; block < 9; block++) {
                            weights.subB1[block][block] += 0.2 * (block + 1);
                        }
                        cpu.apply(weights);
                        weights = cpu.snapshot();
                    }
                    int size = pass + 1;
                    double[][][][] planes = new double[size][4][19][19];
                    double[][] aux = new double[size][24];
                    NeuralEvaluator.ForwardResult[] expected = new NeuralEvaluator.ForwardResult[size];
                    for (int n = 0; n < size; n++) {
                        for (double[][] plane : planes[n]) for (double[] row : plane) {
                            for (int y = 0; y < row.length; y++) row[y] = random.nextDouble();
                        }
                        for (int i = 0; i < 24; i++) aux[n][i] = random.nextDouble();
                        expected[n] = cpu.forward(planes[n], aux[n]);
                    }
                    double[][] policy = new double[size][362];
                    double[] value = new double[size];
                    assertTrue(backend.inferForward(planes, aux, size, pass == 1 ? 2 : 1, pass == 3 ? 1 : 0,
                            weights.subW1, weights.subB1, weights.blockW1, weights.blockB1,
                            weights.topW1, weights.topB1, weights.policyW, weights.policyB,
                            weights.valueW1, weights.valueB1, weights.valueW2, weights.valueB2, policy, value),
                            "native inference must succeed, without a hidden CPU fallback");
                    for (int n = 0; n < size; n++) {
                        assertEquals(expected[n].value, value[n], 1e-5);
                        assertArrayEquals(expected[n].policy, policy[n], 1e-5);
                    }
                }
            } finally {
                first.release();
                second.release();
            }
        }
    }

    private static OpenCLBackend availableBackend() throws Exception {
        OpenCLBackend backend = new OpenCLBackend(false);
        backendField("available").setBoolean(backend, true);
        backendField("deviceName").set(backend, "test GPU");
        return backend;
    }

    private static Field backendField(String name) throws Exception {
        Field field = OpenCLBackend.class.getDeclaredField(name);
        field.setAccessible(true);
        return field;
    }

    private static Field evaluatorField(String name) throws Exception {
        Field field = NeuralEvaluator.class.getDeclaredField(name);
        field.setAccessible(true);
        return field;
    }
}
