package com.wzz.game_console.client.screens.games.gogame;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.util.*;
import java.util.concurrent.*;

import static org.junit.jupiter.api.Assertions.*;

@Timeout(30)
class GoFeatureRegressionTest {
    private static final int SIZE = 19;
    private static final int[][] DIRECTIONS = {{0, 1}, {1, 0}, {0, -1}, {-1, 0}};

    @Test
    void featuresMatchCollectionReferenceForDenseSparseAndLegalPositions() {
        NeuralEvaluator evaluator = new NeuralEvaluator();
        try {
            Random random = new Random(486);
            List<GoPlayer[][]> boards = new ArrayList<>();
            boards.add(emptyBoard());
            GoPlayer[][] full = emptyBoard();
            for (GoPlayer[] row : full) Arrays.fill(row, GoPlayer.BLACK);
            boards.add(full);
            GoPlayer[][] oneLiberty = copy(full);
            oneLiberty[9][9] = GoPlayer.NONE;
            boards.add(oneLiberty);
            for (int n = 0; n < 50; n++) {
                GoPlayer[][] board = emptyBoard();
                for (GoPlayer[] row : board) for (int y = 0; y < SIZE; y++) {
                    if (random.nextDouble() < n / 49.0) {
                        row[y] = random.nextBoolean() ? GoPlayer.BLACK : GoPlayer.WHITE;
                    }
                }
                boards.add(board);
            }
            try (GoGame game = GoGame.rulesOnly()) {
                for (int n = 0; n < 200; n++) {
                    game.placeStone(random.nextInt(SIZE), random.nextInt(SIZE));
                    if (n % 20 == 0) boards.add(game.getBoardCopy());
                }
            }
            for (GoPlayer[][] board : boards) {
                GoPlayer[][] before = copy(board);
                for (GoPlayer player : new GoPlayer[]{GoPlayer.BLACK, GoPlayer.WHITE}) {
                    double[] expected = referenceFeatures(evaluator, board, player);
                    double[] actual = evaluator.extractAuxFeatures(board, player);
                    for (int feature : new int[]{0, 1, 2, 3, 4, 5, 6, 7, 18, 19, 21}) {
                        assertEquals(expected[feature], actual[feature], 1e-12, "feature " + feature);
                    }
                }
                for (int x = 0; x < SIZE; x++) assertArrayEquals(before[x], board[x]);
            }
        } finally {
            evaluator.release();
        }
    }

    @Test
    void sameGroupTouchingMultipleSidesIsNotCountedAsSeparation() {
        NeuralEvaluator evaluator = new NeuralEvaluator();
        try {
            GoPlayer[][] board = emptyBoard();
            for (GoPlayer[] row : board) Arrays.fill(row, GoPlayer.WHITE);
            board[9][9] = GoPlayer.NONE;
            double[] features = evaluator.extractAuxFeatures(board, GoPlayer.BLACK);
            assertEquals(1.0, features[1]); // The single group has one unique liberty.
            assertEquals(0.0, features[19]);
            assertEquals(-1.0 / 50, features[21]);
            assertEquals(1.0 / 50, evaluator.extractAuxFeatures(board, GoPlayer.WHITE)[21]);
            assertArrayEquals(new double[8], Arrays.copyOf(
                    evaluator.extractAuxFeatures(emptyBoard(), GoPlayer.BLACK), 8));
        } finally {
            evaluator.release();
        }
    }

    @Test
    void concurrentPositionForwardsKeepFeaturesAndPoliciesIsolated() throws Exception {
        String previous = System.getProperty("go.gpu");
        NeuralEvaluator evaluator = new NeuralEvaluator();
        ExecutorService pool = Executors.newFixedThreadPool(8);
        try {
            System.setProperty("go.gpu", "false");
            GoPlayer[][][] boards = {emptyBoard(), emptyBoard(), emptyBoard()};
            for (GoPlayer[] row : boards[1]) Arrays.fill(row, GoPlayer.BLACK);
            boards[1][9][9] = GoPlayer.NONE;
            for (int x = 0; x < SIZE; x++) for (int y = 0; y < SIZE; y++) {
                boards[2][x][y] = (x + y) % 3 == 0 ? GoPlayer.NONE
                        : (x + y) % 3 == 1 ? GoPlayer.BLACK : GoPlayer.WHITE;
            }
            NeuralEvaluator.ForwardResult[] expected = new NeuralEvaluator.ForwardResult[6];
            for (int i = 0; i < expected.length; i++) {
                GoPlayer player = i % 2 == 0 ? GoPlayer.BLACK : GoPlayer.WHITE;
                // Allocate input features independently for the reference path.
                expected[i] = evaluator.forward(evaluator.buildInputPlanes(boards[i % 3], player, null),
                        evaluator.extractAuxFeatures(boards[i % 3], player));
            }
            List<Future<NeuralEvaluator.ForwardResult>> futures = new ArrayList<>();
            for (int i = 0; i < 180; i++) {
                int position = i % 6;
                futures.add(pool.submit(() -> evaluator.forwardPosition(boards[position % 3],
                        position % 2 == 0 ? GoPlayer.BLACK : GoPlayer.WHITE, null)));
            }
            List<NeuralEvaluator.ForwardResult> outputs = new ArrayList<>();
            for (Future<NeuralEvaluator.ForwardResult> future : futures) {
                outputs.add(future.get(10, TimeUnit.SECONDS));
            }
            for (int i = 0; i < outputs.size(); i++) {
                assertEquals(expected[i % 6].value, outputs.get(i).value);
                assertArrayEquals(expected[i % 6].policy, outputs.get(i).policy);
            }
        } finally {
            pool.shutdownNow();
            pool.awaitTermination(10, TimeUnit.SECONDS);
            evaluator.release();
            if (previous == null) System.clearProperty("go.gpu");
            else System.setProperty("go.gpu", previous);
        }
    }

    /** Uses the original collection helpers as an independent oracle for the new analysis. */
    private static double[] referenceFeatures(NeuralEvaluator evaluator, GoPlayer[][] board, GoPlayer player) {
        List<Set<Integer>> groups = new ArrayList<>();
        List<Integer> liberties = new ArrayList<>();
        Set<Integer> visited = new HashSet<>();
        int[] histogram = new int[8];
        double connections = 0;
        for (int x = 0; x < SIZE; x++) for (int y = 0; y < SIZE; y++) {
            if (board[x][y] == GoPlayer.NONE || visited.contains(x * SIZE + y)) continue;
            Set<int[]> original = evaluator.getGroup(board, x, y);
            Set<Integer> group = new HashSet<>();
            for (int[] point : original) group.add(point[0] * SIZE + point[1]);
            groups.add(group);
            int libs = evaluator.countGroupLiberties(board, original);
            liberties.add(libs);
            histogram[Math.min(libs, 7)]++;
            visited.addAll(group);
            if (board[x][y] == player) {
                if (group.size() >= 5) connections += group.size() * 0.3;
                for (int[] point : original) for (int[] d : DIRECTIONS) {
                    int nx = point[0] + d[0], ny = point[1] + d[1];
                    if (inside(nx, ny) && board[nx][ny] == player) connections += 0.5;
                }
                if (libs >= 5) connections += 2;
            }
        }
        GoPlayer opponent = player == GoPlayer.BLACK ? GoPlayer.WHITE : GoPlayer.BLACK;
        double separation = 0;
        for (int x = 0; x < SIZE; x++) for (int y = 0; y < SIZE; y++) {
            if (board[x][y] != GoPlayer.NONE) continue;
            Set<Integer> adjacent = new HashSet<>();
            for (int[] d : DIRECTIONS) {
                int nx = x + d[0], ny = y + d[1];
                if (inside(nx, ny) && board[nx][ny] == opponent) {
                    for (int g = 0; g < groups.size(); g++) {
                        if (groups.get(g).contains(nx * SIZE + ny)) adjacent.add(g);
                    }
                }
            }
            if (adjacent.size() >= 2) {
                int minLibs = adjacent.stream().mapToInt(liberties::get).min().orElseThrow();
                separation += minLibs <= 3 ? 3.0 : 1.0;
            }
        }
        visited.clear();
        double territory = 0;
        for (int x = 0; x < SIZE; x++) for (int y = 0; y < SIZE; y++) {
            if (board[x][y] != GoPlayer.NONE || !visited.add(x * SIZE + y)) continue;
            Set<Integer> region = new HashSet<>();
            Deque<Integer> pending = new ArrayDeque<>();
            pending.push(x * SIZE + y);
            while (!pending.isEmpty()) {
                int point = pending.pop();
                region.add(point);
                for (int[] d : DIRECTIONS) {
                    int nx = point / SIZE + d[0], ny = point % SIZE + d[1];
                    if (inside(nx, ny) && board[nx][ny] == GoPlayer.NONE && visited.add(nx * SIZE + ny)) {
                        pending.push(nx * SIZE + ny);
                    }
                }
            }
            int border = 0;
            for (int point : region) for (int[] d : DIRECTIONS) {
                int nx = point / SIZE + d[0], ny = point % SIZE + d[1];
                if (inside(nx, ny)) {
                    if (board[nx][ny] == player) border++;
                    else if (board[nx][ny] == opponent) border--;
                }
            }
            territory += Integer.signum(border) * region.size();
        }
        double[] expected = new double[24];
        for (int i = 0; i < 8; i++) {
            expected[i] = groups.isEmpty() ? 0.0 : histogram[i] * 2.0 / groups.size() - 1.0;
        }
        expected[18] = connections / 20;
        expected[19] = separation / 20;
        expected[21] = territory / 50;
        return expected;
    }

    private static boolean inside(int x, int y) { return x >= 0 && x < SIZE && y >= 0 && y < SIZE; }

    private static GoPlayer[][] emptyBoard() {
        GoPlayer[][] board = new GoPlayer[SIZE][SIZE];
        for (GoPlayer[] row : board) Arrays.fill(row, GoPlayer.NONE);
        return board;
    }

    private static GoPlayer[][] copy(GoPlayer[][] board) {
        return Arrays.stream(board).map(GoPlayer[]::clone).toArray(GoPlayer[][]::new);
    }
}
