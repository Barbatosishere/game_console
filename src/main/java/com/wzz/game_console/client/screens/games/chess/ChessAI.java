package com.wzz.game_console.client.screens.games.chess;

import com.wzz.game_console.util.GameSettings;

/**
 * 中国象棋 AI 抽象接口（结构仿 {@code GoAI}）。
 * <p>
 * 实现类：
 * <ul>
 *   <li>{@link BuiltInChessAI} — 内置增强搜索引擎（迭代加深 + α-β + 置换表，纯 Java，开箱即用）</li>
 *   <li>{@link PikafishChessAI} — 外部 Pikafish 引擎（UCI 协议，需用户安装 Pikafish）</li>
 * </ul>
 * <p>
 * 引擎选择通过 {@code data/game_settings.json} 中的 {@code chess.engine} 配置：
 * <pre>
 * {
 *   "chess": {
 *     "engine": "built-in",       // "built-in" | "pikafish"
 *     "pikafishPath": "E:/皮卡鱼 20260131/pikafish-avx2.exe",
 *     "pikafishThreads": 1
 *   }
 * }
 * </pre>
 * 若 Pikafish 路径未配置、文件不存在或启动失败，会自动回退到内置引擎。
 */
public interface ChessAI {

    /**
     * 计算最佳走法。
     *
     * @param board   当前局面，{@code board[col][row]}，0=空 正=红 负=黑
     * @param redTurn true=红方走子，false=黑方走子
     * @return {@code {fc, fr, tc, tr}} 走法；无合法走法返回 {@code null}
     */
    int[] getBestMove(int[][] board, boolean redTurn);

    /**
     * 设置本次思考的时间预算（毫秒）。外挂引擎对应 UCI 的 {@code movetime}。
     */
    void setSearchTime(long ms);

    /**
     * 设置内置引擎的搜索深度上限。外挂引擎忽略此设置（以搜索时间为主）。
     */
    void setMaxDepth(int depth);

    /** 请求当前搜索尽快停止。实现不得阻塞调用线程。 */
    default void cancelSearch() {}

    /** 释放 AI 引擎占用的资源（如外部进程）。默认空实现，子类按需覆盖。 */
    default void shutdown() {}

    /** Pikafish 默认路径（文件夹含空格，由 ProcessBuilder 直接处理） */
    String DEFAULT_PIKAFISH_PATH = "E:/皮卡鱼 20260131/pikafish-avx2.exe";

    // ── 工厂方法 ──────────────────────────────────────────────

    /** 懒加载的日志记录器（避免静态初始化时 slf4j 不可用） */
    private static org.slf4j.Logger getFactoryLogger() {
        return org.slf4j.LoggerFactory.getLogger("ChessAI");
    }

    /**
     * 根据 GameSettings 创建 AI 引擎实例。
     * 配置键：chess.engine = "built-in"（默认）或 "pikafish"。
     */
    static ChessAI create() {
        String engine;
        try {
            engine = GameSettings.getString("chess", "engine", "built-in");
        } catch (Throwable t) {
            return new BuiltInChessAI();
        }
        return create(engine);
    }

    /**
     * 根据引擎名称创建 AI 实例。
     *
     * @param engine "built-in" 或 "pikafish"
     */
    static ChessAI create(String engine) {
        org.slf4j.Logger logger;
        try {
            logger = getFactoryLogger();
        } catch (Throwable t) {
            return new BuiltInChessAI();
        }
        if ("pikafish".equalsIgnoreCase(engine)) {
            String path;
            try {
                path = GameSettings.getString("chess", "pikafishPath", "");
            } catch (Throwable t) {
                path = "";
            }
            if (path.isEmpty()) {
                // ★ Bug修复：原版默认路径硬编码 "E:/皮卡鱼 20260131/pikafish-avx2.exe",
                //   Mac/Linux/C/D/F 盘用户/无 Pikafish 用户都失败,仅在用户实际有
                //   该盘符和路径时才能用。改为空时直接回退内置引擎,日志提示配置
                path = "";
                logger.info("[中国象棋] Pikafish 路径未配置,使用内置引擎");
                return new BuiltInChessAI();
            }
            try {
                PikafishChessAI pikafish = new PikafishChessAI(path);
                logger.info("[中国象棋] 使用 Pikafish 引擎: {}", path);
                return pikafish;
            } catch (Exception e) {
                logger.warn("[中国象棋] Pikafish 启动失败，回退到内置引擎: {}", e.getMessage());
                return new BuiltInChessAI();
            }
        }
        // 默认内置引擎
        logger.info("[中国象棋] 使用内置增强搜索引擎");
        return new BuiltInChessAI();
    }
}