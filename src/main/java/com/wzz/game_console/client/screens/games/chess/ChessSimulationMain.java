package com.wzz.game_console.client.screens.games.chess;

import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;

/**
 * 中国象棋自对弈模拟器（支持多引擎对杀）。
 * <p>
 * 用法：gradle.bat runChessSim -PchessSimArgs="局数 并行度 深度 时间ms 红方引擎 黑方引擎 [皮卡鱼路径]"
 * <p>
 * 引擎：built-in（默认）| pikafish
 * 皮卡鱼路径默认：{@link ChessAI#DEFAULT_PIKAFISH_PATH}
 * <p>
 * 例：gradle.bat runChessSim -PchessSimArgs="10 4 3 200 built-in pikafish"
 */
public class ChessSimulationMain {

    private static final int MAX_MOVES = 300;

    public static final class GameResult {
        final int id;
        int totalMoves = 0;
        long elapsedMs = 0;
        String outcome = "UNKNOWN";
        String reason = "";
        int finalMaterial = 0; // 红方视角子力差

        GameResult(int id) { this.id = id; }
    }

    public static void main(String[] args) throws Exception {
        int totalGames  = args.length > 0 ? Integer.parseInt(args[0]) : 100;
        int parallelism = args.length > 1 ? Integer.parseInt(args[1]) : 8;
        int depth       = args.length > 2 ? Integer.parseInt(args[2]) : 3;
        long timeMs     = args.length > 3 ? Long.parseLong(args[3]) : 200L;
        String engRed   = args.length > 4 ? args[4] : "built-in";
        String engBlack = args.length > 5 ? args[5] : "built-in";
        String pkPath   = args.length > 6 ? args[6] : "";

        // 如果皮卡鱼路径传了，设置到系统属性（让 ChessAI.create 能读到）
        // 实际 ChessAI.create 的工厂方法读取 GameSettings —— 模拟环境可能获取不到。
        // 绕过：直接传 argv 给 playOneGame，由它创建对应引擎

        System.out.println("╔══════════════════════════════════════════════════════════╗");
        System.out.println("║ 中国象棋对杀模拟                                        ║");
        System.out.println("╠══════════════════════════════════════════════════════════╣");
        System.out.printf("║ 局数: %d  并行度: %d                              ║%n", totalGames, parallelism);
        System.out.printf("║ 搜索深度: %d  搜索时间: %d ms                     ║%n", depth, timeMs);
        System.out.printf("║ 红方: %s  黑方: %s                     ║%n", engRed, engBlack);
        if (!pkPath.isEmpty()) System.out.printf("║ 皮卡鱼路径: %s                        ║%n", pkPath);
        System.out.println("╚══════════════════════════════════════════════════════════╝");
        System.out.println();

        long startWall = System.currentTimeMillis();
        AtomicInteger completed = new AtomicInteger(0);
        AtomicInteger redWins   = new AtomicInteger(0);
        AtomicInteger blackWins = new AtomicInteger(0);
        AtomicInteger draws     = new AtomicInteger(0);
        AtomicInteger crashes   = new AtomicInteger(0);
        AtomicLong    totalMovesSum  = new AtomicLong(0);
        AtomicLong    totalTimeNs    = new AtomicLong(0);
        AtomicLong    totalMaterial  = new AtomicLong(0);

        ExecutorService executor = Executors.newFixedThreadPool(parallelism);
        List<Future<GameResult>> futures = new ArrayList<>(totalGames);

        for (int i = 0; i < totalGames; i++) {
            final int id = i + 1;
            final String fEngRed = engRed, fEngBlack = engBlack, fPkPath = pkPath;
            final long fTimeMs = timeMs;
            final int fDepth = depth;
            futures.add(executor.submit(() -> {
                long gStart = System.nanoTime();
                GameResult r = new GameResult(id);
                try {
                    ChessAI redAI = createEngine(fEngRed, fPkPath);
                    ChessAI blackAI = createEngine(fEngBlack, fPkPath);
                    redAI.setSearchTime(fTimeMs);  redAI.setMaxDepth(fDepth);
                    blackAI.setSearchTime(fTimeMs); blackAI.setMaxDepth(fDepth);
                    try {
                        playOneGame(redAI, blackAI, fDepth, fTimeMs, r);
                    } finally {
                        redAI.shutdown();
                        blackAI.shutdown();
                    }
                    r.elapsedMs = (System.nanoTime() - gStart) / 1_000_000;
                    totalMovesSum.addAndGet(r.totalMoves);
                    totalTimeNs.addAndGet(System.nanoTime() - gStart);
                    totalMaterial.addAndGet(r.finalMaterial);
                    return r;
                } catch (Exception e) {
                    r.outcome = "CRASH";
                    r.reason = e.getClass().getSimpleName() + ": " + e.getMessage();
                    r.elapsedMs = (System.nanoTime() - gStart) / 1_000_000;
                    return r;
                }
            }));
        }

        executor.shutdown();
        // ★ Bug修复：原版 f.get() 无 try/catch,任一 worker 抛 ExecutionException
        //   会终止整个仿真循环;且 shutdown 后无 awaitTermination,Pikafish 子进程
        //   可能未完全关闭就退出 main
        try {
            if (!executor.awaitTermination(120, TimeUnit.SECONDS)) {
                System.err.println("[警告] 仿真 120s 内未完成,强制 shutdownNow");
                executor.shutdownNow();
            }
        } catch (InterruptedException ie) {
            executor.shutdownNow();
            Thread.currentThread().interrupt();
        }
        for (Future<GameResult> f : futures) {
            GameResult r;
            try {
                r = f.get();
            } catch (Exception ex) {
                crashes.incrementAndGet();
                int done = completed.incrementAndGet();
                System.out.printf("  第%2d局: 异常崩溃 %s%n", done, ex.getClass().getSimpleName());
                continue;
            }
            switch (r.outcome) {
                case "RED_WIN"   -> redWins.incrementAndGet();
                case "BLACK_WIN" -> blackWins.incrementAndGet();
                case "DRAW"      -> draws.incrementAndGet();
                default          -> crashes.incrementAndGet();
            }
            int done = completed.incrementAndGet();
            System.out.printf("  第%2d局: %s（%s） 步数=%d  子力差=%+d  耗时=%.1fs%n",
                r.id, r.outcome, r.reason, r.totalMoves, r.finalMaterial, r.elapsedMs / 1000.0);
        }

        long wallMs = System.currentTimeMillis() - startWall;
        long total = totalGames;
        System.out.println();
        System.out.println("═══════════════════════════════════════════════════════════");
        System.out.println("                     对杀统计");
        System.out.println("═══════════════════════════════════════════════════════════");
        System.out.printf("  %s(红) vs %s(黑)  共 %d 局%n", engRed, engBlack, totalGames);
        System.out.printf("  红方胜: %d  (%.1f%%)%n", redWins.get(),   redWins.get()   * 100.0 / total);
        System.out.printf("  黑方胜: %d  (%.1f%%)%n", blackWins.get(), blackWins.get() * 100.0 / total);
        System.out.printf("  平局:   %d  (%.1f%%)%n", draws.get(),     draws.get()     * 100.0 / total);
        System.out.printf("  崩溃:   %d%n", crashes.get());
        System.out.printf("  平均步数: %.1f%n",  total > 0 ? (double) totalMovesSum.get() / total : 0);
        System.out.printf("  平均耗时: %.1f s/局%n", total > 0 ? (double) totalTimeNs.get() / total / 1_000_000_000 : 0);
        System.out.printf("  平均终局子力差: %+.1f（红方视角）%n", total > 0 ? (double) totalMaterial.get() / total : 0);
        System.out.printf("  总耗时: %.1f s (并行度 %d)%n", wallMs / 1000.0, parallelism);
        System.out.println("═══════════════════════════════════════════════════════════");
    }

    private static ChessAI createEngine(String type, String pkPath) {
        if ("pikafish".equalsIgnoreCase(type)) {
            String path = pkPath.isEmpty() ? ChessAI.DEFAULT_PIKAFISH_PATH : pkPath;
            try {
                PikafishChessAI pk = new PikafishChessAI(path);
                System.out.println("  [Pikafish 引擎启动成功: " + path + "]");
                return pk;
            } catch (Exception e) {
                System.out.println("  [Pikafish 启动失败，回退内置: " + e.getMessage() + "]");
                return new BuiltInChessAI();
            }
        }
        if ("classic".equalsIgnoreCase(type) || "built-in-classic".equalsIgnoreCase(type)) {
            return new BuiltInChessAI(false); // 关闭 LMR + Delta
        }
        // 默认内置引擎（含 LMR 增强）
        return new BuiltInChessAI();
    }

    static void playOneGame(ChessAI redAI, ChessAI blackAI, int depth, long timeMs, GameResult r) {
        int[][] board = initialBoard();
        boolean redTurn = true;
        int moves = 0;
        Set<Long> posHashes = new HashSet<>();

        while (moves < MAX_MOVES) {
            List<int[]> legal = ChessRules.legalMoves(board, redTurn);
            if (legal.isEmpty()) {
                if (ChessRules.inCheckOnBoard(board, redTurn)) {
                    r.outcome = redTurn ? "BLACK_WIN" : "RED_WIN";
                    r.reason = redTurn ? "红方被将死" : "黑方被将死";
                } else {
                    r.outcome = redTurn ? "BLACK_WIN" : "RED_WIN";
                    r.reason = redTurn ? "红方困毙" : "黑方困毙";
                }
                r.totalMoves = moves;
                r.finalMaterial = countMaterial(board);
                return;
            }

            long hash = boardHash(board, redTurn);
            if (posHashes.contains(hash)) {
                r.outcome = "DRAW";
                r.reason = "局面重复";
                r.totalMoves = moves;
                r.finalMaterial = countMaterial(board);
                return;
            }
            posHashes.add(hash);

            ChessAI ai = redTurn ? redAI : blackAI;
            int[][] boardCopy = deepCopy(board);
            int[] mv = ai.getBestMove(boardCopy, redTurn);
            if (mv == null) {
                r.outcome = "DRAW";
                r.reason = "引擎返回 null";
                r.totalMoves = moves;
                r.finalMaterial = countMaterial(board);
                return;
            }

            board[mv[2]][mv[3]] = board[mv[0]][mv[1]];
            board[mv[0]][mv[1]] = 0;
            moves++;
            redTurn = !redTurn;
        }

        r.outcome = "DRAW";
        r.reason = "超过 " + MAX_MOVES + " 步上限";
        r.totalMoves = moves;
        r.finalMaterial = countMaterial(board);
    }

    private static int[][] initialBoard() {
        int[][] b = new int[ChessRules.COLS][ChessRules.ROWS];
        int[] back = {ChessRules.CHARIOT, ChessRules.HORSE, ChessRules.ELEPHANT,
                ChessRules.ADVISOR, ChessRules.GENERAL, ChessRules.ADVISOR,
                ChessRules.ELEPHANT, ChessRules.HORSE, ChessRules.CHARIOT};
        for (int c = 0; c < 9; c++) b[c][0] = -back[c];
        b[1][2] = -ChessRules.CANNON; b[7][2] = -ChessRules.CANNON;
        for (int c = 0; c < 9; c += 2) b[c][3] = -ChessRules.SOLDIER;
        for (int c = 0; c < 9; c++) b[c][9] = back[c];
        b[1][7] = ChessRules.CANNON; b[7][7] = ChessRules.CANNON;
        for (int c = 0; c < 9; c += 2) b[c][6] = ChessRules.SOLDIER;
        return b;
    }

    private static int countMaterial(int[][] b) {
        int score = 0;
        for (int c = 0; c < 9; c++) for (int r = 0; r < 10; r++) {
            int p = b[c][r];
            if (p == 0) continue;
            int val = ChessRules.PIECE_VAL[Math.abs(p)];
            score += (p > 0) ? val : -val;
        }
        return score;
    }

    private static int[][] deepCopy(int[][] src) {
        int[][] d = new int[ChessRules.COLS][ChessRules.ROWS];
        for (int i = 0; i < ChessRules.COLS; i++) d[i] = src[i].clone();
        return d;
    }

    private static long boardHash(int[][] board, boolean redTurn) {
        long h = 1;
        for (int c = 0; c < 9; c++) for (int r = 0; r < 10; r++) {
            int p = board[c][r];
            long v = p == 0 ? 0 : (p > 0 ? p : -p + 20L);
            h = 31 * h + v; h ^= h >>> 33;
        }
        if (redTurn) h ^= 0xFEEDBACL;
        return h;
    }
}