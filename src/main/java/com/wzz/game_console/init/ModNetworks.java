package com.wzz.game_console.init;

import com.wzz.game_console.ModMain;
import com.wzz.game_console.network.GameSelectorPacket;
import com.wzz.game_console.network.MultiplayerGamePacket;
import net.neoforged.neoforge.network.event.RegisterPayloadHandlersEvent;
import net.neoforged.neoforge.network.handling.IPayloadContext;
import net.neoforged.neoforge.network.registration.PayloadRegistrar;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.lang.reflect.Method;

/**
 * 网络包注册中心
 * 客户端处理器通过 Class.forName 反射加载，避免服务端引用客户端类。
 */
public class ModNetworks {

    private static final Logger LOGGER = LoggerFactory.getLogger(ModNetworks.class);
    private static final String CLIENT_HANDLER = "com.wzz.game_console.network.ClientPayloadHandler";

    /** 反射 Method 缓存：客户端处理器只查找一次，避免每个包都 Class.forName + getMethod */
    private static volatile Method handleGameSelectorMethod;
    private static volatile Method handleMultiplayerClientMethod;

    /** 兼容旧代码的 PACKET_HANDLER（委托到 PacketDistributor） */
    public static final PacketHandlerCompat PACKET_HANDLER = new PacketHandlerCompat();

    public static void register(final RegisterPayloadHandlersEvent event) {
        // ★ Bug修复：新增 INVITE_CANCELLED/PLAYER_QUIT 包类型后未升协议版本，
        //   新旧客户端混连时可能因 codec 校验被服务端拒收，这里同步升版
        final PayloadRegistrar registrar = event.registrar(ModMain.MODID).versioned("1.3.0");

        // GameSelectorPacket: 服务端→客户端（打开游戏选择器）
        // 处理器通过反射调用 ClientPayloadHandler，避免服务端加载客户端类
        registrar.playToClient(
                GameSelectorPacket.TYPE,
                GameSelectorPacket.STREAM_CODEC,
                (packet, context) -> {
                    context.enqueueWork(() -> {
                        try {
                            Method m = handleGameSelectorMethod;
                            if (m == null) {
                                synchronized (ModNetworks.class) {
                                    if (handleGameSelectorMethod == null) {
                                        handleGameSelectorMethod = Class.forName(CLIENT_HANDLER)
                                                .getMethod("handleGameSelector", GameSelectorPacket.class, IPayloadContext.class);
                                    }
                                    m = handleGameSelectorMethod;
                                }
                            }
                            m.invoke(null, packet, context);
                        } catch (Throwable t) {
                            // 反射失败（类缺失/初始化异常）不能静默吞掉，否则客户端收不到任何包且无从排查
                            LOGGER.error("[游戏机] 分发 GameSelectorPacket 失败", t);
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
                                Method m = handleMultiplayerClientMethod;
                                if (m == null) {
                                    synchronized (ModNetworks.class) {
                                        if (handleMultiplayerClientMethod == null) {
                                            handleMultiplayerClientMethod = Class.forName(CLIENT_HANDLER)
                                                    .getMethod("handleMultiplayerClient", MultiplayerGamePacket.class, IPayloadContext.class);
                                        }
                                        m = handleMultiplayerClientMethod;
                                    }
                                }
                                m.invoke(null, packet, context);
                            } catch (Throwable t) {
                                LOGGER.error("[游戏机] 分发 MultiplayerGamePacket({}) 失败", packet.getType(), t);
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