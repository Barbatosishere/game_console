package com.wzz.game_console.client.screens.games.gogame;

import com.wzz.game_console.util.GameSettings;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 外部 KataGo 引擎封装（通过 GTP 协议通信）。
 * <p>
 * KataGo 是一款高性能围棋 AI，需要用户单独安装：
 * <ol>
 *   <li>下载 KataGo 可执行文件：<a href="https://github.com/lightvector/KataGo/releases">KataGo Releases</a></li>
 *   <li>下载 KataGo 权重模型：<code>model.bin.gz</code></li>
 *   <li>配置 <code>data/game_settings.json</code> 中的 <code>go.katagoPath</code> 路径</li>
 * </ol>
 * <p>
 * 配置示例：
 * <pre>
 * {
 *   "go": {
 *     "engine": "katago",
 *     "katagoPath": "E:/katago/katago.exe",
 *     "katagoModel": "E:/katago/model.bin.gz",
 *     "katagoConfig": "E:/katago/analysis.cfg"
 *   }
 * }
 * </pre>
 * <p>
 * 如果 KataGo 不可用或启动失败，将自动回退到 MCTSGoAI。
 */
public class KataGoGoAI implements GoAI {
    private static final Logger LOGGER = LoggerFactory.getLogger("KataGoAI");

    /** 默认 GTP 超时（秒） */
    private static final int DEFAULT_GTP_TIMEOUT = 30;

    private final Process process;
    private final BufferedWriter writer;
    private final BufferedReader reader;
    private final int timeout;
    private volatile boolean connected = false;
    private final AtomicInteger commandId = new AtomicInteger(0);

    /** AI 执棋颜色，默认白棋 */
    private GoPlayer aiColor = GoPlayer.WHITE;

    /** 上次同步时的走子数量（用于增量同步） */
    private int lastSyncedMoveCount = 0;

    /** GTP 响应读取线程池 */
    private final ExecutorService responseExecutor = Executors.newCachedThreadPool(r -> {
        Thread t = new Thread(r, "KataGo-Reader");
        t.setDaemon(true);
        return t;
    });

    /**
     * 构造 KataGo AI。
     *
     * @param katagoExePath KataGo 可执行文件路径
     */
    public KataGoGoAI(String katagoExePath) throws IOException {
        this(katagoExePath, DEFAULT_GTP_TIMEOUT);
    }

    /**
     * 构造 KataGo AI（指定超时）。
     */
    public KataGoGoAI(String katagoExePath, int timeoutSeconds) throws IOException {
        this.timeout = timeoutSeconds;

        // 检查文件是否存在
        File exeFile = new File(katagoExePath);
        if (!exeFile.exists()) {
            throw new FileNotFoundException("KataGo 可执行文件不存在: " + katagoExePath);
        }

        // 读取配置
        String modelPath = GameSettings.getString("go", "katagoModel", "");
        String configPath = GameSettings.getString("go", "katagoConfig", "");

        // 构建命令行参数
        java.util.List<String> cmd = new java.util.ArrayList<>();
        cmd.add(katagoExePath);
        cmd.add("gtp");

        if (!modelPath.isEmpty()) {
            cmd.add("-model");
            cmd.add(modelPath);
        }
        if (!configPath.isEmpty()) {
            cmd.add("-config");
            cmd.add(configPath);
        }

        LOGGER.info("[KataGo] 启动进程: {}", String.join(" ", cmd));

        ProcessBuilder pb = new ProcessBuilder(cmd);
        pb.redirectErrorStream(true);
        this.process = pb.start();

        this.writer = new BufferedWriter(new OutputStreamWriter(process.getOutputStream(), StandardCharsets.UTF_8));
        this.reader = new BufferedReader(new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8));

        // 注册关闭钩子
        Runtime.getRuntime().addShutdownHook(new Thread(this::shutdown));

        // 初始化 GTP 连接
        try {
            initGTP();
            connected = true;
            LOGGER.info("[KataGo] GTP 连接成功");
        } catch (Exception e) {
            shutdown();
            throw new IOException("KataGo GTP 初始化失败: " + e.getMessage(), e);
        }
    }

    /**
     * 初始化 GTP 连接
     */
    private void initGTP() throws IOException, TimeoutException {
        // 设置棋盘大小
        sendCommand("boardsize " + BOARD_SIZE);
        expectSuccess();

        // 设置贴目（中国规则黑贴 7.5 目）
        sendCommand("komi 7.5");
        expectSuccess();

        // 清空棋盘
        sendCommand("clear_board");
        expectSuccess();
    }

    /**
     * 设置 AI 的执棋颜色。
     * <p>
     * KataGoGoAI 默认执白棋，调用此方法可更改为执黑。
     *
     * @param color AI 执棋颜色
     */
    public void setAIColor(GoPlayer color) {
        this.aiColor = (color == GoPlayer.BLACK) ? GoPlayer.BLACK : GoPlayer.WHITE;
    }

    /**
     * 获取 AI 的执棋颜色。
     *
     * @return AI 执棋颜色
     */
    @Override
    public GoPlayer getAIColor() {
        return aiColor;
    }

    @Override
    public int[] getBestMove(GoGame game) {
        if (!connected) {
            LOGGER.warn("[KataGo] 未连接，返回 null");
            return null;
        }

        try {
            // 同步棋盘状态（增量同步）
            syncBoard(game);

            // 请求 AI 走法（根据 AI 颜色决定）
            String colorStr = (aiColor == GoPlayer.WHITE) ? "white" : "black";
            sendCommand("genmove " + colorStr);
            String response = readResponse();

            if (response.startsWith("=")) {
                String coord = response.substring(1).trim();
                return parseMove(coord);
            } else {
                LOGGER.warn("[KataGo] genmove 失败: {}", response);
                return null;
            }
        } catch (Exception e) {
            LOGGER.error("[KataGo] 获取走法失败: {}", e.getMessage());
            return null;
        }
    }

    /**
     * 同步棋盘状态到 KataGo（增量同步）。
     * <p>
     * KataGo 内部会缓存棋盘状态，避免每步都 clear_board 丢失缓存。
     * 只有在棋盘为空或历史大幅倒退（如重开）时才全量同步。
     */
    private void syncBoard(GoGame game) throws IOException, TimeoutException {
        List<GoMove> history = game.getMoveHistory();

        // 如果棋盘为空或历史大幅倒退（如重开），全量同步
        if (lastSyncedMoveCount == 0 || history.size() < lastSyncedMoveCount - 5) {
            sendCommand("clear_board");
            expectSuccess();
            lastSyncedMoveCount = 0;
            for (GoMove move : history) {
                if (move.x >= 0 && move.y >= 0) {
                    String color = move.player == GoPlayer.BLACK ? "black" : "white";
                    sendCommand("play " + color + " " + formatMove(move.x, move.y));
                    expectSuccess();
                }
            }
            lastSyncedMoveCount = history.size();
            return;
        }

        // 增量同步：只发送新增的走法
        for (int i = lastSyncedMoveCount; i < history.size(); i++) {
            GoMove move = history.get(i);
            if (move.x >= 0 && move.y >= 0) {
                String color = move.player == GoPlayer.BLACK ? "black" : "white";
                sendCommand("play " + color + " " + formatMove(move.x, move.y));
                expectSuccess();
            }
        }
        lastSyncedMoveCount = history.size();
    }

    /**
     * 发送 GTP 命令
     */
    private String sendCommand(String cmd) throws IOException {
        int id = commandId.incrementAndGet();
        String fullCmd = id + " " + cmd;
        LOGGER.debug("[KataGo] >>> {}", fullCmd);
        writer.write(fullCmd);
        writer.newLine();
        writer.flush();
        try {
            return readResponse(id);
        } catch (java.util.concurrent.TimeoutException e) {
            throw new IOException("KataGo 命令超时: " + cmd, e);
        }
    }

    /**
     * 读取 GTP 响应（使用 Future + waitFor 替代轮询，避免 CPU 空转）。
     */
    private String readResponse() throws IOException, TimeoutException {
        return readResponse(0);
    }

    private String readResponse(int expectedId) throws IOException, TimeoutException {
        Future<String> future = responseExecutor.submit(() -> {
            StringBuilder response = new StringBuilder();
            while (true) {
                String line;
                try {
                    line = reader.readLine();
                } catch (IOException e) {
                    break;
                }
                if (line == null) {
                    break;
                }
                LOGGER.debug("[KataGo] <<< {}", line);
                response.append(line).append("\n");

                // 检查是否收到完整的响应（= 或 ? 开头）
                if (line.startsWith("=") || line.startsWith("?")) {
                    break;
                }
            }
            return response.toString();
        });

        try {
            return future.get(timeout, TimeUnit.SECONDS);
        } catch (TimeoutException e) {
            future.cancel(true);
            throw e;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("KataGo 读取被中断", e);
        } catch (ExecutionException e) {
            throw new IOException("KataGo 读取异常: " + e.getCause().getMessage(), e.getCause());
        }
    }

    /**
     * 期待成功响应
     */
    private void expectSuccess() throws IOException, TimeoutException {
        String response = readResponse();
        if (!response.startsWith("=")) {
            throw new IOException("GTP 命令失败: " + response);
        }
    }

    /**
     * 解析 GTP 坐标为 {x, y}（GTP 使用 A-T 跳过 I 列）
     */
    private int[] parseMove(String coord) {
        if (coord == null || coord.isEmpty()) return null;

        coord = coord.toLowerCase().trim();

        // PASS 或认输
        if ("pass".equals(coord) || "resign".equals(coord)) {
            return null;
        }

        try {
            // GTP 格式：A1, B2, ...（列用字母，行用数字）
            char colChar = coord.charAt(0);
            int row = Integer.parseInt(coord.substring(1));

            // 转换 GTP 列号到棋盘坐标（GTP 列 A=1 对应棋盘列 0）
            int col = colChar - 'a';
            int boardRow = row - 1;

            // 转换为棋盘坐标 (x=col, y=boardRow)
            if (col >= 0 && col < BOARD_SIZE && boardRow >= 0 && boardRow < BOARD_SIZE) {
                return new int[]{col, boardRow};
            }
        } catch (NumberFormatException e) {
            LOGGER.warn("[KataGo] 无法解析坐标: {}", coord);
        }

        return null;
    }

    /**
     * 格式化坐标为 GTP 格式
     */
    private String formatMove(int x, int y) {
        char col = (char) ('a' + x);
        return col + String.valueOf(y + 1);
    }

    @Override
    public void shutdown() {
        connected = false;

        // 先发送 quit 命令优雅关闭
        try {
            if (writer != null) {
                writer.write((commandId.incrementAndGet()) + " quit\n");
                writer.flush();
            }
        } catch (Exception ignored) {}

        // 关闭 writer
        try {
            if (writer != null) {
                writer.close();
            }
        } catch (IOException ignored) {}

        // 关闭 reader
        try {
            if (reader != null) {
                reader.close();
            }
        } catch (IOException ignored) {}

        // 关闭线程池
        responseExecutor.shutdownNow();
        try {
            responseExecutor.awaitTermination(1, TimeUnit.SECONDS);
        } catch (InterruptedException ignored) {
            Thread.currentThread().interrupt();
        }

        // 等待进程退出，最多 2 秒
        if (process != null && process.isAlive()) {
            process.destroy();
            try {
                if (!process.waitFor(2, TimeUnit.SECONDS)) {
                    process.destroyForcibly();
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                process.destroyForcibly();
            }
        }
        LOGGER.info("[KataGo] 已关闭");
    }
}
