package com.wzz.game_console.client.screens.games;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ThreadLocalRandom;

/**
 * 五子棋 AI，移植自 TouhouLittleMaid 项目的 ZhiZhangAIService
 * （原作者 anlingyi，源自 xechat-idea，Apache 2.0）。
 *
 * 算法：迭代加深 + 极小极大 + Alpha-Beta 剪枝 + 棋型评分 + Zobrist 局面缓存 + VCF/VCT 算杀。
 *
 * 棋子约定：0=空 1=黑 2=白。单机模式下本 AI 固定执白（2）。
 */
public class GomokuAI {

    public static final int EMPTY = 0;
    public static final int BLACK = 1;
    public static final int WHITE = 2;

    /**
     * 难度档位，配置与 TouhouLittleMaid 的 MaidGomokuAI 完全一致。
     */
    public enum Difficulty {
        EASY("简单", 1, 10, 0, 6),
        NORMAL("普通", 4, 10, 0, 6),
        HARD("困难", 6, 10, 1, 8),
        HELL("地狱", 8, 10, 1, 10);

        public final String label;
        final int depth;
        final int maxNodes;
        /** 算杀 0.不开启 1.VCT 2.VCF */
        final int vcx;
        final int vcxDepth;

        Difficulty(String label, int depth, int maxNodes, int vcx, int vcxDepth) {
            this.label = label;
            this.depth = depth;
            this.maxNodes = maxNodes;
            this.vcx = vcx;
            this.vcxDepth = vcxDepth;
        }

        /** 循环切换到下一档难度 */
        public Difficulty next() {
            Difficulty[] all = values();
            return all[(ordinal() + 1) % all.length];
        }
    }

    private static final int SIZE = 15;
    /** 单机模式 AI 固定执白 */
    private static final int AI = WHITE;
    /** 执白偏防守的进攻系数 */
    private static final float ATTACK = 0.5f;

    private static final int INFINITY = 999999999;

    private final Difficulty difficulty;

    private int[][] chessData;
    private int rounds;
    private Point bestPoint;
    private long hashcode;
    private Map<Long, SituationCache> situationCacheMap;

    public GomokuAI(Difficulty difficulty) {
        this.difficulty = difficulty;
    }

    /**
     * 计算 AI 落子坐标，返回 {x, y}；无可落子时返回 null。
     *
     * @param board 当前棋盘，0=空 1=黑 2=白
     */
    public int[] getMove(int[][] board) {
        initChessData(board);
        this.bestPoint = null;

        // 最低难度：单步启发式，不做搜索
        if (this.difficulty.depth < 2) {
            Point p = getBestPoint();
            return p == null ? null : new int[]{p.x, p.y};
        }

        int depth = this.difficulty.depth;
        if (depth > 4 && this.rounds < 4) {
            // 前三个回合降低搜索深度，加快落子
            depth = 4;
        }

        // 先尝试算杀（VCF/VCT）
        if (this.difficulty.vcx > 0) {
            this.bestPoint = deepening(1, this.difficulty.vcxDepth, this.difficulty.vcx == 2);
        }

        // 算杀未命中，退回到极大极小搜索
        if (this.bestPoint == null) {
            this.bestPoint = deepeningMinimax(2, depth);
        }

        this.situationCacheMap = null;
        return this.bestPoint == null ? null : new int[]{this.bestPoint.x, this.bestPoint.y};
    }

    // ══════════════════════════════════════════
    //  棋盘数据
    // ══════════════════════════════════════════

    private void initChessData(int[][] board) {
        this.chessData = new int[SIZE][SIZE];
        this.hashcode = 0;
        int chessTotal = 0;
        for (int i = 0; i < SIZE; i++) {
            for (int j = 0; j < SIZE; j++) {
                int type = board[i][j];
                if (type != EMPTY) {
                    putChess(new Point(i, j, type));
                    chessTotal++;
                }
            }
        }
        this.rounds = chessTotal / 2 + 1;
    }

    private void putChess(Point point) {
        this.chessData[point.x][point.y] = point.type;
        calculateHashCode(point);
    }

    private void revokeChess(Point point) {
        this.chessData[point.x][point.y] = EMPTY;
        calculateHashCode(point);
    }

    /** 增量维护当前局面的 Zobrist 哈希 */
    private long calculateHashCode(Point point) {
        this.hashcode ^= point.type == BLACK
                ? BLACK_ZOBRIST[point.x][point.y]
                : WHITE_ZOBRIST[point.x][point.y];
        return this.hashcode;
    }

    // ══════════════════════════════════════════
    //  极大极小搜索
    // ══════════════════════════════════════════

    private Point deepeningMinimax(int depth, int maxDepth) {
        this.situationCacheMap = new HashMap<>(2048);
        Point best = null;
        for (; depth <= maxDepth; depth += 2) {
            int score = minimax(0, depth, -INFINITY, INFINITY);
            best = this.bestPoint;
            if (Math.abs(score) >= INFINITY - 1) {
                // 找到必胜/必败解，提前终止
                break;
            }
        }
        return best;
    }

    private int minimax(int type, int depth, int alpha, int beta) {
        boolean isRoot = type == 0;
        if (isRoot) {
            type = AI;
        }
        boolean isAI = type == AI;

        // 局面缓存
        SituationCache cache = this.situationCacheMap.get(this.hashcode);
        if (cache != null && cache.depth >= depth) {
            return cache.score;
        }

        if (depth == 0) {
            return evaluateAll();
        }

        List<Point> pointList = getHeuristicPoints(type);
        if (isRoot && pointList.size() == 1) {
            this.bestPoint = pointList.get(0);
            return this.bestPoint.score;
        }

        List<Point> bestPointList = new ArrayList<>();
        for (Point point : pointList) {
            if (point.score >= ChessModel.LIANWU.score) {
                // 落子即连五，直接给最值
                point.score = isAI ? INFINITY - 1 : -INFINITY + 1;
            } else {
                putChess(point);
                point.score = minimax(3 - type, depth - 1, alpha, beta);
                revokeChess(point);
            }

            if (isAI) {
                if (point.score >= alpha) {
                    if (isRoot) {
                        if (point.score > alpha && this.rounds <= 1) {
                            bestPointList.clear();
                        }
                        bestPointList.add(point);
                    }
                    alpha = point.score;
                }
            } else {
                if (point.score < beta) {
                    beta = point.score;
                }
            }

            if (alpha >= beta) {
                break;
            }
        }

        if (isRoot) {
            int count = bestPointList.size();
            if (count == 1) {
                this.bestPoint = bestPointList.get(0);
            } else if (this.rounds > 1) {
                // 多解时随机取最佳/次佳，增加棋风变化
                this.bestPoint = getRandomBestPoint(bestPointList);
            } else {
                this.bestPoint = getBestPoint(bestPointList);
            }
        }

        int score = isAI ? alpha : beta;
        this.situationCacheMap.put(this.hashcode, new SituationCache(score, depth));
        return score;
    }

    // ══════════════════════════════════════════
    //  算杀（VCF / VCT）
    // ══════════════════════════════════════════

    private Point deepening(int depth, int maxDepth, boolean isVcf) {
        this.situationCacheMap = new HashMap<>(2048);
        Point point = null;
        for (; depth <= maxDepth; depth += 2) {
            point = vcx(0, depth, isVcf);
            if (point != null) {
                break;
            }
        }
        return point;
    }

    private Point vcx(int type, int depth, boolean isVcf) {
        SituationCache cache = this.situationCacheMap.get(this.hashcode);
        if (cache != null && cache.depth >= depth) {
            return cache.point;
        }
        if (depth == 0) {
            return null;
        }

        boolean isRoot = type == 0;
        if (isRoot) {
            type = AI;
        }
        boolean isAI = type == AI;

        Point best = null;
        List<Point> pointList = getVcxPoints(type, isVcf);
        for (Point point : pointList) {
            if (point.score >= RiskScore.HIGH_RISK.score) {
                // 已形成必胜棋型：AI 落子直接返回，对手落子则算杀失败
                return isAI ? point : null;
            }

            putChess(point);
            best = vcx(3 - type, depth - 1, isVcf);
            revokeChess(point);

            if (best == null) {
                if (isAI) {
                    continue;
                }
                // 对手拦截成功，算杀失败
                return null;
            }

            best = point;
            if (isAI) {
                break;
            }
        }

        this.situationCacheMap.put(this.hashcode, new SituationCache(best, depth));
        return best;
    }

    // ══════════════════════════════════════════
    //  候选点生成
    // ══════════════════════════════════════════

    private List<Point> getHeuristicPoints(int type) {
        int max = this.difficulty.maxNodes;
        List<Point> highPriorityPointList = new ArrayList<>();
        List<Point> lowPriorityPointList = new ArrayList<>();
        List<Point> alternatePointList = new ArrayList<>();
        List<Point> killPointList = new ArrayList<>();

        int dangerLevel = 0;
        for (int i = 0; i < SIZE; i++) {
            for (int j = 0; j < SIZE; j++) {
                if (this.chessData[i][j] != EMPTY) {
                    continue;
                }

                Point point = new Point(i, j, type);
                int score = evaluate(point);
                if (score >= ChessModel.LIANWU.score) {
                    // 自己可连五，直接返回
                    return Collections.singletonList(point);
                }
                if (dangerLevel == 2) {
                    continue;
                }
                if (score >= RiskScore.MEDIUM_RISK.score) {
                    killPointList.add(point);
                }

                Point foePoint = new Point(i, j, 3 - type);
                int foeScore = evaluate(foePoint);
                int level = 0;
                if (foeScore >= ChessModel.LIANWU.score) {
                    level = 2;
                } else if (foeScore >= RiskScore.MEDIUM_RISK.score) {
                    level = 1;
                }

                if (level > 0) {
                    if (dangerLevel < level) {
                        dangerLevel = level;
                        highPriorityPointList.clear();
                    }
                    highPriorityPointList.add(point);
                }
                if (dangerLevel > 0) {
                    continue;
                }

                if (RiskScore.between(score, RiskScore.LOW_RISK, RiskScore.MEDIUM_RISK)
                        || RiskScore.between(foeScore, RiskScore.LOW_RISK, RiskScore.MEDIUM_RISK)) {
                    highPriorityPointList.add(point);
                    continue;
                }

                if (highPriorityPointList.isEmpty()) {
                    if (score >= ChessModel.CHONGSI.score || foeScore >= ChessModel.CHONGSI.score) {
                        lowPriorityPointList.add(point);
                        continue;
                    }
                    if (lowPriorityPointList.isEmpty() && score >= ChessModel.MIANYI.score) {
                        alternatePointList.add(point);
                    }
                }
            }
        }

        if (dangerLevel < 2 && !killPointList.isEmpty()) {
            return killPointList;
        }

        List<Point> pointList;
        if (highPriorityPointList.isEmpty()) {
            if (lowPriorityPointList.isEmpty()) {
                if (alternatePointList.isEmpty()) {
                    return randomPoint(type, 1);
                }
                Collections.shuffle(alternatePointList);
                pointList = alternatePointList;
            } else {
                pointList = lowPriorityPointList;
            }
        } else {
            pointList = highPriorityPointList;
        }

        pointList.sort((p1, p2) -> p1.score == p2.score ? 0 : p1.score > p2.score ? -1 : 1);
        return pointList.subList(0, Math.min(pointList.size(), max));
    }

    private List<Point> getVcxPoints(int type, boolean isVcf) {
        boolean isAI = type == AI;
        List<Point> attackPointList = new ArrayList<>();
        List<Point> defensePointList = new ArrayList<>();
        List<Point> vcxPointList = new ArrayList<>();

        boolean isDanger = false;
        for (int i = 0; i < SIZE; i++) {
            for (int j = 0; j < SIZE; j++) {
                if (this.chessData[i][j] != EMPTY) {
                    continue;
                }

                Point point = new Point(i, j, type);
                int score = evaluate(point);
                if (score >= ChessModel.LIANWU.score) {
                    return Collections.singletonList(point);
                }
                if (isDanger) {
                    continue;
                }

                Point foePoint = new Point(i, j, 3 - type);
                int foeScore = evaluate(foePoint);
                if (foeScore >= ChessModel.LIANWU.score) {
                    isDanger = true;
                    defensePointList.clear();
                    defensePointList.add(point);
                    continue;
                }

                if (score >= RiskScore.MEDIUM_RISK.score) {
                    attackPointList.add(point);
                    continue;
                }

                if (isAI) {
                    if (checkSituation(point, ChessModel.CHONGSI)) {
                        vcxPointList.add(point);
                    } else if (!isVcf && checkSituation(point, ChessModel.HUOSAN)) {
                        vcxPointList.add(point);
                    }
                } else {
                    if (!isVcf
                            && (checkSituation(point, ChessModel.CHONGSI) || foeScore >= ChessModel.HUOSI.score)) {
                        defensePointList.add(point);
                    }
                }
            }
        }

        List<Point> pointList = new ArrayList<>();
        if (!isDanger) {
            if (!attackPointList.isEmpty()) {
                attackPointList.sort((p1, p2) -> p1.score == p2.score ? 0 : p1.score > p2.score ? -1 : 1);
                if (isAI) {
                    return attackPointList;
                }
                pointList.addAll(attackPointList);
            }
            if (!vcxPointList.isEmpty()) {
                pointList.addAll(vcxPointList);
            }
        }

        if (!defensePointList.isEmpty()) {
            if (isAI) {
                pointList.addAll(defensePointList);
            } else {
                pointList.addAll(0, defensePointList);
            }
        }
        return pointList;
    }

    // ══════════════════════════════════════════
    //  评估
    // ══════════════════════════════════════════

    private int evaluate(Point point) {
        int score = 0;
        int huosanTotal = 0;
        int chongsiTotal = 0;
        int tfTotal = 0;

        for (int i = 1; i < 5; i++) {
            String situation = getSituation(point, i);
            ChessModel model = getChessModel(situation);
            if (model != null) {
                switch (model) {
                    case HUOSAN:
                        huosanTotal++;
                        if (checkSituation(situation, ChessModel.CHONGSI)) {
                            tfTotal++;
                        }
                        break;
                    case CHONGSI:
                        chongsiTotal++;
                        break;
                    default:
                        break;
                }
                score += model.score;
            }
        }

        if (chongsiTotal > 1 || tfTotal > 1) {
            score += RiskScore.HIGH_RISK.score;
        } else if ((chongsiTotal > 0 && huosanTotal > 0) || (tfTotal > 0 && huosanTotal > 1)) {
            score += RiskScore.MEDIUM_RISK.score;
        } else if (huosanTotal > 1) {
            score += RiskScore.LOW_RISK.score;
        }

        point.score = score;
        return score;
    }

    /** 以 AI 视角评估整个局面，分值越大对 AI 越有利 */
    private int evaluateAll() {
        int aiScore = 0;
        int foeScore = 0;
        for (int i = 0; i < SIZE; i++) {
            for (int j = 0; j < SIZE; j++) {
                int type = this.chessData[i][j];
                if (type == EMPTY) {
                    continue;
                }
                int val = evaluate(new Point(i, j, type));
                if (type == AI) {
                    aiScore += val;
                } else {
                    foeScore += val;
                }
            }
        }
        return Math.round(aiScore * ATTACK) - foeScore;
    }

    private boolean checkSituation(Point point, ChessModel... chessModels) {
        for (int i = 1; i < 5; i++) {
            String situation = getSituation(point, i);
            for (ChessModel chessModel : chessModels) {
                if (checkSituation(situation, chessModel)) {
                    return true;
                }
            }
        }
        return false;
    }

    private boolean checkSituation(String situation, ChessModel chessModel) {
        for (String value : chessModel.values) {
            if (situation.contains(value)) {
                return true;
            }
        }
        return false;
    }

    /** 按顺序匹配棋型，优先级高的棋型先命中 */
    private ChessModel getChessModel(String situation) {
        for (ChessModel chessModel : ChessModel.values()) {
            for (String value : chessModel.values) {
                if (situation.contains(value)) {
                    return chessModel;
                }
            }
        }
        return null;
    }

    // ══════════════════════════════════════════
    //  点位选取
    // ══════════════════════════════════════════

    private Point getBestPoint() {
        Point best = null;
        int score = -INFINITY;
        for (int i = 0; i < SIZE; i++) {
            for (int j = 0; j < SIZE; j++) {
                if (this.chessData[i][j] != EMPTY) {
                    continue;
                }
                Point p = new Point(i, j, AI);
                int val = Math.round(evaluate(p) * ATTACK) + evaluate(new Point(i, j, 3 - AI));
                if (val > score) {
                    score = val;
                    best = p;
                }
            }
        }
        return best;
    }

    private Point getBestPoint(List<Point> pointList) {
        Point bestPoint = null;
        int bestScore = -INFINITY;
        for (Point point : pointList) {
            int score = Math.round(evaluate(point) * ATTACK)
                    + evaluate(new Point(point.x, point.y, 3 - point.type));
            if (score > bestScore) {
                bestScore = score;
                bestPoint = point;
            }
        }
        return bestPoint;
    }

    private Point getRandomBestPoint(List<Point> pointList) {
        Point bestPoint = null;
        Point secondPoint = null;
        int bestScore = -INFINITY;
        int secondScore = -INFINITY;
        for (Point point : pointList) {
            int score = Math.round(evaluate(point) * ATTACK)
                    + evaluate(new Point(point.x, point.y, 3 - point.type));
            if (score > bestScore) {
                bestScore = score;
                bestPoint = point;
            }
            if (score > secondScore && score < bestScore) {
                secondScore = score;
                secondPoint = point;
            }
        }
        if (secondPoint == null) {
            return bestPoint;
        }
        return Math.random() < 0.5 ? bestPoint : secondPoint;
    }

    private List<Point> randomPoint(int type, int num) {
        List<Point> pointList = new ArrayList<>();
        for (int i = 0; i < SIZE; i++) {
            for (int j = 0; j < SIZE; j++) {
                if (this.chessData[i][j] == EMPTY) {
                    pointList.add(new Point(i, j, type));
                }
            }
        }
        Collections.shuffle(pointList);
        return pointList.subList(0, Math.min(num, pointList.size()));
    }

    // ══════════════════════════════════════════
    //  棋型串
    // ══════════════════════════════════════════

    private String getSituation(Point point, int direction) {
        direction = direction * 2 - 1;
        StringBuilder sb = new StringBuilder();
        appendChess(sb, point, direction, 4);
        appendChess(sb, point, direction, 3);
        appendChess(sb, point, direction, 2);
        appendChess(sb, point, direction, 1);
        sb.append(1); // 当前棋子统一标记为 1（己方）
        appendChess(sb, point, direction + 1, 1);
        appendChess(sb, point, direction + 1, 2);
        appendChess(sb, point, direction + 1, 3);
        appendChess(sb, point, direction + 1, 4);
        return sb.toString();
    }

    private void appendChess(StringBuilder sb, Point point, int direction, int offset) {
        int chess = relativePoint(point, direction, offset);
        if (chess > -1) {
            if (point.type == WHITE) {
                // 白棋方做颜色反转，复用黑棋棋型表
                if (chess > 0) {
                    chess = 3 - chess;
                }
            }
            sb.append(chess);
        }
    }

    /**
     * 获取相对点位棋子。
     *
     * @param direction 1.左横 2.右横 3.上纵 4.下纵 5.左斜上 6.左斜下 7.右斜上 8.右斜下
     * @return -1:越界 0:空位 1:黑棋 2:白棋
     */
    private int relativePoint(Point point, int direction, int offset) {
        int x = point.x;
        int y = point.y;
        switch (direction) {
            case 1: x -= offset; break;
            case 2: x += offset; break;
            case 3: y -= offset; break;
            case 4: y += offset; break;
            case 5: x += offset; y -= offset; break;
            case 6: x -= offset; y += offset; break;
            case 7: x -= offset; y -= offset; break;
            case 8: x += offset; y += offset; break;
            default: break;
        }
        if (x < 0 || y < 0 || x >= SIZE || y >= SIZE) {
            return -1;
        }
        return this.chessData[x][y];
    }

    // ══════════════════════════════════════════
    //  内部类型
    // ══════════════════════════════════════════

    private static class Point {
        final int x;
        final int y;
        int type;
        int score;

        Point(int x, int y, int type) {
            this.x = x;
            this.y = y;
            this.type = type;
        }
    }

    private static class SituationCache {
        /** VCX 缓存的点位 */
        private final Point point;
        /** 缓存的分数 */
        private final int score;
        /** 缓存的搜索深度 */
        private final int depth;

        SituationCache(int score, int depth) {
            this.point = null;
            this.score = score;
            this.depth = depth;
        }

        SituationCache(Point point, int depth) {
            this.point = point;
            this.score = 0;
            this.depth = depth;
        }
    }

    private enum ChessModel {
        LIANWU(10000000, new String[]{"11111"}),
        HUOSI(1000000, new String[]{"011110"}),
        HUOSAN(10000, new String[]{"001110", "011100", "010110", "011010"}),
        CHONGSI(9000, new String[]{"11110", "01111", "10111", "11011", "11101"}),
        HUOER(100, new String[]{"001100", "011000", "000110", "001010", "010100"}),
        HUOYI(80, new String[]{"010200", "002010", "020100", "001020", "201000", "000102", "000201"}),
        MIANSAN(30, new String[]{"001112", "010112", "011012", "211100", "211010"}),
        MIANER(10, new String[]{"011200", "001120", "002110", "021100", "110000", "000011", "000112", "211000"}),
        MIANYI(1, new String[]{"001200", "002100", "000210", "000120", "210000", "000012"});

        final int score;
        final String[] values;

        ChessModel(int score, String[] values) {
            this.score = score;
            this.values = values;
        }
    }

    private enum RiskScore {
        HIGH_RISK(800000),
        MEDIUM_RISK(500000),
        LOW_RISK(100000);

        final int score;

        RiskScore(int score) {
            this.score = score;
        }

        static boolean between(int score, RiskScore leftScore, RiskScore rightScore) {
            return score >= leftScore.score && score < rightScore.score;
        }
    }

    /** 黑白双方各 15×15 个格子的 Zobrist 随机值 */
    private static final long[][] BLACK_ZOBRIST = new long[SIZE][SIZE];
    private static final long[][] WHITE_ZOBRIST = new long[SIZE][SIZE];

    static {
        ThreadLocalRandom random = ThreadLocalRandom.current();
        for (int i = 0; i < SIZE; i++) {
            for (int j = 0; j < SIZE; j++) {
                BLACK_ZOBRIST[i][j] = random.nextLong();
                WHITE_ZOBRIST[i][j] = random.nextLong();
            }
        }
    }
}
