package com.wzz.game_console.client.screens.games;

import com.wzz.game_console.client.screens.GameSelectorScreen;
import com.wzz.game_console.util.GameRenderHelper;
import com.wzz.game_console.util.GameRenderHelper.Particle;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.minecraft.sounds.SoundEvents;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.api.distmarker.OnlyIn;

@OnlyIn(Dist.CLIENT)
public class GomokuScreen extends Screen implements LanMultiplayerScreen {
    boolean showExitConfirm = false;
    private static final int BOARD_SIZE = 15;
    /** 四方向偏移（横、竖、两对角线），避免每次评估重复创建 */
    private static final int[][] DIRS = {{0, 1}, {1, 0}, {1, 1}, {1, -1}};
    private State state = State.MENU;
    private int[][] board = new int[15][15];
    private boolean playerTurn = true;
    private int winner = 0;
    private long tickCount = 0L;
    private int cellSize;
    private int boardStartX;
    private int boardStartY;
    private final List<Particle> particles = new ArrayList<>();
    private int lastMoveX = -1;
    private int lastMoveY = -1;
    private boolean hellMode = false;
    private int lanMode = 0;
    private UUID remotePeer = null;
    private boolean isMyTurn = true;

    public GomokuScreen() {
        super(Component.literal("五子棋"));
    }

    public GomokuScreen(boolean isHost, UUID remote) {
        super(Component.literal("五子棋-联机"));
        this.lanMode = isHost ? 1 : 2;
        this.remotePeer = remote;
        this.isMyTurn = isHost;
        this.startGame();
    }

    public UUID getLanPeer() {
        return this.remotePeer;
    }

    public String getLanGameId() {
        return "gomoku";
    }

    public void onRemoteMove(String data) {
        if ("RESTART".equals(data)) {
            this.startGame();
        } else {
            try {
                String[] p = data.split(",");
                int x = Integer.parseInt(p[0]);
                int y = Integer.parseInt(p[1]);
                if (this.board == null || this.board[x][y] != 0) {
                    return;
                }

                int oppPiece = this.lanMode == 1 ? 2 : 1;
                this.board[x][y] = oppPiece;
                this.lastMoveX = x;
                this.lastMoveY = y;
                if (Minecraft.getInstance().player != null) {
                    Minecraft.getInstance().player.playSound(SoundEvents.WOOD_PLACE, 0.5F, 1.2F);
                }

                if (this.checkWin(oppPiece)) {
                    this.winner = oppPiece;
                    this.state = State.GAME_OVER;
                } else if (this.isBoardFull()) {
                    this.winner = 0;
                    this.state = State.GAME_OVER;
                } else {
                    this.isMyTurn = true;
                }
            } catch (Exception var6) {
            }
        }
    }

    private void startGame() {
        this.board = new int[15][15];
        this.playerTurn = true;
        this.winner = 0;
        this.state = State.PLAYING;
        this.particles.clear();
        this.lastMoveX = -1;
        this.lastMoveY = -1;
        this.isMyTurn = this.lanMode != 2;
    }

    public void tick() {
        this.tickCount++;
        if (this.lanMode == 0) {
            if (this.state == State.PLAYING && !this.playerTurn && this.winner == 0) {
                this.aiMove();
                if (this.checkWin(2)) {
                    this.winner = 2;
                    this.state = State.GAME_OVER;
                } else if (this.isBoardFull()) {
                    this.winner = 0;
                    this.state = State.GAME_OVER;
                } else {
                    this.playerTurn = true;
                }
            }
        }
    }

    private void aiMove() {
        int[] best = this.hellMode ? this.findBestMoveHellMode() : this.findBestMoveNormal();
        if (best != null) {
            this.board[best[0]][best[1]] = 2;
            this.lastMoveX = best[0];
            this.lastMoveY = best[1];
        }
    }

    private int[] findBestMoveNormal() {
        int[] bestMove = null;
        int bestScore = Integer.MIN_VALUE;

        for (int i = 0; i < 15; i++) {
            for (int j = 0; j < 15; j++) {
                if (this.board[i][j] == 0 && this.hasNeighbor(i, j)) {
                    int scoreAI = this.evaluatePosition(i, j, 2);
                    int scorePlayer = this.evaluatePosition(i, j, 1);
                    int total = scoreAI * 2 + scorePlayer;
                    if (total > bestScore) {
                        bestScore = total;
                        bestMove = new int[]{i, j};
                    }
                }
            }
        }

        if (bestMove == null) {
            bestMove = new int[]{7, 7};
        }

        return bestMove;
    }

    private int[] findBestMoveHellMode() {
        int bestScore = Integer.MIN_VALUE;
        int[] bestMove = null;
        int depth = 4;

        for (int i = 0; i < 15; i++) {
            for (int j = 0; j < 15; j++) {
                if (this.board[i][j] == 0 && this.hasNeighbor(i, j)) {
                    this.board[i][j] = 2;
                    int score = this.minimax(depth - 1, false, Integer.MIN_VALUE, Integer.MAX_VALUE, i, j, 2);
                    this.board[i][j] = 0;
                    if (score > bestScore) {
                        bestScore = score;
                        bestMove = new int[]{i, j};
                    }
                }
            }
        }

        return bestMove != null ? bestMove : new int[]{7, 7};
    }

    private int minimax(int depth, boolean isMaximizing, int alpha, int beta, int lastX, int lastY, int lastPlayer) {
        // 仅检查上一步落子是否获胜，避免每个节点全盘扫描
        if (this.checkWinAt(lastX, lastY, lastPlayer)) {
            return lastPlayer == 2 ? 100000 + depth : -100000 - depth;
        }
        if (depth == 0) {
            return this.evaluateBoard();
        }
        List<int[]> candidates = this.generateCandidateMoves();
        if (candidates.isEmpty()) return this.evaluateBoard();
        int bestScore = isMaximizing ? Integer.MIN_VALUE : Integer.MAX_VALUE;

        for (int[] move : candidates) {
            int i = move[0];
            int j = move[1];
            int player = isMaximizing ? 2 : 1;
            this.board[i][j] = player;
            int score = this.minimax(depth - 1, !isMaximizing, alpha, beta, i, j, player);
            this.board[i][j] = 0;
            if (isMaximizing) {
                bestScore = Math.max(bestScore, score);
                alpha = Math.max(alpha, score);
            } else {
                bestScore = Math.min(bestScore, score);
                beta = Math.min(beta, score);
            }

            if (beta <= alpha) {
                break;
            }
        }

        return bestScore;
    }

    private List<int[]> generateCandidateMoves() {
        List<int[]> allMoves = new ArrayList<>();

        for (int i = 0; i < 15; i++) {
            for (int j = 0; j < 15; j++) {
                if (this.board[i][j] == 0 && this.hasNeighbor(i, j)) {
                    allMoves.add(new int[]{i, j});
                }
            }
        }

        allMoves.sort((a, b) -> {
            int scoreA = evaluatePositionPattern(a[0], a[1], 2);
            int scoreB = evaluatePositionPattern(b[0], b[1], 2);
            return Integer.compare(scoreB, scoreA);
        });
        return allMoves.subList(0, Math.min(10, allMoves.size()));
    }

    private int evaluateBoard() {
        int score = 0;

        for (int i = 0; i < 15; i++) {
            for (int j = 0; j < 15; j++) {
                if (this.board[i][j] == 2) {
                    score += this.evaluatePositionHell(i, j, 2);
                } else if (this.board[i][j] == 1) {
                    score -= this.evaluatePositionHell(i, j, 1);
                }
            }
        }

        return score;
    }

    private boolean checkGameOver() {
        return this.checkWinner() != 0 || this.isBoardFull();
    }

    private int checkWinner() {
        for (int i = 0; i < 15; i++) {
            for (int j = 0; j < 15; j++) {
                int cell = this.board[i][j];
                if (cell != 0 && this.checkWinAt(i, j, cell)) {
                    return cell;
                }
            }
        }

        return 0;
    }

    private boolean isBoardFull() {
        for (int i = 0; i < 15; i++) {
            for (int j = 0; j < 15; j++) {
                if (this.board[i][j] == 0) {
                    return false;
                }
            }
        }

        return true;
    }

    private int evaluatePosition(int x, int y, int player) {
        int score = 0;

        for (int[] d : DIRS) {
            int count = 1;
            int blocks = 0;
            int emptyEnds = 0;

            for (int i = 1; i < 5; i++) {
                int nx = x + d[0] * i;
                int ny = y + d[1] * i;
                if (!this.inBoard(nx, ny)) {
                    blocks++;
                    break;
                }

                if (this.board[nx][ny] != player) {
                    if (this.board[nx][ny] == 0) {
                        emptyEnds++;
                    } else {
                        blocks++;
                    }
                    break;
                }

                count++;
            }

            for (int i = 1; i < 5; i++) {
                int nx = x - d[0] * i;
                int ny = y - d[1] * i;
                if (!this.inBoard(nx, ny)) {
                    blocks++;
                    break;
                }

                if (this.board[nx][ny] != player) {
                    if (this.board[nx][ny] == 0) {
                        emptyEnds++;
                    } else {
                        blocks++;
                    }
                    break;
                }

                count++;
            }

            score += this.getPatternScore(count, blocks, emptyEnds);
        }

        return score;
    }

    private int evaluatePositionPattern(int x, int y, int player) {
        int score = 0;

        for (int[] d : DIRS) {
            int count = 1;
            int block = 0;

            for (int i = 1; i < 5; i++) {
                int nx = x + d[0] * i;
                int ny = y + d[1] * i;
                if (!this.inBoard(nx, ny)) {
                    block++;
                    break;
                }

                if (this.board[nx][ny] != player) {
                    if (this.board[nx][ny] != 0) {
                        block++;
                    }
                    break;
                }

                count++;
            }

            for (int i = 1; i < 5; i++) {
                int nx = x - d[0] * i;
                int ny = y - d[1] * i;
                if (!this.inBoard(nx, ny)) {
                    block++;
                    break;
                }

                if (this.board[nx][ny] != player) {
                    if (this.board[nx][ny] != 0) {
                        block++;
                    }
                    break;
                }

                count++;
            }

            score += this.getPatternScore2(count, block);
        }

        return score;
    }

    private int evaluatePositionHell(int x, int y, int player) {
        int score = 0;

        for (int[] d : DIRS) {
            int count = 1;
            int blocks = 0;
            int emptyEnds = 0;

            for (int i = 1; i < 5; i++) {
                int nx = x + d[0] * i;
                int ny = y + d[1] * i;
                if (!this.inBoard(nx, ny)) {
                    blocks++;
                    break;
                }

                if (this.board[nx][ny] != player) {
                    if (this.board[nx][ny] == 0) {
                        emptyEnds++;
                    } else {
                        blocks++;
                    }
                    break;
                }

                count++;
            }

            for (int i = 1; i < 5; i++) {
                int nx = x - d[0] * i;
                int ny = y - d[1] * i;
                if (!this.inBoard(nx, ny)) {
                    blocks++;
                    break;
                }

                if (this.board[nx][ny] != player) {
                    if (this.board[nx][ny] == 0) {
                        emptyEnds++;
                    } else {
                        blocks++;
                    }
                    break;
                }

                count++;
            }

            if (count >= 5) {
                score += 100000;
            } else if (count == 4 && blocks == 0) {
                score += 10000;
            } else if (count == 4 && blocks == 1) {
                score += 1000;
            } else if (count == 3 && blocks == 0 && emptyEnds == 2) {
                score += 500;
            } else if (count == 3 && blocks == 0 && emptyEnds == 1) {
                score += 200;
            } else if (count == 2 && blocks == 0 && emptyEnds == 2) {
                score += 50;
            }
        }

        return score;
    }

    private int getPatternScore(int count, int blocks, int emptyEnds) {
        if (count >= 5) {
            return 100000;
        }

        if (count == 4) {
            if (blocks == 0) {
                return 10000;
            }

            if (blocks == 1) {
                return 1000;
            }
        }

        if (count == 3) {
            if (blocks == 0 && emptyEnds == 2) {
                return 500;
            }

            if (blocks == 1 && emptyEnds == 1) {
                return 100;
            }
        }

        if (count == 2) {
            if (blocks == 0 && emptyEnds == 2) {
                return 50;
            }

            if (blocks == 1 && emptyEnds == 1) {
                return 10;
            }
        }

        return count == 1 && blocks < 2 ? 5 : 0;
    }

    private int getPatternScore2(int count, int blocks) {
        if (count >= 5) {
            return 100000;
        } else if (count == 4) {
            return blocks == 0 ? 10000 : 3000;
        } else if (count == 3) {
            return blocks == 0 ? 1000 : 300;
        } else if (count == 2) {
            return blocks == 0 ? 200 : 50;
        } else {
            return count == 1 ? 10 : 0;
        }
    }

    private boolean inBoard(int x, int y) {
        return x >= 0 && x < 15 && y >= 0 && y < 15;
    }

    private boolean hasNeighbor(int x, int y) {
        for (int dx = -2; dx <= 2; dx++) {
            for (int dy = -2; dy <= 2; dy++) {
                if (dx != 0 || dy != 0) {
                    int nx = x + dx;
                    int ny = y + dy;
                    if (this.inBoard(nx, ny) && this.board[nx][ny] != 0) {
                        return true;
                    }
                }
            }
        }

        return false;
    }

    private boolean checkWin(int player) {
        // 仅检查最后落子位置，避免每次全盘扫描 225 个格子
        if (this.lastMoveX < 0 || this.lastMoveY < 0) return false;
        return this.checkWinAt(this.lastMoveX, this.lastMoveY, player);
    }

    private boolean checkWinAt(int x, int y, int player) {
        for (int[] d : DIRS) {
            int count = 1;

            for (int k = 1; k < 5; k++) {
                int nx = x + d[0] * k;
                int ny = y + d[1] * k;
                if (!this.inBoard(nx, ny) || this.board[nx][ny] != player) {
                    break;
                }

                count++;
            }

            for (int k = 1; k < 5; k++) {
                int nx = x - d[0] * k;
                int ny = y - d[1] * k;
                if (!this.inBoard(nx, ny) || this.board[nx][ny] != player) {
                    break;
                }

                count++;
            }

            if (count >= 5) {
                return true;
            }
        }

        return false;
    }

    public boolean keyPressed(int key, int scan, int mods) {
        if (key != 256) {
            if (key == 82) {
                if (this.lanMode == 2) {
                    return true;
                }

                this.startGame();
                if (this.lanMode == 1) {
                    this.sendMove("RESTART");
                }

                return true;
            } else if (key == 72) {
                if (this.state == State.MENU) {
                    this.hellMode = !this.hellMode;
                }
                return true;
            } else {
                return true;
            }
        } else {
            if (showExitConfirm) { showExitConfirm = false; return true; }
            if (this.lanMode == 0 && this.state != State.MENU) {
                showExitConfirm = true;
            } else {
                Minecraft.getInstance().setScreen(new GameSelectorScreen());
            }

            return true;
        }
    }

    public boolean mouseClicked(double mx, double my, int btn) {
        if (showExitConfirm) { int click = GameRenderHelper.getExitConfirmClick(mx, my, width, height); if (click == 1) { showExitConfirm = false; this.state = State.MENU; return true; } if (click == 2) { showExitConfirm = false; return true; } return true; }
        int cx = this.width / 2;
        int cy = this.height / 2;
        if (this.state == State.MENU) {
            if (mx >= cx - 60 && mx <= cx + 60 && my >= cy + 45 && my <= cy + 67) {
                this.startGame();
                return true;
            }

            if (mx >= cx - 60 && mx <= cx + 60 && my >= cy + 73 && my <= cy + 95) {
                this.hellMode = !this.hellMode;
                return true;
            }
        }

        if (this.state == State.GAME_OVER) {
            // "R - 再来一局" 按钮
            if (mx >= cx - 70 && mx <= cx + 70 && my >= cy + 20 && my <= cy + 38) {
                if (this.lanMode != 2) {
                    this.startGame();
                    if (this.lanMode == 1) {
                        this.sendMove("RESTART");
                    }
                }
                return true;
            }
            // "ESC - 返回" 按钮
            if (mx >= cx - 70 && mx <= cx + 70 && my >= cy + 42 && my <= cy + 60) {
                if (this.lanMode == 0) {
                    showExitConfirm = true;
                } else {
                    Minecraft.getInstance().setScreen(new GameSelectorScreen());
                }
                return true;
            }
            return true;
        }

        if (this.state == State.PLAYING && this.winner == 0) {
            boolean canMove = this.lanMode == 0 ? this.playerTurn : this.isMyTurn;
            if (!canMove) {
                return true;
            }

            int hx = ((int)mx - this.boardStartX) / this.cellSize;
            int hy = ((int)my - this.boardStartY) / this.cellSize;
            if (hx >= 0 && hx < 15 && hy >= 0 && hy < 15 && this.board[hx][hy] == 0) {
                int myPiece = this.lanMode == 2 ? 2 : 1;
                this.board[hx][hy] = myPiece;
                this.lastMoveX = hx;
                this.lastMoveY = hy;
                GameRenderHelper.spawnParticles(
                        this.particles,
                        this.boardStartX + hx * this.cellSize + this.cellSize / 2.0F,
                        this.boardStartY + hy * this.cellSize + this.cellSize / 2.0F,
                        5,
                        4473924
                );
                if (Minecraft.getInstance().player != null) {
                    Minecraft.getInstance().player.playSound(SoundEvents.WOOD_PLACE, 0.5F, 1.2F);
                }

                if (this.checkWin(myPiece)) {
                    this.winner = myPiece;
                    this.state = State.GAME_OVER;
                    if (this.lanMode != 0) {
                        this.isMyTurn = false;
                        this.sendMove(hx + "," + hy);
                    }
                } else if (this.isBoardFull()) {
                    this.winner = 0;
                    this.state = State.GAME_OVER;
                    if (this.lanMode != 0) {
                        this.isMyTurn = false;
                        this.sendMove(hx + "," + hy);
                    }
                } else if (this.lanMode == 0) {
                    this.playerTurn = false;
                } else {
                    this.isMyTurn = false;
                    this.sendMove(hx + "," + hy);
                }

                return true;
            }
        }

        return super.mouseClicked(mx, my, btn);
    }

    public void render(GuiGraphics g, int mx, int my, float pt) {
        this.cellSize = GameRenderHelper.calcCellSize(this.width, this.height, 17, 17, 40);
        this.boardStartX = (this.width - 15 * this.cellSize) / 2;
        this.boardStartY = (this.height - 15 * this.cellSize) / 2;
        GameRenderHelper.fillDarkBackground(g, this.width, this.height);
        switch (this.state) {
            case MENU:
                this.renderMenu(g, mx, my);
                break;
            case PLAYING:
                this.renderPlaying(g, mx, my);
                break;
            case GAME_OVER:
                this.renderGameOver(g, mx, my);
        }
        if (showExitConfirm) GameRenderHelper.drawExitConfirmOverlay(g, font, width, height, mx, my);
    }

    private void renderMenu(GuiGraphics g, int mx, int my) {
        int cx = this.width / 2;
        int cy = this.height / 2;
        GameRenderHelper.renderDecorativeLines(g, this.width, this.height, this.tickCount, 2236928);
        GameRenderHelper.drawShadowedCenteredText(g, this.font, "§f五 §r§8子 §r§f棋", cx, cy - 60, 16777215, 2);
        g.drawCenteredString(this.font, "Gomoku", cx, cy - 42, 5592405);
        GameRenderHelper.drawDivider(g, cx - 80, cy - 32, 160, -7829368, -12303292);
        g.drawCenteredString(this.font, "鼠标点击落子  黑棋先行", cx, cy - 10, 11184810);
        g.drawCenteredString(this.font, "先连成五子者获胜！", cx, cy + 5, 13421772);

        for (int i = 0; i < 5; i++) {
            int bob = (int)(Math.sin(this.tickCount * 0.08 + i * 0.6) * 2.0);
            int px = cx - 30 + i * 14;
            GameRenderHelper.drawCircle(g, px, cy + 28 + bob, 5, i % 2 == 0 ? -15658735 : -1118482);
        }

        GameRenderHelper.drawPrimaryButton(g, this.font, "开始游戏", cx - 60, cy + 45, 120, 22, mx, my);
        String hellLabel = this.hellMode ? "§c地狱模式: 开 [H]" : "§7地狱模式: 关 [H]";
        GameRenderHelper.drawSecondaryButton(g, this.font, hellLabel, cx - 60, cy + 73, 120, 18, mx, my);
    }

    private void renderPlaying(GuiGraphics g, int mx, int my) {
        int bw = 15 * this.cellSize;
        g.fill(this.boardStartX - 4, this.boardStartY - 4, this.boardStartX + bw + 4, this.boardStartY + bw + 4, -12965360);
        g.fill(this.boardStartX - 2, this.boardStartY - 2, this.boardStartX + bw + 2, this.boardStartY + bw + 2, -2968436);

        for (int i = 0; i < 15; i++) {
            int x = this.boardStartX + i * this.cellSize + this.cellSize / 2;
            int y = this.boardStartY + i * this.cellSize + this.cellSize / 2;
            g.fill(x, this.boardStartY + this.cellSize / 2, x + 1, this.boardStartY + bw - this.cellSize / 2, -16777216);
            g.fill(this.boardStartX + this.cellSize / 2, y, this.boardStartX + bw - this.cellSize / 2, y + 1, -16777216);
        }

        int[] stars = new int[]{3, 7, 11};

        for (int sx : stars) {
            for (int sy : stars) {
                GameRenderHelper.drawCircle(
                        g, this.boardStartX + sx * this.cellSize + this.cellSize / 2, this.boardStartY + sy * this.cellSize + this.cellSize / 2, 2, -16777216
                );
            }
        }

        int stoneR = this.cellSize / 2 - 2;

        for (int x = 0; x < 15; x++) {
            for (int y = 0; y < 15; y++) {
                if (this.board[x][y] != 0) {
                    int scx = this.boardStartX + x * this.cellSize + this.cellSize / 2;
                    int scy = this.boardStartY + y * this.cellSize + this.cellSize / 2;
                    int color = this.board[x][y] == 1 ? -15658735 : -1118482;
                    GameRenderHelper.drawCircle(g, scx, scy, stoneR, color);
                    if (this.board[x][y] == 2) {
                        GameRenderHelper.drawCircle(g, scx - stoneR / 3, scy - stoneR / 3, stoneR / 4, 1157627903);
                    }

                    if (x == this.lastMoveX && y == this.lastMoveY) {
                        GameRenderHelper.drawCircle(g, scx, scy, 2, -65536);
                    }
                }
            }
        }

        if (this.playerTurn && this.winner == 0) {
            int hx = (mx - this.boardStartX) / this.cellSize;
            int hy = (my - this.boardStartY) / this.cellSize;
            if (hx >= 0 && hx < 15 && hy >= 0 && hy < 15 && this.board[hx][hy] == 0) {
                int scx = this.boardStartX + hx * this.cellSize + this.cellSize / 2;
                int scy = this.boardStartY + hy * this.cellSize + this.cellSize / 2;
                GameRenderHelper.drawCircle(g, scx, scy, stoneR, 1712394513);
            }
        }

        GameRenderHelper.tickAndRenderParticles(g, this.particles);
        GameRenderHelper.drawTopHUD(g, this.width, this.height);
        String modeTag = this.hellMode ? " §c[地狱]" : " §7[普通]";
        String turnText;
        if (this.lanMode == 0) {
            turnText = this.playerTurn ? "§f⚫ 你的回合 - 黑棋" : "§f⚪ AI思考中...";
        } else {
            boolean mine = this.isMyTurn;
            String myColor = this.lanMode == 1 ? "§f⚫ 黑棋" : "§f⚪ 白棋";
            String oppColor = this.lanMode == 1 ? "§f⚪ 白棋(对方)" : "§f⚫ 黑棋(对方)";
            turnText = mine ? myColor + " - 你的回合" : oppColor + " - 等待对方...";
        }

        g.drawString(this.font, turnText + modeTag, 8, 7, 16777215);
        GameRenderHelper.drawBottomBar(
                g, this.font, this.width, this.height, "ESC 菜单  R 重开  H 切换难度  鼠标点击落子"
        );
    }

    private void renderGameOver(GuiGraphics g, int mx, int my) {
        this.renderPlaying(g, mx, my);
        GameRenderHelper.drawGameOverOverlay(g, this.width, this.height);
        int cx = this.width / 2;
        int cy = this.height / 2;
        boolean win;
        String mainMsg;
        String subMsg;
        if (this.winner == 0) {
            win = false;
            mainMsg = "🤝 平局！";
            subMsg = "棋盘已满，不分胜负";
        } else if (this.lanMode == 0) {
            win = this.winner == 1;
            mainMsg = win ? "🎉 你赢了！" : "AI 获胜！";
            subMsg = win
                    ? "恭喜你连成五子！"
                    : (this.hellMode ? "地狱AI不好惹，再接再厉！" : "再接再厉！");
        } else {
            int myPiece = this.lanMode == 1 ? 1 : 2;
            win = this.winner == myPiece;
            mainMsg = win ? "🎉 你赢了！" : "对方赢了！";
            subMsg = win ? "恭喜连成五子！" : "下次再接再厉！";
        }

        GameRenderHelper.drawGameOverPanel(g, this.font, cx, cy, win, mainMsg, subMsg);
        GameRenderHelper.drawPrimaryButton(g, this.font, "R - 再来一局", cx - 70, cy + 20, 140, 18, mx, my);
        GameRenderHelper.drawSecondaryButton(g, this.font, "ESC - 返回", cx - 70, cy + 42, 140, 18, mx, my);
    }

    public boolean isPauseScreen() {
        return false;
    }

    private enum State {
        MENU,
        PLAYING,
        GAME_OVER;
    }
}
