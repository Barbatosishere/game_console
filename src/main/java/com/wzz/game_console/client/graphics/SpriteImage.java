package com.wzz.game_console.client.graphics;

import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.resources.ResourceLocation;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.api.distmarker.OnlyIn;

/** A reusable, validated region of a texture. The game owns its position and collision state. */
@OnlyIn(Dist.CLIENT)
public record SpriteImage(ResourceLocation texture, int textureWidth, int textureHeight,
                          int u, int v, int width, int height) {
    public SpriteImage {
        java.util.Objects.requireNonNull(texture);
        if (textureWidth < 1 || textureHeight < 1 || width < 1 || height < 1 || u < 0 || v < 0
                || (long) u + width > textureWidth || (long) v + height > textureHeight) {
            throw new IllegalArgumentException("Sprite region must fit inside its texture");
        }
    }
    public void draw(GuiGraphics graphics, int x, int y) {
        graphics.blit(texture, x, y, 0, u, v, width, height, textureWidth, textureHeight);
    }
}
