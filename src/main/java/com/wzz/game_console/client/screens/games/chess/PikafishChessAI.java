package com.wzz.game_console.client.screens.games.chess;

import com.wzz.game_console.util.GameSettings;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.File;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * 外部 Pikafish（皮卡鱼）引擎封装，通过 UCI 协议通信。
 * <p>
 * Pikafish 是当前最强的开源中国象棋引擎，需要用户单独安装。
 * 配置 {@code data/game_settings.json} 中的 {@code chess.engine = "pikafish"} 启用：
 * <pre>
 * {
 *   "chess": {
 *     "engine": "pikafish",
 *     "pikafishPath": "E:/皮卡鱼 20260131/pikafish-avx2.exe",
 *     "pikafishThreads": 1,
 *     "pikafishMovetime": 2000
 *   }
 * }
 * </pre>
 * <p>
 * 若可执行文件不存在或初始化失败，将由 {@link ChessAI#create(String)} 自动回退到内置引擎。
 */
public class PikafishChessAI implements ChessAI {

    private static final Logger LOGGER = LoggerFactory.getLogger("PikafishAI");

    /** UCI 握手/单步命令超时（秒） */
    private static final int CMD_TIMEOUT_MS = 15_000;
    /** ★ Bug修复：原版每个实例都 addShutdownHook,跑 100 局仿真 = 100 个 hook,
     *   进程退出时每个 hook 都尝试 process.destroy,前 99 个空转。改为类级共享
     *   Set 跟踪活动实例,只注册一次 hook 遍历关闭 */
    private static final java.util.Set<PikafishChessAI> LIVE_INSTANCES =
            java.util.Collections.newSetFromMap(new java.util.concurrent.ConcurrentHashMap<>());
    private static volatile boolean SHUTDOWN_HOOK_REGISTERED = false;

    private final Process process;
    private final BufferedWriter writer;
    private final BufferedReader reader;
    private final ExecutorService readerExecutor = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "Pikafish-Reader");
        t.setDaemon(true);
        return t;
    });

    private volatile long searchTimeMs = 2000;
    private volatile boolean connected = false;

    /**
     * 构造并初始化 Pikafish 进程。
     *
     * @param exePath Pikafish 可执行文件路径
     */
    public PikafishChessAI(String exePath) throws IOException {
        File exe = new File(exePath);
        if (!exe.exists()) {
            throw new FileNotFoundException("Pikafish 可执行文件不存在: " + exePath);
        }

        int threads = GameSettings.getInt("chess", "pikafishThreads", 1);
        searchTimeMs = GameSettings.getInt("chess", "pikafishMovetime", 2000);

        ProcessBuilder pb = new ProcessBuilder(exePath);
        pb.redirectErrorStream(true);
        this.process = pb.start();
        this.writer = new BufferedWriter(new OutputStreamWriter(process.getOutputStream(), StandardCharsets.UTF_8));
        this.reader = new BufferedReader(new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8));

        // ★ Bug修复：原版每个实例都 addShutdownHook,跑 N 局仿真 = N 个 hook,
        //   进程退出时 N 次空转 destroy。改为类级共享 LIVE_INSTANCES + 仅一次注册
        LIVE_INSTANCES.add(this);
        if (!SHUTDOWN_HOOK_REGISTERED) {
            SHUTDOWN_HOOK_REGISTERED = true;
            Runtime.getRuntime().addShutdownHook(new Thread(() -> {
                for (PikafishChessAI ai : LIVE_INSTANCES) {
                    try { ai.shutdown(); } catch (Throwable ignored) {}
                }
            }));
        }

        try {
            initUci(threads);
            connected = true;
            LOGGER.info("[Pikafish] UCI 连接成功: {} (threads={})", exePath, threads);
        } catch (Exception e) {
            shutdown();
            throw new IOException("Pikafish UCI 初始化失败: " + e.getMessage(), e);
        }
    }

    /**
     * UCI 握手：uci → 等待 uciok；设置线程数 → isready → 等待 readyok。
     */
    private void initUci(int threads) throws IOException {
        writer.write("uci\n");
        writer.flush();
        String line;
        boolean uciok = false;
        while ((line = readLine(CMD_TIMEOUT_MS)) != null) {
            if ("uciok".equals(line.trim())) { uciok = true; break; }
        }
        if (!uciok) throw new IOException("未收到 uciok");

        writer.write("setoption name Threads value " + threads + "\n");
        writer.write("isready\n");
        writer.flush();

        boolean ready = false;
        while ((line = readLine(CMD_TIMEOUT_MS)) != null) {
            if ("readyok".equals(line.trim())) { ready = true; break; }
        }
        if (!ready) throw new IOException("未收到 readyok");
    }

    @Override
    public int[] getBestMove(int[][] board, boolean redTurn) {
        if (!connected) {
            LOGGER.warn("[Pikafish] 未连接，返回 null");
            return null;
        }
        try {
            writer.write("position fen " + ChessRules.toFen(board, redTurn) + "\n");
            writer.write("go movetime " + searchTimeMs + "\n");
            writer.flush();

            // 读 bestmove；+5s 缓冲多于 movetime 的收尾等待
            String line;
            long deadline = System.nanoTime() + (searchTimeMs + 5000) * 1_000_000L;
            while (true) {
                long remainingMs = (deadline - System.nanoTime()) / 1_000_000L;
                if (remainingMs <= 0) break;
                line = readLine(remainingMs);
                if (line == null) break;
                String trimmed = line.trim();
                if (trimmed.startsWith("bestmove")) {
                    String[] parts = trimmed.split("\\s+");
                    if (parts.length < 2 || "none".equals(parts[1])) return null;
                    int[] mv = ChessRules.parseUciMove(parts[1]);
                    if (mv == null) {
                        LOGGER.warn("[Pikafish] 无法解析的走法: {}", parts[1]);
                        return null;
                    }
                    return mv;
                }
            }
            LOGGER.warn("[Pikafish] 等待 bestmove 超时");
            return null;
        } catch (Exception e) {
            LOGGER.warn("[Pikafish] 获取走法失败: {}", e.getMessage());
            return null;
        }
    }

    @Override
    public void setSearchTime(long ms) {
        this.searchTimeMs = ms;
    }

    @Override
    public void setMaxDepth(int depth) {
        // Pikafish 以 movetime 控制思考，忽略深度上限
    }

    /**
     * 读取一行，超时抛 TimeoutException（用 Future + get 替代阻塞轮询）。
     */
    private String readLine(long timeoutMillis) throws IOException {
        Future<String> future = readerExecutor.submit(() -> reader.readLine());
        try {
            return future.get(timeoutMillis, TimeUnit.MILLISECONDS);
        } catch (TimeoutException e) {
            future.cancel(true);
            throw new IOException("读取超时");
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("读取被中断");
        } catch (java.util.concurrent.ExecutionException e) {
            throw new IOException("读取异常: " + e.getCause().getMessage(), e.getCause());
        }
    }

    @Override
    public void shutdown() {
        // ★ Bug修复：从共享 Set 移除自身,避免 hook 重复关闭已关闭实例
        LIVE_INSTANCES.remove(this);
        connected = false;
        try {
            if (writer != null) { writer.write("quit\n"); writer.flush(); }
        } catch (Exception ignored) {}
        try { if (writer != null) writer.close(); } catch (IOException ignored) {}
        try { if (reader != null) reader.close(); } catch (IOException ignored) {}
        readerExecutor.shutdownNow();
        if (process != null && process.isAlive()) {
            process.destroy();
            try {
                if (!process.waitFor(2, TimeUnit.SECONDS)) process.destroyForcibly();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                process.destroyForcibly();
            }
        }
        LOGGER.info("[Pikafish] 已关闭");
    }
}