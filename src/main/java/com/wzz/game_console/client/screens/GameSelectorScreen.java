package com.wzz.game_console.client.screens;

import com.wzz.game_console.client.screens.games.gogame.GoGame;
import com.wzz.game_console.client.screens.games.gogame.GoGameScreen;
import com.wzz.game_console.client.screens.games.landlord.LandlordGameScreen;
import com.wzz.game_console.client.screens.games.tictactoe.TicTacToeGame;
import com.wzz.game_console.client.screens.games.tictactoe.TicTacToeScreen;
import com.wzz.game_console.util.GameRenderHelper;
import com.wzz.game_console.util.GameSettings;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.api.distmarker.OnlyIn;
import com.wzz.game_console.client.screens.games.*;
import org.jetbrains.annotations.NotNull;
import org.lwjgl.glfw.GLFW;

import java.awt.FileDialog;
import java.awt.Frame;
import java.io.File;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Supplier;

@OnlyIn(Dist.CLIENT)
public class GameSelectorScreen extends Screen {

    // ─── 游戏信息记录 ───
    private record GameEntry(String name, String icon, String description, String category, Supplier<Screen> factory) {}

    private final List<GameEntry> games = new ArrayList<>();
    private int currentPage = 0;
    private int gamesPerPage = 8;
    private long tickCount = 0;
    private int hoveredIndex = -1;
    private String filterCategory = "全部";
    private boolean lobbyHovered = false;

    // 分类
    private static final String[] CATEGORIES = {"全部", "棋牌", "动作", "益智", "休闲"};
    // 分类按钮统一的水平内边距与按钮间距（宽度累加与绘制必须使用同一数值）
    private static final int CATEGORY_PADDING = 16;
    private static final int CATEGORY_GAP = 4;
    private int selectedCategoryIndex = 0;
    /** 导入消息（临时显示） */
    private String importMessage = null;
    private long importMessageTime = 0;

    public GameSelectorScreen() {
        super(Component.literal("Game Console 游戏机"));
        initializeGames();
    }

    @Override
    public void onClose() {
        super.onClose();
        // 本界面自身没有播放任何声音，不需要停止声音
        // （原先的 getSoundManager().stop() 会误停唱片、环境音等所有声音，已移除）
    }

    private void initializeGames() {
        // 棋牌类
        games.add(new GameEntry("五子棋",    "⚫", "经典棋类对弈，先连成五子者获胜", "棋牌", GomokuScreen::new));
        games.add(new GameEntry("围棋",      "⚪", "围地为王，黑白博弈的艺术", "棋牌", () -> new GoGameScreen(new GoGame())));
        games.add(new GameEntry("井字棋",    "✖", "简单而经典的三子连线游戏", "棋牌", () -> new TicTacToeScreen(TicTacToeGame.GameMode.SINGLE_PLAYER)));
        games.add(new GameEntry("中国象棋",  "♚", "千年国粹，楚河汉界的较量", "棋牌", ChessGameScreen::new));
        games.add(new GameEntry("国际象棋", "♟", "六十四格，黑白王后的战场", "棋牌", WesternChessScreen::new));
        games.add(new GameEntry("斗地主",    "🃏", "经典三人扑克牌游戏", "棋牌", LandlordGameScreen::new));
        games.add(new GameEntry("猜大小",    "🎲", "猜测骰子点数大小，考验运气与直觉", "棋牌", DiceGuessingScreen::new));

        // 动作类
        games.add(new GameEntry("贪吃蛇",    "🐍", "控制小蛇吃食物，不断成长变长", "动作", SnakeGameScreen::new));
        games.add(new GameEntry("像素鸟",    "🐦", "点击让小鸟飞过管道间隙", "动作", FlappyBirdScreen::new));
        games.add(new GameEntry("打砖块",    "🧱", "用挡板反弹球击碎所有砖块", "动作", BreakoutScreen::new));
        games.add(new GameEntry("平台跳跃",  "🏃", "跳跃平台收集金币的冒险之旅", "动作", PlatformerScreen::new));
        games.add(new GameEntry("森林冰火人","❄", "双人合作冒险，收集钻石过关", "动作", IceFireGameScreen::new));
        games.add(new GameEntry("黑洞大作战","🕳", "控制黑洞吞噬一切的io游戏", "动作", BlackHoleGameScreen::new));
        games.add(new GameEntry("水果忍者",  "🍉", "挥动鼠标切开飞出的水果", "动作", FruitNinjaScreen::new));
        games.add(new GameEntry("颜色追逐",  "🎨", "在色彩世界中追逐与闪避", "动作", ColorChaseGameScreen::new));
        games.add(new GameEntry("跳一跳Pro", "⬆", "蓄力跳跃，精准落在平台上", "动作", JumpGameScreen::new));

        // 益智类
        games.add(new GameEntry("扫雷",      "💣", "找出所有地雷而不触发它们", "益智", MinesweeperScreen::new));
        games.add(new GameEntry("俄罗斯方块","🟦", "旋转方块组成完整行并消除", "益智", TetrisGameScreen::new));
        games.add(new GameEntry("接水管",    "🔧", "旋转管道使水流从起点到终点", "益智", PipePuzzleScreen::new));
        games.add(new GameEntry("推箱子",    "📦", "推动箱子到指定目标位置", "益智", SokobanScreen::new));
        games.add(new GameEntry("华容道",    "🏯", "滑动方块让曹操到达出口", "益智", KlotskiScreen::new));
        games.add(new GameEntry("拼图游戏",  "🧩", "移动拼图块还原完整图片", "益智", PuzzleGameScreen::new));
        games.add(new GameEntry("数独",      "🔢", "填入数字使每行列宫均不重复", "益智", SudokuGameScreen::new));

        // 休闲类
        games.add(new GameEntry("迷宫",      "🌀", "在迷宫中找到出口，小心鬼魂", "休闲", MazeGameScreen::new));
        games.add(new GameEntry("记忆翻牌",  "🎴", "翻牌找出所有匹配的对子", "休闲", MemoryCardScreen::new));
        games.add(new GameEntry("消消乐",    "💎", "交换相邻物品消除三个以上连线", "休闲", Match3GameScreen::new));
        games.add(new GameEntry("塔防游戏",  "🏰", "建造防御塔抵御怪物入侵", "休闲", TowerDefenseScreen::new));
        games.add(new GameEntry("记忆反应",  "🧠", "记住并重复越来越长的序列", "休闲", MemoryGameScreen::new));
        games.add(new GameEntry("鼠标反应",  "🖱", "控制鼠标穿过不断变窄的隧道", "休闲", MouseTunnelGameScreen::new));
        games.add(new GameEntry("Minecraft 2D", "⛏", "2D版Minecraft，挖掘与建造", "休闲", Minecraft2DScreen::new));
        games.add(new GameEntry("音游",      "🎵", "跟随节奏点击下落的音符", "休闲", PianoTilesGameScreen::new));
        games.add(new GameEntry("打地鼠",    "🔨", "快速点击冒出的地鼠", "休闲", WhackAMoleScreen::new));
    }

    private List<GameEntry> getFilteredGames() {
        if ("全部".equals(filterCategory)) return games;
        List<GameEntry> filtered = new ArrayList<>();
        for (GameEntry g : games) {
            if (g.category.equals(filterCategory)) filtered.add(g);
        }
        return filtered;
    }

    @Override
    public void tick() {
        tickCount++;
    }

    @Override
    public void init() {
        int cardH = 28;
        int availableH = this.height - 120;
        gamesPerPage = Math.max(4, availableH / cardH);
    }

    @Override
    public void render(@NotNull GuiGraphics g, int mouseX, int mouseY, float partialTick) {
        int cx = width / 2;

        // ─── 背景 ───
        GameRenderHelper.fillGradientBackground(g, width, height, 0xFF0A0A18, 0xFF151530);
        GameRenderHelper.renderDecorativeLines(g, width, height, tickCount, 0x002244);

        // ─── 标题区域 ───
        GameRenderHelper.drawShadowedCenteredText(g, font, "Game Console 游戏机", cx, 12, 0xFFDD44, 2);
        g.drawCenteredString(font, "Game Console", cx, 32, 0x556688);

        // 分割线
        GameRenderHelper.drawDivider(g, cx - 120, 42, 240, GameRenderHelper.ACCENT_BLUE, GameRenderHelper.ACCENT_RED);

        // ─── 分类标签 ───
        int catY = 48;
        int catTotalW = 0;
        for (String cat : CATEGORIES) catTotalW += font.width(cat) + CATEGORY_PADDING + CATEGORY_GAP;
        // 联机大厅按钮宽度
        int lobbyW = font.width("🌐 联机大厅") + CATEGORY_PADDING;
        int totalBarW = catTotalW + 8 + lobbyW;
        int catX = cx - totalBarW / 2;

        for (int i = 0; i < CATEGORIES.length; i++) {
            String cat = CATEGORIES[i];
            int tw = font.width(cat) + CATEGORY_PADDING;
            boolean isSelected = i == selectedCategoryIndex;
            boolean isHover = mouseX >= catX && mouseX <= catX + tw && mouseY >= catY && mouseY <= catY + 14;

            if (isSelected) {
                g.fill(catX, catY, catX + tw, catY + 14, 0xFF2244AA);
                g.fill(catX, catY + 13, catX + tw, catY + 14, 0xFF44AAFF);
            } else if (isHover) {
                g.fill(catX, catY, catX + tw, catY + 14, 0x44FFFFFF);
            }
            g.drawString(font, cat, catX + 6, catY + 3, isSelected ? 0xFFFFFF : 0x888888);
            catX += tw + CATEGORY_GAP;
        }

        // ─── 联机大厅按钮 ───
        catX += 8; // 间隔
        lobbyHovered = mouseX >= catX && mouseX <= catX + lobbyW && mouseY >= catY && mouseY <= catY + 14;
        g.fill(catX, catY, catX + lobbyW, catY + 14, lobbyHovered ? 0xFF1A4A3A : 0xFF143328);
        g.fill(catX, catY + 13, catX + lobbyW, catY + 14, 0xFF44CCAA);
        g.drawString(font, "🌐 联机大厅", catX + 6, catY + 3, lobbyHovered ? 0x66FFCC : 0x44AA88);

        // ─── 游戏列表 ───
        List<GameEntry> filtered = getFilteredGames();
        int totalPages = Math.max(1, (int) Math.ceil((double) filtered.size() / gamesPerPage));
        if (currentPage >= totalPages) currentPage = totalPages - 1;

        int listStartY = 68;
        int cardW = Math.min(280, width - 40);
        int cardH = 26;
        int startIdx = currentPage * gamesPerPage;
        int endIdx = Math.min(startIdx + gamesPerPage, filtered.size());

        hoveredIndex = -1;
        for (int i = startIdx; i < endIdx; i++) {
            int idx = i - startIdx;
            GameEntry entry = filtered.get(i);
            int cardX = cx - cardW / 2;
            int cardY = listStartY + idx * (cardH + 2);

            boolean hover = mouseX >= cardX && mouseX <= cardX + cardW && mouseY >= cardY && mouseY <= cardY + cardH;
            if (hover) hoveredIndex = i;

            // 卡片背景
            int bgColor = hover ? 0xFF252550 : 0xFF1A1A35;
            g.fill(cardX, cardY, cardX + cardW, cardY + cardH, bgColor);
            // 左侧分类色条
            int catColor = getCategoryColor(entry.category);
            g.fill(cardX, cardY, cardX + 3, cardY + cardH, catColor);
            // 悬停高光边框
            if (hover) {
                g.fill(cardX, cardY, cardX + cardW, cardY + 1, catColor);
                g.fill(cardX, cardY + cardH - 1, cardX + cardW, cardY + cardH, GameRenderHelper.darken(catColor, 0.5f));
            }

            // 图标和名称
            g.drawString(font, entry.icon + " " + entry.name, cardX + 8, cardY + (cardH - 8) / 2, hover ? 0xFFFFFF : 0xCCCCCC);

            // 分类标签
            String catLabel = "[" + entry.category + "]";
            g.drawString(font, catLabel, cardX + cardW - font.width(catLabel) - 5, cardY + (cardH - 8) / 2, 0x666666);
        }

        // ─── 悬停描述浮窗 ───
        if (hoveredIndex >= 0 && hoveredIndex < filtered.size()) {
            GameEntry hovered = filtered.get(hoveredIndex);
            String desc = hovered.description;
            int descW = font.width(desc);
            int tipX = cx - descW / 2 - 6;
            int tipY = listStartY - 16;
            g.fill(tipX, tipY, tipX + descW + 12, tipY + 14, 0xEE111122);
            g.fill(tipX, tipY, tipX + descW + 12, tipY + 1, getCategoryColor(hovered.category));
            g.drawString(font, desc, tipX + 6, tipY + 3, 0xFFFFFF);
        }

        // ─── 翻页控制 ───
        int navY = this.height - 24;
        if (currentPage > 0) {
            GameRenderHelper.drawSecondaryButton(g, font, "◀ 上一页",
                    cx - 130, navY, 80, 18, mouseX, mouseY);
        }
        String pageText = (currentPage + 1) + " / " + totalPages;
        g.drawCenteredString(font, pageText, cx, navY + 5, 0x888888);
        if (currentPage < totalPages - 1) {
            GameRenderHelper.drawSecondaryButton(g, font, "下一页 ▶",
                    cx + 50, navY, 80, 18, mouseX, mouseY);
        }

        // ─── 底部信息 ───
        g.drawCenteredString(font, "共 " + filtered.size() + " 款游戏  |  ESC 退出", cx, height - 10, 0x444444);

        // ─── 导入设置按钮 ───
        GameRenderHelper.drawButton(g, font, "导入设置", 5, height - 24, 60, 18, mouseX, mouseY,
                0xFF222233, 0xFF333355, 0xFF666688);

        // ─── 导入消息（3秒后消失） ───
        if (importMessage != null && System.currentTimeMillis() - importMessageTime < 3000) {
            int msgColor = importMessage.contains("失败") ? 0xFFFF4444 : 0xFF44FF44;
            g.drawCenteredString(font, importMessage, cx, height - 40, msgColor);
        } else {
            importMessage = null;
        }
    }

    private int getCategoryColor(String category) {
        return switch (category) {
            case "棋牌" -> 0xFFFF8800;
            case "动作" -> 0xFFFF2244;
            case "益智" -> 0xFF2288FF;
            case "休闲" -> 0xFF44CC44;
            default -> 0xFF888888;
        };
    }

    /** 打开文件对话框导入外部游戏设置 JSON */
    private void importSettingsFromFile() {
        try {
            Frame frame = new Frame();
            frame.setAlwaysOnTop(true);
            FileDialog dialog = new FileDialog(frame, "选择游戏设置文件 (.json)", FileDialog.LOAD);
            dialog.setFile("*.json");
            dialog.setVisible(true);
            String filePath = dialog.getFile();
            String dirPath = dialog.getDirectory();
            frame.dispose();

            if (filePath == null || dirPath == null) return;

            File srcFile = new File(dirPath, filePath);
            if (!srcFile.exists() || !srcFile.getName().endsWith(".json")) return;

            Path srcPath = srcFile.toPath();
            boolean success = GameSettings.importFromFile(srcPath);

            importMessage = success ? "设置导入成功！" : "导入失败：文件格式不正确";
            importMessageTime = System.currentTimeMillis();
        } catch (Exception e) {
            importMessage = "导入失败：" + e.getMessage();
            importMessageTime = System.currentTimeMillis();
        }
    }

    @Override
    public boolean mouseClicked(double mx, double my, int btn) {
        int cx = width / 2;

        // ─── 分类标签点击（与渲染逻辑使用同一套宽度常量） ───
        int catY = 48;
        int catTotalW = 0;
        for (String cat : CATEGORIES) catTotalW += font.width(cat) + CATEGORY_PADDING + CATEGORY_GAP;
        int lobbyW = font.width("🌐 联机大厅") + CATEGORY_PADDING;
        int totalBarW = catTotalW + 8 + lobbyW;
        int catX = cx - totalBarW / 2;

        for (int i = 0; i < CATEGORIES.length; i++) {
            int tw = font.width(CATEGORIES[i]) + CATEGORY_PADDING;
            if (mx >= catX && mx <= catX + tw && my >= catY && my <= catY + 14) {
                selectedCategoryIndex = i;
                filterCategory = CATEGORIES[i];
                currentPage = 0;
                return true;
            }
            catX += tw + CATEGORY_GAP;
        }

        // ─── 联机大厅点击（保持与渲染相同的间距计算） ───
        catX += 8;
        if (mx >= catX && mx <= catX + lobbyW && my >= catY && my <= catY + 14) {
            Minecraft.getInstance().setScreen(new MultiplayerLobbyScreen());
            return true;
        }

        // ─── 游戏卡片点击 ───
        List<GameEntry> filtered = getFilteredGames();
        if (hoveredIndex >= 0 && hoveredIndex < filtered.size()) {
            GameEntry entry = filtered.get(hoveredIndex);
            Minecraft.getInstance().setScreen(entry.factory.get());
            return true;
        }

        // ─── 翻页按钮 ───
        int navY = this.height - 24;
        int totalPages = Math.max(1, (int) Math.ceil((double) filtered.size() / gamesPerPage));
        if (mx >= cx - 130 && mx <= cx - 50 && my >= navY && my <= navY + 18 && currentPage > 0) {
            currentPage--;
            return true;
        }
        if (mx >= cx + 50 && mx <= cx + 130 && my >= navY && my <= navY + 18 && currentPage < totalPages - 1) {
            currentPage++;
            return true;
        }

        // ─── 导入设置按钮 ───
        if (mx >= 5 && mx <= 65 && my >= navY && my <= navY + 18) {
            importSettingsFromFile();
            return true;
        }

        return super.mouseClicked(mx, my, btn);
    }

    @Override
    public boolean keyPressed(int key, int scan, int mods) {
        List<GameEntry> filtered = getFilteredGames();
        int totalPages = Math.max(1, (int) Math.ceil((double) filtered.size() / gamesPerPage));
        if (key == GLFW.GLFW_KEY_LEFT && currentPage > 0) { currentPage--; return true; }
        if (key == GLFW.GLFW_KEY_RIGHT && currentPage < totalPages - 1) { currentPage++; return true; }
        return super.keyPressed(key, scan, mods);
    }

    @Override
    public boolean mouseScrolled(double mx, double my, double scrollDeltaX, double delta) {
        List<GameEntry> filtered = getFilteredGames();
        int totalPages = Math.max(1, (int) Math.ceil((double) filtered.size() / gamesPerPage));
        if (delta > 0 && currentPage > 0) currentPage--;
        if (delta < 0 && currentPage < totalPages - 1) currentPage++;
        return true;
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }
}
