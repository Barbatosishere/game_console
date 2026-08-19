package com.wzz.game_console.client.screens.games.gogame;

import java.util.*;

/**
 * 基于 MCTS（蒙特卡洛树搜索）+ 启发式局面评估的围棋 AI。
 * 算法思路参考 KataGo / Leela Zero 等现代围棋引擎：
 * - MCTS 搜索树，UCB1 选择子节点
 * - 叶子节点用启发式评估函数代替随机模拟
 * - 综合位置价值、气、捕捉/防御、连接性等多维特征
 */
public class GoAI {
    private static final int[][] DIRS = {{0,1}, {1,0}, {0,-1}, {-1,0}};
    private static final int BOARD_SIZE = 19;
    /** 每次搜索的迭代次数上限 */
    private static final int MCTS_ITERATIONS = 400;
    /** UCB1 探索常数 */
    private static final double UCB_C = 1.414;

    private Random random = new Random();

    /**
     * 获取最佳落子位置（MCTS + 启发式评估）
     */
    public int[] getBestMove(GoGame game) {
        GoPlayer[][] board = game.getBoardCopy();
        GoPlayer currentPlayer = game.getCurrentPlayer();

        // 收集合法落子点
        List<int[]> validMoves = getAllValidMoves(board, currentPlayer);
        if (validMoves.isEmpty()) return null;

        // 如果只有一个候选点或棋盘较空，用启发式快速评估
        if (validMoves.size() <= 1 || countStones(board) < 8) {
            return getBestHeuristicMove(board, currentPlayer, validMoves);
        }

        // MCTS 搜索
        return mctsSearch(board, currentPlayer, validMoves);
    }

    /**
     * MCTS 主搜索
     */
    private int[] mctsSearch(GoPlayer[][] board, GoPlayer player, List<int[]> validMoves) {
        MCTSNode root = new MCTSNode(board, player, null, null, validMoves);

        long deadline = System.currentTimeMillis() + 500; // 最多搜索 500ms
        int iterations = 0;

        while (iterations < MCTS_ITERATIONS && System.currentTimeMillis() < deadline) {
            iterations++;
            // Selection
            MCTSNode node = root;
            GoPlayer[][] simBoard = deepCopyBoard(root.board);

            // Selection: 遍历树到叶子节点
            while (node.children != null && !node.children.isEmpty()) {
                node = selectChild(node);
                // 执行节点的落子（回溯时已经处理过，这里只需更新 simBoard）
            }

            // 如果节点还有未扩展的走法，扩展一个
            if (node.untriedMoves != null && !node.untriedMoves.isEmpty()) {
                int[] move = node.untriedMoves.remove(random.nextInt(node.untriedMoves.size()));
                // 在 simBoard 上执行落子
                GoPlayer nextPlayer = node.player == GoPlayer.BLACK ? GoPlayer.WHITE : GoPlayer.BLACK;
                simulatePlaceStone(simBoard, move[0], move[1], node.player);

                MCTSNode child = new MCTSNode(simBoard, nextPlayer, node, move, null);
                // 生成子节点的合法走法
                child.untriedMoves = getAllValidMoves(simBoard, nextPlayer);
                if (node.children == null) node.children = new ArrayList<>();
                node.children.add(child);
                node = child;
            } else {
                // 节点已完全扩展，执行模拟落子（用当前 simBoard）
                // 如果 node 是棋盘终局，不做模拟
                if (node.untriedMoves != null && node.untriedMoves.isEmpty()
                        && (node.children == null || node.children.isEmpty())) {
                    // 终局或没有走法，直接用局面评估
                } else {
                    // 在 simBoard 上的 node 局面执行一步随机走法（快速模拟）
                    simulateRandomMove(simBoard, node.player);
                }
            }

            // Simulation: 用启发式评估结果
            double score = evaluateBoard(simBoard, player);

            // 如果当前走法导致自己被杀，降低分数
            GoPlayer[][] currentBoard = node.getBoardState();
            if (currentBoard != null) {
                // 检查 node 对应的走法是否形成有利局面
                int[] lastMove = node.move;
                if (lastMove != null) {
                    score += evaluateCapturePotential(simBoard, lastMove[0], lastMove[1], node.player);
                    score += evaluateDefensePotential(simBoard, lastMove[0], lastMove[1], node.player);
                }
            }

            // Backpropagation
            backpropagate(node, score);
        }

        // 选择访问次数最多的子节点
        return getBestMCTSMove(root);
    }

    /**
     * UCB1 选择最佳子节点
     */
    private MCTSNode selectChild(MCTSNode parent) {
        MCTSNode best = null;
        double bestValue = Double.NEGATIVE_INFINITY;
        double logParentVisits = Math.log(parent.visits);

        for (MCTSNode child : parent.children) {
            if (child.visits == 0) return child; // 未访问的节点优先探索
            double ucb = child.totalScore / child.visits + UCB_C * Math.sqrt(logParentVisits / child.visits);
            if (ucb > bestValue) {
                bestValue = ucb;
                best = child;
            }
        }
        return best;
    }

    /**
     * 反向传播
     */
    private void backpropagate(MCTSNode node, double score) {
        while (node != null) {
            node.visits++;
            node.totalScore += score;
            node = node.parent;
        }
    }

    /**
     * 获取 MCTS 搜索后访问次数最多的走法
     */
    private int[] getBestMCTSMove(MCTSNode root) {
        if (root.children == null || root.children.isEmpty()) {
            return null;
        }
        MCTSNode best = null;
        int maxVisits = -1;
        for (MCTSNode child : root.children) {
            if (child.visits > maxVisits) {
                maxVisits = child.visits;
                best = child;
            }
        }
        return best != null ? best.move : null;
    }

    /**
     * 在棋盘副本上模拟落子（简化版，不处理劫争）
     */
    private boolean simulatePlaceStone(GoPlayer[][] board, int x, int y, GoPlayer player) {
        if (x < 0 || x >= BOARD_SIZE || y < 0 || y >= BOARD_SIZE || board[x][y] != GoPlayer.NONE) {
            return false;
        }

        board[x][y] = player;
        GoPlayer opponent = player == GoPlayer.BLACK ? GoPlayer.WHITE : GoPlayer.BLACK;

        // 检查并移除被吃的对方棋子
        int captured = 0;
        for (int[] dir : DIRS) {
            int nx = x + dir[0], ny = y + dir[1];
            if (nx >= 0 && nx < BOARD_SIZE && ny >= 0 && ny < BOARD_SIZE && board[nx][ny] == opponent) {
                Set<int[]> group = getGroup(board, nx, ny);
                if (!hasLiberty(board, group)) {
                    for (int[] pos : group) {
                        board[pos[0]][pos[1]] = GoPlayer.NONE;
                    }
                    captured += group.size();
                }
            }
        }

        // 检查自杀
        if (captured == 0) {
            Set<int[]> myGroup = getGroup(board, x, y);
            if (!hasLiberty(board, myGroup)) {
                board[x][y] = GoPlayer.NONE;
                return false;
            }
        }

        return true;
    }

    /**
     * 模拟一步随机走法
     */
    private void simulateRandomMove(GoPlayer[][] board, GoPlayer player) {
        List<int[]> moves = getAllValidMoves(board, player);
        if (moves.isEmpty()) return;
        int[] move = moves.get(random.nextInt(moves.size()));
        simulatePlaceStone(board, move[0], move[1], player);
    }

    /**
     * 启发式评估：选择最佳走法（无 MCTS 时的回退）
     */
    private int[] getBestHeuristicMove(GoPlayer[][] board, GoPlayer player, List<int[]> validMoves) {
        int[] bestMove = null;
        int bestScore = Integer.MIN_VALUE;

        for (int[] move : validMoves) {
            int score = evaluateMove(board, player, move[0], move[1]);
            if (score > bestScore) {
                bestScore = score;
                bestMove = move;
            }
        }

        return bestMove != null ? bestMove : validMoves.get(random.nextInt(validMoves.size()));
    }

    // ══════════════════════════════════════════
    //  局面评估（启发式，用于 MCTS 叶节点和回退）
    // ══════════════════════════════════════════

    /**
     * 综合评估棋盘局面（从当前玩家视角）
     * 使用 visited 标记已计分的棋群，避免重复计数
     */
    private double evaluateBoard(GoPlayer[][] board, GoPlayer player) {
        double score = 0;
        GoPlayer opponent = player == GoPlayer.BLACK ? GoPlayer.WHITE : GoPlayer.BLACK;
        boolean[][] visited = new boolean[BOARD_SIZE][BOARD_SIZE];

        for (int x = 0; x < BOARD_SIZE; x++) {
            for (int y = 0; y < BOARD_SIZE; y++) {
                if (board[x][y] == GoPlayer.NONE || visited[x][y]) continue;

                Set<int[]> group = getGroup(board, x, y);
                boolean isMine = board[x][y] == player;
                int libs = countGroupLiberties(board, group);
                int groupScore = 0;

                // 气数评估
                if (isMine) {
                    groupScore += libs * 3;
                    if (libs >= 3) groupScore += 5;   // 安定棋群
                    else if (libs <= 1) groupScore -= 20; // 危险棋群
                } else {
                    if (libs <= 1) groupScore += 15; // 对方危险棋群，有利
                }

                // 标记整个棋群为已访问
                for (int[] pos : group) {
                    visited[pos[0]][pos[1]] = true;
                }

                score += isMine ? groupScore : -groupScore;
            }
        }

        // 棋子数量优势
        int myStones = countStones(board, player);
        int oppStones = countStones(board, opponent);
        score += (myStones - oppStones) * 1.5;

        // 势力范围评估
        score += evaluateInfluence(board, player, opponent);

        return score;
    }

    /**
     * 简易势力范围评估
     */
    private double evaluateInfluence(GoPlayer[][] board, GoPlayer player, GoPlayer opponent) {
        double influence = 0;
        int radius = 4;

        for (int x = 0; x < BOARD_SIZE; x++) {
            for (int y = 0; y < BOARD_SIZE; y++) {
                if (board[x][y] != GoPlayer.NONE) continue;

                double myInf = 0, oppInf = 0;
                for (int dx = -radius; dx <= radius; dx++) {
                    for (int dy = -radius; dy <= radius; dy++) {
                        int nx = x + dx, ny = y + dy;
                        if (nx < 0 || nx >= BOARD_SIZE || ny < 0 || ny >= BOARD_SIZE) continue;
                        if (board[nx][ny] == player) {
                            double dist = Math.sqrt(dx * dx + dy * dy);
                            if (dist > 0) myInf += 1.0 / dist;
                        } else if (board[nx][ny] == opponent) {
                            double dist = Math.sqrt(dx * dx + dy * dy);
                            if (dist > 0) oppInf += 1.0 / dist;
                        }
                    }
                }
                if (myInf > oppInf) influence += 0.5;
                else if (oppInf > myInf) influence -= 0.5;
            }
        }
        return influence;
    }

    /**
     * 单个走法的启发式评估
     */
    private int evaluateMove(GoPlayer[][] board, GoPlayer player, int x, int y) {
        int score = 0;

        // 位置价值
        score += getPositionValue(x, y, BOARD_SIZE);

        // 检查是否能吃掉对方棋子
        GoPlayer opponent = player == GoPlayer.BLACK ? GoPlayer.WHITE : GoPlayer.BLACK;
        for (int[] dir : DIRS) {
            int nx = x + dir[0], ny = y + dir[1];
            if (nx >= 0 && nx < BOARD_SIZE && ny >= 0 && ny < BOARD_SIZE && board[nx][ny] == opponent) {
                Set<int[]> group = getGroup(board, nx, ny);
                if (countGroupLiberties(board, group) <= 1) {
                    score += 50;
                }
            }
        }

        // 检查是否能救援己方棋子
        for (int[] dir : DIRS) {
            int nx = x + dir[0], ny = y + dir[1];
            if (nx >= 0 && nx < BOARD_SIZE && ny >= 0 && ny < BOARD_SIZE && board[nx][ny] == player) {
                Set<int[]> group = getGroup(board, nx, ny);
                if (countGroupLiberties(board, group) <= 1) {
                    score += 30;
                }
            }
        }

        // 周围己方棋子连接
        int friendly = 0;
        for (int[] dir : DIRS) {
            int nx = x + dir[0], ny = y + dir[1];
            if (nx >= 0 && nx < BOARD_SIZE && ny >= 0 && ny < BOARD_SIZE && board[nx][ny] == player) {
                friendly++;
            }
        }
        score += friendly * 8;

        // 周围气数
        int liberties = 0;
        for (int[] dir : DIRS) {
            int nx = x + dir[0], ny = y + dir[1];
            if (nx >= 0 && nx < BOARD_SIZE && ny >= 0 && ny < BOARD_SIZE && board[nx][ny] == GoPlayer.NONE) {
                liberties++;
            }
        }
        score += liberties * 5;

        return score;
    }

    /**
     * 捕捉潜力评估
     */
    private int evaluateCapturePotential(GoPlayer[][] board, int x, int y, GoPlayer player) {
        GoPlayer opponent = player == GoPlayer.BLACK ? GoPlayer.WHITE : GoPlayer.BLACK;
        int score = 0;
        for (int[] dir : DIRS) {
            int nx = x + dir[0], ny = y + dir[1];
            if (nx >= 0 && nx < BOARD_SIZE && ny >= 0 && ny < BOARD_SIZE && board[nx][ny] == opponent) {
                Set<int[]> group = getGroup(board, nx, ny);
                if (countGroupLiberties(board, group) <= 2) {
                    score += 20;
                }
            }
        }
        return score;
    }

    /**
     * 防御潜力评估
     */
    private int evaluateDefensePotential(GoPlayer[][] board, int x, int y, GoPlayer player) {
        int score = 0;
        for (int[] dir : DIRS) {
            int nx = x + dir[0], ny = y + dir[1];
            if (nx >= 0 && nx < BOARD_SIZE && ny >= 0 && ny < BOARD_SIZE && board[nx][ny] == player) {
                Set<int[]> group = getGroup(board, nx, ny);
                if (countGroupLiberties(board, group) <= 2) {
                    score += 15;
                }
            }
        }
        return score;
    }

    // ══════════════════════════════════════════
    //  位置价值
    // ══════════════════════════════════════════

    private int getPositionValue(int x, int y, int boardSize) {
        int score = 0;
        int center = boardSize / 2;

        if ((x == 0 || x == boardSize - 1) && (y == 0 || y == boardSize - 1)) {
            score += 15;
        } else if (x == 0 || x == boardSize - 1 || y == 0 || y == boardSize - 1) {
            score += 8;
        } else if (Math.abs(x - center) <= 3 && Math.abs(y - center) <= 3) {
            score += 12;
        }

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
                    if (x == sx && y == sy) return true;
                }
            }
        }
        return false;
    }

    // ══════════════════════════════════════════
    //  工具方法
    // ══════════════════════════════════════════

    private List<int[]> getAllValidMoves(GoPlayer[][] board, GoPlayer player) {
        List<int[]> moves = new ArrayList<>();
        for (int x = 0; x < BOARD_SIZE; x++) {
            for (int y = 0; y < BOARD_SIZE; y++) {
                if (board[x][y] == GoPlayer.NONE) {
                    // 快速排除明显的自杀走法：在棋盘副本上测试落子
                    GoPlayer[][] testBoard = deepCopyBoard(board);
                    if (simulatePlaceStone(testBoard, x, y, player)) {
                        moves.add(new int[]{x, y});
                    }
                }
            }
        }
        return moves;
    }

    private int countStones(GoPlayer[][] board) {
        int count = 0;
        for (int x = 0; x < BOARD_SIZE; x++)
            for (int y = 0; y < BOARD_SIZE; y++)
                if (board[x][y] != GoPlayer.NONE) count++;
        return count;
    }

    private int countStones(GoPlayer[][] board, GoPlayer player) {
        int count = 0;
        for (int x = 0; x < BOARD_SIZE; x++)
            for (int y = 0; y < BOARD_SIZE; y++)
                if (board[x][y] == player) count++;
        return count;
    }

    private Set<int[]> getGroup(GoPlayer[][] board, int x, int y) {
        Set<int[]> group = new HashSet<>();
        GoPlayer color = board[x][y];
        if (color == GoPlayer.NONE) return group;

        Stack<int[]> stack = new Stack<>();
        boolean[][] visited = new boolean[BOARD_SIZE][BOARD_SIZE];
        stack.push(new int[]{x, y});

        while (!stack.isEmpty()) {
            int[] pos = stack.pop();
            int px = pos[0], py = pos[1];
            if (visited[px][py]) continue;
            visited[px][py] = true;
            group.add(new int[]{px, py});

            for (int[] dir : DIRS) {
                int nx = px + dir[0], ny = py + dir[1];
                if (nx >= 0 && nx < BOARD_SIZE && ny >= 0 && ny < BOARD_SIZE
                        && !visited[nx][ny] && board[nx][ny] == color) {
                    stack.push(new int[]{nx, ny});
                }
            }
        }
        return group;
    }

    private boolean hasLiberty(GoPlayer[][] board, Set<int[]> group) {
        for (int[] pos : group) {
            for (int[] dir : DIRS) {
                int nx = pos[0] + dir[0], ny = pos[1] + dir[1];
                if (nx >= 0 && nx < BOARD_SIZE && ny >= 0 && ny < BOARD_SIZE && board[nx][ny] == GoPlayer.NONE) {
                    return true;
                }
            }
        }
        return false;
    }

    private int countGroupLiberties(GoPlayer[][] board, Set<int[]> group) {
        Set<Long> libertySet = new HashSet<>();
        for (int[] pos : group) {
            for (int[] dir : DIRS) {
                int nx = pos[0] + dir[0], ny = pos[1] + dir[1];
                if (nx >= 0 && nx < BOARD_SIZE && ny >= 0 && ny < BOARD_SIZE && board[nx][ny] == GoPlayer.NONE) {
                    libertySet.add((long)nx * BOARD_SIZE + ny);
                }
            }
        }
        return libertySet.size();
    }

    private GoPlayer[][] deepCopyBoard(GoPlayer[][] board) {
        GoPlayer[][] copy = new GoPlayer[BOARD_SIZE][BOARD_SIZE];
        for (int x = 0; x < BOARD_SIZE; x++) {
            copy[x] = board[x].clone();
        }
        return copy;
    }

    // ══════════════════════════════════════════
    //  MCTS 节点
    // ══════════════════════════════════════════

    private static class MCTSNode {
        GoPlayer[][] board; // 该节点的棋盘状态
        GoPlayer player;    // 该节点轮到谁走
        MCTSNode parent;
        int[] move;         // 从父节点到达该节点的走法
        List<MCTSNode> children;
        List<int[]> untriedMoves;

        int visits = 0;
        double totalScore = 0;

        MCTSNode(GoPlayer[][] board, GoPlayer player, MCTSNode parent, int[] move,
                List<int[]> untriedMoves) {
            this.board = board;
            this.player = player;
            this.parent = parent;
            this.move = move;
            this.untriedMoves = untriedMoves != null ? new ArrayList<>(untriedMoves) : null;
        }

        GoPlayer[][] getBoardState() {
            return board;
        }
    }
}