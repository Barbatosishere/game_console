package com.wzz.momoi_game_console.init;

import com.wzz.momoi_game_console.ModMain;
import com.wzz.momoi_game_console.items.MomoiGameConsoleItem;
import net.minecraft.world.item.Item;
import net.neoforged.neoforge.registries.DeferredItem;
import net.neoforged.neoforge.registries.DeferredRegister;

public class ModItems {
    public static final DeferredRegister.Items REGISTRY = DeferredRegister.createItems(ModMain.MODID);
    public static final DeferredItem<Item> game_console = REGISTRY.register("game_console", MomoiGameConsoleItem::new);
}
