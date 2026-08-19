package com.wzz.game_console.util;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.reflect.TypeToken;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;

/**
 * 游戏外部设置管理器。从 data/ 目录加载 JSON 设置文件，
 * 供各游戏在初始化时读取自定义参数（难度、速度、开关等）。
 *
 * 设置文件格式（data/game_settings.json）：
 * {
 *   "icefire": { "difficulty": 2 },
 *   "tetris": { "level": 3, "speed": 1.5 },
 *   "minesweeper": { "gridSize": 16, "mineCount": 40 }
 * }
 */
public class GameSettings {

    private static final Logger LOGGER = LoggerFactory.getLogger("GameConsole");
    private static final String SETTINGS_FILE = "game_settings.json";

    private static Map<String, Map<String, Object>> settings = new HashMap<>();
    private static boolean loaded = false;

    /** 获取某游戏的一个整型设置项 */
    public static int getInt(String gameId, String key, int defaultValue) {
        ensureLoaded();
        var game = settings.get(gameId);
        if (game == null) return defaultValue;
        Object val = game.get(key);
        if (val instanceof Number) return ((Number) val).intValue();
        return defaultValue;
    }

    /** 获取某游戏的一个浮点设置项 */
    public static double getDouble(String gameId, String key, double defaultValue) {
        ensureLoaded();
        var game = settings.get(gameId);
        if (game == null) return defaultValue;
        Object val = game.get(key);
        if (val instanceof Number) return ((Number) val).doubleValue();
        return defaultValue;
    }

    /** 获取某游戏的一个字符串设置项 */
    public static String getString(String gameId, String key, String defaultValue) {
        ensureLoaded();
        var game = settings.get(gameId);
        if (game == null) return defaultValue;
        Object val = game.get(key);
        return val instanceof String ? (String) val : defaultValue;
    }

    /** 获取某游戏的一个布尔设置项 */
    public static boolean getBoolean(String gameId, String key, boolean defaultValue) {
        ensureLoaded();
        var game = settings.get(gameId);
        if (game == null) return defaultValue;
        Object val = game.get(key);
        return val instanceof Boolean ? (Boolean) val : defaultValue;
    }

    /** 从外部文件导入设置并保存到 data 目录 */
    public static boolean importFromFile(Path sourcePath) {
        try {
            String content = Files.readString(sourcePath, StandardCharsets.UTF_8);
            Gson gson = new Gson();
            java.lang.reflect.Type type = new TypeToken<Map<String, Map<String, Object>>>() {}.getType();
            Map<String, Map<String, Object>> imported = gson.fromJson(content, type);
            if (imported == null) {
                LOGGER.warn("导入设置文件为空: {}", sourcePath);
                return false;
            }
            settings = imported;
            loaded = true;
            // 保存到 data 目录持久化
            saveToDataDir();
            LOGGER.info("游戏设置已导入: {} ({} 个游戏)", sourcePath.getFileName(), settings.size());
            return true;
        } catch (Exception e) {
            LOGGER.error("导入设置失败: {}", e.getMessage());
            return false;
        }
    }

    /** 确保设置已加载 */
    private static void ensureLoaded() {
        if (loaded) return;
        try {
            Path dataDir = ExternalFileManager.getDataDir();
            Path settingsPath = dataDir.resolve(SETTINGS_FILE);
            if (Files.exists(settingsPath)) {
                String content = Files.readString(settingsPath, StandardCharsets.UTF_8);
                Gson gson = new Gson();
                java.lang.reflect.Type type = new TypeToken<Map<String, Map<String, Object>>>() {}.getType();
                Map<String, Map<String, Object>> loadedSettings = gson.fromJson(content, type);
                if (loadedSettings != null) {
                    settings = loadedSettings;
                }
            }
        } catch (Exception e) {
            LOGGER.warn("加载游戏设置失败（使用默认值）: {}", e.getMessage());
        }
        loaded = true;
    }

    /** 保存设置到 data 目录 */
    private static void saveToDataDir() {
        try {
            Path dataDir = ExternalFileManager.getDataDir();
            if (!Files.exists(dataDir)) Files.createDirectories(dataDir);
            Path settingsPath = dataDir.resolve(SETTINGS_FILE);
            Gson gson = new GsonBuilder().setPrettyPrinting().create();
            String json = gson.toJson(settings);
            Files.writeString(settingsPath, json, StandardCharsets.UTF_8);
            LOGGER.info("游戏设置已保存到: {}", settingsPath);
        } catch (IOException e) {
            LOGGER.error("保存游戏设置失败: {}", e.getMessage());
        }
    }

    /** 获取所有已加载的游戏 ID 列表 */
    public static java.util.Set<String> getConfiguredGames() {
        ensureLoaded();
        return settings.keySet();
    }

    /** 获取某游戏的所有设置项 */
    public static Map<String, Object> getGameSettings(String gameId) {
        ensureLoaded();
        return settings.getOrDefault(gameId, new HashMap<>());
    }
}