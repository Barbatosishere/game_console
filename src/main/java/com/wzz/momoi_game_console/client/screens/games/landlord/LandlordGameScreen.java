package com.wzz.momoi_game_console.client.screens.games.landlord;

import com.wzz.momoi_game_console.client.screens.GameSelectorScreen;
import com.wzz.momoi_game_console.client.screens.games.LanMultiplayerScreen;
import com.wzz.momoi_game_console.init.ModNetworks;
import com.wzz.momoi_game_console.network.MultiplayerGamePacket;
import com.wzz.momoi_game_console.util.GameRenderHelper;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.api.distmarker.OnlyIn;
import org.lwjgl.glfw.GLFW;

import java.util.*;

/**
 * 斗地主 —— 现代 UI + 三人局域网联机
 *
 * 单机：玩家0 vs AI1 vs AI2
 * LAN HOST：本地=玩家0，运行完整逻辑，广播给两个 CLIENT
 * LAN CLIENT：本地=玩家1或2，等待 HOST 推送状态，只在自己回合操作
 */
@OnlyIn(Dist.CLIENT)
public class LandlordGameScreen extends Screen implements LanMultiplayerScreen {
    boolean showExitConfirm = false;

    private static final int LAN_NONE=0,LAN_HOST=1,LAN_CLIENT=2;
    private static final int CARD_W=28,CARD_H=42,CARD_SP=22;

    // ── 游戏逻辑 ──────────────────────────────────────
    private LandlordGame game;
    private AIPlayer ai1,ai2;
    private List<Card> selectedCards = new ArrayList<>();
    private boolean[] cardSelected   = new boolean[0];

    private long  lastAiTick = 0;
    private static final int AI_DELAY = 25;

    // ── LAN ───────────────────────────────────────────
    private int  lanMode     = LAN_NONE;
    private UUID peer1Uuid   = null;
    private UUID peer2Uuid   = null;
    private UUID hostUuid    = null;
    private int  myPlayerIdx = 0;
    private boolean waitingStart = false;

    // ── UI 状态 ───────────────────────────────────────
    private String msg=""; private long msgTick=-9999;
    private String lastInfo="";
    private long tickCount=0;

    private static final int BG1=0xFF060C18,BG2=0xFF081220;
    private static final int PBG=0xFF0D1A2E,PBD=0xFF1E3A5F;

    // ══ 构造器 ════════════════════════════════════════
    public LandlordGameScreen(){
        super(Component.literal("斗地主"));
        game=new LandlordGame(); ai1=new AIPlayer(); ai2=new AIPlayer();
    }
    public LandlordGameScreen(boolean isHost,UUID p1,UUID p2){
        super(Component.literal("斗地主"));
        lanMode=LAN_HOST; peer1Uuid=p1; peer2Uuid=p2; myPlayerIdx=0;
        game=new LandlordGame();
    }
    public LandlordGameScreen(boolean isHost,UUID host){
        super(Component.literal("斗地主"));
        lanMode=LAN_CLIENT; hostUuid=host; myPlayerIdx=-1; waitingStart=true;
        game=new LandlordGame();
    }

    // ══ LanMultiplayerScreen ═════════════════════════
    @Override public UUID getLanPeer(){return peer1Uuid!=null?peer1Uuid:hostUuid;}
    @Override public String getLanGameId(){return "landlord";}

    @Override
    public void onRemoteMove(String data){
        if(lanMode!=LAN_HOST)return;
        if(data.startsWith("BID:")){
            String[]p=data.substring(4).split(":");
            int pl=Integer.parseInt(p[0]); boolean w="1".equals(p[1]);
            if(game.bid(pl,w)){showMsg(name(pl)+(w?" 叫地主！":" 不叫"));broadcastState();}
        }else if(data.startsWith("PLAY:")){
            String[]p=data.substring(5).split(":",2);
            int pl=Integer.parseInt(p[0]);
            List<Card> cards=p.length>1?LandlordGame.deserializeCards(p[1]):new ArrayList<>();
            if(game.playCards(pl,cards)){
                lastInfo=name(pl)+(cards.isEmpty()?" 过牌":" 出: "+cardsStr(cards));
                showMsg(lastInfo);broadcastState();
            }
        }
    }

    @Override
    public void onRemoteState(String data){
        if(lanMode!=LAN_CLIENT)return;
        if(data.startsWith("INIT:")){
            String body=data.substring(5);
            int sep=body.indexOf('|');
            myPlayerIdx=Integer.parseInt(body.substring(0,sep));
            waitingStart=false;
            List<Card> h=new ArrayList<>();
            game.applyState(body.substring(sep+1),myPlayerIdx,h);
            cardSelected=new boolean[h.size()];
            showMsg("游戏开始！你是 "+name(myPlayerIdx));
        }else if(data.startsWith("STATE:")){
            List<Card> h=new ArrayList<>();
            game.applyState(data.substring(6),myPlayerIdx,h);
            if(cardSelected.length!=h.size())cardSelected=new boolean[h.size()];
        }
    }
    @Override public void onRemoteGameOver(String d){}

    private void sendToHost(String d){
        ModNetworks.PACKET_HANDLER.sendToServer(new MultiplayerGamePacket(
            MultiplayerGamePacket.PacketType.GAME_MOVE,hostUuid,"landlord",d));
    }
    private void sendToPeer(UUID peer,String d){
        ModNetworks.PACKET_HANDLER.sendToServer(new MultiplayerGamePacket(
            MultiplayerGamePacket.PacketType.GAME_STATE_SYNC,peer,"landlord",d));
    }
    private void broadcastState(){
        if(lanMode!=LAN_HOST||peer1Uuid==null||peer2Uuid==null)return;
        sendToPeer(peer1Uuid,"STATE:"+game.serializeFor(1));
        sendToPeer(peer2Uuid,"STATE:"+game.serializeFor(2));
    }
    private void sendInit(UUID peer,int idx){
        sendToPeer(peer,"INIT:"+idx+"|"+game.serializeFor(idx));
    }

    // ══ Tick ═════════════════════════════════════════
    @Override public void tick(){
        tickCount++;
        if(lanMode==LAN_HOST&&tickCount==5){
            sendInit(peer1Uuid,1); sendInit(peer2Uuid,2);
            showMsg("游戏开始！");
        }
        if(lanMode==LAN_NONE){
            int cp=game.getCurrentPlayer();
            if((cp==1||cp==2)&&tickCount-lastAiTick>AI_DELAY){
                lastAiTick=tickCount;
                AIPlayer ai=cp==1?ai1:ai2;
                if(game.getGameState()==LandlordGame.GameState.BIDDING){
                    boolean b=ai.decideBid(game.getPlayerHand(cp),false);
                    game.bid(cp,b); showMsg(name(cp)+(b?" 叫地主！":" 不叫"));
                }else if(game.getGameState()==LandlordGame.GameState.PLAYING){
                    List<Card> pl=ai.chooseCardsToPlay(game.getPlayerHand(cp),game.getLastPlayedCards(),true);
                    game.playCards(cp,pl);
                    lastInfo=name(cp)+(pl.isEmpty()?" 过牌":" 出: "+cardsStr(pl));
                    showMsg(lastInfo);
                }
            }
        }
    }

    // ══ 渲染 ═════════════════════════════════════════
    @Override public void render(GuiGraphics g,int mx,int my,float pt){
        g.fillGradient(0,0,width,height,BG1,BG2);
        GameRenderHelper.renderDecorativeLines(g,width,height,tickCount,0x001133);
        if(waitingStart){renderWait(g);return;}
        drawHUD(g);
        drawSideHands(g);
        drawCenter(g);
        drawMyHand(g,mx,my);
        drawActionBar(g,mx,my);
        drawMsgBubble(g);
        if(game.getGameState()==LandlordGame.GameState.ENDED) drawResult(g,mx,my);
    }

    private void renderWait(GuiGraphics g){
        String dots=".".repeat((int)(tickCount/10%4));
        g.drawCenteredString(font,"§b等待游戏开始"+dots,width/2,height/2,0x44AAFF);
    }

    private void drawHUD(GuiGraphics g){
        g.fill(0,0,width,22,0xBB060C18); g.fill(0,22,width,23,PBD);
        g.drawCenteredString(font,"§b🃏 斗地主",width/2,6,0x44AAFF);
        if(game.getLandlordPlayer()>=0)
            g.drawString(font,"§c地主: §f"+name(game.getLandlordPlayer()),8,6,0xFFFFFF);
        String cur=game.getGameState()==LandlordGame.GameState.BIDDING
            ?"§e叫地主阶段":"§f轮到: §a"+name(game.getCurrentPlayer());
        g.drawString(font,cur,width-font.width(cur.replaceAll("§.",""))-8,6,0xFFFFFF);
    }

    private void drawSideHands(GuiGraphics g){
        drawSideHand(g,game.getPlayerHand(1).size(),name(1),14,height/2-70,game.getCurrentPlayer()==1);
        drawSideHand(g,game.getPlayerHand(2).size(),name(2),width-54,height/2-70,game.getCurrentPlayer()==2);
    }

    private void drawSideHand(GuiGraphics g,int cnt,String nm,int x,int y,boolean active){
        int bd=active?0xFF44AAFF:PBD;
        int h2=Math.min(cnt,14)*9+34;
        g.fill(x-2,y-2,x+42,y+h2,bd); g.fill(x,y,x+40,y+h2-4,PBG);
        for(int i=0;i<Math.min(cnt,14);i++){g.fill(x+3,y+i*8+3,x+37,y+i*8+12,0xFF1A3055);g.fill(x+4,y+i*8+4,x+36,y+i*8+11,0xFF223366);}
        int ty=y+Math.min(cnt,14)*8+8;
        g.drawCenteredString(font,(active?"§a":"§7")+nm,x+20,ty,active?0x44FF88:0xAAAAAA);
        g.drawCenteredString(font,"§f"+cnt+"张",x+20,ty+10,0xCCCCCC);
    }

    private void drawCenter(GuiGraphics g){
        int cx=width/2,cy=height/2;
        // 底牌
        List<Card> lc=game.getLandlordCards();
        boolean revealed=game.getLandlordPlayer()>=0;
        if(!lc.isEmpty()){
            g.drawCenteredString(font,"§7底牌:",cx,cy-58,0x888888);
            int sx=cx-lc.size()*(CARD_W+4)/2;
            for(int i=0;i<lc.size();i++) drawCard(g,lc.get(i),sx+i*(CARD_W+4),cy-50,revealed,false);
        }
        // 上家出牌
        List<Card> lp=game.getLastPlayedCards();
        if(!lp.isEmpty()){
            CardPattern pat=game.analyzeCards(lp);
            String pn=pat!=null?getPatternName(pat):"";
            g.drawCenteredString(font,"§f"+name(game.getLastPlayer())+" §e"+pn,cx,cy-6,0xFFFFFF);
            int sx=cx-lp.size()*CARD_SP/2;
            for(int i=0;i<lp.size();i++) drawCard(g,lp.get(i),sx+i*CARD_SP,cy+4,true,false);
        }else if(game.getGameState()==LandlordGame.GameState.PLAYING){
            g.drawCenteredString(font,"§7桌面空，主动出牌",cx,cy+4,0x555566);
        }
    }

    private void drawMyHand(GuiGraphics g,int mx,int my){
        List<Card> hand=game.getPlayerHand(myPlayerIdx);
        if(hand.isEmpty())return;
        if(cardSelected.length!=hand.size())cardSelected=new boolean[hand.size()];
        boolean myT=game.getCurrentPlayer()==myPlayerIdx&&lanMode!=LAN_HOST;
        int sx=Math.max(10,width/2-hand.size()*CARD_SP/2);
        int cy=height-CARD_H-52;
        for(int i=0;i<hand.size();i++){
            int cx2=sx+i*CARD_SP, cy2=cy-(cardSelected[i]?12:0);
            boolean hov=myT&&mx>=cx2&&mx<=cx2+CARD_W&&my>=cy2-5&&my<=cy2+CARD_H+5;
            drawCard(g,hand.get(i),cx2,cy2,true,cardSelected[i]||hov);
        }
        String label="§a"+name(myPlayerIdx);
        if(game.getLandlordPlayer()==myPlayerIdx)label+=" §c[地主]";
        g.drawString(font,label,sx,cy+CARD_H+6,0xFFFFFF);
        if(myT){
            int pulse=(int)(150+60*Math.sin(tickCount*0.2));
            g.fill(0,cy-16,width,cy-15,0xFF000000|(pulse<<8)|(pulse/3));
            g.drawCenteredString(font,"§a▼ 你的回合",width/2,cy-12,0x44FF88);
        }
    }

    private void drawActionBar(GuiGraphics g,int mx,int my){
        int cy=height-28,cx=width/2;
        boolean myT=(game.getCurrentPlayer()==myPlayerIdx)&&(lanMode!=LAN_HOST||(lanMode==LAN_HOST&&myPlayerIdx==0));
        var st=game.getGameState();
        if(st==LandlordGame.GameState.BIDDING&&myT){
            drawBtn(g,"叫地主 🏆",cx-80,cy,76,20,0xFF1A5500,0xFF33AA00,mx,my);
            drawBtn(g,"不叫 ✖",cx+4,cy,66,20,0xFF550000,0xFFAA0000,mx,my);
        }else if(st==LandlordGame.GameState.PLAYING&&myT){
            boolean cp=!selectedCards.isEmpty();
            boolean cpp=game.getLastPlayer()!=myPlayerIdx&&!game.getLastPlayedCards().isEmpty();
            drawBtn(g,"出牌 ▶",cx-80,cy,70,20,cp?0xFF1A4400:0xFF222222,cp?0xFF228800:0xFF444444,mx,my);
            drawBtn(g,"过牌 ⏭",cx-4,cy,70,20,cpp?0xFF440011:0xFF222222,cpp?0xFF880033:0xFF444444,mx,my);
        }
        drawBtn(g,"↺ 重开",cx+82,cy,60,20,0xFF1A1A3A,0xFF334488,mx,my);
    }

    private void drawMsgBubble(GuiGraphics g){
        long age=tickCount-msgTick;
        if(age>80||msg.isEmpty())return;
        int cx=width/2,cy=height/2-88;
        int tw=font.width(msg.replaceAll("§.",""));
        g.fill(cx-tw/2-6,cy-2,cx+tw/2+6,cy+13,0xAA000000);
        g.drawCenteredString(font,"§e"+msg,cx,cy,0xFFFF88);
    }

    private void drawResult(GuiGraphics g,int mx,int my){
        int cx=width/2,cy=height/2;
        g.fill(0,0,width,height,0xAA000000);
        int[]sc=game.getScores();
        boolean win=sc[myPlayerIdx]>0;
        int cw=300,ch=130,cax=cx-cw/2,cay=cy-ch/2;
        g.fill(cax-2,cay-2,cax+cw+2,cay+ch+2,win?0xFF44FF44:0xFFFF4444);
        g.fill(cax,cay,cax+cw,cay+ch,0xFF070F1E);
        g.drawCenteredString(font,win?"§a🎉 你赢了！":"§c游戏结束",cx,cay+12,win?0x44FF44:0xFF4444);
        int lp=game.getLandlordPlayer();
        g.drawCenteredString(font,"§7地主: §f"+(lp>=0?name(lp):"?"),cx,cay+28,0xCCCCCC);
        g.drawCenteredString(font,"§f积分 "+name(0)+":§e"+sc[0]+" "+name(1)+":§e"+sc[1]+" "+name(2)+":§e"+sc[2],cx,cay+44,0xCCCCCC);
        boolean bh=mx>=cx-50&&mx<=cx+50&&my>=cay+72&&my<=cay+94;
        g.fill(cx-51,cay+71,cx+51,cay+95,bh?0xFF00AAFF:0xFF005588);
        g.fill(cx-50,cay+72,cx+50,cay+94,bh?0xFF0088CC:0xFF003355);
        g.drawCenteredString(font,"§f↺ 再来一局",cx,cay+80,bh?0xFFFFFF:0x88CCFF);
    }

    // ══ 卡牌渲染 ═════════════════════════════════════
    private void drawCard(GuiGraphics g,Card c,int x,int y,boolean front,boolean hl){
        g.fill(x-1,y-1,x+CARD_W+1,y+CARD_H+1,hl?0xFF00CCFF:0xFF334455);
        if(!front){g.fill(x,y,x+CARD_W,y+CARD_H,0xFF1A3055);for(int dy=2;dy<CARD_H-2;dy+=3)g.fill(x+2,y+dy,x+CARD_W-2,y+dy+1,0xFF223366);return;}
        boolean red=c.getSuit()==Card.Suit.HEARTS||c.getSuit()==Card.Suit.DIAMONDS||c.getSuit()==Card.Suit.JOKER;
        boolean joker=c.getSuit()==Card.Suit.JOKER;
        g.fill(x,y,x+CARD_W,y+CARD_H,joker?0xFF1A1A2E:0xFFF5F5F0);
        if(hl)g.fill(x,y,x+CARD_W,y+2,0xFF00CCFF);
        String suit=switch(c.getSuit()){case SPADES->"♠";case HEARTS->"♥";case DIAMONDS->"♦";case CLUBS->"♣";default->"";};
        String rank=switch(c.getRank()){case ACE->"A";case JACK->"J";case QUEEN->"Q";case KING->"K";case TWO->"2";case SMALL_JOKER->"小王";case BIG_JOKER->"大王";default->String.valueOf(c.getRank().getValue());};
        int tc=joker?(c.getRank()==Card.Rank.BIG_JOKER?0xFFFF4400:0xFF0044FF):(red?0xFFCC1111:0xFF111111);
        if(joker){g.drawCenteredString(font,rank.substring(0,1),x+CARD_W/2,y+6,tc);g.drawCenteredString(font,rank.substring(1),x+CARD_W/2,y+18,tc);}
        else{g.drawString(font,suit,x+3,y+3,tc);g.drawString(font,rank,x+3,y+12,tc);g.drawCenteredString(font,rank+suit,x+CARD_W/2,y+CARD_H/2-4,tc);}
    }

    private void drawBtn(GuiGraphics g,String text,int x,int y,int w,int h,int bc,int hc,int mx,int my){
        boolean hov=mx>=x&&mx<=x+w&&my>=y&&my<=y+h;
        g.fill(x-1,y-1,x+w+1,y+h+1,hov?0xFF00AAFF:PBD);
        g.fill(x,y,x+w,y+h,hov?hc:bc);
        g.drawCenteredString(font,text,x+w/2,y+(h-8)/2,hov?0xFFFFFF:0xCCCCCC);
    }

    // ══ 鼠标/键盘 ════════════════════════════════════
    @Override public boolean mouseClicked(double mx,double my,int btn){
        if(showExitConfirm){int click=GameRenderHelper.getExitConfirmClick(mx,my,width,height);if(click==1){showExitConfirm=false;Minecraft.getInstance().setScreen(new GameSelectorScreen());return true;}if(click==2){showExitConfirm=false;return true;}return true;}
        if(game.getGameState()==LandlordGame.GameState.ENDED){
            int cx=width/2,cay=height/2-65;
            if(mx>=cx-50&&mx<=cx+50&&my>=cay+72&&my<=cay+94){restartGame();return true;}
            return true;
        }
        boolean myT=(game.getCurrentPlayer()==myPlayerIdx)&&(lanMode!=LAN_HOST||(myPlayerIdx==0));
        var st=game.getGameState();
        int cy=height-28,cx=width/2;
        if(st==LandlordGame.GameState.BIDDING&&myT){
            if(mx>=cx-80&&mx<=cx-4&&my>=cy&&my<=cy+20){doBid(true);return true;}
            if(mx>=cx+4&&mx<=cx+70&&my>=cy&&my<=cy+20){doBid(false);return true;}
        }
        if(st==LandlordGame.GameState.PLAYING&&myT){
            if(mx>=cx-80&&mx<=cx-10&&my>=cy&&my<=cy+20){doPlay();return true;}
            if(mx>=cx-4&&mx<=cx+66&&my>=cy&&my<=cy+20){doPass();return true;}
        }
        if(mx>=cx+82&&mx<=cx+142&&my>=cy&&my<=cy+20){restartGame();return true;}
        // 选牌
        if(myT&&st==LandlordGame.GameState.PLAYING){
            List<Card> hand=game.getPlayerHand(myPlayerIdx);
            if(!hand.isEmpty()){
                if(cardSelected.length!=hand.size())cardSelected=new boolean[hand.size()];
                int sx=Math.max(10,width/2-hand.size()*CARD_SP/2);
                int cardY=height-CARD_H-52;
                for(int i=hand.size()-1;i>=0;i--){
                    int cx2=sx+i*CARD_SP,cy2=cardY-(cardSelected[i]?12:0);
                    if(mx>=cx2&&mx<=cx2+CARD_W&&my>=cy2-5&&my<=cy2+CARD_H+5){
                        cardSelected[i]=!cardSelected[i];
                        syncSel(hand);return true;
                    }
                }
            }
        }
        return super.mouseClicked(mx,my,btn);
    }
    @Override public boolean keyPressed(int k,int sc,int m){
        if(k==GLFW.GLFW_KEY_ESCAPE){if(showExitConfirm){showExitConfirm=false;Minecraft.getInstance().setScreen(new GameSelectorScreen());}else{showExitConfirm=true;}return true;}
        if(showExitConfirm) return true;
        return super.keyPressed(k,sc,m);
    }

    // ══ 动作 ═════════════════════════════════════════
    private void doBid(boolean w){
        if(lanMode==LAN_NONE){game.bid(myPlayerIdx,w);showMsg(w?"你叫地主！":"你不叫");lastAiTick=tickCount;}
        else if(lanMode==LAN_CLIENT){sendToHost("BID:"+myPlayerIdx+":"+(w?"1":"0"));}
        else{game.bid(0,w);showMsg(w?"你叫地主！":"你不叫");broadcastState();}
    }
    private void doPlay(){
        if(selectedCards.isEmpty()){showMsg("请先选择手牌！");return;}
        CardPattern p=game.analyzeCards(selectedCards);
        if(p==null){showMsg("无效牌型！");return;}
        if(lanMode==LAN_NONE){
            if(game.playCards(myPlayerIdx,selectedCards)){lastInfo="你出: "+cardsStr(selectedCards);showMsg(lastInfo);clearSel();lastAiTick=tickCount;}
            else showMsg("不能出这些牌！");
        }else if(lanMode==LAN_CLIENT){sendToHost("PLAY:"+myPlayerIdx+":"+LandlordGame.serializeCards(selectedCards));clearSel();}
        else{
            if(game.playCards(0,selectedCards)){lastInfo="你出: "+cardsStr(selectedCards);showMsg(lastInfo);clearSel();broadcastState();}
            else showMsg("不能出这些牌！");
        }
    }
    private void doPass(){
        if(lanMode==LAN_NONE){game.playCards(myPlayerIdx,new ArrayList<>());showMsg("你过牌");clearSel();lastAiTick=tickCount;}
        else if(lanMode==LAN_CLIENT){sendToHost("PLAY:"+myPlayerIdx+":");clearSel();}
        else{game.playCards(0,new ArrayList<>());showMsg("你过牌");clearSel();broadcastState();}
    }
    private void clearSel(){selectedCards.clear();Arrays.fill(cardSelected,false);}
    private void syncSel(List<Card> h){selectedCards.clear();for(int i=0;i<Math.min(cardSelected.length,h.size());i++)if(cardSelected[i])selectedCards.add(h.get(i));}
    private void restartGame(){
        if(lanMode!=LAN_NONE){showMsg("LAN不支持单独重开");return;}
        game.restart();clearSel();showMsg("新的一局！");lastAiTick=0;
    }
    private void showMsg(String m){msg=m;msgTick=tickCount;}
    private String name(int id){
        if(lanMode==LAN_NONE)return switch(id){case 0->"你";case 1->"AI1";default->"AI2";};
        if(id==myPlayerIdx)return "你";
        return "P"+(id+1);
    }
    private String cardsStr(List<Card> c){StringBuilder sb=new StringBuilder();for(Card x:c){if(sb.length()>0)sb.append(' ');sb.append(x);}return sb.toString();}
    private String getPatternName(CardPattern p){return switch(p.getType()){case SINGLE->"单";case PAIR->"对";case TRIPLE->"三张";case TRIPLE_WITH_ONE->"三带一";case TRIPLE_WITH_PAIR->"三带二";case STRAIGHT->"顺子";case PAIR_STRAIGHT->"连对";case TRIPLE_STRAIGHT->"飞机";case BOMB->"炸弹";case JOKER_BOMB->"王炸";};}
    @Override public boolean isPauseScreen(){return false;}
}
