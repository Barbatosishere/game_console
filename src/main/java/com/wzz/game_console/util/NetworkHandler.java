package com.wzz.game_console.util;

import com.wzz.game_console.network.GameSelectorPacket;
import com.wzz.game_console.network.MultiplayerGamePacket;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.neoforge.network.PacketDistributor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 网络发包工具类
 */
public class NetworkHandler {

    private static final Logger LOGGER = LoggerFactory.getLogger("GameConsole");

    /** 发送包给指定玩家（服务端调用） */
    public static void sendToPlayer(Object packet, ServerPlayer player) {
        if (packet instanceof GameSelectorPacket gsp) {
            PacketDistributor.sendToPlayer(player, gsp);
        } else if (packet instanceof MultiplayerGamePacket mp) {
            PacketDistributor.sendToPlayer(player, mp);
        } else {
            LOGGER.warn("NetworkHandler.sendToPlayer: 未处理的包类型 {}", packet != null ? packet.getClass().getName() : "null");
        }
    }
}
