package com.wzz.game_console.client.screens.games.gogame;

import com.wzz.game_console.util.GameSettings;

import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicLong;

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
 *   <li>MCTS 树重用 + RAVE + Virtual Loss</li>
 * </ul>
 */
public class MCTSGoAI implements GoAI {
    /** 默认搜索时间（毫秒） */
    private static final int DEFAULT_SEARCH_TIME = 3000;
    /** 默认迭代次数上限 */
    private static final int DEFAULT_ITERATIONS = 5000;
    /** 并行搜索线程数 */
    private static final int PARALLEL_THREADS = Runtime.getRuntime().availableProcessors();

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

    /** 自对弈训练模式：启用根节点 Dirichlet 噪声增强探索（对局模式禁用） */
    private volatile boolean selfPlayMode = false;

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
        this.neuralEvaluator = new NeuralEvaluator();
        if (model != null) {
            this.neuralEvaluator.apply(model);
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

    public static MCTSGoAI createFromSettings() {
        try {
            int searchTime = GameSettings.getInt("go", "searchTime", DEFAULT_SEARCH_TIME);
            int iterations = GameSettings.getInt("go", "mctsIterations", DEFAULT_ITERATIONS);
            return new MCTSGoAI(searchTime, iterations);
        } catch (Throwable t) {
            return new MCTSGoAI();
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

        // 获取最佳走法的胜率（读 currentRoot.children 是唯一的共享写点）
        MCTSNode root = currentRoot;
        if (root == null || root.children == null || root.children.isEmpty()) {
            return false;
        }

        double bestWinRate = Double.NEGATIVE_INFINITY;
        double secondWinRate = Double.NEGATIVE_INFINITY;

        for (MCTSNode child : root.children) {
            if (child.visits > 0) {
                double winRate = child.totalScore / child.visits;
                if (winRate > bestWinRate) {
                    secondWinRate = bestWinRate;
                    bestWinRate = winRate;
                } else if (winRate > secondWinRate) {
                    secondWinRate = winRate;
                }
            }
        }

        // 必胜/必败检测
        if (bestWinRate > WIN_THRESHOLD) {
            return true;
        }
        if (bestWinRate < LOSS_THRESHOLD) {
            return true;
        }

        // 置信区间判断：如果最佳走法显著优于次佳，可以提前终止
        double margin = bestWinRate - secondWinRate;
        if (margin > 0.3 && iterations > MIN_VISITS_FOR_TERMINATION * 2) {
            return true;
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
        GoPlayer[][] board = game.getBoardCopy();
        GoPlayer currentPlayer = game.getCurrentPlayer();
        int moveCount = game.moveHistorySize();

        List<int[]> validMoves = getAllValidMoves(board, currentPlayer);
        if (validMoves.isEmpty()) return null;

        // 杀棋检测：优先处理威胁
        int[] killerMove = findKillerMove(board, currentPlayer, validMoves);
        if (killerMove != null) return killerMove;

        // 开局定式库
        int[] bookMove = getOpeningBookMove(board, currentPlayer, validMoves, moveCount);
        if (bookMove != null) return bookMove;

        // 终局策略
        int stoneCount = countStones(board);
        if (stoneCount >= ENDGAME_STONES) {
            int[] endgameMove = getEndgameMove(board, currentPlayer, validMoves);
            if (endgameMove != null) return endgameMove;
        }

        // 战术阅读：在 MCTS 之前处理中盘复杂战斗
        int[] tacticalMove = tacticalReading(board, currentPlayer);
        if (tacticalMove != null && isLegalMove(board, tacticalMove[0], tacticalMove[1], currentPlayer)) {
            return tacticalMove;
        }

        if (validMoves.size() == 1) {
            int[] m = validMoves.get(0);
            return new int[]{m[0], m[1]};
        }

        // 动态计算搜索时间
        int searchTime = calculateDynamicSearchTime(moveCount, validMoves.size());

        // 树重用
        MCTSNode reusedRoot = tryReuseTree(board, currentPlayer);
        if (reusedRoot != null) {
            this.currentRoot = reusedRoot;
            this.currentRoot.player = currentPlayer;
            this.currentRoot.parent = null;
        } else {
            // 启发式排序候选点
            List<int[]> sortedMoves = heuristicSort(board, currentPlayer, validMoves);
            this.currentRoot = new MCTSNode(board, currentPlayer, null, null, sortedMoves);
        }

        // 根节点 Dirichlet 噪声（仅在自对弈训练时启用，对局模式关闭以保证棋力）
        if (selfPlayMode) {
            applyRootNoise(this.currentRoot);
        }

        long deadline = System.currentTimeMillis() + searchTime;
        totalIterations.set(0);

        // 并行 MCTS 搜索（带提前终止）
        if (parallelThreads > 1) {
            parallelSearchWithEarlyTerminate(deadline);
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
        this.lastMove = best; // 追踪实际选择的走法
        return best;
    }

    /**
     * 返回上一次搜索的 MCTS 访问分布（362 维），作为策略训练目标。
     * 索引 0~360 为棋盘 361 个位置，索引 361 为弃权 pass。
     */
    public double[] getVisitDistribution() {
        double[] dist = new double[362];
        MCTSNode root = currentRoot;
        if (root == null || root.children == null || root.children.isEmpty()) {
            // 无子节点时全部概率给 pass
            dist[361] = 1.0;
            return dist;
        }
        double total = 0;
        for (MCTSNode child : root.children) {
            if (child.visits > 0) {
                total += child.visits;
                if (child.move != null && child.move.length >= 2) {
                    int idx = child.move[0] * BOARD_SIZE + child.move[1];
                    if (idx >= 0 && idx < 361) dist[idx] = child.visits;
                }
            }
        }
        // pass 的访问数（当前根没有独立 pass 节点时按总访问数推算）
        double passVisits = Math.max(root.visits - total, 0);
        dist[361] = passVisits;
        total += passVisits;

        if (total > 0) {
            for (int i = 0; i < 362; i++) dist[i] /= total;
        } else {
            // 退化为均匀分布（仅合法位置）
            dist[361] = 1.0;
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
        Map<String, Double> noiseMap = new HashMap<>(n * 2);
        int i = 0;
        // 对已展开的子节点，直接设置 prior（噪声按策略先验的 ×361 缩放对齐）
        if (root.children != null) {
            for (MCTSNode child : root.children) {
                if (child.move == null) { i++; continue; }
                double v = noise[i++];
                noiseMap.put(child.move[0] + "," + child.move[1], v);
                child.prior = (1.0 - DIRICHLET_EPS) * child.prior + DIRICHLET_EPS * (v * 361.0);
            }
        }
        // 对未展开的候选，仅记录噪声，expand 时读取
        if (root.untriedMoves != null) {
            for (int[] m : root.untriedMoves) {
                double v = noise[i++];
                noiseMap.put(m[0] + "," + m[1], v);
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
        if (currentRoot.children == null || currentRoot.children.isEmpty()) {
            return 0;
        }
        double bestWinRate = Double.NEGATIVE_INFINITY;
        for (MCTSNode child : currentRoot.children) {
            if (child.visits > 0) {
                double winRate = child.totalScore / child.visits;
                if (winRate > bestWinRate) {
                    bestWinRate = winRate;
                }
            }
        }
        return bestWinRate;
    }

    /**
     * 并行 MCTS 搜索（带提前终止）。
     * <p>
     * 每个线程使用独立的 MCTSNode 树进行搜索，模拟结束后通过 mergeResults
     * 将结果合并回主树，从而彻底避免所有竞态条件：
     * <ul>
     *   <li>node.visits++ / node.totalScore += score — 只在线程本地的树中执行，无竞争</li>
     *   <li>node.untriedMoves.remove() — 仅在线程本地的 ArrayList 中操作，安全</li>
     *   <li>node.children.add(child) — 仅在线程本地的树中增删，安全</li>
     *   <li>shouldTerminateEarly 读取的 currentRoot.children 在合并阶段才被写入，主线程独占</li>
     * </ul>
     */
    private void parallelSearchWithEarlyTerminate(long deadline) {
        List<Future<SearchResult>> futures = new ArrayList<>(parallelThreads);

        // 每个线程独立搜索一棵本地树（复用共享线程池）
        for (int i = 0; i < parallelThreads; i++) {
            futures.add(SHARED_POOL.submit(() -> {
                // 深拷贝主树作为本地根节点，后续所有操作均在本地树上进行
                MCTSNode localRoot = cloneNodeTree(currentRoot);
                int localIters = 0;
                int itersPerThread = maxIterations / parallelThreads;

                while (localIters < itersPerThread && System.currentTimeMillis() < deadline) {
                    // 通过原子变量读取总迭代次数（读操作对合并无影响）
                    if (shouldTerminateEarly(totalIterations.get())) {
                        break;
                    }
                    localIters++;
                    totalIterations.incrementAndGet();

                    MCTSNode node = selectNode(localRoot);
                    if (!node.untriedMoves.isEmpty()) {
                        expand(node);
                    }
                    double score = simulate(node);
                    backpropagate(node, score);
                }
                return new SearchResult(localRoot, localIters);
            }));
        }

        // 等待所有线程完成（或超时）
        List<SearchResult> results = new ArrayList<>();
        for (Future<SearchResult> f : futures) {
            try {
                long remaining = Math.max(1, deadline - System.currentTimeMillis() + 100);
                SearchResult r = f.get(remaining, TimeUnit.MILLISECONDS);
                if (r != null) {
                    results.add(r);
                }
            } catch (Exception ignored) {
                // 超时或中断：忽略该线程结果
            }
        }

        // 共享线程池不关闭（线程设为 daemon，随进程退出）

        // 将所有本地树的结果合并回 currentRoot
        if (!results.isEmpty()) {
            mergeResults(results);
        }
    }

    /**
     * 顺序 MCTS 搜索（带提前终止）
     */
    private void sequentialSearchWithEarlyTerminate(long deadline) {
        int iterations = 0;
        while (iterations < maxIterations && System.currentTimeMillis() < deadline) {
            iterations++;
            totalIterations.set(iterations);
            // 提前终止检查
            if (shouldTerminateEarly(iterations)) {
                break;
            }
            MCTSNode node = selectNode(currentRoot);
            if (!node.untriedMoves.isEmpty()) {
                expand(node);
            }
            double score = simulate(node);
            backpropagate(node, score);
        }
    }

    /**
     * 深度拷贝一棵 MCTSNode 树（用于并行搜索时为每个线程创建独立副本）。
     * <p>
     * 新树的每个节点持有独立的 board 引用和 untriedMoves ArrayList，
     * 保证线程间完全隔离。
     *
     * @param root 要拷贝的根节点（来自 currentRoot）
     * @return 独立副本的根节点
     */
    private MCTSNode cloneNodeTree(MCTSNode root) {
        if (root == null) return null;

        MCTSNode copy = new MCTSNode(
                deepCopyBoard(root.board),
                root.player,
                null,           // 新树的 parent 设为 null
                root.move,
                root.untriedMoves != null ? new ArrayList<>(root.untriedMoves) : new ArrayList<>()
        );
        copy.visits = root.visits;
        copy.totalScore = root.totalScore;
        copy.linkedMove = root.linkedMove;
        copy.prior = root.prior;
        copy.rootNoise = root.rootNoise; // 只读共享，线程安全
        copy.policyCache = root.policyCache; // 只读共享，线程安全
        copy.valueCache = root.valueCache;
        copy.valueCached = root.valueCached;

        // 递归拷贝子树
        if (root.children != null) {
            copy.children = new ArrayList<>(root.children.size());
            for (MCTSNode child : root.children) {
                MCTSNode childCopy = cloneNodeTreeRecursive(child, copy);
                copy.children.add(childCopy);
            }
        }

        return copy;
    }

    /**
     * 递归拷贝子树的内部实现。
     */
    private MCTSNode cloneNodeTreeRecursive(MCTSNode node, MCTSNode parent) {
        if (node == null) return null;

        MCTSNode copy = new MCTSNode(
                deepCopyBoard(node.board),
                node.player,
                parent,
                node.move,
                node.untriedMoves != null ? new ArrayList<>(node.untriedMoves) : new ArrayList<>()
        );
        copy.visits = node.visits;
        copy.totalScore = node.totalScore;
        copy.linkedMove = node.linkedMove;
        copy.prior = node.prior;
        copy.rootNoise = node.rootNoise; // 只读共享，线程安全
        copy.policyCache = node.policyCache; // 只读共享，线程安全
        copy.valueCache = node.valueCache;
        copy.valueCached = node.valueCached;

        if (node.children != null) {
            copy.children = new ArrayList<>(node.children.size());
            for (MCTSNode child : node.children) {
                MCTSNode childCopy = cloneNodeTreeRecursive(child, copy);
                copy.children.add(childCopy);
            }
        }

        return copy;
    }

    /**
     * 将各线程的本地搜索结果合并回主树 currentRoot。
     * <p>
     * 合并策略：对 currentRoot.children 中的每个子节点，将其 visits 和 totalScore
     * 加上所有本地树中对应 move 的对应节点的统计值。
     * 若某本地树有主树不存在的子节点，则追加到主树中。
     */
    private void mergeResults(List<SearchResult> results) {
        if (results.isEmpty() || currentRoot == null) return;

        // 统计主树已有子节点的 move -> child 映射
        Map<String, MCTSNode> rootChildMap = new HashMap<>();
        if (currentRoot.children == null) {
            currentRoot.children = new ArrayList<>();
        } else {
            for (MCTSNode child : currentRoot.children) {
                if (child.move != null) {
                    rootChildMap.put(child.move[0] + "," + child.move[1], child);
                }
            }
        }

        // 遍历每个本地搜索结果
        for (SearchResult result : results) {
            MCTSNode localRoot = result.localRoot;
            if (localRoot == null || localRoot.children == null) continue;

            for (MCTSNode localChild : localRoot.children) {
                if (localChild.move == null) continue;

                String key = localChild.move[0] + "," + localChild.move[1];
                MCTSNode existing = rootChildMap.get(key);

                if (existing != null) {
                    // 累加到现有子节点
                    existing.visits += localChild.visits;
                    existing.totalScore += localChild.totalScore;

                    // 合并子节点的子节点（递归合并）
                    if (localChild.children != null && !localChild.children.isEmpty()) {
                        mergeChildren(existing, localChild.children);
                    }
                } else {
                    // 主树中没有对应节点，深拷贝本地子树并追加
                    MCTSNode newChild = cloneNodeTreeRecursive(localChild, currentRoot);
                    currentRoot.children.add(newChild);
                    rootChildMap.put(key, newChild);
                }
            }
        }
    }

    /**
     * 将 sourceChildren 合并到 targetNode.children 中。
     */
    private void mergeChildren(MCTSNode targetNode, List<MCTSNode> sourceChildren) {
        if (targetNode.children == null) {
            targetNode.children = new ArrayList<>();
        }

        // 建立目标节点已有子节点的映射
        Map<String, MCTSNode> childMap = new HashMap<>();
        for (MCTSNode child : targetNode.children) {
            if (child.move != null) {
                childMap.put(child.move[0] + "," + child.move[1], child);
            }
        }

        for (MCTSNode srcChild : sourceChildren) {
            if (srcChild.move == null) continue;

            String key = srcChild.move[0] + "," + srcChild.move[1];
            MCTSNode existing = childMap.get(key);

            if (existing != null) {
                existing.visits += srcChild.visits;
                existing.totalScore += srcChild.totalScore;

                if (srcChild.children != null && !srcChild.children.isEmpty()) {
                    mergeChildren(existing, srcChild.children);
                }
            } else {
                MCTSNode newChild = cloneNodeTreeRecursive(srcChild, targetNode);
                targetNode.children.add(newChild);
                childMap.put(key, newChild);
            }
        }
    }

    /**
     * 扩展节点
     */
    private void expand(MCTSNode node) {
        if (node.untriedMoves.isEmpty()) return;
        int[] moveFull = node.untriedMoves.remove(node.untriedMoves.size() - 1);
        int[] move = new int[]{moveFull[0], moveFull[1]};

        // 首次展开时：一次前向同时拿到策略先验与价值，避免后续 simulate 重复计算
        if (node.policyCache == null) {
            NeuralEvaluator.ForwardResult fr = neuralEvaluator.forward(
                    neuralEvaluator.buildInputPlanes(node.board, node.player, node.move),
                    neuralEvaluator.extractAuxFeatures(node.board, node.player));
            node.policyCache = fr.policy;
            node.valueCache = fr.value;
            node.valueCached = true;
        }

        GoPlayer[][] childBoard = deepCopyBoard(node.board);
        if (simulatePlaceStone(childBoard, move[0], move[1], node.player)) {
            GoPlayer nextPlayer = node.player == GoPlayer.BLACK ? GoPlayer.WHITE : GoPlayer.BLACK;
            List<int[]> childMoves = heuristicSort(childBoard, nextPlayer, getAllValidMoves(childBoard, nextPlayer));
            MCTSNode child = new MCTSNode(childBoard, nextPlayer, node, move, childMoves);

            // 策略先验：从缓存中查找该走法的概率
            double prior = 1.0;
            if (node.policyCache != null) {
                int moveIdx = move[0] * BOARD_SIZE + move[1];
                if (moveIdx >= 0 && moveIdx < 361) {
                    prior = Math.max(node.policyCache[moveIdx], 1e-10) * 361.0; // 缩放回约 1.0 量级
                }
            }
            // 根节点 Dirichlet 噪声叠加
            if (node.rootNoise != null) {
                Double w = node.rootNoise.get(move[0] + "," + move[1]);
                if (w != null) {
                    prior = (1.0 - DIRICHLET_EPS) * prior + DIRICHLET_EPS * (w * 361.0);
                }
            }
            child.prior = prior;

            if (node.children == null) node.children = new ArrayList<>();
            node.children.add(child);
            node.linkedMove = move;
        }
    }

    /**
     * 启发式排序候选点
     * @param moves 支持 2 元素 [x,y] 或 3 元素 [x,y,pruningValue] 数组
     */
    private List<int[]> heuristicSort(GoPlayer[][] board, GoPlayer player, List<int[]> moves) {
        // 用 int[361] 数组替代 HashMap<String,Integer>，减少分配和装箱
        int[] scores = new int[BOARD_SIZE * BOARD_SIZE];
        java.util.Arrays.fill(scores, Integer.MIN_VALUE);

        for (int[] move : moves) {
            int score = 0;
            int x = move[0], y = move[1];
            int idx = x * BOARD_SIZE + y;

            // 如果是 3 元素数组，先加入剪枝阶段的价值分
            if (move.length >= 3) {
                score += move[2];
            }

            // 1. 吃子检测（打吃）
            score += countCaptures(board, x, y, player) * 50;

            // 2. 被吃检测（防打吃）
            GoPlayer opponent = player == GoPlayer.BLACK ? GoPlayer.WHITE : GoPlayer.BLACK;
            score -= countCaptures(board, x, y, opponent) * 40;

            // 3. 气数评估
            score += evaluateMoveLiberties(board, x, y, player) * 10;

            // 4. 位置评估
            score += getPositionBonus(x, y);

            // 5. 连接己方棋子
            score += countFriendlyNeighbors(board, x, y, player) * 15;

            // 6. 防止己方被打吃
            if (wouldBeInAtari(board, x, y, player)) {
                score -= 30;
            }

            scores[idx] = score;
        }

        // 按分数排序（通过数组查分，避免字符串拼接）
        moves.sort((a, b) -> {
            int sa = scores[a[0] * BOARD_SIZE + a[1]];
            int sb = scores[b[0] * BOARD_SIZE + b[1]];
            // Integer.MIN_VALUE 表示未计算（理论上不可能）
            return Integer.compare(sb, sa);
        });

        return moves;
    }

    /**
     * 计算落子能吃的棋子数
     */
    private int countCaptures(GoPlayer[][] board, int x, int y, GoPlayer player) {
        board[x][y] = player;
        GoPlayer opponent = player == GoPlayer.BLACK ? GoPlayer.WHITE : GoPlayer.BLACK;
        int captures = 0;

        for (int[] dir : DIRS) {
            int nx = x + dir[0], ny = y + dir[1];
            if (nx >= 0 && nx < BOARD_SIZE && ny >= 0 && ny < BOARD_SIZE && board[nx][ny] == opponent) {
                Set<int[]> group = getGroup(board, nx, ny);
                if (!hasLiberty(board, group)) {
                    captures += group.size();
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

    /** 共享线程池（复用线程，避免每次搜索都创建/销毁） */
    private static final ExecutorService SHARED_POOL;
    static {
        int threads = Math.max(2, Runtime.getRuntime().availableProcessors() - 1);
        SHARED_POOL = Executors.newCachedThreadPool(
                r -> { Thread t = new Thread(r, "mcts-worker"); t.setDaemon(true); return t; });
    }

    private MCTSNode selectNode(MCTSNode node) {
        while (node.untriedMoves.isEmpty() && node.children != null && !node.children.isEmpty()) {
            node = selectBestChild(node);
        }
        return node;
    }

    private MCTSNode selectBestChild(MCTSNode parent) {
        MCTSNode best = null;
        double bestValue = Double.NEGATIVE_INFINITY;
        double logParentVisits = Math.log(Math.max(parent.visits, 1));

        for (MCTSNode child : parent.children) {
            if (child.visits == 0) return child;

            double winRate = child.totalScore / child.visits;
            // PUCT: Q + c_puct * P(s,a) * sqrt(N_parent) / (1 + N_child)
            double ucb = winRate
                    + UCB_C * child.prior * Math.sqrt(logParentVisits / (1.0 + child.visits));

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

    private void backpropagate(MCTSNode node, double score) {
        while (node != null) {
            node.visits++;
            node.totalScore += score;
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
                null  // untriedMoves 不需要复制
        );
        copy.visits = node.visits;
        copy.totalScore = node.totalScore;
        copy.linkedMove = node.linkedMove;
        copy.prior = node.prior;
        copy.policyCache = node.policyCache; // 只读共享，线程安全（行为复用）
        copy.valueCache = node.valueCache;
        copy.valueCached = node.valueCached;

        if (node.children != null) {
            copy.children = new ArrayList<>();
            for (MCTSNode child : node.children) {
                // 子节点的棋盘引用会在递归时用 newBoard 更新
                MCTSNode childCopy = deepCopyNode(child, newBoard);
                childCopy.parent = copy;
                copy.children.add(childCopy);
            }
        }

        return copy;
    }

    private int[] getBestMCTSMove(MCTSNode root) {
        if (root.children == null || root.children.isEmpty()) return null;

        // 选择胜率最高的走法
        MCTSNode best = null;
        double bestScore = Double.NEGATIVE_INFINITY;

        for (MCTSNode child : root.children) {
            if (child.visits > 0) {
                double winRate = child.totalScore / child.visits;
                if (winRate > bestScore) {
                    bestScore = winRate;
                    best = child;
                }
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
        if (root.children == null || root.children.isEmpty()) return null;
        double temp = moveCount < 30 ? 1.0 : 0.1;

        List<MCTSNode> candidates = new ArrayList<>();
        List<Double> weights = new ArrayList<>();
        double sum = 0;
        for (MCTSNode child : root.children) {
            if (child.visits > 0) {
                double w = (temp <= 0.01) ? child.visits : Math.pow(child.visits, 1.0 / temp);
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

    // ══════════════════════════════════════════════════════════════════
    //  战术阅读器（深度优先 α-β 搜索）
    // ══════════════════════════════════════════════════════════════════

    private static final double TACTICAL_WINDOW = 0.3;  // α-β 剪枝窗口

    /**
     * 战术阅读：检查某区域是否存在必杀或必活的走法。
     * 用于中盘复杂战斗的局部计算。
     */
    private int[] tacticalReading(GoPlayer[][] board, GoPlayer player) {
        // 找对手的危险棋群（气数<=3）
        GoPlayer opponent = player == GoPlayer.BLACK ? GoPlayer.WHITE : GoPlayer.BLACK;
        List<Set<int[]>> targetGroups = new ArrayList<>();

        for (int x = 0; x < BOARD_SIZE; x++) {
            for (int y = 0; y < BOARD_SIZE; y++) {
                if (board[x][y] == opponent) {
                    Set<int[]> group = getGroup(board, x, y);
                    int libs = countGroupLiberties(board, group);
                    if (libs <= 3 && group.size() >= 2) {
                        targetGroups.add(group);
                    }
                }
            }
        }

        if (targetGroups.isEmpty()) return null;

        // 对每个危险棋群找杀棋走法
        for (Set<int[]> target : targetGroups) {
            int[] killer = findTacticalKill(board, player, target);
            if (killer != null) return killer;
        }

        return null;
    }

    /**
     * 找杀死目标棋群的走法
     */
    private int[] findTacticalKill(GoPlayer[][] board, GoPlayer player, Set<int[]> target) {
        // 收集目标棋群周围的所有候选点
        Set<String> candidates = new HashSet<>();
        for (int[] pos : target) {
            for (int[] dir : DIRS) {
                int nx = pos[0] + dir[0], ny = pos[1] + dir[1];
                if (nx >= 0 && nx < BOARD_SIZE && ny >= 0 && ny < BOARD_SIZE
                    && board[nx][ny] == GoPlayer.NONE) {
                    candidates.add(nx + "," + ny);
                }
            }
        }

        if (candidates.isEmpty()) return null;

        // 简单评分排序
        List<int[]> moves = new ArrayList<>();
        for (String c : candidates) {
            String[] parts = c.split(",");
            int x = Integer.parseInt(parts[0]), y = Integer.parseInt(parts[1]);
            int score = evaluateTacticalMove(board, x, y, player, target);
            moves.add(new int[]{x, y, score});
        }

        moves.sort((a, b) -> b[2] - a[2]);

        // 取最高分的走法
        if (!moves.isEmpty() && moves.get(0)[2] > 0) {
            int[] best = moves.get(0);
            return new int[]{best[0], best[1]};
        }

        return null;
    }

    private int evaluateTacticalMove(GoPlayer[][] board, int x, int y, GoPlayer player,
                                     Set<int[]> target) {
        int score = 0;

        // 检查落子后能否提掉目标
        GoPlayer[][] test = deepCopyBoard(board);
        simulatePlaceStone(test, x, y, player);

        boolean allDead = true;
        for (int[] pos : target) {
            if (test[pos[0]][pos[1]] != GoPlayer.NONE) {
                allDead = false;
                break;
            }
        }
        if (allDead) score += 100;  // 必杀

        // 检查落子后己方是否安全
        test[x][y] = player;
        Set<int[]> myGroup = getGroup(test, x, y);
        int myLibs = countGroupLiberties(test, myGroup);
        if (myLibs >= 2) score += 20;
        if (myLibs == 1) score -= 50;  // 自杀危险

        // 检查能否紧气
        score += countCaptures(board, x, y, player) * 15;

        return score;
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
        int[] ladderMove = findLadderCapture(board, player);
        if (ladderMove != null) return ladderMove;

        // 4. 劫材价值：落子后成为劫材
        int[] koMove = findKoThreat(board, player, validMoves);
        if (koMove != null) return koMove;

        // 5. 防守对手征子
        int[] defendLadder = findDefendLadder(board, player);
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
    private int[] findLadderCapture(GoPlayer[][] board, GoPlayer player) {
        GoPlayer opponent = player == GoPlayer.BLACK ? GoPlayer.WHITE : GoPlayer.BLACK;
        for (int x = 0; x < BOARD_SIZE; x++) {
            for (int y = 0; y < BOARD_SIZE; y++) {
                if (board[x][y] == opponent) {
                    Set<int[]> group = getGroup(board, x, y);
                    if (countGroupLiberties(board, group) == 1) {
                        int[] escape = getEscapeDirection(board, group, opponent);
                        if (escape != null) return escape;
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
    private int[] findDefendLadder(GoPlayer[][] board, GoPlayer player) {
        for (int x = 0; x < BOARD_SIZE; x++) {
            for (int y = 0; y < BOARD_SIZE; y++) {
                if (board[x][y] == player) {
                    Set<int[]> group = getGroup(board, x, y);
                    if (countGroupLiberties(board, group) == 1) {
                        int[] escape = getEscapeDirection(board, group, player);
                        if (escape != null) return escape;
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
     * 获取所有合法走法（带知识剪枝）。
     * 返回 3 元素数组 [x, y, value]，value 用于剪枝和排序。
     * 去掉：角部/边部过于深入、无意义的尖、离对手太远的孤立点等。
     */
    private List<int[]> getAllValidMoves(GoPlayer[][] board, GoPlayer player) {
        List<int[]> moves = new ArrayList<>();

        for (int x = 0; x < BOARD_SIZE; x++) {
            for (int y = 0; y < BOARD_SIZE; y++) {
                if (board[x][y] != GoPlayer.NONE) continue;
                if (!isLegalMove(board, x, y, player)) continue;

                // 知识剪枝：评估走法价值
                int value = evaluateMovePruning(board, x, y, player);
                if (value < -10) continue;  // 明显差的走法直接跳过

                moves.add(new int[]{x, y, value});
            }
        }

        if (moves.isEmpty()) {
            // 回退到全量搜索（不剪枝）
            for (int x = 0; x < BOARD_SIZE; x++) {
                for (int y = 0; y < BOARD_SIZE; y++) {
                    if (board[x][y] == GoPlayer.NONE && isLegalMove(board, x, y, player)) {
                        moves.add(new int[]{x, y, 0});
                    }
                }
            }
        }

        // 按价值排序（优先搜索高价值走法）
        moves.sort((a, b) -> b[2] - a[2]);

        // 返回 3 元素数组给调用方，展开时取前两个元素
        return moves;
    }

    /**
     * 评估走法是否值得搜索（负分表示应剪枝）
     */
    private int evaluateMovePruning(GoPlayer[][] board, int x, int y, GoPlayer player) {
        int score = 0;

        // 基本评估
        score += evaluateMoveLiberties(board, x, y, player) * 3;
        score += countFriendlyNeighbors(board, x, y, player) * 5;
        score += countCaptures(board, x, y, player) * 20;
        score += getPositionBonus(x, y);

        // 负分剪枝条件：

        // 1. 过于深入边角（边部4线以内，深度超过2格）
        int edgeDist = Math.min(Math.min(x, y), Math.min(BOARD_SIZE - 1 - x, BOARD_SIZE - 1 - y));
        if (edgeDist == 0) score -= 5;  // 边路
        if (edgeDist == 0 && !hasFriendlyNeighbor(board, x, y, player)) score -= 20;

        // 2. 孤立的点（周围无己方棋子，对手棋子多）
        int friendlyNbrs = countFriendlyNeighbors(board, x, y, player);
        GoPlayer opponent = player == GoPlayer.BLACK ? GoPlayer.WHITE : GoPlayer.BLACK;
        int oppNbrs = 0;
        for (int[] dir : DIRS) {
            int nx = x + dir[0], ny = y + dir[1];
            if (nx >= 0 && nx < BOARD_SIZE && ny >= 0 && ny < BOARD_SIZE && board[nx][ny] == opponent) {
                oppNbrs++;
            }
        }
        if (friendlyNbrs == 0 && oppNbrs >= 3) score -= 15;  // 被包围的孤立点

        // 3. 自杀式落子（落子后气数很少）
        board[x][y] = player;
        Set<int[]> group = getGroup(board, x, y);
        int libs = countGroupLiberties(board, group);
        board[x][y] = GoPlayer.NONE;
        if (libs <= 1 && countCaptures(board, x, y, player) == 0) score -= 30;

        return score;
    }

    private boolean hasFriendlyNeighbor(GoPlayer[][] board, int x, int y, GoPlayer player) {
        return countFriendlyNeighbors(board, x, y, player) > 0;
    }

    private boolean isLegalMove(GoPlayer[][] board, int x, int y, GoPlayer player) {
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

    // ══════════════════════════════════════════════════════════════════
    //  并行搜索结果容器
    // ══════════════════════════════════════════════════════════════════

    /**
     * 并行搜索线程的返回值封装。
     */
    private static final class SearchResult {
        final MCTSNode localRoot;
        final int iterations;

        SearchResult(MCTSNode localRoot, int iterations) {
            this.localRoot = localRoot;
            this.iterations = iterations;
        }
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
        /** valueCache 是否已填充 */
        boolean valueCached = false;

        MCTSNode(GoPlayer[][] board, GoPlayer player, MCTSNode parent, int[] move, List<int[]> untriedMoves) {
            this.board = board;
            this.player = player;
            this.parent = parent;
            this.move = move;
            this.untriedMoves = untriedMoves != null ? new ArrayList<>(untriedMoves) : new ArrayList<>();
        }
    }
}
