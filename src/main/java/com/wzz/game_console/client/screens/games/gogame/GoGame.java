package com.wzz.game_console.client.screens.games.gogame;

import java.util.*;

public class GoGame implements AutoCloseable {
    private static final int BOARD_SIZE = 19;
    /** Shared komi configuration used by scoring and external engines. */
    public static final double DEFAULT_KOMI = 7.5;

    public static double getConfiguredKomi() {
        try {
            return normalizeKomi(com.wzz.game_console.util.GameSettings.getDouble(
                    "go", "komi", DEFAULT_KOMI));
        } catch (Throwable ignored) {
            return DEFAULT_KOMI;
        }
    }

    /** Returns the canonical finite komi accepted by Go scoring and network messages. */
    public static double normalizeKomi(double komi) {
        if (!Double.isFinite(komi)) return DEFAULT_KOMI;
        if (komi == 0.0d) return 0.0d;
        return Math.max(-100.0d, Math.min(100.0d, komi));
    }
    private static final int[][] DIRS = {{0,1}, {1,0}, {0,-1}, {-1,0}};
    private GoPlayer[][] board;
    private GoPlayer currentPlayer;
    private boolean gameOver;
    private GoPlayer resignedPlayer = GoPlayer.NONE;
    private int blackCaptured;
    private int whiteCaptured;
    private boolean aiMode;
    private int consecutivePasses;
    private List<GoMove> moveHistory;
    private GoAI ai;
    private boolean closed;
    /** Whether reset() should create the configured AI engine. */
    private final boolean initializeAi;
    /** Serializes engine creation and replacement without blocking board resets. */
    private final Object aiLifecycleLock = new Object();
    /** Incremented whenever the board lifecycle changes, invalidating in-flight engine creation. */
    private long aiLifecycleGeneration;
    /** Incremented after every committed board-state mutation. */
    private long positionRevision;
    /** Prevents duplicate lazy engine creation while allowing reset/close to proceed. */
    private boolean aiCreationInProgress;
    /** Generation for the currently reserved engine creation, or -1 when idle. */
    private long aiCreationGeneration = -1L;
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
        GoAI oldAi;
        synchronized (stateLock) {
            aiLifecycleGeneration++;
            positionRevision++;
            aiCreationInProgress = false;
            aiCreationGeneration = -1L;
            // 初始化棋盘
            for (int x = 0; x < BOARD_SIZE; x++) {
                for (int y = 0; y < BOARD_SIZE; y++) {
                    board[x][y] = GoPlayer.NONE;
                }
            }

            currentPlayer = GoPlayer.BLACK;
            gameOver = false;
            resignedPlayer = GoPlayer.NONE;
            blackCaptured = 0;
            whiteCaptured = 0;
            consecutivePasses = 0;
            moveHistory.clear();
            positionHistory.clear();
            positionHistory.add(boardHash());
            oldAi = ai;
            ai = null;
        }
        if (oldAi != null) {
            try { oldAi.shutdown(); } catch (Throwable ignored) {}
        }
        // Engine creation is lazy in GoGameScreen's worker. Starting KataGo here
        // would run process creation and GTP handshakes on the client thread.
    }

    /**
     * 根据 GameSettings 初始化 AI 引擎。
     * 支持在游戏中途切换引擎（重开时生效）。
     * 注意：如果 GameSettings 或其他依赖不可用，会回退到默认 MCTS。
     */
    public void initAi() {
        if (!initializeAi) return;
        replaceAiFromSettings();
    }

    /** 后台搜索线程使用：仅在尚无引擎时创建，避免客户端线程执行外部引擎握手。 */
    public void initAiIfAbsent() {
        if (!initializeAi) return;
        long generation;
        synchronized (aiLifecycleLock) {
            synchronized (stateLock) {
                if (ai != null || gameOver || closed || aiCreationInProgress) return;
                generation = aiLifecycleGeneration;
                aiCreationInProgress = true;
                aiCreationGeneration = generation;
            }
        }
        publishCreatedAi(generation, createConfiguredAi());
    }

    private void replaceAiFromSettings() {
        if (!initializeAi) return;
        GoAI oldAi;
        long generation;
        synchronized (aiLifecycleLock) {
            synchronized (stateLock) {
                aiLifecycleGeneration++;
                oldAi = ai;
                ai = null;
                generation = aiLifecycleGeneration;
                aiCreationInProgress = true;
                aiCreationGeneration = generation;
            }
        }
        if (oldAi != null) {
            try { oldAi.shutdown(); } catch (Throwable ignored) {}
        }
        publishCreatedAi(generation, createConfiguredAi());
    }

    private GoAI createConfiguredAi() {
        try {
            return GoAI.create();
        } catch (Throwable t) {
            try {
                return MCTSGoAI.createFromSettings();
            } catch (Throwable ignored) {
                return null;
            }
        }
    }

    private void publishCreatedAi(long generation, GoAI newAi) {
        boolean publish;
        synchronized (stateLock) {
            publish = newAi != null && generation == aiLifecycleGeneration && ai == null && !gameOver && !closed;
            if (publish) ai = newAi;
            if (generation == aiCreationGeneration) {
                aiCreationInProgress = false;
                aiCreationGeneration = -1L;
            }
        }
        if (!publish && newAi != null) {
            try { newAi.shutdown(); } catch (Throwable ignored) {}
        }
    }

    public String getRuntimeAiEngineLabel() {
        synchronized (stateLock) {
            return GoAI.runtimeEngineLabel(ai == null ? null : ai.getClass());
        }
    }

    /**
     * 设置自定义 AI 引擎（覆盖 GameSettings 配置）。
     */
    public void setAiEngine(GoAI aiEngine) {
        GoAI oldAi;
        synchronized (stateLock) {
            aiLifecycleGeneration++;
            aiCreationInProgress = false;
            if (closed) {
                if (aiEngine != null) {
                    try { aiEngine.shutdown(); } catch (Throwable ignored) {}
                }
                return;
            }
            oldAi = this.ai;
            this.ai = aiEngine;
        }
        if (oldAi != null && oldAi != aiEngine) {
            try { oldAi.shutdown(); } catch (Throwable ignored) {}
        }
    }

    /** Releases the configured AI engine, if this game owns one. */
    @Override
    public void close() {
        GoAI oldAi;
        synchronized (stateLock) {
            closed = true;
            aiLifecycleGeneration++;
            aiCreationInProgress = false;
            oldAi = this.ai;
            this.ai = null;
        }
        if (oldAi != null) {
            try { oldAi.shutdown(); } catch (Throwable ignored) {}
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

    /** Immutable, single-revision view used to initialize one AI search. */
    record PositionSnapshot(GoPlayer[][] board, GoPlayer currentPlayer, int moveCount,
                            Set<Long> positionHistory, int consecutivePasses,
                            int[] lastMove, long currentHash, long revision) {}

    PositionSnapshot positionSnapshot() {
        synchronized (stateLock) {
            int[] lastMove = null;
            if (!moveHistory.isEmpty()) {
                GoMove last = moveHistory.get(moveHistory.size() - 1);
                if (last.x >= 0 && last.y >= 0) lastMove = new int[]{last.x, last.y};
            }
            return new PositionSnapshot(copyBoardInternal(), currentPlayer, moveHistory.size(),
                    Set.copyOf(positionHistory), consecutivePasses, lastMove,
                    boardHash(), positionRevision);
        }
    }

    /**
     * 当前局面的 Zobrist 哈希（供 MCTS 根节点初始化 super-ko 检查用）。
     */
    public long getCurrentHash() {
        synchronized (stateLock) {
            return boardHash();
        }
    }

    /**
     * 返回上一手落子位置 {x,y}；无上一手或上一手是弃权时返回 null。
     * 供 MCTS 根节点构造 plane 3（上一手位置）时使用，保证 train/serve 一致。
     */
    public int[] getLastMove() {
        synchronized (stateLock) {
            if (moveHistory.isEmpty()) return null;
            GoMove last = moveHistory.get(moveHistory.size() - 1);
            if (last.x < 0 || last.y < 0) return null; // 弃权
            return new int[]{last.x, last.y};
        }
    }

    /**
     * 获取当前回合数（落子数 / 2 + 1）。
     */
    public int moveHistorySize() {
        synchronized (stateLock) {
            return moveHistory.size();
        }
    }

    public boolean placeStone(int x, int y) {
        // 落子→提子→劫争/自杀判定→记录→换手整段持锁：后台 AI 线程经
        // getBoardCopy/getCurrentHash 读棋盘时不得看到"已落子未提完"的撕裂快照，
        // positionHistory 的 contains/add 也不再有 TOCTOU 窗口
        synchronized (stateLock) {
        if (gameOver || !isValidPosition(x, y) || board[x][y] != GoPlayer.NONE) {
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

        // 记录移动与局面（记录当前玩家——落子者，与 pass() 一致；外层已持 stateLock）
        moveHistory.add(new GoMove(x, y, currentPlayer, capturedStones));
        positionHistory.add(newHash);
        consecutivePasses = 0;
        positionRevision++;

        // 切换玩家
        switchPlayer();

        return true;
        }
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

    /**
     * 增量 Zobrist：在 baseHash 基础上 XOR 进/出一颗子的哈希分量。
     * XOR 顺序无关，落子/提子序列的增量结果与 {@link #boardHash(GoPlayer[][])}
     * 全盘重算逐位一致；供 AI 候选生成避免逐点深拷贝+全盘哈希。
     */
    static long xorStone(long baseHash, int x, int y, GoPlayer p) {
        return p == GoPlayer.NONE ? baseHash : baseHash ^ ZOBRIST_TABLE[x][y][p.ordinal()];
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
        synchronized (stateLock) {
            if (gameOver || !isValidPosition(x, y) || board[x][y] != GoPlayer.NONE) return false;
            GoPlayer[][] backup = copyBoardInternal();
            GoPlayer player = currentPlayer;
            int oldBlackCaptured = blackCaptured;
            int oldWhiteCaptured = whiteCaptured;
            int oldConsecutivePasses = consecutivePasses;
            boolean oldGameOver = gameOver;
            long oldPositionRevision = positionRevision;
            List<GoMove> oldHistory = new ArrayList<>(moveHistory);
            Set<Long> oldPositions = new HashSet<>(positionHistory);
            try {
                return placeStone(x, y);
            } finally {
                restoreBoard(backup);
                currentPlayer = player;
                blackCaptured = oldBlackCaptured;
                whiteCaptured = oldWhiteCaptured;
                consecutivePasses = oldConsecutivePasses;
                gameOver = oldGameOver;
                positionRevision = oldPositionRevision;
                moveHistory.clear();
                moveHistory.addAll(oldHistory);
                positionHistory.clear();
                positionHistory.addAll(oldPositions);
            }
        }
    }
    
    public void pass() {
        // ★ 修复：consecutivePasses/switchPlayer/endGame 原先在 stateLock 之外修改，
        //   与 placeStone 的持锁协议不一致——并发读端可能看到"已记弃权未换手"的
        //   撕裂状态。整段状态变更持锁；这里只有内存操作，无 IO 重活，不会长期占锁
        synchronized (stateLock) {
            if (gameOver) return;

            // 先按当前玩家记录弃权（原先在switchPlayer之后记录，会把弃权记到对手名下）
            moveHistory.add(new GoMove(-1, -1, currentPlayer, 0)); // -1,-1表示弃权
            positionRevision++;

            consecutivePasses++;
            if (consecutivePasses >= 2) {
                endGame();
            } else {
                switchPlayer();
            }
        }
    }

    public void resign() {
        synchronized (stateLock) {
            if (!gameOver) {
                resignedPlayer = currentPlayer;
                gameOver = true;
                positionRevision++;
            }
        }
    }

    /** Ends the game with the supplied player as the resigning side. */
    public void resign(GoPlayer player) {
        synchronized (stateLock) {
            if (!gameOver && (player == GoPlayer.BLACK || player == GoPlayer.WHITE)) {
                resignedPlayer = player;
                gameOver = true;
                positionRevision++;
            }
        }
    }

    public GoPlayer getResignedPlayer() {
        synchronized (stateLock) { return resignedPlayer; }
    }
    
    record AiMoveComputation(GoAI.MoveResult result, GoAI engine, long lifecycleGeneration,
                             long positionRevision, GoPlayer expectedPlayer) {}

    public void makeAiMove() {
        applyAiMoveComputation(computeAiMoveComputation());
    }

    /** Computes a typed AI action and the state token required to apply it safely. */
    AiMoveComputation computeAiMoveComputation() {
        GoAI engine;
        long lifecycleGeneration;
        long revision;
        GoPlayer player;
        synchronized (stateLock) {
            if (!aiMode || ai == null || gameOver || closed) {
                return new AiMoveComputation(GoAI.MoveResult.error(), null, -1L, -1L, GoPlayer.NONE);
            }
            engine = ai;
            lifecycleGeneration = aiLifecycleGeneration;
            revision = positionRevision;
            player = currentPlayer;
        }
        GoAI.MoveResult result;
        try {
            result = engine.getBestMoveResult(this);
            if (result == null) result = GoAI.MoveResult.error();
        } catch (RuntimeException ignored) {
            result = GoAI.MoveResult.error();
        }
        return new AiMoveComputation(result, engine, lifecycleGeneration, revision, player);
    }

    /** Applies a computed action only if its engine and source position are still current. */
    boolean applyAiMoveComputation(AiMoveComputation computation) {
        if (computation == null || computation.result() == null) return false;
        synchronized (stateLock) {
            if (closed || gameOver || !aiMode || ai != computation.engine()
                    || aiLifecycleGeneration != computation.lifecycleGeneration()
                    || positionRevision != computation.positionRevision()
                    || currentPlayer != computation.expectedPlayer()) {
                return false;
            }
            applyAiMoveResult(computation.result());
            return true;
        }
    }

    /** Computes a typed AI action without applying it. */
    public GoAI.MoveResult computeAiMoveResult() {
        return computeAiMoveComputation().result();
    }

    /** 计算 AI 走法（不落子），返回 {x,y} 或 null（表示建议弃权）。供后台线程计算使用。 */
    public int[] computeAiMove() {
        GoAI.MoveResult result = computeAiMoveResult();
        return result.type() == GoAI.MoveType.MOVE ? result.coordinates() : null;
    }

    /** 将 AI 走法应用到棋盘（含非法回退扫描），应在客户端线程调用。 */
    public void applyAiMove(int[] move) {
        if (move == null) {
            applyAiMoveResult(GoAI.MoveResult.pass());
        } else if (move.length >= 2 && isValidPosition(move[0], move[1])) {
            applyAiMoveResult(GoAI.MoveResult.move(move[0], move[1]));
        } else {
            applyAiMoveResult(GoAI.MoveResult.error());
        }
    }

    /** Applies a typed AI action. Errors leave the position unchanged. */
    public void applyAiMoveResult(GoAI.MoveResult result) {
        if (result == null || result.type() == GoAI.MoveType.ERROR) return;
        if (result.type() == GoAI.MoveType.RESIGN) {
            resign();
            return;
        }
        if (result.type() == GoAI.MoveType.PASS) {
            pass();
            return;
        }
        int[] move = result.coordinates();
        // 校验 AI 坐标后再尝试落子，避免畸形引擎响应触发越界。
        if (move != null && move.length >= 2 && isValidPosition(move[0], move[1]) && placeStone(move[0], move[1])) {
            return;
        }
        // 最优落子非法时从中心向外扫描，优先选择自然的中腹点。
        int center = BOARD_SIZE / 2;
        for (int radius = 0; radius < BOARD_SIZE; radius++) {
            for (int x = center - radius; x <= center + radius; x++) {
                for (int y = center - radius; y <= center + radius; y++) {
                    if (Math.max(Math.abs(x - center), Math.abs(y - center)) == radius
                            && isValidPosition(x, y) && placeStone(x, y)) return;
                }
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
    
    // Getter方法（读棋盘/终态字段，统一持 stateLock 防撕裂读）
    public GoPlayer getStone(int x, int y) {
        if (!isValidPosition(x, y)) return GoPlayer.NONE;
        synchronized (stateLock) {
            return board[x][y];
        }
    }

    public GoPlayer getCurrentPlayer() { synchronized (stateLock) { return currentPlayer; } }
    public boolean isGameOver() { synchronized (stateLock) { return gameOver; } }
    public int getConsecutivePasses() { synchronized (stateLock) { return consecutivePasses; } }
    public int getBlackCaptured() { synchronized (stateLock) { return blackCaptured; } }
    public int getWhiteCaptured() { synchronized (stateLock) { return whiteCaptured; } }
    public boolean isAiMode() { synchronized (stateLock) { return aiMode; } }

    /** Whether this game was created with an AI lifecycle enabled. */
    public boolean isAiEnabled() { return initializeAi; }

    public void setAiMode(boolean aiMode) {
        synchronized (stateLock) {
            if (this.aiMode != aiMode) {
                this.aiMode = aiMode;
                aiLifecycleGeneration++;
            }
        }
    }
    public int getBoardSize() { return BOARD_SIZE; }

    // 获取棋盘副本供AI使用
    public GoPlayer[][] getBoardCopy() {
        synchronized (stateLock) {
            return copyBoardInternal();
        }
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
        return calcTerritory(Collections.emptySet());
    }

    /**
     * Calculates Chinese area score after removing only explicitly marked dead groups.
     * Coordinates may name any stone in a group; the complete connected group is removed
     * from a detached scoring copy, so the live board and move history remain unchanged.
     */
    public int[] calcTerritory(Set<Long> markedDead) {
        synchronized (stateLock) {
            GoPlayer[][] scoringBoard = copyBoardInternal();
            removeMarkedGroups(scoringBoard, markedDead);
            return calcTerritoryInternal(scoringBoard);
        }
    }

    /** Calculates Chinese area totals from a detached board without mutating it. */
    static int[] calcTerritory(GoPlayer[][] scoringBoard) {
        if (scoringBoard == null || scoringBoard.length != BOARD_SIZE) {
            throw new IllegalArgumentException("棋盘尺寸必须为 19x19");
        }
        for (GoPlayer[] column : scoringBoard) {
            if (column == null || column.length != BOARD_SIZE) {
                throw new IllegalArgumentException("棋盘尺寸必须为 19x19");
            }
        }
        return calcTerritoryInternal(scoringBoard);
    }

    /** 无锁内部实现：仅读取传入棋盘（棋盘活子数 + 围空 flood-fill）。 */
    private static int[] calcTerritoryInternal(GoPlayer[][] scoringBoard) {
        boolean[][] visited = new boolean[BOARD_SIZE][BOARD_SIZE];
        int blackT = 0, whiteT = 0;

        // 先统计棋盘上的活子数（死子不计入得分；但活子数只需数黑/白总数，
        // 领地统计用空点相邻判定，对死子已通过 visited 排除）
        for (int x = 0; x < BOARD_SIZE; x++)
            for (int y = 0; y < BOARD_SIZE; y++) {
                GoPlayer s = scoringBoard[x][y];
                if (s == GoPlayer.BLACK) blackT++;
                else if (s == GoPlayer.WHITE) whiteT++;
            }

        // 再统计空点领地
        for (int x = 0; x < BOARD_SIZE; x++) {
            for (int y = 0; y < BOARD_SIZE; y++) {
                if (visited[x][y] || scoringBoard[x][y] != GoPlayer.NONE) continue;
                // BFS 只遍历空点；边界棋子仅记录颜色，不能共享空区 visited 标记。
                java.util.List<int[]> region = new java.util.ArrayList<>();
                java.util.Queue<int[]> queue = new java.util.ArrayDeque<>();
                queue.add(new int[]{x, y});
                visited[x][y] = true;
                boolean touchBlack = false, touchWhite = false;
                while (!queue.isEmpty()) {
                    int[] pos = queue.remove();
                    int px = pos[0], py = pos[1];
                    region.add(pos);
                    for (int[] d : DIRS) {
                        int nx = px + d[0], ny = py + d[1];
                        if (nx < 0 || nx >= BOARD_SIZE || ny < 0 || ny >= BOARD_SIZE) continue;
                        GoPlayer st = scoringBoard[nx][ny];
                        if (st == GoPlayer.BLACK) touchBlack = true;
                        else if (st == GoPlayer.WHITE) touchWhite = true;
                        else if (!visited[nx][ny]) {
                            visited[nx][ny] = true;
                            queue.add(new int[]{nx, ny});
                        }
                    }
                }
                int pts = region.size();
                if (touchBlack && !touchWhite) blackT += pts;
                else if (touchWhite && !touchBlack) whiteT += pts;
                // 争议地带不计
            }
        }
        return new int[]{blackT, whiteT};
    }

    /** Remove only explicitly marked groups from a detached scoring copy. */
    private void removeMarkedGroups(GoPlayer[][] scoringBoard, Set<Long> markedDead) {
        if (markedDead == null || markedDead.isEmpty()) return;
        List<int[]> anchors = new ArrayList<>(markedDead.size());
        for (Long encodedValue : markedDead) {
            if (encodedValue == null) throw new IllegalArgumentException("死棋标记不能为空");
            long encoded = encodedValue;
            int x = (int) (encoded >> 32);
            int y = (int) encoded;
            if (x < 0 || x >= BOARD_SIZE || y < 0 || y >= BOARD_SIZE
                    || board[x][y] == GoPlayer.NONE) {
                throw new IllegalArgumentException("死棋标记必须指向棋盘上的棋子");
            }
            anchors.add(new int[]{x, y});
        }
        boolean[][] seen = new boolean[BOARD_SIZE][BOARD_SIZE];
        for (int[] anchor : anchors) {
            int x = anchor[0], y = anchor[1];
            if (scoringBoard[x][y] == GoPlayer.NONE) continue;
            List<int[]> group = collectGroup(scoringBoard, x, y, seen);
            for (int[] stone : group) scoringBoard[stone[0]][stone[1]] = GoPlayer.NONE;
        }
    }

    private static List<int[]> collectGroup(GoPlayer[][] position, int x, int y, boolean[][] seen) {
        List<int[]> group = new ArrayList<>();
        ArrayDeque<int[]> queue = new ArrayDeque<>();
        queue.add(new int[]{x, y});
        seen[x][y] = true;
        GoPlayer color = position[x][y];
        while (!queue.isEmpty()) {
            int[] point = queue.remove();
            group.add(point);
            for (int[] dir : DIRS) {
                int nx = point[0] + dir[0], ny = point[1] + dir[1];
                if (nx >= 0 && nx < BOARD_SIZE && ny >= 0 && ny < BOARD_SIZE
                        && !seen[nx][ny] && position[nx][ny] == color) {
                    seen[nx][ny] = true;
                    queue.add(new int[]{nx, ny});
                }
            }
        }
        return group;
    }

    private static long key(int x, int y) { return ((long) x << 32) | (y & 0xffffffffL); }

    /**
     * 计算某一方的最终得分（中国规则数子法，白方加贴目）。
     * @param player 视角
     * @return 该方的得分
     */
    public double getScore(GoPlayer player) {
        return getScore(player, Collections.emptySet(), getConfiguredKomi());
    }

    /** Calculates a player's score after removing explicitly marked dead groups. */
    public double getScore(GoPlayer player, Set<Long> markedDead) {
        return getScore(player, markedDead, getConfiguredKomi());
    }

    public record Score(double black, double white) {
        public GoPlayer winner() {
            if (black > white) return GoPlayer.BLACK;
            if (white > black) return GoPlayer.WHITE;
            return GoPlayer.NONE;
        }
    }

    public Score getScores(Set<Long> markedDead, double komi) {
        int[] territory = calcTerritory(markedDead);
        return new Score(territory[0], territory[1] + normalizeKomi(komi));
    }

    /** Calculates a player's score using a fixed komi snapshot. */
    public double getScore(GoPlayer player, Set<Long> markedDead, double komi) {
        if (player != GoPlayer.BLACK && player != GoPlayer.WHITE) {
            throw new IllegalArgumentException("计分方必须是黑棋或白棋");
        }
        Score score = getScores(markedDead, komi);
        return player == GoPlayer.BLACK ? score.black() : score.white();
    }

    /**
     * 计算两方的分差（从指定视角看，正数表示该方领先）。
     * @param perspective 视角方
     * @return 分差（视角方 - 对方）
     */
    public double getScoreMargin(GoPlayer perspective) {
        return getScoreMargin(perspective, Collections.emptySet(), getConfiguredKomi());
    }

    /** Calculates score margin using one fixed komi snapshot. */
    public double getScoreMargin(GoPlayer perspective, Set<Long> markedDead, double komi) {
        if (perspective != GoPlayer.BLACK && perspective != GoPlayer.WHITE) {
            throw new IllegalArgumentException("计分方必须是黑棋或白棋");
        }
        GoPlayer opponent = perspective == GoPlayer.BLACK ? GoPlayer.WHITE : GoPlayer.BLACK;
        return getScore(perspective, markedDead, komi) - getScore(opponent, markedDead, komi);
    }
}