package com.wzz.game_console.client.screens.games.chess;

import java.util.ArrayList;
import java.util.List;

/**
 * 中国象棋公共规则库。
 * <p>
 * 提供两套引擎共用的基础能力：伪合法走法生成、将军检测、FEN / UCI 坐标转换，
 * 以及内置引擎使用的棋子价值与位置价值表。
 * <p>
 * 棋盘约定（与 {@link com.wzz.game_console.client.screens.games.ChessGameScreen} 一致）：
 * {@code board[col][row]}，0=空，正数=红方，负数=黑方；对角线坐标转换见具体方法。
 */
public final class ChessRules {

    public static final int COLS = 9, ROWS = 10;
    public static final int GENERAL = 1, ADVISOR = 2, ELEPHANT = 3,
            HORSE = 4, CHARIOT = 5, CANNON = 6, SOLDIER = 7;

    /** 走法生成方向常量（避免搜索中每次调用重复创建数组） */
    private static final int[][] DIR_ORTHO = {{0, 1}, {0, -1}, {1, 0}, {-1, 0}};
    private static final int[][] DIR_DIAG = {{1, 1}, {1, -1}, {-1, 1}, {-1, -1}};
    private static final int[][] DIR_ELEPHANT = {{2, 2}, {2, -2}, {-2, 2}, {-2, -2}};
    private static final int[][] HORSE_LEGS = {{1, 0}, {-1, 0}, {0, 1}, {0, -1}};
    private static final int[][][] HORSE_DEST = {
        {{2, 1}, {2, -1}}, {{-2, 1}, {-2, -1}}, {{1, 2}, {-1, 2}}, {{1, -2}, {-1, -2}}
    };

    private ChessRules() {}

    // ── 走法生成 ──────────────────────────────────────────────

    /**
     * 生成某棋子的伪合法走法（未过滤"走后自将"），返回目标点列表。
     *
     * @param b   棋盘
     * @param col 棋子列坐标
     * @param row 棋子行坐标
     * @return {@code [col,row]} 目标点列表
     */
    public static List<int[]> pseudoMoves(int[][] b, int col, int row) {
        int p = b[col][row];
        if (p == 0) return new ArrayList<>();
        boolean red = p > 0;
        int abs = Math.abs(p);
        List<int[]> m = new ArrayList<>();
        switch (abs) {
            case GENERAL -> generalMoves(b, col, row, red, m);
            case ADVISOR -> advisorMoves(b, col, row, red, m);
            case ELEPHANT -> elephantMoves(b, col, row, red, m);
            case HORSE -> horseMoves(b, col, row, red, m);
            case CHARIOT -> chariotMoves(b, col, row, red, m);
            case CANNON -> cannonMoves(b, col, row, red, m);
            case SOLDIER -> soldierMoves(b, col, row, red, m);
        }
        return m;
    }

    /** 生成红方或黑方的全部伪合法走法，返回 {@code {fc,fr,tc,tr}} */
    public static List<int[]> allPseudoMoves(int[][] b, boolean red) {
        List<int[]> out = new ArrayList<>(64);
        for (int c = 0; c < COLS; c++) {
            for (int r = 0; r < ROWS; r++) {
                int p = b[c][r];
                if (p == 0) continue;
                if ((red && p > 0) || (!red && p < 0)) {
                    for (int[] t : pseudoMoves(b, c, r)) {
                        out.add(new int[]{c, r, t[0], t[1]});
                    }
                }
            }
        }
        return out;
    }

    /**
     * 生成红方或黑方的全部合法走法（过滤"走后自将"），返回 {@code {fc,fr,tc,tr,scratch}}。
     * <p>
     * 每个走法占用 5 个 int，第 5 位是排序分草稿槽，由调用方自行写入（供 AI 走法排序复用数组，
     * 避免排序过程产生额外对象）。不关心排序的调用方忽略该槽位即可。
     */
    public static List<int[]> legalMoves(int[][] b, boolean red) {
        List<int[]> out = new ArrayList<>(64);
        for (int c = 0; c < COLS; c++) {
            for (int r = 0; r < ROWS; r++) {
                int p = b[c][r];
                if (p == 0) continue;
                if ((red && p > 0) || (!red && p < 0)) {
                    for (int[] t : pseudoMoves(b, c, r)) {
                        int tc = t[0], tr = t[1];
                        int captured = b[tc][tr];
                        int piece = b[c][r];
                        b[tc][tr] = piece;
                        b[c][r] = 0;
                        if (!inCheckOnBoard(b, red)) out.add(new int[]{c, r, tc, tr, 0});
                        b[c][r] = piece;
                        b[tc][tr] = captured;
                    }
                }
            }
        }
        return out;
    }

    private static void tryAdd(int[][] b, List<int[]> m, int c, int r, boolean red) {
        if (c < 0 || c >= COLS || r < 0 || r >= ROWS) return;
        int t = b[c][r];
        if (t == 0 || (red && t < 0) || (!red && t > 0)) m.add(new int[]{c, r});
    }

    private static void generalMoves(int[][] b, int c, int r, boolean red, List<int[]> m) {
        for (int[] d : DIR_ORTHO) {
            int nc = c + d[0], nr = r + d[1];
            if (inPalace(nc, nr, red)) tryAdd(b, m, nc, nr, red);
        }
    }

    private static void advisorMoves(int[][] b, int c, int r, boolean red, List<int[]> m) {
        for (int[] d : DIR_DIAG) {
            int nc = c + d[0], nr = r + d[1];
            if (inPalace(nc, nr, red)) tryAdd(b, m, nc, nr, red);
        }
    }

    private static void elephantMoves(int[][] b, int c, int r, boolean red, List<int[]> m) {
        for (int[] d : DIR_ELEPHANT) {
            int nc = c + d[0], nr = r + d[1];
            if (nc < 0 || nc >= COLS || nr < 0 || nr >= ROWS) continue;
            if (red && nr < 5) continue;
            if (!red && nr > 4) continue;
            int mc = c + d[0] / 2, mr = r + d[1] / 2;
            if (b[mc][mr] != 0) continue;
            tryAdd(b, m, nc, nr, red);
        }
    }

    private static void horseMoves(int[][] b, int c, int r, boolean red, List<int[]> m) {
        for (int i = 0; i < 4; i++) {
            int lc = c + HORSE_LEGS[i][0], lr = r + HORSE_LEGS[i][1];
            if (lc < 0 || lc >= COLS || lr < 0 || lr >= ROWS) continue;
            if (b[lc][lr] != 0) continue;
            for (int[] d : HORSE_DEST[i]) tryAdd(b, m, c + d[0], r + d[1], red);
        }
    }

    private static void chariotMoves(int[][] b, int c, int r, boolean red, List<int[]> m) {
        for (int[] d : DIR_ORTHO) {
            for (int i = 1; i < 10; i++) {
                int nc = c + d[0] * i, nr = r + d[1] * i;
                if (nc < 0 || nc >= COLS || nr < 0 || nr >= ROWS) break;
                int t = b[nc][nr];
                if (t == 0) {
                    m.add(new int[]{nc, nr});
                    continue;
                }
                if ((red && t < 0) || (!red && t > 0)) m.add(new int[]{nc, nr});
                break;
            }
        }
    }

    private static void cannonMoves(int[][] b, int c, int r, boolean red, List<int[]> m) {
        for (int[] d : DIR_ORTHO) {
            boolean jumped = false;
            for (int i = 1; i < 10; i++) {
                int nc = c + d[0] * i, nr = r + d[1] * i;
                if (nc < 0 || nc >= COLS || nr < 0 || nr >= ROWS) break;
                int t = b[nc][nr];
                if (!jumped) {
                    if (t == 0) m.add(new int[]{nc, nr});
                    else jumped = true;
                } else {
                    if (t != 0) {
                        if ((red && t < 0) || (!red && t > 0)) m.add(new int[]{nc, nr});
                        break;
                    }
                }
            }
        }
    }

    private static void soldierMoves(int[][] b, int c, int r, boolean red, List<int[]> m) {
        int fwd = red ? -1 : 1;
        boolean crossed = red ? (r < 5) : (r > 4);
        tryAdd(b, m, c, r + fwd, red);
        if (crossed) {
            tryAdd(b, m, c + 1, r, red);
            tryAdd(b, m, c - 1, r, red);
        }
    }

    /** 是否在九宫格内 */
    public static boolean inPalace(int c, int r, boolean red) {
        if (c < 3 || c > 5) return false;
        return red ? (r >= 7 && r <= 9) : (r >= 0 && r <= 2);
    }

    /** 该走法是否为吃子走法 */
    public static boolean isCapture(int[][] b, int tc, int tr) {
        return b[tc][tr] != 0;
    }

    /** 检测某方主帅是否处于被将军状态（含飞将） */
    public static boolean inCheckOnBoard(int[][] b, boolean isRed) {
        int gc = -1, gr = -1;
        outer:
        for (int c = 0; c < COLS; c++) {
            for (int r = 0; r < ROWS; r++) {
                if (b[c][r] == (isRed ? GENERAL : -GENERAL)) {
                    gc = c;
                    gr = r;
                    break outer;
                }
            }
        }
        if (gc < 0) return true;
        for (int c = 0; c < COLS; c++) {
            for (int r = 0; r < ROWS; r++) {
                int p = b[c][r];
                if (p == 0) continue;
                if ((p > 0) == isRed) continue;
                for (int[] a : pseudoMoves(b, c, r)) {
                    if (a[0] == gc && a[1] == gr) return true;
                }
            }
        }
        // 飞将
        if (gc >= 3 && gc <= 5) {
            for (int r = 0; r < ROWS; r++) {
                if (b[gc][r] == (isRed ? -GENERAL : GENERAL)) {
                    int r1 = Math.min(gr, r) + 1, r2 = Math.max(gr, r);
                    boolean clear = true;
                    for (int rr = r1; rr < r2; rr++) {
                        if (b[gc][rr] != 0) {
                            clear = false;
                            break;
                        }
                    }
                    if (clear) return true;
                }
            }
        }
        return false;
    }

    // ── FEN / UCI 坐标 ────────────────────────────────────────

    /** 棋子编号 1..7 对应 FEN 字母（红大写，黑小写） */
    private static final String PIECE_LETTER = "KABNRCP";

    /** 棋盘转 FEN（中国象棋标准 XFEN） */
    public static String toFen(int[][] b, boolean redTurn) {
        StringBuilder sb = new StringBuilder();
        for (int r = 0; r < ROWS; r++) {
            int empty = 0;
            for (int c = 0; c < COLS; c++) {
                int p = b[c][r];
                if (p == 0) {
                    empty++;
                    continue;
                }
                if (empty > 0) {
                    sb.append(empty);
                    empty = 0;
                }
                boolean red = p > 0;
                int abs = Math.abs(p);
                char ch = PIECE_LETTER.charAt(abs - 1);
                if (!red) ch = Character.toLowerCase(ch);
                sb.append(ch);
            }
            if (empty > 0) sb.append(empty);
            if (r < ROWS - 1) sb.append('/');
        }
        sb.append(' ').append(redTurn ? 'w' : 'b').append(" - - 0 1");
        return sb.toString();
    }

    /** 解析 UCI 走法（如 {@code h2e2}）为 {@code {fc,fr,tc,tr}}，非法返回 null */
    public static int[] parseUciMove(String uci) {
        if (uci == null || uci.length() < 4) return null;
        int fc = uci.charAt(0) - 'a';
        int fr = 9 - (uci.charAt(1) - '0');
        int tc = uci.charAt(2) - 'a';
        int tr = 9 - (uci.charAt(3) - '0');
        if (fc < 0 || fc >= COLS || fr < 0 || fr >= ROWS
                || tc < 0 || tc >= COLS || tr < 0 || tr >= ROWS) {
            return null;
        }
        return new int[]{fc, fr, tc, tr};
    }

    /** 走法转 UCI 坐标串（UCI rank 0 = 底部=红方底线，需翻转行号） */
    public static String toUciMove(int fc, int fr, int tc, int tr) {
        return "" + (char) ('a' + fc) + (9 - fr) + (char) ('a' + tc) + (9 - tr);
    }

    // ── 估值表（供内置引擎使用） ────────────────────────────────

    /** 棋子基础分（下标=棋子编号） */
    public static final int[] PIECE_VAL = {
        0,
        10000, // 将
        200,   // 士
        220,   // 象
        400,   // 马
        900,   // 车
        450,   // 炮
        100    // 兵
    };

    /** 位置价值表，从黑方视角定义（row0=黑方底线），红方行号镜像 */
    public static final int[][] PST_HORSE = {
        { 0,  0, -2,  0,  0,  0, -2,  0,  0, 0},
        { 0,  4,  6,  8,  4,  4,  6,  4,  0, 0},
        { 2,  8, 12, 14, 12, 10, 12,  8,  2, 0},
        { 4, 14, 20, 24, 20, 18, 20, 14,  4, 0},
        { 2, 12, 18, 20, 18, 16, 18, 12,  2, 0},
        { 0,  4, 12, 14, 12, 10, 12,  4,  0, 0},
        { 0,  8, 12, 12, 12, 10, 12,  8,  0, 0},
        { 0,  0,  8, 10,  8,  8,  8,  0,  0, 0},
        { 0,  2,  6,  4,  6,  4,  4,  2,  0, 0},
        { 0,  0,  2,  0,  0,  0,  2,  0,  0, 0},
    };

    public static final int[][] PST_CHARIOT = {
        {14, 14, 12, 18, 16, 18, 12, 14, 14, 0},
        {16, 20, 18, 24, 26, 24, 18, 20, 16, 0},
        {12, 12, 12, 18, 18, 18, 12, 12, 12, 0},
        {12, 18, 16, 22, 22, 22, 16, 18, 12, 0},
        {12, 14, 12, 18, 18, 18, 12, 14, 12, 0},
        {12, 16, 14, 20, 20, 20, 14, 16, 12, 0},
        {12, 12, 12, 18, 18, 18, 12, 12, 12, 0},
        {12, 18, 16, 22, 22, 22, 16, 18, 12, 0},
        {16, 20, 18, 24, 26, 24, 18, 20, 16, 0},
        {14, 14, 12, 18, 16, 18, 12, 14, 14, 0},
    };

    public static final int[][] PST_CANNON = {
        { 6,  4,  0, -10, -12, -10,  0,  4,  6, 0},
        { 2,  2,  0,  -4,  -14,  -4,  0,  2,  2, 0},
        { 2,  6,  4,   0,   -6,   0,  4,  6,  2, 0},
        { 0,  0,  0,   6,   10,   6,  0,  0,  0, 0},
        { 0,  2,  4,   6,   10,   6,  4,  2,  0, 0},
        { 0,  0,  4,   6,   10,   6,  4,  0,  0, 0},
        { 0,  2,  0,   4,    8,   4,  0,  2,  0, 0},
        {-2, -4, -2,   4,    8,   4, -2, -4, -2, 0},
        { 0,  0,  2,   4,    6,   4,  2,  0,  0, 0},
        { 0,  2,  4,   6,    6,   6,  4,  2,  0, 0},
    };

    /** 兵（过河前后差别大） */
    public static final int[][] PST_SOLDIER = {
        { 0,  0,  0,  0,  0,  0,  0,  0,  0, 0},
        { 0,  0,  0,  0,  0,  0,  0,  0,  0, 0},
        { 0,  0,  0,  0,  0,  0,  0,  0,  0, 0},
        { 8, 18, 28, 40, 40, 40, 28, 18,  8, 0},
        {14, 24, 38, 52, 60, 52, 38, 24, 14, 0},
        {22, 34, 50, 64, 76, 64, 50, 34, 22, 0},
        {34, 48, 62, 76, 86, 76, 62, 48, 34, 0},
        { 6, 14, 22, 32, 36, 32, 22, 14,  6, 0},
        { 4, 10, 14, 20, 24, 20, 14, 10,  4, 0},
        { 2,  6,  8, 10, 12, 10,  8,  6,  2, 0},
    };

    /** 士/仕位置表：九宫中央（4,1/4,8）最活跃，底线次之 */
    public static final int[][] PST_ADVISOR = {
        {0,0,0,10,0,10,0,0,0,0},
        {0,0,0,0,20,0,0,0,0,0},
        {0,0,0,0,10,0,0,0,0,0},
        {0,0,0,0,0,0,0,0,0,0},
        {0,0,0,0,0,0,0,0,0,0},
        {0,0,0,0,0,0,0,0,0,0},
        {0,0,0,0,0,0,0,0,0,0},
        {0,0,0,0,10,0,0,0,0,0},
        {0,0,0,0,20,0,0,0,0,0},
        {0,0,0,10,0,10,0,0,0,0},
    };

    /** 象/相位置表：连接状态（双象同一侧）/ 中心控制 */
    public static final int[][] PST_ELEPHANT = {
        {0,0,10,0,0,0,10,0,0,0},
        {0,0,0,0,0,0,0,0,0,0},
        {10,0,0,0,20,0,0,0,10,0},
        {0,0,10,0,0,0,10,0,0,0},
        {0,0,0,0,10,0,0,0,0,0},
        {0,0,0,0,10,0,0,0,0,0},
        {0,0,10,0,0,0,10,0,0,0},
        {0,0,0,0,0,0,0,0,0,0},
        {10,0,0,0,20,0,0,0,10,0},
        {0,0,10,0,0,0,10,0,0,0},
    };

    /** 棋子位置额外得分（从该方视角）。PST 表按 行=rank（横排）/ 列=col 设计，r 已做红方行号镜像 */
    public static int pstBonus(int abs, int col, int row, boolean red) {
        int r = red ? (9 - row) : row;
        // ★ Bug修复：原先写成 PST_xxx[col][r]，把列当行用（转置索引），位置分整体错位；
        //   表尾第 10 列是填充 0，转置后 rank9 一律得 0 分
        return switch (abs) {
            case HORSE -> PST_HORSE[r][col];
            case CHARIOT -> PST_CHARIOT[r][col];
            case CANNON -> PST_CANNON[r][col];
            case SOLDIER -> PST_SOLDIER[r][col];
            case ADVISOR -> PST_ADVISOR[r][col];
            case ELEPHANT -> PST_ELEPHANT[r][col];
            default -> 0;
        };
    }

    /** 统计一方全部伪合法走法数量（用于子力活性评估） */
    public static int countPseudoMoves(int[][] b, boolean red) {
        int count = 0;
        for (int c = 0; c < COLS; c++) {
            for (int r = 0; r < ROWS; r++) {
                int p = b[c][r];
                if (p == 0) continue;
                if ((red && p > 0) || (!red && p < 0)) {
                    count += pseudoMoves(b, c, r).size();
                }
            }
        }
        return count;
    }

    /** 静态局面估值（从红方视角：正=红优，负=黑优） */
    public static int evaluate(int[][] b) {
        int score = 0;
        for (int c = 0; c < COLS; c++) {
            for (int r = 0; r < ROWS; r++) {
                int p = b[c][r];
                if (p == 0) continue;
                boolean red = p > 0;
                int abs = Math.abs(p);
                int val = PIECE_VAL[abs] + pstBonus(abs, c, r, red);
                score += red ? val : -val;
            }
        }
        return score;
    }
}