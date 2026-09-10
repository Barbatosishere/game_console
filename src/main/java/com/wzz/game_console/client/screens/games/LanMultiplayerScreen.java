package com.wzz.game_console.client.screens.games;

import com.wzz.game_console.init.ModNetworks;
import com.wzz.game_console.network.MultiplayerGamePacket;

import java.util.UUID;
import java.util.Map;
import java.util.Set;
import java.util.WeakHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 所有支持局域网联机的游戏 Screen 都实现此接口。
 * 设计原则：
 * ─ 轮制棋类（五子棋/围棋/井字/中象/国象）：只传「走了哪步」(GAME_MOVE)
 * ─ 实时类（冰火人/颜色追逐）：每 tick 传「完整/差量状态」(GAME_STATE_SYNC)
 * MultiplayerLobbyScreen 收到包后用 instanceof 路由，无需在路由层感知具体游戏。
 */
public interface LanMultiplayerScreen {

    /** Per-screen transport state; WeakHashMap avoids retaining closed screens. */
    Map<LanMultiplayerScreen, SessionSequencer> LAN_SEQUENCERS =
            java.util.Collections.synchronizedMap(new WeakHashMap<>());

    final class SessionSequencer {
        private UUID sessionId = UUID.randomUUID();
        private final AtomicLong nextSequence = new AtomicLong();
        private final Map<ReceiveKey, ReceivedSequence> received = new java.util.HashMap<>();
        private final Map<ReceiveKey, Set<UUID>> retiredSessions = new java.util.HashMap<>();

        /** 单键保留的退役会话上限：失控/恶意对端反复换新会话时 FIFO 淘汰最旧，防内存无界增长。 */
        private static final int MAX_RETIRED_SESSIONS_PER_KEY = 64;

        public synchronized UUID sessionId() { return sessionId; }
        public synchronized long nextSequence() { return nextSequence.getAndIncrement(); }

        /**
         * Start a new locally-originated transport session. A restart must
         * use sequence zero in a fresh session so later moves cannot overtake
         * the restart and apply to the previous board.
         */
        public synchronized UUID rotateSession() {
            sessionId = UUID.randomUUID();
            nextSequence.set(0L);
            return sessionId;
        }

        public synchronized boolean accept(UUID sender, MultiplayerGamePacket.PacketType type,
                                            MultiplayerGamePacket.DataEnvelope envelope) {
            // null 判定必须先于 legacy()（防御性；生产入口 acceptLanEnvelope 已先判空，
            // 但本方法为公开 API，不得对 null 信封抛 NPE）。测试源码集无 Minecraft 类路径，
            // PacketType 不可引用，该路径由压测 harness 覆盖。
            if (envelope == null) return false;
            if (envelope.legacy()) return true;
            if (sender == null || type == null || envelope.sessionId() == null) return false;
            return acceptSession(sender, type.name(), envelope.sessionId(), envelope.sequence());
        }

        /** Package-private transport test hook that avoids loading Minecraft packet types. */
        synchronized boolean acceptSession(UUID sender, String channel, UUID incomingSession, long sequence) {
            if (sender == null || channel == null || incomingSession == null) return false;
            return acceptSession(new ReceiveKey(sender, channel), incomingSession, sequence);
        }

        /**
         * Sender-gated acceptance used by the envelope entry point. A sender outside
         * this game's peer set must neither consume sequence slots nor rotate sessions.
         */
        synchronized boolean acceptFromPeer(UUID sender, UUID expectedPeer, String channel,
                                            UUID incomingSession, long sequence) {
            if (sender == null || expectedPeer == null || !expectedPeer.equals(sender)) return false;
            return acceptSession(sender, channel, incomingSession, sequence);
        }

        private boolean acceptSession(ReceiveKey key, UUID incomingSession, long sequence) {
            if (sequence < 0L) return false;
            ReceivedSequence previous = received.get(key);
            if (previous != null) {
                if (!previous.sessionId().equals(incomingSession)) {
                    if (sequence != 0L) return false;
                    Set<UUID> retired = retiredSessions.get(key);
                    if (retired != null && retired.contains(incomingSession)) return false;
                    retireSession(key, previous.sessionId());
                } else if (sequence <= previous.sequence()) {
                    return false;
                }
            }
            received.put(key, new ReceivedSequence(incomingSession, sequence));
            return true;
        }

        private void retireSession(ReceiveKey key, UUID sessionId) {
            // LinkedHashSet 保证按插入序 FIFO 淘汰（HashSet 迭代序不定）。
            // 被淘汰的极旧会话若被重放会再次被接受一次——这是有界内存的既定取舍：
            // 重放防护窗口 = 最近 64 次会话轮换，正常对局每局至多轮换数次，窗口远超需要。
            Set<UUID> retired = retiredSessions.computeIfAbsent(key, ignored -> new java.util.LinkedHashSet<>());
            while (retired.size() >= MAX_RETIRED_SESSIONS_PER_KEY) {
                java.util.Iterator<UUID> eldest = retired.iterator();
                eldest.next();
                eldest.remove();
            }
            retired.add(sessionId);
        }

        private record ReceiveKey(UUID sender, String channel) {}
        private record ReceivedSequence(UUID sessionId, long sequence) {}
    }

    /** Session UUID used for locally generated GAME_* envelopes. */
    default UUID getLanSessionId() {
        return LAN_SEQUENCERS.computeIfAbsent(this, ignored -> new SessionSequencer()).sessionId();
    }

    /** Monotonically increasing sequence for locally generated GAME_* envelopes. */
    default long nextLanSequence() {
        return LAN_SEQUENCERS.computeIfAbsent(this, ignored -> new SessionSequencer()).nextSequence();
    }

    /**
     * GAME_* 包的来源校验：发送者是否为本对局的合法对端。
     * 默认与 LEAVE_GAME 一致（仅 getLanPeer()，多方对局重写 isLeaveFromPeer 即可一并生效）。
     * 在 sequencer 之前拦截，防止第三方伪造 sender 占用 sequence 槽位或轮换会话。
     */
    default boolean isGamePacketFromPeer(UUID sender) {
        return isLeaveFromPeer(sender);
    }

    /** Parse and de-duplicate an incoming GAME_* data field. Legacy bare data is accepted. */
    default MultiplayerGamePacket.DataEnvelope acceptLanEnvelope(UUID sender, String data) {
        return acceptLanEnvelope(MultiplayerGamePacket.PacketType.GAME_MOVE, sender, data);
    }

    /** Parse and de-duplicate an incoming envelope for a specific route. */
    default MultiplayerGamePacket.DataEnvelope acceptLanEnvelope(
            MultiplayerGamePacket.PacketType type, UUID sender, String data) {
        if (!isGamePacketFromPeer(sender)) return null;
        MultiplayerGamePacket.DataEnvelope envelope = MultiplayerGamePacket.parseData(data);
        if (envelope == null) return null;
        return LAN_SEQUENCERS.computeIfAbsent(this, ignored -> new SessionSequencer()).accept(sender, type, envelope)
                ? envelope : null;
    }

    /** Envelope local game data without changing existing sendMove/sendState callers. */
    default String envelopeLanData(String body) {
        return MultiplayerGamePacket.envelopeData(getLanSessionId(), nextLanSequence(), body);
    }

    // ── 联机角色常量 ─────────────────────────────────────────────────
    int LAN_NONE   = 0;  // 单机
    int LAN_HOST   = 1;  // 主机（先手/发起方）
    int LAN_CLIENT = 2;  // 客机（后手/接受方）

    /** 获取对方 UUID，用于发包目标 */
    UUID getLanPeer();

    /** 获取游戏 id（如 "gomoku"），用于填 gameId 字段 */
    String getLanGameId();

    /**
     * 收到对方的 GAME_MOVE 包（轮制游戏：对方的走法字符串）。
     * 实时游戏不必实现此方法（提供默认空实现即可）。
     */
    default void onRemoteMove(String moveData) {}

    /**
     * 收到对方的 GAME_MOVE 包（携带服务端盖章的发送者 UUID）。
     * 需要按「报文来源 → 座位/身份」做安全校验的游戏应重写此方法，
     * 不要信任走法字符串中客户端自报的玩家索引。
     * 默认实现转发到旧签名，保持其他游戏行为不变。
     */
    default void onRemoteMove(java.util.UUID senderUuid, String moveData) {
        onRemoteMove(moveData);
    }

    /**
     * 收到对方的 GAME_STATE_SYNC 包（实时游戏：完整状态快照）。
     * 轮制游戏不必实现此方法。
     */
    default void onRemoteState(String stateData) {}

    /**
     * 收到对方的 GAME_STATE_SYNC 包（携带服务端盖章的发送者 UUID）。
     * 需要按「报文来源 → 身份」做安全校验的游戏应重写此方法，
     * 不要信任状态字符串中客户端自报的玩家索引。
     * 默认实现转发到旧签名，保持其他游戏行为不变。
     */
    default void onRemoteState(UUID senderUuid, String state) {
        onRemoteState(state);
    }

    /**
     * 收到 GAME_OVER 包（可选，游戏结束通知）。
     */
    default void onRemoteGameOver(String data) {}

    /**
     * 收到 GAME_OVER 包（携带服务端盖章的发送者 UUID）。
     * 需要按来源校验胜负结算的游戏应重写此方法。
     * 默认实现转发到旧签名，保持其他游戏行为不变。
     */
    default void onRemoteGameOver(UUID senderUuid, String data) {
        onRemoteGameOver(data);
    }

    /** 收到对方的 LEAVE_GAME 包（对方退出对局，可选实现）。 */
    default void onRemoteLeave(String senderName) {}

    /**
     * LEAVE_GAME/断线通知的来源校验：发送者是否为本对局的合法对端。
     * 默认仅接受 getLanPeer() 一人；多方对局（如斗地主三人）应重写以接受任一对端。
     * 防止第三方伪造 LEAVE_GAME 关闭无关玩家的对局界面。
     */
    default boolean isLeaveFromPeer(UUID sender) {
        UUID peer = getLanPeer();
        return peer != null && sender != null && peer.equals(sender);
    }

    // ── 便捷发包工具方法（接口 default，子类直接调用）─────────────────

    /** 向对方发送走法（轮制游戏用） */
    default void sendMove(String moveData) {
        UUID peer = getLanPeer();
        if (peer == null) return;
        ModNetworks.PACKET_HANDLER.sendToServer(new MultiplayerGamePacket(
                MultiplayerGamePacket.PacketType.GAME_MOVE,
                peer, getLanGameId(), moveData
        ));
    }

    /** 向对方发送带 session/sequence envelope 的走法。 */
    default void sendMoveEnvelope(String moveData) {
        UUID peer = getLanPeer();
        if (peer == null) return;
        ModNetworks.PACKET_HANDLER.sendToServer(new MultiplayerGamePacket(
                MultiplayerGamePacket.PacketType.GAME_MOVE,
                peer, getLanGameId(), envelopeLanData(moveData)
        ));
    }

    /** 向对方发送完整状态（实时游戏用，HOST 调用） */
    default void sendState(String stateData) {
        UUID peer = getLanPeer();
        if (peer == null) return;
        ModNetworks.PACKET_HANDLER.sendToServer(new MultiplayerGamePacket(
                MultiplayerGamePacket.PacketType.GAME_STATE_SYNC,
                peer, getLanGameId(), stateData
        ));
    }

    /** 向对方发送带 session/sequence envelope 的状态。 */
    default void sendStateEnvelope(String stateData) {
        UUID peer = getLanPeer();
        if (peer == null) return;
        ModNetworks.PACKET_HANDLER.sendToServer(new MultiplayerGamePacket(
                MultiplayerGamePacket.PacketType.GAME_STATE_SYNC,
                peer, getLanGameId(), envelopeLanData(stateData)
        ));
    }

    /** 向对方发送输入（实时游戏用，CLIENT 调用） */
    default void sendInput(String inputData) {
        UUID peer = getLanPeer();
        if (peer == null) return;
        ModNetworks.PACKET_HANDLER.sendToServer(new MultiplayerGamePacket(
                MultiplayerGamePacket.PacketType.GAME_MOVE,
                peer, getLanGameId(), inputData
        ));
    }

    /** 向对方发送带 session/sequence envelope 的 GAME_OVER。 */
    default void sendGameOverEnvelope(String data) {
        UUID peer = getLanPeer();
        if (peer == null) return;
        ModNetworks.PACKET_HANDLER.sendToServer(new MultiplayerGamePacket(
                MultiplayerGamePacket.PacketType.GAME_OVER,
                peer, getLanGameId(), envelopeLanData(data)
        ));
    }

    /** 向对方发送输入的 envelope 版本。 */
    default void sendInputEnvelope(String inputData) {
        UUID peer = getLanPeer();
        if (peer == null) return;
        ModNetworks.PACKET_HANDLER.sendToServer(new MultiplayerGamePacket(
                MultiplayerGamePacket.PacketType.GAME_MOVE,
                peer, getLanGameId(), envelopeLanData(inputData)
        ));
    }

    /** 退出对局时通知对方（在 onClose 或等效退出路径调用） */
    default void sendLeaveGame() {
        UUID peer = getLanPeer();
        if (peer == null) return;
        ModNetworks.PACKET_HANDLER.sendToServer(new MultiplayerGamePacket(
                MultiplayerGamePacket.PacketType.LEAVE_GAME,
                peer, getLanGameId(), ""
        ));
    }
}