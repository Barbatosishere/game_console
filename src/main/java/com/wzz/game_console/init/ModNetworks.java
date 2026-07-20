package com.wzz.game_console.init;

import com.wzz.game_console.ModMain;
import com.wzz.game_console.network.GameSelectorPacket;
import com.wzz.game_console.network.MultiplayerGamePacket;
import net.neoforged.neoforge.network.PacketDistributor;
import net.neoforged.neoforge.network.event.RegisterPayloadHandlersEvent;
import net.neoforged.neoforge.network.registration.PayloadRegistrar;

/**
 * 网络包注册中心
 */
public class ModNetworks {

    /** 兼容旧代码的 PACKET_HANDLER（委托到 PacketDistributor） */
    public static final PacketHandlerCompat PACKET_HANDLER = new PacketHandlerCompat();

    public static void register(final RegisterPayloadHandlersEvent event) {
        final PayloadRegistrar registrar = event.registrar(ModMain.MODID).versioned("1.0.0");

        // 服务端 → 客户端：打开游戏选择器
        registrar.playToClient(
                GameSelectorPacket.TYPE,
                GameSelectorPacket.STREAM_CODEC,
                GameSelectorPacket::handle
        );

        // 双向：多人游戏包
        registrar.playBidirectional(
                MultiplayerGamePacket.TYPE,
                MultiplayerGamePacket.STREAM_CODEC,
                (packet, context) -> {
                    if (context.player().level().isClientSide()) {
                        MultiplayerGamePacket.handleClient(packet, context);
                    } else {
                        MultiplayerGamePacket.handleServer(packet, context);
                    }
                }
        );
    }

    /**
     * 兼容旧代码中 ModNetworks.PACKET_HANDLER.sendToServer(...) 的调用方式
     */
    public static class PacketHandlerCompat {
        public void sendToServer(Object packet) {
            if (packet instanceof MultiplayerGamePacket mp) {
                PacketDistributor.sendToServer(mp);
            }
        }

        public void sendToPlayer(Object target, Object packet) {
            if (target instanceof net.minecraft.server.level.ServerPlayer sp && packet instanceof MultiplayerGamePacket mp) {
                PacketDistributor.sendToPlayer(sp, mp);
            }
        }
    }
}
