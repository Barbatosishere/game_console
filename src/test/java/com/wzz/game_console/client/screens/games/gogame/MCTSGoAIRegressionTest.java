package com.wzz.game_console.client.screens.games.gogame;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Random;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.*;

@Timeout(30)
class MCTSGoAIRegressionTest {
    private MCTSGoAI ai;

    @BeforeEach
    void createAi() {
        ai = new MCTSGoAI(0, 0, 1);
    }

    @AfterEach
    void closeAi() {
        ai.shutdown();
    }

    @Test
    void zeroVisitChildrenUseSelectedMoveInsteadOfPass() throws Exception {
        GoPlayer[][] board = emptyBoard();
        Object root = node(board, null, null);
        Object child = node(board, root, new int[]{4, 5});
        setField(root, "children", new ArrayList<>(List.of(child)));
        setField(ai, "currentRoot", root);
        setField(ai, "lastMove", new int[]{4, 5});

        double[] policy = ai.getVisitDistribution();
        assertEquals(1.0, policy[4 * 19 + 5]);
        assertEquals(0.0, policy[361]);
        assertEquals(1.0, Arrays.stream(policy).sum());
    }

    @Test
    void noLegalMoveClearsPreviousTreeAndSelectedMove() throws Exception {
        try (GoGame game = GoGame.rulesOnly()) {
            Object root = node(emptyBoard(), null, null);
            setField(ai, "currentRoot", root);
            setField(ai, "lastRoot", root);
            setField(ai, "lastMove", new int[]{4, 5});
            GoPlayer[][] board = (GoPlayer[][]) getField(game, "board");
            for (GoPlayer[] column : board) Arrays.fill(column, GoPlayer.BLACK);

            assertNull(ai.getBestMove(game));
            assertNull(getField(ai, "currentRoot"));
            assertNull(getField(ai, "lastRoot"));
            assertNull(getField(ai, "lastMove"));
            assertEquals(1.0, ai.getVisitDistribution()[361]);
        }
    }

    @Test
    void zeroIterationSearchReturnsLegalMoveAndMatchingPolicy() {
        try (GoGame game = GoGame.rulesOnly()) {
            int[] move = ai.getBestMove(game);
            assertNotNull(move);
            assertTrue(game.placeStone(move[0], move[1]));
            double[] policy = ai.getVisitDistribution();
            assertEquals(1.0, policy[move[0] * 19 + move[1]]);
            assertEquals(1.0, Arrays.stream(policy).sum());
        }
    }

    @Test
    void oneIterationSelfPlayDoesNotForceAnOpeningPass() {
        MCTSGoAI searchAi = new MCTSGoAI(1_000, 1, 1);
        try (GoGame game = GoGame.rulesOnly()) {
            searchAi.setSelfPlayMode(true);
            searchAi.setRandomSeed(42);
            int[] move = searchAi.getBestMove(game);
            assertNotNull(move, "A single expansion must not force a pass on an empty board");
            assertTrue(game.placeStone(move[0], move[1]));
            double[] policy = searchAi.getVisitDistribution();
            assertEquals(1.0, policy[move[0] * 19 + move[1]]);
            assertEquals(0.0, policy[361]);
        } finally {
            searchAi.shutdown();
        }
    }

    @Test
    void openingSearchPreservesPassThroughPolicyPruning() throws Exception {
        NeuralEvaluator evaluator = new NeuralEvaluator() {
            @Override
            public ForwardResult forwardPosition(GoPlayer[][] board, GoPlayer player, int[] lastMove) {
                double[] policy = new double[362];
                policy[3 * 19 + 3] = 1.0;
                return new ForwardResult(0.0, policy);
            }
        };
        MCTSGoAI searchAi = new MCTSGoAI(1_000, 1, 1, evaluator, false);
        try (GoGame game = GoGame.rulesOnly()) {
            searchAi.setSelfPlayMode(true);
            assertNotNull(searchAi.getBestMove(game));
            @SuppressWarnings("unchecked")
            List<int[]> remaining = (List<int[]>) getField(getField(searchAi, "currentRoot"), "untriedMoves");
            assertTrue(remaining.stream().anyMatch(move -> move[0] < 0 && move[1] < 0),
                    "PASS must remain available after the first board move is expanded");
        } finally {
            searchAi.shutdown();
            evaluator.release();
        }
    }

    @Test
    void firstOpeningExpansionPrefersBoardMoveWithPassAtAnyListPosition() throws Exception {
        for (int passIndex = 0; passIndex < 3; passIndex++) {
            List<int[]> moves = new ArrayList<>(List.of(new int[]{3, 3}, new int[]{15, 15}));
            moves.add(passIndex, new int[]{-1, -1});
            Object root = node(emptyBoard(), GoPlayer.BLACK, null, null, moves);
            var expand = MCTSGoAI.class.getDeclaredMethod("expand", root.getClass());
            expand.setAccessible(true);
            Object child = expand.invoke(ai, root);
            assertNotNull(child);
            int[] move = (int[]) getField(child, "move");
            assertTrue(move[0] >= 0 && move[1] >= 0, "PASS position=" + passIndex);
            GoPlayer[][] board = (GoPlayer[][]) getField(child, "board");
            assertEquals(GoPlayer.BLACK, board[move[0]][move[1]]);
            @SuppressWarnings("unchecked")
            List<int[]> remaining = (List<int[]>) getField(root, "untriedMoves");
            assertTrue(remaining.stream().anyMatch(candidate -> candidate[0] < 0 && candidate[1] < 0));
        }
    }

    @Test
    void zeroIterationSearchPassesAfterOpponentPass() {
        try (GoGame game = GoGame.rulesOnly()) {
            game.pass();
            assertTrue(game.canPlaceStone(0, 0));

            assertNull(ai.getBestMove(game));
            assertEquals(1.0, ai.getVisitDistribution()[361]);
            GoTrainingMove.apply(game, null, ai.getVisitDistribution());
            assertTrue(game.isGameOver());
        }
    }

    @Test
    void selfPlayPassesImmediatelyAfterOpponentPass() {
        MCTSGoAI searchAi = new MCTSGoAI(1_000, 8, 1);
        try (GoGame game = GoGame.rulesOnly()) {
            searchAi.setSelfPlayMode(true);
            game.pass();
            assertTrue(game.canPlaceStone(0, 0));
            assertNull(searchAi.getBestMove(game));
            assertEquals(1.0, searchAi.getVisitDistribution()[361]);
            GoTrainingMove.apply(game, null, searchAi.getVisitDistribution());
            assertTrue(game.isGameOver());
        } finally {
            searchAi.shutdown();
        }
    }

    @Test
    void selfPlaySearchTimeHonorsTrainerBudget() throws Exception {
        MCTSGoAI searchAi = new MCTSGoAI(300, 8, 1);
        try {
            var method = MCTSGoAI.class.getDeclaredMethod("calculateDynamicSearchTime", int.class, int.class);
            method.setAccessible(true);
            searchAi.setSelfPlayMode(true);
            assertEquals(300, (int) method.invoke(searchAi, 10, 200));
            searchAi.setSelfPlayMode(false);
            assertTrue((int) method.invoke(searchAi, 10, 200) >= 500);
        } finally {
            searchAi.shutdown();
        }
    }

    @Test
    void searchMovesAlwaysIncludePassInOpening() throws Exception {
        var method = MCTSGoAI.class.getDeclaredMethod("getSearchMoves",
                long.class, GoPlayer[][].class, GoPlayer.class);
        method.setAccessible(true);
        GoPlayer[][] board = emptyBoard();
        @SuppressWarnings("unchecked")
        List<int[]> moves = (List<int[]>) method.invoke(ai, GoGame.boardHash(board), board, GoPlayer.BLACK);
        assertTrue(moves.stream().anyMatch(move -> move[0] < 0 && move[1] < 0));
    }

    @Test
    void invalidLegalMoveProbeDoesNotOverwriteBoard() throws Exception {
        GoPlayer[][] board = emptyBoard();
        board[3][4] = GoPlayer.BLACK;
        GoPlayer[][] before = copy(board);
        var method = MCTSGoAI.class.getDeclaredMethod("isLegalMove", GoPlayer[][].class,
                int.class, int.class, GoPlayer.class);
        method.setAccessible(true);
        for (int[] move : new int[][]{{3, 4}, {-1, 4}, {19, 4}, {3, -1}, {3, 19}}) {
            assertEquals(false, method.invoke(ai, board, move[0], move[1], GoPlayer.WHITE));
            assertBoardEquals(before, board);
        }
        assertEquals(false, method.invoke(ai, board, 0, 0, GoPlayer.NONE));
        assertBoardEquals(before, board);
    }

    @Test
    void legalCaptureProbeRestoresCapturedStones() throws Exception {
        GoPlayer[][] board = emptyBoard();
        board[0][0] = GoPlayer.WHITE;
        board[1][0] = GoPlayer.BLACK;
        GoPlayer[][] before = copy(board);
        var method = MCTSGoAI.class.getDeclaredMethod("isLegalMove", GoPlayer[][].class,
                int.class, int.class, GoPlayer.class);
        method.setAccessible(true);
        assertEquals(true, method.invoke(ai, board, 0, 1, GoPlayer.BLACK));
        assertBoardEquals(before, board);
    }

    @Test
    void tacticalRegionRejectsOccupiedAndMalformedPointsWithoutMutation() throws Exception {
        GoPlayer[][] board = emptyBoard();
        board[3][4] = GoPlayer.BLACK;
        GoPlayer[][] before = copy(board);
        var method = MCTSGoAI.class.getDeclaredMethod("legalMovesInRegion", GoPlayer[][].class,
                GoPlayer.class, Set.class);
        method.setAccessible(true);
        @SuppressWarnings("unchecked")
        List<int[]> moves = (List<int[]>) method.invoke(ai, board, GoPlayer.WHITE,
                Set.of("3,4", "-1,0", "19,0", "bad", "x,2", "1,2,3", "0,0"));
        assertEquals(1, moves.size());
        assertArrayEquals(new int[]{0, 0}, moves.get(0));
        assertBoardEquals(before, board);
    }

    @Test
    void oneSearchIterationVisitsExpandedChildAndParent() throws Exception {
        MCTSGoAI searchAi = new MCTSGoAI(1_000, 1, 1);
        try {
            Object root = node(emptyBoard(), GoPlayer.BLACK, null, null,
                    List.of(new int[]{3, 3, 0}));
            setField(searchAi, "currentRoot", root);
            var search = MCTSGoAI.class.getDeclaredMethod(
                    "sequentialSearchWithEarlyTerminate", long.class);
            search.setAccessible(true);
            search.invoke(searchAi, System.currentTimeMillis() + 1_000);

            @SuppressWarnings("unchecked")
            List<Object> children = (List<Object>) getField(root, "children");
            assertEquals(1, children.size());
            assertEquals(1.0, (double) getField(children.get(0), "visits"));
            assertEquals(1.0, (double) getField(root, "visits"));
        } finally {
            searchAi.shutdown();
        }
    }

    @Test
    void cachedStatisticsRefreshVisitsAndNewChildrenWithoutChangingEarlyStopOrPassPolicy() throws Exception {
        GoPlayer[][] board = emptyBoard();
        Object root = node(board, null, null);
        Object first = node(board, root, new int[]{3, 3});
        Object second = node(board, root, new int[]{4, 4});
        List<Object> children = new ArrayList<>(List.of(first, second));
        setField(root, "children", children);
        setField(ai, "currentRoot", root);
        var early = MCTSGoAI.class.getDeclaredMethod("shouldTerminateEarly", long.class);
        var winRate = MCTSGoAI.class.getDeclaredMethod("getCurrentWinRate");
        var best = MCTSGoAI.class.getDeclaredMethod("getBestMCTSMove", root.getClass());
        early.setAccessible(true);
        winRate.setAccessible(true);
        best.setAccessible(true);
        assertFalse((boolean) early.invoke(ai, 200L));
        assertEquals(0.0, winRate.invoke(ai));
        setField(first, "visits", 31.0);
        setField(first, "totalScore", -31.0);
        assertFalse((boolean) early.invoke(ai, 200L)); // Require enough visits to trust an advantage.
        Object originalSnapshot = getField(root, "selectionSnapshot");
        setField(first, "visits", 32.0);
        setField(first, "totalScore", -32.0);
        assertFalse((boolean) early.invoke(ai, 49L));
        assertTrue((boolean) early.invoke(ai, 50L));
        assertEquals(1.0, winRate.invoke(ai));
        assertSame(originalSnapshot, getField(root, "selectionSnapshot"));
        setField(first, "totalScore", -19.2);
        assertFalse((boolean) early.invoke(ai, 200L)); // One visited branch cannot establish a margin.
        setField(second, "visits", 32.0);
        setField(second, "totalScore", -3.2);
        assertFalse((boolean) early.invoke(ai, 100L));
        assertTrue((boolean) early.invoke(ai, 101L));
        assertArrayEquals(new int[]{3, 3}, (int[]) best.invoke(ai, root)); // Equal visits keep first.
        Object pass = node(board, root, new int[]{-1, -1});
        setField(pass, "visits", 64.0);
        setField(pass, "totalScore", -44.8);
        synchronized (root) { children.add(pass); }
        assertArrayEquals(new int[]{-1, -1}, (int[]) best.invoke(ai, root));
        assertEquals(0.7, (double) winRate.invoke(ai), 1e-12);
        double[] distribution = ai.getVisitDistribution();
        assertEquals(0.25, distribution[3 * 19 + 3]);
        assertEquals(0.25, distribution[4 * 19 + 4]);
        assertEquals(0.5, distribution[361]);
        assertNotSame(originalSnapshot, getField(root, "selectionSnapshot"));
        setField(root, "children", new ArrayList<>());
        assertFalse((boolean) early.invoke(ai, 200L));
        assertEquals(0.0, winRate.invoke(ai));
        assertNull(best.invoke(ai, root));
        assertEquals(1.0, ai.getVisitDistribution()[361]);
    }

    @Test
    void primitiveSamplingMatchesSeededReferenceAcrossTemperaturesAndChangingCandidateCounts() throws Exception {
        GoPlayer[][] board = emptyBoard();
        Object root = node(board, null, null);
        int[][] moves = {{3, 3}, {4, 4}, {-1, -1}, {5, 5}, {6, 6}};
        double[] visits = {1, 0, 16, 64, 4};
        List<Object> children = new ArrayList<>();
        for (int i = 0; i < moves.length; i++) {
            Object child = node(board, root, moves[i]);
            setField(child, "visits", visits[i]);
            children.add(child);
        }
        var sample = MCTSGoAI.class.getDeclaredMethod("sampleMCTSMove", root.getClass(), int.class);
        sample.setAccessible(true);
        Random actualRandom = new Random(7943), referenceRandom = new Random(7943);
        setField(ai, "random", actualRandom);
        for (double exploration : new double[]{1.0, 0.0, 0.4}) {
            setField(ai, "explorationScale", exploration);
            for (int count : new int[]{5, 1, 4, 2, 5}) {
                setField(root, "children", new ArrayList<>(children.subList(0, count)));
                for (int moveCount : new int[]{0, 29, 30, 100}) {
                    double temperature = moveCount < 30 ? 0.9 * exploration + 0.1 : 0.3 * exploration + 0.05;
                    List<Integer> indices = new ArrayList<>();
                    List<Double> weights = new ArrayList<>();
                    double sum = 0;
                    for (int i = 0; i < count; i++) {
                        if (visits[i] <= 0) continue;
                        double weight = temperature <= 0.01 ? visits[i] : Math.pow(visits[i], 1.0 / temperature);
                        indices.add(i);
                        weights.add(weight);
                        sum += weight;
                    }
                    for (int repeat = 0; repeat < 50; repeat++) {
                        double threshold = referenceRandom.nextDouble() * sum, cumulative = 0;
                        int expected = indices.getLast();
                        for (int i = 0; i < indices.size(); i++) {
                            cumulative += weights.get(i);
                            if (cumulative >= threshold) { expected = indices.get(i); break; }
                        }
                        assertArrayEquals(moves[expected], (int[]) sample.invoke(ai, root, moveCount));
                    }
                }
            }
        }
        for (Object child : children) setField(child, "visits", 0.0);
        setField(root, "children", children);
        assertArrayEquals(moves[0], (int[]) sample.invoke(ai, root, 0));
        assertEquals(referenceRandom.nextDouble(), actualRandom.nextDouble()); // Fallback consumes no randomness.
    }

    @Test
    void childSelectionPreservesScoresAndRefreshesOnlyChangedSnapshots() throws Exception {
        GoPlayer[][] board = emptyBoard();
        Object root = node(board, null, null);
        Object winning = node(board, root, new int[]{3, 3});
        Object losing = node(board, root, new int[]{4, 4});
        Object unexplored = node(board, root, new int[]{5, 5});
        setField(root, "visits", 100.0);
        setField(winning, "visits", 10.0);
        setField(winning, "totalScore", -8.0);
        setField(winning, "prior", 0.0);
        setField(losing, "visits", 10.0);
        setField(losing, "totalScore", 8.0);
        setField(losing, "prior", 0.0);
        setField(unexplored, "prior", 0.0);
        setField(root, "children", new ArrayList<>(List.of(losing, winning, unexplored)));
        var select = MCTSGoAI.class.getDeclaredMethod("selectBestChild", root.getClass());
        select.setAccessible(true);
        assertSame(winning, select.invoke(ai, root));
        Object[] originalSnapshot = (Object[]) getField(root, "selectionSnapshot");
        setField(unexplored, "prior", 1.0);
        assertSame(unexplored, select.invoke(ai, root));
        setField(unexplored, "prior", 0.0);
        setField(winning, "totalScore", 8.0);
        assertSame(unexplored, select.invoke(ai, root)); // Unvisited value is zero.
        setField(winning, "totalScore", 0.0);
        setField(losing, "totalScore", 0.0);
        assertSame(losing, select.invoke(ai, root)); // First child wins equal scores.
        setField(root, "visits", 0.0);
        setField(unexplored, "prior", 1.0);
        assertSame(unexplored, select.invoke(ai, root)); // Exploration works at zero parent visits.
        assertSame(originalSnapshot, getField(root, "selectionSnapshot"));
        setField(root, "children", new ArrayList<>(List.of(winning)));
        assertSame(winning, select.invoke(ai, root));
        Object[] singleSnapshot = (Object[]) getField(root, "selectionSnapshot");
        assertNotSame(originalSnapshot, singleSnapshot);
        setField(root, "children", new ArrayList<>(List.of(losing)));
        assertSame(losing, select.invoke(ai, root)); // A replaced list can have the same size.
        assertNotSame(singleSnapshot, getField(root, "selectionSnapshot"));
        assertSame(winning, singleSnapshot[0]); // Older snapshots remain immutable for readers.
        assertSame(unexplored, originalSnapshot[2]);
        setField(root, "children", new ArrayList<>());
        assertNull(select.invoke(ai, root));
        assertNull(getField(root, "selectionSnapshot"));
        assertNull(getField(root, "selectionSource"));
    }

    @Test
    void childSelectionSnapshotsStayIsolatedWhileOtherThreadsExpandParents() throws Exception {
        GoPlayer[][] board = emptyBoard();
        Object[] roots = {node(board, null, null), node(board, null, null)};
        Object[] winners = {node(board, roots[0], new int[]{3, 3}), node(board, roots[1], new int[]{4, 4})};
        List<List<Object>> children = new ArrayList<>();
        for (int n = 0; n < roots.length; n++) {
            setField(roots[n], "visits", 100.0);
            setField(winners[n], "visits", 1.0);
            setField(winners[n], "totalScore", -1.0);
            setField(winners[n], "prior", 0.0);
            children.add(new ArrayList<>(List.of(winners[n])));
            setField(roots[n], "children", children.get(n));
        }
        List<Object> additional = new ArrayList<>();
        for (int n = 0; n < 256; n++) {
            Object child = node(board, roots[0], new int[]{n / 19, n % 19});
            setField(child, "visits", 1.0);
            setField(child, "totalScore", 0.5);
            setField(child, "prior", 0.0);
            additional.add(child);
        }
        children.get(0).addAll(additional.subList(0, 64));
        var select = MCTSGoAI.class.getDeclaredMethod("selectBestChild", roots[0].getClass());
        select.setAccessible(true);
        CountDownLatch start = new CountDownLatch(1);
        ExecutorService pool = Executors.newFixedThreadPool(5);
        try {
            List<Future<?>> tasks = new ArrayList<>();
            tasks.add(pool.submit(() -> {
                start.await();
                for (Object child : additional.subList(64, additional.size())) {
                    synchronized (roots[0]) { children.get(0).add(child); }
                }
                return null;
            }));
            for (int reader = 0; reader < 4; reader++) {
                tasks.add(pool.submit(() -> {
                    start.await();
                    for (int repeat = 0; repeat < 500; repeat++) {
                        int parent = repeat % 2; // Alternate wide and narrow snapshots.
                        assertSame(winners[parent], select.invoke(ai, roots[parent]));
                    }
                    return null;
                }));
            }
            start.countDown();
            for (Future<?> task : tasks) task.get(10, TimeUnit.SECONDS);
            assertEquals(257, children.get(0).size());
            assertEquals(1, children.get(1).size());
            for (int parent = 0; parent < roots.length; parent++) {
                assertSame(winners[parent], select.invoke(ai, roots[parent]));
                assertEquals(children.get(parent).size(),
                        ((Object[]) getField(roots[parent], "selectionSnapshot")).length);
            }
        } finally {
            pool.shutdownNow();
            pool.awaitTermination(10, TimeUnit.SECONDS);
        }
    }

    @Test
    void parallelSearchSharesTreeWithoutExceedingIterationBudget() throws Exception {
        MCTSGoAI searchAi = new MCTSGoAI(1_000, 8, 32);
        try (GoGame game = GoGame.rulesOnly()) {
            game.pass();
            searchAi.getBestMove(game);

            AtomicLong iterations = (AtomicLong) getField(searchAi, "totalIterations");
            assertTrue(iterations.get() > 0);
            assertTrue(iterations.get() <= 8);
            double[] policy = searchAi.getVisitDistribution();
            assertEquals(362, policy.length);
            assertEquals(1.0, Arrays.stream(policy).sum(), 1.0e-9);
            for (double probability : policy) {
                assertTrue(Double.isFinite(probability));
                assertTrue(probability >= 0.0);
            }
        } finally {
            searchAi.shutdown();
        }
    }

    @Test
    void secondPassCreatesTerminalLeafWithoutChangingBoardOrCheckingSuperko() throws Exception {
        GoPlayer[][] board = emptyBoard();
        Object root = node(board, GoPlayer.BLACK, null, null,
                List.of(new int[]{-1, -1, 0}));
        setField(root, "consecutivePasses", 1);
        long hash = GoGame.boardHash(board);
        setField(root, "hash", hash);
        double[] policy = new double[362];
        policy[361] = 0.25;
        setField(root, "policyCache", policy);
        setField(root, "valueCached", true);
        setField(ai, "koHistory", Set.of(hash));

        var expand = MCTSGoAI.class.getDeclaredMethod("expand", root.getClass());
        expand.setAccessible(true);
        Object child = expand.invoke(ai, root);

        assertNotNull(child);
        assertTrue((boolean) getField(child, "terminal"));
        assertEquals(2, getField(child, "consecutivePasses"));
        assertEquals(GoPlayer.WHITE, getField(child, "player"));
        assertEquals(hash, getField(child, "hash"));
        assertBoardEquals(board, (GoPlayer[][]) getField(child, "board"));
        assertTrue(((List<?>) getField(child, "untriedMoves")).isEmpty());
        assertEquals(0.25 * 361.0, (double) getField(child, "prior"), 1.0e-9);

        var simulate = MCTSGoAI.class.getDeclaredMethod("simulate", root.getClass());
        simulate.setAccessible(true);
        assertEquals(0.075, (double) simulate.invoke(ai, child), 1.0e-9);
    }

    @Test
    void passChildVisitsUsePolicySlot361AndSelectedPassReturnsNull() throws Exception {
        MCTSGoAI searchAi = new MCTSGoAI(1_000, 1, 1);
        try (GoGame game = GoGame.rulesOnly()) {
            GoPlayer[][] board = (GoPlayer[][]) getField(game, "board");
            for (GoPlayer[] column : board) Arrays.fill(column, GoPlayer.BLACK);

            int[] move = searchAi.getBestMove(game);
            assertNull(move);
            double[] policy = searchAi.getVisitDistribution();
            assertEquals(1.0, policy[361]);
            assertEquals(1.0, Arrays.stream(policy).sum());

            GoTrainingMove.Applied applied = GoTrainingMove.apply(game, move, policy);
            assertNull(applied.coordinates());
            assertSame(policy, applied.policy());
            assertEquals(1, game.getConsecutivePasses());
            assertEquals(GoPlayer.WHITE, game.getCurrentPlayer());
        } finally {
            searchAi.shutdown();
        }
    }

    @Test
    void shutdownAndInterruptAreTypedErrorsInsteadOfPasses() throws Exception {
        MCTSGoAI stopped = new MCTSGoAI(100, 10, 1);
        stopped.shutdown();
        assertEquals(GoAI.MoveType.ERROR,
                stopped.getBestMoveResult(GoGame.rulesOnly()).type());

        MCTSGoAI interrupted = new MCTSGoAI(100, 10, 1);
        try {
            Thread.currentThread().interrupt();
            assertEquals(GoAI.MoveType.ERROR,
                    interrupted.getBestMoveResult(GoGame.rulesOnly()).type());
        } finally {
            Thread.interrupted();
            interrupted.shutdown();
        }
    }

    @Test
    void treeReusePreservesIncomingEdgePrior() throws Exception {
        GoPlayer[][] board = emptyBoard();
        Object root = node(board, GoPlayer.BLACK, null, null, List.of());
        Object child = node(copy(board), GoPlayer.WHITE, root, new int[]{4, 4}, List.of());
        setField(child, "prior", 17.5);
        setField(root, "children", new ArrayList<>(List.of(child)));
        setField(ai, "lastRoot", root);
        setField(ai, "lastMove", new int[]{4, 4});

        var reuse = MCTSGoAI.class.getDeclaredMethod("tryReuseTree", GoPlayer[][].class,
                GoPlayer.class);
        reuse.setAccessible(true);
        Object reused = reuse.invoke(ai, board, GoPlayer.WHITE);
        assertNotNull(reused);
        assertEquals(17.5, (double) getField(reused, "prior"));
    }

    @Test
    void treeCopyRebuildsCaptureAndPassBranchesWithIndependentBoardsAndMoveLists() throws Exception {
        GoPlayer[][] board = emptyBoard();
        board[0][0] = GoPlayer.WHITE;
        board[1][0] = GoPlayer.BLACK;
        Object root = node(board, GoPlayer.BLACK, null, null, List.of(new int[]{7, 7, 0}));
        GoPlayer[][] captureBoard = copy(board);
        captureBoard[0][0] = GoPlayer.NONE;
        captureBoard[0][1] = GoPlayer.BLACK;
        Object captured = node(captureBoard, GoPlayer.WHITE, root, new int[]{0, 1},
                List.of(new int[]{8, 8, 0}, new int[]{-1, -1, 0}));
        GoPlayer[][] replyBoard = copy(captureBoard);
        replyBoard[4][4] = GoPlayer.WHITE;
        Object reply = node(replyBoard, GoPlayer.BLACK, captured, new int[]{4, 4},
                List.of(new int[]{9, 9, 0}));
        Object passed = node(copy(board), GoPlayer.WHITE, root, new int[]{-1, -1}, List.of());
        setField(root, "children", new ArrayList<>(List.of(captured, passed)));
        setField(captured, "children", new ArrayList<>(List.of(reply)));
        setField(captured, "visits", 12.0);
        setField(captured, "totalScore", -3.0);
        setField(captured, "prior", 2.5);
        setField(captured, "hash", 123L); // Copies must compute hashes from rebuilt boards.
        setField(captured, "valueCache", 0.25);
        setField(captured, "valueCached", true);
        double[] policy = new double[362];
        policy[7] = 0.75;
        setField(captured, "policyCache", policy);
        setField(passed, "consecutivePasses", 1);
        setField(reply, "terminal", true);

        var clone = MCTSGoAI.class.getDeclaredMethod("deepCopyNode", root.getClass(), GoPlayer[][].class);
        clone.setAccessible(true);
        Object copied = clone.invoke(ai, root, board);
        List<?> children = (List<?>) getField(copied, "children");
        Object copiedCapture = children.get(0), copiedPass = children.get(1);
        Object copiedReply = ((List<?>) getField(copiedCapture, "children")).getFirst();
        assertNull(getField(copied, "parent"));
        assertSame(copied, getField(copiedCapture, "parent"));
        assertSame(copied, getField(copiedPass, "parent"));
        assertSame(copiedCapture, getField(copiedReply, "parent"));
        assertEquals(12.0, getField(copiedCapture, "visits"));
        assertEquals(-3.0, getField(copiedCapture, "totalScore"));
        assertEquals(2.5, getField(copiedCapture, "prior"));
        assertEquals(0.25, getField(copiedCapture, "valueCache"));
        assertEquals(true, getField(copiedCapture, "valueCached"));
        assertSame(policy, getField(copiedCapture, "policyCache"));
        assertEquals(1, getField(copiedPass, "consecutivePasses"));
        assertEquals(true, getField(copiedReply, "terminal"));

        List<Object> originals = List.of(root, captured, passed, reply);
        List<Object> copies = List.of(copied, copiedCapture, copiedPass, copiedReply);
        Set<GoPlayer[]> rows = java.util.Collections.newSetFromMap(new java.util.IdentityHashMap<>());
        for (Object original : originals) {
            for (GoPlayer[] row : (GoPlayer[][]) getField(original, "board")) assertTrue(rows.add(row));
        }
        for (int i = 0; i < copies.size(); i++) {
            GoPlayer[][] actual = (GoPlayer[][]) getField(copies.get(i), "board");
            assertBoardEquals((GoPlayer[][]) getField(originals.get(i), "board"), actual);
            assertEquals(GoGame.boardHash(actual), getField(copies.get(i), "hash"));
            for (GoPlayer[] row : actual) assertTrue(rows.add(row), "board rows must be independent");
            List<?> originalMoves = (List<?>) getField(originals.get(i), "untriedMoves");
            List<?> copiedMoves = (List<?>) getField(copies.get(i), "untriedMoves");
            assertNotSame(originalMoves, copiedMoves);
            int originalSize = originalMoves.size();
            assertEquals(originalSize, copiedMoves.size());
            copiedMoves.clear();
            assertEquals(originalSize, originalMoves.size());
        }
        ((GoPlayer[][]) getField(copiedCapture, "board"))[1][0] = GoPlayer.NONE;
        assertEquals(GoPlayer.BLACK, board[1][0]);
        assertEquals(GoPlayer.BLACK, captureBoard[1][0]);
        assertEquals(GoPlayer.BLACK, ((GoPlayer[][]) getField(copiedPass, "board"))[1][0]);
        assertEquals(GoPlayer.BLACK, ((GoPlayer[][]) getField(copiedReply, "board"))[1][0]);
    }

    @Test
    void treeReuseRejectsSameBoardWithWrongPlayerToMove() throws Exception {
        GoPlayer[][] board = emptyBoard();
        Object root = node(board, GoPlayer.BLACK, null, null, List.of());
        Object child = node(copy(board), GoPlayer.WHITE, root, new int[]{-1, -1}, List.of());
        setField(root, "children", new ArrayList<>(List.of(child)));
        setField(ai, "lastRoot", root);
        setField(ai, "lastMove", new int[]{-1, -1});

        var reuse = MCTSGoAI.class.getDeclaredMethod("tryReuseTree", GoPlayer[][].class,
                GoPlayer.class);
        reuse.setAccessible(true);
        assertNull(reuse.invoke(ai, board, GoPlayer.BLACK));
    }

    private static Object node(GoPlayer[][] board, Object parent, int[] move) throws Exception {
        return node(board, GoPlayer.BLACK, parent, move, List.of());
    }

    private static Object node(GoPlayer[][] board, GoPlayer player, Object parent, int[] move,
                               List<int[]> untriedMoves) throws Exception {
        Class<?> type = Class.forName(MCTSGoAI.class.getName() + "$MCTSNode");
        var constructor = type.getDeclaredConstructor(GoPlayer[][].class, GoPlayer.class,
                type, int[].class, List.class);
        constructor.setAccessible(true);
        return constructor.newInstance(board, player, parent, move, untriedMoves);
    }

    private static void setField(Object target, String name, Object value) throws Exception {
        Field field = target.getClass().getDeclaredField(name);
        field.setAccessible(true);
        field.set(target, value);
    }

    private static Object getField(Object target, String name) throws Exception {
        Field field = target.getClass().getDeclaredField(name);
        field.setAccessible(true);
        return field.get(target);
    }

    private static GoPlayer[][] emptyBoard() {
        GoPlayer[][] board = new GoPlayer[19][19];
        for (GoPlayer[] column : board) Arrays.fill(column, GoPlayer.NONE);
        return board;
    }

    private static GoPlayer[][] copy(GoPlayer[][] board) {
        return Arrays.stream(board).map(GoPlayer[]::clone).toArray(GoPlayer[][]::new);
    }

    private static void assertBoardEquals(GoPlayer[][] expected, GoPlayer[][] actual) {
        for (int x = 0; x < expected.length; x++) assertArrayEquals(expected[x], actual[x]);
    }
}
