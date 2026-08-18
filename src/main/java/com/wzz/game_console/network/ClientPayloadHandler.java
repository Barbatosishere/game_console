package com.wzz.game_console.network;

import com.wzz.game_console.ModMain;
import com.wzz.game_console.client.screens.GameSelectorScreen;
import com.wzz.game_console.client.screens.MultiplayerLobbyScreen;
import net.minecraft.client.Minecraft;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.api.distmarker.OnlyIn;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.network.event.RegisterPayloadHandlersEvent;
import net.neoforged.neoforge.network.handling.IPayloadContext;
import net.neoforged.neoforge.network.registration.PayloadRegistrar;

/**
 * 客户端专用网络包处理器。
 * 通过 @EventBusSubscriber(value = Dist.CLIENT) 确保只在客户端加载，
 * 服务端不会加载此类，避免引用客户端类导致崩溃。
 */
@OnlyIn(Dist.CLIENT)
@EventBusSubscriber(modid = ModMain.MODID, bus = EventBusSubscriber.Bus.MOD, value = Dist.CLIENT)
public class ClientPayloadHandler {

    @SubscribeEvent
    public static void onRegisterPayloadHandlers(RegisterPayloadHandlersEvent event) {
        final PayloadRegistrar registrar = event.registrar(ModMain.MODID).versioned("1.1.0");

        // 游戏选择器包：服务端→客户端
        registrar.playToClient(
                GameSelectorPacket.TYPE,
                GameSelectorPacket.STREAM_CODEC,
                ClientPayloadHandler::handleGameSelector
        );

        // 多人游戏包（客户端接收端）：服务端→客户端
        registrar.playToClient(
                MultiplayerGamePacket.TYPE,
                MultiplayerGamePacket.STREAM_CODEC,
                ClientPayloadHandler::handleMultiplayerClient
        );
    }

    private static void handleGameSelector(GameSelectorPacket packet, IPayloadContext context) {
        context.enqueueWork(() -> {
            Minecraft.getInstance().setScreen(new GameSelectorScreen());
        });
    }

    private static void handleMultiplayerClient(MultiplayerGamePacket packet, IPayloadContext context) {
        context.enqueueWork(() -> {
            MultiplayerLobbyScreen.handleIncomingPacket(packet);
        });
    }
}