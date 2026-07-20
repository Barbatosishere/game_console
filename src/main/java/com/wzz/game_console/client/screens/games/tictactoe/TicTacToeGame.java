package com.wzz.game_console.client.screens.games.tictactoe;

public class TicTacToeGame {
    protected enum Player {
        NONE, X, O
    }
    
    public enum GameMode {
        SINGLE_PLAYER
    }
    
    private Player[][] board;
    private Player currentPlayer;
    private GameMode gameMode;
    private boolean gameOver;
    private Player winner;
    private boolean isPlayerTurn; // 单人模式下，true为玩家回合，false为AI回合
    
    public TicTacToeGame(GameMode mode) {
        this.gameMode = mode;
        resetGame();
    }
    
    public void resetGame() {
        board = new Player[3][3];
        for (int i = 0; i < 3; i++) {
            for (int j = 0; j < 3; j++) {
                board[i][j] = Player.NONE;
            }
        }
        currentPlayer = Player.X;
        gameOver = false;
        winner = Player.NONE;
        isPlayerTurn = true;
    }
    
    public boolean makeMove(int row, int col) {
        if (gameOver || board[row][col] != Player.NONE) {
            return false;
        }
        
        board[row][col] = currentPlayer;
        
        if (checkWin()) {
            winner = currentPlayer;
            gameOver = true;
        } else if (isBoardFull()) {
            gameOver = true; // 平局
        } else {
            switchPlayer();
        }
        
        return true;
    }
    
    private void switchPlayer() {
        currentPlayer = (currentPlayer == Player.X) ? Player.O : Player.X;
        if (gameMode == GameMode.SINGLE_PLAYER) {
            isPlayerTurn = !isPlayerTurn;
        }
    }
    
    public void makeAIMove() {
        if (gameMode != GameMode.SINGLE_PLAYER || isPlayerTurn || gameOver) {
            return;
        }
        
        // 简单的AI逻辑：优先获胜，其次阻止玩家获胜，最后随机下棋
        int[] move = getBestMove();
        if (move != null) {
            makeMove(move[0], move[1]);
        }
    }
    
    private int[] getBestMove() {
        // 1. 检查是否能获胜
        int[] winMove = findWinningMove(currentPlayer);
        if (winMove != null)  {
            return winMove;
        }
        
        // 2. 检查是否需要阻止对手获胜
        Player opponent = (currentPlayer == Player.X) ? Player.O : Player.X;
        int[] blockMove = findWinningMove(opponent);
        if (blockMove != null) return blockMove;
        
        // 3. 选择中心位置
        if (board[1][1] == Player.NONE) {
            return new int[]{1, 1};
        }
        
        // 4. 选择角落
        int[][] corners = {{0, 0}, {0, 2}, {2, 0}, {2, 2}};
        for (int[] corner : corners) {
            if (board[corner[0]][corner[1]] == Player.NONE) {
                return corner;
            }
        }
        
        // 5. 随机选择剩余位置
        for (int i = 0; i < 3; i++) {
            for (int j = 0; j < 3; j++) {
                if (board[i][j] == Player.NONE) {
                    return new int[]{i, j};
                }
            }
        }
        
        return null;
    }
    
    private int[] findWinningMove(Player player) {
        for (int i = 0; i < 3; i++) {
            for (int j = 0; j < 3; j++) {
                if (board[i][j] == Player.NONE) {
                    board[i][j] = player;
                    if (checkWin()) {
                        board[i][j] = Player.NONE;
                        return new int[]{i, j};
                    }
                    board[i][j] = Player.NONE;
                }
            }
        }
        return null;
    }
    
    private boolean checkWin() {
        // 检查行
        for (int i = 0; i < 3; i++) {
            if (board[i][0] == currentPlayer && 
                board[i][1] == currentPlayer && 
                board[i][2] == currentPlayer) {
                return true;
            }
        }
        
        // 检查列
        for (int j = 0; j < 3; j++) {
            if (board[0][j] == currentPlayer && 
                board[1][j] == currentPlayer && 
                board[2][j] == currentPlayer) {
                return true;
            }
        }
        
        // 检查对角线
        if ((board[0][0] == currentPlayer && board[1][1] == currentPlayer && board[2][2] == currentPlayer) ||
            (board[0][2] == currentPlayer && board[1][1] == currentPlayer && board[2][0] == currentPlayer)) {
            return true;
        }
        
        return false;
    }
    
    private boolean isBoardFull() {
        for (int i = 0; i < 3; i++) {
            for (int j = 0; j < 3; j++) {
                if (board[i][j] == Player.NONE) {
                    return false;
                }
            }
        }
        return true;
    }
    
    // Getters
    public Player getCell(int row, int col) {
        return board[row][col];
    }
    
    public Player getCurrentPlayer() {
        return currentPlayer;
    }
    
    public boolean isGameOver() {
        return gameOver;
    }
    
    public Player getWinner() {
        return winner;
    }
    
    public boolean isPlayerTurn() {
        return isPlayerTurn;
    }
    
    public GameMode getGameMode() {
        return gameMode;
    }
    
    public String getGameStatus() {
        if (!gameOver) {
            if (gameMode == GameMode.SINGLE_PLAYER) {
                return isPlayerTurn ? "你的回合 (X)" : "AI的回合 (O)";
            } else {
                return "玩家 " + currentPlayer + " 的回合";
            }
        } else if (winner != Player.NONE) {
            if (gameMode == GameMode.SINGLE_PLAYER) {
                return winner == Player.X ? "你赢了！" : "AI赢了，你这个杂鱼AI都打不过";
            } else {
                return "玩家 " + winner + " 获胜！";
            }
        } else {
            return "平局！";
        }
    }
}