package com.wzz.game_console.client.screens.games.gogame;

import com.wzz.game_console.util.GameSettings;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.DataOutputStream;
import java.lang.reflect.Field;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

/**
 * 运行时 checkpoint 加载契约回归测试：{@code go.modelPath} 指向训练入口保存的
 * NEV2/NEV3 权重文件时，{@link MCTSGoAI#createFromSettings()} 必须把它接入对局；
 * 缺失或损坏时必须静默回退到随机初始化，绝不抛异常。
 */
class MCTSGoAICheckpointLoadTest {

    @TempDir
    Path tempDir;

    private Object originalSettings;
    private boolean originalLoaded;
    private Field settingsField;
    private Field loadedField;

    @BeforeEach
    void snapshotGameSettingsState() throws Exception {
        settingsField = GameSettings.class.getDeclaredField("settings");
        settingsField.setAccessible(true);
        loadedField = GameSettings.class.getDeclaredField("loaded");
        loadedField.setAccessible(true);
        originalSettings = settingsField.get(null);
        originalLoaded = loadedField.getBoolean(null);
    }

    @AfterEach
    void restoreGameSettingsState() throws Exception {
        settingsField.set(null, originalSettings);
        loadedField.setBoolean(null, originalLoaded);
    }

    /** 只改内存快照，不落盘（importFromFile 会持久化到 data/，测试不得污染）。 */
    private void setModelPath(String modelPath) throws Exception {
        Map<String, Object> go = modelPath == null
                ? Map.of()
                : Map.of("modelPath", modelPath);
        settingsField.set(null, Map.of("go", go));
        loadedField.setBoolean(null, true);
    }

    private static NeuralEvaluator evaluatorOf(MCTSGoAI ai) throws Exception {
        Field f = MCTSGoAI.class.getDeclaredField("neuralEvaluator");
        f.setAccessible(true);
        return (NeuralEvaluator) f.get(ai);
    }

    private static double probeValue(NeuralEvaluator evaluator) {
        GoPlayer[][] board = new GoPlayer[GoAI.BOARD_SIZE][GoAI.BOARD_SIZE];
        for (GoPlayer[] row : board) java.util.Arrays.fill(row, GoPlayer.NONE);
        board[9][9] = GoPlayer.BLACK;
        board[3][3] = GoPlayer.WHITE;
        return evaluator.forwardValue(board, GoPlayer.BLACK, new int[]{9, 9});
    }

    @Test
    void configuredNev3CheckpointIsUsedByCreatedAi() throws Exception {
        NeuralEvaluator reference = new NeuralEvaluator();
        Path model = tempDir.resolve("trained.nev3");
        reference.save(model);

        setModelPath(model.toString());
        MCTSGoAI ai = MCTSGoAI.createFromSettings();
        assertNotNull(ai);
        NeuralEvaluator loaded = evaluatorOf(ai);
        NeuralEvaluator direct = new NeuralEvaluator();
        direct.load(model);
        assertEquals(direct.getModelVersion(), loaded.getModelVersion());
        assertEquals(probeValue(direct), probeValue(loaded), 1e-12,
                "created AI must run on the checkpoint weights, not random init");
    }

    @Test
    void legacyDoubleFormatCheckpointLoadsAtRuntime() throws Exception {
        Nev2File file = writeNev2(tempDir.resolve("legacy.nev2"));

        setModelPath(file.path().toString());
        MCTSGoAI ai = MCTSGoAI.createFromSettings();
        assertNotNull(ai);
        NeuralEvaluator loaded = evaluatorOf(ai);
        assertEquals(file.expectedVersion(), loaded.getModelVersion(),
                "legacy checkpoint version must survive the runtime load path");
        assertEquals(probeValue(file.reference()), probeValue(loaded), 1e-12);
    }

    @Test
    void corruptModelFileFallsBackToRandomInitWithoutThrowing() throws Exception {
        Path model = tempDir.resolve("corrupt.nev");
        Files.writeString(model, "this is not a model file");

        setModelPath(model.toString());
        MCTSGoAI ai = MCTSGoAI.createFromSettings();
        assertNotNull(ai);
        assertEquals(0L, evaluatorOf(ai).getModelVersion(),
                "fallback must be a fresh random-init evaluator");
    }

    @Test
    void missingModelFileFallsBackToRandomInitWithoutThrowing() throws Exception {
        setModelPath(tempDir.resolve("absent.nev").toString());
        MCTSGoAI ai = MCTSGoAI.createFromSettings();
        assertNotNull(ai);
        assertEquals(0L, evaluatorOf(ai).getModelVersion());
    }

    @Test
    void emptyModelPathUsesRandomInit() throws Exception {
        setModelPath("");
        MCTSGoAI ai = MCTSGoAI.createFromSettings();
        assertNotNull(ai);
        assertEquals(0L, evaluatorOf(ai).getModelVersion());
    }

    /** NEV2（double）格式的手工编码文件 + 独立参考加载器，用于断言运行时等价。 */
    private record Nev2File(Path path, NeuralEvaluator reference, long expectedVersion) {}

    private static Nev2File writeNev2(Path path) throws Exception {
        NeuralEvaluator source = new NeuralEvaluator();
        NeuralEvaluator.ModelWeights m = source.snapshot();
        try (DataOutputStream out = new DataOutputStream(Files.newOutputStream(path))) {
            out.writeInt(0x4E455632); // LEGACY_MODEL_MAGIC "NEV2"
            out.writeInt(2);          // LEGACY_MODEL_FORMAT
            out.writeLong(m.version);
            for (double[][] block : m.subW1) writeMatrixDoubles(out, block);
            for (double[] bias : m.subB1) writeVectorDoubles(out, bias);
            for (double[][] block : m.blockW1) writeMatrixDoubles(out, block);
            for (double[] bias : m.blockB1) writeVectorDoubles(out, bias);
            writeMatrixDoubles(out, m.topW1);
            writeVectorDoubles(out, m.topB1);
            writeMatrixDoubles(out, m.policyW);
            writeVectorDoubles(out, m.policyB);
            writeMatrixDoubles(out, m.valueW1);
            writeVectorDoubles(out, m.valueB1);
            writeVectorDoubles(out, m.valueW2);
            out.writeDouble(m.valueB2);
        }
        NeuralEvaluator reference = new NeuralEvaluator();
        reference.load(path);
        return new Nev2File(path, reference, m.version);
    }

    private static void writeMatrixDoubles(DataOutputStream out, double[][] matrix) throws Exception {
        for (double[] row : matrix) writeVectorDoubles(out, row);
    }

    private static void writeVectorDoubles(DataOutputStream out, double[] vector) throws Exception {
        for (double v : vector) out.writeDouble(v);
    }
}
