package com.wzz.game_console.client.screens.games.gogame;

import com.wzz.game_console.util.GameSettings;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.locks.ReentrantLock;

/**
 * 全面升级版 MCTS 围棋 AI。
 * <p>
 * 核心优化：
 * <ul>
 *   <li>并行多线程 MCTS 搜索（每线程独立树，模拟结束后合并）</li>
 *   <li>增强评估函数：死活判断、真假眼、劫材价值、区域控制</li>
 *   <li>杀棋检测：Ladder、Atari 识别</li>
 *   <li>扩展开局定式库</li>
 *   <li>终局判断与收官策略</li>
 *   <li>MCTS 树重用 + RAVE + 共享树并行搜索</li>
 * </ul>
 */
public class MCTSGoAI implements GoAI {
    private static final Logger LOGGER = LoggerFactory.getLogger(MCTSGoAI.class);
    /** 默认搜索时间（毫秒） */
    private static final int DEFAULT_SEARCH_TIME = 3000;
    private static final int PASS_INDEX = BOARD_SIZE * BOARD_SIZE;
    private static final int POLICY_SIZE = PASS_INDEX + 1;
    private static final int[] PASS_MOVE = {-1, -1, 0};
    /** 默认迭代次数上限 */
    private static final int DEFAULT_ITERATIONS = 5000;
    /** 并行搜索线程数；高核心数机器也限制 worker 峰值，避免多 AI 实例制造数百线程。 */
    private static final int PARALLEL_THREADS = Math.max(1,
            Math.min(32, Runtime.getRuntime().availableProcessors() - 1));

    // ══════════════════════════════════════════════════════════════════
    //  动态时间控制参数
    // ══════════════════════════════════════════════════════════════════
    /** 开局阶段阈值（手数） */
    private static final int OPENING_THRESHOLD = 30;
    /** 终局阶段阈值（棋子数） */
    private static final int ENDGAME_STONES = 120;
    /** 必胜/必败检测阈值 */
    private static final double WIN_THRESHOLD = 0.95;
    private static final double LOSS_THRESHOLD = -0.95;
    /** 置信区间内提前终止的最小访问次数 */
    private static final int MIN_VISITS_FOR_TERMINATION = 50;
    /** 早停门槛：最高胜率分支自身至少要被访问这么多次，其胜率才可信（防 2 连胜假信号截断搜索） */
    private static final int MIN_EARLY_STOP_BEST_VISITS = 32;

    // ══════════════════════════════════════════════════════════════════
    //  Dirichlet 噪声参数（根节点探索增强，仅用于自对弈训练）
    // ══════════════════════════════════════════════════════════════════
    /** Dirichlet 浓度参数；越大越分散，越小越集中 */
    private static final double DIRICHLET_ALPHA = 0.03;
    /** 噪声与均匀先验的混合比例：final = (1-eps) * prior + eps * noise */
    private static final double DIRICHLET_EPS = 0.25;

    private final int baseSearchTime;
    private final int maxIterations;
    private final int parallelThreads;

    /** 神经网络评估器 */
    private final NeuralEvaluator neuralEvaluator;
    /** Serializes searches with shutdown so evaluator native resources remain live while used. */
    private final ReentrantLock lifecycleLock = new ReentrantLock();
    private volatile boolean shutdown;
    private boolean evaluatorReleased;

    /** 自对弈训练模式：启用根节点 Dirichlet 噪声增强探索（对局模式禁用） */
    private volatile boolean selfPlayMode = false;

    /** 当前对局的全部历史局面哈希（super-ko 全局同型检测），从 GoGame 同步 */
    private volatile Set<Long> koHistory = null;
    /** Fixed komi snapshot for terminal nodes in the current search. */
    private volatile double searchKomi = GoGame.DEFAULT_KOMI;

    /** 上一手评估值（用于趋势判断） */
    private double lastEvaluation = 0;
    /** 连续优势/劣势回合数 */
    private int advantageStreak = 0;
    /** 劣势连续回合数 */
    private int disadvantageStreak = 0;

    private Random random;
    private MCTSNode lastRoot = null;
    private int[] lastMove = null;
    private MCTSNode currentRoot;

    /** 并行搜索时用于追踪总迭代次数的原子变量 */
    private final AtomicLong totalIterations = new AtomicLong(0);
    /** 使超时或关闭后的迟到 worker 无法继续修改已结束的搜索树。 */
    private final AtomicLong searchGeneration = new AtomicLong();

    // ══════════════════════════════════════════════════════════════════
    //  构造函数
    // ══════════════════════════════════════════════════════════════════

    public MCTSGoAI() {
        this(DEFAULT_SEARCH_TIME, DEFAULT_ITERATIONS);
    }

    public MCTSGoAI(int searchTime, int maxIterations) {
        this(searchTime, maxIterations, Math.max(1, PARALLEL_THREADS - 1));
    }

    public MCTSGoAI(int searchTime, int maxIterations, int parallelThreads) {
        this(searchTime, maxIterations, parallelThreads, null);
    }

    /** Creates an AI using a private evaluator initialized from a model snapshot. */
    public MCTSGoAI(int searchTime, int maxIterations, int parallelThreads,
                    NeuralEvaluator.ModelWeights model) {
        if (searchTime < 0 || maxIterations < 0 || parallelThreads < 1) {
            throw new IllegalArgumentException("Invalid MCTS parameters");
        }
        this.baseSearchTime = searchTime;
        this.maxIterations = maxIterations;
        this.parallelThreads = parallelThreads;
        this.random = new Random();
        // 用 fromWeights 跳过随机 init（省去 init+apply 双重开销）
        if (model != null) {
            this.neuralEvaluator = NeuralEvaluator.fromWeights(model);
        } else {
            this.neuralEvaluator = new NeuralEvaluator();
        }
    }

    /** Sets the random seed for a self-play game. */
    public void setRandomSeed(long seed) {
        this.random.setSeed(seed);
    }

    /** 切换自对弈训练模式（启用根节点 Dirichlet 探索噪声）。对局模式（默认）关闭噪声以保证棋力。 */
    public void setSelfPlayMode(boolean selfPlayMode) {
        this.selfPlayMode = selfPlayMode;
    }

    /** 当前探索强度倍率（自对弈训练用，随代次衰减：1.0 → ~0.2） */
    private volatile double explorationScale = 1.0;

    /** 设置探索强度倍率（影响 Dirichlet 噪声比例和采样温度）。范围 [0,1]。 */
    public void setExplorationScale(double scale) {
        this.explorationScale = Math.max(0, Math.min(1, scale));
    }

    /**
     * 从 GameSettings 创建 AI；若配置了 {@code go.modelPath} 且文件有效，
     * 对局使用训练入口保存的 checkpoint 权重，否则使用内置随机初始化。
     * 任何加载失败都只降级为随机初始化，绝不阻断对局创建。
     */
    public static MCTSGoAI createFromSettings() {
        try {
            int searchTime = GameSettings.getInt("go", "searchTime", DEFAULT_SEARCH_TIME);
            int iterations = GameSettings.getInt("go", "mctsIterations", DEFAULT_ITERATIONS);
            NeuralEvaluator trained = loadConfiguredEvaluator();
            if (trained != null) {
                return new MCTSGoAI(searchTime, iterations,
                        Math.max(1, PARALLEL_THREADS - 1), trained.snapshot());
            }
            return new MCTSGoAI(searchTime, iterations);
        } catch (Throwable t) {
            return new MCTSGoAI();
        }
    }

    /**
     * 运行时加载 {@code go.modelPath} 指向的 checkpoint（NEV2/NEV3 均支持，
     * 与 {@link NeuralEvaluator#load} 契约一致）。未配置、文件缺失或损坏时返回
     * null，由调用方回退到随机初始化。
     */
    private static NeuralEvaluator loadConfiguredEvaluator() {
        String modelPath;
        try {
            modelPath = GameSettings.getString("go", "modelPath", "");
        } catch (Throwable t) {
            return null;
        }
        if (modelPath == null || modelPath.isBlank()) return null;
        Path path = Path.of(modelPath);
        if (!Files.isRegularFile(path)) {
            LOGGER.warn("[围棋AI] 配置的模型文件不存在，使用随机初始化权重: {}", modelPath);
            return null;
        }
        try {
            NeuralEvaluator evaluator = new NeuralEvaluator();
            evaluator.load(path);
            LOGGER.info("[围棋AI] 已加载训练模型 (version={}): {}",
                    evaluator.getModelVersion(), modelPath);
            return evaluator;
        } catch (IOException | RuntimeException e) {
            LOGGER.warn("[围棋AI] 模型加载失败，使用随机初始化权重: {} ({})",
                    modelPath, e.getMessage());
            return null;
        }
    }

    // ══════════════════════════════════════════════════════════════════
    //  动态时间控制
    // ══════════════════════════════════════════════════════════════════

    /**
     * 根据对局阶段计算搜索时间
     */
    private int calculateDynamicSearchTime(int moveCount, int validMoveCount) {
        int time = baseSearchTime;

        // 开局阶段：快速落子
        if (moveCount < OPENING_THRESHOLD) {
            time = (int)(baseSearchTime * 0.4); // 40% 时间
        }
        // 中盘阶段：完整搜索
        else if (moveCount < 80) {
            time = baseSearchTime;
        }
        // 终局阶段：快速收官
        else {
            time = (int)(baseSearchTime * 0.6); // 60% 时间
        }

        // 根据候选点数量调整
        if (validMoveCount > 50) {
            time = (int)(time * 1.2); // 更多候选点需要更多时间
        } else if (validMoveCount < 10) {
            time = (int)(time * 0.7); // 少候选点可以更快
        }

        // 根据局势紧张程度调整
        if (advantageStreak >= 3) {
            time = (int)(time * 1.3); // 我方连续占优，延长时间确保
        } else if (disadvantageStreak >= 2) {
            time = (int)(time * 1.5); // 我方连续劣势，延长思考
        }

        return Math.max(500, Math.min(time, baseSearchTime * 2)); // 500ms ~ 2x base
    }

    /**
     * 检查是否应该提前终止搜索
     */
    private boolean shouldTerminateEarly(long iterations) {
        if (iterations < MIN_VISITS_FOR_TERMINATION) {
            return false;
        }

        // 快照 currentRoot.children（共享树下其他线程可能并发写入，需同步）
        MCTSNode root = currentRoot;
        if (root == null) return false;
        List<MCTSNode> children;
        synchronized (root) {
            if (root.children == null || root.children.isEmpty()) return false;
            children = new ArrayList<>(root.children);
        }

        double bestWinRate = Double.NEGATIVE_INFINITY;
        double secondWinRate = Double.NEGATIVE_INFINITY;
        double bestWinRateVisits = 0;

        for (MCTSNode child : children) {
            double visits;
            double totalScore;
            synchronized (child) {
                visits = child.visits;
                totalScore = child.totalScore;
            }
            if (visits > 0) {
                // 子节点是"对手行棋方"视角，父节点视角需取反（节点存自身行棋方视角）
                double winRate = -totalScore / visits;
                if (winRate > bestWinRate) {
                    secondWinRate = bestWinRate;
                    bestWinRate = winRate;
                    bestWinRateVisits = visits;
                } else if (winRate > secondWinRate) {
                    secondWinRate = winRate;
                }
            }
        }

        // 无任何子节点有访问时，bestWinRate 保持 -INF，不能当作必败触发提前终止
        if (bestWinRate == Double.NEGATIVE_INFINITY) {
            return false;
        }
        // ★ 最访问数门槛：胜率均值在极少访问下方差极大（2 连胜即 100%），
        //   必胜/必败/置信区间判定都必须建立在最高胜率分支被充分搜索的基础上，
        //   否则开局几手就可能被低访问高方差的假信号提前截断搜索
        if (bestWinRateVisits < MIN_EARLY_STOP_BEST_VISITS) {
            return false;
        }
        // 必胜/必败检测
        if (bestWinRate > WIN_THRESHOLD) {
            return true;
        }
        if (bestWinRate < LOSS_THRESHOLD) {
            return true;
        }

        // 置信区间判断：仅当至少两个子节点被访问（secondWinRate 有限）时才启用，
        // 否则 bestWinRate - (-INF) = +INF 会导致搜索在第 1 个子节点被访问后立即提前终止
        if (secondWinRate != Double.NEGATIVE_INFINITY) {
            double margin = bestWinRate - secondWinRate;
            if (margin > 0.3 && iterations > MIN_VISITS_FOR_TERMINATION * 2) {
                return true;
            }
        }

        return false;
    }

    /**
     * 更新局势评估
     */
    private void updateGameAssessment() {
        if (lastEvaluation > 0.3) {
            advantageStreak++;
            disadvantageStreak = 0;
        } else if (lastEvaluation < -0.3) {
            disadvantageStreak++;
            advantageStreak = 0;
        } else {
            advantageStreak = 0;
            disadvantageStreak = 0;
        }
    }

    // ══════════════════════════════════════════════════════════════════
    //  MCTS 搜索
    // ══════════════════════════════════════════════════════════════════

    @Override
    public int[] getBestMove(GoGame game) {
        MoveResult result = getBestMoveResult(game);
        return result.type() == MoveType.MOVE ? result.coordinates() : null;
    }

    @Override
    public MoveResult getBestMoveResult(GoGame game) {
        try {
            lifecycleLock.lockInterruptibly();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return MoveResult.error();
        }
        try {
            if (shutdown || Thread.currentThread().isInterrupted()) return MoveResult.error();
            int[] move = getBestMoveLocked(game);
            if (shutdown || Thread.currentThread().isInterrupted()) return MoveResult.error();
            return move == null ? MoveResult.pass() : MoveResult.move(move[0], move[1]);
        } finally {
            if (shutdown) releaseEvaluatorLocked();
            lifecycleLock.unlock();
        }
    }

    private int[] getBestMoveLocked(GoGame game) {
        GoGame.PositionSnapshot snapshot = game.positionSnapshot();
        GoPlayer[][] board = snapshot.board();
        GoPlayer currentPlayer = snapshot.currentPlayer();
        int moveCount = snapshot.moveCount();

        // super-ko 历史：从同一局面修订的快照同步全部历史局面哈希（含当前局面），
        // 必须在 getAllValidMoves 之前设置，使根节点走法也经过 super-ko 过滤
        this.koHistory = snapshot.positionHistory();
        this.searchKomi = GoGame.getConfiguredKomi();

        List<int[]> validMoves = getAllValidMoves(snapshot.currentHash(), board, currentPlayer);
        int consecutivePasses = snapshot.consecutivePasses();
        int stoneCount = countStones(board);
        boolean passCandidate = consecutivePasses > 0 || stoneCount >= ENDGAME_STONES || validMoves.isEmpty();
        if (!passCandidate) {
            // 杀棋、定式与局部战术只处理棋盘落子；进入收官或已有一手 PASS 后，
            // 必须交给包含 PASS 的树搜索比较继续落子和结束对局。
            int[] killerMove = findKillerMove(board, currentPlayer, validMoves);
            if (killerMove != null) { this.lastMove = killerMove; this.currentRoot = null; return killerMove; }

            int[] bookMove = getOpeningBookMove(board, currentPlayer, validMoves, moveCount);
            if (bookMove != null) { this.lastMove = bookMove; this.currentRoot = null; return bookMove; }

            int tacticalBudget = Math.max(200, Math.min(800, baseSearchTime / 4));
            int[] tacticalMove = tacticalReading(board, currentPlayer, System.currentTimeMillis() + tacticalBudget);
            if (tacticalMove != null && isLegalMove(board, tacticalMove[0], tacticalMove[1], currentPlayer)
                    && !isKoIllegal(board, tacticalMove[0], tacticalMove[1], currentPlayer)) {
                this.lastMove = tacticalMove;
                this.currentRoot = null;
                return tacticalMove;
            }
        }

        if (validMoves.size() == 1 && !passCandidate) {
            int[] m = validMoves.get(0);
            this.lastMove = new int[]{m[0], m[1]};
            this.currentRoot = null;
            return new int[]{m[0], m[1]};
        }

        // maxIterations 为 0 时不启动空搜索；直接使用合法走法作为安全兜底，
        // 避免并行 worker 全部拿到 0 次迭代后 getBestMCTSMove 返回 null。
        if (maxIterations == 0) {
            if (validMoves.isEmpty() || consecutivePasses > 0) {
                this.currentRoot = null;
                this.lastRoot = null;
                this.lastMove = null;
                return null;
            }
            int[] fallback = validMoves.get(validMoves.size() - 1);
            this.lastMove = new int[]{fallback[0], fallback[1]};
            this.currentRoot = null;
            return new int[]{fallback[0], fallback[1]};
        }

        List<int[]> searchMoves = new ArrayList<>(validMoves);
        if (passCandidate) searchMoves.add(PASS_MOVE.clone());

        // 动态计算搜索时间
        int searchTime = calculateDynamicSearchTime(moveCount, validMoves.size());

        // 树重用
        MCTSNode reusedRoot = tryReuseTree(board, currentPlayer);
        // 上一手位置（plane 3）：新鲜根节点需要设置 move 以保证与训练分布一致
        int[] gameLastMove = snapshot.lastMove();
        if (reusedRoot != null) {
            this.currentRoot = reusedRoot;
            this.currentRoot.player = currentPlayer;
            this.currentRoot.parent = null;
        } else {
            // 启发式排序候选点（getAllValidMoves 已按价值升序排好）
            this.currentRoot = new MCTSNode(board, currentPlayer, null, gameLastMove, searchMoves);
        }
        this.currentRoot.consecutivePasses = consecutivePasses;
        this.currentRoot.terminal = consecutivePasses >= 2;
        this.currentRoot.hash = snapshot.currentHash();

        // 根节点 Dirichlet 噪声（仅在自对弈训练时启用，对局模式关闭以保证棋力）
        if (selfPlayMode) {
            applyRootNoise(this.currentRoot);
        }

        long deadline = System.currentTimeMillis() + searchTime;
        long generation = searchGeneration.incrementAndGet();
        totalIterations.set(0);

        // 并行 MCTS 搜索（带提前终止）
        if (parallelThreads > 1) {
            parallelSearchWithEarlyTerminate(currentRoot, deadline, generation);
        } else {
            sequentialSearchWithEarlyTerminate(deadline);
        }

        // 更新局势评估
        this.lastEvaluation = getCurrentWinRate();
        updateGameAssessment();

        this.lastRoot = this.currentRoot;
        int[] best;
        if (selfPlayMode) {
            // 自对弈：按访问分布采样（含温度控制），增强探索多样性
            best = sampleMCTSMove(currentRoot, moveCount);
        } else {
            best = getBestMCTSMove(currentRoot); // 对局：贪心选最高胜率
        }
        // 搜索可能因极短时间预算、线程异常或所有候选扩展被过滤而没有访问节点。
        if (best == null) {
            if (validMoves.isEmpty()) {
                best = PASS_MOVE.clone();
            } else {
                int[] fallback = validMoves.get(validMoves.size() - 1);
                best = new int[]{fallback[0], fallback[1]};
            }
            this.currentRoot = null;
        }
        this.lastMove = new int[]{best[0], best[1]};
        return isPass(best) ? null : new int[]{best[0], best[1]};
    }

    /**
     * 返回上一次搜索的 MCTS 访问分布（362 维），作为策略训练目标。
     * 索引 0~360 为棋盘 361 个位置，索引 361 为弃权 pass。
     */
    public double[] getVisitDistribution() {
        double[] dist = new double[POLICY_SIZE];
        MCTSNode root = currentRoot;
        List<MCTSNode> children = null;
        if (root != null) {
            synchronized (root) {
                if (root.children != null && !root.children.isEmpty()) {
                    children = new ArrayList<>(root.children);
                }
            }
        }
        if (children == null) {
            // 无 MCTS 分布（早退路径：杀棋/定式/终局/战术/唯一走法）。
            // 返回 lastMove 的 one-hot，避免全 pass 污染策略目标。
            if (lastMove != null && lastMove.length >= 2) {
                dist[policyIndex(lastMove)] = 1.0;
                return dist;
            }
            // 连 lastMove 都没有（理论上不会走到），退化为全 pass
            dist[PASS_INDEX] = 1.0;
            return dist;
        }
        double total = 0;
        for (MCTSNode child : children) {
            double visits;
            synchronized (child) {
                visits = child.visits;
            }
            if (visits > 0 && child.move != null) {
                total += visits;
                dist[policyIndex(child.move)] += visits;
            }
        }

        if (total > 0) {
            for (int i = 0; i < POLICY_SIZE; i++) dist[i] /= total;
        } else if (lastMove != null && lastMove.length >= 2) {
            dist[policyIndex(lastMove)] = 1.0;
        } else {
            dist[PASS_INDEX] = 1.0;
        }
        return dist;
    }

    /**
     * 对根节点应用 Dirichlet 噪声，增强 MCTS 搜索的探索多样性。
     * 噪声仅对当前根节点的子节点生效，非根节点无影响。
     */
    private void applyRootNoise(MCTSNode root) {
        if (root == null) return;
        // 统计候选走法总数：已展开的子节点 + 未展开的候选
        int n = (root.children == null ? 0 : root.children.size())
                + (root.untriedMoves == null ? 0 : root.untriedMoves.size());
        if (n < 2) return;
        double[] noise = dirichletSample(n, DIRICHLET_ALPHA, this.random);
        // 探索衰减：噪声混入比例随训练代次降低
        double eps = DIRICHLET_EPS * explorationScale;
        Map<String, Double> noiseMap = new HashMap<>(n * 2);
        int i = 0;
        // 对已展开的子节点，直接设置 prior（噪声按策略先验的 ×361 缩放对齐）
        if (root.children != null) {
            for (MCTSNode child : root.children) {
                if (child.move == null) { i++; continue; }
                double v = noise[i++];
                noiseMap.put(actionKey(child.move), v);
                child.prior = (1.0 - eps) * child.prior + eps * (v * PASS_INDEX);
            }
        }
        // 对未展开的候选，仅记录噪声，expand 时读取
        if (root.untriedMoves != null) {
            for (int[] m : root.untriedMoves) {
                double v = noise[i++];
                noiseMap.put(actionKey(m), v);
            }
        }
        root.rootNoise = noiseMap;
    }

    /**
     * 从 Dirichlet(alpha) 分布采样 n 个值。
     * 使用 Ahrens 算法对 shape < 1 的 Gamma 采样，再归一化。
     */
    private static double[] dirichletSample(int n, double alpha, Random rnd) {
        double[] z = new double[n];
        double sum = 0;
        for (int i = 0; i < n; i++) {
            z[i] = gammaSample(alpha, rnd);
            sum += z[i];
        }
        if (sum <= 0) {
            // 退化情况：全部为 0，回退到均匀分布
            for (int i = 0; i < n; i++) z[i] = 1.0 / n;
            return z;
        }
        for (int i = 0; i < n; i++) z[i] /= sum;
        return z;
    }

    /**
     * Gamma(shape, 1) 采样，支持 shape < 1（Ahrens-Dieter 算法）。
     */
    private static double gammaSample(double shape, Random rnd) {
        if (shape < 1e-9) return 0.0;
        if (shape >= 1.0) {
            // Marsaglia-Tsang 算法对于 shape >= 1
            double d = shape - 1.0 / 3.0;
            double c = 1.0 / Math.sqrt(9.0 * d);
            while (true) {
                double x, v;
                do {
                    x = rnd.nextGaussian();
                    v = 1.0 + c * x;
                } while (v <= 0);
                v = v * v * v;
                double u = rnd.nextDouble();
                double rsq = x * x;
                if (u < 1.0 - 0.0331 * rsq * rsq) return d * v;
                if (Math.log(u) < 0.5 * rsq + d * (1.0 - v + Math.log(v))) return d * v;
            }
        } else {
            // Ahrens-Dieter 算法 for shape < 1
            double e = Math.E + shape;
            while (true) {
                double u = rnd.nextDouble();
                double p = e * u;
                if (p > 1.0) {
                    double x = -Math.log((e - p) / shape);
                    if (rnd.nextDouble() < Math.pow(x, shape - 1.0)) return x;
                } else {
                    double x = Math.pow(p, 1.0 / shape);
                    if (rnd.nextDouble() < Math.exp(-x)) return x;
                }
            }
        }
    }

    /**
     * 获取当前最佳走法的胜率
     */
    private double getCurrentWinRate() {
        double bestWinRate = Double.NEGATIVE_INFINITY;
        List<MCTSNode> children;
        synchronized (currentRoot) {
            if (currentRoot.children == null || currentRoot.children.isEmpty()) return 0;
            children = new ArrayList<>(currentRoot.children);
        }
        for (MCTSNode child : children) {
            double visits;
            double totalScore;
            synchronized (child) {
                visits = child.visits;
                totalScore = child.totalScore;
            }
            if (visits > 0) {
                // 子节点是"对手行棋方"视角，根视角需取反
                double winRate = -totalScore / visits;
                if (winRate > bestWinRate) {
                    bestWinRate = winRate;
                }
            }
        }
        // 无任何子节点有访问时返回 0，避免 NEGATIVE_INFINITY 污染局势评估
        return bestWinRate == Double.NEGATIVE_INFINITY ? 0 : bestWinRate;
    }

    /**
     * 并行 MCTS 搜索（共享树）。
     * <p>
     * 所有线程共享同一棵搜索树；节点扩展和回传分别在节点锁内完成，
     * 无需树克隆和合并。
     */
    private void parallelSearchWithEarlyTerminate(MCTSNode searchRoot, long deadline, long generation) {
        // 只启动有迭代预算的 worker，并将余数平均分配，确保 maxIterations <
        // parallelThreads 时仍至少有一个 worker 执行一次迭代，同时不超出总预算。
        int workerCount = Math.min(parallelThreads, maxIterations);
        int baseIterations = maxIterations / workerCount;
        int remainder = maxIterations % workerCount;
        CompletionService<Void> completions = new ExecutorCompletionService<>(SHARED_POOL);
        List<Future<Void>> futures = new ArrayList<>(workerCount);
        AtomicLong activeWorkers = new AtomicLong();
        Object workerMonitor = new Object();

        for (int i = 0; i < workerCount; i++) {
            final int workerIterations = baseIterations + (i < remainder ? 1 : 0);
            futures.add(completions.submit(() -> {
                synchronized (workerMonitor) {
                    if (shutdown || searchGeneration.get() != generation
                            || Thread.currentThread().isInterrupted()) return null;
                    activeWorkers.incrementAndGet();
                }
                try {
                    int iters = 0;
                    while (iters < workerIterations && System.currentTimeMillis() < deadline
                            && !shutdown && searchGeneration.get() == generation
                            && !Thread.currentThread().isInterrupted()) {
                        if (shouldTerminateEarly(totalIterations.get())) break;
                        iters++;
                        totalIterations.incrementAndGet();

                        MCTSNode node = selectNode(searchRoot);
                        MCTSNode leaf = node;
                        MCTSNode expanded = expand(node);
                        if (expanded != null) leaf = expanded;
                        double score = simulate(leaf);

                        // Native/自定义 evaluator 可能在 deadline 后才返回；迟到结果不得回传。
                        if (shutdown || searchGeneration.get() != generation
                                || Thread.currentThread().isInterrupted()) break;
                        backpropagate(leaf, score);
                    }
                    return null;
                } finally {
                    if (activeWorkers.decrementAndGet() == 0L) {
                        synchronized (workerMonitor) {
                            workerMonitor.notifyAll();
                        }
                    }
                }
            }));
        }

        // 所有 Future 共享一次搜索截止时间。额外 1 秒只用于正常 worker 收尾，
        // 不再按 worker 数量叠加每个 Future 的等待上限。
        long remainingMillis = Math.max(0L, deadline - System.currentTimeMillis());
        long waitDeadline = System.nanoTime()
                + TimeUnit.MILLISECONDS.toNanos(remainingMillis + 1_000L);
        int completed = 0;
        boolean cancelled = false;
        try {
            while (completed < workerCount && !shutdown
                    && !Thread.currentThread().isInterrupted()) {
                long remainingNanos = waitDeadline - System.nanoTime();
                if (remainingNanos <= 0L) {
                    cancelled = true;
                    break;
                }
                Future<Void> finished = completions.poll(
                        Math.min(remainingNanos, TimeUnit.MILLISECONDS.toNanos(100L)),
                        TimeUnit.NANOSECONDS);
                if (finished == null) continue;
                completed++;
                try {
                    finished.get();
                } catch (CancellationException | ExecutionException ignored) {
                }
            }
            if (completed < workerCount) cancelled = true;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            cancelled = true;
        } finally {
            if (cancelled || shutdown || Thread.currentThread().isInterrupted()) {
                searchGeneration.compareAndSet(generation, generation + 1L);
                for (Future<Void> future : futures) future.cancel(true);
            }
            boolean restoreInterrupt = Thread.interrupted();
            synchronized (workerMonitor) {
                while (activeWorkers.get() > 0L) {
                    try {
                        workerMonitor.wait();
                    } catch (InterruptedException e) {
                        restoreInterrupt = true;
                        searchGeneration.compareAndSet(generation, generation + 1L);
                        for (Future<Void> future : futures) future.cancel(true);
                    }
                }
            }
            if (restoreInterrupt) Thread.currentThread().interrupt();
        }
        // 共享线程池不关闭（线程设为 daemon，随进程退出）
    }

    /**
     * 顺序 MCTS 搜索（带提前终止）
     */
    private void sequentialSearchWithEarlyTerminate(long deadline) {
        int iterations = 0;
        while (iterations < maxIterations && System.currentTimeMillis() < deadline
                && !shutdown && !Thread.currentThread().isInterrupted()) {
            iterations++;
            totalIterations.set(iterations);
            // 提前终止检查
            if (shouldTerminateEarly(iterations)) {
                break;
            }
            MCTSNode node = selectNode(currentRoot);
            MCTSNode leaf = node;
            MCTSNode expanded = expand(node);
            if (expanded != null) leaf = expanded;
            double score = simulate(leaf);
            backpropagate(leaf, score);
        }
    }

    /**
     * Expands one action and returns the new leaf that must be evaluated this iteration.
     */
    private MCTSNode expand(MCTSNode node) {
        int[] moveFull;
        synchronized (node) {
            if (node.terminal || node.untriedMoves.isEmpty()) return null;
            moveFull = node.untriedMoves.remove(node.untriedMoves.size() - 1);
        }
        int[] move = new int[]{moveFull[0], moveFull[1]};

        boolean needsForward;
        synchronized (node) {
            needsForward = node.policyCache == null && !node.forwardInFlight;
            if (needsForward) node.forwardInFlight = true;
        }
        if (needsForward) {
            NeuralEvaluator.ForwardResult fr;
            try {
                fr = neuralEvaluator.forward(
                        neuralEvaluator.buildInputPlanes(node.board, node.player, node.move),
                        neuralEvaluator.extractAuxFeatures(node.board, node.player));
            } catch (RuntimeException | Error e) {
                synchronized (node) {
                    node.forwardInFlight = false;
                    node.notifyAll();
                }
                throw e;
            }

            synchronized (node) {
                node.policyCache = fr.policy;
                node.valueCache = fr.value;
                node.valueCached = true;
                if (node.untriedMoves.size() > 1) {
                    double[] policy = node.policyCache;
                    node.untriedMoves.sort((a, b) -> Double.compare(
                            policy[policyIndex(a)], policy[policyIndex(b)]));
                    double total = 0;
                    for (int[] candidate : node.untriedMoves) total += policy[policyIndex(candidate)];
                    if (total > 0) {
                        double cumulative = 0;
                        List<int[]> kept = new ArrayList<>(node.untriedMoves.size());
                        for (int i = node.untriedMoves.size() - 1; i >= 0; i--) {
                            int[] candidate = node.untriedMoves.get(i);
                            kept.add(candidate);
                            cumulative += policy[policyIndex(candidate)];
                            if (cumulative >= 0.95 * total && kept.size() >= 3) break;
                        }
                        Collections.reverse(kept);
                        node.untriedMoves = kept;
                    }
                }
                node.forwardInFlight = false;
                node.notifyAll();
            }
        }

        GoPlayer[][] childBoard = deepCopyBoard(node.board);
        GoPlayer nextPlayer = opposite(node.player);
        int childPasses;
        long childHash;
        boolean terminal;
        if (isPass(move)) {
            childPasses = node.consecutivePasses + 1;
            childHash = node.hash != 0 ? node.hash : GoGame.boardHash(childBoard);
            terminal = childPasses >= 2;
        } else {
            if (!simulatePlaceStone(childBoard, move[0], move[1], node.player)) return null;
            childHash = GoGame.boardHash(childBoard);
            if (koHistory != null && (koHistory.contains(childHash) || isAncestorKoRepeat(node, childHash))) {
                return null;
            }
            childPasses = 0;
            terminal = false;
        }

        List<int[]> childMoves = terminal
                ? Collections.emptyList()
                : getSearchMoves(childHash, childBoard, nextPlayer, childPasses);
        MCTSNode child = new MCTSNode(childBoard, nextPlayer, node, move, childMoves);
        child.hash = childHash;
        child.consecutivePasses = childPasses;
        child.terminal = terminal;

        synchronized (node) {
            while (node.policyCache == null && node.forwardInFlight
                    && !shutdown && !Thread.currentThread().isInterrupted()) {
                try {
                    node.wait(100L);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return null;
                }
            }
            if (node.policyCache == null || shutdown || Thread.currentThread().isInterrupted()) {
                return null;
            }
            int moveIdx = policyIndex(move);
            double prior = moveIdx < node.policyCache.length
                    ? Math.max(node.policyCache[moveIdx], 1e-10) * PASS_INDEX
                    : 1.0;
            if (node.rootNoise != null) {
                Double noise = node.rootNoise.get(actionKey(move));
                if (noise != null) {
                    double eps = DIRICHLET_EPS * explorationScale;
                    prior = (1.0 - eps) * prior + eps * (noise * PASS_INDEX);
                }
            }
            child.prior = prior;
        }

        synchronized (node) {
            if (node.children == null) node.children = new ArrayList<>();
            node.children.add(child);
        }
        node.linkedMove = move;
        return child;
    }

    /** 每线程零分配缓冲：热路径（候选生成/打分）的棋群扫描、去重、BFS 全部复用。
     *  并行搜索多 worker 共享同一 AI 实例，故必须 ThreadLocal。 */
    private static final ThreadLocal<int[]> SCRATCH_CELLS_A = ThreadLocal.withInitial(() -> new int[BOARD_SIZE * BOARD_SIZE]);
    private static final ThreadLocal<int[]> SCRATCH_CELLS_B = ThreadLocal.withInitial(() -> new int[BOARD_SIZE * BOARD_SIZE]);
    private static final ThreadLocal<int[]> SCRATCH_STACK = ThreadLocal.withInitial(() -> new int[BOARD_SIZE * BOARD_SIZE]);
    private static final ThreadLocal<int[]> SCRATCH_CAPT = ThreadLocal.withInitial(() -> new int[BOARD_SIZE * BOARD_SIZE]);
    private static final ThreadLocal<int[]> SCRATCH_BFS_Q = ThreadLocal.withInitial(() -> new int[BOARD_SIZE * BOARD_SIZE]);
    private static final ThreadLocal<boolean[]> SCRATCH_VIS_GROUP = ThreadLocal.withInitial(() -> new boolean[BOARD_SIZE * BOARD_SIZE]);
    private static final ThreadLocal<boolean[]> SCRATCH_VIS_BFS = ThreadLocal.withInitial(() -> new boolean[BOARD_SIZE * BOARD_SIZE]);
    private static final ThreadLocal<boolean[]> SCRATCH_SEEN = ThreadLocal.withInitial(() -> new boolean[BOARD_SIZE * BOARD_SIZE]);

    /** 原语版 {@link #getGroup}：把 (sx,sy) 处 color 棋群的格点写入 cells，返回数量。
     *  要求 board[sx][sy]==color。输出为同一格点集合（getGroup 的集合语义与遍历顺序无关）。 */
    private int scanGroup(GoPlayer[][] board, int sx, int sy, GoPlayer color, int[] cells) {
        boolean[] visited = SCRATCH_VIS_GROUP.get();
        java.util.Arrays.fill(visited, false);
        int[] stack = SCRATCH_STACK.get();
        int top = 0, n = 0;
        int start = sx * BOARD_SIZE + sy;
        visited[start] = true;
        stack[top++] = start;
        cells[n++] = start;
        while (top > 0) {
            int p = stack[--top];
            int px = p / BOARD_SIZE, py = p % BOARD_SIZE;
            for (int[] dir : DIRS) {
                int nx = px + dir[0], ny = py + dir[1];
                if (nx >= 0 && nx < BOARD_SIZE && ny >= 0 && ny < BOARD_SIZE) {
                    int idx = nx * BOARD_SIZE + ny;
                    if (!visited[idx] && board[nx][ny] == color) {
                        visited[idx] = true;
                        stack[top++] = idx;
                        cells[n++] = idx;
                    }
                }
            }
        }
        return n;
    }

    /** cells 中的 n 个格点构成的棋群在当前盘面上是否有气。 */
    private boolean cellsHaveLiberty(GoPlayer[][] board, int[] cells, int n) {
        for (int i = 0; i < n; i++) {
            int p = cells[i];
            int px = p / BOARD_SIZE, py = p % BOARD_SIZE;
            for (int[] dir : DIRS) {
                int nx = px + dir[0], ny = py + dir[1];
                if (nx >= 0 && nx < BOARD_SIZE && ny >= 0 && ny < BOARD_SIZE
                        && board[nx][ny] == GoPlayer.NONE) return true;
            }
        }
        return false;
    }

    /** 原语版 {@link #countGroupLiberties}：cells 中 n 个格点棋群的不同空点邻数。 */
    private int countGroupLibertiesPrim(GoPlayer[][] board, int[] cells, int n) {
        boolean[] seen = SCRATCH_SEEN.get();
        java.util.Arrays.fill(seen, false);
        int count = 0;
        for (int i = 0; i < n; i++) {
            int p = cells[i];
            int px = p / BOARD_SIZE, py = p % BOARD_SIZE;
            for (int[] dir : DIRS) {
                int nx = px + dir[0], ny = py + dir[1];
                if (nx >= 0 && nx < BOARD_SIZE && ny >= 0 && ny < BOARD_SIZE
                        && board[nx][ny] == GoPlayer.NONE) {
                    int idx = nx * BOARD_SIZE + ny;
                    if (!seen[idx]) {
                        seen[idx] = true;
                        count++;
                    }
                }
            }
        }
        return count;
    }

    /** 原语版 {@link #countCaptures}：语义逐位一致（含"标记不移除"去重），
     *  仅把 HashSet 换成线程局部原语缓冲。契约同样要求调用时 (x,y) 为空。 */
    private int countCapturesPrim(GoPlayer[][] board, int x, int y, GoPlayer player) {
        board[x][y] = player;
        GoPlayer opponent = player == GoPlayer.BLACK ? GoPlayer.WHITE : GoPlayer.BLACK;
        int[] cells = SCRATCH_CELLS_B.get();
        boolean[] seen = SCRATCH_SEEN.get();
        java.util.Arrays.fill(seen, false);
        int captures = 0;
        for (int[] dir : DIRS) {
            int nx = x + dir[0], ny = y + dir[1];
            if (nx >= 0 && nx < BOARD_SIZE && ny >= 0 && ny < BOARD_SIZE
                    && board[nx][ny] == opponent) {
                int idx = nx * BOARD_SIZE + ny;
                if (seen[idx]) continue; // 该棋群已被计入
                int n = scanGroup(board, nx, ny, opponent, cells);
                if (!cellsHaveLiberty(board, cells, n)) {
                    captures += n;
                    for (int i = 0; i < n; i++) seen[cells[i]] = true;
                }
            }
        }
        board[x][y] = GoPlayer.NONE;
        return captures;
    }

    /** 原语版 {@link #evaluateMoveLiberties}：FIFO 出队顺序与 DIRS 扫描顺序逐位复刻，
     *  软上限 10 的过冲语义因此与旧实现一致。 */
    private int evaluateMoveLibertiesPrim(GoPlayer[][] board, int x, int y, GoPlayer player) {
        boolean[] visited = SCRATCH_VIS_BFS.get();
        java.util.Arrays.fill(visited, false);
        int[] queue = SCRATCH_BFS_Q.get();
        int head = 0, tail = 0, liberties = 0;
        int start = x * BOARD_SIZE + y;
        queue[tail++] = start;
        visited[start] = true;
        while (head < tail && liberties < 10) {
            int p = queue[head++];
            int px = p / BOARD_SIZE, py = p % BOARD_SIZE;
            for (int[] dir : DIRS) {
                int nx = px + dir[0], ny = py + dir[1];
                if (nx >= 0 && nx < BOARD_SIZE && ny >= 0 && ny < BOARD_SIZE) {
                    int idx = nx * BOARD_SIZE + ny;
                    if (!visited[idx]) {
                        visited[idx] = true;
                        if (board[nx][ny] == GoPlayer.NONE) {
                            liberties++;
                        } else if (board[nx][ny] == player) {
                            queue[tail++] = idx;
                        }
                    }
                }
            }
        }
        return liberties;
    }

    /**
     * 融合打分：合并剪枝判定和排序分，且复用候选模拟阶段已算出的自身棋群，
     * 省去 wouldBeInAtari 的冗余棋群遍历。输出与原 {@code evaluateMoveScore}
     * 逐位一致（行为快照门禁保证）：
     * <ul>
     *   <li>captures/oppCaptures 仍走 countCaptures 语义（其"标记不移除"去重
     *       与模拟阶段的即时移除在"提二甩一"等边角上可能不同，不能直接用
     *       提子数替代）；原语版 countCapturesPrim 与之逐位一致。</li>
     *   <li>打吃判定：精确气数==1 ⟺ wouldBeInAtari（同在落子态下计数，(x,y)
     *       自身不计入气）。</li>
     *   <li>气数：evaluateMoveLiberties 的"上限 10"是软上限——BFS 在轮询间隔检查
     *       liberties&lt;10，单个棋格的邻居扫描可一次过冲到 10–13，故精确气数
     *       ≥10 时必须调 BFS 复刻过冲语义，&lt;10 时精确气数逐位等于其返回值。</li>
     * </ul>
     *
     * @param cells     落子点所属棋群（含 (x,y)，落子态下计算）；提子复位不改变
     *                  该棋群的组成，可直接复用
     * @param selfCount cells 中的棋群格点数；&lt;0 表示尚未扫描，在落子态下现算
     * @return 走法分值（负值表示应剪枝跳过）
     */
    private int scoreFusedMove(GoPlayer[][] board, int x, int y, GoPlayer player,
                               int[] cells, int selfCount) {
        GoPlayer opponent = player == GoPlayer.BLACK ? GoPlayer.WHITE : GoPlayer.BLACK;

        // ── 一次性计算所有昂贵原语（棋群遍历仅一次）──
        int captures = countCapturesPrim(board, x, y, player);
        int oppCaptures = countCapturesPrim(board, x, y, opponent);
        board[x][y] = player;
        if (selfCount < 0) selfCount = scanGroup(board, x, y, player, cells);
        int groupLibs = countGroupLibertiesPrim(board, cells, selfCount);
        board[x][y] = GoPlayer.NONE;
        int libs = groupLibs < 10 ? groupLibs
                : evaluateMoveLibertiesPrim(board, x, y, player); // 复刻 BFS 软上限过冲
        int friendly = countFriendlyNeighbors(board, x, y, player);
        int posBonus = getPositionBonus(x, y);
        int edgeDist = Math.min(Math.min(x, y), Math.min(BOARD_SIZE - 1 - x, BOARD_SIZE - 1 - y));

        // 对手邻居数
        int oppNbrs = 0;
        for (int[] dir : DIRS) {
            int nx = x + dir[0], ny = y + dir[1];
            if (nx >= 0 && nx < BOARD_SIZE && ny >= 0 && ny < BOARD_SIZE && board[nx][ny] == opponent)
                oppNbrs++;
        }

        // ── 剪枝判定 ──
        int pruneScore = libs * 3 + friendly * 5 + captures * 20 + posBonus;
        if (edgeDist == 0) pruneScore -= 5;
        if (edgeDist == 0 && friendly == 0) pruneScore -= 20;
        if (friendly == 0 && oppNbrs >= 3) pruneScore -= 15;
        if (libs <= 1 && captures == 0) pruneScore -= 30;

        // 明显差的走法直接跳过（被剪掉的点不再付出排序分的开销）
        if (pruneScore < -10) return pruneScore;

        // ── 完整排序分 ──
        int score = captures * 50 - oppCaptures * 40 + libs * 10 + posBonus + friendly * 15;
        if (groupLibs == 1) score -= 30; // ⟺ wouldBeInAtari
        return score;
    }

    /**
     * 计算落子能吃的棋子数
     */
    private int countCaptures(GoPlayer[][] board, int x, int y, GoPlayer player) {
        board[x][y] = player;
        GoPlayer opponent = player == GoPlayer.BLACK ? GoPlayer.WHITE : GoPlayer.BLACK;
        int captures = 0;
        // 用已计数集合去重：同一对手棋群若环绕 (x,y) 从两个方向相邻，只计一次
        Set<Long> counted = new HashSet<>();
        for (int[] dir : DIRS) {
            int nx = x + dir[0], ny = y + dir[1];
            if (nx >= 0 && nx < BOARD_SIZE && ny >= 0 && ny < BOARD_SIZE && board[nx][ny] == opponent) {
                long key = (long) nx * BOARD_SIZE + ny;
                if (counted.contains(key)) continue; // 该棋群已被计入
                Set<int[]> group = getGroup(board, nx, ny);
                if (!hasLiberty(board, group)) {
                    captures += group.size();
                    for (int[] pos : group) counted.add((long) pos[0] * BOARD_SIZE + pos[1]);
                }
            }
        }
        board[x][y] = GoPlayer.NONE;
        return captures;
    }

    /**
     * 评估走子的气数
     */
    private int evaluateMoveLiberties(GoPlayer[][] board, int x, int y, GoPlayer player) {
        int liberties = 0;
        boolean[][] visited = new boolean[BOARD_SIZE][BOARD_SIZE];
        Queue<int[]> queue = new LinkedList<>();
        queue.offer(new int[]{x, y});
        visited[x][y] = true;

        while (!queue.isEmpty() && liberties < 10) {
            int[] pos = queue.poll();
            for (int[] dir : DIRS) {
                int nx = pos[0] + dir[0], ny = pos[1] + dir[1];
                if (nx >= 0 && nx < BOARD_SIZE && ny >= 0 && ny < BOARD_SIZE && !visited[nx][ny]) {
                    visited[nx][ny] = true;
                    if (board[nx][ny] == GoPlayer.NONE) {
                        liberties++;
                    } else if (board[nx][ny] == player) {
                        queue.offer(new int[]{nx, ny});
                    }
                }
            }
        }
        return liberties;
    }

    /**
     * 落子后是否会被打吃
     */
    private boolean wouldBeInAtari(GoPlayer[][] board, int x, int y, GoPlayer player) {
        board[x][y] = player;
        Set<int[]> group = getGroup(board, x, y);
        boolean inAtari = countGroupLiberties(board, group) == 1;
        board[x][y] = GoPlayer.NONE;
        return inAtari;
    }

    /**
     * 统计相邻己方棋子数
     */
    private int countFriendlyNeighbors(GoPlayer[][] board, int x, int y, GoPlayer player) {
        int count = 0;
        for (int[] dir : DIRS) {
            int nx = x + dir[0], ny = y + dir[1];
            if (nx >= 0 && nx < BOARD_SIZE && ny >= 0 && ny < BOARD_SIZE && board[nx][ny] == player) {
                count++;
            }
        }
        return count;
    }

    /**
     * 位置加成（优先中心、避开边角）
     */
    private int getPositionBonus(int x, int y) {
        int center = BOARD_SIZE / 2;
        int distToCenter = Math.max(Math.abs(x - center), Math.abs(y - center));
        int distToEdge = Math.min(Math.min(x, y), Math.min(BOARD_SIZE - 1 - x, BOARD_SIZE - 1 - y));

        int bonus = 0;
        if (distToCenter <= 2) bonus += 8;      // 中心区域
        else if (distToCenter <= 4) bonus += 4; // 中腹
        if (distToEdge <= 1) bonus -= 5;        // 边角惩罚
        return bonus;
    }

    // ══════════════════════════════════════════════════════════════════
    //  MCTS 选择与模拟
    // ══════════════════════════════════════════════════════════════════

    private static final double UCB_C = 1.414;
    /** FPU（First Play Urgency）：未访问子节点的默认价值，0 表示假设均势 */
    private static final double FPU_VALUE = 0.0;

    /** 共享有界线程池；跨 AI 复用，同时限制异常高并发下的系统线程峰值。 */
    private static final ExecutorService SHARED_POOL = Executors.newFixedThreadPool(
            Math.max(2, PARALLEL_THREADS),
            r -> { Thread t = new Thread(r, "mcts-worker"); t.setDaemon(true); return t; });

    /**
     * 释放本 AI 持有的 native 资源（OpenCL 后端）。
     * 修复：GoAI.shutdown 原为 default 空实现且本类未覆写，GoGame.close() 对
     * MCTS 路径是 no-op，GPU 句柄在屏显重开时反复堆积。SHARED_POOL 是跨实例
     * 共享的静态池，此处不关闭。
     */
    @Override
    public void shutdown() {
        shutdown = true;
        searchGeneration.incrementAndGet();
        if (!lifecycleLock.tryLock()) return;
        try {
            releaseEvaluatorLocked();
        } finally {
            lifecycleLock.unlock();
        }
    }

    private void releaseEvaluatorLocked() {
        if (evaluatorReleased) return;
        evaluatorReleased = true;
        neuralEvaluator.release();
    }

    private MCTSNode selectNode(MCTSNode node) {
        while (true) {
            synchronized (node) {
                if (!node.untriedMoves.isEmpty()
                        || node.children == null || node.children.isEmpty()) {
                    return node;
                }
            }
            MCTSNode child = selectBestChild(node);
            if (child == null) return node;
            node = child;
        }
    }

    private MCTSNode selectBestChild(MCTSNode parent) {
        MCTSNode best = null;
        double bestValue = Double.NEGATIVE_INFINITY;
        double parentVisits;

        // 快照 children 避免并发修改异常（expand 在加锁状态下添加子节点）
        List<MCTSNode> children;
        synchronized (parent) {
            if (parent.children == null || parent.children.isEmpty()) return null;
            parentVisits = parent.visits;
            children = new ArrayList<>(parent.children);
        }
        double sqrtParentVisits = Math.sqrt(Math.max(parentVisits, 1));

        for (MCTSNode child : children) {
            double visits;
            double totalScore;
            synchronized (child) {
                visits = child.visits;
                totalScore = child.totalScore;
            }
            // 子节点是"对手行棋方"视角，父节点视角需取反（Q 项为 -child.Q）
            double winRate;
            if (visits == 0) {
                // FPU：未访问子节点用 FPU_VALUE（默认 0 表示均势），替代强制展开。
                // 避免宽局面（100+ 合法走法）下前 100 次迭代全花在"点一遍每个点"，
                // 已访问高胜率走法可被立即重访，搜索深度显著提升。
                winRate = FPU_VALUE;
            } else {
                winRate = -totalScore / visits;
            }
            // PUCT（标准 AlphaGo Zero 形式）: Q + c_puct * P(s,a) * sqrt(N_parent) / (1 + N_child)
            // ★ 修正：原版探索项用 √log(N)，随搜索增长过慢，先验高但少访问的
            //   走法迟迟得不到试探；标准式随 √N 增长，prior 引导的探索更充分
            double ucb = winRate
                    + UCB_C * child.prior * sqrtParentVisits / (1.0 + visits);

            if (ucb > bestValue) {
                bestValue = ucb;
                best = child;
            }
        }
        return best;
    }

    /**
     * 纯神经网络模拟评估（缓存感知）。
     * <p>
     * 若节点已有 valueCache（来自 expand 的同一次前向），直接返回；
     * 否则（首次选中且 untried 为空时）做一次完整前向，同时缓存策略+价值。
     */
    private double simulate(MCTSNode node) {
        if (node.terminal) return terminalScore(node);
        if (node.valueCached) return node.valueCache;
        // 首次遇此节点：一次前向同时拿到策略+价值，避免后续重复前向
        NeuralEvaluator.ForwardResult fr = neuralEvaluator.forward(
                neuralEvaluator.buildInputPlanes(node.board, node.player, node.move),
                neuralEvaluator.extractAuxFeatures(node.board, node.player));
        node.valueCache = fr.value;
        node.valueCached = true;
        if (node.policyCache == null) {
            node.policyCache = fr.policy;
        }
        return fr.value;
    }

    private double terminalScore(MCTSNode node) {
        int[] area = GoGame.calcTerritory(node.board);
        double blackMargin = area[0] - (area[1] + searchKomi);
        double perspectiveMargin = node.player == GoPlayer.BLACK ? blackMargin : -blackMargin;
        return Math.max(-1.0, Math.min(1.0, perspectiveMargin / 100.0));
    }

    private void backpropagate(MCTSNode node, double score) {
        // score 是 node（叶子）行棋方视角的价值。
        // 每往上一层，行棋方交替，价值取反 —— 这样每个节点存的是"该节点行棋方视角"。
        // （价值头输出为当前行棋方视角，见 valueTarget = (s.player==BLACK ? margin : -margin)）
        // ★ Bug修复：神经网络在极端输入下可能输出 NaN/Infinity（如全 0 plane、batch
        //   越界等），一旦回传将沿 parent 链把所有 totalScore 污染为 NaN，导致
        //   shouldTerminateEarly 与 winrate 计算全部失效 → 中盘胜率坍塌。
        //   这里加一道 finite 守卫：若 score 非有限值则跳过累加，但 visits 仍 +1，
        //   避免一个坏叶子把整棵树打分全部"毒化"。
        if (!Double.isFinite(score)) {
            while (node != null) {
                synchronized (node) { node.visits++; }
                node = node.parent;
            }
            return;
        }
        while (node != null) {
            synchronized (node) {
                node.visits++;
                node.totalScore += score;
            }
            score = -score;
            node = node.parent;
        }
    }

    /**
     * 尝试重用上一回合的搜索树。
     * <p>
     * 找到匹配的子节点后，先对该子树执行深拷贝（cloneNodeTree），再递归更新棋盘。
     * 使用 deepCopyNode 递归复制子树，避免破坏兄弟节点间的棋盘独立性。
     *
     * @param board        当前棋盘
     * @param currentPlayer 当前玩家
     * @return 可复用的子树根节点，若无匹配则返回 null
     */
    private MCTSNode tryReuseTree(GoPlayer[][] board, GoPlayer currentPlayer) {
        if (lastRoot == null || lastMove == null || lastRoot.children == null) return null;

        for (MCTSNode child : lastRoot.children) {
            if (child.move != null && child.move[0] == lastMove[0] && child.move[1] == lastMove[1]) {
                // 校验棋盘一致：重用的子节点必须是"当前局面恰好是上一步之后"。
                // 自对弈（AI 每步都走）时匹配；对抗/人机对局中对手插了一手，
                // 子节点棋盘与当前棋盘不同，跳过重用避免旧位置子树污染搜索。
                if (child.player != currentPlayer || !boardsEqual(child.board, board)) continue;
                // 使用 deepCopyNode 递归复制子树，避免破坏兄弟节点的棋盘引用
                MCTSNode newRoot = deepCopyNode(child, board);
                newRoot.parent = null;
                return newRoot;
            }
        }
        return null;
    }

    /**
     * 递归深拷贝节点及其子树，并用新棋盘状态替换根节点的棋盘
     */
    private MCTSNode deepCopyNode(MCTSNode node, GoPlayer[][] newBoard) {
        GoPlayer[][] boardCopy = deepCopyBoard(newBoard);
        MCTSNode copy = new MCTSNode(
                boardCopy,
                node.player,
                null, // parent 稍后设置
                node.move,
                node.untriedMoves // 复用未展开走法（棋盘已按走法重建，合法走法集合一致）
        );
        copy.visits = node.visits;
        copy.totalScore = node.totalScore;
        copy.linkedMove = node.linkedMove;
        // prior belongs to the incoming parent edge. A node's own policyCache describes
        // its outgoing edges and cannot reconstruct this value during tree reuse.
        copy.prior = node.prior;
        copy.policyCache = node.policyCache; // 只读共享，线程安全（行为复用）
        copy.valueCache = node.valueCache;
        copy.valueCached = node.valueCached;
        // 复制局面哈希（deepCopyNode 递归应用走法重建棋盘，哈希需从子节点棋盘重新计算）
        copy.hash = GoGame.boardHash(boardCopy);
        copy.consecutivePasses = node.consecutivePasses;
        copy.terminal = node.terminal;

        if (node.children != null) {
            copy.children = new ArrayList<>();
            for (MCTSNode child : node.children) {
                // 为每个子节点创建正确的棋盘：在父棋盘基础上应用子走法
                GoPlayer[][] childBoard = deepCopyBoard(boardCopy);
                if (child.move != null && !isPass(child.move)) {
                    simulatePlaceStone(childBoard, child.move[0], child.move[1], node.player);
                }
                MCTSNode childCopy = deepCopyNode(child, childBoard);
                childCopy.parent = copy;
                copy.children.add(childCopy);
            }
        }

        return copy;
    }

    private int[] getBestMCTSMove(MCTSNode root) {
        List<MCTSNode> children;
        synchronized (root) {
            if (root.children == null || root.children.isEmpty()) return null;
            children = new ArrayList<>(root.children);
        }

        // AlphaZero 标准终局选着：按访问数最大。
        // 访问数对评估噪声更鲁棒：胜率均值在低访问分支方差大，
        // 原版按胜率选会偶尔选进"2 连胜假信号"的冷门分支
        MCTSNode best = null;
        double bestVisits = -1;

        for (MCTSNode child : children) {
            double visits;
            synchronized (child) {
                visits = child.visits;
            }
            if (visits > bestVisits) {
                bestVisits = visits;
                best = child;
            }
        }
        return best != null ? best.move : null;
    }

    /**
     * 自对弈模式走法选择：按访问分布采样（AlphaZero 风格温度控制）。
     * 开局 temp=1.0（按比例采样，探索充分），30 手后 temp=0.1（趋近贪心收敛）。
     * 与存盘的策略目标（访问分布）保持一致，保证训练样本分布合理。
     */
    private int[] sampleMCTSMove(MCTSNode root, int moveCount) {
        List<MCTSNode> children;
        synchronized (root) {
            if (root.children == null || root.children.isEmpty()) return null;
            children = new ArrayList<>(root.children);
        }
        // 温度随探索强度衰减（早期高探索→高温，后期低探索→低温更贪心）
        // ★ 修正：默认探索强度(1.0)下开局温度应为 1.0（与上方注释一致）。
        //   原公式 1.5*x+0.1 在默认档得 1.6，开局采样过散，策略训练目标噪声过大
        double earlyTemp = 0.9 * explorationScale + 0.1;
        double lateTemp = 0.3 * explorationScale + 0.05;
        double temp = moveCount < 30 ? earlyTemp : lateTemp;

        List<MCTSNode> candidates = new ArrayList<>();
        List<Double> weights = new ArrayList<>();
        double sum = 0;
        for (MCTSNode child : children) {
            double visits;
            synchronized (child) {
                visits = child.visits;
            }
            if (visits > 0) {
                double w = (temp <= 0.01) ? visits : Math.pow(visits, 1.0 / temp);
                weights.add(w);
                candidates.add(child);
                sum += w;
            }
        }
        if (candidates.isEmpty() || sum <= 0) return getBestMCTSMove(root);

        double r = random.nextDouble() * sum;
        double cum = 0;
        for (int i = 0; i < candidates.size(); i++) {
            cum += weights.get(i);
            if (cum >= r) return candidates.get(i).move;
        }
        return candidates.get(candidates.size() - 1).move;
    }

    private boolean movesEqual(int[] a, int[] b) {
        return a != null && b != null && a[0] == b[0] && a[1] == b[1];
    }

    private static boolean isPass(int[] move) {
        return move != null && move.length >= 2 && move[0] < 0 && move[1] < 0;
    }

    private static int policyIndex(int[] move) {
        if (isPass(move)) return PASS_INDEX;
        if (move == null || move.length < 2 || move[0] < 0 || move[0] >= BOARD_SIZE
                || move[1] < 0 || move[1] >= BOARD_SIZE) {
            throw new IllegalArgumentException("Invalid MCTS action");
        }
        return move[0] * BOARD_SIZE + move[1];
    }

    private static String actionKey(int[] move) {
        return isPass(move) ? "pass" : move[0] + "," + move[1];
    }

    private static GoPlayer opposite(GoPlayer player) {
        return player == GoPlayer.BLACK ? GoPlayer.WHITE : GoPlayer.BLACK;
    }

    // ══════════════════════════════════════════════════════════════════
    //  战术阅读器（深度优先 α-β 搜索）
    // ══════════════════════════════════════════════════════════════════

    // ══════════════════════════════════════════════════════════════════
    //  局部战术搜索（受限 α-β + 神经网络叶子评估）
    // ══════════════════════════════════════════════════════════════════

    /** 局部搜索最大深度 */
    private static final int TACTICAL_DEPTH = 5;

    /**
     * 战术阅读：对对手危险棋群做受限 α-β 深度搜索，用神经网络评估叶子。
     * 气数≤2 的棋群用深度 5 搜索，气数=3 用深度 3。
     * 搜索范围限定在目标棋群周围 2 格内，避免全盘扫描。
     *
     * @param deadline 时间预算截止时间戳，超时立即返回
     * @return 必胜走法 {x,y}，找不到则返回 null
     */
    private int[] tacticalReading(GoPlayer[][] board, GoPlayer player, long deadline) {
        GoPlayer opponent = player == GoPlayer.BLACK ? GoPlayer.WHITE : GoPlayer.BLACK;
        // ★ Bug修复（中盘算炸防御）：原版对每个 (x,y) 都重新取棋群并搜索，
        //   中盘 200+ 步时濒死棋群密集，多个同色连体子被反复扫到，导致
        //   O(361 × α-β深度5) 的组合爆炸 → 内存/时间耗尽。
        //   1) 棋群去重：Set<int[]> 没有原生 hash，把 group 序列化为 "x,y" 串后
        //      放入 visited 集合，已访问过的棋群整体跳过。
        //   2) 候选点过载保护：collectLocalRegion 返回超过 30 个候选时直接跳过，
        //      避免在松散棋形上启动深度 5 α-β。
        Set<String> visited = new HashSet<>();

        for (int x = 0; x < BOARD_SIZE; x++) {
            for (int y = 0; y < BOARD_SIZE; y++) {
                if (board[x][y] != opponent) continue;
                Set<int[]> group = getGroup(board, x, y);
                int libs = countGroupLiberties(board, group);
                if (libs <= 3 && group.size() >= 2) {
                    // 棋群去重（用最小 x,y 作为代表 key；group 自身按 x 升序排）
                    String key = groupKey(group);
                    if (visited.contains(key)) continue;
                    visited.add(key);
                    // 收集局部区域
                    Set<String> region = collectLocalRegion(board, group);
                    if (region.isEmpty()) continue;
                    if (region.size() > 30) continue; // 候选过载，跳过
                    int[] best = localAlphaBetaSearch(board, group, player, opponent,
                            libs <= 2 ? TACTICAL_DEPTH : 3, region, deadline);
                    if (best != null) return best;
                }
            }
        }
        return null;
    }

    /** 把棋群序列化为唯一字符串 key，用于 visited 集合去重。
     *  使用相对坐标（锚点 = 棋群最左上的子）+ 字典序排序，
     *  使同形状但位置不同的棋群产生不同 key，避免被错误地合并；
     *  同位置同形状的棋群（递归扫描时的重复）产生相同 key，被正确去重。 */
    private static String groupKey(Set<int[]> group) {
        int minX = Integer.MAX_VALUE, minY = Integer.MAX_VALUE;
        for (int[] p : group) {
            if (p[0] < minX || (p[0] == minX && p[1] < minY)) {
                minX = p[0]; minY = p[1];
            }
        }
        StringBuilder sb = new StringBuilder();
        List<int[]> sorted = new ArrayList<>(group);
        sorted.sort((a, b) -> {
            int dx = a[0] - b[0], dy = a[1] - b[1];
            return dx != 0 ? dx : dy;
        });
        for (int[] p : sorted) {
            if (sb.length() > 0) sb.append(';');
            sb.append(p[0] - minX).append(',').append(p[1] - minY);
        }
        return sb.toString();
    }

    /**
     * 收集目标棋群周围 2 格内的所有空点（局部搜索区域）。
     */
    private Set<String> collectLocalRegion(GoPlayer[][] board, Set<int[]> group) {
        Set<String> region = new HashSet<>();
        for (int[] pos : group) {
            for (int[] dir : DIRS) {
                int nx = pos[0] + dir[0], ny = pos[1] + dir[1];
                if (nx >= 0 && nx < BOARD_SIZE && ny >= 0 && ny < BOARD_SIZE
                        && board[nx][ny] == GoPlayer.NONE) {
                    region.add(nx + "," + ny);
                    // 扩展一圈到 2 格半径
                    for (int[] d2 : DIRS) {
                        int nx2 = nx + d2[0], ny2 = ny + d2[1];
                        if (nx2 >= 0 && nx2 < BOARD_SIZE && ny2 >= 0 && ny2 < BOARD_SIZE
                                && board[nx2][ny2] == GoPlayer.NONE)
                            region.add(nx2 + "," + ny2);
                    }
                }
            }
        }
        return region;
    }

    /**
     * 对目标棋群做局部 α-β 搜索，返回最佳杀棋走法。
     * 只有找到明确优势（评估值 > 0.3）的走法才返回。
     */
    private int[] localAlphaBetaSearch(GoPlayer[][] board, Set<int[]> target,
                                        GoPlayer attacker, GoPlayer defender,
                                        int maxDepth, Set<String> region, long deadline) {
        // 候选点排序（吃子优先）
        List<int[]> candidates = new ArrayList<>();
        for (String s : region) {
            // ★ Bug修复：region 里的字符串可能为 "x,"(缺 y)或 ","(空),split 后
            //   p.length<2 会抛 AIOOBE;NumberFormatException 也可能。
            //   防御性跳过,AI 线程不应因此崩
            try {
                String[] p = s.split(",");
                if (p.length != 2) continue;
                int x = Integer.parseInt(p[0]);
                int y = Integer.parseInt(p[1]);
                if (!isLegalMove(board, x, y, attacker)) continue;
                int priority = countCaptures(board, x, y, attacker) * 20
                             + countFriendlyNeighbors(board, x, y, attacker) * 5;
                candidates.add(new int[]{x, y, priority});
            } catch (NumberFormatException nfe) {
                // 畸形坐标字符串,跳过
            }
        }
        candidates.sort((a, b) -> b[2] - a[2]);

        int[] bestMove = null;
        double bestScore = Double.NEGATIVE_INFINITY;

        for (int[] move : candidates) {
            if (System.currentTimeMillis() > deadline) break;
            GoPlayer[][] next = deepCopyBoard(board);
            if (!simulatePlaceStone(next, move[0], move[1], attacker)) continue;

            double score = -localAlphaBeta(next, target, defender, attacker, maxDepth - 1,
                    Double.NEGATIVE_INFINITY, -bestScore, region, deadline);

            if (score > bestScore) {
                bestScore = score;
                bestMove = new int[]{move[0], move[1]};
            }
            // 必胜走法，提前停止
            if (score > 0.8) break;
        }

        return bestScore > 0.3 ? bestMove : null;
    }

    /**
     * 递归 α-β 搜索（限深、限局部区域）。
     * 叶子节点用神经网络价值头评估。
     * 通过 negamax 负号翻转处理交替行棋方。
     */
    private double localAlphaBeta(GoPlayer[][] board, Set<int[]> target,
                                   GoPlayer player, GoPlayer attacker, int depth,
                                   double alpha, double beta, Set<String> region, long deadline) {
        if (System.currentTimeMillis() > deadline) return 0;

        // 终局：目标棋群被完全提掉
        if (targetIsCaptured(board, target)) {
            // negamax 约定：返回当前行棋方视角。
            // 攻击方（player==attacker）成功提掉目标 → +1.0；防守方（player!=attacker）→ -1.0
            return player == attacker ? 1.0 : -1.0;
        }

        // 达到深度或目标活了（多气+安全）：神经网络评估
        if (depth <= 0) {
            return neuralEvaluator.forwardValue(board, player, null);
        }

        // 生成局部合法走法
        List<int[]> moves = legalMovesInRegion(board, player, region);
        if (moves.isEmpty()) return 0;

        // 按吃子数排序提升剪枝效率
        GoPlayer opponent = player == GoPlayer.BLACK ? GoPlayer.WHITE : GoPlayer.BLACK;
        moves.sort((a, b) -> {
            int ca = countCaptures(board, a[0], a[1], player);
            int cb = countCaptures(board, b[0], b[1], player);
            return cb - ca;
        });

        for (int[] move : moves) {
            GoPlayer[][] next = deepCopyBoard(board);
            if (!simulatePlaceStone(next, move[0], move[1], player)) continue;
            double v = -localAlphaBeta(next, target, opponent, attacker, depth - 1, -beta, -alpha, region, deadline);
            if (v > alpha) alpha = v;
            if (alpha >= beta) break;
        }
        return alpha;
    }

    /** 检查目标棋群是否已被完全提掉 */
    private boolean targetIsCaptured(GoPlayer[][] board, Set<int[]> target) {
        for (int[] pos : target) {
            if (board[pos[0]][pos[1]] != GoPlayer.NONE) return false;
        }
        return true;
    }

    /** 生成局部区域内的合法走法 */
    private List<int[]> legalMovesInRegion(GoPlayer[][] board, GoPlayer player, Set<String> region) {
        List<int[]> moves = new ArrayList<>();
        for (String s : region) {
            try {
                String[] p = s.split(",");
                if (p.length != 2) continue;
                int x = Integer.parseInt(p[0]), y = Integer.parseInt(p[1]);
                // isLegalMove temporarily writes the candidate square. Calling it on
                // an occupied point would overwrite the board and then reset it.
                if (x < 0 || x >= BOARD_SIZE || y < 0 || y >= BOARD_SIZE
                        || board[x][y] != GoPlayer.NONE) continue;
                if (isLegalMove(board, x, y, player)) moves.add(new int[]{x, y});
            } catch (NumberFormatException ignored) {
                // Ignore malformed tactical-region coordinates.
            }
        }
        return moves;
    }

    // ══════════════════════════════════════════════════════════════════
    //  杀棋检测
    // ══════════════════════════════════════════════════════════════════

    /**
     * 查找杀棋走法（围棋特有战术检测）
     */
    private int[] findKillerMove(GoPlayer[][] board, GoPlayer player, List<int[]> validMoves) {
        // 1. 提大龙：落子能提掉对手3+子的大龙
        int[] captureMove = findBigCapture(board, player, validMoves);
        if (captureMove != null) return captureMove;

        // 2. 救己方大龙：防己方被打吃
        int[] saveMove = findSaveOwnGroup(board, player, validMoves);
        if (saveMove != null) return saveMove;

        // 3. 征子检测：追捕逃子
        int[] ladderMove = findLadderCapture(board, player, validMoves);
        if (ladderMove != null) return ladderMove;

        // 4. 劫材价值：落子后成为劫材
        int[] koMove = findKoThreat(board, player, validMoves);
        if (koMove != null) return koMove;

        // 5. 防守对手征子
        int[] defendLadder = findDefendLadder(board, player, validMoves);
        if (defendLadder != null) return defendLadder;

        // 6. 杀对手大龙（气数<=2的对手棋群）
        int[] killMove = findKillMove(board, player, validMoves);
        if (killMove != null) return killMove;

        // 7. 防守打吃
        for (int[] move : validMoves) {
            if (wouldPreventAtari(board, move[0], move[1], player)) {
                return move;
            }
        }

        return null;
    }

    /**
     * 找能提大龙的走法（至少提3子）
     */
    private int[] findBigCapture(GoPlayer[][] board, GoPlayer player, List<int[]> validMoves) {
        int[] best = null;
        int bestSize = 0;
        for (int[] move : validMoves) {
            int captures = countCaptures(board, move[0], move[1], player);
            if (captures > bestSize) {
                bestSize = captures;
                best = move;
            }
        }
        return bestSize >= 3 ? best : null;
    }

    /**
     * 救己方被打吃的棋群（找其气点落子）
     */
    private int[] findSaveOwnGroup(GoPlayer[][] board, GoPlayer player, List<int[]> validMoves) {
        for (int x = 0; x < BOARD_SIZE; x++) {
            for (int y = 0; y < BOARD_SIZE; y++) {
                if (board[x][y] == player) {
                    Set<int[]> group = getGroup(board, x, y);
                    if (countGroupLiberties(board, group) == 1) {
                        for (int[] pos : group) {
                            for (int[] dir : DIRS) {
                                int nx = pos[0] + dir[0], ny = pos[1] + dir[1];
                                if (isValid(validMoves, nx, ny)) {
                                    return new int[]{nx, ny};
                                }
                            }
                        }
                    }
                }
            }
        }
        return null;
    }

    /**
     * 征子追击：在对手逃路上落子
     */
    private int[] findLadderCapture(GoPlayer[][] board, GoPlayer player, List<int[]> validMoves) {
        GoPlayer opponent = player == GoPlayer.BLACK ? GoPlayer.WHITE : GoPlayer.BLACK;
        for (int x = 0; x < BOARD_SIZE; x++) {
            for (int y = 0; y < BOARD_SIZE; y++) {
                if (board[x][y] == opponent) {
                    Set<int[]> group = getGroup(board, x, y);
                    if (countGroupLiberties(board, group) == 1) {
                        int[] escape = getEscapeDirection(board, group, opponent);
                        if (escape != null && isValid(validMoves, escape[0], escape[1])) return escape;
                    }
                }
            }
        }
        return null;
    }

    /**
     * 找棋群最靠近边角的逃生点
     */
    private int[] getEscapeDirection(GoPlayer[][] board, Set<int[]> group, GoPlayer player) {
        Set<String> liberties = new HashSet<>();
        for (int[] pos : group) {
            for (int[] dir : DIRS) {
                int nx = pos[0] + dir[0], ny = pos[1] + dir[1];
                if (nx >= 0 && nx < BOARD_SIZE && ny >= 0 && ny < BOARD_SIZE && board[nx][ny] == GoPlayer.NONE) {
                    liberties.add(nx + "," + ny);
                }
            }
        }
        if (liberties.isEmpty()) return null;
        int[] best = null;
        int bestScore = Integer.MAX_VALUE;
        for (String lib : liberties) {
            String[] parts = lib.split(",");
            int lx = Integer.parseInt(parts[0]);
            int ly = Integer.parseInt(parts[1]);
            int score = Math.min(Math.min(lx, ly), Math.min(BOARD_SIZE - 1 - lx, BOARD_SIZE - 1 - ly));
            if (score < bestScore) {
                bestScore = score;
                best = new int[]{lx, ly};
            }
        }
        return best;
    }

    /**
     * 找能成为劫材的走法：在对手紧气点附近落子
     */
    private int[] findKoThreat(GoPlayer[][] board, GoPlayer player, List<int[]> validMoves) {
        GoPlayer opponent = player == GoPlayer.BLACK ? GoPlayer.WHITE : GoPlayer.BLACK;
        for (int x = 0; x < BOARD_SIZE; x++) {
            for (int y = 0; y < BOARD_SIZE; y++) {
                if (board[x][y] == opponent) {
                    Set<int[]> group = getGroup(board, x, y);
                    if (countGroupLiberties(board, group) == 1) {
                        for (int[] pos : group) {
                            for (int[] dir : DIRS) {
                                int nx = pos[0] + dir[0], ny = pos[1] + dir[1];
                                if (isValid(validMoves, nx, ny) && !wouldBeInAtari(board, nx, ny, player)) {
                                    return new int[]{nx, ny};
                                }
                            }
                        }
                    }
                }
            }
        }
        return null;
    }

    /**
     * 防守对手征子：己方棋群被打吃时找逃生方向
     */
    private int[] findDefendLadder(GoPlayer[][] board, GoPlayer player, List<int[]> validMoves) {
        for (int x = 0; x < BOARD_SIZE; x++) {
            for (int y = 0; y < BOARD_SIZE; y++) {
                if (board[x][y] == player) {
                    Set<int[]> group = getGroup(board, x, y);
                    if (countGroupLiberties(board, group) == 1) {
                        int[] escape = getEscapeDirection(board, group, player);
                        if (escape != null && isValid(validMoves, escape[0], escape[1])) return escape;
                    }
                }
            }
        }
        return null;
    }

    /**
     * 杀气数<=2的对手大龙
     */
    private int[] findKillMove(GoPlayer[][] board, GoPlayer player, List<int[]> validMoves) {
        GoPlayer opponent = player == GoPlayer.BLACK ? GoPlayer.WHITE : GoPlayer.BLACK;
        for (int x = 0; x < BOARD_SIZE; x++) {
            for (int y = 0; y < BOARD_SIZE; y++) {
                if (board[x][y] == opponent) {
                    Set<int[]> group = getGroup(board, x, y);
                    if (group.size() >= 3 && countGroupLiberties(board, group) <= 2) {
                        for (int[] pos : group) {
                            for (int[] dir : DIRS) {
                                int nx = pos[0] + dir[0], ny = pos[1] + dir[1];
                                if (isValid(validMoves, nx, ny)) {
                                    return new int[]{nx, ny};
                                }
                            }
                        }
                    }
                }
            }
        }
        return null;
    }

    /**
     * 防止己方被打吃
     */
    private boolean wouldPreventAtari(GoPlayer[][] board, int x, int y, GoPlayer player) {
        // 检查周围己方棋群
        for (int[] dir : DIRS) {
            int nx = x + dir[0], ny = y + dir[1];
            if (nx >= 0 && nx < BOARD_SIZE && ny >= 0 && ny < BOARD_SIZE && board[nx][ny] == player) {
                Set<int[]> group = getGroup(board, nx, ny);
                if (countGroupLiberties(board, group) == 1) {
                    // 这个走法能救活己方被打吃的棋
                    return true;
                }
            }
        }
        return false;
    }

    // ══════════════════════════════════════════════════════════════════
    //  终局策略
    // ══════════════════════════════════════════════════════════════════

    /**
     * 终局走法（收官）
     */
    private int[] getEndgameMove(GoPlayer[][] board, GoPlayer player, List<int[]> validMoves) {
        int[] bestMove = null;
        double bestScore = Double.NEGATIVE_INFINITY;

        for (int[] move : validMoves) {
            double score = evaluateEndgameMove(board, player, move);
            if (score > bestScore) {
                bestScore = score;
                bestMove = move;
            }
        }
        return bestMove;
    }

    /**
     * 终局走法评估
     */
    private double evaluateEndgameMove(GoPlayer[][] board, GoPlayer player, int[] move) {
        double score = 0;
        int x = move[0], y = move[1];

        // 1. 围空评估
        score += evaluateTerritoryGain(board, player, x, y) * 2.0;

        // 2. 自身安全
        board[x][y] = player;
        Set<int[]> group = getGroup(board, x, y);
        int libs = countGroupLiberties(board, group);
        score += libs * 0.5;
        board[x][y] = GoPlayer.NONE;

        // 3. 阻止对手围空
        GoPlayer opponent = player == GoPlayer.BLACK ? GoPlayer.WHITE : GoPlayer.BLACK;
        score -= evaluateTerritoryGain(board, opponent, x, y) * 1.5;

        // 4. 位置价值
        score += getPositionBonus(x, y) * 0.3;

        return score;
    }

    /**
     * 评估落子后围空增益
     */
    private double evaluateTerritoryGain(GoPlayer[][] board, GoPlayer player, int x, int y) {
        double gain = 0;
        int radius = 3;

        for (int dx = -radius; dx <= radius; dx++) {
            for (int dy = -radius; dy <= radius; dy++) {
                int nx = x + dx, ny = y + dy;
                if (nx >= 0 && nx < BOARD_SIZE && ny >= 0 && ny < BOARD_SIZE) {
                    if (board[nx][ny] == GoPlayer.NONE) {
                        // 检查这个空点被谁控制
                        if (isNearPlayer(board, nx, ny, player)) {
                            gain += 1.0 / (Math.abs(dx) + Math.abs(dy) + 1);
                        }
                    }
                }
            }
        }
        return gain;
    }

    private boolean isNearPlayer(GoPlayer[][] board, int x, int y, GoPlayer player) {
        for (int[] dir : DIRS) {
            int nx = x + dir[0], ny = y + dir[1];
            if (nx >= 0 && nx < BOARD_SIZE && ny >= 0 && ny < BOARD_SIZE && board[nx][ny] == player) {
                return true;
            }
        }
        return false;
    }

    // ══════════════════════════════════════════════════════════════════
    //  开局定式库（覆盖前30手）
    // ══════════════════════════════════════════════════════════════════

    /**
     * 围棋开局定式库。
     * 覆盖：星位、三三、小目、高目、目外等多种开局变化。
     * 格式：moveHistorySize -> [x, y] 或 null（继续 MCTS 搜索）
     */
    private int[] getOpeningBookMove(GoPlayer[][] board, GoPlayer player, List<int[]> validMoves, int moveCount) {
        if (moveCount > 30) return null;  // 开局库覆盖前30手

        int center = BOARD_SIZE / 2;
        GoPlayer opponent = player == GoPlayer.BLACK ? GoPlayer.WHITE : GoPlayer.BLACK;

        // 找对手棋子位置（判断对手开局的类型）
        int[] oppFirst = findFirstStone(board, opponent);
        int[] oppSecond = findSecondStone(board, opponent);

        // === 第1手：黑棋首选 ===
        if (moveCount == 0) {
            // 经典开局选择：星位(3,3)、三三(3,4)、天元(center,center)
            int[][] options = {{3, 3}, {3, 4}, {center, center}};
            return pickValid(options, validMoves);
        }

        // === 第2手：白棋应对 ===
        if (moveCount == 1) {
            if (oppFirst != null) {
                int dx = oppFirst[0] - center, dy = oppFirst[1] - center; // 相对于中心

                // 应对星位(3,3)或类似位置：各种挂角
                if (Math.abs(dx) <= 2 && Math.abs(dy) <= 2) {
                    int[][] responses = {
                        {oppFirst[0] - 1, oppFirst[1]},     // 小飞挂
                        {oppFirst[0] + 1, oppFirst[1]},     // 另一侧小飞
                        {oppFirst[0], oppFirst[1] - 1},     // 垂直小飞
                        {oppFirst[0], oppFirst[1] + 1},     // 垂直小飞另一侧
                        {oppFirst[0] - 1, oppFirst[1] - 1}, // 一间高挂
                        {oppFirst[0] + 1, oppFirst[1] + 1},
                    };
                    int[] r = pickValid(responses, validMoves);
                    if (r != null) return r;
                }

                // 应对三三：肩冲、托退、飞压
                if (Math.abs(dx) <= 3 && Math.abs(dy) <= 3) {
                    int[][] responses = {
                        {oppFirst[0] - 1, oppFirst[1] - 1}, // 肩冲
                        {oppFirst[0] + 1, oppFirst[1] + 1}, // 另一侧肩冲
                    };
                    int[] r = pickValid(responses, validMoves);
                    if (r != null) return r;
                }
            }
            return null;
        }

        // === 第3-10手：定式继续 ===
        if (moveCount >= 2 && moveCount <= 10) {
            return getBookFollowUp(board, player, validMoves, oppFirst, oppSecond);
        }

        // === 中盘开局（11-30手）：扩张阵型 ===
        if (moveCount > 10 && moveCount <= 30) {
            return getOpeningExpansion(board, player, validMoves);
        }

        return null;
    }

    private int[] findFirstStone(GoPlayer[][] board, GoPlayer player) {
        for (int x = 0; x < BOARD_SIZE; x++) {
            for (int y = 0; y < BOARD_SIZE; y++) {
                if (board[x][y] == player) {
                    return new int[]{x, y};
                }
            }
        }
        return null;
    }

    private int[] findSecondStone(GoPlayer[][] board, GoPlayer player) {
        int count = 0;
        for (int x = 0; x < BOARD_SIZE; x++) {
            for (int y = 0; y < BOARD_SIZE; y++) {
                if (board[x][y] == player) {
                    count++;
                    if (count == 2) return new int[]{x, y};
                }
            }
        }
        return null;
    }

    private int[] pickValid(int[][] options, List<int[]> validMoves) {
        for (int[] opt : options) {
            if (isValid(validMoves, opt[0], opt[1])) {
                return opt;
            }
        }
        return null;
    }

    /**
     * 定式后续应对：找对手棋子附近的价值点
     */
    private int[] getBookFollowUp(GoPlayer[][] board, GoPlayer player, List<int[]> validMoves,
                                  int[] oppFirst, int[] oppSecond) {
        // 在对手棋子附近寻找价值点
        List<int[]> candidates = new ArrayList<>();
        Set<String> targets = new HashSet<>();

        if (oppFirst != null) targets.add(oppFirst[0] + "," + oppFirst[1]);
        if (oppSecond != null) targets.add(oppSecond[0] + "," + oppSecond[1]);

        for (String target : targets) {
            String[] parts = target.split(",");
            int tx = Integer.parseInt(parts[0]);
            int ty = Integer.parseInt(parts[1]);

            // 周围3格范围内的空点
            for (int dx = -3; dx <= 3; dx++) {
                for (int dy = -3; dy <= 3; dy++) {
                    if (dx == 0 && dy == 0) continue;
                    int nx = tx + dx, ny = ty + dy;
                    if (isValid(validMoves, nx, ny)) {
                        // 评估该点的价值
                        int value = evaluateApproachMove(board, nx, ny, player, tx, ty);
                        candidates.add(new int[]{nx, ny, value});
                    }
                }
            }
        }

        if (candidates.isEmpty()) return null;

        // 按价值排序，取最高分
        candidates.sort((a, b) -> b[2] - a[2]);
        int[] best = candidates.get(0);
        return new int[]{best[0], best[1]};
    }

    /**
     * 评估接近点的价值（用于定式后续）
     */
    private int evaluateApproachMove(GoPlayer[][] board, int x, int y, GoPlayer player, int targetX, int targetY) {
        int value = 0;
        int dist = Math.abs(x - targetX) + Math.abs(y - targetY);

        // 距离越近价值越高
        value += Math.max(0, 10 - dist) * 5;

        // 在对手棋子周围（1-2格）很有价值
        if (dist == 1) value += 30;
        if (dist == 2) value += 15;

        // 连接己方棋子
        value += countFriendlyNeighbors(board, x, y, player) * 10;

        // 避免被对手包围
        GoPlayer opponent = player == GoPlayer.BLACK ? GoPlayer.WHITE : GoPlayer.BLACK;
        board[x][y] = player;
        Set<int[]> group = getGroup(board, x, y);
        int libs = countGroupLiberties(board, group);
        board[x][y] = GoPlayer.NONE;
        value += libs * 3;

        // 位置价值
        value += getPositionBonus(x, y);

        return value;
    }

    /**
     * 中盘开局扩张：在己方棋子周围找最优点
     */
    private int[] getOpeningExpansion(GoPlayer[][] board, GoPlayer player, List<int[]> validMoves) {
        // 找己方棋子周围最近的空点
        List<int[]> candidates = new ArrayList<>();

        for (int x = 0; x < BOARD_SIZE; x++) {
            for (int y = 0; y < BOARD_SIZE; y++) {
                if (board[x][y] == player) {
                    // 在己方棋子周围2-4格找空点
                    for (int d = 2; d <= 4; d++) {
                        for (int[] dir : DIRS) {
                            int nx = x + dir[0] * d, ny = y + dir[1] * d;
                            if (isValid(validMoves, nx, ny)) {
                                int value = evaluateMoveLiberties(board, nx, ny, player) * 5
                                          + countFriendlyNeighbors(board, nx, ny, player) * 8
                                          + getPositionBonus(nx, ny);
                                // 避免距离对手太近（被攻击风险）
                                GoPlayer opp = player == GoPlayer.BLACK ? GoPlayer.WHITE : GoPlayer.BLACK;
                                for (int dx = -2; dx <= 2; dx++) {
                                    for (int dy = -2; dy <= 2; dy++) {
                                        int ox = nx + dx, oy = ny + dy;
                                        if (ox >= 0 && ox < BOARD_SIZE && oy >= 0 && oy < BOARD_SIZE
                                            && board[ox][oy] == opp) {
                                            value -= (3 - Math.abs(dx) - Math.abs(dy)) * 5;
                                        }
                                    }
                                }
                                candidates.add(new int[]{nx, ny, value});
                            }
                        }
                    }
                }
            }
        }

        if (candidates.isEmpty()) return null;

        candidates.sort((a, b) -> b[2] - a[2]);
        int[] best = candidates.get(0);
        return new int[]{best[0], best[1]};
    }

    private boolean isValid(List<int[]> moves, int x, int y) {
        if (x < 0 || x >= BOARD_SIZE || y < 0 || y >= BOARD_SIZE) return false;
        for (int[] m : moves) {
            if (m[0] == x && m[1] == y) return true;
        }
        return false;
    }

    // ══════════════════════════════════════════════════════════════════
    //  棋盘操作
    // ══════════════════════════════════════════════════════════════════

    private boolean simulatePlaceStone(GoPlayer[][] board, int x, int y, GoPlayer player) {
        if (x < 0 || x >= BOARD_SIZE || y < 0 || y >= BOARD_SIZE || board[x][y] != GoPlayer.NONE) {
            return false;
        }

        board[x][y] = player;
        GoPlayer opponent = player == GoPlayer.BLACK ? GoPlayer.WHITE : GoPlayer.BLACK;

        int captured = 0;
        for (int[] dir : DIRS) {
            int nx = x + dir[0], ny = y + dir[1];
            if (nx >= 0 && nx < BOARD_SIZE && ny >= 0 && ny < BOARD_SIZE && board[nx][ny] == opponent) {
                Set<int[]> group = getGroup(board, nx, ny);
                if (!hasLiberty(board, group)) {
                    for (int[] pos : group) board[pos[0]][pos[1]] = GoPlayer.NONE;
                    captured += group.size();
                }
            }
        }

        if (captured == 0) {
            Set<int[]> myGroup = getGroup(board, x, y);
            if (!hasLiberty(board, myGroup)) {
                board[x][y] = GoPlayer.NONE;
                return false;
            }
        }
        return true;
    }

    /**
     * 获取所有合法走法（带知识剪枝 + super-ko 过滤）。
     * 返回 3 元素数组 [x, y, value]，value 用于剪枝和排序。
     * 去掉：角部/边部过于深入、无意义的尖、离对手太远的孤立点等。
     * 并过滤会触发 super-ko（全局同型）的走法。
     *
     * <p>性能关键：合法性、提子与 super-ko 哈希在真实棋盘上一次模拟完成，
     * 哈希用 Zobrist 增量更新（XOR 顺序无关，与全盘 boardHash 逐位一致），
     * 替代原先每个候选点的整盘深拷贝 + 361 点重哈希。
     */
    private List<int[]> getAllValidMoves(long baseHash, GoPlayer[][] board, GoPlayer player) {
        List<int[]> moves = new ArrayList<>();
        collectValidMoves(baseHash, board, player, moves, true);

        if (moves.isEmpty()) {
            // 回退到全量搜索（不剪枝，但仍过滤 super-ko）
            collectValidMoves(baseHash, board, player, moves, false);
        }

        // 按价值排序（升序，让 expand 的 remove(size-1) 取出最高分走法优先展开）
        moves.sort((a, b) -> a[2] - b[2]);

        return moves;
    }

    /** 融合的候选收集：落子→收集提子→自杀判定→增量哈希→super-ko 过滤→（可选）知识剪枝。
     *  每个候选点模拟后在原棋盘上完全恢复，与旧 isLegalMove+isKoIllegal 组合逐位等价。
     *  热路径全程使用线程局部原语缓冲，不产生 HashSet/int[] 分配。 */
    private void collectValidMoves(long baseHash, GoPlayer[][] board, GoPlayer player,
                                   List<int[]> out, boolean prune) {
        GoPlayer opponent = player == GoPlayer.BLACK ? GoPlayer.WHITE : GoPlayer.BLACK;
        int[] cells = SCRATCH_CELLS_A.get();
        int[] captured = SCRATCH_CAPT.get();
        for (int x = 0; x < BOARD_SIZE; x++) {
            for (int y = 0; y < BOARD_SIZE; y++) {
                if (board[x][y] != GoPlayer.NONE) continue;
                board[x][y] = player;
                long hashAfter = GoGame.xorStone(baseHash, x, y, player);
                int capturedCount = 0;
                boolean anyCaptured = false;
                for (int[] dir : DIRS) {
                    int nx = x + dir[0], ny = y + dir[1];
                    if (nx >= 0 && nx < BOARD_SIZE && ny >= 0 && ny < BOARD_SIZE
                            && board[nx][ny] == opponent) {
                        int n = scanGroup(board, nx, ny, opponent, cells);
                        if (!cellsHaveLiberty(board, cells, n)) {
                            for (int i = 0; i < n; i++) {
                                int p = cells[i];
                                board[p / BOARD_SIZE][p % BOARD_SIZE] = GoPlayer.NONE;
                                hashAfter = GoGame.xorStone(hashAfter,
                                        p / BOARD_SIZE, p % BOARD_SIZE, opponent);
                                captured[capturedCount++] = p;
                            }
                            anyCaptured = true;
                        }
                    }
                }
                // 无提子时才需检查自身气；有提子必活。棋群在落子态下记录，
                // 供 scoreFusedMove 复用（提子复位不改变其组成）
                int selfCount = -1;
                boolean legal = anyCaptured;
                if (!legal) {
                    selfCount = scanGroup(board, x, y, player, cells);
                    legal = cellsHaveLiberty(board, cells, selfCount);
                }
                if (legal && koHistory != null && koHistory.contains(hashAfter)) {
                    legal = false; // super-ko 过滤
                }
                // 恢复棋盘：先复位提子，再清空落子点（两组点位不相交）。
                // ★ scoreFusedMove/countCaptures 的契约要求 (x,y) 为空、棋盘为原盘，
                //   必须先恢复再打分，否则打分失真且污染后续候选点
                for (int i = 0; i < capturedCount; i++) {
                    int p = captured[i];
                    board[p / BOARD_SIZE][p % BOARD_SIZE] = opponent;
                }
                board[x][y] = GoPlayer.NONE;
                int value = 0;
                boolean keep = false;
                if (legal) {
                    if (prune) {
                        value = scoreFusedMove(board, x, y, player, cells, selfCount);
                        keep = value >= -10;
                    } else {
                        keep = true;
                    }
                }
                if (keep) out.add(new int[]{x, y, value});
            }
        }
    }

    private List<int[]> getSearchMoves(long baseHash, GoPlayer[][] board, GoPlayer player, int consecutivePasses) {
        List<int[]> moves = getAllValidMoves(baseHash, board, player);
        if (consecutivePasses > 0 || countStones(board) >= ENDGAME_STONES || moves.isEmpty()) {
            moves.add(PASS_MOVE.clone());
        }
        return moves;
    }

    /**
     * 评估走法是否值得搜索（负分表示应剪枝）
     */
    private boolean isLegalMove(GoPlayer[][] board, int x, int y, GoPlayer player) {
        if (x < 0 || x >= BOARD_SIZE || y < 0 || y >= BOARD_SIZE
                || board[x][y] != GoPlayer.NONE || player == GoPlayer.NONE) return false;
        board[x][y] = player;
        GoPlayer opponent = player == GoPlayer.BLACK ? GoPlayer.WHITE : GoPlayer.BLACK;
        List<int[]> captured = new ArrayList<>();

        for (int[] dir : DIRS) {
            int nx = x + dir[0], ny = y + dir[1];
            if (nx >= 0 && nx < BOARD_SIZE && ny >= 0 && ny < BOARD_SIZE && board[nx][ny] == opponent) {
                Set<int[]> group = getGroup(board, nx, ny);
                if (!hasLiberty(board, group)) {
                    for (int[] pos : group) {
                        board[pos[0]][pos[1]] = GoPlayer.NONE;
                        captured.add(pos);
                    }
                }
            }
        }

        boolean legal = !captured.isEmpty() || hasLiberty(board, getGroup(board, x, y));

        board[x][y] = GoPlayer.NONE;
        for (int[] pos : captured) board[pos[0]][pos[1]] = opponent;
        return legal;
    }

    /**
     * super-ko 检查：落子后的局面若与全局历史（koHistory）中任何局面重复则非法。
     * 注意：在棋盘副本上模拟（不修改传入棋盘），并通过祖先链（isAncestorKoRepeat）覆盖树内重复。
     */
    private boolean isKoIllegal(GoPlayer[][] board, int x, int y, GoPlayer player) {
        if (koHistory == null) return false;
        GoPlayer[][] test = deepCopyBoard(board);
        if (!simulatePlaceStone(test, x, y, player)) return true; // 落子本身不合法（自杀等）
        long h = GoGame.boardHash(test);
        return koHistory.contains(h);
    }

    private int countStones(GoPlayer[][] board) {
        int count = 0;
        for (int x = 0; x < BOARD_SIZE; x++) {
            for (int y = 0; y < BOARD_SIZE; y++) {
                if (board[x][y] != GoPlayer.NONE) count++;
            }
        }
        return count;
    }

    private int countStones(GoPlayer[][] board, GoPlayer player) {
        int count = 0;
        for (int x = 0; x < BOARD_SIZE; x++) {
            for (int y = 0; y < BOARD_SIZE; y++) {
                if (board[x][y] == player) count++;
            }
        }
        return count;
    }

    private Set<int[]> getGroup(GoPlayer[][] board, int x, int y) {
        Set<int[]> group = new HashSet<>();
        GoPlayer color = board[x][y];
        if (color == GoPlayer.NONE) return group;

        Stack<int[]> stack = new Stack<>();
        boolean[][] visited = new boolean[BOARD_SIZE][BOARD_SIZE];
        stack.push(new int[]{x, y});

        while (!stack.isEmpty()) {
            int[] pos = stack.pop();
            int px = pos[0], py = pos[1];
            if (visited[px][py]) continue;
            visited[px][py] = true;
            group.add(new int[]{px, py});

            for (int[] dir : DIRS) {
                int nx = px + dir[0], ny = py + dir[1];
                if (nx >= 0 && nx < BOARD_SIZE && ny >= 0 && ny < BOARD_SIZE
                        && !visited[nx][ny] && board[nx][ny] == color) {
                    stack.push(new int[]{nx, ny});
                }
            }
        }
        return group;
    }

    private boolean hasLiberty(GoPlayer[][] board, Set<int[]> group) {
        for (int[] pos : group) {
            for (int[] dir : DIRS) {
                int nx = pos[0] + dir[0], ny = pos[1] + dir[1];
                if (nx >= 0 && nx < BOARD_SIZE && ny >= 0 && ny < BOARD_SIZE && board[nx][ny] == GoPlayer.NONE) {
                    return true;
                }
            }
        }
        return false;
    }

    private int countGroupLiberties(GoPlayer[][] board, Set<int[]> group) {
        Set<Long> libertySet = new HashSet<>();
        for (int[] pos : group) {
            for (int[] dir : DIRS) {
                int nx = pos[0] + dir[0], ny = pos[1] + dir[1];
                if (nx >= 0 && nx < BOARD_SIZE && ny >= 0 && ny < BOARD_SIZE && board[nx][ny] == GoPlayer.NONE) {
                    libertySet.add((long) nx * BOARD_SIZE + ny);
                }
            }
        }
        return libertySet.size();
    }

    private GoPlayer[][] deepCopyBoard(GoPlayer[][] board) {
        GoPlayer[][] copy = new GoPlayer[BOARD_SIZE][BOARD_SIZE];
        for (int x = 0; x < BOARD_SIZE; x++) {
            copy[x] = board[x].clone();
        }
        return copy;
    }

    /** 判断两块棋盘是否完全一致（用于树重用前的棋盘匹配校验） */
    private static boolean boardsEqual(GoPlayer[][] a, GoPlayer[][] b) {
        for (int x = 0; x < BOARD_SIZE; x++) {
            for (int y = 0; y < BOARD_SIZE; y++) {
                if (a[x][y] != b[x][y]) return false;
            }
        }
        return true;
    }

    /**
     * 沿父链检查目标哈希是否与任一祖先局面重复（super-ko 全局同型）。
     * 根节点哈希由 getBestMove 初始化，子节点哈希在 expand 时设置。
     */
    private boolean isAncestorKoRepeat(MCTSNode node, long targetHash) {
        MCTSNode ancestor = node;
        while (ancestor != null) {
            if (ancestor.hash != 0 && ancestor.hash == targetHash) return true;
            ancestor = ancestor.parent;
        }
        return false;
    }

    // ══════════════════════════════════════════════════════════════════
    //  MCTS 节点
    // ══════════════════════════════════════════════════════════════════

    private static class MCTSNode {
        GoPlayer[][] board;
        GoPlayer player;
        MCTSNode parent;
        int[] move;
        int[] linkedMove;
        List<MCTSNode> children;
        List<int[]> untriedMoves;

        /** 本节点局面的 Zobrist 哈希（用于 super-ko 全局同型检测） */
        long hash;
        int consecutivePasses;
        boolean terminal;

        double visits = 0;
        double totalScore = 0;
        /** 先验偏置（默认 1.0），根节点的子节点由 Dirichlet 噪声调制 */
        double prior = 1.0;
        /** 仅根节点持有：move "x,y" -> Dirichlet 噪声值（只读） */
        Map<String, Double> rootNoise = null;
        /** 该节点的策略缓存（362 维，网络输出的走法先验），首次展开时填充 */
        double[] policyCache = null;
        /** 该节点的价值缓存（-1~1，与 policyCache 同一次前向计算），避免 MCTS 重复前向 */
        double valueCache = 0;
        /** valueCache 是否已填充（volatile 保证写入顺序，防止双检锁失效） */
        volatile boolean valueCached = false;
        /** 是否已有线程正在为该节点做首次前向，配合 policyCache 判空实现原子占位，
         * 防止并发展开同一节点时被重复 Top-K 剪枝（见 expand() 注释） */
        volatile boolean forwardInFlight = false;

        MCTSNode(GoPlayer[][] board, GoPlayer player, MCTSNode parent, int[] move, List<int[]> untriedMoves) {
            this.board = board;
            this.player = player;
            this.parent = parent;
            this.move = move;
            this.untriedMoves = untriedMoves != null ? new ArrayList<>(untriedMoves) : new ArrayList<>();
        }
    }
}
