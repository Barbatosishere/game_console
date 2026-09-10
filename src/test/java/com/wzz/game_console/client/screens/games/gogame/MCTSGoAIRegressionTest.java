package com.wzz.game_console.client.screens.games.gogame;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

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
