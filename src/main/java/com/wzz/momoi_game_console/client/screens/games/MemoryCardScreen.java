package com.wzz.momoi_game_console.client.screens.games;

import com.wzz.momoi_game_console.client.screens.GameSelectorScreen;
import com.wzz.momoi_game_console.util.GameRenderHelper;
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

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

@OnlyIn(Dist.CLIENT)
public class MemoryCardScreen extends Screen {
    boolean showExitConfirm = false;
    private int CARD_SIZE = 40;
    private static final int GRID_ROWS = 4; // 4行
    private static final int GRID_COLS = 4; // 4列
    private static final int TOTAL_PAIRS = (GRID_ROWS * GRID_COLS) / 2; // 8对(16张)
    private static final int CARD_MARGIN = 5;

    private Card[][] cards;
    private Card firstSelected;
    private int matchedPairs;
    private boolean gameWon;
    private int startX, startY;
    private int moves;
    private long startTime;
    private boolean waitingForFlipBack; // 新增: 标记是否正在等待翻回卡片

    // 卡片状态
    private enum CardState {
        HIDDEN, REVEALED, MATCHED
    }

    // 卡片类
    private class Card {
        int value;
        CardState state = CardState.HIDDEN;
        int gridX, gridY; // 修改: 更清楚地表示网格坐标

        Card(int value, int gridX, int gridY) {
            this.value = value;
            this.gridX = gridX;
            this.gridY = gridY;
        }
    }

    public MemoryCardScreen() {
        super(Component.literal("记忆翻牌"));
        initializeGame();
    }

    private void initializeGame() {
        // 创建卡片对(值为1-TOTAL_PAIRS，每值2张)
        List<Integer> cardValues = new ArrayList<>();
        for (int i = 1; i <= TOTAL_PAIRS; i++) {
            cardValues.add(i);
            cardValues.add(i);
        }

        // 随机打乱卡片顺序
        Collections.shuffle(cardValues);

        // 初始化卡片数组
        cards = new Card[GRID_ROWS][GRID_COLS];
        int index = 0;
        for (int y = 0; y < GRID_ROWS; y++) {
            for (int x = 0; x < GRID_COLS; x++) {
                cards[y][x] = new Card(cardValues.get(index++), x, y);
            }
        }

        firstSelected = null;
        matchedPairs = 0;
        gameWon = false;
        moves = 0;
        waitingForFlipBack = false;
        startTime = System.currentTimeMillis();

        // 计算绘制起始位置，使网格居中
        startX = (this.width - (GRID_COLS * (CARD_SIZE + CARD_MARGIN))) / 2;
        startY = (this.height - (GRID_ROWS * (CARD_SIZE + CARD_MARGIN))) / 2;
    }

    @Override
    public void init() {
        int centerX = this.width / 2;
        this.addRenderableWidget(Button.builder(Component.literal("重新开始"), b -> {
            initializeGame();
        }).pos(centerX - 50, this.height - 30).size(100, 20).build());

        this.addRenderableWidget(Button.builder(Component.literal("返回"), b -> {
            Minecraft.getInstance().setScreen(new GameSelectorScreen());
        }).pos(centerX - 50, this.height - 60).size(100, 20).build());
    }

    @Override
    public boolean keyPressed(int keyCode, int scanCode, int modifiers) {
        if (keyCode == GLFW.GLFW_KEY_ESCAPE) {
            if (showExitConfirm) { showExitConfirm = false; return true; }
            if (gameWon) { Minecraft.getInstance().setScreen(new GameSelectorScreen()); return true; }
            showExitConfirm = true; return true;
        }
        if (showExitConfirm) return true;
        return super.keyPressed(keyCode, scanCode, modifiers);
    }

    private long elapsedSeconds;

    @Override
    public void render(@NotNull GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        GameRenderHelper.fillDarkBackground(graphics, width, height);

        // 绘制卡片
        for (int y = 0; y < GRID_ROWS; y++) {
            for (int x = 0; x < GRID_COLS; x++) {
                Card card = cards[y][x];
                int posX = startX + x * (CARD_SIZE + CARD_MARGIN);
                int posY = startY + y * (CARD_SIZE + CARD_MARGIN);

                // 绘制卡片背景
                int bgColor = 0xFF555555; // 默认灰色
                if (card.state == CardState.REVEALED || card.state == CardState.MATCHED) {
                    bgColor = 0xFFDDDDDD; // 翻开的卡片是白色
                }
                graphics.fill(posX, posY, posX + CARD_SIZE, posY + CARD_SIZE, bgColor);

                // 绘制卡片边框
                graphics.fill(posX + 1, posY + 1, posX + CARD_SIZE - 1, posY + CARD_SIZE - 1, 0xFF333333);

                // 绘制卡片内容
                if (card.state == CardState.REVEALED || card.state == CardState.MATCHED) {
                    // 绘制卡片值(使用不同颜色区分)
                    int textColor = getColorForValue(card.value);
                    String text = String.valueOf(card.value);
                    int textWidth = font.width(text);
                    graphics.drawString(font, text, posX + (CARD_SIZE - textWidth) / 2,
                            posY + (CARD_SIZE - 8) / 2, textColor, false);

                    // 如果是已匹配的卡片，加绿色边框
                    if (card.state == CardState.MATCHED) {
                        graphics.fill(posX + 2, posY + 2, posX + CARD_SIZE - 2, posY + 2, 0xFF00FF00); // 上
                        graphics.fill(posX + 2, posY + CARD_SIZE - 3, posX + CARD_SIZE - 2, posY + CARD_SIZE - 2, 0xFF00FF00); // 下
                        graphics.fill(posX + 2, posY + 2, posX + 3, posY + CARD_SIZE - 2, 0xFF00FF00); // 左
                        graphics.fill(posX + CARD_SIZE - 3, posY + 2, posX + CARD_SIZE - 2, posY + CARD_SIZE - 2, 0xFF00FF00); // 右
                    }
                } else {
                    // 绘制卡背图案
                    graphics.fill(posX + 5, posY + 5, posX + CARD_SIZE - 5, posY + CARD_SIZE - 5, 0xFF0077BB);
                    graphics.fill(posX + 8, posY + 8, posX + CARD_SIZE - 8, posY + CARD_SIZE - 8, 0xFF005588);
                }
            }
        }

        // 显示游戏信息
        if (!gameWon)
            elapsedSeconds = (System.currentTimeMillis() - startTime) / 1000;
        graphics.drawString(font, "移动次数: " + moves, 10, 10, 0xFFFFFF, false);
        graphics.drawString(font, "已匹配: " + matchedPairs + "/" + TOTAL_PAIRS, 10, 25, 0xFFFFFF, false);
        graphics.drawString(font, "时间: " + elapsedSeconds + "秒", 10, 40, 0xFFFFFF, false);

        if (gameWon) {
            graphics.drawCenteredString(font, "恭喜! 你完成了游戏!", width / 2, 30, 0xFF00FF00);
            graphics.drawCenteredString(font, "用时: " + elapsedSeconds + "秒, 移动: " + moves + "次",
                    width / 2, 50, 0xFFFFFF);
        }

        super.render(graphics, mouseX, mouseY, partialTick);
        if (showExitConfirm) GameRenderHelper.drawExitConfirmOverlay(graphics, font, width, height, mouseX, mouseY);
    }

    private int getColorForValue(int value) {
        // 为不同卡片值分配不同颜色
        return switch (value % 8) {
            case 0 -> 0xFFFF0000; // 红
            case 1 -> 0xFF00FF00; // 绿
            case 2 -> 0xFF0000FF; // 蓝
            case 3 -> 0xFFFFFF00; // 黄
            case 4 -> 0xFFFF00FF; // 紫
            case 5 -> 0xFF00FFFF; // 青
            case 6 -> 0xFFFF8800; // 橙
            case 7 -> 0xFF8800FF; // 紫红
            default -> 0xFFFFFFFF; // 白
        };
    }

    @Override
    public boolean mouseClicked(double mouseX, double mouseY, int button) {
        if (showExitConfirm) {
            int click = GameRenderHelper.getExitConfirmClick((int)mouseX, (int)mouseY, width, height);
            if (click == 1) { showExitConfirm = false; Minecraft.getInstance().setScreen(new GameSelectorScreen()); return true; }
            if (click == 2) { showExitConfirm = false; return true; }
            return true;
        }
        if (gameWon || waitingForFlipBack) return super.mouseClicked(mouseX, mouseY, button);

        // 检查是否点击了卡片
        for (int y = 0; y < GRID_ROWS; y++) {
            for (int x = 0; x < GRID_COLS; x++) {
                Card card = cards[y][x];
                int posX = startX + x * (CARD_SIZE + CARD_MARGIN);
                int posY = startY + y * (CARD_SIZE + CARD_MARGIN);

                if (mouseX >= posX && mouseX <= posX + CARD_SIZE &&
                        mouseY >= posY && mouseY <= posY + CARD_SIZE) {

                    handleCardClick(card);
                    return true;
                }
            }
        }

        return super.mouseClicked(mouseX, mouseY, button);
    }

    private void handleCardClick(Card card) {
        // 忽略已匹配或已翻开的卡片
        if (card.state != CardState.HIDDEN) return;

        // 翻开卡片
        card.state = CardState.REVEALED;

        if (firstSelected == null) {
            // 这是第一张选择的卡片
            firstSelected = card;
        } else {
            // 这是第二张选择的卡片
            moves++;

            if (firstSelected.value == card.value) {
                // 匹配成功
                if (Minecraft.getInstance().player != null) {
                    Minecraft.getInstance().player.playSound(SoundEvents.EXPERIENCE_ORB_PICKUP, 1.0F, 1.0F);
                }
                firstSelected.state = CardState.MATCHED;
                card.state = CardState.MATCHED;
                matchedPairs++;

                // 检查是否所有卡片都匹配了
                if (matchedPairs == TOTAL_PAIRS) {
                    gameWon = true;
                    if (Minecraft.getInstance().player != null) {
                        Minecraft.getInstance().player.playSound(SoundEvents.PLAYER_LEVELUP, 1.0F, 1.0F);
                    }
                }

                firstSelected = null;
            } else {
                // 匹配失败，设置等待翻回状态
                waitingForFlipBack = true;

                // 使用Minecraft的定时器而不是新建线程
                Minecraft.getInstance().tell(() -> {
                    try {
                        Thread.sleep(1000); // 等待1秒
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                    }

                    Minecraft.getInstance().execute(() -> {
                        if (firstSelected.state == CardState.REVEALED) {
                            firstSelected.state = CardState.HIDDEN;
                        }
                        if (card.state == CardState.REVEALED) {
                            card.state = CardState.HIDDEN;
                        }
                        firstSelected = null;
                        waitingForFlipBack = false;
                    });
                });
            }
        }
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }
}