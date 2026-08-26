package com.wzz.game_console.client.screens.games.gogame;

import com.wzz.game_console.client.screens.GameSelectorScreen;
import com.wzz.game_console.client.screens.games.LanMultiplayerScreen;
import com.wzz.game_console.util.GameRenderHelper;
import com.wzz.game_console.util.GameSettings;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.minecraft.util.Mth;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.api.distmarker.OnlyIn;
import org.lwjgl.glfw.GLFW;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

@OnlyIn(Dist.CLIENT)
public class GoGameScreen extends Screen implements LanMultiplayerScreen {
    private static final Logger LOGGER = LoggerFactory.getLogger(GoGameScreen.class);
    boolean showExitConfirm = false;
    private static final int BOARD_SIZE = 19;

    private enum State { MENU, SETTINGS, PLAYING, GAME_OVER }
    private State state = State.MENU;
    private final GoGame game;
    private long tickCount = 0;
    private int cellSize, boardStartX, boardStartY;

    // ── 胜负结果 ──
    private String resultMsg  = "";
    private boolean myWin     = false;

    // ── LAN 联机 ──
    private static final int LAN_NONE   = LanMultiplayerScreen.LAN_NONE;
    private static final int LAN_HOST   = LanMultiplayerScreen.LAN_HOST;
    private static final int LAN_CLIENT = LanMultiplayerScreen.LAN_CLIENT;
    private int     lanMode    = LAN_NONE;
    private java.util.UUID remotePeer = null;
    /** LAN HOST=黑棋先手，CLIENT=白棋后手 */
    private boolean myTurn = true; // 单机或 HOST 默认先手
    /** 防重复发送 LEAVE_GAME 标志 */
    private boolean lanLeaveSent = false;

    // ── AI 引擎设置 ─────────────────────────────────────────
    /** 设置界面中当前选中的引擎 */
    private String settingsEngine = GameSettings.getString("go", "engine", "mcts");
    /** 设置界面中当前的搜索时间（ms） */
    private int settingsSearchTime = GameSettings.getInt("go", "searchTime", 3000);
    /** 设置界面中当前的 KataGo 路径 */
    private String settingsKatagoPath = GameSettings.getString("go", "katagoPath", "");

    /** AI 后台思考标记（防止重复启动线程） */
    private volatile boolean aiThinking = false;
    /** AI 后台思考产生走法（null=建议弃权）；aiComputed=true 时有效 */
    private volatile int[] aiPendingMove = null;
    /** AI 后台是否已完成思考（待客户端线程消费） */
    private volatile boolean aiComputed = false;

    /** 单机 / AI 构造 */
    public GoGameScreen(GoGame game) {
        super(Component.literal("围棋"));
        this.game = game;
        this.game.setAiMode(true);
    }

    /** LAN 联机构造 */
    public GoGameScreen(boolean isHost, java.util.UUID remote) {
        super(Component.literal("围棋"));
        this.game       = new GoGame();
        this.game.setAiMode(false);
        this.lanMode    = isHost ? LAN_HOST : LAN_CLIENT;
        this.remotePeer = remote;
        this.myTurn     = isHost; // HOST（黑）先手
    }

    // ── LanMultiplayerScreen 接口 ──────────────────────────────
    @Override public java.util.UUID getLanPeer() { return remotePeer; }
    @Override public String getLanGameId() { return "go"; }

    /**
     * 来源校验：仅接受已配对对端（服务端盖章 UUID）发来的走法，
     * 防止在线第三方伪造报文注入走法。
     */
    @Override
    public void onRemoteMove(java.util.UUID senderUuid, String data) {
        if (remotePeer == null || !remotePeer.equals(senderUuid)) {
            LOGGER.warn("[围棋] 丢弃来源非法的联机走法: sender={}，期望对端={}", senderUuid, remotePeer);
            return;
        }
        this.onRemoteMove(data);
    }

    /** 退出对局时向对端发送 LEAVE_GAME（带防重复标志，避免重复发包） */
    private void sendLeaveGameOnce() {
        if (lanMode == LAN_NONE || lanLeaveSent || remotePeer == null) return;
        lanLeaveSent = true;
        sendLeaveGame();
    }

    /** 后台 AI 计算线程（onClose 时 interrupt 防止 screen 泄漏到 GC 之外） */
    private volatile Thread aiWorker = null;

    @Override
    public void onClose() {
        sendLeaveGameOnce();
        // ★ Bug修复：原版只发 LEAVE_GAME,后台 AI 线程仍在跑（MCTS 搜索 1-12s）
        //   闭包持有 game/screen 引用 → screen 永不 GC → 神经网络/Zobrist/树内存
        //   全部泄漏。interrupt 后 MCTS 主循环 deadline 检查外层加 Thread.interrupted()
        //   加速回收；同时 close GoGame（AutoCloseable）释放 AI 资源。
        Thread t = aiWorker;
        if (t != null) t.interrupt();
        try { if (game != null) game.close(); } catch (Exception ignored) {}
        super.onClose();
    }

    @Override
    public void onRemoteMove(String data) {
        if ("RESTART".equals(data)) { resetGame(); return; }
        if ("PASS".equals(data)) {
            game.pass();
            myTurn = true;
            if (game.isGameOver()) finishGame();
            return;
        }
        if (data.startsWith("RESIGN:")) {
            // 对方认输，我赢
            myWin    = true;
            resultMsg = "对方认输，你赢了！";
            state    = State.GAME_OVER;
            return;
        }
        try {
            String[] p = data.split(",");
            // ★ Bug修复：原版 split 后直接 parseInt,1 字段/越界/畸形报文抛异常被吞
            //   但 myTurn=true 已执行,玩家误以为轮到自己下子空过一回合
            if (p.length < 2) return;
            int x = Integer.parseInt(p[0]);
            int y = Integer.parseInt(p[1]);
            if (x < 0 || x >= 19 || y < 0 || y >= 19) return;
            game.placeStone(x, y);
            myTurn = true;
            if (game.isGameOver()) finishGame();
        } catch (Exception ignored) {}
    }

    @Override public void onRemoteState(String data) { /* 围棋走法驱动 */ }
    @Override public void onRemoteGameOver(String data) { /* 由 finishGame 本地处理 */ }

    private void sendLanMove(String moveData) {
        if (lanMode == LAN_NONE) return;
        sendMove(moveData);
    }

    // ── 游戏逻辑 ──────────────────────────────────────────────
    private void resetGame() {
        // ★ Bug修复：玩家在 AI 思考中按 N 重开,旧 AI 线程仍持有旧 game 引用,
        //   写入的 aiPendingMove 可能是新 game 还没准备好的状态,后续落子错乱。
        //   这里中断旧 AI 线程并清空 pending 状态
        Thread old = aiWorker;
        if (old != null) old.interrupt();
        aiWorker = null;
        aiThinking = false;
        aiPendingMove = null;
        aiComputed = false;

        game.reset();
        myTurn    = (lanMode != LAN_CLIENT);
        resultMsg = "";
        myWin     = false;
        state     = State.PLAYING;
    }

    /**
     * 游戏结束时计算胜负（中国规则：数子法，黑子贴目3.75目）。
     * 修复 Bug：原版 endGame() 不计算胜者，导致局域网双方都显示"你赢了"。
     */
    private void finishGame() {
        // 计算双方领地（中国规则数子法，复用 GoGame 的公共计分方法）
        int[] territory = game.calcTerritory();
        int blackTerritory = territory[0];
        int whiteTerritory = territory[1];

        // 中国规则数子法（区域计分）：得分 = 棋盘活子数 + 单独围空。
        // 修复：不再把提子数加进总分——提掉的子已从对方区域中消失，
        // 区域计分天然包含了提子收益，再加一次属于双重计分。
        // 黑棋贴3.75子（等价于贴目7.5）。
        double blackScore = blackTerritory;
        double whiteScore = whiteTerritory + 3.75;

        boolean blackWins = blackScore > whiteScore;

        if (lanMode == LAN_NONE) {
            // 单机/AI 模式
            if (game.isAiMode()) {
                // AI 执白，玩家执黑
                myWin = blackWins;
                resultMsg = String.format("%s 胜！黑%.1f 白%.1f",
                        blackWins ? "玩家（黑）" : "AI（白）", blackScore, whiteScore);
            } else {
                // 双人模式：本地双人
                myWin = blackWins;
                resultMsg = String.format("%s 胜！黑%.1f 白%.1f",
                        blackWins ? "黑方" : "白方", blackScore, whiteScore);
            }
        } else {
            // LAN 模式：HOST=黑，CLIENT=白
            boolean iAmBlack = (lanMode == LAN_HOST);
            myWin     = iAmBlack == blackWins;
            resultMsg = String.format("%s 胜！黑%.1f 白%.1f",
                    myWin ? "你" : "对方", blackScore, whiteScore);
        }
        state = State.GAME_OVER;
    }

    @Override public void tick() {
        tickCount++;
        // AI 模式：AI 执白，黑棋下完后触发
        if (state == State.PLAYING && lanMode == LAN_NONE && game.isAiMode()
                && !game.isGameOver() && game.getCurrentPlayer() == GoPlayer.WHITE) {
            // 后台线程计算 AI 走法，避免阻塞客户端线程（MCTS 搜索 1~12 秒）
            if (!aiThinking && !aiComputed) {
                aiThinking = true;
                Thread t = new Thread(() -> {
                    try {
                        // screen 关闭时会被 interrupt,这里快速退出
                        if (Thread.currentThread().isInterrupted()) return;
                        aiPendingMove = game.computeAiMove(); // null = 建议弃权
                    } catch (Exception e) {
                        aiPendingMove = null; // 异常时弃权
                    } finally {
                        aiThinking = false;
                        aiComputed = true;
                    }
                }, "go-ai-worker");
                aiWorker = t;
                t.setDaemon(true);
                t.start();
            }
        }
        // 客户端线程消费 AI 结果并落子
        if (aiComputed) {
            aiComputed = false;
            int[] move = aiPendingMove;
            aiPendingMove = null;
            game.applyAiMove(move);
            if (game.isGameOver()) finishGame();
        }
    }

    @Override public boolean keyPressed(int key, int scan, int mods) {
        if (key == GLFW.GLFW_KEY_ESCAPE) {
            if (showExitConfirm) { showExitConfirm = false; return true; }
            if (state == State.SETTINGS) { state = State.MENU; return true; }
            if (state != State.MENU) { showExitConfirm = true; return true; }
            Minecraft.getInstance().setScreen(new GameSelectorScreen()); return true;
        }
        if (showExitConfirm) return true;
        if (key == GLFW.GLFW_KEY_N) {
            // LAN：CLIENT 不能单方面重开；HOST 重开需广播 RESTART 同步对端，否则双方棋盘永久分叉
            if (lanMode == LAN_CLIENT) return true;
            resetGame(); state = State.PLAYING;
            if (lanMode == LAN_HOST) sendLanMove("RESTART");
            return true;
        }
        if (key == GLFW.GLFW_KEY_P && state == State.PLAYING && !game.isGameOver()) {
            if (lanMode == LAN_NONE) {
                game.pass();
                if (game.isGameOver()) finishGame();
            } else if (myTurn) {
                game.pass();
                myTurn = false;
                sendLanMove("PASS");
                if (game.isGameOver()) finishGame();
            }
            return true;
        }
        return true;
    }

    @Override public void render(GuiGraphics g, int mx, int my, float pt) {
        cellSize = Math.max(8, Math.min((width - 120) / BOARD_SIZE, (height - 80) / BOARD_SIZE));
        int boardPixels = BOARD_SIZE * cellSize;
        boardStartX = (width - boardPixels) / 2 - 40;
        boardStartY = (height - boardPixels) / 2;

        GameRenderHelper.fillDarkBackground(g, width, height);
        switch (state) {
            case MENU -> renderMenu(g, mx, my);
            case SETTINGS -> renderSettings(g, mx, my);
            case PLAYING -> renderPlaying(g, mx, my);
            case GAME_OVER -> { renderPlaying(g, mx, my); renderGameOver(g, mx, my); }
        }
        if (showExitConfirm) GameRenderHelper.drawExitConfirmOverlay(g, font, width, height, mx, my);
    }

    private void renderMenu(GuiGraphics g, int mx, int my) {
        int cx = width / 2, cy = height / 2;
        GameRenderHelper.renderDecorativeLines(g, width, height, tickCount, 0x112200);
        GameRenderHelper.drawShadowedCenteredText(g, font, "围 棋", cx, cy - 60, 0xFFFFFF, 2);
        g.drawCenteredString(font, "Go Game", cx, cy - 42, 0x555555);
        GameRenderHelper.drawDivider(g, cx - 80, cy - 32, 160, 0xFFD2B48C, 0xFF8B7355);
        g.drawCenteredString(font, "鼠标点击落子  N新游戏  P弃权", cx, cy - 10, 0xAAAAAA);
        g.drawCenteredString(font, "围地为王，黑白博弈的艺术", cx, cy + 5, 0xCCCCCC);
        GameRenderHelper.drawPrimaryButton(g, font, "开始游戏", cx - 60, cy + 30, 120, 22, mx, my);
        // 设置按钮
        GameRenderHelper.drawPrimaryButton(g, font, "⚙ AI设置", cx - 60, cy + 58, 120, 22, mx, my);
    }

    private void renderSettings(GuiGraphics g, int mx, int my) {
        int cx = width / 2, cy = height / 2;
        GameRenderHelper.renderDecorativeLines(g, width, height, tickCount, 0x112200);

        // 设置面板背景
        int pw = 320, ph = 260;
        int px = cx - pw / 2, py = cy - ph / 2;
        g.fill(px, py, px + pw, py + ph, 0xCC0A1520);
        g.fill(px - 2, py - 2, px + pw + 2, py + 2, 0xFF3A5A8A);
        g.fill(px - 2, py + ph, px + pw + 2, py + ph + 2, 0xFF3A5A8A);
        g.fill(px - 2, py, px + 2, py + ph, 0xFF3A5A8A);
        g.fill(px + pw, py, px + pw + 2, py + ph, 0xFF3A5A8A);

        // 标题
        GameRenderHelper.drawShadowedCenteredText(g, font, "AI 引擎设置", cx, py + 12, 0xFFFFFF, 1);

        // 引擎选择
        int optionY = py + 45;
        g.drawString(font, "引擎类型:", px + 15, optionY, 0xCCCCCC);

        // MCTS 按钮
        boolean mctsSelected = "mcts".equals(settingsEngine);
        int mctsColor = mctsSelected ? 0xFF44AA44 : 0xFF666666;
        int mctsBg = mctsSelected ? 0xFF1A3A1A : 0xFF2A2A2A;
        g.fill(px + 90, optionY - 4, px + 200, optionY + 18, mctsBg);
        g.drawString(font, "改进版MCTS", px + 95, optionY, mctsColor);

        // KataGo 按钮
        boolean kataSelected = "katago".equals(settingsEngine);
        int kataColor = kataSelected ? 0xFF44AA44 : 0xFF666666;
        int kataBg = kataSelected ? 0xFF1A3A1A : 0xFF2A2A2A;
        g.fill(px + 205, optionY - 4, px + 305, optionY + 18, kataBg);
        g.drawString(font, "KataGo", px + 215, optionY, kataColor);

        // 搜索时间
        int timeY = optionY + 45;
        g.drawString(font, "搜索时间: " + (settingsSearchTime / 1000.0) + "s", px + 15, timeY, 0xCCCCCC);

        // 时间滑块背景
        int sliderX = px + 90, sliderW = 200;
        g.fill(sliderX, timeY + 8, sliderX + sliderW, timeY + 18, 0xFF3A3A3A);
        // 滑块填充
        int fillW = (settingsSearchTime - 1000) * sliderW / 9000;
        g.fill(sliderX, timeY + 8, sliderX + fillW, timeY + 18, 0xFF4A6A8A);
        // 滑块位置
        int thumbX = sliderX + fillW - 5;
        g.fill(thumbX, timeY + 3, thumbX + 10, timeY + 23, 0xFF88AACC);

        // KataGo 路径（仅当选择 KataGo 时显示）
        int pathY = timeY + 45;
        if ("katago".equals(settingsEngine)) {
            g.drawString(font, "KataGo 路径:", px + 15, pathY, 0xCCCCCC);
            // 路径显示（截断过长路径）
            String displayPath = settingsKatagoPath.isEmpty() ? "(未配置)" :
                    (settingsKatagoPath.length() > 30 ?
                            "..." + settingsKatagoPath.substring(settingsKatagoPath.length() - 30) :
                            settingsKatagoPath);
            int pathColor = settingsKatagoPath.isEmpty() ? 0xFF666666 : 0xFFAAAAAA;
            g.fill(px + 90, pathY - 4, px + 305, pathY + 18, 0xFF2A2A2A);
            g.drawString(font, displayPath, px + 95, pathY, pathColor);
            pathY += 45;
        }

        // 当前状态
        int statusY = pathY + 10;
        String currentEngine = GameSettings.getString("go", "engine", "mcts");
        String status = "当前引擎: " + ("mcts".equals(currentEngine) ? "改进版MCTS" : "KataGo");
        g.drawString(font, status, px + 15, statusY, 0xFF888888);

        // 保存并返回按钮
        int btnY = py + ph - 50;
        GameRenderHelper.drawPrimaryButton(g, font, "保存并返回", cx - 60, btnY, 120, 22, mx, my);
    }

    private void renderPlaying(GuiGraphics g, int mx, int my) {
        int bw = BOARD_SIZE * cellSize;
        g.fill(boardStartX - 4, boardStartY - 4, boardStartX + bw + 4, boardStartY + bw + 4, 0xFF3A2A10);
        g.fill(boardStartX, boardStartY, boardStartX + bw, boardStartY + bw, 0xFFD2B48C);

        for (int i = 0; i < BOARD_SIZE; i++) {
            int x = boardStartX + i * cellSize + cellSize/2;
            int y = boardStartY + i * cellSize + cellSize/2;
            g.fill(x, boardStartY + cellSize/2, x+1, boardStartY + bw - cellSize/2, 0xFF000000);
            g.fill(boardStartX + cellSize/2, y, boardStartX + bw - cellSize/2, y+1, 0xFF000000);
        }

        int[] stars = {3, 9, 15};
        for (int sx : stars)
            for (int sy : stars)
                GameRenderHelper.drawCircle(g, boardStartX + sx * cellSize + cellSize/2,
                    boardStartY + sy * cellSize + cellSize/2, 2, 0xFF000000);

        int stoneR = cellSize / 2 - 1;
        for (int x = 0; x < BOARD_SIZE; x++)
            for (int y = 0; y < BOARD_SIZE; y++) {
                GoPlayer stone = game.getStone(x, y);
                if (stone != GoPlayer.NONE) {
                    int scx = boardStartX + x * cellSize + cellSize/2;
                    int scy = boardStartY + y * cellSize + cellSize/2;
                    int color = stone == GoPlayer.BLACK ? 0xFF111111 : 0xFFEEEEEE;
                    GameRenderHelper.drawCircle(g, scx, scy, stoneR, color);
                    if (stone == GoPlayer.WHITE) GameRenderHelper.drawCircle(g, scx-stoneR/3, scy-stoneR/3, stoneR/4, 0x44FFFFFF);
                }
            }

        // 悬停预览
        if (state == State.PLAYING && !game.isGameOver()) {
            boolean canPlay = (lanMode == LAN_NONE) || myTurn;
            if (canPlay) {
                int[] pos = getBoardPos(mx, my);
                if (pos != null && game.canPlaceStone(pos[0], pos[1])) {
                    int scx = boardStartX + pos[0] * cellSize + cellSize/2;
                    int scy = boardStartY + pos[1] * cellSize + cellSize/2;
                    int color = game.getCurrentPlayer() == GoPlayer.BLACK ? 0x66111111 : 0x66EEEEEE;
                    GameRenderHelper.drawCircle(g, scx, scy, stoneR, color);
                }
            }
        }

        // 信息面板
        int infoX = boardStartX + bw + 15;
        int infoY = boardStartY;
        GameRenderHelper.drawPanel(g, infoX, infoY, 100, 140, GameRenderHelper.BG_PANEL, 0xFF334455);

        String curColor = game.getCurrentPlayer() == GoPlayer.BLACK ? "黑棋" : "白棋";
        g.drawString(font, "当前: " + curColor, infoX + 5, infoY + 8, 0xFFFFFF);

        String turnHint;
        if (lanMode == LAN_NONE) {
            turnHint = game.isAiMode() ? (game.getCurrentPlayer()==GoPlayer.BLACK ? "你的回合" : "AI思考中") : "进行中";
        } else {
            turnHint = myTurn ? "你的回合" : "等待对方";
        }
        g.drawString(font, turnHint, infoX + 5, infoY + 24, 0xFFFFFF);
        g.drawString(font, "黑捕: " + game.getBlackCaptured(), infoX + 5, infoY + 44, 0xCCCCCC);
        g.drawString(font, "白捕: " + game.getWhiteCaptured(), infoX + 5, infoY + 58, 0xCCCCCC);

        String modeStr = lanMode != LAN_NONE ? (lanMode==LAN_HOST ? "局域网(黑)" : "局域网(白)")
                : (game.isAiMode() ? "AI模式" : "双人模式");
        g.drawString(font, modeStr, infoX + 5, infoY + 78, 0xCCCCCC);

        // AI 引擎状态
        String engineStr = GameSettings.getString("go", "engine", "mcts");
        String engineLabel = "mcts".equals(engineStr) ? "MCTS" : "KataGo";
        g.drawString(font, "AI: " + engineLabel, infoX + 5, infoY + 92, 0x666666);

        GameRenderHelper.drawTopHUD(g, width, height);
        g.drawString(font, "⚫⚪ 围棋", 8, 7, 0xFFFFFF);
        GameRenderHelper.drawBottomBar(g, font, width, height, "ESC 菜单  N 新游戏  P 弃权");
    }

    private void renderGameOver(GuiGraphics g, int mx, int my) {
        int cx = width / 2, cy = height / 2;
        g.flush(); // 防止先绘制的棋盘/HUD文字盖住遮罩背景（批量渲染text批次后置）
        g.fill(0, 0, width, height, 0xAA000000);

        int pw = 300, ph = 120;
        int px = cx - pw/2, py = cy - ph/2;
        g.fill(px - 2, py - 2, px + pw + 2, py + ph + 2, myWin ? 0xFF44FF44 : 0xFFFF4444);
        g.fill(px, py, px + pw, py + ph, 0xFF0A1A2A);

        String headline = myWin ? "🎉 你赢了！" : "游戏结束";
        g.drawCenteredString(font, headline, cx, py + 12, myWin ? 0x44FF44 : 0xFF4444);
        g.drawCenteredString(font, resultMsg, cx, py + 30, 0xFFFFFF);
        g.drawCenteredString(font, "N - 再来一局  |  ESC - 返回", cx, py + 50, 0xAAAAAA);
    }

    private int[] getBoardPos(int mx, int my) {
        int bx = Mth.floor((mx - boardStartX + cellSize/2) / (float)cellSize);
        int by = Mth.floor((my - boardStartY + cellSize/2) / (float)cellSize);
        if (bx >= 0 && bx < BOARD_SIZE && by >= 0 && by < BOARD_SIZE) return new int[]{bx, by};
        return null;
    }

    @Override public boolean mouseClicked(double mx, double my, int btn) {
        if (showExitConfirm) { int click = GameRenderHelper.getExitConfirmClick(mx, my, width, height); if (click == 1) { showExitConfirm = false; sendLeaveGameOnce(); Minecraft.getInstance().setScreen(new GameSelectorScreen()); return true; } if (click == 2) { showExitConfirm = false; return true; } return true; }

        // ── 设置界面 ──
        if (state == State.SETTINGS) {
            int cx = width / 2, cy = height / 2;
            int pw = 320, ph = 260;
            int px = cx - pw / 2, py = cy - ph / 2;

            // 引擎选择
            int optionY = py + 45;
            if (mx >= px + 90 && mx <= px + 200 && my >= optionY - 4 && my <= optionY + 18) {
                settingsEngine = "mcts";
                return true;
            }
            if (mx >= px + 205 && mx <= px + 305 && my >= optionY - 4 && my <= optionY + 18) {
                settingsEngine = "katago";
                return true;
            }

            // 时间滑块
            int timeY = optionY + 45;
            int sliderX = px + 90, sliderW = 200;
            if (mx >= sliderX && mx <= sliderX + sliderW && my >= timeY + 3 && my <= timeY + 23) {
                int ratio = (int) ((mx - sliderX) * 9.0 / sliderW) + 1;
                settingsSearchTime = Math.max(1000, Math.min(10000, ratio * 1000));
                return true;
            }

            // 保存并返回按钮
            int btnY = py + ph - 50;
            if (mx >= cx - 60 && mx <= cx + 60 && my >= btnY && my <= btnY + 22) {
                // 保存设置到 GameSettings
                saveSettings();
                state = State.MENU;
                return true;
            }
            return true;
        }

        if (state == State.MENU) {
            int cx = width/2, cy = height/2;
            if (mx >= cx-60 && mx <= cx+60 && my >= cy+30 && my <= cy+52) {
                resetGame(); state = State.PLAYING; return true;
            }
            // 设置按钮
            if (mx >= cx-60 && mx <= cx+60 && my >= cy+58 && my <= cy+80) {
                state = State.SETTINGS; return true;
            }
        }
        if (state == State.PLAYING && btn == 0 && !game.isGameOver()) {
            boolean canPlay = (lanMode == LAN_NONE) || myTurn;
            if (!canPlay) return true;

            int[] pos = getBoardPos((int)mx, (int)my);
            if (pos != null && game.canPlaceStone(pos[0], pos[1])) {
                if (game.placeStone(pos[0], pos[1])) {
                    if (lanMode != LAN_NONE) {
                        sendLanMove(pos[0] + "," + pos[1]);
                        myTurn = false;
                    }
                    if (game.isGameOver()) finishGame();
                    else if (lanMode == LAN_NONE && game.isAiMode()) {
                        // AI 在 tick 里触发
                    }
                }
                return true;
            }
        }
        return super.mouseClicked(mx, my, btn);
    }

    /**
     * 保存 AI 设置到 data/game_settings.json
     */
    private void saveSettings() {
        try {
            // 使用 GameSettings 的内部机制保存
            // 这里通过反射或直接修改 settings map 来保存
            // 由于 GameSettings 没有提供保存单个键的方法，我们使用 importFromFile 的变通方式
            java.nio.file.Path dataDir = com.wzz.game_console.util.ExternalFileManager.getDataDir();
            java.nio.file.Path settingsPath = dataDir.resolve("game_settings.json");

            // 读取现有设置
            java.util.Map<String, java.util.Map<String, Object>> allSettings = new java.util.HashMap<>();
            if (java.nio.file.Files.exists(settingsPath)) {
                // ★ Bug修复：原版 Files.readString 整文件读入,无大小限制,settings 文件
                //   被外部异常增长时瞬时占大块堆。改为 BufferedReader + try-with-resources,
                //   并通过 size 预检拒绝 > 1MB 的文件
                long size = java.nio.file.Files.size(settingsPath);
                if (size > 1024 * 1024) {
                    // 配置文件超 1MB,视为异常,直接跳过读取
                } else {
                    StringBuilder sb = new StringBuilder();
                    try (java.io.BufferedReader br = java.nio.file.Files.newBufferedReader(
                            settingsPath, java.nio.charset.StandardCharsets.UTF_8)) {
                        char[] buf = new char[4096];
                        int n;
                        while ((n = br.read(buf)) > 0) sb.append(buf, 0, n);
                    }
                    String content = sb.toString();
                    com.google.gson.Gson gson = new com.google.gson.Gson();
                    java.lang.reflect.Type type = new com.google.gson.reflect.TypeToken<java.util.Map<String, java.util.Map<String, Object>>>(){}.getType();
                    java.util.Map<String, java.util.Map<String, Object>> loaded = gson.fromJson(content, type);
                    if (loaded != null) allSettings = loaded;
                }
            }

            // 更新围棋设置
            java.util.Map<String, Object> goSettings = allSettings.getOrDefault("go", new java.util.HashMap<>());
            // ★ Bug修复：settingsEngine/KatagoPath 可能为 null(null 进 GSON 序列化为
            //   "engine": null,后续 getString 虽 instanceof 兜底不崩,但下游分支
            //   可能因 null 走错路径。改为只 put 非空字段
            if (settingsEngine != null && !settingsEngine.isBlank()) {
                goSettings.put("engine", settingsEngine);
            }
            goSettings.put("searchTime", settingsSearchTime);
            if (settingsKatagoPath != null && !settingsKatagoPath.isBlank()) {
                goSettings.put("katagoPath", settingsKatagoPath);
            }
            allSettings.put("go", goSettings);

            // 保存 — 走 ExternalFileManager 原子写(tmp+atomic move),防 JVM 崩溃
            // 时截断 settings.json 导致玩家全部游戏配置丢失
            com.google.gson.Gson gson = new com.google.gson.GsonBuilder().setPrettyPrinting().create();
            String json = gson.toJson(allSettings);
            com.wzz.game_console.util.ExternalFileManager.writeTextFile("data", "game_settings.json", json);

            LOGGER.info("[围棋] AI 设置已保存: engine={}, searchTime={}ms", settingsEngine, settingsSearchTime);
        } catch (Exception e) {
            LOGGER.error("[围棋] 保存 AI 设置失败: {}", e.getMessage());
        }
    }

    @Override public boolean isPauseScreen() { return false; }
}

