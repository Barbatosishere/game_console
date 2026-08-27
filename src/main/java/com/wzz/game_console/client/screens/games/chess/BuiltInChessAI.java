package com.wzz.game_console.client.screens.games.chess;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * 内置中国象棋搜索引擎（增强版）。
 * <p>
 * 算法栈参考 Pikafish/Stockfish 的经典技术：
 * <ul>
 *   <li>迭代加深 + 空窗搜索（Aspiration Windows）</li>
 *   <li>Negamax + α-β 剪枝 + 将军延伸（Check Extension）</li>
 *   <li>空步裁剪（Null Move Pruning）— 象棋无顿挫安全</li>
 *   <li>无效裁减（Futility Pruning）— 浅层安静走法跳过</li>
 *   <li>置换表（Zobrist）+ 走法排序（TT > MVV-LVA > 杀手 > 历史）</li>
 *   <li>静态搜索（Quiescence）— 将军时全展开，否则仅吃子</li>
 *   <li>评估：子力 + 位置 + 先手优势</li>
 * </ul>
 */
public class BuiltInChessAI implements ChessAI {

    private static final int INF = 1_000_000;

    /** 置换表容量 */
    private static final int TT_SIZE = 1 << 18;
    private static final int TT_MASK = TT_SIZE - 1;

    private static final int FLAG_EXACT = 0, FLAG_LOWER = 1, FLAG_UPPER = 2;
    private static final int NULL_MOVE_R = 2; // 空步额外减少

    private static final long[][][] ZOBRIST = new long[2][8][90];
    private static final long ZOBRIST_SIDE;
    static {
        java.util.Random rnd = new java.util.Random(0xDEAD_BEEF_5EED_CAFEL);
        for (int s = 0; s < 2; s++) for (int p = 1; p < 8; p++) for (int i = 0; i < 90; i++) ZOBRIST[s][p][i] = rnd.nextLong();
        ZOBRIST_SIDE = rnd.nextLong();
    }

    private volatile long timeBudgetMs = 1500;
    private volatile int maxDepth = 6;
    private long timeStartNs;
    private long nodeCount;

    private final long[] ttKey = new long[TT_SIZE];
    private final int[] ttMove = new int[TT_SIZE];
    private final int[] ttScore = new int[TT_SIZE];
    private final int[] ttDepth = new int[TT_SIZE];
    private final int[] ttFlag = new int[TT_SIZE];

    private final int[][] killers = new int[128][2];
    private final int[] history = new int[8100];

    private int nullMovePly = -1; // 最近空步所在层，用于防连续空步
    private final boolean useLmr; // LMR + Delta 裁剪（默认启用，可关闭以对比）

    public BuiltInChessAI() { this(true); }
    public BuiltInChessAI(boolean useLmr) { this.useLmr = useLmr; }

    @Override public void setSearchTime(long ms) { this.timeBudgetMs = ms; }
    @Override public void setMaxDepth(int depth) { this.maxDepth = depth; }

    @Override
    public int[] getBestMove(int[][] board, boolean redTurn) {
        timeStartNs = System.nanoTime(); nodeCount = 0; nullMovePly = -1;

        long rootKey = zobrist(board, redTurn);
        List<int[]> rootMoves = orderedMoves(board, redTurn, 0, ttMove[(int) rootKey & TT_MASK]);
        if (rootMoves.isEmpty()) return null;

        int[] bestMove = null;
        int prevScore = 0;
        boolean inCheck = ChessRules.inCheckOnBoard(board, redTurn);

        try {
            for (int depth = 1; depth <= maxDepth; depth++) {
                int alpha = -INF, beta = INF;
                // 深度 ≥ 2 时使用空窗搜索
                if (depth >= 2) {
                    int window = Math.max(30, 50 - depth * 5);
                    alpha = Math.max(-INF, prevScore - window);
                    beta  = Math.min(INF,  prevScore + window);
                }

                // 如果根节点被将军，不使用空窗（避免反复重搜）
                if (inCheck) { alpha = -INF; beta = INF; }

                int bestScore = -INF;
                int[] depthBest = null;

                for (int attempt = 0; attempt < 2; attempt++) {
                    bestScore = -INF;
                    depthBest = null;
                    for (int[] mv : rootMoves) {
                        TrieMove undo = makeMove(board, mv);
                        // 根层同样要 finally 回滚：negamax 超时抛 SearchAbort 时若不回滚，
                        // 最后试探的走子会永久残留在调用方棋盘上（吃将/丢子的根源）
                        try {
                            if (!ChessRules.inCheckOnBoard(board, redTurn)) {
                                int score = -negamax(board, !redTurn, depth - 1, -beta, -alpha, 1, 0);
                                if (score > bestScore) { bestScore = score; depthBest = mv; }
                                if (score > alpha) alpha = score;
                            }
                        } finally {
                            unmakeMove(board, mv, undo);
                        }
                        if (shouldStop()) throw new SearchAbort();
                    }
                    if (depthBest == null) break;
                    // 失败/失败高 → 扩大窗口重搜
                    if (attempt == 0 && (bestScore <= alpha || bestScore >= beta)) {
                        alpha = -INF; beta = INF;
                        continue;
                    }
                    break;
                }

                if (depthBest != null) {
                    bestMove = depthBest;
                    prevScore = bestScore;
                    storeTt(rootKey, encodeMove(depthBest), bestScore, depth, FLAG_EXACT);
                }
                if (shouldStop()) break;
            }
        } catch (SearchAbort ignored) {}

        if (bestMove == null) {
            List<int[]> fallback = orderedMoves(board, redTurn, 0, 0);
            if (!fallback.isEmpty()) bestMove = fallback.get(0);
        }
        return bestMove == null ? null : new int[]{bestMove[0], bestMove[1], bestMove[2], bestMove[3]};
    }

    // ── α-β 搜索 ─────────────────────────────────────────

    private int negamax(int[][] b, boolean red, int depth, int alpha, int beta, int ply, int checkExt) throws SearchAbort {
        nodeCount++;
        if ((nodeCount & 0xFFF) == 0 && shouldStop()) throw new SearchAbort();
        if (depth <= 0) return quiescence(b, red, alpha, beta, ply, checkExt);

        boolean inCheck = ChessRules.inCheckOnBoard(b, red);
        long key = zobrist(b, red);
        int idx = (int) key & TT_MASK;

        // 置换表探测
        if (ttKey[idx] == key && ttDepth[idx] >= depth) {
            int ttSc = ttScore[idx];
            if (ttFlag[idx] == FLAG_EXACT) return ttSc;
            if (ttFlag[idx] == FLAG_LOWER && ttSc > alpha) alpha = ttSc;
            else if (ttFlag[idx] == FLAG_UPPER && ttSc < beta) beta = ttSc;
            if (alpha >= beta) return ttSc;
        }

        // 空步裁剪：不被将军且深度≥3 且上一步不是空步
        if (!inCheck && depth >= 3 && ply - nullMovePly > 1) {
            nullMovePly = ply;
            int score = -negamax(b, !red, depth - 3 - NULL_MOVE_R, -beta, -beta + 1, ply + 1, checkExt);
            nullMovePly = -1;
            if (score >= beta) return beta;
        }

        List<int[]> moves = orderedMoves(b, red, ply, ttMove[idx]);
        if (moves.isEmpty()) return -(INF - ply);

        int bestScore = -INF, bestMove = 0, origAlpha = alpha;

        for (int moveIdx = 0; moveIdx < moves.size(); moveIdx++) {
            int[] mv = moves.get(moveIdx);
            boolean capture = b[mv[2]][mv[3]] != 0;

            // 无效裁减：浅层安静走法，静态评估远低于 α 则跳过
            if (!capture && !inCheck && depth <= 2 && bestScore > -INF + 1000) {
                int stand = red ? ChessRules.evaluate(b) : -ChessRules.evaluate(b);
                int margin = 300 * depth;
                if (stand + margin <= alpha) continue;
            }

            // LMR（Late Move Reduction）：排序靠后的安静走法降层搜索
            int lmrR = 0;
            if (useLmr && !capture && !inCheck && depth >= 3 && moveIdx >= 4) {
                lmrR = 1 + (moveIdx - 4) / 4;
                if (depth >= 5) lmrR++;
                lmrR = Math.min(lmrR, depth - 2);
            }

            TrieMove undo = makeMove(b, mv);
            // 递归搜索可抛 SearchAbort：必须 finally 回滚，否则异常冒泡后棋盘残留脏子
            try {
                if (!ChessRules.inCheckOnBoard(b, red)) {
                    boolean givesCheck = ChessRules.inCheckOnBoard(b, !red);
                    int ext = (givesCheck && checkExt < 2) ? 1 : 0; // 将军延伸上限 2 层
                    int baseDepth = depth - 1 + ext; // 可能为 0 或负
                    int newCheckExt = checkExt + ext;
                    int searchDepth = baseDepth - lmrR; // 可能为 0 或负 → 走 quiescence

                    int score;
                    if (searchDepth <= 0) {
                        score = -quiescence(b, !red, -(alpha+1), -alpha, ply + 1, newCheckExt);
                    } else {
                        score = -negamax(b, !red, searchDepth, -beta, -alpha, ply + 1, newCheckExt);
                    }
                    // LMR 找到好走法后需用完整深度重搜确认
                    if (lmrR > 0 && score > alpha) {
                        int fullDepth = Math.max(1, baseDepth); // 重搜至少 depth=1
                        score = -negamax(b, !red, fullDepth, -beta, -alpha, ply + 1, newCheckExt);
                    }

                    if (score > bestScore) { bestScore = score; bestMove = encodeMove(mv); }
                    if (score > alpha) alpha = score;
                }
            } finally {
                unmakeMove(b, mv, undo);
            }
            if (alpha >= beta) {
                if (!capture && bestMove != 0) updateKillerAndHistory(b, mv, ply, depth);
                break;
            }
        }

        int flag = bestScore <= origAlpha ? FLAG_UPPER : bestScore >= beta ? FLAG_LOWER : FLAG_EXACT;
        storeTt(key, bestMove, bestScore, depth, flag);
        return bestScore;
    }

    // ── 静态搜索 ─────────────────────────────────────────

    private int quiescence(int[][] b, boolean red, int alpha, int beta, int ply, int checkExt) throws SearchAbort {
        nodeCount++;
        if ((nodeCount & 0xFFF) == 0 && shouldStop()) throw new SearchAbort();

        boolean inCheck = ChessRules.inCheckOnBoard(b, red);
        int stand = enhancedEval(b, red);
        if (stand >= beta) return beta;
        if (stand > alpha) alpha = stand;

        // Delta 裁剪：静态评估加上最大吃子价值仍低于 α → 直接返回
        if (!inCheck && stand + 1100 < alpha && useLmr) {
            return alpha;
        }

        List<int[]> moves = inCheck ? orderedMoves(b, red, ply, 0) : captureMoves(b, red);

        for (int[] mv : moves) {
            boolean capture = b[mv[2]][mv[3]] != 0;
            TrieMove undo = makeMove(b, mv);
            // 递归可抛 SearchAbort：finally 保证回滚
            try {
                if (!ChessRules.inCheckOnBoard(b, red)) {
                    boolean givesCheck = ChessRules.inCheckOnBoard(b, !red);
                    int ext = (givesCheck && checkExt < 2) ? 1 : 0;
                    int newCheckExt = checkExt + ext;
                    int score = -quiescence(b, !red, -beta, -alpha, ply + 1, newCheckExt);
                    if (score > alpha) alpha = score;
                }
            } finally {
                unmakeMove(b, mv, undo);
            }
            if (alpha >= beta) {
                if (!capture) { int code = encodeMove(mv); history[code] += 4; }
                break;
            }
        }
        return alpha;
    }

    // ── 评估 ─────────────────────────────────────────────

    /**
     * 增强评估（从走子方视角，正=本方占优）：
     * 子力 + 位置表 + 子力活性（mobility）+ 将帅安全 + 先手优势。
     */
    private int enhancedEval(int[][] b, boolean red) {
        int base = ChessRules.evaluate(b); // 红方视角
        int score = red ? base : -base;    // 转走子方视角

        // 子力活性：己方伪合法走法数 - 对方，限制在 ±12，每步 3 分
        int myMob = ChessRules.countPseudoMoves(b, red);
        int oppMob = ChessRules.countPseudoMoves(b, !red);
        int mob = Math.max(-12, Math.min(12, myMob - oppMob));
        score += mob * 3;

        // 将帅安全：九宫内己方士/象守卫数量奖励，对方对称
        score += kingSafety(b, red);
        score -= kingSafety(b, !red);

        score += 20; // 先手优势（Tempo bonus）
        return score;
    }

    /**
     * 将帅安全：统计九宫内己方士/象守卫数量。
     * 0 个 -12 / 1 个 +0 / 2 个 +12 / 3 个以上 +20。
     */
    private int kingSafety(int[][] b, boolean red) {
        int rLo = red ? 7 : 0;
        int rHi = red ? 9 : 2;
        int defenders = 0;
        for (int c = 3; c <= 5; c++) {
            for (int r = rLo; r <= rHi; r++) {
                int p = b[c][r];
                if (p == 0) continue;
                if ((p > 0) == red) {
                    int abs = Math.abs(p);
                    if (abs == ChessRules.ADVISOR || abs == ChessRules.ELEPHANT) defenders++;
                }
            }
        }
        return defenders == 0 ? -12 : defenders <= 2 ? (defenders - 1) * 12 : 20;
    }

    // ── 走法生成与排序 ─────────────────────────────────────

    private List<int[]> orderedMoves(int[][] b, boolean red, int ply, int ttMoveCode) {
        List<int[]> legal = ChessRules.legalMoves(b, red);
        int k0 = kill(ply, 0), k1 = kill(ply, 1);
        for (int[] mv : legal) {
            boolean capture = b[mv[2]][mv[3]] != 0;
            int score;
            if (capture) {
                int victim = Math.abs(b[mv[2]][mv[3]]);
                int attacker = Math.abs(b[mv[0]][mv[1]]);
                score = 1_000_000 + ChessRules.PIECE_VAL[victim] * 16 - ChessRules.PIECE_VAL[attacker];
            } else {
                score = history[encodeMove(mv)];
            }
            int enc = encodeMove(mv);
            if (ttMoveCode != 0 && enc == ttMoveCode) score += 100_000_000;
            if (enc == k0) score += 10_000_000;
            else if (enc == k1) score += 9_000_000;
            mv[4] = score;
        }
        legal.sort(Comparator.comparingInt(m -> -m[4]));
        return legal;
    }

    private List<int[]> captureMoves(int[][] b, boolean red) {
        List<int[]> legal = ChessRules.legalMoves(b, red);
        List<int[]> caps = new ArrayList<>();
        for (int[] mv : legal) {
            if (b[mv[2]][mv[3]] == 0) continue;
            int victim = Math.abs(b[mv[2]][mv[3]]);
            int attacker = Math.abs(b[mv[0]][mv[1]]);
            mv[4] = ChessRules.PIECE_VAL[victim] * 16 - ChessRules.PIECE_VAL[attacker];
            caps.add(mv);
        }
        caps.sort(Comparator.comparingInt(m -> -m[4]));
        return caps;
    }

    private int kill(int ply, int slot) { return (ply >= 0 && ply < killers.length) ? killers[ply][slot] : 0; }

    private void updateKillerAndHistory(int[][] b, int[] mv, int ply, int depth) {
        int code = encodeMove(mv);
        if (ply >= 0 && ply < killers.length) {
            if (killers[ply][0] != code) { killers[ply][1] = killers[ply][0]; killers[ply][0] = code; }
        }
        history[code] += depth * depth;
    }

    // ── 置换表 ─────────────────────────────────────────

    private void storeTt(long key, int move, int score, int depth, int flag) {
        int idx = (int) key & TT_MASK;
        if (ttDepth[idx] <= depth) {
            ttKey[idx] = key; ttMove[idx] = move; ttScore[idx] = score;
            ttDepth[idx] = depth; ttFlag[idx] = flag;
        }
    }

    private static int encodeMove(int[] mv) { return (mv[0] * 10 + mv[1]) * 90 + (mv[2] * 10 + mv[3]); }

    // ── 走法执行 ─────────────────────────────────────────

    private static final class TrieMove { final int captured; TrieMove(int c) { captured = c; } }

    private TrieMove makeMove(int[][] b, int[] mv) {
        TrieMove undo = new TrieMove(b[mv[2]][mv[3]]);
        b[mv[2]][mv[3]] = b[mv[0]][mv[1]]; b[mv[0]][mv[1]] = 0;
        return undo;
    }

    private void unmakeMove(int[][] b, int[] mv, TrieMove undo) {
        b[mv[0]][mv[1]] = b[mv[2]][mv[3]]; b[mv[2]][mv[3]] = undo.captured;
    }

    // ── Zobrist ─────────────────────────────────────────

    private long zobrist(int[][] b, boolean red) {
        long h = 0;
        for (int c = 0; c < 9; c++) for (int r = 0; r < 10; r++) {
            int p = b[c][r];
            if (p == 0) continue;
            h ^= ZOBRIST[p > 0 ? 0 : 1][Math.abs(p)][c * 10 + r];
        }
        if (red) h ^= ZOBRIST_SIDE;
        return h;
    }

    private boolean shouldStop() { return (System.nanoTime() - timeStartNs) >= timeBudgetMs * 1_000_000L; }

    private static final class SearchAbort extends RuntimeException {}
}