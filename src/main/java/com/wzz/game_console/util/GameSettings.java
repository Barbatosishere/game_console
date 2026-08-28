package com.wzz.game_console.util;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.reflect.TypeToken;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

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
    public static final String SETTINGS_FILE = "game_settings.json";

    // ★ Bug修复：原版 HashMap,读侧(IceFireGameScreen render 读 getConfiguredGames)
    //   与写侧(GoGameScreen.saveSettings 整体 swap)无并发保护,会导致 CME
    //   或读到 partial 状态。改为 ConcurrentHashMap(非 final,允许整体替换)
    private static java.util.concurrent.ConcurrentHashMap<String, Map<String, Object>> settings
            = new java.util.concurrent.ConcurrentHashMap<>();
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
            // ★ Bug修复：原版无 null key 防御,GSON 允许外层 key 为 null,
            //   后续 settings.get(null) 在某些 map 实现下抛 NPE/CCE
            imported.entrySet().removeIf(e -> e.getKey() == null);
            // 用 ConcurrentHashMap 包装保障并发读安全
            java.util.concurrent.ConcurrentHashMap<String, Map<String, Object>> newMap =
                    new java.util.concurrent.ConcurrentHashMap<>();
            newMap.putAll(imported);
            settings = newMap;
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
                    // ★ Bug修复：null key 过滤同 importFromFile
                    loadedSettings.entrySet().removeIf(e -> e.getKey() == null);
                    java.util.concurrent.ConcurrentHashMap<String, Map<String, Object>> newMap =
                            new java.util.concurrent.ConcurrentHashMap<>();
                    newMap.putAll(loadedSettings);
                    settings = newMap;
                }
            }
        } catch (Exception e) {
            // ★ Bug修复：原版 JSON 损坏仍设 loaded=true,后续 getInt/getString 永不重试
            //   损坏文件,玩家只能重启游戏。改为解析失败时 loaded=false,
            //   下次 ensureLoaded() 会再次尝试读取
            LOGGER.warn("加载游戏设置失败（使用默认值，下次访问会重试）: {}", e.getMessage());
            loaded = false;
            return;
        }
        loaded = true;
    }

    /** 保存设置到 data 目录 */
    private static void saveToDataDir() {
        try {
            Gson gson = new GsonBuilder().setPrettyPrinting().create();
            String json = gson.toJson(settings);
            // ★ 修复：改走 ExternalFileManager.writeTextFile 的原子写（.tmp + ATOMIC_MOVE），
            //   原先直接 Files.writeString 在 JVM 崩溃/断电时会把 game_settings.json 截断为 0 字节
            boolean ok = ExternalFileManager.writeTextFile(ExternalFileManager.DATA_FOLDER, SETTINGS_FILE, json);
            if (ok) {
                LOGGER.info("游戏设置已保存到 data/{}", SETTINGS_FILE);
            } else {
                LOGGER.error("保存游戏设置失败");
            }
        } catch (Exception e) {
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
        return settings.getOrDefault(gameId, new java.util.concurrent.ConcurrentHashMap<>());
    }
}