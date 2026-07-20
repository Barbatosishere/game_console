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
public class MouseTunnelGameScreen extends Screen {
    boolean showExitConfirm = false;

    // 游戏常量
    private static final int SEGMENT_WIDTH = 8;    // 每段宽度
    private static final int MIN_TUNNEL_HEIGHT = 60; // 最小通道高度
    private static final int MAX_TUNNEL_HEIGHT = 120; // 最大通道高度
    private static final int SCROLL_SPEED = 2; // 滚动速度

    // 颜色定义
    private static final int WALL_COLOR = 0xFF8B4513;      // 棕色墙壁
    private static final int TUNNEL_COLOR = 0xFF000000;    // 黑色通道
    private static final int PLAYER_COLOR = 0xFF00FF00;    // 绿色玩家点
    private static final int DANGER_COLOR = 0xFFFF0000;    // 红色危险区域

    // 游戏状态
    private enum GameState {
        WAITING_START,
        PLAYING,
        GAME_OVER
    }

    private GameState gameState = GameState.WAITING_START;
    private long gameStartTime = 0;
    private long survivalTime = 0;
    private int score = 0;
    private int bestScore = 0;

    // 通道数据
    private List<TunnelSegment> tunnelSegments = new ArrayList<>();
    private int scrollOffset = 0;
    private int tunnelSegmentCount; // 动态计算需要的段数

    // 鼠标追踪
    private int playerX;
    private int playerY;
    private boolean mouseInTunnel = true;

    // 游戏参数
    private Random random = new Random();
    private int difficulty = 1;
    private long lastDifficultyIncrease = 0;

    // UI组件
    private Button startButton;
    private Button exitButton;

    // 通道段类
    private static class TunnelSegment {
        int centerY;      // 通道中心Y坐标
        int height;       // 通道高度
        int topY;         // 上边界
        int bottomY;      // 下边界

        TunnelSegment(int centerY, int height) {
            this.centerY = centerY;
            this.height = height;
            updateBounds();
        }

        void updateBounds() {
            this.topY = centerY - height / 2;
            this.bottomY = centerY + height / 2;
        }

        void setHeight(int newHeight) {
            this.height = newHeight;
            updateBounds();
        }
    }

    public MouseTunnelGameScreen() {
        super(Component.literal("Mouse Tunnel Game"));
    }

    @Override
    public void init() {
        super.init();
        playerX = 100;
        playerY = this.height / 2;

        // 计算需要的通道段数量，确保覆盖屏幕宽度 + 额外缓冲
        tunnelSegmentCount = (this.width / SEGMENT_WIDTH) + 20; // 额外20段作为缓冲

        this.startButton = Button.builder(Component.literal("开始游戏"), button -> startGame())
                .bounds(this.width / 2 - 50, this.height / 2 + 50, 100, 20)
                .build();
        this.addRenderableWidget(this.startButton);

        this.exitButton = Button.builder(Component.literal("返回"), button -> Minecraft.getInstance().setScreen(new GameSelectorScreen()))
                .bounds(this.width / 2 - 50, this.height / 2 + 80, 100, 20)
                .build();
        this.addRenderableWidget(this.exitButton);
        generateInitialTunnel();
    }

    private void generateInitialTunnel() {
        tunnelSegments.clear();
        int centerY = this.height / 2;
        int currentHeight = MAX_TUNNEL_HEIGHT;

        // 生成足够多的段来覆盖整个屏幕宽度
        for (int i = 0; i < tunnelSegmentCount; i++) {
            // 随机变化通道中心位置和高度
            centerY += random.nextInt(21) - 10; // -10到10的随机变化
            centerY = Mth.clamp(centerY, MAX_TUNNEL_HEIGHT / 2, this.height - MAX_TUNNEL_HEIGHT / 2);

            // 随机变化通道高度
            currentHeight += random.nextInt(11) - 5; // -5到5的变化
            currentHeight = Mth.clamp(currentHeight, MIN_TUNNEL_HEIGHT, MAX_TUNNEL_HEIGHT);

            tunnelSegments.add(new TunnelSegment(centerY, currentHeight));
        }
    }

    private void startGame() {
        gameState = GameState.PLAYING;
        gameStartTime = System.currentTimeMillis();
        survivalTime = 0;
        score = 0;
        difficulty = 1;
        scrollOffset = 0;
        mouseInTunnel = true;

        generateInitialTunnel();

        // 隐藏开始按钮
        this.startButton.visible = false;
        this.exitButton.visible = false;
    }

    @Override
    public void render(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        GameRenderHelper.fillDarkBackground(graphics, width, height);

        if (gameState == GameState.PLAYING) {
            // 更新鼠标位置
            playerX = mouseX;
            playerY = mouseY;

            // 检查碰撞
            checkCollision();

            // 渲染游戏
            renderGame(graphics);
            renderGameHUD(graphics);
        } else {
            // 渲染菜单
            renderMenu(graphics);
        }

        super.render(graphics, mouseX, mouseY, partialTick);
        if (showExitConfirm) GameRenderHelper.drawExitConfirmOverlay(graphics, font, width, height, mouseX, mouseY);
    }

    private void renderGame(GuiGraphics graphics) {
        // 渲染通道
        for (int i = 0; i < tunnelSegments.size(); i++) {
            int x = i * SEGMENT_WIDTH - scrollOffset;

            // 扩大渲染范围，避免边缘空白
            if (x < -SEGMENT_WIDTH * 2 || x > this.width + SEGMENT_WIDTH) continue;

            TunnelSegment segment = tunnelSegments.get(i);

            // 渲染上墙壁
            graphics.fill(x, 0, x + SEGMENT_WIDTH, segment.topY, WALL_COLOR);

            // 渲染下墙壁
            graphics.fill(x, segment.bottomY, x + SEGMENT_WIDTH, this.height, WALL_COLOR);

            // 渲染通道内部
            graphics.fill(x, segment.topY, x + SEGMENT_WIDTH, segment.bottomY, TUNNEL_COLOR);

            // 如果是危险区域（很窄的通道），用红色高亮边界
            if (segment.height < MIN_TUNNEL_HEIGHT + 20) {
                graphics.fill(x, segment.topY, x + SEGMENT_WIDTH, segment.topY + 2, DANGER_COLOR);
                graphics.fill(x, segment.bottomY - 2, x + SEGMENT_WIDTH, segment.bottomY, DANGER_COLOR);
            }
        }

        // 渲染玩家（鼠标光标）
        int playerSize = mouseInTunnel ? 4 : 6;
        int playerColor = mouseInTunnel ? PLAYER_COLOR : DANGER_COLOR;

        graphics.fill(playerX - playerSize, playerY - playerSize,
                playerX + playerSize, playerY + playerSize, playerColor);

        // 渲染轨迹效果
        if (mouseInTunnel) {
            graphics.fill(playerX - 2, playerY - 2, playerX + 2, playerY + 2, 0x80FFFFFF);
        }
    }

    private void renderGameHUD(GuiGraphics graphics) {
        // 渲染分数和时间
        String scoreText = "分数: " + score;
        String timeText = "时间: " + (survivalTime / 1000) + "s";
        String difficultyText = "难度: " + difficulty;

        graphics.drawString(this.font, scoreText, 10, 10, 0xFFFFFF);
        graphics.drawString(this.font, timeText, 10, 25, 0xFFFFFF);
        graphics.drawString(this.font, difficultyText, 10, 40, 0xFFFFFF);

        // 渲染提示
        if (!mouseInTunnel) {
            String warningText = "警告: 鼠标超出通道!";
            int textWidth = this.font.width(warningText);
            graphics.drawString(this.font, warningText, (this.width - textWidth) / 2, 60, 0xFFFF0000);
        }
    }

    private void renderMenu(GuiGraphics graphics) {
        // 渲染标题
        String title = "鼠标通道游戏";
        int titleWidth = this.font.width(title);
        graphics.drawString(this.font, title, (this.width - titleWidth) / 2, this.height / 2 - 50, 0xFFFFFF);

        if (gameState == GameState.GAME_OVER) {
            String gameOverText = "游戏结束!";
            String finalScoreText = "最终分数: " + score;
            String bestScoreText = "最佳分数: " + bestScore;

            int gameOverWidth = this.font.width(gameOverText);
            int finalScoreWidth = this.font.width(finalScoreText);
            int bestScoreWidth = this.font.width(bestScoreText);

            graphics.drawString(this.font, gameOverText, (this.width - gameOverWidth) / 2, this.height / 2 - 20, 0xFFFF0000);
            graphics.drawString(this.font, finalScoreText, (this.width - finalScoreWidth) / 2, this.height / 2, 0xFFFFFF);
            graphics.drawString(this.font, bestScoreText, (this.width - bestScoreWidth) / 2, this.height / 2 + 15, 0xFFFF00);
        }

        // 渲染说明
        String[] instructions = {
                "用鼠标在通道中导航",
                "避免碰触墙壁",
                "生存时间越长分数越高"
        };

        for (int i = 0; i < instructions.length; i++) {
            int textWidth = this.font.width(instructions[i]);
            graphics.drawString(this.font, instructions[i],
                    (this.width - textWidth) / 2,
                    this.height / 2 + 120 + i * 12, 0xFFCCCCCC);
        }
    }

    private void checkCollision() {
        // 计算当前鼠标位置对应的通道段
        int segmentIndex = (playerX + scrollOffset) / SEGMENT_WIDTH;

        if (segmentIndex >= 0 && segmentIndex < tunnelSegments.size()) {
            TunnelSegment segment = tunnelSegments.get(segmentIndex);

            // 检查是否在通道内
            if (playerY < segment.topY || playerY > segment.bottomY) {
                if (mouseInTunnel) {
                    // 第一次碰墙
                    mouseInTunnel = false;
                    playWarningSound();

                    // 给玩家短暂时间返回通道
                    new Thread(() -> {
                        try {
                            Thread.sleep(500); // 0.5秒宽限时间
                            if (!mouseInTunnel) {
                                gameOver();
                            }
                        } catch (InterruptedException e) {
                            Thread.currentThread().interrupt();
                        }
                    }).start();
                }
            } else {
                // 重新进入通道
                if (!mouseInTunnel) {
                    mouseInTunnel = true;
                    playSuccessSound();
                }
            }
        }
    }

    private void gameOver() {
        gameState = GameState.GAME_OVER;

        // 更新最佳分数
        if (score > bestScore) {
            bestScore = score;
        }

        // 显示按钮
        this.startButton.visible = true;
        this.exitButton.visible = true;

        playFailSound();
    }

    @Override
    public void tick() {
        super.tick();

        if (gameState == GameState.PLAYING && mouseInTunnel) {
            // 更新生存时间
            long currentTime = System.currentTimeMillis();
            survivalTime = currentTime - gameStartTime;
            score = (int)(survivalTime / 100); // 每100毫秒1分

            // 滚动通道
            scrollOffset += SCROLL_SPEED + (difficulty - 1);

            // 增加难度
            if (currentTime - lastDifficultyIncrease > 10000) { // 每10秒增加难度
                difficulty++;
                lastDifficultyIncrease = currentTime;
                generateMoreChallengingTunnel();
            }

            // 生成新的通道段 - 修改触发条件
            if (scrollOffset >= SEGMENT_WIDTH) {
                scrollOffset -= SEGMENT_WIDTH;
                generateNewTunnelSegment();
            }
        }
    }

    private void generateNewTunnelSegment() {
        if (tunnelSegments.size() > 0) {
            // 移除最前面的段
            tunnelSegments.remove(0);

            // 添加新的段到末尾
            TunnelSegment lastSegment = tunnelSegments.get(tunnelSegments.size() - 1);
            int newCenterY = lastSegment.centerY + random.nextInt(31) - 15; // -15到15的变化
            newCenterY = Mth.clamp(newCenterY, MAX_TUNNEL_HEIGHT / 2, this.height - MAX_TUNNEL_HEIGHT / 2);

            int newHeight = lastSegment.height + random.nextInt(21) - 10; // -10到10的变化
            newHeight = Mth.clamp(newHeight,
                    Math.max(MIN_TUNNEL_HEIGHT - difficulty * 5, 30),
                    MAX_TUNNEL_HEIGHT - difficulty * 5);

            tunnelSegments.add(new TunnelSegment(newCenterY, newHeight));
        }
    }

    private void generateMoreChallengingTunnel() {
        // 随着难度增加，通道变得更加曲折和狭窄
        int segmentsToModify = Math.min(10, tunnelSegments.size());
        for (int i = tunnelSegments.size() - segmentsToModify; i < tunnelSegments.size(); i++) {
            if (i >= 0) {
                TunnelSegment segment = tunnelSegments.get(i);
                segment.setHeight(Math.max(40, segment.height - 5));
            }
        }
    }

    private void playWarningSound() {
        if (this.minecraft != null && this.minecraft.getSoundManager() != null) {
            this.minecraft.getSoundManager().play(SimpleSoundInstance.forUI(SoundEvents.NOTE_BLOCK_BASS, 0.5f));
        }
    }

    private void playSuccessSound() {
        if (this.minecraft != null && this.minecraft.getSoundManager() != null) {
            this.minecraft.getSoundManager().play(SimpleSoundInstance.forUI(SoundEvents.EXPERIENCE_ORB_PICKUP, 1.0f));
        }
    }

    private void playFailSound() {
        if (this.minecraft != null && this.minecraft.getSoundManager() != null) {
            this.minecraft.getSoundManager().play(SimpleSoundInstance.forUI(SoundEvents.VILLAGER_NO, 1.0f));
        }
    }

    @Override
    public boolean keyPressed(int keyCode, int scanCode, int modifiers) {
        if (keyCode == 256) { if (showExitConfirm) { showExitConfirm = false; } else { showExitConfirm = true; } return true; }
        if (showExitConfirm) return true;
        return super.keyPressed(keyCode, scanCode, modifiers);
    }

    @Override
    public boolean mouseClicked(double mx, double my, int btn) {
        if (showExitConfirm) { int click = GameRenderHelper.getExitConfirmClick(mx, my, width, height); if (click == 1) { showExitConfirm = false; Minecraft.getInstance().setScreen(new GameSelectorScreen()); return true; } if (click == 2) { showExitConfirm = false; return true; } return true; }
        return super.mouseClicked(mx, my, btn);
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double scrollDeltaX, double delta) {
        // 禁用滚轮，避免意外操作
        return true;
    }
}