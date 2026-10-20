package com.wzz.game_console.client.screens.games.gogame;

import java.nio.file.Path;
import java.util.concurrent.TimeUnit;

/** A real launcher process whose engine child keeps the inherited pipes open. */
public final class EngineProcessFixture {
    public static void main(String[] args) throws Exception {
        if ("wrapper".equals(args[0])) {
            Process child = new ProcessBuilder(javaExecutable(), "-cp", args[1],
                    EngineProcessFixture.class.getName(), "engine").inheritIO().start();
            System.out.println(child.pid());
            System.out.flush();
            child.waitFor();
        } else {
            TimeUnit.MINUTES.sleep(2);
        }
    }

    static String javaExecutable() {
        String executable = System.getProperty("os.name").startsWith("Windows") ? "java.exe" : "java";
        return Path.of(System.getProperty("java.home"), "bin", executable).toString();
    }
}
