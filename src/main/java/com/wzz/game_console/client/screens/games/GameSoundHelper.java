package com.wzz.game_console.client.screens.games;

import net.minecraft.client.Minecraft;
import net.minecraft.client.resources.sounds.SimpleSoundInstance;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.sounds.SoundEvent;

import java.util.HashSet;
import java.util.Set;

final class GameSoundHelper {
    private static final Set<ResourceLocation> PLAYED_THIS_TICK = new HashSet<>();
    private static int lastTick = Integer.MIN_VALUE;

    private GameSoundHelper() {}

    static void playUiSound(Minecraft minecraft, SoundEvent sound, float pitch, float volume) {
        if (minecraft == null || sound == null) return;
        int tick = minecraft.gui.getGuiTicks();
        if (tick != lastTick) {
            PLAYED_THIS_TICK.clear();
            lastTick = tick;
        }
        // Channels are reclaimed by audio ticks, so a burst must not enqueue unlimited duplicates.
        if (PLAYED_THIS_TICK.add(sound.getLocation())) {
            minecraft.getSoundManager().play(SimpleSoundInstance.forUI(sound, pitch, volume));
        }
    }
}
