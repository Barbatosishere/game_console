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
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;

/**
 * 国际象棋
 *
 * 修复记录：
 * 1. AI颜色Bug：原 findBestMove(!whiteTurn) 在黑方回合(!whiteTurn=true)
 *    反而让AI给白方出招。修复：直接传 forWhite=false（AI执黑）。
 * 2. 卡顿：AI改为固定深度2 + 500ms硬超时；AI内部用伪合法走法
 *    （不对每个节点做完整copy+check_king，只在走后判断王是否被将）。
 * 3. 升变弹窗：兵到底线时弹出选择面板，不再强制升后。
 */
@OnlyIn(Dist.CLIENT)
public class WesternChessScreen extends Screen implements LanMultiplayerScreen {
    private static final Logger LOGGER = LoggerFactory.getLogger(WesternChessScreen.class);
    boolean showExitConfirm = false;

    // ─── 棋子 ─────────────────────────────────────────────────
    static final int E=0,WP=1,WN=2,WB=3,WR=4,WQ=5,WK=6;
    static final int BP=-1,BN=-2,BB=-3,BR=-4,BQ=-5,BK=-6;
    static final String[] PIECE_LABEL = {"","P","N","B","R","Q","K"};
    static final int[]    PIECE_VALUE = {0,100,320,330,500,900,20000};
    static final int SP_NORMAL=0,SP_CASTLE_K=1,SP_CASTLE_Q=2,SP_EN_PASSANT=3,SP_PROMOTE=4;

    // ─── 位置奖励表（白方视角，黑方镜像row） ─────────────────
    static final int[][] PT_P  = {{0,0,0,0,0,0,0,0},{50,50,50,50,50,50,50,50},{10,10,20,30,30,20,10,10},{5,5,10,25,25,10,5,5},{0,0,0,20,20,0,0,0},{5,-5,-10,0,0,-10,-5,5},{5,10,10,-20,-20,10,10,5},{0,0,0,0,0,0,0,0}};
    static final int[][] PT_N  = {{-50,-40,-30,-30,-30,-30,-40,-50},{-40,-20,0,0,0,0,-20,-40},{-30,0,10,15,15,10,0,-30},{-30,5,15,20,20,15,5,-30},{-30,0,15,20,20,15,0,-30},{-30,5,10,15,15,10,5,-30},{-40,-20,0,5,5,0,-20,-40},{-50,-40,-30,-30,-30,-30,-40,-50}};
    static final int[][] PT_B  = {{-20,-10,-10,-10,-10,-10,-10,-20},{-10,0,0,0,0,0,0,-10},{-10,0,5,10,10,5,0,-10},{-10,5,5,10,10,5,5,-10},{-10,0,10,10,10,10,0,-10},{-10,10,10,10,10,10,10,-10},{-10,5,0,0,0,0,5,-10},{-20,-10,-10,-10,-10,-10,-10,-20}};
    static final int[][] PT_R  = {{0,0,0,0,0,0,0,0},{5,10,10,10,10,10,10,5},{-5,0,0,0,0,0,0,-5},{-5,0,0,0,0,0,0,-5},{-5,0,0,0,0,0,0,-5},{-5,0,0,0,0,0,0,-5},{-5,0,0,0,0,0,0,-5},{0,0,0,5,5,0,0,0}};
    static final int[][] PT_Q  = {{-20,-10,-10,-5,-5,-10,-10,-20},{-10,0,0,0,0,0,0,-10},{-10,0,5,5,5,5,0,-10},{-5,0,5,5,5,5,0,-5},{0,0,5,5,5,5,0,-5},{-10,5,5,5,5,5,0,-10},{-10,0,5,0,0,0,0,-10},{-20,-10,-10,-5,-5,-10,-10,-20}};
    static final int[][] PT_K  = {{-30,-40,-40,-50,-50,-40,-40,-30},{-30,-40,-40,-50,-50,-40,-40,-30},{-30,-40,-40,-50,-50,-40,-40,-30},{-30,-40,-40,-50,-50,-40,-40,-30},{-20,-30,-30,-40,-40,-30,-30,-20},{-10,-20,-20,-20,-20,-20,-20,-10},{20,20,0,0,0,0,20,20},{20,30,10,0,0,10,30,20}};

    // ─── 方向常量（避免走法生成和 isAttacked 中重复创建数组） ───
    static final int[][] DIR_BISHOP = {{1,1},{1,-1},{-1,1},{-1,-1}};
    static final int[][] DIR_ROOK   = {{1,0},{-1,0},{0,1},{0,-1}};
    static final int[][] DIR_KNIGHT = {{-2,-1},{-2,1},{-1,-2},{-1,2},{1,-2},{1,2},{2,-1},{2,1}};

    // ─── 状态 ─────────────────────────────────────────────────
    private enum S { MENU, PLAYING, OVER }
    private S state = S.MENU;
    private boolean vsAI = true;
    private int[][] board = new int[8][8];
    private boolean whiteTurn = true;
    private int[] selected = null;
    private List<int[]> validMoves = new ArrayList<>();
    private boolean wCK=true,wCQ=true,bCK=true,bCQ=true;
    private int[] epTarget = null;
    private int[] lastFrom=null, lastTo=null;
    private String resultMsg = "";
    private long tickN = 0;
    private int cellSize, bx, by;
    private final List<GameRenderHelper.Particle> particles = new ArrayList<>();
    private boolean aiThinking = false;
    private boolean inCheck = false;
    private boolean promoPending = false;
    private int promoRow, promoCol;

    // AI：固定深度2，硬超时500ms
    private static final int  AI_DEPTH = 2;
    private static final long AI_MS    = 500;
    private long aiT0;

    // LAN
    private int lanMode = LAN_NONE;
    private java.util.UUID remotePeer = null;
    /** LAN 升变走法：本地选完升变子后才携带最终子力类型发送，避免双端分叉 */
    private String pendingLanPromote = null;
    /** 防重复发送 LEAVE_GAME 标志 */
    private boolean lanLeaveSent = false;

    public WesternChessScreen() { super(Component.literal("国际象棋")); }
    public WesternChessScreen(boolean isHost, java.util.UUID remote) {
        super(Component.literal("国际象棋-联机"));
        lanMode = isHost ? LAN_HOST : LAN_CLIENT;
        remotePeer = remote; vsAI = false; initBoard();
    }
    @Override public java.util.UUID getLanPeer() { return remotePeer; }
    @Override public String getLanGameId()        { return "wchess"; }

    /**
     * 来源校验：仅接受已配对对端（服务端盖章 UUID）发来的走法，
     * 防止在线第三方伪造报文注入走法。
     */
    @Override
    public void onRemoteMove(java.util.UUID senderUuid, String data) {
        if (remotePeer == null || !remotePeer.equals(senderUuid)) {
            LOGGER.warn("[国际象棋] 丢弃来源非法的联机走法: sender={}，期望对端={}", senderUuid, remotePeer);
            return;
        }
        this.onRemoteMove(data);
    }

    /** 退出对局时向对端发送 LEAVE_GAME（带防重复标志，避免重复发包） */
    private void sendLeaveGameOnce() {
        if (lanMode == LAN_NONE || lanLeaveSent || remotePeer == null) return;
        lanLeaveSent = true;
        sendLeaveGame();
    }

    @Override
    public void onClose() {
        sendLeaveGameOnce();
        super.onClose();
    }

    @Override public void onRemoteMove(String data) {
        if ("RESTART".equals(data)) { initBoard(); return; }
        try { String[] p = data.split(",");
            int[] m = new int[]{Integer.parseInt(p[0]),Integer.parseInt(p[1]),Integer.parseInt(p[2]),Integer.parseInt(p[3]),Integer.parseInt(p[4])};
            if (m[4] == SP_PROMOTE) {
                // LAN 升变走法：报文携带最终升变子类型（第6字段，缺省兼容旧报文默认升后），
                // 接收方不弹升变面板、直接按报文完成升变，避免升变子由对手选择导致双端棋盘分叉
                int promoType = p.length > 5 ? Integer.parseInt(p[5]) : WQ;
                boolean w = board[m[0]][m[1]] > 0;
                int[][] ep = new int[1][]; boolean[] cf = {wCK,wCQ,bCK,bCQ};
                m[4] = SP_NORMAL;
                applyOn(board, m, cf, ep);
                board[m[2]][m[3]] = w ? promoType : -promoType;
                wCK=cf[0];wCQ=cf[1];bCK=cf[2];bCQ=cf[3]; epTarget=ep[0]!=null?ep[0]:null;
                lastFrom=new int[]{m[0],m[1]}; lastTo=new int[]{m[2],m[3]};
                whiteTurn=!whiteTurn; selected=null; validMoves.clear();
                inCheck=kingInCheck(board,whiteTurn);
                if (Minecraft.getInstance().player!=null)
                    Minecraft.getInstance().player.playSound(SoundEvents.WOOD_PLACE,0.5f,1.2f);
                checkEnd(); // 升变完成后再判定终局（修正原时机错误）
                return;
            }
            applyMove(m, true);
            checkEnd(); } catch (Exception ignored) {}
    }

    // ══════════════ 初始化 ══════════════
    private void initBoard() {
        board = new int[8][8];
        board[0] = new int[]{BR,BN,BB,BQ,BK,BB,BN,BR};
        for (int c=0;c<8;c++) board[1][c]=BP;
        for (int c=0;c<8;c++) board[6][c]=WP;
        board[7] = new int[]{WR,WN,WB,WQ,WK,WB,WN,WR};
        whiteTurn=true; selected=null; validMoves.clear();
        wCK=wCQ=bCK=bCQ=true; epTarget=null; lastFrom=lastTo=null;
        resultMsg=""; particles.clear(); aiThinking=false; inCheck=false;
        promoPending=false; pendingLanPromote=null; state=S.PLAYING;
    }

    // ══════════════ TICK ══════════════
    @Override public void tick() {
        tickN++;
        if (lanMode != LAN_NONE) return;
        // ★ 关键修复：AI执黑（forWhite=false），仅黑方回合才触发
        if (state==S.PLAYING && vsAI && !whiteTurn && !aiThinking && !promoPending) {
            aiThinking = true;
            new Thread(() -> {
                int[] best = findBestMove(false); // false = 为黑方找最优
                Minecraft.getInstance().execute(() -> {
                    if (best != null) applyMove(best, false);
                    aiThinking = false;
                    checkEnd();
                });
            }, "chess-ai").start();
        }
    }

    // ══════════════ 走法生成 ══════════════
    /** 完整合法走法（过滤走后王被将的情况） */
    private List<int[]> legalMoves(int[][] b, boolean fw) {
        List<int[]> result = new ArrayList<>();
        for (int[] mv : pseudoMoves(b, fw, epTarget)) {
            int[][] nb = copy(b); applyOn(nb, mv, null, null);
            if (!kingInCheck(nb, fw)) result.add(mv);
        }
        return result;
    }
    /** 伪合法走法（不检查走后将军）。ep 为该局面的吃过路兵目标格（AI搜索时传节点自身的目标，不能读字段） */
    private List<int[]> pseudoMoves(int[][] b, boolean fw, int[] ep) {
        List<int[]> m = new ArrayList<>();
        for (int r=0;r<8;r++) for (int c=0;c<8;c++) {
            int p = b[r][c];
            if (p==E || (fw ? p<0 : p>0)) continue;
            addMoves(b, r, c, fw, m, ep);
        }
        return m;
    }
    private void addMoves(int[][] b, int r, int c, boolean w, List<int[]> o, int[] ep) {
        switch (Math.abs(b[r][c])) {
            case 1 -> pawnMoves(b,r,c,w,o,ep);
            case 2 -> knightMoves(b,r,c,w,o);
            case 3 -> slideMoves(b,r,c,w,o,DIR_BISHOP);
            case 4 -> slideMoves(b,r,c,w,o,DIR_ROOK);
            case 5 -> { slideMoves(b,r,c,w,o,DIR_BISHOP); slideMoves(b,r,c,w,o,DIR_ROOK); }
            case 6 -> { kingMoves(b,r,c,w,o); castleMoves(b,r,c,w,o); }
        }
    }
    private void pawnMoves(int[][] b, int r, int c, boolean w, List<int[]> o, int[] ep) {
        int d=w?-1:1, sr=w?6:1, pr=w?0:7, nr=r+d;
        if (ok(nr,c) && b[nr][c]==E) {
            o.add(mv(r,c,nr,c,nr==pr?SP_PROMOTE:SP_NORMAL));
            if (r==sr && b[r+d*2][c]==E) o.add(mv(r,c,r+d*2,c,SP_NORMAL));
        }
        for (int dc : new int[]{-1,1}) { int nc=c+dc;
            if (!ok(nr,nc)) continue;
            if (w?b[nr][nc]<0:b[nr][nc]>0) o.add(mv(r,c,nr,nc,nr==pr?SP_PROMOTE:SP_NORMAL));
            if (ep!=null && ep[0]==nr && ep[1]==nc) o.add(mv(r,c,nr,nc,SP_EN_PASSANT));
        }
    }
    private void knightMoves(int[][] b, int r, int c, boolean w, List<int[]> o) {
        for (int[] d : DIR_KNIGHT) {
            int nr=r+d[0],nc=c+d[1];
            if (ok(nr,nc) && !friendly(b[nr][nc],w)) o.add(mv(r,c,nr,nc,SP_NORMAL));
        }
    }
    private void slideMoves(int[][] b, int r, int c, boolean w, List<int[]> o, int[][] dirs) {
        for (int[] d : dirs) { int nr=r+d[0],nc=c+d[1];
            while (ok(nr,nc)) {
                if (friendly(b[nr][nc],w)) break;
                o.add(mv(r,c,nr,nc,SP_NORMAL));
                if (b[nr][nc]!=E) break;
                nr+=d[0]; nc+=d[1];
            }
        }
    }
    private void kingMoves(int[][] b, int r, int c, boolean w, List<int[]> o) {
        for (int dr=-1;dr<=1;dr++) for (int dc=-1;dc<=1;dc++) {
            if (dr==0&&dc==0) continue;
            int nr=r+dr,nc=c+dc;
            if (ok(nr,nc) && !friendly(b[nr][nc],w)) o.add(mv(r,c,nr,nc,SP_NORMAL));
        }
    }
    private void castleMoves(int[][] b, int r, int c, boolean w, List<int[]> o) {
        if ((w&&r!=7)||(!w&&r!=0)||c!=4||kingInCheck(b,w)) return;
        int kr=w?WR:BR, cr=w?7:0;
        if ((w?wCK:bCK) && b[cr][7]==kr && b[cr][5]==E && b[cr][6]==E
                && !isAttacked(b,cr,5,!w) && !isAttacked(b,cr,6,!w))
            o.add(mv(r,c,cr,6,SP_CASTLE_K));
        if ((w?wCQ:bCQ) && b[cr][0]==kr && b[cr][1]==E && b[cr][2]==E && b[cr][3]==E
                && !isAttacked(b,cr,3,!w) && !isAttacked(b,cr,2,!w))
            o.add(mv(r,c,cr,2,SP_CASTLE_Q));
    }
    private int[] mv(int fr,int fc,int tr,int tc,int sp){return new int[]{fr,fc,tr,tc,sp};}

    // ══════════════ 执行走法 ══════════════
    private void applyOn(int[][] b, int[] m, boolean[] cf, int[][] ep) {
        int fr=m[0],fc=m[1],tr=m[2],tc=m[3],sp=m[4],p=b[fr][fc]; boolean w=p>0;
        b[tr][tc]=p; b[fr][fc]=E;
        if (ep!=null) ep[0]=null;
        switch (sp) {
            case SP_EN_PASSANT -> b[w?tr+1:tr-1][tc] = E;
            case SP_CASTLE_K   -> { b[tr][5]=b[tr][7]; b[tr][7]=E; }
            case SP_CASTLE_Q   -> { b[tr][3]=b[tr][0]; b[tr][0]=E; }
            case SP_PROMOTE    -> b[tr][tc] = w?WQ:BQ;
        }
        if (Math.abs(p)==1 && Math.abs(fr-tr)==2 && ep!=null) ep[0]=new int[]{(fr+tr)/2,fc};
        if (cf!=null) {
            if (Math.abs(p)==6) { if(w){cf[0]=false;cf[1]=false;}else{cf[2]=false;cf[3]=false;} }
            if (fr==7&&fc==7) cf[0]=false; if (fr==7&&fc==0) cf[1]=false;
            if (fr==0&&fc==7) cf[2]=false; if (fr==0&&fc==0) cf[3]=false;
        }
    }
    private void applyMove(int[] m, boolean sound) {
        boolean w = board[m[0]][m[1]]>0;
        int[][] ep = new int[1][]; boolean[] cf = {wCK,wCQ,bCK,bCQ};
        if (board[m[2]][m[3]]!=E || m[4]==SP_EN_PASSANT)
            GameRenderHelper.spawnParticles(particles, bx+m[3]*cellSize+cellSize/2, by+m[2]*cellSize+cellSize/2, 8, 0xFF6644);
        // 升变：先执行走法，再弹窗（仅玩家，AI自动选后）
        if (m[4]==SP_PROMOTE && !aiThinking) {
            m[4]=SP_NORMAL; applyOn(board,m,cf,ep);
            wCK=cf[0];wCQ=cf[1];bCK=cf[2];bCQ=cf[3]; epTarget=ep[0]!=null?ep[0]:null;
            lastFrom=new int[]{m[0],m[1]}; lastTo=new int[]{m[2],m[3]};
            promoPending=true; promoRow=m[2]; promoCol=m[3];
            selected=null; validMoves.clear(); return;
        }
        applyOn(board,m,cf,ep);
        wCK=cf[0];wCQ=cf[1];bCK=cf[2];bCQ=cf[3]; epTarget=ep[0]!=null?ep[0]:null;
        lastFrom=new int[]{m[0],m[1]}; lastTo=new int[]{m[2],m[3]};
        whiteTurn=!whiteTurn; selected=null; validMoves.clear();
        inCheck=kingInCheck(board,whiteTurn);
        if (sound && Minecraft.getInstance().player!=null)
            Minecraft.getInstance().player.playSound(SoundEvents.WOOD_PLACE,0.5f,1.2f);
    }
    private void completePromo(int t) {
        boolean w=(promoRow==0); board[promoRow][promoCol]=w?t:-t;
        promoPending=false; whiteTurn=!whiteTurn; inCheck=kingInCheck(board,whiteTurn);
        if (Minecraft.getInstance().player!=null)
            Minecraft.getInstance().player.playSound(SoundEvents.PLAYER_LEVELUP,0.5f,1.5f);
        // LAN：本地选完升变子后才发送走法报文，并把最终子力类型编码进报文（双端对称）
        if (pendingLanPromote != null) {
            sendMove(pendingLanPromote + "," + t);
            pendingLanPromote = null;
        }
        checkEnd();
    }
    private void checkEnd() {
        if (state!=S.PLAYING) return;
        if (legalMoves(board,whiteTurn).isEmpty()) {
            state=S.OVER;
            resultMsg = kingInCheck(board,whiteTurn) ? (whiteTurn?"黑方胜利！将死":"白方胜利！将死") : "僵局！平局";
            if (Minecraft.getInstance().player!=null) Minecraft.getInstance().player.playSound(SoundEvents.PLAYER_LEVELUP,1f,1f);
        }
    }

    // ══════════════ 辅助检测 ══════════════
    private boolean kingInCheck(int[][] b, boolean w) {
        int kr=-1,kc=-1;
        outer: for (int r=0;r<8;r++) for (int c=0;c<8;c++) if (b[r][c]==(w?WK:BK)){kr=r;kc=c;break outer;}
        return kr<0 || isAttacked(b,kr,kc,!w);
    }
    private boolean isAttacked(int[][] b, int r, int c, boolean byW) {
        // 马
        for (int[] d:DIR_KNIGHT)
            if (ok(r+d[0],c+d[1]) && b[r+d[0]][c+d[1]]==(byW?WN:BN)) return true;
        // 直线（车/后）
        for (int[] d:DIR_ROOK) {
            int nr=r+d[0],nc=c+d[1];
            while (ok(nr,nc)) { if (b[nr][nc]!=E){int p=b[nr][nc]; if(byW&&(p==WR||p==WQ)||!byW&&(p==BR||p==BQ)) return true; break;} nr+=d[0];nc+=d[1]; }
        }
        // 斜线（象/后）
        for (int[] d:DIR_BISHOP) {
            int nr=r+d[0],nc=c+d[1];
            while (ok(nr,nc)) { if (b[nr][nc]!=E){int p=b[nr][nc]; if(byW&&(p==WB||p==WQ)||!byW&&(p==BB||p==BQ)) return true; break;} nr+=d[0];nc+=d[1]; }
        }
        // 王
        for (int dr=-1;dr<=1;dr++) for (int dc=-1;dc<=1;dc++)
            if ((dr!=0||dc!=0) && ok(r+dr,c+dc) && b[r+dr][c+dc]==(byW?WK:BK)) return true;
        // 兵
        int pd=byW?1:-1;
        for (int dc:new int[]{-1,1}) if (ok(r+pd,c+dc)&&b[r+pd][c+dc]==(byW?WP:BP)) return true;
        return false;
    }
    private boolean friendly(int p, boolean w) { return w?p>0:p<0; }
    private boolean ok(int r, int c) { return r>=0&&r<8&&c>=0&&c<8; }
    private int[][] copy(int[][] b) { int[][] n=new int[8][8]; for(int i=0;i<8;i++) n[i]=b[i].clone(); return n; }

    // ══════════════ AI（深度2 + 500ms超时） ══════════════
    /** ★ forWhite=false → 为黑方找最优（分数越小越好） */
    private int[] findBestMove(boolean forWhite) {
        aiT0 = System.currentTimeMillis();
        List<int[]> moves = legalMoves(board, forWhite);
        if (moves.isEmpty()) return null;
        // MVV-LVA：吃高价值子优先
        moves.sort((a,b) -> Integer.compare(PIECE_VALUE[Math.abs(board[b[2]][b[3]])], PIECE_VALUE[Math.abs(board[a[2]][a[3]])]));
        int[] best = moves.get(0);
        int bestScore = forWhite ? Integer.MIN_VALUE : Integer.MAX_VALUE;
        boolean[] cf = {wCK,wCQ,bCK,bCQ};
        for (int[] mv : moves) {
            if (System.currentTimeMillis()-aiT0 > AI_MS) break; // 超时直接返回
            int[][] nb = copy(board); boolean[] ncf = cf.clone(); int[][] ep = {epTarget};
            applyOn(nb,mv,ncf,ep);
            int score = alphaBeta(nb, AI_DEPTH-1, Integer.MIN_VALUE, Integer.MAX_VALUE, !forWhite, ncf, ep[0]);
            if (forWhite && score>bestScore) { bestScore=score; best=mv; }
            if (!forWhite && score<bestScore) { bestScore=score; best=mv; }
        }
        return best;
    }
    /**
     * ★ AI内部用伪合法走法（不对每个节点做完整copy+legalMoves），
     *   大幅减少开销；只在走后用 kingInCheck 剔除非法（一次copy）。
     */
    private int alphaBeta(int[][] b, int depth, int alpha, int beta, boolean max, boolean[] cf, int[] ep) {
        if (System.currentTimeMillis()-aiT0 > AI_MS) return evalBoard(b);
        List<int[]> moves = pseudoMoves(b, max, ep); // ← 伪合法，快；ep用搜索节点自身的目标
        if (moves.isEmpty()) return kingInCheck(b,max) ? (max?-99999+depth:99999-depth) : 0;
        if (depth == 0) return evalBoard(b);
        // 简单排序：吃子优先
        moves.sort((a,bb) -> Integer.compare(PIECE_VALUE[Math.abs(b[bb[2]][bb[3]])], PIECE_VALUE[Math.abs(b[a[2]][a[3]])]));
        int best = max ? Integer.MIN_VALUE : Integer.MAX_VALUE;
        for (int[] mv : moves) {
            if (System.currentTimeMillis()-aiT0 > AI_MS) break;
            int[][] nb = copy(b); boolean[] ncf = cf!=null?cf.clone():new boolean[]{true,true,true,true}; int[][] epR = {ep};
            applyOn(nb,mv,ncf,epR);
            if (kingInCheck(nb,max)) continue; // 走后王被将 → 不合法
            int score = alphaBeta(nb, depth-1, alpha, beta, !max, ncf, epR[0]);
            if (max) { best=Math.max(best,score); alpha=Math.max(alpha,best); }
            else     { best=Math.min(best,score); beta =Math.min(beta, best); }
            if (beta<=alpha) break;
        }
        if (best==Integer.MIN_VALUE||best==Integer.MAX_VALUE) return evalBoard(b);
        return best;
    }
    private int evalBoard(int[][] b) {
        int s=0;
        for (int r=0;r<8;r++) for (int c=0;c<8;c++) {
            int p=b[r][c]; if(p==E) continue;
            boolean w=p>0; int abs=Math.abs(p), tr=w?r:7-r;
            int bonus = switch(abs){case 1->PT_P[tr][c];case 2->PT_N[tr][c];case 3->PT_B[tr][c];case 4->PT_R[tr][c];case 5->PT_Q[tr][c];case 6->PT_K[tr][c];default->0;};
            s += w?(PIECE_VALUE[abs]+bonus):-(PIECE_VALUE[abs]+bonus);
        }
        return s;
    }

    // ══════════════ 输入 ══════════════
    @Override
    public boolean mouseClicked(double mx, double my, int btn) {
        if (showExitConfirm) { int click = GameRenderHelper.getExitConfirmClick(mx, my, width, height); if (click == 1) { showExitConfirm = false; sendLeaveGameOnce(); Minecraft.getInstance().setScreen(new GameSelectorScreen()); return true; } if (click == 2) { showExitConfirm = false; return true; } return true; }
        if (state==S.MENU) {
            if (lanMode!=LAN_NONE) return true;
            int cx=width/2, cy=height/2;
            if (mx>=cx-104&&mx<=cx-4&&my>=cy+30&&my<=cy+52) { vsAI=true; initBoard(); return true; }
            if (mx>=cx+4&&mx<=cx+104&&my>=cy+30&&my<=cy+52) { vsAI=false; initBoard(); return true; }
            return true;
        }
        if (state==S.OVER) {
            int cx2=width/2, cy2=height/2;
            if (mx>=cx2-70&&mx<=cx2+70&&my>=cy2+22&&my<=cy2+40) { initBoard(); return true; }
            if (mx>=cx2-70&&mx<=cx2+70&&my>=cy2+44&&my<=cy2+62) { sendLeaveGameOnce(); Minecraft.getInstance().setScreen(new GameSelectorScreen()); return true; }
            return super.mouseClicked(mx,my,btn);
        }
        if (promoPending) { handlePromoClick((int)mx,(int)my); return true; }
        int col=((int)mx-bx)/cellSize, row=((int)my-by)/cellSize;
        if (col<0||col>=8||row<0||row>=8) return super.mouseClicked(mx,my,btn);
        // 禁止非己方操作
        if (vsAI && (!whiteTurn||aiThinking)) return true;
        if (lanMode==LAN_HOST   && !whiteTurn) return true;
        if (lanMode==LAN_CLIENT &&  whiteTurn) return true;
        int piece = board[row][col];
        // 尝试走子
        if (selected!=null) {
            for (int[] m : validMoves) if (m[2]==row&&m[3]==col) {
                // 修复：先组装联机报文再 applyMove —— 升变走法的 m[4] 会在 applyMove 内被改写为 SP_NORMAL，
                // 若发送在改写之后，对端将永远收不到升变标记
                int lanSp = m[4];
                String lanData = lanMode != LAN_NONE ? (m[0]+","+m[1]+","+m[2]+","+m[3]+","+m[4]) : null;
                applyMove(m,true);
                if (lanData != null) {
                    if (lanSp == SP_PROMOTE) {
                        // 升变走法延迟到本地选完升变子后再发送（见 completePromo），报文携带最终子力类型
                        pendingLanPromote = lanData;
                    } else {
                        sendMove(lanData);
                    }
                }
                if (!promoPending) checkEnd();
                return true;
            }
        }
        // 选择己方棋子
        boolean mine;
        if      (lanMode==LAN_HOST)   mine=piece>0;
        else if (lanMode==LAN_CLIENT) mine=piece<0;
        else if (vsAI)                mine=piece>0;
        else                          mine=whiteTurn?piece>0:piece<0;
        if (piece!=E && mine) {
            selected=new int[]{row,col};
            validMoves=legalMoves(board,whiteTurn);
            validMoves.removeIf(m->m[0]!=row||m[1]!=col);
        } else { selected=null; validMoves.clear(); }
        return true;
    }
    private void handlePromoClick(int mx, int my) {
        int cx=width/2, cy=height/2;
        int pw=cellSize*4+20, px=cx-pw/2, py=cy-30;
        int[] types={WQ,WR,WB,WN};
        for (int i=0;i<4;i++) { int sx=px+10+i*(cellSize+4), sy=py+22;
            // 修复：不再在此处取反颜色，completePromo 会根据 promoRow 决定正负号（原两处各取反一次，黑兵升变会变成白子）
            if (mx>=sx&&mx<sx+cellSize&&my>=sy&&my<sy+cellSize) { completePromo(types[i]); return; }
        }
    }
    @Override
    public boolean keyPressed(int k, int sc, int mod) {
        if (k==GLFW.GLFW_KEY_ESCAPE) {
            if (showExitConfirm) { showExitConfirm = false; return true; }
            if (lanMode!=LAN_NONE||state==S.MENU) { sendLeaveGameOnce(); Minecraft.getInstance().setScreen(new GameSelectorScreen()); }
            else showExitConfirm = true;
            return true;
        }
        if (showExitConfirm) return true;
        if (k==GLFW.GLFW_KEY_R) {
            if (lanMode!=LAN_CLIENT) { initBoard(); if (lanMode==LAN_HOST) sendMove("RESTART"); }
            return true;
        }
        return true;
    }

    // ══════════════ 渲染 ══════════════
    @Override
    public void render(GuiGraphics g, int mx, int my, float pt) {
        cellSize = Math.max(20, Math.min((width-80)/8,(height-60)/8));
        bx=(width-cellSize*8)/2; by=(height-cellSize*8)/2;
        GameRenderHelper.fillDarkBackground(g,width,height);
        switch (state) {
            case MENU    -> renderMenu(g,mx,my);
            case PLAYING -> renderBoard(g,mx,my);
            case OVER    -> { renderBoard(g,mx,my); renderOver(g,mx,my); }
        }
        if (showExitConfirm) GameRenderHelper.drawExitConfirmOverlay(g, font, width, height, mx, my);
    }
    private void renderMenu(GuiGraphics g, int mx, int my) {
        int cx=width/2, cy=height/2;
        GameRenderHelper.renderDecorativeLines(g,width,height,tickN,0x220022);
        GameRenderHelper.drawShadowedCenteredText(g,font,"国 际 象 棋",cx,cy-70,0xFFFFFF,2);
        g.drawCenteredString(font,"Western Chess",cx,cy-50,0x555555);
        GameRenderHelper.drawDivider(g,cx-80,cy-38,160,0xFF888888,0xFF444444);
        g.drawCenteredString(font,"点击棋子选中，再点目标格走棋",cx,cy-18,0xAAAAAA);
        for (int i=0;i<4;i++) for (int j=0;j<4;j++) { boolean l=(i+j)%2==0; g.fill(cx-32+i*16,cy-2+j*16,cx-16+i*16,cy+14+j*16,l?0xFFCCBB99:0xFF886644); }
        GameRenderHelper.drawPrimaryButton(g,font,"🤖 玩家 vs AI",cx-104,cy+30,100,22,mx,my);
        GameRenderHelper.drawSecondaryButton(g,font,"👥 本地双人",cx+4,cy+30,100,22,mx,my);
        GameRenderHelper.drawBottomBar(g,font,width,height,"ESC 返回  R 重开");
    }
    private void renderBoard(GuiGraphics g, int mx, int my) {
        g.fill(bx-4,by-4,bx+cellSize*8+4,by+cellSize*8+4,0xFF3A2A10);
        g.fill(bx-2,by-2,bx+cellSize*8+2,by+cellSize*8+2,0xFFAA8855);
        for (int r=0;r<8;r++) for (int c=0;c<8;c++) {
            int sx=bx+c*cellSize, sy=by+r*cellSize; boolean light=(r+c)%2==0;
            int bg = light?0xFFF0D9B5:0xFFB58863;
            boolean lf=lastFrom!=null&&lastFrom[0]==r&&lastFrom[1]==c, lt=lastTo!=null&&lastTo[0]==r&&lastTo[1]==c;
            if (lf||lt) bg=light?0xFFF6F669:0xFFDAC34A;
            if (selected!=null&&selected[0]==r&&selected[1]==c) bg=0xFF80C080;
            g.fill(sx,sy,sx+cellSize,sy+cellSize,bg);
            for (int[] mv:validMoves) if (mv[2]==r&&mv[3]==c) {
                if (board[r][c]!=E) { g.fill(sx,sy,sx+4,sy+4,0xAA229922); g.fill(sx+cellSize-4,sy,sx+cellSize,sy+4,0xAA229922); g.fill(sx,sy+cellSize-4,sx+4,sy+cellSize,0xAA229922); g.fill(sx+cellSize-4,sy+cellSize-4,sx+cellSize,sy+cellSize,0xAA229922); }
                else { int d=cellSize/5; GameRenderHelper.drawCircle(g,sx+cellSize/2,sy+cellSize/2,d,0x66229922); }
            }
            if (mx>=sx&&mx<sx+cellSize&&my>=sy&&my<sy+cellSize) g.fill(sx,sy,sx+cellSize,sy+cellSize,0x22FFFFFF);
            int p=board[r][c]; if(p!=E) renderPiece(g,p,sx,sy);
            if (c==0) g.drawString(font,String.valueOf(8-r),sx+2,sy+2,light?0xFFB58863:0xFFF0D9B5);
            if (r==7) g.drawString(font,String.valueOf((char)('a'+c)),sx+cellSize-6,sy+cellSize-10,light?0xFFB58863:0xFFF0D9B5);
        }
        GameRenderHelper.tickAndRenderParticles(g,particles);
        GameRenderHelper.drawTopHUD(g,width,height);
        String ts=whiteTurn?"♔ 白方走棋":"♚ 黑方走棋";
        if (vsAI&&!whiteTurn&&aiThinking) ts="AI 思考中...";
        g.drawString(font,ts,8,7,0xFFFFFF);
        g.drawString(font,vsAI?"AI对战":"本地双人",width-60,7,0x888888);
        if (state==S.PLAYING&&inCheck) g.drawCenteredString(font,"⚠ 将军！",width/2,7,0xFF4444);
        if (promoPending) renderPromoPanel(g,mx,my);
        GameRenderHelper.drawBottomBar(g,font,width,height,"ESC 菜单  R 重开  点击走棋");
    }
    private void renderPiece(GuiGraphics g, int p, int sx, int sy) {
        boolean w=p>0; int abs=Math.abs(p), cx2=sx+cellSize/2, cy2=sy+cellSize/2, rad=cellSize/2-2;
        GameRenderHelper.drawCircle(g,cx2,cy2,rad,w?0xFF999999:0xFF555555);
        GameRenderHelper.drawCircle(g,cx2,cy2,rad-1,w?0xFFF5F5F5:0xFF1A1A1A);
        String lbl=PIECE_LABEL[abs]; int lw=font.width(lbl);
        g.drawString(font,lbl,cx2-lw/2,cy2-4,w?0xFF222222:0xFFDDDDDD);
        if (abs==5) g.fill(cx2-1,sy+3,cx2+2,sy+5,w?0xFFDDAA00:0xFF886600);
    }
    private void renderPromoPanel(GuiGraphics g, int mx, int my) {
        boolean w=(promoRow==0); int cx2=width/2, cy2=height/2;
        int pw=cellSize*4+20, px2=cx2-pw/2, py2=cy2-30;
        g.fill(px2-2,py2-2,px2+pw+2,py2+cellSize+44,0xFF000000);
        g.fill(px2,py2,px2+pw,py2+cellSize+42,0xFF2A2A3A);
        g.drawCenteredString(font,"选择升变棋子",cx2,py2+6,0xFFFFFF);
        int[]types={WQ,WR,WB,WN}; String[]names={"后","车","象","马"};
        for (int i=0;i<4;i++) { int sx=px2+10+i*(cellSize+4), sy=py2+22;
            boolean hv=mx>=sx&&mx<sx+cellSize&&my>=sy&&my<sy+cellSize;
            g.fill(sx,sy,sx+cellSize,sy+cellSize,hv?0xFF556655:0xFF334433);
            renderPiece(g,w?types[i]:-types[i],sx,sy);
            g.drawCenteredString(font,names[i],sx+cellSize/2,sy+cellSize+2,0xAAAAAA);
        }
    }
    private void renderOver(GuiGraphics g, int mx, int my) {
        GameRenderHelper.drawGameOverOverlay(g,width,height);
        int cx2=width/2, cy2=height/2; boolean win=resultMsg.contains("白方")&&vsAI;
        GameRenderHelper.drawGameOverPanel(g,font,cx2,cy2,win,resultMsg.replace("§c","").replace("§a","").replace("§e",""),vsAI?(win?"恭喜战胜AI！":"再接再厉！"):"精彩对局！");
        GameRenderHelper.drawPrimaryButton(g,font,"R - 再来一局",cx2-70,cy2+22,140,18,mx,my);
        GameRenderHelper.drawSecondaryButton(g,font,"ESC - 返回",cx2-70,cy2+44,140,18,mx,my);
    }
    @Override public boolean isPauseScreen() { return false; }
}