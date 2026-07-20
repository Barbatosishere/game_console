package com.wzz.momoi_game_console.client.screens.games.gogame;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;

public class GoAI {
    /** 四方向偏移（上、右、下、左），避免每次方法调用重复创建 */
    private static final int[][] DIRS = {{0,1}, {1,0}, {0,-1}, {-1,0}};

    private Random random = new Random();
    
    public int[] getBestMove(GoGame game) {
        List<int[]> validMoves = getAllValidMoves(game);
        
        if (validMoves.isEmpty()) {
            return null; // 没有有效移动，应该弃权
        }
        
        // 简单AI策略：
        // 1. 优先保护自己的棋子群
        // 2. 尝试攻击对方的棋子群
        // 3. 占据重要位置（角落、边、中心）
        // 4. 随机选择
        
        int[] bestMove = null;
        int bestScore = Integer.MIN_VALUE;
        
        for (int[] move : validMoves) {
            int score = evaluateMove(game, move[0], move[1]);
            if (score > bestScore) {
                bestScore = score;
                bestMove = move;
            }
        }
        
        return bestMove != null ? bestMove : validMoves.get(random.nextInt(validMoves.size()));
    }
    
    private List<int[]> getAllValidMoves(GoGame game) {
        List<int[]> moves = new ArrayList<>();
        int boardSize = game.getBoardSize();
        
        for (int x = 0; x < boardSize; x++) {
            for (int y = 0; y < boardSize; y++) {
                if (game.canPlaceStone(x, y)) {
                    moves.add(new int[]{x, y});
                }
            }
        }
        
        return moves;
    }
    
    private int evaluateMove(GoGame game, int x, int y) {
        int score = 0;
        int boardSize = game.getBoardSize();
        
        // 1. 位置价值评估
        score += getPositionValue(x, y, boardSize);
        
        // 2. 安全性评估 - 检查放置后是否安全
        score += evaluateSafety(game, x, y);
        
        // 3. 攻击性评估 - 是否能吃掉对方棋子
        score += evaluateCapture(game, x, y);
        
        // 4. 防御性评估 - 是否能救自己的棋子
        score += evaluateDefense(game, x, y);
        
        // 5. 连接性评估 - 是否能连接自己的棋子
        score += evaluateConnection(game, x, y);
        
        return score;
    }
    
    private int getPositionValue(int x, int y, int boardSize) {
        int score = 0;
        int center = boardSize / 2;
        
        // 角落位置加分
        if ((x == 0 || x == boardSize - 1) && (y == 0 || y == boardSize - 1)) {
            score += 15;
        }
        // 边位置加分
        else if (x == 0 || x == boardSize - 1 || y == 0 || y == boardSize - 1) {
            score += 8;
        }
        // 中心区域加分
        else if (Math.abs(x - center) <= 3 && Math.abs(y - center) <= 3) {
            score += 12;
        }
        
        // 星位点额外加分
        if (isStarPoint(x, y, boardSize)) {
            score += 10;
        }
        
        return score;
    }
    
    private boolean isStarPoint(int x, int y, int boardSize) {
        if (boardSize == 19) {
            int[] starPositions = {3, 9, 15};
            for (int sx : starPositions) {
                for (int sy : starPositions) {
                    if (x == sx && y == sy) {
                        return true;
                    }
                }
            }
        }
        return false;
    }
    
    private int evaluateSafety(GoGame game, int x, int y) {
        // 简化的安全性评估 - 检查周围空位数量
        int liberties = 0;
        for (int[] dir : DIRS) {
            int nx = x + dir[0];
            int ny = y + dir[1];
            
            if (isValidPosition(nx, ny, game.getBoardSize()) && 
                game.getStone(nx, ny) == GoPlayer.NONE) {
                liberties++;
            }
        }
        
        return liberties * 5; // 每个气值5分
    }
    
    private int evaluateCapture(GoGame game, int x, int y) {
        int score = 0;
        GoPlayer currentPlayer = game.getCurrentPlayer();
        GoPlayer opponent = currentPlayer == GoPlayer.BLACK ? GoPlayer.WHITE : GoPlayer.BLACK;
        
        // 检查是否能吃掉对方棋子
        for (int[] dir : DIRS) {
            int nx = x + dir[0];
            int ny = y + dir[1];
            
            if (isValidPosition(nx, ny, game.getBoardSize()) && 
                game.getStone(nx, ny) == opponent) {
                
                // 简化检查：如果对方棋子只有一个气，放置后可能吃掉
                int opponentLiberties = countLiberties(game, nx, ny);
                if (opponentLiberties <= 1) {
                    score += 50; // 能吃掉对方棋子，高分
                }
            }
        }
        
        return score;
    }
    
    private int evaluateDefense(GoGame game, int x, int y) {
        int score = 0;
        GoPlayer currentPlayer = game.getCurrentPlayer();
        
        // 检查是否能救自己的棋子
        for (int[] dir : DIRS) {
            int nx = x + dir[0];
            int ny = y + dir[1];
            
            if (isValidPosition(nx, ny, game.getBoardSize()) && 
                game.getStone(nx, ny) == currentPlayer) {
                
                int friendlyLiberties = countLiberties(game, nx, ny);
                if (friendlyLiberties <= 2) {
                    score += 30; // 能救危险的己方棋子
                }
            }
        }
        
        return score;
    }
    
    private int evaluateConnection(GoGame game, int x, int y) {
        int score = 0;
        GoPlayer currentPlayer = game.getCurrentPlayer();
        
        // 检查周围己方棋子数量
        int friendlyNeighbors = 0;
        for (int[] dir : DIRS) {
            int nx = x + dir[0];
            int ny = y + dir[1];
            
            if (isValidPosition(nx, ny, game.getBoardSize()) && 
                game.getStone(nx, ny) == currentPlayer) {
                friendlyNeighbors++;
            }
        }
        
        score += friendlyNeighbors * 8; // 每个相邻己方棋子8分
        
        return score;
    }
    
    private int countLiberties(GoGame game, int x, int y) {
        // 简化的气计算 - 实际应该计算整个棋子群的气
        int liberties = 0;
        for (int[] dir : DIRS) {
            int nx = x + dir[0];
            int ny = y + dir[1];
            
            if (isValidPosition(nx, ny, game.getBoardSize()) && 
                game.getStone(nx, ny) == GoPlayer.NONE) {
                liberties++;
            }
        }
        return liberties;
    }
    
    private boolean isValidPosition(int x, int y, int boardSize) {
        return x >= 0 && x < boardSize && y >= 0 && y < boardSize;
    }
}