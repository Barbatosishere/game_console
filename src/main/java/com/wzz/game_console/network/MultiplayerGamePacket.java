package com.wzz.game_console.network;

import com.wzz.game_console.ModMain;
import com.wzz.game_console.client.screens.MultiplayerLobbyScreen;
import io.netty.buffer.ByteBufUtil;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.neoforged.neoforge.network.handling.IPayloadContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.UUID;

/**
 * 多人游戏网络包（双向）
 * 客户端 → 服务端：发送邀请、走法、状态同步等
 * 服务端 → 客户端：转发给目标玩家
 *
 * 安全说明：senderUuid / senderName 由服务端在转发时盖章，
 * 客户端自报的发送者身份一律不可信，接收方只能信任服务端盖章的字段。
 */
public record MultiplayerGamePacket(
        PacketType packetType,
        UUID targetPlayer,
        UUID senderUuid,   // 服务端盖章的发送者真实 UUID（客户端发送时填 null 即可）
        String senderName, // 服务端盖章的发送者名字（客户端发送时填 "" 即可）
        String gameId,
        String data
) implements CustomPacketPayload {

    private static final Logger LOGGER = LoggerFactory.getLogger(MultiplayerGamePacket.class);

    private static final int MAX_GAME_ID_BYTES = 256;
    private static final int MAX_DATA_BYTES = 32767;
    private static final int MAX_NAME_BYTES = 256;

    public enum PacketType {
        INVITE,
        ACCEPT_INVITE,
        DECLINE_INVITE,
        GAME_MOVE,
        GAME_STATE_SYNC,
        GAME_OVER,
        LEAVE_GAME,
        REQUEST_PLAYERS,
        PLAYER_LIST,
        // ⚠ 新类型必须追加在枚举末尾：序号即线上索引，
        // 插入/调整已有顺序会破坏与旧版本的 encode/decode 兼容性。
        INVITE_CANCELLED
    }

    public static final Type<MultiplayerGamePacket> TYPE =
            new Type<>(ResourceLocation.fromNamespaceAndPath(ModMain.MODID, "multiplayer_game"));

    /** 客户端发包用的便捷构造器（发送者身份由服务端转发时盖章，客户端无需填写） */
    public MultiplayerGamePacket(PacketType packetType, UUID targetPlayer, String gameId, String data) {
        this(packetType, targetPlayer, null, "", gameId, data);
    }

    public static final StreamCodec<FriendlyByteBuf, MultiplayerGamePacket> STREAM_CODEC = new StreamCodec<>() {
        @Override
        public MultiplayerGamePacket decode(FriendlyByteBuf buf) {
            // 范围校验：畸形包的类型索引不允许触发数组越界
            int typeIdx = buf.readVarInt();
            PacketType[] values = PacketType.values();
            if (typeIdx < 0 || typeIdx >= values.length) {
                throw new IllegalArgumentException("非法的多人游戏包类型索引: " + typeIdx);
            }
            PacketType type = values[typeIdx];

            boolean hasTarget = buf.readBoolean();
            UUID target = hasTarget ? buf.readUUID() : null;

            boolean hasSender = buf.readBoolean();
            UUID senderUuid = hasSender ? buf.readUUID() : null;
            String senderName = buf.readUtf(MAX_NAME_BYTES);

            String gameId = buf.readUtf(MAX_GAME_ID_BYTES);
            String data = buf.readUtf(MAX_DATA_BYTES);
            return new MultiplayerGamePacket(type, target, senderUuid, senderName, gameId, data);
        }

        @Override
        public void encode(FriendlyByteBuf buf, MultiplayerGamePacket packet) {
            buf.writeVarInt(packet.packetType.ordinal());
            buf.writeBoolean(packet.targetPlayer != null);
            if (packet.targetPlayer != null) {
                buf.writeUUID(packet.targetPlayer);
            }
            buf.writeBoolean(packet.senderUuid != null);
            if (packet.senderUuid != null) {
                buf.writeUUID(packet.senderUuid);
            }
            buf.writeUtf(safeUtf(packet.senderName, MAX_NAME_BYTES, "senderName"), MAX_NAME_BYTES);
            buf.writeUtf(safeUtf(packet.gameId, MAX_GAME_ID_BYTES, "gameId"), MAX_GAME_ID_BYTES);
            buf.writeUtf(safeUtf(packet.data, MAX_DATA_BYTES, "data"), MAX_DATA_BYTES);
        }
    };

    /**
     * 按 UTF-8 编码字节数安全截断字符串，避免超长内容写入时抛异常导致断连。
     * 超长时记录警告并截断（按码点边界截断，不会截出半个字符）。
     */
    private static String safeUtf(String s, int maxBytes, String fieldName) {
        if (s == null) return "";
        if (ByteBufUtil.utf8Bytes(s) <= maxBytes) return s;
        LOGGER.warn("[游戏机联机] 字段 {} 超过 {} 字节上限，已截断（原长 {} 字符）", fieldName, maxBytes, s.length());
        int bytes = 0;
        int i = 0;
        while (i < s.length()) {
            int cp = s.codePointAt(i);
            int cb;
            if (cp <= 0x7F) cb = 1;
            else if (cp <= 0x7FF) cb = 2;
            else if (cp <= 0xFFFF) cb = 3;
            else cb = 4;
            if (bytes + cb > maxBytes) break;
            bytes += cb;
            i += Character.charCount(cp);
        }
        return s.substring(0, i);
    }

    // 兼容旧代码的 getter
    public PacketType getType() { return packetType; }
    public UUID getTargetPlayer() { return targetPlayer; }
    public UUID getSenderUuid() { return senderUuid; }
    public String getSenderName() { return senderName; }
    public String getGameId() { return gameId; }
    public String getData() { return data; }

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }

    /**
     * 客户端收到包：路由到 MultiplayerLobbyScreen 处理
     */
    public static void handleClient(MultiplayerGamePacket packet, IPayloadContext context) {
        context.enqueueWork(() -> {
            MultiplayerLobbyScreen.handleIncomingPacket(packet);
        });
    }

    /**
     * 服务端收到包：校验并转发给目标玩家或处理请求。
     * 安全要点：转发前一律重建包，用 context.player() 的真实 UUID/名字
     * 覆盖发送者信息，防止客户端伪造身份或冒名注入走法/状态。
     */
    public static void handleServer(MultiplayerGamePacket packet, IPayloadContext context) {
        context.enqueueWork(() -> {
            var sender = context.player();
            var server = sender.getServer();
            if (server == null) return;

            switch (packet.packetType()) {
                case REQUEST_PLAYERS -> {
                    // 收集在线玩家列表发回给请求者
                    StringBuilder sb = new StringBuilder();
                    for (var p : server.getPlayerList().getPlayers()) {
                        if (!p.getUUID().equals(sender.getUUID())) {
                            if (!sb.isEmpty()) sb.append(";");
                            sb.append(p.getUUID()).append(",").append(p.getGameProfile().getName());
                        }
                    }
                    var response = new MultiplayerGamePacket(
                            PacketType.PLAYER_LIST, sender.getUUID(), null, "", "", sb.toString()
                    );
                    net.neoforged.neoforge.network.PacketDistributor.sendToPlayer(
                            (net.minecraft.server.level.ServerPlayer) sender, response);
                }
                case INVITE, ACCEPT_INVITE, DECLINE_INVITE, INVITE_CANCELLED,
                     GAME_MOVE, GAME_STATE_SYNC, GAME_OVER, LEAVE_GAME -> {
                    // 转发给目标玩家
                    UUID target = packet.targetPlayer();
                    if (target == null) return;
                    // 不允许给自己转发，防止回环或自发自收伪造
                    if (target.equals(sender.getUUID())) return;
                    var targetPlayer = server.getPlayerList().getPlayer(target);
                    if (targetPlayer == null) return;
                    // 重建包：target 保持为收件人，发送者身份由服务端盖章（忽略客户端自报字段）
                    var forwarded = new MultiplayerGamePacket(
                            packet.packetType(),
                            target,
                            sender.getUUID(),
                            sender.getGameProfile().getName(),
                            packet.gameId(),
                            packet.data()
                    );
                    net.neoforged.neoforge.network.PacketDistributor.sendToPlayer(targetPlayer, forwarded);
                }
                default -> {}
            }
        });
    }
}
