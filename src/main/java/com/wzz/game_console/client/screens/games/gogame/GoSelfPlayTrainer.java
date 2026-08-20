package com.wzz.game_console.client.screens.games.gogame;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Random;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

/** Pure-Java self-play trainer for the local Go evaluator. */
public final class GoSelfPlayTrainer {
    public static final class Config {
        public int searchTimeMillis = 300;
        public int maxIterations = 500;
        public int parallelism = 30;
        public int maxMoves = 300;
        public int batchSize = 128;
        public double l2 = 1.0e-5;
        public double gradientClip = 5.0;
        public double momentum = 0.9;
        public int maxReplaySamples = 20_000;
    }

    // ── 8-fold 对称增强 ──────────────────────────────────────────
    private static final int BOARD_SIZE = 19;
    private static final int BOARD_FEATURES = BOARD_SIZE * BOARD_SIZE;
    private static final int AUX_FEATURES = 24;
    private static final int[][] SYMM_PERMS = buildSymmetryPerms();

    private static int[][] buildSymmetryPerms() {
        int n = BOARD_SIZE;
        int[][] perms = new int[8][BOARD_FEATURES];
        for (int x = 0; x < n; x++) {
            for (int y = 0; y < n; y++) {
                int idx = x * n + y;
                int[] targets = new int[]{
                    idx,
                    y * n + (n - 1 - x),
                    (n - 1 - x) * n + (n - 1 - y),
                    (n - 1 - y) * n + x,
                    (n - 1 - x) * n + y,
                    x * n + (n - 1 - y),
                    y * n + x,
                    (n - 1 - y) * n + (n - 1 - x),
                };
                for (int t = 0; t < 8; t++) perms[t][idx] = targets[t];
            }
        }
        return perms;
    }

    public static final class Result {
        public final int games;
        public final int samples;
        public final int completedGames;
        public final double meanLoss;
        public final NeuralEvaluator evaluator;

        private Result(int games, int samples, int completedGames, double meanLoss,
                       NeuralEvaluator evaluator) {
            this.games = games;
            this.samples = samples;
            this.completedGames = completedGames;
            this.meanLoss = meanLoss;
            this.evaluator = evaluator;
        }
    }

    private static final class Sample {
        final GoPlayer[][] board;       // 存储棋盘供训练时重建平面
        final GoPlayer player;
        final double[] policyTarget;    // 362 维 MCTS 访问分布
        double valueTarget;

        Sample(GoPlayer[][] board, GoPlayer player, double[] policyTarget) {
            this.board = board;
            this.player = player;
            this.policyTarget = policyTarget;
        }
    }

    private final Config config;
    private NeuralEvaluator evaluator;
    private final List<Sample> replayBuffer = new ArrayList<>();
    /** 当前代次数（用于探索衰减等训练策略） */
    private int generation = 0;

    public GoSelfPlayTrainer() {
        this(new Config(), new NeuralEvaluator());
    }

    public GoSelfPlayTrainer(Config config) {
        this(config, new NeuralEvaluator());
    }

    public GoSelfPlayTrainer(Config config, NeuralEvaluator evaluator) {
        this.config = config == null ? new Config() : config;
        this.evaluator = evaluator == null ? new NeuralEvaluator() : evaluator;
    }

    public NeuralEvaluator getEvaluator() { return evaluator; }
    public int getReplayBufferSize() { return replayBuffer.size(); }
    public void clearReplayBuffer() { replayBuffer.clear(); }

    /** Runs one generation, then trains the shared model on the collected positions. */
    public Result runGeneration(int games, int parallelism, int epochs, double learningRate, long seed) {
        if (games < 0 || epochs < 0 || learningRate <= 0) throw new IllegalArgumentException("Invalid generation parameters");
        if (games == 0) return new Result(0, 0, 0, 0, evaluator);
        generation++; // 递增代次，供探索衰减使用
        // 探索强度随训练代次衰减：gen 0→1.0, gen 40→0.2
        final double expScale = Math.max(0.2, 1.0 - 0.02 * (generation - 1));
        int workers = Math.max(1, Math.min(parallelism <= 0 ? config.parallelism : parallelism, games));
        final NeuralEvaluator.ModelWeights snapshot = evaluator.snapshot();
        ExecutorService pool = Executors.newFixedThreadPool(workers);
        List<Future<GameSamples>> futures = new ArrayList<>();
        try {
            for (int i = 0; i < games; i++) {
                final int gameIndex = i;
                futures.add(pool.submit(() -> playGame(snapshot, seed + 0x9E3779B97F4A7C15L * gameIndex, expScale)));
            }
            List<Sample> newSamples = new ArrayList<>();
            int completed = 0;
            for (Future<GameSamples> future : futures) {
                try {
                    GameSamples game = future.get();
                    newSamples.addAll(game.samples);
                    completed++;
                } catch (Exception e) {
                    System.err.println("[SelfPlay] game failed: " + e.getMessage());
                }
            }
            replayBuffer.addAll(newSamples);
            trimReplayBuffer();
            double loss = train(replayBuffer, epochs, learningRate, seed ^ 0xD1B54A32D192ED03L);
            return new Result(games, newSamples.size(), completed, loss, evaluator);
        } finally {
            pool.shutdownNow();
        }
    }

    private void trimReplayBuffer() {
        int max = Math.max(1, config.maxReplaySamples);
        if (replayBuffer.size() > max) {
            // O(n) 批量移除，避免逐条 remove(0) 的 O(n²)
            replayBuffer.subList(0, replayBuffer.size() - max).clear();
        }
    }

    /**
     * 余弦退火学习率。
     * @param initialLr 初始学习率
     * @param currentStep 当前步数（0-based）
     * @param totalSteps 总步数
     * @return 当前学习率
     */
    public static double cosineLearningRate(double initialLr, int currentStep, int totalSteps) {
        if (totalSteps <= 0) return initialLr;
        double ratio = (double) currentStep / totalSteps;
        return initialLr * 0.5 * (1.0 + Math.cos(Math.PI * ratio));
    }

    private GameSamples playGame(NeuralEvaluator.ModelWeights model, long seed, double explorationScale) {
        List<Sample> samples = new ArrayList<>();
        GoGame game = GoGame.rulesOnly();
        MCTSGoAI ai = new MCTSGoAI(config.searchTimeMillis, config.maxIterations, 1, model);
        ai.setRandomSeed(seed);
        // 自对弈模式：开启根节点 Dirichlet 噪声 + 访问分布温度采样（增强探索）
        ai.setSelfPlayMode(true);
        // 探索强度随训练代次衰减
        ai.setExplorationScale(explorationScale);
        try {
            int moves = 0;
            while (!game.isGameOver() && moves < Math.max(1, config.maxMoves)) {
                GoPlayer player = game.getCurrentPlayer();
                // 获取当前棋盘副本
                GoPlayer[][] boardCopy = game.getBoardCopy();

                // 获取 MCTS 走法（并记录访问分布作为策略目标）
                int[] move = ai.getBestMove(game);
                // 从 MCTS 获取访问分布（访问数 / 总访问数）
                double[] policyTarget = ai.getVisitDistribution();

                // 存储样本：棋盘 + 当前玩家 + 策略目标（价值目标在终局后统一设置）
                samples.add(new Sample(boardCopy, player, policyTarget));

                // 执行走法
                boolean played = move != null && move.length >= 2 && game.placeStone(move[0], move[1]);
                if (!played) {
                    for (int x = 0; x < game.getBoardSize() && !played; x++) {
                        for (int y = 0; y < game.getBoardSize() && !played; y++) {
                            played = game.placeStone(x, y);
                        }
                    }
                }
                if (!played) game.pass();
                moves++;
            }
            if (!game.isGameOver()) game.pass();

            // 设置价值目标（中国规则数子法）
            double margin = game.getScoreMargin(GoPlayer.BLACK);
            for (Sample s : samples) {
                s.valueTarget = clamp((s.player == GoPlayer.BLACK ? margin : -margin) / 100.0);
            }
            return new GameSamples(samples);
        } finally {
            ai.shutdown();
            game.close();
        }
    }

    /**
     * 训练模型。对每个样本重建输入平面和辅助特征，8 倍对称展开后训练。
     */
    private double train(List<Sample> samples, int epochs, double learningRate, long seed) {
        if (samples.isEmpty() || epochs == 0) return 0;
        Random random = new Random(seed);
        double total = 0;
        int batches = 0;
        int symCount = SYMM_PERMS.length;

        for (int epoch = 0; epoch < epochs; epoch++) {
            Collections.shuffle(samples, random);
            int batchLimit = Math.max(1, config.batchSize);
            for (int start = 0; start < samples.size(); start += batchLimit) {
                int end = Math.min(samples.size(), start + batchLimit);
                int baseCount = end - start;
                double[][][][] planes = new double[baseCount * symCount][4][BOARD_SIZE][BOARD_SIZE];
                double[][] aux = new double[baseCount * symCount][AUX_FEATURES];
                double[] values = new double[baseCount * symCount];
                double[][] policies = new double[baseCount * symCount][362];

                int n = 0;
                for (int k = start; k < end; k++) {
                    Sample s = samples.get(k);
                    // 辅助特征在 D4 对称下不变（气数直方图/眼形/全局特征均对称），只需计算一次
                    double[] baseAux = evaluator.extractAuxFeatures(s.board, s.player);
                    for (int t = 0; t < symCount; t++) {
                        // 对棋盘应用对称变换
                        GoPlayer[][] transformedBoard = applySymmetry(s.board, SYMM_PERMS[t]);
                        // 构建输入平面
                        planes[n] = evaluator.buildInputPlanes(transformedBoard, s.player, null);
                        // 辅助特征（对称不变，复用）
                        aux[n] = baseAux;
                        // 价值目标
                        values[n] = s.valueTarget;
                        // 策略目标：前 361 维随棋盘变换，pass 维不变
                        if (t == 0) {
                            policies[n] = s.policyTarget.clone();
                        } else {
                            double[] pt = new double[362];
                            for (int i = 0; i < BOARD_FEATURES; i++)
                                pt[SYMM_PERMS[t][i]] = s.policyTarget[i];
                            pt[361] = s.policyTarget[361]; // pass 不变
                            policies[n] = pt;
                        }
                        n++;
                    }
                }
                total += evaluator.trainMiniBatch(planes, aux, values, policies,
                        learningRate, config.l2, config.gradientClip, config.momentum);
                batches++;
            }
        }
        return batches == 0 ? 0 : total / batches;
    }

    /** 对棋盘应用 D4 对称变换，返回新棋盘 */
    private static GoPlayer[][] applySymmetry(GoPlayer[][] board, int[] perm) {
        int n = BOARD_SIZE;
        GoPlayer[][] result = new GoPlayer[n][n];
        for (int x = 0; x < n; x++) {
            for (int y = 0; y < n; y++) {
                int srcIdx = x * n + y;
                int dstIdx = perm[srcIdx];
                int dx = dstIdx / n, dy = dstIdx % n;
                result[dx][dy] = board[x][y];
            }
        }
        return result;
    }

    private static double clamp(double value) { return Math.max(-1.0, Math.min(1.0, value)); }

    private static final class GameSamples {
        final List<Sample> samples;
        GameSamples(List<Sample> samples) { this.samples = samples; }
    }
}