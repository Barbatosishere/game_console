package com.wzz.game_console.client.screens;

import com.wzz.game_console.util.GameRenderHelper;
import com.wzz.game_console.util.GameCatalog;
import com.wzz.game_console.util.GameMenuLayout;
import net.minecraft.locale.Language;
import com.wzz.game_console.client.GameText;
import com.wzz.game_console.util.GameSettings;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.api.distmarker.OnlyIn;
import org.jetbrains.annotations.NotNull;
import org.lwjgl.glfw.GLFW;

import java.awt.FileDialog;
import java.awt.Frame;
import java.io.File;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

@OnlyIn(Dist.CLIENT)
public class GameSelectorScreen extends Screen {

    // ─── 游戏信息记录 ───
    private final List<GameCatalog.Entry> games = GameCatalog.all();
    private List<GameCatalog.Entry> filteredGames = List.of();
    private GameCatalog.Category filteredCategory;
    private int currentPage = 0;
    private int gamesPerPage = 8;
    private long tickCount = 0;
    private int hoveredIndex = -1;
    private GameCatalog.Category filterCategory = GameCatalog.Category.ALL;
    private List<GameMenuLayout.Button> controls = List.of();
    private int controlsWidth = -1;
    private Language controlsLanguage;

    // 分类
    private static final GameCatalog.Category[] CATEGORIES = GameCatalog.Category.values();
    // 分类按钮统一的水平内边距与按钮间距（宽度累加与绘制必须使用同一数值）
    private static final int CATEGORY_PADDING = 16;
    private static final int CATEGORY_GAP = 4;
    private int selectedCategoryIndex = 0;
    /** 导入消息（临时显示） */
    private String importMessage = null;
    private boolean importFailed;
    private long importMessageTime = 0;
    private final AtomicBoolean importInProgress = new AtomicBoolean();

    public GameSelectorScreen() {
        super(Component.translatable("gui.game_console.title"));
    }

    @Override
    public void onClose() {
        super.onClose();
        // 本界面自身没有播放任何声音，不需要停止声音
        // （原先的 getSoundManager().stop() 会误停唱片、环境音等所有声音，已移除）
    }

    private List<GameCatalog.Entry> getFilteredGames() {
        if (filterCategory.equals(filteredCategory)) return filteredGames;

        filteredCategory = filterCategory;
        if (filterCategory == GameCatalog.Category.ALL) {
            filteredGames = List.copyOf(games);
            return filteredGames;
        }
        List<GameCatalog.Entry> filtered = new ArrayList<>();
        for (GameCatalog.Entry g : games) {
            if (g.category() == filterCategory) filtered.add(g);
        }
        filteredGames = List.copyOf(filtered);
        return filteredGames;
    }

    @Override
    public void tick() {
        tickCount++;
    }

    @Override
    public void init() {
        controlsWidth = -1;
        gamesPerPage = Math.max(1, (height - listStartY() - 46) / 28);
    }

    @Override
    public void render(@NotNull GuiGraphics g, int mouseX, int mouseY, float partialTick) {
        int cx = width / 2;

        // ─── 背景 ───
        GameRenderHelper.fillGradientBackground(g, width, height, 0xFF0A0A18, 0xFF151530);
        GameRenderHelper.renderDecorativeLines(g, width, height, tickCount, 0x002244);

        // ─── 标题区域 ───
        GameRenderHelper.drawShadowedCenteredText(g, font, GameText.text("gui.game_console.title"), cx, 12, 0xFFDD44, 2);
        g.drawCenteredString(font, "Game Console", cx, 32, 0x556688);

        // 分割线
        GameRenderHelper.drawDivider(g, cx - 120, 42, 240, GameRenderHelper.ACCENT_BLUE, GameRenderHelper.ACCENT_RED);

        // 分类与大厅共用布局，小窗口和较长的翻译文本自动换行。
        for (GameMenuLayout.Button button : controls()) {
            boolean lobby = button.index() == CATEGORIES.length;
            boolean selected = !lobby && button.index() == selectedCategoryIndex;
            boolean hover = button.contains(mouseX, mouseY);
            String label = lobby ? GameText.text("gui.game_console.lobby")
                    : GameText.text(CATEGORIES[button.index()].translationKey());
            int color = lobby ? 0xFF143328 : selected ? 0xFF2244AA : hover ? 0x44FFFFFF : 0;
            if (color != 0) g.fill(button.x(), button.y(), button.x()+button.width(), button.y()+button.height(), color);
            if (lobby || selected) g.fill(button.x(),button.y()+13,button.x()+button.width(),button.y()+14,lobby?0xFF44CCAA:0xFF44AAFF);
            g.drawString(font,label,button.x()+6,button.y()+3,lobby?0x44AA88:selected?0xFFFFFF:0x888888);
        }

        // ─── 游戏列表 ───
        List<GameCatalog.Entry> filtered = getFilteredGames();
        int totalPages = Math.max(1, (int) Math.ceil((double) filtered.size() / gamesPerPage));
        if (currentPage >= totalPages) currentPage = totalPages - 1;

        int listStartY = listStartY();
        int cardW = Math.min(280, width - 40);
        int cardH = 26;
        int startIdx = currentPage * gamesPerPage;
        int endIdx = Math.min(startIdx + gamesPerPage, filtered.size());

        hoveredIndex = -1;
        for (int i = startIdx; i < endIdx; i++) {
            int idx = i - startIdx;
            GameCatalog.Entry entry = filtered.get(i);
            int cardX = cx - cardW / 2;
            int cardY = listStartY + idx * (cardH + 2);

            boolean hover = mouseX >= cardX && mouseX <= cardX + cardW && mouseY >= cardY && mouseY <= cardY + cardH;
            if (hover) hoveredIndex = i;

            // 卡片背景
            int bgColor = hover ? 0xFF252550 : 0xFF1A1A35;
            g.fill(cardX, cardY, cardX + cardW, cardY + cardH, bgColor);
            // 左侧分类色条
            int catColor = entry.category().color();
            g.fill(cardX, cardY, cardX + 3, cardY + cardH, catColor);
            // 悬停高光边框
            if (hover) {
                g.fill(cardX, cardY, cardX + cardW, cardY + 1, catColor);
                g.fill(cardX, cardY + cardH - 1, cardX + cardW, cardY + cardH, GameRenderHelper.darken(catColor, 0.5f));
            }

            // 图标和名称
            g.drawString(font, entry.icon() + " " + GameText.text(entry.nameKey()), cardX + 8, cardY + (cardH - 8) / 2, hover ? 0xFFFFFF : 0xCCCCCC);

            // 分类标签
            String catLabel = "[" + GameText.text(entry.category().translationKey()) + "]";
            g.drawString(font, catLabel, cardX + cardW - font.width(catLabel) - 5, cardY + (cardH - 8) / 2, 0x666666);
        }

        // ─── 翻页控制 ───
        int navY = this.height - 24;
        if (currentPage > 0) {
            GameRenderHelper.drawSecondaryButton(g, font, GameText.text("gui.game_console.previous"),
                    cx - 90, navY, 60, 18, mouseX, mouseY);
        }
        String pageText = (currentPage + 1) + " / " + totalPages;
        g.drawCenteredString(font, pageText, cx, navY + 5, 0x888888);
        if (currentPage < totalPages - 1) {
            GameRenderHelper.drawSecondaryButton(g, font, GameText.text("gui.game_console.next"),
                    cx + 30, navY, 60, 18, mouseX, mouseY);
        }

        // ─── 底部信息 ───
        g.drawCenteredString(font, GameText.text("gui.game_console.game_count", filtered.size()), cx, height - 10, 0x444444);

        // ─── 导入设置按钮 ───
        GameRenderHelper.drawButton(g, font, GameText.text("gui.game_console.import"), 5, height - 46, 60, 18, mouseX, mouseY,
                0xFF222233, 0xFF333355, 0xFF666688);

        if (hoveredIndex >= 0 && hoveredIndex < filtered.size()) {
            g.renderTooltip(font, Component.translatable(filtered.get(hoveredIndex).descriptionKey()), mouseX, mouseY);
        }

        // ─── 导入消息（3秒后消失） ───
        if (importMessage != null && System.currentTimeMillis() - importMessageTime < 3000) {
            int msgColor = importFailed ? 0xFFFF4444 : 0xFF44FF44;
            g.drawCenteredString(font, importMessage, cx, height - 40, msgColor);
        } else {
            importMessage = null;
        }
    }

    private List<GameMenuLayout.Button> controls() {
        Language language = Language.getInstance();
        if (controlsWidth == width && controlsLanguage == language) return controls;
        int[] widths = new int[CATEGORIES.length + 1];
        for (int i=0; i<CATEGORIES.length; i++) widths[i] = font.width(GameText.text(CATEGORIES[i].translationKey())) + CATEGORY_PADDING;
        widths[CATEGORIES.length] = font.width(GameText.text("gui.game_console.lobby")) + CATEGORY_PADDING;
        controls = GameMenuLayout.wrap(width, 48, 14, CATEGORY_GAP, widths);
        controlsWidth = width;
        controlsLanguage = language;
        return controls;
    }

    private int listStartY() {
        List<GameMenuLayout.Button> buttons = controls();
        return buttons.getLast().y() + buttons.getLast().height() + 6;
    }

    /** 打开文件对话框导入外部游戏设置 JSON */
    private void importSettingsFromFile() {
        if (!importInProgress.compareAndSet(false, true)) return;
        // ★ Bug修复：FileDialog.setVisible(true) 是模态阻塞调用，在 MC 主线程直接打开
        //   会冻结整个游戏（停帧、无响应）。改为在后台 daemon 线程弹窗，
        //   用户选完后 mc.execute 回主线程执行实际导入与提示（importMessage 由 render 读取）
        Thread picker = new Thread(() -> {
            Frame frame = null;
            try {
                frame = new Frame();
                frame.setAlwaysOnTop(true);
                // ★ Bug修复：原版不设位置,Windows 多显示器/扩展屏(主屏 x<0 或 y<0)
                //   时 FileDialog 会落在不可见区域,玩家看不见但模态阻塞,只能 Alt+F4。
                //   setLocationRelativeTo(null) 强制居中到主屏可视区
                frame.setLocationRelativeTo(null);
                FileDialog dialog = new FileDialog(frame, GameText.text("gui.game_console.import_picker"), FileDialog.LOAD);
                dialog.setFile("*.json");
                dialog.setLocationRelativeTo(frame);
                dialog.setVisible(true); // 只阻塞本后台线程，不再冻结游戏
                String filePath = dialog.getFile();
                String dirPath = dialog.getDirectory();

                if (filePath == null || dirPath == null) return; // 用户取消

                File srcFile = new File(dirPath, filePath);
                if (!srcFile.isFile() || !srcFile.getName().toLowerCase(java.util.Locale.ROOT).endsWith(".json")) {
                    Minecraft.getInstance().execute(() -> {
                        importFailed = true;
                        importMessage = GameText.text("gui.game_console.import_json");
                        importMessageTime = System.currentTimeMillis();
                    });
                    return;
                }

                Path srcPath = srcFile.toPath();
                // 文件读取、解析和写盘留在后台；仅界面状态在 MC 主线程更新。
                boolean success = GameSettings.importFromFile(srcPath);
                Minecraft.getInstance().execute(() -> {
                    importFailed = !success;
                    importMessage = success ? GameText.text("gui.game_console.import_ok") : GameText.text("gui.game_console.import_format");
                    importMessageTime = System.currentTimeMillis();
                });
            } catch (Exception e) {
                String msg = GameText.text("gui.game_console.import_error", e.getMessage());
                Minecraft.getInstance().execute(() -> {
                    importFailed = true;
                    importMessage = msg;
                    importMessageTime = System.currentTimeMillis();
                });
            } finally {
                // ★ 修复 AWT Frame 泄漏：dispose 移入 finally，异常/用户取消路径也会释放
                if (frame != null) frame.dispose();
                importInProgress.set(false);
            }
        }, "GameConsole-SettingsImport");
        picker.setDaemon(true);
        picker.start();
    }

    @Override
    public boolean mouseClicked(double mx, double my, int btn) {
        int cx = width / 2;

        for (GameMenuLayout.Button button : controls()) {
            if (!button.contains(mx, my)) continue;
            if (button.index() == CATEGORIES.length) {
                Minecraft.getInstance().setScreen(new MultiplayerLobbyScreen());
            } else {
                selectedCategoryIndex = button.index();
                filterCategory = CATEGORIES[button.index()];
                currentPage = 0;
            }
            return true;
        }

        // ─── 游戏卡片点击：按本次点击坐标判断，不能使用上一帧的悬停结果 ───
        List<GameCatalog.Entry> filtered = getFilteredGames();
        int cardW = Math.min(280, width - 40);
        int cardX = cx - cardW / 2;
        if (mx >= cardX && mx <= cardX + cardW) {
            int startIdx = currentPage * gamesPerPage;
            int endIdx = Math.min(startIdx + gamesPerPage, filtered.size());
            for (int i = startIdx; i < endIdx; i++) {
                int cardY = listStartY() + (i - startIdx) * 28;
                if (my >= cardY && my <= cardY + 26) {
                    Minecraft.getInstance().setScreen(GameScreens.create(filtered.get(i).id()));
                    return true;
                }
            }
        }

        // ─── 翻页按钮 ───
        int navY = this.height - 24;
        int totalPages = Math.max(1, (int) Math.ceil((double) filtered.size() / gamesPerPage));
        if (mx >= cx - 90 && mx <= cx - 30 && my >= navY && my <= navY + 18 && currentPage > 0) {
            currentPage--;
            return true;
        }
        if (mx >= cx + 30 && mx <= cx + 90 && my >= navY && my <= navY + 18 && currentPage < totalPages - 1) {
            currentPage++;
            return true;
        }

        // ─── 导入设置按钮 ───
        if (mx >= 5 && mx <= 65 && my >= height - 46 && my <= height - 28) {
            importSettingsFromFile();
            return true;
        }

        return super.mouseClicked(mx, my, btn);
    }

    @Override
    public boolean keyPressed(int key, int scan, int mods) {
        List<GameCatalog.Entry> filtered = getFilteredGames();
        int totalPages = Math.max(1, (int) Math.ceil((double) filtered.size() / gamesPerPage));
        if (key == GLFW.GLFW_KEY_LEFT && currentPage > 0) { currentPage--; return true; }
        if (key == GLFW.GLFW_KEY_RIGHT && currentPage < totalPages - 1) { currentPage++; return true; }
        return super.keyPressed(key, scan, mods);
    }

    @Override
    public boolean mouseScrolled(double mx, double my, double scrollDeltaX, double delta) {
        List<GameCatalog.Entry> filtered = getFilteredGames();
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
