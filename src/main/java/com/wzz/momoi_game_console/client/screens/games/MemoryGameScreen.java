package com.wzz.momoi_game_console.client.screens.games;

import com.wzz.momoi_game_console.client.screens.GameSelectorScreen;
import com.wzz.momoi_game_console.util.GameRenderHelper;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.resources.sounds.SimpleSoundInstance;
import net.minecraft.network.chat.Component;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.util.Mth;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.api.distmarker.OnlyIn;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;

@OnlyIn(Dist.CLIENT)
public class MemoryGameScreen extends Screen {
    boolean showExitConfirm = false;
    private static final int GRID_SIZE = 8; // 4x4网格
    private static int CELL_SIZE = 40;
    private static final int CELL_SPACING = 5;
    private static final int GRID_WIDTH = GRID_SIZE * CELL_SIZE + (GRID_SIZE - 1) * CELL_SPACING;
    private static final int GRID_HEIGHT = GRID_SIZE * CELL_SIZE + (GRID_SIZE - 1) * CELL_SPACING;
    
    // 颜色定义
    private static final int NORMAL_COLOR = 0xFF404040;
    private static final int HIGHLIGHT_COLOR = 0xFFFFFF00;
    private static final int BORDER_COLOR = 0xFF808080;
    private static final int CLICKED_COLOR = 0xFF00FF00;
    
    // 游戏状态
    private enum GameState {
        WAITING_START,
        SHOWING_SEQUENCE,
        WAITING_INPUT,
        GAME_OVER,
        SUCCESS
    }
    
    private GameState gameState = GameState.WAITING_START;
    private List<Integer> sequence = new ArrayList<>();
    private int currentSequenceIndex = 0;
    private int playerInputIndex = 0;
    private int score = 0;
    private int level = 1;
    
    // 动画相关
    private long lastSequenceTime = 0;
    private int highlightedCell = -1;
    private long highlightStartTime = 0;
    private static final long HIGHLIGHT_DURATION = 800; // 高亮持续时间（毫秒）
    private long sequenceDelay = 1200; // 序列间隔时间（毫秒）
    private static final long MIN_SEQUENCE_DELAY = 400; // 最小间隔时间
    
    // 网格位置
    private int gridStartX;
    private int gridStartY;
    
    // 按钮
    private Button startButton;
    private Button resetButton;
    
    private Random random = new Random();
    
    public MemoryGameScreen() {
        super(Component.literal("记忆反应"));
    }
    
    @Override
    public void init() {
        super.init();
        this.gridStartX = (this.width - GRID_WIDTH) / 2;
        this.gridStartY = (this.height - GRID_HEIGHT) / 2;
        int buttonY = this.gridStartY + GRID_HEIGHT + 20;
        this.startButton = Button.builder(Component.literal("开始游戏"), button -> startGame())
                .bounds(this.width / 2 - 100, buttonY, 90, 20)
                .build();
        this.addRenderableWidget(this.startButton);
        
        this.resetButton = Button.builder(Component.literal("返回"), button -> Minecraft.getInstance().setScreen(new GameSelectorScreen()))
                .bounds(this.width / 2 + 10, buttonY, 90, 20)
                .build();
        this.addRenderableWidget(this.resetButton);
    }
    
    @Override
    public void render(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        GameRenderHelper.fillDarkBackground(graphics, width, height);
        renderGrid(graphics, mouseX, mouseY);
        renderGameInfo(graphics);
        super.render(graphics, mouseX, mouseY, partialTick);
        if (showExitConfirm) GameRenderHelper.drawExitConfirmOverlay(graphics, font, width, height, mouseX, mouseY);
    }
    
    private void renderGrid(GuiGraphics graphics, int mouseX, int mouseY) {
        for (int row = 0; row < GRID_SIZE; row++) {
            for (int col = 0; col < GRID_SIZE; col++) {
                int cellIndex = row * GRID_SIZE + col;
                int x = gridStartX + col * (CELL_SIZE + CELL_SPACING);
                int y = gridStartY + row * (CELL_SIZE + CELL_SPACING);
                int color = NORMAL_COLOR;
                if (cellIndex == highlightedCell) {
                    long currentTime = System.currentTimeMillis();
                    if (currentTime - highlightStartTime < HIGHLIGHT_DURATION) {
                        float progress = (float)(currentTime - highlightStartTime) / HIGHLIGHT_DURATION;
                        float alpha = 0.5f + 0.5f * Mth.sin(progress * 8 * Mth.PI);
                        int brightness = (int)(255 * alpha);
                        color = 0xFF000000 | (brightness << 16) | (brightness << 8) | 0;
                    }
                }
                if (isMouseOverCell(mouseX, mouseY, x, y) && gameState == GameState.WAITING_INPUT) {
                    color = brightenColor(color, 1.2f);
                }
                graphics.fill(x - 1, y - 1, x + CELL_SIZE + 1, y + CELL_SIZE + 1, BORDER_COLOR);
                graphics.fill(x, y, x + CELL_SIZE, y + CELL_SIZE, color);
            }
        }
    }
    
    private void renderGameInfo(GuiGraphics graphics) {
        int infoY = this.gridStartY - 50;
        String scoreText = "分数: " + score;
        String levelText = "回合: " + level;
        graphics.drawString(this.font, scoreText, this.width / 2 - 60, infoY, 0xFFFFFF);
        graphics.drawString(this.font, levelText, this.width / 2 + 20, infoY, 0xFFFFFF);
        String stateText = getStateText();
        int textWidth = this.font.width(stateText);
        graphics.drawString(this.font, stateText, (this.width - textWidth) / 2, infoY + 15, 0xFFFF00);
    }
    
    private String getStateText() {
        return switch (gameState) {
            case WAITING_START -> "点击开始游戏以开始游戏！";
            case SHOWING_SEQUENCE -> "观看序列……";
            case WAITING_INPUT -> "重复该序列";
            case GAME_OVER -> "游戏结束！最终得分：" + score;
            case SUCCESS -> "下一关……";
        };
    }
    
    private boolean isMouseOverCell(int mouseX, int mouseY, int cellX, int cellY) {
        return mouseX >= cellX && mouseX < cellX + CELL_SIZE && 
               mouseY >= cellY && mouseY < cellY + CELL_SIZE;
    }
    
    private int brightenColor(int color, float factor) {
        int r = (color >> 16) & 0xFF;
        int g = (color >> 8) & 0xFF;
        int b = color & 0xFF;
        
        r = Math.min(255, (int)(r * factor));
        g = Math.min(255, (int)(g * factor));
        b = Math.min(255, (int)(b * factor));
        
        return 0xFF000000 | (r << 16) | (g << 8) | b;
    }
    
    @Override
    public boolean mouseClicked(double mouseX, double mouseY, int button) {
        if (showExitConfirm) { int click = GameRenderHelper.getExitConfirmClick(mouseX, mouseY, width, height); if (click == 1) { showExitConfirm = false; Minecraft.getInstance().setScreen(new GameSelectorScreen()); return true; } if (click == 2) { showExitConfirm = false; return true; } return true; }
        if (gameState == GameState.WAITING_INPUT) {
            int clickedCell = getCellAtPosition((int)mouseX, (int)mouseY);
            if (clickedCell != -1) {
                handleCellClick(clickedCell);
                return true;
            }
        }
        return super.mouseClicked(mouseX, mouseY, button);
    }
    
    private int getCellAtPosition(int mouseX, int mouseY) {
        for (int row = 0; row < GRID_SIZE; row++) {
            for (int col = 0; col < GRID_SIZE; col++) {
                int x = gridStartX + col * (CELL_SIZE + CELL_SPACING);
                int y = gridStartY + row * (CELL_SIZE + CELL_SPACING);
                
                if (isMouseOverCell(mouseX, mouseY, x, y)) {
                    return row * GRID_SIZE + col;
                }
            }
        }
        return -1;
    }
    
    private void handleCellClick(int cellIndex) {
        if (sequence.get(playerInputIndex) == cellIndex) {
            playSuccessSound();
            playerInputIndex++;
            if (playerInputIndex >= sequence.size()) {
                score += level * 10;
                level++;
                gameState = GameState.SUCCESS;
                new Thread(() -> {
                    try {
                        Thread.sleep(1000);
                        addNextSequenceItem();
                        startShowingSequence();
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                    }
                }).start();
            }
        } else {
            gameState = GameState.GAME_OVER;
            playFailSound();
        }
    }
    
    private void startGame() {
        resetGame();
        sequence.add(random.nextInt(GRID_SIZE * GRID_SIZE));
        startShowingSequence();
    }
    
    private void resetGame() {
        sequence.clear();
        currentSequenceIndex = 0;
        playerInputIndex = 0;
        score = 0;
        level = 1;
        gameState = GameState.WAITING_START;
        highlightedCell = -1;
        sequenceDelay = 1200;
    }
    
    private void addNextSequenceItem() {
        sequence.add(random.nextInt(GRID_SIZE * GRID_SIZE));
        sequenceDelay = Math.max(MIN_SEQUENCE_DELAY, sequenceDelay - 50);
    }
    
    private void startShowingSequence() {
        gameState = GameState.SHOWING_SEQUENCE;
        currentSequenceIndex = 0;
        playerInputIndex = 0;
        showNextSequenceItem();
    }
    
    private void showNextSequenceItem() {
        if (currentSequenceIndex < sequence.size()) {
            highlightedCell = sequence.get(currentSequenceIndex);
            highlightStartTime = System.currentTimeMillis();
            lastSequenceTime = System.currentTimeMillis();
            
            // 播放声音
            playHighlightSound();
            
            currentSequenceIndex++;
            
            // 安排下一个序列项
            new Thread(() -> {
                try {
                    Thread.sleep(sequenceDelay);
                    if (currentSequenceIndex < sequence.size()) {
                        showNextSequenceItem();
                    } else {
                        // 序列显示完毕，等待玩家输入
                        gameState = GameState.WAITING_INPUT;
                        highlightedCell = -1;
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }).start();
        }
    }
    
    private void playHighlightSound() {
        if (this.minecraft != null && this.minecraft.getSoundManager() != null) {
            this.minecraft.getSoundManager().play(SimpleSoundInstance.forUI(SoundEvents.EXPERIENCE_ORB_PICKUP, 1.0f + currentSequenceIndex * 0.1f));
        }
    }
    
    private void playSuccessSound() {
        if (this.minecraft != null && this.minecraft.getSoundManager() != null) {
            this.minecraft.getSoundManager().play(SimpleSoundInstance.forUI(SoundEvents.EXPERIENCE_ORB_PICKUP, 1.2f));
        }
    }
    
    private void playFailSound() {
        if (this.minecraft != null && this.minecraft.getSoundManager() != null) {
            this.minecraft.getSoundManager().play(SimpleSoundInstance.forUI(SoundEvents.VILLAGER_NO, 1.0f));
        }
    }
    
    @Override
    public void tick() {
        super.tick();
        if (highlightedCell != -1) {
            long currentTime = System.currentTimeMillis();
            if (currentTime - highlightStartTime >= HIGHLIGHT_DURATION) {
                if (gameState == GameState.SHOWING_SEQUENCE) {
                    highlightedCell = -1;
                }
            }
        }
    }
    
    @Override
    public boolean keyPressed(int keyCode, int scanCode, int modifiers) {
        if (keyCode == 256) { if (showExitConfirm) { showExitConfirm = false; } else { showExitConfirm = true; } return true; }
        if (showExitConfirm) return true;
        return super.keyPressed(keyCode, scanCode, modifiers);
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }
}