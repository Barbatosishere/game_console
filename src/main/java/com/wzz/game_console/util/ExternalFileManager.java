package com.wzz.game_console.util;

import net.neoforged.fml.loading.FMLPaths;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Collections;
import java.util.List;
import java.util.stream.Stream;

/** 管理游戏目录下 game_console 文件夹中的外部文件。 */
public final class ExternalFileManager {

    private static final Logger LOGGER = LoggerFactory.getLogger("GameConsole");

    public static final String ROOT_FOLDER = "game_console";
    public static final String MUSIC_FOLDER = "music";
    public static final String VOICE_FOLDER = "voice";
    public static final String DATA_FOLDER = "data";

    private enum InitState {
        UNINITIALIZED,
        INITIALIZED,
        FAILED
    }

    private static final Object INIT_LOCK = new Object();
    private static volatile InitState initState = InitState.UNINITIALIZED;
    private static volatile Path gameDir;
    private static volatile Path rootDir;

    private ExternalFileManager() {}

    /**
     * 初始化并创建标准目录。初始化仅执行一次；失败后固定为 FAILED，避免并发重试和重复日志。
     */
    public static void init() {
        if (initState != InitState.UNINITIALIZED) return;

        synchronized (INIT_LOCK) {
            if (initState != InitState.UNINITIALIZED) return;
            try {
                Path resolvedGameDir = FMLPaths.GAMEDIR.get();
                Path resolvedRootDir = resolvedGameDir.resolve(ROOT_FOLDER);
                Files.createDirectories(resolvedRootDir);
                Files.createDirectories(resolvedRootDir.resolve(MUSIC_FOLDER));
                Files.createDirectories(resolvedRootDir.resolve(VOICE_FOLDER));
                Files.createDirectories(resolvedRootDir.resolve(DATA_FOLDER));

                gameDir = resolvedGameDir;
                rootDir = resolvedRootDir;
                initState = InitState.INITIALIZED;
                LOGGER.info("外部文件夹已创建: {}", resolvedRootDir.toAbsolutePath());
            } catch (Throwable failure) {
                // 先发布失败状态，确保即使日志后续出现问题也不会重复初始化或重复记录该失败。
                gameDir = null;
                rootDir = null;
                initState = InitState.FAILED;
                try {
                    LOGGER.error("创建外部文件夹失败，外部文件 API 将安全降级", failure);
                } catch (Throwable ignored) {
                    // 日志后端异常不能越过公共 API 的异常边界。
                }
            }
        }
    }

    public static Path getGameDir() {
        ensureInitialized();
        return initState == InitState.INITIALIZED ? gameDir : null;
    }

    public static Path getRootDir() {
        return availableRoot();
    }

    public static Path getMusicDir() {
        return resolveSubFolder(MUSIC_FOLDER);
    }

    public static Path getVoiceDir() {
        return resolveSubFolder(VOICE_FOLDER);
    }

    public static Path getDataDir() {
        return resolveSubFolder(DATA_FOLDER);
    }

    public static List<Path> listFiles(String subFolder, String extension) {
        Path dir = resolveSubFolder(subFolder);
        if (dir == null) return Collections.emptyList();
        try {
            if (!Files.isDirectory(dir)) return Collections.emptyList();
            try (Stream<Path> stream = Files.list(dir)) {
                return stream.filter(Files::isRegularFile)
                        .filter(path -> extension == null || path.getFileName().toString().endsWith(extension))
                        .sorted()
                        .toList();
            }
        } catch (Throwable failure) {
            logOperationFailure("列出文件失败: " + dir, failure);
            return Collections.emptyList();
        }
    }

    public static List<Path> listFiles(String subFolder) {
        return listFiles(subFolder, null);
    }

    public static List<String> listSubFolders() {
        Path root = availableRoot();
        if (root == null) return Collections.emptyList();
        try {
            if (!Files.isDirectory(root)) return Collections.emptyList();
            try (Stream<Path> stream = Files.list(root)) {
                return stream.filter(Files::isDirectory)
                        .map(path -> path.getFileName().toString())
                        .sorted()
                        .toList();
            }
        } catch (Throwable failure) {
            logOperationFailure("列出子文件夹失败: " + root, failure);
            return Collections.emptyList();
        }
    }

    private static boolean isSafePathPart(String value) {
        return value != null && !value.isEmpty() && !value.equals(".") && !value.contains("..")
                && !value.contains("/") && !value.contains("\\");
    }

    private static boolean isSafeFileName(String fileName) {
        return isSafePathPart(fileName);
    }

    public static String readTextFile(String subFolder, String fileName) {
        Path file = resolveFile(subFolder, fileName);
        if (file == null) return null;
        try {
            return Files.isRegularFile(file) ? Files.readString(file, StandardCharsets.UTF_8) : null;
        } catch (Throwable failure) {
            logOperationFailure("读取文件失败: " + file, failure);
            return null;
        }
    }

    public static byte[] readBytes(String subFolder, String fileName) {
        Path file = resolveFile(subFolder, fileName);
        if (file == null) return null;
        try {
            return Files.isRegularFile(file) ? Files.readAllBytes(file) : null;
        } catch (Throwable failure) {
            logOperationFailure("读取文件失败: " + file, failure);
            return null;
        }
    }

    public static boolean writeTextFile(String subFolder, String fileName, String content) {
        Path file = resolveFile(subFolder, fileName);
        if (file == null || content == null) return false;
        return atomicWrite(file, temp -> Files.writeString(temp, content, StandardCharsets.UTF_8));
    }

    public static boolean writeBytes(String subFolder, String fileName, byte[] data) {
        Path file = resolveFile(subFolder, fileName);
        if (file == null || data == null) return false;
        return atomicWrite(file, temp -> Files.write(temp, data));
    }

    public static String getFilePath(String subFolder, String fileName) {
        Path file = resolveFile(subFolder, fileName);
        if (file == null) return null;
        try {
            return file.toAbsolutePath().toString();
        } catch (Throwable failure) {
            logOperationFailure("获取文件路径失败: " + file, failure);
            return null;
        }
    }

    public static boolean fileExists(String subFolder, String fileName) {
        Path file = resolveFile(subFolder, fileName);
        if (file == null) return false;
        try {
            return Files.exists(file);
        } catch (Throwable failure) {
            logOperationFailure("检查文件失败: " + file, failure);
            return false;
        }
    }

    public static void ensureSubFolder(String subFolder) {
        Path dir = resolveSubFolder(subFolder);
        if (dir == null) return;
        try {
            Files.createDirectories(dir);
        } catch (Throwable failure) {
            logOperationFailure("创建子文件夹失败: " + subFolder, failure);
        }
    }

    private static void ensureInitialized() {
        try {
            init();
        } catch (Throwable failure) {
            // init 本身已有完整边界；此处作为公共 API 的最后防线。
            synchronized (INIT_LOCK) {
                if (initState == InitState.UNINITIALIZED) initState = InitState.FAILED;
            }
        }
    }

    private static Path availableRoot() {
        ensureInitialized();
        return initState == InitState.INITIALIZED ? rootDir : null;
    }

    private static Path resolveSubFolder(String subFolder) {
        Path root = availableRoot();
        if (root == null || !isSafePathPart(subFolder)) return null;
        try {
            return root.resolve(subFolder);
        } catch (Throwable failure) {
            logOperationFailure("解析子文件夹失败: " + subFolder, failure);
            return null;
        }
    }

    private static Path resolveFile(String subFolder, String fileName) {
        if (!isSafeFileName(fileName)) return null;
        Path dir = resolveSubFolder(subFolder);
        if (dir == null) return null;
        try {
            return dir.resolve(fileName);
        } catch (Throwable failure) {
            logOperationFailure("解析文件失败: " + fileName, failure);
            return null;
        }
    }

    private static boolean atomicWrite(Path file, ThrowingPathWriter writer) {
        Path temp = null;
        try {
            Files.createDirectories(file.getParent());
            temp = Files.createTempFile(file.getParent(), file.getFileName().toString() + ".", ".tmp");
            writer.write(temp);
            Files.move(temp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            return true;
        } catch (Throwable failure) {
            logOperationFailure("写入文件失败: " + file, failure);
            return false;
        } finally {
            if (temp != null) {
                try {
                    Files.deleteIfExists(temp);
                } catch (Throwable ignored) {
                    // 临时文件清理失败不改变写入结果。
                }
            }
        }
    }

    private static void logOperationFailure(String message, Throwable failure) {
        try {
            LOGGER.error(message, failure);
        } catch (Throwable ignored) {
            // 日志系统不可用时仍保持 API 安全降级。
        }
    }

    @FunctionalInterface
    private interface ThrowingPathWriter {
        void write(Path path) throws Exception;
    }
}
