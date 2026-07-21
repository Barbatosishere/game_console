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
 * 接水管游戏 —— 现代风格重制版
 * Bug修复：原版 UI 过于简陋（纯黑背景 + 蓝色色块），
 * 重制为现代科技感界面：渐变背景、发光水管、水流动画、难度选择。
 */
@OnlyIn(Dist.CLIENT)
public class PipePuzzleScreen extends Screen {
    boolean showExitConfirm = false;

    private enum Difficulty {
        EASY(4,"简单"), NORMAL(5,"普通"), HARD(7,"困难"), EXPERT(9,"专家");
        final int size; final String label;
        Difficulty(int s,String l){size=s;label=l;}
    }
    private enum State { MENU, PLAYING }
    private State state = State.MENU;
    private Difficulty difficulty = Difficulty.NORMAL;

    private PipeTile[][] grid;
    private int gridSize;
    private int moves = 0;
    private boolean gameWon = false;
    private long tickCount = 0;
    private long winTick = -1;
    private int hovX = -1, hovY = -1;

    private List<int[]> flowPath = new ArrayList<>();
    private float flowProg = 0f;

    private int tileSize, startX, startY;

    private static final int BG_DARK   = 0xFF060C1A;
    private static final int BG_PANEL  = 0xFF0D1A2E;
    private static final int BORDER    = 0xFF1E3A5F;
    private static final int TILE_IDLE = 0xFF0A1628;
    private static final int TILE_HOV  = 0xFF112040;
    private static final int TILE_GRID = 0xFF0F2238;
    private static final int PIPE_IDLE = 0xFF2A6A9A;
    private static final int PIPE_FLOW = 0xFF00CCFF;
    private static final int PIPE_GLOW = 0x4400AAFF;
    private static final int START_C   = 0xFF00FF88;
    private static final int END_C     = 0xFFFF6600;
    private static final int WIN_C     = 0xFF44FFAA;

    private final Random random = new Random();

    public PipePuzzleScreen() { super(Component.literal("接水管")); }

    @Override public void init() { super.init(); recalcLayout(); }

    private void recalcLayout() {
        gridSize = difficulty.size;
        int maxT = Math.min((width-160)/gridSize, (height-100)/gridSize);
        tileSize = Math.max(28, Math.min(52, maxT));
        startX = (width  - gridSize * tileSize) / 2;
        startY = (height - gridSize * tileSize) / 2 + 10;
    }

    private void initPuzzle() {
        recalcLayout();
        grid = new PipeTile[gridSize][gridSize];
        gameWon = false; moves = 0; winTick = -1;
        flowPath = new ArrayList<>(); flowProg = 0f;
        PipeType[] rots = {PipeType.STRAIGHT, PipeType.CORNER, PipeType.T_SHAPE};
        for (int y=0;y<gridSize;y++) for (int x=0;x<gridSize;x++)
            grid[y][x] = new PipeTile(x, y, rots[random.nextInt(rots.length)]);
        grid[0][0] = new PipeTile(0,0,PipeType.START);
        grid[gridSize-1][gridSize-1] = new PipeTile(gridSize-1,gridSize-1,PipeType.END);
        for (int y=0;y<gridSize;y++) for (int x=0;x<gridSize;x++) {
            PipeTile t=grid[y][x];
            if (t.type.isRotatable()) for (int r=random.nextInt(t.type.rotations.length);r>0;r--) t.rotate();
        }
        updateFlow();
    }

    private void rotatePipe(int x, int y) {
        if (gameWon) return;
        PipeTile t = grid[y][x];
        if (!t.type.isRotatable()) return;
        t.rotate(); moves++;
        if (Minecraft.getInstance().player != null)
            Minecraft.getInstance().player.playSound(SoundEvents.BAMBOO_PLACE, 0.4f, 1.5f);
        updateFlow();
    }

    private void updateFlow() {
        flowPath = findFlowPath();
        boolean ok = !flowPath.isEmpty()
                && flowPath.get(flowPath.size()-1)[0]==gridSize-1
                && flowPath.get(flowPath.size()-1)[1]==gridSize-1;
        if (ok && !gameWon) {
            gameWon = true; winTick = tickCount;
            if (Minecraft.getInstance().player != null)
                Minecraft.getInstance().player.playSound(SoundEvents.PLAYER_LEVELUP, 1f, 1f);
        }
        flowProg = 0f;
    }

    private List<int[]> findFlowPath() {
        List<int[]> p = new ArrayList<>();
        boolean[][] v = new boolean[gridSize][gridSize];
        if (dfs(0,0,v,p)) return p;
        return new ArrayList<>();
    }

    private boolean dfs(int x, int y, boolean[][] v, List<int[]> path) {
        if (x<0||x>=gridSize||y<0||y>=gridSize||v[y][x]) return false;
        v[y][x]=true; path.add(new int[]{x,y});
        if (x==gridSize-1&&y==gridSize-1) return true;
        for (Direction d : grid[y][x].getOpenings()) {
            int nx=x+d.dx, ny=y+d.dy;
            if (nx>=0&&nx<gridSize&&ny>=0&&ny<gridSize&&!v[ny][nx]
                    && grid[ny][nx].getOpenings().contains(d.getOpposite()))
                if (dfs(nx,ny,v,path)) return true;
        }
        path.remove(path.size()-1); return false;
    }

    @Override public void tick() {
        tickCount++;
        if (!flowPath.isEmpty() && flowProg < 1f) flowProg = Math.min(1f, flowProg + 0.04f);
    }

    @Override public boolean mouseClicked(double mx, double my, int btn) {
        if (showExitConfirm) { int click = GameRenderHelper.getExitConfirmClick(mx, my, width, height); if (click == 1) { showExitConfirm = false; state = State.MENU; return true; } if (click == 2) { showExitConfirm = false; return true; } return true; }
        if (state == State.MENU) {
            int cx=width/2, cy=height/2;
            Difficulty[] dvs = Difficulty.values();
            for (int i=0;i<dvs.length;i++) {
                int bx=cx-110+i*58, by=cy+10;
                if (mx>=bx&&mx<=bx+50&&my>=by&&my<=by+22) { difficulty=dvs[i]; return true; }
            }
            if (mx>=cx-60&&mx<=cx+60&&my>=cy+48&&my<=cy+70) { state=State.PLAYING; initPuzzle(); return true; }
            return true;
        }
        if (mx>=startX && mx<=startX+gridSize*tileSize && my>=startY && my<=startY+gridSize*tileSize) {
            int gx=(int)((mx-startX)/tileSize), gy=(int)((my-startY)/tileSize);
            if (gx>=0&&gx<gridSize&&gy>=0&&gy<gridSize) { rotatePipe(gx,gy); return true; }
        }
        if (gameWon) {
            int cx=width/2, cardY=height/2-55;
            if (mx>=cx-60&&mx<=cx+60&&my>=cardY+70&&my<=cardY+92) { initPuzzle(); return true; }
        }
        return false;
    }

    @Override public void mouseMoved(double mx, double my) {
        hovX=-1; hovY=-1;
        if (mx>=startX&&mx<=startX+gridSize*tileSize&&my>=startY&&my<=startY+gridSize*tileSize) {
            hovX=(int)((mx-startX)/tileSize); hovY=(int)((my-startY)/tileSize);
        }
    }

    @Override public boolean keyPressed(int key, int scan, int mods) {
        if (key==GLFW.GLFW_KEY_ESCAPE) {
            if (showExitConfirm) { showExitConfirm = false; return true; }
            if (state==State.PLAYING && !gameWon) { showExitConfirm = true; return true; }
            if (state==State.MENU) { Minecraft.getInstance().setScreen(new GameSelectorScreen()); return true; }
            state=State.MENU; return true;
        }
        if (showExitConfirm) return true;
        if (key==GLFW.GLFW_KEY_R&&state==State.PLAYING){initPuzzle();return true;}
        return super.keyPressed(key,scan,mods);
    }

    @Override public void render(GuiGraphics g, int mx, int my, float pt) {
        g.fillGradient(0,0,width,height,BG_DARK,0xFF0A1428);
        GameRenderHelper.renderDecorativeLines(g,width,height,tickCount,0x001122);
        if (state==State.MENU) renderMenu(g,mx,my);
        else                   renderGame(g,mx,my);
        if (showExitConfirm) GameRenderHelper.drawExitConfirmOverlay(g, font, width, height, mx, my);
    }

    private void renderMenu(GuiGraphics g, int mx, int my) {
        int cx=width/2, cy=height/2;
        GameRenderHelper.drawShadowedCenteredText(g,font,"§b接 水 管",cx,cy-75,0x00CCFF,2);
        g.drawCenteredString(font,"§7点击旋转管道，将水从入口引至出口",cx,cy-52,0x446688);
        GameRenderHelper.drawDivider(g,cx-110,cy-38,220,0xFF1E4A7A,0xFF0D2A4A);
        g.drawCenteredString(font,"§f选择难度",cx,cy-16,0xCCCCCC);

        Difficulty[] dvs=Difficulty.values();
        for (int i=0;i<dvs.length;i++) {
            int bx=cx-110+i*58, by=cy+10;
            boolean sel=dvs[i]==difficulty, hov=mx>=bx&&mx<=bx+50&&my>=by&&my<=by+22;
            int bg=sel?0xFF1A5A9A:(hov?0xFF0D2A4A:0xFF071828);
            int bo=sel?0xFF00AAFF:(hov?0xFF1A5A9A:0xFF0D3050);
            g.fill(bx-1,by-1,bx+51,by+23,bo); g.fill(bx,by,bx+50,by+22,bg);
            g.drawCenteredString(font,(sel?"§b":"§7")+dvs[i].label,bx+25,by+7,sel?0x00CCFF:0xAAAAAA);
        }
        g.drawCenteredString(font,"§8"+difficulty.size+"×"+difficulty.size+" 格",cx,cy+36,0x334455);

        boolean sh=mx>=cx-60&&mx<=cx+60&&my>=cy+48&&my<=cy+70;
        g.fill(cx-61,cy+47,cx+61,cy+71,sh?0xFF00AAFF:0xFF005588);
        g.fill(cx-60,cy+48,cx+60,cy+70,sh?0xFF0088CC:0xFF003355);
        g.drawCenteredString(font,"§f▶  开始游戏",cx,cy+56,sh?0xFFFFFF:0x88CCFF);
        g.drawCenteredString(font,"§8ESC 退出",cx,cy+86,0x334455);
    }

    private void renderGame(GuiGraphics g, int mx, int my) {
        int bw=gridSize*tileSize;
        g.fill(startX-6,startY-6,startX+bw+6,startY+bw+6,0x3300AAFF);
        g.fill(startX-3,startY-3,startX+bw+3,startY+bw+3,BORDER);
        g.fill(startX,startY,startX+bw,startY+bw,BG_PANEL);

        Set<Long> fs=new HashSet<>();
        int fl=Math.round(flowProg*flowPath.size());
        for (int i=0;i<Math.min(fl,flowPath.size());i++) {
            int[]p=flowPath.get(i); fs.add((long)p[0]<<32|p[1]);
        }

        for (int gy=0;gy<gridSize;gy++) for (int gx=0;gx<gridSize;gx++) {
            int px=startX+gx*tileSize, py=startY+gy*tileSize;
            boolean hov=(gx==hovX&&gy==hovY), inF=fs.contains((long)gx<<32|gy);
            g.fill(px+1,py+1,px+tileSize-1,py+tileSize-1,hov?TILE_HOV:TILE_IDLE);
            g.fill(px,py,px+tileSize,py+1,TILE_GRID); g.fill(px,py,px+1,py+tileSize,TILE_GRID);
            drawTile(g,grid[gy][gx],px,py,tileSize,inF,hov);
        }
        drawHUD(g);
        if (gameWon) drawWin(g,mx,my);
    }

    private void drawTile(GuiGraphics g, PipeTile t, int px, int py, int sz, boolean inF, boolean hov) {
        int cx=px+sz/2, cy=py+sz/2;
        int pw=Math.max(4,sz/5), hf=pw/2;
        int pc, gc;
        if (t.type==PipeType.START)      { pc=START_C; gc=0x4400FF88; }
        else if (t.type==PipeType.END)   { pc=inF?WIN_C:END_C; gc=inF?0x4444FFAA:0x44FF6600; }
        else                              { pc=inF?PIPE_FLOW:PIPE_IDLE; gc=inF?PIPE_GLOW:0x11224466; }

        if (inF||t.type==PipeType.START||t.type==PipeType.END) {
            int gr=(t.type==PipeType.START||t.type==PipeType.END)?sz/3:pw+2;
            g.fill(cx-gr,cy-gr,cx+gr,cy+gr,gc);
        }
        g.fill(cx-hf,cy-hf,cx+hf,cy+hf,pc);
        for (Direction d:t.getOpenings()) switch(d){
            case UP    -> g.fill(cx-hf,py+2,cx+hf,cy,pc);
            case DOWN  -> g.fill(cx-hf,cy,cx+hf,py+sz-2,pc);
            case LEFT  -> g.fill(px+2,cy-hf,cx,cy+hf,pc);
            case RIGHT -> g.fill(cx,cy-hf,px+sz-2,cy+hf,pc);
        }
        if (t.type==PipeType.START) g.drawCenteredString(font,"§a▶",cx-font.width("▶")/2+1,cy-4,START_C);
        else if (t.type==PipeType.END) g.drawCenteredString(font,inF?"§a★":"§6★",cx-font.width("★")/2+1,cy-4,inF?WIN_C:END_C);
        if (hov&&t.type.isRotatable()) {
            g.fill(px,py,px+sz,py+1,0x8800CCFF); g.fill(px,py+sz-1,px+sz,py+sz,0x8800CCFF);
            g.fill(px,py,px+1,py+sz,0x8800CCFF); g.fill(px+sz-1,py,px+sz,py+sz,0x8800CCFF);
        }
    }

    private void drawHUD(GuiGraphics g) {
        int cx=width/2;
        g.fill(0,0,width,24,0xCC060C1A); g.fill(0,24,width,25,BORDER);
        g.drawString(font,"§b接水管",8,7,0x00CCFF);
        g.drawCenteredString(font,"§7步数: §f"+moves,cx,7,0xCCCCCC);
        String st=gameWon?"§a✔ 已连通":flowPath.isEmpty()?"§c✘ 未连通":"§e~ 部分连通";
        g.drawString(font,st,width-font.width(st.replaceAll("§.",""))-8,7,0xFFFFFF);
        g.fill(0,height-20,width,height,0xCC060C1A); g.fill(0,height-21,width,height-20,BORDER);
        g.drawCenteredString(font,"§8点击旋转管道  R 重置  ESC 菜单",cx,height-14,0x334455);
    }

    private void drawWin(GuiGraphics g, int mx, int my) {
        g.flush(); // 防止先绘制的管道/HUD文字盖住遮罩背景（批量渲染text批次后置）
        int cx=width/2, cy=height/2;
        int pulse=(int)(128+80*Math.sin((tickCount-winTick)*0.2));
        int wc=0xFF000000|(pulse<<16)|(255<<8)|pulse;
        g.fill(0,0,width,height,0x88000000);
        int cw=280,ch=110,cax=cx-cw/2,cay=cy-ch/2;
        g.fill(cax-2,cay-2,cax+cw+2,cay+ch+2,wc);
        g.fill(cax,cay,cax+cw,cay+ch,0xFF071828);
        g.drawCenteredString(font,"§a🎉  管道连通！  🎉",cx,cay+14,WIN_C);
        g.drawCenteredString(font,"§f总步数: §b"+moves+" 步",cx,cay+32,0xFFFFFF);
        g.drawCenteredString(font,"§7难度: §f"+difficulty.label+" ("+gridSize+"×"+gridSize+")",cx,cay+48,0xAAAAAA);
        boolean bh=mx>=cx-60&&mx<=cx+60&&my>=cay+70&&my<=cay+92;
        g.fill(cax+60,cay+70,cax+cw-60,cay+92,bh?0xFF00AAFF:0xFF005588);
        g.drawCenteredString(font,"§f↺  再来一局",cx,cay+77,bh?0xFFFFFF:0x88CCFF);
    }

    @Override public boolean isPauseScreen() { return false; }

    // ── 数据结构 ──────────────────────────────────────
    private enum PipeType {
        START(new Direction[][]{{Direction.RIGHT,Direction.DOWN}}),
        END  (new Direction[][]{{Direction.LEFT, Direction.UP  }}),
        STRAIGHT(new Direction[][]{{Direction.UP,Direction.DOWN},{Direction.LEFT,Direction.RIGHT}}),
        CORNER  (new Direction[][]{
                {Direction.RIGHT,Direction.DOWN},{Direction.DOWN,Direction.LEFT},
                {Direction.LEFT,Direction.UP},   {Direction.UP,Direction.RIGHT}}),
        T_SHAPE (new Direction[][]{
                {Direction.LEFT,Direction.RIGHT,Direction.DOWN},
                {Direction.UP,Direction.DOWN,Direction.RIGHT},
                {Direction.LEFT,Direction.RIGHT,Direction.UP},
                {Direction.UP,Direction.DOWN,Direction.LEFT}});
        final Direction[][] rotations;
        PipeType(Direction[][] r){rotations=r;}
        boolean isRotatable(){return this!=START&&this!=END;}
        List<Direction> getOpenings(int r){return List.of(rotations[r%rotations.length]);}
    }

    private static class PipeTile {
        final PipeType type; int rotation=0;
        PipeTile(int x,int y,PipeType t){type=t;}
        void rotate(){if(type.isRotatable())rotation=(rotation+1)%type.rotations.length;}
        List<Direction> getOpenings(){return type.getOpenings(rotation);}
    }

    private enum Direction {
        UP(0,-1),DOWN(0,1),LEFT(-1,0),RIGHT(1,0);
        final int dx,dy; Direction(int x,int y){dx=x;dy=y;}
        Direction getOpposite(){return switch(this){
            case UP->DOWN; case DOWN->UP; case LEFT->RIGHT; case RIGHT->LEFT;
        };}
    }
}
