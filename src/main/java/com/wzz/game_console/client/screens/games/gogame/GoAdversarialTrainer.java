package com.wzz.game_console.client.screens.games.gogame;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicReference;
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
    /** Serializes generation, replay-buffer, and evaluator mutation per trainer instance. */
    private final Object generationLock = new Object();

    public GoAdversarialTrainer(Config config, NeuralEvaluator evaluator) {
        this.config = config == null ? new Config() : config;
        this.evaluator = evaluator == null ? new NeuralEvaluator() : evaluator;
    }

    public NeuralEvaluator getEvaluator() { return evaluator; }
    public int getReplayBufferSize() {
        synchronized (generationLock) { return replayBuffer.size(); }
    }
    public void clearReplayBuffer() {
        synchronized (generationLock) { replayBuffer.clear(); }
    }

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
        synchronized (generationLock) {
            return runGenerationLocked(games, parallelism, epochs, learningRate, seed);
        }
    }

    private Result runGenerationLocked(int games, int parallelism, int epochs, double learningRate, long seed) {
        if (games == 0 || Thread.currentThread().isInterrupted()) return new Result(games, 0, 0, 0, evaluator, 0);

        int workers = Math.max(1, Math.min(parallelism <= 0 ? 1 : parallelism, games));
        final NeuralEvaluator.ModelWeights snapshot = evaluator.snapshot();
        ExecutorService pool = Executors.newFixedThreadPool(workers);
        List<Future<GameResult>> futures = new ArrayList<>(workers);
        try {
            List<Sample> newSamples = new ArrayList<>();
            int nextGame = 0;
            for (; nextGame < workers; nextGame++) {
                final int gameIndex = nextGame;
                futures.add(pool.submit(() ->
                    playAdversarialGame(snapshot, seed + 0x9E3779B97F4A7C15L * gameIndex)));
            }
            int completed = 0;
            int ourWins = 0;
            // 单局上限：避免外部引擎不响应时整代无限等待。
            long perGameTimeoutSec = (long) (Math.max(1, config.maxMoves) * 11L * 60 * 1.5);
            while (!futures.isEmpty()) {
                Future<GameResult> future = futures.remove(0);
                try {
                    GameResult gr = future.get(perGameTimeoutSec, TimeUnit.SECONDS);
                    if (gr.completed) {
                        appendReplaySamples(newSamples, gr.samples);
                        completed++;
                        if (gr.ourWin) ourWins++;
                    } else {
                        System.err.println("[Adversarial] game did not complete; discarding samples");
                    }
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    future.cancel(true);
                    for (Future<GameResult> pending : futures) pending.cancel(true);
                    return new Result(games, 0, 0, 0, evaluator, 0);
                } catch (java.util.concurrent.CancellationException ce) {
                    for (Future<GameResult> pending : futures) pending.cancel(true);
                    return new Result(games, 0, 0, 0, evaluator, 0);
                } catch (java.util.concurrent.ExecutionException ee) {
                    Throwable cause = ee.getCause();
                    if (cause instanceof java.util.concurrent.CancellationException
                            || cause instanceof InterruptedException) {
                        if (cause instanceof InterruptedException) Thread.currentThread().interrupt();
                        for (Future<GameResult> pending : futures) pending.cancel(true);
                        return new Result(games, 0, 0, 0, evaluator, 0);
                    }
                    System.err.println("[Adversarial] game failed: " + cause);
                } catch (java.util.concurrent.TimeoutException te) {
                    future.cancel(true);
                    System.err.println("[Adversarial] 对局超时（" + perGameTimeoutSec + "s），已取消");
                }
                if (nextGame < games) {
                    final int gameIndex = nextGame++;
                    futures.add(pool.submit(() ->
                        playAdversarialGame(snapshot, seed + 0x9E3779B97F4A7C15L * gameIndex)));
                }
            }

            if (Thread.currentThread().isInterrupted()) return new Result(games, 0, 0, 0, evaluator, 0);
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

    private void appendReplaySamples(List<Sample> samples, List<Sample> gameSamples) {
        samples.addAll(gameSamples);
        int max = Math.max(1, config.maxReplaySamples);
        if (samples.size() > max) {
            samples.subList(0, samples.size() - max).clear();
        }
    }

    private void trimReplayBuffer() {
        int max = Math.max(1, config.maxReplaySamples);
        int overflow = replayBuffer.size() - max;
        if (overflow > 0) {
            // ★ 修复：原版 while(remove(0)) 逐条前移，O(n²)；subList 批量清除一次完成
            replayBuffer.subList(0, overflow).clear();
        }
    }

    private static final class GameResult {
        final List<Sample> samples;
        final boolean ourWin;
        final boolean completed;
        GameResult(List<Sample> samples, boolean ourWin, boolean completed) {
            this.samples = samples;
            this.ourWin = ourWin;
            this.completed = completed;
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

        Process process = null;
        try {
            String katagoPath = config.katagoPath;
            if (katagoPath.isEmpty()) {
                System.err.println("[Adversarial] 未配置 katagoPath，跳过");
                return new GameResult(java.util.Collections.emptyList(), false, false);
            }
            // 检查文件是否存在
            java.io.File exeFile = new java.io.File(katagoPath);
            if (!exeFile.exists()) {
                System.err.println("[Adversarial] KataGo 不存在: " + katagoPath);
                return new GameResult(java.util.Collections.emptyList(), false, false);
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
            // ★ 修复：不再 redirectErrorStream——stdout 必须保持纯 GTP 流，
            //   引擎日志混入 stdout 会被当作响应解析，导致 GTP 解析错位分叉；
            //   stderr 也不能完全不消费——管道缓冲写满会挂死引擎，继承到本进程 stderr
            pb.redirectError(ProcessBuilder.Redirect.INHERIT);
            process = pb.start();
            java.io.BufferedWriter writer = new java.io.BufferedWriter(
                new java.io.OutputStreamWriter(process.getOutputStream(), java.nio.charset.StandardCharsets.UTF_8));
            java.io.BufferedReader reader = new java.io.BufferedReader(
                new java.io.InputStreamReader(process.getInputStream(), java.nio.charset.StandardCharsets.UTF_8));

            // 初始化 GTP，使用与训练标签相同的固定贴目快照。
            double roundKomi = GoGame.getConfiguredKomi();
            sendGTP(process, writer, reader, "boardsize 19");
            sendGTP(process, writer, reader, "komi " + GoScoringProtocol.formatKomi(roundKomi));
            sendGTP(process, writer, reader, "clear_board");

            // 对弈
            GoGame game = GoGame.rulesOnly();
            boolean kataResigned = false;

            try {
                int moves = 0;
                int[] lastMoveOnBoard = null; // 上一手（plane 3），追踪双方落子
                while (!game.isGameOver() && moves < Math.max(1, config.maxMoves)) {
                    if (Thread.currentThread().isInterrupted()) {
                        throw new java.util.concurrent.CancellationException("adversarial generation cancelled");
                    }
                    GoPlayer currentPlayer = game.getCurrentPlayer();
                    boolean ourTurn = (currentPlayer == GoPlayer.BLACK) == ourIsBlack;

                    if (ourTurn) {
                        // 己方 AI 走棋
                        GoPlayer[][] boardCopy = game.getBoardCopy();
                        int[] previousLastMove = lastMoveOnBoard == null ? null : lastMoveOnBoard.clone();
                        int[] move = ourAI.getBestMove(game);
                        if (Thread.currentThread().isInterrupted()) {
                            throw new java.util.concurrent.CancellationException("adversarial generation cancelled");
                        }
                        double[] policyTarget = ourAI.getVisitDistribution();
                        GoTrainingMove.Applied applied = GoTrainingMove.apply(game, move, policyTarget);
                        int[] actuallyPlayed = applied.coordinates();
                        lastMoveOnBoard = actuallyPlayed;
                        policyTarget = applied.policy();
                        samples.add(new Sample(boardCopy, currentPlayer, previousLastMove, policyTarget));

                        // 同步到 KataGo（用实际落子，避免 fallback 时两盘棋分叉）
                        if (actuallyPlayed != null) {
                            String color = ourIsBlack ? "black" : "white";
                            sendGTP(process, writer, reader, "play " + color + " " + formatMove(actuallyPlayed[0], actuallyPlayed[1]));
                        } else {
                            String color = ourIsBlack ? "black" : "white";
                            sendGTP(process, writer, reader, "play " + color + " pass");
                        }
                    } else {
                        // KataGo 走棋
                        String color = ourIsBlack ? "white" : "black";
                        String response = sendGTP(process, writer, reader, "genmove " + color);
                        // resign ends the game; pass is a real pass. Any other malformed
                        // or illegal response fails the game instead of desynchronizing boards.
                        GoAI.MoveResult action = parseGTPAction(response);
                        if (action.type() == GoAI.MoveType.RESIGN) {
                            kataResigned = true;
                            break;
                        }
                        if (action.type() == GoAI.MoveType.PASS) {
                            game.pass();
                            lastMoveOnBoard = null;
                        } else if (action.type() == GoAI.MoveType.MOVE && game.placeStone(action.x(), action.y())) {
                            lastMoveOnBoard = action.coordinates();
                        } else {
                            throw new IOException("Invalid KataGo genmove response: " + response);
                        }
                    }
                    moves++;
                }
                if (!game.isGameOver() && !kataResigned) {
                    // maxMoves is a truncation guard, not an implicit second pass.
                    return new GameResult(java.util.Collections.emptyList(), false, false);
                }

                // 计算胜负：认输直接记确定值 ±1（残盘点目对中盘认输无意义）
                double margin = game.getScoreMargin(GoPlayer.BLACK, java.util.Collections.emptySet(), roundKomi);
                boolean ourWin = kataResigned
                        ? true
                        : (ourIsBlack && margin > 0) || (!ourIsBlack && margin < 0);
                // ★ 修复：resign 是"黑方（KataGo 或我方）认输"——我方执白时黑（对手）实际输了，
                //   blackValue 应为 -1 而非 +1，否则训练标签方向完全颠倒
                double blackValue = kataResigned ? (ourIsBlack ? 1.0 : -1.0) : clamp(margin / 100.0);

                for (Sample s : samples) {
                    s.valueTarget = (s.player == GoPlayer.BLACK) ? blackValue : -blackValue;
                }

                // 关闭 KataGo（优先优雅退出，外层 finally 兜底强杀，覆盖所有异常路径）
                sendGTP(process, writer, reader, "quit");
                writer.close();
                reader.close();
                process.waitFor(2, java.util.concurrent.TimeUnit.SECONDS);

                return new GameResult(samples, ourWin, true);
            } finally {
                game.close();
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new java.util.concurrent.CancellationException("adversarial generation cancelled");
        } catch (java.util.concurrent.CancellationException e) {
            throw e;
        } catch (Exception e) {
            System.err.println("[Adversarial] 对局异常: " + e.getMessage());
            return new GameResult(java.util.Collections.emptyList(), false, false);
        } finally {
            // Every early-return and initialization failure must release the private evaluator.
            ourAI.shutdown();
            // ★ Bug修复：此前 kataGo 变量从未真正赋值，异常路径下真正持有子进程的 process
            // 完全没被清理——GTP 通信异常/超时会让 KataGo 残留为僵尸进程占用显存。
            // 无论正常返回还是任意异常路径，这里保证子进程被强杀。
            if (process != null && process.isAlive()) {
                process.destroyForcibly();
            }
        }
    }

    private String sendGTP(java.io.Writer writer, java.io.Reader reader, String cmd) throws Exception {
        return sendGTP(null, writer, reader, cmd, 600_000L);
    }

    private String sendGTP(Process process, java.io.Writer writer, java.io.Reader reader,
                           String cmd) throws Exception {
        return sendGTP(process, writer, reader, cmd, 600_000L);
    }

    /** Test hook without a process handle: timeout abandons the reader instead of closing the stream. */
    private String sendGTP(java.io.Writer writer, java.io.Reader reader, String cmd,
                           long timeoutMillis) throws Exception {
        return sendGTP(null, writer, reader, cmd, timeoutMillis);
    }

    /**
     * 发送 GTP 命令并等待响应终止行（"=..."/"?..."）。
     * <p>
     * 超时/中断路径绝不在主线程 close() reader：reader 线程可能仍持有 BufferedReader
     * 内部锁阻塞在管道读上，同步 close 会永久死锁（Windows 实测复现）。此处改为
     * destroyForcibly 引擎使管道 EOF，reader 线程自行退出；进程清理由对局 finally 兜底。
     *
     * @param process 所属引擎进程；仅用于超时/中断时强制解除管道阻塞，可为 null（测试钩子）
     */
    private String sendGTP(Process process, java.io.Writer writer, java.io.Reader reader, String cmd,
                           long timeoutMillis) throws Exception {
        writer.write(cmd + "\n");
        writer.flush();
        java.io.BufferedReader br = (java.io.BufferedReader) reader;
        long timeoutNanos = TimeUnit.MILLISECONDS.toNanos(Math.max(1L, timeoutMillis));
        CountDownLatch done = new CountDownLatch(1);
        AtomicReference<String> responseRef = new AtomicReference<>();
        AtomicReference<Throwable> errorRef = new AtomicReference<>();
        Thread readerThread = new Thread(() -> {
            StringBuilder noise = new StringBuilder();
            try {
                String line;
                while ((line = br.readLine()) != null) {
                    String trimmed = line.stripLeading();
                    if (trimmed.startsWith("=") || trimmed.startsWith("?")) {
                        responseRef.set(trimmed);
                        return;
                    }
                    // 响应前的引擎日志/横幅行不属于 GTP 响应，丢弃
                    noise.append(line).append('\n');
                }
                // EOF：若此前还有未终止的内容（半行响应），不得当作成功返回
                if (noise.length() > 0) {
                    errorRef.set(new IOException("KataGo 进程已退出（响应不完整）"));
                }
                // 注意：readLine 会把"无换行即 EOF"的尾行当作完整行返回（如 "=1"），
                // 这类响应会作为成功文本交由 parseGTPAction 判定；裸 id/非法动作会被
                // 判为 ERROR 使对局安全失败，且引擎已死时下一条命令必然 EOF。
            } catch (Throwable t) {
                errorRef.set(t);
            } finally {
                done.countDown();
            }
        }, "gtp-response-reader");
        readerThread.setDaemon(true);
        readerThread.start();
        try {
            long deadline = System.nanoTime() + timeoutNanos;
            while (!done.await(100L, TimeUnit.MILLISECONDS)) {
                if (System.nanoTime() >= deadline) {
                    throw new java.util.concurrent.TimeoutException("GTP 命令超时: " + cmd);
                }
            }
        } catch (java.util.concurrent.TimeoutException | InterruptedException e) {
            if (process != null) process.destroyForcibly();
            throw e;
        }
        Throwable error = errorRef.get();
        if (error instanceof IOException io) throw io;
        if (error instanceof RuntimeException re) throw re;
        if (error != null) throw new IOException("KataGo GTP read failed", error);
        String response = responseRef.get();
        if (response == null) throw new IOException("KataGo 进程已退出");
        if (response.startsWith("?")) {
            throw new IOException("KataGo rejected GTP command " + cmd + ": " + response);
        }
        return response + "\n";
    }

    static GoAI.MoveResult parseGTPAction(String response) {
        if (response == null) return GoAI.MoveResult.error();
        String line = response.trim();
        if (!line.startsWith("=")) return GoAI.MoveResult.error();
        String body = line.substring(1).trim();
        int idEnd = 0;
        while (idEnd < body.length() && Character.isDigit(body.charAt(idEnd))) idEnd++;
        if (idEnd > 0) {
            if (idEnd == body.length() || !Character.isWhitespace(body.charAt(idEnd))) {
                return GoAI.MoveResult.error();
            }
            body = body.substring(idEnd).trim();
        }
        if (body.isEmpty() || body.chars().anyMatch(Character::isWhitespace)) {
            return GoAI.MoveResult.error();
        }
        return KataGoGoAI.parseMoveResult(body);
    }

    private String formatMove(int x, int y) {
        int gtpCol = x + (x >= 8 ? 1 : 0);
        return String.valueOf((char) ('a' + gtpCol)) + (y + 1);
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
                if (Thread.currentThread().isInterrupted()) return batches == 0 ? 0 : total / batches;
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
                if (Thread.currentThread().isInterrupted()) return batches == 0 ? 0 : total / batches;
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