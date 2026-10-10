package com.wzz.game_console.network;

import net.minecraft.server.level.ServerPlayer;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;

/** 广播玩家断线事件，让客户端结束对应的联机对局。 */
public final class ServerDisconnectWatcher {

    private ServerDisconnectWatcher() {}

    public static void onPlayerLoggedOut(PlayerEvent.PlayerLoggedOutEvent event) {
        if (!(event.getEntity() instanceof ServerPlayer quitter)) return;
        var server = quitter.getServer();
        if (server == null) return;
        // data 携带退出者 UUID；senderName 盖章为退出者名字，客户端直接用于提示
        var notify = new MultiplayerGamePacket(
                MultiplayerGamePacket.PacketType.PLAYER_QUIT,
                null,
                quitter.getUUID(),
                quitter.getGameProfile().getName(),
                "",
                quitter.getUUID().toString());
        for (ServerPlayer p : server.getPlayerList().getPlayers()) {
            if (!p.getUUID().equals(quitter.getUUID())) {
                net.neoforged.neoforge.network.PacketDistributor.sendToPlayer(p, notify);
            }
        }
    }
}
