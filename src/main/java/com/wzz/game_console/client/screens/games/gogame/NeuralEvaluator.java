package com.wzz.game_console.client.screens.games.gogame;

import java.util.*;
import java.util.concurrent.locks.ReentrantReadWriteLock;
import com.wzz.game_console.util.GameSettings;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;

/**
 * 三级分块隔离神经网络评估器。
 * <p>
 * 架构：
 * <pre>
 * 输入: 4×19×19 平面
 *   → 二级子块(81个, 3×3×4→FC(36→16)→ReLU) × 9套独立权重
 *   → 一级字块(9个, 9×16→FC(144→64)→ReLU) × 9套独立权重
 *   → 顶级(9×64+24→FC(600→256)→ReLU) → 共享256维
 *     → 策略头: FC(256→362) + softmax
 *     → 价值头: FC(256→128) + ReLU → FC(128→1) + tanh
 * </pre>
 */
public class NeuralEvaluator {

    // ══════════════════════════════════════════════════════════════════════
    //  常量
    // ══════════════════════════════════════════════════════════════════════

    private static final int BOARD_SIZE = 19;
    private static final int PLANES = 4;

    // 分块网格
    private static final int BLOCK_SIZE = 7;           // 一级字块尺寸
    private static final int BLOCK_OVERLAP = 1;         // 一级字块重叠
    private static final int BLOCK_STRIDE = BLOCK_SIZE - BLOCK_OVERLAP; // 6
    private static final int BLOCKS_PER_DIM = 3;        // 每维块数
    private static final int NUM_BLOCKS = 9;            // 总块数

    private static final int SUB_SIZE = 3;              // 二级子块尺寸
    private static final int SUB_OVERLAP = 1;           // 二级子块重叠
    private static final int SUB_STRIDE = SUB_SIZE - SUB_OVERLAP; // 2
    private static final int SUBS_PER_BLOCK = 9;        // 每大块子块数

    // 网络维度
    private static final int SUB_INPUT = SUB_SIZE * SUB_SIZE * PLANES; // 36
    private static final int SUB_HIDDEN = 16;
    private static final int BLOCK_INPUT = SUBS_PER_BLOCK * SUB_HIDDEN; // 144
    private static final int BLOCK_HIDDEN = 64;
    private static final int TOP_INPUT = NUM_BLOCKS * BLOCK_HIDDEN + 24; // 576+24=600
    private static final int TOP_HIDDEN = 256;
    private static final int POLICY_SIZE = 362;  // 361 moves + pass
    private static final int VALUE_HIDDEN = 128;

    // 辅助特征维度（沿用旧版）
    private static final int LIBERTY_HIST_BINS = 8;
    private static final int EYE_FEATURE_SIZE = 8;
    private static final int GLOBAL_FEATURE_SIZE = 8;
    private static final int AUX_SIZE = LIBERTY_HIST_BINS + EYE_FEATURE_SIZE + GLOBAL_FEATURE_SIZE; // 24

    // 持久化
    private static final int MODEL_MAGIC = 0x4E455633; // NEV3
    private static final int MODEL_FORMAT = 3;
    private static final int MAX_CACHE_SIZE = 10000;

    // 四方向
    private static final int[][] DIRS = {{0, 1}, {1, 0}, {0, -1}, {-1, 0}};

    // ══════════════════════════════════════════════════════════════════════
    //  分块索引（静态计算）
    // ══════════════════════════════════════════════════════════════════════

    /** 9 个一级字块的左上角 (bx, by) */
    private static final int[][] BLOCK_STARTS = new int[9][2];
    /** 每个一级字块内 9 个二级子块的左上角偏移 (sx, sy) 相对于大块起点 */
    private static final int[][] SUB_OFFSETS = new int[9][2];

    static {
        // 一级字块起始位置
        for (int bx = 0; bx < BLOCKS_PER_DIM; bx++) {
            for (int by = 0; by < BLOCKS_PER_DIM; by++) {
                int idx = bx * BLOCKS_PER_DIM + by;
                BLOCK_STARTS[idx][0] = bx * BLOCK_STRIDE;
                BLOCK_STARTS[idx][1] = by * BLOCK_STRIDE;
            }
        }
        // 二级子块偏移
        for (int sx = 0; sx < BLOCKS_PER_DIM; sx++) {
            for (int sy = 0; sy < BLOCKS_PER_DIM; sy++) {
                int idx = sx * BLOCKS_PER_DIM + sy;
                SUB_OFFSETS[idx][0] = sx * SUB_STRIDE;
                SUB_OFFSETS[idx][1] = sy * SUB_STRIDE;
            }
        }
    }

    // ══════════════════════════════════════════════════════════════════════
    //  权重矩阵
    // ══════════════════════════════════════════════════════════════════════

    // 第 1 级：二级子块（9 套独立权重）
    private final double[][][] subW1;  // [block][input=36][hidden=16]
    private final double[][] subB1;    // [block][hidden=16]

    // 第 2 级：一级字块（9 套独立权重）
    private final double[][][] blockW1; // [block][input=144][hidden=64]
    private final double[][] blockB1;   // [block][hidden=64]

    // 第 3 级：顶级（共享权重）
    private final double[][] topW1;     // [600][256]
    private final double[] topB1;       // [256]

    // 策略头
    private final double[][] policyW;   // [256][362]
    private final double[] policyB;     // [362]

    // 价值头
    private final double[][] valueW1;   // [256][128]
    private final double[] valueB1;     // [128]
    private final double[] valueW2;     // [128]
    private double valueB2;

    private final ReentrantReadWriteLock modelLock = new ReentrantReadWriteLock();
    private volatile long modelVersion;

    // 动量缓冲（惰性分配，首次 momentum > 0 训练时创建）
    private double[][][] vSubW1;
    private double[][] vSubB1;
    private double[][][] vBlockW1;
    private double[][] vBlockB1;
    private double[][] vTopW1;
    private double[] vTopB1;
    private double[][] vPolicyW;
    private double[] vPolicyB;
    private double[][] vValueW1;
    private double[] vValueB1;
    private double[] vValueW2;
    private double vValueB2;

    // 评估缓存（Zobrist 哈希 -> 评估值，仅用于旧版 evaluate 接口）
    private final Map<CacheKey, Double> evaluationCache = new HashMap<>();

    /** OpenCL GPU 加速后端（懒初始化，失败自动回退 CPU） */
    private volatile OpenCLBackend opencl;

    // ══════════════════════════════════════════════════════════════════════
    //  构造
    // ══════════════════════════════════════════════════════════════════════

    public NeuralEvaluator() {
        this(false);
    }

    /** 私有构造：skipInit=true 时跳过随机初始化（默认构造调用 initWeights） */
    private NeuralEvaluator(boolean skipInit) {
        this.subW1 = new double[NUM_BLOCKS][SUB_INPUT][SUB_HIDDEN];
        this.subB1 = new double[NUM_BLOCKS][SUB_HIDDEN];
        this.blockW1 = new double[NUM_BLOCKS][BLOCK_INPUT][BLOCK_HIDDEN];
        this.blockB1 = new double[NUM_BLOCKS][BLOCK_HIDDEN];
        this.topW1 = new double[TOP_INPUT][TOP_HIDDEN];
        this.topB1 = new double[TOP_HIDDEN];
        this.policyW = new double[TOP_HIDDEN][POLICY_SIZE];
        this.policyB = new double[POLICY_SIZE];
        this.valueW1 = new double[TOP_HIDDEN][VALUE_HIDDEN];
        this.valueB1 = new double[VALUE_HIDDEN];
        this.valueW2 = new double[VALUE_HIDDEN];
        this.valueB2 = 0.0;
        this.modelVersion = 0L;
        if (!skipInit) initWeights();
    }

    /**
     * 直接从模型快照构造评估器（跳过随机初始化，省去一倍的 init+apply 开销）。
     * 用于并行自对弈的每局 worker，避免频繁创建。
     */
    public static NeuralEvaluator fromWeights(ModelWeights m) {
        // 跳过随机 init（省去一倍的 init+apply 开销），直接 apply 模型快照
        NeuralEvaluator e = new NeuralEvaluator(true);
        e.apply(m);
        return e;
    }

    /** 前向传播结果 */
    public static final class ForwardResult {
        public final double value;
        public final double[] policy;
        public ForwardResult(double value, double[] policy) {
            this.value = value;
            this.policy = policy;
        }
    }

    // ══════════════════════════════════════════════════════════════════════
    //  权重初始化
    // ══════════════════════════════════════════════════════════════════════

    private void initWeights() {
        Random rnd = new Random(42);
        // 二级子块（9 套）
        for (int b = 0; b < NUM_BLOCKS; b++) {
            for (int i = 0; i < SUB_INPUT; i++)
                for (int j = 0; j < SUB_HIDDEN; j++)
                    subW1[b][i][j] = rnd.nextGaussian() * 0.5 / Math.sqrt(SUB_INPUT);
            Arrays.fill(subB1[b], 0.01);
        }
        // 一级字块（9 套）
        for (int b = 0; b < NUM_BLOCKS; b++) {
            for (int i = 0; i < BLOCK_INPUT; i++)
                for (int j = 0; j < BLOCK_HIDDEN; j++)
                    blockW1[b][i][j] = rnd.nextGaussian() * 0.5 / Math.sqrt(BLOCK_INPUT);
            Arrays.fill(blockB1[b], 0.01);
        }
        // 顶级
        for (int i = 0; i < TOP_INPUT; i++)
            for (int j = 0; j < TOP_HIDDEN; j++)
                topW1[i][j] = rnd.nextGaussian() * 0.5 / Math.sqrt(TOP_INPUT);
        Arrays.fill(topB1, 0.01);
        // 策略头
        for (int i = 0; i < TOP_HIDDEN; i++)
            for (int j = 0; j < POLICY_SIZE; j++)
                policyW[i][j] = rnd.nextGaussian() * 0.5 / Math.sqrt(TOP_HIDDEN);
        Arrays.fill(policyB, 0.0);
        // 价值头
        for (int i = 0; i < TOP_HIDDEN; i++)
            for (int j = 0; j < VALUE_HIDDEN; j++)
                valueW1[i][j] = rnd.nextGaussian() * 0.5 / Math.sqrt(TOP_HIDDEN);
        Arrays.fill(valueB1, 0.01);
        for (int i = 0; i < VALUE_HIDDEN; i++)
            valueW2[i] = rnd.nextGaussian() * 0.5 / Math.sqrt(VALUE_HIDDEN);
        valueB2 = 0.0;
    }

    // ══════════════════════════════════════════════════════════════════════
    //  输入平面构建
    // ══════════════════════════════════════════════════════════════════════

    /**
     * 构建 4×19×19 输入平面。
     * 平面0: 己方棋子
     * 平面1: 对方棋子
     * 平面2: 空点气数归一化
     * 平面3: 上一步落子位置
     */
    public double[][][] buildInputPlanes(GoPlayer[][] board, GoPlayer player, int[] lastMove) {
        double[][][] planes = new double[PLANES][BOARD_SIZE][BOARD_SIZE];
        GoPlayer opponent = player == GoPlayer.BLACK ? GoPlayer.WHITE : GoPlayer.BLACK;

        for (int x = 0; x < BOARD_SIZE; x++) {
            for (int y = 0; y < BOARD_SIZE; y++) {
                if (board[x][y] == player) {
                    planes[0][x][y] = 1.0;
                } else if (board[x][y] == opponent) {
                    planes[1][x][y] = 1.0;
                } else {
                    // 空点：计算气数
                    int libs = countEmptyLiberties(board, x, y);
                    if (libs >= 3) planes[2][x][y] = 1.0;
                    else if (libs == 2) planes[2][x][y] = 0.5;
                    else if (libs == 1) planes[2][x][y] = 0.25;
                }
                // 上一步落子位置
                if (lastMove != null && lastMove.length >= 2 && lastMove[0] == x && lastMove[1] == y) {
                    planes[3][x][y] = 1.0;
                }
            }
        }
        return planes;
    }

    /** 计算空点 x,y 周围的气数（仅看相邻空点+同色连通） */
    private int countEmptyLiberties(GoPlayer[][] board, int x, int y) {
        int libs = 0;
        for (int[] d : DIRS) {
            int nx = x + d[0], ny = y + d[1];
            if (nx >= 0 && nx < BOARD_SIZE && ny >= 0 && ny < BOARD_SIZE
                    && board[nx][ny] == GoPlayer.NONE) {
                libs++;
            }
        }
        return libs;
    }

    // ══════════════════════════════════════════════════════════════════════
    //  辅助特征（24 维，沿用旧版计算逻辑）
    // ══════════════════════════════════════════════════════════════════════

    /**
     * 提取 24 维全局辅助特征：气数直方图(8) + 眼形特征(8) + 全局特征(8)。
     */
    public double[] extractAuxFeatures(GoPlayer[][] board, GoPlayer player) {
        double[] aux = new double[AUX_SIZE];
        GoPlayer opponent = player == GoPlayer.BLACK ? GoPlayer.WHITE : GoPlayer.BLACK;
        int BS = BOARD_SIZE;

        // ── 气数直方图 [0..7] ──────────────────────────────────────────
        int[] libertyHist = new int[LIBERTY_HIST_BINS];
        boolean[][] visited = new boolean[BS][BS];
        for (int x = 0; x < BS; x++) {
            for (int y = 0; y < BS; y++) {
                if (board[x][y] != GoPlayer.NONE && !visited[x][y]) {
                    Set<int[]> group = getGroup(board, x, y);
                    int libs = countGroupLiberties(board, group);
                    int bin = Math.min(libs, LIBERTY_HIST_BINS - 1);
                    libertyHist[bin]++;
                    for (int[] p : group) visited[p[0]][p[1]] = true;
                }
            }
        }
        int histSum = 0;
        for (int v : libertyHist) histSum += v;
        for (int i = 0; i < LIBERTY_HIST_BINS; i++) {
            aux[i] = histSum > 0 ? (libertyHist[i] * 2.0 / histSum - 1.0) : 0.0;
        }

        // ── 眼形特征 [8..15] ──────────────────────────────────────────
        int myTrueEyes = 0, myFalseEyes = 0, oppTrueEyes = 0, oppFalseEyes = 0;
        for (int x = 0; x < BS; x++) {
            for (int y = 0; y < BS; y++) {
                if (board[x][y] != GoPlayer.NONE) {
                    EyeInfo eye = analyzeEye(board, x, y);
                    if (board[x][y] == player) {
                        if (eye.isTrue) myTrueEyes++; else if (eye.isPotential) myFalseEyes++;
                    } else {
                        if (eye.isTrue) oppTrueEyes++; else if (eye.isPotential) oppFalseEyes++;
                    }
                }
            }
        }
        aux[8] = (myTrueEyes - myFalseEyes) / 5.0;
        aux[9] = (oppTrueEyes - oppFalseEyes) / 5.0;

        // 眼大小分布
        int cornerEyesMy = 0, cornerEyesOpp = 0, centerEyesMy = 0, centerEyesOpp = 0;
        int cornerZone = 3, centerZone = BS / 2 - 2;
        for (int x = 0; x < BS; x++) {
            for (int y = 0; y < BS; y++) {
                if (board[x][y] == GoPlayer.NONE) {
                    int distToEdge = Math.min(Math.min(x, y), Math.min(BS - 1 - x, BS - 1 - y));
                    boolean isCorner = distToEdge <= cornerZone;
                    boolean isCenter = x >= centerZone && x < BS - centerZone && y >= centerZone && y < BS - centerZone;
                    double surrounding = countSurrounding(board, x, y, player);
                    double oppSurrounding = countSurrounding(board, x, y, opponent);
                    if (surrounding > oppSurrounding + 0.5) {
                        if (isCorner) cornerEyesMy++; else if (isCenter) centerEyesMy++;
                    } else if (oppSurrounding > surrounding + 0.5) {
                        if (isCorner) cornerEyesOpp++; else if (isCenter) centerEyesOpp++;
                    }
                }
            }
        }
        aux[10] = (cornerEyesMy - cornerEyesOpp) / 20.0;
        aux[11] = (centerEyesMy - centerEyesOpp) / 20.0;

        // 眼形空洞
        int eyeHolesMy = 0, eyeHolesOpp = 0;
        for (int x = 0; x < BS; x++) {
            for (int y = 0; y < BS; y++) {
                if (board[x][y] == GoPlayer.NONE) {
                    int fn = countFriendlyNeighbors(board, x, y, player);
                    int on = countFriendlyNeighbors(board, x, y, opponent);
                    if (fn >= 4) eyeHolesMy++; else if (on >= 4) eyeHolesOpp++;
                }
            }
        }
        aux[12] = eyeHolesMy / 30.0;
        aux[13] = eyeHolesOpp / 30.0;

        // ── 全局特征 [16..23] ──────────────────────────────────────────
        int myStones = 0, oppStones = 0;
        for (int x = 0; x < BS; x++) for (int y = 0; y < BS; y++) {
            if (board[x][y] == player) myStones++; else if (board[x][y] == opponent) oppStones++;
        }
        aux[16] = (myStones - oppStones) / 100.0;

        double myControl = 0, oppControl = 0;
        int radius = 3;
        for (int x = 0; x < BS; x++) for (int y = 0; y < BS; y++) {
            if (board[x][y] == GoPlayer.NONE) {
                double myInf = 0, oppInf = 0;
                for (int dx = -radius; dx <= radius; dx++) for (int dy = -radius; dy <= radius; dy++) {
                    int nx = x + dx, ny = y + dy;
                    if (nx >= 0 && nx < BS && ny >= 0 && ny < BS) {
                        double w = Math.sqrt(dx * dx + dy * dy);
                        w = w > 0 ? 1.0 / w : 1.0;
                        if (board[nx][ny] == player) myInf += w;
                        else if (board[nx][ny] == opponent) oppInf += w;
                    }
                }
                if (myInf > oppInf) myControl++; else if (oppInf > myInf) oppControl++;
            }
        }
        aux[17] = (myControl - oppControl) / 100.0;
        aux[18] = evaluateConnections(board, player) / 20.0;
        aux[19] = evaluateSeparation(board, player) / 20.0;
        aux[20] = evaluateStrategicPoints(board, player) / 10.0;
        TerritoryResult tr = evaluateTerritory(board, player);
        aux[21] = (tr.myTerritory - tr.oppTerritory) / 50.0;

        return aux;
    }

    // ══════════════════════════════════════════════════════════════════════
    //  前向传播
    // ══════════════════════════════════════════════════════════════════════

    /** GPU 加速是否启用（可选配置 go.gpu，默认开）。false 时一律走 CPU，不初始化 OpenCL。 */
    private static volatile Boolean gpuEnabledCache = null;

    private static boolean isGpuEnabled() {
        Boolean v = gpuEnabledCache;
        if (v != null) return v;
        // 1) 系统属性 -Dgo.gpu=false（训练 CLI 用）
        String prop = System.getProperty("go.gpu");
        if (prop != null) {
            v = Boolean.parseBoolean(prop);
            gpuEnabledCache = v;
            return v;
        }
        // 2) GameSettings 配置文件（MC 对局用）
        try {
            v = GameSettings.getBoolean("go", "gpu", true);
        } catch (Exception e) {
            v = true;
        }
        gpuEnabledCache = v;
        return v;
    }

    /**
     * 懒初始化 OpenCL 后端（GPU 启用且成功才使用，失败自动回退 CPU）。
     */
    private OpenCLBackend ensureOpenCL() {
        if (!isGpuEnabled()) return null; // GPU 可选：配置关闭时走 CPU
        if (opencl == null) {
            synchronized (this) {
                if (opencl == null) {
                    opencl = new OpenCLBackend();
                }
            }
        }
        return opencl.isAvailable() ? opencl : null;
    }

    /** 若 GPU 可用返回其设备名，否则 null */
    public String getGpuDevice() {
        OpenCLBackend b = ensureOpenCL();
        return b == null ? null : b.getDeviceName();
    }

    /**
     * 完整前向传播：三级分块 → 双头。
     */
    public ForwardResult forward(double[][][] planes, double[] auxFeatures) {
        modelLock.readLock().lock();
        try {
            // ── 第 1 级：二级子块 ──────────────────────────────────────
            // subOut[b][s][h] — 大块 b 的第 s 个子块的 16 维输出
            double[][][] subOut = new double[NUM_BLOCKS][SUBS_PER_BLOCK][SUB_HIDDEN];
            for (int b = 0; b < NUM_BLOCKS; b++) {
                int bx = BLOCK_STARTS[b][0], by = BLOCK_STARTS[b][1];
                double[] w1 = null; // flatten subW1[b] for fast access
                double[] b1 = subB1[b];
                for (int s = 0; s < SUBS_PER_BLOCK; s++) {
                    int sx = bx + SUB_OFFSETS[s][0], sy = by + SUB_OFFSETS[s][1];
                    // 提取 3×3×4 = 36 个值
                    double[] input = new double[SUB_INPUT];
                    int idx = 0;
                    for (int p = 0; p < PLANES; p++)
                        for (int dx = 0; dx < SUB_SIZE; dx++)
                            for (int dy = 0; dy < SUB_SIZE; dy++)
                                input[idx++] = planes[p][sx + dx][sy + dy];
                    // FC(36→16) + ReLU
                    for (int j = 0; j < SUB_HIDDEN; j++) {
                        double sum = b1[j];
                        for (int i = 0; i < SUB_INPUT; i++)
                            sum += subW1[b][i][j] * input[i];
                        subOut[b][s][j] = Math.max(0, sum);
                    }
                }
            }

            // ── 第 2 级：一级字块 ──────────────────────────────────────
            double[][] blockOut = new double[NUM_BLOCKS][BLOCK_HIDDEN];
            for (int b = 0; b < NUM_BLOCKS; b++) {
                // 拼接 9 个子块输出 → 144 维
                double[] input = new double[BLOCK_INPUT];
                int idx = 0;
                for (int s = 0; s < SUBS_PER_BLOCK; s++)
                    for (int h = 0; h < SUB_HIDDEN; h++)
                        input[idx++] = subOut[b][s][h];
                // FC(144→64) + ReLU
                for (int j = 0; j < BLOCK_HIDDEN; j++) {
                    double sum = blockB1[b][j];
                    for (int i = 0; i < BLOCK_INPUT; i++)
                        sum += blockW1[b][i][j] * input[i];
                    blockOut[b][j] = Math.max(0, sum);
                }
            }

            // ── 第 3 级：顶级 ──────────────────────────────────────────
            double[] topInput = new double[TOP_INPUT];
            int idx = 0;
            for (int b = 0; b < NUM_BLOCKS; b++)
                for (int h = 0; h < BLOCK_HIDDEN; h++)
                    topInput[idx++] = blockOut[b][h];
            // 拼接辅助特征
            System.arraycopy(auxFeatures, 0, topInput, NUM_BLOCKS * BLOCK_HIDDEN, AUX_SIZE);

            // FC(600→256) + ReLU
            double[] shared = new double[TOP_HIDDEN];
            for (int j = 0; j < TOP_HIDDEN; j++) {
                double sum = topB1[j];
                for (int i = 0; i < TOP_INPUT; i++)
                    sum += topW1[i][j] * topInput[i];
                shared[j] = Math.max(0, sum);
            }

            // ── 策略头 ──────────────────────────────────────────────────
            double[] policy = new double[POLICY_SIZE];
            double maxLogit = Double.NEGATIVE_INFINITY;
            for (int j = 0; j < POLICY_SIZE; j++) {
                double sum = policyB[j];
                for (int i = 0; i < TOP_HIDDEN; i++)
                    sum += policyW[i][j] * shared[i];
                if (sum > maxLogit) maxLogit = sum;
                policy[j] = sum;
            }
            // softmax（数值稳定版）
            double sumExp = 0;
            for (int j = 0; j < POLICY_SIZE; j++) {
                policy[j] = Math.exp(policy[j] - maxLogit);
                sumExp += policy[j];
            }
            double invSum = 1.0 / Math.max(sumExp, 1e-30);
            for (int j = 0; j < POLICY_SIZE; j++) policy[j] *= invSum;

            // ── 价值头 ──────────────────────────────────────────────────
            double[] vh = new double[VALUE_HIDDEN];
            for (int j = 0; j < VALUE_HIDDEN; j++) {
                double sum = valueB1[j];
                for (int i = 0; i < TOP_HIDDEN; i++)
                    sum += valueW1[i][j] * shared[i];
                vh[j] = Math.max(0, sum);
            }
            double valueSum = valueB2;
            for (int i = 0; i < VALUE_HIDDEN; i++)
                valueSum += valueW2[i] * vh[i];
            double value = Math.tanh(valueSum);

            return new ForwardResult(value, policy);
        } finally {
            modelLock.readLock().unlock();
        }
    }

    /**
     * 仅计算价值头（用于 MCTS 叶子评估，兼容旧接口）。
     */
    public double evaluate(GoPlayer[][] board, GoPlayer player) {
        return evaluate(board, player, null);
    }

    /**
     * 仅计算价值头（用于 MCTS 叶子评估，兼容旧接口）。
     */
    public double evaluate(GoPlayer[][] board, GoPlayer player, int[] lastMove) {
        long hash = computeZobristHash(board, player);
        // 上一手位置影响 plane 3，必须纳入缓存键，避免不同 lastMove 碰撞。
        // 用 (x+1,y+1) 编码避免 {0,0} 与 null 映射到 0 的碰撞。
        if (lastMove != null && lastMove.length >= 2) {
            long lm = ((long)(lastMove[0] + 1) * 32 + (lastMove[1] + 1));
            hash ^= lm * 0x9E3779B97F4A7C15L;
        }
        CacheKey key = new CacheKey(hash, modelVersion);
        synchronized (evaluationCache) {
            Double cached = evaluationCache.get(key);
            if (cached != null) return cached;
        }

        double[][][] planes = buildInputPlanes(board, player, lastMove);
        double[] aux = extractAuxFeatures(board, player);
        ForwardResult result = forward(planes, aux);

        // 混合评估（神经网络 60% + 启发式 40%），保持向后兼容
        double heuristicValue = heuristicEvaluation(board, player);
        double blendedValue = 0.6 * result.value + 0.4 * (heuristicValue / 100.0);

        synchronized (evaluationCache) {
            if (evaluationCache.size() < MAX_CACHE_SIZE)
                evaluationCache.put(new CacheKey(hash, modelVersion), blendedValue);
        }
        return blendedValue;
    }

    /**
     * 纯神经网络价值评估（不混合启发式），用于 MCTS 搜索。
     * 直接返回价值头输出（-1~1），不携带启发式偏差。
     */
    public double forwardValue(GoPlayer[][] board, GoPlayer player, int[] lastMove) {
        double[][][] planes = buildInputPlanes(board, player, lastMove);
        double[] aux = extractAuxFeatures(board, player);
        return forward(planes, aux).value;
    }

    /**
     * 仅计算策略头（用于 MCTS 节点扩展获取先验概率）。
     */
    public double[] forwardPolicy(double[][][] planes, double[] auxFeatures) {
        return forward(planes, auxFeatures).policy;
    }

    // ══════════════════════════════════════════════════════════════════════
    //  训练（反向传播）
    // ══════════════════════════════════════════════════════════════════════

    /**
     * 训练一个 mini-batch（双头 loss：value MSE + policy cross-entropy）。
     *
     * @param planes        [batch][4][19][19]
     * @param auxFeatures   [batch][24]
     * @param valueTargets  [batch]
     * @param policyTargets [batch][362]
     * @param learningRate  学习率
     * @param l2            L2 正则系数
     * @param gradientClip  梯度裁剪阈值
     * @param momentum      动量系数（0 = 无动量，推荐 0.9）
     * @return 平均 loss
     */
    public double trainMiniBatch(double[][][][] planes, double[][] auxFeatures,
                                  double[] valueTargets, double[][] policyTargets,
                                  double learningRate, double l2, double gradientClip,
                                  double momentum) {
        int batchSize = planes.length;
        if (batchSize == 0) return 0;

        modelLock.writeLock().lock();
        try {
            // 确保动量缓冲就绪
            if (momentum > 0) ensureVelocities();
            // ── 梯度累加器 ──────────────────────────────────────────────
            double[][][] gSubW = new double[NUM_BLOCKS][SUB_INPUT][SUB_HIDDEN];
            double[][] gSubB = new double[NUM_BLOCKS][SUB_HIDDEN];
            double[][][] gBlockW = new double[NUM_BLOCKS][BLOCK_INPUT][BLOCK_HIDDEN];
            double[][] gBlockB = new double[NUM_BLOCKS][BLOCK_HIDDEN];
            double[][] gTopW = new double[TOP_INPUT][TOP_HIDDEN];
            double[] gTopB = new double[TOP_HIDDEN];
            double[][] gPolicyW = new double[TOP_HIDDEN][POLICY_SIZE];
            double[] gPolicyB = new double[POLICY_SIZE];
            double[][] gValueW1 = new double[TOP_HIDDEN][VALUE_HIDDEN];
            double[] gValueB1 = new double[VALUE_HIDDEN];
            double[] gValueW2 = new double[VALUE_HIDDEN];
            double gValueB2 = 0;
            double totalLoss = 0;

            // ── OpenCL GPU 路径：Pass 0 预计算子块/字块并批量运行顶级 FC ──
            boolean useGpu = false;
            OpenCLBackend oc = null;
            double[][][][] bSubIn = null, bSubZ = null;
            double[][][] bBlkIn = null, bBlkZ = null;
            double[][] bTopIn = null, bShared = null, bShZ = null;
            double[][] bPolicyOut = null;  // [B][362] GPU softmax 输出
            double[] bValueOut = null;     // [B] GPU tanh 输出
            try { oc = ensureOpenCL(); useGpu = (oc != null); }
            catch (Throwable e) { useGpu = false; oc = null; }
            if (useGpu) {
                bSubIn = new double[batchSize][NUM_BLOCKS][SUBS_PER_BLOCK][SUB_INPUT];
                bSubZ = new double[batchSize][NUM_BLOCKS][SUBS_PER_BLOCK][SUB_HIDDEN];
                bBlkIn = new double[batchSize][NUM_BLOCKS][BLOCK_INPUT];
                bBlkZ = new double[batchSize][NUM_BLOCKS][BLOCK_HIDDEN];
                bTopIn = new double[batchSize][TOP_INPUT];
                bShared = new double[batchSize][TOP_HIDDEN];
                bShZ = new double[batchSize][TOP_HIDDEN];
                bPolicyOut = new double[batchSize][POLICY_SIZE];
                bValueOut = new double[batchSize];
                // 整个 batch 的前向在 GPU 上完成（子块→字块→顶级→策略头→价值头）
                boolean ranGpu = oc.batchPass0Forward(planes, auxFeatures, batchSize,
                        subW1, subB1, blockW1, blockB1, topW1, topB1,
                        policyW, policyB, valueW1, valueB1, valueW2, valueB2,
                        bSubIn, bSubZ, bBlkIn, bBlkZ, bTopIn, bShared, bShZ,
                        bPolicyOut, bValueOut);
                if (!ranGpu) {
                    // GPU 失败，回退 CPU 路径
                    useGpu = false;
                }
            }

            for (int n = 0; n < batchSize; n++) {
                double[][][] p = planes[n];
                double[] aux = auxFeatures[n];
                double vTgt = valueTargets[n];
                double[] pTgt = policyTargets[n];

                // ═══════════════════════════════════════════════════════════
                //  前向（保存中间结果供反向使用）
                // ═══════════════════════════════════════════════════════════

                double[][][] subIn; double[][][] subZ;
                double[][] blkIn; double[][] blkZ;
                double[] topIn; double[] shZ; double[] shared;
                if (useGpu) {
                    // GPU 路径：复用 Pass 0 预计算的中间结果与顶级 FC 输出
                    subIn = bSubIn[n]; subZ = bSubZ[n];
                    blkIn = bBlkIn[n]; blkZ = bBlkZ[n];
                    topIn = bTopIn[n]; shZ = bShZ[n]; shared = bShared[n];
                } else {
                    // ── 第 1 级：二级子块 ──
                    subIn = new double[NUM_BLOCKS][SUBS_PER_BLOCK][SUB_INPUT];
                    subZ = new double[NUM_BLOCKS][SUBS_PER_BLOCK][SUB_HIDDEN];
                    double[][][] subOut = new double[NUM_BLOCKS][SUBS_PER_BLOCK][SUB_HIDDEN];
                    for (int b = 0; b < NUM_BLOCKS; b++) {
                        int bx = BLOCK_STARTS[b][0], by = BLOCK_STARTS[b][1];
                        for (int s = 0; s < SUBS_PER_BLOCK; s++) {
                            int sx = bx + SUB_OFFSETS[s][0], sy = by + SUB_OFFSETS[s][1];
                            int idx = 0;
                            for (int pp = 0; pp < PLANES; pp++)
                                for (int dx = 0; dx < SUB_SIZE; dx++)
                                    for (int dy = 0; dy < SUB_SIZE; dy++)
                                        subIn[b][s][idx++] = p[pp][sx + dx][sy + dy];
                            for (int j = 0; j < SUB_HIDDEN; j++) {
                                double sum = subB1[b][j];
                                for (int i = 0; i < SUB_INPUT; i++)
                                    sum += subW1[b][i][j] * subIn[b][s][i];
                                subZ[b][s][j] = sum;
                                subOut[b][s][j] = Math.max(0, sum);
                            }
                        }
                    }
                    // ── 第 2 级：一级字块 ──
                    blkIn = new double[NUM_BLOCKS][BLOCK_INPUT];
                    blkZ = new double[NUM_BLOCKS][BLOCK_HIDDEN];
                    double[][] blkOut = new double[NUM_BLOCKS][BLOCK_HIDDEN];
                    for (int b = 0; b < NUM_BLOCKS; b++) {
                        int idx = 0;
                        for (int s = 0; s < SUBS_PER_BLOCK; s++)
                            for (int h = 0; h < SUB_HIDDEN; h++)
                                blkIn[b][idx++] = subOut[b][s][h];
                        for (int j = 0; j < BLOCK_HIDDEN; j++) {
                            double sum = blockB1[b][j];
                            for (int i = 0; i < BLOCK_INPUT; i++)
                                sum += blockW1[b][i][j] * blkIn[b][i];
                            blkZ[b][j] = sum;
                            blkOut[b][j] = Math.max(0, sum);
                        }
                    }
                    // ── 第 3 级：顶级 ──
                    topIn = new double[TOP_INPUT];
                    int topIdx = 0;
                    for (int b = 0; b < NUM_BLOCKS; b++)
                        for (int h = 0; h < BLOCK_HIDDEN; h++)
                            topIn[topIdx++] = blkOut[b][h];
                    System.arraycopy(aux, 0, topIn, NUM_BLOCKS * BLOCK_HIDDEN, AUX_SIZE);
                    shZ = new double[TOP_HIDDEN];
                    shared = new double[TOP_HIDDEN];
                    for (int j = 0; j < TOP_HIDDEN; j++) {
                        double sum = topB1[j];
                        for (int i = 0; i < TOP_INPUT; i++)
                            sum += topW1[i][j] * topIn[i];
                        shZ[j] = sum;
                        shared[j] = Math.max(0, sum);
                    }
                }

                // 策略头（GPU 路径直接复用 GPU softmax 输出，CPU 路径自行前向）
                double[] policy;
                if (useGpu) {
                    policy = bPolicyOut[n];
                } else {
                    double[] logits = new double[POLICY_SIZE];
                    double maxLog = Double.NEGATIVE_INFINITY;
                    for (int j = 0; j < POLICY_SIZE; j++) {
                        double sum = policyB[j];
                        for (int i = 0; i < TOP_HIDDEN; i++)
                            sum += policyW[i][j] * shared[i];
                        logits[j] = sum;
                        if (sum > maxLog) maxLog = sum;
                    }
                    policy = new double[POLICY_SIZE];
                    double sumExp = 0;
                    for (int j = 0; j < POLICY_SIZE; j++) {
                        policy[j] = Math.exp(logits[j] - maxLog);
                        sumExp += policy[j];
                    }
                    double invSum = 1.0 / Math.max(sumExp, 1e-30);
                    for (int j = 0; j < POLICY_SIZE; j++) policy[j] *= invSum;
                }

                // 价值头（vh/vhZ 供反向使用；最终值 GPU 已算出则直接复用）
                double[] vhZ = new double[VALUE_HIDDEN];
                double[] vh = new double[VALUE_HIDDEN];
                for (int j = 0; j < VALUE_HIDDEN; j++) {
                    double sum = valueB1[j];
                    for (int i = 0; i < TOP_HIDDEN; i++)
                        sum += valueW1[i][j] * shared[i];
                    vhZ[j] = sum;
                    vh[j] = Math.max(0, sum);
                }
                double value;
                if (useGpu) {
                    value = bValueOut[n]; // GPU tanh 输出
                } else {
                    double valuePre = valueB2;
                    for (int i = 0; i < VALUE_HIDDEN; i++)
                        valuePre += valueW2[i] * vh[i];
                    value = Math.tanh(valuePre);
                }

                // ── Loss ──────────────────────────────────────────────
                double vLoss = (value - vTgt) * (value - vTgt);
                double pLoss = 0;
                for (int j = 0; j < POLICY_SIZE; j++) {
                    if (pTgt[j] > 0)
                        pLoss -= pTgt[j] * Math.log(Math.max(policy[j], 1e-15));
                }
                totalLoss += vLoss + pLoss;

                // ═══════════════════════════════════════════════════════════
                //  反向传播
                // ═══════════════════════════════════════════════════════════

                // ── 价值头 ──────────────────────────────────────────────
                double dValue = 2.0 * (value - vTgt) * (1.0 - value * value);
                double[] dVh = new double[VALUE_HIDDEN];
                for (int i = 0; i < VALUE_HIDDEN; i++) {
                    gValueW2[i] += dValue * vh[i];
                    dVh[i] = dValue * valueW2[i] * (vhZ[i] > 0 ? 1 : 0);
                    gValueB1[i] += dVh[i]; // 价值头隐藏层偏置梯度（此前遗漏，偏置永久冻结）
                }
                gValueB2 += dValue;

                // ── 策略头 ──────────────────────────────────────────────
                double[] dLogit = new double[POLICY_SIZE];
                for (int j = 0; j < POLICY_SIZE; j++) {
                    dLogit[j] = policy[j] - pTgt[j];
                    gPolicyB[j] += dLogit[j];
                }

                // ── 共享层梯度 ──────────────────────────────────────────
                double[] dShared = new double[TOP_HIDDEN];
                for (int i = 0; i < TOP_HIDDEN; i++) {
                    double fromPolicy = 0;
                    for (int j = 0; j < POLICY_SIZE; j++) {
                        gPolicyW[i][j] += dLogit[j] * shared[i];
                        fromPolicy += dLogit[j] * policyW[i][j];
                    }
                    double fromValue = 0;
                    for (int j = 0; j < VALUE_HIDDEN; j++) {
                        gValueW1[i][j] += dVh[j] * shared[i];
                        fromValue += dVh[j] * valueW1[i][j];
                    }
                    dShared[i] = (fromPolicy + fromValue) * (shZ[i] > 0 ? 1 : 0);
                    gTopB[i] += dShared[i];
                }
                // 顶级权重
                for (int k = 0; k < TOP_INPUT; k++)
                    for (int i = 0; i < TOP_HIDDEN; i++)
                        gTopW[k][i] += dShared[i] * topIn[k];

                // ── 第 2 级：一级字块 ──────────────────────────────────
                double[] dTopIn = new double[TOP_INPUT];
                for (int k = 0; k < TOP_INPUT; k++)
                    for (int i = 0; i < TOP_HIDDEN; i++)
                        dTopIn[k] += dShared[i] * topW1[k][i];

                for (int b = 0; b < NUM_BLOCKS; b++) {
                    double[] dBlkOut = new double[BLOCK_HIDDEN];
                    for (int h = 0; h < BLOCK_HIDDEN; h++)
                        dBlkOut[h] = dTopIn[b * BLOCK_HIDDEN + h];

                    double[] dBlkZ = new double[BLOCK_HIDDEN];
                    for (int j = 0; j < BLOCK_HIDDEN; j++) {
                        dBlkZ[j] = dBlkOut[j] * (blkZ[b][j] > 0 ? 1 : 0);
                        gBlockB[b][j] += dBlkZ[j];
                    }
                    for (int i = 0; i < BLOCK_INPUT; i++)
                        for (int j = 0; j < BLOCK_HIDDEN; j++)
                            gBlockW[b][i][j] += dBlkZ[j] * blkIn[b][i];

                    // ── 第 1 级：二级子块 ──────────────────────────────
                    double[] dBlkIn = new double[BLOCK_INPUT];
                    for (int i = 0; i < BLOCK_INPUT; i++)
                        for (int j = 0; j < BLOCK_HIDDEN; j++)
                            dBlkIn[i] += dBlkZ[j] * blockW1[b][i][j];

                    for (int s = 0; s < SUBS_PER_BLOCK; s++) {
                        for (int h = 0; h < SUB_HIDDEN; h++) {
                            double dSub = dBlkIn[s * SUB_HIDDEN + h] * (subZ[b][s][h] > 0 ? 1 : 0);
                            gSubB[b][h] += dSub;
                            for (int i = 0; i < SUB_INPUT; i++)
                                gSubW[b][i][h] += dSub * subIn[b][s][i];
                        }
                    }
                }
            }

            // ── 应用梯度（平均 + L2 + 裁剪） ──────────────────────────
            double scale = 1.0 / batchSize;
            double norm = gradientNorm(gSubW, gSubB, gBlockW, gBlockB, gTopW, gTopB,
                                       gPolicyW, gPolicyB, gValueW1, gValueB1, gValueW2, gValueB2);
            if (norm > gradientClip) scale *= gradientClip / norm;

            // 更新 all weights（momentum > 0 时使用动量更新）
            double r = learningRate * scale;
            boolean useMom = momentum > 0;
            if (useMom) {
                updateMMM(subW1, gSubW, vSubW1, r, l2, momentum);
                updateMM(subB1, gSubB, vSubB1, r, 0, momentum);
                updateMMM(blockW1, gBlockW, vBlockW1, r, l2, momentum);
                updateMM(blockB1, gBlockB, vBlockB1, r, 0, momentum);
                updateMM(topW1, gTopW, vTopW1, r, l2, momentum);
                updateM(topB1, gTopB, vTopB1, r, 0, momentum);
                updateMM(policyW, gPolicyW, vPolicyW, r, l2, momentum);
                updateM(policyB, gPolicyB, vPolicyB, r, 0, momentum);
                updateMM(valueW1, gValueW1, vValueW1, r, l2, momentum);
                updateM(valueB1, gValueB1, vValueB1, r, 0, momentum);
                updateM(valueW2, gValueW2, vValueW2, r, l2, momentum);
                // bias 不做 L2 正则（与 valueB1 等其它 bias 一致，避免 valueB2 被额外收缩）
                vValueB2 = momentum * vValueB2 + r * gValueB2;
                valueB2 -= vValueB2;
            } else {
                updateMMM(subW1, gSubW, null, r, l2, 0);
                updateMM(subB1, gSubB, null, r, 0, 0);
                updateMMM(blockW1, gBlockW, null, r, l2, 0);
                updateMM(blockB1, gBlockB, null, r, 0, 0);
                updateMM(topW1, gTopW, null, r, l2, 0);
                updateM(topB1, gTopB, null, r, 0, 0);
                updateMM(policyW, gPolicyW, null, r, l2, 0);
                updateM(policyB, gPolicyB, null, r, 0, 0);
                updateMM(valueW1, gValueW1, null, r, l2, 0);
                updateM(valueB1, gValueB1, null, r, 0, 0);
                updateM(valueW2, gValueW2, null, r, l2, 0);
                valueB2 -= r * gValueB2; // bias 不做 L2 正则（与其它 bias 一致）
            }

            modelVersion++;
            synchronized (evaluationCache) { evaluationCache.clear(); }
            return totalLoss / batchSize;
        } finally {
            modelLock.writeLock().unlock();
        }
    }

    // ── 梯度更新辅助（支持动量）─────────────────────────────────────
    /**
     * 更新 3D 权重：w -= v, v = momentum * v + rate * (g + l2 * w)
     * 当 v == null 或 momentum == 0 时退化为纯 SGD：w -= rate * (g + l2 * w)
     */
    private static void updateMMM(double[][][] w, double[][][] g, double[][][] v, double rate, double l2, double momentum) {
        if (v != null) {
            for (int a = 0; a < w.length; a++)
                for (int b = 0; b < w[a].length; b++)
                    for (int c = 0; c < w[a][b].length; c++) {
                        double grad = g[a][b][c] + l2 * w[a][b][c];
                        v[a][b][c] = momentum * v[a][b][c] + rate * grad;
                        w[a][b][c] -= v[a][b][c];
                    }
        } else {
            for (int a = 0; a < w.length; a++)
                for (int b = 0; b < w[a].length; b++)
                    for (int c = 0; c < w[a][b].length; c++)
                        w[a][b][c] -= rate * (g[a][b][c] + l2 * w[a][b][c]);
        }
    }
    private static void updateMM(double[][] w, double[][] g, double[][] v, double rate, double l2, double momentum) {
        if (v != null) {
            for (int a = 0; a < w.length; a++)
                for (int b = 0; b < w[a].length; b++) {
                    double grad = g[a][b] + l2 * w[a][b];
                    v[a][b] = momentum * v[a][b] + rate * grad;
                    w[a][b] -= v[a][b];
                }
        } else {
            for (int a = 0; a < w.length; a++)
                for (int b = 0; b < w[a].length; b++)
                    w[a][b] -= rate * (g[a][b] + l2 * w[a][b]);
        }
    }
    private static void updateM(double[] w, double[] g, double[] v, double rate, double l2, double momentum) {
        if (v != null) {
            for (int a = 0; a < w.length; a++) {
                double grad = g[a] + l2 * w[a];
                v[a] = momentum * v[a] + rate * grad;
                w[a] -= v[a];
            }
        } else {
            for (int a = 0; a < w.length; a++)
                w[a] -= rate * (g[a] + l2 * w[a]);
        }
    }

    /** 惰性创建动量缓冲（首次 momentum > 0 训练时调用） */
    private void ensureVelocities() {
        if (vSubW1 != null) return;
        vSubW1 = new double[NUM_BLOCKS][SUB_INPUT][SUB_HIDDEN];
        vSubB1 = new double[NUM_BLOCKS][SUB_HIDDEN];
        vBlockW1 = new double[NUM_BLOCKS][BLOCK_INPUT][BLOCK_HIDDEN];
        vBlockB1 = new double[NUM_BLOCKS][BLOCK_HIDDEN];
        vTopW1 = new double[TOP_INPUT][TOP_HIDDEN];
        vTopB1 = new double[TOP_HIDDEN];
        vPolicyW = new double[TOP_HIDDEN][POLICY_SIZE];
        vPolicyB = new double[POLICY_SIZE];
        vValueW1 = new double[TOP_HIDDEN][VALUE_HIDDEN];
        vValueB1 = new double[VALUE_HIDDEN];
        vValueW2 = new double[VALUE_HIDDEN];
        vValueB2 = 0;
    }
    /** 重置动量缓冲为 0（加载新权重后调用） */
    private void resetVelocities() {
        vSubW1 = null; vSubB1 = null;
        vBlockW1 = null; vBlockB1 = null;
        vTopW1 = null; vTopB1 = null;
        vPolicyW = null; vPolicyB = null;
        vValueW1 = null; vValueB1 = null;
        vValueW2 = null;
        vValueB2 = 0;
    }

    private static double gradientNorm(double[][][] gSubW, double[][] gSubB,
                                        double[][][] gBlockW, double[][] gBlockB,
                                        double[][] gTopW, double[] gTopB,
                                        double[][] gPolicyW, double[] gPolicyB,
                                        double[][] gValueW1, double[] gValueB1,
                                        double[] gValueW2, double gValueB2) {
        double s = 0;
        // gSubW [9][36][16]
        for (double[][] mm : gSubW) for (double[] r : mm) for (double v : r) s += v * v;
        // gBlockW [9][144][64]
        for (double[][] mm : gBlockW) for (double[] r : mm) for (double v : r) s += v * v;
        // 2D arrays
        for (double[][] m : new double[][][]{gSubB, gBlockB, gTopW, gPolicyW, gValueW1})
            for (double[] r : m) for (double v : r) s += v * v;
        // 1D arrays
        for (double[] v : new double[][]{gTopB, gPolicyB, gValueB1, gValueW2})
            for (double x : v) s += x * x;
        s += gValueB2 * gValueB2;
        return Math.sqrt(s);
    }

    // ══════════════════════════════════════════════════════════════════════
    //  辅助方法（眼形、连接、领地等，沿用旧版）
    // ══════════════════════════════════════════════════════════════════════

    private static class EyeInfo { boolean isTrue, isPotential; }

    private EyeInfo analyzeEye(GoPlayer[][] board, int x, int y) {
        EyeInfo info = new EyeInfo();
        GoPlayer player = board[x][y];
        GoPlayer opponent = player == GoPlayer.BLACK ? GoPlayer.WHITE : GoPlayer.BLACK;
        int friendly = 0, enemy = 0, empty = 0;
        for (int[] d : DIRS) {
            int nx = x + d[0], ny = y + d[1];
            if (nx >= 0 && nx < BOARD_SIZE && ny >= 0 && ny < BOARD_SIZE) {
                if (board[nx][ny] == player) friendly++;
                else if (board[nx][ny] == opponent) enemy++;
                else if (board[nx][ny] == GoPlayer.NONE) empty++;
            }
        }
        info.isTrue = (friendly >= 3 && empty >= 1) || (friendly == 4);
        info.isPotential = friendly >= 2 && enemy > 0;
        return info;
    }

    private double evaluateConnections(GoPlayer[][] board, GoPlayer player) {
        boolean[][] visited = new boolean[BOARD_SIZE][BOARD_SIZE];
        double bonus = 0;
        for (int x = 0; x < BOARD_SIZE; x++) for (int y = 0; y < BOARD_SIZE; y++) {
            if (board[x][y] == player && !visited[x][y]) {
                Set<int[]> group = getGroup(board, x, y);
                int size = group.size();
                if (size >= 5) bonus += size * 0.3;
                for (int[] p : group) {
                    for (int[] d : DIRS) {
                        int nx = p[0] + d[0], ny = p[1] + d[1];
                        if (nx >= 0 && nx < BOARD_SIZE && ny >= 0 && ny < BOARD_SIZE && board[nx][ny] == player)
                            bonus += 0.5;
                    }
                }
                int libs = countGroupLiberties(board, group);
                if (libs >= 5) bonus += 2;
                for (int[] p : group) visited[p[0]][p[1]] = true;
            }
        }
        return bonus;
    }

    private double evaluateSeparation(GoPlayer[][] board, GoPlayer player) {
        GoPlayer opponent = player == GoPlayer.BLACK ? GoPlayer.WHITE : GoPlayer.BLACK;
        double threat = 0;
        for (int x = 0; x < BOARD_SIZE; x++) for (int y = 0; y < BOARD_SIZE; y++) {
            if (board[x][y] == GoPlayer.NONE) {
                // getGroup 返回的 Set<int[]> 中 int[] 是 identity 相等，同一棋群从多方向
                // 相邻会被 Set<Set<int[]>> 当成多个。用棋群最小坐标的 Long 键规范化去重。
                Set<Long> groupKeys = new HashSet<>();
                for (int[] d : DIRS) {
                    int nx = x + d[0], ny = y + d[1];
                    if (nx >= 0 && nx < BOARD_SIZE && ny >= 0 && ny < BOARD_SIZE && board[nx][ny] == opponent) {
                        Set<int[]> g = getGroup(board, nx, ny);
                        long minKey = Long.MAX_VALUE;
                        for (int[] p : g) {
                            long key = (long) p[0] * BOARD_SIZE + p[1];
                            if (key < minKey) minKey = key;
                        }
                        groupKeys.add(minKey);
                    }
                }
                if (groupKeys.size() >= 2) {
                    int minLibs = Integer.MAX_VALUE;
                    for (long key : groupKeys) {
                        int px = (int)(key / BOARD_SIZE), py = (int)(key % BOARD_SIZE);
                        int libs = countGroupLiberties(board, getGroup(board, px, py));
                        minLibs = Math.min(minLibs, libs);
                    }
                    threat += minLibs <= 3 ? 3.0 : 1.0;
                }
            }
        }
        return threat;
    }

    private double evaluateStrategicPoints(GoPlayer[][] board, GoPlayer player) {
        GoPlayer opponent = player == GoPlayer.BLACK ? GoPlayer.WHITE : GoPlayer.BLACK;
        double score = 0;
        int center = BOARD_SIZE / 2;
        if (board[center][center] == player) score += 3;
        else if (board[center][center] == opponent) score -= 3;
        int[] star = {3, BOARD_SIZE - 4};
        for (int s1 : star) for (int s2 : star) {
            if (board[s1][s2] == player) score += 2;
            else if (board[s1][s2] == opponent) score -= 2;
        }
        int[] komoku = {6, BOARD_SIZE - 7};
        for (int k1 : komoku) for (int k2 : komoku) {
            if (board[k1][k2] == player) score += 1.5;
            else if (board[k1][k2] == opponent) score -= 1.5;
        }
        return score;
    }

    private static class TerritoryResult { double myTerritory, oppTerritory; }

    private TerritoryResult evaluateTerritory(GoPlayer[][] board, GoPlayer player) {
        GoPlayer opponent = player == GoPlayer.BLACK ? GoPlayer.WHITE : GoPlayer.BLACK;
        TerritoryResult result = new TerritoryResult();
        boolean[][] visited = new boolean[BOARD_SIZE][BOARD_SIZE];
        for (int x = 0; x < BOARD_SIZE; x++) for (int y = 0; y < BOARD_SIZE; y++) {
            if (board[x][y] == GoPlayer.NONE && !visited[x][y]) {
                List<int[]> region = new ArrayList<>();
                Queue<int[]> queue = new LinkedList<>();
                queue.add(new int[]{x, y}); visited[x][y] = true;
                while (!queue.isEmpty()) {
                    int[] pos = queue.poll();
                    region.add(pos);
                    for (int[] d : DIRS) {
                        int nx = pos[0] + d[0], ny = pos[1] + d[1];
                        if (nx >= 0 && nx < BOARD_SIZE && ny >= 0 && ny < BOARD_SIZE
                                && board[nx][ny] == GoPlayer.NONE && !visited[nx][ny]) {
                            visited[nx][ny] = true;
                            queue.add(new int[]{nx, ny});
                        }
                    }
                }
                int myBorder = 0, oppBorder = 0;
                for (int[] p : region) for (int[] d : DIRS) {
                    int nx = p[0] + d[0], ny = p[1] + d[1];
                    if (nx >= 0 && nx < BOARD_SIZE && ny >= 0 && ny < BOARD_SIZE) {
                        if (board[nx][ny] == player) myBorder++;
                        else if (board[nx][ny] == opponent) oppBorder++;
                    }
                }
                double tv = region.size();
                if (myBorder > oppBorder) result.myTerritory += tv;
                else if (oppBorder > myBorder) result.oppTerritory += tv;
                else { result.myTerritory += tv * 0.5; result.oppTerritory += tv * 0.5; }
            }
        }
        return result;
    }

    private double heuristicEvaluation(GoPlayer[][] board, GoPlayer player) {
        GoPlayer opponent = player == GoPlayer.BLACK ? GoPlayer.WHITE : GoPlayer.BLACK;
        double score = 0;
        boolean[][] visited = new boolean[BOARD_SIZE][BOARD_SIZE];
        for (int x = 0; x < BOARD_SIZE; x++) for (int y = 0; y < BOARD_SIZE; y++) {
            if (board[x][y] != GoPlayer.NONE && !visited[x][y]) {
                Set<int[]> group = getGroup(board, x, y);
                int libs = countGroupLiberties(board, group);
                boolean isOwn = board[x][y] == player;
                if (libs >= 6) score += isOwn ? 15 : -15;
                else if (libs == 5) score += isOwn ? 12 : -12;
                else if (libs == 4) score += isOwn ? 8 : -8;
                else if (libs == 3) score += isOwn ? 4 : -4;
                else if (libs == 2) score += isOwn ? 1 : -3;
                else if (libs == 1) score += isOwn ? -25 : 25;
                else score += isOwn ? -40 : 40;
                if (isOwn && group.size() >= 5) score += group.size() * 2;
                if (!isOwn && libs <= 2) score += 20;
                for (int[] p : group) visited[p[0]][p[1]] = true;
            }
        }
        int myEyes = 0, oppEyes = 0;
        for (int x = 0; x < BOARD_SIZE; x++) for (int y = 0; y < BOARD_SIZE; y++) {
            if (board[x][y] != GoPlayer.NONE && isPotentialEye(board, x, y)) {
                if (board[x][y] == player) myEyes++; else oppEyes++;
            }
        }
        score += (myEyes - oppEyes) * 8;
        int myControl = 0, oppControl = 0;
        for (int x = 0; x < BOARD_SIZE; x++) for (int y = 0; y < BOARD_SIZE; y++) {
            if (board[x][y] == GoPlayer.NONE) {
                double myInf = 0, oppInf = 0;
                for (int dx = -2; dx <= 2; dx++) for (int dy = -2; dy <= 2; dy++) {
                    int nx = x + dx, ny = y + dy;
                    if (nx >= 0 && nx < BOARD_SIZE && ny >= 0 && ny < BOARD_SIZE) {
                        double w = Math.sqrt(dx * dx + dy * dy);
                        w = w > 0 ? 1.0 / w : 1.0;
                        if (board[nx][ny] == player) myInf += w;
                        else if (board[nx][ny] == opponent) oppInf += w;
                    }
                }
                if (myInf > oppInf + 0.5) myControl++;
                else if (oppInf > myInf + 0.5) oppControl++;
            }
        }
        score += (myControl - oppControl) * 0.5;
        int myStones = 0, oppStones = 0;
        for (int x = 0; x < BOARD_SIZE; x++) for (int y = 0; y < BOARD_SIZE; y++) {
            if (board[x][y] == player) myStones++;
            else if (board[x][y] == opponent) oppStones++;
        }
        score += (myStones - oppStones) * 2;
        return score;
    }

    private boolean isPotentialEye(GoPlayer[][] board, int x, int y) {
        int friendly = 0, empty = 0;
        for (int[] d : DIRS) {
            int nx = x + d[0], ny = y + d[1];
            if (nx >= 0 && nx < BOARD_SIZE && ny >= 0 && ny < BOARD_SIZE) {
                if (board[nx][ny] == board[x][y]) friendly++;
                else if (board[nx][ny] == GoPlayer.NONE) empty++;
            }
        }
        return friendly + empty >= 3;
    }

    private double countSurrounding(GoPlayer[][] board, int x, int y, GoPlayer player) {
        double influence = 0;
        for (int[] d : DIRS) {
            int nx = x + d[0], ny = y + d[1];
            if (nx >= 0 && nx < BOARD_SIZE && ny >= 0 && ny < BOARD_SIZE && board[nx][ny] == player)
                influence += 1.0;
        }
        return influence;
    }

    private int countFriendlyNeighbors(GoPlayer[][] board, int x, int y, GoPlayer player) {
        int count = 0;
        for (int[] d : DIRS) {
            int nx = x + d[0], ny = y + d[1];
            if (nx >= 0 && nx < BOARD_SIZE && ny >= 0 && ny < BOARD_SIZE && board[nx][ny] == player)
                count++;
        }
        return count;
    }

    Set<int[]> getGroup(GoPlayer[][] board, int x, int y) {
        Set<int[]> group = new HashSet<>();
        if (board[x][y] == GoPlayer.NONE) return group;
        Stack<int[]> stack = new Stack<>();
        boolean[][] visited = new boolean[BOARD_SIZE][BOARD_SIZE];
        stack.push(new int[]{x, y});
        while (!stack.isEmpty()) {
            int[] pos = stack.pop();
            int px = pos[0], py = pos[1];
            if (visited[px][py]) continue;
            visited[px][py] = true;
            group.add(new int[]{px, py});
            for (int[] d : DIRS) {
                int nx = px + d[0], ny = py + d[1];
                if (nx >= 0 && nx < BOARD_SIZE && ny >= 0 && ny < BOARD_SIZE
                        && !visited[nx][ny] && board[nx][ny] == board[x][y])
                    stack.push(new int[]{nx, ny});
            }
        }
        return group;
    }

    int countGroupLiberties(GoPlayer[][] board, Set<int[]> group) {
        Set<Long> libertySet = new HashSet<>();
        for (int[] pos : group) {
            for (int[] d : DIRS) {
                int nx = pos[0] + d[0], ny = pos[1] + d[1];
                if (nx >= 0 && nx < BOARD_SIZE && ny >= 0 && ny < BOARD_SIZE
                        && board[nx][ny] == GoPlayer.NONE)
                    libertySet.add((long) nx * BOARD_SIZE + ny);
            }
        }
        return libertySet.size();
    }

    // ══════════════════════════════════════════════════════════════════════
    //  Zobrist 哈希（复用 GoGame 的表）
    // ══════════════════════════════════════════════════════════════════════

    private long computeZobristHash(GoPlayer[][] board, GoPlayer player) {
        long hash = GoGame.boardHash(board);
        if (player == GoPlayer.WHITE) hash ^= 0xFFFFFFFFL;
        return hash;
    }

    private static final class CacheKey {
        final long hash, version;
        CacheKey(long hash, long version) { this.hash = hash; this.version = version; }
        @Override public int hashCode() { return Long.hashCode(hash * 31L + version); }
        @Override public boolean equals(Object o) {
            if (!(o instanceof CacheKey)) return false;
            CacheKey k = (CacheKey) o;
            return hash == k.hash && version == k.version;
        }
    }

    // ══════════════════════════════════════════════════════════════════════
    //  快照与持久化
    // ══════════════════════════════════════════════════════════════════════

    public long getModelVersion() { return modelVersion; }

    public int getCacheSize() { synchronized (evaluationCache) { return evaluationCache.size(); } }
    public void clearCache() { synchronized (evaluationCache) { evaluationCache.clear(); } }

    /** Immutable model snapshot. */
    public static final class ModelWeights {
        public final double[][][] subW1, blockW1;
        public final double[][] subB1, blockB1;
        public final double[][] topW1;
        public final double[] topB1;
        public final double[][] policyW;
        public final double[] policyB;
        public final double[][] valueW1;
        public final double[] valueB1;
        public final double[] valueW2;
        public final double valueB2;
        public final long version;

        private ModelWeights(double[][][] subW1, double[][] subB1,
                            double[][][] blockW1, double[][] blockB1,
                            double[][] topW1, double[] topB1,
                            double[][] policyW, double[] policyB,
                            double[][] valueW1, double[] valueB1,
                            double[] valueW2, double valueB2, long version) {
            this.subW1 = deepCopy(subW1); this.subB1 = deepCopy(subB1);
            this.blockW1 = deepCopy(blockW1); this.blockB1 = deepCopy(blockB1);
            this.topW1 = deepCopy(topW1); this.topB1 = topB1.clone();
            this.policyW = deepCopy(policyW); this.policyB = policyB.clone();
            this.valueW1 = deepCopy(valueW1); this.valueB1 = valueB1.clone();
            this.valueW2 = valueW2.clone(); this.valueB2 = valueB2;
            this.version = version;
        }

        private static double[][][] deepCopy(double[][][] src) {
            double[][][] dst = new double[src.length][][];
            for (int i = 0; i < src.length; i++) dst[i] = deepCopy(src[i]);
            return dst;
        }
        private static double[][] deepCopy(double[][] src) {
            double[][] dst = new double[src.length][];
            for (int i = 0; i < src.length; i++) dst[i] = src[i].clone();
            return dst;
        }
        private static double[] deepCopy(double[] src) { return src.clone(); }
    }

    public ModelWeights snapshot() {
        modelLock.readLock().lock();
        try {
            return new ModelWeights(subW1, subB1, blockW1, blockB1,
                    topW1, topB1, policyW, policyB,
                    valueW1, valueB1, valueW2, valueB2, modelVersion);
        } finally {
            modelLock.readLock().unlock();
        }
    }

    public void apply(ModelWeights m) {
        if (m == null) throw new IllegalArgumentException("Null model");
        modelLock.writeLock().lock();
        try {
            copyInto(m.subW1, subW1); copyInto(m.subB1, subB1);
            copyInto(m.blockW1, blockW1); copyInto(m.blockB1, blockB1);
            copyInto(m.topW1, topW1); copyInto(m.topB1, topB1);
            copyInto(m.policyW, policyW); copyInto(m.policyB, policyB);
            copyInto(m.valueW1, valueW1); copyInto(m.valueB1, valueB1);
            copyInto(m.valueW2, valueW2); this.valueB2 = m.valueB2;
            // 恢复持久化版本号（避免加载 checkpoint 后版本被重置为 1，
            // 导致模型版本与缓存键不一致）
            modelVersion = m.version;
            synchronized (evaluationCache) { evaluationCache.clear(); }
            // 加载新权重后重置动量缓冲，避免旧动量污染新权重
            resetVelocities();
        } finally {
            modelLock.writeLock().unlock();
        }
    }

    private static void copyInto(double[][][] src, double[][][] dst) {
        for (int i = 0; i < src.length; i++) copyInto(src[i], dst[i]);
    }
    private static void copyInto(double[][] src, double[][] dst) {
        for (int i = 0; i < src.length; i++) System.arraycopy(src[i], 0, dst[i], 0, dst[i].length);
    }
    private static void copyInto(double[] src, double[] dst) {
        System.arraycopy(src, 0, dst, 0, dst.length);
    }

    public void save(Path path) throws IOException {
        Path absolute = path.toAbsolutePath();
        Path temp = absolute.resolveSibling(absolute.getFileName() + ".tmp");
        ModelWeights m = snapshot();
        try (DataOutputStream out = new DataOutputStream(
                Files.newOutputStream(temp, StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING))) {
            out.writeInt(MODEL_MAGIC);
            out.writeInt(MODEL_FORMAT);
            out.writeLong(m.version);
            // 9 套子块权重
            for (int b = 0; b < NUM_BLOCKS; b++) {
                writeMatrix(out, m.subW1[b]); writeVector(out, m.subB1[b]);
            }
            // 9 套字块权重
            for (int b = 0; b < NUM_BLOCKS; b++) {
                writeMatrix(out, m.blockW1[b]); writeVector(out, m.blockB1[b]);
            }
            writeMatrix(out, m.topW1); writeVector(out, m.topB1);
            writeMatrix(out, m.policyW); writeVector(out, m.policyB);
            writeMatrix(out, m.valueW1); writeVector(out, m.valueB1);
            writeVector(out, m.valueW2); out.writeFloat((float)m.valueB2);
        }
        try {
            Files.move(temp, absolute, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        } catch (java.nio.file.AtomicMoveNotSupportedException e) {
            Files.move(temp, absolute, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    public void load(Path path) throws IOException {
        try (DataInputStream in = new DataInputStream(Files.newInputStream(path))) {
            int magic = in.readInt();
            int fmt = in.readInt();
            // 向后兼容：NEV2（0x4E455632）用 double 8 字节，NEV3 用 float 4 字节
            boolean isDouble = (magic == 0x4E455632 && fmt == 2);
            if (magic != MODEL_MAGIC && magic != 0x4E455632)
                throw new IOException("Unsupported model format: magic=" + Integer.toHexString(magic) + " fmt=" + fmt);
            long ver = in.readLong();

            double[][][] lSubW1 = new double[NUM_BLOCKS][SUB_INPUT][SUB_HIDDEN];
            double[][] lSubB1 = new double[NUM_BLOCKS][SUB_HIDDEN];
            double[][][] lBlockW1 = new double[NUM_BLOCKS][BLOCK_INPUT][BLOCK_HIDDEN];
            double[][] lBlockB1 = new double[NUM_BLOCKS][BLOCK_HIDDEN];
            for (int b = 0; b < NUM_BLOCKS; b++) { readMatrix(in, lSubW1[b], isDouble); readVector(in, lSubB1[b], isDouble); }
            for (int b = 0; b < NUM_BLOCKS; b++) { readMatrix(in, lBlockW1[b], isDouble); readVector(in, lBlockB1[b], isDouble); }
            double[][] lTopW1 = readMatrix(in, TOP_INPUT, TOP_HIDDEN, isDouble);
            double[] lTopB1 = readVector(in, TOP_HIDDEN, isDouble);
            double[][] lPolicyW = readMatrix(in, TOP_HIDDEN, POLICY_SIZE, isDouble);
            double[] lPolicyB = readVector(in, POLICY_SIZE, isDouble);
            double[][] lValueW1 = readMatrix(in, TOP_HIDDEN, VALUE_HIDDEN, isDouble);
            double[] lValueB1 = readVector(in, VALUE_HIDDEN, isDouble);
            double[] lValueW2 = readVector(in, VALUE_HIDDEN, isDouble);
            double lValueB2 = isDouble ? in.readDouble() : in.readFloat();

            ModelWeights m = new ModelWeights(lSubW1, lSubB1, lBlockW1, lBlockB1,
                    lTopW1, lTopB1, lPolicyW, lPolicyB,
                    lValueW1, lValueB1, lValueW2, lValueB2, ver);
            apply(m);
        }
    }

    private static void writeMatrix(DataOutputStream o, double[][] m) throws IOException {
        for (double[] r : m) for (double v : r) o.writeFloat((float)v);
    }
    private static void writeVector(DataOutputStream o, double[] v) throws IOException {
        for (double x : v) o.writeFloat((float)x);
    }
    private static double[][] readMatrix(DataInputStream in, int r, int c, boolean isDouble) throws IOException {
        double[][] m = new double[r][c];
        for (int i = 0; i < r; i++) for (int j = 0; j < c; j++) m[i][j] = isDouble ? in.readDouble() : in.readFloat();
        return m;
    }
    private static void readMatrix(DataInputStream in, double[][] target, boolean isDouble) throws IOException {
        for (double[] r : target) for (int j = 0; j < r.length; j++) r[j] = isDouble ? in.readDouble() : in.readFloat();
    }
    private static double[] readVector(DataInputStream in, int n, boolean isDouble) throws IOException {
        double[] v = new double[n];
        for (int i = 0; i < n; i++) v[i] = isDouble ? in.readDouble() : in.readFloat();
        return v;
    }
    private static void readVector(DataInputStream in, double[] target, boolean isDouble) throws IOException {
        for (int i = 0; i < target.length; i++) target[i] = isDouble ? in.readDouble() : in.readFloat();
    }
}