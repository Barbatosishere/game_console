package com.wzz.game_console.client.screens.games.gogame;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.*;
import java.util.concurrent.*;

import static org.junit.jupiter.api.Assertions.*;

@Timeout(60)
class GoCandidateRegressionTest {
    @Test
    void analyzedCandidatesMatchSimulationIncludingScoresOrderAndKo() throws Exception {
        MCTSGoAI ai = new MCTSGoAI(0, 0, 1);
        try {
            Random random = new Random(537);
            List<GoPlayer[][]> positions = new ArrayList<>();
            try (GoGame game = GoGame.rulesOnly()) {
                positions.add(game.getBoardCopy());
                for (int step = 0; step < 350; step++) {
                    game.placeStone(random.nextInt(19), random.nextInt(19));
                    if (step % 15 == 0) positions.add(game.getBoardCopy());
                }
            }
            for (int n = 0; n < 32; n++) {
                GoPlayer[][] board = new GoPlayer[19][19];
                for (GoPlayer[] row : board) for (int y = 0; y < 19; y++) {
                    row[y] = random.nextDouble() < n / 31.0
                            ? (random.nextBoolean() ? GoPlayer.BLACK : GoPlayer.WHITE) : GoPlayer.NONE;
                }
                positions.add(board);
            }
            // A large group touching the move on all four sides must be captured only once.
            GoPlayer[][] surrounded = new GoPlayer[19][19];
            for (GoPlayer[] row : surrounded) Arrays.fill(row, GoPlayer.WHITE);
            surrounded[9][9] = GoPlayer.NONE;
            positions.add(surrounded);

            for (GoPlayer[][] board : positions) for (GoPlayer player : new GoPlayer[]{GoPlayer.BLACK, GoPlayer.WHITE}) {
                Set<Long> banned = new HashSet<>();
                for (int x = 0; x < 19; x++) for (int y = 0; y < 19; y++) {
                    if (board[x][y] == GoPlayer.NONE && random.nextInt(12) == 0) {
                        GoPlayer[][] after = copy(board);
                        if ((boolean) method("simulatePlaceStone", GoPlayer[][].class, int.class, int.class, GoPlayer.class)
                                .invoke(ai, after, x, y, player)) banned.add(GoGame.boardHash(after));
                    }
                }
                field("koHistory").set(ai, banned);
                long hash = GoGame.boardHash(board);
                GoPlayer[][] before = copy(board);
                List<int[]> expected = simulationCandidates(ai, board, player);
                List<int[]> actual = candidates(ai, board, player);
                assertEquals(expected.size(), actual.size());
                for (int i = 0; i < actual.size(); i++) {
                    assertArrayEquals(expected.get(i), actual.get(i), "candidate " + i);
                    GoPlayer[][] after = copy(board);
                    assertTrue((boolean) method("simulatePlaceStone", GoPlayer[][].class, int.class, int.class, GoPlayer.class)
                            .invoke(ai, after, actual.get(i)[0], actual.get(i)[1], player));
                    assertFalse(banned.contains(GoGame.boardHash(after)));
                }
                for (int x = 0; x < 19; x++) assertArrayEquals(before[x], board[x]);
                assertEquals(hash, GoGame.boardHash(board));
            }
        } finally {
            ai.shutdown();
        }
    }

    @Test
    void fallbackAndInvalidPlayerRemainSafe() throws Exception {
        MCTSGoAI ai = new MCTSGoAI(0, 0, 1);
        try {
            GoPlayer[][] board = new GoPlayer[19][19];
            for (GoPlayer[] row : board) Arrays.fill(row, GoPlayer.WHITE);
            board[0][0] = GoPlayer.NONE;
            assertEquals(1, candidates(ai, board, GoPlayer.BLACK).size());
            assertTrue(candidates(ai, board, GoPlayer.NONE).isEmpty());
            field("koHistory").set(ai, Set.of(GoGame.xorStone(0L, 0, 0, GoPlayer.BLACK)));
            assertTrue(candidates(ai, board, GoPlayer.BLACK).isEmpty());

            // Ban every normally kept move; the isolated corner is legal but knowledge-pruned.
            for (GoPlayer[] row : board) Arrays.fill(row, GoPlayer.NONE);
            Set<Long> history = new HashSet<>();
            for (int x = 0; x < 19; x++) for (int y = 0; y < 19; y++) {
                if (x != 0 || y != 0) history.add(GoGame.xorStone(0L, x, y, GoPlayer.BLACK));
            }
            field("koHistory").set(ai, history);
            List<int[]> fallback = candidates(ai, board, GoPlayer.BLACK);
            assertEquals(1, fallback.size());
            assertArrayEquals(new int[]{0, 0, 0}, fallback.getFirst());
        } finally {
            ai.shutdown();
        }
    }

    @Test
    void candidateGroupScratchIsIsolatedAcrossConcurrentBoards() throws Exception {
        MCTSGoAI ai = new MCTSGoAI(0, 0, 1);
        ExecutorService pool = Executors.newFixedThreadPool(8);
        try (GoGame game = GoGame.rulesOnly()) {
            GoPlayer[][][] boards = new GoPlayer[4][][];
            Random random = new Random(758);
            for (int n = 0; n < boards.length; n++) {
                for (int step = 0; step < 60; step++) game.placeStone(random.nextInt(19), random.nextInt(19));
                boards[n] = game.getBoardCopy();
            }
            List<List<int[]>> expected = new ArrayList<>();
            for (GoPlayer[][] board : boards) expected.add(candidates(ai, board, GoPlayer.BLACK));
            List<Future<List<int[]>>> futures = new ArrayList<>();
            for (int i = 0; i < 160; i++) {
                int position = i % boards.length;
                futures.add(pool.submit(() -> candidates(ai, copy(boards[position]), GoPlayer.BLACK)));
            }
            for (int i = 0; i < futures.size(); i++) {
                List<int[]> result = futures.get(i).get(10, TimeUnit.SECONDS);
                List<int[]> reference = expected.get(i % boards.length);
                assertEquals(reference.size(), result.size());
                for (int j = 0; j < result.size(); j++) assertArrayEquals(reference.get(j), result.get(j));
            }
        } finally {
            pool.shutdownNow();
            pool.awaitTermination(10, TimeUnit.SECONDS);
            ai.shutdown();
        }
    }

    @Test
    void analyzedHashRejectsKoRecaptureAndAllowsItAfterThreats() throws Exception {
        MCTSGoAI ai = new MCTSGoAI(0, 0, 1);
        try (GoGame game = GoGame.rulesOnly()) {
            int[][] moves = {{3, 5}, {3, 4}, {5, 5}, {5, 4}, {4, 6}, {4, 3}, {4, 4}, {4, 5}};
            for (int[] move : moves) assertTrue(game.placeStone(move[0], move[1]));
            field("koHistory").set(ai, game.getPositionHistory());
            assertFalse(candidates(ai, game.getBoardCopy(), GoPlayer.BLACK).stream()
                    .anyMatch(move -> move[0] == 4 && move[1] == 4));
            assertTrue(game.placeStone(16, 16));
            assertTrue(game.placeStone(0, 0));
            field("koHistory").set(ai, game.getPositionHistory());
            assertTrue(candidates(ai, game.getBoardCopy(), GoPlayer.BLACK).stream()
                    .anyMatch(move -> move[0] == 4 && move[1] == 4));
        } finally {
            ai.shutdown();
        }
    }

    @SuppressWarnings("unchecked")
    private static List<int[]> candidates(MCTSGoAI ai, GoPlayer[][] board, GoPlayer player) throws Exception {
        return (List<int[]>) method("getAllValidMoves", long.class, GoPlayer[][].class, GoPlayer.class)
                .invoke(ai, GoGame.boardHash(board), board, player);
    }

    private static List<int[]> simulationCandidates(MCTSGoAI ai, GoPlayer[][] board, GoPlayer player) throws Exception {
        List<int[]> moves = new ArrayList<>();
        Method collect = method("collectValidMoves", long.class, GoPlayer[][].class, GoPlayer.class, List.class, boolean.class);
        collect.invoke(ai, GoGame.boardHash(board), board, player, moves, true);
        if (moves.isEmpty()) collect.invoke(ai, GoGame.boardHash(board), board, player, moves, false);
        moves.sort(Comparator.comparingInt(move -> move[2]));
        return moves;
    }

    private static Method method(String name, Class<?>... types) throws Exception {
        Method result = MCTSGoAI.class.getDeclaredMethod(name, types);
        result.setAccessible(true);
        return result;
    }

    private static Field field(String name) throws Exception {
        Field result = MCTSGoAI.class.getDeclaredField(name);
        result.setAccessible(true);
        return result;
    }

    private static GoPlayer[][] copy(GoPlayer[][] board) {
        return Arrays.stream(board).map(GoPlayer[]::clone).toArray(GoPlayer[][]::new);
    }
}
