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

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

@OnlyIn(Dist.CLIENT)
public class SokobanScreen extends Screen {
    boolean showExitConfirm = false;
    private static int TILE_SIZE = 40;
    private char[][] level;
    private int playerX, playerY;
    private int levelWidth, levelHeight;
    private int startX, startY;
    private int currentLevel = 1;
    private final List<char[][]> levels = new ArrayList<>();

    public SokobanScreen() {
        super(Component.literal("推箱子游戏"));
        initializeLevels();
        loadLevel(currentLevel);
    }

    private void initializeLevels() {
        levels.add(new char[][]{
                {'#','#','#','#','#'},
                {'#',' ',' ',' ','#'},
                {'#',' ','$','.','#'},
                {'#','@',' ',' ','#'},
                {'#','#','#','#','#'}
        });

        levels.add(new char[][]{
                {'#','#','#','#','#'},
                {'#','.','#',' ','#'},
                {'#',' ','$',' ','#'},
                {'#','@',' ',' ','#'},
                {'#','#','#','#','#'}
        });

        levels.add(new char[][]{
                {'#','#','#','#','#','#'},
                {'#',' ',' ',' ','.','#'},
                {'#','.','$','$','@','#'},
                {'#',' ',' ',' ',' ','#'},
                {'#','#','#','#','#','#'}
        });

        levels.add(new char[][]{
                {'#','#','#','#','#','#'},
                {'#','.',' ',' ',' ','#'},
                {'#',' ','#','$','@','#'},
                {'#',' ',' ',' ',' ','#'},
                {'#','#','#','#','#','#'}
        });

        levels.add(new char[][]{
                {'#','#','#','#','#','#','#'},
                {'#',' ',' ',' ','.',' ','#'},
                {'#',' ',' ','$','#',' ','#'},
                {'#','.','$','@','$','.','#'},
                {'#',' ','#',' ','#',' ','#'},
                {'#',' ',' ',' ',' ',' ','#'},
                {'#','#','#','#','#','#','#'}
        });

        levels.add(new char[][]{
                {'#','#','#','#','#','#','#'},
                {'#','.',' ','#',' ','.','#'},
                {'#',' ','$',' ','$',' ','#'},
                {'#',' ',' ','@',' ',' ','#'},
                {'#',' ','$',' ','$',' ','#'},
                {'#','.',' ','#',' ','.','#'},
                {'#','#','#','#','#','#','#'}
        });

        levels.add(new char[][]{
                {'#','#','#','#','#','#','#'},
                {'#',' ','.',' ','.','.','#'},
                {'#',' ','$',' ','$',' ','#'},
                {'#','$',' ','@',' ','$','#'},
                {'#',' ','$',' ','$',' ','#'},
                {'#','.','.',' ','.',' ','#'},
                {'#','#','#','#','#','#','#'}
        });

        levels.add(new char[][]{
                {'#','#','#','#','#','#','#','#'},
                {'#','.','#',' ','#','.',' ','#'},
                {'#',' ','$',' ','$',' ',' ','#'},
                {'#',' ',' ','@',' ',' ',' ','#'},
                {'#',' ','$',' ','$',' ',' ','#'},
                {'#','.','#',' ','#','.',' ','#'},
                {'#','#','#','#','#','#','#','#'}
        });

        levels.add(new char[][]{
                {'#','#','#','#','#','#','#','#','#'},
                {'#',' ','.','#','.','.',' ',' ','#'},
                {'#',' ','$',' ','$',' ','$',' ','#'},
                {'#',' ',' ','@',' ',' ',' ',' ','#'},
                {'#',' ','$',' ','$',' ','$',' ','#'},
                {'#',' ','.','#','.','.',' ',' ','#'},
                {'#','#','#','#','#','#','#','#','#'}
        });

        levels.add(new char[][]{
                {'#','#','#','#','#','#','#','#','#','#'},
                {'#','.',' ','.','.','.',' ','.','.','#'},
                {'#',' ',' ',' ',' ',' ',' ',' ',' ','#'},
                {'#',' ','$','$','$','$','$','$',' ','#'},
                {'#',' ',' ',' ','@',' ',' ',' ',' ','#'},
                {'#',' ','$','$','$','$','$','$',' ','#'},
                {'#',' ',' ',' ',' ',' ',' ',' ',' ','#'},
                {'#','.',' ','.','.','.','.','.',' ','#'},
                {'#','#','#','#','#','#','#','#','#','#'}
        });

        levels.add(new char[][]{
                {'#','#','#','#','#','#','#','#','#','#'},
                {'#','.','#',' ',' ',' ',' ',' ',' ','#'},
                {'#',' ','#',' ',' ',' ',' ',' ','#','#'},
                {'#',' ',' ','$',' ','#',' ','$',' ','#'},
                {'#',' ',' ',' ','@','#',' ','#',' ','#'},
                {'#',' ',' ',' ',' ',' ',' ',' ',' ','#'},
                {'#',' ','#','$','#',' ',' ',' ',' ','#'},
                {'#','.','#',' ',' ',' ','.',' ',' ','#'},
                {'#','#','#','#','#','#','#','#','#','#'}
        });

        levels.add(new char[][]{
                {'#','#','#','#','#','#','#','#'},
                {'#','.','#','#',' ',' ','.','#'},
                {'#',' ','#',' ','$',' ',' ','#'},
                {'#',' ','$','@',' ',' ','#','#'},
                {'#',' ',' ',' ',' ',' ',' ','#'},
                {'#',' ','#',' ','#',' ',' ','#'},
                {'#','#','#','#','#','#','#','#'}
        });

        levels.add(new char[][]{
                {'#','#','#','#','#','#','#','#'},
                {'#',' ',' ','#',' ',' ',' ','#'}, // 修复：末列原为'.'缺右墙，导致关卡不可解
                {'#',' ','.','$',' ',' ',' ','#'}, // 同步补目标点：左上封闭区的箱子只能推到此格，保证2箱2目标可通关
                {'#',' ','#',' ','#',' ','#','#'},
                {'#',' ',' ','@',' ',' ','#','#'},
                {'#',' ','$','#',' ',' ',' ','#'},
                {'#',' ',' ','.','#',' ',' ','#'},
                {'#','#','#','#','#','#','#','#'}
        });
    }

    private void loadLevel(int levelNum) {
        if (levelNum < 1 || levelNum > levels.size()) {
            currentLevel = 1; // 循环回到第一关
        } else {
            currentLevel = levelNum;
        }

        level = copyLevel(levels.get(currentLevel - 1));
        levelWidth = level[0].length;
        levelHeight = level.length;

        // 查找玩家位置
        for (int y = 0; y < levelHeight; y++) {
            for (int x = 0; x < levelWidth; x++) {
                if (level[y][x] == '@') {
                    playerX = x;
                    playerY = y;
                }
            }
        }
    }

    private char[][] copyLevel(char[][] original) {
        char[][] copy = new char[original.length][];
        for (int i = 0; i < original.length; i++) {
            copy[i] = Arrays.copyOf(original[i], original[i].length);
        }
        return copy;
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
        guiGraphics.drawCenteredString(font, "关卡: " + currentLevel + "/" + levels.size(),
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