package com.wzz.game_console.util;

import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.api.distmarker.OnlyIn;

import java.util.List;
import java.util.Random;

/**
 * 游戏渲染工具类 - 提供所有游戏共用的美化渲染方法
 */
@OnlyIn(Dist.CLIENT)
public class GameRenderHelper {

    // ═══════════════ 颜色常量 ═══════════════
    public static final int BG_DARK       = 0xFF0D0D1A;
    public static final int BG_PANEL      = 0xFF1A1A2E;
    public static final int BG_PANEL_LIGHT= 0xFF252545;
    public static final int TEXT_WHITE     = 0xFFFFFF;
    public static final int TEXT_GRAY     = 0xAAAAAA;
    public static final int TEXT_GOLD     = 0xFFFF44;
    public static final int TEXT_GREEN    = 0x44FF44;
    public static final int TEXT_RED      = 0xFF4444;
    public static final int TEXT_CYAN     = 0x44CCFF;
    public static final int ACCENT_BLUE   = 0xFF2244AA;
    public static final int ACCENT_GREEN  = 0xFF44AA44;
    public static final int ACCENT_RED    = 0xFFAA2200;
    public static final int BTN_NORMAL    = 0xFF2A3D14;
    public static final int BTN_HOVER     = 0xFF446622;
    public static final int BTN_BORDER    = 0xFF88CC44;

    // ═══════════════ 背景渲染 ═══════════════

    /** 深色背景填充 */
    public static void fillDarkBackground(GuiGraphics g, int w, int h) {
        g.fill(0, 0, w, h, BG_DARK);
    }

    /** 渐变背景 */
    public static void fillGradientBackground(GuiGraphics g, int w, int h, int topColor, int bottomColor) {
        int steps = 16;
        for (int i = 0; i < steps; i++) {
            float t = (float) i / steps;
            int color = lerpColor(topColor, bottomColor, t);
            int y1 = h * i / steps;
            int y2 = h * (i + 1) / steps;
            g.fill(0, y1, w, y2, color);
        }
    }

    /** 装饰性背景竖线动画 */
    public static void renderDecorativeLines(GuiGraphics g, int w, int h, long tick, int lineColor) {
        for (int i = 0; i < 20; i++) {
            int x = i * (w / 20);
            float alpha = 0.15f + 0.1f * (float) Math.sin(tick * 0.05 + i);
            g.fill(x, 0, x + 1, h, ((int)(alpha * 255) << 24) | (lineColor & 0xFFFFFF));
        }
    }

    // ═══════════════ 面板 & 卡片 ═══════════════

    /** 绘制圆角面板（用矩形近似） */
    public static void drawPanel(GuiGraphics g, int x, int y, int w, int h, int bgColor, int borderColor) {
        g.fill(x - 1, y - 1, x + w + 1, y + h + 1, borderColor);
        g.fill(x, y, x + w, y + h, bgColor);
        g.fill(x, y, x + w, y + 1, brighten(borderColor, 1.3f));
        g.fill(x, y + h - 1, x + w, y + h, darken(bgColor, 0.7f));
    }

    /** 绘制游戏区域边框 */
    public static void drawGameBorder(GuiGraphics g, int x, int y, int w, int h, int borderColor) {
        g.fill(x - 3, y - 3, x + w + 3, y + h + 3, darken(borderColor, 0.6f));
        g.fill(x - 2, y - 2, x + w + 2, y + h + 2, borderColor);
        g.fill(x - 1, y - 1, x + w + 1, y + h + 1, darken(borderColor, 0.8f));
    }

    // ═══════════════ 按钮 ═══════════════

    /** 绘制自定义按钮并返回是否悬停 */
    public static boolean drawButton(GuiGraphics g, Font font, String text, int x, int y, int w, int h,
                                      int mx, int my, int normalColor, int hoverColor, int borderColor) {
        boolean hover = mx >= x && mx <= x + w && my >= y && my <= y + h;
        int bg = hover ? hoverColor : normalColor;
        g.fill(x, y, x + w, y + h, bg);
        g.fill(x, y, x + w, y + 1, borderColor);
        g.fill(x, y + h - 1, x + w, y + h, darken(bg, 0.5f));
        int textColor = hover ? 0xAAFF66 : brightenInt(borderColor, 1.2f);
        String displayText = hover ? "► " + text + " ◄" : text;
        g.drawCenteredString(font, displayText, x + w / 2, y + (h - 8) / 2, textColor);
        return hover;
    }

    /** 绿色主按钮 */
    public static boolean drawPrimaryButton(GuiGraphics g, Font font, String text,
                                             int x, int y, int w, int h, int mx, int my) {
        return drawButton(g, font, text, x, y, w, h, mx, my, BTN_NORMAL, BTN_HOVER, BTN_BORDER);
    }

    /** 灰色次要按钮 */
    public static boolean drawSecondaryButton(GuiGraphics g, Font font, String text,
                                               int x, int y, int w, int h, int mx, int my) {
        return drawButton(g, font, text, x, y, w, h, mx, my, 0xFF222233, 0xFF333355, 0xFF666688);
    }

    /** 蓝色按钮 */
    public static boolean drawBlueButton(GuiGraphics g, Font font, String text,
                                          int x, int y, int w, int h, int mx, int my) {
        return drawButton(g, font, text, x, y, w, h, mx, my, 0xFF142A4A, 0xFF1E3A6A, 0xFF4488CC);
    }

    /** 红色按钮 */
    public static boolean drawRedButton(GuiGraphics g, Font font, String text,
                                         int x, int y, int w, int h, int mx, int my) {
        return drawButton(g, font, text, x, y, w, h, mx, my, 0xFF4A1414, 0xFF6A1E1E, 0xFFCC4444);
    }

    // ═══════════════ 文字 ═══════════════

    /** 带阴影的居中文字 */
    public static void drawShadowedCenteredText(GuiGraphics g, Font font, String text,
                                                 int x, int y, int color, int scale) {
        g.pose().pushPose();
        g.pose().translate(x, y, 0);
        g.pose().scale(scale, scale, 1);
        int sw = font.width(text);
        g.drawString(font, text, -sw / 2 + 1, 1, 0x44000000);
        g.drawString(font, text, -sw / 2, 0, color);
        g.pose().popPose();
    }

    /** 带描边的居中文字 */
    public static void drawOutlinedCenteredText(GuiGraphics g, Font font, String text,
                                                 int x, int y, int color, int outlineColor) {
        for (int dx = -1; dx <= 1; dx++)
            for (int dy = -1; dy <= 1; dy++)
                if (dx != 0 || dy != 0)
                    g.drawCenteredString(font, text, x + dx, y + dy, outlineColor);
        g.drawCenteredString(font, text, x, y, color);
    }

    // ═══════════════ HUD ═══════════════

    /** 顶部HUD栏 */
    public static void drawTopHUD(GuiGraphics g, int w, int h) {
        g.fill(0, 0, w, 22, 0xCC000000);
        g.fill(0, 22, w, 23, 0x44FFFFFF);
    }

    /** 底部提示栏 */
    public static void drawBottomBar(GuiGraphics g, Font font, int w, int h, String text) {
        g.fill(0, h - 16, w, h, 0xCC000000);
        g.fill(0, h - 16, w, h - 15, 0x22FFFFFF);
        g.drawCenteredString(font, text, w / 2, h - 12, 0x666666);
    }

    // ═══════════════ 游戏结束遮罩 ═══════════════

    /** 半透明游戏结束遮罩 */
    public static void drawGameOverOverlay(GuiGraphics g, int w, int h) {
        // 先flush将之前绘制的游戏内容落盘：GuiGraphics批量渲染时text批次整体晚于gui批次，
        // 不flush会导致先绘制的游戏文字盖住遮罩背景
        g.flush();
        g.fill(0, 0, w, h, 0xAA000000);
    }

    /** 完整的游戏结束面板 */
    public static void drawGameOverPanel(GuiGraphics g, Font font, int cx, int cy,
                                          boolean win, String title, String subtitle) {
        int pw = 280, ph = 140;
        drawPanel(g, cx - pw/2, cy - ph/2, pw, ph, BG_PANEL,
                win ? 0xFF44FF44 : 0xFFFF4444);

        drawShadowedCenteredText(g, font, title, cx, cy - 40, win ? TEXT_GREEN : TEXT_RED, 1);
        if (subtitle != null && !subtitle.isEmpty()) {
            g.drawCenteredString(font, subtitle, cx, cy - 20, win ? 0xCCFFCC : 0xFFAAAA);
        }
    }

    // ═══════════════ 动态尺寸计算 ═══════════════

    /** 计算游戏区域缩放比例 */
    public static int calcScale(int screenW, int screenH, int gameW, int gameH) {
        return Math.max(1, Math.min(screenW / gameW, screenH / gameH));
    }

    /** 计算居中偏移 X */
    public static int calcOffsetX(int screenW, int gameW, int scale) {
        return (screenW - gameW * scale) / 2;
    }

    /** 计算居中偏移 Y */
    public static int calcOffsetY(int screenH, int gameH, int scale) {
        return (screenH - gameH * scale) / 2;
    }

    /** 根据屏幕大小计算自适应格子大小 */
    public static int calcCellSize(int screenW, int screenH, int gridW, int gridH, int margin) {
        int availW = screenW - margin * 2;
        int availH = screenH - margin * 2;
        return Math.max(8, Math.min(availW / gridW, availH / gridH));
    }

    // ═══════════════ 绘制形状 ═══════════════

    /** 绘制填充圆 */
    /**
     * 绘制实心圆 —— 扫描线算法，每行一次 fill，性能约为逐像素版的 1/r 倍。
     * 修复：WesternChessScreen 国际象棋卡顿问题（原版每个棋子产生 ~600 次 fill 调用）
     */
    public static void drawCircle(GuiGraphics g, int cx, int cy, int radius, int color) {
        int r2 = radius * radius;
        for (int dy = -radius; dy <= radius; dy++) {
            int half = (int) Math.sqrt(r2 - dy * (long) dy);
            g.fill(cx - half, cy + dy, cx + half + 1, cy + dy + 1, color);
        }
    }

    /** 绘制圆形边框（扫描线算法） */
    public static void drawCircleOutline(GuiGraphics g, int cx, int cy, int radius, int color) {
        int r2 = radius * radius;
        int ri2 = (radius - 1) * (radius - 1);
        for (int dy = -radius; dy <= radius; dy++) {
            int half  = (int) Math.sqrt(r2  - dy * (long) dy);
            int halfI = (int) Math.sqrt(Math.max(0, ri2 - dy * (long) dy));
            if (half > halfI) {
                g.fill(cx - half,  cy + dy, cx - halfI,     cy + dy + 1, color);
                g.fill(cx + halfI, cy + dy, cx + half + 1,  cy + dy + 1, color);
            } else {
                g.fill(cx - half, cy + dy, cx + half + 1, cy + dy + 1, color);
            }
        }
    }

    /** 绘制分割线 */
    public static void drawDivider(GuiGraphics g, int x, int y, int w, int color1, int color2) {
        g.fill(x, y, x + w, y + 1, color1);
        if (color2 != 0) g.fill(x, y + 1, x + w, y + 2, color2);
    }

    /** 绘制棋盘格背景 */
    public static void drawCheckerboard(GuiGraphics g, int ox, int oy, int cols, int rows, int cellSize, int color1, int color2) {
        for (int x = 0; x < cols; x++)
            for (int y = 0; y < rows; y++)
                g.fill(ox + x * cellSize, oy + y * cellSize,
                        ox + (x + 1) * cellSize, oy + (y + 1) * cellSize,
                        (x + y) % 2 == 0 ? color1 : color2);
    }

    /** 绘制方块带3D效果 */
    public static void drawBlock3D(GuiGraphics g, int x, int y, int s, int color) {
        g.fill(x, y, x + s, y + s, color);
        g.fill(x, y, x + s, y + 1, brighten(color, 1.3f));
        g.fill(x, y, x + 1, y + s, brighten(color, 1.15f));
        g.fill(x, y + s - 1, x + s, y + s, darken(color, 0.6f));
        g.fill(x + s - 1, y, x + s, y + s, darken(color, 0.7f));
    }

    /** 绘制网格线 */
    public static void drawGrid(GuiGraphics g, int ox, int oy, int cols, int rows, int cellSize, int color) {
        for (int x = 0; x <= cols; x++)
            g.fill(ox + x * cellSize, oy, ox + x * cellSize + 1, oy + rows * cellSize, color);
        for (int y = 0; y <= rows; y++)
            g.fill(ox, oy + y * cellSize, ox + cols * cellSize, oy + y * cellSize + 1, color);
    }

    /** 绘制标准菜单布局 */
    public static void drawMenuLayout(GuiGraphics g, Font font, int w, int h, long tick,
                                       String title, String subtitle, int titleColor, int lineColor) {
        int cx = w / 2, cy = h / 2;
        fillGradientBackground(g, w, h, 0xFF0A0A18, 0xFF151530);
        renderDecorativeLines(g, w, h, tick, lineColor);
        drawShadowedCenteredText(g, font, title, cx, cy - 60, titleColor, 2);
        g.drawCenteredString(font, subtitle, cx, cy - 42, darken(titleColor, 0.5f) & 0xFFFFFF);
        drawDivider(g, cx - 80, cy - 32, 160, titleColor | 0xFF000000, darken(titleColor, 0.5f));
    }

    // ═══════════════ 颜色工具 ═══════════════

    /** 两个颜色之间线性插值 */
    public static int lerpColor(int c1, int c2, float t) {
        int a1 = (c1 >> 24) & 0xFF, r1 = (c1 >> 16) & 0xFF, g1 = (c1 >> 8) & 0xFF, b1 = c1 & 0xFF;
        int a2 = (c2 >> 24) & 0xFF, r2 = (c2 >> 16) & 0xFF, g2 = (c2 >> 8) & 0xFF, b2 = c2 & 0xFF;
        int a = (int)(a1 + (a2 - a1) * t);
        int r = (int)(r1 + (r2 - r1) * t);
        int g = (int)(g1 + (g2 - g1) * t);
        int b = (int)(b1 + (b2 - b1) * t);
        return (a << 24) | (r << 16) | (g << 8) | b;
    }

    /** 加亮颜色 */
    public static int brighten(int color, float factor) {
        int a = (color >> 24) & 0xFF;
        int r = Math.min(255, (int)(((color >> 16) & 0xFF) * factor));
        int g = Math.min(255, (int)(((color >> 8) & 0xFF) * factor));
        int b = Math.min(255, (int)((color & 0xFF) * factor));
        return (a << 24) | (r << 16) | (g << 8) | b;
    }

    public static int brightenInt(int color, float factor) {
        return brighten(color, factor) & 0xFFFFFF;
    }

    /** 变暗颜色 */
    public static int darken(int color, float factor) {
        int a = (color >> 24) & 0xFF;
        int r = (int)(((color >> 16) & 0xFF) * factor);
        int g = (int)(((color >> 8) & 0xFF) * factor);
        int b = (int)((color & 0xFF) * factor);
        return (a << 24) | (r << 16) | (g << 8) | b;
    }

    /** 设置Alpha */
    public static int withAlpha(int color, int alpha) {
        return (alpha << 24) | (color & 0xFFFFFF);
    }

    // ═══════════════ 粒子系统 ═══════════════

    public static class Particle {
        public float x, y, vx, vy;
        public int color, life, maxLife;
        public boolean alive = true;

        public Particle(float x, float y, float vx, float vy, int color, int life) {
            this.x = x; this.y = y; this.vx = vx; this.vy = vy;
            this.color = color; this.life = life; this.maxLife = life;
        }

        public void update() {
            x += vx; y += vy; vy += 0.15f; life--;
            if (life <= 0) alive = false;
        }

        public void render(GuiGraphics g) {
            int a = (int)((float) life / maxLife * 220);
            g.fill((int) x, (int) y, (int) x + 2, (int) y + 2, (a << 24) | (color & 0xFFFFFF));
        }
    }

    /** 在指定位置生成爆炸粒子 */
    public static void spawnParticles(List<Particle> particles, float x, float y, int count, int color) {
        Random r = new Random();
        for (int i = 0; i < count; i++) {
            particles.add(new Particle(x, y,
                    (r.nextFloat() - 0.5f) * 4, -r.nextFloat() * 3,
                    color, 20 + r.nextInt(15)));
        }
    }

    /** 更新和渲染粒子 */
    public static void tickAndRenderParticles(GuiGraphics g, List<Particle> particles) {
        particles.removeIf(p -> !p.alive);
        for (Particle p : particles) {
            p.update();
            p.render(g);
        }
    }

    // ═══════════════ 浮动文字 ═══════════════

    public static class FloatingText {
        public String text;
        public float x, y, vy;
        public int color, life, maxLife;

        public FloatingText(String text, float x, float y, int color, int life) {
            this.text = text; this.x = x; this.y = y;
            this.vy = -1.5f; this.color = color;
            this.life = life; this.maxLife = life;
        }

        public void update() {
            y += vy; vy *= 0.95f; life--;
        }

        public boolean isAlive() { return life > 0; }

        public void render(GuiGraphics g, Font font) {
            int a = (int)(255f * life / maxLife);
            int c = (a << 24) | (color & 0xFFFFFF);
            g.drawCenteredString(font, text, (int) x, (int) y, c);
        }
    }

    // ═══════════════ ESC退出确认弹窗 ═══════════════

    /** 绘制ESC退出确认弹窗覆盖层 */
    public static void drawExitConfirmOverlay(GuiGraphics g, Font font, int w, int h, int mx, int my) {
        // 先flush将之前绘制的游戏内容落盘：GuiGraphics批量渲染时text批次整体晚于gui批次，
        // 不flush会导致先绘制的游戏文字盖住弹窗背景
        g.flush();
        g.fill(0, 0, w, h, 0xAA000000);
        int cx = w / 2, cy = h / 2;
        int ww = 240, wh = 90;
        int wx = cx - ww / 2, wy = cy - wh / 2;
        g.fill(wx, wy, wx + ww, wy + wh, 0xFF1A1A2E);
        g.fill(wx, wy, wx + ww, wy + 1, 0xFFFFAA00);
        g.fill(wx, wy + wh - 1, wx + ww, wy + wh, 0xFFFFAA00);
        g.fill(wx, wy, wx + 1, wy + wh, 0xFFFFAA00);
        g.fill(wx + ww - 1, wy, wx + ww, wy + wh, 0xFFFFAA00);
        g.drawCenteredString(font, "确定要退出当前游戏吗？", cx, wy + 16, 0xFFFFDD44);
        boolean h1 = mx >= cx - 105 && mx <= cx - 9 && my >= cy + 10 && my <= cy + 34;
        g.fill(cx - 105, cy + 10, cx - 9, cy + 34, h1 ? 0xFF553322 : 0xFF331A10);
        g.fill(cx - 105, cy + 10, cx - 9, cy + 11, 0xFFFF4444);
        g.drawCenteredString(font, "确认退出", cx - 57, cy + 18, h1 ? 0xFFFF6644 : 0xFFCC4444);
        boolean h2 = mx >= cx + 9 && mx <= cx + 105 && my >= cy + 10 && my <= cy + 34;
        g.fill(cx + 9, cy + 10, cx + 105, cy + 34, h2 ? 0xFF224422 : 0xFF112211);
        g.fill(cx + 9, cy + 10, cx + 105, cy + 11, 0xFF44CC44);
        g.drawCenteredString(font, "继续游戏", cx + 57, cy + 18, h2 ? 0xFF66FF66 : 0xFF44AA44);
        g.drawCenteredString(font, "再按 ESC 取消", cx, wy + wh - 14, 0xFF666666);
    }

    /**
     * 检测退出确认弹窗的点击。返回: 0=无点击, 1=确认退出, 2=继续游戏
     */
    public static int getExitConfirmClick(double mx, double my, int w, int h) {
        int cx = w / 2, cy = h / 2;
        if (mx >= cx - 105 && mx <= cx - 9 && my >= cy + 10 && my <= cy + 34) return 1;
        if (mx >= cx + 9 && mx <= cx + 105 && my >= cy + 10 && my <= cy + 34) return 2;
        return 0;
    }
}
