package com.wzz.game_console.client.screens.games;

import com.wzz.game_console.client.screens.GameSelectorScreen;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import org.lwjgl.glfw.GLFW;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 中国象棋 — 双人 / 人机对战
 *
 * 操作：鼠标左键选子，再次点击合法位置移动
 *       R = 悔一步（人机模式悔两步）  |  ESC = 退出/返回菜单
 *
 * AI：负极大值搜索 + α-β 剪枝 + 棋子-位置价值表
 *     难度：初级 depth=2 / 中级 depth=3 / 高级 depth=4
 *     后台线程运算，运算时显示"思考中…"动画，不卡游戏
 */
public class ChessGameScreen extends Screen implements LanMultiplayerScreen {

    // ══════════════════════════════════════════════
    //  常量
    // ══════════════════════════════════════════════
    static final int COLS = 9, ROWS = 10;
    static final int GENERAL=1, ADVISOR=2, ELEPHANT=3,
                     HORSE=4, CHARIOT=5, CANNON=6, SOLDIER=7;

    // ══════════════════════════════════════════════
    //  游戏模式
    // ══════════════════════════════════════════════
    enum GameMode { MENU, PVP, PVA }
    enum Difficulty { EASY, MEDIUM, HARD }

    GameMode   gameMode   = GameMode.MENU;
    Difficulty difficulty = Difficulty.MEDIUM;
    boolean showExitConfirm = false;

    // ══════════════════════════════════════════════
    //  布局（自适应屏幕）
    // ══════════════════════════════════════════════
    int CELL, PR, bx, by;

    // ══════════════════════════════════════════════
    //  棋盘状态
    // ══════════════════════════════════════════════
    int[][] board = new int[COLS][ROWS];

    // 悔棋（最多2步用于人机模式）
    int[][][] undoBoards   = new int[2][][];
    boolean[] undoRedTurns = new boolean[2];
    int undoCount = 0;

    int selCol = -1, selRow = -1;
    List<int[]> legalMoves = new ArrayList<>();
    boolean redTurn = true;
    boolean redInCheck = false, blackInCheck = false;
    int lastFC=-1, lastFR=-1, lastTC=-1, lastTR=-1;
    boolean gameOver = false;
    String resultMsg = "";
    long tick = 0;

    // ══════════════════════════════════════════════
    //  AI 状态
    // ══════════════════════════════════════════════
    final AtomicBoolean aiThinking = new AtomicBoolean(false);
    volatile int[] aiPendingMove = null;   // {fc,fr,tc,tr} 由AI线程写入
    Thread aiThread = null;
    long aiStartTick = 0;

    // ══════════════════════════════════════════════
    //  AI 估值参数
    // ══════════════════════════════════════════════
    static final int INF = 1_000_000;

    /** 棋子基础分 */
    static final int[] PIECE_VAL = {0,
        10000, // 将
        200,   // 士
        220,   // 象
        400,   // 马
        900,   // 车
        450,   // 炮
        100    // 兵
    };

    /**
     * 位置价值表，从黑方视角定义（row0=黑方底线）
     * 正分=有利，从黑方角度。红方使用时行号镜像(9-r)。
     * 每张表 [col][row]，共 9×10。
     */
    // 马
    static final int[][] PST_HORSE = {
        { 0,  0, -2,  0,  0,  0, -2,  0,  0, 0},
        { 0,  4,  6,  8,  4,  4,  6,  4,  0, 0},
        { 2,  8, 12, 14, 12, 10, 12,  8,  2, 0},
        { 4, 14, 20, 24, 20, 18, 20, 14,  4, 0},
        { 2, 12, 18, 20, 18, 16, 18, 12,  2, 0},
        { 0,  4, 12, 14, 12, 10, 12,  4,  0, 0},
        { 0,  8, 12, 12, 12, 10, 12,  8,  0, 0},
        { 0,  0,  8, 10,  8,  8,  8,  0,  0, 0},
        { 0,  2,  6,  4,  6,  4,  4,  2,  0, 0},
        { 0,  0,  2,  0,  0,  0,  2,  0,  0, 0},
    };
    // 车
    static final int[][] PST_CHARIOT = {
        {14, 14, 12, 18, 16, 18, 12, 14, 14, 0},
        {16, 20, 18, 24, 26, 24, 18, 20, 16, 0},
        {12, 12, 12, 18, 18, 18, 12, 12, 12, 0},
        {12, 18, 16, 22, 22, 22, 16, 18, 12, 0},
        {12, 14, 12, 18, 18, 18, 12, 14, 12, 0},
        {12, 16, 14, 20, 20, 20, 14, 16, 12, 0},
        {12, 12, 12, 18, 18, 18, 12, 12, 12, 0},
        {12, 18, 16, 22, 22, 22, 16, 18, 12, 0},
        {16, 20, 18, 24, 26, 24, 18, 20, 16, 0},
        {14, 14, 12, 18, 16, 18, 12, 14, 14, 0},
    };
    // 炮
    static final int[][] PST_CANNON = {
        { 6,  4,  0, -10, -12, -10,  0,  4,  6, 0},
        { 2,  2,  0,  -4,  -14,  -4,  0,  2,  2, 0},
        { 2,  6,  4,   0,   -6,   0,  4,  6,  2, 0},
        { 0,  0,  0,   6,   10,   6,  0,  0,  0, 0},
        { 0,  2,  4,   6,   10,   6,  4,  2,  0, 0},
        { 0,  0,  4,   6,   10,   6,  4,  0,  0, 0},
        { 0,  2,  0,   4,    8,   4,  0,  2,  0, 0},
        {-2, -4, -2,   4,    8,   4, -2, -4, -2, 0},
        { 0,  0,  2,   4,    6,   4,  2,  0,  0, 0},
        { 0,  2,  4,   6,    6,   6,  4,  2,  0, 0},
    };
    // 兵（过河前后差别大）
    static final int[][] PST_SOLDIER = {
        { 0,  0,  0,  0,  0,  0,  0,  0,  0, 0},
        { 0,  0,  0,  0,  0,  0,  0,  0,  0, 0},
        { 0,  0,  0,  0,  0,  0,  0,  0,  0, 0},
        { 8, 18, 28, 40, 40, 40, 28, 18,  8, 0}, // 过河第一行
        {14, 24, 38, 52, 60, 52, 38, 24, 14, 0},
        {22, 34, 50, 64, 76, 64, 50, 34, 22, 0},
        {34, 48, 62, 76, 86, 76, 62, 48, 34, 0},
        { 6, 14, 22, 32, 36, 32, 22, 14,  6, 0}, // 未过河
        { 4, 10, 14, 20, 24, 20, 14, 10,  4, 0},
        { 2,  6,  8, 10, 12, 10,  8,  6,  2, 0},
    };

    /** 走法生成方向常量（避免 AI 搜索中每次调用重复创建数组） */
    static final int[][] DIR_ORTHO = {{0,1},{0,-1},{1,0},{-1,0}};
    static final int[][] DIR_DIAG  = {{1,1},{1,-1},{-1,1},{-1,-1}};
    static final int[][] DIR_ELEPHANT = {{2,2},{2,-2},{-2,2},{-2,-2}};
    static final int[][] HORSE_LEGS = {{1,0},{-1,0},{0,1},{0,-1}};
    static final int[][][] HORSE_DEST = {{{2,1},{2,-1}},{{-2,1},{-2,-1}},{{1,2},{-1,2}},{{1,-2},{-1,-2}}};

    // ══════════════════════════════════════════════
    //  LAN 联机支持
    // ══════════════════════════════════════════════
    private int lanMode = LAN_NONE;
    /** 防止远程走法回音：收到远程走法时不发送回去 */
    private boolean receivingRemoteMove = false;
    private java.util.UUID remotePeer = null;

    /** LAN 联机构造：isHost=true → 执红先手，false → 执黑后手 */
    public ChessGameScreen(boolean isHost, java.util.UUID remote) {
        super(Component.literal("中国象棋"));
        this.lanMode    = isHost ? LAN_HOST : LAN_CLIENT;
        this.remotePeer = remote;
    }

    @Override public java.util.UUID getLanPeer() { return remotePeer; }
    @Override public String getLanGameId() { return "chess"; }

    @Override
    public void onRemoteMove(String data) {
        if ("RESTART".equals(data)) { resetBoard(); return; }
        try {
            String[] p = data.split(",");
            int fc = Integer.parseInt(p[0]), fr = Integer.parseInt(p[1]);
            int tc = Integer.parseInt(p[2]), tr = Integer.parseInt(p[3]);
            receivingRemoteMove = true;
            doMove(fc, fr, tc, tr);
            receivingRemoteMove = false;
        } catch (Exception ignored) {}
    }

    @Override public void onRemoteState(String data) { /* 象棋走法驱动，无需状态同步 */ }
    @Override public void onRemoteGameOver(String data) { /* 由 doMove 本地检测 */ }

    private void sendLanMove(int fc, int fr, int tc, int tr) {
        if (lanMode == LAN_NONE) return;
        sendMove(fc + "," + fr + "," + tc + "," + tr);
    }

    // ══════════════════════════════════════════════
    //  构造
    // ══════════════════════════════════════════════
    public ChessGameScreen() {
        super(Component.literal("中国象棋"));
    }

    @Override
    public void init() {
        super.init();
        CELL = Math.max(24, Math.min(44, Math.min(
                (width - 140) / COLS,
                (height - 160) / (ROWS + 1))));
        PR = CELL * 6 / 10;
        bx = (width  - (COLS - 1) * CELL) / 2;
        by = (height - (ROWS - 1) * CELL) / 2 - CELL / 4;
        // LAN 联机：跳过菜单直接开始
        if (lanMode != LAN_NONE && gameMode == GameMode.MENU) startGame(GameMode.PVP);
    }

    void startGame(GameMode mode) {
        gameMode = mode;
        resetBoard();
    }

    void resetBoard() {
        for (int[] col : board) Arrays.fill(col, 0);
        int[] back = {CHARIOT,HORSE,ELEPHANT,ADVISOR,GENERAL,ADVISOR,ELEPHANT,HORSE,CHARIOT};
        for (int c = 0; c < 9; c++) board[c][0] = -back[c];
        board[1][2]=-CANNON; board[7][2]=-CANNON;
        for (int c=0;c<9;c+=2) board[c][3]=-SOLDIER;
        for (int c = 0; c < 9; c++) board[c][9] = back[c];
        board[1][7]=CANNON; board[7][7]=CANNON;
        for (int c=0;c<9;c+=2) board[c][6]=SOLDIER;

        selCol=selRow=-1; legalMoves.clear();
        redTurn=true; gameOver=false; resultMsg="";
        redInCheck=blackInCheck=false;
        lastFC=lastFR=lastTC=lastTR=-1;
        undoCount=0; Arrays.fill(undoBoards,null);
        aiPendingMove=null;
        if (aiThread!=null) aiThread.interrupt();
        aiThinking.set(false);
    }

    // ══════════════════════════════════════════════
    //  Tick — AI调度
    // ══════════════════════════════════════════════
    @Override
    public void tick() {
        tick++;
        if (gameMode != GameMode.PVA || gameOver || redTurn) return;
        // AI的轮到了
        if (aiPendingMove != null && !aiThinking.get()) {
            // 应用AI计算好的落子
            int[] mv = aiPendingMove;
            aiPendingMove = null;
            doMove(mv[0], mv[1], mv[2], mv[3]);
            selCol=selRow=-1; legalMoves.clear();
        } else if (!aiThinking.get() && aiPendingMove == null) {
            // 启动AI思考
            launchAI();
        }
    }

    void launchAI() {
        aiThinking.set(true);
        aiStartTick = tick;
        int[][] snapshot = deepCopy(board);
        int depth = switch (difficulty) {
            case EASY   -> 2;
            case MEDIUM -> 3;
            case HARD   -> 4;
        };
        aiThread = new Thread(() -> {
            try {
                int[] best = aiBestMove(snapshot, false, depth); // false=黑方走
                aiPendingMove = best;
            } catch (Exception ignored) {
            } finally {
                aiThinking.set(false);
            }
        }, "ChessAI");
        aiThread.setDaemon(true);
        aiThread.start();
    }

    // ══════════════════════════════════════════════
    //  主渲染
    // ══════════════════════════════════════════════
    @Override
    public void renderBackground(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        // 不渲染默认32x32像素菜单背景纹理和模糊效果,游戏自行绘制不透明背景
    }

    @Override
    public void render(GuiGraphics g, int mx, int my, float pt) {
        g.fill(0, 0, width, height, 0xFF140E00);
        if (gameMode == GameMode.MENU) {
            renderMenu(g, mx, my);
        } else {
            drawBackground(g);
            drawBoard(g);
            drawHighlights(g, mx, my);
            drawPieces(g);
            drawHUD(g);
            if (gameMode == GameMode.PVA) drawAiStatus(g);
            // 覆盖层绘制前先flush：GuiGraphics批量渲染时text批次整体晚于gui批次，
            // 不flush会导致先绘制的棋子文字盖住后绘制的弹窗/结束面板背景
            if (gameOver || showExitConfirm) g.flush();
            if (gameOver) drawGameOver(g);
            if (showExitConfirm) drawExitConfirm(g, mx, my);
        }
        super.render(g, mx, my, pt);
    }

    // ══════════════════════════════════════════════
    //  菜单
    // ══════════════════════════════════════════════
    void renderMenu(GuiGraphics g, int mx, int my) {
        int cx = width/2, cy = height/2;

        // 棋盘格背景
        for (int r=0;r<8;r++) for (int c=0;c<9;c++) {
            int x=c*(width/9), y=r*(height/8);
            g.fill(x,y,x+width/9-1,y+height/8-1,0xFF180E00+(((r+c)%2)*0x080400));
        }
        g.fill(0,0,width,height,0xCC0E0800);

        // 标题
        drawBig(g,"中  国  象  棋",cx,cy-110,0xFFDD8800);
        g.drawCenteredString(font,"Chinese Chess",cx,cy-88,0xFF886633);
        g.fill(cx-130,cy-76,cx+130,cy-75,0xFF664400);

        // 模式按钮
        boolean hp = inBtn(mx,my,cx-130,cy-64,120,26);
        boolean ha = inBtn(mx,my,cx+10 ,cy-64,120,26);
        drawBtn(g,cx-130,cy-64,120,26,hp,"👥  双人对战",0xFF4CAF50,0xFF1A3320);
        drawBtn(g,cx+10 ,cy-64,120,26,ha,"🤖  人机对战",0xFF2196F3,0xFF0D1F3E);

        // 难度（仅在人机时亮显）
        g.drawCenteredString(font,"难度选择",cx,cy-24,0xFF886644);
        String[] dlbl = {"初  级","中  级","高  级"};
        Difficulty[] dvals = Difficulty.values();
        for (int i=0;i<3;i++) {
            int dx=cx-110+i*76, dy=cy-12;
            boolean sel = difficulty==dvals[i];
            boolean hd  = inBtn(mx,my,dx,dy,68,22);
            int border = i==0?0xFF44AA44:i==1?0xFFFFAA00:0xFFFF4444;
            g.fill(dx,dy,dx+68,dy+22, sel?0xFF333333:hd?0xFF222222:0xFF111111);
            g.fill(dx,dy,dx+68,dy+1, sel||hd?border:0xFF333333);
            g.fill(dx,dy+21,dx+68,dy+22, sel||hd?border:0xFF333333);
            g.drawCenteredString(font,dlbl[i],dx+34,dy+7,
                sel?border:hd?(border|0xFF000000):0xFF666666);
        }

        // 说明
        g.drawCenteredString(font,"点击选子，再点落子 | R=悔棋 | ESC=返回",cx,cy+22,0xFF554433);
        g.drawCenteredString(font,"人机模式：您执红先手，AI执黑",cx,cy+36,0xFF886644);
        g.drawCenteredString(font,
            difficulty==Difficulty.EASY?"初级：适合初学者，思考较浅":
            difficulty==Difficulty.MEDIUM?"中级：有一定棋力，会布局策略":
            "高级：搜索较深，有较强攻防能力",
            cx,cy+52,0xFF665533);
    }

    boolean inBtn(int mx,int my,int x,int y,int w,int h){
        return mx>=x&&mx<=x+w&&my>=y&&my<=y+h;
    }
    void drawBtn(GuiGraphics g,int x,int y,int w,int h,
                 boolean hover,String lbl,int border,int bg){
        g.fill(x,y,x+w,y+h,hover?(bg|0xFF000000):0xFF111118);
        g.fill(x,y,x+w,y+1,border);
        g.fill(x,y+h-1,x+w,y+h,(border>>1)&0xFF7F7F7F|0xFF000000);
        g.fill(x,y,x+1,y+h,border);
        g.fill(x+w-1,y,x+w,y+h,border);
        g.drawCenteredString(font,lbl,x+w/2,y+h/2-4,hover?0xFFFFFF:(border|0xFF000000));
    }
    void drawBig(GuiGraphics g,String t,int cx,int y,int color){
        // 用偏移多次绘制模拟粗体大字，避免 pose.scale 导致文字模糊
        int sw=font.width(t);
        int x=cx-sw/2;
        // 阴影
        g.drawString(font,t,x+1,y+1,0x44000000);
        // 主体（画两次偏移模拟加粗）
        g.drawString(font,t,x,y,color);
        g.drawString(font,t,x+1,y,color);
        g.drawString(font,t,x,y+1,color);
    }

    // ══════════════════════════════════════════════
    //  背景 & 棋盘
    // ══════════════════════════════════════════════
    void drawBackground(GuiGraphics g){
        for(int i=0;i<60;i++){int x=(i*37)%width;g.fill(x,0,x+1,height,0x06FFDDAA);}
        int pw=(COLS-1)*CELL+CELL*3,ph=(ROWS-1)*CELL+CELL*4;
        int px=bx-CELL*3/2,py=by-CELL*3/2;
        g.fill(px-4,py-4,px+pw+4,py+ph+4,0xFF3A2200);
        g.fill(px-3,py-3,px+pw+3,py+ph+3,0xFFE8A830);
        g.fill(px-1,py-1,px+pw+1,py+ph+1,0xFF6B3A00);
        g.fill(px,py,px+pw,py+ph,0xFFC88C20);
    }

    void drawBoard(GuiGraphics g){
        int w=(COLS-1)*CELL; int lc=0xFF5A3000;
        for(int r=0;r<ROWS;r++) g.fill(bx,by+r*CELL,bx+w+1,by+r*CELL+1,lc);
        for(int c=0;c<COLS;c++){
            g.fill(bx+c*CELL,by,bx+c*CELL+1,by+4*CELL+1,lc);
            g.fill(bx+c*CELL,by+5*CELL,bx+c*CELL+1,by+9*CELL+1,lc);
            if(c==0||c==COLS-1) g.fill(bx+c*CELL,by+4*CELL,bx+c*CELL+1,by+5*CELL+1,lc);
        }
        int ry=by+4*CELL+1;
        g.fill(bx+1,ry,bx+w,ry+CELL-1,0xFFB88020);
        int fs=font.width("楚  河");
        g.drawString(font,"楚  河",bx+w/4-fs/2,ry+CELL/2-4,0xFF7A4A00);
        g.drawString(font,"汉  界",bx+w*3/4-fs/2,ry+CELL/2-4,0xFF7A4A00);
        drawLine(g,bx+3*CELL,by,bx+5*CELL,by+2*CELL,0xFF224488);
        drawLine(g,bx+5*CELL,by,bx+3*CELL,by+2*CELL,0xFF224488);
        drawLine(g,bx+3*CELL,by+7*CELL,bx+5*CELL,by+9*CELL,0xFF882222);
        drawLine(g,bx+5*CELL,by+7*CELL,bx+3*CELL,by+9*CELL,0xFF882222);
        int[][] dots={{1,2},{7,2},{0,3},{2,3},{4,3},{6,3},{8,3},
                      {1,7},{7,7},{0,6},{2,6},{4,6},{6,6},{8,6}};
        for(int[] d:dots) cornerMark(g,d[0],d[1],lc);
    }

    void cornerMark(GuiGraphics g,int col,int row,int color){
        int x=bx+col*CELL,y=by+row*CELL,s=Math.max(3,CELL/8),g2=2;
        boolean L=col>0,R=col<8,U=row>0,D=row<9;
        if(U)g.fill(x,y-s,x+1,y-g2,color);
        if(D)g.fill(x,y+g2+1,x+1,y+s+1,color);
        if(L)g.fill(x-s,y,x-g2,y+1,color);
        if(R)g.fill(x+g2+1,y,x+s+1,y+1,color);
        if(U&&L){g.fill(x-s,y-s,x-g2,y-s+1,color);g.fill(x-s,y-s,x-s+1,y-g2,color);}
        if(U&&R){g.fill(x+g2+1,y-s,x+s+1,y-s+1,color);g.fill(x+s,y-s,x+s+1,y-g2,color);}
        if(D&&L){g.fill(x-s,y+s,x-g2,y+s+1,color);g.fill(x-s,y+g2+1,x-s+1,y+s+1,color);}
        if(D&&R){g.fill(x+g2+1,y+s,x+s+1,y+s+1,color);g.fill(x+s,y+g2+1,x+s+1,y+s+1,color);}
    }

    // ══════════════════════════════════════════════
    //  高亮层
    // ══════════════════════════════════════════════
    void drawHighlights(GuiGraphics g,int mx,int my){
        if(lastFC>=0){
            fillRect(g,bx+lastFC*CELL,by+lastFR*CELL,PR+2,0x55FFCC44);
            fillRect(g,bx+lastTC*CELL,by+lastTR*CELL,PR+2,0x55FFAA22);
            drawLine(g,bx+lastFC*CELL,by+lastFR*CELL,bx+lastTC*CELL,by+lastTR*CELL,0x44FFCC44);
        }
        if(redInCheck||blackInCheck){
            int p=redInCheck?GENERAL:-GENERAL;
            for(int c=0;c<COLS;c++) for(int r=0;r<ROWS;r++)
                if(board[c][r]==p){
                    int a=(int)(100+80*Math.sin(tick*0.28));
                    fillRect(g,bx+c*CELL,by+r*CELL,PR+4,(a<<24)|0xFF2222);
                }
        }
        if(selCol>=0){
            int fa=(int)(160+80*Math.sin(tick*0.22));
            fillRect(g,bx+selCol*CELL,by+selRow*CELL,PR+4,(fa<<24)|0xFFFF00);
            fillRect(g,bx+selCol*CELL,by+selRow*CELL,PR+2,(Math.min(255,fa+40)<<24)|0xFFFF00);
        }
        for(int[] mv:legalMoves){
            int sx=bx+mv[0]*CELL,sy=by+mv[1]*CELL;
            int a=(int)(120+80*Math.sin(tick*0.18+mv[0]*0.5));
            if(board[mv[0]][mv[1]]!=0){
                g.fill(sx-PR-2,sy-PR-2,sx+PR+3,sy-PR+1,(a<<24)|0xFF3333);
                g.fill(sx-PR-2,sy+PR,  sx+PR+3,sy+PR+3,(a<<24)|0xFF3333);
                g.fill(sx-PR-2,sy-PR-2,sx-PR+1,sy+PR+3,(a<<24)|0xFF3333);
                g.fill(sx+PR,  sy-PR-2,sx+PR+3,sy+PR+3,(a<<24)|0xFF3333);
            } else {
                fillCircle(g,sx,sy,PR/3,(a/2<<24)|0x44FF88);
            }
        }
        // 悬停
        boolean aiTurn = gameMode==GameMode.PVA && !redTurn;
        if(selCol<0&&!gameOver&&!aiTurn){
            int hc=snapCol(mx),hr=snapRow(my);
            if(hc>=0&&hc<COLS&&hr>=0&&hr<ROWS){
                int hp=board[hc][hr];
                boolean own=(redTurn&&hp>0)||(!redTurn&&hp<0);
                if(own) fillRect(g,bx+hc*CELL,by+hr*CELL,PR+3,0x44FFFFFF);
            }
        }
    }

    // ══════════════════════════════════════════════
    //  棋子绘制
    // ══════════════════════════════════════════════
    void drawPieces(GuiGraphics g){
        for(int c=0;c<COLS;c++) for(int r=0;r<ROWS;r++)
            if(board[c][r]!=0) drawPiece(g,c,r,board[c][r]);
    }

    /** 棋子名称（静态常量，避免每帧重复创建） */
    static final String[] RED_NAMES = {"","帅","仕","相","马","车","炮","兵"};
    static final String[] BLACK_NAMES = {"","将","士","象","馬","車","砲","卒"};

    void drawPiece(GuiGraphics g,int col,int row,int piece){
        boolean red=piece>0; int abs=Math.abs(piece);
        int px=bx+col*CELL,py=by+row*CELL;
        int cOuter =red?0xFF991500:0xFF1A1A1A;
        int cInner =red?0xFFCC2200:0xFF2E2E2E;
        int cHighlt=red?0xFFFF6644:0xFF555566;
        int cRing  =red?0xFFFFAA66:0xFF7788BB;
        int cText  =red?0xFFFFEE88:0xFFCCDDFF;
        fillCircle(g,px+2,py+2,PR+1,0x66000000);
        fillCircle(g,px,py,PR+2,cOuter);
        fillCircle(g,px,py,PR,  cInner);
        for(int a=210;a<=320;a+=4){
            int hx=(int)(px+(PR-2)*Math.cos(Math.toRadians(a)));
            int hy=(int)(py+(PR-2)*Math.sin(Math.toRadians(a)));
            g.fill(hx,hy,hx+2,hy+2,cHighlt);
        }
        drawCircleRing(g,px,py,PR-3,cRing);
        String name=red?RED_NAMES[abs]:BLACK_NAMES[abs];
        int tw=font.width(name);
        g.drawString(font,name,px-tw/2+1,py-4+1,0x88000000);
        g.drawString(font,name,px-tw/2,  py-4,  cText);
    }

    // ══════════════════════════════════════════════
    //  HUD
    // ══════════════════════════════════════════════
    void drawHUD(GuiGraphics g){
        int bw=(COLS-1)*CELL, hm=CELL/2;
        // 黑方（上）
        int topY=by-hm-28;
        g.fill(bx-hm,topY,bx+bw+hm,topY+28,0xAA000011);
        g.fill(bx-hm,topY+27,bx+bw+hm,topY+28,0xFF224488);
        String bName = gameMode==GameMode.PVA?"AI（黑）":"黑  方";
        String bLbl=(!redTurn&&!gameOver?"◀  ":"   ")+bName;
        String bSuf=blackInCheck?"  ⚠ 将军！":(!redTurn&&!gameOver&&!aiThinking.get()?"  走棋中...":
                     !redTurn&&aiThinking.get()?"  思考中…":"");
        g.drawString(font,bLbl+bSuf,bx,topY+8,
            blackInCheck?0xFF5577FF:!redTurn?0xFF88AAFF:0xFF666688);

        // 红方（下）
        int botY=by+(ROWS-1)*CELL+hm+2;
        g.fill(bx-hm,botY,bx+bw+hm,botY+28,0xAA110000);
        g.fill(bx-hm,botY,bx+bw+hm,botY+1,0xFF883322);
        String rName = gameMode==GameMode.PVA?"玩家（红）":"红  方";
        String rLbl=(redTurn&&!gameOver?"▶  ":"   ")+rName;
        String rSuf=redInCheck?"  ⚠ 将军！":(redTurn&&!gameOver?"  走棋中...":"");
        g.drawString(font,rLbl+rSuf,bx,botY+8,
            redInCheck?0xFFFF4444:redTurn?0xFFFFBB44:0xFF886644);

        // 列坐标
        String[] cl={"九","八","七","六","五","四","三","二","一"};
        for(int c=0;c<COLS;c++) g.drawString(font,cl[c],bx+c*CELL-font.width(cl[c])/2,botY+18,0xFF886644);

        // 难度标签（人机模式）
        if(gameMode==GameMode.PVA){
            String dlbl="难度："+(difficulty==Difficulty.EASY?"初级":
                          difficulty==Difficulty.MEDIUM?"中级":"高级");
            g.drawString(font,dlbl,bx+bw+hm+6,by+4,0xFF886644);
        }

        // 操作提示（左侧）
        int tipX=bx-hm-font.width("R:悔棋")-12;
        if(tipX>0){
            g.drawString(font,"选棋",tipX,by+(ROWS-1)*CELL/2-20,0xFF665544);
            g.fill(tipX,by+(ROWS-1)*CELL/2-2,tipX+font.width("R:悔棋"),by+(ROWS-1)*CELL/2-1,0xFF554433);
            g.drawString(font,"R:悔棋",tipX,by+(ROWS-1)*CELL/2+2,0xFF665544);
        }
    }

    // AI思考动画指示器
    void drawAiStatus(GuiGraphics g){
        if(!aiThinking.get()||gameOver) return;
        int bw=(COLS-1)*CELL, hm=CELL/2;
        int topY=by-hm-28;
        // 旋转点动画
        int dots=4;
        int animI=(int)((tick/5)%dots);
        StringBuilder sb=new StringBuilder("  ");
        for(int i=0;i<dots;i++) sb.append(i==animI?"●":"○");
        g.drawString(font,sb.toString(),bx+bw-60,topY+8,0xFF88AAFF);
        // 思考进度条（模拟）
        int barW=80;
        float prog=(float)((tick-aiStartTick)%40)/40f;
        g.fill(bx+bw-80,topY+20,bx+bw,topY+24,0xFF222244);
        g.fill(bx+bw-80,topY+20,bx+bw-80+(int)(barW*prog),topY+24,0xFF4466CC);
    }

    // ══════════════════════════════════════════════
    //  游戏结束
    // ══════════════════════════════════════════════
    void drawGameOver(GuiGraphics g){
        g.fill(0,0,width,height,0x99000000);
        int ww=340,wh=160,wx=(width-ww)/2,wy=(height-wh)/2;
        g.fill(wx,wy,wx+ww,wy+wh,0xFF1A1200);
        for(int i=0;i<3;i++){
            g.fill(wx+i,wy+i,wx+ww-i,wy+i+1,0xFFFFAA00-i*0x001100);
            g.fill(wx+i,wy+wh-i-1,wx+ww-i,wy+wh-i,0xFFFFAA00);
        }
        g.drawCenteredString(font,"── 对  局  结  束 ──",width/2,wy+18,0xFFFFDD44);
        g.drawCenteredString(font,resultMsg,width/2,wy+40,0xFFFFFFFF);
        int btnY=wy+72;
        g.fill(wx+20,btnY,wx+ww/2-8,btnY+24,0xFF1A3320);
        g.fill(wx+20,btnY,wx+ww/2-8,btnY+1,0xFF44AA44);
        g.drawCenteredString(font,"R — 新对局",wx+ww/4+6,btnY+8,0xFF88FF88);
        g.fill(wx+ww/2+8,btnY,wx+ww-20,btnY+24,0xFF1A1A40);
        g.fill(wx+ww/2+8,btnY,wx+ww-20,btnY+1,0xFF4444AA);
        g.drawCenteredString(font,"ESC — 返回菜单",wx+ww*3/4-6,btnY+8,0xFF8888FF);
        g.drawCenteredString(font,"再次感谢您的对弈！",width/2,wy+112,0xFF886644);
    }

    void drawExitConfirm(GuiGraphics g, int mx, int my){
        g.fill(0,0,width,height,0xAA000000);
        int cx=width/2, cy=height/2;
        int ww=240, wh=90;
        int wx=cx-ww/2, wy=cy-wh/2;
        g.fill(wx,wy,wx+ww,wy+wh,0xFF1A1A2E);
        g.fill(wx,wy,wx+ww,wy+1,0xFFFFAA00);
        g.fill(wx,wy+wh-1,wx+ww,wy+wh,0xFFFFAA00);
        g.fill(wx,wy,wx+1,wy+wh,0xFFFFAA00);
        g.fill(wx+ww-1,wy,wx+ww,wy+wh,0xFFFFAA00);
        g.drawCenteredString(font,"确定要退出当前对局吗？",cx,wy+16,0xFFFFDD44);
        // 确认退出按钮
        boolean h1=inBtn(mx,my,cx-105,cy+10,96,24);
        g.fill(cx-105,cy+10,cx-9,cy+34,h1?0xFF553322:0xFF331A10);
        g.fill(cx-105,cy+10,cx-9,cy+11,0xFFFF4444);
        g.drawCenteredString(font,"确认退出",cx-57,cy+18,h1?0xFFFF6644:0xFFCC4444);
        // 继续游戏按钮
        boolean h2=inBtn(mx,my,cx+9,cy+10,96,24);
        g.fill(cx+9,cy+10,cx+105,cy+34,h2?0xFF224422:0xFF112211);
        g.fill(cx+9,cy+10,cx+105,cy+11,0xFF44CC44);
        g.drawCenteredString(font,"继续游戏",cx+57,cy+18,h2?0xFF66FF66:0xFF44AA44);
        g.drawCenteredString(font,"再按 ESC 取消",cx,wy+wh-14,0xFF666666);
    }

    // ══════════════════════════════════════════════
    //  输入
    // ══════════════════════════════════════════════
    @Override
    public boolean mouseClicked(double mx,double my,int btn){
        if(showExitConfirm){
            int cx=width/2, cy=height/2;
            if(inBtn((int)mx,(int)my,cx-105,cy+10,96,24)){
                showExitConfirm=false; gameMode=GameMode.MENU;
                if(aiThread!=null)aiThread.interrupt(); aiThinking.set(false);
                return true;
            }
            if(inBtn((int)mx,(int)my,cx+9,cy+10,96,24)){
                showExitConfirm=false; return true;
            }
            return true;
        }
        if(gameMode==GameMode.MENU){
            int cx=width/2,cy=height/2;
            // LAN 模式：跳过菜单，直接进入 PVP（HOST=红，CLIENT=黑）
            if (lanMode != LAN_NONE) { startGame(GameMode.PVP); return true; }
            // 模式按钮
            if(inBtn((int)mx,(int)my,cx-130,cy-64,120,26)){startGame(GameMode.PVP);return true;}
            if(inBtn((int)mx,(int)my,cx+10, cy-64,120,26)){startGame(GameMode.PVA);return true;}
            // 难度按钮
            Difficulty[] dv=Difficulty.values();
            for(int i=0;i<3;i++)
                if(inBtn((int)mx,(int)my,cx-110+i*76,cy-12,68,22)){difficulty=dv[i];return true;}
            return super.mouseClicked(mx,my,btn);
        }
        if(gameOver) return super.mouseClicked(mx,my,btn);
        // AI轮到时禁止操作
        if(gameMode==GameMode.PVA&&!redTurn) return true;
        if(aiThinking.get()) return true;
        // LAN 模式：HOST 执红，CLIENT 执黑，非自己回合不能点击
        if (lanMode == LAN_HOST   && !redTurn)  return true;
        if (lanMode == LAN_CLIENT &&  redTurn)  return true;

        int col=snapCol((int)mx),row=snapRow((int)my);
        if(col<0||col>=COLS||row<0||row>=ROWS) return super.mouseClicked(mx,my,btn);
        int px2=bx+col*CELL,py2=by+row*CELL;
        if(Math.abs(mx-px2)>CELL*0.55||Math.abs(my-py2)>CELL*0.55) return super.mouseClicked(mx,my,btn);
        handleClick(col,row);
        return true;
    }

    void handleClick(int col,int row){
        int piece=board[col][row];
        boolean own=(redTurn&&piece>0)||(!redTurn&&piece<0);
        if(selCol>=0&&isLegal(col,row)){
            doMove(selCol,selRow,col,row);
            selCol=selRow=-1; legalMoves.clear();
            return;
        }
        if(own){selCol=col;selRow=row;legalMoves=computeLegal(col,row);}
        else{selCol=selRow=-1;legalMoves.clear();}
    }

    boolean isLegal(int c,int r){
        for(int[] m:legalMoves) if(m[0]==c&&m[1]==r) return true;
        return false;
    }

    @Override
    public boolean keyPressed(int key,int scan,int mods){
        if(key==GLFW.GLFW_KEY_ESCAPE){
            if(showExitConfirm){ showExitConfirm=false; return true; }
            if(gameMode==GameMode.MENU) Minecraft.getInstance().setScreen(new GameSelectorScreen());
            else{ showExitConfirm=true; }
            return true;
        }
        if(showExitConfirm) return true;
        if(key==GLFW.GLFW_KEY_R){
            if(gameOver){ resetBoard(); return true; }
            undoMove(); return true;
        }
        return super.keyPressed(key,scan,mods);
    }

    // ══════════════════════════════════════════════
    //  移动执行 & 悔棋
    // ══════════════════════════════════════════════
    void doMove(int fc,int fr,int tc,int tr){
        // 压栈（最多2步）
        if(undoCount<2){
            undoBoards[undoCount]=deepCopy(board);
            undoRedTurns[undoCount]=redTurn;
            undoCount++;
        } else {
            undoBoards[0]=undoBoards[1];
            undoRedTurns[0]=undoRedTurns[1];
            undoBoards[1]=deepCopy(board);
            undoRedTurns[1]=redTurn;
        }
        board[tc][tr]=board[fc][fr];
        board[fc][fr]=0;
        lastFC=fc;lastFR=fr;lastTC=tc;lastTR=tr;
        // LAN 模式：我方走完后发给对方（远程收到的走法不再回传，防止回音）
        if (lanMode != LAN_NONE && !receivingRemoteMove) sendLanMove(fc, fr, tc, tr);
        redTurn=!redTurn;
        redInCheck=isInCheck(true);
        blackInCheck=isInCheck(false);
        if(isCheckmate(redTurn)){
            gameOver=true;
            if(gameMode==GameMode.PVA)
                resultMsg=(redTurn?"AI胜利！玩家被将死。":"玩家胜利！AI被将死。");
            else if(lanMode==LAN_HOST)
                resultMsg=(redTurn?"黑方（对手）胜利！":"红方（你）胜利！");
            else if(lanMode==LAN_CLIENT)
                resultMsg=(redTurn?"黑方（你）胜利！":"红方（对手）胜利！");
            else
                resultMsg=(redTurn?"黑":"红")+"方胜利！"+(redTurn?"红":"黑")+"方被将死！";
        } else if(isStalemate(redTurn)){
            gameOver=true;
            if(gameMode==GameMode.PVA)
                resultMsg=(redTurn?"AI胜利！玩家无子可动。":"玩家胜利！AI无子可动。");
            else if(lanMode==LAN_HOST)
                resultMsg=(redTurn?"黑方（对手）胜！":"红方（你）胜！");
            else if(lanMode==LAN_CLIENT)
                resultMsg=(redTurn?"黑方（你）胜！":"红方（对手）胜！");
            else
                resultMsg=(redTurn?"红":"黑")+"方无子可动，"+(redTurn?"黑":"红")+"方胜！";
        }
    }

    void undoMove(){
        if(undoCount==0) return;
        // 人机模式：悔2步（撤回AI和玩家各1步）
        int steps = gameMode==GameMode.PVA ? Math.min(2,undoCount) : 1;
        undoCount=Math.max(0,undoCount-steps);
        board=deepCopy(undoBoards[undoCount]);
        redTurn=undoRedTurns[undoCount];
        undoBoards[undoCount]=null;
        selCol=selRow=-1; legalMoves.clear();
        redInCheck=isInCheck(true); blackInCheck=isInCheck(false);
        gameOver=false; resultMsg="";
        lastFC=lastFR=lastTC=lastTR=-1;
        aiPendingMove=null;
        if(aiThread!=null) aiThread.interrupt();
        aiThinking.set(false);
    }

    // ══════════════════════════════════════════════
    //  合法性计算
    // ══════════════════════════════════════════════
    List<int[]> computeLegal(int col,int row){
        List<int[]> pseudo=pseudoMoves(board,col,row);
        List<int[]> legal=new ArrayList<>();
        boolean isRed=board[col][row]>0;
        for(int[] mv:pseudo){
            // make/unmake 替代 deepCopy，避免大量临时数组
            int captured=board[mv[0]][mv[1]];
            int piece=board[col][row];
            board[mv[0]][mv[1]]=piece; board[col][row]=0;
            if(!inCheckOnBoard(board,isRed)) legal.add(mv);
            board[col][row]=piece; board[mv[0]][mv[1]]=captured;
        }
        return legal;
    }

    List<int[]> pseudoMoves(int[][] b,int col,int row){
        int p=b[col][row]; if(p==0) return new ArrayList<>();
        boolean red=p>0; int abs=Math.abs(p);
        List<int[]> m=new ArrayList<>();
        switch(abs){
            case GENERAL ->  generalMoves(b,col,row,red,m);
            case ADVISOR ->  advisorMoves(b,col,row,red,m);
            case ELEPHANT->  elephantMoves(b,col,row,red,m);
            case HORSE ->    horseMoves(b,col,row,red,m);
            case CHARIOT ->  chariotMoves(b,col,row,red,m);
            case CANNON ->   cannonMoves(b,col,row,red,m);
            case SOLDIER ->  soldierMoves(b,col,row,red,m);
        }
        return m;
    }

    void tryAdd(int[][] b,List<int[]> m,int c,int r,boolean red){
        if(c<0||c>=COLS||r<0||r>=ROWS) return;
        int t=b[c][r];
        if(t==0||(red&&t<0)||(!red&&t>0)) m.add(new int[]{c,r});
    }

    void generalMoves(int[][] b,int c,int r,boolean red,List<int[]> m){
        for(int[] d:DIR_ORTHO){
            int nc=c+d[0],nr=r+d[1];
            if(inPalace(nc,nr,red)) tryAdd(b,m,nc,nr,red);
        }
    }
    void advisorMoves(int[][] b,int c,int r,boolean red,List<int[]> m){
        for(int[] d:DIR_DIAG){
            int nc=c+d[0],nr=r+d[1];
            if(inPalace(nc,nr,red)) tryAdd(b,m,nc,nr,red);
        }
    }
    void elephantMoves(int[][] b,int c,int r,boolean red,List<int[]> m){
        for(int[] d:DIR_ELEPHANT){
            int nc=c+d[0],nr=r+d[1];
            if(nc<0||nc>=COLS||nr<0||nr>=ROWS) continue;
            if(red&&nr<5) continue;
            if(!red&&nr>4) continue;
            int mc=c+d[0]/2,mr=r+d[1]/2;
            if(b[mc][mr]!=0) continue;
            tryAdd(b,m,nc,nr,red);
        }
    }
    void horseMoves(int[][] b,int c,int r,boolean red,List<int[]> m){
        for(int i=0;i<4;i++){
            int lc=c+HORSE_LEGS[i][0],lr=r+HORSE_LEGS[i][1];
            if(lc<0||lc>=COLS||lr<0||lr>=ROWS) continue;
            if(b[lc][lr]!=0) continue;
            for(int[] d:HORSE_DEST[i]) tryAdd(b,m,c+d[0],r+d[1],red);
        }
    }
    void chariotMoves(int[][] b,int c,int r,boolean red,List<int[]> m){
        for(int[] d:DIR_ORTHO){
            for(int i=1;i<10;i++){
                int nc=c+d[0]*i,nr=r+d[1]*i;
                if(nc<0||nc>=COLS||nr<0||nr>=ROWS) break;
                int t=b[nc][nr];
                if(t==0){m.add(new int[]{nc,nr});continue;}
                if((red&&t<0)||(!red&&t>0)) m.add(new int[]{nc,nr});
                break;
            }
        }
    }
    void cannonMoves(int[][] b,int c,int r,boolean red,List<int[]> m){
        for(int[] d:DIR_ORTHO){
            boolean jumped=false;
            for(int i=1;i<10;i++){
                int nc=c+d[0]*i,nr=r+d[1]*i;
                if(nc<0||nc>=COLS||nr<0||nr>=ROWS) break;
                int t=b[nc][nr];
                if(!jumped){ if(t==0) m.add(new int[]{nc,nr}); else jumped=true; }
                else { if(t!=0){ if((red&&t<0)||(!red&&t>0)) m.add(new int[]{nc,nr}); break; } }
            }
        }
    }
    void soldierMoves(int[][] b,int c,int r,boolean red,List<int[]> m){
        int fwd=red?-1:1;
        boolean crossed=red?(r<5):(r>4);
        tryAdd(b,m,c,r+fwd,red);
        if(crossed){ tryAdd(b,m,c+1,r,red); tryAdd(b,m,c-1,r,red); }
    }

    boolean inPalace(int c,int r,boolean red){
        if(c<3||c>5) return false;
        return red?(r>=7&&r<=9):(r>=0&&r<=2);
    }

    boolean isInCheck(boolean isRed){ return inCheckOnBoard(board,isRed); }

    boolean inCheckOnBoard(int[][] b,boolean isRed){
        int gc=-1,gr=-1;
        outer:for(int c=0;c<COLS;c++) for(int r=0;r<ROWS;r++)
            if(b[c][r]==(isRed?GENERAL:-GENERAL)){gc=c;gr=r;break outer;}
        if(gc<0) return true;
        for(int c=0;c<COLS;c++) for(int r=0;r<ROWS;r++){
            int p=b[c][r]; if(p==0) continue;
            if((p>0)==isRed) continue;
            for(int[] a:pseudoMoves(b,c,r)) if(a[0]==gc&&a[1]==gr) return true;
        }
        // 飞将
        if(gc>=3&&gc<=5){
            for(int r=0;r<ROWS;r++){
                if(b[gc][r]==(isRed?-GENERAL:GENERAL)){
                    int r1=Math.min(gr,r)+1,r2=Math.max(gr,r);
                    boolean clear=true;
                    for(int rr=r1;rr<r2;rr++) if(b[gc][rr]!=0){clear=false;break;}
                    if(clear) return true;
                }
            }
        }
        return false;
    }

    boolean isCheckmate(boolean isRed){
        for(int c=0;c<COLS;c++) for(int r=0;r<ROWS;r++){
            int p=board[c][r];
            if((isRed&&p>0)||(!isRed&&p<0)) if(!computeLegal(c,r).isEmpty()) return false;
        }
        return true;
    }
    boolean isStalemate(boolean isRed){
        if(isInCheck(isRed)) return false;
        return isCheckmate(isRed);
    }

    // ══════════════════════════════════════════════
    //  AI 引擎 — 负极大值 + α-β 剪枝
    // ══════════════════════════════════════════════

    /** 生成所有棋子的所有伪合法落子，已按价值粗排序（吃子优先） */
    List<int[]> allPseudoMoves(int[][] b, boolean red){
        List<int[]> caps=new ArrayList<>(), quiets=new ArrayList<>();
        for(int c=0;c<COLS;c++) for(int r=0;r<ROWS;r++){
            int p=b[c][r];
            if((red&&p>0)||(!red&&p<0)){
                for(int[] mv:pseudoMoves(b,c,r)){
                    int[] full={c,r,mv[0],mv[1]};
                    if(b[mv[0]][mv[1]]!=0) caps.add(full);
                    else quiets.add(full);
                }
            }
        }
        caps.addAll(quiets);
        return caps;
    }

    /** 静态局面估值（从红方角度：正=红优，负=黑优） */
    int evaluate(int[][] b){
        int score=0;
        for(int c=0;c<COLS;c++) for(int r=0;r<ROWS;r++){
            int p=b[c][r]; if(p==0) continue;
            boolean red=p>0; int abs=Math.abs(p);
            int base=PIECE_VAL[abs];
            int pst=pstBonus(abs,c,r,red);
            score += red?(base+pst):-(base+pst);
        }
        return score;
    }

    /** 棋子位置额外得分（从该方视角） */
    int pstBonus(int abs,int col,int row,boolean red){
        // 红方行号镜像
        int r=red?(9-row):row;
        return switch(abs){
            case GENERAL  -> 0;
            case HORSE    -> colRow(PST_HORSE,  col,r);
            case CHARIOT  -> colRow(PST_CHARIOT,col,r);
            case CANNON   -> colRow(PST_CANNON, col,r);
            case SOLDIER  -> colRow(PST_SOLDIER,col,r);
            default       -> 0;
        };
    }

    int colRow(int[][] t,int c,int r){
        if(c<0||c>=t.length||r<0||r>=t[0].length) return 0;
        return t[c][r];
    }

    /**
     * 负极大值搜索 + α-β 剪枝
     * @param b     当前棋盘
     * @param isRed 当前走子方是否为红方
     * @param depth 剩余搜索深度
     * @param alpha α（当前走子方的最低保证）
     * @param beta  β（对手的最高接受）
     * @return 当前走子方视角的局面分
     */
    int negamax(int[][] b,boolean isRed,int depth,int alpha,int beta){
        if(Thread.currentThread().isInterrupted()) return 0;
        if(depth==0) return isRed?evaluate(b):-evaluate(b);

        List<int[]> moves=allPseudoMoves(b,isRed);
        if(moves.isEmpty()) return -INF+1; // 无子可动（将死或困毙）

        int best=-INF;
        for(int[] mv:moves){
            // make move（原地修改，避免 deepCopy 产生大量垃圾对象）
            int captured=b[mv[2]][mv[3]];
            int piece=b[mv[0]][mv[1]];
            b[mv[2]][mv[3]]=piece; b[mv[0]][mv[1]]=0;
            if(inCheckOnBoard(b,isRed)){
                // unmake
                b[mv[0]][mv[1]]=piece; b[mv[2]][mv[3]]=captured;
                continue; // 走后自将，跳过
            }
            int val=-negamax(b,!isRed,depth-1,-beta,-alpha);
            // unmake
            b[mv[0]][mv[1]]=piece; b[mv[2]][mv[3]]=captured;
            if(val>best) best=val;
            if(val>alpha) alpha=val;
            if(alpha>=beta) break; // β 剪枝
        }
        return best;
    }

    /** 根节点搜索，返回最佳落子 {fc,fr,tc,tr} */
    int[] aiBestMove(int[][] b,boolean isRed,int depth){
        List<int[]> moves=allPseudoMoves(b,isRed);
        int best=-INF; int[] bestMv=null;
        for(int[] mv:moves){
            if(Thread.currentThread().isInterrupted()) break;
            // make/unmake 替代 deepCopy
            int captured=b[mv[2]][mv[3]];
            int piece=b[mv[0]][mv[1]];
            b[mv[2]][mv[3]]=piece; b[mv[0]][mv[1]]=0;
            if(inCheckOnBoard(b,isRed)){
                b[mv[0]][mv[1]]=piece; b[mv[2]][mv[3]]=captured;
                continue;
            }
            int val=-negamax(b,!isRed,depth-1,-INF,INF);
            b[mv[0]][mv[1]]=piece; b[mv[2]][mv[3]]=captured;
            if(val>best||bestMv==null){best=val;bestMv=mv;}
        }
        return bestMv;
    }

    // ══════════════════════════════════════════════
    //  绘图工具
    // ══════════════════════════════════════════════
    /** 圆形填充缓存：避免每帧对每个棋子重复计算 Math.sqrt */
    private final java.util.Map<Integer,int[]> circleCache = new java.util.HashMap<>();
    /** 圆环缓存：避免每帧重复计算 cos/sin */
    private final java.util.Map<Integer,int[]> ringCache = new java.util.HashMap<>();

    void fillRect(GuiGraphics g,int cx,int cy,int half,int color){
        g.fill(cx-half,cy-half,cx+half+1,cy+half+1,color);
    }
    void fillCircle(GuiGraphics g,int cx,int cy,int r,int color){
        int[] dxs = circleCache.computeIfAbsent(r, rad -> {
            int[] arr=new int[rad*2+1];
            for(int dy=-rad;dy<=rad;dy++) arr[dy+rad]=(int)Math.sqrt((double)rad*rad-dy*dy);
            return arr;
        });
        for(int dy=-r;dy<=r;dy++){
            int dx=dxs[dy+r];
            g.fill(cx-dx,cy+dy,cx+dx+1,cy+dy+1,color);
        }
    }
    void drawCircleRing(GuiGraphics g,int cx,int cy,int r,int color){
        int[] pts = ringCache.computeIfAbsent(r, rad -> {
            int[] arr=new int[72*2];
            for(int i=0;i<72;i++){
                double a=Math.toRadians(i*5);
                arr[i*2]=(int)(rad*Math.cos(a));
                arr[i*2+1]=(int)(rad*Math.sin(a));
            }
            return arr;
        });
        for(int i=0;i<72;i++){
            g.fill(cx+pts[i*2],cy+pts[i*2+1],cx+pts[i*2]+1,cy+pts[i*2+1]+1,color);
        }
    }
    void drawLine(GuiGraphics g,int x1,int y1,int x2,int y2,int color){
        int steps=Math.max(Math.abs(x2-x1),Math.abs(y2-y1));
        if(steps==0){g.fill(x1,y1,x1+1,y1+1,color);return;}
        for(int i=0;i<=steps;i++){
            int x=x1+(x2-x1)*i/steps,y=y1+(y2-y1)*i/steps;
            g.fill(x,y,x+1,y+1,color);
        }
    }
    int snapCol(int mx){return Math.round((float)(mx-bx)/CELL);}
    int snapRow(int my){return Math.round((float)(my-by)/CELL);}
    int[][] deepCopy(int[][] src){
        int[][] d=new int[COLS][ROWS];
        for(int i=0;i<COLS;i++) d[i]=src[i].clone();
        return d;
    }

    @Override public boolean isPauseScreen(){return false;}
}