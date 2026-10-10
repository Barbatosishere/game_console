package com.wzz.game_console.client.screens.games;

import net.neoforged.neoforge.network.PacketDistributor;
import com.wzz.game_console.network.MultiplayerGamePacket;

import java.util.UUID;
import java.util.Map;
import java.util.Set;
import java.util.WeakHashMap;
import java.util.concurrent.atomic.AtomicLong;

/** 联机界面的报文入口：GAME_MOVE 传走法或输入，GAME_STATE_SYNC 传状态快照。 */
public interface LanMultiplayerScreen {

    /** Per-screen transport state; WeakHashMap avoids retaining closed screens. */
    Map<LanMultiplayerScreen, SessionSequencer> LAN_SEQUENCERS =
            java.util.Collections.synchronizedMap(new WeakHashMap<>());

    final class SessionSequencer {
        private final UUID sessionId = UUID.randomUUID();
        private final AtomicLong nextSequence = new AtomicLong();
        private final Map<ReceiveKey, ReceivedSequence> received = new java.util.HashMap<>();
        private final Map<ReceiveKey, Set<UUID>> retiredSessions = new java.util.HashMap<>();

        /** 单键保留的退役会话上限：失控/恶意对端反复换新会话时 FIFO 淘汰最旧，防内存无界增长。 */
        private static final int MAX_RETIRED_SESSIONS_PER_KEY = 64;

        public synchronized UUID sessionId() { return sessionId; }
        public synchronized long nextSequence() { return nextSequence.getAndIncrement(); }

        public synchronized boolean accept(UUID sender, MultiplayerGamePacket.PacketType type,
                                            MultiplayerGamePacket.DataEnvelope envelope) {

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
            // 按插入序淘汰最旧会话；重放防护仅覆盖最近 64 次轮换。
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

    /** 为本地游戏数据添加会话和序号。 */
    default String envelopeLanData(String body) {
        return MultiplayerGamePacket.envelopeData(getLanSessionId(), nextLanSequence(), body);
    }

    int LAN_NONE   = 0;  // 单机
    int LAN_HOST   = 1;  // 主机（先手/发起方）
    int LAN_CLIENT = 2;  // 客机（后手/接受方）

    /** 获取对方 UUID，用于发包目标 */
    UUID getLanPeer();

    /** 获取游戏 id（如 "gomoku"），用于填 gameId 字段 */
    String getLanGameId();

    /** 接收走法或实时输入。 */
    default void onRemoteMove(String moveData) {}

    /** 接收服务端盖章的发送者 UUID；多方对局按此身份校验，不能信任报文自报的座位。 */
    default void onRemoteMove(java.util.UUID senderUuid, String moveData) {
        onRemoteMove(moveData);
    }

    /** 接收状态快照。 */
    default void onRemoteState(String stateData) {}

    /** 按服务端盖章的发送者身份接收快照。 */
    default void onRemoteState(UUID senderUuid, String state) {
        onRemoteState(state);
    }

    /** 接收游戏结束通知。 */
    default void onRemoteGameOver(String data) {}

    /** 按服务端盖章的发送者身份接收结算。 */
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

    /** 发送带会话和序号的 GAME_MOVE（走法或实时输入）。 */
    default void sendMoveEnvelope(String moveData) {
        UUID peer = getLanPeer();
        if (peer == null) return;
        PacketDistributor.sendToServer(new MultiplayerGamePacket(
                MultiplayerGamePacket.PacketType.GAME_MOVE,
                peer, getLanGameId(), envelopeLanData(moveData)
        ));
    }

    /** 向对方发送带 session/sequence envelope 的状态。 */
    default void sendStateEnvelope(String stateData) {
        UUID peer = getLanPeer();
        if (peer == null) return;
        PacketDistributor.sendToServer(new MultiplayerGamePacket(
                MultiplayerGamePacket.PacketType.GAME_STATE_SYNC,
                peer, getLanGameId(), envelopeLanData(stateData)
        ));
    }

    /** 退出对局时通知对方（在 onClose 或等效退出路径调用） */
    default void sendLeaveGame() {
        UUID peer = getLanPeer();
        if (peer == null) return;
        PacketDistributor.sendToServer(new MultiplayerGamePacket(
                MultiplayerGamePacket.PacketType.LEAVE_GAME,
                peer, getLanGameId(), ""
        ));
    }
}
