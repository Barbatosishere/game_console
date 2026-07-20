package com.wzz.momoi_game_console;

import com.wzz.momoi_game_console.init.ModItems;
import com.wzz.momoi_game_console.init.ModNetworks;
import com.wzz.momoi_game_console.init.ModTabs;
import com.wzz.momoi_game_console.util.ExternalFileManager;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.common.Mod;
import net.neoforged.fml.event.lifecycle.FMLCommonSetupEvent;
import net.neoforged.fml.ModContainer;

@Mod(ModMain.MODID)
public class ModMain {

    public static final String MODID = "game_console";

    public ModMain(IEventBus modEventBus, ModContainer modContainer) {
        modEventBus.addListener(this::commonSetup);
        modEventBus.addListener(ModNetworks::register);
        ModItems.REGISTRY.register(modEventBus);
        ModTabs.REGISTRY.register(modEventBus);
    }

    private void commonSetup(final FMLCommonSetupEvent event) {
        // 在 .minecraft 目录下创建 game_console 外部文件夹
        ExternalFileManager.init();
    }
}
