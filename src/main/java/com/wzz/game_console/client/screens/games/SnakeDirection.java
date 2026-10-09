package com.wzz.game_console.client.screens.games;

/** Checks turns against the direction used by the previous movement tick. */
final class SnakeDirection {
    private SnakeDirection() {}

    static boolean canTurn(int lastDx, int lastDy, int nextDx, int nextDy) {
        return nextDx != -lastDx || nextDy != -lastDy;
    }
}
