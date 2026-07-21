package com.wzz.game_console.client.screens.games;

import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.PoseStack;
import com.wzz.game_console.client.screens.GameSelectorScreen;
import com.wzz.game_console.util.GameRenderHelper;
import com.wzz.game_console.util.ResourceUtil;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.util.Mth;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.api.distmarker.OnlyIn;
import org.lwjgl.glfw.GLFW;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;

@OnlyIn(Dist.CLIENT)
public class BlackHoleGameScreen extends Screen {
    boolean showExitConfirm = false;
    private static final ResourceLocation BACKGROUND = ResourceUtil.createMinecraftInstance("textures/block/obsidian.png");
    
    private GameState gameState;
    private Player player;
    private List<Enemy> enemies;
    private List<Food> foods;
    private List<Particle> particles;
    private Random random;
    private int gameTime;
    private float cameraX, cameraY, cameraZ;
    private final boolean[] keys = new boolean[512];
    
    public BlackHoleGameScreen() {
        super(Component.literal("黑洞大作战"));
        this.gameState = GameState.PLAYING;
        this.random = new Random();
        initGame();
    }
    
    private void initGame() {
        // 初始化玩家 (屏幕中心)
        this.player = new Player(0, 0, 0, 20);
        
        // 初始化敌人列表
        this.enemies = new ArrayList<>();
        for (int i = 0; i < 10; i++) {
            enemies.add(new Enemy(
                random.nextFloat() * 400 - 200,
                random.nextFloat() * 400 - 200,
                random.nextFloat() * 100 - 50,
                random.nextFloat() * 15 + 5
            ));
        }
        
        // 初始化食物
        this.foods = new ArrayList<>();
        for (int i = 0; i < 50; i++) {
            foods.add(new Food(
                random.nextFloat() * 600 - 300,
                random.nextFloat() * 600 - 300,
                random.nextFloat() * 100 - 50,
                random.nextFloat() * 5 + 1
            ));
        }
        
        this.particles = new ArrayList<>();
        this.gameTime = 0;
        this.cameraX = 0;
        this.cameraY = 0;
        this.cameraZ = 50;
    }
    
    @Override
    public void init() {
        super.init();
    }
    
    @Override
    public void tick() {
        super.tick();
        gameTime++;
        
        if (gameState == GameState.PLAYING) {
            updateGame();
        }
    }
    
    private void updateGame() {
        // 更新玩家位置
        updatePlayer();
        
        // 更新敌人AI
        updateEnemies();
        
        // 检测碰撞
        checkCollisions();
        
        // 更新粒子效果
        updateParticles();
        
        // 更新摄像机跟随玩家
        cameraX = Mth.lerp(0.1f, cameraX, player.x);
        cameraY = Mth.lerp(0.1f, cameraY, player.y);
        
        // 生成新的食物
        if (gameTime % 60 == 0 && foods.size() < 100) {
            foods.add(new Food(
                random.nextFloat() * 800 - 400,
                random.nextFloat() * 800 - 400,
                random.nextFloat() * 100 - 50,
                random.nextFloat() * 3 + 1
            ));
        }

        generateNearbyEntities();
    }
    
    private void updatePlayer() {
        float speed = 2.0f;
        
        // 键盘控制
        if (keys[87]) player.y -= speed; // W
        if (keys[83]) player.y += speed; // S
        if (keys[65]) player.x -= speed; // A
        if (keys[68]) player.x += speed; // D
        
        // 限制移动范围
//        player.x = Mth.clamp(player.x, -500, 500);
//        player.y = Mth.clamp(player.y, -500, 500);
    }

    private void generateNearbyEntities() {
        int range = 500; // 每次扩展区域的半径

        // 食物生成
        while (foods.size() < 100) {
            float fx = player.x + random.nextFloat() * range * 2 - range;
            float fy = player.y + random.nextFloat() * range * 2 - range;

            // 判断是否已经有食物在这附近
            boolean near = false;
            for (Food f : foods) {
                if (Math.abs(f.x - fx) < 10 && Math.abs(f.y - fy) < 10) {
                    near = true;
                    break;
                }
            }

            if (!near) {
                foods.add(new Food(fx, fy, random.nextFloat() * 100 - 50, random.nextFloat() * 3 + 1));
            }
        }

        // 敌人生成（适当控制数量）
        while (enemies.size() < 30) {
            float ex = player.x + random.nextFloat() * range * 2 - range;
            float ey = player.y + random.nextFloat() * range * 2 - range;

            boolean near = false;
            for (Enemy e : enemies) {
                if (Math.abs(e.x - ex) < 10 && Math.abs(e.y - ey) < 10) {
                    near = true;
                    break;
                }
            }

            if (!near) {
                float size;
                if (random.nextFloat() < 0.3f) { // 30% 概率生成比玩家大的敌人
                    size = player.size + random.nextFloat() * player.size * 0.5f; // 比玩家大 0%~50%
                } else {
                    size = random.nextFloat() * player.size * 0.8f; // 比玩家小
                    size = Mth.clamp(size, 2.0f, 30.0f); // 限制范围
                }

                enemies.add(new Enemy(ex, ey, random.nextFloat() * 100 - 50, size));
            }
        }
    }

    private void updateEnemies() {
        for (Enemy enemy : enemies) {
            float dx = player.x - enemy.x;
            float dy = player.y - enemy.y;
            float distance = (float) Math.sqrt(dx * dx + dy * dy);

            float speed = 0.5f;

            if (distance < 200) {
                dx /= distance;
                dy /= distance;

                if (player.size > enemy.size * 1.1f) {
                    // 靠得近且玩家大，逃跑
                    enemy.x -= dx * speed;
                    enemy.y -= dy * speed;
                } else if (player.size < enemy.size * 0.8f) {
                    // 靠得近且敌人大，追击
                    enemy.x += dx * speed;
                    enemy.y += dy * speed;
                } // 否则就不动
            }

            // 简单的随机移动（增强智能感）
            if (random.nextFloat() < 0.02f) {
                enemy.x += (random.nextFloat() - 0.5f) * 2;
                enemy.y += (random.nextFloat() - 0.5f) * 2;
            }

            // 敌人之间碰撞略微推动（保留）
            for (Enemy other : enemies) {
                if (other != enemy) {
                    float edx = other.x - enemy.x;
                    float edy = other.y - enemy.y;
                    float edist = (float) Math.sqrt(edx * edx + edy * edy);

                    if (edist < enemy.size + other.size && edist > 0) {
                        float overlap = (enemy.size + other.size - edist) / 2f;
                        enemy.x -= edx / edist * overlap;
                        enemy.y -= edy / edist * overlap;
                    }
                }
            }
        }

        // 移除过小的敌人
        enemies.removeIf(enemy -> enemy.size < 2);
    }


    private void checkCollisions() {
        // 玩家吃食物
        foods.removeIf(food -> {
            float dx = player.x - food.x;
            float dy = player.y - food.y;
            float distance = (float) Math.sqrt(dx * dx + dy * dy);
            
            if (distance < player.size / 2 + food.size) {
                player.size += food.size * 0.2f;
                createParticles(food.x, food.y, 5);
                return true;
            }
            return false;
        });
        
        // 玩家与敌人碰撞
        for (Enemy enemy : enemies) {
            float dx = player.x - enemy.x;
            float dy = player.y - enemy.y;
            float distance = (float) Math.sqrt(dx * dx + dy * dy);
            
            if (distance < player.size / 2 + enemy.size / 2) {
                if (player.size > enemy.size) {
                    player.size += enemy.size * 0.3f;
                    enemy.size = 0; // 标记为删除
                    createParticles(enemy.x, enemy.y, 10);
                } else if (enemy.size > player.size) {
                    gameState = GameState.GAME_OVER;
                }
            }
        }
    }
    
    private void createParticles(float x, float y, int count) {
        for (int i = 0; i < count; i++) {
            particles.add(new Particle(
                x + random.nextFloat() * 20 - 10,
                y + random.nextFloat() * 20 - 10,
                random.nextFloat() * 4 - 2,
                random.nextFloat() * 4 - 2,
                30 + random.nextInt(30)
            ));
        }
        if (Minecraft.getInstance().player != null) {
            Minecraft.getInstance().player.playSound(SoundEvents.EXPERIENCE_ORB_PICKUP, 1.0F, 1.0F);
        }
    }
    
    private void updateParticles() {
        particles.removeIf(particle -> {
            particle.x += particle.vx;
            particle.y += particle.vy;
            particle.life--;
            return particle.life <= 0;
        });
    }
    
    @Override
    public void render(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        // 绘制背景
        renderBackground(graphics);
        
        PoseStack poseStack = graphics.pose();
        poseStack.pushPose();
        
        // 应用摄像机变换
        poseStack.translate(width / 2.0, height / 2.0, 0);
        poseStack.translate(-cameraX, -cameraY, 0);
        
        if (gameState == GameState.PLAYING) {
            // 渲染游戏对象
            renderFoods(graphics, poseStack);
            renderPlayer(graphics, poseStack);
            renderEnemies(graphics, poseStack);
            renderParticles(graphics, poseStack);
        }
        
        poseStack.popPose();
        
        // 渲染UI
        renderUI(graphics);
        
        super.render(graphics, mouseX, mouseY, partialTick);
        if (showExitConfirm) GameRenderHelper.drawExitConfirmOverlay(graphics, font, width, height, mouseX, mouseY);
    }
    
    @Override
    public void renderBackground(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        // 游戏已在render()中绘制不透明背景,阻止默认32x32菜单纹理和模糊效果
    }

    public void renderBackground(GuiGraphics graphics) {
        // 使用黑曜石纹理作为背景
        RenderSystem.setShaderTexture(0, BACKGROUND);
        graphics.blit(BACKGROUND, 0, 0, 0, 0, width, height, 16, 16);
    }
    
    private void renderPlayer(GuiGraphics graphics, PoseStack poseStack) {
        int size = (int) (player.size * (50 + cameraZ) / 100.0f);
        int x = (int) player.x - size / 2;
        int y = (int) player.y - size / 2;
        
        // 使用钻石块纹理表示玩家
        ResourceLocation diamond = ResourceUtil.createMinecraftInstance("textures/block/diamond_block.png");
        graphics.blit(diamond, x, y, 0, 0, size, size, 16, 16);
        
        // 绘制光环效果
        drawCircle(graphics, (int) player.x, (int) player.y, size / 2 + 2, 0x4400FFFF);
    }
    
    private void renderEnemies(GuiGraphics graphics, PoseStack poseStack) {
        ResourceLocation redstone = ResourceUtil.createMinecraftInstance("textures/block/redstone_block.png");
        
        for (Enemy enemy : enemies) {
            if (enemy.size <= 0) continue;
            
            float distance = (float) Math.sqrt(
                (enemy.x - cameraX) * (enemy.x - cameraX) + 
                (enemy.y - cameraY) * (enemy.y - cameraY)
            );
            
            int size = (int) (enemy.size * (50 + cameraZ - distance * 0.1f) / 100.0f);
            if (size < 2) continue;
            
            int x = (int) enemy.x - size / 2;
            int y = (int) enemy.y - size / 2;
            
            graphics.blit(redstone, x, y, 0, 0, size, size, 16, 16);
        }
    }
    
    private void renderFoods(GuiGraphics graphics, PoseStack poseStack) {
        ResourceLocation emerald = ResourceUtil.createMinecraftInstance("textures/block/emerald_block.png");
        
        for (Food food : foods) {
            float distance = (float) Math.sqrt(
                (food.x - cameraX) * (food.x - cameraX) + 
                (food.y - cameraY) * (food.y - cameraY)
            );
            
            int size = Math.max(2, (int) (food.size * (50 + cameraZ - distance * 0.1f) / 100.0f));
            int x = (int) food.x - size / 2;
            int y = (int) food.y - size / 2;
            
            graphics.blit(emerald, x, y, 0, 0, size, size, 16, 16);
        }
    }
    
    private void renderParticles(GuiGraphics graphics, PoseStack poseStack) {
        for (Particle particle : particles) {
            int alpha = (int) (255 * particle.life / 60.0f);
            int color = (alpha << 24) | 0xFFFF00;
            
            graphics.fill((int) particle.x - 1, (int) particle.y - 1, 
                         (int) particle.x + 1, (int) particle.y + 1, color);
        }
    }
    
    private void renderUI(GuiGraphics graphics) {
        // 分数显示
        String score = "大小: " + String.format("%.1f", player.size);
        graphics.drawString(minecraft.font, score, 10, 10, 0xFFFFFF);
        
        // 敌人数量
//        String enemies = "敌人: " + this.enemies.size();
//        graphics.drawString(minecraft.font, enemies, 10, 25, 0xFFFFFF);
        
        // 游戏结束界面
        if (gameState == GameState.GAME_OVER) {
            int centerX = width / 2;
            int centerY = height / 2;
            
            graphics.flush(); // 防止先绘制的游戏内容盖住遮罩背景（批量渲染text批次后置）
            graphics.fill(0, 0, width, height, 0x80000000);
            
            String gameOver = "游戏结束!";
            int textWidth = minecraft.font.width(gameOver);
            graphics.drawString(minecraft.font, gameOver, 
                              centerX - textWidth / 2, centerY - 20, 0xFF0000);
            
            String restart = "按 R 重新开始";
            int restartWidth = minecraft.font.width(restart);
            graphics.drawString(minecraft.font, restart, 
                              centerX - restartWidth / 2, centerY + 10, 0xFFFFFF);
        }
    }
    
    private void drawCircle(GuiGraphics graphics, int centerX, int centerY, int radius, int color) {
        for (int i = 0; i < 360; i += 10) {
            double angle = Math.toRadians(i);
            int x = centerX + (int) (Math.cos(angle) * radius);
            int y = centerY + (int) (Math.sin(angle) * radius);
            graphics.fill(x - 1, y - 1, x + 1, y + 1, color);
        }
    }
    
    @Override
    public boolean keyPressed(int keyCode, int scanCode, int modifiers) {
        if (keyCode == GLFW.GLFW_KEY_ESCAPE) {
            if (showExitConfirm) { showExitConfirm = false; return true; }
            if (gameState == GameState.GAME_OVER) { Minecraft.getInstance().setScreen(new GameSelectorScreen()); return true; }
            showExitConfirm = true; return true;
        }
        if (showExitConfirm) return true;
        keys[keyCode] = true;
        
        // R键重新开始
        if (keyCode == 82 && gameState == GameState.GAME_OVER) {
            initGame();
        }
        
        return super.keyPressed(keyCode, scanCode, modifiers);
    }
    
    @Override
    public boolean keyReleased(int keyCode, int scanCode, int modifiers) {
        keys[keyCode] = false;
        return super.keyReleased(keyCode, scanCode, modifiers);
    }

    @Override
    public boolean mouseClicked(double mx, double my, int btn) {
        if (showExitConfirm) { int click = GameRenderHelper.getExitConfirmClick((int)mx, (int)my, width, height); if (click == 1) { showExitConfirm = false; Minecraft.getInstance().setScreen(new GameSelectorScreen()); return true; } if (click == 2) { showExitConfirm = false; return true; } return true; }
        return super.mouseClicked(mx, my, btn);
    }
    
    @Override
    public boolean isPauseScreen() {
        return false;
    }
    
    // 游戏状态枚举
    private enum GameState {
        PLAYING,
        GAME_OVER
    }
    
    // 游戏对象类
    private static class GameObject {
        float x, y, z, size;
        
        GameObject(float x, float y, float z, float size) {
            this.x = x;
            this.y = y;
            this.z = z;
            this.size = size;
        }
    }
    
    private static class Player extends GameObject {
        Player(float x, float y, float z, float size) {
            super(x, y, z, size);
        }
    }
    
    private static class Enemy extends GameObject {
        Enemy(float x, float y, float z, float size) {
            super(x, y, z, size);
        }
    }
    
    private static class Food extends GameObject {
        Food(float x, float y, float z, float size) {
            super(x, y, z, size);
        }
    }
    
    private static class Particle {
        float x, y, vx, vy;
        int life;
        
        Particle(float x, float y, float vx, float vy, int life) {
            this.x = x;
            this.y = y;
            this.vx = vx;
            this.vy = vy;
            this.life = life;
        }
    }
}