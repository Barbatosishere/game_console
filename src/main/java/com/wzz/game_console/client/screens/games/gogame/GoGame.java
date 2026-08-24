package com.wzz.game_console.client.screens.games.gogame;

import java.util.*;

public class GoGame implements AutoCloseable {
    private static final int BOARD_SIZE = 19;
    private static final int[][] DIRS = {{0,1}, {1,0}, {0,-1}, {-1,0}};
    private GoPlayer[][] board;
    private GoPlayer currentPlayer;
    private boolean gameOver;
    private int blackCaptured;
    private int whiteCaptured;
    private boolean aiMode;
    private int consecutivePasses;
    private List<GoMove> moveHistory;
    private GoAI ai;
    /** Whether reset() should create the configured AI engine. */
    private final boolean initializeAi;
    /** 历史局面哈希：劫争判定（禁止全局同型，中国规则），新局面不得与任何历史局面重复 */
    private final Set<Long> positionHistory = new HashSet<>();
    /** 对局状态锁：placeStone/pass 写、getMoveHistory/getPositionHistory 读，跨线程同步 */
    private final Object stateLock = new Object();
    /** 调试开关：为 true 时输出劫争判定的详细追踪信息（默认关闭，正常对局不刷屏） */
    private static final boolean DEBUG_KO = false;

    // ── Zobrist 哈希表 ──────────────────────────────────────────
    // 64 位随机值，为每个 (x, y, 棋子颜色) 分配一个独立哈希，
    // 局面哈希 = 所有棋子的 Zobrist 值异或和。碰撞概率极低（2^-64），
    // 且增量更新可 O(1) 计算，替代原 Arrays.deepHashCode 的 32 位 int 碰撞风险。
    private static final long[][][] ZOBRIST_TABLE = new long[BOARD_SIZE][BOARD_SIZE][3];
    private static final java.util.Random ZOBRIST_RND = new java.util.Random(0xDEADBEEF);
    static {
        for (int x = 0; x < BOARD_SIZE; x++)
            for (int y = 0; y < BOARD_SIZE; y++)
                for (int p = 0; p < 3; p++)
                    ZOBRIST_TABLE[x][y][p] = ZOBRIST_RND.nextLong();
    }
    
    public GoGame() {
        this(true);
    }

    /**
     * Creates a game and optionally initializes its AI engine.
     *
     * <p>Passing {@code false} creates a rules-only game. This path does not
     * read {@code GameSettings} and does not start an external engine, making
     * it suitable for trainers and rule/evaluation code.</p>
     *
     * @param initializeAi whether to create the configured AI engine
     */
    public GoGame(boolean initializeAi) {
        this.initializeAi = initializeAi;
        this.board = new GoPlayer[BOARD_SIZE][BOARD_SIZE];
        this.moveHistory = new ArrayList<>();
        // reset() 已声明为 final，避免构造器调用可覆写方法的 this-escape 风险
        reset();
    }

    /** Creates a game containing only the Go rules and board state. */
    public static GoGame rulesOnly() {
        return new GoGame(false);
    }

    public final void reset() {
        // 初始化棋盘
        for (int x = 0; x < BOARD_SIZE; x++) {
            for (int y = 0; y < BOARD_SIZE; y++) {
                board[x][y] = GoPlayer.NONE;
            }
        }

        currentPlayer = GoPlayer.BLACK;
        gameOver = false;
        blackCaptured = 0;
        whiteCaptured = 0;
        consecutivePasses = 0;
        moveHistory.clear();
        // 空棋盘作为初始历史局面（用于劫争的同型判定）
        positionHistory.clear();
        positionHistory.add(boardHash());

        // 重新初始化 AI（支持引擎切换）；规则专用局面始终不创建 AI。
        if (initializeAi) {
            initAi();
        }
    }

    /**
     * 根据 GameSettings 初始化 AI 引擎。
     * 支持在游戏中途切换引擎（重开时生效）。
     * 注意：如果 GameSettings 或其他依赖不可用，会回退到默认 MCTS。
     */
    public void initAi() {
        if (!initializeAi) {
            return;
        }
        // 关闭旧 AI 引擎（如果有外部进程需要清理）
        if (this.ai != null) {
            this.ai.shutdown();
        }

        // 尝试创建 AI 引擎
        try {
            this.ai = GoAI.create();
        } catch (Throwable t) {
            // 任何异常（包括 ClassNotFoundException、NoClassDefFoundError）
            // 都使用默认的 MCTSGoAI
            this.ai = new MCTSGoAI();
        }
    }

    /**
     * 设置自定义 AI 引擎（覆盖 GameSettings 配置）。
     */
    public void setAiEngine(GoAI aiEngine) {
        if (this.ai != null) {
            this.ai.shutdown();
        }
        this.ai = aiEngine;
    }

    /** Releases the configured AI engine, if this game owns one. */
    @Override
    public void close() {
        if (this.ai != null) {
            this.ai.shutdown();
            this.ai = null;
        }
    }

    /** Alias for callers that use explicit resource lifecycle naming. */
    public void shutdown() {
        close();
    }

    /**
     * 获取历史走法列表（供 KataGo 等外部引擎同步棋盘用）。
     */
    public List<GoMove> getMoveHistory() {
        synchronized (stateLock) {
            return Collections.unmodifiableList(new ArrayList<>(moveHistory));
        }
    }

    /**
     * 获取全部历史局面哈希集合（含当前局面），供 MCTS 搜索做 super-ko 检查。
     * 返回副本，避免外部修改影响内部状态。
     */
    public Set<Long> getPositionHistory() {
        synchronized (stateLock) {
            return new HashSet<>(positionHistory);
        }
    }

    /**
     * 当前局面的 Zobrist 哈希（供 MCTS 根节点初始化 super-ko 检查用）。
     */
    public long getCurrentHash() {
        return boardHash();
    }

    /**
     * 返回上一手落子位置 {x,y}；无上一手或上一手是弃权时返回 null。
     * 供 MCTS 根节点构造 plane 3（上一手位置）时使用，保证 train/serve 一致。
     */
    public int[] getLastMove() {
        if (moveHistory.isEmpty()) return null;
        GoMove last = moveHistory.get(moveHistory.size() - 1);
        if (last.x < 0 || last.y < 0) return null; // 弃权
        return new int[]{last.x, last.y};
    }

    /**
     * 获取当前回合数（落子数 / 2 + 1）。
     */
    public int moveHistorySize() {
        return moveHistory.size();
    }

    public boolean placeStone(int x, int y) {
        if (!canPlaceStone(x, y)) {
            return false;
        }

        // 备份当前局面：自杀或劫争判定失败时整体回滚
        GoPlayer[][] backup = copyBoardInternal();

        // 放置棋子
        board[x][y] = currentPlayer;

        // 检查并移除被吃掉的对方棋子
        GoPlayer opponent = currentPlayer == GoPlayer.BLACK ? GoPlayer.WHITE : GoPlayer.BLACK;
        int capturedStones = 0;

        // 检查四个方向的相邻棋子群
        for (int[] dir : DIRS) {
            int nx = x + dir[0];
            int ny = y + dir[1];

            if (isValidPosition(nx, ny) && board[nx][ny] == opponent) {
                Set<int[]> group = getGroup(nx, ny);
                if (!hasLiberty(group)) {
                    // 移除这个无气的棋子群
                    for (int[] pos : group) {
                        board[pos[0]][pos[1]] = GoPlayer.NONE;
                        capturedStones++;
                    }
                }
            }
        }

// 劫争规则（禁止全局同型）：新局面与任何历史局面重复则非法，
        // 可防住单子提劫、双劫/长生等一切循环局面
        long newHash = boardHash();

        if (DEBUG_KO) {
            System.out.println("DEBUG: Move #" + (moveHistory.size() + 1) + " at (" + x + "," + y + ") by " + currentPlayer);
            System.out.println("DEBUG: New hash = " + newHash);
            System.out.println("DEBUG: Position history contains hash: " + positionHistory.contains(newHash));
            System.out.println("DEBUG: Captured stones: " + capturedStones);
        }

        // 全局同型：任何历史局面都不得再次出现，包括单子提劫的立即回提
        if (positionHistory.contains(newHash)) {
            if (DEBUG_KO) {
                System.out.println("DEBUG: MOVE REJECTED - Ko violation detected");
            }
            restoreBoard(backup);
            return false;
        }

        // 检查自杀规则 - 如果当前放置的棋子群无气且没有吃掉对方棋子，则为非法移动
        Set<int[]> currentGroup = getGroup(x, y);
        if (!hasLiberty(currentGroup) && capturedStones == 0) {
            restoreBoard(backup);
            return false;
        }

        // 更新捕获计数
        if (currentPlayer == GoPlayer.BLACK) {
            whiteCaptured += capturedStones;
        } else {
            blackCaptured += capturedStones;
        }

        // 记录移动与局面（记录当前玩家——落子者，与 pass() 一致）
        synchronized (stateLock) {
            moveHistory.add(new GoMove(x, y, currentPlayer, capturedStones));
            positionHistory.add(newHash);
        }
        consecutivePasses = 0;

        // 切换玩家
        switchPlayer();

        return true;
    }

    /**
     * 根据棋盘状态计算 Zobrist 哈希（静态方法，供 NeuralEvaluator 复用）。
     */
    public static long boardHash(GoPlayer[][] board) {
        long hash = 0;
        for (int x = 0; x < BOARD_SIZE; x++)
            for (int y = 0; y < BOARD_SIZE; y++)
                if (board[x][y] != GoPlayer.NONE)
                    hash ^= ZOBRIST_TABLE[x][y][board[x][y].ordinal()];
        return hash;
    }

    /** 当前局面的 Zobrist 哈希（64 位，碰撞概率极低） */
    private long boardHash() {
        return boardHash(this.board);
    }

    private GoPlayer[][] copyBoardInternal() {
        GoPlayer[][] copy = new GoPlayer[BOARD_SIZE][BOARD_SIZE];
        for (int i = 0; i < BOARD_SIZE; i++) copy[i] = board[i].clone();
        return copy;
    }

    private void restoreBoard(GoPlayer[][] backup) {
        // backup 是刚刚 copyBoardInternal() 深拷贝的独立副本，直接整行赋回即可，无需再 clone
        System.arraycopy(backup, 0, board, 0, BOARD_SIZE);
    }
    
    public boolean canPlaceStone(int x, int y) {
        if (gameOver || !isValidPosition(x, y) || board[x][y] != GoPlayer.NONE) {
            return false;
        }
        
        // 简单检查 - 实际实现中应该检查自杀规则和劫争规则
        return true;
    }
    
    public void pass() {
        if (gameOver) return;

        // 先按当前玩家记录弃权（原先在switchPlayer之后记录，会把弃权记到对手名下）
        synchronized (stateLock) {
            moveHistory.add(new GoMove(-1, -1, currentPlayer, 0)); // -1,-1表示弃权
        }

        consecutivePasses++;
        if (consecutivePasses >= 2) {
            endGame();
        } else {
            switchPlayer();
        }
    }
    
    public void resign() {
        gameOver = true;
        // 可以记录谁认输了
    }
    
    public void makeAiMove() {
        applyAiMove(computeAiMove());
    }

    /** 计算 AI 走法（不落子），返回 {x,y} 或 null（表示建议弃权）。供后台线程计算使用。 */
    public int[] computeAiMove() {
        if (!aiMode || ai == null || gameOver) {
            return null;
        }
        return ai.getBestMove(this);
    }

    /** 将 AI 走法应用到棋盘（含非法回退扫描），应在客户端线程调用。 */
    public void applyAiMove(int[] move) {
        if (move == null) {
            // AI 返回 null 表示建议弃权（KataGo 的 pass/resign），尊重 AI 选择，不得强行落子
            pass();
            return;
        }
        // 最优落子非法（劫争/自杀）时，扫描棋盘找第一个合法点，避免直接弃权
        if (placeStone(move[0], move[1])) {
            return;
        }
        for (int x = 0; x < BOARD_SIZE; x++) {
            for (int y = 0; y < BOARD_SIZE; y++) {
                if (placeStone(x, y)) return;
            }
        }
        // 全盘无合法落子则弃权
        pass();
    }
    
    private void switchPlayer() {
        currentPlayer = currentPlayer == GoPlayer.BLACK ? GoPlayer.WHITE : GoPlayer.BLACK;
    }
    
    private void endGame() {
        gameOver = true;
        // 可以在这里计算最终得分
    }
    
    private boolean isValidPosition(int x, int y) {
        return x >= 0 && x < BOARD_SIZE && y >= 0 && y < BOARD_SIZE;
    }
    
    private Set<int[]> getGroup(int x, int y) {
        Set<int[]> group = new HashSet<>();
        GoPlayer color = board[x][y];
        
        if (color == GoPlayer.NONE) {
            return group;
        }
        
        Stack<int[]> stack = new Stack<>();
        boolean[][] visited = new boolean[BOARD_SIZE][BOARD_SIZE];
        
        stack.push(new int[]{x, y});
        
        while (!stack.isEmpty()) {
            int[] pos = stack.pop();
            int px = pos[0], py = pos[1];
            
            if (visited[px][py]) continue;
            visited[px][py] = true;
            group.add(new int[]{px, py});
            
            // 检查四个方向
            for (int[] dir : DIRS) {
                int nx = px + dir[0];
                int ny = py + dir[1];
                
                if (isValidPosition(nx, ny) && !visited[nx][ny] && board[nx][ny] == color) {
                    stack.push(new int[]{nx, ny});
                }
            }
        }
        
        return group;
    }
    
    private boolean hasLiberty(Set<int[]> group) {
        for (int[] pos : group) {
            for (int[] dir : DIRS) {
                int nx = pos[0] + dir[0];
                int ny = pos[1] + dir[1];
                
                if (isValidPosition(nx, ny) && board[nx][ny] == GoPlayer.NONE) {
                    return true;
                }
            }
        }
        return false;
    }
    
    // Getter方法
    public GoPlayer getStone(int x, int y) {
        if (!isValidPosition(x, y)) return GoPlayer.NONE;
        return board[x][y];
    }
    
    public GoPlayer getCurrentPlayer() { return currentPlayer; }
    public boolean isGameOver() { return gameOver; }
    public int getBlackCaptured() { return blackCaptured; }
    public int getWhiteCaptured() { return whiteCaptured; }
    public boolean isAiMode() { return aiMode; }
    public void setAiMode(boolean aiMode) { this.aiMode = aiMode; }
    public int getBoardSize() { return BOARD_SIZE; }

    // 获取棋盘副本供AI使用
    public GoPlayer[][] getBoardCopy() {
        GoPlayer[][] copy = new GoPlayer[BOARD_SIZE][BOARD_SIZE];
        for (int x = 0; x < BOARD_SIZE; x++) {
            for (int y = 0; y < BOARD_SIZE; y++) {
                copy[x][y] = board[x][y];
            }
        }
        return copy;
    }

    // ═══════════════════════════════════════════════════════════════
    //  计分（中国规则数子法）
    // ═══════════════════════════════════════════════════════════════

    /**
     * 数子法计算领地（flood-fill 无子区域，判断属于哪方）。
     * 得分 = 棋盘活子数 + 单独围空（不含提子数，避免双重计分）。
     * @return [黑领地, 白领地]
     */
    public int[] calcTerritory() {
        boolean[][] visited = new boolean[BOARD_SIZE][BOARD_SIZE];
        int blackT = 0, whiteT = 0;

        // 先统计棋盘上的活子数（死子不计入得分；但活子数只需数黑/白总数，
        // 领地统计用空点相邻判定，对死子已通过 visited 排除）
        for (int x = 0; x < BOARD_SIZE; x++)
            for (int y = 0; y < BOARD_SIZE; y++) {
                GoPlayer s = board[x][y];
                if (s == GoPlayer.BLACK) blackT++;
                else if (s == GoPlayer.WHITE) whiteT++;
            }

        // 再统计空点领地
        for (int x = 0; x < BOARD_SIZE; x++) {
            for (int y = 0; y < BOARD_SIZE; y++) {
                if (visited[x][y] || board[x][y] != GoPlayer.NONE) continue;
                // BFS 找连通空区
                java.util.List<int[]> region = new java.util.ArrayList<>();
                java.util.Queue<int[]> queue = new java.util.LinkedList<>();
                queue.add(new int[]{x, y});
                boolean touchBlack = false, touchWhite = false;
                while (!queue.isEmpty()) {
                    int[] pos = queue.poll();
                    int px = pos[0], py = pos[1];
                    if (px < 0 || px >= BOARD_SIZE || py < 0 || py >= BOARD_SIZE) continue;
                    if (visited[px][py]) continue;
                    visited[px][py] = true;
                    GoPlayer st = board[px][py];
                    if (st == GoPlayer.BLACK) { touchBlack = true; continue; }
                    if (st == GoPlayer.WHITE) { touchWhite = true; continue; }
                    region.add(new int[]{px, py});
                    for (int[] d : DIRS)
                        queue.add(new int[]{px + d[0], py + d[1]});
                }
                int pts = region.size();
                if (touchBlack && !touchWhite) blackT += pts;
                else if (touchWhite && !touchBlack) whiteT += pts;
                // 争议地带不计
            }
        }
        return new int[]{blackT, whiteT};
    }

    /**
     * 计算某一方的最终得分（中国规则数子法，黑贴 3.75 子）。
     * @param player 视角
     * @return 该方的得分
     */
    public double getScore(GoPlayer player) {
        int[] territory = calcTerritory();
        double score = (player == GoPlayer.BLACK ? territory[0] : territory[1]);
        if (player == GoPlayer.WHITE) score += 3.75; // 贴目
        return score;
    }

    /**
     * 计算两方的分差（从指定视角看，正数表示该方领先）。
     * @param perspective 视角方
     * @return 分差（视角方 - 对方）
     */
    public double getScoreMargin(GoPlayer perspective) {
        GoPlayer opponent = perspective == GoPlayer.BLACK ? GoPlayer.WHITE : GoPlayer.BLACK;
        return getScore(perspective) - getScore(opponent);
    }
}