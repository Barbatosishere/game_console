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
    }
    
    public boolean placeStone(int x, int y) {
        if (!canPlaceStone(x, y)) {
            return false;
        }
        
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
        
        // 检查自杀规则 - 如果当前放置的棋子群无气且没有吃掉对方棋子，则为非法移动
        Set<int[]> currentGroup = getGroup(x, y);
        if (!hasLiberty(currentGroup) && capturedStones == 0) {
            board[x][y] = GoPlayer.NONE; // 撤销移动
            return false;
        }
        
        // 更新捕获计数
        if (currentPlayer == GoPlayer.BLACK) {
            whiteCaptured += capturedStones;
        } else {
            blackCaptured += capturedStones;
        }
        
        // 记录移动
        moveHistory.add(new GoMove(x, y, currentPlayer, capturedStones));
        
        // 切换玩家
        switchPlayer();
        consecutivePasses = 0;
        
        return true;
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
        
        consecutivePasses++;
        if (consecutivePasses >= 2) {
            endGame();
        } else {
            switchPlayer();
        }
        
        moveHistory.add(new GoMove(-1, -1, currentPlayer, 0)); // -1,-1表示弃权
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
        if (move != null) {
            placeStone(move[0], move[1]);
        } else {
            pass();
        }
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