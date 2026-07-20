package com.wzz.momoi_game_console.client.screens;

import com.wzz.momoi_game_console.client.screens.games.LanMultiplayerScreen;
import com.wzz.momoi_game_console.init.ModNetworks;
import com.wzz.momoi_game_console.network.MultiplayerGamePacket;
import com.wzz.momoi_game_console.util.GameRenderHelper;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.api.distmarker.OnlyIn;
import org.lwjgl.glfw.GLFW;

import java.util.*;

/**
 * 多人游戏联机大厅
 * 支持：玩家 vs AI、本地双人、局域网对战
 */
@OnlyIn(Dist.CLIENT)
public class MultiplayerLobbyScreen extends Screen {

    // ─── 支持多人的游戏列表 ───
    public record MultiplayerGame(String id, String name, String icon, boolean supportsAI, boolean supportsLocal, boolean supportsLAN) {}

    private static final List<MultiplayerGame> MP_GAMES = List.of(
            new MultiplayerGame("gomoku",    "五子棋",    "⚫", true, true, true),
            new MultiplayerGame("go",        "围棋",      "⚪", true, true, true),
            new MultiplayerGame("tictactoe", "井字棋",    "✖",  true, true, true),
            new MultiplayerGame("chess",     "中国象棋",  "♚",  true, true, true),
            new MultiplayerGame("icefire",   "森林冰火人","❄",  false, true, true),
            new MultiplayerGame("colorchase","颜色追逐",  "🎨", true, true, true),
            new MultiplayerGame("landlord",  "斗地主",    "🃏\uFE0F", true, false, true),
            new MultiplayerGame("breakout",  "打砖块",    "🧱", false, true, false),
            new MultiplayerGame("maze",      "迷宫",      "🌀", false, true, false),
            new MultiplayerGame("snake",     "贪吃蛇",    "🐍", false, true, false),
            new MultiplayerGame("wchess",    "国际象棋",  "♟", true, true, true)
    );

    // ─── 状态 ───
    private enum LobbyState { GAME_SELECT, MODE_SELECT, PLAYER_LIST, PLAYER_LIST_MULTI, WAITING }
    private LobbyState state = LobbyState.GAME_SELECT;
    private int selectedGameIndex = 0;
    private int hoveredGameIndex = -1;
    private int hoveredModeIndex = -1;
    private int hoveredPlayerIndex = -1;
    private long tickCount = 0;

    // ─── 在线玩家 ───
    private final List<PlayerInfo> onlinePlayers = new ArrayList<>();
    private long lastRefreshTime = 0;

    public record PlayerInfo(UUID uuid, String name) {}

    // ─── 等待状态 ───
    private String waitingMessage = "";
    private UUID invitedPlayer = null;

    // ─── 斗地主三人联机：需要选两个玩家 ───
    private final Set<UUID> selectedLanPeers = new LinkedHashSet<>();  // 最多2个
    private int pendingAccepts = 0;       // 已接受的数量
    private int expectedAccepts = 1;      // 期待的接受数量（斗地主=2，其他=1）
    private final Map<UUID, String> acceptedPeers = new LinkedHashMap<>(); // uuid->name

    // ─── 分页 ───
    private int playerListPage = 0;
    private static final int PLAYERS_PER_PAGE = 8;

    // ─── 收到的邀请 ───
    private static MultiplayerGamePacket pendingInvite = null;
    private static String inviterName = null;

    public MultiplayerLobbyScreen() {
        super(Component.literal("联机大厅"));
    }

    /** 由网络包处理器调用 */
    public static void handleIncomingPacket(MultiplayerGamePacket packet) {
        Minecraft mc = Minecraft.getInstance();
        switch (packet.getType()) {
            case INVITE -> {
                pendingInvite = packet;
                inviterName = packet.getData();
                // 如果当前不在大厅，显示通知
                if (!(mc.screen instanceof MultiplayerLobbyScreen)) {
                    mc.execute(() -> {
                        if (mc.player != null) {
                            mc.player.displayClientMessage(
                                    Component.literal("§e[游戏机] §f" + inviterName + " §a邀请你玩 §f" + packet.getGameId() + " §7(打开游戏机查看)"),
                                    false
                            );
                        }
                    });
                }
            }
            case ACCEPT_INVITE -> {
                mc.execute(() -> {
                    if (mc.screen instanceof MultiplayerLobbyScreen lobby) {
                        lobby.onInviteAccepted(packet);
                    }
                });
            }
            case DECLINE_INVITE -> {
                mc.execute(() -> {
                    if (mc.screen instanceof MultiplayerLobbyScreen lobby) {
                        lobby.state = LobbyState.MODE_SELECT;
                        lobby.waitingMessage = "对方拒绝了邀请";
                    }
                });
            }
            case PLAYER_LIST -> {
                mc.execute(() -> {
                    if (mc.screen instanceof MultiplayerLobbyScreen lobby) {
                        lobby.parsePlayerList(packet.getData());
                    }
                });
            }
            // ─── 游戏内网络包路由（通用，任何实现 LanMultiplayerScreen 的 Screen 均可接收）
            case GAME_MOVE -> {
                mc.execute(() -> {
                    if (mc.screen instanceof LanMultiplayerScreen s)
                        s.onRemoteMove(packet.getData());
                });
            }
            case GAME_STATE_SYNC -> {
                mc.execute(() -> {
                    if (mc.screen instanceof LanMultiplayerScreen s)
                        s.onRemoteState(packet.getData());
                });
            }
            case GAME_OVER -> {
                mc.execute(() -> {
                    if (mc.screen instanceof LanMultiplayerScreen s)
                        s.onRemoteGameOver(packet.getData());
                });
            }
        }
    }

    private void parsePlayerList(String data) {
        onlinePlayers.clear();
        if (data == null || data.isEmpty()) return;
        for (String entry : data.split(";")) {
            String[] parts = entry.split(",", 2);
            if (parts.length == 2) {
                try {
                    onlinePlayers.add(new PlayerInfo(UUID.fromString(parts[0]), parts[1]));
                } catch (Exception ignored) {}
            }
        }
    }

    private void requestPlayerList() {
        ModNetworks.PACKET_HANDLER.sendToServer(new MultiplayerGamePacket(
                MultiplayerGamePacket.PacketType.REQUEST_PLAYERS, null, "", ""
        ));
    }

    private void sendInvite(UUID target) {
        MultiplayerGame game = MP_GAMES.get(selectedGameIndex);
        String senderName = Minecraft.getInstance().player != null ?
                Minecraft.getInstance().player.getGameProfile().getName() : "???";
        ModNetworks.PACKET_HANDLER.sendToServer(new MultiplayerGamePacket(
                MultiplayerGamePacket.PacketType.INVITE, target, game.id, senderName
        ));
    }

    /** 斗地主：选好2个玩家后批量发邀请 */
    private void sendLandlordInvites() {
        MultiplayerGame game = MP_GAMES.get(selectedGameIndex);
        String senderName = Minecraft.getInstance().player != null ?
                Minecraft.getInstance().player.getGameProfile().getName() : "???";
        for (UUID uuid : selectedLanPeers) {
            ModNetworks.PACKET_HANDLER.sendToServer(new MultiplayerGamePacket(
                    MultiplayerGamePacket.PacketType.INVITE, uuid, game.id, senderName
            ));
        }
        invitedPlayer = null; // 斗地主用 selectedLanPeers 代替
        expectedAccepts = 2;
        pendingAccepts = 0;
        acceptedPeers.clear();
        state = LobbyState.WAITING;
        waitingMessage = "等待两位玩家接受邀请 (0/2)...";
    }

    private void onInviteAccepted(MultiplayerGamePacket packet) {
        // packet.getTargetPlayer() = 接受方 UUID
        // packet.getGameId()       = 游戏 id
        String gameId    = packet.getGameId();
        UUID accepterUuid = packet.getTargetPlayer();
        String accepterName = packet.getData();

        if ("landlord".equals(gameId) && expectedAccepts == 2) {
            // 斗地主三人联机：等够2人
            acceptedPeers.put(accepterUuid, accepterName);
            pendingAccepts = acceptedPeers.size();
            waitingMessage = "等待两位玩家接受邀请 (" + pendingAccepts + "/2)...";

            if (pendingAccepts >= 2) {
                // 两人都接受，启动游戏
                UUID[] peers = acceptedPeers.keySet().toArray(new UUID[0]);
                UUID p1 = peers[0], p2 = peers[1];
                Screen gs = new com.wzz.momoi_game_console.client.screens.games.landlord
                        .LandlordGameScreen(true, p1, p2);
                Minecraft.getInstance().setScreen(gs);
                waitingMessage = "";
                selectedLanPeers.clear();
                acceptedPeers.clear();
            }
        } else {
            // 普通单人邀请
            UUID clientUuid = accepterUuid;
            Screen gameScreen = launchGameForLAN(gameId, clientUuid);
            if (gameScreen != null) {
                Minecraft.getInstance().setScreen(gameScreen);
            } else {
                state = LobbyState.GAME_SELECT;
            }
            waitingMessage = "";
            invitedPlayer = null;
        }
    }

    @Override
    public void tick() {
        tickCount++;
        if ((state == LobbyState.PLAYER_LIST || state == LobbyState.PLAYER_LIST_MULTI)
                && tickCount - lastRefreshTime > 40) {
            requestPlayerList();
            lastRefreshTime = tickCount;
        }
    }

    @Override
    public void render(GuiGraphics g, int mx, int my, float pt) {
        int cx = width / 2;
        // 背景
        GameRenderHelper.fillGradientBackground(g, width, height, 0xFF0A0A18, 0xFF101030);
        GameRenderHelper.renderDecorativeLines(g, width, height, tickCount, 0x003355);

        // 标题
        GameRenderHelper.drawShadowedCenteredText(g, font, "§b联机大厅", cx, 10, 0x44CCFF, 2);
        GameRenderHelper.drawDivider(g, cx - 100, 30, 200, 0xFF2266AA, 0xFF224488);

        // 收到邀请时，挂起其他内容，全屏显示邀请弹窗
        if (pendingInvite != null) {
            renderInviteNotification(g, mx, my);
        } else {
            switch (state) {
                case GAME_SELECT -> renderGameSelect(g, mx, my);
                case MODE_SELECT -> renderModeSelect(g, mx, my);
                case PLAYER_LIST -> renderPlayerList(g, mx, my);
                case PLAYER_LIST_MULTI -> renderPlayerListMulti(g, mx, my);
                case WAITING     -> renderWaiting(g);
            }
            GameRenderHelper.drawBottomBar(g, font, width, height, "ESC 返回");
        }
    }

    private void renderGameSelect(GuiGraphics g, int mx, int my) {
        int cx = width / 2;
        g.drawCenteredString(font, "§f选择多人游戏", cx, 38, 0xCCCCCC);

        int startY = 55;
        int cardW = 220;
        int cardH = 24;
        hoveredGameIndex = -1;

        for (int i = 0; i < MP_GAMES.size(); i++) {
            MultiplayerGame game = MP_GAMES.get(i);
            int cardX = cx - cardW / 2;
            int cardY = startY + i * (cardH + 3);

            boolean hover = mx >= cardX && mx <= cardX + cardW && my >= cardY && my <= cardY + cardH;
            if (hover) hoveredGameIndex = i;

            int bg = hover ? 0xFF252555 : 0xFF1A1A38;
            g.fill(cardX, cardY, cardX + cardW, cardY + cardH, bg);
            if (hover) {
                g.fill(cardX, cardY, cardX + cardW, cardY + 1, 0xFF44AAFF);
            }
            g.fill(cardX, cardY, cardX + 3, cardY + cardH, 0xFF44AAFF);

            g.drawString(font, game.icon + " " + game.name, cardX + 8, cardY + 8, hover ? 0xFFFFFF : 0xBBBBBB);

            // 支持模式标签
            StringBuilder modes = new StringBuilder();
            if (game.supportsAI) modes.append("§aAI ");
            if (game.supportsLocal) modes.append("§e本地 ");
            if (game.supportsLAN) modes.append("§b联机");
            int mw = font.width(modes.toString());
            g.drawString(font, modes.toString(), cardX + cardW - mw - 5, cardY + 8, 0x888888);
        }
    }

    private void renderModeSelect(GuiGraphics g, int mx, int my) {
        int cx = width / 2;
        MultiplayerGame game = MP_GAMES.get(selectedGameIndex);
        g.drawCenteredString(font, game.icon + " " + game.name + " - 选择模式", cx, 38, 0xFFFFFF);

        if (!waitingMessage.isEmpty()) {
            g.drawCenteredString(font, "§e" + waitingMessage, cx, 52, 0xFFFF44);
        }

        int startY = 68;
        int btnW = 180;
        int btnH = 24;
        hoveredModeIndex = -1;
        int modeIdx = 0;

        if (game.supportsAI) {
            boolean h = drawModeButton(g, mx, my, cx - btnW/2, startY + modeIdx * 30, btnW, btnH,
                    "§f🤖 玩家 vs 人机", 0xFF2A4A14);
            if (h) hoveredModeIndex = 0;
            modeIdx++;
        }
        if (game.supportsLocal) {
            boolean h = drawModeButton(g, mx, my, cx - btnW/2, startY + modeIdx * 30, btnW, btnH,
                    "§f👥 本地双人", 0xFF4A3A14);
            if (h) hoveredModeIndex = 1;
            modeIdx++;
        }
        if (game.supportsLAN) {
            boolean h = drawModeButton(g, mx, my, cx - btnW/2, startY + modeIdx * 30, btnW, btnH,
                    "§f🌐 局域网对战", 0xFF143A4A);
            if (h) hoveredModeIndex = 2;
            modeIdx++;
        }

        // 返回按钮
        boolean backH = drawModeButton(g, mx, my, cx - 50, startY + modeIdx * 30 + 10, 100, 20,
                "§7◀ 返回", 0xFF222233);
        if (backH) hoveredModeIndex = 99;
    }

    private boolean drawModeButton(GuiGraphics g, int mx, int my, int x, int y, int w, int h, String text, int baseColor) {
        boolean hover = mx >= x && mx <= x + w && my >= y && my <= y + h;
        g.fill(x, y, x + w, y + h, hover ? GameRenderHelper.brighten(baseColor, 1.4f) : baseColor);
        if (hover) g.fill(x, y, x + w, y + 1, 0xFF88CC44);
        g.drawCenteredString(font, text, x + w / 2, y + (h - 8) / 2, hover ? 0xFFFFFF : 0xBBBBBB);
        return hover;
    }

    private void renderPlayerList(GuiGraphics g, int mx, int my) {
        int cx = width / 2;
        int totalPages = Math.max(1, (onlinePlayers.size() + PLAYERS_PER_PAGE - 1) / PLAYERS_PER_PAGE);
        if (playerListPage >= totalPages) playerListPage = totalPages - 1;
        g.drawCenteredString(font, "§b在线玩家  (" + (playerListPage + 1) + "/" + totalPages + ")", cx, 38, 0x44CCFF);

        hoveredPlayerIndex = -1;
        if (onlinePlayers.isEmpty()) {
            g.drawCenteredString(font, "§7没有找到其他在线玩家", cx, 70, 0x888888);
            g.drawCenteredString(font, "§7确保在局域网/服务器中有其他玩家", cx, 85, 0x666666);
        } else {
            int startIdx = playerListPage * PLAYERS_PER_PAGE;
            int endIdx = Math.min(startIdx + PLAYERS_PER_PAGE, onlinePlayers.size());
            int startY = 55;
            for (int displayIdx = startIdx; displayIdx < endIdx; displayIdx++) {
                int i = displayIdx - startIdx;
                PlayerInfo p = onlinePlayers.get(displayIdx);
                int cardX = cx - 100;
                int cardY = startY + i * 22;
                boolean hover = mx >= cardX && mx <= cardX + 200 && my >= cardY && my <= cardY + 20;
                if (hover) hoveredPlayerIndex = displayIdx;

                g.fill(cardX, cardY, cardX + 200, cardY + 20, hover ? 0xFF335566 : 0xFF1A2A33);
                g.drawString(font, "§f" + p.name, cardX + 5, cardY + 6, 0xFFFFFF);
                if (hover) {
                    g.drawString(font, "§a点击邀请", cardX + 150, cardY + 6, 0x44FF44);
                }
            }
        }

        // 翻页按钮
        if (totalPages > 1) {
            if (playerListPage > 0) {
                GameRenderHelper.drawSecondaryButton(g, font, "◀ 上页", cx - 110, height - 40, 60, 18, mx, my);
            }
            if (playerListPage < totalPages - 1) {
                GameRenderHelper.drawSecondaryButton(g, font, "下页 ▶", cx + 50, height - 40, 60, 18, mx, my);
            }
        }
        // 返回按钮
        GameRenderHelper.drawSecondaryButton(g, font, "◀ 返回", cx - 40, height - 40, 80, 18, mx, my);
    }

    /** 斗地主三人局域网：选2个玩家（支持翻页） */
    private void renderPlayerListMulti(GuiGraphics g, int mx, int my) {
        int cx = width / 2;
        int totalPages = Math.max(1, (onlinePlayers.size() + PLAYERS_PER_PAGE - 1) / PLAYERS_PER_PAGE);
        if (playerListPage >= totalPages) playerListPage = totalPages - 1;
        g.drawCenteredString(font, "§b斗地主 - 选择两位对手  (" + (playerListPage+1) + "/" + totalPages + ")", cx, 38, 0x44CCFF);
        g.drawCenteredString(font, "§7已选 §f" + selectedLanPeers.size() + " §7/ 2 人", cx, 50, 0xAAAAAA);

        hoveredPlayerIndex = -1;
        if (onlinePlayers.isEmpty()) {
            g.drawCenteredString(font, "§7没有其他在线玩家", cx, 75, 0x888888);
        } else {
            int startIdx = playerListPage * PLAYERS_PER_PAGE;
            int endIdx = Math.min(startIdx + PLAYERS_PER_PAGE, onlinePlayers.size());
            int startY = 62;
            for (int displayIdx = startIdx; displayIdx < endIdx; displayIdx++) {
                int i = displayIdx - startIdx;
                PlayerInfo p = onlinePlayers.get(displayIdx);
                int cardX = cx - 110, cardY = startY + i * 24;
                boolean hover = mx >= cardX && mx <= cardX + 220 && my >= cardY && my <= cardY + 22;
                boolean selected2 = selectedLanPeers.contains(p.uuid());
                if (hover) hoveredPlayerIndex = displayIdx;

                int bg = selected2 ? 0xFF1A4A2A : (hover ? 0xFF335566 : 0xFF1A2A33);
                g.fill(cardX, cardY, cardX + 220, cardY + 22, bg);
                if (selected2) g.fill(cardX, cardY, cardX + 3, cardY + 22, 0xFF44FF88);
                g.drawString(font, (selected2 ? "§a✔ " : "§7  ") + p.name(), cardX + 8, cardY + 7, 0xFFFFFF);
                g.drawString(font, selected2 ? "§a已选" : "§7点击选择", cardX + 170, cardY + 7, selected2 ? 0x44FF88 : 0x666666);
            }
        }

        // 翻页按钮
        if (totalPages > 1) {
            if (playerListPage > 0) {
                GameRenderHelper.drawSecondaryButton(g, font, "◀ 上页", cx - 140, height - 55, 60, 18, mx, my);
            }
            if (playerListPage < totalPages - 1) {
                GameRenderHelper.drawSecondaryButton(g, font, "下页 ▶", cx + 80, height - 55, 60, 18, mx, my);
            }
        }

        // 确认按钮（凑够2人才亮）
        boolean ready = selectedLanPeers.size() == 2;
        int btnY = height - 55;
        int bg = ready ? 0xFF1A5500 : 0xFF222222;
        int hbg = ready ? 0xFF33AA00 : 0xFF333333;
        boolean btnHov = mx>=cx-60&&mx<=cx+60&&my>=btnY&&my<=btnY+22;
        g.fill(cx-61,btnY-1,cx+61,btnY+23, ready&&btnHov?0xFF00AAFF:0xFF1E3A5F);
        g.fill(cx-60,btnY,cx+60,btnY+22, ready&&btnHov?hbg:bg);
        g.drawCenteredString(font, ready?"§f发送邀请 ▶":"§7请选满2人", cx, btnY+7, ready?0xFFFFFF:0x666666);

        GameRenderHelper.drawSecondaryButton(g, font, "◀ 返回", cx - 40, height - 28, 80, 18, mx, my);
    }

    private void renderWaiting(GuiGraphics g) {
        int cx = width / 2, cy = height / 2;
        // 动画点
        String dots = ".".repeat((int)(tickCount / 10 % 4));
        g.drawCenteredString(font, "§e" + waitingMessage + dots, cx, cy - 10, 0xFFFF44);
        GameRenderHelper.drawSecondaryButton(g, font, "取消", cx - 40, cy + 10, 80, 18, 0, 0);
    }

    private void renderInviteNotification(GuiGraphics g, int mx, int my) {
        int cx = width / 2, cy = height / 2;

        // 半透明全屏遮罩
        g.fill(0, 0, width, height, 0xAA000022);

        // 弹窗主体
        int nw = 280, nh = 90;
        int nx = cx - nw / 2, ny = cy - nh / 2;
        g.fill(nx - 2, ny - 2, nx + nw + 2, ny + nh + 2, 0xFF44AAFF); // 外发光边框
        g.fill(nx, ny, nx + nw, ny + nh, 0xFF0A1A3A);

        g.drawCenteredString(font, "§b📩 收到游戏邀请", cx, ny + 8, 0x44CCFF);
        g.drawCenteredString(font, "§e" + inviterName + " §f邀请你玩 §b" + pendingInvite.getGameId(), cx, ny + 24, 0xFFFFFF);

        // 接受按钮（绿色）
        GameRenderHelper.drawPrimaryButton(g, font, "✔ 接受", nx + 20, ny + nh - 28, 110, 22, mx, my);
        // 拒绝按钮（红色风格）
        GameRenderHelper.drawButton(g, font, "✘ 拒绝", nx + nw - 130, ny + nh - 28, 110, 22, mx, my,
                0xFF3A1010, 0xFF662222, 0xFFCC4444);
    }

    @Override
    public boolean mouseClicked(double mx, double my, int btn) {
        int cx = width / 2;

        // 邀请通知点击（新坐标与renderInviteNotification一致）
        if (pendingInvite != null) {
            int cx2 = width / 2, cy2 = height / 2;
            int nw = 280, nh = 90;
            int nx = cx2 - nw / 2, ny = cy2 - nh / 2;

            // 接受按钮：nx+20, ny+nh-28, 宽110, 高22
            if (mx >= nx + 20 && mx <= nx + 130 && my >= ny + nh - 28 && my <= ny + nh - 6) {
                String gameId    = pendingInvite.getGameId();
                UUID hostUuid    = pendingInvite.getTargetPlayer(); // 服务端转发后变为HOST的UUID
                ModNetworks.PACKET_HANDLER.sendToServer(new MultiplayerGamePacket(
                        MultiplayerGamePacket.PacketType.ACCEPT_INVITE,
                        hostUuid, gameId, ""
                ));
                pendingInvite = null;
                // 被邀请方作为 CLIENT 直接启动游戏
                // CLIENT 侧：以 isHost=false 启动对应联机实例
                Screen clientScreen = switch (gameId) {
                    case "icefire"    -> new com.wzz.momoi_game_console.client.screens.games.IceFireGameScreen(false, hostUuid);
                    case "gomoku"     -> new com.wzz.momoi_game_console.client.screens.games.GomokuScreen(false, hostUuid);
                    case "tictactoe"  -> new com.wzz.momoi_game_console.client.screens.games.tictactoe.TicTacToeScreen(false, hostUuid);
                    case "wchess"     -> new com.wzz.momoi_game_console.client.screens.games.WesternChessScreen(false, hostUuid);
                    case "colorchase" -> new com.wzz.momoi_game_console.client.screens.games.ColorChaseGameScreen(false, hostUuid);
                    case "chess"      -> new com.wzz.momoi_game_console.client.screens.games.ChessGameScreen(false, hostUuid);
                    case "go"         -> new com.wzz.momoi_game_console.client.screens.games.gogame.GoGameScreen(false, hostUuid);
                    // 斗地主CLIENT：等HOST推送初始状态（含玩家索引）
                    case "landlord"   -> new com.wzz.momoi_game_console.client.screens.games.landlord.LandlordGameScreen(false, hostUuid);
                    default           -> null;
                };
                if (clientScreen != null) {
                    Minecraft.getInstance().setScreen(clientScreen);
                } else {
                    state = LobbyState.WAITING;
                    waitingMessage = "已接受邀请，等待 " + inviterName + " 开始 " + gameId + "...";
                }
                return true;
            }

            // 拒绝按钮：nx+nw-130, ny+nh-28, 宽110, 高22
            if (mx >= nx + nw - 130 && mx <= nx + nw - 20 && my >= ny + nh - 28 && my <= ny + nh - 6) {
                ModNetworks.PACKET_HANDLER.sendToServer(new MultiplayerGamePacket(
                        MultiplayerGamePacket.PacketType.DECLINE_INVITE,
                        pendingInvite.getTargetPlayer(),
                        pendingInvite.getGameId(), ""
                ));
                pendingInvite = null;
                return true;
            }
            return true; // 弹窗显示时屏蔽所有背景点击
        }

        switch (state) {
            case GAME_SELECT -> {
                if (hoveredGameIndex >= 0) {
                    selectedGameIndex = hoveredGameIndex;
                    state = LobbyState.MODE_SELECT;
                    waitingMessage = "";
                    return true;
                }
            }
            case MODE_SELECT -> {
                if (hoveredModeIndex == 0) {
                    launchGame("ai");
                    return true;
                } else if (hoveredModeIndex == 1) {
                    launchGame("local");
                    return true;
                } else if (hoveredModeIndex == 2) {
                    // 局域网 - 斗地主需要选2人
                    MultiplayerGame curGame = MP_GAMES.get(selectedGameIndex);
                    if ("landlord".equals(curGame.id())) {
                        selectedLanPeers.clear();
                        playerListPage = 0;
                        state = LobbyState.PLAYER_LIST_MULTI;
                    } else {
                        playerListPage = 0;
                        state = LobbyState.PLAYER_LIST;
                    }
                    requestPlayerList();
                    lastRefreshTime = tickCount;
                    return true;
                } else if (hoveredModeIndex == 99) {
                    state = LobbyState.GAME_SELECT;
                    return true;
                }
            }
            case PLAYER_LIST -> {
                // 翻页按钮
                int totalPages = Math.max(1, (onlinePlayers.size() + PLAYERS_PER_PAGE - 1) / PLAYERS_PER_PAGE);
                if (totalPages > 1) {
                    if (playerListPage > 0 && mx >= cx - 110 && mx <= cx - 50 && my >= height - 40 && my <= height - 22) {
                        playerListPage--; return true;
                    }
                    if (playerListPage < totalPages - 1 && mx >= cx + 50 && mx <= cx + 110 && my >= height - 40 && my <= height - 22) {
                        playerListPage++; return true;
                    }
                }
                if (hoveredPlayerIndex >= 0 && hoveredPlayerIndex < onlinePlayers.size()) {
                    UUID target = onlinePlayers.get(hoveredPlayerIndex).uuid();
                    sendInvite(target);
                    invitedPlayer = target;
                    expectedAccepts = 1;
                    pendingAccepts = 0;
                    acceptedPeers.clear();
                    state = LobbyState.WAITING;
                    waitingMessage = "等待对方接受邀请...";
                    return true;
                }
                if (mx >= cx - 40 && mx <= cx + 40 && my >= height - 40 && my <= height - 22) {
                    playerListPage = 0;
                    state = LobbyState.MODE_SELECT;
                    return true;
                }
            }
            case PLAYER_LIST_MULTI -> {
                // 翻页按钮
                int totalPages = Math.max(1, (onlinePlayers.size() + PLAYERS_PER_PAGE - 1) / PLAYERS_PER_PAGE);
                if (totalPages > 1) {
                    if (playerListPage > 0 && mx >= cx - 140 && mx <= cx - 80 && my >= height - 55 && my <= height - 37) {
                        playerListPage--; return true;
                    }
                    if (playerListPage < totalPages - 1 && mx >= cx + 80 && mx <= cx + 140 && my >= height - 55 && my <= height - 37) {
                        playerListPage++; return true;
                    }
                }
                if (hoveredPlayerIndex >= 0 && hoveredPlayerIndex < onlinePlayers.size()) {
                    UUID uuid = onlinePlayers.get(hoveredPlayerIndex).uuid();
                    if (selectedLanPeers.contains(uuid)) {
                        selectedLanPeers.remove(uuid);
                    } else if (selectedLanPeers.size() < 2) {
                        selectedLanPeers.add(uuid);
                    }
                    return true;
                }
                int btnY = height - 55;
                if (selectedLanPeers.size() == 2
                        && mx >= cx - 60 && mx <= cx + 60
                        && my >= btnY && my <= btnY + 22) {
                    sendLandlordInvites();
                    return true;
                }
                if (mx >= cx - 40 && mx <= cx + 40 && my >= height - 28 && my <= height - 10) {
                    selectedLanPeers.clear();
                    playerListPage = 0;
                    state = LobbyState.MODE_SELECT;
                    return true;
                }
            }
            case WAITING -> {
                if (mx >= cx - 40 && mx <= cx + 40 && my >= height / 2 + 10 && my <= height / 2 + 28) {
                    state = LobbyState.MODE_SELECT;
                    return true;
                }
            }
        }
        return super.mouseClicked(mx, my, btn);
    }

    private void launchGame(String mode) {
        MultiplayerGame game = MP_GAMES.get(selectedGameIndex);
        if ("lan".equals(mode)) {
            // LAN HOST 启动：只有 icefire 有专属 HOST 构造，其他游戏复用单机版
            Screen s = switch (game.id) {
                case "icefire" -> new com.wzz.momoi_game_console.client.screens.games.IceFireGameScreen(true, invitedPlayer);
                default        -> launchGameForLAN(game.id, invitedPlayer);
            };
            if (s != null) Minecraft.getInstance().setScreen(s);
            return;
        }
        Screen gameScreen = switch (game.id) {
            case "gomoku"    -> new com.wzz.momoi_game_console.client.screens.games.GomokuScreen();
            case "go"        -> new com.wzz.momoi_game_console.client.screens.games.gogame.GoGameScreen(
                    new com.wzz.momoi_game_console.client.screens.games.gogame.GoGame());
            case "tictactoe" -> new com.wzz.momoi_game_console.client.screens.games.tictactoe.TicTacToeScreen(
                    com.wzz.momoi_game_console.client.screens.games.tictactoe.TicTacToeGame.GameMode.SINGLE_PLAYER);
            case "chess"     -> new com.wzz.momoi_game_console.client.screens.games.ChessGameScreen();
            case "icefire"   -> new com.wzz.momoi_game_console.client.screens.games.IceFireGameScreen();
            case "colorchase"-> new com.wzz.momoi_game_console.client.screens.games.ColorChaseGameScreen();
            case "landlord"  -> new com.wzz.momoi_game_console.client.screens.games.landlord.LandlordGameScreen();
            case "breakout"  -> new com.wzz.momoi_game_console.client.screens.games.BreakoutScreen();
            case "maze"      -> new com.wzz.momoi_game_console.client.screens.games.MazeGameScreen();
            case "snake"     -> new com.wzz.momoi_game_console.client.screens.games.SnakeGameScreen();
            case "wchess"    -> new com.wzz.momoi_game_console.client.screens.games.WesternChessScreen();
            default -> null;
        };
        if (gameScreen != null) Minecraft.getInstance().setScreen(gameScreen);
    }

    /**
     * 为 LAN HOST 返回联机游戏 Screen。
     * 每个支持联机的游戏都有 (boolean isHost, UUID remote) 构造器。
     */
    private Screen launchGameForLAN(String gameId, UUID remotePeer) {
        return switch (gameId) {
            case "icefire"    -> new com.wzz.momoi_game_console.client.screens.games.IceFireGameScreen(true, remotePeer);
            case "gomoku"     -> new com.wzz.momoi_game_console.client.screens.games.GomokuScreen(true, remotePeer);
            case "tictactoe"  -> new com.wzz.momoi_game_console.client.screens.games.tictactoe.TicTacToeScreen(true, remotePeer);
            case "wchess"     -> new com.wzz.momoi_game_console.client.screens.games.WesternChessScreen(true, remotePeer);
            case "colorchase" -> new com.wzz.momoi_game_console.client.screens.games.ColorChaseGameScreen(true, remotePeer);
            // ★ Bug修复：中国象棋和围棋缺少 LAN 分支，导致邀请方接受后返回游戏选择界面
            case "chess"      -> new com.wzz.momoi_game_console.client.screens.games.ChessGameScreen(true, remotePeer);
            case "go"         -> new com.wzz.momoi_game_console.client.screens.games.gogame.GoGameScreen(true, remotePeer);
            default           -> null;
        };
    }

    @Override
    public boolean keyPressed(int key, int scan, int mods) {
        if (key == GLFW.GLFW_KEY_ESCAPE) {
            switch (state) {
                case MODE_SELECT -> { state = LobbyState.GAME_SELECT; return true; }
                case PLAYER_LIST -> { state = LobbyState.MODE_SELECT; return true; }
                case PLAYER_LIST_MULTI -> { selectedLanPeers.clear(); state = LobbyState.MODE_SELECT; return true; }
                case WAITING     -> { state = LobbyState.MODE_SELECT; return true; }
                default -> { Minecraft.getInstance().setScreen(new GameSelectorScreen()); return true; }
            }
        }
        return super.keyPressed(key, scan, mods);
    }

    @Override
    public boolean isPauseScreen() { return false; }
}