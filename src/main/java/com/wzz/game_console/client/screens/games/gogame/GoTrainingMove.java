package com.wzz.game_console.client.screens.games.gogame;

final class GoTrainingMove {
    private GoTrainingMove() {}

    record Applied(int[] coordinates, double[] policy) {}

    static Applied apply(GoGame game, int[] suggested, double[] policy) {
        if (suggested == null) {
            game.pass();
            return new Applied(null, policy);
        }
        if (suggested.length >= 2 && game.placeStone(suggested[0], suggested[1])) {
            return new Applied(new int[]{suggested[0], suggested[1]}, policy);
        }
        double[] fallbackPolicy = new double[362];
        for (int x = 0; x < game.getBoardSize(); x++) {
            for (int y = 0; y < game.getBoardSize(); y++) {
                if (game.placeStone(x, y)) {
                    fallbackPolicy[x * game.getBoardSize() + y] = 1.0;
                    return new Applied(new int[]{x, y}, fallbackPolicy);
                }
            }
        }
        game.pass();
        fallbackPolicy[361] = 1.0;
        return new Applied(null, fallbackPolicy);
    }
}
