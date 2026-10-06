package com.wzz.game_console.client.screens.games.gogame;

import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.locks.LockSupport;
import java.util.concurrent.locks.ReentrantLock;
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
    /** Serializes load/save of the same model path inside this JVM. */
    private static final ConcurrentHashMap<Path, Object> MODEL_IO_LOCKS = new ConcurrentHashMap<>();
    private static final int VALUE_HIDDEN = 128;

    // 辅助特征维度（沿用旧版）
    private static final int LIBERTY_HIST_BINS = 8;
    private static final int EYE_FEATURE_SIZE = 8;
    private static final int GLOBAL_FEATURE_SIZE = 8;
    private static final int AUX_SIZE = LIBERTY_HIST_BINS + EYE_FEATURE_SIZE + GLOBAL_FEATURE_SIZE; // 24

    // 持久化
    private static final int MODEL_MAGIC = 0x4E455633; // NEV3
    private static final int MODEL_FORMAT = 3;
    private static final int LEGACY_MODEL_MAGIC = 0x4E455632; // NEV2
    private static final int LEGACY_MODEL_FORMAT = 2;
    private static final int MAX_CACHE_SIZE = 10000;
    private static final AtomicInteger GPU_OWNER_SEQ = new AtomicInteger(1);

    /** Per-caller feature buffers. They are only reused after forward() returns. */
    private static final ThreadLocal<InputScratch> INPUT_SCRATCH =
            ThreadLocal.withInitial(InputScratch::new);

    /** Per-thread intermediate buffers; returned policies always own their storage. */
    private static final ThreadLocal<CpuForwardScratch> CPU_FORWARD_SCRATCH =
            ThreadLocal.withInitial(CpuForwardScratch::new);

    private static final ThreadLocal<FeatureScratch> FEATURE_SCRATCH =
            ThreadLocal.withInitial(FeatureScratch::new);

    private static final class InputScratch {
        final double[][][] planes = new double[PLANES][BOARD_SIZE][BOARD_SIZE];
        final double[] aux = new double[AUX_SIZE];
    }

    private static final class CpuForwardScratch {
        final double[][][] subOut = new double[NUM_BLOCKS][SUBS_PER_BLOCK][SUB_HIDDEN];
        final double[] subInput = new double[SUB_INPUT];
        final double[][] blockOut = new double[NUM_BLOCKS][BLOCK_HIDDEN];
        final double[] blockInput = new double[BLOCK_INPUT];
        final double[] topInput = new double[TOP_INPUT];
        final double[] shared = new double[TOP_HIDDEN];
        final double[] valueHidden = new double[VALUE_HIDDEN];
    }

    /** One analysis per position, shared by the liberty, connection and separation features. */
    private static final class FeatureScratch {
        final GoPlayer[] cells = new GoPlayer[BOARD_SIZE * BOARD_SIZE];
        final int[] groupAt = new int[BOARD_SIZE * BOARD_SIZE];
        final GoPlayer[] colors = new GoPlayer[BOARD_SIZE * BOARD_SIZE];
        final int[] sizes = new int[BOARD_SIZE * BOARD_SIZE];
        final int[] liberties = new int[BOARD_SIZE * BOARD_SIZE];
        final int[] links = new int[BOARD_SIZE * BOARD_SIZE];
        final int[] libertySeen = new int[BOARD_SIZE * BOARD_SIZE];
        final int[] queue = new int[BOARD_SIZE * BOARD_SIZE];
        final int[] histogram = new int[LIBERTY_HIST_BINS];
        final int[] adjacentGroups = new int[4];
        final boolean[] regionSeen = new boolean[BOARD_SIZE * BOARD_SIZE];
        int groupCount;
    }

    // 四方向
    private static final int[][] DIRS = {{0, 1}, {1, 0}, {0, -1}, {-1, 0}};

    // ══════════════════════════════════════════════════════════════════════
    //  分块索引（静态计算）
    // ══════════════════════════════════════════════════════════════════════

    /** 9 个一级字块的左上角 (bx, by) */
    private static final int[][] BLOCK_STARTS = new int[9][2];
    /** 每个一级字块内 9 个二级子块的左上角偏移 (sx, sy) 相对于大块起点 */
    private static final int[][] SUB_OFFSETS = new int[9][2];
    private static final double[][] INFLUENCE_WEIGHT = new double[7][7];
    private static final int[][] CELL_NEIGHBORS = new int[BOARD_SIZE * BOARD_SIZE][];

    static {
        for (int x = 0; x < BOARD_SIZE; x++) {
            for (int y = 0; y < BOARD_SIZE; y++) {
                int[] neighbors = new int[4];
                int count = 0;
                for (int[] d : DIRS) {
                    int nx = x + d[0], ny = y + d[1];
                    if (nx >= 0 && nx < BOARD_SIZE && ny >= 0 && ny < BOARD_SIZE) {
                        neighbors[count++] = nx * BOARD_SIZE + ny;
                    }
                }
                CELL_NEIGHBORS[x * BOARD_SIZE + y] = Arrays.copyOf(neighbors, count);
            }
        }
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
        for (int dx = -3; dx <= 3; dx++) {
            for (int dy = -3; dy <= 3; dy++) {
                int distanceSquared = dx * dx + dy * dy;
                INFLUENCE_WEIGHT[dx + 3][dy + 3] = distanceSquared == 0
                        ? 1.0 : 1.0 / Math.sqrt(distanceSquared);
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
    /**
     * 与 release 互斥：前向/训练可并发持有读锁，关闭时独占写锁。
     * 原先用独占 ReentrantLock 会把 30 路自对弈的 GPU 前向串成一条。
     */
    private final ReentrantReadWriteLock lifecycleLock = new ReentrantReadWriteLock();
    private volatile boolean released;
    private volatile long modelVersion;
    private final int gpuOwnerId = GPU_OWNER_SEQ.getAndIncrement();

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
    /** OpenCL 初始化失败标记：置位后不再反复尝试加载（每次 forward 都调 ensureOpenCL） */
    private volatile boolean openclDisabled;
    /** 把并发 MCTS 前向收成一批再上 GPU；单线程时不等待。 */
    private static final int INFER_MAX_BATCH = 64;
    private static final long INFER_GATHER_NS = 1_500_000L;
    private final ConcurrentLinkedQueue<GpuInferCall> gpuInferQueue = new ConcurrentLinkedQueue<>();
    private final AtomicInteger gpuInferWaiters = new AtomicInteger();
    private final ReentrantLock gpuInferDrain = new ReentrantLock();

    private static final class GpuInferCall {
        final double[][][] planes;
        final double[] aux;
        final CompletableFuture<ForwardResult> done = new CompletableFuture<>();
        GpuInferCall(double[][][] planes, double[] aux) {
            this.planes = planes;
            this.aux = aux;
        }
    }

    /**
     * 释放 OpenCL native 资源（kernel/program/queue/context）。
     * 修复：此前 OpenCLBackend.close() 全项目无调用点，屏显重开反复创建
     * MCTSGoAI 会堆积 native 句柄只能靠 GC 兜底。重复调用安全（幂等）。
     */
    public void release() {
        lifecycleLock.writeLock().lock();
        try {
            if (released) return;
            released = true;
            OpenCLBackend b = opencl;
            opencl = null;
            if (b != null) {
                try {
                    b.close();
                } catch (Throwable ignored) {
                }
            }
        } finally {
            lifecycleLock.writeLock().unlock();
        }
    }

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
        fillInputPlanes(planes, board, player, lastMove);
        return planes;
    }

    private void fillInputPlanes(double[][][] planes, GoPlayer[][] board,
                                 GoPlayer player, int[] lastMove) {
        for (double[][] plane : planes) {
            for (double[] row : plane) Arrays.fill(row, 0.0);
        }
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
        fillAuxFeatures(aux, board, player);
        return aux;
    }

    private void fillAuxFeatures(double[] aux, GoPlayer[][] board, GoPlayer player) {
        Arrays.fill(aux, 0.0);
        FeatureScratch scratch = FEATURE_SCRATCH.get();
        analyzeFeatureGroups(board, scratch);
        GoPlayer opponent = player == GoPlayer.BLACK ? GoPlayer.WHITE : GoPlayer.BLACK;
        int BS = BOARD_SIZE;

        // ── 气数直方图 [0..7] ──────────────────────────────────────────
        int[] libertyHist = scratch.histogram;
        Arrays.fill(libertyHist, 0);
        for (int group = 0; group < scratch.groupCount; group++) {
            libertyHist[Math.min(scratch.liberties[group], LIBERTY_HIST_BINS - 1)]++;
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
                        double w = INFLUENCE_WEIGHT[dx + 3][dy + 3];
                        if (board[nx][ny] == player) myInf += w;
                        else if (board[nx][ny] == opponent) oppInf += w;
                    }
                }
                if (myInf > oppInf) myControl++; else if (oppInf > myInf) oppControl++;
            }
        }
        aux[17] = (myControl - oppControl) / 100.0;
        aux[18] = evaluateConnections(scratch, player) / 20.0;
        aux[19] = evaluateSeparation(scratch, player) / 20.0;
        aux[20] = evaluateStrategicPoints(board, player) / 10.0;
        TerritoryResult tr = evaluateTerritory(scratch, player);
        aux[21] = (tr.myTerritory - tr.oppTerritory) / 50.0;

    }

    // ══════════════════════════════════════════════════════════════════════
    //  前向传播
    // ══════════════════════════════════════════════════════════════════════

    /** GPU 加速是否启用（可选配置 go.gpu，默认开）。false 时一律走 CPU，不初始化 OpenCL。 */
    private static volatile Boolean gpuEnabledCache = null;

    private static boolean isGpuEnabled() {
        // 系统属性每次重读：训练 CLI / 测试可以在进程内切换，且优先于配置缓存
        String prop = System.getProperty("go.gpu");
        if (prop != null) return Boolean.parseBoolean(prop);
        Boolean v = gpuEnabledCache;
        if (v != null) return v;
        try {
            v = GameSettings.getBoolean("go", "gpu", true);
        } catch (Throwable e) {
            v = true;
        }
        gpuEnabledCache = v;
        return v;
    }

    /**
     * 懒初始化 OpenCL 后端（GPU 启用且成功才使用，失败自动回退 CPU）。
     * 进程内共享一个上下文，避免每个评估器各自建队列。
     */
    private OpenCLBackend ensureOpenCL() {
        if (openclDisabled || !isGpuEnabled()) return null; // GPU 可选/初始化失败：走 CPU
        OpenCLBackend existing = opencl;
        if (existing != null) {
            if (existing.isAvailable()) return existing;
            openclDisabled = true;
            return null;
        }
        synchronized (this) {
            if (openclDisabled) return null;
            existing = opencl;
            if (existing != null) {
                if (existing.isAvailable()) return existing;
                openclDisabled = true;
                return null;
            }
            try {
                OpenCLBackend created = OpenCLBackend.acquireShared();
                if (created == null || !created.isAvailable()) {
                    openclDisabled = true;
                    return null;
                }
                opencl = created;
                return created;
            } catch (Throwable t) {
                // ★ 兜底：JNA 缺失/UnsatisfiedLinkError 等属于 Error，
                //   不能让 GPU 探测失败把整条推理路径炸掉，降级 CPU
                System.err.println("[NeuralEvaluator] OpenCL 初始化失败，回退 CPU: " + t);
                opencl = null;
                openclDisabled = true;
                return null;
            }
        }
    }

    /** 当前评估器是否已经拿到可用 GPU。探测失败后保持 false，不会反复加载。 */
    public boolean isGpuActive() {
        OpenCLBackend existing = opencl;
        if (existing != null && existing.isAvailable()) return true;
        return ensureOpenCL() != null;
    }

    public String gpuDeviceName() {
        OpenCLBackend existing = opencl;
        if (existing != null && existing.isAvailable()) return existing.getDeviceName();
        OpenCLBackend backend = ensureOpenCL();
        return backend == null ? "CPU" : backend.getDeviceName();
    }

    /**
     * 进程级 GPU 探测：打印一次设备名或失败原因，训练入口/探针共用。
     * 探测本身不长期占用引用；成功时驱动已加载，后续 {@link OpenCLBackend#acquireShared()} 复用。
     */
    public static GpuStatus detectGpu() {
        if (!isGpuEnabled()) return GpuStatus.disabled();
        try {
            OpenCLBackend existing = OpenCLBackend.peekShared();
            if (existing != null) return GpuStatus.available(existing.getDeviceName());
            OpenCLBackend backend = OpenCLBackend.acquireShared();
            if (backend != null && backend.isAvailable()) {
                GpuStatus status = GpuStatus.available(backend.getDeviceName());
                backend.close();
                return status;
            }
            String fail = OpenCLBackend.lastSharedFailure();
            return GpuStatus.unavailable(fail == null ? "OpenCL 不可用" : fail);
        } catch (Throwable t) {
            return GpuStatus.unavailable(String.valueOf(t));
        }
    }

    public static final class GpuStatus {
        public final boolean enabled;
        public final boolean available;
        public final String deviceName;
        public final String detail;

        private GpuStatus(boolean enabled, boolean available, String deviceName, String detail) {
            this.enabled = enabled;
            this.available = available;
            this.deviceName = deviceName;
            this.detail = detail;
        }

        static GpuStatus disabled() {
            return new GpuStatus(false, false, "CPU", "go.gpu=false");
        }

        static GpuStatus available(String device) {
            return new GpuStatus(true, true, device, "ok");
        }

        static GpuStatus unavailable(String reason) {
            return new GpuStatus(true, false, "CPU", reason == null ? "unavailable" : reason);
        }

        public String describe() {
            if (!enabled) return "GPU 已关闭，使用 CPU (" + detail + ")";
            if (available) return "GPU 可用: " + deviceName;
            return "GPU 不可用，回退 CPU (" + detail + ")";
        }
    }

    /**
     * 完整前向传播：三级分块 → 双头。GPU 可用时走 OpenCL，失败回退 CPU。
     */
    public ForwardResult forward(double[][][] planes, double[] auxFeatures) {
        try {
            lifecycleLock.readLock().lockInterruptibly();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return new ForwardResult(0.0, new double[POLICY_SIZE]);
        }
        try {
            if (released) return new ForwardResult(0.0, new double[POLICY_SIZE]);
            OpenCLBackend oc = ensureOpenCL();
            if (oc != null) {
                ForwardResult gpu = coalesceGpuForward(oc, planes, auxFeatures);
                if (gpu != null) return gpu;
            }
            modelLock.readLock().lock();
            try {
            return cpuForward(planes, auxFeatures);
            } finally {
                modelLock.readLock().unlock();
            }
        } finally {
            lifecycleLock.readLock().unlock();
        }
    }

    private ForwardResult coalesceGpuForward(OpenCLBackend oc, double[][][] planes, double[] auxFeatures) {
        GpuInferCall call = new GpuInferCall(planes, auxFeatures);
        gpuInferWaiters.incrementAndGet();
        gpuInferQueue.offer(call);
        try {
            while (!call.done.isDone()) {
                if (gpuInferDrain.tryLock()) {
                    try {
                        flushGpuInferQueue(oc);
                    } finally {
                        gpuInferDrain.unlock();
                    }
                    continue;
                }
                try {
                    return call.done.get(5, TimeUnit.MILLISECONDS);
                } catch (java.util.concurrent.TimeoutException ignored) {
                    // 排空线程可能刚好错过本请求，下一轮自己抢锁。
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    call.done.completeExceptionally(e);
                    return null;
                } catch (java.util.concurrent.ExecutionException e) {
                    return null;
                }
            }
            return joinGpuInfer(call);
        } finally {
            gpuInferWaiters.decrementAndGet();
        }
    }

    private static ForwardResult joinGpuInfer(GpuInferCall call) {
        try {
            return call.done.get();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return null;
        } catch (java.util.concurrent.ExecutionException | RuntimeException e) {
            return null;
        }
    }

    private void flushGpuInferQueue(OpenCLBackend oc) {
        while (true) {
            List<GpuInferCall> batch = new ArrayList<>(INFER_MAX_BATCH);
            GpuInferCall next;
            while (batch.size() < INFER_MAX_BATCH && (next = gpuInferQueue.poll()) != null) {
                batch.add(next);
            }
            if (batch.isEmpty()) return;
            if (batch.size() == 1 && gpuInferWaiters.get() > 1) {
                long deadline = System.nanoTime() + INFER_GATHER_NS;
                while (batch.size() < INFER_MAX_BATCH && System.nanoTime() < deadline) {
                    next = gpuInferQueue.poll();
                    if (next == null) {
                        LockSupport.parkNanos(50_000L);
                        continue;
                    }
                    batch.add(next);
                }
            }
            runGpuInferBatch(oc, batch);
        }
    }

    private void runGpuInferBatch(OpenCLBackend oc, List<GpuInferCall> batch) {
        int b = batch.size();
        modelLock.readLock().lock();
        try {
            double[][][][] planes = new double[b][][][];
            double[][] aux = new double[b][];
            for (int i = 0; i < b; i++) {
                GpuInferCall call = batch.get(i);
                planes[i] = call.planes;
                aux[i] = call.aux;
            }
            double[][] policyOut = new double[b][POLICY_SIZE];
            double[] valueOut = new double[b];
            boolean ok = oc.inferForward(planes, aux, b, gpuOwnerId, modelVersion,
                    subW1, subB1, blockW1, blockB1, topW1, topB1,
                    policyW, policyB, valueW1, valueB1, valueW2, valueB2,
                    policyOut, valueOut);
            if (ok) {
                for (int i = 0; i < b; i++) {
                    batch.get(i).done.complete(new ForwardResult(valueOut[i], policyOut[i]));
                }
                return;
            }
        } catch (Throwable t) {
            System.err.println("[NeuralEvaluator] GPU 前向失败，回退 CPU: " + t);
        } finally {
            modelLock.readLock().unlock();
        }
        modelLock.readLock().lock();
        try {
            for (GpuInferCall call : batch) {
                if (call.done.isDone()) continue;
                try {
                    call.done.complete(cpuForward(call.planes, call.aux));
                } catch (Throwable t) {
                    call.done.completeExceptionally(t);
                }
            }
        } finally {
            modelLock.readLock().unlock();
        }
    }

    private ForwardResult cpuForward(double[][][] planes, double[] auxFeatures) {
            CpuForwardScratch scratch = CPU_FORWARD_SCRATCH.get();
            // ── 第 1 级：二级子块 ──────────────────────────────────────
            // subOut[b][s][h] — 大块 b 的第 s 个子块的 16 维输出
            double[][][] subOut = scratch.subOut;
            for (int b = 0; b < NUM_BLOCKS; b++) {
                int bx = BLOCK_STARTS[b][0], by = BLOCK_STARTS[b][1];
                double[] b1 = subB1[b];
                for (int s = 0; s < SUBS_PER_BLOCK; s++) {
                    int sx = bx + SUB_OFFSETS[s][0], sy = by + SUB_OFFSETS[s][1];
                    // 提取 3×3×4 = 36 个值
                    double[] input = scratch.subInput;
                    int idx = 0;
                    for (int p = 0; p < PLANES; p++)
                        for (int dx = 0; dx < SUB_SIZE; dx++)
                            for (int dy = 0; dy < SUB_SIZE; dy++)
                                input[idx++] = planes[p][sx + dx][sy + dy];
                    // FC(36→16) + ReLU
                    cpuDense(subW1[b], b1, input, subOut[b][s], true);
                }
            }

            // ── 第 2 级：一级字块 ──────────────────────────────────────
            double[][] blockOut = scratch.blockOut;
            for (int b = 0; b < NUM_BLOCKS; b++) {
                // 拼接 9 个子块输出 → 144 维
                double[] input = scratch.blockInput;
                int idx = 0;
                for (int s = 0; s < SUBS_PER_BLOCK; s++)
                    for (int h = 0; h < SUB_HIDDEN; h++)
                        input[idx++] = subOut[b][s][h];
                // FC(144→64) + ReLU
                cpuDense(blockW1[b], blockB1[b], input, blockOut[b], true);
            }

            // ── 第 3 级：顶级 ──────────────────────────────────────────
            double[] topInput = scratch.topInput;
            int idx = 0;
            for (int b = 0; b < NUM_BLOCKS; b++)
                for (int h = 0; h < BLOCK_HIDDEN; h++)
                    topInput[idx++] = blockOut[b][h];
            // 拼接辅助特征
            System.arraycopy(auxFeatures, 0, topInput, NUM_BLOCKS * BLOCK_HIDDEN, AUX_SIZE);

            // FC(600→256) + ReLU
            double[] shared = scratch.shared;
            cpuDense(topW1, topB1, topInput, shared, true);

            // ── 策略头 ──────────────────────────────────────────────────
            double[] policy = new double[POLICY_SIZE];
            cpuDense(policyW, policyB, shared, policy, false);
            double maxLogit = Double.NEGATIVE_INFINITY;
            for (int j = 0; j < POLICY_SIZE; j++) {
                if (policy[j] > maxLogit) maxLogit = policy[j];
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
            double[] vh = scratch.valueHidden;
            cpuDense(valueW1, valueB1, shared, vh, true);
            double valueSum = valueB2;
            for (int i = 0; i < VALUE_HIDDEN; i++)
                valueSum += valueW2[i] * vh[i];
            double value = Math.tanh(valueSum);

            return new ForwardResult(value, policy);
    }

    /** Read contiguous weight rows while preserving each output's accumulation order. */
    private static void cpuDense(double[][] weights, double[] bias, double[] input,
                                 double[] output, boolean relu) {
        System.arraycopy(bias, 0, output, 0, output.length);
        for (int i = 0; i < input.length; i++) {
            double x = input[i];
            double[] row = weights[i];
            for (int j = 0; j < output.length; j++) {
                output[j] += row[j] * x;
            }
        }
        if (relu) {
            for (int j = 0; j < output.length; j++) output[j] = Math.max(0, output[j]);
        }
    }

    /**
     * 纯神经网络价值评估（不混合启发式），用于 MCTS 搜索。
     * 直接返回价值头输出（-1~1），不携带启发式偏差。
     */
    public double forwardValue(GoPlayer[][] board, GoPlayer player, int[] lastMove) {
        try {
            lifecycleLock.readLock().lockInterruptibly();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return 0.0;
        }
        try {
            if (released) return 0.0;
            // Configuration can enable GPU even when initialization failed.
            // Only an available backend needs the complete GPU forward path.
            if (ensureOpenCL() != null) {
                double[][][] planes = buildInputPlanes(board, player, lastMove);
                double[] aux = extractAuxFeatures(board, player);
                return forward(planes, aux).value;
            }
            InputScratch input = INPUT_SCRATCH.get();
            fillInputPlanes(input.planes, board, player, lastMove);
            fillAuxFeatures(input.aux, board, player);
            modelLock.readLock().lock();
            try {
                return cpuValueForward(input.planes, input.aux);
            } finally {
                modelLock.readLock().unlock();
            }
        } finally {
            lifecycleLock.readLock().unlock();
        }
    }

    /** CPU trunk plus value head only. Caller holds modelLock.readLock(). */
    private double cpuValueForward(double[][][] planes, double[] auxFeatures) {
        CpuForwardScratch scratch = CPU_FORWARD_SCRATCH.get();
        double[][][] subOut = scratch.subOut;
        for (int b = 0; b < NUM_BLOCKS; b++) {
            int bx = BLOCK_STARTS[b][0], by = BLOCK_STARTS[b][1];
            double[] b1 = subB1[b];
            for (int s = 0; s < SUBS_PER_BLOCK; s++) {
                int sx = bx + SUB_OFFSETS[s][0], sy = by + SUB_OFFSETS[s][1];
                double[] input = scratch.subInput;
                int idx = 0;
                for (int p = 0; p < PLANES; p++)
                    for (int dx = 0; dx < SUB_SIZE; dx++)
                        for (int dy = 0; dy < SUB_SIZE; dy++)
                            input[idx++] = planes[p][sx + dx][sy + dy];
                cpuDense(subW1[b], b1, input, subOut[b][s], true);
            }
        }

        double[][] blockOut = scratch.blockOut;
        for (int b = 0; b < NUM_BLOCKS; b++) {
            double[] input = scratch.blockInput;
            int idx = 0;
            for (int s = 0; s < SUBS_PER_BLOCK; s++)
                for (int h = 0; h < SUB_HIDDEN; h++)
                    input[idx++] = subOut[b][s][h];
            cpuDense(blockW1[b], blockB1[b], input, blockOut[b], true);
        }

        double[] topInput = scratch.topInput;
        int idx = 0;
        for (int b = 0; b < NUM_BLOCKS; b++)
            for (int h = 0; h < BLOCK_HIDDEN; h++)
                topInput[idx++] = blockOut[b][h];
        System.arraycopy(auxFeatures, 0, topInput, NUM_BLOCKS * BLOCK_HIDDEN, AUX_SIZE);
        double[] shared = scratch.shared;
        cpuDense(topW1, topB1, topInput, shared, true);

        double[] valueHidden = scratch.valueHidden;
        cpuDense(valueW1, valueB1, shared, valueHidden, true);
        double valueSum = valueB2;
        for (int i = 0; i < VALUE_HIDDEN; i++) valueSum += valueW2[i] * valueHidden[i];
        return Math.tanh(valueSum);
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

        try {
            lifecycleLock.writeLock().lockInterruptibly();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return 0;
        }
        try {
            if (released) return 0;
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
                boolean ranGpu = oc.batchPass0Forward(planes, auxFeatures, batchSize, gpuOwnerId, modelVersion,
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
            if (!Double.isFinite(norm)) {
                // ★ Bug修复：NaN 与裁剪阈值比较恒为 false，NaN 梯度会绕过裁剪直接写入全部权重且无法回滚。
                // 范数非有限时直接跳过本次权重更新，保留上一步的有效权重。
                System.err.println("[NeuralEvaluator] 检测到非有限梯度范数(" + norm + ")，跳过本次权重更新");
                return totalLoss / batchSize;
            }
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
        } finally {
            lifecycleLock.writeLock().unlock();
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

    private static void analyzeFeatureGroups(GoPlayer[][] board, FeatureScratch scratch) {
        for (int x = 0; x < BOARD_SIZE; x++) {
            System.arraycopy(board[x], 0, scratch.cells, x * BOARD_SIZE, BOARD_SIZE);
        }
        Arrays.fill(scratch.groupAt, -1);
        Arrays.fill(scratch.libertySeen, -1);
        scratch.groupCount = 0;
        for (int cell = 0; cell < scratch.cells.length; cell++) {
            GoPlayer color = scratch.cells[cell];
            if (color == GoPlayer.NONE || scratch.groupAt[cell] >= 0) continue;
            int group = scratch.groupCount++;
            scratch.colors[group] = color;
            int head = 0, tail = 1, liberties = 0, links = 0;
            scratch.queue[0] = cell;
            scratch.groupAt[cell] = group;
            while (head < tail) {
                int current = scratch.queue[head++];
                for (int neighbor : CELL_NEIGHBORS[current]) {
                    if (scratch.cells[neighbor] == GoPlayer.NONE) {
                        if (scratch.libertySeen[neighbor] != group) {
                            scratch.libertySeen[neighbor] = group;
                            liberties++;
                        }
                    } else if (scratch.cells[neighbor] == color) {
                        links++;
                        if (scratch.groupAt[neighbor] < 0) {
                            // Mark on enqueue: the queue is bounded by the board's 361 cells.
                            scratch.groupAt[neighbor] = group;
                            scratch.queue[tail++] = neighbor;
                        }
                    }
                }
            }
            scratch.sizes[group] = tail;
            scratch.liberties[group] = liberties;
            scratch.links[group] = links;
        }
    }

    private double evaluateConnections(FeatureScratch scratch, GoPlayer player) {
        double bonus = 0;
        for (int group = 0; group < scratch.groupCount; group++) {
            if (scratch.colors[group] == player) {
                int size = scratch.sizes[group];
                if (size >= 5) bonus += size * 0.3;
                bonus += scratch.links[group] * 0.5;
                if (scratch.liberties[group] >= 5) bonus += 2;
            }
        }
        return bonus;
    }

    private double evaluateSeparation(FeatureScratch scratch, GoPlayer player) {
        GoPlayer opponent = player == GoPlayer.BLACK ? GoPlayer.WHITE : GoPlayer.BLACK;
        double threat = 0;
        for (int cell = 0; cell < scratch.cells.length; cell++) {
            if (scratch.cells[cell] == GoPlayer.NONE) {
                int groupCount = 0, minLibs = Integer.MAX_VALUE;
                for (int neighbor : CELL_NEIGHBORS[cell]) {
                    if (scratch.cells[neighbor] == opponent) {
                        int group = scratch.groupAt[neighbor];
                        boolean duplicate = false;
                        for (int i = 0; i < groupCount; i++) {
                            if (scratch.adjacentGroups[i] == group) {
                                duplicate = true;
                                break;
                            }
                        }
                        if (!duplicate) {
                            scratch.adjacentGroups[groupCount++] = group;
                            minLibs = Math.min(minLibs, scratch.liberties[group]);
                        }
                    }
                }
                if (groupCount >= 2) {
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

    private TerritoryResult evaluateTerritory(FeatureScratch scratch, GoPlayer player) {
        GoPlayer opponent = player == GoPlayer.BLACK ? GoPlayer.WHITE : GoPlayer.BLACK;
        TerritoryResult result = new TerritoryResult();
        Arrays.fill(scratch.regionSeen, false);
        for (int cell = 0; cell < scratch.cells.length; cell++) {
            if (scratch.cells[cell] == GoPlayer.NONE && !scratch.regionSeen[cell]) {
                int head = 0, tail = 1, myBorder = 0, oppBorder = 0;
                scratch.queue[0] = cell;
                scratch.regionSeen[cell] = true;
                while (head < tail) {
                    int current = scratch.queue[head++];
                    for (int neighbor : CELL_NEIGHBORS[current]) {
                        GoPlayer color = scratch.cells[neighbor];
                        if (color == GoPlayer.NONE) {
                            if (!scratch.regionSeen[neighbor]) {
                                scratch.regionSeen[neighbor] = true;
                                scratch.queue[tail++] = neighbor;
                            }
                        } else if (color == player) {
                            myBorder++;
                        } else if (color == opponent) {
                            oppBorder++;
                        }
                    }
                }
                double tv = tail;
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
                        double w = INFLUENCE_WEIGHT[dx + 3][dy + 3];
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
        synchronized (modelIoLock(absolute)) {
            Path parent = absolute.getParent();
            if (parent != null) Files.createDirectories(parent);
            Path temp = Files.createTempFile(parent == null ? Path.of(".") : parent,
                    absolute.getFileName().toString() + ".", ".tmp");
            try {
                ModelWeights m = snapshot();
                // 缓冲流：模型 ~37 万 float 逐值写出，无缓冲时每次 writeFloat 都是一次系统调用
                try (DataOutputStream out = new DataOutputStream(new java.io.BufferedOutputStream(
                        Files.newOutputStream(temp, StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING),
                        1 << 16))) {
                    out.writeInt(MODEL_MAGIC);
                    out.writeInt(MODEL_FORMAT);
                    out.writeLong(m.version);
                    for (int b = 0; b < NUM_BLOCKS; b++) {
                        writeMatrix(out, m.subW1[b]); writeVector(out, m.subB1[b]);
                    }
                    for (int b = 0; b < NUM_BLOCKS; b++) {
                        writeMatrix(out, m.blockW1[b]); writeVector(out, m.blockB1[b]);
                    }
                    writeMatrix(out, m.topW1); writeVector(out, m.topB1);
                    writeMatrix(out, m.policyW); writeVector(out, m.policyB);
                    writeMatrix(out, m.valueW1); writeVector(out, m.valueB1);
                    writeVector(out, m.valueW2); out.writeFloat((float)m.valueB2);
                }
                replaceFile(temp, absolute);
            } finally {
                Files.deleteIfExists(temp);
            }
        }
    }

    /** Builds one position's features into a thread-local buffer and evaluates it. */
    public ForwardResult forwardPosition(GoPlayer[][] board, GoPlayer player, int[] lastMove) {
        InputScratch scratch = INPUT_SCRATCH.get();
        fillInputPlanes(scratch.planes, board, player, lastMove);
        fillAuxFeatures(scratch.aux, board, player);
        return forward(scratch.planes, scratch.aux);
    }

    private static Object modelIoLock(Path absolute) {
        return MODEL_IO_LOCKS.computeIfAbsent(absolute.normalize(), ignored -> new Object());
    }

    /**
     * 原子替换目标文件；Windows 上若另一线程/进程正持有目标文件的读句柄
     * （JVM 流不申请 FILE_SHARE_DELETE），replace 会抛 AccessDeniedException。
     * 读取窗口极短（缓冲流毫秒级），指数退避重试即可收敛。
     */
    private static void replaceFile(Path temp, Path absolute) throws IOException {
        IOException last = null;
        for (int attempt = 0; attempt < 6; attempt++) {
            try {
                try {
                    Files.move(temp, absolute, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
                } catch (java.nio.file.AtomicMoveNotSupportedException e) {
                    Files.move(temp, absolute, StandardCopyOption.REPLACE_EXISTING);
                }
                return;
            } catch (java.nio.file.AccessDeniedException e) {
                last = e;
                try {
                    Thread.sleep(20L << attempt);
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    throw new IOException("interrupted while replacing " + absolute, ie);
                }
            }
        }
        throw last;
    }

    public void load(Path path) throws IOException {
        Path absolute = path.toAbsolutePath();
        synchronized (modelIoLock(absolute)) {
            // 缓冲流：与 save 对称，避免 ~37 万次逐 float 读取的系统调用开销
            try (DataInputStream in = new DataInputStream(new java.io.BufferedInputStream(
                    Files.newInputStream(absolute), 1 << 16))) {
            int magic = in.readInt();
            int fmt = in.readInt();
            boolean currentFormat = magic == MODEL_MAGIC && fmt == MODEL_FORMAT;
            boolean legacyFormat = magic == LEGACY_MODEL_MAGIC && fmt == LEGACY_MODEL_FORMAT;
            if (!currentFormat && !legacyFormat) {
                throw new IOException("Unsupported model format: magic="
                        + Integer.toHexString(magic) + " fmt=" + fmt);
            }
            // 向后兼容：NEV2 用 double 8 字节，NEV3 用 float 4 字节。
            boolean isDouble = legacyFormat;
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
            double lValueB2 = readFinite(in, isDouble);
            if (in.read() != -1) {
                throw new IOException("Unexpected trailing data in model file");
            }

            ModelWeights m = new ModelWeights(lSubW1, lSubB1, lBlockW1, lBlockB1,
                    lTopW1, lTopB1, lPolicyW, lPolicyB,
                    lValueW1, lValueB1, lValueW2, lValueB2, ver);
                apply(m);
            }
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
        for (int i = 0; i < r; i++) for (int j = 0; j < c; j++) m[i][j] = readFinite(in, isDouble);
        return m;
    }
    private static void readMatrix(DataInputStream in, double[][] target, boolean isDouble) throws IOException {
        for (double[] r : target) for (int j = 0; j < r.length; j++) r[j] = readFinite(in, isDouble);
    }
    private static double[] readVector(DataInputStream in, int n, boolean isDouble) throws IOException {
        double[] v = new double[n];
        for (int i = 0; i < n; i++) v[i] = readFinite(in, isDouble);
        return v;
    }
    private static void readVector(DataInputStream in, double[] target, boolean isDouble) throws IOException {
        for (int i = 0; i < target.length; i++) target[i] = readFinite(in, isDouble);
    }
    private static double readFinite(DataInputStream in, boolean isDouble) throws IOException {
        double value = isDouble ? in.readDouble() : in.readFloat();
        if (!Double.isFinite(value)) throw new IOException("Non-finite model weight");
        return value;
    }
}
