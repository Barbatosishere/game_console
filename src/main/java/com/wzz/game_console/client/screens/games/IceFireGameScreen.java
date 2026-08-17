package com.wzz.game_console.client.screens.games;

import com.wzz.game_console.client.screens.GameSelectorScreen;
import com.wzz.game_console.util.GameRenderHelper;
import com.wzz.game_console.util.ResourceUtil;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.api.distmarker.OnlyIn;
import org.lwjgl.glfw.GLFW;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.*;
import java.util.stream.Collectors;

@OnlyIn(Dist.CLIENT)
public class IceFireGameScreen extends Screen implements LanMultiplayerScreen {
    private static final Logger LOGGER = LoggerFactory.getLogger(IceFireGameScreen.class);
    // ──────────────── 常量 ────────────────
    static final int TILE_SIZE  = 16;
    static final int GAME_W     = 320;
    static final int GAME_H     = 240;
    static final int MAP_COLS   = GAME_W / TILE_SIZE; // 20
    static final int MAP_ROWS   = GAME_H / TILE_SIZE; // 15
    static final int PW         = 12; // 玩家宽度
    static final int PH         = 14; // 玩家高度

    // ──────────────── 纹理 ────────────────
    static final ResourceLocation TEX_ICE      = ResourceUtil.createInstance("minecraft", "textures/block/blue_ice.png");
    static final ResourceLocation TEX_STONE    = ResourceUtil.createInstance("minecraft", "textures/block/stone.png");
    static final ResourceLocation TEX_DIAMOND  = ResourceUtil.createInstance("minecraft", "textures/item/diamond.png");
    static final ResourceLocation TEX_GRASS    = ResourceUtil.createInstance("minecraft", "textures/block/grass_block_top.png");
    static final ResourceLocation TEX_DIRT     = ResourceUtil.createInstance("minecraft", "textures/block/dirt.png");
    static final ResourceLocation TEX_PLANKS   = ResourceUtil.createInstance("minecraft", "textures/block/oak_planks.png");

    // ──────────────── 状态 ────────────────
    private GameState gameState = GameState.MENU;
    private GameSession session;
    /** 当前持续按下的按键 */
    private final Set<Integer> heldKeys = new HashSet<>();
    private long tickCount = 0;
    boolean showExitConfirm = false;
    /** 难度：0=简单 1=普通 2=困难 */
    private int difficulty = 1;

    // ─── LAN 联机 ──────────────────────────────────────────────────
    public static final int LAN_NONE = 0, LAN_HOST = 1, LAN_CLIENT = 2;
    private int lanMode = LAN_NONE;
    private java.util.UUID remotePeer = null;
    /** CLIENT 收到的主机状态（逗号分隔整数） */
    private volatile String receivedState = null;
    /** HOST 收到的客户端输入掩码 (bit0=左 bit1=右 bit2=跳) */
    private volatile int receivedClientInput = 0;
    /** 独立的跳跃请求标志，防止被移动掩码覆盖导致跳跃丢失 */
    private volatile boolean clientJumpRequested = false;
    /** 活跃实例（供静态网络回调使用） */
    public static volatile IceFireGameScreen activeInstance = null;
    /** 防重复发送 LEAVE_GAME 标志 */
    private boolean lanLeaveSent = false;

    /** 单机构造 */
    public IceFireGameScreen() {
        super(Component.literal("冰火双人冒险"));
    }

    /** 联机构造：isHost=true 为主机（冰人），false 为客机（火人） */
    public IceFireGameScreen(boolean isHost, java.util.UUID remote) {
        super(Component.literal("冰火双人冒险"));
        this.lanMode    = isHost ? LAN_HOST : LAN_CLIENT;
        this.remotePeer = remote;
    }

    // ── LanMultiplayerScreen 接口实现 ──────────────────────────────
    @Override public java.util.UUID getLanPeer()  { return remotePeer; }
    @Override public String getLanGameId()         { return "icefire"; }

    /**
     * 来源校验：CLIENT 仅接受已配对 HOST（服务端盖章 UUID）广播的状态，
     * 防止在线第三方伪造状态报文。
     */
    @Override
    public void onRemoteState(java.util.UUID senderUuid, String data) {
        if (lanMode == LAN_CLIENT) {
            if (remotePeer == null || !remotePeer.equals(senderUuid)) {
                LOGGER.warn("[冰火人] 丢弃来源非法的状态包: sender={}，期望对端={}", senderUuid, remotePeer);
                return;
            }
        }
        this.onRemoteState(data);
    }

    /**
     * 来源校验：HOST 仅接受已配对 CLIENT（服务端盖章 UUID）发来的输入，
     * 防止在线第三方伪造输入报文。
     */
    @Override
    public void onRemoteMove(java.util.UUID senderUuid, String data) {
        if (lanMode == LAN_HOST) {
            if (remotePeer == null || !remotePeer.equals(senderUuid)) {
                LOGGER.warn("[冰火人] 丢弃来源非法的输入包: sender={}，期望对端={}", senderUuid, remotePeer);
                return;
            }
        }
        this.onRemoteMove(data);
    }

    /** 退出对局时向对端发送 LEAVE_GAME（带防重复标志，避免重复发包） */
    private void sendLeaveGameOnce() {
        if (lanMode == LAN_NONE || lanLeaveSent || remotePeer == null) return;
        lanLeaveSent = true;
        sendLeaveGame();
    }

    @Override
    public void onClose() {
        sendLeaveGameOnce();
        super.onClose();
    }

    /** CLIENT 收到 HOST 广播的完整状态 */
    @Override
    public void onRemoteState(String data) { receivedState = data; }

    /** HOST 收到 CLIENT 发来的输入掩码（bit0=左 bit1=右 bit2=跳，含一次性 "4" 跳跃包） */
    @Override
    public void onRemoteMove(String data) {
        try {
            int val = Integer.parseInt(data.trim());
            // 修复：统一跳跃位语义——CLIENT 每 tick 发送的掩码可能带跳跃位（5/6/7），
            // 原先只识别纯跳跃包 "4" 导致掩码中的跳跃被忽略
            if ((val & 4) != 0) {
                clientJumpRequested = true;  // 独立处理跳跃，防止被移动掩码覆盖
            }
            receivedClientInput = val & 3;   // 只保留左右移动位
        } catch (Exception ignored) {}
    }

    // ══════════════════════════════════════
    //  渲染总入口
    // ══════════════════════════════════════
    @Override
    public void render(GuiGraphics g, int mx, int my, float pt) {
        // 深色背景
        g.fill(0, 0, width, height, 0xFF0D0D1A);
        switch (gameState) {
            case MENU       -> renderMenu(g, mx, my);
            case DIFFICULTY -> renderDifficultySelect(g, mx, my);
            case PLAYING    -> renderGame(g);
            case GAME_OVER  -> renderGameOver(g);
        }
        if (showExitConfirm) GameRenderHelper.drawExitConfirmOverlay(g, font, width, height, mx, my);
    }

    // ──────────────── 菜单画面 ────────────────
    private void renderMenu(GuiGraphics g, int mx, int my) {
        int cx = width / 2, cy = height / 2;

        // 装饰性背景竖线
        for (int i = 0; i < 20; i++) {
            int x = i * (width / 20);
            float alpha = 0.3f + 0.2f * (float) Math.sin(tickCount * 0.05 + i);
            g.fill(x, 0, x + 1, height, (int)(alpha * 255) << 24 | 0x002244);
        }

        // 标题
        drawShadowedCenteredText(g, "§b冰 §r§e& §r§c火 §r双人冒险", cx, cy - 90, 0xFFFFFF, 2);
        g.drawCenteredString(font, "Ice & Fire Platformer", cx, cy - 72, 0x556688);

        // 分割线
        g.fill(cx - 110, cy - 60, cx + 110, cy - 59, 0xFF2244AA);
        g.fill(cx - 110, cy - 59, cx + 110, cy - 58, 0xFFAA2200);

        // 冰人预览
        drawPlayerPreview(g, cx - 90, cy - 44, PlayerRole.ICE, tickCount);
        g.drawCenteredString(font, "§b冰人", cx - 80, cy - 20, 0x44AAFF);
        g.drawCenteredString(font, "W/A/D 移动", cx - 80, cy - 10, 0x7799CC);

        // 火人预览
        drawPlayerPreview(g, cx + 66, cy - 44, PlayerRole.FIRE, tickCount);
        g.drawCenteredString(font, "§c火人", cx + 76, cy - 20, 0xFF5533);
        g.drawCenteredString(font, "↑/←/→ 移动", cx + 76, cy - 10, 0xCC7755);

        // VS
        g.drawCenteredString(font, "§eVS", cx, cy - 30, 0xFFFF00);

        // 说明
        g.drawCenteredString(font, "收集全部钻石即可过关！共 3 关", cx, cy + 10, 0xCCCCCC);
        g.drawCenteredString(font, "§b冰人§r不能碰熔岩   §c火人§r不能碰水", cx, cy + 25, 0xFFFF99);
        g.drawCenteredString(font, "两人可以互相踩头！", cx, cy + 38, 0x88FF88);

        // 开始按钮
        int bx = cx - 80, by = cy + 54, bw = 160, bh = 22;
        boolean hover = mx >= bx && mx <= bx + bw && my >= by && my <= by + bh;
        g.fill(bx, by, bx + bw, by + bh, hover ? 0xFF446622 : 0xFF2A3D14);
        g.fill(bx, by, bx + bw, by + 1, 0xFF88CC44);
        g.fill(bx, by + bh - 1, bx + bw, by + bh, 0xFF223308);
        g.drawCenteredString(font, hover ? "§a► 开始游戏 ◄" : "► 开始游戏", cx, by + 7, hover ? 0xAAFF66 : 0x88CC44);
    }

    // ──────────────── 难度选择画面 ────────────────
    private void renderDifficultySelect(GuiGraphics g, int mx, int my) {
        int cx = width / 2, cy = height / 2;

        // 装饰性背景竖线
        for (int i = 0; i < 20; i++) {
            int x = i * (width / 20);
            float alpha = 0.3f + 0.2f * (float) Math.sin(tickCount * 0.05 + i);
            g.fill(x, 0, x + 1, height, (int)(alpha * 255) << 24 | 0x002244);
        }

        // 标题
        drawShadowedCenteredText(g, "选择难度", cx, cy - 80, 0xFFFFDD44, 2);
        g.drawCenteredString(font, "Select Difficulty", cx, cy - 62, 0x556688);

        // 分割线
        g.fill(cx - 100, cy - 52, cx + 100, cy - 51, 0xFFDDAA22);

        // 三个难度按钮
        String[] names  = {"§a简单", "§e普通", "§c困难"};
        String[] descs  = {"平台更宽·间距更短·钻石更少", "标准体验·原汁原味", "平台更窄·间距更远·钻石更多"};
        int[]    colors = {0xFF225522, 0xFF444422, 0xFF552222};
        int[]    hovers = {0xFF338833, 0xFF666633, 0xFF883333};
        int[]    tops   = {0xFF44CC44, 0xFFDDAA22, 0xFFCC4444};
        int[]    texts  = {0x88FF88, 0xFFDD66, 0xFF8888};

        for (int i = 0; i < 3; i++) {
            int bx = cx - 110, by = cy - 36 + i * 40, bw = 220, bh = 32;
            boolean hover = mx >= bx && mx <= bx + bw && my >= by && my <= by + bh;
            g.fill(bx, by, bx + bw, by + bh, hover ? hovers[i] : colors[i]);
            g.fill(bx, by, bx + bw, by + 1, tops[i]);
            g.fill(bx, by + bh - 1, bx + bw, by + bh, 0x44000000);
            g.drawCenteredString(font, (hover ? "► " : "") + names[i] + (hover ? " ◄" : ""), cx, by + 5, hover ? 0xFFFFFF : texts[i]);
            g.drawCenteredString(font, "§7" + descs[i], cx, by + 18, 0x999999);
        }

        // 底部提示
        g.drawCenteredString(font, "ESC 返回菜单", cx, cy + 96, 0x666666);
    }

    private void drawPlayerPreview(GuiGraphics g, int x, int y, PlayerRole role, long tick) {
        int body  = role == PlayerRole.ICE ? 0xFF3388FF : 0xFFFF4411;
        int head  = role == PlayerRole.ICE ? 0xFF88CCFF : 0xFFFFAA55;
        int bob   = (int)(Math.sin(tick * 0.08) * 2);
        g.fill(x,     y + bob,    x + PW, y + 4 + bob, head);
        g.fill(x,     y + 4 + bob,x + PW, y + PH + bob, body);
        // 眼睛
        g.fill(x + 2, y + 2 + bob, x + 5, y + 5 + bob, 0xFFFFFFFF);
        g.fill(x + 3, y + 3 + bob, x + 5, y + 5 + bob, 0xFF000000);
    }

    // ──────────────── 游戏画面 ────────────────
    private void renderGame(GuiGraphics g) {
        if (session == null) return;

        int scale  = Math.max(1, Math.min(width / GAME_W, height / GAME_H));
        int ox     = (width  - GAME_W * scale) / 2;
        int oy     = (height - GAME_H * scale) / 2;

        // 边框光晕
        g.fill(ox - 3, oy - 3, ox + GAME_W * scale + 3, oy + GAME_H * scale + 3, 0xFF334455);
        g.fill(ox - 2, oy - 2, ox + GAME_W * scale + 2, oy + GAME_H * scale + 2, 0xFF4466AA);

        g.pose().pushPose();
        g.pose().translate(ox, oy, 0);
        g.pose().scale(scale, scale, 1);
        session.render(g, tickCount);
        g.pose().popPose();

        renderHUD(g);
    }

    private void renderHUD(GuiGraphics g) {
        if (session == null) return;
        // 顶部 HUD 条
        g.fill(0, 0, width, 22, 0xCC000000);

        // 冰人标志
        g.fill(6,  4, 18, 16, 0xFF3388FF);
        g.fill(6,  4, 18,  5, 0xFF88CCFF);
        g.drawString(font, "§b冰人 WASD", 22, 7, 0x88CCFF);

        // 钻石进度（中央）
        String prog = "💎 " + session.getDiamonds() + " / " + session.getTotalDiamonds()
                + "   关卡 " + session.getLevel();
        g.drawCenteredString(font, prog, width / 2, 7, 0xFFFF44);

        // 火人标志
        g.fill(width - 18, 4, width - 6, 16, 0xFFFF4411);
        g.fill(width - 18, 4, width - 6,  5, 0xFFFF8866);
        int fw = font.width("火人 ←↑→");
        g.drawString(font, "§c火人 ←↑→", width - fw - 22, 7, 0xFF8866);

        // 底部提示
        g.fill(0, height - 14, width, height, 0xCC000000);
        g.drawCenteredString(font, "ESC 退出   R 重开", width / 2, height - 10, 0x666666);
    }

    // ──────────────── 结算画面 ────────────────
    private void renderGameOver(GuiGraphics g) {
        // 半透明遮罩
        g.flush(); // 防止先绘制的游戏文字盖住遮罩背景（批量渲染text批次后置）
        g.fill(0, 0, width, height, 0xAA000000);
        int cx = width / 2, cy = height / 2;
        boolean win = session != null && session.isVictory();

        // 面板
        int pw = 280, ph = 140;
        g.fill(cx - pw/2, cy - ph/2, cx + pw/2, cy + ph/2, 0xFF1A1A2E);
        g.fill(cx - pw/2, cy - ph/2, cx + pw/2, cy - ph/2 + 2,
                win ? 0xFF44FF44 : 0xFFFF4444);

        if (win) {
            drawShadowedCenteredText(g, "🎉  游戏通关！  🎉", cx, cy - 50, 0x44FF44, 1);
            g.drawCenteredString(font, "恭喜两位勇士一起完成了所有关卡！", cx, cy - 30, 0xCCFFCC);
        } else {
            drawShadowedCenteredText(g, "游戏结束！", cx, cy - 50, 0xFF4444, 1);
            g.drawCenteredString(font, "有玩家碰到了危险方块……", cx, cy - 30, 0xFFAAAA);
        }

        if (session != null) {
            g.drawCenteredString(font, "抵达关卡：" + session.getLevel(), cx, cy - 12, 0xFFFF44);
            g.drawCenteredString(font, "收集钻石：" + session.getDiamonds() + " / " + session.getTotalDiamonds(), cx, cy + 4, 0x44CCFF);
        }

        // 按钮
        g.fill(cx - 80, cy + 20, cx + 80, cy + 38, 0xFF224422);
        g.fill(cx - 80, cy + 20, cx + 80, cy + 21, 0xFF44AA44);
        g.drawCenteredString(font, "R  —  重新开始", cx, cy + 27, 0x88FF88);

        g.fill(cx - 80, cy + 44, cx + 80, cy + 62, 0xFF222233);
        g.fill(cx - 80, cy + 44, cx + 80, cy + 45, 0xFF666688);
        g.drawCenteredString(font, "ESC — 返回菜单", cx, cy + 51, 0xAAAACC);
    }

    private void drawShadowedCenteredText(GuiGraphics g, String text, int x, int y, int color, int scale) {
        g.pose().pushPose();
        g.pose().translate(x, y, 0);
        g.pose().scale(scale, scale, 1);
        int sw = font.width(text) * scale;
        g.drawString(font, text, -sw / 2 / scale + 1, 1, 0x44000000);
        g.drawString(font, text, -sw / 2 / scale, 0, color);
        g.pose().popPose();
    }

    // ══════════════════════════════════════
    //  每帧逻辑 tick（约 20 次/秒）
    // ══════════════════════════════════════
    @Override
    public void init() {
        super.init();
        activeInstance = this;
        // LAN 联机：跳过菜单直接开始
        if (lanMode != LAN_NONE) startGame();
    }

    @Override
    public void removed() {
        super.removed();
        if (activeInstance == this) activeInstance = null;
    }

    @Override
    public void tick() {
        tickCount++;

        // ★ Bug修复：LAN_CLIENT 即使在 GAME_OVER 状态也要处理来自 HOST 的最新状态，
        //   以便跟随 HOST 的重开信号（hostGameOver=0 → CLIENT 从 GAME_OVER 恢复 PLAYING）
        if (lanMode == LAN_CLIENT && session != null
                && receivedState != null && !receivedState.isEmpty()) {
            applyReceivedState(receivedState);
            receivedState = null;
        }

        if (session == null || gameState != GameState.PLAYING || showExitConfirm) return; // 弹窗期间暂停本地模拟

        switch (lanMode) {
            case LAN_NONE -> {
                // 单机：两人本地输入
                processMovement();
                session.update();
            }
            case LAN_HOST -> {
                // HOST：处理本地冰人输入 + 来自网络的火人输入
                processIceInput();
                applyClientFireInput(receivedClientInput);
                session.update();
                // 序列化状态并发送给客端
                sendStateToClient();
            }
            case LAN_CLIENT -> {
                // CLIENT：发送本地火人输入（物理由 HOST 运算）
                sendFireInputToHost();
            }
        }
        if (session.isGameOver() && lanMode != LAN_CLIENT) gameState = GameState.GAME_OVER;
    }

    /** 单机：冰人 WASD，火人方向键 */
    private void processMovement() {
        if (session == null) return;
        boolean il = heldKeys.contains(GLFW.GLFW_KEY_A);
        boolean ir = heldKeys.contains(GLFW.GLFW_KEY_D);
        if (il)        session.iceAction(Action.LEFT);
        else if (ir)   session.iceAction(Action.RIGHT);
        else           session.iceAction(Action.STOP);

        boolean fl = heldKeys.contains(GLFW.GLFW_KEY_LEFT);
        boolean fr = heldKeys.contains(GLFW.GLFW_KEY_RIGHT);
        if (fl)        session.fireAction(Action.LEFT);
        else if (fr)   session.fireAction(Action.RIGHT);
        else           session.fireAction(Action.STOP);
    }

    /** HOST 处理本地冰人（WASD）输入 */
    private void processIceInput() {
        if (session == null) return;
        boolean il = heldKeys.contains(GLFW.GLFW_KEY_A);
        boolean ir = heldKeys.contains(GLFW.GLFW_KEY_D);
        if (il)      session.iceAction(Action.LEFT);
        else if (ir) session.iceAction(Action.RIGHT);
        else         session.iceAction(Action.STOP);
    }

    /** HOST 将收到的客端输入掩码应用到火人（跳跃用独立标志，不会被移动掩码覆盖） */
    private void applyClientFireInput(int mask) {
        if (session == null) return;
        boolean fl = (mask & 1) != 0;
        boolean fr = (mask & 2) != 0;
        if (fl)      session.fireAction(Action.LEFT);
        else if (fr) session.fireAction(Action.RIGHT);
        else         session.fireAction(Action.STOP);
        if (clientJumpRequested) {
            session.fireAction(Action.JUMP);
            clientJumpRequested = false;
        }
    }

    /** HOST：将游戏状态发送到客端 */
    private void sendStateToClient() {
        if (session == null) return;
        sendState(buildStateString());
    }

    /**
     * 状态字符串格式（全为整数，浮点*10）：
     * "lv,ix,iy,iog,idead,fx,fy,fog,fdead,col,tot,gov,vic"
     */
    /**
     * 状态字符串格式：
     * "lv,ix,iy,iog,idead,fx,fy,fog,fdead,col,tot,gov,vic;x1_y1|x2_y2|..."
     * 分号后面是已收集的钻石格坐标列表，CLIENT 据此把对应 tile 改成 AIR。
     */
    private String buildStateString() {
        GameSession s = session;
        GamePlayer ice = s.ice, fire = s.fire;
        StringBuilder sb = new StringBuilder();
        sb.append(s.level).append(',')
          .append((int)(ice.x*10)).append(',').append((int)(ice.y*10)).append(',')
          .append(ice.onGround?1:0).append(',').append(ice.dead?1:0).append(',')
          .append((int)(fire.x*10)).append(',').append((int)(fire.y*10)).append(',')
          .append(fire.onGround?1:0).append(',').append(fire.dead?1:0).append(',')
          .append(s.getDiamonds()).append(',').append(s.getTotalDiamonds()).append(',')
          .append(s.isGameOver()?1:0).append(',').append(s.isVictory()?1:0);
        // 追加已收集钻石坐标（CLIENT 用来清除地图中的钻石 tile）
        sb.append(';');
        java.util.List<int[]> collected = s.map.collectedPositions;
        for (int i = 0; i < collected.size(); i++) {
            if (i > 0) sb.append('|');
            sb.append(collected.get(i)[0]).append('_').append(collected.get(i)[1]);
        }
        return sb.toString();
    }

    /** CLIENT：发送火人输入到 HOST（移动掩码 + 独立跳跃事件双保险） */
    private void sendFireInputToHost() {
        int mask = 0;
        if (heldKeys.contains(GLFW.GLFW_KEY_LEFT))  mask |= 1;
        if (heldKeys.contains(GLFW.GLFW_KEY_RIGHT)) mask |= 2;
        if (heldKeys.contains(GLFW.GLFW_KEY_UP))    mask |= 4;  // 跳跃也在掩码中持续发送
        sendInput(String.valueOf(mask));
    }

    /** CLIENT：将收到的状态字符串应用到本地 session（只更新显示，不跑物理） */
    private void applyReceivedState(String st) {
        if (st == null || session == null) return;
        try {
            // 格式："基础字段...;x1_y1|x2_y2|..."
            String[] parts = st.split(";", 2);
            String[] p = parts[0].split(",");

            int lv = Integer.parseInt(p[0]);
            // 关卡切换时重新加载地图（双端种子相同，初始钻石位置一致）
            if (lv != session.level) {
                session.level = lv;
                session.map.load(lv, session.difficulty);
            }
            session.ice.x  = Integer.parseInt(p[1]) / 10f;
            session.ice.y  = Integer.parseInt(p[2]) / 10f;
            session.ice.onGround = p[3].equals("1");
            session.ice.dead     = p[4].equals("1");
            session.fire.x = Integer.parseInt(p[5]) / 10f;
            session.fire.y = Integer.parseInt(p[6]) / 10f;
            session.fire.onGround = p[7].equals("1");
            session.fire.dead     = p[8].equals("1");
            session.map.collected = Integer.parseInt(p[9]);
            session.map.total     = Integer.parseInt(p[10]);
            if (p[11].equals("1")) {
                session.gameOver = true;
                session.victory  = p[12].equals("1");
                gameState = GameState.GAME_OVER;
            } else if (gameState == GameState.GAME_OVER) {
                // HOST 已重开（gameOver=0），CLIENT 跟随恢复 PLAYING
                session.gameOver = false;
                session.victory  = false;
                gameState = GameState.PLAYING;
            }

            // ★ 关键修复：把 HOST 已收集的钻石格改成 AIR，确保 CLIENT 地图一致
            if (parts.length > 1 && !parts[1].isEmpty()) {
                for (String coord : parts[1].split("\\|")) {
                    String[] xy = coord.split("_");
                    if (xy.length == 2) {
                        int cx = Integer.parseInt(xy[0]);
                        int cy = Integer.parseInt(xy[1]);
                        // 只有当前仍是 DIAMOND 时才改（避免重复操作）
                        if (session.map.get(cx, cy) == Tile.DIAMOND) {
                            session.map.tiles[cx][cy] = Tile.AIR;
                        }
                    }
                }
            }
        } catch (Exception ignored) {}
    }

    // ──────────────── 输入处理 ────────────────
    @Override
    public boolean keyPressed(int key, int scan, int mods) {
        // 修复：弹窗打开时不注册按键（此前add在拦截检查之前，导致弹窗期间仍能移动）
        if (key != GLFW.GLFW_KEY_ESCAPE && showExitConfirm) return true;

        if (key == GLFW.GLFW_KEY_ESCAPE) {
            if (showExitConfirm) { showExitConfirm = false; return true; }
            if (gameState == GameState.DIFFICULTY) { gameState = GameState.MENU; return true; }
            if (gameState == GameState.GAME_OVER) { sendLeaveGameOnce(); gameState = GameState.MENU; session = null; return true; }
            if (gameState != GameState.MENU) { showExitConfirm = true; heldKeys.clear(); return true; }
            Minecraft.getInstance().setScreen(new GameSelectorScreen()); return true;
        }

        heldKeys.add(key);

        switch (gameState) {
            case PLAYING -> {
                // 冰人跳（单机或HOST）
                if (lanMode != LAN_CLIENT && key == GLFW.GLFW_KEY_W && session != null)
                    session.iceAction(Action.JUMP);
                // 火人跳
                if (lanMode == LAN_NONE && key == GLFW.GLFW_KEY_UP && session != null)
                    session.fireAction(Action.JUMP);
                // CLIENT 发送跳跃给 HOST
                if (lanMode == LAN_CLIENT && key == GLFW.GLFW_KEY_UP)
                    sendInput("4");
            }
            case GAME_OVER -> {
                if (key == GLFW.GLFW_KEY_R && lanMode != LAN_CLIENT) { restart(); return true; }
            }
        }
        return true;
    }

    @Override
    public boolean keyReleased(int key, int scan, int mods) {
        heldKeys.remove(key);
        return super.keyReleased(key, scan, mods);
    }

    @Override
    public boolean mouseClicked(double mx, double my, int btn) {
        if (showExitConfirm) { int click = GameRenderHelper.getExitConfirmClick(mx, my, width, height); if (click == 1) { showExitConfirm = false; sendLeaveGameOnce(); Minecraft.getInstance().setScreen(new GameSelectorScreen()); return true; } if (click == 2) { showExitConfirm = false; return true; } return true; }
        int cx = width/2, cy = height/2;
        if (gameState == GameState.MENU && lanMode == LAN_NONE) {
            if (mx >= cx-80 && mx <= cx+80 && my >= cy+54 && my <= cy+76) {
                gameState = GameState.DIFFICULTY; return true;
            }
        }
        if (gameState == GameState.DIFFICULTY) {
            for (int i = 0; i < 3; i++) {
                int bx = cx - 110, by = cy - 36 + i * 40, bw = 220, bh = 32;
                if (mx >= bx && mx <= bx + bw && my >= by && my <= by + bh) {
                    difficulty = i; startGame(); return true;
                }
            }
            return true;
        }
        if (gameState == GameState.GAME_OVER && lanMode != LAN_CLIENT) {
            if (mx >= cx-80 && mx <= cx+80 && my >= cy+20 && my <= cy+38) { restart(); return true; }
            if (mx >= cx-80 && mx <= cx+80 && my >= cy+44 && my <= cy+62) { sendLeaveGameOnce(); gameState = GameState.MENU; session = null; return true; }
        }
        return super.mouseClicked(mx, my, btn);
    }

    private void startGame() {
        session = new GameSession(difficulty);
        session.init();
        gameState = GameState.PLAYING;
        heldKeys.clear();
    }
    private void restart() {
        if (session != null) { session.restart(); gameState = GameState.PLAYING; heldKeys.clear(); }
    }

    @Override public boolean isPauseScreen() { return false; }

    // ══════════════════════════════════════
    //  枚举
    // ══════════════════════════════════════
    enum GameState  { MENU, DIFFICULTY, PLAYING, GAME_OVER }
    enum PlayerRole { ICE, FIRE }
    enum Action     { LEFT, RIGHT, STOP, JUMP }
    enum Tile       { AIR, STONE, DIRT, GRASS, PLANKS, WATER, LAVA, DIAMOND, ICE_BLOCK }

    // ══════════════════════════════════════
    //  粒子
    // ══════════════════════════════════════
    static class Particle {
        float x, y, vx, vy;
        int color, life, maxLife;
        boolean alive = true;
        Particle(float x, float y, float vx, float vy, int color, int life) {
            this.x=x; this.y=y; this.vx=vx; this.vy=vy;
            this.color=color; this.life=life; this.maxLife=life;
        }
        void update() {
            x += vx; y += vy; vy += 0.18f; life--;
            if (life <= 0) alive = false;
        }
        void render(GuiGraphics g) {
            int a = (int)((float)life / maxLife * 220);
            g.fill((int)x, (int)y, (int)x+2, (int)y+2, (a<<24)|(color&0xFFFFFF));
        }
    }

    // ══════════════════════════════════════
    //  游戏会话
    // ══════════════════════════════════════
    static class GameSession {
        GamePlayer ice, fire;
        GameMap map;
        public int level = 1;
        public boolean gameOver, victory;
        final List<Particle> particles = new ArrayList<>();
        private final Random rand = new Random();
        final int difficulty; // 0=简单 1=普通 2=困难

        GameSession(int difficulty) { this.difficulty = difficulty; }

        void init() {
            map = new GameMap();
            map.load(level, difficulty);
            ice  = new GamePlayer(map.iceSpawn[0],  map.iceSpawn[1],  PlayerRole.ICE);
            fire = new GamePlayer(map.fireSpawn[0], map.fireSpawn[1], PlayerRole.FIRE);
            gameOver = false; victory = false; particles.clear();
        }

        void update() {
            if (gameOver) return;
            ice.update(map, fire, particles);
            fire.update(map, ice, particles);
            particles.removeIf(p -> !p.alive);
            for (Particle p : particles) p.update();

            // 死亡检测
            if (ice.checkHazard(map) || fire.checkHazard(map)) {
                gameOver = true; victory = false; return;
            }

            // 钻石收集
            collectDiamonds(ice);
            collectDiamonds(fire);

            // 过关
            if (map.allCollected()) {
                level++;
                nextLevel(); // 无限关卡，永不结束
            }
        }

        private void collectDiamonds(GamePlayer p) {
            for (int dx = 0; dx <= 1; dx++) for (int dy = 0; dy <= 1; dy++) {
                int tx = (int)((p.x + dx * PW) / TILE_SIZE);
                int ty = (int)((p.y + dy * PH) / TILE_SIZE);
                if (map.get(tx, ty) == Tile.DIAMOND) {
                    map.collect(tx, ty);
                    // 钻石收集粒子
                    for (int i = 0; i < 10; i++)
                        particles.add(new Particle(tx*TILE_SIZE+8, ty*TILE_SIZE+8,
                                (rand.nextFloat()-0.5f)*4, -rand.nextFloat()*3,
                                0xFF00FFFF, 25+rand.nextInt(15)));
                }
            }
        }

        private void nextLevel() {
            map.load(level, difficulty);
            ice.reset(map.iceSpawn[0],  map.iceSpawn[1]);
            fire.reset(map.fireSpawn[0], map.fireSpawn[1]);
        }

        void iceAction(Action a)  { if (ice  != null && !ice.dead)  ice.applyAction(a); }
        void fireAction(Action a) { if (fire != null && !fire.dead) fire.applyAction(a); }

        void render(GuiGraphics g, long tick) {
            renderSky(g, tick);
            map.render(g, tick);
            for (Particle p : particles) p.render(g);
            ice.render(g, tick);
            fire.render(g, tick);
        }

        private void renderSky(GuiGraphics g, long tick) {
            // 关卡主题天空渐变（8 段近似渐变）
            int top, bot;
            switch (level) {
                case 1  -> { top = 0xFF0D1B2A; bot = 0xFF1A3A6F; }
                case 2  -> { top = 0xFF1A0800; bot = 0xFF4A1500; }
                default -> { top = 0xFF0A1A0A; bot = 0xFF1A3A1A; }
            }
            for (int i = 0; i < 8; i++) {
                float t = i / 7f;
                int y1 = i * GAME_H / 8, y2 = (i+1) * GAME_H / 8;
                int r = lerp((top>>16)&0xFF, (bot>>16)&0xFF, t);
                int gg= lerp((top>>8)&0xFF,  (bot>>8)&0xFF,  t);
                int b = lerp( top&0xFF,        bot&0xFF,       t);
                g.fill(0, y1, GAME_W, y2, 0xFF000000|(r<<16)|(gg<<8)|b);
            }
            // 背景星星 / 浮云
            renderBgDecorations(g, tick);
        }

        private void renderBgDecorations(GuiGraphics g, long tick) {
            // 星点
            for (int i = 0; i < 28; i++) {
                int sx = (i * 43 + 7)  % GAME_W;
                int sy = (i * 29 + 11) % (GAME_H / 2);
                int br = (int)(100 + 80 * Math.sin(tick * 0.04 + i));
                int c  = 0xFF000000 | (br<<16)|(br<<8)|br;
                g.fill(sx, sy, sx+1, sy+1, c);
            }
            // 云（关卡1蓝白，关卡2橙红火焰，关卡3绿白）
            int[] cxBase = {0, 90, 185, 265};
            int[] cyArr  = {18, 10, 22, 14};
            int[] cw     = {48, 36, 56, 40};
            int cloudColor = level==2 ? 0x44FF6622 : level==3 ? 0x4488FF88 : 0x55DDDDEE;
            for (int i = 0; i < 4; i++) {
                int cx = (int)((tick/3 + cxBase[i]) % (GAME_W + cw[i])) - cw[i];
                int cy = cyArr[i], w = cw[i];
                g.fill(cx+w/4, cy,   cx+3*w/4, cy+4,    cloudColor);
                g.fill(cx,     cy+4, cx+w,      cy+10,   cloudColor);
                g.fill(cx+w/5, cy+10,cx+4*w/5, cy+13,   cloudColor & 0xAAFFFFFF);
            }
        }

        private static int lerp(int a, int b, float t) {
            return (int)(a + (b-a) * t);
        }

        void restart() { level=1; init(); }


        boolean isGameOver()  { return gameOver; }
        boolean isVictory()   { return victory; }
        int     getLevel()    { return level; }
        int     getDiamonds() { return map!=null ? map.collected : 0; }
        int     getTotalDiamonds() { return map!=null ? map.total : 0; }
    }

    // ══════════════════════════════════════
    //  玩家
    // ══════════════════════════════════════
    static class GamePlayer {
        float x, y, vx, vy;
        final PlayerRole role;
        boolean onGround, dead, initialized, facingRight = true;
        long birthTime;
        float walkPhase;

        static final float GRAVITY      = 0.48f;
        static final float MOVE_SPEED   = 2.3f;
        static final float JUMP_VEL     = -9.0f;
        static final float FRICTION_GND = 0.72f;
        static final float FRICTION_AIR = 0.92f;

        GamePlayer(float x, float y, PlayerRole role) {
            this.x=x; this.y=y; this.role=role;
            birthTime = System.currentTimeMillis();
        }

        void applyAction(Action a) {
            if (dead) return;
            switch (a) {
                case LEFT  -> { vx = -MOVE_SPEED; facingRight = false; }
                case RIGHT -> { vx =  MOVE_SPEED; facingRight = true;  }
                case STOP  -> { /* 靠摩擦力减速 */ }
                case JUMP  -> {
                    if (onGround) { vy = JUMP_VEL; onGround = false; }
                }
            }
        }

        void update(GameMap map, GamePlayer other, List<Particle> ps) {
            if (dead) return;
            if (!initialized) { initialized = true; return; }

            // 重力
            vy += GRAVITY;
            // 摩擦
            vx *= onGround ? FRICTION_GND : FRICTION_AIR;
            if (Math.abs(vx) < 0.05f) vx = 0;

            // 走路动画
            if (onGround && Math.abs(vx) > 0.2f) walkPhase += Math.abs(vx) * 0.12f;

            onGround = false;

            // 横向移动 + 碰撞
            moveX(map, other);
            // 纵向移动 + 碰撞
            moveY(map, other);

            // 边界
            if (x < 0)           { x = 0;           vx = 0; }
            if (x + PW > GAME_W) { x = GAME_W - PW; vx = 0; }

            // 掉出地图
            if (y > GAME_H + 20) dead = true;
        }

        private void moveX(GameMap map, GamePlayer other) {
            x += vx;
            if (vx > 0) {
                int tileR = (int)((x + PW) / TILE_SIZE);
                for (int ty = (int)(y/TILE_SIZE); ty <= (int)((y+PH-1)/TILE_SIZE); ty++) {
                    if (map.isSolid(tileR, ty)) {
                        x = tileR * TILE_SIZE - PW; vx = 0; break;
                    }
                }
            } else if (vx < 0) {
                int tileL = (int)(x / TILE_SIZE);
                for (int ty = (int)(y/TILE_SIZE); ty <= (int)((y+PH-1)/TILE_SIZE); ty++) {
                    if (map.isSolid(tileL, ty)) {
                        x = (tileL + 1) * TILE_SIZE; vx = 0; break;
                    }
                }
            }
            // 与另一玩家横向碰撞
            if (other != null && !other.dead && overlapsXY(other)) {
                if (vx > 0) x = other.x - PW;
                else        x = other.x + PW;
                vx = 0;
            }
        }

        private void moveY(GameMap map, GamePlayer other) {
            y += vy;
            if (vy > 0) {
                // 向下
                int tileB = (int)((y + PH) / TILE_SIZE);
                int txL = (int)(x / TILE_SIZE), txR = (int)((x+PW-1) / TILE_SIZE);
                boolean hit = false;
                for (int tx = txL; tx <= txR; tx++) {
                    if (map.isSolid(tx, tileB)) {
                        y = tileB * TILE_SIZE - PH; vy = 0; onGround = true; hit = true; break;
                    }
                }
                // 站在另一位玩家头上
                if (!hit && other != null && !other.dead && canStandOn(other)) {
                    y = other.y - PH; vy = 0; onGround = true;
                }
            } else if (vy < 0) {
                // 向上
                int tileT = (int)(y / TILE_SIZE);
                int txL = (int)(x / TILE_SIZE), txR = (int)((x+PW-1) / TILE_SIZE);
                for (int tx = txL; tx <= txR; tx++) {
                    if (map.isSolid(tx, tileT)) {
                        y = (tileT + 1) * TILE_SIZE; vy = 0; break;
                    }
                }
            }
        }

        /** 水平矩形重叠 */
        private boolean overlapsXY(GamePlayer o) {
            return x < o.x+PW && x+PW > o.x && y < o.y+PH && y+PH > o.y;
        }

        /** 是否可以站在另一玩家头上（从上方踩） */
        private boolean canStandOn(GamePlayer o) {
            float prevBottom = y - vy + PH; // 上一帧的脚底
            boolean wasAbove = prevBottom <= o.y + 4;
            boolean horizOk  = x + PW > o.x + 2 && x < o.x + PW - 2;
            boolean vertOk   = y + PH >= o.y && y + PH <= o.y + PH / 2;
            return wasAbove && horizOk && vertOk;
        }

        boolean checkHazard(GameMap map) {
            if (dead || !initialized) return false;
            if (System.currentTimeMillis() - birthTime < 600) return false;
            for (int tx = (int)(x/TILE_SIZE); tx <= (int)((x+PW-1)/TILE_SIZE); tx++) {
                for (int ty = (int)(y/TILE_SIZE); ty <= (int)((y+PH-1)/TILE_SIZE); ty++) {
                    Tile t = map.get(tx, ty);
                    if (role == PlayerRole.ICE  && t == Tile.LAVA ) { dead = true; return true; }
                    if (role == PlayerRole.FIRE && t == Tile.WATER) { dead = true; return true; }
                }
            }
            return false;
        }

        void render(GuiGraphics g, long tick) {
            if (dead) {
                renderDead(g);
                return;
            }
            int body   = role == PlayerRole.ICE ? 0xFF2277EE : 0xFFEE3311;
            int head   = role == PlayerRole.ICE ? 0xFF66BBFF : 0xFFFFAA44;
            int foot   = role == PlayerRole.ICE ? 0xFF1155BB : 0xFFBB2200;
            int lx     = (int)x, ly = (int)y;

            // 地面阴影
            if (onGround)
                g.fill(lx+2, ly+PH+1, lx+PW-2, ly+PH+3, 0x33000000);

            // 身体
            g.fill(lx,     ly+3, lx+PW, ly+PH,   body);
            // 头
            g.fill(lx+1,   ly,   lx+PW-1, ly+4,  head);
            // 发饰条
            g.fill(lx+1,   ly,   lx+PW-1, ly+1,
                    role==PlayerRole.ICE ? 0xFFAAEEFF : 0xFFFFDD66);
            // 眼睛
            int ex = facingRight ? lx+PW-5 : lx+2;
            g.fill(ex,   ly+1, ex+3, ly+4, 0xFFFFFFFF);
            g.fill(ex+(facingRight?1:0), ly+2, ex+2, ly+4, 0xFF000000);

            // 走路时的脚动画
            if (onGround) {
                float bob = (float) Math.sin(walkPhase * Math.PI);
                int lf = (int)(bob * 2);
                int rf = (int)(-bob * 2);
                g.fill(lx+1,     ly+PH-2+lf, lx+PW/2,   ly+PH+lf,   foot);
                g.fill(lx+PW/2+1,ly+PH-2+rf, lx+PW-1, ly+PH+rf, foot);
            }

            // 玩家标签（头顶）
            String label = role==PlayerRole.ICE ? "§bW" : "§c↑";
            g.drawString(Minecraft.getInstance().font, label, lx-1, ly-10,
                    role==PlayerRole.ICE ? 0xFF88CCFF : 0xFFFF8866);

            // 跳跃时的特效：身体拉伸/压缩
            if (!onGround && vy < -2)
                g.fill(lx+3, ly-1, lx+PW-3, ly+2, head); // 向上拉伸
        }

        private void renderDead(GuiGraphics g) {
            int lx = (int)x, ly = (int)y;
            // 灰色残影
            g.fill(lx, ly, lx+PW, ly+PH, 0x66888888);
            // × 眼
            g.fill(lx+2, ly+1, lx+5, ly+4, 0xFFFF4444);
            g.fill(lx+PW-5, ly+1, lx+PW-2, ly+4, 0xFFFF4444);
            // 叉
            g.fill(lx+2, ly+2, lx+5, ly+3, 0xFFFFFFFF);
            g.fill(lx+3, ly+1, lx+4, ly+4, 0xFFFFFFFF);
        }

        void reset(float nx, float ny) {
            x=nx; y=ny; vx=0; vy=0;
            dead=false; onGround=false; initialized=false;
            birthTime=System.currentTimeMillis(); walkPhase=0;
        }

        boolean isDead() { return dead; }
    }

    // ══════════════════════════════════════
    //  地图
    // ══════════════════════════════════════
    static class GameMap {
        Tile[][] tiles = new Tile[MAP_COLS][MAP_ROWS];
        public int total, collected;
        /** HOST 侧记录每一个被收集的钻石格坐标，供 buildStateString 追加给 CLIENT */
        final java.util.List<int[]> collectedPositions = new java.util.ArrayList<>();
        float[] iceSpawn  = {20, (MAP_ROWS-4)*TILE_SIZE};
        float[] fireSpawn = {40, (MAP_ROWS-4)*TILE_SIZE};

        // ══════════════════════════════════════════════════════
        //  随机关卡生成器
        //
        //  核心思路：
        //  1. 用"可达链"逐段生成平台，保证每段都能从上一段跳到
        //  2. 危险液体只填在地面层的"平台间隙"列，绝不放在平台上
        //  3. 钻石只放在平台表面上方 1 格（保证可踩到），
        //     且优先放在平台中央，避免边缘
        //  4. 关卡越高：液体比例↑、平台间距↑、钻石数↑
        // ══════════════════════════════════════════════════════
        void load(int level, int difficulty) {
            Random rng = new Random(level * 987654321L + difficulty * 12345L); // 确定性种子
            for (Tile[] col : tiles) Arrays.fill(col, Tile.AIR);
            total = 0; collected = 0;
            collectedPositions.clear();
            buildRandom(level, rng, difficulty);
        }

        private void buildRandom(int level, Random rng, int difficulty) {
            // ── 参数随关卡和难度调整 ──
            // 跳跃能力：JUMP_VEL=-9, GRAVITY=0.48 → 最大高度≈84px≈5格，最大水平≈86px≈5格
            int   maxJumpUp   = difficulty == 0 ? 3 : 4;    // 简单：上升更缓
            int   maxGapH     = (difficulty == 0 ? 3 : difficulty == 2 ? 5 : 4) + Math.min(level-1, 3);
            int   minPlatW    = difficulty == 0 ? 4 : difficulty == 2 ? 2 : 3;
            int   maxPlatW    = difficulty == 0 ? 6 : difficulty == 2 ? 4 : 5;
            int   groundY     = MAP_ROWS - 1;
            int   groundTop   = MAP_ROWS - 3;
            int   minPlatY    = 3;
            int   diamondGoal = (difficulty == 0 ? 2 : difficulty == 2 ? 4 : 3) + Math.min(level - 1, 5);

            // ── Step 1：地面两行 ──
            row(groundY,     0, MAP_COLS-1, Tile.STONE);
            row(groundY - 1, 0, MAP_COLS-1, Tile.DIRT);

            // ── Step 2：生成平台链 ──
            // 每个 Platform 记录其 x 范围和 y 行（表面行）
            // 平台表面 = platY，平台本身占 platY 到 platY+1（两行厚）
            record Platform(int x1, int x2, int y) {}
            List<Platform> platforms = new ArrayList<>();

            // 起始平台：左侧固定，方便玩家出生
            int startW  = 4 + rng.nextInt(2);              // 起始宽 4~5
            int startY  = groundTop - 1 - rng.nextInt(2);  // 高度接近地面
            platforms.add(new Platform(0, startW - 1, startY));

            int curX2 = startW - 1;  // 当前段右端 x
            int curY  = startY;      // 当前段平台行

            while (curX2 < MAP_COLS - 3) {
                // 间距
                int gap  = 1 + rng.nextInt(maxGapH);       // 水平间隙 1~maxGapH
                int newX1= curX2 + 1 + gap;
                if (newX1 >= MAP_COLS - 1) break;

                // 高度变化：-maxJumpUp ~ +2（允许稍微下降，不超出范围）
                int dy    = -rng.nextInt(maxJumpUp + 1) + rng.nextInt(3); // 负=上升
                int newY  = Math.max(minPlatY, Math.min(groundTop - 1, curY + dy));
                int platW = minPlatW + rng.nextInt(maxPlatW - minPlatW + 1);
                int newX2 = Math.min(MAP_COLS - 1, newX1 + platW - 1);

                platforms.add(new Platform(newX1, newX2, newY));
                curX2 = newX2;
                curY  = newY;
            }

            // 若最后一段没到右边，补一个右侧平台
            if (curX2 < MAP_COLS - 3) {
                int finalY = Math.max(minPlatY, Math.min(groundTop - 1, curY + rng.nextInt(3) - 1));
                platforms.add(new Platform(curX2 + 2, MAP_COLS - 1, finalY));
            }

            // ── Step 3：写入平台 tile（两行厚，表面 + 底面）──
            // 关卡越高，平台材质更多样
            Tile[] platTiles = switch (((level - 1) % 3)) {
                case 0  -> new Tile[]{Tile.GRASS,     Tile.DIRT,  Tile.PLANKS};
                case 1  -> new Tile[]{Tile.STONE,     Tile.STONE, Tile.PLANKS};
                default -> new Tile[]{Tile.ICE_BLOCK, Tile.STONE, Tile.GRASS};
            };
            for (Platform p : platforms) {
                Tile surf = platTiles[rng.nextInt(platTiles.length)];
                Tile base = (surf == Tile.GRASS || surf == Tile.ICE_BLOCK) ? Tile.DIRT : Tile.STONE;
                row(p.y(),     p.x1(), p.x2(), surf);
                row(p.y() + 1, p.x1(), p.x2(), base);
            }

            // ── Step 4：收集"平台覆盖"的列集合，用于后续安全判断 ──
            boolean[] coveredByPlat = new boolean[MAP_COLS]; // 该列是否有平台
            for (Platform p : platforms)
                for (int x = p.x1(); x <= p.x2(); x++)
                    coveredByPlat[x] = true;

            // ── Step 5：地面间隙填液体（所有间隙必然填满，水岩浆交替）──
            boolean waterTurn = rng.nextBoolean();
            int gapStart = -1;
            for (int x = 0; x <= MAP_COLS; x++) {
                boolean inGap = x < MAP_COLS && !coveredByPlat[x];
                if (inGap && gapStart < 0) gapStart = x;
                if (!inGap && gapStart >= 0) {
                    int gapEnd = x - 1;
                    Tile liquid = waterTurn ? Tile.WATER : Tile.LAVA;
                    waterTurn = !waterTurn;
                    for (int lx = gapStart; lx <= gapEnd; lx++)
                        set(lx, groundTop, liquid);
                    gapStart = -1;
                }
            }

            // ── Step 6：放钻石（只放在平台表面上方 1 格，且是 AIR）──
            // 收集所有合法放置点：平台表面(y)的上方一格(y-1)且不是边缘
            List<int[]> diamondCandidates = new ArrayList<>();
            for (Platform p : platforms) {
                int midX = (p.x1() + p.x2()) / 2; // 中央列优先
                // 先加中央区域
                for (int x = p.x1() + 1; x <= p.x2() - 1; x++) {
                    int dy = p.y() - 1;
                    if (dy >= 0 && get(x, dy) == Tile.AIR)
                        diamondCandidates.add(new int[]{x, dy,
                                Math.abs(x - midX)}); // 第3位=距中央距离，用于排序
                }
            }
            // 按距中央距离升序（优先中央，不会卡边）
            diamondCandidates.sort((a, b) -> a[2] - b[2]);

            // 随机挑选 diamondGoal 个，相邻平台各至少1个
            Set<Integer> usedPlatIdx = new HashSet<>();
            int placed = 0;
            // 先给每个平台各放1个（如果可用）
            for (int pi = 0; pi < platforms.size() && placed < diamondGoal; pi++) {
                Platform p = platforms.get(pi);
                List<int[]> forThis = diamondCandidates.stream()
                        .filter(c -> c[0] >= p.x1()+1 && c[0] <= p.x2()-1 && c[1] == p.y()-1)
                        .toList();
                if (!forThis.isEmpty()) {
                    int[] chosen = forThis.get(rng.nextInt(forThis.size()));
                    set(chosen[0], chosen[1], Tile.DIAMOND);
                    placed++;
                    usedPlatIdx.add(pi);
                }
            }
            // 补剩余（从所有候选里随机再选）
            List<int[]> remaining = diamondCandidates.stream()
                    .filter(c -> get(c[0], c[1]) == Tile.AIR)
                    .collect(Collectors.toList());
            Collections.shuffle(remaining, rng);
            for (int[] c : remaining) {
                if (placed >= diamondGoal) break;
                if (get(c[0], c[1]) == Tile.AIR) {
                    set(c[0], c[1], Tile.DIAMOND);
                    placed++;
                }
            }
            total = placed;

            // ── Step 7：出生点 = 起始平台左侧，表面上方 ──
            Platform sp = platforms.get(0);
            float spawnY = (sp.y() - 2) * TILE_SIZE; // 表面上方2格
            iceSpawn  = new float[]{ sp.x1() * TILE_SIZE + 2,          spawnY };
            fireSpawn = new float[]{ Math.min((sp.x1()+2)*TILE_SIZE+2,
                    sp.x2() * TILE_SIZE - 2),         spawnY };
        }

        // ──────── 工具方法 ────────
        void row(int y, int x1, int x2, Tile t) {
            for (int x = x1; x <= x2 && x < MAP_COLS; x++)
                if (y >= 0 && y < MAP_ROWS) tiles[x][y] = t;
        }
        void set(int x, int y, Tile t) {
            if (x>=0 && x<MAP_COLS && y>=0 && y<MAP_ROWS) tiles[x][y] = t;
        }
        Tile get(int x, int y) {
            if (x<0||x>=MAP_COLS||y<0||y>=MAP_ROWS) return Tile.AIR;
            return tiles[x][y];
        }
        boolean isSolid(int x, int y) {
            Tile t = get(x, y);
            return t==Tile.STONE||t==Tile.DIRT||t==Tile.GRASS||
                    t==Tile.PLANKS||t==Tile.ICE_BLOCK;
        }
        void collect(int x, int y) {
            if (get(x,y)==Tile.DIAMOND) {
                tiles[x][y] = Tile.AIR;
                collected++;
                collectedPositions.add(new int[]{x, y});
            }
        }
        boolean allCollected() { return total>0 && collected>=total; }

        void render(GuiGraphics g, long tick) {
            for (int x = 0; x < MAP_COLS; x++)
                for (int y = 0; y < MAP_ROWS; y++) {
                    Tile t = tiles[x][y];
                    if (t != Tile.AIR) renderTile(g, t, x*TILE_SIZE, y*TILE_SIZE, tick);
                }
        }

        private void renderTile(GuiGraphics g, Tile t, int px, int py, long tick) {
            // 液体：彩色填充 + 波浪动画
            if (t == Tile.WATER || t == Tile.LAVA) {
                int base = t == Tile.WATER ? 0xFF1155CC : 0xFFCC3300;
                float wave = 0.82f + 0.18f * (float)Math.sin(tick * 0.12 + px * 0.25);
                int r = (int)(((base>>16)&0xFF)*wave);
                int gg= (int)(((base>>8)&0xFF)*wave);
                int b = (int)((base&0xFF)*wave);
                g.fill(px, py, px+TILE_SIZE, py+TILE_SIZE, 0xFF000000|(r<<16)|(gg<<8)|b);
                // 波纹高光
                int hlY = py + (int)(tick/4 % TILE_SIZE);
                g.fill(px, hlY, px+TILE_SIZE, hlY+2,
                        t==Tile.WATER ? 0x3366AAFF : 0x33FFAA44);
                // 危险图标（半透明骷髅/感叹号用简单形状代替）
                g.fill(px+6, py+3, px+10, py+5, 0x99FFFFFF);
                g.fill(px+7, py+6, px+9, py+12, 0x99FFFFFF);
                return;
            }
            // 钻石：浮动 + 十字闪光
            if (t == Tile.DIAMOND) {
                int bob = (int)(Math.sin(tick * 0.1 + px * 0.4) * 2.5);
                g.blit(TEX_DIAMOND, px+2, py+2+bob, 0, 0, TILE_SIZE-4, TILE_SIZE-4, TILE_SIZE, TILE_SIZE);
                int sa = (int)(80 + 60*Math.sin(tick*0.12));
                g.fill(px+7, py+bob,    px+9, py+TILE_SIZE+bob, (sa<<24)|0x00CCFFFF);
                g.fill(px,   py+7+bob,  px+TILE_SIZE, py+9+bob, (sa<<24)|0x00CCFFFF);
                return;
            }
            // 常规纹理
            ResourceLocation tex = switch (t) {
                case STONE, ICE_BLOCK -> t==Tile.ICE_BLOCK ? TEX_ICE : TEX_STONE;
                case DIRT    -> TEX_DIRT;
                case GRASS   -> TEX_GRASS;
                case PLANKS  -> TEX_PLANKS;
                default      -> TEX_STONE;
            };
            g.blit(tex, px, py, 0, 0, TILE_SIZE, TILE_SIZE, TILE_SIZE, TILE_SIZE);
            // 边缘光影（AO 感）
            g.fill(px,            py,            px+TILE_SIZE, py+1,          0x28FFFFFF);
            g.fill(px,            py,            px+1,          py+TILE_SIZE,  0x20FFFFFF);
            g.fill(px,            py+TILE_SIZE-1,px+TILE_SIZE, py+TILE_SIZE,  0x28000000);
            g.fill(px+TILE_SIZE-1,py,            px+TILE_SIZE,  py+TILE_SIZE,  0x20000000);
        }
    }
}