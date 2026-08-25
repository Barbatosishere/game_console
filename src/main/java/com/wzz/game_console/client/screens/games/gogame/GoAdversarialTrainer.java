package com.wzz.game_console.client.screens.games.gogame;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.io.IOException;

/**
 * 对抗训练器：己方 MCTS AI vs 外部 KataGo 引擎。
 * <p>
 * 每局以随机先后手对弈，收集己方 AI 每步的局面样本，
 * 以终局胜负作为标签，训练神经网络。
 */
public final class GoAdversarialTrainer {
    public static final class Config {
        public int searchTimeMillis = 300;
        public int maxIterations = 500;
        public int maxMoves = 300;
        public int batchSize = 128;
        public double l2 = 1.0e-5;
        public double gradientClip = 5.0;
        public double momentum = 0.9;
        public int maxReplaySamples = 20_000;
        /** KataGo 可执行文件路径 */
        public String katagoPath = "";
        /** KataGo 模型文件路径（可选，不填则用 KataGo 默认） */
        public String katagoModel = "";
        /** KataGo GTP 配置文件路径（可选） */
        public String katagoConfig = "default_gtp.cfg";
    }

    public static final class Result {
        public final int games;
        public final int samples;
        public final int completedGames;
        public final double meanLoss;
        public final NeuralEvaluator evaluator;
        public final int ourWins;

        private Result(int games, int samples, int completedGames, double meanLoss,
                       NeuralEvaluator evaluator, int ourWins) {
            this.games = games;
            this.samples = samples;
            this.completedGames = completedGames;
            this.meanLoss = meanLoss;
            this.evaluator = evaluator;
            this.ourWins = ourWins;
        }
    }

    private static final class Sample {
        final GoPlayer[][] board;
        final GoPlayer player;
        final int[] lastMove;           // 上一手位置（plane 3，与推理对齐）
        final double[] policyTarget;
        double valueTarget;
        Sample(GoPlayer[][] board, GoPlayer player, int[] lastMove, double[] policyTarget) {
            this.board = board;
            this.player = player;
            this.lastMove = lastMove;
            this.policyTarget = policyTarget;
        }
    }

    private final Config config;
    private NeuralEvaluator evaluator;
    private final List<Sample> replayBuffer = new ArrayList<>();

    public GoAdversarialTrainer(Config config, NeuralEvaluator evaluator) {
        this.config = config == null ? new Config() : config;
        this.evaluator = evaluator == null ? new NeuralEvaluator() : evaluator;
    }

    public NeuralEvaluator getEvaluator() { return evaluator; }
    public int getReplayBufferSize() { return replayBuffer.size(); }
    public void clearReplayBuffer() { replayBuffer.clear(); }

    /**
     * 运行一代对抗训练。
     * @param games      对局数
     * @param parallelism 并行数
     * @param epochs      训练轮次
     * @param learningRate 学习率
     * @param seed        随机种子
     * @return 训练结果
     */
    public Result runGeneration(int games, int parallelism, int epochs, double learningRate, long seed) {
        if (games < 0 || epochs < 0 || learningRate <= 0)
            throw new IllegalArgumentException("Invalid generation parameters");
        if (games == 0) return new Result(0, 0, 0, 0, evaluator, 0);

        int workers = Math.max(1, Math.min(parallelism, games));
        final NeuralEvaluator.ModelWeights snapshot = evaluator.snapshot();
        ExecutorService pool = Executors.newFixedThreadPool(workers);
        List<Future<GameResult>> futures = new ArrayList<>();

        try {
            for (int i = 0; i < games; i++) {
                final int gameIndex = i;
                futures.add(pool.submit(() ->
                    playAdversarialGame(snapshot, seed + 0x9E3779B97F4A7C15L * gameIndex)));
            }

            List<Sample> newSamples = new ArrayList<>();
            int completed = 0;
            int ourWins = 0;
            for (Future<GameResult> future : futures) {
                try {
                    GameResult gr = future.get();
                    newSamples.addAll(gr.samples);
                    completed++;
                    if (gr.ourWin) ourWins++;
                } catch (Exception e) {
                    System.err.println("[Adversarial] game failed: " + e.getMessage());
                }
            }

            replayBuffer.addAll(newSamples);
            trimReplayBuffer();
            double loss = train(replayBuffer, epochs, learningRate, seed ^ 0xD1B54A32D192ED03L);
            return new Result(games, newSamples.size(), completed, loss, evaluator, ourWins);
        } finally {
            pool.shutdownNow();
            // ★ Bug修复：等待 worker 释放 native 资源,见 GoSelfPlayTrainer 同改
            try {
                if (!pool.awaitTermination(5, TimeUnit.SECONDS)) {
                    System.err.println("[对抗训练] 训练线程池 5s 内未关闭,放弃等待");
                }
            } catch (InterruptedException ie) {
                Thread.currentThread().interrupt();
            }
        }
    }

    private void trimReplayBuffer() {
        int max = Math.max(1, config.maxReplaySamples);
        while (replayBuffer.size() > max) replayBuffer.remove(0);
    }

    private static final class GameResult {
        final List<Sample> samples;
        final boolean ourWin;
        GameResult(List<Sample> samples, boolean ourWin) {
            this.samples = samples;
            this.ourWin = ourWin;
        }
    }

    /**
     * 一局对抗：MCTS AI vs KataGo，随机先后手。
     */
    private GameResult playAdversarialGame(NeuralEvaluator.ModelWeights model, long seed) {
        List<Sample> samples = new ArrayList<>();
        Random rnd = new Random(seed);
        boolean ourIsBlack = rnd.nextBoolean();

        // 创建己方 AI
        MCTSGoAI ourAI = new MCTSGoAI(config.searchTimeMillis, config.maxIterations, 1, model);
        ourAI.setRandomSeed(seed ^ 0x12345678);

        // 创建 KataGo
        KataGoGoAI kataGo = null;
        try {
            String katagoPath = config.katagoPath;
            if (katagoPath.isEmpty()) {
                System.err.println("[Adversarial] 未配置 katagoPath，跳过");
                return new GameResult(samples, false);
            }
            // 检查文件是否存在
            java.io.File exeFile = new java.io.File(katagoPath);
            if (!exeFile.exists()) {
                System.err.println("[Adversarial] KataGo 不存在: " + katagoPath);
                return new GameResult(samples, false);
            }

            // 构建命令行参数
            java.util.List<String> cmd = new java.util.ArrayList<>();
            cmd.add(exeFile.getAbsolutePath());
            cmd.add("gtp");
            if (!config.katagoModel.isEmpty()) {
                cmd.add("-model");
                cmd.add(new java.io.File(config.katagoModel).getAbsolutePath());
            }
            // 配置文件路径 — 相对于 KataGo 目录或绝对路径
            String configPath = config.katagoConfig;
            if (!configPath.isEmpty()) {
                java.io.File cfgFile = new java.io.File(configPath);
                if (!cfgFile.isAbsolute()) {
                    // 相对于 KataGo 可执行文件目录
                    cfgFile = new java.io.File(exeFile.getParentFile(), configPath);
                }
                cmd.add("-config");
                cmd.add(cfgFile.getAbsolutePath());
            }

            ProcessBuilder pb = new ProcessBuilder(cmd);
            pb.directory(exeFile.getParentFile()); // 设置工作目录为 KataGo 目录（找到 DLL 和调优缓存）
            pb.redirectErrorStream(true);
            Process process = pb.start();
            java.io.BufferedWriter writer = new java.io.BufferedWriter(
                new java.io.OutputStreamWriter(process.getOutputStream(), java.nio.charset.StandardCharsets.UTF_8));
            java.io.BufferedReader reader = new java.io.BufferedReader(
                new java.io.InputStreamReader(process.getInputStream(), java.nio.charset.StandardCharsets.UTF_8));

            // 初始化 GTP
            sendGTP(writer, reader, "boardsize 19");
            sendGTP(writer, reader, "komi 7.5");
            sendGTP(writer, reader, "clear_board");

            // 对弈
            GoGame game = GoGame.rulesOnly();

            try {
                int moves = 0;
                int[] lastMoveOnBoard = null; // 上一手（plane 3），追踪双方落子
                while (!game.isGameOver() && moves < Math.max(1, config.maxMoves)) {
                    GoPlayer currentPlayer = game.getCurrentPlayer();
                    boolean ourTurn = (currentPlayer == GoPlayer.BLACK) == ourIsBlack;

                    if (ourTurn) {
                        // 己方 AI 走棋
                        GoPlayer[][] boardCopy = game.getBoardCopy();
                        int[] move = ourAI.getBestMove(game);
                        double[] policyTarget = ourAI.getVisitDistribution();
                        samples.add(new Sample(boardCopy, currentPlayer, lastMoveOnBoard, policyTarget));

                        // 实际落子的坐标（fallback 时可能与 AI 建议不一致，必须用实际落子同步 KataGo）
                        int[] actuallyPlayed = null;
                        boolean played = move != null && move.length >= 2 && game.placeStone(move[0], move[1]);
                        if (played) {
                            actuallyPlayed = new int[]{move[0], move[1]};
                        } else {
                            int fx = -1, fy = -1;
                            for (int x = 0; x < game.getBoardSize() && !played; x++)
                                for (int y = 0; y < game.getBoardSize() && !played; y++)
                                    if (game.placeStone(x, y)) { played = true; fx = x; fy = y; }
                            if (played) actuallyPlayed = new int[]{fx, fy};
                        }
                        if (!played) {
                            game.pass();
                            lastMoveOnBoard = null;
                        } else {
                            lastMoveOnBoard = actuallyPlayed;
                        }

                        // 同步到 KataGo（用实际落子，避免 fallback 时两盘棋分叉）
                        if (actuallyPlayed != null) {
                            String color = ourIsBlack ? "black" : "white";
                            sendGTP(writer, reader, "play " + color + " " + formatMove(actuallyPlayed[0], actuallyPlayed[1]));
                        } else {
                            String color = ourIsBlack ? "black" : "white";
                            sendGTP(writer, reader, "play " + color + " pass");
                        }
                    } else {
                        // KataGo 走棋
                        String color = ourIsBlack ? "white" : "black";
                        String response = sendGTP(writer, reader, "genmove " + color);
                        int[] move = parseGTPMove(response);

                        boolean played = false;
                        if (move != null) {
                            played = game.placeStone(move[0], move[1]);
                            lastMoveOnBoard = new int[]{move[0], move[1]};
                        }
                        if (!played) {
                            game.pass();
                            lastMoveOnBoard = null;
                        }
                    }
                    moves++;
                }
                if (!game.isGameOver()) game.pass();

                // 计算胜负
                double margin = game.getScoreMargin(GoPlayer.BLACK);
                boolean ourWin = (ourIsBlack && margin > 0) || (!ourIsBlack && margin < 0);

                for (Sample s : samples) {
                    s.valueTarget = clamp((s.player == GoPlayer.BLACK ? margin : -margin) / 100.0);
                }

                // 关闭 KataGo
                sendGTP(writer, reader, "quit");
                writer.close();
                reader.close();
                process.waitFor(2, java.util.concurrent.TimeUnit.SECONDS);
                process.destroyForcibly();

                return new GameResult(samples, ourWin);
            } finally {
                game.close();
                ourAI.shutdown();
            }
        } catch (Exception e) {
            System.err.println("[Adversarial] 对局异常: " + e.getMessage());
            if (kataGo != null) try { kataGo.shutdown(); } catch (Exception ignored) {}
            return new GameResult(samples, false);
        }
    }

    private String sendGTP(java.io.Writer writer, java.io.Reader reader, String cmd) throws Exception {
        writer.write(cmd + "\n");
        writer.flush();
        StringBuilder sb = new StringBuilder();
        java.io.BufferedReader br = (java.io.BufferedReader) reader;
        String line;
        int timeout = 600000; // 600 秒 = 10 分钟，充分覆盖并发 GPU 竞争
        long deadline = System.currentTimeMillis() + timeout;
        while (System.currentTimeMillis() < deadline) {
            while (br.ready()) {
                line = br.readLine();
                if (line == null) throw new IOException("KataGo 进程已退出");
                sb.append(line).append("\n");
                if (line.startsWith("=") || line.startsWith("?")) {
                    return sb.toString();
                }
            }
            Thread.sleep(50);
        }
        throw new java.util.concurrent.TimeoutException("GTP 命令超时: " + cmd);
    }

    /** 读取进程直到收到 GTP 响应（用于处理进程启动时的调优输出） */
    private String waitForGTPReady(java.io.BufferedReader br, int timeoutMs) throws Exception {
        long deadline = System.currentTimeMillis() + timeoutMs;
        StringBuilder sb = new StringBuilder();
        while (System.currentTimeMillis() < deadline) {
            while (br.ready()) {
                String line = br.readLine();
                if (line == null) return sb.toString();
                sb.append(line).append("\n");
                if (line.startsWith("=") || line.startsWith("?")) {
                    return sb.toString();
                }
            }
            Thread.sleep(10);
        }
        return sb.toString();
    }

    private int[] parseGTPMove(String response) {
        if (response == null || response.isEmpty()) return null;
        String line = response.trim();
        if (!line.startsWith("=")) return null;
        String coord = line.substring(1).trim().toLowerCase();
        if ("pass".equals(coord) || "resign".equals(coord)) return null;
        try {
            char colChar = coord.charAt(0);
            int row = Integer.parseInt(coord.substring(1));
            int col = colChar - 'a';
            if (col >= 0 && col < 19 && row - 1 >= 0 && row - 1 < 19) {
                return new int[]{col, row - 1};
            }
        } catch (NumberFormatException ignored) {}
        return null;
    }

    private String formatMove(int x, int y) {
        return String.valueOf((char) ('a' + x)) + (y + 1);
    }

    // ── 训练（与 GoSelfPlayTrainer 相同） ──────────────────────────

    private static final int BOARD_SIZE = 19;
    private static final int BOARD_FEATURES = BOARD_SIZE * BOARD_SIZE;
    private static final int AUX_FEATURES = 24;
    private static final int[][] SYMM_PERMS = buildSymmetryPerms();

    private static int[][] buildSymmetryPerms() {
        int n = BOARD_SIZE;
        int[][] perms = new int[8][BOARD_FEATURES];
        for (int x = 0; x < n; x++) for (int y = 0; y < n; y++) {
            int idx = x * n + y;
            perms[0][idx] = idx;
            perms[1][idx] = y * n + (n - 1 - x);
            perms[2][idx] = (n - 1 - x) * n + (n - 1 - y);
            perms[3][idx] = (n - 1 - y) * n + x;
            perms[4][idx] = (n - 1 - x) * n + y;
            perms[5][idx] = x * n + (n - 1 - y);
            perms[6][idx] = y * n + x;
            perms[7][idx] = (n - 1 - y) * n + (n - 1 - x);
        }
        return perms;
    }

    private double train(List<Sample> samples, int epochs, double learningRate, long seed) {
        if (samples.isEmpty() || epochs == 0) return 0;
        Random random = new Random(seed);
        double total = 0;
        int batches = 0;
        int symCount = SYMM_PERMS.length;

        for (int epoch = 0; epoch < epochs; epoch++) {
            java.util.Collections.shuffle(samples, random);
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
                    for (int t = 0; t < symCount; t++) {
                        GoPlayer[][] tb = applySymmetry(s.board, SYMM_PERMS[t]);
                        // 上一手随对称变换（plane 3 与推理对齐）
                        int[] tLastMove = null;
                        if (s.lastMove != null && s.lastMove.length >= 2) {
                            int dstIdx = SYMM_PERMS[t][s.lastMove[0] * BOARD_SIZE + s.lastMove[1]];
                            tLastMove = new int[]{dstIdx / BOARD_SIZE, dstIdx % BOARD_SIZE};
                        }
                        planes[n] = evaluator.buildInputPlanes(tb, s.player, tLastMove);
                        aux[n] = evaluator.extractAuxFeatures(tb, s.player);
                        values[n] = s.valueTarget;
                        if (t == 0) {
                            policies[n] = s.policyTarget.clone();
                        } else {
                            double[] pt = new double[362];
                            for (int i = 0; i < BOARD_FEATURES; i++)
                                pt[SYMM_PERMS[t][i]] = s.policyTarget[i];
                            pt[361] = s.policyTarget[361];
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

    private static GoPlayer[][] applySymmetry(GoPlayer[][] board, int[] perm) {
        int n = BOARD_SIZE;
        GoPlayer[][] result = new GoPlayer[n][n];
        for (int x = 0; x < n; x++) for (int y = 0; y < n; y++) {
            int dstIdx = perm[x * n + y];
            result[dstIdx / n][dstIdx % n] = board[x][y];
        }
        return result;
    }

    private static double clamp(double value) { return Math.max(-1.0, Math.min(1.0, value)); }
}