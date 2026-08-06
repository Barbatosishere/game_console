package com.wzz.game_console.client.screens.games;

import com.wzz.game_console.init.ModNetworks;
import com.wzz.game_console.network.MultiplayerGamePacket;

import java.util.UUID;

/**
 * 所有支持局域网联机的游戏 Screen 都实现此接口。
 * 设计原则：
 * ─ 轮制棋类（五子棋/围棋/井字/中象/国象）：只传「走了哪步」(GAME_MOVE)
 * ─ 实时类（冰火人/颜色追逐）：每 tick 传「完整/差量状态」(GAME_STATE_SYNC)
 * MultiplayerLobbyScreen 收到包后用 instanceof 路由，无需在路由层感知具体游戏。
 */
public interface LanMultiplayerScreen {

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

    /**
     * 收到对方的 LEAVE_GAME 包（对方退出对局，可选实现）。
     */
    default void onRemoteLeave(String senderName) {}

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

    /** 向对方发送完整状态（实时游戏用，HOST 调用） */
    default void sendState(String stateData) {
        UUID peer = getLanPeer();
        if (peer == null) return;
        ModNetworks.PACKET_HANDLER.sendToServer(new MultiplayerGamePacket(
                MultiplayerGamePacket.PacketType.GAME_STATE_SYNC,
                peer, getLanGameId(), stateData
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