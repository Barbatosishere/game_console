package com.wzz.game_console.init;

import com.wzz.game_console.ModMain;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.CreativeModeTab;
import net.minecraft.world.item.ItemStack;
import net.neoforged.neoforge.registries.DeferredHolder;
import net.neoforged.neoforge.registries.DeferredRegister;

public class ModTabs {
	public static final DeferredRegister<CreativeModeTab> REGISTRY = DeferredRegister.create(Registries.CREATIVE_MODE_TAB, ModMain.MODID);

	public static final DeferredHolder<CreativeModeTab, CreativeModeTab> TAB_GAME_CONSOLE = REGISTRY.register("tab_game_console",
			() -> CreativeModeTab.builder().title(Component.translatable("itemGroup.game_console")).icon(() -> new ItemStack(ModItems.game_console.get())).displayItems((parameters, tabData) -> {
						tabData.accept(ModItems.game_console.get());
					})
					.build());
}
