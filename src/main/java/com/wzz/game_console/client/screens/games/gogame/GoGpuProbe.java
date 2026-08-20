package com.wzz.game_console.client.screens.games.gogame;

/** OpenCL GPU 探针：检测 GPU 加速是否可用。 */
public final class GoGpuProbe {
    public static void main(String[] args) {
        System.out.println("=== OpenCL GPU 探针 ===");
        try (OpenCLBackend backend = new OpenCLBackend()) {
            if (backend.isAvailable()) {
                System.out.println("SUCCESS: OpenCL GPU 加速可用");
                System.out.println("设备: " + backend.getDeviceName());
            } else {
                System.out.println("FAILED: OpenCL 不可用，将回退 CPU");
            }
        }
    }
}