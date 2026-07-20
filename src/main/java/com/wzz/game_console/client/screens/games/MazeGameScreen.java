package com.wzz.game_console.client.screens.games;

import com.wzz.game_console.client.screens.GameSelectorScreen;
import com.wzz.game_console.util.GameRenderHelper;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.minecraft.sounds.SoundEvents;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.api.distmarker.OnlyIn;
import org.jetbrains.annotations.NotNull;
import org.lwjgl.glfw.GLFW;

import java.util.*;

@OnlyIn(Dist.CLIENT)
public class MazeGameScreen extends Screen {
    boolean showExitConfirm = false;
    private int TILE_SIZE = 20;
    private static final int MAZE_WIDTH = 21;  // 奇数
    private static final int MAZE_HEIGHT = 21; // 奇数

    private char[][] maze;
    private int playerX, playerY;
    private int ghostX, ghostY;
    private boolean gameOver;
    private boolean gameWon;
    private int startX, startY;
    private long lastGhostMoveTime;
    private final long ghostMoveInterval = 500; // 鬼魂移动间隔(毫秒)
    private List<int[]> ghostPath = new ArrayList<>();
    private final Random random = new Random();

    public MazeGameScreen() {
        super(Component.literal("迷宫游戏"));
        generateMaze();
    }

    @Override
    public void init() {
        // 计算绘制起始位置，使迷宫居中
        TILE_SIZE = Math.max(8, Math.min((this.width - 40) / MAZE_WIDTH, (this.height - 80) / MAZE_HEIGHT));
        startX = (this.width - MAZE_WIDTH * TILE_SIZE) / 2;
        startY = (this.height - MAZE_HEIGHT * TILE_SIZE) / 2;

        int centerX = this.width / 2;
        this.addRenderableWidget(Button.builder(Component.literal("重新开始"), b -> {
            generateMaze();
        }).pos(centerX - 50, this.height - 30).size(100, 20).build());

        this.addRenderableWidget(Button.builder(Component.literal("返回"), b -> {
            Minecraft.getInstance().setScreen(new GameSelectorScreen());
        }).pos(centerX - 50, this.height - 60).size(100, 20).build());
    }

    private void generateMaze() {
        maze = new char[MAZE_HEIGHT][MAZE_WIDTH];

        // 初始化迷宫 - 全部设为墙
        for (int y = 0; y < MAZE_HEIGHT; y++) {
            for (int x = 0; x < MAZE_WIDTH; x++) {
                maze[y][x] = '#';
            }
        }

        // 使用深度优先搜索生成迷宫
        Stack<int[]> stack = new Stack<>();
        playerX = 1;
        playerY = 1;
        stack.push(new int[]{playerX, playerY});
        maze[playerY][playerX] = ' ';

        int[][] directions = {{0, 2}, {2, 0}, {0, -2}, {-2, 0}};

        while (!stack.isEmpty()) {
            int[] current = stack.peek();
            List<int[]> neighbors = new ArrayList<>();

            for (int[] dir : directions) {
                int nx = current[0] + dir[0];
                int ny = current[1] + dir[1];

                if (nx > 0 && nx < MAZE_WIDTH - 1 && ny > 0 && ny < MAZE_HEIGHT - 1 && maze[ny][nx] == '#') {
                    neighbors.add(new int[]{nx, ny, dir[0], dir[1]});
                }
            }

            if (!neighbors.isEmpty()) {
                int[] next = neighbors.get(random.nextInt(neighbors.size()));
                maze[next[1]][next[0]] = ' ';
                maze[current[1] + next[3]/2][current[0] + next[2]/2] = ' ';
                stack.push(new int[]{next[0], next[1]});
            } else {
                stack.pop();
            }
        }

        // 设置出口
        maze[MAZE_HEIGHT-2][MAZE_WIDTH-2] = 'E';

        // 在玩家后方生成鬼魂
        placeGhostBehindPlayer();

        gameOver = false;
        gameWon = false;
        lastGhostMoveTime = System.currentTimeMillis();
        ghostPath.clear();
    }

    private void placeGhostBehindPlayer() {
        // 尝试在玩家后方3-5格的位置放置鬼魂
        int[][] directions = {{0, -1}, {1, 0}, {0, 1}, {-1, 0}}; // 上、右、下、左
        List<int[]> possiblePositions = new ArrayList<>();

        for (int i = 3; i <= 5; i++) {
            for (int[] dir : directions) {
                int gx = playerX + dir[0] * i;
                int gy = playerY + dir[1] * i;

                if (gx >= 0 && gx < MAZE_WIDTH && gy >= 0 && gy < MAZE_HEIGHT && maze[gy][gx] == ' ') {
                    possiblePositions.add(new int[]{gx, gy});
                }
            }
        }

        if (!possiblePositions.isEmpty()) {
            int[] pos = possiblePositions.get(random.nextInt(possiblePositions.size()));
            ghostX = pos[0];
            ghostY = pos[1];
        } else {
            // 如果找不到合适位置，随机放置
            do {
                ghostX = random.nextInt(MAZE_WIDTH);
                ghostY = random.nextInt(MAZE_HEIGHT);
            } while (maze[ghostY][ghostX] != ' ' ||
                    (ghostX == playerX && ghostY == playerY));
        }
    }

    @Override
    public void render(@NotNull GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        GameRenderHelper.fillDarkBackground(graphics, width, height);

        // 绘制迷宫
        for (int y = 0; y < MAZE_HEIGHT; y++) {
            for (int x = 0; x < MAZE_WIDTH; x++) {
                int posX = startX + x * TILE_SIZE;
                int posY = startY + y * TILE_SIZE;

                switch (maze[y][x]) {
                    case '#' -> { // 墙 - 明显的蓝灰色砖块
                        graphics.fill(posX, posY, posX + TILE_SIZE, posY + TILE_SIZE, 0xFF4A5568);
                        graphics.fill(posX + 1, posY + 1, posX + TILE_SIZE - 1, posY + TILE_SIZE - 1, 0xFF2D3748);
                    }
                    case 'E' -> { // 出口 - 明亮绿色
                        graphics.fill(posX, posY, posX + TILE_SIZE, posY + TILE_SIZE, 0xFF1A1A2E);
                        graphics.fill(posX + 3, posY + 3, posX + TILE_SIZE - 3, posY + TILE_SIZE - 3, 0xFF00CC00);
                        graphics.fill(posX + 5, posY + 5, posX + TILE_SIZE - 5, posY + TILE_SIZE - 5, 0xFF00FF44);
                    }
                    default -> // 路径 - 深色
                        graphics.fill(posX, posY, posX + TILE_SIZE, posY + TILE_SIZE, 0xFF1A1A2E);
                }
            }
        }

        // 绘制鬼魂路径(调试用)
        /*
        for (int[] pos : ghostPath) {
            int pathX = startX + pos[0] * TILE_SIZE + TILE_SIZE/2 - 2;
            int pathY = startY + pos[1] * TILE_SIZE + TILE_SIZE/2 - 2;
            graphics.fill(pathX, pathY, pathX + 4, pathY + 4, 0x44FF0000);
        }
        */

        // 绘制玩家
        int playerPosX = startX + playerX * TILE_SIZE;
        int playerPosY = startY + playerY * TILE_SIZE;
        graphics.fill(playerPosX + 3, playerPosY + 3, playerPosX + TILE_SIZE - 3, playerPosY + TILE_SIZE - 3, 0xFFFF0000);

        // 绘制鬼魂(带眼睛更恐怖)
        int ghostPosX = startX + ghostX * TILE_SIZE;
        int ghostPosY = startY + ghostY * TILE_SIZE;
        graphics.fill(ghostPosX + 2, ghostPosY + 2, ghostPosX + TILE_SIZE - 2, ghostPosY + TILE_SIZE - 2, 0xFF0000FF);
        // 眼睛(看向玩家方向)
        int eyeOffsetX = playerX > ghostX ? 4 : -4;
        int eyeOffsetY = playerY > ghostY ? 4 : -4;
        graphics.fill(ghostPosX + TILE_SIZE/2 + eyeOffsetX/2 - 2, ghostPosY + TILE_SIZE/2 - 2,
                ghostPosX + TILE_SIZE/2 + eyeOffsetX/2 + 2, ghostPosY + TILE_SIZE/2 + 2, 0xFFFFFFFF);
        graphics.fill(ghostPosX + TILE_SIZE/2 - eyeOffsetX/2 - 2, ghostPosY + TILE_SIZE/2 - 2,
                ghostPosX + TILE_SIZE/2 - eyeOffsetX/2 + 2, ghostPosY + TILE_SIZE/2 + 2, 0xFFFFFFFF);

        // 游戏状态提示
        if (gameOver) {
            graphics.drawCenteredString(this.font, "游戏结束! 被鬼抓住了!", this.width / 2, 30, 0xFFFF0000);
        } else if (gameWon) {
            graphics.drawCenteredString(this.font, "恭喜! 你逃出了迷宫!", this.width / 2, 30, 0xFF00FF00);
        } else {
            graphics.drawCenteredString(this.font, "WASD移动 - 找到出口并避开鬼魂!", this.width / 2, 30, 0xFFFFFF);
            // 显示鬼魂距离
            int distance = Math.abs(playerX - ghostX) + Math.abs(playerY - ghostY);
            graphics.drawCenteredString(this.font, "鬼魂距离: " + distance, this.width / 2, 50,
                    distance < 5 ? 0xFFFF0000 : 0xFFFFFF);
        }

        super.render(graphics, mouseX, mouseY, partialTick);
        if (showExitConfirm) GameRenderHelper.drawExitConfirmOverlay(graphics, font, width, height, mouseX, mouseY);
    }

    @Override
    public void tick() {
        super.tick();

        // 定期移动鬼魂
        if (!gameOver && !gameWon && System.currentTimeMillis() - lastGhostMoveTime > ghostMoveInterval) {
            moveGhost();
            lastGhostMoveTime = System.currentTimeMillis();

            // 检查是否被鬼抓住
            if (playerX == ghostX && playerY == ghostY) {
                gameOver = true;
            }
        }
    }

    @Override
    public boolean keyPressed(int keyCode, int scanCode, int modifiers) {
        if (keyCode == GLFW.GLFW_KEY_ESCAPE) {
            if (showExitConfirm) { showExitConfirm = false; return true; }
            if (gameOver || gameWon) { Minecraft.getInstance().setScreen(new GameSelectorScreen()); return true; }
            showExitConfirm = true; return true;
        }
        if (showExitConfirm) return true;
        if (gameOver || gameWon) return true;

        int newX = playerX;
        int newY = playerY;

        // 使用GLFW常量检测按键
        if (keyCode == GLFW.GLFW_KEY_W) {
            newY--;
        } else if (keyCode == GLFW.GLFW_KEY_S) {
            newY++;
        } else if (keyCode == GLFW.GLFW_KEY_A) {
            newX--;
        } else if (keyCode == GLFW.GLFW_KEY_D) {
            newX++;
        } else {
            return false;
        }

        // 检查移动是否有效
        if (newX >= 0 && newX < MAZE_WIDTH && newY >= 0 && newY < MAZE_HEIGHT && maze[newY][newX] != '#') {
            playerX = newX;
            playerY = newY;

            // 检查是否到达出口
            if (maze[playerY][playerX] == 'E') {
                gameWon = true;
                if (Minecraft.getInstance().player != null) {
                    Minecraft.getInstance().player.playSound(SoundEvents.PLAYER_LEVELUP, 1.0F, 1.0F);
                }
            }

            return true;
        }

        return false;
    }

    private void moveGhost() {
        // 使用A*寻路算法找到最短路径
        ghostPath = findPath(ghostX, ghostY, playerX, playerY);

        if (ghostPath != null && ghostPath.size() > 1) {
            // 沿着路径移动一步
            int[] nextStep = ghostPath.get(1);
            ghostX = nextStep[0];
            ghostY = nextStep[1];
        } else {
            // 如果找不到路径，使用简单追踪
            simpleChase();
        }
    }

    private void simpleChase() {
        // 简单追踪作为备用方案
        List<int[]> possibleMoves = new ArrayList<>();
        int[][] directions = {{0, 1}, {1, 0}, {0, -1}, {-1, 0}};

        for (int[] dir : directions) {
            int nx = ghostX + dir[0];
            int ny = ghostY + dir[1];

            if (nx >= 0 && nx < MAZE_WIDTH && ny >= 0 && ny < MAZE_HEIGHT && maze[ny][nx] != '#') {
                possibleMoves.add(new int[]{nx, ny});
            }
        }

        if (!possibleMoves.isEmpty()) {
            // 选择最接近玩家的方向
            possibleMoves.sort(Comparator.comparingInt(move ->
                    Math.abs(move[0] - playerX) + Math.abs(move[1] - playerY)));

            ghostX = possibleMoves.get(0)[0];
            ghostY = possibleMoves.get(0)[1];
        }
    }

    private List<int[]> findPath(int startX, int startY, int targetX, int targetY) {
        // A*寻路算法实现
        PriorityQueue<Node> openSet = new PriorityQueue<>();
        Map<String, Node> allNodes = new HashMap<>();

        Node startNode = new Node(startX, startY, null, 0, heuristic(startX, startY, targetX, targetY));
        openSet.add(startNode);
        allNodes.put(startX + "," + startY, startNode);

        int[][] directions = {{0, 1}, {1, 0}, {0, -1}, {-1, 0}};

        while (!openSet.isEmpty()) {
            Node current = openSet.poll();

            if (current.x == targetX && current.y == targetY) {
                return reconstructPath(current);
            }

            for (int[] dir : directions) {
                int nx = current.x + dir[0];
                int ny = current.y + dir[1];

                if (nx >= 0 && nx < MAZE_WIDTH && ny >= 0 && ny < MAZE_HEIGHT && maze[ny][nx] != '#') {
                    String key = nx + "," + ny;
                    double newGScore = current.gScore + 1;
                    Node neighbor = allNodes.getOrDefault(key, new Node(nx, ny));

                    if (newGScore < neighbor.gScore) {
                        neighbor.parent = current;
                        neighbor.gScore = newGScore;
                        neighbor.fScore = newGScore + heuristic(nx, ny, targetX, targetY);

                        if (!openSet.contains(neighbor)) {
                            openSet.add(neighbor);
                        }
                    }
                }
            }
        }

        return null; // 没有找到路径
    }

    private double heuristic(int x1, int y1, int x2, int y2) {
        // 曼哈顿距离作为启发式函数
        return Math.abs(x1 - x2) + Math.abs(y1 - y2);
    }

    private List<int[]> reconstructPath(Node endNode) {
        List<int[]> path = new ArrayList<>();
        Node current = endNode;

        while (current != null) {
            path.add(0, new int[]{current.x, current.y});
            current = current.parent;
        }

        return path;
    }

    private static class Node implements Comparable<Node> {
        int x, y;
        Node parent;
        double gScore; // 从起点到当前节点的成本
        double fScore; // gScore + 启发式估计

        Node(int x, int y) {
            this(x, y, null, Double.POSITIVE_INFINITY, Double.POSITIVE_INFINITY);
        }

        Node(int x, int y, Node parent, double gScore, double fScore) {
            this.x = x;
            this.y = y;
            this.parent = parent;
            this.gScore = gScore;
            this.fScore = fScore;
        }

        @Override
        public int compareTo(Node other) {
            return Double.compare(this.fScore, other.fScore);
        }

        @Override
        public boolean equals(Object obj) {
            if (this == obj) return true;
            if (obj == null || getClass() != obj.getClass()) return false;
            Node node = (Node) obj;
            return x == node.x && y == node.y;
        }

        @Override
        public int hashCode() {
            return Objects.hash(x, y);
        }
    }

    @Override
    public boolean mouseClicked(double mx, double my, int btn) {
        if (showExitConfirm) {
            int click = GameRenderHelper.getExitConfirmClick((int)mx, (int)my, width, height);
            if (click == 1) { showExitConfirm = false; Minecraft.getInstance().setScreen(new GameSelectorScreen()); return true; }
            if (click == 2) { showExitConfirm = false; return true; }
            return true;
        }
        return super.mouseClicked(mx, my, btn);
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }
}