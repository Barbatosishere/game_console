package com.wzz.game_console.client.screens.games;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;

final class RealtimeLanState {
    private RealtimeLanState() {}

    record Received<T>(T snapshot, boolean newRound) {}
    record Cell(int x, int y) {}
    record Color(int p1X, int p1Y, boolean p1Dead, int p2X, int p2Y, boolean p2Dead,
                 int target, int score1, int score2, int level, boolean gameOver,
                 String winner, int[][] grid) {}
    record Ice(int level, float iceX, float iceY, boolean iceGround, boolean iceDead,
               float fireX, float fireY, boolean fireGround, boolean fireDead,
               int collected, int total, boolean gameOver, boolean victory,
               int difficulty, List<Cell> removedDiamonds) {}

    static final class Receiver {
        private UUID session;
        private long sequence = -1;
        private final Set<UUID> retired = new HashSet<>();

        // State sequences span rounds within one screen, so a lost RESTART is recoverable.
        <T> Received<T> receive(String data, Function<String, T> decoder) {
            if (data == null) return null;
            try {
                UUID incoming = null;
                long next = -1;
                String payload = data;
                if (data.startsWith("v2|")) {
                    String[] fields = data.split("\\|", 4);
                    if (fields.length != 4) return null;
                    incoming = UUID.fromString(fields[1]);
                    next = Long.parseLong(fields[2]);
                    if (next < 1 || next <= sequence || retired.contains(incoming)) return null;
                    payload = fields[3];
                } else if (session != null) {
                    return null;
                }
                T snapshot = decoder.apply(payload);
                if (snapshot == null) return null;
                boolean newRound = incoming != null && !incoming.equals(session);
                if (incoming != null) {
                    if (newRound && session != null) retired.add(session);
                    session = incoming;
                    sequence = next;
                }
                return new Received<>(snapshot, newRound);
            } catch (IllegalArgumentException ex) {
                return null;
            }
        }

        String input(String payload) {
            return session == null ? null : "INPUT|" + session + "|" + payload;
        }

        boolean restart(String data) {
            if (data == null) return false;
            if ("RESTART".equals(data)) return session == null;
            try {
                String[] fields = data.split("\\|", -1);
                if (fields.length != 3 || !"RESTART".equals(fields[0])) return false;
                UUID incoming = UUID.fromString(fields[1]);
                long next = Long.parseLong(fields[2]);
                if (next < 1 || next <= sequence || incoming.equals(session)
                        || retired.contains(incoming)) return false;
                if (session != null) retired.add(session);
                session = incoming;
                sequence = next;
                return true;
            } catch (IllegalArgumentException ex) {
                return false;
            }
        }
    }

    static String decodeInput(UUID session, String data) {
        if (session == null || data == null) return null;
        String[] fields = data.split("\\|", 3);
        if (fields.length != 3 || !"INPUT".equals(fields[0]) || fields[2].isEmpty()) return null;
        try {
            return session.equals(UUID.fromString(fields[1])) ? fields[2] : null;
        } catch (IllegalArgumentException ex) {
            return null;
        }
    }

    static Color parseColor(String payload) {
        if (payload == null) return null;
        try {
            return parseColorStrict(payload);
        } catch (IllegalArgumentException ex) {
            return null;
        }
    }

    private static Color parseColorStrict(String payload) {
        String[] sections = payload.split(";", -1);
        if (sections.length != 2) return null;
        String[] f = sections[0].split(",", -1);
        if (f.length != 11 && f.length != 12) return null;
        int p1X = integer(f[0], 0, 15), p1Y = integer(f[1], 0, 15);
        boolean p1Dead = flag(f[2]);
        int p2X = integer(f[3], 0, 15), p2Y = integer(f[4], 0, 15);
        boolean p2Dead = flag(f[5]);
        int target = integer(f[6], 0, 7);
        int score1 = integer(f[7], 0, Integer.MAX_VALUE);
        int score2 = integer(f[8], 0, Integer.MAX_VALUE);
        int level = integer(f[9], 1, 999);
        boolean gameOver = flag(f[10]);
        String[] cells = sections[1].split(",", -1);
        if (cells.length != 256) return null;
        int[][] grid = new int[16][16];
        for (int i = 0; i < cells.length; i++) {
            try {
                grid[i / 16][i % 16] = integer(cells[i], 0, 7);
            } catch (IllegalArgumentException ex) {
                grid[i / 16][i % 16] = 0;
            }
        }
        return new Color(p1X, p1Y, p1Dead, p2X, p2Y, p2Dead, target, score1, score2,
                level, gameOver, gameOver && f.length == 12 ? f[11] : "", grid);
    }

    static Ice parseIce(String payload, int defaultDifficulty) {
        if (payload == null) return null;
        try {
            return parseIceStrict(payload, defaultDifficulty);
        } catch (IllegalArgumentException ex) {
            return null;
        }
    }

    private static Ice parseIceStrict(String payload, int defaultDifficulty) {
        String[] sections = payload.split(";", -1);
        if (sections.length > 2) return null;
        String[] f = sections[0].split(",", -1);
        if (f.length != 13 && f.length != 14) return null;
        int level = integer(f[0], 1, 99);
        int iceXRaw = Integer.parseInt(f[1]), iceYRaw = Integer.parseInt(f[2]);
        boolean iceGround = flag(f[3]), iceDead = flag(f[4]);
        int fireXRaw = Integer.parseInt(f[5]), fireYRaw = Integer.parseInt(f[6]);
        boolean fireGround = flag(f[7]), fireDead = flag(f[8]);
        // Jumping above the map and the final falling step may leave its visible bounds.
        if (iceXRaw < 0 || iceXRaw > 3200 || iceYRaw < -2400 || iceYRaw > 4800
                || fireXRaw < 0 || fireXRaw > 3200 || fireYRaw < -2400 || fireYRaw > 4800) {
            return null;
        }
        float iceX = iceXRaw / 10f, iceY = iceYRaw / 10f;
        float fireX = fireXRaw / 10f, fireY = fireYRaw / 10f;
        int collected = integer(f[9], 0, 300), total = integer(f[10], 0, 300);
        if (collected > total) return null;
        boolean gameOver = flag(f[11]), victory = flag(f[12]);
        if (victory && !gameOver) return null;
        int difficulty = f.length == 14 ? integer(f[13], 0, 2) : defaultDifficulty;
        List<Cell> removed = new ArrayList<>();
        if (sections.length == 2 && !sections[1].isEmpty()) {
            for (String coord : sections[1].split("\\|", -1)) {
                String[] xy = coord.split("_", -1);
                if (xy.length != 2) return null;
                removed.add(new Cell(integer(xy[0], 0, 19), integer(xy[1], 0, 14)));
            }
        }
        return new Ice(level, iceX, iceY, iceGround, iceDead, fireX, fireY, fireGround,
                fireDead, collected, total, gameOver, victory, difficulty, List.copyOf(removed));
    }

    private static int integer(String value, int min, int max) {
        int parsed = Integer.parseInt(value);
        if (parsed < min || parsed > max) throw new IllegalArgumentException("out of range");
        return parsed;
    }

    private static boolean flag(String value) {
        if ("0".equals(value)) return false;
        if ("1".equals(value)) return true;
        throw new IllegalArgumentException("invalid flag");
    }
}
