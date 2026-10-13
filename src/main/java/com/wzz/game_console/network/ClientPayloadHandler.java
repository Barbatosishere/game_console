package com.wzz.game_console.network;

import com.wzz.game_console.client.screens.GameSelectorScreen;
import com.wzz.game_console.client.screens.MultiplayerLobbyScreen;
import com.wzz.game_console.ModMain;
import net.minecraft.client.Minecraft;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.api.distmarker.OnlyIn;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.ClientPlayerNetworkEvent;
import net.neoforged.neoforge.network.handling.IPayloadContext;

/** 客户端网络处理与断线清理，专服不会注册此订阅者。 */
@OnlyIn(Dist.CLIENT)
@EventBusSubscriber(modid = ModMain.MODID, value = Dist.CLIENT)
public class ClientPayloadHandler {

    @SubscribeEvent
    public static void onLogout(ClientPlayerNetworkEvent.LoggingOut event) {
        MultiplayerLobbyScreen.clearInvitations();
    }

    public static void handleGameSelector(GameSelectorPacket packet, IPayloadContext context) {
        context.enqueueWork(() -> Minecraft.getInstance().setScreen(new GameSelectorScreen()));
    }

    public static void handleMultiplayerClient(MultiplayerGamePacket packet, IPayloadContext context) {
        context.enqueueWork(() -> MultiplayerLobbyScreen.handleIncomingPacket(packet));
    }
}
