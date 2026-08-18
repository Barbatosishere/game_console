package com.wzz.game_console.client.screens.games.gogame;

import java.util.*;

public class GoGame {
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
    /** 历史局面哈希：劫争判定（禁止全局同型，中国规则），新局面不得与任何历史局面重复 */
    private final Set<Long> positionHistory = new HashSet<>();
    /** 调试开关：为 true 时输出劫争判定的详细追踪信息（默认关闭，正常对局不刷屏） */
    private static final boolean DEBUG_KO = false;
    
    public GoGame() {
        this.board = new GoPlayer[BOARD_SIZE][BOARD_SIZE];
        this.moveHistory = new ArrayList<>();
        this.ai = new GoAI();
        // reset() 已声明为 final，避免构造器调用可覆写方法的 this-escape 风险
        reset();
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

        // 切换玩家
        switchPlayer();

        // 记录移动与局面（记录切换后的玩家状态）
        moveHistory.add(new GoMove(x, y, currentPlayer, capturedStones));
        positionHistory.add(newHash);
        consecutivePasses = 0;

        return true;
    }

    /** 当前局面的确定性哈希（同型局面必同哈希） */
    private long boardHash() {
        return Arrays.deepHashCode(board);
    }

    private GoPlayer[][] copyBoardInternal() {
        GoPlayer[][] copy = new GoPlayer[BOARD_SIZE][BOARD_SIZE];
        for (int i = 0; i < BOARD_SIZE; i++) copy[i] = board[i].clone();
        return copy;
    }

    private void restoreBoard(GoPlayer[][] backup) {
        for (int i = 0; i < BOARD_SIZE; i++) board[i] = backup[i].clone();
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
        moveHistory.add(new GoMove(-1, -1, currentPlayer, 0)); // -1,-1表示弃权

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
        if (!aiMode || gameOver || currentPlayer == GoPlayer.BLACK) {
            return;
        }

        int[] move = ai.getBestMove(this);
        // 最优落子非法（劫争/自杀）时，扫描棋盘找第一个合法点，避免直接弃权
        if (move != null && placeStone(move[0], move[1])) {
            return;
        }
        for (int x = 0; x < BOARD_SIZE; x++) {
            for (int y = 0; y < BOARD_SIZE; y++) {
                if (placeStone(x, y)) return;
            }
        }
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
}