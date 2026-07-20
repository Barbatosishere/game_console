package com.wzz.momoi_game_console.network;

import com.wzz.momoi_game_console.ModMain;
import com.wzz.momoi_game_console.client.screens.GameSelectorScreen;
import net.minecraft.client.Minecraft;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.neoforged.neoforge.network.handling.IPayloadContext;

/**
 * 服务端 → 客户端：通知打开游戏选择界面
 */
public record GameSelectorPacket() implements CustomPacketPayload {

    public static final Type<GameSelectorPacket> TYPE =
            new Type<>(ResourceLocation.fromNamespaceAndPath(ModMain.MODID, "open_game_selector"));

    public static final StreamCodec<FriendlyByteBuf, GameSelectorPacket> STREAM_CODEC =
            StreamCodec.unit(new GameSelectorPacket());

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }

    public static void handle(GameSelectorPacket packet, IPayloadContext context) {
        context.enqueueWork(() -> {
            Minecraft.getInstance().setScreen(new GameSelectorScreen());
        });
    }
}
