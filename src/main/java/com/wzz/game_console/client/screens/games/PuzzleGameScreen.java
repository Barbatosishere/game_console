package com.wzz.game_console.client.screens.games;

import com.wzz.game_console.client.screens.GameSelectorScreen;
import com.wzz.game_console.util.GameRenderHelper;
import com.wzz.game_console.util.ResourceUtil;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.util.Mth;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.api.distmarker.OnlyIn;
import org.jetbrains.annotations.NotNull;
import org.lwjgl.glfw.GLFW;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;

@OnlyIn(Dist.CLIENT)
public class PuzzleGameScreen extends Screen {
    boolean showExitConfirm = false;
    private int PIECE_SIZE = 80;
    private static final int PUZZLE_ROWS = 3;
    private static final int PUZZLE_COLS = 3;
    private static final int EMPTY_PIECE = PUZZLE_ROWS * PUZZLE_COLS - 1; // 最后一块为空
    
    private PuzzlePiece[][] puzzleGrid;
    private int emptyX, emptyY;
    private boolean gameWon;
    private int moves;
    private long startTime;
    private ResourceLocation puzzleImage;
    private int startX, startY;
    private final Random random = new Random();
    
    // 拼图块类
    private static class PuzzlePiece {
        int originalX, originalY; // 原始位置
        int currentX, currentY;   // 当前位置
        boolean isBlank;          // 是否是空白块
        float animX, animY; // 动画位置
        boolean isAnimating;
        int animTargetX, animTargetY;
        
        PuzzlePiece(int originalX, int originalY) {
            this.originalX = originalX;
            this.originalY = originalY;
            this.currentX = originalX;
            this.currentY = originalY;
            this.isBlank = (originalX == PUZZLE_COLS - 1 && originalY == PUZZLE_ROWS - 1);
        }
    }
    
    public PuzzleGameScreen() {
        super(Component.literal("拼图游戏"));
        initializeGame();
    }
    
    private void initializeGame() {
        int i = random.nextInt(11);
        if (i == 0)
            puzzleImage = ResourceUtil.createMinecraftInstance("textures/item/diamond.png");
        if (i == 1)
            puzzleImage = ResourceUtil.createMinecraftInstance("textures/item/iron_ingot.png");
        if (i == 2)
            puzzleImage = ResourceUtil.createMinecraftInstance("textures/item/iron_chestplate.png");
        if (i == 3)
            puzzleImage = ResourceUtil.createMinecraftInstance("textures/item/chicken.png");
        if (i == 4)
            puzzleImage = ResourceUtil.createMinecraftInstance("textures/item/apple.png");
        if (i == 5)
            puzzleImage = ResourceUtil.createMinecraftInstance("textures/item/golden_apple.png");
        if (i == 6)
            puzzleImage = ResourceUtil.createMinecraftInstance("textures/item/gold_ingot.png");
        if (i == 7)
            puzzleImage = ResourceUtil.createMinecraftInstance("textures/item/emerald.png");
        if (i == 8)
            puzzleImage = ResourceUtil.createMinecraftInstance("textures/item/golden_sword.png");
        if (i == 9)
            puzzleImage = ResourceUtil.createInstance("textures/item/game_console.png");
        if (i == 10)
            puzzleImage = ResourceUtil.createMinecraftInstance("textures/item/egg.png");
        puzzleGrid = new PuzzlePiece[PUZZLE_ROWS][PUZZLE_COLS];
        
        // 初始化拼图块
        for (int y = 0; y < PUZZLE_ROWS; y++) {
            for (int x = 0; x < PUZZLE_COLS; x++) {
                puzzleGrid[y][x] = new PuzzlePiece(x, y);
            }
        }
        
        // 打乱拼图
        shufflePuzzle();
        
        // 找到空白块位置
        for (int y = 0; y < PUZZLE_ROWS; y++) {
            for (int x = 0; x < PUZZLE_COLS; x++) {
                if (puzzleGrid[y][x].isBlank) {
                    emptyX = x;
                    emptyY = y;
                    break;
                }
            }
        }
        
        gameWon = false;
        moves = 0;
        startTime = System.currentTimeMillis();
        
        // 计算绘制起始位置，使拼图居中
        startX = (this.width - (PUZZLE_COLS * PIECE_SIZE)) / 2;
        startY = (this.height - (PUZZLE_ROWS * PIECE_SIZE)) / 2;
    }

    private void shufflePuzzle() {
        // 执行随机移动100次以确保拼图可解
        for (int i = 0; i < 200; i++) {
            List<int[]> possibleMoves = getPossibleMoves(emptyX, emptyY);
            int[] move = possibleMoves.get(random.nextInt(possibleMoves.size()));
            swapPieces(emptyX, emptyY, move[0], move[1]);
            emptyX = move[0];
            emptyY = move[1];
        }
    }

    private List<int[]> getPossibleMoves(int x, int y) {
        List<int[]> moves = new ArrayList<>();
        if (x > 0) moves.add(new int[]{x - 1, y});
        if (x < PUZZLE_COLS - 1) moves.add(new int[]{x + 1, y});
        if (y > 0) moves.add(new int[]{x, y - 1});
        if (y < PUZZLE_ROWS - 1) moves.add(new int[]{x, y + 1});
        return moves;
    }

    private void swapPieces(int x1, int y1, int x2, int y2) {
        PuzzlePiece temp = puzzleGrid[y1][x1];
        puzzleGrid[y1][x1] = puzzleGrid[y2][x2];
        puzzleGrid[y2][x2] = temp;

        // 更新两个拼图块的当前位置
        puzzleGrid[y1][x1].currentX = x1;
        puzzleGrid[y1][x1].currentY = y1;
        puzzleGrid[y2][x2].currentX = x2;
        puzzleGrid[y2][x2].currentY = y2;
    }

    @Override
    public boolean mouseClicked(double mouseX, double mouseY, int button) {
        if (showExitConfirm) {
            int click = GameRenderHelper.getExitConfirmClick((int)mouseX, (int)mouseY, width, height);
            if (click == 1) { showExitConfirm = false; Minecraft.getInstance().setScreen(new GameSelectorScreen()); return true; }
            if (click == 2) { showExitConfirm = false; return true; }
            return true;
        }
        boolean b = super.mouseClicked(mouseX, mouseY, button);
        if (gameWon) return false;

        // 检查是否点击了拼图块
        for (int y = 0; y < PUZZLE_ROWS; y++) {
            for (int x = 0; x < PUZZLE_COLS; x++) {
                if (puzzleGrid[y][x].isBlank) continue;

                int posX = startX + x * PIECE_SIZE;
                int posY = startY + y * PIECE_SIZE;

                if (mouseX >= posX && mouseX <= posX + PIECE_SIZE &&
                        mouseY >= posY && mouseY <= posY + PIECE_SIZE) {

                    // 检查是否与空白块相邻
                    if ((Math.abs(x - emptyX) == 1 && y == emptyY) ||
                            (Math.abs(y - emptyY) == 1 && x == emptyX)) {

                        // 移动拼图块
                        swapPieces(x, y, emptyX, emptyY);
                        emptyX = x;
                        emptyY = y;
                        moves++;

                        // 检查是否完成拼图
                        checkPuzzleComplete();
                    }
                    return true;
                }
            }
        }

        return b;
    }

    private void checkPuzzleComplete() {
        for (int y = 0; y < PUZZLE_ROWS; y++) {
            for (int x = 0; x < PUZZLE_COLS; x++) {
                PuzzlePiece piece = puzzleGrid[y][x];
                if (!piece.isBlank &&
                        (piece.currentX != piece.originalX || piece.currentY != piece.originalY)) {
                    return;
                }
            }
        }
        if (!gameWon) {
            gameWon = true;
            if (Minecraft.getInstance().player != null) {
                Minecraft.getInstance().player.playSound(SoundEvents.PLAYER_LEVELUP, 1.0F, 1.0F);
            }
        }
    }
    
    @Override
    public void init() {
        clearWidgets();
        // ★ 修复偏移：构造时 width/height 为0，必须在 init 中重新计算拼图位置
        startX = (this.width - (PUZZLE_COLS * PIECE_SIZE)) / 2;
        startY = (this.height - (PUZZLE_ROWS * PIECE_SIZE)) / 2;
        int centerX = this.width / 2;
        this.addRenderableWidget(Button.builder(Component.literal("重新开始"), b -> {
            initializeGame();
        }).pos(centerX - 50, this.height - 30).size(100, 20).build());
        
        this.addRenderableWidget(Button.builder(Component.literal("返回"), b -> {
            Minecraft.getInstance().setScreen(new GameSelectorScreen());
        }).pos(centerX - 50, this.height - 60).size(100, 20).build());
    }

    @Override
    public void render(@NotNull GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        GameRenderHelper.fillDarkBackground(graphics, width, height);
        if (this.children().isEmpty()) {
            this.init();
        }
        // 绘制拼图背景
        graphics.fill(startX - 5, startY - 5, 
                     startX + PUZZLE_COLS * PIECE_SIZE + 5, 
                     startY + PUZZLE_ROWS * PIECE_SIZE + 5, 
                     0xFF333333);

        // 绘制拼图块
        for (int y = 0; y < PUZZLE_ROWS; y++) {
            for (int x = 0; x < PUZZLE_COLS; x++) {
                PuzzlePiece piece = puzzleGrid[y][x];
                if (!piece.isBlank) {
                    int posX = startX + x * PIECE_SIZE;
                    int posY = startY + y * PIECE_SIZE;
                    if (piece.isAnimating) {
                        posX = (int)Mth.lerp(partialTick, startX + piece.currentX * PIECE_SIZE,
                                startX + piece.animTargetX * PIECE_SIZE);
                        posY = (int) Mth.lerp(partialTick, startY + piece.currentY * PIECE_SIZE,
                                startY + piece.animTargetY * PIECE_SIZE);
                    }
                    // 绘制拼图块背景
                    graphics.fill(posX, posY, posX + PIECE_SIZE, posY + PIECE_SIZE, 0xFF555555);
                    
                    // 绘制拼图块内容（使用原始位置的图片部分）
                    int texX = piece.originalX * PIECE_SIZE;
                    int texY = piece.originalY * PIECE_SIZE;
                    
                    // 使用Minecraft的渲染方法绘制图片部分
                    graphics.blit(puzzleImage, 
                                posX, posY, 
                                0, 
                                texX, texY, 
                                PIECE_SIZE, PIECE_SIZE, 
                                PIECE_SIZE * PUZZLE_COLS, PIECE_SIZE * PUZZLE_ROWS);
                    
                    // 绘制拼图块边框
                    graphics.fill(posX, posY, posX + PIECE_SIZE, posY + 2, 0xFFFFFFFF); // 上边
                    graphics.fill(posX, posY + PIECE_SIZE - 2, posX + PIECE_SIZE, posY + PIECE_SIZE, 0xFFFFFFFF); // 下边
                    graphics.fill(posX, posY, posX + 2, posY + PIECE_SIZE, 0xFFFFFFFF); // 左边
                    graphics.fill(posX + PIECE_SIZE - 2, posY, posX + PIECE_SIZE, posY + PIECE_SIZE, 0xFFFFFFFF); // 右边
                }
            }
        }
        
        // 显示游戏信息
        long elapsedSeconds = (System.currentTimeMillis() - startTime) / 1000;
        graphics.drawString(font, "移动次数: " + moves, 10, 10, 0xFFFFFF, false);
        graphics.drawString(font, "时间: " + elapsedSeconds + "秒", 10, 25, 0xFFFFFF, false);
        
        if (gameWon) {
            graphics.drawCenteredString(font, "恭喜! 你完成了拼图!", width / 2, 30, 0xFF00FF00);
            graphics.drawCenteredString(font, "用时: " + elapsedSeconds + "秒, 移动: " + moves + "次", 
                                     width / 2, 50, 0xFFFFFF);
        }
        
        super.render(graphics, mouseX, mouseY, partialTick);
        if (showExitConfirm) GameRenderHelper.drawExitConfirmOverlay(graphics, font, width, height, mouseX, mouseY);
    }

    @Override
    public boolean keyPressed(int keyCode, int scanCode, int modifiers) {
        if (keyCode == GLFW.GLFW_KEY_ESCAPE) {
            if (showExitConfirm) { showExitConfirm = false; return true; }
            if (gameWon) { Minecraft.getInstance().setScreen(new GameSelectorScreen()); return true; }
            showExitConfirm = true; return true;
        }
        if (showExitConfirm) return true;
        if (gameWon) return true;

        int newEmptyX = emptyX;
        int newEmptyY = emptyY;

        switch (keyCode) {
            case GLFW.GLFW_KEY_UP, GLFW.GLFW_KEY_W -> {
                if (emptyY < PUZZLE_ROWS - 1) newEmptyY++;
            }
            case GLFW.GLFW_KEY_DOWN, GLFW.GLFW_KEY_S -> {
                if (emptyY > 0) newEmptyY--;
            }
            case GLFW.GLFW_KEY_LEFT, GLFW.GLFW_KEY_A -> {
                if (emptyX < PUZZLE_COLS - 1) newEmptyX++;
            }
            case GLFW.GLFW_KEY_RIGHT, GLFW.GLFW_KEY_D -> {
                if (emptyX > 0) newEmptyX--;
            }
            default -> { return false; }
        }

        if (newEmptyX != emptyX || newEmptyY != emptyY) {
            swapPieces(emptyX, emptyY, newEmptyX, newEmptyY);
            puzzleGrid[emptyY][emptyX].currentX = emptyX;
            puzzleGrid[emptyY][emptyX].currentY = emptyY;
            puzzleGrid[newEmptyY][newEmptyX].currentX = newEmptyX;
            puzzleGrid[newEmptyY][newEmptyX].currentY = newEmptyY;
            emptyX = newEmptyX;
            emptyY = newEmptyY;
            moves++;
            checkPuzzleComplete(); // ★★★ 关键：移动后判定胜利
            return true;
        }

        return false;
    }
    
    @Override
    public boolean isPauseScreen() {
        return false;
    }
}