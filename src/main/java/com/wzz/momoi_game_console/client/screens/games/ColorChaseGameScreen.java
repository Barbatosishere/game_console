package com.wzz.momoi_game_console.client.screens.games;

import com.wzz.momoi_game_console.client.screens.GameSelectorScreen;
import com.wzz.momoi_game_console.util.GameRenderHelper;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import org.lwjgl.glfw.GLFW;

import java.util.HashSet;
import java.util.Random;
import java.util.Set;

public class ColorChaseGameScreen extends Screen implements LanMultiplayerScreen {
    boolean showExitConfirm = false;

    // ─────── 尺寸常量 ───────
    private static final int GRID_SIZE   = 16;
    private static final int CELL_SIZE   = 20;
    private static final int GAME_WIDTH  = GRID_SIZE * CELL_SIZE;
    private static final int GAME_HEIGHT = GRID_SIZE * CELL_SIZE;

    // ─────── 颜色表 ───────
    private static final int[] GAME_COLORS = {
            0xFF4CAF50, 0xFF2196F3, 0xFFFF9800, 0xFF9C27B0,
            0xFFE91E63, 0xFF00BCD4, 0xFFFFEB3B, 0xFFFF5722
    };
    private static final String[] COLOR_NAMES = {
            "绿","蓝","橙","紫","粉","青","黄","红"
    };

    // ─────── 游戏模式 ───────
    private enum GameMode { MENU, SINGLE, TWO_PLAYER }
    private GameMode gameMode = GameMode.MENU;

    // ─────── 地图 ───────
    private final int[][] grid = new int[GRID_SIZE][GRID_SIZE];

    // ─────── 玩家1（WASD） ───────
    private int     p1X = GRID_SIZE / 4,       p1Y = GRID_SIZE / 2;
    private boolean p1Dead  = false;
    private int     p1Score = 0;
    private long    p1LastSafe;

    // ─────── 玩家2（方向键，仅双人） ───────
    private int     p2X = GRID_SIZE * 3 / 4,   p2Y = GRID_SIZE / 2;
    private boolean p2Dead  = false;
    private int     p2Score = 0;
    private long    p2LastSafe;

    // ─────── 共用状态 ───────
    private int     targetColor = 0;
    private int     level       = 1;
    private boolean gameRunning = true;
    private boolean gameOver    = false;
    private String  winnerText  = "";

    // ─────── 时间 ───────
    private long lastColorChangeTime;
    private long colorChangeInterval    = 3000;
    private static final long INPUT_COOLDOWN        = 80;
    private static final long DEATH_GRACE_PERIOD    = 2000;
    private static final long GAME_START_PROTECTION = 1200;
    private long p1LastInput = 0, p2LastInput = 0;
    private long gameStartTime;

    // ─────── 长按 ───────
    private final Set<Integer> heldKeys = new HashSet<>();

    private final Random random = new Random();
    private int gameStartX, gameStartY;

    // ── LAN 联机 ──────────────────────────────────────────────────
    private int lanMode = LAN_NONE;
    private java.util.UUID remotePeer = null;
    // HOST 控制 P1（WASD），CLIENT 控制 P2（方向键）
    // HOST 每 tick 发送完整状态给 CLIENT

    /** 本地模式（单机/本地双人）构造器 */
    public ColorChaseGameScreen() {
        super(net.minecraft.network.chat.Component.literal("颜色追逐"));
    }

    /** LAN 联机构造：HOST 控制P1，CLIENT 控制P2 */
    public ColorChaseGameScreen(boolean isHost, java.util.UUID remote) {
        super(net.minecraft.network.chat.Component.literal("颜色追逐-联机"));
        this.lanMode    = isHost ? LAN_HOST : LAN_CLIENT;
        this.remotePeer = remote;
        initGame(true); // 双人模式启动
    }

    // ── LanMultiplayerScreen 接口实现 ──────────────────────────────
    @Override public java.util.UUID getLanPeer() { return remotePeer; }
    @Override public String getLanGameId()        { return "colorchase"; }

    /**
     * CLIENT 收到 HOST 完整状态：
     * "p1x,p1y,p1d,p2x,p2y,p2d,target,s1,s2,lv,gov;grid..."
     */
    @Override
    public void onRemoteState(String data) {
        try {
            String[] parts = data.split(";", 2);
            String[] f = parts[0].split(",");
            p1X = Integer.parseInt(f[0]); p1Y = Integer.parseInt(f[1]); p1Dead = f[2].equals("1");
            p2X = Integer.parseInt(f[3]); p2Y = Integer.parseInt(f[4]); p2Dead = f[5].equals("1");
            targetColor = Integer.parseInt(f[6]);
            p1Score = Integer.parseInt(f[7]); p2Score = Integer.parseInt(f[8]);
            level = Integer.parseInt(f[9]);
            gameOver = f[10].equals("1");
            gameRunning = !gameOver;
            if (gameOver && f.length > 11) winnerText = f[11];
            // 同步格子色
            if (parts.length > 1 && !parts[1].isEmpty()) {
                String[] cells = parts[1].split(",");
                int idx = 0;
                for (int x = 0; x < GRID_SIZE && idx < cells.length; x++)
                    for (int y = 0; y < GRID_SIZE && idx < cells.length; y++)
                        grid[x][y] = Integer.parseInt(cells[idx++]);
            }
        } catch (Exception ignored) {}
    }

    /** HOST 收到 CLIENT 的输入，CLIENT 收到 HOST 的 RESTART 信号 */
    @Override
    public void onRemoteMove(String data) {
        // CLIENT 侧：HOST 通知重开
        if ("RESTART".equals(data)) {
            initGame(true);
            return;
        }
        if (lanMode != LAN_HOST || p2Dead || !gameRunning) return;
        try {
            String[] p = data.split(",");
            long now = System.currentTimeMillis();
            if (now - p2LastInput < INPUT_COOLDOWN) return;
            boolean moved = false;
            if (p[0].equals("1")) { p2Y = Math.max(0, p2Y - 1); moved = true; }
            else if (p[1].equals("1")) { p2Y = Math.min(GRID_SIZE-1, p2Y + 1); moved = true; }
            if (p[2].equals("1")) { p2X = Math.max(0, p2X - 1); moved = true; }
            else if (p[3].equals("1")) { p2X = Math.min(GRID_SIZE-1, p2X + 1); moved = true; }
            if (moved) {
                p2LastInput = now;
                if (grid[p2X][p2Y] == targetColor) p2LastSafe = now;
            }
        } catch (Exception ignored) {}
    }

    /** 构建完整状态字符串（HOST→CLIENT） */
    private String buildColorChaseState() {
        StringBuilder sb = new StringBuilder();
        sb.append(p1X).append(',').append(p1Y).append(',').append(p1Dead?1:0).append(',')
          .append(p2X).append(',').append(p2Y).append(',').append(p2Dead?1:0).append(',')
          .append(targetColor).append(',')
          .append(p1Score).append(',').append(p2Score).append(',')
          .append(level).append(',')
          .append(gameOver?1:0);
        if (gameOver && !winnerText.isEmpty())
            sb.append(',').append(winnerText.replace(',', '，'));
        sb.append(';');
        for (int x = 0; x < GRID_SIZE; x++)
            for (int y = 0; y < GRID_SIZE; y++) {
                if (x > 0 || y > 0) sb.append(',');
                sb.append(grid[x][y]);
            }
        return sb.toString();
    }

    // ─────── 粒子 ───────
    private static final int MAX_PARTICLES = 80;
    private final float[] pX    = new float[MAX_PARTICLES];
    private final float[] pY    = new float[MAX_PARTICLES];
    private final float[] pVX   = new float[MAX_PARTICLES];
    private final float[] pVY   = new float[MAX_PARTICLES];
    private final int[]   pColor= new int[MAX_PARTICLES];
    private final int[]   pLife = new int[MAX_PARTICLES];

    private long tickCount = 0;


    @Override
    public void init() {
        super.init();
        gameStartX = (this.width  - GAME_WIDTH)  / 2;
        gameStartY = (this.height - GAME_HEIGHT) / 2;
    }

    // ══════════════════════════════════════
    //  初始化
    // ══════════════════════════════════════
    private void initGame(boolean twoPlayer) {
        gameMode    = twoPlayer ? GameMode.TWO_PLAYER : GameMode.SINGLE;
        gameRunning = true;
        gameOver    = false;
        level       = 1;
        p1Score     = 0;   p2Score = 0;
        p1Dead      = false; p2Dead = false;
        colorChangeInterval = 3000;
        winnerText  = "";

        p1X = GRID_SIZE / 4;       p1Y = GRID_SIZE / 2;
        p2X = GRID_SIZE * 3 / 4;   p2Y = GRID_SIZE / 2;

        targetColor = random.nextInt(GAME_COLORS.length);
        randomizeGrid();
        grid[p1X][p1Y] = targetColor;
        if (twoPlayer) grid[p2X][p2Y] = targetColor;
        spawnSafeSpots(8);

        long now = System.currentTimeMillis();
        lastColorChangeTime = now;
        gameStartTime       = now;
        p1LastSafe          = now;
        p2LastSafe          = now;
        heldKeys.clear();
    }

    private void randomizeGrid() {
        for (int x = 0; x < GRID_SIZE; x++)
            for (int y = 0; y < GRID_SIZE; y++)
                grid[x][y] = random.nextInt(GAME_COLORS.length);
    }

    private void spawnSafeSpots(int count) {
        for (int i = 0; i < count; i++)
            grid[random.nextInt(GRID_SIZE)][random.nextInt(GRID_SIZE)] = targetColor;
    }

    // ══════════════════════════════════════
    //  tick（约每50ms一次）
    // ══════════════════════════════════════
    @Override
    public void tick() {
        tickCount++;
        if (lanMode == LAN_CLIENT) {
            // CLIENT：发送P2按键输入，游戏逻辑由HOST驱动
            if (gameMode != GameMode.MENU && gameRunning && !gameOver) {
                int u = heldKeys.contains(GLFW.GLFW_KEY_UP)    ? 1 : 0;
                int d = heldKeys.contains(GLFW.GLFW_KEY_DOWN)  ? 1 : 0;
                int l = heldKeys.contains(GLFW.GLFW_KEY_LEFT)  ? 1 : 0;
                int r = heldKeys.contains(GLFW.GLFW_KEY_RIGHT) ? 1 : 0;
                sendInput(u+","+d+","+l+","+r);
            }
            return;
        }
        if (gameMode != GameMode.MENU && gameRunning && !gameOver) {
            processHeldKeys();
            updateGame();
            if (lanMode == LAN_HOST) sendState(buildColorChaseState()); // 广播状态给CLIENT
        }
    }

    /** 每 tick 根据持续按下的键推送移动，实现丝滑长按 */
    private void processHeldKeys() {
        long now = System.currentTimeMillis();

        // P1 (WASD)
        if (!p1Dead && now - p1LastInput >= INPUT_COOLDOWN) {
            boolean moved = false;
            if (heldKeys.contains(GLFW.GLFW_KEY_W)) {
                p1Y = Math.max(0, p1Y - 1); moved = true;
            } else if (heldKeys.contains(GLFW.GLFW_KEY_S)) {
                p1Y = Math.min(GRID_SIZE - 1, p1Y + 1); moved = true;
            }
            if (heldKeys.contains(GLFW.GLFW_KEY_A)) {
                p1X = Math.max(0, p1X - 1); moved = true;
            } else if (heldKeys.contains(GLFW.GLFW_KEY_D)) {
                p1X = Math.min(GRID_SIZE - 1, p1X + 1); moved = true;
            }
            if (moved) {
                p1LastInput = now;
                if (grid[p1X][p1Y] == targetColor) p1LastSafe = now;
                playMoveSound();
            }
        }

        // P2 (方向键) — 本地双人模式（联机时P2由CLIENT网络控制）
        if (lanMode == LAN_NONE && gameMode == GameMode.TWO_PLAYER && !p2Dead && now - p2LastInput >= INPUT_COOLDOWN) {
            boolean moved = false;
            if (heldKeys.contains(GLFW.GLFW_KEY_UP)) {
                p2Y = Math.max(0, p2Y - 1); moved = true;
            } else if (heldKeys.contains(GLFW.GLFW_KEY_DOWN)) {
                p2Y = Math.min(GRID_SIZE - 1, p2Y + 1); moved = true;
            }
            if (heldKeys.contains(GLFW.GLFW_KEY_LEFT)) {
                p2X = Math.max(0, p2X - 1); moved = true;
            } else if (heldKeys.contains(GLFW.GLFW_KEY_RIGHT)) {
                p2X = Math.min(GRID_SIZE - 1, p2X + 1); moved = true;
            }
            if (moved) {
                p2LastInput = now;
                if (grid[p2X][p2Y] == targetColor) p2LastSafe = now;
                playMoveSound();
            }
        }
    }

    // ══════════════════════════════════════
    //  游戏逻辑
    // ══════════════════════════════════════
    private void updateGame() {
        long now = System.currentTimeMillis();
        if (now - gameStartTime < GAME_START_PROTECTION) return;

        // 刷新安全时间
        if (!p1Dead && grid[p1X][p1Y] == targetColor) p1LastSafe = now;
        if (gameMode == GameMode.TWO_PLAYER && !p2Dead && grid[p2X][p2Y] == targetColor)
            p2LastSafe = now;

        // 颜色变化
        if (now - lastColorChangeTime >= colorChangeInterval) {
            randomizeGrid();
            targetColor = random.nextInt(GAME_COLORS.length);
            int safeSpots = Math.max(3, 10 - level);
            spawnSafeSpots(safeSpots);
            // 落地安全刷新
            if (!p1Dead && grid[p1X][p1Y] == targetColor) p1LastSafe = now;
            if (gameMode == GameMode.TWO_PLAYER && !p2Dead && grid[p2X][p2Y] == targetColor)
                p2LastSafe = now;

            lastColorChangeTime = now;
            playColorChangeSound();

            int gained = level * 10;
            if (!p1Dead) p1Score += gained;
            if (gameMode == GameMode.TWO_PLAYER && !p2Dead) p2Score += gained;

            // 升级
            int combined = (gameMode == GameMode.TWO_PLAYER) ? p1Score + p2Score : p1Score;
            if (combined > 0 && combined % (gameMode == GameMode.TWO_PLAYER ? 80 : 50) == 0) {
                level++;
                colorChangeInterval = Math.max(500, colorChangeInterval - 200);
            }
        }

        // 死亡判断
        if (!p1Dead && (now - p1LastSafe) > DEATH_GRACE_PERIOD) {
            p1Dead = true;
            spawnDeathParticles(p1X, p1Y, 0xFF2196F3);
        }
        if (gameMode == GameMode.TWO_PLAYER && !p2Dead && (now - p2LastSafe) > DEATH_GRACE_PERIOD) {
            p2Dead = true;
            spawnDeathParticles(p2X, p2Y, 0xFFFF5722);
        }

        // 游戏结束
        boolean end = (gameMode == GameMode.SINGLE)
                ? p1Dead
                : (p1Dead && p2Dead);
        if (end) {
            gameRunning = false;
            gameOver    = true;
            if (gameMode == GameMode.TWO_PLAYER) {
                winnerText = (p1Dead && p2Dead) ? "平局！两人同时落入危险！"
                        : p1Dead ? "§c玩家2胜利！（P2）" : "§b玩家1胜利！（P1）";
            }
            playGameOverSound();
        }

        updateParticles();
    }

    // ─────── 粒子 ───────
    private void spawnDeathParticles(int gx, int gy, int color) {
        float cx = gameStartX + gx * CELL_SIZE + CELL_SIZE / 2f;
        float cy = gameStartY + gy * CELL_SIZE + CELL_SIZE / 2f;
        for (int i = 0; i < 18; i++) {
            double angle = i / 18.0 * Math.PI * 2;
            float  spd   = 1.5f + random.nextFloat() * 3f;
            spawnParticle(cx, cy, (float) Math.cos(angle) * spd, (float) Math.sin(angle) * spd,
                    color, 28 + random.nextInt(22));
        }
    }

    private void spawnParticle(float x, float y, float vx, float vy, int color, int life) {
        for (int i = 0; i < MAX_PARTICLES; i++) {
            if (pLife[i] <= 0) {
                pX[i] = x; pY[i] = y; pVX[i] = vx; pVY[i] = vy;
                pColor[i] = color; pLife[i] = life;
                return;
            }
        }
    }

    private void updateParticles() {
        for (int i = 0; i < MAX_PARTICLES; i++) {
            if (pLife[i] > 0) {
                pX[i] += pVX[i]; pY[i] += pVY[i];
                pVY[i] += 0.1f;  pLife[i]--;
            }
        }
    }

    // ══════════════════════════════════════
    //  渲染
    // ══════════════════════════════════════
    @Override
    public void render(GuiGraphics g, int mx, int my, float pt) {
        g.fill(0, 0, this.width, this.height, 0xFF1A1A2E);

        if (gameMode == GameMode.MENU) {
            renderMenu(g, mx, my);
        } else {
            renderGrid(g);
            renderParticles(g);
            renderPlayers(g);
            renderHUD(g);
            if (gameOver) renderGameOver(g);
        }

        super.render(g, mx, my, pt);
        if (showExitConfirm) GameRenderHelper.drawExitConfirmOverlay(g, font, width, height, mx, my);
    }

    // ─────── 主菜单 ───────
    private void renderMenu(GuiGraphics g, int mx, int my) {
        int cx = this.width / 2, cy = this.height / 2;

        // 动态背景格子
        int cols = this.width / 28 + 1, rows = this.height / 28 + 1;
        for (int x = 0; x < cols; x++) for (int y = 0; y < rows; y++) {
            int ci = (int)((x * 5 + y * 3 + tickCount / 10) % GAME_COLORS.length);
            int a  = 20 + (int)(10 * Math.sin(tickCount * 0.05 + x * 0.6 + y * 0.8));
            g.fill(x*28, y*28, x*28+27, y*28+27, (a<<24)|(GAME_COLORS[ci]&0xFFFFFF));
        }
        g.fill(0, 0, this.width, this.height, 0xAA0D0D1A);

        // 标题
        drawBigText(g, "颜 色 追 逐", cx, cy - 100, 0xFFFF44);
        g.drawCenteredString(this.font, "Color Chase  —  踩住目标颜色，否则 2 秒后死亡！", cx, cy - 78, 0x778899);
        g.fill(cx - 160, cy - 64, cx + 160, cy - 63, 0xFF2244AA);

        // ── 单人按钮 ──
        boolean h1 = mx >= cx-155 && mx <= cx-15 && my >= cy-54 && my <= cy-28;
        drawMenuButton(g, cx - 155, cy - 54, 140, 26, h1,
                "▶  单人模式", 0xFF4CAF50, 0xFF1A3320);

        // ── 双人按钮 ──
        boolean h2 = mx >= cx+15 && mx <= cx+155 && my >= cy-54 && my <= cy-28;
        drawMenuButton(g, cx + 15, cy - 54, 140, 26, h2,
                "▶  双人模式", 0xFF2196F3, 0xFF0D1F3E);

        // 说明区
        int iy = cy - 18;
        g.fill(cx - 160, iy, cx + 160, iy + 1, 0xFF333355);

        g.drawString(this.font, "§b单人§r  ·  WASD 移动，颜色变化时站到目标色格子", cx - 158, iy + 6, 0x88AACC);
        g.drawString(this.font, "§b双人§r  ·  P1 用 WASD，P2 用 ←↑↓→ 方向键",        cx - 158, iy + 20, 0x88AACC);
        g.drawString(this.font, "离开安全格超过 2 秒会死！双人模式先死者输。",          cx - 158, iy + 34, 0xFFCC44);
        g.drawString(this.font, "颜色变化越来越快，坚持越久分数越高！",                 cx - 158, iy + 48, 0xFF9988);

        // 颜色彩条
        int dotX = cx - 56;
        for (int i = 0; i < GAME_COLORS.length; i++) {
            int ci = (i + (int)(tickCount / 7)) % GAME_COLORS.length;
            g.fill(dotX + i*14, iy+62, dotX + i*14+12, iy+74, GAME_COLORS[ci]);
        }
        g.drawCenteredString(this.font, "ESC — 退出", cx, iy + 80, 0x555566);
    }

    private void drawMenuButton(GuiGraphics g, int x, int y, int w, int h,
                                boolean hover, String label, int border, int bg) {
        g.fill(x, y, x+w, y+h, hover ? (bg | 0xFF000000) : 0xFF141428);
        g.fill(x, y, x+w, y+1, border);
        g.fill(x, y+h-1, x+w, y+h, (border>>1)&0xFF7F7F7F | 0xFF000000);
        g.fill(x, y, x+1, y+h, border);
        g.fill(x+w-1, y, x+w, y+h, border);
        int textColor = hover ? 0xFFFFFF : (border | 0xFF000000);
        g.drawCenteredString(this.font, label, x + w/2, y + h/2 - 4, textColor);
    }

    private void drawBigText(GuiGraphics g, String text, int cx, int y, int color) {
        g.pose().pushPose();
        g.pose().translate(cx, y, 0);
        g.pose().scale(2, 2, 1);
        int sw = this.font.width(text);
        g.drawString(this.font, text, -sw/2 + 1, 1, 0x44000000);
        g.drawString(this.font, text, -sw/2, 0, color);
        g.pose().popPose();
    }

    // ─────── 格子渲染 ───────
    private void renderGrid(GuiGraphics g) {
        for (int x = 0; x < GRID_SIZE; x++) {
            for (int y = 0; y < GRID_SIZE; y++) {
                int sx = gameStartX + x * CELL_SIZE;
                int sy = gameStartY + y * CELL_SIZE;

                int color = GAME_COLORS[grid[x][y]];
                // 目标色格子发光
                if (grid[x][y] == targetColor) {
                    float f = 1.05f + 0.08f * (float) Math.sin(tickCount * 0.15 + x * 0.3 + y * 0.2);
                    color = brighten(color, f);
                }
                g.fill(sx, sy, sx + CELL_SIZE, sy + CELL_SIZE, color);
                // 边缘阴影（仿 AO）
                g.fill(sx, sy, sx + CELL_SIZE, sy + 1, 0x40000000);
                g.fill(sx, sy, sx + 1, sy + CELL_SIZE, 0x40000000);
                g.fill(sx, sy + CELL_SIZE - 1, sx + CELL_SIZE, sy + CELL_SIZE, 0x30FFFFFF);
            }
        }
        // 游戏区边框
        g.fill(gameStartX-2, gameStartY-2, gameStartX+GAME_WIDTH+2, gameStartY,           0xFF334466);
        g.fill(gameStartX-2, gameStartY+GAME_HEIGHT, gameStartX+GAME_WIDTH+2, gameStartY+GAME_HEIGHT+2, 0xFF334466);
        g.fill(gameStartX-2, gameStartY-2, gameStartX,             gameStartY+GAME_HEIGHT+2, 0xFF334466);
        g.fill(gameStartX+GAME_WIDTH, gameStartY-2, gameStartX+GAME_WIDTH+2, gameStartY+GAME_HEIGHT+2, 0xFF334466);
    }

    // ─────── 玩家渲染 ───────
    private void renderPlayers(GuiGraphics g) {
        if (!p1Dead) renderPlayer(g, p1X, p1Y, 0xFF2196F3, 0xFFE3F2FD, "P1", false);
        if (gameMode == GameMode.TWO_PLAYER && !p2Dead)
            renderPlayer(g, p2X, p2Y, 0xFFFF5722, 0xFFFFF3E0, "P2", true);
    }

    private void renderPlayer(GuiGraphics g, int gx, int gy, int body, int head,
                              String label, boolean isP2) {
        int sx = gameStartX + gx * CELL_SIZE + 2;
        int sy = gameStartY + gy * CELL_SIZE + 2;
        int ps = CELL_SIZE - 4;

        // 阴影
        g.fill(sx + 1, sy + ps + 1, sx + ps - 1, sy + ps + 3, 0x33000000);
        // 黑色描边
        g.fill(sx - 1, sy - 1, sx + ps + 1, sy + ps + 1, 0xFF000000);
        // 身体
        g.fill(sx, sy + 4, sx + ps, sy + ps, body);
        // 头
        g.fill(sx + 1, sy, sx + ps - 1, sy + 4, head);
        // 发色
        int hair = isP2 ? 0xFFFF9933 : 0xFF66BBFF;
        g.fill(sx + 1, sy, sx + ps - 1, sy + 1, hair);
        // 眼睛（偶尔眨眼）
        boolean blink = tickCount % 80 < 3;
        if (!blink) {
            g.fill(sx + 2, sy + 1, sx + 5, sy + 4, 0xFFFFFFFF);
            g.fill(sx + ps - 5, sy + 1, sx + ps - 2, sy + 4, 0xFFFFFFFF);
            g.fill(sx + 3, sy + 2, sx + 5, sy + 4, 0xFF111111);
            g.fill(sx + ps - 4, sy + 2, sx + ps - 2, sy + 4, 0xFF111111);
        } else {
            g.fill(sx + 2, sy + 2, sx + 5, sy + 3, 0xFF111111);
            g.fill(sx + ps - 5, sy + 2, sx + ps - 2, sy + 3, 0xFF111111);
        }
        // 脚（走路动画）
        int fb = (int)(Math.sin(tickCount * 0.28) * 1.5);
        g.fill(sx + 1,      sy + ps - 2 + fb, sx + ps/2,  sy + ps + fb,  0xFF000000);
        g.fill(sx + ps/2+1, sy + ps - 2 - fb, sx + ps - 1, sy + ps - fb, 0xFF000000);
        // 标签
        g.drawString(this.font, label, sx - 1, sy - 10,
                isP2 ? 0xFFFF9966 : 0xFF88CCFF);
    }

    // ─────── 粒子渲染 ───────
    private void renderParticles(GuiGraphics g) {
        for (int i = 0; i < MAX_PARTICLES; i++) {
            if (pLife[i] > 0) {
                int a = Math.min(255, pLife[i] * 8);
                g.fill((int)pX[i], (int)pY[i], (int)pX[i]+3, (int)pY[i]+3,
                        (a<<24)|(pColor[i]&0xFFFFFF));
            }
        }
    }

    // ─────── HUD ───────
    private void renderHUD(GuiGraphics g) {
        long now  = System.currentTimeMillis();
        int  uiY  = gameStartY - 82;

        // 背景条
        g.fill(gameStartX-2, uiY-4, gameStartX+GAME_WIDTH+2, uiY+64, 0xBB0D0D1A);

        // 目标颜色
        g.drawString(this.font, "目标:", gameStartX, uiY, 0xFFFFFF);
        g.fill(gameStartX+36, uiY-1, gameStartX+58, uiY+10, GAME_COLORS[targetColor]);
        g.drawString(this.font, COLOR_NAMES[targetColor], gameStartX+62, uiY, GAME_COLORS[targetColor]);

        // 等级
        String lvlStr = "Lv." + level;
        g.drawString(this.font, lvlStr, gameStartX+GAME_WIDTH-font.width(lvlStr), uiY, 0xFFFF44);

        // 变色倒计时条
        long  rem    = colorChangeInterval - (now - lastColorChangeTime);
        float tPct   = (float)Math.max(0, rem) / colorChangeInterval;
        int   barW   = GAME_WIDTH;
        g.fill(gameStartX, uiY+14, gameStartX+barW, uiY+21, 0xFF333333);
        int fillW  = (int)(barW * tPct);
        int bColor = tPct>0.4f ? 0xFF4CAF50 : tPct>0.15f ? 0xFFFF9800 : 0xFFFF3322;
        g.fill(gameStartX, uiY+14, gameStartX+fillW, uiY+21, bColor);
        g.drawString(this.font,
                String.format("变色: %.1fs", Math.max(0, rem/1000.0)),
                gameStartX, uiY+24, 0xBBBBBB);

        // 分数区
        if (gameMode == GameMode.SINGLE) {
            String sc = "分数: " + p1Score;
            g.drawString(this.font, sc, gameStartX+GAME_WIDTH/2 - font.width(sc)/2, uiY+24, 0xFFFFFF);
        } else {
            // P1 框
            g.fill(gameStartX, uiY+38, gameStartX+GAME_WIDTH/2-4, uiY+52, 0x880A1A38);
            g.fill(gameStartX, uiY+38, gameStartX+GAME_WIDTH/2-4, uiY+39, 0xFF2196F3);
            g.drawString(this.font, "§bP1: " + p1Score + (p1Dead?" ✘":""), gameStartX+4, uiY+42,
                    p1Dead ? 0xFF555566 : 0xFF88CCFF);
            // P2 框
            int rx = gameStartX + GAME_WIDTH/2 + 4;
            g.fill(rx, uiY+38, gameStartX+GAME_WIDTH, uiY+52, 0x88380A0A);
            g.fill(rx, uiY+38, gameStartX+GAME_WIDTH, uiY+39, 0xFFFF5722);
            g.drawString(this.font, "§cP2: " + p2Score + (p2Dead?" ✘":""), rx+4, uiY+42,
                    p2Dead ? 0xFF665555 : 0xFFFF9966);
        }

        // 危险条
        renderDangerBars(g, now);

        // 底部提示
        int cy = gameStartY + GAME_HEIGHT + 8;
        if (gameMode == GameMode.SINGLE)
            g.drawString(this.font, "WASD 移动   ESC 返回菜单   R 重开", gameStartX, cy, 0xFF444466);
        else
            g.drawString(this.font, "§bP1: WASD   §cP2: ←↑↓→   §rESC 返回菜单   R 重开",
                    gameStartX, cy, 0xFF444466);
    }

    private void renderDangerBars(GuiGraphics g, long now) {
        if (now - gameStartTime <= GAME_START_PROTECTION) return;
        int barY = gameStartY + GAME_HEIGHT + 22;

        // P1
        if (!p1Dead && grid[p1X][p1Y] != targetColor) {
            float dp = 1f - Math.min(1f, (float)(now - p1LastSafe) / DEATH_GRACE_PERIOD);
            renderDangerBar(g, gameStartX, barY, 150, dp,
                    (tickCount%4<2 ? "§b" : "§c") + "P1 危险！", 0xFFFF5522);
        }
        // P2
        if (gameMode == GameMode.TWO_PLAYER && !p2Dead && grid[p2X][p2Y] != targetColor) {
            float dp = 1f - Math.min(1f, (float)(now - p2LastSafe) / DEATH_GRACE_PERIOD);
            int rx = gameStartX + GAME_WIDTH - 162;
            renderDangerBar(g, rx, barY, 150, dp,
                    (tickCount%4<2 ? "§c" : "§e") + "P2 危险！", 0xFFFF2200);
        }
    }

    private void renderDangerBar(GuiGraphics g, int x, int y, int w, float pct,
                                 String label, int color) {
        g.drawString(this.font, label, x, y, color);
        g.fill(x, y+10, x+w, y+18, 0xFF333333);
        g.fill(x, y+10, x+(int)(w*pct), y+18, color);
        g.drawString(this.font,
                String.format("%.1fs", pct * DEATH_GRACE_PERIOD / 1000.0),
                x+w+4, y+10, color);
    }

    // ─────── 游戏结束弹窗 ───────
    private void renderGameOver(GuiGraphics g) {
        g.fill(0, 0, this.width, this.height, 0x88000000);

        int ww=320, wh=190;
        int wx=(this.width-ww)/2, wy=(this.height-wh)/2;
        g.fill(wx, wy, wx+ww, wy+wh, 0xFF14142A);
        g.fill(wx, wy, wx+ww, wy+2,  0xFFFF5722);
        g.fill(wx, wy+wh-2, wx+ww, wy+wh, 0xFF2196F3);

        g.drawCenteredString(this.font, "游 戏 结 束", this.width/2, wy+14, 0xFFFF5722);

        if (gameMode == GameMode.SINGLE) {
            g.drawCenteredString(this.font, "最终分数: " + p1Score, this.width/2, wy+34, 0xFFFFFF);
            g.drawCenteredString(this.font, "达到等级: " + level,    this.width/2, wy+50, 0xFFFF44);
        } else {
            g.drawCenteredString(this.font, winnerText, this.width/2, wy+34, 0xFFFFFF);
            // 分数对比卡
            int lx = wx+28, rx = wx+ww/2+12;
            g.fill(lx,  wy+50, lx+128, wy+84, 0xFF0A1530);
            g.fill(lx,  wy+50, lx+128, wy+51, 0xFF2196F3);
            g.drawCenteredString(this.font, "玩家1 (WASD)",  lx+64, wy+55, 0xFF88CCFF);
            g.drawCenteredString(this.font, String.valueOf(p1Score), lx+64, wy+68,
                    p1Dead ? 0xFF555566 : 0xFFFFFFFF);

            g.fill(rx,  wy+50, rx+128, wy+84, 0xFF30100A);
            g.fill(rx,  wy+50, rx+128, wy+51, 0xFFFF5722);
            g.drawCenteredString(this.font, "玩家2 (方向键)", rx+64, wy+55, 0xFFFF9966);
            g.drawCenteredString(this.font, String.valueOf(p2Score), rx+64, wy+68,
                    p2Dead ? 0xFF665555 : 0xFFFFFFFF);

            g.drawCenteredString(this.font, "等级: " + level, this.width/2, wy+92, 0xFFFF44);
        }

        int btnY = wy+wh-54;
        g.fill(wx+20, btnY,    wx+ww-20, btnY+20,   0xFF1A3320);
        g.fill(wx+20, btnY,    wx+ww-20, btnY+1,    0xFF44AA44);
        g.drawCenteredString(this.font, "R  —  再来一局", this.width/2, btnY+6, 0xFF88FF88);

        g.fill(wx+20, btnY+24, wx+ww-20, btnY+44, 0xFF1A1A40);
        g.fill(wx+20, btnY+24, wx+ww-20, btnY+25, 0xFF4444AA);
        g.drawCenteredString(this.font, "ESC  —  返回主菜单", this.width/2, btnY+30, 0xFF8888FF);
    }

    // ══════════════════════════════════════
    //  键盘事件
    // ══════════════════════════════════════
    @Override
    public boolean keyPressed(int key, int scan, int mods) {
        heldKeys.add(key);

        if (key == GLFW.GLFW_KEY_ESCAPE) {
            if (showExitConfirm) { showExitConfirm = false; return true; }
            if (gameMode == GameMode.MENU || lanMode != LAN_NONE) {
                Minecraft.getInstance().setScreen(new GameSelectorScreen());
            } else if (gameOver) {
                gameMode = GameMode.MENU; gameRunning = false;
            } else {
                showExitConfirm = true;
            }
            return true;
        }
        if (showExitConfirm) return true;

        if (gameMode == GameMode.MENU) return super.keyPressed(key, scan, mods);

        if (gameOver && key == GLFW.GLFW_KEY_R) {
            if (lanMode == LAN_CLIENT) return true; // CLIENT 不能单方面重开
            initGame(gameMode == GameMode.TWO_PLAYER);
            // HOST 重开后，下一帧的 sendState 会自动同步新状态给 CLIENT
            // 但 CLIENT 的 gameOver 还是 true，需要发一个明确的重开信号
            if (lanMode == LAN_HOST) sendInput("RESTART");
            return true;
        }

        return super.keyPressed(key, scan, mods);
    }

    @Override
    public boolean keyReleased(int key, int scan, int mods) {
        heldKeys.remove(key);
        return super.keyReleased(key, scan, mods);
    }

    @Override
    public boolean mouseClicked(double mx, double my, int btn) {
        if (showExitConfirm) { int click = GameRenderHelper.getExitConfirmClick((int)mx, (int)my, width, height); if (click == 1) { showExitConfirm = false; gameMode = GameMode.MENU; gameRunning = false; return true; } if (click == 2) { showExitConfirm = false; return true; } return true; }
        if (gameMode == GameMode.MENU) {
            int cx = this.width/2, cy = this.height/2;
            if (mx>=cx-155&&mx<=cx-15&&my>=cy-54&&my<=cy-28) { initGame(false); return true; }
            if (mx>=cx+15 &&mx<=cx+155&&my>=cy-54&&my<=cy-28) { initGame(true);  return true; }
        }
        return super.mouseClicked(mx, my, btn);
    }

    private void playMoveSound() {
        if (minecraft != null && minecraft.level != null)
            minecraft.level.playLocalSound(0, 0, 0, SoundEvents.UI_BUTTON_CLICK.value(),
                    SoundSource.MASTER, 0.18f, 1.7f, false);
    }

    private void playColorChangeSound() {
        if (minecraft != null && minecraft.player != null)
            minecraft.player.playSound(SoundEvents.EXPERIENCE_ORB_PICKUP, 0.8f,
                    1.0f + level * 0.08f);
    }

    private void playGameOverSound() {
        if (minecraft != null && minecraft.level != null)
            minecraft.level.playLocalSound(0, 0, 0, SoundEvents.ANVIL_LAND,
                    SoundSource.MASTER, 0.7f, 0.5f, false);
    }

    private int brighten(int color, float f) {
        int a = (color>>24)&0xFF;
        int r = Math.min(255, (int)(((color>>16)&0xFF)*f));
        int gg= Math.min(255, (int)(((color>>8 )&0xFF)*f));
        int b = Math.min(255, (int)(( color&0xFF       )*f));
        return (a<<24)|(r<<16)|(gg<<8)|b;
    }

    @Override public boolean isPauseScreen() { return false; }
}