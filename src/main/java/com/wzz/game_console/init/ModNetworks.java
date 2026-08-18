package com.wzz.game_console.init;

import com.wzz.game_console.ModMain;
import com.wzz.game_console.network.GameSelectorPacket;
import com.wzz.game_console.network.MultiplayerGamePacket;
import net.neoforged.neoforge.network.event.RegisterPayloadHandlersEvent;
import net.neoforged.neoforge.network.registration.PayloadRegistrar;

/**
 * 网络包注册中心（公共注册）
 * 客户端专用注册在 ClientPayloadHandler 中。
 */
public class ModNetworks {

    /** 兼容旧代码的 PACKET_HANDLER（委托到 PacketDistributor） */
    public static final PacketHandlerCompat PACKET_HANDLER = new PacketHandlerCompat();

    public static void register(final RegisterPayloadHandlersEvent event) {
        final PayloadRegistrar registrar = event.registrar(ModMain.MODID).versioned("1.1.0");

        // GameSelectorPacket: 注册类型和编解码，服务端用无操作处理器
        // （客户端处理器由 ClientPayloadHandler 注册）
        registrar.playToClient(
                GameSelectorPacket.TYPE,
                GameSelectorPacket.STREAM_CODEC,
                GameSelectorPacket::handle
        );

        // MultiplayerGamePacket: 服务端接收处理
        // （客户端接收处理由 ClientPayloadHandler 注册）
        registrar.playToServer(
                MultiplayerGamePacket.TYPE,
                MultiplayerGamePacket.STREAM_CODEC,
                (packet, context) -> MultiplayerGamePacket.handleServer(packet, context)
        );
    }

    /**
     * 兼容旧代码中 ModNetworks.PACKET_HANDLER.sendToServer(...) 的调用方式
     */
    public static class PacketHandlerCompat {
        public void sendToServer(Object packet) {
            if (packet instanceof MultiplayerGamePacket mp) {
                net.neoforged.neoforge.network.PacketDistributor.sendToServer(mp);
            }
        }

        public void sendToPlayer(Object target, Object packet) {
            if (target instanceof net.minecraft.server.level.ServerPlayer sp && packet instanceof MultiplayerGamePacket mp) {
                net.neoforged.neoforge.network.PacketDistributor.sendToPlayer(sp, mp);
            }
        }
    }
}