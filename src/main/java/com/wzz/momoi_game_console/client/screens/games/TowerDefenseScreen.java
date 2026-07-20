package com.wzz.momoi_game_console.client.screens.games;

import com.wzz.momoi_game_console.client.screens.GameSelectorScreen;
import com.wzz.momoi_game_console.util.GameRenderHelper;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.api.distmarker.OnlyIn;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Iterator;
import java.util.List;

@OnlyIn(Dist.CLIENT)
public class TowerDefenseScreen extends Screen {
    boolean showExitConfirm = false;
    private static int GRID_SIZE = 32;
    private static final int GRID_WIDTH = 15;
    private static final int GRID_HEIGHT = 10;

    /** 缓存 ItemStack，避免每帧为每个实体创建新对象 */
    private static final ItemStack ITEM_BOW = new ItemStack(Items.BOW);
    private static final ItemStack ITEM_DISPENSER = new ItemStack(Items.DISPENSER);
    private static final ItemStack ITEM_BLAZE_POWDER = new ItemStack(Items.BLAZE_POWDER);
    private static final ItemStack ITEM_SLIME_BALL = new ItemStack(Items.SLIME_BALL);
    private static final ItemStack ITEM_ROTTEN_FLESH = new ItemStack(Items.ROTTEN_FLESH);
    private static final ItemStack ITEM_BONE = new ItemStack(Items.BONE);
    private static final ItemStack ITEM_ARROW = new ItemStack(Items.ARROW);
    private static final ItemStack ITEM_FIRE_CHARGE = new ItemStack(Items.FIRE_CHARGE);
    private static final ItemStack ITEM_IRON_NUGGET = new ItemStack(Items.IRON_NUGGET);
    
    // 游戏状态
    private int health = 20;
    private int coins = 100;
    private int wave = 1;
    private boolean gameStarted = false;
    private boolean gameOver = false;
    
    // 游戏对象列表
    private final List<Tower> towers = new ArrayList<>();
    private final List<Enemy> enemies = new ArrayList<>();
    private final List<Projectile> projectiles = new ArrayList<>();
    
    // 选中的塔类型
    private TowerType selectedTowerType = null;
    
    // 路径点（敌人行走路径）
    private final List<GridPos> path = Arrays.asList(
        new GridPos(0, 5), new GridPos(5, 5), new GridPos(5, 2),
        new GridPos(10, 2), new GridPos(10, 7), new GridPos(14, 7)
    );
    
    // 敌人生成计时器
    private int enemySpawnTimer = 0;
    private int enemiesSpawned = 0;
    
    public TowerDefenseScreen() {
        super(Component.literal("塔防游戏"));
    }
    
    @Override
    public void init() {
        super.init();
        
        // 塔选择按钮
        this.addRenderableWidget(Button.builder(Component.literal("弓箭塔 (10金币)"), button -> {
            if (coins >= 10) {
                selectedTowerType = TowerType.BOW_TOWER;
            }
        }).bounds(width - 150, 20, 140, 20).build());
        
        this.addRenderableWidget(Button.builder(Component.literal("大炮塔 (25金币)"), button -> {
            if (coins >= 25) {
                selectedTowerType = TowerType.CANNON_TOWER;
            }
        }).bounds(width - 150, 45, 140, 20).build());
        
        this.addRenderableWidget(Button.builder(Component.literal("火焰塔 (40金币)"), button -> {
            if (coins >= 40) {
                selectedTowerType = TowerType.FIRE_TOWER;
            }
        }).bounds(width - 150, 70, 140, 20).build());
        
        // 游戏控制按钮
        this.addRenderableWidget(Button.builder(Component.literal("开始游戏"), button -> {
            if (!gameStarted && !gameOver) {
                gameStarted = true;
            }
        }).bounds(width - 150, 120, 140, 20).build());
        
        this.addRenderableWidget(Button.builder(Component.literal("重新开始"), button -> {
            resetGame();
        }).bounds(width - 150, 145, 140, 20).build());

        this.addRenderableWidget(Button.builder(Component.literal("退出"), button -> {
            Minecraft.getInstance().setScreen(new GameSelectorScreen());
        }).bounds(width - 150, 170, 140, 20).build());
    }
    
    @Override
    public void render(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        GameRenderHelper.fillDarkBackground(graphics, width, height);
        
        // 绘制游戏网格
        renderGrid(graphics);
        
        // 绘制路径
        renderPath(graphics);
        
        // 绘制游戏对象
        renderTowers(graphics);
        renderEnemies(graphics);
        renderProjectiles(graphics);
        
        // 绘制UI信息
        renderUI(graphics);
        
        // 绘制选中塔的预览
        if (selectedTowerType != null) {
            renderTowerPreview(graphics, mouseX, mouseY);
        }
        
        super.render(graphics, mouseX, mouseY, partialTick);
        if (showExitConfirm) GameRenderHelper.drawExitConfirmOverlay(graphics, font, width, height, mouseX, mouseY);
    }
    
    private void renderGrid(GuiGraphics graphics) {
        int startX = 20;
        int startY = 20;
        
        // 绘制网格线
        for (int x = 0; x <= GRID_WIDTH; x++) {
            graphics.vLine(startX + x * GRID_SIZE, startY, startY + GRID_HEIGHT * GRID_SIZE, 0xFF666666);
        }
        for (int y = 0; y <= GRID_HEIGHT; y++) {
            graphics.hLine(startX, startX + GRID_WIDTH * GRID_SIZE, startY + y * GRID_SIZE, 0xFF666666);
        }
    }
    
    private void renderPath(GuiGraphics graphics) {
        int startX = 20;
        int startY = 20;
        
        for (GridPos pos : path) {
            int x = startX + pos.x * GRID_SIZE + 8;
            int y = startY + pos.y * GRID_SIZE + 8;
            graphics.fill(x, y, x + 16, y + 16, 0xFF8B4513); // 棕色路径
        }
    }
    
    private void renderTowers(GuiGraphics graphics) {
        int startX = 20;
        int startY = 20;
        
        for (Tower tower : towers) {
            int x = startX + tower.gridX * GRID_SIZE + 8;
            int y = startY + tower.gridY * GRID_SIZE + 8;
            
            // 根据塔类型绘制不同的物品图标（使用缓存的ItemStack）
            ItemStack item = switch (tower.type) {
                case BOW_TOWER -> ITEM_BOW;
                case CANNON_TOWER -> ITEM_DISPENSER;
                case FIRE_TOWER -> ITEM_BLAZE_POWDER;
            };
            
            graphics.renderItem(item, x, y);
            
            // 绘制射程范围（当鼠标悬停时）
            if (isMouseOverTower(tower, getMouseGridPos())) {
                drawRange(graphics, startX + tower.gridX * GRID_SIZE + 16, 
                         startY + tower.gridY * GRID_SIZE + 16, tower.range);
            }
        }
    }
    
    private void renderEnemies(GuiGraphics graphics) {
        int startX = 20;
        int startY = 20;
        
        for (Enemy enemy : enemies) {
            int x = startX + (int)enemy.x;
            int y = startY + (int)enemy.y;
            
            // 绘制敌人（使用缓存的ItemStack）
            ItemStack enemyItem = switch (enemy.type) {
                case SLIME -> ITEM_SLIME_BALL;
                case ZOMBIE -> ITEM_ROTTEN_FLESH;
                case SKELETON -> ITEM_BONE;
            };
            
            graphics.renderItem(enemyItem, x, y);
            
            // 绘制血量条
            if (enemy.health < enemy.maxHealth) {
                int barWidth = 20;
                int barHeight = 3;
                int barX = x - 2;
                int barY = y - 8;
                
                graphics.fill(barX, barY, barX + barWidth, barY + barHeight, 0xFF800000);
                int healthWidth = (int)(barWidth * enemy.health / enemy.maxHealth);
                graphics.fill(barX, barY, barX + healthWidth, barY + barHeight, 0xFF00FF00);
            }
        }
    }
    
    private void renderProjectiles(GuiGraphics graphics) {
        int startX = 20;
        int startY = 20;
        
        for (Projectile proj : projectiles) {
            int x = startX + (int)proj.x;
            int y = startY + (int)proj.y;
            
            ItemStack projItem = switch (proj.type) {
                case ARROW -> ITEM_ARROW;
                case FIREBALL -> ITEM_FIRE_CHARGE;
                case CANNONBALL -> ITEM_IRON_NUGGET;
            };
            
            graphics.renderItem(projItem, x, y);
        }
    }
    
    private void renderUI(GuiGraphics graphics) {
        // 绘制游戏状态信息
        graphics.drawString(this.font, "生命值: " + health, width - 150, height - 100, 0xFFFFFF);
        graphics.drawString(this.font, "金币: " + coins, width - 150, height - 85, 0xFFFFD700);
        graphics.drawString(this.font, "波次: " + wave, width - 150, height - 70, 0xFFFFFFFF);
        
        if (gameOver) {
            String text = health <= 0 ? "游戏失败!" : "游戏胜利!";
            int textWidth = this.font.width(text);
            graphics.drawString(this.font, text, (width - textWidth) / 2, height / 2, 0xFFFF0000);
        }
        
        if (selectedTowerType != null) {
            graphics.drawString(this.font, "选中: " + selectedTowerType.name, 
                              width - 150, height - 50, 0xFF00FF00);
        }
    }
    
    private void renderTowerPreview(GuiGraphics graphics, int mouseX, int mouseY) {
        GridPos gridPos = screenToGrid(mouseX, mouseY);
        if (canPlaceTower(gridPos.x, gridPos.y)) {
            int startX = 20;
            int startY = 20;
            int x = startX + gridPos.x * GRID_SIZE;
            int y = startY + gridPos.y * GRID_SIZE;
            
            // 绘制预览框
            graphics.fill(x, y, x + GRID_SIZE, y + GRID_SIZE, 0x8000FF00);
            
            // 绘制射程预览
            drawRange(graphics, x + 16, y + 16, selectedTowerType.range);
        }
    }
    
    private void drawRange(GuiGraphics graphics, int centerX, int centerY, int range) {
        // 简单的圆形射程显示
        graphics.fill(centerX - range, centerY - range, 
                     centerX + range, centerY + range, 0x40FFFFFF);
    }
    
    @Override
    public boolean keyPressed(int keyCode, int scanCode, int modifiers) {
        if (keyCode == 256) { if (showExitConfirm) { showExitConfirm = false; } else { showExitConfirm = true; } return true; }
        if (showExitConfirm) return true;
        return super.keyPressed(keyCode, scanCode, modifiers);
    }

    @Override
    public boolean mouseClicked(double mouseX, double mouseY, int button) {
        if (showExitConfirm) { int click = GameRenderHelper.getExitConfirmClick(mouseX, mouseY, width, height); if (click == 1) { showExitConfirm = false; Minecraft.getInstance().setScreen(new GameSelectorScreen()); return true; } if (click == 2) { showExitConfirm = false; return true; } return true; }
        if (selectedTowerType != null && button == 0) {
            GridPos pos = screenToGrid((int)mouseX, (int)mouseY);
            if (canPlaceTower(pos.x, pos.y) && coins >= selectedTowerType.cost) {
                towers.add(new Tower(pos.x, pos.y, selectedTowerType));
                coins -= selectedTowerType.cost;
                selectedTowerType = null;
                
                // 播放放置音效
                Minecraft.getInstance().level.playLocalSound(
                    Minecraft.getInstance().player.getX(),
                    Minecraft.getInstance().player.getY(),
                    Minecraft.getInstance().player.getZ(),
                    SoundEvents.STONE_PLACE, SoundSource.BLOCKS, 0.5f, 1.0f, false
                );
                return true;
            }
        }
        return super.mouseClicked(mouseX, mouseY, button);
    }
    
    @Override
    public void tick() {
        super.tick();
        
        if (!gameStarted || gameOver) return;
        
        // 生成敌人
        spawnEnemies();
        
        // 更新敌人
        updateEnemies();
        
        // 更新塔攻击
        updateTowers();
        
        // 更新弹药
        updateProjectiles();
        
        // 检查游戏结束条件
        checkGameOver();
    }
    
    private void spawnEnemies() {
        enemySpawnTimer++;
        if (enemySpawnTimer >= 60 && enemiesSpawned < wave * 10) { // 每秒生成一个敌人
            EnemyType type = EnemyType.values()[enemiesSpawned % 3];
            enemies.add(new Enemy(type, path));
            enemiesSpawned++;
            enemySpawnTimer = 0;
        }
        
        // 检查是否开始下一波
        if (enemies.isEmpty() && enemiesSpawned >= wave * 10) {
            wave++;
            enemiesSpawned = 0;
            coins += 50; // 波次奖励
            if (Minecraft.getInstance().player != null) {
                Minecraft.getInstance().player.playSound(SoundEvents.PLAYER_LEVELUP, 1.0F, 1.0F);
            }
        }
    }
    
    private void updateEnemies() {
        Iterator<Enemy> it = enemies.iterator();
        while (it.hasNext()) {
            Enemy enemy = it.next();
            enemy.update();
            
            if (enemy.reachedEnd) {
                health--;
                it.remove();
            } else if (enemy.health <= 0) {
                coins += enemy.reward;
                it.remove();
            }
        }
    }
    
    private void updateTowers() {
        for (Tower tower : towers) {
            tower.update();
            if (tower.canAttack()) {
                Enemy target = findNearestEnemy(tower);
                if (target != null) {
                    ProjectileType projType = switch (tower.type) {
                        case BOW_TOWER -> ProjectileType.ARROW;
                        case CANNON_TOWER -> ProjectileType.CANNONBALL;
                        case FIRE_TOWER -> ProjectileType.FIREBALL;
                    };

                    projectiles.add(new Projectile(
                            tower.gridX * GRID_SIZE + 16,
                            tower.gridY * GRID_SIZE + 16,
                            target, projType, tower.damage
                    ));
                    tower.resetAttackTimer();
                    SoundEvent shootSound = switch (tower.type) {
                        case BOW_TOWER -> SoundEvents.ARROW_SHOOT;
                        case CANNON_TOWER -> SoundEvents.GENERIC_EXPLODE.value(); // 你可以换成其他炮塔发射音效
                        case FIRE_TOWER -> SoundEvents.BLAZE_SHOOT;
                    };

                    if (Minecraft.getInstance().level != null) {
                        if (Minecraft.getInstance().player != null) {
                            Minecraft.getInstance().level.playLocalSound(
                                    tower.gridX * GRID_SIZE + 16,
                                    tower.gridY * GRID_SIZE + 16,
                                    Minecraft.getInstance().player.getZ(),
                                    shootSound,
                                    SoundSource.BLOCKS,
                                    0.8f, 1.0f, false
                            );
                        }
                    }
                }
            }
        }
    }
    
    private void updateProjectiles() {
        Iterator<Projectile> it = projectiles.iterator();
        while (it.hasNext()) {
            Projectile proj = it.next();
            proj.update();
            
            if (proj.hitTarget()) {
                proj.target.takeDamage(proj.damage);
                it.remove();
            } else if (proj.isExpired()) {
                it.remove();
            }
        }
    }
    
    private Enemy findNearestEnemy(Tower tower) {
        Enemy nearest = null;
        double minDistance = Double.MAX_VALUE;
        
        for (Enemy enemy : enemies) {
            double dx = enemy.x - (tower.gridX * GRID_SIZE + 16);
            double dy = enemy.y - (tower.gridY * GRID_SIZE + 16);
            double distance = Math.sqrt(dx * dx + dy * dy);
            
            if (distance <= tower.range && distance < minDistance) {
                nearest = enemy;
                minDistance = distance;
            }
        }
        
        return nearest;
    }
    
    private void checkGameOver() {
        if (health <= 0) {
            gameOver = true;
            gameStarted = false;
        }
    }
    
    private void resetGame() {
        health = 20;
        coins = 100;
        wave = 1;
        gameStarted = false;
        gameOver = false;
        enemiesSpawned = 0;
        enemySpawnTimer = 0;
        
        towers.clear();
        enemies.clear();
        projectiles.clear();
        selectedTowerType = null;
    }
    
    // 辅助方法
    private GridPos screenToGrid(int screenX, int screenY) {
        return new GridPos((screenX - 20) / GRID_SIZE, (screenY - 20) / GRID_SIZE);
    }
    
    private GridPos getMouseGridPos() {
        // 这里需要获取当前鼠标位置，简化处理
        return new GridPos(0, 0);
    }
    
    private boolean canPlaceTower(int gridX, int gridY) {
        if (gridX < 0 || gridX >= GRID_WIDTH || gridY < 0 || gridY >= GRID_HEIGHT) {
            return false;
        }
        
        // 检查路径
        for (GridPos pathPoint : path) {
            if (pathPoint.x == gridX && pathPoint.y == gridY) {
                return false;
            }
        }
        
        // 检查是否已有塔
        for (Tower tower : towers) {
            if (tower.gridX == gridX && tower.gridY == gridY) {
                return false;
            }
        }
        
        return true;
    }
    
    private boolean isMouseOverTower(Tower tower, GridPos mousePos) {
        return tower.gridX == mousePos.x && tower.gridY == mousePos.y;
    }

    @Override
    public boolean isPauseScreen() { return false; }
    
    // 内部类定义
    public static class GridPos {
        public int x, y;
        public GridPos(int x, int y) { this.x = x; this.y = y; }
    }
    
    public enum TowerType {
        BOW_TOWER("弓箭塔", 10, 80, 20, 30),
        CANNON_TOWER("大炮塔", 25, 60, 50, 40),
        FIRE_TOWER("火焰塔", 40, 40, 80, 25);
        
        public final String name;
        public final int cost;
        public final int range;
        public final int damage;
        public final int attackSpeed;
        
        TowerType(String name, int cost, int range, int damage, int attackSpeed) {
            this.name = name;
            this.cost = cost;
            this.range = range;
            this.damage = damage;
            this.attackSpeed = attackSpeed;
        }
    }
    
    public enum EnemyType {
        SLIME(50, 1.0f, 5),
        ZOMBIE(100, 0.8f, 10),
        SKELETON(80, 1.2f, 8);
        
        public final int health;
        public final float speed;
        public final int reward;
        
        EnemyType(int health, float speed, int reward) {
            this.health = health;
            this.speed = speed;
            this.reward = reward;
        }
    }
    
    public enum ProjectileType {
        ARROW(3.0f),
        CANNONBALL(2.0f),
        FIREBALL(2.5f);
        
        public final float speed;
        
        ProjectileType(float speed) {
            this.speed = speed;
        }
    }
    
    // 游戏对象类
    public static class Tower {
        public int gridX, gridY;
        public TowerType type;
        public int range;
        public int damage;
        public int attackTimer;
        public int attackSpeed;
        
        public Tower(int x, int y, TowerType type) {
            this.gridX = x;
            this.gridY = y;
            this.type = type;
            this.range = type.range;
            this.damage = type.damage;
            this.attackSpeed = type.attackSpeed;
            this.attackTimer = 0;
        }
        
        public void update() {
            if (attackTimer > 0) attackTimer--;
        }
        
        public boolean canAttack() {
            return attackTimer <= 0;
        }
        
        public void resetAttackTimer() {
            attackTimer = attackSpeed;
        }
    }
    
    public static class Enemy {
        public float x, y;
        public EnemyType type;
        public int health, maxHealth;
        public float speed;
        public int reward;
        public boolean reachedEnd = false;
        
        private List<GridPos> path;
        private int pathIndex = 0;
        
        public Enemy(EnemyType type, List<GridPos> path) {
            this.type = type;
            this.health = this.maxHealth = type.health;
            this.speed = type.speed;
            this.reward = type.reward;
            this.path = path;
            
            if (!path.isEmpty()) {
                this.x = path.get(0).x * GRID_SIZE + 8;
                this.y = path.get(0).y * GRID_SIZE + 8;
            }
        }
        
        public void update() {
            if (pathIndex >= path.size() - 1) {
                reachedEnd = true;
                return;
            }
            
            GridPos target = path.get(pathIndex + 1);
            float targetX = target.x * GRID_SIZE + 8;
            float targetY = target.y * GRID_SIZE + 8;
            
            float dx = targetX - x;
            float dy = targetY - y;
            float distance = (float)Math.sqrt(dx * dx + dy * dy);
            
            if (distance < speed) {
                pathIndex++;
                if (pathIndex < path.size()) {
                    x = path.get(pathIndex).x * GRID_SIZE + 8;
                    y = path.get(pathIndex).y * GRID_SIZE + 8;
                }
            } else {
                x += (dx / distance) * speed;
                y += (dy / distance) * speed;
            }
        }
        
        public void takeDamage(int damage) {
            health -= damage;
        }
    }
    
    public static class Projectile {
        public float x, y;
        public Enemy target;
        public ProjectileType type;
        public int damage;
        public float speed;
        
        public Projectile(float x, float y, Enemy target, ProjectileType type, int damage) {
            this.x = x;
            this.y = y;
            this.target = target;
            this.type = type;
            this.damage = damage;
            this.speed = type.speed;
        }
        
        public void update() {
            if (target == null || target.health <= 0) return;
            
            float dx = target.x + 8 - x;
            float dy = target.y + 8 - y;
            float distance = (float)Math.sqrt(dx * dx + dy * dy);
            
            if (distance > speed) {
                x += (dx / distance) * speed;
                y += (dy / distance) * speed;
            } else {
                x = target.x + 8;
                y = target.y + 8;
            }
        }
        
        public boolean hitTarget() {
            if (target == null) return false;
            float dx = target.x + 8 - x;
            float dy = target.y + 8 - y;
            return Math.sqrt(dx * dx + dy * dy) < 8;
        }
        
        public boolean isExpired() {
            return target == null || target.health <= 0;
        }
    }
}