package com.wzz.game_console.client.screens.games;

import com.wzz.game_console.client.screens.GameSelectorScreen;
import com.wzz.game_console.util.GameRenderHelper;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import org.lwjgl.glfw.GLFW;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;

/**
 * 跳一跳 — 单人蓄力跳跃小游戏
 *
 * 操作：按住 鼠标左键 或 空格键 蓄力，松开跳跃
 *       力度越大跳越远，落在平台中心获得额外分
 *       R = 重新开始  |  ESC = 退出
 *
 * 特性：
 *   等轴投影视角 + 镜头平滑跟随
 *   人物蓄力压扁动画、跳跃弧线、落地弹跳
 *   平台中心命中检测（Perfect！双倍分）
 *   连续 Perfect 触发 Combo 提示
 *   背景粒子、落地粒子、分数浮字动画
 *   平台随机形状（圆柱、方块、书本、音符方块）
 */
public class JumpGameScreen extends Screen {
    boolean showExitConfirm = false;

    // ══════════════════════════════════════════════
    //  投影常量（等轴视角）
    // ══════════════════════════════════════════════
    // 世界坐标 (wx, wy, wz) → 屏幕坐标
    // sx = cx + (wx - wz) * ISO_X
    // sy = cy + (wx + wz) * ISO_Y - wy * ISO_H
    static final float ISO_X = 0.80f;
    static final float ISO_Y = 0.40f;
    static final float ISO_H = 1.00f;

    // ══════════════════════════════════════════════
    //  游戏参数
    // ══════════════════════════════════════════════
    static final float GRAVITY       = 0.035f;  // 重力加速度
    static final float JUMP_VY       = 0.42f;   // 固定起跳纵向速度（决定飞行时间 ~24tick）
    static final float MAX_VX        = 0.28f;   // 满蓄力时的水平速度（满蓄 = 6.7格，覆盖DIST_MAX）
    static final float CHARGE_RATE   = 0.045f;  // 每tick蓄力增量（~22tick满蓄）
    static final float CENTER_RADIUS = 0.30f;   // 中心判定半径（平台半尺寸的比例）
    static final float PERFECT_BONUS = 2.0f;    // 中心分数倍率
    static final int   BASE_SCORE    = 10;      // 每次落地基础分

    static final float PLAT_MIN_W = 1.6f;       // 平台最小半宽
    static final float PLAT_MAX_W = 2.4f;       // 平台最大半宽
    static final float PLAT_H     = 0.5f;       // 平台高度

    static final float DIST_MIN = 3.5f;         // 下一平台最近距离
    static final float DIST_MAX = 6.0f;         // 下一平台最远距离

    // ══════════════════════════════════════════════
    //  平台
    // ══════════════════════════════════════════════
    enum PlatType { CYLINDER, BLOCK, BOOK, MUSIC }

    static class Platform {
        private static final Random RAND = new Random();
        private static final int[][] SCHEMES = {
            {0xFF5B8AD4, 0xFF7AAFF5},   // 蓝
            {0xFF8B5CF6, 0xFFA78BFA},   // 紫
            {0xFF10B981, 0xFF34D399},   // 绿
            {0xFFF59E0B, 0xFFFBBF24},   // 橙
            {0xFFEF4444, 0xFFF87171},   // 红
            {0xFF64748B, 0xFF94A3B8},   // 灰蓝
            {0xFFEC4899, 0xFFF472B6},   // 粉
        };
        float wx, wz;     // 世界中心（Y=0 为顶面）
        float hw;         // 半宽（radius for cylinder）
        PlatType type;
        int   color;      // 主色
        int   topColor;   // 顶面色
        boolean lit;      // 是否高亮（当前目标）

        Platform(float wx, float wz, float hw, PlatType type) {
            this.wx=wx; this.wz=wz; this.hw=hw; this.type=type;
            int[] s = SCHEMES[RAND.nextInt(SCHEMES.length)];
            this.color = s[0]; this.topColor = s[1];
        }
    }

    // ══════════════════════════════════════════════
    //  人物
    // ══════════════════════════════════════════════
    static class Player {
        float wx, wy, wz;
        float vx, vy, vz;
        boolean onGround = true;
        float   scaleY   = 1f;   // 蓄力时压扁（Y方向）
        float   scaleXZ  = 1f;   // 蓄力时扩张（XZ方向）
        float   bobPhase = 0f;   // 站立时的微小浮动
        float   landBounce = 0f; // 落地后的弹跳
        // 跳跃方向单位向量（蓄力时朝向下一平台）
        float dirX = 1f, dirZ = 0f;
    }

    // ══════════════════════════════════════════════
    //  粒子
    // ══════════════════════════════════════════════
    static class Particle {
        float wx, wy, wz;
        float vx, vy, vz;
        int   color;
        int   life, maxLife;
        float size;
        boolean isText;
        String text;

        // 普通粒子
        Particle(float wx, float wy, float wz,
                 float vx, float vy, float vz,
                 int color, int life, float size) {
            this.wx=wx;this.wy=wy;this.wz=wz;
            this.vx=vx;this.vy=vy;this.vz=vz;
            this.color=color;this.life=this.maxLife=life;this.size=size;
        }
        // 浮字粒子
        Particle(float wx, float wy, float wz, String text, int color) {
            this.wx=wx;this.wy=wy+2;this.wz=wz;
            this.vy=0.03f;this.vx=0;this.vz=0;
            this.color=color;this.life=this.maxLife=50;
            this.isText=true;this.text=text;this.size=1;
        }
        void update() {
            wx+=vx; wy+=vy; wz+=vz;
            vy -= 0.006f; // 轻微重力
            life--;
        }
        boolean alive() { return life>0; }
    }

    // ══════════════════════════════════════════════
    //  游戏状态
    // ══════════════════════════════════════════════
    List<Platform> platforms = new ArrayList<>();
    Player         player    = new Player();
    List<Particle> particles = new ArrayList<>();

    int   score     = 0;
    int   combo     = 0;       // 连续 Perfect 次数
    int   bestScore = 0;
    int   currentPlatIdx = 0;  // 当前站立平台序号
    boolean charging  = false; // 是否正在蓄力
    float   charge    = 0f;    // 蓄力量 [0,1]
    boolean gameOver  = false;
    boolean justLanded= false; // 用于音效/动画触发标记
    long    tick      = 0;

    // 镜头（以世界坐标表示观察中心）
    float camWX = 0f, camWZ = 0f;  // 目标镜头位置
    float camSX = 0f, camSZ = 0f;  // 当前镜头位置（平滑插值）

    // 虚影轨迹（蓄力时预测落点）
    float[] predictWX = null, predictWZ = null, predictWY = null;

    Random rng = new Random();

    // ══════════════════════════════════════════════
    //  构造
    // ══════════════════════════════════════════════
    public JumpGameScreen() {
        super(Component.literal("跳一跳"));
    }

    @Override
    public void init() {
        super.init();
        // 修复：窗口缩放会重复调用 init()，仅首次进入时初始化，避免游戏进行中丢进度
        if (platforms.isEmpty()) startGame();
    }

    void startGame() {
        platforms.clear();
        particles.clear();
        score=0; combo=0; gameOver=false; charging=false; charge=0;
        currentPlatIdx=0; predictWX=null; predictWZ=null; predictWY=null;

        // 生成前15个平台（边玩边无限生成）
        platforms.add(new Platform(0, 0,
            PLAT_MIN_W + rng.nextFloat()*(PLAT_MAX_W-PLAT_MIN_W),
            PlatType.values()[rng.nextInt(PlatType.values().length)]));
        for (int i=1;i<15;i++) addPlatform();

        // 玩家站在第一个平台上
        Platform p0 = platforms.get(0);
        player = new Player();
        player.wx = p0.wx; player.wy = 0; player.wz = p0.wz;
        player.dirX = 1; player.dirZ = 0; // 默认朝右

        updateCamera(true);
        updateDirToNext();
    }

    void addPlatform() {
        Platform last = platforms.get(platforms.size()-1);
        float nx = last.wx + DIST_MAX, nz = last.wz + DIST_MAX;
        float hw  = PLAT_MIN_W + rng.nextFloat()*(PLAT_MAX_W-PLAT_MIN_W);
        PlatType t= PlatType.values()[rng.nextInt(PlatType.values().length)];
        // 尝试生成不与旧平台重叠的位置（跳过相邻平台，间距由 DIST 控制）
        for (int attempt = 0; attempt < 25; attempt++) {
            float angle = rng.nextFloat() * (float)Math.PI / 2f
                          - (float)Math.PI / 4f; // ±45° 偏转
            float dist  = DIST_MIN + rng.nextFloat()*(DIST_MAX-DIST_MIN);
            if (attempt > 8) dist = Math.min(dist + (attempt-8)*0.35f, 6.4f); // 多次失败时适度拉远（不超过最大跳跃距离）
            // 基础方向沿 x+z 对角方向，保证等轴视图中向右下前进
            float finalAngle = (float)Math.PI / 4f + angle;
            nx = last.wx + dist * (float)Math.cos(finalAngle);
            nz = last.wz + dist * (float)Math.sin(finalAngle);
            hw  = PLAT_MIN_W + rng.nextFloat()*(PLAT_MAX_W-PLAT_MIN_W);
            t   = PlatType.values()[rng.nextInt(PlatType.values().length)];
            boolean ok = true;
            for (int i = Math.max(0, platforms.size()-6); i < platforms.size()-1; i++) {
                Platform p = platforms.get(i);
                float dx = nx - p.wx, dz = nz - p.wz;
                float minD = hw + p.hw + 1.5f; // 额外间距防止方块角部视觉重叠
                if (dx*dx + dz*dz < minD*minD) { ok = false; break; }
            }
            if (ok) break;
        }
        Platform np = new Platform(nx, nz, hw, t);
        np.lit = false;
        platforms.add(np);
    }

    // ══════════════════════════════════════════════
    //  Tick
    // ══════════════════════════════════════════════
    @Override
    public void tick() {
        tick++;

        // ── 物理更新（gameOver 时也继续，保证掉落动画正常播放）──
        if (!player.onGround) {
            player.wx += player.vx;
            player.wy += player.vy;
            player.wz += player.vz;
            player.vy -= GRAVITY;

            if (player.wy <= 0) {
                if (!gameOver) {
                    // 正常落地检测
                    player.wy = 0;
                    checkLanding();
                } else {
                    // gameOver 后继续下落（掉进深渊动画），不触发落地
                    // wy 可以无限下降，镜头不再跟随
                }
            }
        }

        // ── 粒子更新（gameOver 时也继续）──
        particles.removeIf(p -> !p.alive());
        for (Particle p : particles) p.update();

        if (gameOver) return; // ← gameOver 后其余逻辑不执行

        // ── 蓄力 ──
        if (charging && player.onGround) {
            charge = Math.min(1f, charge + CHARGE_RATE);
            float sq = 1f - charge * 0.35f;
            player.scaleY  = sq;
            player.scaleXZ = 1f + charge * 0.25f;
            updatePredict();
        } else if (!player.onGround) {
            predictWX = null; predictWZ = null; predictWY = null;
        }

        // ── 站立微浮动 ──
        if (player.onGround) {
            player.bobPhase += 0.08f;
            if (player.landBounce > 0) player.landBounce *= 0.75f;
        }

        // ── 镜头平滑跟随 ──
        updateCamera(false);

        // ── 生成更多平台 ──
        while (platforms.size() - currentPlatIdx < 10) addPlatform();

        // ── 移除过远的旧平台 ──
        while (platforms.size() > 20 && currentPlatIdx > 5) {
            platforms.remove(0);
            currentPlatIdx--;
        }
    }

    void checkLanding() {
        Platform cur  = platforms.get(currentPlatIdx);

        // 落回当前平台：重置到当前平台中心，不扣分不 game over
        float dxCur = player.wx - cur.wx;
        float dzCur = player.wz - cur.wz;
        if ((float)Math.sqrt(dxCur*dxCur+dzCur*dzCur) <= cur.hw) {
            player.onGround = true;
            player.wx = cur.wx; player.wz = cur.wz; // 归位到平台中心
            player.vy = 0; player.vx = 0; player.vz = 0;
            player.scaleY = 1f; player.scaleXZ = 1f;
            player.landBounce = 0.15f;
            charging = false; charge = 0; predictWX = null; predictWZ = null; predictWY = null;
            updateDirToNext();
            return;
        }

        // 修复：落点可能跳过下一个平台落在更远的平台上，扫描全部平台判定落点所在平台
        Platform landed = null;
        int landedIdx = -1;
        float dist = 0f;
        for (int i = 0; i < platforms.size(); i++) {
            if (i == currentPlatIdx) continue;
            Platform p = platforms.get(i);
            float dxi = player.wx - p.wx;
            float dzi = player.wz - p.wz;
            float di = (float)Math.sqrt(dxi*dxi+dzi*dzi);
            if (di <= p.hw) {
                landed = p;
                landedIdx = i;
                dist = di;
                break;
            }
        }

        // 是否落在某个平台上
        if (landed != null) {
            // 成功落地
            player.onGround = true;
            player.vy = 0; player.vx = 0; player.vz = 0;
            player.scaleY = 1f; player.scaleXZ = 1f;
            player.landBounce = 0.25f;
            // 落地时清空蓄力，防止之前按键残留状态导致自动蓄力
            charging = false; charge = 0; predictWX = null; predictWZ = null; predictWY = null;
            currentPlatIdx = landedIdx;

            // 是否 Perfect（中心）
            boolean perfect = dist <= landed.hw * CENTER_RADIUS;
            int pts;
            if (perfect) {
                combo++;
                pts = (int)(BASE_SCORE * PERFECT_BONUS) + (combo > 1 ? combo * 5 : 0);
                spawnLandParticles(landed, true);
                particles.add(new Particle(player.wx, player.wy, player.wz,
                    combo>1?"PERFECT  x"+combo+" 连击！":"PERFECT！",
                    combo>3?0xFFFF4444:0xFFFFDD00));
            } else {
                combo = 0;
                pts = BASE_SCORE;
                spawnLandParticles(landed, false);
                particles.add(new Particle(player.wx, player.wy, player.wz,
                    "+"+pts, 0xFFFFFFFF));
            }
            score += pts;
            if (score > bestScore) bestScore = score;

            updateDirToNext();
            // 点亮下一个平台
            for (int i=0;i<platforms.size();i++)
                platforms.get(i).lit = (i==currentPlatIdx+1);
        } else {
            // 落空，游戏结束
            player.onGround = false; // 继续下落（掉进深渊动画）
            gameOver = true;
            // 落空粒子
            for (int i=0;i<20;i++)
                particles.add(new Particle(
                    player.wx, 0, player.wz,
                    (rng.nextFloat()-0.5f)*0.2f,
                    0.08f+rng.nextFloat()*0.15f,
                    (rng.nextFloat()-0.5f)*0.2f,
                    0xFFFF4444, 40+rng.nextInt(20), 0.3f));
        }
    }

    void spawnLandParticles(Platform p, boolean perfect) {
        int n = perfect ? 20 : 10;
        for (int i=0;i<n;i++) {
            float angle = rng.nextFloat()*(float)(Math.PI*2);
            float spd   = 0.05f+rng.nextFloat()*0.12f;
            int c = perfect
                ? lerpColor(p.topColor, 0xFFFFFF00, rng.nextFloat())
                : lerpColor(p.topColor, 0xFFFFFFFF, rng.nextFloat()*0.4f);
            particles.add(new Particle(
                p.wx + (float)Math.cos(angle)*p.hw*0.5f, 0.1f,
                p.wz + (float)Math.sin(angle)*p.hw*0.5f,
                (float)Math.cos(angle)*spd, 0.1f+rng.nextFloat()*0.1f,
                (float)Math.sin(angle)*spd,
                c, 30+rng.nextInt(20), 0.15f+rng.nextFloat()*0.15f));
        }
    }

    void updateDirToNext() {
        if (currentPlatIdx+1 >= platforms.size()) return;
        Platform next = platforms.get(currentPlatIdx+1);
        Platform cur  = platforms.get(currentPlatIdx);
        float dx = next.wx - cur.wx, dz = next.wz - cur.wz;
        float len = (float)Math.sqrt(dx*dx+dz*dz);
        if (len>0) { player.dirX=dx/len; player.dirZ=dz/len; }
    }

    void updatePredict() {
        if (currentPlatIdx+1 >= platforms.size()) return;
        float vx = player.dirX * charge * MAX_VX;
        float vz = player.dirZ * charge * MAX_VX;
        float vy = JUMP_VY;
        float px=player.wx, py=player.wy, pz=player.wz;
        int steps=60;
        predictWX=new float[steps]; predictWZ=new float[steps]; predictWY=new float[steps];
        for (int i=0;i<steps;i++) {
            px+=vx; py+=vy; pz+=vz; vy-=GRAVITY;
            predictWX[i]=px; predictWZ[i]=pz; predictWY[i]=Math.max(0,py);
            if (py<=0) {
                for(int j=i+1;j<steps;j++){
                    predictWX[j]=px; predictWZ[j]=pz; predictWY[j]=0;
                }
                break;
            }
        }
    }

    void releaseJump() {
        // 无论如何都清空蓄力状态，防止落地后意外蓄力
        float savedCharge = charge;
        charge = 0; charging = false;
        predictWX = null; predictWZ = null; predictWY = null;

        if (!player.onGround || gameOver || savedCharge < 0.01f) return;

        // 固定纵速 + 水平速度线性正比于蓄力量
        player.vx = player.dirX * savedCharge * MAX_VX;
        player.vz = player.dirZ * savedCharge * MAX_VX;
        player.vy = JUMP_VY;
        player.onGround = false;
        player.scaleY  = 1.2f;  // 起跳拉伸
        player.scaleXZ = 0.85f;
    }

    void updateCamera(boolean snap) {
        // 镜头跟随当前平台和下一个平台的中间点
        Platform cur = platforms.get(currentPlatIdx);
        float tx = cur.wx, tz = cur.wz;
        if (currentPlatIdx+1 < platforms.size()) {
            Platform nxt = platforms.get(currentPlatIdx+1);
            tx = (cur.wx+nxt.wx)/2; tz = (cur.wz+nxt.wz)/2;
        }
        camWX=tx; camWZ=tz;
        if (snap) { camSX=camWX; camSZ=camWZ; }
        else {
            camSX += (camWX-camSX)*0.08f;
            camSZ += (camWZ-camSZ)*0.08f;
        }
    }

    // ══════════════════════════════════════════════
    //  投影工具
    // ══════════════════════════════════════════════
    float[] project(float wx, float wy, float wz) {
        float rx = wx-camSX, rz = wz-camSZ;
        float sx = width/2f  + (rx-rz)*ISO_X*32;
        float sy = height/2f + (rx+rz)*ISO_Y*32 - wy*ISO_H*32;
        return new float[]{sx,sy};
    }

    // ══════════════════════════════════════════════
    //  主渲染
    // ══════════════════════════════════════════════
    @Override
    public void render(GuiGraphics g, int mx, int my, float pt) {
        renderBackground(g);
        renderShadows(g);
        renderPlatforms(g);
        renderPredictLine(g);
        renderParticles(g);
        renderPlayer(g);
        renderHUD(g);
        if (gameOver) renderGameOver(g);
        if (showExitConfirm) GameRenderHelper.drawExitConfirmOverlay(g, font, width, height, mx, my);
        super.render(g, mx, my, pt);
    }

    // ── 背景 ─────────────────────────────────────
    @Override
    public void renderBackground(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        // 游戏已在render()中绘制不透明背景,阻止默认32x32菜单纹理和模糊效果
    }

    public void renderBackground(GuiGraphics g) {
        // 渐变天空
        for (int i=0;i<height;i++) {
            float t = (float)i/height;
            int r = lerp(0x0D, 0x1A, t);
            int gg= lerp(0x0A, 0x14, t);
            int b = lerp(0x1F, 0x0E, t);
            g.fill(0, i, width, i+1, 0xFF000000|(r<<16)|(gg<<8)|b);
        }
        // 星点
        for (int i=0;i<80;i++) {
            int sx=(i*97+13)%width, sy=(i*53+7)%(height*2/3);
            int br=(int)(120+80*Math.sin(tick*0.04+i*1.3));
            g.fill(sx,sy,sx+1,sy+1,0xFF000000|(br<<16)|(br<<8)|br);
        }
        // 网格地面（模拟无限地板感）
        for (int gx=-20;gx<=20;gx++) {
            for (int gz=-20;gz<=20;gz++) {
                if ((gx+gz)%2!=0) continue;
                float[] p = project(gx*2f, -0.02f, gz*2f);
                float[] q = project(gx*2f+2, -0.02f, gz*2f);
                float[] r2= project(gx*2f+2, -0.02f, gz*2f+2);
                float[] s2= project(gx*2f, -0.02f, gz*2f+2);
                // 只画可见范围
                if (p[0]<-50||p[0]>width+50||p[1]<-50||p[1]>height+50) continue;
                float brightness = Math.max(0f, 1f - (Math.abs(gx)+Math.abs(gz))*0.04f);
                int gridColor = (int)(0x10*brightness)<<24|0x003344;
                fillQuad(g, p,q,r2,s2, 0xFF000000|(int)(brightness*0x0A)<<16|(int)(brightness*0x22)<<8|(int)(brightness*0x33));
            }
        }
    }

    // ── 平台阴影（落在地面上） ─────────────────────
    void renderShadows(GuiGraphics g) {
        for (Platform p : platforms) {
            int idx = platforms.indexOf(p);
            if (Math.abs(idx-currentPlatIdx)>4) continue;
            float alpha = 0.35f;
            renderPlatShadow(g, p, alpha);
        }
        // 人物阴影
        float h = player.wy;
        float sAlpha = Math.max(0, 0.5f - h*0.05f);
        float[] sc = project(player.wx, 0, player.wz);
        int sa = (int)(sAlpha*200);
        int ssize = (int)(6*player.scaleXZ) + 2;
        g.fill((int)sc[0]-ssize,(int)sc[1]-2,(int)sc[0]+ssize,(int)sc[1]+3, (sa<<24));
    }

    void renderPlatShadow(GuiGraphics g, Platform p, float alpha) {
        float[] c = project(p.wx, 0, p.wz);
        int a = (int)(alpha*120);
        int sw = (int)(p.hw*ISO_X*32*1.4f);
        int sh = (int)(p.hw*ISO_Y*32*1.0f);
        for (int dy=-sh;dy<=sh;dy++) {
            float t = (float)dy/sh;
            int dw = (int)(sw*Math.sqrt(Math.max(0,1-t*t)));
            g.fill((int)c[0]-dw,(int)c[1]+dy,(int)c[0]+dw,(int)c[1]+dy+1,(a<<24));
        }
    }

    // ── 平台渲染 ───────────────────────────────────
    void renderPlatforms(GuiGraphics g) {
        // 按深度从后到前排序（简单：先渲染远的）
        List<Platform> sorted = new ArrayList<>(platforms);
        sorted.sort((a,b) -> {
            float da = (a.wx-camSX)+(a.wz-camSZ);
            float db = (b.wx-camSX)+(b.wz-camSZ);
            return Float.compare(da,db);
        });
        for (Platform p : sorted) {
            int idx = platforms.indexOf(p);
            if (Math.abs(idx-currentPlatIdx)>5) continue;
            renderPlatform(g, p, idx==currentPlatIdx+1);
        }
    }

    void renderPlatform(GuiGraphics g, Platform p, boolean isNext) {
        float h   = PLAT_H;
        float hw  = p.hw;
        int   col = p.color;
        int   top = p.topColor;

        // 动画：下一个平台轻微脉动
        float pulse = 1f;
        if (isNext && !gameOver) {
            pulse = 1f + 0.04f*(float)Math.sin(tick*0.12);
            hw *= pulse;
        }

        switch (p.type) {
            case CYLINDER -> renderCylinder(g, p.wx, p.wz, hw, h, col, top, isNext);
            case BLOCK    -> renderBlock(g,    p.wx, p.wz, hw, h, col, top, isNext);
            case BOOK     -> renderBook(g,     p.wx, p.wz, hw, h);
            case MUSIC    -> renderMusicBlock(g, p.wx, p.wz, hw, h);
        }

        // 中心标记（下一个平台）
        if (isNext && !charging) {
            float[] c = project(p.wx, h+0.02f, p.wz);
            int ca = (int)(100+80*Math.sin(tick*0.15));
            drawIsoCircle(g, p.wx, h+0.02f, p.wz, 0.25f, (ca<<24)|0x00FFFFFF);
        }
    }

    void renderCylinder(GuiGraphics g, float wx, float wz, float hw, float h,
                        int col, int top, boolean next) {
        int steps = 16;
        // 侧面（前半圆）
        for (int i=0;i<steps;i++) {
            float a1=(float)Math.PI*i/steps, a2=(float)Math.PI*(i+1)/steps;
            float x1=wx+(float)Math.cos(a1)*hw, z1=wz+(float)Math.sin(a1)*hw;
            float x2=wx+(float)Math.cos(a2)*hw, z2=wz+(float)Math.sin(a2)*hw;
            // 左侧面（较暗）
            float shade = 0.65f+0.35f*(float)Math.abs(Math.cos(a1));
            int sc = shadeColor(col, shade);
            float[] p0=project(x1,0,z1), p1=project(x2,0,z2);
            float[] p2=project(x2,h,z2), p3=project(x1,h,z1);
            fillQuad(g,p0,p1,p2,p3,sc);
        }
        // 顶面椭圆
        drawIsoFilledCircle(g, wx, h, wz, hw, top);
        if (next) drawIsoCircle(g, wx, h+0.01f, wz, hw*0.25f, 0x88FFFFFF);
    }

    void renderBlock(GuiGraphics g, float wx, float wz, float hw, float h,
                     int col, int top, boolean next) {
        // 等轴方块：左面、右面、顶面
        // 顶面
        float[] tfl=project(wx-hw,h,wz-hw), tfr=project(wx+hw,h,wz-hw);
        float[] tbr=project(wx+hw,h,wz+hw), tbl=project(wx-hw,h,wz+hw);
        fillQuad(g,tfl,tfr,tbr,tbl,top);
        // 左侧面（暗）
        float[] bfl=project(wx-hw,0,wz-hw), bbr2=project(wx-hw,0,wz+hw);
        float[] lT1=project(wx-hw,h,wz-hw), lT2=project(wx-hw,h,wz+hw);
        fillQuad(g,bfl,bbr2,lT2,lT1,shadeColor(col,0.55f));
        // 右侧面（中）
        float[] bfr=project(wx+hw,0,wz-hw);
        // 右前侧
        float[] bbl=project(wx-hw,0,wz-hw), rT1=project(wx-hw,h,wz-hw), rT2=project(wx+hw,h,wz-hw);
        float[] rfB=project(wx+hw,0,wz-hw);
        // 右后侧
        float[] rB2=project(wx+hw,0,wz+hw), rT3=project(wx+hw,h,wz+hw), rT4=project(wx+hw,h,wz-hw);
        fillQuad(g, rfB, rB2, rT3, rT4, shadeColor(col, 0.75f));
    }

    void renderBook(GuiGraphics g, float wx, float wz, float hw, float h) {
        // 蓝白条纹书本
        int[] colors = {0xFF2563EB,0xFFFFFFFF,0xFF2563EB,0xFFFFFFFF};
        for (int i=0;i<4;i++) {
            float y0=(float)i/4*h, y1=(float)(i+1)/4*h;
            float[] p0=project(wx-hw,y0,wz-hw), p1=project(wx+hw,y0,wz-hw);
            float[] p2=project(wx+hw,y1,wz-hw), p3=project(wx-hw,y1,wz-hw);
            fillQuad(g,p0,p1,p2,p3,shadeColor(colors[i],0.7f));
            float[] q0=project(wx-hw,y0,wz+hw), q1=project(wx-hw,y0,wz-hw);
            float[] q2=project(wx-hw,y1,wz-hw), q3=project(wx-hw,y1,wz+hw);
            fillQuad(g,q0,q1,q2,q3,shadeColor(colors[i],0.5f));
        }
        // 顶面（书脊）
        float[] t0=project(wx-hw,h,wz-hw),t1=project(wx+hw,h,wz-hw);
        float[] t2=project(wx+hw,h,wz+hw),t3=project(wx-hw,h,wz+hw);
        fillQuad(g,t0,t1,t2,t3,0xFFEFF6FF);
        // 书脊线
        float[] s0=project(wx,h+0.01f,wz-hw), s1=project(wx,h+0.01f,wz+hw);
        drawIsoLine(g,s0,s1,0xFF93C5FD);
    }

    void renderMusicBlock(GuiGraphics g, float wx, float wz, float hw, float h) {
        // 绿色音符方块
        int col=0xFF065F46, top=0xFF059669;
        renderBlock(g,wx,wz,hw,h,col,top,false);
        // 音符符号（顶面中心画两个小方块）
        float[] n1=project(wx-0.2f,h+0.02f,wz);
        float[] n2=project(wx+0.2f,h+0.02f,wz);
        int ns=4;
        g.fill((int)n1[0]-ns,(int)n1[1]-ns,(int)n1[0]+ns,(int)n1[1]+ns,0xFF6EE7B7);
        g.fill((int)n2[0]-ns,(int)n2[1]-ns,(int)n2[0]+ns,(int)n2[1]+ns,0xFF6EE7B7);
        float[] n3=project(wx-0.2f,h+0.3f,wz);
        float[] n4=project(wx+0.2f,h+0.3f,wz);
        g.fill((int)n3[0]-2,(int)n3[1],(int)n3[0]+2,(int)n1[1],0xFF6EE7B7);
        g.fill((int)n4[0]-2,(int)n4[1],(int)n4[0]+2,(int)n2[1],0xFF6EE7B7);
    }

    // ── 预测轨迹 ────────────────────────────────────
    void renderPredictLine(GuiGraphics g) {
        if (predictWX==null||!charging) return;
        int n=predictWX.length;
        for (int i=0;i<n;i+=2) {
            float t=(float)i/n;
            int a=(int)((1f-t)*160);
            float wy_pred = (predictWY!=null)?predictWY[i]:0;
            float[] sc=project(predictWX[i], wy_pred, predictWZ[i]);
            int s=Math.max(1,(int)((1f-t)*5));
            g.fill((int)sc[0]-s,(int)sc[1]-s,(int)sc[0]+s,(int)sc[1]+s,(a<<24)|0x00AAFFFF);
        }
        // 落点圆圈
        float lx=predictWX[n-1], lz=predictWZ[n-1];
        float[] lc=project(lx,0,lz);
        int la=(int)(100+60*Math.sin(tick*0.2));
        for (int r=4;r<=10;r+=3)
            drawCircle(g,(int)lc[0],(int)lc[1],r,(la<<24)|0x00AAFFFF);
    }

    // ── 粒子 ────────────────────────────────────────
    void renderParticles(GuiGraphics g) {
        for (Particle p : particles) {
            float alpha = (float)p.life/p.maxLife;
            if (p.isText) {
                int a=(int)(alpha*220);
                int col=(p.color&0x00FFFFFF)|(a<<24);
                float[] sc=project(p.wx, p.wy, p.wz);
                int tw=font.width(p.text);
                // 文字随上升淡出，轻微放大
                float scale=0.9f+0.2f*(1f-alpha);
                g.pose().pushPose();
                g.pose().translate(sc[0],sc[1],0);
                g.pose().scale(scale,scale,1);
                g.drawString(font, p.text, -tw/2, 0, col);
                g.pose().popPose();
            } else {
                int a=(int)(alpha*(p.color>>24&0xFF));
                int col=(p.color&0x00FFFFFF)|(a<<24);
                float[] sc=project(p.wx,p.wy,p.wz);
                int s=(int)(p.size*24*alpha);
                if(s<1) s=1;
                g.fill((int)sc[0]-s,(int)sc[1]-s,(int)sc[0]+s,(int)sc[1]+s,col);
            }
        }
    }

    // ── 人物 ────────────────────────────────────────
    void renderPlayer(GuiGraphics g) {
        float bob = (float)Math.sin(player.bobPhase)*0.04f;
        float bounce = player.landBounce;
        float scY = player.scaleY * (1f + bounce*0.3f);
        float scXZ = player.scaleXZ;
        float wy = player.wy + bob;

        // 人物是个小人：圆形身体+头+帽子（等轴视角）
        float bodyH  = 0.6f*scY;
        float bodyHW = 0.25f*scXZ;
        float headH  = 0.35f;
        float headHW = 0.22f*scXZ;

        float baseY = wy;

        // 身体（小矩形块）
        renderPlayerBlock(g, player.wx, player.wz, bodyHW, bodyH, baseY,
            0xFF3B82F6, 0xFF60A5FA);

        // 头
        renderPlayerBlock(g, player.wx, player.wz, headHW, headH, baseY+bodyH,
            0xFFFFD4AA, 0xFFFFE0C0);

        // 帽子
        renderPlayerBlock(g, player.wx, player.wz, headHW+0.05f, 0.08f, baseY+bodyH+headH,
            0xFF1E3A5F, 0xFF2563EB);
        renderPlayerBlock(g, player.wx, player.wz, headHW*0.7f, 0.15f, baseY+bodyH+headH+0.08f,
            0xFF1E3A5F, 0xFF2563EB);

        // 眼睛（等轴右前面）
        float eyeY = baseY+bodyH+headH*0.6f;
        float[] el=project(player.wx+headHW*0.4f, eyeY, player.wz-headHW-0.01f);
        float[] er=project(player.wx+headHW*0.1f, eyeY, player.wz-headHW-0.01f);
        g.fill((int)el[0]-2,(int)el[1]-2,(int)el[0]+2,(int)el[1]+2,0xFF0F172A);
        g.fill((int)er[0]-2,(int)er[1]-2,(int)er[0]+2,(int)er[1]+2,0xFF0F172A);

        // 蓄力时的能量光环
        if (charging && player.onGround) {
            int qa=(int)(charge*200);
            drawIsoCircle(g, player.wx, wy+0.05f, player.wz,
                0.3f+charge*0.2f, (qa<<24)|0x00FFFF00);
            drawIsoCircle(g, player.wx, wy+0.05f, player.wz,
                0.5f+charge*0.3f, (qa/2<<24)|0x00FFAA00);
        }
    }

    void renderPlayerBlock(GuiGraphics g, float wx, float wz, float hw, float h,
                           float baseY, int col, int top) {
        // 右侧面
        float[] rB1=project(wx+hw,baseY,  wz-hw), rB2=project(wx+hw,baseY,  wz+hw);
        float[] rT1=project(wx+hw,baseY+h,wz-hw), rT2=project(wx+hw,baseY+h,wz+hw);
        fillQuad(g,rB1,rB2,rT2,rT1,shadeColor(col,0.75f));
        // 前侧面
        float[] fB1=project(wx-hw,baseY,  wz-hw), fT1=project(wx-hw,baseY+h,wz-hw);
        fillQuad(g,fB1,rB1,rT1,fT1,shadeColor(col,0.55f));
        // 顶面
        float[] t0=project(wx-hw,baseY+h,wz-hw),t1=project(wx+hw,baseY+h,wz-hw);
        float[] t2=project(wx+hw,baseY+h,wz+hw),t3=project(wx-hw,baseY+h,wz+hw);
        fillQuad(g,t0,t1,t2,t3,top);
    }

    // ── HUD ─────────────────────────────────────────
    void renderHUD(GuiGraphics g) {
        // 分数
        g.pose().pushPose();
        g.pose().translate(width/2f, 24, 0);
        g.pose().scale(2f,2f,1);
        String sc=String.valueOf(score);
        g.drawString(font,sc,-font.width(sc)/2,0,0xFFFFFFFF);
        g.pose().popPose();

        // 最高分
        g.drawCenteredString(font,"最高 "+bestScore,width/2,52,0xFF94A3B8);

        // Combo 提示
        if (combo>=2 && !gameOver) {
            int ca=(int)(200+55*Math.sin(tick*0.2));
            g.pose().pushPose();
            g.pose().translate(width/2f,72,0);
            float cs=1f+0.1f*(float)Math.sin(tick*0.15);
            g.pose().scale(cs,cs,1);
            String ct="🔥 "+combo+" 连击！";
            g.drawString(font,ct,-font.width(ct)/2,0,(ca<<24)|0x00FF6600);
            g.pose().popPose();
        }

        // 蓄力条（底部）
        if (charging && player.onGround) {
            int bw=200, bh=10;
            int bx=(width-bw)/2, by=height-36;
            g.fill(bx-2,by-2,bx+bw+2,by+bh+2,0xFF0F172A);
            g.fill(bx,by,bx+bw,by+bh,0xFF1E293B);
            // 渐变填充
            int fw=(int)(charge*bw);
            for (int i=0;i<fw;i++) {
                float t=(float)i/bw;
                int r=lerp(0x22,0xFF,t), gg=lerp(0xCC,0x44,t), b=lerp(0xFF,0x00,t);
                g.fill(bx+i,by,bx+i+1,by+bh,0xFF000000|(r<<16)|(gg<<8)|b);
            }
            // 蓄力条文字
            g.drawCenteredString(font,"POWER",width/2,by+bh+4,0xFF64748B);
            // 满力提示闪烁
            if (charge>=0.99f) {
                int fa=(int)(160+80*Math.sin(tick*0.3));
                g.drawCenteredString(font,"MAX!",width/2,by-14,(fa<<24)|0x00FFFF00);
            }
        }

        // 操作提示（初始阶段）
        if (score==0 && !charging && player.onGround) {
            int ha=(int)(150+80*Math.sin(tick*0.08));
            g.drawCenteredString(font,"按住 空格键 或 鼠标 蓄力，松开跳跃",
                width/2,height-20,(ha<<24)|0x00CCDDFF);
        }

        // R / ESC 提示
        g.drawString(font,"R:重开  ESC:退出",6,6,0xFF334455);
    }

    // ── 游戏结束 ────────────────────────────────────
    void renderGameOver(GuiGraphics g) {
        g.flush(); // 防止先绘制的游戏内容盖住遮罩背景（批量渲染text批次后置）
        g.fill(0,0,width,height,0xBB0A0F1A);
        int cx=width/2, cy=height/2;
        int ww=300, wh=160;
        int wx=(width-ww)/2, wy=(height-wh)/2;

        // 面板
        g.fill(wx,wy,wx+ww,wy+wh,0xFF0F172A);
        for (int i=0;i<3;i++) {
            g.fill(wx+i,wy+i,wx+ww-i,wy+i+1,0xFF3B82F6);
            g.fill(wx+i,wy+wh-i-1,wx+ww-i,wy+wh-i,0xFF1D4ED8);
        }

        g.drawCenteredString(font,"GAME OVER",cx,wy+20,0xFFEF4444);
        g.drawCenteredString(font,"得分  "+score,cx,wy+44,0xFFFFFFFF);
        if (score>=bestScore && score>0)
            g.drawCenteredString(font,"🏆 新纪录！",cx,wy+62,0xFFFFDD00);
        else
            g.drawCenteredString(font,"最高  "+bestScore,cx,wy+62,0xFF94A3B8);

        int btnY=wy+88;
        g.fill(wx+20,btnY,wx+130,btnY+24,0xFF1E3A5F);
        g.fill(wx+20,btnY,wx+130,btnY+1,0xFF3B82F6);
        g.drawCenteredString(font,"R — 重新开始",wx+75,btnY+8,0xFF60A5FA);

        g.fill(wx+150,btnY,wx+ww-20,btnY+24,0xFF1F1635);
        g.fill(wx+150,btnY,wx+ww-20,btnY+1,0xFF7C3AED);
        g.drawCenteredString(font,"ESC — 退出",wx+ww/2+40,btnY+8,0xFFA78BFA);
    }

    // ══════════════════════════════════════════════
    //  输入
    // ══════════════════════════════════════════════
    @Override
    public boolean mouseClicked(double mx,double my,int btn) {
        if (showExitConfirm) { int click = GameRenderHelper.getExitConfirmClick((int)mx, (int)my, width, height); if (click == 1) { showExitConfirm = false; Minecraft.getInstance().setScreen(new GameSelectorScreen()); return true; } if (click == 2) { showExitConfirm = false; return true; } return true; }
        if (gameOver) {
            int ww=300, wh=160;
            int wx=(width-ww)/2, wy=(height-wh)/2;
            int btnY=wy+88;
            if (mx>=wx+20&&mx<=wx+130&&my>=btnY&&my<=btnY+24) { startGame(); return true; }
            if (mx>=wx+150&&mx<=wx+ww-20&&my>=btnY&&my<=btnY+24) { Minecraft.getInstance().setScreen(new GameSelectorScreen()); return true; }
            return true;
        }
        if (btn==0) { if (player.onGround) charging=true; }
        return true;
    }
    @Override
    public boolean mouseReleased(double mx,double my,int btn) {
        if (btn==0 && charging) releaseJump();
        return true;
    }
    @Override
    public boolean keyPressed(int key,int scan,int mods) {
        if (key==GLFW.GLFW_KEY_ESCAPE) {
            if (showExitConfirm) { showExitConfirm = false; return true; }
            if (gameOver) { Minecraft.getInstance().setScreen(new GameSelectorScreen()); return true; }
            showExitConfirm = true; return true;
        }
        if (showExitConfirm) return true;
        if (key==GLFW.GLFW_KEY_R) { startGame(); return true; }
        if (key==GLFW.GLFW_KEY_SPACE && !gameOver && player.onGround) {
            charging=true; return true;
        }
        return super.keyPressed(key,scan,mods);
    }
    @Override
    public boolean keyReleased(int key,int scan,int mods) {
        if (key==GLFW.GLFW_KEY_SPACE && charging) { releaseJump(); return true; }
        return super.keyReleased(key,scan,mods);
    }

    // ══════════════════════════════════════════════
    //  绘图工具
    // ══════════════════════════════════════════════

    /** 填充任意四边形（分成两个三角形，用行扫描） */
    void fillQuad(GuiGraphics g, float[] p0, float[] p1, float[] p2, float[] p3, int color) {
        fillTri(g, p0, p1, p2, color);
        fillTri(g, p0, p2, p3, color);
    }

    void fillTri(GuiGraphics g, float[] a, float[] b, float[] c, int color) {
        int y0=(int)Math.min(a[1],Math.min(b[1],c[1]));
        int y1=(int)Math.max(a[1],Math.max(b[1],c[1]));
        if (y0==y1) return;
        float[][] pts={{a[0],a[1]},{b[0],b[1]},{c[0],c[1]}};
        // 按y排序
        if(pts[0][1]>pts[1][1]){float[] t=pts[0];pts[0]=pts[1];pts[1]=t;}
        if(pts[1][1]>pts[2][1]){float[] t=pts[1];pts[1]=pts[2];pts[2]=t;}
        if(pts[0][1]>pts[1][1]){float[] t=pts[0];pts[0]=pts[1];pts[1]=t;}
        for (int y=y0;y<=y1;y++) {
            if (y<0||y>=height) continue;
            float xl=width,xr=0;
            // 求y行的x范围
            float[][] segs={{pts[0][0],pts[0][1],pts[1][0],pts[1][1]},
                            {pts[1][0],pts[1][1],pts[2][0],pts[2][1]},
                            {pts[0][0],pts[0][1],pts[2][0],pts[2][1]}};
            for (float[] seg:segs) {
                float x1=seg[0],y1f=seg[1],x2=seg[2],y2f=seg[3];
                if ((y<Math.min(y1f,y2f))||(y>Math.max(y1f,y2f))) continue;
                float t=(y2f==y1f)?0:(y-y1f)/(y2f-y1f);
                float xi=x1+(x2-x1)*t;
                xl=Math.min(xl,xi); xr=Math.max(xr,xi);
            }
            if (xr>xl) g.fill((int)xl,y,(int)xr+1,y+1,color);
        }
    }

    void drawIsoCircle(GuiGraphics g, float wx, float wy, float wz, float r, int color) {
        int steps=24;
        for (int i=0;i<steps;i++) {
            float a1=(float)(Math.PI*2*i/steps);
            float a2=(float)(Math.PI*2*(i+1)/steps);
            float[] p0=project(wx+(float)Math.cos(a1)*r, wy, wz+(float)Math.sin(a1)*r);
            float[] p1=project(wx+(float)Math.cos(a2)*r, wy, wz+(float)Math.sin(a2)*r);
            drawIsoLine(g,p0,p1,color);
        }
    }

    void drawIsoFilledCircle(GuiGraphics g, float wx, float wy, float wz, float r, int color) {
        int steps=20;
        for (int i=0;i<steps;i++) {
            float a1=(float)(Math.PI*2*i/steps);
            float a2=(float)(Math.PI*2*(i+1)/steps);
            float[] p0=project(wx+(float)Math.cos(a1)*r, wy, wz+(float)Math.sin(a1)*r);
            float[] p1=project(wx+(float)Math.cos(a2)*r, wy, wz+(float)Math.sin(a2)*r);
            float[] pc=project(wx,wy,wz);
            fillTri(g,p0,p1,pc,color);
        }
    }

    void drawIsoLine(GuiGraphics g, float[] p0, float[] p1, int color) {
        int steps=(int)Math.max(1, Math.sqrt(Math.pow(p1[0]-p0[0],2)+Math.pow(p1[1]-p0[1],2)));
        for (int i=0;i<=steps;i++) {
            int x=(int)(p0[0]+(p1[0]-p0[0])*i/steps);
            int y=(int)(p0[1]+(p1[1]-p0[1])*i/steps);
            g.fill(x,y,x+1,y+1,color);
        }
    }

    void drawCircle(GuiGraphics g, int cx, int cy, int r, int color) {
        for (int a=0;a<360;a+=8) {
            int x=(int)(cx+r*Math.cos(Math.toRadians(a)));
            int y=(int)(cy+r*Math.sin(Math.toRadians(a)));
            g.fill(x,y,x+2,y+2,color);
        }
    }

    int shadeColor(int color, float shade) {
        int r=(int)(((color>>16)&0xFF)*shade);
        int gg=(int)(((color>>8)&0xFF)*shade);
        int b=(int)((color&0xFF)*shade);
        return (color&0xFF000000)|(r<<16)|(gg<<8)|b;
    }

    int lerpColor(int a, int b, float t) {
        int ar=(a>>16)&0xFF, ag=(a>>8)&0xFF, ab=a&0xFF;
        int br=(b>>16)&0xFF, bg=(b>>8)&0xFF, bb=b&0xFF;
        return 0xFF000000|
            (lerp(ar,br,t)<<16)|
            (lerp(ag,bg,t)<<8)|
            lerp(ab,bb,t);
    }

    int lerp(int a, int b, float t) { return (int)(a+(b-a)*t); }

    @Override public boolean isPauseScreen() { return false; }
}