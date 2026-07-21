package com.wzz.game_console.client.screens.games;

import com.wzz.game_console.client.screens.GameSelectorScreen;
import com.wzz.game_console.util.ExternalFileManager;
import com.wzz.game_console.util.GameRenderHelper;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import org.lwjgl.glfw.GLFW;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.*;

public class PianoTilesGameScreen extends Screen {
    boolean showExitConfirm = false;
    // 音符数据类
    public static class Note {
        public int lane;           // 轨道 (0-3)
        public long timestamp;     // 时间戳 (毫秒)
        public int noteType;       // 音符类型 (0-3对应不同音色)
        public boolean hit;        // 是否已被点击
        public boolean missed;     // 是否已错过

        public Note(int lane, long timestamp, int noteType) {
            this.lane = lane;
            this.timestamp = timestamp;
            this.noteType = noteType;
            this.hit = false;
            this.missed = false;
        }
    }

    public static class SongInfo {
        public String name;
        public String artist;
        public int bpm;
        public String difficulty;
        public List<Note> notes;
        public String audioFile;        // 临时音频文件路径
        public byte[] audioData;        // 音频数据
        public String audioFormat;      // 音频格式

        public SongInfo() {
            this.notes = new ArrayList<>();
            this.bpm = 120;
            this.difficulty = "Normal";
            this.name = "默认歌曲";
            this.artist = "未知";
            this.audioData = null;
            this.audioFormat = null;
        }
    }

    // 判定结果枚举
    public enum HitResult {
        PERFECT(100, "Perfect!", 0xFF4CAF50),
        GREAT(75, "Great", 0xFF8BC34A),
        GOOD(50, "Good", 0xFFFFEB3B),
        MISS(0, "Miss", 0xFFFF5722);

        final int score;
        final String text;
        final int color;

        HitResult(int score, String text, int color) {
            this.score = score;
            this.text = text;
            this.color = color;
        }
    }

    // 游戏常量
    private static final int LANE_COUNT = 4;
    // 每条轨道的颜色（亮色）
    private static final int[] LANE_BRIGHT = {0xFF3F51B5, 0xFF4CAF50, 0xFF9C27B0, 0xFFE91E63};
    private static final int[] LANE_DARK   = {0xFF0D1442, 0xFF0D3210, 0xFF260A4E, 0xFF440728};
    private static final String[] LANE_KEYS = {"D", "F", "J", "K"};
    private static final int HIT_ZONE_HEIGHT = 80;
    private static final int PERFECT_THRESHOLD = 80;   // 从20增加到80毫秒
    private static final int GREAT_THRESHOLD = 150;    // 从40增加到150毫秒
    private static final int GOOD_THRESHOLD = 250;     // 从60增加到250毫秒
    private static final int MISS_THRESHOLD = 350;     // 新增：错过阈值350毫秒
    private static final String MUSIC_FOLDER = ExternalFileManager.MUSIC_FOLDER;
    private static final String VOICE_FOLDER = ExternalFileManager.VOICE_FOLDER;

    public static class GameAudioPlayer {
        private javax.sound.sampled.Clip backgroundClip;
        private boolean isLoaded = false;
        private long audioLength = 0;
        private float volume = 0.5f;
        private String tempAudioFile = null; // 临时音频文件路径

        public boolean loadBackgroundMusic(String filePath) {
            try {
                debugLog("尝试加载音频文件: " + filePath);
                closeAudio();
                File audioFile = new File(filePath);
                debugLog("文件对象创建完成");
                debugLog("文件存在检查: " + audioFile.exists());
                debugLog("文件可读检查: " + audioFile.canRead());
                debugLog("文件大小: " + audioFile.length() + " bytes");
                debugLog("文件绝对路径: " + audioFile.getAbsolutePath());

                if (!audioFile.exists()) {
                    debugLog("音频文件不存在: " + filePath);

                    // 尝试列出目录内容进行调试
                    File parentDir = audioFile.getParentFile();
                    if (parentDir != null && parentDir.exists()) {
                        debugLog("父目录存在，内容列表:");
                        File[] files = parentDir.listFiles();
                        if (files != null) {
                            for (File f : files) {
                                debugLog("  - " + f.getName() + " (大小: " + f.length() + ")");
                            }
                        } else {
                            debugLog("  - 无法列出目录内容");
                        }
                    } else {
                        debugLog("父目录不存在: " + (parentDir != null ? parentDir.getAbsolutePath() : "null"));
                    }

                    return false;
                }

                if (!audioFile.canRead()) {
                    debugLog("音频文件不可读: " + filePath);
                    return false;
                }

                if (audioFile.length() == 0) {
                    debugLog("音频文件为空: " + filePath);
                    return false;
                }
                debugLog("开始创建音频输入流...");
                javax.sound.sampled.AudioInputStream audioStream = javax.sound.sampled.AudioSystem.getAudioInputStream(audioFile);
                debugLog("音频流创建成功");
                debugLog("开始创建音频剪辑...");
                backgroundClip = javax.sound.sampled.AudioSystem.getClip();
                backgroundClip.open(audioStream);
                debugLog("音频剪辑创建成功");
                audioLength = backgroundClip.getMicrosecondLength();
                isLoaded = true;
                debugLog("音频加载成功，长度: " + (audioLength / 1000000) + " 秒");
                setVolume(volume);
                return true;
            } catch (Exception e) {
                debugLog("音频加载失败: " + e.getMessage());
                e.printStackTrace();
                isLoaded = false;
                return false;
            }
        }

        public boolean loadBackgroundMusicFromMemory(byte[] audioData, String format) {
            try {
                debugLog("尝试直接从内存播放音频，数据大小: " + audioData.length + " bytes");
                closeAudio();
                if (audioData == null || audioData.length == 0) {
                    debugLog("音频数据为空");
                    return false;
                }
                ByteArrayInputStream bais = new ByteArrayInputStream(audioData);
                debugLog("字节数组输入流创建成功");
                javax.sound.sampled.AudioInputStream audioStream = javax.sound.sampled.AudioSystem.getAudioInputStream(bais);
                debugLog("音频输入流创建成功");
                backgroundClip = javax.sound.sampled.AudioSystem.getClip();
                backgroundClip.open(audioStream);
                debugLog("音频剪辑创建成功");
                audioLength = backgroundClip.getMicrosecondLength();
                isLoaded = true;
                debugLog("直接从内存加载音频成功，长度: " + (audioLength / 1000000) + " 秒");
                setVolume(volume);
                return true;

            } catch (Exception e) {
                debugLog("从内存加载音频失败: " + e.getMessage());
                e.printStackTrace();
                isLoaded = false;
                return false;
            }
        }

        public boolean loadBackgroundMusicFromData(byte[] audioData, String format) {
            try {
                debugLog("尝试从音频数据加载，数据大小: " +
                        (audioData != null ? audioData.length : 0) + " bytes，格式: " + format);
                closeAudio();
                if (audioData == null || audioData.length == 0) {
                    debugLog("音频数据为空");
                    return false;
                }
                debugLog("=== 策略1: 尝试直接从内存播放 ===");
                try {
                    if (loadBackgroundMusicFromMemory(audioData, format)) {
                        debugLog("策略1成功：直接从内存播放");
                        return true;
                    }
                } catch (Exception e) {
                    debugLog("策略1失败：" + e.getMessage());
                }
                debugLog("=== 策略2: 尝试临时文件播放 ===");
                try {
                    tempAudioFile = createTempAudioFile(audioData, format);
                    if (tempAudioFile == null) {
                        debugLog("策略2失败：创建临时音频文件失败");
                    } else {
                        debugLog("临时音频文件创建成功: " + tempAudioFile);

                        boolean result = loadBackgroundMusic(tempAudioFile);
                        if (result) {
                            debugLog("策略2成功：从临时文件播放");
                            return true;
                        } else {
                            debugLog("策略2失败：从临时文件加载音频失败");
                        }
                    }
                } catch (Exception e) {
                    debugLog("策略2异常：" + e.getMessage());
                }
                debugLog("所有加载策略都失败了");
                return false;

            } catch (Exception e) {
                debugLog("从音频数据加载失败: " + e.getMessage());
                e.printStackTrace();
                isLoaded = false;
                return false;
            }
        }


        private void debugLog(String message) {
            //BlueArchivesLogger.println("[GameAudioPlayer] " + message);
        }

        private String createTempAudioFile(byte[] audioData, String format) {
            try {
                // 确保voice文件夹存在
                Path voiceDir = ExternalFileManager.getVoiceDir();
                if (!Files.exists(voiceDir)) {
                    Files.createDirectories(voiceDir);
                    debugLog("创建voice文件夹: " + voiceDir.toAbsolutePath());
                }

                // 确定文件扩展名
                String suffix;
                if (format != null) {
                    suffix = format.startsWith(".") ? format : "." + format;
                } else {
                    suffix = ".wav"; // 默认扩展名
                }
                String fileName = "temp_audio_" + System.currentTimeMillis() + "_" +
                        (int)(Math.random() * 1000) + suffix;
                Path tempFile = voiceDir.resolve(fileName);

                debugLog("创建临时音频文件: " + tempFile.toAbsolutePath());
                try (FileOutputStream fos = new FileOutputStream(tempFile.toFile());
                     BufferedOutputStream bos = new BufferedOutputStream(fos)) {

                    bos.write(audioData);
                    bos.flush(); // 强制刷新缓冲区
                    fos.getFD().sync(); // 强制同步到磁盘

                } // 自动关闭文件流
                debugLog("音频数据写入完成，文件大小: " + Files.size(tempFile) + " bytes");
                int maxRetries = 10;
                for (int i = 0; i < maxRetries; i++) {
                    if (Files.exists(tempFile) && Files.isReadable(tempFile) && Files.size(tempFile) == audioData.length) {
                        debugLog("文件验证成功，尝试次数: " + (i + 1));
                        break;
                    }

                    if (i < maxRetries - 1) {
                        debugLog("文件验证失败，等待重试... (尝试 " + (i + 1) + "/" + maxRetries + ")");
                        try {
                            Thread.sleep(50); // 等待50毫秒
                        } catch (InterruptedException e) {
                            Thread.currentThread().interrupt();
                            break;
                        }
                    } else {
                        debugLog("文件验证最终失败");
                        return null;
                    }
                }

                // 最终验证
                if (!Files.exists(tempFile)) {
                    debugLog("最终检查：临时文件不存在");
                    return null;
                }

                if (!Files.isReadable(tempFile)) {
                    debugLog("最终检查：临时文件不可读");
                    return null;
                }

                long actualSize = Files.size(tempFile);
                if (actualSize != audioData.length) {
                    debugLog("最终检查：文件大小不匹配，期望: " + audioData.length + ", 实际: " + actualSize);
                    return null;
                }

                debugLog("临时文件创建和验证成功");
                return tempFile.toAbsolutePath().toString();

            } catch (Exception e) {
                debugLog("创建临时音频文件异常: " + e.getMessage());
                e.printStackTrace();
                return null;
            }
        }

        private boolean isFirstPlay = true;

        public void play() {
            if (isLoaded && backgroundClip != null) {
                backgroundClip.setFramePosition(0); // 重置到开头
                backgroundClip.start();
                isFirstPlay = false;
            }
        }

        public void resume() {
            if (isLoaded && backgroundClip != null) {
                backgroundClip.start();
            }
        }

        public void stop() {
            if (isLoaded && backgroundClip != null) {
                backgroundClip.stop();
                backgroundClip.setFramePosition(0);
                isFirstPlay = true;
            }
        }

        public void pause() {
            if (isLoaded && backgroundClip != null) {
                backgroundClip.stop();
            }
        }

        public boolean isPlaying() {
            return isLoaded && backgroundClip != null && backgroundClip.isRunning();
        }

        public void setVolume(float volume) {
            this.volume = Math.max(0.0f, Math.min(1.0f, volume));
            if (isLoaded && backgroundClip != null) {
                try {
                    javax.sound.sampled.FloatControl volumeControl =
                            (javax.sound.sampled.FloatControl) backgroundClip.getControl(javax.sound.sampled.FloatControl.Type.MASTER_GAIN);
                    float dB = (float) (Math.log(this.volume) / Math.log(10.0) * 20.0);
                    volumeControl.setValue(Math.max(volumeControl.getMinimum(), dB));
                } catch (Exception e) {
                    // Volume control not supported
                }
            }
        }

        public long getCurrentPosition() {
            if (isLoaded && backgroundClip != null) {
                return backgroundClip.getMicrosecondPosition();
            }
            return 0;
        }

        public void setPosition(long microseconds) {
            if (isLoaded && backgroundClip != null) {
                backgroundClip.setMicrosecondPosition(Math.max(0, Math.min(microseconds, audioLength)));
            }
        }

        public boolean isFinished() {
            if (isLoaded && backgroundClip != null) {
                return backgroundClip.getMicrosecondPosition() >= audioLength;
            }
            return false;
        }

        public void closeAudio() {
            try {
                if (backgroundClip != null) {
                    backgroundClip.close();
                    backgroundClip = null;
                }
                if (tempAudioFile != null) {
                    try {
                        Files.deleteIfExists(Paths.get(tempAudioFile));
                    } catch (Exception e) {
                        // 忽略删除失败
                    }
                    tempAudioFile = null;
                }

                isLoaded = false;
                audioLength = 0;
            } catch (Exception e) {
                System.err.println("Error closing audio: " + e.getMessage());
            }
        }
    }

    // 修改loadSongFromFile方法 - 支持嵌入音频
    private SongInfo loadSongFromFile(String filePath) throws IOException {
        SongInfo song = new SongInfo();

        try (BufferedReader reader = new BufferedReader(
                new InputStreamReader(new FileInputStream(filePath), StandardCharsets.UTF_8))) {

            String line;
            while ((line = reader.readLine()) != null) {
                if (line.startsWith("Name:")) {
                    song.name = line.substring(5).trim();
                } else if (line.startsWith("Artist:")) {
                    song.artist = line.substring(7).trim();
                } else if (line.startsWith("BPM:")) {
                    song.bpm = Integer.parseInt(line.substring(4).trim());
                } else if (line.startsWith("Difficulty:")) {
                    song.difficulty = line.substring(11).trim();
                } else if (line.startsWith("AudioFormat:")) {
                    song.audioFormat = line.substring(12).trim();
                } else if (line.startsWith("AudioData:")) {
                    // 解码Base64音频数据
                    String audioBase64 = line.substring(10).trim();
                    try {
                        song.audioData = Base64.getDecoder().decode(audioBase64);
                    } catch (Exception e) {
                        System.err.println("解码音频数据失败: " + e.getMessage());
                        song.audioData = null;
                    }
                } else if (line.startsWith("Audio:")) {
                    // 兼容旧格式（路径方式）
                    String audioPath = line.substring(6).trim();
                    if (song.audioData == null) { // 如果没有嵌入的音频数据，则使用路径
                        song.audioFile = audioPath;
                    }
                } else if (line.startsWith("Note:")) {
                    String noteData = line.substring(5).trim();
                    String[] parts = noteData.split(",");
                    if (parts.length >= 3) {
                        int lane = Integer.parseInt(parts[0].split(":")[1]);
                        long timestamp = Long.parseLong(parts[1].split(":")[1]);
                        int noteType = Integer.parseInt(parts[2].split(":")[1]);
                        song.notes.add(new Note(lane, timestamp, noteType));
                    }
                }
            }
        }

        // 按时间排序音符
        song.notes.sort(Comparator.comparingLong(n -> n.timestamp));

        return song;
    }

    // 修改loadSelectedSong方法 - 支持嵌入音频加载
    private void loadSelectedSong() {
        if (selectedSongIndex >= 0 && selectedSongIndex < availableSongs.size()) {
            String songName = availableSongs.get(selectedSongIndex);

            if ("默认歌曲".equals(songName)) {
                loadDefaultSong();
            } else {
                try {
                    Path songFile = ExternalFileManager.getMusicDir().resolve(songName + ".pts");
                    if (Files.exists(songFile)) {
                        currentSong = loadSongFromFile(songFile.toString());

                        // 优先使用嵌入的音频数据
                        if (currentSong.audioData != null && currentSong.audioData.length > 0) {
                            audioPlayer.loadBackgroundMusicFromData(currentSong.audioData, currentSong.audioFormat);
                        } else if (currentSong.audioFile != null && !currentSong.audioFile.isEmpty()) {
                            // 兼容旧格式，使用路径加载
                            audioPlayer.loadBackgroundMusic(currentSong.audioFile);
                        }
                    } else {
                        loadDefaultSong();
                    }
                } catch (Exception e) {
                    loadDefaultSong();
                }
            }
        }
    }

    // 游戏状态
    private boolean gameActive = false;
    private boolean gamePaused = false;
    private boolean gameOver = false;
    private boolean songSelectMode = true;

    // 动态布局变量
    private int laneWidth;
    private int gameAreaWidth;
    private int gameAreaHeight;
    private int gameStartX, gameStartY;
    private int hitZoneY;

    // 谱面数据
    private SongInfo currentSong;
    private List<String> availableSongs = new ArrayList<>();
    private int selectedSongIndex = 0;
    private List<Note> activeTiles = new ArrayList<>();
    private int noteIndex = 0; // 当前音符索引

    // 游戏数据
    private int score = 0;
    private int combo = 0;
    private int maxCombo = 0;
    private int perfectHits = 0;
    private int greatHits = 0;
    private int goodHits = 0;
    private int missedHits = 0;

    // 时间和速度
    private long gameStartTime;
    private long currentGameTime = 0;
    private float fallSpeed;

    // 视觉效果
    private List<HitEffect> hitEffects = new ArrayList<>();
    private long lastHitTime = 0;
    private HitResult lastHitResult = null;

    // 音效数组
    private final SoundEvent[] PIANO_SOUNDS = {
            SoundEvents.NOTE_BLOCK_HARP.value(),
            SoundEvents.NOTE_BLOCK_PLING.value(),
            SoundEvents.NOTE_BLOCK_BELL.value(),
            SoundEvents.NOTE_BLOCK_CHIME.value()
    };

    // 背景音乐播放器
    private GameAudioPlayer audioPlayer;

    // UI按钮
    private Button startButton, pauseButton, backButton;
    private Button prevSongButton, nextSongButton, selectSongButton;

    // 特效类
    public static class HitEffect {
        public int lane;
        public long startTime;
        public HitResult result;
        public float alpha;

        public HitEffect(int lane, HitResult result) {
            this.lane = lane;
            this.result = result;
            this.startTime = System.currentTimeMillis();
            this.alpha = 1.0f;
        }

        public boolean isExpired() {
            return System.currentTimeMillis() - startTime > 1000;
        }

        public void update() {
            long elapsed = System.currentTimeMillis() - startTime;
            alpha = Math.max(0, 1.0f - elapsed / 1000.0f);
        }
    }

    public PianoTilesGameScreen() {
        super(Component.literal("\u94a2\u7434\u5757"));
        minecraft = Minecraft.getInstance();
        font = minecraft.font;
        audioPlayer = new GameAudioPlayer();
        loadAvailableSongs();
        loadDefaultSong();
    }

    @Override
    public void init() {
        super.init();
        calculateLayout();
        setupButtons();
    }

    @Override
    public void resize(Minecraft minecraft, int width, int height) {
        super.resize(minecraft, width, height);
        calculateLayout();
        this.clearWidgets();
        setupButtons();
    }

    private void calculateLayout() {
        // 计算游戏区域大小
        gameAreaWidth = Math.min(400, this.width - 100);
        gameAreaHeight = Math.min(600, this.height - 150);

        // 确保是4的倍数以便平分轨道
        gameAreaWidth = (gameAreaWidth / 4) * 4;
        laneWidth = gameAreaWidth / LANE_COUNT;

        // 居中定位
        gameStartX = (this.width - gameAreaWidth) / 2;
        gameStartY = (this.height - gameAreaHeight) / 2 - 30;

        // 计算打击区域位置
        hitZoneY = gameStartY + gameAreaHeight - HIT_ZONE_HEIGHT;

        // 计算下落速度 (2秒下落完成)
        fallSpeed = gameAreaHeight / 2000.0f;
    }

    private void setupButtons() {
        int buttonY = gameStartY + gameAreaHeight + 20;
        int buttonWidth = 80;
        int buttonHeight = 20;

        if (songSelectMode) {
            // 歌曲选择模式的按钮
            int buttonSpacing = 90;
            int buttonStartX = (this.width - (buttonSpacing * 3 - 10)) / 2;

            prevSongButton = Button.builder(Component.literal("\u4e0a\u4e00\u9996"), button -> { // "上一首"
                selectedSongIndex = (selectedSongIndex - 1 + availableSongs.size()) % availableSongs.size();
                loadSelectedSong();
                playSound(SoundEvents.UI_BUTTON_CLICK.value());
            }).bounds(buttonStartX, buttonY, buttonWidth, buttonHeight).build();
            this.addRenderableWidget(prevSongButton);

            selectSongButton = Button.builder(Component.literal("\u9009\u62e9"), button -> { // "选择"
                startSongSelectMode();
                playSound(SoundEvents.UI_BUTTON_CLICK.value());
            }).bounds(buttonStartX + buttonSpacing, buttonY, buttonWidth, buttonHeight).build();
            this.addRenderableWidget(selectSongButton);

            nextSongButton = Button.builder(Component.literal("\u4e0b\u4e00\u9996"), button -> { // "下一首"
                selectedSongIndex = (selectedSongIndex + 1) % availableSongs.size();
                loadSelectedSong();
                playSound(SoundEvents.UI_BUTTON_CLICK.value());
            }).bounds(buttonStartX + buttonSpacing * 2, buttonY, buttonWidth, buttonHeight).build();
            this.addRenderableWidget(nextSongButton);

        } else {
            // 游戏模式的按钮
            String startText = gameActive ? (gamePaused ? "\u7ee7\u7eed" : "\u6682\u505c") : "\u5f00\u59cb"; // "继续":"暂停":"开始"
            startButton = Button.builder(Component.literal(startText), button -> {
                if (!gameActive) {
                    startGame();
                } else {
                    togglePause();
                }
                updateButtonTexts();
            }).bounds(gameStartX, buttonY, buttonWidth, buttonHeight).build();
            this.addRenderableWidget(startButton);

            backButton = Button.builder(Component.literal("\u8fd4\u56de"), button -> { // "返回"
                backToSongSelect();
                playSound(SoundEvents.UI_BUTTON_CLICK.value());
            }).bounds(gameStartX + 90, buttonY, buttonWidth, buttonHeight).build();
            this.addRenderableWidget(backButton);
        }
    }

    private void updateButtonTexts() {
        if (startButton != null && !songSelectMode) {
            String startText = gameActive ? (gamePaused ? "\u7ee7\u7eed" : "\u6682\u505c") : "\u5f00\u59cb"; // "继续":"暂停":"开始"
            this.removeWidget(startButton);
            int buttonY = gameStartY + gameAreaHeight + 20;
            startButton = Button.builder(Component.literal(startText), button -> {
                if (!gameActive) {
                    startGame();
                } else {
                    togglePause();
                }
                updateButtonTexts();
            }).bounds(gameStartX, buttonY, 80, 20).build();
            this.addRenderableWidget(startButton);
        }
    }

    private void loadAvailableSongs() {
        availableSongs.clear();

        try {
            Path musicDir = ExternalFileManager.getMusicDir();
            if (Files.exists(musicDir)) {
                Files.list(musicDir)
                        .filter(path -> path.toString().endsWith(".pts"))
                        .forEach(path -> {
                            String fileName = path.getFileName().toString();
                            String songName = fileName.substring(0, fileName.lastIndexOf('.'));
                            availableSongs.add(songName);
                        });
            }
        } catch (Exception e) {
            // 静默处理错误
        }

        if (availableSongs.isEmpty()) {
            availableSongs.add("\u9ed8\u8ba4\u6b4c\u66f2"); // "默认歌曲"
        }
    }

    private void loadDefaultSong() {
        currentSong = new SongInfo();
        currentSong.name = "\u9ed8\u8ba4\u6b4c\u66f2"; // "默认歌曲"
        currentSong.artist = "\u7cfb\u7edf"; // "系统"
        currentSong.bpm = 120;
        currentSong.difficulty = "Normal";

        // 默认谱：120BPM，8分音符节奏，循序渐进
        int bpm = 120;
        long halfBeat = 60000L / bpm / 2; // 250ms 每8分音符
        int[] pattern = {0,2,1,3, 0,1,2,3, 1,3,0,2, 2,0,3,1,
                         0,0,2,2, 1,1,3,3, 0,3,1,2, 0,1,0,1,
                         2,3,2,3, 0,2,1,3, 3,1,2,0, 1,2,3,0,
                         0,1,2,3, 3,2,1,0, 0,2,0,3, 1,3,1,2};
        for (int i = 0; i < pattern.length; i++) {
            currentSong.notes.add(new Note(pattern[i], i * halfBeat, pattern[i]));
        }

        // 按时间排序
        currentSong.notes.sort(Comparator.comparingLong(n -> n.timestamp));
    }

    @Override
    public boolean keyPressed(int keyCode, int scanCode, int modifiers) {
        if (keyCode == GLFW.GLFW_KEY_ESCAPE) { if (showExitConfirm) { showExitConfirm = false; } else { showExitConfirm = true; } return true; }
        if (showExitConfirm) return true;
        // 如果在歌曲选择模式，使用默认的键盘处理
        if (songSelectMode) {
            // 可以添加方向键切换歌曲的功能
            if (keyCode == GLFW.GLFW_KEY_LEFT || keyCode == GLFW.GLFW_KEY_A) {
                // 上一首
                selectedSongIndex = (selectedSongIndex - 1 + availableSongs.size()) % availableSongs.size();
                loadSelectedSong();
                playSound(SoundEvents.UI_BUTTON_CLICK.value());
                return true;
            } else if (keyCode == GLFW.GLFW_KEY_RIGHT || keyCode == GLFW.GLFW_KEY_D) {
                // 下一首
                selectedSongIndex = (selectedSongIndex + 1) % availableSongs.size();
                loadSelectedSong();
                playSound(SoundEvents.UI_BUTTON_CLICK.value());
                return true;
            } else if (keyCode == GLFW.GLFW_KEY_ENTER || keyCode == GLFW.GLFW_KEY_SPACE) {
                // 选择歌曲
                startSongSelectMode();
                playSound(SoundEvents.UI_BUTTON_CLICK.value());
                return true;
            }
            return super.keyPressed(keyCode, scanCode, modifiers);
        }

        // 游戏中的键盘控制
        if (gameActive && !gamePaused && !gameOver) {
            // 1、2、3、4键对应四个轨道
            switch (keyCode) {
                case GLFW.GLFW_KEY_1:
                case GLFW.GLFW_KEY_KP_1:
                    handleLaneClick(0);
                    return true;
                case GLFW.GLFW_KEY_2:
                case GLFW.GLFW_KEY_KP_2:
                    handleLaneClick(1);
                    return true;
                case GLFW.GLFW_KEY_3:
                case GLFW.GLFW_KEY_KP_3:
                    handleLaneClick(2);
                    return true;
                case GLFW.GLFW_KEY_4:
                case GLFW.GLFW_KEY_KP_4:
                    handleLaneClick(3);
                    return true;
            }

            // 额外的快捷键支持
            switch (keyCode) {
                // ASDF键作为替代方案（更符合手指位置）
                case GLFW.GLFW_KEY_A:
                    handleLaneClick(0);
                    return true;
                case GLFW.GLFW_KEY_S:
                    handleLaneClick(1);
                    return true;
                case GLFW.GLFW_KEY_D:
                    handleLaneClick(2);
                    return true;
                case GLFW.GLFW_KEY_F:
                    handleLaneClick(3);
                    return true;

                // 方向键支持
                case GLFW.GLFW_KEY_LEFT:
                    handleLaneClick(0);
                    return true;
                case GLFW.GLFW_KEY_DOWN:
                    handleLaneClick(1);
                    return true;
                case GLFW.GLFW_KEY_UP:
                    handleLaneClick(2);
                    return true;
                case GLFW.GLFW_KEY_RIGHT:
                    handleLaneClick(3);
                    return true;
                // J/K 右手
                case GLFW.GLFW_KEY_J:
                    handleLaneClick(2);
                    return true;
                case GLFW.GLFW_KEY_K:
                    handleLaneClick(3);
                    return true;
            }
        }
        return super.keyPressed(keyCode, scanCode, modifiers);
    }

    private void restartGame() {
        initializeGame();
        audioPlayer.stop();
        startGame();
        playSound(SoundEvents.UI_BUTTON_CLICK.value());
    }

    private long pauseStartTime = 0; // 暂停开始时间
    private long totalPausedTime = 0; // 总暂停时间

    private void startSongSelectMode() {
        songSelectMode = false;
        this.clearWidgets();
        setupButtons();
    }

    private void backToSongSelect() {
        songSelectMode = true;
        gameActive = false;
        gamePaused = false;
        gameOver = false;
        activeTiles.clear();
        hitEffects.clear();
        noteIndex = 0;
        score = 0;
        combo = 0;
        perfectHits = 0;
        greatHits = 0;
        goodHits = 0;
        missedHits = 0;
        totalPausedTime = 0;

        audioPlayer.stop();
        this.clearWidgets();
        setupButtons();
    }

    private void initializeGame() {
        activeTiles.clear();
        hitEffects.clear();
        noteIndex = 0;
        score = 0;
        combo = 0;
        maxCombo = 0;
        perfectHits = 0;
        greatHits = 0;
        goodHits = 0;
        missedHits = 0;
        gameActive = false;
        gamePaused = false;
        gameOver = false;
        currentGameTime = 0;
    }

    private void startGame() {
        Minecraft.getInstance().getSoundManager().stop();
        gameActive = true;
        gamePaused = false;
        gameOver = false;
        gameStartTime = System.currentTimeMillis();
        noteIndex = 0;
        activeTiles.clear();
        totalPausedTime = 0;
        // 开始播放背景音乐
        audioPlayer.play();
    }

    private void togglePause() {
        if (gameActive && !gameOver) {
            if (!gamePaused) {
                gamePaused = true;
                pauseStartTime = System.currentTimeMillis();
                audioPlayer.pause();
            } else {
                gamePaused = false;
                totalPausedTime += System.currentTimeMillis() - pauseStartTime;
                gameStartTime += System.currentTimeMillis() - pauseStartTime;
                audioPlayer.resume();
            }
        }
    }

    private void endGame() {
        gameActive = false;
        gameOver = true;
        audioPlayer.stop();
        playSound(SoundEvents.VILLAGER_NO);
        int totalNotes = perfectHits + greatHits + goodHits + missedHits;
        if (totalNotes > 0) {
            float accuracy = (float)(perfectHits + greatHits + goodHits) / totalNotes;
            int baseReward = Math.min(200, score / 50);
            int accuracyBonus = (int)(baseReward * accuracy * 0.5f);
            int comboBonus = Math.min(50, maxCombo / 10);
        }
    }

    @Override
    public void tick() {
        super.tick();
        if (!songSelectMode && gameActive && !gamePaused && !gameOver && leftMouseDown) {
            long currentTime = System.currentTimeMillis();
            if (currentTime - lastClickTime > 100) { // 每100ms最多触发一次
                double mouseX = minecraft.mouseHandler.xpos() * minecraft.getWindow().getGuiScaledWidth() / minecraft.getWindow().getScreenWidth();

                if (mouseX >= gameStartX && mouseX < gameStartX + gameAreaWidth) {
                    int lane = (int) ((mouseX - gameStartX) / laneWidth);
                    if (lane >= 0 && lane < LANE_COUNT) {
                        handleLaneClick(lane);
                        lastClickTime = currentTime;
                    }
                }
            }
        }
        if (songSelectMode || !gameActive || gamePaused || gameOver) {
            return;
        }
        currentGameTime = System.currentTimeMillis() - gameStartTime;

        if (audioPlayer.isLoaded && audioPlayer.isPlaying()) {
            long audioTime = audioPlayer.getCurrentPosition() / 1000;
            long timeDiff = Math.abs(currentGameTime - audioTime);
            if (timeDiff > 100) {
                 currentGameTime = audioTime;
            }
        }
        spawnNotes();
        updateTiles();
        updateEffects();
        checkGameEnd();
    }

    private void spawnNotes() {
        // 提前2秒生成音符块
        long spawnTime = currentGameTime + 2000;

        while (noteIndex < currentSong.notes.size()) {
            Note templateNote = currentSong.notes.get(noteIndex);

            if (templateNote.timestamp <= spawnTime) {
                Note activeTile = new Note(templateNote.lane, templateNote.timestamp, templateNote.noteType);
                activeTiles.add(activeTile);
                noteIndex++;
            } else {
                break;
            }
        }
    }

    private void updateTiles() {
        Iterator<Note> iterator = activeTiles.iterator();
        while (iterator.hasNext()) {
            Note tile = iterator.next();

            // 跳过已经处理的音符
            if (tile.hit || tile.missed) {
                // 移除已经处理的音符
                if (tile.hit || currentGameTime - tile.timestamp > 2000) {
                    iterator.remove();
                }
                continue;
            }

            // 检查是否错过 - 只有当音符完全离开判定区域才标记为missed
            long timePassed = currentGameTime - tile.timestamp;
            if (timePassed > MISS_THRESHOLD) {
                tile.missed = true;
                missHit();
            }

            // 移除太远的音符块
            if (timePassed > 5000) { // 增加清理时间，避免过早删除
                iterator.remove();
            }
        }
    }

    private void updateEffects() {
        hitEffects.removeIf(effect -> {
            effect.update();
            return effect.isExpired();
        });
    }

    private void checkGameEnd() {
        boolean allNotesProcessed = noteIndex >= currentSong.notes.size() && activeTiles.isEmpty();
        if (allNotesProcessed) {
            if (audioPlayer.isLoaded) {
                if (audioPlayer.isFinished() || !audioPlayer.isPlaying()) {
                    endGame();
                }
            } else {
                endGame();
            }
        }
    }

    @Override
    public void renderBackground(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        // 不渲染默认32x32像素菜单背景纹理和模糊效果,游戏自行绘制不透明背景
    }

    @Override
    public void render(GuiGraphics guiGraphics, int mouseX, int mouseY, float partialTick) {
        // 绘制背景
        guiGraphics.fill(0, 0, this.width, this.height, 0xFF0A0A0A);

        if (songSelectMode) {
            renderSongSelection(guiGraphics);
        } else {
            renderGameArea(guiGraphics);
            renderGameUI(guiGraphics);

            if (gameOver) {
                renderGameOverScreen(guiGraphics);
            }
        }

        super.render(guiGraphics, mouseX, mouseY, partialTick);
        if (showExitConfirm) GameRenderHelper.drawExitConfirmOverlay(guiGraphics, font, width, height, mouseX, mouseY);
    }

    private void renderGameArea(GuiGraphics guiGraphics) {
        // 各轨道渐变背景
        for (int i = 0; i < LANE_COUNT; i++) {
            int lx = gameStartX + i * laneWidth;
            guiGraphics.fill(lx, gameStartY, lx + laneWidth, gameStartY + gameAreaHeight, LANE_DARK[i]);
            // 速度感横线
            for (int gy = gameStartY + ((int)(System.currentTimeMillis() / 80) % 30); gy < gameStartY + gameAreaHeight; gy += 30) {
                guiGraphics.fill(lx, gy, lx + laneWidth, gy + 1, 0x12FFFFFF);
            }
            // 分隔线
            if (i > 0) guiGraphics.fill(lx - 1, gameStartY, lx + 1, gameStartY + gameAreaHeight, 0xFF000000);
        }

        renderHitZone(guiGraphics);
        renderTiles(guiGraphics);
        renderEffects(guiGraphics);

        // 底部按键标签（白色钢琴键）
        int keyH = 36;
        int keyY = gameStartY + gameAreaHeight;
        for (int i = 0; i < LANE_COUNT; i++) {
            int lx = gameStartX + i * laneWidth;
            int finalI = i;
            boolean pressed = hitEffects.stream().anyMatch(e -> e.lane == finalI && System.currentTimeMillis() - e.startTime < 120);
            int keyBg = pressed ? 0xFFDDDDDD : 0xFFEEEEEE;
            guiGraphics.fill(lx + 1, keyY, lx + laneWidth - 1, keyY + keyH, keyBg);
            guiGraphics.fill(lx + 1, keyY, lx + laneWidth - 1, keyY + 3, 0xFFFFFFFF);
            if (!pressed) guiGraphics.fill(lx + 1, keyY + keyH - 3, lx + laneWidth - 1, keyY + keyH, 0xFFAAAAAA);
            guiGraphics.drawCenteredString(font, LANE_KEYS[i],
                gameStartX + i * laneWidth + laneWidth / 2, keyY + keyH / 2 - 4, 0xFF333333);
        }

        // 外框
        guiGraphics.fill(gameStartX - 2, gameStartY - 2, gameStartX + gameAreaWidth + 2, gameStartY - 1, 0xFF555577);
        guiGraphics.fill(gameStartX - 2, gameStartY - 2, gameStartX - 1, gameStartY + gameAreaHeight + keyH + 2, 0xFF555577);
        guiGraphics.fill(gameStartX + gameAreaWidth + 1, gameStartY - 2, gameStartX + gameAreaWidth + 2, gameStartY + gameAreaHeight + keyH + 2, 0xFF555577);
        guiGraphics.fill(gameStartX - 2, gameStartY + gameAreaHeight + keyH + 1, gameStartX + gameAreaWidth + 2, gameStartY + gameAreaHeight + keyH + 2, 0xFF555577);
    }

    private void renderHitZone(GuiGraphics guiGraphics) {
        // 打击线：金色粗线是判定点
        int lineY = hitZoneY + HIT_ZONE_HEIGHT / 2;
        guiGraphics.fill(gameStartX, lineY - 3, gameStartX + gameAreaWidth, lineY + 3, 0xCCFFD700);
        guiGraphics.fill(gameStartX, lineY - 1, gameStartX + gameAreaWidth, lineY + 1, 0xFFFFFFFF);
        // 各轨道按下高亮
        for (int i = 0; i < LANE_COUNT; i++) {
            int finalI = i;
            boolean lit = hitEffects.stream().anyMatch(e -> e.lane == finalI && System.currentTimeMillis() - e.startTime < 120);
            if (lit) {
                int lx = gameStartX + i * laneWidth;
                guiGraphics.fill(lx + 1, hitZoneY, lx + laneWidth - 1, hitZoneY + HIT_ZONE_HEIGHT, 0x44FFFFFF);
            }
        }
    }

    private void renderTiles(GuiGraphics guiGraphics) {
        for (Note tile : activeTiles) {
            if (tile.hit) {
                continue;
            }

            // 计算音符块的Y位置
            long timeDiff = currentGameTime - tile.timestamp;
            float y = hitZoneY + HIT_ZONE_HEIGHT / 2 + timeDiff * fallSpeed;

            int x = gameStartX + tile.lane * laneWidth;
            int tileHeight = 40;

            // 检查音符是否在可见区域内（扩展上边界检查）
            if (y + tileHeight < gameStartY - 50 || y > gameStartY + gameAreaHeight + 50) {
                continue; // 跳过不在可见区域的音符
            }

            int color = tile.missed ? 0xAAFF5722 : LANE_BRIGHT[tile.lane % LANE_COUNT];

            // 确保渲染坐标在游戏区域内
            int renderY = Math.max(gameStartY, (int)y);
            int renderHeight = tileHeight;

            // 如果音符部分超出上边界，调整高度
            if (y < gameStartY) {
                renderHeight = tileHeight - (gameStartY - (int)y);
            }

            // 如果音符部分超出下边界，调整高度
            if (renderY + renderHeight > gameStartY + gameAreaHeight) {
                renderHeight = gameStartY + gameAreaHeight - renderY;
            }

            if (renderHeight > 0) {
                // 绘制音符块主体
                guiGraphics.fill(x + 2, renderY, x + laneWidth - 2, renderY + renderHeight, color);

                // 绘制高光效果（只在顶部可见时绘制）
                if (y >= gameStartY) {
                    guiGraphics.fill(x + 2, (int)y, x + laneWidth - 2, (int)y + 8, adjustBrightness(color, 1.3f));
                }

                // 绘制边框
                if (y >= gameStartY) {
                    guiGraphics.fill(x + 2, (int)y - 1, x + laneWidth - 2, (int)y, 0xFFFFFFFF);
                }
                guiGraphics.fill(x + 2, renderY + renderHeight, x + laneWidth - 2, renderY + renderHeight + 1, 0xFF000000);
                guiGraphics.fill(x + 1, renderY, x + 2, renderY + renderHeight, 0xFFFFFFFF);
                guiGraphics.fill(x + laneWidth - 2, renderY, x + laneWidth - 1, renderY + renderHeight, 0xFF000000);
            }
        }
    }

    private void renderEffects(GuiGraphics guiGraphics) {
        for (HitEffect effect : hitEffects) {
            int x = gameStartX + effect.lane * laneWidth + laneWidth / 2;
            int y = hitZoneY + HIT_ZONE_HEIGHT / 2;

            // 计算颜色和透明度
            int color = effect.result.color;
            int alpha = (int)(effect.alpha * 255);
            color = (alpha << 24) | (color & 0xFFFFFF);

            // 绘制特效文字
            String text = effect.result.text;
            int textWidth = font.width(text);
            guiGraphics.drawString(font, text, x - textWidth / 2, y - 20, color);

            // 绘制光圈效果
            if (effect.result != HitResult.MISS) {
                int radius = (int)(30 * (1.0f - effect.alpha));
                guiGraphics.fill(x - radius, y - radius, x + radius, y + radius, (alpha / 4 << 24) | (color & 0xFFFFFF));
            }
        }

        // 显示最近的判定结果
        if (System.currentTimeMillis() - lastHitTime < 500 && lastHitResult != null) {
            String text = lastHitResult.text;
            int textWidth = font.width(text);
            int x = gameStartX + gameAreaWidth / 2 - textWidth / 2;
            int y = gameStartY + 50;

            guiGraphics.drawString(font, text, x, y, lastHitResult.color);
        }
    }

    private void renderControlHints(GuiGraphics guiGraphics) {
        String[] hints = {
                "键盘控制:",
                "1 2 3 4 - 对应四个轨道",
                "A S D F - 替代按键"
        };

        int hintStartY = gameStartY + gameAreaHeight + 50;
        int hintX = gameStartX;

        for (int i = 0; i < hints.length; i++) {
            int color = i == 0 ? 0xFFFFD700 : 0xFFAAAAAA; // 第一行标题用金色
            guiGraphics.drawString(font, hints[i], hintX, hintStartY + i * (font.lineHeight + 2), color);
        }
    }

    private void renderGameUI(GuiGraphics guiGraphics) {
        // 歌曲信息
        String title = currentSong.name + " - " + currentSong.artist;
        int titleX = (this.width - font.width(title)) / 2;
        guiGraphics.drawString(font, title, titleX, gameStartY - 60, 0xFFFFFFFF);

        String difficulty = "\u96be\u5ea6: " + currentSong.difficulty + " | BPM: " + currentSong.bpm; // "难度: "
        int diffX = (this.width - font.width(difficulty)) / 2;
        guiGraphics.drawString(font, difficulty, diffX, gameStartY - 45, 0xFFCCCCCC);

        // 分数和连击
        String scoreText = "\u5206\u6570: " + score; // "分数: "
        String comboText = "\u8fde\u51fb: " + combo; // "连击: "
        String maxComboText = "\u6700\u5927\u8fde\u51fb: " + maxCombo; // "最大连击: "

        int infoY = gameStartY - 25;
        guiGraphics.drawString(font, scoreText, gameStartX, infoY, 0xFFFFD700);
        guiGraphics.drawString(font, comboText, gameStartX + 150, infoY, combo > 0 ? 0xFF4CAF50 : 0xFFCCCCCC);
        guiGraphics.drawString(font, maxComboText, gameStartX + 250, infoY, 0xFFCCCCCC);

        // 游戏进度
        if (gameActive && currentSong.notes.size() > 0) {
            float progress = (float) noteIndex / currentSong.notes.size();
            String progressText = String.format("\u8fdb\u5ea6: %.1f%%", progress * 100); // "进度: "
            guiGraphics.drawString(font, progressText, gameStartX, gameStartY - 5, 0xFFCCCCCC);
        }

        if (gamePaused) {
            String pauseText = "\u6e38\u620f\u6682\u505c";
            String resumeHint = "\u70b9\u51fb'\u7ee7\u7eed'\u6216\u6309\u7a7a\u683c\u952e\u6062\u590d\u6e38\u620f";

            int pauseX = (this.width - font.width(pauseText)) / 2;
            int hintX = (this.width - font.width(resumeHint)) / 2;
            int pauseY = (this.height - font.lineHeight) / 2;

            guiGraphics.flush(); // 防止先绘制的游戏内容盖住遮罩背景（批量渲染text批次后置）
            guiGraphics.fill(0, 0, this.width, this.height, 0x80000000);
            guiGraphics.drawString(font, pauseText, pauseX, pauseY, 0xFFFFFFFF);
            guiGraphics.drawString(font, resumeHint, hintX, pauseY + font.lineHeight + 10, 0xFFCCCCCC);
        }
    }

    private void renderSongSelection(GuiGraphics guiGraphics) {
        // 标题
        String title = "选择歌曲";
        int titleX = (this.width - font.width(title)) / 2;
        guiGraphics.drawString(font, title, titleX, this.height / 2 - 150, 0xFFFFFFFF);

        // 当前选择的歌曲信息
        if (!availableSongs.isEmpty() && currentSong != null) {
            String songName = currentSong.name;
            String artist = "艺术家: " + currentSong.artist;
            String difficulty = "难度: " + currentSong.difficulty;
            String bpm = "BPM: " + currentSong.bpm;
            String noteCount = "音符数: " + currentSong.notes.size();

            int centerX = this.width / 2;
            int startY = this.height / 2 - 100;

            // 歌曲名称
            int nameX = centerX - font.width(songName) / 2;
            guiGraphics.drawString(font, songName, nameX, startY, 0xFFFFD700);

            // 其他信息
            int infoY = startY + 30;
            int lineHeight = font.lineHeight + 5;

            String[] infos = {artist, difficulty, bpm, noteCount};
            for (String info : infos) {
                int infoX = centerX - font.width(info) / 2;
                guiGraphics.drawString(font, info, infoX, infoY, 0xFFCCCCCC);
                infoY += lineHeight;
            }

            // 显示歌曲索引
            String indexText = (selectedSongIndex + 1) + " / " + availableSongs.size();
            int indexX = centerX - font.width(indexText) / 2;
            guiGraphics.drawString(font, indexText, indexX, infoY + 20, 0xFFAAAAAA);

            // 音频状态
            String audioStatus;
            int audioColor;

            if (currentSong.audioData != null && currentSong.audioData.length > 0) {
                float sizeMB = currentSong.audioData.length / (1024.0f * 1024.0f);
                audioStatus = String.format("音频: 已嵌入 (%.2fMB)", sizeMB);
                audioColor = audioPlayer.isLoaded ? 0xFF4CAF50 : 0xFFFF9800;
            } else if (currentSong.audioFile != null && !currentSong.audioFile.isEmpty()) {
                audioStatus = audioPlayer.isLoaded ? "音频: 已加载 (路径)" : "音频: 加载失败 (路径)";
                audioColor = audioPlayer.isLoaded ? 0xFF4CAF50 : 0xFFFF5722;
            } else {
                audioStatus = "音频: 无";
                audioColor = 0xFFAAAAAA;
            }

            int audioX = centerX - font.width(audioStatus) / 2;
            guiGraphics.drawString(font, audioStatus, audioX, infoY + 40, audioColor);
        }

        // 操作说明（包含键盘操作）
        String[] instructions = {
                "使用左右按钮或 ← → / A D 键选择歌曲",
                "点击'选择'或按 回车/空格 开始游戏",
                "按 ESC 退出"
        };

        int instructionY = this.height / 2 + 100;
        for (String instruction : instructions) {
            int instructionX = (this.width - font.width(instruction)) / 2;
            guiGraphics.drawString(font, instruction, instructionX, instructionY, 0xFFAAAAAA);
            instructionY += font.lineHeight + 3;
        }
    }

    private void renderGameOverScreen(GuiGraphics guiGraphics) {
        // 半透明遮罩
        guiGraphics.flush(); // 防止先绘制的游戏内容盖住遮罩背景（批量渲染text批次后置）
        guiGraphics.fill(0, 0, this.width, this.height, 0x80000000);

        // 游戏结束窗口
        int windowWidth = Math.min(400, this.width - 100);
        int windowHeight = Math.min(300, this.height - 100);
        int windowX = (this.width - windowWidth) / 2;
        int windowY = (this.height - windowHeight) / 2;

        guiGraphics.fill(windowX, windowY, windowX + windowWidth, windowY + windowHeight, 0xFF2E2E2E);
        guiGraphics.fill(windowX, windowY, windowX + windowWidth, windowY + 3, 0xFF4CAF50);

        // 游戏结束文本
        String gameOverText = "\u6f14\u594f\u5b8c\u6210!"; // "演奏完成!"
        String songText = currentSong.name;
        String finalScoreText = "\u6700\u7ec8\u5206\u6570: " + score; // "最终分数: "
        String maxComboText = "\u6700\u5927\u8fde\u51fb: " + maxCombo; // "最大连击: "

        int totalNotes = perfectHits + greatHits + goodHits + missedHits;
        String accuracyText = totalNotes > 0 ?
                String.format("\u51c6\u786e\u7387: %.1f%%", (float)(perfectHits + greatHits + goodHits) / totalNotes * 100) :  // "准确率: "
                "\u51c6\u786e\u7387: 0%"; // "准确率: 0%"

        // 居中绘制文本
        int textY = windowY + 30;
        int lineHeight = font.lineHeight + 5;

        guiGraphics.drawString(font, gameOverText,
                windowX + (windowWidth - font.width(gameOverText)) / 2, textY, 0xFFFFFFFF);
        textY += lineHeight;

        guiGraphics.drawString(font, songText,
                windowX + (windowWidth - font.width(songText)) / 2, textY, 0xFFFFD700);
        textY += lineHeight * 2;

        guiGraphics.drawString(font, finalScoreText,
                windowX + (windowWidth - font.width(finalScoreText)) / 2, textY, 0xFFFFD700);
        textY += lineHeight;

        guiGraphics.drawString(font, maxComboText,
                windowX + (windowWidth - font.width(maxComboText)) / 2, textY, 0xFF4CAF50);
        textY += lineHeight;

        guiGraphics.drawString(font, accuracyText,
                windowX + (windowWidth - font.width(accuracyText)) / 2, textY, 0xFF2196F3);
        textY += lineHeight * 2;

        // 详细统计
        String[] statTexts = {
                "Perfect: " + perfectHits,
                "Great: " + greatHits,
                "Good: " + goodHits,
                "Miss: " + missedHits
        };

        for (String statText : statTexts) {
            guiGraphics.drawString(font, statText,
                    windowX + (windowWidth - font.width(statText)) / 2, textY, 0xFFCCCCCC);
            textY += lineHeight;
        }

        textY += lineHeight;
        String backText = "\u70b9\u51fb'\u8fd4\u56de'\u9009\u62e9\u5176\u4ed6\u6b4c\u66f2"; // "点击'返回'选择其他歌曲"
        guiGraphics.drawString(font, backText,
                windowX + (windowWidth - font.width(backText)) / 2, textY, 0xFFA5D6A7);
    }

    private boolean leftMouseDown = false;
    private long lastClickTime = 0;
    private int currentClickLane = -1;

    @Override
    public boolean mouseClicked(double mouseX, double mouseY, int button) {
        if (showExitConfirm) { int click = GameRenderHelper.getExitConfirmClick(mouseX, mouseY, width, height); if (click == 1) { showExitConfirm = false; Minecraft.getInstance().setScreen(new GameSelectorScreen()); return true; } if (click == 2) { showExitConfirm = false; return true; } return true; }
        if (songSelectMode || !gameActive || gamePaused || gameOver || button != 0) {
            return super.mouseClicked(mouseX, mouseY, button);
        }

        leftMouseDown = true;
        lastClickTime = System.currentTimeMillis();

        if (mouseX >= gameStartX && mouseX < gameStartX + gameAreaWidth &&
                mouseY >= gameStartY && mouseY < gameStartY + gameAreaHeight) {

            int lane = (int) ((mouseX - gameStartX) / laneWidth);
            if (lane >= 0 && lane < LANE_COUNT) {
                currentClickLane = lane;
                handleLaneClick(lane);
                return true;
            }
        }

        return super.mouseClicked(mouseX, mouseY, button);
    }

    @Override
    public boolean mouseReleased(double mouseX, double mouseY, int button) {
        if (button == 0) {
            leftMouseDown = false;
            currentClickLane = -1;
        }
        return super.mouseReleased(mouseX, mouseY, button);
    }

    private void handleLaneClick(int lane) {
        Note closestTile = null;
        float closestDistance = Float.MAX_VALUE;

        // 找到该轨道中最接近打击区域的音符块
        for (Note tile : activeTiles) {
            if (tile.lane == lane && !tile.hit && !tile.missed) {
                // 修改：更宽松的时间窗口，允许更早和更晚的点击
                long timeDiff = currentGameTime - tile.timestamp;

                // 允许提前200毫秒到延后350毫秒点击
                if (timeDiff >= -200 && timeDiff <= MISS_THRESHOLD) {
                    float distance = Math.abs(timeDiff);
                    if (distance < closestDistance) {
                        closestDistance = distance;
                        closestTile = tile;
                    }
                }
            }
        }

        if (closestTile != null) {
            // 判定命中
            HitResult result = getHitResult(closestDistance);
            handleHit(closestTile, result);
        } else {
            playSound(SoundEvents.WOODEN_BUTTON_CLICK_ON);
        }
    }

    private HitResult getHitResult(float distance) {
        if (distance <= PERFECT_THRESHOLD) {
            return HitResult.PERFECT;
        } else if (distance <= GREAT_THRESHOLD) {
            return HitResult.GREAT;
        } else if (distance <= GOOD_THRESHOLD) {
            return HitResult.GOOD;
        } else {
            return HitResult.MISS;
        }
    }

    private void handleHit(Note tile, HitResult result) {
        tile.hit = true;

        // 更新统计
        switch (result) {
            case PERFECT:
                perfectHits++;
                break;
            case GREAT:
                greatHits++;
                break;
            case GOOD:
                goodHits++;
                break;
            case MISS:
                missedHits++;
                break;
        }

        if (result != HitResult.MISS) {
            // 增加分数和连击
            combo++;
            maxCombo = Math.max(maxCombo, combo);

            int bonusScore = result.score + (combo / 10) * 5; // 连击奖励
            score += bonusScore;

            // 播放音效
            playSound(PIANO_SOUNDS[tile.noteType % PIANO_SOUNDS.length]);

            // 添加视觉特效
            hitEffects.add(new HitEffect(tile.lane, result));
        } else {
            combo = 0;
            playSound(SoundEvents.VILLAGER_NO);
        }

        lastHitTime = System.currentTimeMillis();
        lastHitResult = result;
    }

    private void missHit() {
        combo = 0;
        missedHits++;
        lastHitTime = System.currentTimeMillis();
        lastHitResult = HitResult.MISS;
        playSound(SoundEvents.VILLAGER_NO);
    }

    private int adjustBrightness(int color, float factor) {
        int alpha = (color >> 24) & 0xFF;
        int red = (int)(((color >> 16) & 0xFF) * factor);
        int green = (int)(((color >> 8) & 0xFF) * factor);
        int blue = (int)((color & 0xFF) * factor);

        red = Math.min(255, Math.max(0, red));
        green = Math.min(255, Math.max(0, green));
        blue = Math.min(255, Math.max(0, blue));

        return (alpha << 24) | (red << 16) | (green << 8) | blue;
    }

    private void playSound(SoundEvent sound) {
        if (minecraft != null && minecraft.level != null) {
            minecraft.level.playLocalSound(0, 0, 0, sound, SoundSource.MASTER, 0.8f, 1.0f, false);
        }
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }

    @Override
    public void onClose() {
        super.onClose();
        Minecraft.getInstance().getSoundManager().stop();
        Minecraft.getInstance().setScreen(new GameSelectorScreen());
    }

    @Override
    public void removed() {
        super.removed();
        if (audioPlayer != null) {
            audioPlayer.closeAudio();
        }
    }
}