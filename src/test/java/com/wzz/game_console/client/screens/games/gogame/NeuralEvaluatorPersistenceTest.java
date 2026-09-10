package com.wzz.game_console.client.screens.games.gogame;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class NeuralEvaluatorPersistenceTest {
    private static final int FORMAT_OFFSET = Integer.BYTES;
    private static final int FIRST_WEIGHT_OFFSET = Integer.BYTES * 2 + Long.BYTES;

    @TempDir
    Path tempDir;

    @Test
    void currentFormatRoundTripPreservesModel() throws Exception {
        Path model = tempDir.resolve("round-trip.nev");
        NeuralEvaluator source = new NeuralEvaluator();
        NeuralEvaluator.ModelWeights expected;
        try {
            expected = source.snapshot();
            source.save(model);
        } finally {
            source.release();
        }

        NeuralEvaluator target = new NeuralEvaluator();
        try {
            target.load(model);
            NeuralEvaluator.ModelWeights actual = target.snapshot();
            assertEquals(expected.version, actual.version);
            assertEquals((double) (float) expected.subW1[0][0][0], actual.subW1[0][0][0]);
            assertEquals((double) (float) expected.valueB2, actual.valueB2);
        } finally {
            target.release();
        }
    }

    @Test
    void rejectsUnknownVersionWithoutChangingCurrentModel() throws Exception {
        Path model = saveModel("unknown-format.nev");
        byte[] bytes = Files.readAllBytes(model);
        ByteBuffer.wrap(bytes).order(ByteOrder.BIG_ENDIAN).putInt(FORMAT_OFFSET, 999);
        Files.write(model, bytes);

        assertRejectedWithoutMutation(model);
    }

    @Test
    void rejectsNonFiniteWeightWithoutChangingCurrentModel() throws Exception {
        Path model = saveModel("nan-weight.nev");
        byte[] bytes = Files.readAllBytes(model);
        ByteBuffer.wrap(bytes).order(ByteOrder.BIG_ENDIAN)
                .putInt(FIRST_WEIGHT_OFFSET, Float.floatToRawIntBits(Float.NaN));
        Files.write(model, bytes);

        assertRejectedWithoutMutation(model);
    }

    @Test
    void rejectsTrailingDataWithoutChangingCurrentModel() throws Exception {
        Path model = saveModel("trailing-data.nev");
        Files.write(model, new byte[]{1}, java.nio.file.StandardOpenOption.APPEND);

        assertRejectedWithoutMutation(model);
    }

    private Path saveModel(String name) throws IOException {
        Path model = tempDir.resolve(name);
        NeuralEvaluator evaluator = new NeuralEvaluator();
        try {
            evaluator.save(model);
        } finally {
            evaluator.release();
        }
        return model;
    }

    private void assertRejectedWithoutMutation(Path model) {
        NeuralEvaluator evaluator = new NeuralEvaluator();
        try {
            NeuralEvaluator.ModelWeights before = evaluator.snapshot();
            assertThrows(IOException.class, () -> evaluator.load(model));
            NeuralEvaluator.ModelWeights after = evaluator.snapshot();
            assertEquals(before.version, after.version);
            assertEquals(before.subW1[0][0][0], after.subW1[0][0][0]);
        } finally {
            evaluator.release();
        }
    }
}
