package com.wzz.game_console.client.screens.games;

import com.wzz.game_console.client.screens.GameSelectorScreen;
import com.wzz.game_console.util.GameRenderHelper;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.minecraft.sounds.SoundEvents;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.api.distmarker.OnlyIn;
import org.lwjgl.glfw.GLFW;

import java.util.*;

@OnlyIn(Dist.CLIENT)
public class FruitNinjaScreen extends Screen {
    boolean showExitConfirm = false;
    private static final int FRUIT_SIZE = 24;
    private static final int[] FRUIT_COLORS = {0xFFFF2222, 0xFFFF8800, 0xFFFFFF00, 0xFF22FF22, 0xFF8800FF, 0xFFFF44AA};
    private static final String[] FRUIT_ICONS = {"🍎", "🍊", "🍋", "🍐", "🍇", "🍓"};

    private enum State { MENU, PLAYING, GAME_OVER }
    private State state = State.MENU;
    private final List<float[]> fruits = new ArrayList<>(); // {x, y, vx, vy, type, sliced}
    private final List<float[]> sliceEffects = new ArrayList<>(); // {x, y, life}
    private final List<int[]> mouseTrail = new ArrayList<>();
    private int score, lives, spawnTimer, comboCount, comboTimer;
    private long tickCount;
    private int lastMX = -1, lastMY = -1;
    private final Random random = new Random();
    private final List<GameRenderHelper.Particle> particles = new ArrayList<>();
    private final List<GameRenderHelper.FloatingText> floats = new ArrayList<>();

    public FruitNinjaScreen() { super(Component.literal("水果忍者")); }

    private void startGame() {
        fruits.clear(); sliceEffects.clear(); mouseTrail.clear(); particles.clear(); floats.clear();
        score = 0; lives = 3; spawnTimer = 0; comboCount = 0; comboTimer = 0;
        lastMX = -1; lastMY = -1;
        state = State.PLAYING;
    }

    @Override public void tick() {
        tickCount++;
        floats.removeIf(f -> { f.update(); return !f.isAlive(); });
        if (state != State.PLAYING || showExitConfirm) return; // 弹窗期间暂停游戏

        spawnTimer++;
        if (spawnTimer >= Math.max(8, 25 - score / 5)) {
            spawnTimer = 0;
            float x = random.nextFloat() * (width - 40) + 20;
            float vy = -(8 + random.nextFloat() * 4);
            float vx = (random.nextFloat() - 0.5f) * 4;
            // 15%概率生成炸弹（type=-1）
            int type = random.nextFloat() < 0.15f ? -1 : random.nextInt(FRUIT_COLORS.length);
            fruits.add(new float[]{x, height + 10, vx, vy, type, 0});
        }

        // 更新水果
        Iterator<float[]> it = fruits.iterator();
        while (it.hasNext()) {
            float[] f = it.next();
            f[0] += f[2]; f[1] += f[3]; f[3] += 0.3f; // gravity
            if (f[1] > height + 50) {
                // 只有未被切开的普通水果掉出屏幕才扣命（f[4]==-1为炸弹，f[5]==0为未切开）
                if (f[5] == 0 && f[4] != -1) { lives--; if (lives <= 0) state = State.GAME_OVER; }
                it.remove();
            }
        }

        comboTimer--;
        if (comboTimer <= 0) comboCount = 0;
        mouseTrail.removeIf(t -> tickCount - t[2] > 8);
    }

    @Override public boolean keyPressed(int key, int scan, int mods) {
        if (key == GLFW.GLFW_KEY_ESCAPE) {
            if (showExitConfirm) { showExitConfirm = false; return true; }
            if (state != State.MENU) { showExitConfirm = true; return true; }
            Minecraft.getInstance().setScreen(new GameSelectorScreen()); return true;
        }
        if (showExitConfirm) return true;
        if (key == GLFW.GLFW_KEY_R) { startGame(); return true; }
        return true;
    }

    @Override public boolean mouseDragged(double mx, double my, int btn, double dx, double dy) {
        if (state == State.PLAYING && !showExitConfirm) {
            mouseTrail.add(new int[]{(int)mx, (int)my, (int)tickCount});
            // 切水果检测
            for (float[] f : fruits) {
                if (f[5] == 0 && Math.abs(mx - f[0]) < FRUIT_SIZE && Math.abs(my - f[1]) < FRUIT_SIZE) {
                    f[5] = 1; // sliced
                    if ((int)f[4] == -1) {
                        // 切到炸弹：扣一条命，不加分，连击清零
                        lives--; comboCount = 0;
                        GameRenderHelper.spawnParticles(particles, f[0], f[1], 20, 0xFFFF2200);
                        floats.add(new GameRenderHelper.FloatingText("💥 -1", f[0], f[1] - 10, 0xFFFF4400, 40));
                        if (lives <= 0) state = State.GAME_OVER;
                    } else {
                        int pts = 1;
                        comboCount++; comboTimer = 20;
                        if (comboCount >= 3) pts = comboCount;
                        score += pts;
                        GameRenderHelper.spawnParticles(particles, f[0], f[1], 8, FRUIT_COLORS[(int)f[4]]);
                        floats.add(new GameRenderHelper.FloatingText("+" + pts, f[0], f[1] - 10, FRUIT_COLORS[(int)f[4]], 25));
                    }
                    if (Minecraft.getInstance().player != null) Minecraft.getInstance().player.playSound(SoundEvents.PLAYER_ATTACK_SWEEP, 0.5F, (int)f[4]==-1?0.5F:1.2F);
                }
            }
        }
        return true;
    }

    @Override public void render(GuiGraphics g, int mx, int my, float pt) {
        GameRenderHelper.fillDarkBackground(g, width, height);
        switch (state) {
            case MENU -> renderMenu(g, mx, my);
            case PLAYING -> renderPlaying(g, mx, my);
            case GAME_OVER -> renderGameOver(g, mx, my);
        }
        if (showExitConfirm) GameRenderHelper.drawExitConfirmOverlay(g, font, width, height, mx, my);
    }

    private void renderMenu(GuiGraphics g, int mx, int my) {
        int cx = width/2, cy = height/2;
        GameRenderHelper.fillGradientBackground(g, width, height, 0xFF1A0A0A, 0xFF2A1A0A);
        GameRenderHelper.renderDecorativeLines(g, width, height, tickCount, 0x331100);
        GameRenderHelper.drawShadowedCenteredText(g, font, "水果忍者", cx, cy - 60, 0xFF4444, 2);
        g.drawCenteredString(font, "Fruit Ninja", cx, cy - 42, 0x553322);
        GameRenderHelper.drawDivider(g, cx - 80, cy - 32, 160, 0xFFFF4444, 0xFF882222);
        g.drawCenteredString(font, "按住鼠标左键滑动切水果", cx, cy - 10, 0xAAAAAA);
        g.drawCenteredString(font, "不要让水果掉落！连切得更多分！切到💣炸弹会扣命！", cx, cy + 5, 0xCCCCCC);
        // 水果预览动画
        for (int i = 0; i < 6; i++) {
            int bob = (int)(Math.sin(tickCount * 0.1 + i * 0.8) * 4);
            g.drawCenteredString(font, FRUIT_ICONS[i], cx - 40 + i * 16, cy + 25 + bob, 0xFFFFFF);
        }
        GameRenderHelper.drawPrimaryButton(g, font, "开始游戏", cx - 60, cy + 45, 120, 22, mx, my);
    }

    private void renderPlaying(GuiGraphics g, int mx, int my) {
        GameRenderHelper.fillGradientBackground(g, width, height, 0xFF0A0A18, 0xFF1A1A2A);
        // 鼠标轨迹
        for (int i = 1; i < mouseTrail.size(); i++) {
            int[] p1 = mouseTrail.get(i-1), p2 = mouseTrail.get(i);
            float age = (tickCount - p2[2]) / 8f;
            int alpha = (int)((1 - age) * 180);
            if (alpha > 0) {
                g.fill(Math.min(p1[0], p2[0]), Math.min(p1[1], p2[1]),
                       Math.max(p1[0], p2[0]) + 2, Math.max(p1[1], p2[1]) + 2, (alpha << 24) | 0xFFFFFF);
            }
        }
        // 水果和炸弹
        for (float[] f : fruits) {
            if (f[5] == 0) {
                if ((int)f[4] == -1) {
                    // 炸弹：黑色圆 + 红色引线
                    GameRenderHelper.drawCircle(g, (int)f[0], (int)f[1], FRUIT_SIZE/2, 0xFF111111);
                    GameRenderHelper.drawCircle(g, (int)f[0], (int)f[1], FRUIT_SIZE/2 - 2, 0xFF2A2A2A);
                    // 引线
                    g.fill((int)f[0], (int)f[1] - FRUIT_SIZE/2 - 4, (int)f[0]+2, (int)f[1] - FRUIT_SIZE/2, 0xFFFF4400);
                    // 警告圈（红色闪烁轮廓）
                    int bombPulse = (int)(System.currentTimeMillis() / 300) % 2 == 0 ? 0xFFFF2200 : 0xFF880000;
                    GameRenderHelper.drawCircle(g, (int)f[0], (int)f[1], FRUIT_SIZE/2 + 2, bombPulse);
                    g.drawCenteredString(font, "💣", (int)f[0], (int)f[1] - 4, 0xFFFFFFFF);
                } else {
                    int color = FRUIT_COLORS[(int)f[4]];
                    GameRenderHelper.drawCircle(g, (int)f[0], (int)f[1], FRUIT_SIZE/2, color);
                    GameRenderHelper.drawCircle(g, (int)f[0] - 3, (int)f[1] - 3, FRUIT_SIZE/6, GameRenderHelper.brighten(color, 1.4f));
                    g.drawCenteredString(font, FRUIT_ICONS[(int)f[4]], (int)f[0], (int)f[1] - 5, 0xFFFFFFFF);
                }
            }
        }
        GameRenderHelper.tickAndRenderParticles(g, particles);
        for (GameRenderHelper.FloatingText ft : floats) ft.render(g, font);

        // HUD
        GameRenderHelper.drawTopHUD(g, width, height);
        g.drawString(font, "🍉 分数: " + score, 8, 7, 0xFF4444);
        String livesStr = "❤".repeat(Math.max(0, lives));
        g.drawString(font, livesStr, width - font.width(livesStr) - 8, 7, 0xFF4444);
        if (comboCount >= 3) g.drawCenteredString(font, "✦ Combo x" + comboCount + " ✦", width/2, 7, 0xFFAA00);
        GameRenderHelper.drawBottomBar(g, font, width, height, "按住鼠标滑动切水果  ESC 菜单  R 重开");
    }

    private void renderGameOver(GuiGraphics g, int mx, int my) {
        renderPlaying(g, mx, my);
        GameRenderHelper.drawGameOverOverlay(g, width, height);
        int cx = width/2, cy = height/2;
        GameRenderHelper.drawGameOverPanel(g, font, cx, cy, false, "游戏结束！", "最终分数: " + score);
        GameRenderHelper.drawPrimaryButton(g, font, "R - 重来", cx - 60, cy + 20, 120, 18, mx, my);
        GameRenderHelper.drawSecondaryButton(g, font, "ESC - 返回", cx - 60, cy + 42, 120, 18, mx, my);
    }

    @Override public boolean mouseClicked(double mx, double my, int btn) {
        if (showExitConfirm) { int click = GameRenderHelper.getExitConfirmClick(mx, my, width, height); if (click == 1) { showExitConfirm = false; Minecraft.getInstance().setScreen(new GameSelectorScreen()); return true; } if (click == 2) { showExitConfirm = false; return true; } return true; }
        int cx = width/2, cy = height/2;
        if (state == State.MENU && mx >= cx-60 && mx <= cx+60 && my >= cy+45 && my <= cy+67) { startGame(); return true; }
        if (state == State.GAME_OVER) {
            if (mx >= cx-60 && mx <= cx+60 && my >= cy+20 && my <= cy+38) { startGame(); return true; }
            if (mx >= cx-60 && mx <= cx+60 && my >= cy+42 && my <= cy+60) { Minecraft.getInstance().setScreen(new GameSelectorScreen()); return true; }
        }
        return super.mouseClicked(mx, my, btn);
    }

    @Override public boolean isPauseScreen() { return false; }
}
