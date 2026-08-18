package com.wzz.game_console.network;

import com.wzz.game_console.client.screens.GameSelectorScreen;
import com.wzz.game_console.client.screens.MultiplayerLobbyScreen;
import net.minecraft.client.Minecraft;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.api.distmarker.OnlyIn;
import net.neoforged.neoforge.network.handling.IPayloadContext;

/**
 * 客户端专用网络包处理器。
 * 不注册为 @EventBusSubscriber，由 ModNetworks 通过 Class.forName 反射调用，
 * 避免服务端加载此类时引用客户端类导致崩溃。
 */
@OnlyIn(Dist.CLIENT)
public class ClientPayloadHandler {

    public static void handleGameSelector(GameSelectorPacket packet, IPayloadContext context) {
        context.enqueueWork(() -> {
            Minecraft.getInstance().setScreen(new GameSelectorScreen());
        });
    }

    public static void handleMultiplayerClient(MultiplayerGamePacket packet, IPayloadContext context) {
        context.enqueueWork(() -> {
            MultiplayerLobbyScreen.handleIncomingPacket(packet);
        });
    }
}