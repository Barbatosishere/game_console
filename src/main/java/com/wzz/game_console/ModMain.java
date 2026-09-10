package com.wzz.game_console;

import com.wzz.game_console.init.ModItems;
import com.wzz.game_console.init.ModNetworks;
import com.wzz.game_console.init.ModTabs;
import com.wzz.game_console.network.ServerDisconnectWatcher;
import com.wzz.game_console.util.ExternalFileManager;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.common.Mod;
import net.neoforged.fml.event.lifecycle.FMLCommonSetupEvent;
import net.neoforged.fml.ModContainer;
import net.neoforged.neoforge.common.NeoForge;

@Mod(ModMain.MODID)
public class ModMain {

    public static final String MODID = "game_console";

    public ModMain(IEventBus modEventBus, ModContainer modContainer) {
        modEventBus.addListener(this::commonSetup);
        modEventBus.addListener(ModNetworks::register);
        ModItems.REGISTRY.register(modEventBus);
        ModTabs.REGISTRY.register(modEventBus);
        // 服务端断线看门狗：玩家退出服务器时广播 PLAYER_QUIT（客户端据此关闭死等的对局界面）
        NeoForge.EVENT_BUS.addListener(ServerDisconnectWatcher::onPlayerLoggedOut);
    }

    private void commonSetup(final FMLCommonSetupEvent event) {
        // 在 .minecraft 目录下创建 game_console 外部文件夹
        ExternalFileManager.init();
    }
}
