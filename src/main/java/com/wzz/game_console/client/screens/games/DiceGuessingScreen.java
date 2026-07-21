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

import java.util.Random;

@OnlyIn(Dist.CLIENT)
public class DiceGuessingScreen extends Screen {
    boolean showExitConfirm = false;

    private Random random;

    // 游戏状态
    private GameState gameState;
    private int[] diceValues;  // 3个骰子的值
    private int totalPoints;   // 总点数
    private String lastGuess;  // 上次的猜测
    private boolean isWin;     // 是否获胜

    // 统计数据
    private int totalGames;
    private int winCount;
    private int totalRewards;
    private int currentBet;    // 当前下注金额

    // GUI组件
    private Button bigButton;
    private Button smallButton;
    private Button bet1Button;
    private Button bet3Button;
    private Button bet5Button;
    private Button rollButton;
    private Button exitButton;
    private Button resetButton;

    // 动画相关
    private int animationTick;
    private boolean isRolling;
    private int rollingDuration = 60; // 3秒动画(20 tick/s)

    public DiceGuessingScreen() {
        super(Component.literal("猜大小"));
        this.random = new Random();
        this.gameState = GameState.BETTING;
        this.diceValues = new int[3];
        this.totalGames = 0;
        this.winCount = 0;
        this.totalRewards = 0;
        this.currentBet = 1;
        this.animationTick = 0;
        this.isRolling = false;

        // 初始化骰子值
        resetDice();
    }

    @Override
    public void init() {
        int centerX = this.width / 2;
        int centerY = this.height / 2;

        // 退出按钮
        this.exitButton = Button.builder(Component.literal("退出"),
                        button -> Minecraft.getInstance().setScreen(new GameSelectorScreen()))
                .bounds(this.width - 60, 10, 50, 20)
                .build();
        this.addRenderableWidget(this.exitButton);

        // 重置统计按钮
        this.resetButton = Button.builder(Component.literal("重置统计"),
                        button -> resetStats())
                .bounds(this.width - 130, 10, 60, 20)
                .build();
        this.addRenderableWidget(this.resetButton);

        // 下注金额按钮
        this.bet1Button = Button.builder(Component.literal("下注 1"),
                        button -> setBet(1))
                .bounds(centerX - 120, centerY - 80, 70, 25)
                .build();

        this.bet3Button = Button.builder(Component.literal("下注 3"),
                        button -> setBet(3))
                .bounds(centerX - 35, centerY - 80, 70, 25)
                .build();

        this.bet5Button = Button.builder(Component.literal("下注 5"),
                        button -> setBet(5))
                .bounds(centerX + 50, centerY - 80, 70, 25)
                .build();

        // 猜测按钮
        this.bigButton = Button.builder(Component.literal("猜 大 (11-18)"),
                        button -> makeGuess("大"))
                .bounds(centerX - 100, centerY + 20, 90, 35)
                .build();

        this.smallButton = Button.builder(Component.literal("猜 小 (3-10)"),
                        button -> makeGuess("小"))
                .bounds(centerX + 10, centerY + 20, 90, 35)
                .build();

        // 投掷按钮
        this.rollButton = Button.builder(Component.literal("重新开始"),
                        button -> startNewGame())
                .bounds(centerX - 50, centerY + 70, 100, 30)
                .build();

        // 根据游戏状态添加按钮
        updateButtons();
    }

    private void updateButtons() {
        // 清除所有按钮
        this.clearWidgets();
        this.addRenderableWidget(this.exitButton);
        this.addRenderableWidget(this.resetButton);

        if (gameState == GameState.BETTING) {
            // 下注阶段
            this.addRenderableWidget(this.bet1Button);
            this.addRenderableWidget(this.bet3Button);
            this.addRenderableWidget(this.bet5Button);
            this.addRenderableWidget(this.bigButton);
            this.addRenderableWidget(this.smallButton);
        } else if (gameState == GameState.RESULT) {
            // 结果阶段
            this.addRenderableWidget(this.rollButton);
        }
        // ROLLING状态不显示任何游戏按钮
    }

    @Override
    public void renderBackground(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        // 不渲染默认32x32像素菜单背景纹理和模糊效果,游戏自行绘制不透明背景
    }

    @Override
    public void render(GuiGraphics guiGraphics, int mouseX, int mouseY, float partialTick) {
        GameRenderHelper.fillDarkBackground(guiGraphics, width, height);
        super.render(guiGraphics, mouseX, mouseY, partialTick);

        int centerX = this.width / 2;
        int centerY = this.height / 2;

        // 渲染标题
        guiGraphics.drawCenteredString(this.font, "猜大小游戏", centerX, 20, 0xFFD700);

        // 渲染统计信息
        renderStats(guiGraphics);

        // 渲染当前下注
        renderCurrentBet(guiGraphics, centerX, centerY);

        // 渲染骰子
        renderDice(guiGraphics, centerX, centerY);

        // 渲染游戏结果
        if (gameState == GameState.RESULT) {
            renderResult(guiGraphics, centerX, centerY);
        }

        // 渲染游戏规则
        renderRules(guiGraphics);

        // 渲染总奖励
        if (totalRewards > 0) {
            guiGraphics.drawString(this.font, "总获得奖励: " + totalRewards + "",
                    20, this.height - 30, 0x00FF00);
        }
        if (showExitConfirm) GameRenderHelper.drawExitConfirmOverlay(guiGraphics, font, width, height, mouseX, mouseY);
    }

    private void renderStats(GuiGraphics guiGraphics) {
        int x = 20;
        int y = 50;
        int lineHeight = 15;

        // 统计背景
        guiGraphics.fill(x - 5, y - 5, x + 200, y + lineHeight * 4 + 5, 0x80000000);

        guiGraphics.drawString(this.font, "游戏统计:", x, y, 0xFFFF00);
        guiGraphics.drawString(this.font, "总局数: " + totalGames, x, y + lineHeight, 0xFFFFFF);
        guiGraphics.drawString(this.font, "胜利: " + winCount + " 局", x, y + lineHeight * 2, 0x00FF00);

        if (totalGames > 0) {
            int winRate = (winCount * 100) / totalGames;
            guiGraphics.drawString(this.font, "胜率: " + winRate + "%", x, y + lineHeight * 3, getWinRateColor(winRate));
        }
    }

    private void renderCurrentBet(GuiGraphics guiGraphics, int centerX, int centerY) {
        // 当前下注背景
        int betY = centerY - 100;
        guiGraphics.fill(centerX - 80, betY - 5, centerX + 80, betY + 15, 0x80000000);

        String betText = "当前下注: " + currentBet + "";
        guiGraphics.drawCenteredString(this.font, betText, centerX, betY, 0xFFFF00);
    }

    private void renderDice(GuiGraphics guiGraphics, int centerX, int centerY) {
        int diceY = centerY - 40;
        int diceSize = 30;
        int spacing = 40;

        // 骰子背景
        guiGraphics.fill(centerX - 80, diceY - 20, centerX + 80, diceY + 50, 0x90000000);
        guiGraphics.fill(centerX - 80, diceY - 20, centerX + 80, diceY - 15, 0xFF4169E1);

        for (int i = 0; i < 3; i++) {
            int diceX = centerX - 60 + i * spacing;

            // 骰子框
            guiGraphics.fill(diceX, diceY, diceX + diceSize, diceY + diceSize, 0xFFFFFFFF);
            guiGraphics.fill(diceX + 2, diceY + 2, diceX + diceSize - 2, diceY + diceSize - 2, 0xFF000000);

            // 骰子点数
            int value = isRolling ? random.nextInt(6) + 1 : diceValues[i];
            drawDiceValue(guiGraphics, diceX, diceY, diceSize, value);
        }

        // 总点数显示
        if (!isRolling && gameState == GameState.RESULT) {
            String totalText = "总点数: " + totalPoints;
            guiGraphics.drawCenteredString(this.font, totalText, centerX, diceY + 42, 0xFFFF00);
        }
    }

    private void drawDiceValue(GuiGraphics guiGraphics, int x, int y, int size, int value) {
        int dotSize = 3;
        int centerX = x + size / 2;
        int centerY = y + size / 2;

        // 根据点数绘制点
        switch (value) {
            case 1:
                drawDot(guiGraphics, centerX, centerY, dotSize);
                break;
            case 2:
                drawDot(guiGraphics, x + 8, y + 8, dotSize);
                drawDot(guiGraphics, x + 20, y + 20, dotSize);
                break;
            case 3:
                drawDot(guiGraphics, x + 8, y + 8, dotSize);
                drawDot(guiGraphics, centerX, centerY, dotSize);
                drawDot(guiGraphics, x + 20, y + 20, dotSize);
                break;
            case 4:
                drawDot(guiGraphics, x + 8, y + 8, dotSize);
                drawDot(guiGraphics, x + 20, y + 8, dotSize);
                drawDot(guiGraphics, x + 8, y + 20, dotSize);
                drawDot(guiGraphics, x + 20, y + 20, dotSize);
                break;
            case 5:
                drawDot(guiGraphics, x + 8, y + 8, dotSize);
                drawDot(guiGraphics, x + 20, y + 8, dotSize);
                drawDot(guiGraphics, centerX, centerY, dotSize);
                drawDot(guiGraphics, x + 8, y + 20, dotSize);
                drawDot(guiGraphics, x + 20, y + 20, dotSize);
                break;
            case 6:
                drawDot(guiGraphics, x + 8, y + 8, dotSize);
                drawDot(guiGraphics, x + 20, y + 8, dotSize);
                drawDot(guiGraphics, x + 8, y + 15, dotSize);
                drawDot(guiGraphics, x + 20, y + 15, dotSize);
                drawDot(guiGraphics, x + 8, y + 22, dotSize);
                drawDot(guiGraphics, x + 20, y + 22, dotSize);
                break;
        }
    }

    private void drawDot(GuiGraphics guiGraphics, int x, int y, int size) {
        guiGraphics.fill(x - size/2, y - size/2, x + size/2 + 1, y + size/2 + 1, 0xFFFFFFFF);
    }

    private void renderResult(GuiGraphics guiGraphics, int centerX, int centerY) {
        int resultY = centerY + 5;

        // 结果背景
        guiGraphics.fill(centerX - 120, resultY - 10, centerX + 120, resultY + 40, 0x90000000);

        String resultType = totalPoints >= 11 ? "大" : "小";
        String resultText = "开奖结果: " + resultType + " (" + totalPoints + "点)";
        guiGraphics.drawCenteredString(this.font, resultText, centerX, resultY, 0xFFFF00);

        if (lastGuess != null) {
            String guessText = "你的猜测: " + lastGuess;
            guiGraphics.drawCenteredString(this.font, guessText, centerX, resultY + 15, 0xFFFFFF);

            String winText = isWin ? "恭喜猜对了！" : "很遗憾猜错了";
            int winColor = isWin ? 0x00FF00 : 0xFF6666;
            guiGraphics.drawCenteredString(this.font, winText, centerX, resultY + 30, winColor);
        }
    }

    private void renderRules(GuiGraphics guiGraphics) {
        int x = this.width - 250;
        int y = 50;
        int lineHeight = 12;

        // 规则背景
        guiGraphics.fill(x - 5, y - 5, x + 240, y + lineHeight * 9 + 5, 0x80000000);

        guiGraphics.drawString(this.font, "游戏规则:", x, y, 0xFFFF00);
        guiGraphics.drawString(this.font, "• 投掷3个骰子", x, y + lineHeight, 0xCCCCCC);
        guiGraphics.drawString(this.font, "• 总点数 3-10 为 小", x, y + lineHeight * 2, 0xCCCCCC);
        guiGraphics.drawString(this.font, "• 总点数 11-18 为 大", x, y + lineHeight * 3, 0xCCCCCC);
        guiGraphics.drawString(this.font, "• 猜对奖励 = 下注金额 × 2", x, y + lineHeight * 4, 0x00FF00);
        guiGraphics.drawString(this.font, "• 猜错扣除下注金额", x, y + lineHeight * 5, 0xFF6666);
        guiGraphics.drawString(this.font, "• 下注越多风险和收益越高", x, y + lineHeight * 6, 0xCCCCCC);
        guiGraphics.drawString(this.font, "• 连胜有额外奖励", x, y + lineHeight * 7, 0xCCCCCC);
        guiGraphics.drawString(this.font, "• 极值(3点/18点)额外奖励", x, y + lineHeight * 8, 0xCCCCCC);
    }

    @Override
    public void tick() {
        super.tick();

        if (isRolling) {
            animationTick++;
            if (animationTick >= rollingDuration) {
                // 动画结束，显示结果
                finishRolling();
            }
        }
    }

    private void setBet(int amount) {
        this.currentBet = amount;

        // 更新按钮状态
        bet1Button.active = (amount != 1);
        bet3Button.active = (amount != 3);
        bet5Button.active = (amount != 5);

        Minecraft.getInstance().player.playSound(SoundEvents.UI_BUTTON_CLICK.value(), 0.5f, 1.0f);
    }

    private void makeGuess(String guess) {
        if (gameState != GameState.BETTING || isRolling) return;

        this.lastGuess = guess;
        this.gameState = GameState.ROLLING;
        this.isRolling = true;
        this.animationTick = 0;

        updateButtons();
        Minecraft.getInstance().player.playSound(SoundEvents.EXPERIENCE_ORB_PICKUP, 1.0f, 0.8f);
    }

    private void finishRolling() {
        isRolling = false;
        gameState = GameState.RESULT;

        // 生成最终骰子结果
        for (int i = 0; i < 3; i++) {
            diceValues[i] = random.nextInt(6) + 1;
        }

        totalPoints = diceValues[0] + diceValues[1] + diceValues[2];

        // 判断输赢
        String actualResult = totalPoints >= 11 ? "大" : "小";
        isWin = actualResult.equals(lastGuess);

        totalGames++;

        if (isWin) {
            winCount++;
            giveReward();
        }
        updateButtons();

        // 播放结果音效
        if (isWin) {
            Minecraft.getInstance().player.playSound(SoundEvents.PLAYER_LEVELUP, 1.0f, 1.2f);
        } else {
            Minecraft.getInstance().player.playSound(SoundEvents.ITEM_BREAK, 1.0f, 0.8f);
        }
    }

    private void giveReward() {
        int baseReward = currentBet * 2;

        // 连胜奖励
        int consecutiveWins = getConsecutiveWins();
        if (consecutiveWins >= 3) {
            baseReward += consecutiveWins; // 连胜3次以上额外奖励
        }

        // 特殊点数奖励
        if (totalPoints == 3 || totalPoints == 18) {
            baseReward += 2; // 极值额外奖励
        }

        totalRewards += baseReward;
    }

    private int getConsecutiveWins() {
        // 简化实现：基于当前胜率估算连胜
        if (totalGames < 3) return winCount;
        return Math.min(winCount, 5); // 最多计算5连胜
    }

    private void startNewGame() {
        gameState = GameState.BETTING;
        lastGuess = null;
        resetDice();
        updateButtons();

        Minecraft.getInstance().player.playSound(SoundEvents.UI_BUTTON_CLICK.value(), 0.8f, 1.0f);
    }

    private void resetDice() {
        for (int i = 0; i < 3; i++) {
            diceValues[i] = 1;
        }
        totalPoints = 3;
    }

    private void resetStats() {
        totalGames = 0;
        winCount = 0;
        totalRewards = 0;

        Minecraft.getInstance().player.playSound(SoundEvents.UI_BUTTON_CLICK.value(), 1.0f, 1.0f);
    }

    private int getWinRateColor(int winRate) {
        if (winRate >= 60) return 0x00FF00;      // 绿色 - 很好
        else if (winRate >= 45) return 0xFFFF00; // 黄色 - 一般
        else return 0xFF6666;                    // 红色 - 较差
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

    // 游戏状态枚举
    public enum GameState {
        BETTING,    // 下注猜测阶段
        ROLLING,    // 骰子滚动动画阶段  
        RESULT      // 显示结果阶段
    }
}