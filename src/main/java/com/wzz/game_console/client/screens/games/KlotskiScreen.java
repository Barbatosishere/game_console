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

/**
 * 华容道 —— 现代UI重制版
 * 改进：深色科技感UI / 5个经典关卡 / 步数统计 / 撤销 / 动画高亮 / 出口发光
 */
@OnlyIn(Dist.CLIENT)
public class KlotskiScreen extends Screen {
    boolean showExitConfirm = false;

    private static final int BW = 4, BH = 5; // 棋盘宽高（格）

    // ── 颜色 ──────────────────────────────────────────
    private static final int C_BG      = 0xFF080E1C;
    private static final int C_BOARD   = 0xFF0D1628;
    private static final int C_BORDER  = 0xFF1E3A5F;
    private static final int C_GRID    = 0xFF0F2035;
    private static final int C_EXIT    = 0xFF004422;
    private static final int C_EXIT_HL = 0xFF00FF88;
    private static final int C_CAO     = 0xFF8B0000; // 曹操 深红
    private static final int C_CAO_HL  = 0xFFCC2222;
    private static final int C_GEN     = 0xFF004488; // 大将 深蓝
    private static final int C_GEN_HL  = 0xFF1166CC;
    private static final int C_SOLD    = 0xFF3A3A2A; // 小兵 暗绿黄
    private static final int C_SOLD_HL = 0xFF666644;
    private static final int C_SEL_BDR = 0xFF00CCFF; // 选中边框

    // ── 状态 ──────────────────────────────────────────
    private Piece[][] board = new Piece[BH][BW];
    private List<Piece> pieces = new ArrayList<>();
    private Piece selected = null;
    private int tileSize, bx, by;
    private int currentLevel = 1;
    private int steps = 0;
    private long tickCount = 0;
    private long winTick = -1;
    private boolean won = false;

    // 撤销：记录 (piece, oldX, oldY)
    private final Deque<int[]> undoStack = new ArrayDeque<>();
    private static final int MAX_UNDO = 200;

    // ── 关卡定义 ──────────────────────────────────────
    // 每关 = 多条 {x, y, w, h, nameIndex, colorType}
    // nameIndex: 0=曹,1=关,2=张,3=马,4=赵,5=黄,6=兵  colorType: 0=曹,1=大将,2=小兵
    private static final String[] NAMES = {"曹","关","张","马","赵","黄","兵"};
    private static final int[][][] LEVELS = {
        // ── 关卡1 横刀立马 ──
        {{1,0,2,2,0,0},{0,0,1,2,2,1},{3,0,1,2,5,1},{1,2,2,1,1,1},
         {0,2,1,2,4,1},{3,2,1,2,3,1},{1,3,1,1,6,2},{2,3,1,1,6,2},
         {0,4,1,1,6,2},{3,4,1,1,6,2}},
        // ── 关卡2 指挥若定 ──
        {{1,0,2,2,0,0},{0,0,1,2,3,1},{3,0,1,2,5,1},{0,2,2,1,1,1},
         {2,2,1,2,4,1},{3,2,1,2,2,1},{0,3,1,1,6,2},{1,4,1,1,6,2},
         {2,4,1,1,6,2},{3,4,1,1,6,2}},
        // ── 关卡3 将拥曹营 ──
        {{1,1,2,2,0,0},{0,0,1,2,2,1},{3,0,1,2,3,1},{1,0,2,1,1,1},
         {0,3,1,2,4,1},{3,3,1,2,5,1},{1,3,1,1,6,2},{2,3,1,1,6,2},
         {0,2,1,1,6,2},{3,2,1,1,6,2}},
        // ── 关卡4 兵分三路 ──
        {{1,0,2,2,0,0},{0,0,1,2,3,1},{3,0,1,2,2,1},{0,2,1,1,6,2},
         {3,2,1,1,6,2},{0,3,2,1,1,1},{2,3,2,1,5,1},{1,2,2,1,4,1},
         {0,4,1,1,6,2},{3,4,1,1,6,2}},
        // ── 关卡5 雷霆万钧 ──
        {{1,0,2,2,0,0},{0,0,1,2,2,1},{3,0,1,2,3,1},{1,2,1,2,4,1},
         {2,2,1,2,5,1},{0,2,1,1,6,2},{3,2,1,1,6,2},{0,4,1,1,6,2},
         {1,4,1,1,6,2},{3,4,1,1,6,2}},
    };

    public KlotskiScreen() {
        super(Component.literal("华容道"));
    }

    @Override public void init() {
        super.init();
        int max = Math.min((width - 140) / BW, (height - 120) / BH);
        tileSize = Math.max(36, Math.min(64, max));
        bx = (width  - BW * tileSize) / 2;
        by = (height - BH * tileSize) / 2;
        if (!won && pieces.isEmpty()) loadLevel(currentLevel);
    }

    // ══════════════════════════════════════════════════
    //  关卡加载
    // ══════════════════════════════════════════════════
    private void loadLevel(int lv) {
        currentLevel = Math.max(1, Math.min(lv, LEVELS.length));
        board = new Piece[BH][BW];
        pieces.clear();
        selected = null;
        steps = 0;
        won = false;
        winTick = -1;
        undoStack.clear();

        for (int[] d : LEVELS[currentLevel - 1]) {
            Piece p = new Piece(d[0], d[1], d[2], d[3], NAMES[d[4]], d[5]);
            pieces.add(p);
            place(p, p.x, p.y);
        }
    }

    // ══════════════════════════════════════════════════
    //  棋盘操作
    // ══════════════════════════════════════════════════
    private void place(Piece p, int nx, int ny) {
        clear(p);
        p.x = nx; p.y = ny;
        for (int dy = 0; dy < p.h; dy++) for (int dx = 0; dx < p.w; dx++)
            board[ny+dy][nx+dx] = p;
    }

    private void clear(Piece p) {
        for (int dy = 0; dy < p.h; dy++) for (int dx = 0; dx < p.w; dx++)
            if (board[p.y+dy][p.x+dx] == p) board[p.y+dy][p.x+dx] = null;
    }

    private boolean canMove(Piece p, int nx, int ny) {
        if (nx < 0 || nx + p.w > BW || ny < 0 || ny + p.h > BH) return false;
        for (int dy = 0; dy < p.h; dy++) for (int dx = 0; dx < p.w; dx++) {
            Piece o = board[ny+dy][nx+dx];
            if (o != null && o != p) return false;
        }
        return true;
    }

    private void tryMove(Piece p, int dx, int dy) {
        if (p == null || won) return;
        int nx = p.x + dx, ny = p.y + dy;
        if (!canMove(p, nx, ny)) return;
        // 压栈撤销
        if (undoStack.size() >= MAX_UNDO) undoStack.pollFirst();
        undoStack.push(new int[]{pieces.indexOf(p), p.x, p.y});
        place(p, nx, ny);
        steps++;
        if (Minecraft.getInstance().player != null)
            Minecraft.getInstance().player.playSound(SoundEvents.WOOD_PLACE, 0.3f, 1.4f);
        checkWin(p);
    }

    private void undoMove() {
        if (undoStack.isEmpty()) return;
        int[] u = undoStack.pop();
        Piece p = pieces.get(u[0]);
        place(p, u[1], u[2]);
        steps = Math.max(0, steps - 1);
        won = false; winTick = -1;
    }

    private void checkWin(Piece p) {
        // 曹操(2×2)到达 x=1, y=BH-2
        if (p.w == 2 && p.h == 2 && p.x == 1 && p.y == BH - 2) {
            won = true;
            winTick = tickCount;
            if (Minecraft.getInstance().player != null)
                Minecraft.getInstance().player.playSound(SoundEvents.PLAYER_LEVELUP, 1f, 1f);
        }
    }

    // ══════════════════════════════════════════════════
    //  输入
    // ══════════════════════════════════════════════════
    @Override public void tick() { tickCount++; }

    @Override public boolean mouseClicked(double mx, double my, int btn) {
        if (showExitConfirm) { int click = GameRenderHelper.getExitConfirmClick(mx, my, width, height); if (click == 1) { showExitConfirm = false; Minecraft.getInstance().setScreen(new GameSelectorScreen()); return true; } if (click == 2) { showExitConfirm = false; return true; } return true; }
        if (won) {
            // 下一关按钮
            int cx = width/2, cy = height/2;
            if (mx >= cx-60 && mx <= cx+60 && my >= cy+32 && my <= cy+54) {
                if (currentLevel < LEVELS.length) { currentLevel++; loadLevel(currentLevel); }
                else loadLevel(1);
                return true;
            }
            return true;
        }
        int gx = (int)((mx - bx) / tileSize), gy = (int)((my - by) / tileSize);
        if (gx < 0 || gx >= BW || gy < 0 || gy >= BH) { selected = null; return true; }
        Piece clicked = board[gy][gx];
        if (clicked != null) {
            selected = clicked;
        } else if (selected != null) {
            // 尝试向点击的空格方向移动
            int dx = gx - selected.x - selected.w/2 + (selected.w%2==0?0:0);
            int dy = gy - selected.y - selected.h/2;
            // 找最近的合法方向
            if (Math.abs(dx) >= Math.abs(dy)) tryMove(selected, dx > 0 ? 1 : -1, 0);
            else                               tryMove(selected, 0, dy > 0 ? 1 : -1);
        }
        return true;
    }

    @Override public boolean keyPressed(int key, int scan, int mods) {
        if (key == GLFW.GLFW_KEY_ESCAPE) {
            if (showExitConfirm) { showExitConfirm = false; return true; }
            if (selected != null) { selected = null; return true; }
            showExitConfirm = true; return true;
        }
        if (showExitConfirm) return true;
        if (key == GLFW.GLFW_KEY_R) { loadLevel(currentLevel); return true; }
        if (key == GLFW.GLFW_KEY_N && !won) {
            if (currentLevel < LEVELS.length) loadLevel(currentLevel + 1);
            return true;
        }
        if (key == GLFW.GLFW_KEY_Z) { undoMove(); return true; }
        if (selected != null) {
            switch (key) {
                case GLFW.GLFW_KEY_W, GLFW.GLFW_KEY_UP    -> { tryMove(selected, 0,-1); return true; }
                case GLFW.GLFW_KEY_S, GLFW.GLFW_KEY_DOWN  -> { tryMove(selected, 0, 1); return true; }
                case GLFW.GLFW_KEY_A, GLFW.GLFW_KEY_LEFT  -> { tryMove(selected,-1, 0); return true; }
                case GLFW.GLFW_KEY_D, GLFW.GLFW_KEY_RIGHT -> { tryMove(selected, 1, 0); return true; }
            }
        }
        return super.keyPressed(key, scan, mods);
    }

    // ══════════════════════════════════════════════════
    //  渲染
    // ══════════════════════════════════════════════════
    @Override public void render(GuiGraphics g, int mx, int my, float pt) {
        // 背景
        g.fillGradient(0, 0, width, height, C_BG, 0xFF0C1525);
        GameRenderHelper.renderDecorativeLines(g, width, height, tickCount, 0x001133);

        int cx = width/2;

        // 标题
        GameRenderHelper.drawShadowedCenteredText(g, font, "§b华 容 道", cx, 8, 0x44AAFF, 2);
        g.drawCenteredString(font, "§7关卡 " + currentLevel + " / " + LEVELS.length
                + "   §f步数: §e" + steps
                + (undoStack.isEmpty() ? "" : "   §8Z-撤销"),
                cx, 28, 0xCCCCCC);

        // 出口发光（底部中间2格）
        int exitPulse = (int)(80 + 40 * Math.sin(tickCount * 0.15));
        int exitGlowCol = 0xFF000000 | (exitPulse << 8); // 脉冲绿色
        g.fill(bx + tileSize, by + BH * tileSize - 3,
               bx + 3 * tileSize, by + BH * tileSize + 6, exitGlowCol);
        g.fill(bx + tileSize, by + BH * tileSize,
               bx + 3 * tileSize, by + BH * tileSize + 4, C_EXIT_HL);

        // 棋盘
        g.fill(bx - 4, by - 4, bx + BW * tileSize + 4, by + BH * tileSize + 4, C_BORDER);
        g.fill(bx, by, bx + BW * tileSize, by + BH * tileSize, C_BOARD);

        // 出口底色（行3-4中间）
        g.fill(bx + tileSize + 1, by + 3 * tileSize + 1,
               bx + 3 * tileSize - 1, by + BH * tileSize - 1, C_EXIT);

        // 网格
        for (int x = 0; x <= BW; x++) g.fill(bx + x*tileSize, by, bx + x*tileSize+1, by + BH*tileSize, C_GRID);
        for (int y = 0; y <= BH; y++) g.fill(bx, by + y*tileSize, bx + BW*tileSize, by + y*tileSize+1, C_GRID);

        // 棋子
        Set<Piece> drawn = new HashSet<>();
        for (Piece p : pieces) {
            if (drawn.contains(p)) continue; drawn.add(p);
            drawPiece(g, p);
        }

        // 右侧说明
        int infoX = bx + BW * tileSize + 16;
        int infoY = by;
        GameRenderHelper.drawPanel(g, infoX, infoY, 90, 130, 0xFF0D1628, C_BORDER);
        g.drawString(font, "§b操作说明", infoX+6, infoY+8, 0x44AAFF);
        g.drawString(font, "§7点击选棋",  infoX+6, infoY+22, 0xAAAAAA);
        g.drawString(font, "§7点空格移动",infoX+6, infoY+34, 0xAAAAAA);
        g.drawString(font, "§7WASD/方向键",infoX+6,infoY+46, 0xAAAAAA);
        g.drawString(font, "§7Z 撤销",    infoX+6, infoY+58, 0xAAAAAA);
        g.drawString(font, "§7R 重置",    infoX+6, infoY+70, 0xAAAAAA);
        g.drawString(font, "§7N 下一关",  infoX+6, infoY+82, 0xAAAAAA);
        g.drawString(font, "§7ESC 返回",  infoX+6, infoY+94, 0xAAAAAA);
        g.drawString(font, "§a目标:",     infoX+6, infoY+110, 0x44FF88);
        g.drawString(font, "§f曹操出口",  infoX+6, infoY+122, 0xCCCCCC);

        // 底栏
        g.fill(0, height-20, width, height, 0xBB060C1A);
        g.drawCenteredString(font, "§7让 §c曹操 §7从底部出口逃脱！", cx, height-14, 0x556688);

        // 胜利遮罩
        if (won) drawWin(g, mx, my);
        if (showExitConfirm) GameRenderHelper.drawExitConfirmOverlay(g, font, width, height, mx, my);
    }

    private void drawPiece(GuiGraphics g, Piece p) {
        int px = bx + p.x * tileSize + 3;
        int py = by + p.y * tileSize + 3;
        int pw = p.w * tileSize - 6;
        int ph = p.h * tileSize - 6;
        boolean isSel = (p == selected);

        // 底色
        int baseColor = switch (p.colorType) {
            case 0 -> isSel ? C_CAO_HL : C_CAO;
            case 1 -> isSel ? C_GEN_HL : C_GEN;
            default -> isSel ? C_SOLD_HL : C_SOLD;
        };

        // 渐变效果（顶部亮一点）
        int lightColor = blend(baseColor, 0xFFFFFFFF, 0.12f);
        g.fillGradient(px, py, px+pw, py+ph, lightColor, baseColor);

        // 边框
        int borderColor = isSel ? C_SEL_BDR : blend(baseColor, 0xFFFFFFFF, 0.3f);
        g.fill(px-1, py-1, px+pw+1, py, borderColor);
        g.fill(px-1, py+ph, px+pw+1, py+ph+1, borderColor);
        g.fill(px-1, py-1, px, py+ph+1, borderColor);
        g.fill(px+pw, py-1, px+pw+1, py+ph+1, borderColor);

        // 选中时额外发光边框
        if (isSel) {
            g.fill(px-2, py-2, px+pw+2, py-1, 0x8800CCFF);
            g.fill(px-2, py+ph+1, px+pw+2, py+ph+2, 0x8800CCFF);
            g.fill(px-2, py-2, px-1, py+ph+2, 0x8800CCFF);
            g.fill(px+pw+1, py-2, px+pw+2, py+ph+2, 0x8800CCFF);
        }

        // 文字
        int textColor = p.colorType == 0 ? 0xFFFFCC : (p.colorType == 1 ? 0xAADDFF : 0xCCCCAA);
        // 曹操用2倍字
        if (p.w == 2 && p.h == 2) {
            g.pose().pushPose();
            g.pose().translate(px + pw/2f, py + ph/2f - 6, 0);
            g.pose().scale(1.5f, 1.5f, 1);
            int tw = font.width(p.name);
            g.drawString(font, p.name, -tw/2, -4, textColor);
            g.pose().popPose();
        } else {
            g.drawCenteredString(font, p.name, px + pw/2, py + ph/2 - 4, textColor);
        }
    }

    private void drawWin(GuiGraphics g, int mx, int my) {
        int cx = width/2, cy = height/2;
        g.fill(0, 0, width, height, 0xAA000000);

        int pulse = (int)(150 + 80*Math.sin((tickCount - winTick)*0.15));
        int wc = 0xFF000000 | (pulse << 8) | (pulse/3);
        int cw=280, ch=100, cax=cx-cw/2, cay=cy-ch/2;
        g.fill(cax-2, cay-2, cax+cw+2, cay+ch+2, wc);
        g.fill(cax, cay, cax+cw, cay+ch, 0xFF050F1E);

        g.drawCenteredString(font, "§a🎉 曹操成功逃脱！", cx, cay+12, 0x44FF88);
        g.drawCenteredString(font, "§f关卡 §b"+currentLevel+" §f完成！  步数: §e"+steps, cx, cay+28, 0xFFFFFF);

        String nextTxt = currentLevel < LEVELS.length ? "下一关 ▶" : "重新开始";
        boolean bh = mx>=cx-60&&mx<=cx+60&&my>=cay+52&&my<=cay+74;
        g.fill(cx-61,cay+51,cx+61,cay+75, bh?0xFF00AAFF:0xFF005588);
        g.fill(cx-60,cay+52,cx+60,cay+74, bh?0xFF0088CC:0xFF003355);
        g.drawCenteredString(font, "§f"+nextTxt, cx, cay+60, bh?0xFFFFFF:0x88CCFF);
    }

    private static int blend(int c, int with, float t) {
        int ar=(c>>16&0xFF), ag=(c>>8&0xFF), ab=(c&0xFF);
        int br=(with>>16&0xFF), bg=(with>>8&0xFF), bb=(with&0xFF);
        int r=(int)(ar+t*(br-ar)), gg=(int)(ag+t*(bg-ag)), b=(int)(ab+t*(bb-ab));
        return 0xFF000000|(r<<16)|(gg<<8)|b;
    }

    @Override public boolean isPauseScreen() { return false; }

    // ── 棋子数据 ──────────────────────────────────────
    private static class Piece {
        int x, y, w, h;
        final String name;
        final int colorType; // 0=曹操,1=大将,2=小兵
        Piece(int x,int y,int w,int h,String name,int ct){
            this.x=x;this.y=y;this.w=w;this.h=h;this.name=name;this.colorType=ct;
        }
    }
}
