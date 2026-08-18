package com.wzz.game_console.init;

import com.wzz.game_console.ModMain;
import com.wzz.game_console.network.GameSelectorPacket;
import com.wzz.game_console.network.MultiplayerGamePacket;
import net.neoforged.neoforge.network.event.RegisterPayloadHandlersEvent;
import net.neoforged.neoforge.network.handling.IPayloadContext;
import net.neoforged.neoforge.network.registration.PayloadRegistrar;

/**
 * 网络包注册中心
 * 客户端处理器通过 Class.forName 反射加载，避免服务端引用客户端类。
 */
public class ModNetworks {

    /** 兼容旧代码的 PACKET_HANDLER（委托到 PacketDistributor） */
    public static final PacketHandlerCompat PACKET_HANDLER = new PacketHandlerCompat();

    public static void register(final RegisterPayloadHandlersEvent event) {
        final PayloadRegistrar registrar = event.registrar(ModMain.MODID).versioned("1.1.0");

        // GameSelectorPacket: 服务端→客户端（打开游戏选择器）
        // 处理器通过反射调用 ClientPayloadHandler，避免服务端加载客户端类
        registrar.playToClient(
                GameSelectorPacket.TYPE,
                GameSelectorPacket.STREAM_CODEC,
                (packet, context) -> {
                    context.enqueueWork(() -> {
                        try {
                            Class.forName("com.wzz.game_console.network.ClientPayloadHandler")
                                    .getMethod("handleGameSelector", GameSelectorPacket.class, IPayloadContext.class)
                                    .invoke(null, packet, context);
                        } catch (Exception ignored) {
                            // 服务端无操作
                        }
                    });
                }
        );

        // MultiplayerGamePacket: 双向
        registrar.playBidirectional(
                MultiplayerGamePacket.TYPE,
                MultiplayerGamePacket.STREAM_CODEC,
                (packet, context) -> {
                    if (!context.flow().isClientbound()) {
                        // 服务端接收处理
                        MultiplayerGamePacket.handleServer(packet, context);
                    } else {
                        // 客户端接收处理：通过反射调用 ClientPayloadHandler
                        context.enqueueWork(() -> {
                            try {
                                Class.forName("com.wzz.game_console.network.ClientPayloadHandler")
                                        .getMethod("handleMultiplayerClient", MultiplayerGamePacket.class, IPayloadContext.class)
                                        .invoke(null, packet, context);
                            } catch (Exception ignored) {
                                // 服务端无操作
                            }
                        });
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