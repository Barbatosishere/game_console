package com.wzz.game_console.network;

import com.wzz.game_console.ModMain;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.neoforged.neoforge.network.handling.IPayloadContext;

/**
 * 服务端 → 客户端：通知打开游戏选择界面
 * 注：客户端处理器在 ClientPayloadHandler 中注册，避免服务端加载客户端类。
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

    /** 服务端侧无操作，客户端处理器由 ClientPayloadHandler 注册 */
    public static void handle(GameSelectorPacket packet, IPayloadContext context) {}
}