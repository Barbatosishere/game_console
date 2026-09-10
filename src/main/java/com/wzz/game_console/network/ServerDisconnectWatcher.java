package com.wzz.game_console.network;

import net.minecraft.server.level.ServerPlayer;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;

/**
 * 服务端断线看门狗：玩家退出服务器时向其余在线玩家广播 PLAYER_QUIT。
 * 客户端收到后若退出者正是自己的对局对端，按"对方退出对局"处理并关闭界面，
 * 避免对端停留在"等待对方走棋"的死等状态（此前只能靠对方主动发 LEAVE_GAME）。
 * 在 ModMain 构造器中通过 NeoForge.EVENT_BUS.addListener 注册。
 */
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
