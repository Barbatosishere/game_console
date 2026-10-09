package com.wzz.game_console.client.screens;

import com.wzz.game_console.client.screens.games.*;
import com.wzz.game_console.client.screens.games.gogame.GoGame;
import com.wzz.game_console.client.screens.games.gogame.GoGameScreen;
import com.wzz.game_console.client.screens.games.landlord.LandlordGameScreen;
import com.wzz.game_console.client.screens.games.tictactoe.TicTacToeGame;
import com.wzz.game_console.client.screens.games.tictactoe.TicTacToeScreen;
import net.minecraft.client.gui.screens.Screen;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.api.distmarker.OnlyIn;

@OnlyIn(Dist.CLIENT)
public final class GameScreens {
    private GameScreens() {}
    public static Screen create(String id) {
        return switch (id) {
            case "gomoku" -> new GomokuScreen();
            case "go" -> new GoGameScreen(new GoGame());
            case "tictactoe" -> new TicTacToeScreen(TicTacToeGame.GameMode.SINGLE_PLAYER);
            case "chess" -> new ChessGameScreen();
            case "wchess" -> new WesternChessScreen();
            case "landlord" -> new LandlordGameScreen();
            case "dice" -> new DiceGuessingScreen();
            case "snake" -> new SnakeGameScreen();
            case "flappybird" -> new FlappyBirdScreen();
            case "breakout" -> new BreakoutScreen();
            case "platformer" -> new PlatformerScreen();
            case "icefire" -> new IceFireGameScreen();
            case "blackhole" -> new BlackHoleGameScreen();
            case "fruitninja" -> new FruitNinjaScreen();
            case "colorchase" -> new ColorChaseGameScreen();
            case "jump" -> new JumpGameScreen();
            case "minesweeper" -> new MinesweeperScreen();
            case "tetris" -> new TetrisGameScreen();
            case "pipepuzzle" -> new PipePuzzleScreen();
            case "sokoban" -> new SokobanScreen();
            case "klotski" -> new KlotskiScreen();
            case "puzzle" -> new PuzzleGameScreen();
            case "sudoku" -> new SudokuGameScreen();
            case "maze" -> new MazeGameScreen();
            case "memorycard" -> new MemoryCardScreen();
            case "match3" -> new Match3GameScreen();
            case "towerdefense" -> new TowerDefenseScreen();
            case "memory" -> new MemoryGameScreen();
            case "mousetunnel" -> new MouseTunnelGameScreen();
            case "minecraft2d" -> new Minecraft2DScreen();
            case "pianotiles" -> new PianoTilesGameScreen();
            case "whackamole" -> new WhackAMoleScreen();
            default -> throw new IllegalArgumentException("Unknown game: " + id);
        };
    }
}
