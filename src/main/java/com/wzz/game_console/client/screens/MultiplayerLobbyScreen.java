package com.wzz.game_console.client.screens;

import com.wzz.game_console.client.screens.games.LanMultiplayerScreen;
import com.wzz.game_console.init.ModNetworks;
import com.wzz.game_console.network.MultiplayerGamePacket;
import com.wzz.game_console.network.MultiplayerInviteAttempt;
import com.wzz.game_console.util.GameRenderHelper;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.api.distmarker.OnlyIn;
import org.lwjgl.glfw.GLFW;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 多人游戏联机大厅
 * 支持：玩家 vs AI、本地双人、局域网对战
 */
@OnlyIn(Dist.CLIENT)
public class MultiplayerLobbyScreen extends Screen {

    private static final Logger LOGGER = LoggerFactory.getLogger(MultiplayerLobbyScreen.class);

    // ─── 支持多人的游戏列表 ───
    public record MultiplayerGame(String id, String name, String icon, boolean supportsAI, boolean supportsLocal, boolean supportsLAN) {}

    private static final List<MultiplayerGame> MP_GAMES = List.of(
            new MultiplayerGame("gomoku",    "五子棋",    "⚫", true, true, true),
            new MultiplayerGame("go",        "围棋",      "⚪", true, true, true),
            new MultiplayerGame("tictactoe", "井字棋",    "✖",  true, true, true),
            new MultiplayerGame("chess",     "中国象棋",  "♚",  true, true, true),
            new MultiplayerGame("icefire",   "森林冰火人","❄",  false, true, true),
            new MultiplayerGame("colorchase","颜色追逐",  "🎨", true, true, true),
            new MultiplayerGame("landlord",  "斗地主",    "🃏\uFE0F", true, true, true),
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
    private String invitedGameId = null;
    private UUID invitedAttemptNonce = null;

    // ─── 斗地主三人联机：需要选两个玩家 ───
    private final Set<UUID> selectedLanPeers = Collections.synchronizedSet(new LinkedHashSet<>());  // 最多2个，线程安全
    private int pendingAccepts = 0;       // 已接受的数量
    private int expectedAccepts = 1;      // 期待的接受数量（斗地主=2，其他=1）
    private final Map<UUID, String> acceptedPeers = new ConcurrentHashMap<>(); // uuid->name，线程安全
    private UUID lanHostUuid = null;      // 发起邀请时记录主机自身UUID

    // ─── 等待超时机制 ───
    /** -1 means the lobby is not currently waiting for invite responses. */
    private long waitingStartTick = -1L;
    private static final long WAIT_TIMEOUT_TICKS = 600; // 30秒超时

    // ─── 分页 ───
    private int playerListPage = 0;
    private static final int PLAYERS_PER_PAGE = 8;
    private int gamesPage = 0;                                            // 游戏选择列表当前页
    private static final int GAMES_PER_PAGE = 7;                          // 1080p 自动缩放下一页最多画 7 张卡

    // ─── 收到的邀请 ───
    private static MultiplayerGamePacket pendingInvite = null;
    private static String inviterName = null;
    private static long pendingInviteArrivalMs = 0;                       // 邀请到达时间戳
    private static final long INVITE_TIMEOUT_MS = 60_000;                 // 邀请 60 秒过期
    // 已接受邀请的主机与时间戳：候选者接受邀请后会切到对局界面等待开局（pendingInvite 已清空），
    // 主机此后发来的 INVITE_CANCELLED 需要靠它做兜底通知（见 handleIncomingPacket）
    private static UUID acceptedInviteHostUuid = null;
    private static String acceptedInviteGameId = null;
    private static UUID acceptedInviteNonce = null;
    private static long acceptedInviteMs = 0;
    /** 兜底通知有效窗口：主机等待上限 30 秒，60 秒足以覆盖其流产通知，又防过期报文误伤 */
    private static final long ACCEPTED_INVITE_VALID_MS = 60_000;
    /** 等待动画 "." 帧表(预计算,避免 renderWaiting 每帧 repeat 分配) */
    private static final String[] WAIT_DOTS = { "", ".", "..", "..." };
    // 说明：主机不在大厅时到达的 ACCEPT_INVITE 不再缓存回放。
    // 原因：回放发生在新建的大厅实例上，邀请上下文（invitedPlayer、selectedLanPeers 等）
    // 无法随包可靠恢复，回放会被来源校验全部拒绝（形同虚设），且误恢复上下文反而
    // 可能被伪造包利用。故降级为明确的 LOGGER.warn + 丢弃，见 ACCEPT_INVITE 分支。

    private static MultiplayerGame findGame(String gameId) {
        if (gameId == null || gameId.isBlank()) return null;
        for (MultiplayerGame game : MP_GAMES) {
            if (game.id().equals(gameId) && game.supportsLAN()) return game;
        }
        return null;
    }

    private static void clearAcceptedInvite(UUID sender, String gameId) {
        if (Objects.equals(acceptedInviteHostUuid, sender)
                && Objects.equals(acceptedInviteGameId, gameId)) {
            acceptedInviteHostUuid = null;
            acceptedInviteGameId = null;
            acceptedInviteNonce = null;
            acceptedInviteMs = 0;
        }
    }

    public MultiplayerLobbyScreen() {
        super(Component.literal("联机大厅"));
        // 进入大厅时清理已过期的邀请
        if (pendingInvite != null && System.currentTimeMillis() - pendingInviteArrivalMs > INVITE_TIMEOUT_MS) {
            pendingInvite = null;
            inviterName = null;
        }
    }

    /** 由网络包处理器调用 */
    public static void handleIncomingPacket(MultiplayerGamePacket packet) {
        Minecraft mc = Minecraft.getInstance();
        switch (packet.getType()) {
            case INVITE -> {
                UUID sender = packet.getSenderUuid();
                UUID nonce = MultiplayerInviteAttempt.parse(packet.getData());
                UUID self = mc.player != null ? mc.player.getUUID() : null;
                if (sender == null || sender.equals(self) || nonce == null || findGame(packet.getGameId()) == null) {
                    LOGGER.warn("[游戏机联机] 忽略非法邀请 sender={} gameId={}", sender, packet.getGameId());
                    break;
                }
                if (pendingInvite != null
                        && (!Objects.equals(pendingInvite.getSenderUuid(), sender)
                        || !Objects.equals(pendingInvite.getGameId(), packet.getGameId())
                        || !Objects.equals(MultiplayerInviteAttempt.parse(pendingInvite.getData()), nonce))) {
                    LOGGER.warn("[游戏机联机] 已有待处理邀请，拒绝覆盖 sender={} gameId={}", sender, packet.getGameId());
                    ModNetworks.PACKET_HANDLER.sendToServer(new MultiplayerGamePacket(
                            MultiplayerGamePacket.PacketType.DECLINE_INVITE,
                            sender, packet.getGameId(), MultiplayerInviteAttempt.encode(nonce)));
                    break;
                }
                pendingInvite = packet;
                // 邀请者名字只使用服务端盖章字段；data 保留给邀请尝试 nonce。
                inviterName = packet.getSenderName() != null && !packet.getSenderName().isEmpty()
                        ? packet.getSenderName() : "对方";
                pendingInviteArrivalMs = System.currentTimeMillis();
                // 如果当前不在大厅，显示通知
                if (!(mc.screen instanceof MultiplayerLobbyScreen)) {
                    mc.execute(() -> {
                        if (mc.player != null) {
                            mc.player.displayClientMessage(
                                    Component.literal("[游戏机] " + inviterName + " 邀请你玩 " + packet.getGameId() + " (打开游戏机查看)"),
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
                    } else {
                        // 主机不在大厅界面：邀请上下文（invitedPlayer、selectedLanPeers 等）无法
                        // 随包可靠恢复，缓存回放会被来源校验全部拒绝且存在被伪造包利用的风险，
                        // 故明确记录日志后丢弃，不做静默处理。
                        LOGGER.warn("[游戏机联机] 收到 ACCEPT_INVITE 但主机不在大厅界面，邀请上下文不可恢复，已丢弃（来自 {}）",
                                packet.getSenderName());
                    }
                });
            }
            case INVITE_CANCELLED -> {
                // 主机取消/超时/离开大厅：被邀者清除待处理邀请并提示
                mc.execute(() -> {
                    // 仅当取消方确实是当前待处理邀请的发起者时才清除并提示，
                    // 防止伪造包顶掉正常邀请、或无关报文打扰玩家
                    if (pendingInvite != null
                            && Objects.equals(pendingInvite.getSenderUuid(), packet.getSenderUuid())
                            && Objects.equals(pendingInvite.getGameId(), packet.getGameId())
                            && Objects.equals(MultiplayerInviteAttempt.parse(pendingInvite.getData()),
                            MultiplayerInviteAttempt.parse(packet.getData()))) {
                        pendingInvite = null;
                        inviterName = null;
                        if (mc.player != null) {
                            mc.player.displayClientMessage(
                                    Component.literal("[游戏机] 邀请已取消/超时"), false);
                        }
                        return;
                    }
                    // ★ Bug修复：兜底——候选者已接受邀请并切到对局界面等待开局时，
                    //   pendingInvite 已在接受时清空，上面的分支不会命中，主机流产
                    //   会让玩家永远停在"等待游戏开始"界面。此处校验取消方是当初
                    //   接受邀请的主机且仍在有效窗口内，防止伪造/过期报文误伤；
                    //   回到大厅并用 chat 提示（沿用"邀请已取消/超时"的提示机制）
                    if (acceptedInviteHostUuid != null
                            && Objects.equals(acceptedInviteHostUuid, packet.getSenderUuid())
                            && Objects.equals(acceptedInviteGameId, packet.getGameId())
                            && MultiplayerInviteAttempt.matches(packet.getData(), acceptedInviteNonce)
                            && System.currentTimeMillis() - acceptedInviteMs <= ACCEPTED_INVITE_VALID_MS
                            && !(mc.screen instanceof MultiplayerLobbyScreen)) {
                        acceptedInviteHostUuid = null;
                        acceptedInviteGameId = null;
                        acceptedInviteNonce = null;
                        acceptedInviteMs = 0;
                        mc.setScreen(new MultiplayerLobbyScreen());
                        if (mc.player != null) {
                            mc.player.displayClientMessage(
                                    Component.literal("[游戏机] 主机已取消对局"), false);
                        }
                    }
                });
            }
            case DECLINE_INVITE -> {
                mc.execute(() -> {
                    if (mc.screen instanceof MultiplayerLobbyScreen lobby) {
                        UUID from = packet.getSenderUuid();
                        if (lobby.state != LobbyState.WAITING || lobby.invitedGameId == null
                                || !lobby.invitedGameId.equals(packet.getGameId())
                                || !MultiplayerInviteAttempt.matches(packet.getData(), lobby.invitedAttemptNonce)) {
                            LOGGER.warn("[游戏机联机] 忽略与当前邀请不匹配的拒绝消息 sender={} gameId={}",
                                    from, packet.getGameId());
                            return;
                        }
                        // 斗地主批量邀请路径：候选人拒绝则移出名单，凑不齐两人时终止等待
                        if (from != null && lobby.selectedLanPeers.contains(from)) {
                            lobby.selectedLanPeers.remove(from);
                            String nm = packet.getSenderName() == null || packet.getSenderName().isEmpty()
                                    ? "一位玩家" : packet.getSenderName();
                            if (lobby.state == LobbyState.WAITING && lobby.selectedLanPeers.size() < 2) {
                                // 剩余候选不足两人，永远等不到 2 个接受，直接终止
                                lobby.terminateWaiting(nm + " 拒绝了斗地主邀请");
                            } else if (mc.player != null) {
                                mc.player.displayClientMessage(
                                        Component.literal("[游戏机] " + nm + " 拒绝了斗地主邀请"), false);
                            }
                            return;
                        }
                        // 普通单人邀请路径：仅接受被邀请者本人发来的拒绝，防止第三方伪造包误取消邀请
                        if (lobby.invitedPlayer == null || !lobby.invitedPlayer.equals(from)) {
                            LOGGER.warn("[游戏机联机] 忽略非受邀玩家的拒绝消息 sender={}", from);
                            return;
                        }
                        lobby.state = LobbyState.MODE_SELECT;
                        lobby.waitingMessage = "对方拒绝了邀请";
                        lobby.resetLanWaitState();
                        lobby.waitingMessage = "对方拒绝了邀请"; // resetLanWaitState会清空，重新设置提示
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
                    if (mc.screen instanceof LanMultiplayerScreen s
                            && s.getLanGameId() != null
                            && packet.getGameId() != null
                            && Objects.equals(s.getLanGameId(), packet.getGameId()))
                        {
                            MultiplayerGamePacket.DataEnvelope data = s.acceptLanEnvelope(packet.getType(), packet.getSenderUuid(), packet.getData());
                            if (data != null) {
                                clearAcceptedInvite(packet.getSenderUuid(), packet.getGameId());
                                s.onRemoteMove(packet.getSenderUuid(), data.body());
                            }
                        }
                });
            }
            case GAME_STATE_SYNC -> {
                mc.execute(() -> {
                    if (mc.screen instanceof LanMultiplayerScreen s
                            && s.getLanGameId() != null
                            && packet.getGameId() != null
                            && Objects.equals(s.getLanGameId(), packet.getGameId()))
                        {
                            MultiplayerGamePacket.DataEnvelope data = s.acceptLanEnvelope(packet.getType(), packet.getSenderUuid(), packet.getData());
                            if (data != null) {
                                clearAcceptedInvite(packet.getSenderUuid(), packet.getGameId());
                                s.onRemoteState(packet.getSenderUuid(), data.body());
                            }
                        }
                });
            }
            case GAME_OVER -> {
                mc.execute(() -> {
                    if (mc.screen instanceof LanMultiplayerScreen s
                            && s.getLanGameId() != null
                            && packet.getGameId() != null
                            && Objects.equals(s.getLanGameId(), packet.getGameId()))
                        {
                            MultiplayerGamePacket.DataEnvelope data = s.acceptLanEnvelope(packet.getType(), packet.getSenderUuid(), packet.getData());
                            if (data != null) s.onRemoteGameOver(packet.getSenderUuid(), data.body());
                        }
                });
            }
            case LEAVE_GAME -> {
                // 对方退出对局：提示并关闭当前对局界面
                mc.execute(() -> {
                    if (mc.screen instanceof LanMultiplayerScreen s) {
                        // 校验包中 gameId 与当前对局一致，防止第三方伪造 LEAVE_GAME 关闭无关界面
                        // （当前界面未提供 gameId 时才退化为仅凭 instanceof 判断）
                        String currentGameId = s.getLanGameId();
                        if (currentGameId != null
                                && !currentGameId.equals(packet.getGameId())) {
                            LOGGER.warn("[游戏机联机] 忽略 gameId 不匹配的 LEAVE_GAME（当前={}，包中={}）",
                                    currentGameId, packet.getGameId());
                            return;
                        }
                        // 来源校验：仅对端本人（多方对局由各 Screen 重写 isLeaveFromPeer 判定），
                        // 非对局参与者的 LEAVE_GAME 一律忽略
                        if (!s.isLeaveFromPeer(packet.getSenderUuid())) {
                            LOGGER.warn("[游戏机联机] 忽略非对端来源的 LEAVE_GAME（sender={}）",
                                    packet.getSenderUuid());
                            return;
                        }
                        String name = packet.getSenderName() == null || packet.getSenderName().isEmpty()
                                ? "对方" : packet.getSenderName();
                        s.onRemoteLeave(name);
                        if (mc.player != null) {
                            mc.player.displayClientMessage(
                                    Component.literal("[游戏机] " + name + " 已退出对局"), false);
                        }
                        mc.setScreen(null);
                    } else if (mc.screen instanceof MultiplayerLobbyScreen lobby) {
                        lobby.onWaitingPeerLeave(packet);
                    }
                });
            }
            case PLAYER_QUIT -> {
                // 服务端断线看门狗（ServerDisconnectWatcher）广播：某玩家退出服务器。
                // 若退出者正是当前对端，同样按"对方退出对局"处理，避免对端干等
                mc.execute(() -> {
                    UUID quitter;
                    try {
                        quitter = UUID.fromString(packet.getData().trim());
                    } catch (Exception e) {
                        return;
                    }
                    if (mc.screen instanceof LanMultiplayerScreen s && s.isLeaveFromPeer(quitter)) {
                        String name = packet.getSenderName() == null || packet.getSenderName().isEmpty()
                                ? "对方" : packet.getSenderName();
                        s.onRemoteLeave(name);
                        if (mc.player != null) {
                            mc.player.displayClientMessage(
                                    Component.literal("[游戏机] " + name + " 已断线，对局结束"), false);
                        }
                        mc.setScreen(null);
                    } else if (mc.screen instanceof MultiplayerLobbyScreen lobby) {
                        // ★ Bug修复：大厅侧同样要处理——退出者是候选/被邀玩家时
                        //   移出名单并提前终止等待，避免主机干等到 30 秒超时
                        lobby.onPeerQuit(quitter);
                    }
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
                } catch (Exception e) {
                    LOGGER.warn("[游戏机联机] 解析玩家列表条目失败: {}", entry, e);
                }
            }
        }
    }

    private void requestPlayerList() {
        ModNetworks.PACKET_HANDLER.sendToServer(new MultiplayerGamePacket(
                MultiplayerGamePacket.PacketType.REQUEST_PLAYERS, null, "", ""
        ));
    }

    private void sendInvite(UUID target, UUID nonce) {
        MultiplayerGame game = MP_GAMES.get(selectedGameIndex);
        ModNetworks.PACKET_HANDLER.sendToServer(new MultiplayerGamePacket(
                MultiplayerGamePacket.PacketType.INVITE, target, game.id,
                MultiplayerInviteAttempt.encode(nonce)
        ));
    }

    /** 按玩家点击选择的先后顺序生成快照；不要依赖 ConcurrentHashMap 的遍历顺序分配座位。 */
    private List<UUID> selectedLanPeersInOrder() {
        synchronized (selectedLanPeers) {
            return new ArrayList<>(selectedLanPeers);
        }
    }

    /** 斗地主：选好2个玩家后批量发邀请 */
    private void sendLandlordInvites() {
        MultiplayerGame game = MP_GAMES.get(selectedGameIndex);
        Minecraft mc = Minecraft.getInstance();
        // 记录主机UUID，启动游戏时传入三个UUID（主机+两个接受者）
        lanHostUuid = mc.player != null ? mc.player.getGameProfile().getId() : null;
        invitedAttemptNonce = UUID.randomUUID();
        for (UUID uuid : selectedLanPeersInOrder()) {
            ModNetworks.PACKET_HANDLER.sendToServer(new MultiplayerGamePacket(
                    MultiplayerGamePacket.PacketType.INVITE, uuid, game.id,
                    MultiplayerInviteAttempt.encode(invitedAttemptNonce)
            ));
        }
        invitedPlayer = null; // 斗地主用 selectedLanPeers 代替
        invitedGameId = game.id();
        expectedAccepts = 2;
        pendingAccepts = 0;
        acceptedPeers.clear();
        state = LobbyState.WAITING;
        waitingStartTick = tickCount;
        waitingMessage = "等待两位玩家接受邀请 (0/2)...";
    }

    /**
     * 主机侧：邀请超时/主动取消/离开大厅时，通知所有被邀者邀请已作废。
     * 无需额外字段：target 指向被邀者，发送者身份由服务端盖章。
     */
    private void notifyInviteCancelled() {
        Set<UUID> targets = new LinkedHashSet<>();
        if (invitedPlayer != null) targets.add(invitedPlayer);
        targets.addAll(selectedLanPeers); // 斗地主：批量邀请的所有候选
        if (targets.isEmpty()) return;
        String gameId = invitedGameId;
        UUID nonce = invitedAttemptNonce;
        if (gameId == null || nonce == null) return;
        for (UUID target : targets) {
            ModNetworks.PACKET_HANDLER.sendToServer(new MultiplayerGamePacket(
                    MultiplayerGamePacket.PacketType.INVITE_CANCELLED, target, gameId,
                    MultiplayerInviteAttempt.encode(nonce)
            ));
        }
    }

    /** 统一清理联机等待状态（无论成功或失败都调用） */
    private void resetLanWaitState() {
        invitedPlayer = null;
        invitedGameId = null;
        invitedAttemptNonce = null;
        lanHostUuid = null;
        pendingAccepts = 0;
        expectedAccepts = 1;
        acceptedPeers.clear();
        selectedLanPeers.clear();
        waitingMessage = "";
        waitingStartTick = -1L;
    }

    /**
     * 终止当前等待（等待超时/对端断线共用的终止路径）：
     * 先通知其余被邀者邀请作废，再回到模式选择并给出原因提示。
     */
    private void terminateWaiting(String reason) {
        notifyInviteCancelled(); // 先通知被邀者，避免对方无限等待
        state = LobbyState.MODE_SELECT;
        resetLanWaitState();
        waitingMessage = reason; // resetLanWaitState会清空，重新设置提示
    }

    /**
     * PLAYER_QUIT（服务端断线看门狗广播）的大厅侧处理。
     * (a) 将退出者移出斗地主候选名单；(b) 若正处于等待其接受的 WAITING 态
     * （单邀的 invitedPlayer 或批量邀的候选含该 UUID），复用超时终止路径
     * 提前结束等待，避免主机干等到 30 秒超时。
     */
    private void onPeerQuit(UUID quitter) {
        if (quitter == null) return;
        boolean wasCandidate = selectedLanPeers.contains(quitter);
        boolean wasInvitee = quitter.equals(invitedPlayer);
        if (!wasCandidate && !wasInvitee) return; // 与当前邀请无关的退出者，忽略
        selectedLanPeers.remove(quitter);
        if (state == LobbyState.WAITING) {
            terminateWaiting("对方已断线");
        }
    }

    private void onWaitingPeerLeave(MultiplayerGamePacket packet) {
        UUID sender = packet.getSenderUuid();
        if (state != LobbyState.WAITING || !"landlord".equals(invitedGameId)
                || !Objects.equals(invitedGameId, packet.getGameId())
                || !MultiplayerInviteAttempt.matches(packet.getData(), invitedAttemptNonce)
                || sender == null || !acceptedPeers.containsKey(sender)
                || !selectedLanPeers.contains(sender)) {
            return;
        }
        String name = acceptedPeers.remove(sender);
        selectedLanPeers.remove(sender);
        terminateWaiting((name == null || name.isEmpty() ? "一位玩家" : name) + " 已退出等待");
    }

    private void onInviteAccepted(MultiplayerGamePacket packet) {
        // 接受者身份一律以服务端盖章的 senderUuid/senderName 为准
        // （targetPlayer 是收件人即主机自己，不可用作接受者身份）
        String gameId      = packet.getGameId();
        UUID accepterUuid  = packet.getSenderUuid();
        String accepterName = packet.getSenderName() != null && !packet.getSenderName().isEmpty()
                ? packet.getSenderName() : "一位玩家";
        if (accepterUuid == null) {
            LOGGER.warn("[游戏机联机] 收到缺少发送者身份的 ACCEPT_INVITE，已忽略");
            return;
        }
        if (state != LobbyState.WAITING || invitedGameId == null
                || !invitedGameId.equals(gameId) || findGame(gameId) == null
                || !MultiplayerInviteAttempt.matches(packet.getData(), invitedAttemptNonce)) {
            LOGGER.warn("[游戏机联机] 忽略与当前邀请不匹配的 ACCEPT_INVITE sender={} gameId={} pending={}",
                    accepterUuid, gameId, invitedGameId);
            return;
        }

        if ("landlord".equals(gameId) && expectedAccepts == 2) {
            // 斗地主三人联机：只接受被邀请过的玩家，防止陌生人冒充
            if (!selectedLanPeers.contains(accepterUuid)) {
                LOGGER.warn("[游戏机联机] 忽略未邀请玩家 {} 的接受消息", accepterName);
                return;
            }
            // 斗地主三人联机：等够2人
            acceptedPeers.put(accepterUuid, accepterName);
            pendingAccepts = acceptedPeers.size();
            waitingMessage = "等待两位玩家接受邀请 (" + pendingAccepts + "/2)...";

            if (pendingAccepts >= 2) {
                // 座位严格按 selectedLanPeers 的邀请顺序确定，接受包到达顺序不影响 peer1/peer2。
                List<UUID> invitedOrder=selectedLanPeersInOrder();
                if(invitedOrder.size()!=2||!acceptedPeers.keySet().containsAll(invitedOrder)){
                    LOGGER.warn("[游戏机联机] 斗地主接受名单与邀请顺序不一致，暂不启动");
                    return;
                }
                UUID p1=invitedOrder.get(0),p2=invitedOrder.get(1);
                Screen gs = new com.wzz.game_console.client.screens.games.landlord
                        .LandlordGameScreen(true, lanHostUuid, p1, p2);
                // 先清理再切屏：避免 setScreen 触发 removed() 时误发 INVITE_CANCELLED
                resetLanWaitState(); // 统一清理
                Minecraft.getInstance().setScreen(gs);
            }
        } else {
            // 普通单人邀请：校验接受者确实是受邀玩家
            if (invitedPlayer == null || !accepterUuid.equals(invitedPlayer)) {
                LOGGER.warn("[游戏机联机] 忽略非受邀玩家 {} 的接受消息", accepterName);
                return;
            }
            // 普通单人邀请
            UUID clientUuid = accepterUuid;
            Screen gameScreen = launchGameForLAN(gameId, clientUuid);
            // 先清理再切屏：避免 setScreen 触发 removed() 时误发 INVITE_CANCELLED
            resetLanWaitState(); // 统一清理
            if (gameScreen != null) {
                Minecraft.getInstance().setScreen(gameScreen);
            } else {
                state = LobbyState.GAME_SELECT;
            }
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
        // 等待超时机制：长时间无人接受则自动取消邀请
        if (state == LobbyState.WAITING && waitingStartTick >= 0
                && tickCount - waitingStartTick > WAIT_TIMEOUT_TICKS) {
            terminateWaiting("等待超时，邀请已取消");
        }
        // 收到的邀请超时清理：避免过期的邀请弹窗一直遮挡界面
        if (pendingInvite != null && System.currentTimeMillis() - pendingInviteArrivalMs > INVITE_TIMEOUT_MS) {
            pendingInvite = null;
            inviterName = null;
        }
    }

    @Override
    public void render(GuiGraphics g, int mx, int my, float pt) {
        int cx = width / 2;
        // 背景
        GameRenderHelper.fillGradientBackground(g, width, height, 0xFF0A0A18, 0xFF101030);
        GameRenderHelper.renderDecorativeLines(g, width, height, tickCount, 0x003355);

        // 标题
        GameRenderHelper.drawShadowedCenteredText(g, font, "联机大厅", cx, 10, 0x44CCFF, 2);
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
                case WAITING     -> renderWaiting(g, mx, my);
            }
            GameRenderHelper.drawBottomBar(g, font, width, height, "ESC 返回");
        }
    }

    private void renderGameSelect(GuiGraphics g, int mx, int my) {
        int cx = width / 2;
        // ★ Bug修复：11 张卡一页画不下（1080p 自动缩放下超出屏幕且无法点选），
        //   参照 renderPlayerList 的分页模式，只渲染当前页
        int totalPages = Math.max(1, (MP_GAMES.size() + GAMES_PER_PAGE - 1) / GAMES_PER_PAGE);
        if (gamesPage >= totalPages) gamesPage = totalPages - 1;
        // 页码指示沿用玩家列表的写法（标题带 当前页/总页数）
        g.drawCenteredString(font, "选择多人游戏  (" + (gamesPage + 1) + "/" + totalPages + ")", cx, 38, 0xCCCCCC);

        int startY = 55;
        int cardW = 220;
        int cardH = 24;
        hoveredGameIndex = -1;

        int startIdx = gamesPage * GAMES_PER_PAGE;
        int endIdx = Math.min(startIdx + GAMES_PER_PAGE, MP_GAMES.size());
        for (int i = startIdx; i < endIdx; i++) {
            MultiplayerGame game = MP_GAMES.get(i);
            int cardX = cx - cardW / 2;
            int cardY = startY + (i - startIdx) * (cardH + 3);

            boolean hover = mx >= cardX && mx <= cardX + cardW && my >= cardY && my <= cardY + cardH;
            if (hover) hoveredGameIndex = i; // 绝对索引，点击处理与原逻辑一致

            int bg = hover ? 0xFF252555 : 0xFF1A1A38;
            g.fill(cardX, cardY, cardX + cardW, cardY + cardH, bg);
            if (hover) {
                g.fill(cardX, cardY, cardX + cardW, cardY + 1, 0xFF44AAFF);
            }
            g.fill(cardX, cardY, cardX + 3, cardY + cardH, 0xFF44AAFF);

            g.drawString(font, game.icon + " " + game.name, cardX + 8, cardY + 8, hover ? 0xFFFFFF : 0xBBBBBB);

            // 支持模式标签
            StringBuilder modes = new StringBuilder();
            if (game.supportsAI) modes.append("AI ");
            if (game.supportsLocal) modes.append("本地 ");
            if (game.supportsLAN) modes.append("联机");
            int mw = font.width(modes.toString());
            g.drawString(font, modes.toString(), cardX + cardW - mw - 5, cardY + 8, 0x888888);
        }

        // 翻页提示：游戏列表用滚轮/PageUp/PageDown 翻页（玩家列表用的是底部按钮位，此处空间不足）
        if (totalPages > 1) {
            g.drawCenteredString(font, "滚轮 / PgUp·PgDn 翻页", cx,
                    startY + (endIdx - startIdx) * (cardH + 3) + 4, 0x666666);
        }
    }

    private void renderModeSelect(GuiGraphics g, int mx, int my) {
        int cx = width / 2;
        MultiplayerGame game = MP_GAMES.get(selectedGameIndex);
        g.drawCenteredString(font, game.icon + " " + game.name + " - 选择模式", cx, 38, 0xFFFFFF);

        if (!waitingMessage.isEmpty()) {
            g.drawCenteredString(font, waitingMessage, cx, 52, 0xFFFF44);
        }

        int startY = 68;
        int btnW = 180;
        int btnH = 24;
        hoveredModeIndex = -1;
        int modeIdx = 0;

        if (game.supportsAI) {
            boolean h = drawModeButton(g, mx, my, cx - btnW/2, startY + modeIdx * 30, btnW, btnH,
                    "🤖 玩家 vs 人机", 0xFF2A4A14);
            if (h) hoveredModeIndex = modeIdx;
            modeIdx++;
        }
        if (game.supportsLocal) {
            boolean h = drawModeButton(g, mx, my, cx - btnW/2, startY + modeIdx * 30, btnW, btnH,
                    "👥 本地双人", 0xFF4A3A14);
            if (h) hoveredModeIndex = modeIdx;
            modeIdx++;
        }
        if (game.supportsLAN) {
            boolean h = drawModeButton(g, mx, my, cx - btnW/2, startY + modeIdx * 30, btnW, btnH,
                    "🌐 局域网对战", 0xFF143A4A);
            if (h) hoveredModeIndex = modeIdx;
            modeIdx++;
        }

        // 返回按钮
        boolean backH = drawModeButton(g, mx, my, cx - 50, startY + modeIdx * 30 + 10, 100, 20,
                "◀ 返回", 0xFF222233);
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
        g.drawCenteredString(font, "在线玩家  (" + (playerListPage + 1) + "/" + totalPages + ")", cx, 38, 0x44CCFF);

        hoveredPlayerIndex = -1;
        if (onlinePlayers.isEmpty()) {
            g.drawCenteredString(font, "没有找到其他在线玩家", cx, 70, 0x888888);
            g.drawCenteredString(font, "确保在局域网/服务器中有其他玩家", cx, 85, 0x666666);
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
                g.drawString(font, p.name, cardX + 5, cardY + 6, 0xFFFFFF);
                if (hover) {
                    g.drawString(font, "点击邀请", cardX + 150, cardY + 6, 0x44FF44);
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
        g.drawCenteredString(font, "斗地主 - 选择两位对手  (" + (playerListPage+1) + "/" + totalPages + ")", cx, 38, 0x44CCFF);
        g.drawCenteredString(font, "已选 " + selectedLanPeers.size() + " / 2 人", cx, 50, 0xAAAAAA);

        hoveredPlayerIndex = -1;
        if (onlinePlayers.isEmpty()) {
            g.drawCenteredString(font, "没有其他在线玩家", cx, 75, 0x888888);
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
                g.drawString(font, (selected2 ? "✔ " : "  ") + p.name(), cardX + 8, cardY + 7, 0xFFFFFF);
                g.drawString(font, selected2 ? "已选" : "点击选择", cardX + 170, cardY + 7, selected2 ? 0x44FF88 : 0x666666);
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
        g.drawCenteredString(font, ready ? "发送邀请 ▶" : "请选满2人", cx, btnY+7, ready ? 0xFFFFFF : 0x666666);

        GameRenderHelper.drawSecondaryButton(g, font, "◀ 返回", cx - 40, height - 28, 80, 18, mx, my);
    }

    private void renderWaiting(GuiGraphics g, int mx, int my) {
        int cx = width / 2, cy = height / 2;
        // 动画点（查表,帧表为类级预计算常量）
        String dots = WAIT_DOTS[(int)(tickCount / 10 % WAIT_DOTS.length)];
        g.drawCenteredString(font, waitingMessage + dots, cx, cy - 10, 0xFFFF44);
        GameRenderHelper.drawSecondaryButton(g, font, "取消", cx - 40, cy + 10, 80, 18, mx, my);
    }

    private void renderInviteNotification(GuiGraphics g, int mx, int my) {
        int cx = width / 2, cy = height / 2;

        // 半透明全屏遮罩
        g.flush(); // 防止先绘制的标题文字盖住遮罩背景（批量渲染text批次后置）
        g.fill(0, 0, width, height, 0xAA000022);

        // 弹窗主体
        int nw = 280, nh = 90;
        int nx = cx - nw / 2, ny = cy - nh / 2;
        g.fill(nx - 2, ny - 2, nx + nw + 2, ny + nh + 2, 0xFF44AAFF); // 外发光边框
        g.fill(nx, ny, nx + nw, ny + nh, 0xFF0A1A3A);

        g.drawCenteredString(font, "📩 收到游戏邀请", cx, ny + 8, 0x44CCFF);
        g.drawCenteredString(font, inviterName + " 邀请你玩 " + pendingInvite.getGameId(), cx, ny + 24, 0xFFFFFF);

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
                String gameId = pendingInvite.getGameId();
                UUID inviteNonce = MultiplayerInviteAttempt.parse(pendingInvite.getData());
                // 主机 UUID 以服务端盖章的发送者身份为准（targetPlayer 仍是收件人即自己）
                UUID hostUuid = pendingInvite.getSenderUuid();
                if (hostUuid == null || inviteNonce == null) {
                    // 缺失发送者身份或邀请尝试标识的畸形邀请，直接丢弃
                    pendingInvite = null;
                    return true;
                }
                ModNetworks.PACKET_HANDLER.sendToServer(new MultiplayerGamePacket(
                        MultiplayerGamePacket.PacketType.ACCEPT_INVITE,
                        hostUuid, gameId, MultiplayerInviteAttempt.encode(inviteNonce)
                ));
                // 记录已接受邀请的主机：主机此后流产(INVITE_CANCELLED)时用于兜底通知，
                // 否则已切到对局界面等待的候选者收不到任何通知
                acceptedInviteHostUuid = hostUuid;
                acceptedInviteGameId = gameId;
                acceptedInviteNonce = inviteNonce;
                acceptedInviteMs = System.currentTimeMillis();
                pendingInvite = null;
                // 被邀请方作为 CLIENT 直接启动游戏
                // CLIENT 侧：以 isHost=false 启动对应联机实例
                Screen clientScreen = switch (gameId) {
                    case "icefire"    -> new com.wzz.game_console.client.screens.games.IceFireGameScreen(false, hostUuid);
                    case "gomoku"     -> new com.wzz.game_console.client.screens.games.GomokuScreen(false, hostUuid);
                    case "tictactoe"  -> new com.wzz.game_console.client.screens.games.tictactoe.TicTacToeScreen(false, hostUuid);
                    case "wchess"     -> new com.wzz.game_console.client.screens.games.WesternChessScreen(false, hostUuid);
                    case "colorchase" -> new com.wzz.game_console.client.screens.games.ColorChaseGameScreen(false, hostUuid);
                    case "chess"      -> new com.wzz.game_console.client.screens.games.ChessGameScreen(false, hostUuid);
                    case "go"         -> new com.wzz.game_console.client.screens.games.gogame.GoGameScreen(false, hostUuid);
                    // 斗地主CLIENT：等HOST推送初始状态（含玩家索引）
                    case "landlord"   -> new com.wzz.game_console.client.screens.games.landlord.LandlordGameScreen(false, hostUuid, inviteNonce);
                    default           -> null;
                };
                if (clientScreen != null) {
                    Minecraft.getInstance().setScreen(clientScreen);
                } else {
                    state = LobbyState.WAITING;
                    waitingStartTick = tickCount; // 补超时起点：否则主机永远不开局时客机无限等待
                    waitingMessage = "已接受邀请，等待 " + inviterName + " 开始 " + gameId + "...";
                }
                return true;
            }

            // 拒绝按钮：nx+nw-130, ny+nh-28, 宽110, 高22
            if (mx >= nx + nw - 130 && mx <= nx + nw - 20 && my >= ny + nh - 28 && my <= ny + nh - 6) {
                declinePendingInvite();
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
                MultiplayerGame selected = MP_GAMES.get(selectedGameIndex);
                int modeIndex = 0;
                if (selected.supportsAI && hoveredModeIndex == modeIndex++) {
                    launchGame("AI");
                    return true;
                }
                if (selected.supportsLocal && hoveredModeIndex == modeIndex++) {
                    launchGame("LOCAL_TWO_PLAYER");
                    return true;
                }
                if (selected.supportsLAN && hoveredModeIndex == modeIndex) {
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
                    invitedAttemptNonce = UUID.randomUUID();
                    sendInvite(target, invitedAttemptNonce);
                    invitedPlayer = target;
                    invitedGameId = MP_GAMES.get(selectedGameIndex).id();
                    expectedAccepts = 1;
                    pendingAccepts = 0;
                    acceptedPeers.clear();
                    state = LobbyState.WAITING;
                    waitingStartTick = tickCount;
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
                    notifyInviteCancelled(); // 主机主动取消：通知被邀者
                    state = LobbyState.MODE_SELECT;
                    resetLanWaitState(); // 取消时统一清理
                    return true;
                }
            }
        }
        return super.mouseClicked(mx, my, btn);
    }

    private void launchGame(String mode) {
        MultiplayerGame game = MP_GAMES.get(selectedGameIndex);
        boolean ai = "AI".equalsIgnoreCase(mode) || "HUMAN".equalsIgnoreCase(mode);
        boolean localTwoPlayer = "LOCAL_TWO_PLAYER".equalsIgnoreCase(mode) || "LOCAL".equalsIgnoreCase(mode);
        Screen gameScreen = switch (game.id) {
            case "gomoku"    -> new com.wzz.game_console.client.screens.games.GomokuScreen(ai);
            case "go"        -> new com.wzz.game_console.client.screens.games.gogame.GoGameScreen(
                    new com.wzz.game_console.client.screens.games.gogame.GoGame(ai));
            case "tictactoe" -> new com.wzz.game_console.client.screens.games.tictactoe.TicTacToeScreen(
                    localTwoPlayer
                            ? com.wzz.game_console.client.screens.games.tictactoe.TicTacToeGame.GameMode.TWO_PLAYER
                            : com.wzz.game_console.client.screens.games.tictactoe.TicTacToeGame.GameMode.SINGLE_PLAYER);
            case "chess"     -> new com.wzz.game_console.client.screens.games.ChessGameScreen(
                    ai ? com.wzz.game_console.client.screens.games.ChessGameScreen.GameMode.PVA
                            : com.wzz.game_console.client.screens.games.ChessGameScreen.GameMode.PVP);
            case "icefire"   -> new com.wzz.game_console.client.screens.games.IceFireGameScreen();
            case "colorchase"-> new com.wzz.game_console.client.screens.games.ColorChaseGameScreen(localTwoPlayer);
            case "landlord"  -> new com.wzz.game_console.client.screens.games.landlord.LandlordGameScreen(localTwoPlayer);
            case "breakout"  -> new com.wzz.game_console.client.screens.games.BreakoutScreen();
            case "maze"      -> new com.wzz.game_console.client.screens.games.MazeGameScreen();
            case "snake"     -> new com.wzz.game_console.client.screens.games.SnakeGameScreen();
            case "wchess"    -> new com.wzz.game_console.client.screens.games.WesternChessScreen();
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
            case "icefire"    -> new com.wzz.game_console.client.screens.games.IceFireGameScreen(true, remotePeer);
            case "gomoku"     -> new com.wzz.game_console.client.screens.games.GomokuScreen(true, remotePeer);
            case "tictactoe"  -> new com.wzz.game_console.client.screens.games.tictactoe.TicTacToeScreen(true, remotePeer);
            case "wchess"     -> new com.wzz.game_console.client.screens.games.WesternChessScreen(true, remotePeer);
            case "colorchase" -> new com.wzz.game_console.client.screens.games.ColorChaseGameScreen(true, remotePeer);
            // ★ Bug修复：中国象棋和围棋缺少 LAN 分支，导致邀请方接受后返回游戏选择界面
            case "chess"      -> new com.wzz.game_console.client.screens.games.ChessGameScreen(true, remotePeer);
            case "go"         -> new com.wzz.game_console.client.screens.games.gogame.GoGameScreen(true, remotePeer);
            default           -> null;
        };
    }

    @Override
    public boolean mouseScrolled(double mx, double my, double scrollX, double scrollY) {
        // 游戏选择列表滚轮翻页（邀请弹窗显示时不翻页，避免隔空误操作）
        if (pendingInvite == null && state == LobbyState.GAME_SELECT) {
            int totalPages = Math.max(1, (MP_GAMES.size() + GAMES_PER_PAGE - 1) / GAMES_PER_PAGE);
            if (scrollY < 0 && gamesPage < totalPages - 1) { gamesPage++; return true; }
            if (scrollY > 0 && gamesPage > 0) { gamesPage--; return true; }
        }
        return super.mouseScrolled(mx, my, scrollX, scrollY);
    }

    private void declinePendingInvite() {
        MultiplayerGamePacket invite = pendingInvite;
        if (invite == null) return;
        UUID inviterUuid = invite.getSenderUuid();
        if (inviterUuid == null) {
            LOGGER.warn("[游戏机联机] 无法拒绝缺少邀请方身份的邀请");
        } else {
            ModNetworks.PACKET_HANDLER.sendToServer(new MultiplayerGamePacket(
                    MultiplayerGamePacket.PacketType.DECLINE_INVITE,
                    inviterUuid, invite.getGameId(), invite.getData()));
        }
        pendingInvite = null;
        inviterName = null;
    }

    @Override
    public boolean keyPressed(int key, int scan, int mods) {
        if (key == GLFW.GLFW_KEY_ESCAPE && pendingInvite != null) {
            declinePendingInvite();
            return true;
        }
        // 游戏选择列表 PageUp/PageDown 翻页（与滚轮等效）
        if (pendingInvite == null && state == LobbyState.GAME_SELECT) {
            int totalPages = Math.max(1, (MP_GAMES.size() + GAMES_PER_PAGE - 1) / GAMES_PER_PAGE);
            if (key == GLFW.GLFW_KEY_PAGE_DOWN && gamesPage < totalPages - 1) { gamesPage++; return true; }
            if (key == GLFW.GLFW_KEY_PAGE_UP && gamesPage > 0) { gamesPage--; return true; }
        }
        if (key == GLFW.GLFW_KEY_ESCAPE) {
            switch (state) {
                case MODE_SELECT -> { state = LobbyState.GAME_SELECT; return true; }
                case PLAYER_LIST -> { state = LobbyState.MODE_SELECT; return true; }
                case PLAYER_LIST_MULTI -> { selectedLanPeers.clear(); state = LobbyState.MODE_SELECT; return true; }
                case WAITING     -> { notifyInviteCancelled(); state = LobbyState.MODE_SELECT; resetLanWaitState(); return true; }
                default -> { Minecraft.getInstance().setScreen(new GameSelectorScreen()); return true; }
            }
        }
        return super.keyPressed(key, scan, mods);
    }

    @Override
    public void removed() {
        super.removed();
        // 主机离开大厅时若仍有未决邀请，通知被邀者取消。
        // 成功开局的路径会在 setScreen 前先 resetLanWaitState，不会误发。
        if (state == LobbyState.WAITING) {
            notifyInviteCancelled();
        }
        // ★ Bug修复：static pendingInvite/inviterName 在玩家切世界/Singleplayer→Multiplayer
        //   后不被清理,旧邀请若未超时,新服务器邀请可能被旧 sender UUID 误清。
        //   退出屏时强制清空
        pendingInvite = null;
        inviterName = null;
    }

    @Override
    public boolean isPauseScreen() { return false; }
}