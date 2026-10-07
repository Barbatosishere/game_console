package com.wzz.game_console.client.screens.games.gogame;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Random;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

@Timeout(60)
class GoSimulationRegressionTest {
    private MCTSGoAI ai;
    private String previousGpu;
    private Method simulate;
    private ThreadLocal<?> simulationHashes;
    private Field hashField;
    private Method legalProbe;
    private Method captureProbe;
    private Method atariProbe;

    @BeforeEach
    void createAi() throws Exception {
        previousGpu = System.getProperty("go.gpu");
        System.setProperty("go.gpu", "false");
        ai = new MCTSGoAI(0, 0, 1);
        simulationHashes = (ThreadLocal<?>) field(MCTSGoAI.class, "SIMULATION_HASH").get(null);
        Class<?> hashClass = Class.forName(MCTSGoAI.class.getName() + "$SimulationHash");
        hashField = field(hashClass, "hash");
        simulate = MCTSGoAI.class.getDeclaredMethod("simulatePlaceStone",
                GoPlayer[][].class, int.class, int.class, GoPlayer.class, hashClass);
        simulate.setAccessible(true);
        legalProbe = probeMethod("isLegalMove");
        captureProbe = probeMethod("countCaptures");
        atariProbe = probeMethod("wouldBeInAtari");
    }

    @AfterEach
    void closeAi() {
        if (ai != null) ai.shutdown();
        if (previousGpu == null) System.clearProperty("go.gpu");
        else System.setProperty("go.gpu", previousGpu);
    }

    @Test
    void simulationAndIncrementalHashMatchRulesEngineOnEveryPoint() throws Exception {
        Random random = new Random(9713);
        List<GoPlayer[][]> boards = new ArrayList<>();
        try (GoGame game = GoGame.rulesOnly()) {
            boards.add(game.getBoardCopy());
            for (int step = 0; step < 500; step++) {
                game.placeStone(random.nextInt(19), random.nextInt(19));
                if (step % 50 == 49) boards.add(game.getBoardCopy());
            }
        }
        // Also exercise imported positions that contain groups with no liberties.
        for (int n = 1; n <= 8; n++) {
            GoPlayer[][] board = emptyBoard();
            for (GoPlayer[] row : board) for (int y = 0; y < 19; y++) {
                if (random.nextDouble() < n / 8.0) {
                    row[y] = random.nextBoolean() ? GoPlayer.BLACK : GoPlayer.WHITE;
                }
            }
            boards.add(board);
        }
        try (GoGame oracle = GoGame.rulesOnly()) {
            for (GoPlayer[][] board : boards) {
                for (GoPlayer player : new GoPlayer[]{GoPlayer.BLACK, GoPlayer.WHITE}) {
                    for (int x = 0; x < 19; x++) for (int y = 0; y < 19; y++) {
                        GoPlayer[][] expected = rulesMove(oracle, board, x, y, player);
                        GoPlayer[][] actual = copy(board);
                        assertEquals(expected != null, legalProbe.invoke(ai, actual, x, y, player));
                        assertBoardEquals(board, actual);
                        boolean legal = simulateWithHash(actual, x, y, player);
                        assertEquals(expected != null, legal, "move " + x + "," + y + " by " + player);
                        assertBoardEquals(expected == null ? board : expected, actual);
                    }
                }
            }
        }
    }

    @Test
    void captureAndAtariProbesMatchIndependentGroupAnalysis() throws Exception {
        Random random = new Random(942);
        List<GoPlayer[][]> boards = new ArrayList<>();
        for (int n = 0; n < 12; n++) {
            GoPlayer[][] board = emptyBoard();
            for (GoPlayer[] row : board) for (int y = 0; y < 19; y++) {
                if (random.nextDouble() < n / 11.0) {
                    row[y] = random.nextBoolean() ? GoPlayer.BLACK : GoPlayer.WHITE;
                }
            }
            boards.add(board);
        }
        GoPlayer[][] large = emptyBoard();
        for (GoPlayer[] row : large) Arrays.fill(row, GoPlayer.WHITE);
        large[9][9] = GoPlayer.NONE;
        boards.add(large);
        // The same sole liberty may touch several stones of a friendly group.
        GoPlayer[][] sharedLiberty = emptyBoard();
        for (GoPlayer[] row : sharedLiberty) Arrays.fill(row, GoPlayer.BLACK);
        sharedLiberty[9][9] = sharedLiberty[10][10] = GoPlayer.NONE;
        boards.add(sharedLiberty);
        for (GoPlayer[][] board : boards) {
            GoPlayer[][] before = copy(board);
            for (GoPlayer player : new GoPlayer[]{GoPlayer.BLACK, GoPlayer.WHITE, GoPlayer.NONE}) {
                for (int x = 0; x < 19; x++) for (int y = 0; y < 19; y++) {
                    int[] expected = captureAndLibertyOracle(board, x, y, player);
                    assertEquals(expected[0], captureProbe.invoke(ai, board, x, y, player), "capture count");
                    assertEquals(expected[1] == 1, atariProbe.invoke(ai, board, x, y, player), "atari");
                    assertBoardEquals(before, board);
                }
            }
            for (int[] move : new int[][]{{-1, 0}, {0, -1}, {19, 0}, {0, 19}}) {
                assertEquals(0, captureProbe.invoke(ai, board, move[0], move[1], GoPlayer.BLACK));
                assertEquals(false, atariProbe.invoke(ai, board, move[0], move[1], GoPlayer.BLACK));
                assertBoardEquals(before, board);
            }
        }
    }

    @Test
    void capturesLargeAndMultipleGroupsAndPreservesRejectedMoves() throws Exception {
        GoPlayer[][] surrounded = emptyBoard();
        for (GoPlayer[] row : surrounded) Arrays.fill(row, GoPlayer.WHITE);
        surrounded[9][9] = GoPlayer.NONE;
        assertTrue(simulateWithHash(surrounded, 9, 9, GoPlayer.BLACK));
        GoPlayer[][] singleStone = emptyBoard();
        singleStone[9][9] = GoPlayer.BLACK;
        assertBoardEquals(singleStone, surrounded); // 360 stones, touching on four sides.

        GoPlayer[][] multiple = emptyBoard();
        int[][] whites = {{8, 9}, {10, 9}, {9, 8}, {9, 10}};
        for (int[] stone : whites) {
            int x = stone[0], y = stone[1];
            multiple[x][y] = GoPlayer.WHITE;
            for (int[] offset : new int[][]{{1, 0}, {-1, 0}, {0, 1}, {0, -1}}) {
                if (x + offset[0] != 9 || y + offset[1] != 9) {
                    multiple[x + offset[0]][y + offset[1]] = GoPlayer.BLACK;
                }
            }
        }
        assertTrue(simulateWithHash(multiple, 9, 9, GoPlayer.BLACK));
        for (int[] stone : whites) assertEquals(GoPlayer.NONE, multiple[stone[0]][stone[1]]);

        GoPlayer[][] alive = emptyBoard();
        for (GoPlayer[] row : alive) Arrays.fill(row, GoPlayer.BLACK);
        alive[9][9] = alive[18][18] = GoPlayer.NONE;
        assertTrue(simulateWithHash(alive, 9, 9, GoPlayer.BLACK));
        assertEquals(GoPlayer.NONE, alive[18][18]);

        GoPlayer[][] suicide = emptyBoard();
        suicide[0][1] = suicide[1][0] = GoPlayer.WHITE;
        GoPlayer[][] before = copy(suicide);
        assertFalse(simulateWithHash(suicide, 0, 0, GoPlayer.BLACK));
        assertFalse(simulateWithHash(suicide, 0, 1, GoPlayer.BLACK));
        assertFalse(simulateWithHash(suicide, -1, 0, GoPlayer.BLACK));
        assertFalse(simulateWithHash(suicide, 19, 0, GoPlayer.BLACK));
        assertFalse(simulateWithHash(suicide, 0, -1, GoPlayer.BLACK));
        assertFalse(simulateWithHash(suicide, 0, 19, GoPlayer.BLACK));
        assertFalse(simulateWithHash(suicide, 9, 9, GoPlayer.NONE));
        assertBoardEquals(before, suicide);
    }

    @Test
    void tacticalRegionStoresUniquePointsAndClearsBetweenUses() throws Exception {
        Class<?> regionType = Class.forName(MCTSGoAI.class.getName() + "$TacticalRegion");
        Method add = regionType.getDeclaredMethod("add", int.class);
        Method clear = regionType.getDeclaredMethod("clear");
        Field size = regionType.getDeclaredField("size");
        add.setAccessible(true);
        clear.setAccessible(true);
        size.setAccessible(true);
        Constructor<?> constructor = regionType.getDeclaredConstructor();
        constructor.setAccessible(true);
        Object region = constructor.newInstance();
        add.invoke(region, 4 * 19 + 5);
        add.invoke(region, 4 * 19 + 5);
        add.invoke(region, 4 * 19 + 6);
        assertEquals(2, size.getInt(region));
        clear.invoke(region);
        assertEquals(0, size.getInt(region));
        add.invoke(region, 18 * 19 + 18);
        assertEquals(1, size.getInt(region));
    }

    @Test
    void escapeDirectionUsesUniqueLibertiesWithoutMutatingBoard() throws Exception {
        Method escape = MCTSGoAI.class.getDeclaredMethod("getEscapeDirection",
                GoPlayer[][].class, Set.class, GoPlayer.class);
        escape.setAccessible(true);
        GoPlayer[][] board = emptyBoard();
        board[9][9] = GoPlayer.BLACK;
        board[9][10] = GoPlayer.BLACK;
        Set<int[]> group = new java.util.LinkedHashSet<>(List.of(
                new int[]{9, 9}, new int[]{9, 10}));
        GoPlayer[][] before = copy(board);
        int[] selected = (int[]) escape.invoke(ai, board, group, GoPlayer.BLACK);
        assertNotNull(selected);
        assertEquals(GoPlayer.NONE, board[selected[0]][selected[1]]);
        int selectedScore = Math.min(Math.min(selected[0], selected[1]),
                Math.min(18 - selected[0], 18 - selected[1]));
        int bestScore = Integer.MAX_VALUE;
        for (int[] point : new int[][]{{8, 9}, {8, 10}, {10, 9}, {10, 10},
                {9, 8}, {10, 8}, {9, 11}, {8, 11}}) {
            if (board[point[0]][point[1]] == GoPlayer.NONE) {
                bestScore = Math.min(bestScore, Math.min(Math.min(point[0], point[1]),
                        Math.min(18 - point[0], 18 - point[1])));
            }
        }
        assertEquals(bestScore, selectedScore);
        assertBoardEquals(before, board);
        GoPlayer[][] blocked = emptyBoard();
        for (int[] point : group) blocked[point[0]][point[1]] = GoPlayer.BLACK;
        for (int[] point : new int[][]{{8, 9}, {10, 9}, {9, 8}, {8, 10}, {10, 10}, {9, 11}}) {
            blocked[point[0]][point[1]] = GoPlayer.WHITE;
        }
        assertNull(escape.invoke(ai, blocked, group, GoPlayer.BLACK));
    }

    @Test
    void cappedLibertyCountMatchesExactCountAtAndBelowThreshold() throws Exception {
        Method count = MCTSGoAI.class.getDeclaredMethod("countGroupLibertiesUpTo",
                GoPlayer[][].class, Set.class, int.class);
        count.setAccessible(true);
        GoPlayer[][] board = emptyBoard();
        board[9][9] = GoPlayer.BLACK;
        Set<int[]> group = new java.util.LinkedHashSet<>(List.of(new int[]{9, 9}));
        assertEquals(4, count.invoke(ai, board, group, 10));
        assertEquals(3, count.invoke(ai, board, group, 3));
        assertEquals(1, count.invoke(ai, board, group, 1));
        board[0][0] = GoPlayer.BLACK;
        Set<int[]> corner = new java.util.LinkedHashSet<>(List.of(new int[]{0, 0}));
        assertEquals(2, count.invoke(ai, board, corner, 3));
        assertEquals(2, count.invoke(ai, board, corner, 2));
    }

    @Test
    void tacticalGroupMarksEveryStoneAndKeepsSeparateGroupsDistinct() throws Exception {
        Method key = MCTSGoAI.class.getDeclaredMethod("markTacticalGroup", boolean[].class, Set.class);
        key.setAccessible(true);
        boolean[] visited = new boolean[19 * 19];
        Set<int[]> first = new java.util.LinkedHashSet<>(List.of(new int[]{2, 3}, new int[]{2, 4}));
        Set<int[]> second = new java.util.LinkedHashSet<>(List.of(new int[]{10, 12}, new int[]{10, 13}));
        assertTrue((boolean) key.invoke(null, visited, first));
        assertTrue(visited[2 * 19 + 3]);
        assertTrue(visited[2 * 19 + 4]);
        assertTrue((boolean) key.invoke(null, visited, second));
        assertTrue(visited[10 * 19 + 12]);
        assertTrue(visited[10 * 19 + 13]);
        Set<int[]> same = new java.util.LinkedHashSet<>(List.of(new int[]{2, 4}, new int[]{2, 3}));
        assertFalse((boolean) key.invoke(null, visited, same));
    }

    @Test
    void tacticalCandidatesCacheCaptureCountsWithoutChangingOrderOrBoard() throws Exception {
        Method unscored = MCTSGoAI.class.getDeclaredMethod("legalMovesInRegion",
                GoPlayer[][].class, GoPlayer.class, Set.class);
        Method scored = MCTSGoAI.class.getDeclaredMethod("legalMovesInRegion",
                GoPlayer[][].class, GoPlayer.class, Set.class, boolean.class);
        unscored.setAccessible(true);
        scored.setAccessible(true);
        GoPlayer[][] board = emptyBoard();
        board[0][0] = GoPlayer.WHITE;
        board[1][0] = GoPlayer.BLACK;
        GoPlayer[][] before = copy(board);
        Set<String> region = new java.util.LinkedHashSet<>(List.of(
                "4,4", "0,1", "1,0", "-1,0", "bad", "2,2", "19,0", "1,2,3"));
        @SuppressWarnings("unchecked")
        List<int[]> reference = (List<int[]>) unscored.invoke(ai, board, GoPlayer.BLACK, region);
        @SuppressWarnings("unchecked")
        List<int[]> cached = (List<int[]>) scored.invoke(ai, board, GoPlayer.BLACK, region, true);
        assertEquals(reference.size(), cached.size());
        for (int i = 0; i < cached.size(); i++) {
            int[] move = cached.get(i);
            assertEquals(3, move.length);
            assertArrayEquals(reference.get(i), new int[]{move[0], move[1]});
            assertEquals(captureAndLibertyOracle(board, move[0], move[1], GoPlayer.BLACK)[0], move[2]);
        }
        reference.sort((a, b) -> Integer.compare(
                captureAndLibertyOracle(board, b[0], b[1], GoPlayer.BLACK)[0],
                captureAndLibertyOracle(board, a[0], a[1], GoPlayer.BLACK)[0]));
        cached.sort((a, b) -> Integer.compare(b[2], a[2]));
        for (int i = 0; i < cached.size(); i++) {
            assertArrayEquals(reference.get(i), new int[]{cached.get(i)[0], cached.get(i)[1]});
        }
        assertBoardEquals(before, board);
    }

    @Test
    void tacticalSearchMatchesExhaustiveRulesSearchAcrossDepthsAndRepeatedCalls() throws Exception {
        Class<?> regionType = Class.forName(MCTSGoAI.class.getName() + "$TacticalRegion");
        Method search = MCTSGoAI.class.getDeclaredMethod("localAlphaBeta", GoPlayer[][].class,
                int[].class, int.class, GoPlayer.class, GoPlayer.class, int.class,
                double.class, double.class, regionType, long.class);
        search.setAccessible(true);
        NeuralEvaluator evaluator = (NeuralEvaluator) field(MCTSGoAI.class, "neuralEvaluator").get(ai);
        int[] target = {9 * 19 + 9, 9 * 19 + 10};
        int[] points = {8 * 19 + 10, 8 * 19 + 9, 9 * 19 + 11, 8 * 19 + 11};
        Object region = tacticalRegion(points);
        GoPlayer[][] board = emptyBoard();
        board[9][9] = board[9][10] = GoPlayer.WHITE;
        board[10][9] = board[10][10] = board[9][8] = GoPlayer.BLACK;
        GoPlayer[][] before = copy(board);
        try (GoGame oracle = GoGame.rulesOnly()) {
            for (int repeat = 0; repeat < 2; repeat++) {
                for (int depth : new int[]{3, 1, 2}) {
                    for (GoPlayer player : new GoPlayer[]{GoPlayer.BLACK, GoPlayer.WHITE}) {
                        double expected = exhaustiveTacticalValue(oracle, evaluator, board, target,
                                points, player, GoPlayer.BLACK, depth);
                        double actual = (double) search.invoke(ai, board, target, target.length,
                                player, GoPlayer.BLACK, depth, Double.NEGATIVE_INFINITY,
                                Double.POSITIVE_INFINITY, region, Long.MAX_VALUE);
                        assertEquals(expected, actual, 1e-12, "depth " + depth + " player " + player);
                        assertBoardEquals(before, board);
                    }
                }
            }
        }
        assertEquals(0.0, search.invoke(ai, board, target, target.length, GoPlayer.BLACK,
                GoPlayer.BLACK, 3, Double.NEGATIVE_INFINITY, Double.POSITIVE_INFINITY, region, 0L));
        assertBoardEquals(before, board);
    }

    @Test
    void tacticalRootPreservesRegionOrderForEqualScoresAndReturnsIndependentMoves() throws Exception {
        NeuralEvaluator evaluator = (NeuralEvaluator) field(MCTSGoAI.class, "neuralEvaluator").get(ai);
        Arrays.fill((double[]) field(NeuralEvaluator.class, "valueW2").get(evaluator), 0.0);
        field(NeuralEvaluator.class, "valueB2").setDouble(evaluator, -0.5);
        Class<?> regionType = Class.forName(MCTSGoAI.class.getName() + "$TacticalRegion");
        Method search = MCTSGoAI.class.getDeclaredMethod("localAlphaBetaSearch", GoPlayer[][].class,
                int[].class, int.class, GoPlayer.class, GoPlayer.class, int.class, regionType, long.class);
        search.setAccessible(true);
        GoPlayer[][] board = emptyBoard();
        board[9][9] = GoPlayer.WHITE;
        GoPlayer[][] before = copy(board);
        int[] target = {9 * 19 + 9};
        int[] first = (int[]) search.invoke(ai, board, target, 1, GoPlayer.BLACK, GoPlayer.WHITE, 1,
                tacticalRegion(new int[]{4 * 19 + 4, 3 * 19 + 3}), Long.MAX_VALUE);
        assertArrayEquals(new int[]{4, 4}, first);
        int[] second = (int[]) search.invoke(ai, board, target, 1, GoPlayer.BLACK, GoPlayer.WHITE, 1,
                tacticalRegion(new int[]{3 * 19 + 3, 4 * 19 + 4}), Long.MAX_VALUE);
        assertArrayEquals(new int[]{3, 3}, second);
        assertArrayEquals(new int[]{4, 4}, first);
        assertNotSame(first, second);
        assertBoardEquals(before, board);
    }

    @Test
    void tacticalRootReturnsCaptureAndKeepsBuffersIsolatedAcrossThreads() throws Exception {
        Class<?> regionType = Class.forName(MCTSGoAI.class.getName() + "$TacticalRegion");
        Method search = MCTSGoAI.class.getDeclaredMethod("localAlphaBetaSearch", GoPlayer[][].class,
                int[].class, int.class, GoPlayer.class, GoPlayer.class, int.class, regionType, long.class);
        search.setAccessible(true);
        Object region = tacticalRegion(new int[]{8 * 19 + 11, 7 * 19 + 10, 8 * 19 + 10, 7 * 19 + 11});
        ExecutorService pool = Executors.newFixedThreadPool(4);
        try {
            List<Future<?>> futures = new ArrayList<>();
            for (int task = 0; task < 12; task++) {
                GoPlayer attacker = task % 2 == 0 ? GoPlayer.BLACK : GoPlayer.WHITE;
                GoPlayer defender = attacker == GoPlayer.BLACK ? GoPlayer.WHITE : GoPlayer.BLACK;
                GoPlayer[][] board = emptyBoard();
                board[9][9] = board[9][10] = defender;
                for (int[] point : new int[][]{{8, 9}, {10, 9}, {9, 8}, {10, 10}, {9, 11}}) {
                    board[point[0]][point[1]] = attacker;
                }
                GoPlayer[][] before = copy(board);
                futures.add(pool.submit(() -> {
                    for (int depth : new int[]{5, 1, 3}) {
                        int[] selected = (int[]) search.invoke(ai, board, new int[]{9 * 19 + 9, 9 * 19 + 10},
                                2, attacker, defender, depth, region, Long.MAX_VALUE);
                        assertArrayEquals(new int[]{8, 10}, selected);
                        assertBoardEquals(before, board);
                    }
                    assertNull(search.invoke(ai, board, new int[]{9 * 19 + 9, 9 * 19 + 10},
                            2, attacker, defender, 5, region, 0L));
                    return null;
                }));
            }
            for (Future<?> future : futures) future.get(20, TimeUnit.SECONDS);
        } finally {
            pool.shutdownNow();
            pool.awaitTermination(10, TimeUnit.SECONDS);
        }
    }

    private static Object tacticalRegion(int[] points) throws Exception {
        Class<?> type = Class.forName(MCTSGoAI.class.getName() + "$TacticalRegion");
        Constructor<?> constructor = type.getDeclaredConstructor();
        constructor.setAccessible(true);
        Object region = constructor.newInstance();
        Method add = type.getDeclaredMethod("add", int.class);
        add.setAccessible(true);
        for (int point : points) add.invoke(region, point);
        return region;
    }

    /** Independent exhaustive minimax using the game rules, with no pruning or reusable boards. */
    private static double exhaustiveTacticalValue(GoGame oracle, NeuralEvaluator evaluator,
                                                  GoPlayer[][] board, int[] target, int[] region,
                                                  GoPlayer player, GoPlayer attacker, int depth) throws Exception {
        boolean captured = true;
        for (int point : target) {
            if (board[point / 19][point % 19] != GoPlayer.NONE) captured = false;
        }
        if (captured) return player == attacker ? 1.0 : -1.0;
        if (depth == 0) return evaluator.forwardValue(board, player, null);
        double best = Double.NEGATIVE_INFINITY;
        GoPlayer opponent = player == GoPlayer.BLACK ? GoPlayer.WHITE : GoPlayer.BLACK;
        for (int point : region) {
            GoPlayer[][] next = rulesMove(oracle, board, point / 19, point % 19, player);
            if (next != null) {
                best = Math.max(best, -exhaustiveTacticalValue(oracle, evaluator, next, target,
                        region, opponent, attacker, depth - 1));
            }
        }
        return best == Double.NEGATIVE_INFINITY ? 0.0 : best;
    }

    @Test
    void concurrentSimulationsKeepScratchAndHashesIsolated() throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(8);
        try (GoGame oracle = GoGame.rulesOnly()) {
            Random random = new Random(641);
            GoPlayer[][] board = emptyBoard();
            for (int step = 0; step < 240; step++) {
                oracle.placeStone(random.nextInt(19), random.nextInt(19));
            }
            board = oracle.getBoardCopy();
            List<Future<?>> futures = new ArrayList<>();
            for (int n = 0; n < 200; n++) {
                int x = random.nextInt(19), y = random.nextInt(19);
                GoPlayer player = random.nextBoolean() ? GoPlayer.BLACK : GoPlayer.WHITE;
                GoPlayer[][] input = copy(board);
                GoPlayer[][] expected = rulesMove(oracle, input, x, y, player);
                int[] expectedProbes = captureAndLibertyOracle(input, x, y, player);
                futures.add(pool.submit(() -> {
                    for (int repeat = 0; repeat < 10; repeat++) {
                        GoPlayer[][] actual = copy(input);
                        assertEquals(expected != null, legalProbe.invoke(ai, actual, x, y, player));
                        assertEquals(expectedProbes[0], captureProbe.invoke(ai, actual, x, y, player));
                        assertEquals(expectedProbes[1] == 1, atariProbe.invoke(ai, actual, x, y, player));
                        assertBoardEquals(input, actual);
                        assertEquals(expected != null, simulateWithHash(actual, x, y, player));
                        assertBoardEquals(expected == null ? input : expected, actual);
                    }
                    return null;
                }));
            }
            for (Future<?> future : futures) future.get(10, TimeUnit.SECONDS);
        } finally {
            pool.shutdownNow();
            pool.awaitTermination(10, TimeUnit.SECONDS);
        }
    }

    @Test
    void expansionUsesCorrectHashAndIndependentBoardsForKnownAndUnknownParentHash() throws Exception {
        for (boolean knownHash : new boolean[]{false, true}) {
            GoPlayer[][] board = emptyBoard();
            board[4][4] = GoPlayer.WHITE;
            List<int[]> moves = List.of(new int[]{8, 8}, new int[]{10, 10});
            Object root = node(board, GoPlayer.BLACK, null, moves);
            if (knownHash) field(root.getClass(), "hash").setLong(root, GoGame.boardHash(board));
            Method expand = expandMethod(root);
            Object first = expand.invoke(ai, root);
            Object second = expand.invoke(ai, root);
            assertNotNull(first);
            assertNotNull(second);
            GoPlayer[][] firstBoard = (GoPlayer[][]) field(first.getClass(), "board").get(first);
            GoPlayer[][] secondBoard = (GoPlayer[][]) field(second.getClass(), "board").get(second);
            assertEquals(GoGame.boardHash(firstBoard), field(first.getClass(), "hash").getLong(first));
            assertEquals(GoGame.boardHash(secondBoard), field(second.getClass(), "hash").getLong(second));
            assertEquals(GoPlayer.NONE, board[10][10]);
            assertEquals(GoPlayer.NONE, secondBoard[10][10]);
            for (int x = 0; x < 19; x++) {
                assertNotSame(board[x], firstBoard[x]);
                assertNotSame(board[x], secondBoard[x]);
                assertNotSame(firstBoard[x], secondBoard[x]);
            }
            firstBoard[4][4] = GoPlayer.NONE;
            assertEquals(GoPlayer.WHITE, board[4][4]);
            assertEquals(GoPlayer.WHITE, secondBoard[4][4]);
        }
    }

    @Test
    void expansionHashesCapturesAndRejectsHistoricalAndAncestorKo() throws Exception {
        try (GoGame game = GoGame.rulesOnly()) {
            int[][] moves = {{3, 5}, {3, 4}, {5, 5}, {5, 4}, {4, 6}, {4, 3}, {4, 4}};
            for (int[] move : moves) assertTrue(game.placeStone(move[0], move[1]));
            GoPlayer[][] beforeCapture = game.getBoardCopy();
            Object root = node(beforeCapture, GoPlayer.WHITE, null, List.of(new int[]{4, 5}));
            field(root.getClass(), "hash").setLong(root, GoGame.boardHash(beforeCapture));
            Object child = expandMethod(root).invoke(ai, root);
            assertNotNull(child);
            assertTrue(game.placeStone(4, 5));
            assertBoardEquals(game.getBoardCopy(), (GoPlayer[][]) field(child.getClass(), "board").get(child));
            assertEquals(game.getCurrentHash(), field(child.getClass(), "hash").getLong(child));
            for (boolean ancestorOnly : new boolean[]{false, true}) {
                Object recapture = node(game.getBoardCopy(), GoPlayer.BLACK, root, List.of(new int[]{4, 4}));
                field(recapture.getClass(), "hash").setLong(recapture, game.getCurrentHash());
                field(MCTSGoAI.class, "koHistory").set(ai, ancestorOnly ? Set.of() : game.getPositionHistory());
                assertNull(expandMethod(recapture).invoke(ai, recapture));
                assertNull(field(recapture.getClass(), "children").get(recapture));
                assertBoardEquals(game.getBoardCopy(), (GoPlayer[][]) field(recapture.getClass(), "board").get(recapture));
            }
        }
    }

    private boolean simulateWithHash(GoPlayer[][] board, int x, int y, GoPlayer player) throws Exception {
        Object hash = simulationHashes.get();
        long before = GoGame.boardHash(board);
        hashField.setLong(hash, before);
        boolean legal = (boolean) simulate.invoke(ai, board, x, y, player, hash);
        assertEquals(GoGame.boardHash(board), hashField.getLong(hash), "incremental hash");
        if (!legal) assertEquals(before, hashField.getLong(hash), "rejected hash");
        return legal;
    }

    private static Method probeMethod(String name) throws Exception {
        Method method = MCTSGoAI.class.getDeclaredMethod(name,
                GoPlayer[][].class, int.class, int.class, GoPlayer.class);
        method.setAccessible(true);
        return method;
    }

    private static int[] captureAndLibertyOracle(GoPlayer[][] board, int x, int y, GoPlayer player) {
        if (player == GoPlayer.NONE || board[x][y] != GoPlayer.NONE) return new int[]{0, 0};
        GoPlayer[][] placed = copy(board);
        placed[x][y] = player;
        GoPlayer opponent = player == GoPlayer.BLACK ? GoPlayer.WHITE : GoPlayer.BLACK;
        boolean[][] counted = new boolean[19][19];
        int captured = 0;
        for (int[] offset : new int[][]{{0, 1}, {1, 0}, {0, -1}, {-1, 0}}) {
            int nx = x + offset[0], ny = y + offset[1];
            if (nx < 0 || nx >= 19 || ny < 0 || ny >= 19
                    || placed[nx][ny] != opponent || counted[nx][ny]) continue;
            List<int[]> group = oracleGroup(placed, nx, ny);
            for (int[] point : group) counted[point[0]][point[1]] = true;
            if (oracleLiberties(placed, group) == 0) captured += group.size();
        }
        // Capture priority and atari deliberately examine the position before removing enemies.
        return new int[]{captured, oracleLiberties(placed, oracleGroup(placed, x, y))};
    }

    private static List<int[]> oracleGroup(GoPlayer[][] board, int x, int y) {
        List<int[]> group = new ArrayList<>();
        boolean[][] visited = new boolean[19][19];
        group.add(new int[]{x, y});
        visited[x][y] = true;
        for (int head = 0; head < group.size(); head++) {
            int[] point = group.get(head);
            for (int[] offset : new int[][]{{0, 1}, {1, 0}, {0, -1}, {-1, 0}}) {
                int nx = point[0] + offset[0], ny = point[1] + offset[1];
                if (nx >= 0 && nx < 19 && ny >= 0 && ny < 19
                        && !visited[nx][ny] && board[nx][ny] == board[x][y]) {
                    visited[nx][ny] = true;
                    group.add(new int[]{nx, ny});
                }
            }
        }
        return group;
    }

    private static int oracleLiberties(GoPlayer[][] board, List<int[]> group) {
        Set<Integer> liberties = new java.util.HashSet<>();
        for (int[] point : group) {
            for (int[] offset : new int[][]{{0, 1}, {1, 0}, {0, -1}, {-1, 0}}) {
                int nx = point[0] + offset[0], ny = point[1] + offset[1];
                if (nx >= 0 && nx < 19 && ny >= 0 && ny < 19 && board[nx][ny] == GoPlayer.NONE) {
                    liberties.add(nx * 19 + ny);
                }
            }
        }
        return liberties.size();
    }

    private static GoPlayer[][] rulesMove(GoGame game, GoPlayer[][] board, int x, int y, GoPlayer player)
            throws Exception {
        game.reset();
        field(GoGame.class, "board").set(game, copy(board));
        field(GoGame.class, "currentPlayer").set(game, player);
        // Simulation validates local rules; expansion checks history separately.
        ((Set<?>) field(GoGame.class, "positionHistory").get(game)).clear();
        return game.placeStone(x, y) ? game.getBoardCopy() : null;
    }

    private static Object node(GoPlayer[][] board, GoPlayer player, Object parent, List<int[]> moves)
            throws Exception {
        Class<?> type = Class.forName(MCTSGoAI.class.getName() + "$MCTSNode");
        Constructor<?> constructor = type.getDeclaredConstructor(
                GoPlayer[][].class, GoPlayer.class, type, int[].class, List.class);
        constructor.setAccessible(true);
        Object node = constructor.newInstance(board, player, parent, null, moves);
        double[] policy = new double[362];
        Arrays.fill(policy, 1.0 / 362);
        field(type, "policyCache").set(node, policy);
        return node;
    }

    private static Method expandMethod(Object node) throws Exception {
        Method method = MCTSGoAI.class.getDeclaredMethod("expand", node.getClass());
        method.setAccessible(true);
        return method;
    }

    private static Field field(Class<?> type, String name) throws Exception {
        Field field = type.getDeclaredField(name);
        field.setAccessible(true);
        return field;
    }

    private static GoPlayer[][] emptyBoard() {
        GoPlayer[][] board = new GoPlayer[19][19];
        for (GoPlayer[] row : board) Arrays.fill(row, GoPlayer.NONE);
        return board;
    }

    private static GoPlayer[][] copy(GoPlayer[][] board) {
        return Arrays.stream(board).map(GoPlayer[]::clone).toArray(GoPlayer[][]::new);
    }

    private static void assertBoardEquals(GoPlayer[][] expected, GoPlayer[][] actual) {
        for (int x = 0; x < 19; x++) assertArrayEquals(expected[x], actual[x], "row " + x);
    }
}
