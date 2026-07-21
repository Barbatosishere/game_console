package com.wzz.game_console.client.screens.games;

import com.wzz.game_console.client.screens.GameSelectorScreen;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import org.lwjgl.glfw.GLFW;

import java.util.*;

public class SudokuGameScreen extends Screen {
    boolean showExitConfirm = false;
    private static final int GRID_SIZE = 9;
    private static int CELL_SIZE = 35;
    private static final int GAME_WIDTH = GRID_SIZE * CELL_SIZE;
    private static final int GAME_HEIGHT = GRID_SIZE * CELL_SIZE;

    // 游戏数据
    private final int[][] puzzle = new int[GRID_SIZE][GRID_SIZE];      // 当前谜题
    private final int[][] solution = new int[GRID_SIZE][GRID_SIZE];    // 完整解答
    private final boolean[][] fixed = new boolean[GRID_SIZE][GRID_SIZE]; // 固定数字（题目给出的）
    private final boolean[][] errors = new boolean[GRID_SIZE][GRID_SIZE]; // 错误标记

    // 选择状态
    private int selectedRow = 4;
    private int selectedCol = 4;
    private boolean showErrors = true;
    private boolean gameCompleted = false;
    private boolean rewardGiven = false; // 新增：防止重复给奖励
    private long startTime;
    private int hintsUsed = 0;
    private int maxHints = 3;

    // 难度设置
    public enum Difficulty {
        EASY(45, "简单"),
        MEDIUM(35, "中等"),
        HARD(25, "困难"),
        EXPERT(17, "专家");

        final int filledCells;
        final String name;

        Difficulty(int filledCells, String name) {
            this.filledCells = filledCells;
            this.name = name;
        }
    }

    private Difficulty currentDifficulty = Difficulty.EASY;
    private final Random random = new Random();

    // UI位置
    private int gameStartX, gameStartY;
    private Button newGameButton, hintButton, checkButton;
    private Button[] difficultyButtons = new Button[4];

    public SudokuGameScreen() {
        super(Component.literal("数独游戏"));
        startTime = System.currentTimeMillis();
        generateNewPuzzle();
    }

    @Override
    public void init() {
        super.init();
        gameStartX = (this.width - GAME_WIDTH) / 2;
        gameStartY = (this.height - GAME_HEIGHT) / 2 - 20;

        setupButtons();
    }

    private void setupButtons() {
        int buttonY = gameStartY + GAME_HEIGHT + 20;
        int buttonWidth = 80;
        int buttonHeight = 20;

        // 新游戏按钮
        newGameButton = Button.builder(Component.literal("新游戏"), button -> {
            generateNewPuzzle();
            playSound(SoundEvents.UI_BUTTON_CLICK.value());
        }).bounds(gameStartX, buttonY, buttonWidth, buttonHeight).build();
        this.addRenderableWidget(newGameButton);

        // 提示按钮
        hintButton = Button.builder(Component.literal("提示 (" + (maxHints - hintsUsed) + ")"), button -> {
            giveHint();
            playSound(SoundEvents.NOTE_BLOCK_CHIME.value());
        }).bounds(gameStartX + buttonWidth + 10, buttonY, buttonWidth, buttonHeight).build();
        this.addRenderableWidget(hintButton);

        // 检查按钮
        checkButton = Button.builder(Component.literal("检查"), button -> {
            checkForErrors();
            playSound(SoundEvents.UI_BUTTON_CLICK.value());
        }).bounds(gameStartX + (buttonWidth + 10) * 2, buttonY, buttonWidth, buttonHeight).build();
        this.addRenderableWidget(checkButton);

        // 难度按钮
        int diffButtonY = buttonY + 30;
        for (int i = 0; i < Difficulty.values().length; i++) {
            final Difficulty diff = Difficulty.values()[i];
            difficultyButtons[i] = Button.builder(Component.literal(diff.name), button -> {
                currentDifficulty = diff;
                generateNewPuzzle();
                playSound(SoundEvents.UI_BUTTON_CLICK.value());
            }).bounds(gameStartX + i * (buttonWidth + 5), diffButtonY, buttonWidth - 5, buttonHeight).build();
            this.addRenderableWidget(difficultyButtons[i]);
        }
    }

    private void generateNewPuzzle() {
        // 重置状态
        gameCompleted = false;
        rewardGiven = false; // 重置奖励状态
        startTime = System.currentTimeMillis();
        hintsUsed = 0;
        clearArrays();

        // 生成完整的数独解答
        generateFullSolution();

        // 从完整解答中移除一些数字创建谜题
        createPuzzleFromSolution();

        // 更新按钮状态
        if (hintButton != null) {
            hintButton.setMessage(Component.literal("提示 (" + (maxHints - hintsUsed) + ")"));
        }
    }

    private void clearArrays() {
        for (int i = 0; i < GRID_SIZE; i++) {
            Arrays.fill(puzzle[i], 0);
            Arrays.fill(solution[i], 0);
            Arrays.fill(fixed[i], false);
            Arrays.fill(errors[i], false);
        }
    }

    private void generateFullSolution() {
        // 使用回溯算法生成完整解答
        solveSudoku(solution);
    }

    private boolean solveSudoku(int[][] grid) {
        for (int row = 0; row < GRID_SIZE; row++) {
            for (int col = 0; col < GRID_SIZE; col++) {
                if (grid[row][col] == 0) {
                    // 创建随机数字顺序
                    List<Integer> numbers = Arrays.asList(1, 2, 3, 4, 5, 6, 7, 8, 9);
                    Collections.shuffle(numbers, random);

                    for (int num : numbers) {
                        if (isValidMove(grid, row, col, num)) {
                            grid[row][col] = num;
                            if (solveSudoku(grid)) {
                                return true;
                            }
                            grid[row][col] = 0;
                        }
                    }
                    return false;
                }
            }
        }
        return true;
    }

    private void createPuzzleFromSolution() {
        // 复制解答到谜题
        for (int i = 0; i < GRID_SIZE; i++) {
            System.arraycopy(solution[i], 0, puzzle[i], 0, GRID_SIZE);
        }

        // 根据难度移除数字
        int cellsToRemove = 81 - currentDifficulty.filledCells;
        Set<String> removedCells = new HashSet<>();

        while (removedCells.size() < cellsToRemove) {
            int row = random.nextInt(GRID_SIZE);
            int col = random.nextInt(GRID_SIZE);
            String cellKey = row + "," + col;

            if (!removedCells.contains(cellKey)) {
                puzzle[row][col] = 0;
                removedCells.add(cellKey);
            }
        }

        // 标记固定的数字
        for (int row = 0; row < GRID_SIZE; row++) {
            for (int col = 0; col < GRID_SIZE; col++) {
                fixed[row][col] = (puzzle[row][col] != 0);
            }
        }
    }

    private boolean isValidMove(int[][] grid, int row, int col, int num) {
        // 检查行
        for (int c = 0; c < GRID_SIZE; c++) {
            if (grid[row][c] == num) return false;
        }

        // 检查列
        for (int r = 0; r < GRID_SIZE; r++) {
            if (grid[r][col] == num) return false;
        }

        // 检查3x3方块
        int boxRow = (row / 3) * 3;
        int boxCol = (col / 3) * 3;
        for (int r = boxRow; r < boxRow + 3; r++) {
            for (int c = boxCol; c < boxCol + 3; c++) {
                if (grid[r][c] == num) return false;
            }
        }

        return true;
    }

    private void giveReward() {
        if (rewardGiven) {
            return; // 已经给过奖励了，避免重复给予
        }

        rewardGiven = true;
    }

    @Override
    public void renderBackground(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        // 不渲染默认32x32像素菜单背景纹理和模糊效果,游戏自行绘制不透明背景
    }

    @Override
    public void render(GuiGraphics guiGraphics, int mouseX, int mouseY, float partialTick) {
        // 绘制背景
        guiGraphics.fill(0, 0, this.width, this.height, 0xFF1E1E1E);

        renderSudokuGrid(guiGraphics);
        renderUI(guiGraphics);

        if (gameCompleted) {
            renderCompletionScreen(guiGraphics);
        }

        super.render(guiGraphics, mouseX, mouseY, partialTick);
    }

    private void renderSudokuGrid(GuiGraphics guiGraphics) {
        // 绘制网格背景
        guiGraphics.fill(gameStartX - 2, gameStartY - 2,
                gameStartX + GAME_WIDTH + 2, gameStartY + GAME_HEIGHT + 2, 0xFF000000);

        for (int row = 0; row < GRID_SIZE; row++) {
            for (int col = 0; col < GRID_SIZE; col++) {
                int x = gameStartX + col * CELL_SIZE;
                int y = gameStartY + row * CELL_SIZE;

                // 确定单元格颜色
                int cellColor = getCellColor(row, col);
                guiGraphics.fill(x, y, x + CELL_SIZE, y + CELL_SIZE, cellColor);

                // 绘制数字
                if (puzzle[row][col] != 0) {
                    String number = String.valueOf(puzzle[row][col]);
                    int textColor = getNumberColor(row, col);
                    int textX = x + (CELL_SIZE - font.width(number)) / 2;
                    int textY = y + (CELL_SIZE - font.lineHeight) / 2;
                    guiGraphics.drawString(font, number, textX, textY, textColor);
                }

                // 绘制网格线
                drawGridLines(guiGraphics, x, y, row, col);
            }
        }
    }

    private int getCellColor(int row, int col) {
        // 错误高亮
        if (showErrors && errors[row][col]) {
            return 0xFFFF6B6B;
        }

        // 选中单元格
        if (row == selectedRow && col == selectedCol) {
            return 0xFF4A90E2;
        }

        // 同行同列高亮
        if (row == selectedRow || col == selectedCol) {
            return 0xFF2C2C2C;
        }

        // 同3x3区域高亮
        if ((row / 3) == (selectedRow / 3) && (col / 3) == (selectedCol / 3)) {
            return 0xFF2C2C2C;
        }

        // 交替颜色的3x3方块
        int boxRow = row / 3;
        int boxCol = col / 3;
        if ((boxRow + boxCol) % 2 == 0) {
            return 0xFFF8F8F8;
        } else {
            return 0xFFE8E8E8;
        }
    }

    private int getNumberColor(int row, int col) {
        if (errors[row][col]) {
            return 0xFFFFFFFF; // 错误数字用白色
        }
        if (fixed[row][col]) {
            return 0xFF000000; // 固定数字用黑色
        }
        return 0xFF1565C0; // 用户输入用蓝色
    }

    private void drawGridLines(GuiGraphics guiGraphics, int x, int y, int row, int col) {
        // 粗线（3x3区域边界）
        if (row % 3 == 0) {
            guiGraphics.fill(x, y - 1, x + CELL_SIZE, y + 1, 0xFF000000);
        }
        if (col % 3 == 0) {
            guiGraphics.fill(x - 1, y, x + 1, y + CELL_SIZE, 0xFF000000);
        }

        // 细线（单元格边界）
        guiGraphics.fill(x, y + CELL_SIZE - 1, x + CELL_SIZE, y + CELL_SIZE, 0xFF888888);
        guiGraphics.fill(x + CELL_SIZE - 1, y, x + CELL_SIZE, y + CELL_SIZE, 0xFF888888);

        // 外边框粗线
        if (row == GRID_SIZE - 1) {
            guiGraphics.fill(x, y + CELL_SIZE, x + CELL_SIZE, y + CELL_SIZE + 2, 0xFF000000);
        }
        if (col == GRID_SIZE - 1) {
            guiGraphics.fill(x + CELL_SIZE, y, x + CELL_SIZE + 2, y + CELL_SIZE, 0xFF000000);
        }
    }

    private void renderUI(GuiGraphics guiGraphics) {
        // 标题
        String title = "数独游戏 - " + currentDifficulty.name;
        int titleX = (this.width - font.width(title)) / 2;
        guiGraphics.drawString(font, title, titleX, gameStartY - 40, 0xFFFFFFFF);

        // 游戏信息
        long playTime = (System.currentTimeMillis() - startTime) / 1000;
        String timeText = String.format("时间: %02d:%02d", playTime / 60, playTime % 60);
        String hintsText = "剩余提示: " + (maxHints - hintsUsed);

        guiGraphics.drawString(font, timeText, gameStartX, gameStartY - 20, 0xFFCCCCCC);
        guiGraphics.drawString(font, hintsText, gameStartX + 150, gameStartY - 20, 0xFFCCCCCC);

        // 操作说明
        int instructionY = gameStartY + GAME_HEIGHT + 80;
        guiGraphics.drawString(font, "操作: 鼠标点击选择格子，数字键1-9输入，Delete/Backspace清除",
                gameStartX, instructionY, 0xFFAAAAAA);
        guiGraphics.drawString(font, "ESC退出游戏",
                gameStartX, instructionY + 12, 0xFFAAAAAA);
    }

    private void renderCompletionScreen(GuiGraphics guiGraphics) {
        // 半透明遮罩
        guiGraphics.flush(); // 防止先绘制的数独数字盖住遮罩背景（批量渲染text批次后置）
        guiGraphics.fill(0, 0, this.width, this.height, 0x80000000);

        // 完成窗口
        int windowWidth = 300;
        int windowHeight = 150;
        int windowX = (this.width - windowWidth) / 2;
        int windowY = (this.height - windowHeight) / 2;

        guiGraphics.fill(windowX, windowY, windowX + windowWidth, windowY + windowHeight, 0xFF2E7D32);
        guiGraphics.fill(windowX, windowY, windowX + windowWidth, windowY + 3, 0xFF4CAF50);

        // 完成文本
        String congratsText = "恭喜完成!";
        long totalTime = (System.currentTimeMillis() - startTime) / 1000;
        String timeText = String.format("用时: %02d:%02d", totalTime / 60, totalTime % 60);
        String difficultyText = "难度: " + currentDifficulty.name;
        String hintsText = "使用提示: " + hintsUsed + "/" + maxHints;

        int textX = windowX + (windowWidth - font.width(congratsText)) / 2;
        guiGraphics.drawString(font, congratsText, textX, windowY + 20, 0xFFFFFFFF);

        textX = windowX + (windowWidth - font.width(timeText)) / 2;
        guiGraphics.drawString(font, timeText, textX, windowY + 45, 0xFFE8F5E8);

        textX = windowX + (windowWidth - font.width(difficultyText)) / 2;
        guiGraphics.drawString(font, difficultyText, textX, windowY + 65, 0xFFE8F5E8);

        textX = windowX + (windowWidth - font.width(hintsText)) / 2;
        guiGraphics.drawString(font, hintsText, textX, windowY + 85, 0xFFE8F5E8);

        String newGameText = "点击'新游戏'开始下一局";
        textX = windowX + (windowWidth - font.width(newGameText)) / 2;
        guiGraphics.drawString(font, newGameText, textX, windowY + 110, 0xFFA5D6A7);
    }

    @Override
    public boolean mouseClicked(double mouseX, double mouseY, int button) {
        if (button == 0 && !gameCompleted) { // 左键点击
            int gridX = (int) (mouseX - gameStartX) / CELL_SIZE;
            int gridY = (int) (mouseY - gameStartY) / CELL_SIZE;

            if (gridX >= 0 && gridX < GRID_SIZE && gridY >= 0 && gridY < GRID_SIZE) {
                selectedRow = gridY;
                selectedCol = gridX;
                clearErrors(); // 清除错误高亮
                playSound(SoundEvents.UI_BUTTON_CLICK.value());
                return true;
            }
        }
        return super.mouseClicked(mouseX, mouseY, button);
    }

    @Override
    public boolean keyPressed(int keyCode, int scanCode, int modifiers) {
        if (gameCompleted) {
            return super.keyPressed(keyCode, scanCode, modifiers);
        }
        if (keyCode == GLFW.GLFW_KEY_ESCAPE) {
            Minecraft.getInstance().setScreen(new GameSelectorScreen());
            return true;
        }
        if (keyCode >= GLFW.GLFW_KEY_1 && keyCode <= GLFW.GLFW_KEY_9) {
            int number = keyCode - GLFW.GLFW_KEY_0;
            inputNumber(number);
            return true;
        }

        // 小键盘数字
        if (keyCode >= GLFW.GLFW_KEY_KP_1 && keyCode <= GLFW.GLFW_KEY_KP_9) {
            int number = keyCode - GLFW.GLFW_KEY_KP_0;
            inputNumber(number);
            return true;
        }

        // 清除
        if (keyCode == GLFW.GLFW_KEY_DELETE || keyCode == GLFW.GLFW_KEY_BACKSPACE || keyCode == GLFW.GLFW_KEY_0) {
            inputNumber(0);
            return true;
        }

        // 方向键移动
        handleArrowKeys(keyCode);

        return super.keyPressed(keyCode, scanCode, modifiers);
    }

    private void inputNumber(int number) {
        if (fixed[selectedRow][selectedCol]) {
            playSound(SoundEvents.VILLAGER_NO);
            return; // 不能修改固定数字
        }

        puzzle[selectedRow][selectedCol] = number;
        clearErrors();

        if (number != 0) {
            playSound(SoundEvents.UI_BUTTON_CLICK.value());
        } else {
            playSound(SoundEvents.UI_CARTOGRAPHY_TABLE_TAKE_RESULT);
        }

        if (isPuzzleComplete()) {
            gameCompleted = true;
            playSound(SoundEvents.UI_TOAST_CHALLENGE_COMPLETE);
            giveReward(); // 修复：调用统一的奖励方法
        }
    }

    private void handleArrowKeys(int keyCode) {
        switch (keyCode) {
            case GLFW.GLFW_KEY_UP:
                selectedRow = Math.max(0, selectedRow - 1);
                break;
            case GLFW.GLFW_KEY_DOWN:
                selectedRow = Math.min(GRID_SIZE - 1, selectedRow + 1);
                break;
            case GLFW.GLFW_KEY_LEFT:
                selectedCol = Math.max(0, selectedCol - 1);
                break;
            case GLFW.GLFW_KEY_RIGHT:
                selectedCol = Math.min(GRID_SIZE - 1, selectedCol + 1);
                break;
        }
        clearErrors();
    }

    private void giveHint() {
        if (hintsUsed >= maxHints || gameCompleted) {
            playSound(SoundEvents.VILLAGER_NO);
            return;
        }

        // 找到一个空的单元格给出提示
        List<int[]> emptyCells = new ArrayList<>();
        for (int row = 0; row < GRID_SIZE; row++) {
            for (int col = 0; col < GRID_SIZE; col++) {
                if (puzzle[row][col] == 0) {
                    emptyCells.add(new int[]{row, col});
                }
            }
        }

        if (!emptyCells.isEmpty()) {
            int[] cell = emptyCells.get(random.nextInt(emptyCells.size()));
            puzzle[cell[0]][cell[1]] = solution[cell[0]][cell[1]];
            fixed[cell[0]][cell[1]] = true; // 提示的数字也设为固定
            hintsUsed++;

            hintButton.setMessage(Component.literal("提示 (" + (maxHints - hintsUsed) + ")"));

            if (isPuzzleComplete()) {
                gameCompleted = true;
                playSound(SoundEvents.UI_TOAST_CHALLENGE_COMPLETE);
                giveReward(); // 修复：使用提示完成游戏时也给奖励
            }
        }
    }

    private void checkForErrors() {
        clearErrors();

        for (int row = 0; row < GRID_SIZE; row++) {
            for (int col = 0; col < GRID_SIZE; col++) {
                if (puzzle[row][col] != 0 && !isValidMove(puzzle, row, col, puzzle[row][col])) {
                    errors[row][col] = true;
                }
            }
        }

        playSound(SoundEvents.EXPERIENCE_ORB_PICKUP);
    }

    private void clearErrors() {
        for (int i = 0; i < GRID_SIZE; i++) {
            Arrays.fill(errors[i], false);
        }
    }

    private boolean isPuzzleComplete() {
        // 检查是否所有格子都填满且无错误
        for (int row = 0; row < GRID_SIZE; row++) {
            for (int col = 0; col < GRID_SIZE; col++) {
                if (puzzle[row][col] == 0) {
                    return false;
                }
                if (!isValidMove(puzzle, row, col, puzzle[row][col])) {
                    return false;
                }
            }
        }
        return true;
    }

    private void playSound(SoundEvent sound) {
        if (minecraft != null && minecraft.level != null) {
            minecraft.level.playLocalSound(0, 0, 0, sound, SoundSource.MASTER, 0.5f, 1.0f, false);
        }
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }
}