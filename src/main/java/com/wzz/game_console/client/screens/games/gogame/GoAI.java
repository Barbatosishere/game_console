package com.wzz.game_console.client.screens.games.gogame;

import com.wzz.game_console.util.GameSettings;

/**
 * 围棋 AI 抽象接口。
 * <p>
 * 实现类：
 * <ul>
 *   <li>{@link MCTSGoAI} — 改进版 MCTS 蒙特卡洛树搜索（默认，纯 Java，开箱即用）</li>
 *   <li>{@link KataGoGoAI} — 外部 KataGo 引擎（GTP 协议，需用户安装 KataGo）</li>
 * </ul>
 * <p>
 * 引擎选择通过 {@code data/game_settings.json} 中的 {@code go.engine} 配置：
 * <pre>
 * {
 *   "go": {
 *     "engine": "mcts",        // "mcts" | "katago"
 *     "searchTime": 3000,      // 搜索时间 ms
 *     "mctsIterations": 2000,  // MCTS 迭代次数
 *     "katagoPath": "E:/katago/katago.exe",
 *     "katagoModel": "E:/katago/model.bin.gz",
 *     "katagoConfig": "E:/katago/analysis.cfg"
 *   }
 * }
 * </pre>
 */
public interface GoAI {
    /**
     * 获取最佳落子位置。
     *
     * @param game 当前对局状态
     * @return {x, y} 落子坐标，或 null 表示弃权
     */
    int[] getBestMove(GoGame game);

    /**
     * 获取 AI 的执棋颜色。
     * <p>
     * 默认返回白棋，子类（如 KataGoGoAI）可通过构造或配置改变。
     *
     * @return AI 执棋颜色
     */
    default GoPlayer getAIColor() {
        return GoPlayer.WHITE;
    }

    /**
     * 释放 AI 引擎占用的资源（如外部进程）。
     * 默认空实现，子类按需覆盖。
     */
    default void shutdown() {}

    /** 棋盘大小 */
    int BOARD_SIZE = 19;

    /** 四个方向偏移 */
    int[][] DIRS = {{0, 1}, {1, 0}, {0, -1}, {-1, 0}};

    // ── 工厂方法 ─────────────────────────────────────────────────

    /** 懒加载的日志记录器（避免静态初始化时 slf4j 不可用） */
    private static org.slf4j.Logger getFactoryLogger() {
        return org.slf4j.LoggerFactory.getLogger("GoAI");
    }

    /**
     * 根据 GameSettings 创建 AI 引擎实例。
     * 配置键：go.engine = "mcts"（默认）或 "katago"。
     */
    static GoAI create() {
        String engine;
        try {
            engine = GameSettings.getString("go", "engine", "mcts");
        } catch (Throwable t) {
            // GameSettings 不可用时使用默认 MCTS
            return new MCTSGoAI();
        }
        return create(engine);
    }

    /**
     * 根据引擎名称创建 AI 实例。
     *
     * @param engine "mcts" 或 "katago"
     */
    static GoAI create(String engine) {
        org.slf4j.Logger logger;
        try {
            logger = getFactoryLogger();
        } catch (Throwable t) {
            // slf4j 不可用时使用默认 MCTS
            return new MCTSGoAI();
        }
        if ("katago".equalsIgnoreCase(engine)) {
            String katagoPath;
            try {
                katagoPath = GameSettings.getString("go", "katagoPath", "");
            } catch (Throwable t) {
                logger.warn("[围棋AI] KataGo 路径读取失败，回退到 MCTS");
                return new MCTSGoAI();
            }
            if (katagoPath.isEmpty()) {
                logger.warn("[围棋AI] KataGo 路径未配置，回退到 MCTS");
                return new MCTSGoAI();
            }
            try {
                KataGoGoAI katago = new KataGoGoAI(katagoPath);
                logger.info("[围棋AI] 使用 KataGo 引擎: {}", katagoPath);
                return katago;
            } catch (Exception e) {
                logger.warn("[围棋AI] KataGo 启动失败，回退到 MCTS: {}", e.getMessage());
                return new MCTSGoAI();
            }
        }
        // 默认 MCTS
        logger.info("[围棋AI] 使用改进版 MCTS 引擎");
        return new MCTSGoAI();
    }
}