package com.wzz.game_console.client;

import net.minecraft.network.chat.Component;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.api.distmarker.OnlyIn;

@OnlyIn(Dist.CLIENT)
public final class GameText {
    private GameText() {}
    public static String text(String key, Object... arguments) {
        return Component.translatable(key, arguments).getString();
    }
}
