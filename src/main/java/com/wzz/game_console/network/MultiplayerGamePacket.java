package com.wzz.game_console.network;

import com.wzz.game_console.ModMain;
import com.wzz.game_console.client.screens.MultiplayerLobbyScreen;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.neoforged.neoforge.network.handling.IPayloadContext;

import java.util.UUID;

/**
 * 多人游戏网络包（双向）
 * 客户端 → 服务端：发送邀请、走法、状态同步等
 * 服务端 → 客户端：转发给目标玩家
 */
public record MultiplayerGamePacket(
        PacketType packetType,
        UUID targetPlayer,
        String gameId,
        String data
) implements CustomPacketPayload {

    public enum PacketType {
        INVITE,
        ACCEPT_INVITE,
        DECLINE_INVITE,
        GAME_MOVE,
        GAME_STATE_SYNC,
        GAME_OVER,
        LEAVE_GAME,
        REQUEST_PLAYERS,
        PLAYER_LIST
    }

    public static final Type<MultiplayerGamePacket> TYPE =
            new Type<>(ResourceLocation.fromNamespaceAndPath(ModMain.MODID, "multiplayer_game"));

    public static final StreamCodec<FriendlyByteBuf, MultiplayerGamePacket> STREAM_CODEC = new StreamCodec<>() {
        @Override
        public MultiplayerGamePacket decode(FriendlyByteBuf buf) {
            PacketType type = PacketType.values()[buf.readVarInt()];
            boolean hasTarget = buf.readBoolean();
            UUID target = hasTarget ? buf.readUUID() : null;
            String gameId = buf.readUtf(256);
            String data = buf.readUtf(32767);
            return new MultiplayerGamePacket(type, target, gameId, data);
        }

        @Override
        public void encode(FriendlyByteBuf buf, MultiplayerGamePacket packet) {
            buf.writeVarInt(packet.packetType.ordinal());
            buf.writeBoolean(packet.targetPlayer != null);
            if (packet.targetPlayer != null) {
                buf.writeUUID(packet.targetPlayer);
            }
            buf.writeUtf(packet.gameId, 256);
            buf.writeUtf(packet.data, 32767);
        }
    };

    // 兼容旧代码的构造器
    public MultiplayerGamePacket() {
        this(PacketType.REQUEST_PLAYERS, null, "", "");
    }

    public MultiplayerGamePacket(String data) {
        this(PacketType.GAME_STATE_SYNC, null, "", data);
    }

    // 兼容旧代码的 getter
    public PacketType getType() { return packetType; }
    public UUID getTargetPlayer() { return targetPlayer; }
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
     * 服务端收到包：转发给目标玩家或处理请求
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
                            PacketType.PLAYER_LIST, sender.getUUID(), "", sb.toString()
                    );
                    net.neoforged.neoforge.network.PacketDistributor.sendToPlayer(
                            (net.minecraft.server.level.ServerPlayer) sender, response);
                }
                case INVITE, ACCEPT_INVITE, DECLINE_INVITE, GAME_MOVE, GAME_STATE_SYNC, GAME_OVER, LEAVE_GAME -> {
                    // 转发给目标玩家
                    UUID target = packet.targetPlayer();
                    if (target != null) {
                        var targetPlayer = server.getPlayerList().getPlayer(target);
                        if (targetPlayer != null) {
                            // 转发时把发送者信息附加到 data（对于 INVITE，data 是发送者名字）
                            net.neoforged.neoforge.network.PacketDistributor.sendToPlayer(targetPlayer, packet);
                        }
                    }
                }
                default -> {}
            }
        });
    }
}
