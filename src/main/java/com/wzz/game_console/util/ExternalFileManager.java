package com.wzz.game_console.util;

import net.neoforged.fml.loading.FMLPaths;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.Collections;
import java.util.List;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * 外部文件管理器
 * 在 .minecraft 目录下创建 game_console 文件夹，并管理其子目录和文件读取。
 *
 * 目录结构：
 * .minecraft/game_console/
 *   ├── music/    (谱面文件 .pts)
 *   ├── voice/    (音频/语音文件)
 *   └── data/     (其他数据文件)
 */
public class ExternalFileManager {

    /** 日志记录器 */
    private static final Logger LOGGER = LoggerFactory.getLogger("GameConsole");

    /** 根文件夹名称 */
    public static final String ROOT_FOLDER = "game_console";
    /** 子文件夹名称 */
    public static final String MUSIC_FOLDER = "music";
    public static final String VOICE_FOLDER = "voice";
    public static final String DATA_FOLDER = "data";

    private static Path gameDir;
    private static Path rootDir;
    private static volatile boolean initialized = false;

    /**
     * 初始化：创建所有必要的文件夹。
     * 应在模组启动时调用（commonSetup 或 clientSetup）。
     */
    public static void init() {
        if (initialized) return;
        gameDir = FMLPaths.GAMEDIR.get();
        rootDir = gameDir.resolve(ROOT_FOLDER);
        try {
            // 创建根目录和子目录
            Files.createDirectories(rootDir);
            Files.createDirectories(rootDir.resolve(MUSIC_FOLDER));
            Files.createDirectories(rootDir.resolve(VOICE_FOLDER));
            Files.createDirectories(rootDir.resolve(DATA_FOLDER));

            initialized = true;
            LOGGER.info("外部文件夹已创建: {}", rootDir.toAbsolutePath());
        } catch (IOException e) {
            LOGGER.error("创建外部文件夹失败", e);
        }
    }

    /**
     * 获取 .minecraft 游戏目录
     */
    public static Path getGameDir() {
        if (!initialized) init();
        return gameDir;
    }

    /**
     * 获取根目录 (.minecraft/game_console/)
     */
    public static Path getRootDir() {
        if (!initialized) init();
        return rootDir;
    }

    /**
     * 获取 music 子目录
     */
    public static Path getMusicDir() {
        if (!initialized) init();
        return rootDir.resolve(MUSIC_FOLDER);
    }

    /**
     * 获取 voice 子目录
     */
    public static Path getVoiceDir() {
        if (!initialized) init();
        return rootDir.resolve(VOICE_FOLDER);
    }

    /**
     * 获取 data 子目录
     */
    public static Path getDataDir() {
        if (!initialized) init();
        return rootDir.resolve(DATA_FOLDER);
    }

    /**
     * 列出指定子目录中匹配扩展名的文件
     * @param subFolder 子文件夹名（如 "music"）
     * @param extension 文件扩展名（如 ".pts"），传 null 则列出所有文件
     * @return 文件路径列表
     */
    public static List<Path> listFiles(String subFolder, String extension) {
        if (!initialized) init();
        Path dir = rootDir.resolve(subFolder);
        if (!Files.exists(dir) || !Files.isDirectory(dir)) {
            return Collections.emptyList();
        }
        try (Stream<Path> stream = Files.list(dir)) {
            return stream
                    .filter(Files::isRegularFile)
                    .filter(p -> extension == null || p.getFileName().toString().endsWith(extension))
                    .sorted()
                    .collect(Collectors.toList());
        } catch (IOException e) {
            LOGGER.error("列出文件失败", e);
            return Collections.emptyList();
        }
    }

    /**
     * 列出指定子目录中的所有文件
     */
    public static List<Path> listFiles(String subFolder) {
        return listFiles(subFolder, null);
    }

    /**
     * 列出根目录下的所有子文件夹名称
     */
    public static List<String> listSubFolders() {
        if (!initialized) init();
        if (!Files.exists(rootDir) || !Files.isDirectory(rootDir)) {
            return Collections.emptyList();
        }
        try (Stream<Path> stream = Files.list(rootDir)) {
            return stream
                    .filter(Files::isDirectory)
                    .map(p -> p.getFileName().toString())
                    .sorted()
                    .collect(Collectors.toList());
        } catch (IOException e) {
            LOGGER.error("列出子文件夹失败", e);
            return Collections.emptyList();
        }
    }

    /**
     * 读取文本文件内容
     * @param subFolder 子文件夹名
     * @param fileName  文件名
     * @return 文件内容字符串，失败返回 null
     */
    public static String readTextFile(String subFolder, String fileName) {
        if (!initialized) init();
        Path file = rootDir.resolve(subFolder).resolve(fileName);
        if (!Files.exists(file)) return null;
        try {
            return Files.readString(file, StandardCharsets.UTF_8);
        } catch (IOException e) {
            LOGGER.error("读取文件失败: {}", file, e);
            return null;
        }
    }

    /**
     * 读取二进制文件内容
     * @param subFolder 子文件夹名
     * @param fileName  文件名
     * @return 文件字节数组，失败返回 null
     */
    public static byte[] readBytes(String subFolder, String fileName) {
        if (!initialized) init();
        Path file = rootDir.resolve(subFolder).resolve(fileName);
        if (!Files.exists(file)) return null;
        try {
            return Files.readAllBytes(file);
        } catch (IOException e) {
            LOGGER.error("读取文件失败: {}", file, e);
            return null;
        }
    }

    /**
     * 写入文本文件
     * @param subFolder 子文件夹名
     * @param fileName  文件名
     * @param content   内容
     * @return 是否成功
     */
    public static boolean writeTextFile(String subFolder, String fileName, String content) {
        if (!initialized) init();
        Path file = rootDir.resolve(subFolder).resolve(fileName);
        try {
            Files.createDirectories(file.getParent());
            Files.writeString(file, content, StandardCharsets.UTF_8);
            return true;
        } catch (IOException e) {
            LOGGER.error("写入文件失败: {}", file, e);
            return false;
        }
    }

    /**
     * 写入二进制文件
     * @param subFolder 子文件夹名
     * @param fileName  文件名
     * @param data      字节数据
     * @return 是否成功
     */
    public static boolean writeBytes(String subFolder, String fileName, byte[] data) {
        if (!initialized) init();
        Path file = rootDir.resolve(subFolder).resolve(fileName);
        try {
            Files.createDirectories(file.getParent());
            Files.write(file, data);
            return true;
        } catch (IOException e) {
            LOGGER.error("写入文件失败: {}", file, e);
            return false;
        }
    }

    /**
     * 获取文件的完整路径
     * @param subFolder 子文件夹名
     * @param fileName  文件名
     * @return 完整路径字符串
     */
    public static String getFilePath(String subFolder, String fileName) {
        if (!initialized) init();
        return rootDir.resolve(subFolder).resolve(fileName).toAbsolutePath().toString();
    }

    /**
     * 检查文件是否存在
     */
    public static boolean fileExists(String subFolder, String fileName) {
        if (!initialized) init();
        return Files.exists(rootDir.resolve(subFolder).resolve(fileName));
    }

    /**
     * 确保子目录存在（用于动态创建新子目录）
     * @param subFolder 子文件夹名
     */
    public static void ensureSubFolder(String subFolder) {
        if (!initialized) init();
        try {
            Files.createDirectories(rootDir.resolve(subFolder));
        } catch (IOException e) {
            LOGGER.error("创建子文件夹失败: {}", subFolder, e);
        }
    }
}
