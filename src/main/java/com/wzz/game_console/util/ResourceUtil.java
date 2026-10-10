package com.wzz.game_console.util;

import com.wzz.game_console.ModMain;
import net.minecraft.resources.ResourceLocation;

public final class ResourceUtil {
    private ResourceUtil() {}

    public static ResourceLocation createInstance(String path) {
        return createInstance(ModMain.MODID, path);
    }

    public static ResourceLocation createInstance(String namespace, String path) {
        return ResourceLocation.fromNamespaceAndPath(namespace, path);
    }

    public static ResourceLocation createMinecraftInstance(String path) {
        return createInstance("minecraft", path);
    }
}
