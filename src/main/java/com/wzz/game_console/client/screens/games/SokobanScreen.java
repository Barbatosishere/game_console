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
import org.lwjgl.glfw.GLFW;

import java.util.Random;

@OnlyIn(Dist.CLIENT)
public class SokobanScreen extends Screen {
    boolean showExitConfirm = false;
    private static int TILE_SIZE = 40;
    private char[][] level;
    private int playerX, playerY;
    private int levelWidth, levelHeight;
    private int startX, startY;
    private int currentLevel = 1;

    public SokobanScreen() {
        super(Component.literal("推箱子游戏"));
        generateLevel(currentLevel);
    }

    private void generateLevel(int levelNum) {
        Random rand = new Random(levelNum * 7919L + 271L);

        // ★ Bug修复：原版关卡参数增速过缓（gridSize 每 3 关 +1，boxCount 每 4 关 +1），
        //   玩家通关 5~6 关仍感觉不到明显难度提升。重新调参为：
        //     gridSize   = 5 + (levelNum-1)/1.5   → 第 1 关 5x5，第 5 关 8x8，第 10 关 11x11
        //     boxCount   = 1 + (levelNum-1)/2     → 第 1 关 1 个，第 5 关 3 个，第 10 关 5 个
        //     obstacleCount = 1 + (levelNum-1)/2  → 第 1 关 1 个，第 5 关 3 个，第 10 关 5 个
        int gridSize = Math.min(5 + (levelNum - 1) * 2 / 3, 14);
        int boxCount = Math.min(1 + (levelNum - 1) / 2, 6);
        int obstacleCount = Math.min(1 + (levelNum - 1) / 2, 8);

        // 创建网格
        char[][] grid = new char[gridSize][gridSize];
        levelWidth = gridSize;
        levelHeight = gridSize;

        // 填充边界墙
        for (int y = 0; y < gridSize; y++)
            for (int x = 0; x < gridSize; x++)
                grid[y][x] = (x == 0 || x == gridSize - 1 || y == 0 || y == gridSize - 1) ? '#' : ' ';

        // 放置内部障碍物
        for (int i = 0; i < obstacleCount; i++) {
            for (int attempt = 0; attempt < 30; attempt++) {
                int wx = 1 + rand.nextInt(gridSize - 2);
                int wy = 1 + rand.nextInt(gridSize - 2);
                if (grid[wy][wx] == ' ') {
                    grid[wy][wx] = '#';
                    break;
                }
            }
        }

        // 初始状态：箱子全部在目标点上（已解决状态 '+'）
        int placed = 0;
        for (int attempt = 0; attempt < 500 && placed < boxCount; attempt++) {
            int bx = 1 + rand.nextInt(gridSize - 2);
            int by = 1 + rand.nextInt(gridSize - 2);
            if (grid[by][bx] == ' ') {
                grid[by][bx] = '+';
                placed++;
            }
        }
        boxCount = Math.max(placed, 1);
        if (placed == 0) {
            grid[gridSize / 2][gridSize / 2] = '+';
            boxCount = 1;
        }

        // 放置玩家在左上角空地
        playerX = 1;
        playerY = 1;
        if (grid[1][1] != ' ') {
            outer:
            for (int y = 1; y < gridSize - 1; y++)
                for (int x = 1; x < gridSize - 1; x++)
                    if (grid[y][x] == ' ') { playerX = x; playerY = y; break outer; }
        }
        grid[playerY][playerX] = '@';

        // 打乱阶段：随机移动玩家来推动箱子离开目标点
        // 此方法保证生成的关卡一定可解（逆向操作即为解）
        // 使用固定种子确保同一关卡序号生成相同地图
        int[] dx = {1, -1, 0, 0};
        int[] dy = {0, 0, 1, -1};
        int pushes = 0;
        for (int step = 0; step < 300 && pushes < boxCount; step++) {
            int dir = rand.nextInt(4);
            int nx = playerX + dx[dir];
            int ny = playerY + dy[dir];
            if (nx <= 0 || nx >= gridSize - 1 || ny <= 0 || ny >= gridSize - 1) continue;
            if (grid[ny][nx] == '#') continue;

            if (grid[ny][nx] == '+' || grid[ny][nx] == '$') {
                boolean wasOnTarget = (grid[ny][nx] == '+');
                int bx = nx + dx[dir];
                int by = ny + dy[dir];
                if (bx <= 0 || bx >= gridSize - 1 || by <= 0 || by >= gridSize - 1) continue;
                if (grid[by][bx] == '#' || grid[by][bx] == '+' || grid[by][bx] == '$') continue;

                char oldPos = grid[playerY][playerX];
                grid[playerY][playerX] = (oldPos == '*') ? '.' : ' ';
                grid[ny][nx] = wasOnTarget ? '*' : '@';
                grid[by][bx] = '$';
                playerX = nx;
                playerY = ny;
                if (wasOnTarget) pushes++;
            } else if (grid[ny][nx] == '.') {
                char oldPos = grid[playerY][playerX];
                grid[playerY][playerX] = (oldPos == '*') ? '.' : ' ';
                playerX = nx;
                playerY = ny;
                grid[playerY][playerX] = '*';
            } else if (grid[ny][nx] == ' ') {
                char oldPos = grid[playerY][playerX];
                grid[playerY][playerX] = (oldPos == '*') ? '.' : ' ';
                playerX = nx;
                playerY = ny;
                grid[playerY][playerX] = '@';
            }
        }

        // 最终安全处理：如果仍有 '+' 未被推动，转为 '.'（移除未打乱的箱子）
        // 确保不会出现开局即胜利的情况
        for (int y = 1; y < gridSize - 1; y++) {
            for (int x = 1; x < gridSize - 1; x++) {
                if (grid[y][x] == '+') {
                    grid[y][x] = '.';
                }
            }
        }

        level = grid;
    }

    private void loadLevel(int levelNum) {
        if (levelNum < 1) levelNum = 1;
        currentLevel = levelNum;
        generateLevel(currentLevel);
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }

    @Override
    public void init() {
        clearWidgets();
        TILE_SIZE = Math.min(
                (width - 40) / levelWidth,  // 留出左右边距
                (height - 100) / levelHeight // 留出上下边距
        );

        startX = (width - levelWidth * TILE_SIZE) / 2;
        startY = (height - levelHeight * TILE_SIZE) / 2 - 20; // 上移给按钮留空间

        // 添加重置按钮
        int bottomY = startY + levelHeight * TILE_SIZE + 20;
        this.addRenderableWidget(Button.builder(Component.literal("重置 (R)"), b -> loadLevel(currentLevel))
                .pos(startX, bottomY)
                .size(levelWidth * TILE_SIZE / 2 - 5, 20).build());

        this.addRenderableWidget(Button.builder(Component.literal("下一关 (N)"), b -> loadLevel(currentLevel + 1))
                .pos(startX + levelWidth * TILE_SIZE / 2 + 5, bottomY)
                .size(levelWidth * TILE_SIZE / 2 - 5, 20).build());
    }

    @Override
    public boolean keyPressed(int keyCode, int scanCode, int modifiers) {
        // ESC 开关退出确认弹窗
        if (keyCode == GLFW.GLFW_KEY_ESCAPE) {
            showExitConfirm = !showExitConfirm;
            return true;
        }
        // 弹窗打开期间拦截移动/切关等全部游戏输入
        if (showExitConfirm) return true;
        // WASD控制移动
        switch (keyCode) {
            case GLFW.GLFW_KEY_W -> movePlayer(0, -1);
            case GLFW.GLFW_KEY_A -> movePlayer(-1, 0);
            case GLFW.GLFW_KEY_S -> movePlayer(0, 1);
            case GLFW.GLFW_KEY_D -> movePlayer(1, 0);
            case GLFW.GLFW_KEY_R -> loadLevel(currentLevel);
            case GLFW.GLFW_KEY_N -> loadLevel(currentLevel + 1);
            default -> {
                return super.keyPressed(keyCode, scanCode, modifiers);
            }
        }
        return true;
    }

    @Override
    public boolean mouseClicked(double mx, double my, int btn) {
        if (showExitConfirm) {
            int click = GameRenderHelper.getExitConfirmClick(mx, my, width, height);
            if (click == 1) { showExitConfirm = false; Minecraft.getInstance().setScreen(new GameSelectorScreen()); return true; }
            if (click == 2) { showExitConfirm = false; return true; }
            return true;
        }
        return super.mouseClicked(mx, my, btn);
    }

    @Override
    public void renderBackground(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        // 不渲染默认32x32像素菜单背景纹理和模糊效果,游戏自行绘制不透明背景
    }

    @Override
    public void render(GuiGraphics guiGraphics, int mouseX, int mouseY, float partialTick) {
        GameRenderHelper.fillDarkBackground(guiGraphics, width, height);

        // 绘制游戏地图
        for (int y = 0; y < levelHeight; y++) {
            for (int x = 0; x < levelWidth; x++) {
                int posX = startX + x * TILE_SIZE;
                int posY = startY + y * TILE_SIZE;

                // 绘制背景
                guiGraphics.fill(posX, posY, posX + TILE_SIZE, posY + TILE_SIZE, 0xFF333333);

                // 绘制游戏元素
                switch (level[y][x]) {
                    case '#' -> // 墙
                            guiGraphics.fill(posX + 2, posY + 2, posX + TILE_SIZE - 2, posY + TILE_SIZE - 2, 0xFF555555);
                    case '$' -> { // 箱子
                        guiGraphics.fill(posX + 5, posY + 5, posX + TILE_SIZE - 5, posY + TILE_SIZE - 5, 0xFFFFA500);
                        guiGraphics.fill(posX + 8, posY + 8, posX + TILE_SIZE - 8, posY + TILE_SIZE - 8, 0xFFDD8800);
                    }
                    case '.' -> // 目标点
                            guiGraphics.fill(posX + TILE_SIZE/4, posY + TILE_SIZE/4,
                                    posX + 3*TILE_SIZE/4, posY + 3*TILE_SIZE/4, 0xFF00FF00);
                    case '@' -> { // 玩家
                        guiGraphics.fill(posX + 3, posY + 3, posX + TILE_SIZE - 3, posY + TILE_SIZE - 3, 0xFFFF0000);
                        // 绘制玩家朝向指示
                        guiGraphics.fill(posX + TILE_SIZE/2 - 2, posY + 5,
                                posX + TILE_SIZE/2 + 2, posY + TILE_SIZE/2, 0xFFFFFFFF);
                    }
                    case '+' -> { // 箱子在目标点上
                        guiGraphics.fill(posX + TILE_SIZE/4, posY + TILE_SIZE/4,
                                posX + 3*TILE_SIZE/4, posY + 3*TILE_SIZE/4, 0xFF00FF00);
                        guiGraphics.fill(posX + 5, posY + 5, posX + TILE_SIZE - 5, posY + TILE_SIZE - 5, 0xFFFFA500);
                    }
                    case '*' -> { // 玩家在目标点上
                        // 先绘制目标点
                        guiGraphics.fill(posX + TILE_SIZE/4, posY + TILE_SIZE/4,
                                posX + 3*TILE_SIZE/4, posY + 3*TILE_SIZE/4, 0xFF00FF00);
                        // 再绘制玩家
                        guiGraphics.fill(posX + 3, posY + 3, posX + TILE_SIZE - 3, posY + TILE_SIZE - 3, 0xFFFF0000);
                    }
                }
            }
        }

        // 显示当前关卡和操作提示
        guiGraphics.drawCenteredString(font, "关卡: " + currentLevel,
                width / 2, startY - 30, 0xFFFFFF);
        guiGraphics.drawCenteredString(font, "WASD移动 | R重置 | N下一关",
                width / 2, startY - 15, 0xAAAAAA);

        super.render(guiGraphics, mouseX, mouseY, partialTick);
        if (showExitConfirm) GameRenderHelper.drawExitConfirmOverlay(guiGraphics, font, width, height, mouseX, mouseY);
    }

    private void movePlayer(int dx, int dy) {
        int newX = playerX + dx;
        int newY = playerY + dy;

        // 边界检查
        if (newX < 0 || newX >= levelWidth || newY < 0 || newY >= levelHeight) {
            return;
        }

        char targetCell = level[newY][newX];

        // 墙壁检查
        if (targetCell == '#') return;

        // 处理箱子移动
        if (targetCell == '$' || targetCell == '+') {
            int boxX = newX + dx;
            int boxY = newY + dy;

            // 检查箱子是否可以移动
            if (boxX < 0 || boxX >= levelWidth || boxY < 0 || boxY >= levelHeight) {
                return;
            }

            char boxTarget = level[boxY][boxX];
            if (boxTarget == '#' || boxTarget == '$' || boxTarget == '+') {
                return; // 不能推动
            }

            // 移动箱子
            level[boxY][boxX] = (boxTarget == '.') ? '+' : '$';
            level[newY][newX] = (targetCell == '+') ? '.' : ' ';
        }

        // 移动玩家
        char currentPos = level[playerY][playerX];
        // 恢复玩家原来的位置：如果是站在目标点上('*')，恢复为'.'，否则恢复为' '
        level[playerY][playerX] = (currentPos == '*') ? '.' : ' ';

        playerX = newX;
        playerY = newY;

        // 设置玩家新位置：如果移动到目标点('.')，则设为'*'，否则设为'@'
        level[playerY][playerX] = (targetCell == '.' || targetCell == '+') ? '*' : '@';

        checkWinCondition();
    }

    private void checkWinCondition() {
        boolean won = true;
        for (int y = 0; y < levelHeight; y++) {
            for (int x = 0; x < levelWidth; x++) {
                if (level[y][x] == '$') {
                    won = false;
                    break;
                }
            }
            if (!won) break;
        }

        if (won) {
            loadLevel(currentLevel + 1);
            if (Minecraft.getInstance().player != null) {
                Minecraft.getInstance().player.playSound(SoundEvents.PLAYER_LEVELUP, 1.0F, 1.0F);
            }
            init(); // 重新初始化UI
        }
    }
}