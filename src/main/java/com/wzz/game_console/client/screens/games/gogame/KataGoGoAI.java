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
    /** ★ Bug修复：原版每个实例都 addShutdownHook,多次创建引擎会注册多个 hook,
     *   进程退出时每个 hook 都尝试 process.destroy,前面的空转。改为类级共享 */
    private static final java.util.Set<KataGoGoAI> LIVE_INSTANCES =
            java.util.Collections.newSetFromMap(new java.util.concurrent.ConcurrentHashMap<>());
    private static volatile boolean SHUTDOWN_HOOK_REGISTERED = false;

    /** AI 执棋颜色，默认白棋 */
    private GoPlayer aiColor = GoPlayer.WHITE;

    private final BoardSync boardSync = new BoardSync(this::sendCommand);

    /** 驻留读线程 + 队列：读线程只负责把 GTP 行推入队列，
     *  响应等待方用 poll(剩余时间) 实现超时，超时不会遗留阻塞在 readLine 上的任务 */
    /** Bounded so a buggy engine spamming stdout fails fast instead of leaking memory. */
    private static final int RESPONSE_QUEUE_CAPACITY = 4_096;
    private final BlockingQueue<String> responseQueue = new LinkedBlockingDeque<>(RESPONSE_QUEUE_CAPACITY);
    private volatile boolean running = true;
    /** 引擎退出/流关闭时入队的哨兵（空行已在读线程过滤，队列中出现 "" 仅表示 EOF） */
    private static final String EOF_SENTINEL = "";

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
        // ★ Bug修复：Mac/Linux 文件存在但无执行位时,ProcessBuilder 报 "permission denied"
        //   错误信息玩家看不懂。提前 canExecute 检查并提示 chmod +x
        if (!exeFile.canExecute()) {
            throw new IOException("KataGo 文件无执行权限: " + katagoExePath
                    + " (Mac/Linux 请运行: chmod +x " + exeFile.getName() + ")");
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
        // ★ Bug修复：stderr 不并入 stdout——stdout 必须保持纯 GTP 流，
        //   引擎日志混入后会被响应解析吞掉/错位；日志改走本进程 stderr。
        //   stderr 也不能完全不消费——管道缓冲写满会挂死引擎，继承到本进程 stderr
        pb.redirectError(ProcessBuilder.Redirect.INHERIT);
        this.process = pb.start();

        this.writer = new BufferedWriter(new OutputStreamWriter(process.getOutputStream(), StandardCharsets.UTF_8));
        this.reader = new BufferedReader(new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8));

        // 驻留读线程必须先于 initGTP 启动，否则首条命令的响应无人消费
        Thread rt = new Thread(this::readLoop, "KataGo-Reader");
        rt.setDaemon(true);
        rt.start();

        // 注册关闭钩子
        // ★ Bug修复：见 PikafishChessAI 同样处理
        LIVE_INSTANCES.add(this);
        if (!SHUTDOWN_HOOK_REGISTERED) {
            SHUTDOWN_HOOK_REGISTERED = true;
            Runtime.getRuntime().addShutdownHook(new Thread(() -> {
                for (KataGoGoAI ai : LIVE_INSTANCES) {
                    try { ai.shutdown(); } catch (Throwable ignored) {}
                }
            }));
        }

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
     * 初始化 GTP 连接（sendCommand 对 "?" 错误响应直接抛 IOException）
     */
    private void initGTP() throws IOException {
        // 设置棋盘大小
        sendCommand("boardsize " + BOARD_SIZE);
        // 设置贴目，与 GoGame 的计分配置保持一致。
        sendCommand("komi " + GoGame.getConfiguredKomi());
        // 清空棋盘
        sendCommand("clear_board");
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
        return getBestMoveResult(game).coordinates();
    }

    @Override
    public MoveResult getBestMoveResult(GoGame game) {
        if (!connected) {
            LOGGER.warn("[KataGo] 未连接，返回错误结果");
            return MoveResult.error();
        }

        try {
            return boardSync.generate(game.getMoveHistory(), aiColor);
        } catch (Exception e) {
            LOGGER.error("[KataGo] 获取走法失败: {}", e.getMessage());
            return MoveResult.error();
        }
    }

    @FunctionalInterface
    interface CommandTransport {
        String send(String command) throws IOException;
    }

    /** Production replay logic, independent of the child process for transport tests. */
    static final class BoardSync {
        private final CommandTransport transport;
        private final java.util.ArrayList<GoMove> engineHistory = new java.util.ArrayList<>();
        private boolean dirty = true;

        BoardSync(CommandTransport transport) {
            this.transport = transport;
        }

        synchronized MoveResult generate(List<GoMove> history, GoPlayer color) throws IOException {
            try {
                syncBoard(history);
                MoveResult result = parseMoveResult(transport.send("genmove " + colorName(color)));
                // genmove applies its own move. The next local history must acknowledge it.
                if (result.type() == MoveType.MOVE) {
                    engineHistory.add(new GoMove(result.x(), result.y(), color, 0));
                } else if (result.type() == MoveType.PASS) {
                    engineHistory.add(new GoMove(-1, -1, color, 0));
                } else {
                    dirty = true;
                }
                return result;
            } catch (IOException | RuntimeException e) {
                dirty = true;
                throw e;
            }
        }

        private void syncBoard(List<GoMove> history) throws IOException {
            boolean prefixMatches = history.size() >= engineHistory.size();
            if (prefixMatches) {
                for (int i = 0; i < engineHistory.size(); i++) {
                    GoMove local = history.get(i), engine = engineHistory.get(i);
                    if (local.x != engine.x || local.y != engine.y || local.player != engine.player) {
                        prefixMatches = false;
                        break;
                    }
                }
            }
            if (dirty || !prefixMatches) {
                transport.send("clear_board");
                engineHistory.clear();
            }
            for (int i = engineHistory.size(); i < history.size(); i++) {
                GoMove move = history.get(i);
                String coordinate = move.x < 0 || move.y < 0 ? "pass" : formatMove(move.x, move.y);
                transport.send("play " + colorName(move.player) + " " + coordinate);
                engineHistory.add(move);
            }
            dirty = false;
        }

        private static String colorName(GoPlayer color) {
            return color == GoPlayer.BLACK ? "black" : "white";
        }
    }

    /**
     * 发送 GTP 命令并返回该命令的响应正文。
     * ★ Bug修复：原版 sendCommand 内部读一次响应、调用方 expectSuccess/readResponse 再读一次，
     *   每条命令的响应被双重消费——第二条读取只能等到下一条命令的响应或超时，
     *   genmove 必然超时失败，KataGo 引擎 100% 不可用。
     * 现在发送+读取严格一一对应：成功返回正文，"?" 错误响应抛 IOException。
     */
    private String sendCommand(String cmd) throws IOException {
        int id = commandId.incrementAndGet();
        String fullCmd = id + " " + cmd;
        LOGGER.debug("[KataGo] >>> {}", fullCmd);
        try {
            writer.write(fullCmd);
            writer.newLine();
            writer.flush();
        } catch (IOException e) {
            connected = false;
            throw e;
        }
        try {
            return readResponse();
        } catch (TimeoutException e) {
            connected = false;
            throw new IOException("KataGo 命令超时: " + cmd, e);
        }
    }

    /**
     * 驻留读线程：把引擎 stdout 的 GTP 行推入队列。
     * 空行（GTP 响应块终止符）与引擎日志行全部入队，由消费方按前缀过滤。
     */
    private void readLoop() {
        try {
            String line;
            while (running && (line = reader.readLine()) != null) {
                if (line.isBlank()) continue; // GTP 响应以"=..."行为准，空行终止符无需入队
                if (!responseQueue.offer(line)) {
                    LOGGER.error("[KataGo] 响应队列溢出（{} 条未消费），判定引擎输出异常并断开",
                            RESPONSE_QUEUE_CAPACITY);
                    connected = false;
                    running = false;
                    responseQueue.clear();
                    break;
                }
            }
        } catch (IOException ignored) {
            // shutdown 通过 closeProcess 关闭流使 readLine 抛出并落到 finally 哨兵
        } finally {
            if (!responseQueue.offer(EOF_SENTINEL)) {
                responseQueue.clear();
                responseQueue.offer(EOF_SENTINEL);
            }
        }
    }

    /**
     * 等待并返回当前命令的响应正文，带整体超时。
     * 非 GTP 行（引擎日志/横幅）跳过；"=xxx" 剥离前缀与回显的命令 id 后返回；
     * "?xxx" 视为 GTP 错误抛 IOException。
     */
    private String readResponse() throws IOException, TimeoutException {
        long deadline = System.nanoTime() + timeout * 1_000_000_000L;
        while (true) {
            long remaining = deadline - System.nanoTime();
            if (remaining <= 0) {
                connected = false;
                throw new TimeoutException("KataGo 响应超时");
            }
            String line;
            try {
                line = responseQueue.poll(remaining, TimeUnit.NANOSECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IOException("KataGo 读取被中断", e);
            }
            if (line == null) continue; // 单次 poll 到时未到整体 deadline，继续等
            if (line.isEmpty()) { // EOF 哨兵
                connected = false;
                throw new IOException("KataGo 引擎已退出");
            }
            LOGGER.debug("[KataGo] <<< {}", line);
            if (line.startsWith("=") || line.startsWith("?")) {
                String body = line.substring(1).stripLeading();
                // GTP 会在响应中回显命令 id（如 "=5 D4"），剥掉纯数字 id 前缀
                int sp = body.indexOf(' ');
                String head = sp >= 0 ? body.substring(0, sp) : body;
                if (!head.isEmpty() && head.chars().allMatch(Character::isDigit)) {
                    body = sp >= 0 ? body.substring(sp + 1) : "";
                }
                if (line.startsWith("?")) {
                    throw new IOException("GTP 命令失败: " + body);
                }
                return body;
            }
            // 非 GTP 输出（引擎启动日志/横幅），跳过继续等
        }
    }

    /**
     * 解析 GTP 坐标为 {x, y}（GTP 使用 A-T 跳过 I 列）
     */
    static MoveResult parseMoveResult(String coordinate) {
        if (coordinate == null) return MoveResult.error();
        String coord = coordinate.toLowerCase().trim();
        if (coord.isEmpty()) return MoveResult.error();
        if ("pass".equals(coord)) return MoveResult.pass();
        if ("resign".equals(coord)) return MoveResult.resign();
        try {
            char colChar = coord.charAt(0);
            int row = Integer.parseInt(coord.substring(1));
            if (colChar == 'i' || colChar < 'a' || colChar > 't') return MoveResult.error();
            int col = colChar < 'i' ? colChar - 'a' : colChar - 'a' - 1;
            int boardRow = row - 1;
            if (col >= 0 && col < BOARD_SIZE && boardRow >= 0 && boardRow < BOARD_SIZE) {
                return MoveResult.move(col, boardRow);
            }
        } catch (NumberFormatException ignored) {
            LOGGER.warn("[KataGo] 无法解析坐标: {}", coordinate);
        }
        return MoveResult.error();
    }

    /**
     * 格式化坐标为 GTP 格式
     */
    private static String formatMove(int x, int y) {
        // GTP 列坐标跳过 I：棋盘第 8 列对应 J，而不是 I。
        char col = (char) ('a' + x + (x >= 8 ? 1 : 0));
        return col + String.valueOf(y + 1);
    }

    @Override
    public void shutdown() {
        // ★ Bug修复：从共享 Set 移除自身,避免 hook 重复关闭已关闭实例
        LIVE_INSTANCES.remove(this);
        connected = false;
        running = false;
        responseQueue.clear();
        responseQueue.offer(EOF_SENTINEL); // 唤醒可能仍在等待的消费者

        closeProcess(process, writer, reader);
        LOGGER.info("[KataGo] 已关闭");
    }

    static void closeProcess(Process process, Closeable writer, Closeable reader) {
        // Terminate the child first. Closing a reader before the child exits can
        // block on platform pipes while KataGo is still writing its response.
        if (process != null && process.isAlive()) {
            process.destroy();
            try {
                if (!process.waitFor(2, TimeUnit.SECONDS)) process.destroyForcibly();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                process.destroyForcibly();
            }
        }

        try {
            if (writer != null) writer.close();
        } catch (IOException ignored) {}
        try {
            if (reader != null) reader.close();
        } catch (IOException ignored) {}
    }
}
