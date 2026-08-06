package com.wzz.game_console.client.screens.games;

import com.wzz.game_console.client.screens.GameSelectorScreen;
import com.wzz.game_console.util.GameRenderHelper;
import com.wzz.game_console.util.ResourceUtil;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import org.lwjgl.glfw.GLFW;

import java.util.*;

/**
 * 2D Minecraft 游戏
 *
 * 修复：
 * 1. 物品栏恢复 renderItem() 3D渲染（之前误改为纯色方块）
 * 2. 即死Bug修复：spawnY = surfaceY（脚底站在地表上方），onGround初始化true
 * 3. 渲染对齐：playerY=脚底，身体向上绘制，与碰撞严格一致
 * 4. 物理重写：solidFeet用 floor(y+0.01)，避免整数边界误判
 */
public class Minecraft2DScreen extends Screen {
    boolean showExitConfirm = false;

    // ─── 世界 ─────────────────────────────────────────────────
    private static final int   W   = 120;
    private static final int   H   = 64;
    private static final int   BS  = 20;   // 方块像素（放大以提升清晰度）
    private static final float PW  = 0.6f; // 玩家宽
    private static final float PH  = 1.8f; // 玩家高

    private Block[][] world;
    private int[][]   light;

    // ─── 玩家 ─────────────────────────────────────────────────
    /** playerY = 脚底 y（向下为正）*/
    private float  playerX, playerY;
    private float  velX=0, velY=0;
    private boolean onGround=false;
    private boolean[] keys=new boolean[512];
    private boolean sprinting=false;

    private float  hp=20, maxHp=20;
    private float  hunger=20, maxHunger=20;
    private long   lastHungerTick=0, lastRegenTick=0, lastDmgTime=0;
    private float  fallStartY=0;
    private boolean wasFalling=false;
    private boolean dead=false;
    private long   deadAt=0;
    private float  spawnX, spawnY;

    // ─── 相机 ─────────────────────────────────────────────────
    private float camX=0, camY=0;
    /** 渲染/点击共用的整数像素偏移，消除取整不一致导致的偏移 */
    private int camPX=0, camPY=0;

    // ─── 快捷栏 ──────────────────────────────────────────────
    /** 游戏逻辑用（放置/破坏） */
    private final Block[]     hotbarBlock = new Block[9];
    private final int[]       hotbarCount = new int[9];
    /** 渲染用（3D物品图标），与 hotbarBlock 同步 */
    private final ItemStack[] hotbarItem  = new ItemStack[9];
    private int    slot=0;
    private boolean showInv=false;

    // ─── 挖矿 ────────────────────────────────────────────────
    private int    breakBX=-1, breakBY=-1;
    private float  breakProg=0;
    private boolean holdBreak=false;

    // ─── 状态 ────────────────────────────────────────────────
    private boolean started=false;
    private long    tick=0, dayTick=0;

    // ─── 纹理 & 颜色 ─────────────────────────────────────────
    private final Map<Block,ResourceLocation> TEX  = new HashMap<>();
    private static final Map<Block,Integer>   COL  = new HashMap<>();
    private static final Map<Block,Float>     HARD = new HashMap<>();
    private final Random rng=new Random();

    static {
        COL.put(Blocks.GRASS_BLOCK,0xFF4CAF50); COL.put(Blocks.DIRT,       0xFF795548);
        COL.put(Blocks.STONE,      0xFF9E9E9E); COL.put(Blocks.OAK_LOG,    0xFF8D6E63);
        COL.put(Blocks.OAK_PLANKS, 0xFFD4A76A); COL.put(Blocks.OAK_LEAVES, 0xFF388E3C);
        COL.put(Blocks.COBBLESTONE,0xFF757575); COL.put(Blocks.COAL_ORE,   0xFF424242);
        COL.put(Blocks.IRON_ORE,   0xFFBCAAA4); COL.put(Blocks.DIAMOND_ORE,0xFF4DD0E1);
        COL.put(Blocks.SAND,       0xFFE6D06C); COL.put(Blocks.GRAVEL,     0xFF9E9E9E);
        COL.put(Blocks.BEDROCK,    0xFF333333);

        HARD.put(Blocks.GRASS_BLOCK,0.6f); HARD.put(Blocks.DIRT,        0.5f);
        HARD.put(Blocks.SAND,       0.5f); HARD.put(Blocks.GRAVEL,      0.6f);
        HARD.put(Blocks.OAK_LOG,    2.0f); HARD.put(Blocks.OAK_PLANKS,  1.5f);
        HARD.put(Blocks.OAK_LEAVES, 0.2f); HARD.put(Blocks.STONE,       7.5f);
        HARD.put(Blocks.COBBLESTONE,6.0f); HARD.put(Blocks.COAL_ORE,    7.5f);
        HARD.put(Blocks.IRON_ORE,   7.5f); HARD.put(Blocks.DIAMOND_ORE, 7.5f);
        HARD.put(Blocks.BEDROCK,    Float.MAX_VALUE);
    }

    public Minecraft2DScreen(){
        super(Component.literal("2D Minecraft"));
        initTex(); initWorld(); initHotbar();
    }

    private void initTex(){
        TEX.put(Blocks.GRASS_BLOCK, ResourceUtil.createInstance("minecraft","textures/block/grass_block_top.png"));
        TEX.put(Blocks.DIRT,        ResourceUtil.createInstance("minecraft","textures/block/dirt.png"));
        TEX.put(Blocks.STONE,       ResourceUtil.createInstance("minecraft","textures/block/stone.png"));
        TEX.put(Blocks.OAK_LOG,     ResourceUtil.createInstance("minecraft","textures/block/oak_log.png"));
        TEX.put(Blocks.OAK_PLANKS,  ResourceUtil.createInstance("minecraft","textures/block/oak_planks.png"));
        TEX.put(Blocks.COBBLESTONE, ResourceUtil.createInstance("minecraft","textures/block/cobblestone.png"));
        TEX.put(Blocks.COAL_ORE,    ResourceUtil.createInstance("minecraft","textures/block/coal_ore.png"));
        TEX.put(Blocks.IRON_ORE,    ResourceUtil.createInstance("minecraft","textures/block/iron_ore.png"));
        TEX.put(Blocks.DIAMOND_ORE, ResourceUtil.createInstance("minecraft","textures/block/diamond_ore.png"));
        TEX.put(Blocks.OAK_LEAVES,  ResourceUtil.createInstance("minecraft","textures/block/oak_leaves.png"));
        TEX.put(Blocks.SAND,        ResourceUtil.createInstance("minecraft","textures/block/sand.png"));
        TEX.put(Blocks.GRAVEL,      ResourceUtil.createInstance("minecraft","textures/block/gravel.png"));
    }

    // ══════════════ 世界生成 ══════════════
    private void initWorld(){
        world=new Block[W][H]; light=new int[W][H];
        Random r=new Random(9999L);
        int[]sy=new int[W];
        for(int x=0;x<W;x++) sy[x]=28+(int)(4*Math.sin(x*.18))+(int)(2*Math.sin(x*.07+1.2))+(int)(1.5*Math.sin(x*.4+.5));

        for(int x=0;x<W;x++){
            int s=sy[x];
            for(int y=0;y<H;y++){
                if(y==H-1)      world[x][y]=Blocks.BEDROCK;
                else if(y>s+5)  world[x][y]=Blocks.STONE;
                else if(y>s)    world[x][y]=Blocks.DIRT;
                else if(y==s)   world[x][y]=Blocks.GRASS_BLOCK;
            }
        }
        // 树
        for(int x=4;x<W-4;x+=5+r.nextInt(5)){
            int s=sy[x];if(world[x][s]!=Blocks.GRASS_BLOCK)continue;
            int th=4+r.nextInt(3);
            for(int ty=s-th;ty<s;ty++)if(ty>=0)world[x][ty]=Blocks.OAK_LOG;
            int top=s-th;
            for(int lx=x-2;lx<=x+2;lx++)for(int ly=top-2;ly<=top+1;ly++){
                if(!inW(lx,ly))continue;
                if((lx==x-2||lx==x+2)&&(ly==top-2||ly==top+1))continue;
                if(world[lx][ly]==null)world[lx][ly]=Blocks.OAK_LEAVES;
            }
        }
        placeOre(Blocks.COAL_ORE,    r,150,30,H-2,0.4f,3);
        placeOre(Blocks.IRON_ORE,    r, 80,35,H-2,0.3f,2);
        placeOre(Blocks.DIAMOND_ORE, r, 25,45,H-2,0.2f,1);
        caves(r);

        // ★ 修复出生点：脚底 = 地表行（站在地表顶面）
        int spBX=W/2;
        spawnX=spBX+.5f; spawnY=sy[spBX]; // 脚底恰好在地表方块顶面
        playerX=spawnX; playerY=spawnY;
        onGround=true; // ★ 初始在地面

        for(int x=0;x<W;x++)calcLight(x);
    }
    private boolean inW(int x,int y){return x>=0&&x<W&&y>=0&&y<H;}
    private void placeOre(Block b,Random r,int tries,int minY,int maxY,float chance,int vein){
        for(int i=0;i<tries;i++){
            int x=r.nextInt(W),y=minY+r.nextInt(Math.max(1,maxY-minY));
            if(!inW(x,y)||world[x][y]!=Blocks.STONE||r.nextFloat()>chance)continue;
            for(int v=0;v<vein;v++){int vx=x+r.nextInt(3)-1,vy=y+r.nextInt(3)-1;if(inW(vx,vy)&&world[vx][vy]==Blocks.STONE)world[vx][vy]=b;}
        }
    }
    private void caves(Random r){
        for(int i=0;i<25;i++){
            int cx=r.nextInt(W),cy=30+r.nextInt(H-34);int len=10+r.nextInt(25);
            for(int j=0;j<len;j++){
                for(int dx=-1;dx<=1;dx++)for(int dy=-1;dy<=1;dy++){int nx=cx+dx,ny=cy+dy;if(inW(nx,ny)&&world[nx][ny]!=Blocks.BEDROCK&&r.nextFloat()<.75f)world[nx][ny]=null;}
                cx=Math.max(1,Math.min(W-2,cx+r.nextInt(3)-1));cy=Math.max(30,Math.min(H-3,cy+r.nextInt(3)-1));
            }
        }
    }

    // ══════════════ 快捷栏初始化 ══════════════
    private void initHotbar(){
        setSlot(0, Blocks.GRASS_BLOCK, Items.GRASS_BLOCK, 64);
        setSlot(1, Blocks.DIRT,        Items.DIRT,        64);
        setSlot(2, Blocks.OAK_PLANKS,  Items.OAK_PLANKS,  16);
        for(int i=3;i<9;i++){hotbarBlock[i]=null;hotbarItem[i]=null;hotbarCount[i]=0;}
    }
    private void setSlot(int i, Block b, net.minecraft.world.item.Item item, int count){
        hotbarBlock[i]=b; hotbarItem[i]=new ItemStack(item,count); hotbarCount[i]=count;
    }

    // ══════════════ TICK ══════════════
    @Override public void tick(){
        super.tick();if(!started)return;
        tick++;dayTick=(dayTick+1)%2400;
        if(dead){if(System.currentTimeMillis()-deadAt>3000)respawn();return;}
        float dt=.05f;
        physics(dt);breakTick(dt);hungerTick();regenTick();
    }

    // ══════════════ 物理 ══════════════
    private void physics(float dt){
        // 重力
        if(!onGround){
            velY+=40f*dt; velY=Math.min(velY,25f);
            if(!wasFalling){fallStartY=playerY;wasFalling=true;}
        } else {
            if(wasFalling){float d=playerY-fallStartY;if(d>3.5f)hurt((d-3.5f)*2f);wasFalling=false;}
            velY=0;
        }
        velX*=0.85f;

        // 垂直
        float ny=playerY+velY*dt;
        if(velY>0){ // 向下
            if(solidFeet(playerX,ny)){playerY=(float)Math.floor(ny);velY=0;onGround=true;}
            else{playerY=ny;onGround=false;}
        } else if(velY<0){ // 向上
            if(solidHead(playerX,ny)){int hr=(int)Math.floor(ny-PH);playerY=hr+1+PH;velY=0;}
            else playerY=ny;
        }
        // 重新检测地面
        onGround=solidFeet(playerX,playerY);

        // 水平
        sprinting=keys[GLFW.GLFW_KEY_LEFT_CONTROL]||keys[GLFW.GLFW_KEY_RIGHT_CONTROL];
        float spd=sprinting?13f:8f;
        if(keys[GLFW.GLFW_KEY_A]||keys[GLFW.GLFW_KEY_LEFT])  velX=-spd;
        if(keys[GLFW.GLFW_KEY_D]||keys[GLFW.GLFW_KEY_RIGHT]) velX= spd;
        float nx=playerX+velX*dt;
        if(!solidBody(nx,playerY))playerX=nx; else velX=0;

        // 跳跃
        if((keys[GLFW.GLFW_KEY_SPACE]||keys[GLFW.GLFW_KEY_W])&&onGround){velY=hunger<2?-9f:-13.5f;onGround=false;}

        playerX=Math.max(PW/2f,Math.min(W-PW/2f,playerX));
        playerY=Math.max(PH,Math.min(H-.01f,playerY));
        if(playerY>H-1)hurt(4f);
    }
    /** ★ floor(y+0.01) 确保站在整数面上不误判 */
    private boolean solidFeet(float x,float y){
        int row=(int)Math.floor(y+.01f);
        return solid((int)Math.floor(x-PW/2+.05f),row)||solid((int)Math.floor(x+PW/2-.05f),row);
    }
    private boolean solidHead(float x,float y){
        int row=(int)Math.floor(y-PH);
        return solid((int)Math.floor(x-PW/2+.05f),row)||solid((int)Math.floor(x+PW/2-.05f),row);
    }
    private boolean solidBody(float x,float y){
        int col=(int)Math.floor(x>playerX?x+PW/2-.05f:x-PW/2+.05f);
        for(int dy=0;dy<2;dy++)if(solid(col,(int)Math.floor(y-dy-.1f)))return true;
        return false;
    }
    private boolean solid(int x,int y){
        if(!inW(x,y))return true;
        Block b=world[x][y];return b!=null&&b!=Blocks.OAK_LEAVES;
    }

    // ══════════════ 挖矿 ══════════════
    private void breakTick(float dt){
        if(!holdBreak||breakBX<0){breakProg=0;return;}
        Block b=world[breakBX][breakBY];if(b==null){holdBreak=false;breakProg=0;return;}
        float hard=HARD.getOrDefault(b,3f);if(hard==Float.MAX_VALUE)return;
        breakProg+=dt/hard;
        if(breakProg>=1f){
            collect(b);world[breakBX][breakBY]=null;updateLight(breakBX,breakBY);
            holdBreak=false;breakProg=0;hunger=Math.max(0f,hunger-.3f);
        }
    }
    private void collect(Block b){
        Block drop=drop(b);if(drop==null)return;
        for(int i=0;i<9;i++)if(hotbarBlock[i]==drop&&hotbarCount[i]<64){hotbarCount[i]++;syncItem(i);return;}
        for(int i=0;i<9;i++)if(hotbarBlock[i]==null||hotbarCount[i]==0){
            hotbarBlock[i]=drop;hotbarCount[i]=1;
            hotbarItem[i]=new ItemStack(blockToItem(drop),1);return;
        }
    }
    private Block drop(Block b){
        if(b==Blocks.GRASS_BLOCK)return Blocks.DIRT;
        if(b==Blocks.STONE)return Blocks.COBBLESTONE;
        if(b==Blocks.OAK_LEAVES)return rng.nextFloat()<.05f?Blocks.OAK_LOG:null;
        return b;
    }
    private net.minecraft.world.item.Item blockToItem(Block b){
        if(b==Blocks.GRASS_BLOCK)return Items.GRASS_BLOCK;
        if(b==Blocks.DIRT)return Items.DIRT;
        if(b==Blocks.STONE)return Items.STONE;
        if(b==Blocks.OAK_LOG)return Items.OAK_LOG;
        if(b==Blocks.OAK_PLANKS)return Items.OAK_PLANKS;
        if(b==Blocks.COBBLESTONE)return Items.COBBLESTONE;
        if(b==Blocks.COAL_ORE)return Items.COAL_ORE;
        if(b==Blocks.IRON_ORE)return Items.IRON_ORE;
        if(b==Blocks.DIAMOND_ORE)return Items.DIAMOND_ORE;
        if(b==Blocks.SAND)return Items.SAND;
        if(b==Blocks.GRAVEL)return Items.GRAVEL;
        return Items.DIRT;
    }
    private void syncItem(int i){
        if(hotbarItem[i]!=null)hotbarItem[i].setCount(hotbarCount[i]);
    }

    private void hungerTick(){if(tick-lastHungerTick>80&&(Math.abs(velX)>.1f||!onGround)){hunger=Math.max(0f,hunger-.5f);lastHungerTick=tick;}if(hunger<=0&&tick-lastHungerTick>80){hurt(1f);lastHungerTick=tick;}} // 修复：饥饿伤害改固定间隔并更新时间戳，避免饱食度归零后每 tick 扣血
    private void regenTick(){if(hunger>18f&&hp<maxHp&&tick-lastRegenTick>10){hp=Math.min(maxHp,hp+.5f);lastRegenTick=tick;}}
    private void hurt(float d){if(dead)return;hp=Math.max(0f,hp-d);lastDmgTime=System.currentTimeMillis();if(hp<=0){dead=true;deadAt=System.currentTimeMillis();}}
    private void respawn(){dead=false;hp=maxHp;hunger=20f;playerX=spawnX;playerY=spawnY;velX=velY=0;onGround=true;wasFalling=false;}

    // ══════════════ 相机 ══════════════
    private void updateCam(){
        float tx=playerX-(float)width/(2f*BS),ty=playerY-(float)height/(2f*BS);
        camX+=(tx-camX)*.12f;camY+=(ty-camY)*.12f;
        camX=Math.max(0,Math.min(W-(float)width/BS,camX));
        camY=Math.max(0,Math.min(H-(float)height/BS,camY));
        camPX=Math.round(camX*BS);camPY=Math.round(camY*BS);
    }

    // ══════════════ 渲染 ══════════════
    @Override
    public void renderBackground(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        // 不渲染默认32x32像素菜单背景纹理和模糊效果,游戏自行绘制不透明背景
    }

    @Override
    public void render(GuiGraphics g,int mx,int my,float pt){
        if(!started){renderMenu(g,mx,my);return;}
        g.fill(0,0,width,height,skyColor());
        updateCam();
        renderWorld(g);
        renderPlayer(g);
        renderBreak(g,mx,my);
        renderUI(g,mx,my);
        if(showInv)renderInv(g);
        if(dead)renderDeath(g);
        if(showExitConfirm) GameRenderHelper.drawExitConfirmOverlay(g, font, width, height, mx, my);
        super.render(g,mx,my,pt);
    }

    private int skyColor(){
        float t=dayTick/2400f;
        if(t<.25f)return lerp(0xFF0A0A2A,0xFFFF9944,t/.25f);
        if(t<.5f) return lerp(0xFFFF9944,0xFF87CEEB,(t-.25f)/.25f);
        if(t<.75f)return lerp(0xFF87CEEB,0xFFFF6633,(t-.5f)/.25f);
        return lerp(0xFFFF6633,0xFF0A0A2A,(t-.75f)/.25f);
    }
    private int lerp(int a,int b,float t){
        return 0xFF000000|(((int)(((a>>16)&0xFF)+(((b>>16)&0xFF)-((a>>16)&0xFF))*t))<<16)|(((int)(((a>>8)&0xFF)+(((b>>8)&0xFF)-((a>>8)&0xFF))*t))<<8)|((int)((a&0xFF)+((b&0xFF)-(a&0xFF))*t));
    }

    private void renderMenu(GuiGraphics g,int mx,int my){
        g.fill(0,0,width,height,0xFF1A3A1A);
        drawSh(g,"2D Minecraft",width/2,height/2-60,0x55FF55,2);
        drawSh(g,"探索 · 挖矿 · 建造",width/2,height/2-42,0xAAAAAA,1);
        String[]tips={"WASD/方向键 移动   空格 跳跃","左键按住 挖矿   右键 放置方块","1-9/滚轮 切换快捷栏   Ctrl 疾跑","E 背包   ESC 退出"};
        for(int i=0;i<tips.length;i++)g.drawCenteredString(font,tips[i],width/2,height/2-16+i*13,0x888888);
        int bw=160,bh=22,bx2=width/2-bw/2,by2=height/2+42;
        boolean hv=mx>=bx2&&mx<=bx2+bw&&my>=by2&&my<=by2+bh;
        g.fill(bx2,by2,bx2+bw,by2+bh,hv?0xFF448844:0xFF224422);
        g.fill(bx2,by2,bx2+bw,by2+1,hv?0xFF66AA66:0xFF336633);
        g.drawCenteredString(font,"开始游戏",width/2,by2+7,0xFFFFFF);
    }
    private void drawSh(GuiGraphics g,String t,int x,int y,int col,int size){
        g.drawString(font,t,x-font.width(t)/2+1,y+1,0x000000);
        g.drawString(font,t,x-font.width(t)/2,y,col);
    }

    private void renderWorld(GuiGraphics g){
        int sx=Math.max(0,(int)camX-1),ex=Math.min(W,(int)camX+width/BS+2);
        int sy=Math.max(0,(int)camY-1),ey=Math.min(H,(int)camY+height/BS+2);
        for(int x=sx;x<ex;x++)for(int y=sy;y<ey;y++){Block b=world[x][y];if(b!=null)drawBlock(g,b,x,y);}
    }
    private void drawBlock(GuiGraphics g,Block b,int wx,int wy){
        int sx=wx*BS-camPX,sy=wy*BS-camPY;
        ResourceLocation t=TEX.get(b);
        if(t!=null){try{g.blit(t,sx,sy,0,0,BS,BS,BS,BS);shade(g,b,sx,sy);return;}catch(Exception ignored){}}
        g.fill(sx,sy,sx+BS,sy+BS,COL.getOrDefault(b,0xFF888888));
        shade(g,b,sx,sy);
    }
    private void shade(GuiGraphics g,Block b,int sx,int sy){
        g.fill(sx,sy,sx+BS,sy+1,0x20FFFFFF);g.fill(sx,sy,sx+1,sy+BS,0x18FFFFFF);
        g.fill(sx,sy+BS-1,sx+BS,sy+BS,0x30000000);g.fill(sx+BS-1,sy,sx+BS,sy+BS,0x20000000);
        if(b==Blocks.GRASS_BLOCK)g.fill(sx,sy,sx+BS,sy+4,0xFF66BB6A);
    }

    /**
     * ★ 修复渲染偏移：playerY=脚底，身体从 (screenY-BODY_PX) 到 screenY
     */
    private void renderPlayer(GuiGraphics g){
        int px=Math.round(playerX*BS)-camPX;
        int py=Math.round(playerY*BS)-camPY; // 脚底像素y

        boolean dmg=System.currentTimeMillis()-lastDmgTime<300;
        int bodyC=dmg?0xFFFF4444:0xFF1565C0;
        int legC =dmg?0xFFCC2222:0xFF0D47A1;
        int skinC=dmg?0xFFFF8888:0xFFFFDBB5;

        int bh=(int)(PH*BS);    // 总高度28px
        int hw=(int)(PW/2*BS);  // 半宽4px
        int headH=BS/2;         // 头高8px

        // 腿部（身体下半）
        g.fill(px-hw+1,py-bh/2,px,      py,     legC);
        g.fill(px,     py-bh/2,px+hw-1, py,     bodyC);
        // 身体（上半）
        g.fill(px-hw,  py-bh,  px+hw,   py-bh/2,bodyC);
        // 头部
        g.fill(px-hw/2,py-bh-headH, px+hw/2,py-bh, skinC);
        // 眼睛
        g.fill(px-hw/2+1,py-bh-headH+2, px-2,     py-bh-headH+5, 0xFF000000);
        g.fill(px+2,     py-bh-headH+2, px+hw/2-1,py-bh-headH+5, 0xFF000000);
        // 头发
        g.fill(px-hw/2,py-bh-headH, px+hw/2,py-bh-headH+2, 0xFF5C3A1A);
    }

    private void renderBreak(GuiGraphics g,int mx,int my){
        int hx=(camPX+mx)/BS,hy=(camPY+my)/BS;
        if(inW(hx,hy)&&world[hx][hy]!=null){
            int sx=hx*BS-camPX,sy=hy*BS-camPY;
            g.fill(sx,sy,sx+BS,sy+1,0x77FFFFFF);g.fill(sx,sy,sx+1,sy+BS,0x77FFFFFF);
            g.fill(sx+BS-1,sy,sx+BS,sy+BS,0x77FFFFFF);g.fill(sx,sy+BS-1,sx+BS,sy+BS,0x77FFFFFF);
        }
        if(!holdBreak||breakBX<0||breakProg<=0)return;
        int sx=breakBX*BS-camPX,sy=breakBY*BS-camPY;
        g.fill(sx,sy,sx+BS,sy+BS,(int)(0xAA*breakProg)<<24|0x000000);
        g.fill(sx,sy+BS-3,sx+BS,sy+BS,0xFF333333);
        g.fill(sx,sy+BS-3,sx+(int)(BS*breakProg),sy+BS,0xFFFFDD00);
    }

    private void renderUI(GuiGraphics g,int mx,int my){
        renderHotbar(g);
        renderHp(g);renderHunger(g);
        g.drawString(font,String.format("§7X:%.1f Y:%.1f",playerX,playerY),8,8,0xFFFFFF);
        float t=dayTick/2400f;String ts=t<.25f?"§8深夜":t<.5f?"§e白天":t<.75f?"§6傍晚":"§8夜晚";
        g.drawString(font,ts,width-40,8,0xFFFFFF);
        if(sprinting)g.drawString(font,"§e⚡疾跑",8,19,0xFFFFFF);
        int hbx=(camPX+mx)/BS,hby=(camPY+my)/BS;
        if(inW(hbx,hby)&&world[hbx][hby]!=null)g.drawCenteredString(font,"§7["+bname(world[hbx][hby])+"]",width/2,height/2+20,0xAAAAAA);
        int cw=width/2,ch=height/2;
        g.fill(cw-5,ch-1,cw+5,ch+1,0x88FFFFFF);g.fill(cw-1,ch-5,cw+1,ch+5,0x88FFFFFF);
    }

    /**
     * ★ 恢复 renderItem() 3D物品渲染
     */
    private void renderHotbar(GuiGraphics g){
        int ss=20,tot=9*ss,stX=(width-tot)/2,hy=height-26;
        g.fill(stX-2,hy-2,stX+tot+2,hy+ss+2,0xAA000000);
        for(int i=0;i<9;i++){
            int sx=stX+i*ss;boolean sel=i==slot;
            g.fill(sx,hy,sx+ss,hy+ss,sel?0xFF888888:0xFF444444);
            if(sel){g.fill(sx,hy,sx+ss,hy+1,0xFFFFFFFF);g.fill(sx,hy,sx+1,hy+ss,0xFFFFFFFF);g.fill(sx+ss-1,hy,sx+ss,hy+ss,0xFFFFFFFF);g.fill(sx,hy+ss-1,sx+ss,hy+ss,0xFFFFFFFF);}
            // ★ renderItem 3D渲染
            if(hotbarItem[i]!=null&&hotbarCount[i]>0){
                g.renderItem(hotbarItem[i],sx+2,hy+2);
                // 数量标签
                if(hotbarCount[i]>1)g.renderItemDecorations(net.minecraft.client.Minecraft.getInstance().font,hotbarItem[i],sx+2,hy+2,null);
            }
            g.drawString(font,String.valueOf(i+1),sx+2,hy+2,0x777777);
        }
        // 选中方块名称
        if(hotbarBlock[slot]!=null&&hotbarCount[slot]>0)
            g.drawCenteredString(font,bname(hotbarBlock[slot]),width/2,hy-12,0xFFFFFF);
    }

    private void renderHp(GuiGraphics g){
        int bx=8,by=height-52;
        for(int i=0;i<(int)(maxHp/2);i++){boolean f=hp>=(i+1)*2,h=hp>i*2&&hp<(i+1)*2;g.fill(bx+i*10,by,bx+i*10+8,by+8,f?0xFFFF2222:h?0xFFFF8888:0xFF444444);}
    }
    private void renderHunger(GuiGraphics g){
        int bx=8,by=height-40;
        for(int i=0;i<(int)(maxHunger/2);i++){boolean f=hunger>=(i+1)*2,h=hunger>i*2&&hunger<(i+1)*2;g.fill(bx+i*10,by,bx+i*10+8,by+8,f?0xFFFFAA00:h?0xFFCC8800:0xFF333300);}
    }
    private void renderInv(GuiGraphics g){
        int pw=220,ph=110,px=(width-pw)/2,py=(height-ph)/2;
        g.fill(px-2,py-2,px+pw+2,py+ph+2,0xFF000000);
        g.fill(px,py,px+pw,py+ph,0xFF222233);
        g.drawCenteredString(font,"背包 (E关闭)",width/2,py+6,0xFFFFFF);
        for(int i=0;i<9;i++){
            int sx=px+10+i*22,sy=py+22;
            g.fill(sx,sy,sx+21,sy+21,i==slot?0xFF666666:0xFF333333);
            // ★ 背包里也用 renderItem
            if(hotbarItem[i]!=null&&hotbarCount[i]>0)g.renderItem(hotbarItem[i],sx+2,sy+2);
        }
        g.drawString(font,"§7石头→鹅卵石  草地→泥土  叶→5%木头",px+8,py+55,0xFFFFFF);
        g.drawString(font,"§7右键放置  左键按住挖矿  Ctrl疾跑",px+8,py+67,0xFFFFFF);
        g.drawString(font,"§7挖到的方块自动进入快捷栏",px+8,py+79,0xFFFFFF);
    }
    private void renderDeath(GuiGraphics g){
        g.flush(); // 防止先绘制的游戏内容盖住遮罩背景（批量渲染text批次后置）
        g.fill(0,0,width,height,0xAA660000);
        drawSh(g,"你死了！",width/2,height/2-16,0xFF4444,2);
        g.drawCenteredString(font,"§73秒后自动重生...",width/2,height/2+6,0xAAAAAA);
    }
    private String bname(Block b){
        if(b==Blocks.GRASS_BLOCK)return "草方块";if(b==Blocks.DIRT)return "泥土";
        if(b==Blocks.STONE)return "石头";if(b==Blocks.OAK_LOG)return "橡木原木";
        if(b==Blocks.OAK_PLANKS)return "木板";if(b==Blocks.OAK_LEAVES)return "树叶";
        if(b==Blocks.COBBLESTONE)return "鹅卵石";if(b==Blocks.COAL_ORE)return "煤矿";
        if(b==Blocks.IRON_ORE)return "铁矿";if(b==Blocks.DIAMOND_ORE)return "钻石矿";
        if(b==Blocks.SAND)return "沙子";if(b==Blocks.GRAVEL)return "砂砾";
        if(b==Blocks.BEDROCK)return "基岩(不可破)";return b.getDescriptionId();
    }

    // ══════════════ 输入 ══════════════
    @Override public boolean keyPressed(int k,int sc,int m){
        if(!started)return super.keyPressed(k,sc,m);
        // 修复：退出确认弹窗打开时，仅允许 ESC（再次按 ESC 关闭弹窗），拦截移动等所有游戏按键输入
        if(k==GLFW.GLFW_KEY_ESCAPE){
            if(showExitConfirm){showExitConfirm=false;}
            else{showExitConfirm=true;Arrays.fill(keys,false);} // 清空已按住的按键，防止打开弹窗前按住的 WASD 继续移动
            return true;
        }
        if(showExitConfirm) return true;
        if(k>=0&&k<keys.length)keys[k]=true; // 修复：GLFW_KEY_UNKNOWN(-1) 等非法 keyCode 会数组越界
        if(k>=GLFW.GLFW_KEY_1&&k<=GLFW.GLFW_KEY_9){slot=k-GLFW.GLFW_KEY_1;return true;}
        if(k==GLFW.GLFW_KEY_E){showInv=!showInv;return true;}
        return super.keyPressed(k,sc,m);
    }
    @Override public boolean keyReleased(int k,int sc,int m){if(k>=0&&k<keys.length)keys[k]=false;return super.keyReleased(k,sc,m);} // 修复：同上，过滤非法 keyCode
    @Override public boolean mouseClicked(double mx,double my,int btn){
        // 修复：退出后回游戏选择界面，与其他游戏保持一致（原 onClose() 会回到游戏世界）
        if(showExitConfirm){int click=GameRenderHelper.getExitConfirmClick(mx,my,width,height);if(click==1){showExitConfirm=false;Minecraft.getInstance().setScreen(new GameSelectorScreen());return true;}if(click==2){showExitConfirm=false;return true;}return true;}
        if(!started){
            int bw=160,bh=22,bx2=width/2-bw/2,by2=height/2+42;
            if(mx>=bx2&&mx<=bx2+bw&&my>=by2&&my<=by2+bh){started=true;return true;}
            return super.mouseClicked(mx,my,btn);
        }
        if(dead||showInv)return super.mouseClicked(mx,my,btn);
        int bx2=(camPX+(int)mx)/BS,by2=(camPY+(int)my)/BS;
        if(!inW(bx2,by2))return super.mouseClicked(mx,my,btn);
        if(btn==0&&world[bx2][by2]!=null){holdBreak=true;breakBX=bx2;breakBY=by2;breakProg=0;return true;}
        if(btn==1&&world[bx2][by2]==null&&hotbarBlock[slot]!=null&&hotbarCount[slot]>0){
            world[bx2][by2]=hotbarBlock[slot];
            hotbarCount[slot]--;
            if(hotbarItem[slot]!=null)hotbarItem[slot].setCount(hotbarCount[slot]);
            if(hotbarCount[slot]==0){hotbarBlock[slot]=null;hotbarItem[slot]=null;}
            updateLight(bx2,by2);return true;
        }
        return super.mouseClicked(mx,my,btn);
    }
    @Override public boolean mouseReleased(double mx,double my,int btn){if(btn==0){holdBreak=false;breakProg=0;}return super.mouseReleased(mx,my,btn);}
    @Override public boolean mouseScrolled(double mx, double my, double scrollDeltaX, double d){if(started){slot=(slot+(d>0?-1:1)+9)%9;return true;}return super.mouseScrolled(mx, my, scrollDeltaX, d);}

    // ══════════════ 光照 ══════════════
    private void updateLight(int cx,int cy){for(int x=Math.max(0,cx-12);x<=Math.min(W-1,cx+12);x++)calcLight(x);}
    private void calcLight(int x){
        int s=-1;for(int y=0;y<H;y++)if(world[x][y]!=null){s=y;break;}
        for(int y=0;y<H;y++)light[x][y]=(world[x][y]==null)?(s==-1?15:Math.max(0,15-Math.max(0,s-y))):(s==-1||y<=s?0:Math.max(0,5-(y-s)));
    }
    @Override public boolean isPauseScreen(){return false;}
}