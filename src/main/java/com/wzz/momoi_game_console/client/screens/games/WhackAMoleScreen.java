package com.wzz.momoi_game_console.client.screens.games;

import com.wzz.momoi_game_console.client.screens.GameSelectorScreen;
import com.wzz.momoi_game_console.util.ResourceUtil;
import com.wzz.momoi_game_console.util.GameRenderHelper;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.api.distmarker.OnlyIn;
import org.jetbrains.annotations.NotNull;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;

@OnlyIn(Dist.CLIENT)
public class WhackAMoleScreen extends Screen {
    boolean showExitConfirm = false;
    // 游戏常量
    private static final int GRID_SIZE = 3; // 3x3网格
    private int HOLE_SIZE = 48; // 洞的大小
    private static final int MOLE_SIZE = 32; // 地鼠大小
    private int GRID_SPACING = 80; // 网格间距
    private static final int MAX_MISSED_MOLES = 5; // 最大漏掉地鼠数
    private static final int MOLE_SHOW_TIME = 2000; // 地鼠显示时间2秒
    private static final int MOLE_SPAWN_INTERVAL = 1000; // 地鼠生成间隔1秒

    // MC贴图资源
    private static final ResourceLocation DIRT_TEXTURE = ResourceUtil.createInstance("minecraft", "textures/block/dirt.png");
    private static final ResourceLocation GRASS_TEXTURE = ResourceUtil.createInstance("minecraft", "textures/block/grass_block_top.png");
    private static final ResourceLocation ZOMBIE_HEAD = ResourceUtil.createInstance("minecraft", "textures/entity/zombie/zombie.png");
    private static final ResourceLocation CREEPER_HEAD = ResourceUtil.createInstance("minecraft", "textures/entity/creeper/creeper.png");
    private static final ResourceLocation SKELETON_HEAD = ResourceUtil.createInstance("minecraft", "textures/entity/skeleton/skeleton.png");

    // 游戏状态
    private GameState gameState = GameState.MENU;
    private long gameStartTime;
    private long lastMoleSpawnTime;
    private int score = 0;
    private int combo = 0;
    private int maxCombo = 0;
    private int missedMoles = 0; // 漏掉的地鼠数量
    private int totalHits = 0; // 总击中数
    private final List<MoleHole> holes = new ArrayList<>();
    private final Random random = new Random();

    // UI组件
    private Button startButton;
    private Button backButton;
    private Button restartButton;

    // 计算的UI位置
    private int gameAreaX, gameAreaY;
    private int gameAreaWidth, gameAreaHeight;

    public WhackAMoleScreen() {
        super(Component.literal("打地鼠小游戏"));
        initializeHoles();
    }

    private void initializeHoles() {
        holes.clear();
        for (int row = 0; row < GRID_SIZE; row++) {
            for (int col = 0; col < GRID_SIZE; col++) {
                holes.add(new MoleHole(row, col));
            }
        }
    }

    @Override
    public void init() {
        super.init();
        calculateLayout();
        createButtons();
    }

    private void calculateLayout() {
        gameAreaWidth = GRID_SIZE * GRID_SPACING;
        gameAreaHeight = GRID_SIZE * GRID_SPACING;
        gameAreaX = (this.width - gameAreaWidth) / 2;
        gameAreaY = (this.height - gameAreaHeight) / 2;

        // 更新洞的位置
        for (int i = 0; i < holes.size(); i++) {
            MoleHole hole = holes.get(i);
            int row = i / GRID_SIZE;
            int col = i % GRID_SIZE;
            hole.updatePosition(
                    gameAreaX + col * GRID_SPACING + (GRID_SPACING - HOLE_SIZE) / 2,
                    gameAreaY + row * GRID_SPACING + (GRID_SPACING - HOLE_SIZE) / 2
            );
        }
    }

    private void createButtons() {
        // 开始游戏按钮
        startButton = Button.builder(Component.literal("开始游戏"),
                        button -> startGame())
                .bounds(this.width / 2 - 100, this.height / 2 + 80, 200, 20)
                .build();

        // 返回按钮
        backButton = Button.builder(Component.literal("返回"),
                        button -> Minecraft.getInstance().setScreen(new GameSelectorScreen()))
                .bounds(this.width / 2 - 100, this.height / 2 + 110, 200, 20)
                .build();

        // 重新开始按钮
        restartButton = Button.builder(Component.literal("重新开始"),
                        button -> startGame())
                .bounds(this.width / 2 - 100, this.height / 2 + 50, 200, 20)
                .build();

        updateButtonVisibility();
    }

    private void updateButtonVisibility() {
        this.clearWidgets();

        switch (gameState) {
            case MENU:
                this.addRenderableWidget(startButton);
                this.addRenderableWidget(backButton);
                break;
            case GAME_OVER:
                this.addRenderableWidget(restartButton);
                this.addRenderableWidget(backButton);
                break;
            case PLAYING:
                this.addRenderableWidget(backButton);
                break;
        }
    }

    private void startGame() {
        gameState = GameState.PLAYING;
        gameStartTime = System.currentTimeMillis();
        lastMoleSpawnTime = 0;
        score = 0;
        combo = 0;
        maxCombo = 0;
        missedMoles = 0;
        totalHits = 0;

        for (MoleHole hole : holes) {
            hole.reset();
        }

        updateButtonVisibility();
    }

    @Override
    public void tick() {
        super.tick();

        if (gameState == GameState.PLAYING) {
            long currentTime = System.currentTimeMillis();

            // 检查是否漏掉太多地鼠
            if (missedMoles >= MAX_MISSED_MOLES) {
                endGame();
                return;
            }

            // 生成地鼠
            if (currentTime - lastMoleSpawnTime >= MOLE_SPAWN_INTERVAL) {
                spawnMole();
                lastMoleSpawnTime = currentTime;
            }

            // 更新地鼠状态
            for (MoleHole hole : holes) {
                if (hole.update(currentTime)) {
                    // 地鼠自然消失了，增加漏掉计数
                    missedMoles++;
                    combo = 0; // 重置连击

                    // 播放漏掉音效
                    if (minecraft != null && minecraft.level != null && minecraft.player != null) {
                        minecraft.level.playLocalSound(
                                minecraft.player.getX(), minecraft.player.getY(), minecraft.player.getZ(),
                                SoundEvents.VILLAGER_NO, SoundSource.MASTER,
                                0.6f, 0.8f, false
                        );
                    }
                }
            }
        }
    }

    private void spawnMole() {
        List<MoleHole> availableHoles = new ArrayList<>();
        for (MoleHole hole : holes) {
            if (!hole.hasMole()) {
                availableHoles.add(hole);
            }
        }

        if (!availableHoles.isEmpty()) {
            MoleHole selectedHole = availableHoles.get(random.nextInt(availableHoles.size()));
            selectedHole.spawnMole();
        }
    }

    private void endGame() {
        gameState = GameState.GAME_OVER;
        maxCombo = Math.max(maxCombo, combo);
        updateButtonVisibility();
        if (minecraft != null && minecraft.level != null && minecraft.player != null) {
            minecraft.level.playLocalSound(
                    minecraft.player.getX(), minecraft.player.getY(), minecraft.player.getZ(),
                    SoundEvents.EXPERIENCE_ORB_PICKUP, SoundSource.MASTER,
                    0.5f, 1.0f, false
            );
        }
    }

    @Override
    public boolean mouseClicked(double mouseX, double mouseY, int button) {
        if (showExitConfirm) { int click = GameRenderHelper.getExitConfirmClick(mouseX, mouseY, width, height); if (click == 1) { showExitConfirm = false; Minecraft.getInstance().setScreen(new GameSelectorScreen()); return true; } if (click == 2) { showExitConfirm = false; return true; } return true; }
        if (gameState == GameState.PLAYING && button == 0) {
            // 检查是否点击了地鼠
            for (MoleHole hole : holes) {
                if (hole.isClicked(mouseX, mouseY) && hole.hasMole() && !hole.isHit()) {
                    hole.hitMole();
                    totalHits++;
                    score += (combo + 1) * 10; // 连击加分
                    combo++;

                    // 播放击中音效
                    if (minecraft != null && minecraft.level != null && minecraft.player != null) {
                        minecraft.level.playLocalSound(
                                minecraft.player.getX(), minecraft.player.getY(), minecraft.player.getZ(),
                                SoundEvents.ARROW_HIT, SoundSource.MASTER,
                                0.8f, 1.2f + combo * 0.1f, false
                        );
                    }
                    return true;
                }
            }

            // 如果没打中，重置连击
            combo = 0;
        }

        return super.mouseClicked(mouseX, mouseY, button);
    }

    @Override
    public void render(@NotNull GuiGraphics guiGraphics, int mouseX, int mouseY, float partialTick) {
        // 渲染背景
        renderBackground(guiGraphics);

        switch (gameState) {
            case MENU:
                renderMenu(guiGraphics);
                break;
            case PLAYING:
                renderGame(guiGraphics, mouseX, mouseY);
                break;
            case GAME_OVER:
                renderGameOver(guiGraphics);
                break;
        }

        super.render(guiGraphics, mouseX, mouseY, partialTick);
        if (showExitConfirm) GameRenderHelper.drawExitConfirmOverlay(guiGraphics, font, width, height, mouseX, mouseY);
    }

    public void renderBackground(GuiGraphics guiGraphics) {
        // 渲染草地背景
        try {
            // 平铺草地纹理
            int tileSize = 64;
            int tilesX = (this.width + tileSize - 1) / tileSize;
            int tilesY = (this.height + tileSize - 1) / tileSize;

            for (int x = 0; x < tilesX; x++) {
                for (int y = 0; y < tilesY; y++) {
                    guiGraphics.blit(GRASS_TEXTURE,
                            x * tileSize, y * tileSize,
                            0, 0,
                            Math.min(tileSize, this.width - x * tileSize),
                            Math.min(tileSize, this.height - y * tileSize),
                            16, 16); // MC草地纹理是16x16
                }
            }
        } catch (Exception e) {
            // 备用背景
            guiGraphics.fillGradient(0, 0, this.width, this.height, 0xFF7CB342, 0xFF558B2F);
        }
    }

    private void renderMenu(GuiGraphics guiGraphics) {
        // 标题
        guiGraphics.drawCenteredString(this.font, Component.literal("打地鼠小游戏"),
                this.width / 2, this.height / 2 - 60, 0xFFFFFF);

        // 游戏说明
        guiGraphics.drawCenteredString(this.font, Component.literal("点击地鼠得分！连击有额外加分！"),
                this.width / 2, this.height / 2 - 20, 0xCCCCCC);
        guiGraphics.drawCenteredString(this.font, Component.literal("漏掉5个地鼠就失败！"),
                this.width / 2, this.height / 2, 0xFF4444);
    }

    private void renderGame(GuiGraphics guiGraphics, int mouseX, int mouseY) {
        // 渲染游戏区域背景
        guiGraphics.fill(gameAreaX - 20, gameAreaY - 20,
                gameAreaX + gameAreaWidth + 20, gameAreaY + gameAreaHeight + 20,
                0x80000000);

        // 渲染所有洞和地鼠
        for (MoleHole hole : holes) {
            hole.render(guiGraphics);
        }

        // 渲染UI信息
        renderGameUI(guiGraphics);

        // 渲染鼠标指针（锤子）
        renderHammer(guiGraphics, mouseX, mouseY);
    }

    private void renderGameUI(GuiGraphics guiGraphics) {
        // 分数
        guiGraphics.drawString(this.font, "分数: " + score, 10, 10, 0xFFFF00);

        // 击中数
        guiGraphics.drawString(this.font, "击中: " + totalHits, 10, 25, 0x44FF44);

        // 漏掉的地鼠数（用红色显示，越接近5越危险）
        int missedColor = missedMoles >= 4 ? 0xFF0000 :
                missedMoles >= 3 ? 0xFF4444 : 0xFF8888;
        guiGraphics.drawString(this.font, "漏掉: " + missedMoles + "/5", 10, 40, missedColor);

        // 连击
        if (combo > 0) {
            guiGraphics.drawString(this.font, "连击: " + combo + "x", 10, 55, 0xFF4444);
        }

        // 最高连击
        if (maxCombo > 0) {
            guiGraphics.drawString(this.font, "最高连击: " + maxCombo, 10, 70, 0x44FF44);
        }

        // 游戏时长（可选显示）
        long gameTime = System.currentTimeMillis() - gameStartTime;
        int seconds = (int) (gameTime / 1000);
        int minutes = seconds / 60;
        seconds = seconds % 60;
        guiGraphics.drawString(this.font, String.format("时间: %d:%02d", minutes, seconds),
                this.width - 100, 10, 0xCCCCCC);
    }

    private void renderHammer(GuiGraphics guiGraphics, int mouseX, int mouseY) {
        // 简单的锤子图标（使用方块模拟）
        guiGraphics.fill(mouseX - 2, mouseY - 8, mouseX + 2, mouseY - 4, 0xFF8B4513); // 锤柄
        guiGraphics.fill(mouseX - 6, mouseY - 10, mouseX + 6, mouseY - 6, 0xFF696969); // 锤头
    }

    private void renderGameOver(GuiGraphics guiGraphics) {
        // 半透明背景
        guiGraphics.fill(0, 0, this.width, this.height, 0x80000000);

        // 游戏结束文本
        guiGraphics.drawCenteredString(this.font, Component.literal("游戏结束！"),
                this.width / 2, this.height / 2 - 100, 0xFFFFFF);

        // 失败原因
        guiGraphics.drawCenteredString(this.font, Component.literal("漏掉了太多地鼠！"),
                this.width / 2, this.height / 2 - 80, 0xFF4444);

        // 最终分数
        guiGraphics.drawCenteredString(this.font, Component.literal("最终分数: " + score),
                this.width / 2, this.height / 2 - 50, 0xFFFF00);

        // 击中统计
        guiGraphics.drawCenteredString(this.font, Component.literal("击中地鼠: " + totalHits),
                this.width / 2, this.height / 2 - 30, 0x44FF44);

        // 最高连击
        guiGraphics.drawCenteredString(this.font, Component.literal("最高连击: " + maxCombo),
                this.width / 2, this.height / 2 - 10, 0xFF4444);

        // 准确率
        float accuracy = totalHits + missedMoles > 0 ? (float) totalHits / (totalHits + missedMoles) * 100 : 0;
        guiGraphics.drawCenteredString(this.font, Component.literal(String.format("准确率: %.1f%%", accuracy)),
                this.width / 2, this.height / 2 + 10, 0xCCCCCC);

        // 评价
        String rating = getRating(score, accuracy);
        guiGraphics.drawCenteredString(this.font, Component.literal(rating),
                this.width / 2, this.height / 2 + 30, 0x44FF44);
    }

    private String getRating(int score, float accuracy) {
        if (score >= 2000 && accuracy >= 90) return "完美大师！";
        if (score >= 1500 && accuracy >= 85) return "地鼠终结者！";
        if (score >= 1000 && accuracy >= 80) return "打地鼠专家！";
        if (score >= 800 && accuracy >= 75) return "地鼠猎手！";
        if (score >= 600 && accuracy >= 70) return "反应敏捷！";
        if (score >= 400 && accuracy >= 60) return "还不错！";
        if (score >= 200) return "继续努力！";
        return "多多练习！";
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

    private enum GameState {
        MENU, PLAYING, GAME_OVER
    }

    private class MoleHole {
        private final int gridRow, gridCol;
        private long hitTime;
        private int x, y;
        private boolean hasMole = false;
        private boolean isHit = false;
        private long moleSpawnTime;
        private MoleType moleType = MoleType.ZOMBIE;
        private float moleY = 0; // 地鼠的垂直偏移（动画用）

        public MoleHole(int row, int col) {
            this.gridRow = row;
            this.gridCol = col;
        }

        public void updatePosition(int x, int y) {
            this.x = x;
            this.y = y;
        }

        public void spawnMole() {
            hasMole = true;
            isHit = false;
            moleSpawnTime = System.currentTimeMillis();
            moleType = MoleType.values()[random.nextInt(MoleType.values().length)];
            moleY = MOLE_SIZE; // 开始时地鼠在地下
        }

        public void reset() {
            hasMole = false;
            isHit = false;
            moleY = 0;
        }

        public boolean update(long currentTime) {
            if (hasMole) {
                long timeAlive = currentTime - moleSpawnTime;

                if (timeAlive >= MOLE_SHOW_TIME && !isHit) {
                    // 地鼠自然消失
                    hasMole = false;
                    return true;
                } else if (!isHit) {
                    // 地鼠弹出动画
                    float progress = Math.min(1.0f, timeAlive / 300.0f);
                    moleY = MOLE_SIZE * (1 - progress);
                } else {
                    // 被打中后下沉动画 - 修复后的逻辑
                    long timeSinceHit = currentTime - hitTime;
                    float progress = Math.min(1.0f, timeSinceHit / 200.0f);
                    moleY = progress * MOLE_SIZE;

                    if (progress >= 1.0f) {
                        hasMole = false;
                    }
                }
            }
            return false;
        }

        public void hitMole() {
            isHit = true;
            moleSpawnTime = System.currentTimeMillis(); // 重置时间用于下沉动画
            hitTime = System.currentTimeMillis();
        }

        public void render(GuiGraphics guiGraphics) {
            // 渲染洞（泥土背景）
            try {
                guiGraphics.blit(DIRT_TEXTURE, x, y, 0, 0, HOLE_SIZE, HOLE_SIZE, 16, 16);
            } catch (Exception e) {
                guiGraphics.fill(x, y, x + HOLE_SIZE, y + HOLE_SIZE, 0xFF8B4513);
            }

            // 渲染洞的边缘（更暗的颜色）
            guiGraphics.fill(x, y, x + HOLE_SIZE, y + 4, 0x80000000); // 上边缘
            guiGraphics.fill(x, y, x + 4, y + HOLE_SIZE, 0x80000000); // 左边缘

            // 渲染地鼠
            if (hasMole) {
                int moleRenderY = (int) (y + HOLE_SIZE - MOLE_SIZE + moleY);
                int moleRenderX = x + (HOLE_SIZE - MOLE_SIZE) / 2;

                try {
                    ResourceLocation texture = moleType.getTexture();
                    // 渲染地鼠头像（从怪物纹理中截取头部）
                    guiGraphics.blit(texture,
                            moleRenderX, moleRenderY,
                            8, 8, // 纹理上头部的位置
                            MOLE_SIZE, MOLE_SIZE,
                            64, 64); // MC皮肤纹理尺寸
                } catch (Exception e) {
                    // 备用渲染
                    int color = moleType == MoleType.CREEPER ? 0xFF00FF00 :
                            moleType == MoleType.SKELETON ? 0xFFCCCCCC : 0xFF00AA00;
                    guiGraphics.fill(moleRenderX, moleRenderY,
                            moleRenderX + MOLE_SIZE, moleRenderY + MOLE_SIZE, color);
                }

                // 如果被打中，渲染打击效果
                if (isHit) {
                    guiGraphics.fill(moleRenderX, moleRenderY,
                            moleRenderX + MOLE_SIZE, moleRenderY + MOLE_SIZE, 0x80FF0000);
                }
            }
        }

        public boolean isClicked(double mouseX, double mouseY) {
            return mouseX >= x && mouseX <= x + HOLE_SIZE &&
                    mouseY >= y && mouseY <= y + HOLE_SIZE;
        }

        public boolean hasMole() { return hasMole; }
        public boolean isHit() { return isHit; }
    }

    private enum MoleType {
        ZOMBIE(ZOMBIE_HEAD),
        CREEPER(CREEPER_HEAD),
        SKELETON(SKELETON_HEAD);

        private final ResourceLocation texture;

        MoleType(ResourceLocation texture) {
            this.texture = texture;
        }

        public ResourceLocation getTexture() {
            return texture;
        }
    }
}