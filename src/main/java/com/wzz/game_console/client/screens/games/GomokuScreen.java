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
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

@OnlyIn(Dist.CLIENT)
public class GomokuScreen extends Screen implements LanMultiplayerScreen {
    private static final Logger LOGGER = LoggerFactory.getLogger(GomokuScreen.class);
    boolean showExitConfirm = false;
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
    private GomokuAI.Difficulty difficulty = GomokuAI.Difficulty.NORMAL;
    private int boardSize = 15;
    private GomokuAI ai;
    private int lanMode = 0;
    private UUID remotePeer = null;
    private boolean isMyTurn = true;
    /** 防重复发送 LEAVE_GAME 标志 */
    private boolean lanLeaveSent = false;
    /** AI 后台思考结果（null=无解），由主线程 tick 消费 */
    private volatile int[] aiPending = null;
    /** AI 后台思考是否已完成 */
    private volatile boolean aiDone = false;
    /** 是否已有 AI 线程在思考（仅主线程访问） */
    private boolean aiComputing = false;
    /** 对局代次，重开/退出后丢弃残留的 AI 结果 */
    private int aiGeneration = 0;

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

    /**
     * 来源校验：仅接受已配对对端（服务端盖章 UUID）发来的走法，
     * 防止在线第三方伪造报文注入走法。
     */
    @Override
    public void onRemoteMove(UUID senderUuid, String data) {
        if (this.remotePeer == null || !this.remotePeer.equals(senderUuid)) {
            LOGGER.warn("[五子棋] 丢弃来源非法的联机走法: sender={}，期望对端={}", senderUuid, this.remotePeer);
            return;
        }
        this.onRemoteMove(data);
    }

    /** 退出对局时向对端发送 LEAVE_GAME（带防重复标志，避免重复发包） */
    private void sendLeaveGameOnce() {
        if (this.lanMode == 0 || this.lanLeaveSent || this.remotePeer == null) return;
        this.lanLeaveSent = true;
        this.sendLeaveGame();
    }

    @Override
    public void onClose() {
        this.sendLeaveGameOnce();
        super.onClose();
    }

    public void onRemoteMove(String data) {
        if (data != null && data.startsWith("RESTART")) {
            // ★ 修复 LAN 棋盘尺寸不同步死锁：HOST 报文携带棋盘尺寸 "RESTART:<boardSize>"，
            //   接收端先同步 boardSize 再重开，否则两端各画各的棋盘、走法互相越界。
            //   兼容无后缀旧报文 "RESTART"：按默认 15 处理；解析 try-catch 防坏包
            try {
                if (data.contains(":")) {
                    int size = Integer.parseInt(data.substring(data.indexOf(':') + 1).trim());
                    this.boardSize = Math.max(9, Math.min(19, size)); // 钳制到合法范围 9-19
                } else {
                    this.boardSize = 15;
                }
            } catch (NumberFormatException e) {
                LOGGER.warn("[五子棋] RESTART 报文棋盘尺寸非法: {}", data);
                this.boardSize = 15;
            }
            this.startGame();
        } else {
            try {
                String[] p = data.split(",");
                if (p.length < 2) return; // 报文不足两个字段,丢弃
                int x = Integer.parseInt(p[0]);
                int y = Integer.parseInt(p[1]);
                // ★ Bug修复：远程报文越界防御——x/y 可能为负、>= boardSize、
                //   或 boardSize 切换后旧报文指向不存在的下标。原始 AIOOBE 被
                //   外层 try 静默吞,玩家看到"对手不动"。这里加双重范围校验。
                if (this.board == null || x < 0 || x >= this.boardSize
                        || y < 0 || y >= this.boardSize || this.board[x][y] != 0) {
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

    private int cycleBoardSize() {
        // ★ 用户需求：9~19 全档可选。原版只有 9/13/15/19 四档,缺 11/17。
        // 循环顺序：9 → 11 → 13 → 15 → 17 → 19 → 9 ...
        if (this.boardSize == 9)  return 11;
        if (this.boardSize == 11) return 13;
        if (this.boardSize == 13) return 15;
        if (this.boardSize == 15) return 17;
        if (this.boardSize == 17) return 19;
        return 9;
    }

    private int[] getStarPoints() {
        // 各档星位（按 1-based 算的奇数坐标；与原版 9/13/19 一致，新增 11/17 用近似中心点）
        if (this.boardSize == 9)  return new int[]{2, 6};
        if (this.boardSize == 11) return new int[]{2, 5, 8};
        if (this.boardSize == 13) return new int[]{3, 7, 11};
        if (this.boardSize == 15) return new int[]{3, 7, 11};
        if (this.boardSize == 17) return new int[]{3, 8, 13};
        if (this.boardSize == 19) return new int[]{3, 9, 15};
        return new int[]{3, 7, 11};
    }

    private void startGame() {
        synchronized (this) {
            this.aiGeneration++;
            this.aiPending = null;
            this.aiDone = false;
            this.aiComputing = false;
        }
        this.board = new int[this.boardSize][this.boardSize];
        this.playerTurn = true;
        this.winner = 0;
        this.state = State.PLAYING;
        this.particles.clear();
        this.lastMoveX = -1;
        this.lastMoveY = -1;
        this.isMyTurn = this.lanMode != 2;
        this.ai = new GomokuAI(this.difficulty);
    }

    public void tick() {
        this.tickCount++;
        if (this.lanMode == 0) {
            if (this.state == State.PLAYING && !this.playerTurn && this.winner == 0) {
                this.tickAiTurn();
            }
        }
    }

    private void tickAiTurn() {
        if (!this.aiComputing) {
            // 启动后台线程计算落子，避免阻塞渲染线程
            this.aiComputing = true;
            this.aiDone = false;
            if (this.ai == null) {
                this.ai = new GomokuAI(this.difficulty);
            }
            final GomokuAI ai = this.ai;
            final int[][] snapshot = this.board;
            final int gen;
            synchronized (this) {
                gen = this.aiGeneration;
            }
            Thread t = new Thread(() -> {
                int[] move = ai.getMove(snapshot);
                synchronized (this) {
                    if (gen == this.aiGeneration) {
                        this.aiPending = move;
                        this.aiDone = true;
                    }
                }
            }, "GomokuAI");
            t.setDaemon(true);
            t.start();
            return;
        }

        if (this.aiDone) {
            // 后台计算完成，在主线程落地走法
            this.aiDone = false;
            this.aiComputing = false;
            int[] best = this.aiPending;
            this.aiPending = null;
            if (best != null) {
                this.board[best[0]][best[1]] = 2;
                this.lastMoveX = best[0];
                this.lastMoveY = best[1];
            }
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

    private boolean isBoardFull() {
        for (int i = 0; i < this.boardSize; i++) {
            for (int j = 0; j < this.boardSize; j++) {
                if (this.board[i][j] == 0) {
                    return false;
                }
            }
        }

        return true;
    }

    private boolean inBoard(int x, int y) {
        return x >= 0 && x < this.boardSize && y >= 0 && y < this.boardSize;
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
                    this.sendMove("RESTART:" + this.boardSize); // 携带棋盘尺寸，防止两端尺寸不同步
                }

                return true;
            } else if (key == 83) {
                if (this.state == State.MENU) {
                    this.boardSize = this.cycleBoardSize();
                }
                return true;
            } else if (key == 72) {
                if (this.state == State.MENU) {
                    this.difficulty = this.difficulty.next();
                }
                return true;
            } else {
                return super.keyPressed(key, scan, mods);
            }
        } else {
            if (showExitConfirm) { showExitConfirm = false; return true; }
            if (this.lanMode == 0 && this.state != State.MENU) {
                showExitConfirm = true;
            } else {
                this.sendLeaveGameOnce(); // 联机退出时通知对端，避免对方无限等待
                Minecraft.getInstance().setScreen(new GameSelectorScreen());
            }

            return true;
        }
    }

    public boolean mouseClicked(double mx, double my, int btn) {
        if (showExitConfirm) { int click = GameRenderHelper.getExitConfirmClick(mx, my, width, height); if (click == 1) { showExitConfirm = false; this.sendLeaveGameOnce(); Minecraft.getInstance().setScreen(new GameSelectorScreen()); return true; } if (click == 2) { showExitConfirm = false; return true; } return true; }
        int cx = this.width / 2;
        int cy = this.height / 2;
        if (this.state == State.MENU) {
            if (mx >= cx - 60 && mx <= cx + 60 && my >= cy + 45 && my <= cy + 67) {
                this.startGame();
                return true;
            }

            if (mx >= cx - 60 && mx <= cx + 60 && my >= cy + 73 && my <= cy + 95) {
                this.boardSize = this.cycleBoardSize();
                return true;
            }

            if (mx >= cx - 60 && mx <= cx + 60 && my >= cy + 95 && my <= cy + 117) {
                this.difficulty = this.difficulty.next();
                return true;
            }
        }

        if (this.state == State.GAME_OVER) {
            // "R - 再来一局" 按钮
            if (mx >= cx - 70 && mx <= cx + 70 && my >= cy + 20 && my <= cy + 38) {
                if (this.lanMode != 2) {
                    this.startGame();
                    if (this.lanMode == 1) {
                        this.sendMove("RESTART:" + this.boardSize); // 携带棋盘尺寸，防止两端尺寸不同步
                    }
                }
                return true;
            }
            // "ESC - 返回" 按钮
            if (mx >= cx - 70 && mx <= cx + 70 && my >= cy + 42 && my <= cy + 60) {
                if (this.lanMode == 0) {
                    showExitConfirm = true;
                } else {
                    this.sendLeaveGameOnce(); // 联机退出时通知对端
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

            int hx = Math.floorDiv((int)mx - this.boardStartX, this.cellSize);
            int hy = Math.floorDiv((int)my - this.boardStartY, this.cellSize);
            if (hx >= 0 && hx < this.boardSize && hy >= 0 && hy < this.boardSize && this.board[hx][hy] == 0) {
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
        this.cellSize = GameRenderHelper.calcCellSize(this.width, this.height, this.boardSize + 2, this.boardSize + 2, 40);
        this.boardStartX = (this.width - this.boardSize * this.cellSize) / 2;
        this.boardStartY = (this.height - this.boardSize * this.cellSize) / 2;
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
        GameRenderHelper.drawShadowedCenteredText(g, this.font, "五 子 棋", cx, cy - 60, 16777215, 2);
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
        String sizeLabel = "棋盘大小: " + this.boardSize + "x" + this.boardSize + " [S]";
        GameRenderHelper.drawSecondaryButton(g, this.font, sizeLabel, cx - 60, cy + 73, 120, 18, mx, my);
        String diffLabel = "难度: " + this.difficulty.label + " [H]";
        GameRenderHelper.drawSecondaryButton(g, this.font, diffLabel, cx - 60, cy + 95, 120, 18, mx, my);
    }

    private void renderPlaying(GuiGraphics g, int mx, int my) {
        int bw = this.boardSize * this.cellSize;
        g.fill(this.boardStartX - 4, this.boardStartY - 4, this.boardStartX + bw + 4, this.boardStartY + bw + 4, -12965360);
        g.fill(this.boardStartX - 2, this.boardStartY - 2, this.boardStartX + bw + 2, this.boardStartY + bw + 2, -2968436);

        for (int i = 0; i < this.boardSize; i++) {
            int x = this.boardStartX + i * this.cellSize + this.cellSize / 2;
            int y = this.boardStartY + i * this.cellSize + this.cellSize / 2;
            g.fill(x, this.boardStartY + this.cellSize / 2, x + 1, this.boardStartY + bw - this.cellSize / 2, -16777216);
            g.fill(this.boardStartX + this.cellSize / 2, y, this.boardStartX + bw - this.cellSize / 2, y + 1, -16777216);
        }

        int[] stars = this.getStarPoints();

        for (int sx : stars) {
            for (int sy : stars) {
                GameRenderHelper.drawCircle(
                        g, this.boardStartX + sx * this.cellSize + this.cellSize / 2, this.boardStartY + sy * this.cellSize + this.cellSize / 2, 2, -16777216
                );
            }
        }

        int stoneR = this.cellSize / 2 - 2;

        for (int x = 0; x < this.boardSize; x++) {
            for (int y = 0; y < this.boardSize; y++) {
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

        boolean canPreview = this.lanMode == 0 ? this.playerTurn : this.isMyTurn;
        if (canPreview && this.winner == 0) {
            int hx = Math.floorDiv(mx - this.boardStartX, this.cellSize);
            int hy = Math.floorDiv(my - this.boardStartY, this.cellSize);
            if (hx >= 0 && hx < this.boardSize && hy >= 0 && hy < this.boardSize && this.board[hx][hy] == 0) {
                int scx = this.boardStartX + hx * this.cellSize + this.cellSize / 2;
                int scy = this.boardStartY + hy * this.cellSize + this.cellSize / 2;
                int previewColor = this.lanMode == 2 ? 0x66EEEEEE : 0x66111111;
                GameRenderHelper.drawCircle(g, scx, scy, stoneR, previewColor);
            }
        }

        GameRenderHelper.tickAndRenderParticles(g, this.particles);
        GameRenderHelper.drawTopHUD(g, this.width, this.height);
        String modeTag = " [" + this.difficulty.label + "]";
        String turnText;
        if (this.lanMode == 0) {
            turnText = this.playerTurn ? "⚫ 你的回合 - 黑棋" : "⚪ AI思考中...";
        } else {
            boolean mine = this.isMyTurn;
            String myColor = this.lanMode == 1 ? "⚫ 黑棋" : "⚪ 白棋";
            String oppColor = this.lanMode == 1 ? "⚪ 白棋(对方)" : "⚫ 黑棋(对方)";
            turnText = mine ? myColor + " - 你的回合" : oppColor + " - 等待对方...";
        }

        g.drawString(this.font, turnText + modeTag, 8, 7, 16777215);
        GameRenderHelper.drawBottomBar(
                g, this.font, this.width, this.height, "ESC 菜单  R 重开  H 切换难度  S 棋盘大小  鼠标点击落子"
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
                    : ("难度[" + this.difficulty.label + "]的AI获胜，再接再厉！");
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
