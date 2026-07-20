package com.wzz.momoi_game_console.client.screens.games.gogame;

class GoMove {
    public final int x, y;
    public final GoPlayer player;
    public final int capturedStones;
    
    public GoMove(int x, int y, GoPlayer player, int capturedStones) {
        this.x = x;
        this.y = y;
        this.player = player;
        this.capturedStones = capturedStones;
    }
}