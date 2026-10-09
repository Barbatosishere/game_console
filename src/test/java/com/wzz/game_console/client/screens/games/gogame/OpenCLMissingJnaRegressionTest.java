package com.wzz.game_console.client.screens.games.gogame;

import org.junit.jupiter.api.Test;

import java.net.URL;
import java.net.URLClassLoader;

class OpenCLMissingJnaRegressionTest {
    @Test
    void missingJnaInitializesCpuFallbackAndPreservesSharedCooldown() throws Exception {
        URL mainClasses = OpenCLBackend.class.getProtectionDomain().getCodeSource().getLocation();
        URL testClasses = getClass().getProtectionDomain().getCodeSource().getLocation();
        // A separate loader with only platform classes reproduces a player
        // environment where the optional JNA dependency is completely absent.
        try (URLClassLoader isolated = new URLClassLoader(new URL[]{mainClasses, testClasses},
                ClassLoader.getPlatformClassLoader())) {
            Runnable probe = (Runnable) isolated.loadClass(MissingJnaProbe.class.getName())
                    .getConstructor().newInstance();
            probe.run();
        }
    }

    public static final class MissingJnaProbe implements Runnable {
        @Override
        public void run() {
            if (OpenCLBackend.acquireShared() != null) {
                throw new AssertionError("Missing JNA must select CPU fallback");
            }
            if (OpenCLBackend.lastSharedFailure() == null) {
                throw new AssertionError("Failed initialization must record the shared cooldown");
            }
            for (int i = 0; i < 25; i++) {
                if (OpenCLBackend.acquireShared() != null) {
                    throw new AssertionError("Repeated acquisition must stay on CPU during cooldown");
                }
            }
        }
    }
}
