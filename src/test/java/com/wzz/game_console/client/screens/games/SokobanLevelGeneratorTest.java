package com.wzz.game_console.client.screens.games;

import org.junit.jupiter.api.Test;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Random;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

class SokobanLevelGeneratorTest {
    private static final int[][] DIRECTIONS = {{1, 0}, {-1, 0}, {0, 1}, {0, -1}};

    @Test
    void firstLevelNoLongerStrandsItsBoxAgainstTheBottomWall() {
        char[][] originalFailure = {
                "#####".toCharArray(), "#   #".toCharArray(), "##* #".toCharArray(),
                "# $ #".toCharArray(), "#####".toCharArray()
        };
        assertFalse(hasPushSolution(originalFailure));
        assertTrue(hasPushSolution(SokobanLevelGenerator.generate(1, 0)));
    }

    @Test
    void randomizedEarlyLevelsHaveAnIndependentPushSolution() {
        for (int level = 1; level <= 4; level++) {
            for (long salt = 0; salt < 40; salt++) {
                char[][] grid = SokobanLevelGenerator.generate(level, salt);
                assertValid(grid);
                assertTrue(hasPushSolution(grid), "level=" + level + " salt=" + salt
                        + " grid=" + Arrays.deepToString(grid));
            }
        }
    }

    @Test
    void largeAndExtremeLevelNumbersKeepValidBoundedBoards() {
        for (int level : new int[]{0, 5, 10, 15, 25, 100, Integer.MAX_VALUE}) {
            for (long salt = 0; salt < 30; salt++) {
                char[][] grid = SokobanLevelGenerator.generate(level, salt);
                assertTrue(grid.length >= 5 && grid.length <= 14);
                assertValid(grid);
            }
        }
    }

    @Test
    void generationIsRepeatableAndRestartSaltChangesTheLayout() {
        Set<String> layouts = new HashSet<>();
        for (long salt = 0; salt < 12; salt++) {
            char[][] first = SokobanLevelGenerator.generate(3, salt);
            assertEquals(Arrays.deepToString(first),
                    Arrays.deepToString(SokobanLevelGenerator.generate(3, salt)));
            layouts.add(Arrays.deepToString(first));
        }
        assertTrue(layouts.size() > 1);
    }

    @Test
    void repeatedRandomChoicesStillPlaceEveryRequestedObstacle() throws Exception {
        var generate = SokobanLevelGenerator.class.getDeclaredMethod("generateOnce",
                int.class, int.class, int.class, int.class, Random.class);
        generate.setAccessible(true);
        for (int seed = 0; seed < 32; seed++) {
            Random random = new Random(seed) {
                private int draws;
                @Override public int nextInt(int bound) {
                    // Repeated coordinates exhaust the old 30-retry placement loop.
                    return draws++ < 62 ? 0 : super.nextInt(bound);
                }
            };
            char[][] grid = (char[][]) generate.invoke(null, 6, 1, 2, 3, random);
            if (grid == null) continue; // Reverse scrambling may leave a solved board.
            assertValid(grid);
            assertEquals(2, interiorWalls(grid), "Repeated random choices must not omit obstacles");
            assertTrue(hasPushSolution(grid));
            return;
        }
        fail("The controlled random source did not produce an unfinished board");
    }

    @Test
    void successfulScramblesPreserveRequestedObstacleCounts() throws Exception {
        var generate = SokobanLevelGenerator.class.getDeclaredMethod("generateOnce",
                int.class, int.class, int.class, int.class, Random.class);
        generate.setAccessible(true);
        int accepted = 0;
        for (int level = 1; level <= 25; level++) {
            int size = Math.min(5 + (level - 1) * 2 / 3, 14);
            int boxes = Math.min(1 + (level - 1) / 2, 6);
            int obstacles = Math.min(1 + (level - 1) / 2, 8);
            for (int seed = 0; seed < 32; seed++) {
                char[][] grid = (char[][]) generate.invoke(null, size, boxes, obstacles, level,
                        new Random(level * 7919L + seed));
                if (grid == null) continue;
                accepted++;
                assertValid(grid);
                assertEquals(obstacles, interiorWalls(grid), "level=" + level + " seed=" + seed);
            }
        }
        assertTrue(accepted > 500);
    }

    private static int interiorWalls(char[][] grid) {
        int walls = 0;
        for (int y = 1; y < grid.length - 1; y++)
            for (int x = 1; x < grid[y].length - 1; x++)
                if (grid[y][x] == '#') walls++;
        return walls;
    }

    private static void assertValid(char[][] grid) {
        int boxes = 0, targets = 0, players = 0, offTarget = 0;
        for (int y = 0; y < grid.length; y++) {
            assertEquals(grid.length, grid[y].length);
            for (int x = 0; x < grid[y].length; x++) {
                char cell = grid[y][x];
                assertTrue("# $+.@*".indexOf(cell) >= 0);
                if (x == 0 || y == 0 || x == grid.length - 1 || y == grid.length - 1)
                    assertEquals('#', cell);
                if (cell == '$' || cell == '+') boxes++;
                if (cell == '.' || cell == '+' || cell == '*') targets++;
                if (cell == '@' || cell == '*') players++;
                if (cell == '$') offTarget++;
            }
        }
        assertTrue(boxes > 0 && boxes <= 6);
        assertEquals(boxes, targets);
        assertEquals(1, players);
        assertTrue(offTarget > 0, "A fresh level must need at least one push");
    }

    /** 搜索普通玩家走动和推动，不依赖生成器的反向打乱步骤。 */
    private static boolean hasPushSolution(char[][] grid) {
        int width = grid[0].length, player = -1;
        Set<Integer> targets = new HashSet<>();
        List<Integer> boxes = new ArrayList<>();
        for (int y = 0; y < grid.length; y++) {
            for (int x = 0; x < width; x++) {
                char cell = grid[y][x];
                int position = y * width + x;
                if (cell == '$' || cell == '+') boxes.add(position);
                if (cell == '.' || cell == '+' || cell == '*') targets.add(position);
                if (cell == '@' || cell == '*') player = position;
            }
        }
        assertEquals(boxes.size(), targets.size());
        assertTrue(player >= 0 && !boxes.isEmpty());
        int[] initialBoxes = boxes.stream().mapToInt(Integer::intValue).sorted().toArray();
        ArrayDeque<State> queue = new ArrayDeque<>();
        Set<String> seen = new HashSet<>();
        queue.add(new State(player, initialBoxes));
        seen.add(key(player, initialBoxes));
        while (!queue.isEmpty()) {
            State state = queue.remove();
            if (Arrays.stream(state.boxes).allMatch(targets::contains)) return true;
            for (int[] direction : DIRECTIONS) {
                int x = state.player % width + direction[0];
                int y = state.player / width + direction[1];
                if (!open(grid, x, y)) continue;
                int nextPlayer = y * width + x;
                int boxIndex = Arrays.binarySearch(state.boxes, nextPlayer);
                int[] nextBoxes = state.boxes;
                if (boxIndex >= 0) {
                    int boxX = x + direction[0], boxY = y + direction[1];
                    int destination = boxY * width + boxX;
                    if (!open(grid, boxX, boxY) || Arrays.binarySearch(state.boxes, destination) >= 0)
                        continue;
                    nextBoxes = state.boxes.clone();
                    nextBoxes[boxIndex] = destination;
                    Arrays.sort(nextBoxes);
                }
                if (seen.add(key(nextPlayer, nextBoxes))) queue.add(new State(nextPlayer, nextBoxes));
            }
            assertTrue(seen.size() < 200_000, "Solver exceeded its verification budget");
        }
        return false;
    }

    private static boolean open(char[][] grid, int x, int y) {
        return y >= 0 && y < grid.length && x >= 0 && x < grid[y].length && grid[y][x] != '#';
    }

    private static String key(int player, int[] boxes) {
        return player + ":" + Arrays.toString(boxes);
    }

    private record State(int player, int[] boxes) {}
}
