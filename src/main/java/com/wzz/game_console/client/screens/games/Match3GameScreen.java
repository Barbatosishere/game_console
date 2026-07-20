package com.wzz.game_console.client.screens.games;

import com.wzz.game_console.client.screens.GameSelectorScreen;
import com.wzz.game_console.util.GameRenderHelper;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.api.distmarker.OnlyIn;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;

@OnlyIn(Dist.CLIENT)
public class Match3GameScreen extends Screen {
    boolean showExitConfirm = false;
    private static final int GRID_SIZE = 8;
    private int CELL_SIZE = 32;
    private int GRID_START_X = 120;
    private int GRID_START_Y = 60;
    
    // 使用MC原版物品作为游戏元素
    private static final Item[] GAME_ITEMS = {
        Items.DIAMOND,
        Items.EMERALD,
        Items.GOLD_INGOT,
        Items.IRON_INGOT,
        Items.REDSTONE,
        Items.LAPIS_LAZULI,
        Items.COAL
    };
    
    private int[][] gameGrid;
    private boolean[][] selectedGrid;
    private int selectedX = -1, selectedY = -1;
    private int score = 0;
    private Random random = new Random();
    private List<AnimationEffect> animations = new ArrayList<>();
    
    // 动画效果类
    private static class AnimationEffect {
        int x, y, timer;
        boolean isMatch;
        
        AnimationEffect(int x, int y, boolean isMatch) {
            this.x = x;
            this.y = y;
            this.isMatch = isMatch;
            this.timer = 20; // 20 ticks动画
        }
    }
    
    public Match3GameScreen() {
        super(Component.literal("消消乐"));
        initializeGame();
    }
    
    private void initializeGame() {
        gameGrid = new int[GRID_SIZE][GRID_SIZE];
        selectedGrid = new boolean[GRID_SIZE][GRID_SIZE];
        
        // 随机填充游戏网格，避免初始匹配
        for (int x = 0; x < GRID_SIZE; x++) {
            for (int y = 0; y < GRID_SIZE; y++) {
                do {
                    gameGrid[x][y] = random.nextInt(GAME_ITEMS.length);
                } while (wouldCreateMatch(x, y, gameGrid[x][y]));
            }
        }
    }
    
    private boolean wouldCreateMatch(int x, int y, int itemType) {
        // 检查水平匹配
        int horizontalCount = 1;
        // 向左检查
        for (int i = x - 1; i >= 0 && gameGrid[i][y] == itemType; i--) {
            horizontalCount++;
        }
        // 向右检查
        for (int i = x + 1; i < GRID_SIZE && gameGrid[i][y] == itemType; i++) {
            horizontalCount++;
        }
        
        // 检查垂直匹配
        int verticalCount = 1;
        // 向上检查
        for (int i = y - 1; i >= 0 && gameGrid[x][i] == itemType; i--) {
            verticalCount++;
        }
        // 向下检查
        for (int i = y + 1; i < GRID_SIZE && gameGrid[x][i] == itemType; i++) {
            verticalCount++;
        }
        
        return horizontalCount >= 3 || verticalCount >= 3;
    }

    private void calcDynamicLayout() {
        CELL_SIZE = Math.max(16, Math.min((width - 80) / GRID_SIZE, (height - 100) / GRID_SIZE));
        GRID_START_X = (width - GRID_SIZE * CELL_SIZE) / 2;
        GRID_START_Y = (height - GRID_SIZE * CELL_SIZE) / 2;
    }

    @Override
    public void init() {
        // ★ 修复偏移：每次屏幕尺寸变化时重新计算布局
        calcDynamicLayout();
    }
    
    @Override
    public void render(GuiGraphics guiGraphics, int mouseX, int mouseY, float partialTick) {
        // 渲染背景
        GameRenderHelper.fillDarkBackground(guiGraphics, width, height);
        
        // 渲染标题
        guiGraphics.drawCenteredString(this.font, this.title, this.width / 2, 20, 0xFFFFFF);
        
        // 渲染分数
        guiGraphics.drawString(this.font, "分数: " + score, 20, 20, 0xFFFFFF);
        
        // 渲染游戏网格
        renderGameGrid(guiGraphics, mouseX, mouseY);
        
        // 渲染动画效果
        renderAnimations(guiGraphics);
        
        super.render(guiGraphics, mouseX, mouseY, partialTick);
        if (showExitConfirm) GameRenderHelper.drawExitConfirmOverlay(guiGraphics, font, width, height, mouseX, mouseY);
    }
    
    private void renderGameGrid(GuiGraphics guiGraphics, int mouseX, int mouseY) {
        for (int x = 0; x < GRID_SIZE; x++) {
            for (int y = 0; y < GRID_SIZE; y++) {
                int screenX = GRID_START_X + x * CELL_SIZE;
                int screenY = GRID_START_Y + y * CELL_SIZE;
                
                // 渲染背景框
                int backgroundColor = 0xFF444444;
                if (selectedGrid[x][y]) {
                    backgroundColor = 0xFF888888; // 选中状态
                } else if (isMouseOver(mouseX, mouseY, screenX, screenY)) {
                    backgroundColor = 0xFF666666; // 悬停状态
                }
                
                guiGraphics.fill(screenX, screenY, screenX + CELL_SIZE, screenY + CELL_SIZE, backgroundColor);
                guiGraphics.fill(screenX + 1, screenY + 1, screenX + CELL_SIZE - 1, screenY + CELL_SIZE - 1, 0xFF222222);
                
                // 渲染物品
                Item item = GAME_ITEMS[gameGrid[x][y]];
                ItemStack itemStack = new ItemStack(item);
                guiGraphics.renderItem(itemStack, screenX + 8, screenY + 8);
            }
        }
    }
    
    private void renderAnimations(GuiGraphics guiGraphics) {
        animations.removeIf(anim -> {
            anim.timer--;
            if (anim.timer > 0) {
                int screenX = GRID_START_X + anim.x * CELL_SIZE;
                int screenY = GRID_START_Y + anim.y * CELL_SIZE;
                
                if (anim.isMatch) {
                    // 匹配消除动画 - 闪烁效果
                    int alpha = (anim.timer % 4 < 2) ? 0x88 : 0xFF;
                    guiGraphics.fill(screenX, screenY, screenX + CELL_SIZE, screenY + CELL_SIZE, 
                                   (alpha << 24) | 0xFFFF00);
                }
                return false;
            }
            return true;
        });
    }
    
    private boolean isMouseOver(int mouseX, int mouseY, int x, int y) {
        return mouseX >= x && mouseX < x + CELL_SIZE && mouseY >= y && mouseY < y + CELL_SIZE;
    }
    
    @Override
    public boolean mouseClicked(double mouseX, double mouseY, int button) {
        if (showExitConfirm) { int click = GameRenderHelper.getExitConfirmClick(mouseX, mouseY, width, height); if (click == 1) { showExitConfirm = false; Minecraft.getInstance().setScreen(new GameSelectorScreen()); return true; } if (click == 2) { showExitConfirm = false; return true; } return true; }
        if (button == 0) { // 左键点击
            int gridX = (int) ((mouseX - GRID_START_X) / CELL_SIZE);
            int gridY = (int) ((mouseY - GRID_START_Y) / CELL_SIZE);
            
            if (gridX >= 0 && gridX < GRID_SIZE && gridY >= 0 && gridY < GRID_SIZE) {
                handleCellClick(gridX, gridY);
                return true;
            }
        }
        return super.mouseClicked(mouseX, mouseY, button);
    }
    
    private void handleCellClick(int x, int y) {
        if (selectedX == -1 && selectedY == -1) {
            // 第一次选择
            selectedX = x;
            selectedY = y;
            selectedGrid[x][y] = true;
        } else if (selectedX == x && selectedY == y) {
            // 取消选择
            selectedGrid[x][y] = false;
            selectedX = -1;
            selectedY = -1;
        } else if (isAdjacent(selectedX, selectedY, x, y)) {
            // 尝试交换
            swapAndCheck(selectedX, selectedY, x, y);
            clearSelection();
        } else {
            // 选择新的位置
            clearSelection();
            selectedX = x;
            selectedY = y;
            selectedGrid[x][y] = true;
        }
    }
    
    private boolean isAdjacent(int x1, int y1, int x2, int y2) {
        return Math.abs(x1 - x2) + Math.abs(y1 - y2) == 1;
    }
    
    private void swapAndCheck(int x1, int y1, int x2, int y2) {
        // 交换物品
        int temp = gameGrid[x1][y1];
        gameGrid[x1][y1] = gameGrid[x2][y2];
        gameGrid[x2][y2] = temp;
        
        // 检查是否有匹配
        List<int[]> matches = findAllMatches();
        if (!matches.isEmpty()) {
            // 有匹配，处理消除
            processMatches(matches);
        } else {
            // 没有匹配，交换回来
            temp = gameGrid[x1][y1];
            gameGrid[x1][y1] = gameGrid[x2][y2];
            gameGrid[x2][y2] = temp;
        }
    }
    
    private List<int[]> findAllMatches() {
        List<int[]> matches = new ArrayList<>();
        boolean[][] checked = new boolean[GRID_SIZE][GRID_SIZE];
        
        // 检查水平匹配
        for (int y = 0; y < GRID_SIZE; y++) {
            for (int x = 0; x < GRID_SIZE - 2; x++) {
                if (!checked[x][y] && gameGrid[x][y] == gameGrid[x + 1][y] && 
                    gameGrid[x][y] == gameGrid[x + 2][y]) {
                    
                    int endX = x + 2;
                    while (endX + 1 < GRID_SIZE && gameGrid[x][y] == gameGrid[endX + 1][y]) {
                        endX++;
                    }
                    
                    for (int i = x; i <= endX; i++) {
                        if (!checked[i][y]) {
                            matches.add(new int[]{i, y});
                            checked[i][y] = true;
                        }
                    }
                }
            }
        }
        
        // 检查垂直匹配
        for (int x = 0; x < GRID_SIZE; x++) {
            for (int y = 0; y < GRID_SIZE - 2; y++) {
                if (!checked[x][y] && gameGrid[x][y] == gameGrid[x][y + 1] && 
                    gameGrid[x][y] == gameGrid[x][y + 2]) {
                    
                    int endY = y + 2;
                    while (endY + 1 < GRID_SIZE && gameGrid[x][y] == gameGrid[x][endY + 1]) {
                        endY++;
                    }
                    
                    for (int i = y; i <= endY; i++) {
                        if (!checked[x][i]) {
                            matches.add(new int[]{x, i});
                            checked[x][i] = true;
                        }
                    }
                }
            }
        }
        
        return matches;
    }
    
    private void processMatches(List<int[]> matches) {
        // 添加消除动画
        for (int[] match : matches) {
            animations.add(new AnimationEffect(match[0], match[1], true));
        }
        
        // 增加分数
        score += matches.size() * 10;
        
        // 移除匹配的物品
        for (int[] match : matches) {
            gameGrid[match[0]][match[1]] = -1; // 标记为空
        }
        
        // 掉落物品
        dropItems();

        // 填充新物品
        fillEmptySpaces();
        
        // 检查是否有连锁反应
        List<int[]> newMatches = findAllMatches();
        if (!newMatches.isEmpty()) {
            processMatches(newMatches); // 递归处理连锁
        }
    }
    
    private void dropItems() {
        for (int x = 0; x < GRID_SIZE; x++) {
            int writePos = GRID_SIZE - 1;
            for (int y = GRID_SIZE - 1; y >= 0; y--) {
                if (gameGrid[x][y] != -1) {
                    if (writePos != y) {
                        gameGrid[x][writePos] = gameGrid[x][y];
                        gameGrid[x][y] = -1;
                    }
                    writePos--;
                }
            }
        }
    }
    
    private void fillEmptySpaces() {
        for (int x = 0; x < GRID_SIZE; x++) {
            for (int y = 0; y < GRID_SIZE; y++) {
                if (gameGrid[x][y] == -1) {
                    gameGrid[x][y] = random.nextInt(GAME_ITEMS.length);
                }
            }
        }
    }
    
    private void clearSelection() {
        if (selectedX != -1 && selectedY != -1) {
            selectedGrid[selectedX][selectedY] = false;
        }
        selectedX = -1;
        selectedY = -1;
    }
    
    @Override
    public void tick() {
        super.tick();
        calcDynamicLayout();
    }
    
    @Override
    public boolean isPauseScreen() {
        return false; // 不暂停游戏
    }
    
    @Override
    public boolean keyPressed(int keyCode, int scanCode, int modifiers) {
        if (keyCode == 256) { // ESC键
            if (showExitConfirm) { showExitConfirm = false; } else { showExitConfirm = true; }
            return true;
        }
        if (showExitConfirm) return true;
        return super.keyPressed(keyCode, scanCode, modifiers);
    }
}