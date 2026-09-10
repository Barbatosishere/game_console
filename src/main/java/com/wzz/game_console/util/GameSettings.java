package com.wzz.game_console.util;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.reflect.TypeToken;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 游戏外部设置管理器。从 data/ 目录加载 JSON 设置文件，
 * 供各游戏在初始化时读取自定义参数（难度、速度、开关等）。
 */
public class GameSettings {

    private static final Logger LOGGER = LoggerFactory.getLogger("GameConsole");
    public static final String SETTINGS_FILE = "game_settings.json";
    public static final int GO_SEARCH_TIME_MIN = 100;
    public static final int GO_SEARCH_TIME_MAX = 60_000;
    private static final long MAX_SETTINGS_BYTES = 1L * 1024 * 1024;
    private static final Object LOAD_LOCK = new Object();
    private static volatile Map<String, Map<String, Object>> settings = Collections.emptyMap();
    private static volatile boolean loaded;

    public static int getInt(String gameId, String key, int defaultValue) {
        Object val = getValue(gameId, key);
        if (!(val instanceof Number number)) return defaultValue;
        long value = number.longValue();
        long min = 0, max = Integer.MAX_VALUE;
        if ("go".equals(gameId) && "searchTime".equals(key)) {
            min = GO_SEARCH_TIME_MIN;
            max = GO_SEARCH_TIME_MAX;
        }
        else if ("go".equals(gameId) && "mctsIterations".equals(key)) { min = 1; max = 10_000_000; }
        else if ("chess".equals(gameId) && "pikafishMovetime".equals(key)) { min = 100; max = 120_000; }
        else if ("chess".equals(gameId) && "pikafishThreads".equals(key)) { min = 1; max = 64; }
        else if ("icefire".equals(gameId) && "difficulty".equals(key)) { min = 0; max = 2; }
        return (int) Math.max(min, Math.min(max, value));
    }

    public static double getDouble(String gameId, String key, double defaultValue) {
        Object val = getValue(gameId, key);
        if (!(val instanceof Number number)) return defaultValue;
        double value = number.doubleValue();
        if (!Double.isFinite(value)) return defaultValue;
        if ("go".equals(gameId) && "komi".equals(key)) {
            return Math.max(-100.0, Math.min(100.0, value));
        }
        return value;
    }

    public static String getString(String gameId, String key, String defaultValue) {
        Object val = getValue(gameId, key);
        return val instanceof String ? (String) val : defaultValue;
    }

    public static boolean getBoolean(String gameId, String key, boolean defaultValue) {
        Object val = getValue(gameId, key);
        return val instanceof Boolean ? (Boolean) val : defaultValue;
    }

    private static Object getValue(String gameId, String key) {
        ensureLoaded();
        if (gameId == null || key == null) return null;
        Map<String, Object> game = settings.get(gameId);
        return game == null ? null : game.get(key);
    }

    /** 从外部文件导入设置并保存到 data 目录。 */
    public static boolean importFromFile(Path sourcePath) {
        if (sourcePath == null) return false;
        try {
            if (!Files.isRegularFile(sourcePath) || Files.size(sourcePath) > MAX_SETTINGS_BYTES) {
                LOGGER.warn("设置文件不存在或超过 {} 字节: {}", MAX_SETTINGS_BYTES, sourcePath);
                return false;
            }
            String content = Files.readString(sourcePath, StandardCharsets.UTF_8);
            Gson gson = new Gson();
            java.lang.reflect.Type type = new TypeToken<Map<String, Map<String, Object>>>() {}.getType();
            Map<String, Map<String, Object>> imported = gson.fromJson(content, type);
            if (imported == null) {
                LOGGER.warn("导入设置文件为空: {}", sourcePath);
                return false;
            }
            Map<String, Map<String, Object>> snapshot = freezeSettings(imported);
            synchronized (LOAD_LOCK) {
                if (!saveToDataDir(snapshot)) {
                    LOGGER.warn("设置无法持久化到 data/{}，保留当前运行时配置", SETTINGS_FILE);
                    return false;
                }
                settings = snapshot;
                loaded = true;
            }
            LOGGER.info("游戏设置已导入: {} ({} 个游戏)", sourcePath.getFileName(), snapshot.size());
            return true;
        } catch (Exception e) {
            LOGGER.error("导入设置失败: {}", e.getMessage());
            return false;
        }
    }

    /** 确保设置已加载；初始化状态发布与设置快照交换均受同一把锁保护。 */
    private static void ensureLoaded() {
        if (loaded) return;
        synchronized (LOAD_LOCK) {
            if (loaded) return;
            Map<String, Map<String, Object>> loadedSnapshot = Collections.emptyMap();
            try {
                Path dataDir = ExternalFileManager.getDataDir();
                if (dataDir != null) {
                    Path settingsPath = dataDir.resolve(SETTINGS_FILE);
                    if (Files.exists(settingsPath) && Files.isRegularFile(settingsPath)
                            && Files.size(settingsPath) <= MAX_SETTINGS_BYTES) {
                        String content = Files.readString(settingsPath, StandardCharsets.UTF_8);
                        java.lang.reflect.Type type = new TypeToken<Map<String, Map<String, Object>>>() {}.getType();
                        Map<String, Map<String, Object>> parsed = new Gson().fromJson(content, type);
                        if (parsed != null) loadedSnapshot = freezeSettings(parsed);
                    }
                }
            } catch (Exception e) {
                LOGGER.warn("加载游戏设置失败，本次会话使用默认配置（不再重试）: {}", e.getMessage());
            }
            settings = loadedSnapshot;
            loaded = true;
        }
    }

    /** 创建不可变的深快照，避免调用方修改内部状态或并发读写。 */
    private static Map<String, Map<String, Object>> freezeSettings(Map<String, Map<String, Object>> source) {
        Map<String, Map<String, Object>> outer = new LinkedHashMap<>();
        for (Map.Entry<String, Map<String, Object>> entry : source.entrySet()) {
            String gameId = entry.getKey();
            Map<String, Object> values = entry.getValue();
            if (gameId == null || values == null) continue;
            Map<String, Object> inner = new LinkedHashMap<>();
            for (Map.Entry<String, Object> value : values.entrySet()) {
                if (value.getKey() != null && value.getValue() != null) {
                    inner.put(value.getKey(), freezeValue(value.getValue()));
                }
            }
            outer.put(gameId, Collections.unmodifiableMap(inner));
        }
        return Collections.unmodifiableMap(outer);
    }

    private static Object freezeValue(Object value) {
        if (value instanceof Map<?, ?> map) {
            Map<String, Object> copy = new LinkedHashMap<>();
            for (Map.Entry<?, ?> entry : map.entrySet()) {
                if (entry.getKey() instanceof String key && entry.getValue() != null) {
                    copy.put(key, freezeValue(entry.getValue()));
                }
            }
            return Collections.unmodifiableMap(copy);
        }
        if (value instanceof List<?> list) {
            List<Object> copy = new ArrayList<>(list.size());
            for (Object element : list) {
                if (element != null) copy.add(freezeValue(element));
            }
            return Collections.unmodifiableList(copy);
        }
        return value;
    }

    private static boolean saveToDataDir(Map<String, Map<String, Object>> snapshot) {
        try {
            String json = new GsonBuilder().setPrettyPrinting().create().toJson(snapshot);
            boolean ok = ExternalFileManager.writeTextFile(ExternalFileManager.DATA_FOLDER, SETTINGS_FILE, json);
            if (ok) LOGGER.info("游戏设置已保存到 data/{}", SETTINGS_FILE);
            else LOGGER.error("保存游戏设置失败");
            return ok;
        } catch (Exception e) {
            LOGGER.error("保存游戏设置失败: {}", e.getMessage());
            return false;
        }
    }

    /** 获取所有已加载的游戏 ID 列表的防御性快照。 */
    public static Set<String> getConfiguredGames() {
        ensureLoaded();
        return Collections.unmodifiableSet(new java.util.HashSet<>(settings.keySet()));
    }

    /** 获取某游戏所有设置项的防御性快照。 */
    public static Map<String, Object> getGameSettings(String gameId) {
        ensureLoaded();
        if (gameId == null) return Collections.emptyMap();
        Map<String, Object> game = settings.get(gameId);
        return game == null ? Collections.emptyMap() : Collections.unmodifiableMap(new LinkedHashMap<>(game));
    }
}
