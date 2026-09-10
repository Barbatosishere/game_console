package com.wzz.game_console.client.screens.games.gogame;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

final class GoScoringProtocol {
    static final String PREFIX = "GO_SCORE1";
    static final int MAX_MARKS = 19 * 19;

    private GoScoringProtocol() {}

    enum Phase { PLAYING, SCORING, FINISHED, OTHER }

    record Snapshot(UUID epoch, long revision, Set<Long> marks, String digest, double komi) {
        Snapshot {
            marks = Set.copyOf(marks);
        }

        String encode(String action) {
            return PREFIX + "|" + action + "|" + epoch + "|" + revision + "|"
                    + encodeMarks(marks) + "|" + digest + "|" + formatKomi(komi);
        }
    }

    record Receiver(boolean client, UUID peer, UUID gameEpoch, Phase phase, Snapshot current) {}

    static Snapshot receiveSnapshot(GoGame game, Receiver receiver, UUID sender, String[] parts) {
        if (!receiver.client() || receiver.peer() == null || !receiver.peer().equals(sender)
                || !game.isGameOver() || parts.length != 7 || !PREFIX.equals(parts[0])) return null;
        String action = parts[1];
        if (!"BEGIN".equals(action) && !"STATE".equals(action) && !"FINAL".equals(action)) return null;
        if (receiver.phase() != Phase.PLAYING && receiver.phase() != Phase.SCORING) return null;
        UUID epoch = parseEpoch(parts[2]);
        Long revision = parseRevision(parts[3]);
        Double komi = parseKomi(parts[6]);
        if (epoch == null || revision == null || komi == null || !epoch.equals(receiver.gameEpoch())) return null;
        Snapshot current = receiver.current();
        if ("BEGIN".equals(action)) {
            if (current != null && epoch.equals(current.epoch())) return null;
        } else if (receiver.phase() == Phase.SCORING) {
            if (current == null || !epoch.equals(current.epoch()) || revision < current.revision()
                    || Double.compare(komi, current.komi()) != 0) return null;
        }
        if ("FINAL".equals(action) && receiver.phase() != Phase.SCORING) return null;
        Set<Long> marks = parseMarks(parts[4]);
        for (long point : marks) {
            if (game.getStone(x(point), y(point)) == GoPlayer.NONE) return null;
        }
        if (!hasValidDigest(marks, parts[5])) return null;
        Snapshot incoming = new Snapshot(epoch, revision, marks, parts[5], komi);
        if ("FINAL".equals(action) && !incoming.equals(current)) return null;
        return incoming;
    }

    static UUID parseEpoch(String value) {
        try {
            return UUID.fromString(value);
        } catch (RuntimeException ignored) {
            return null;
        }
    }

    static Long parseRevision(String value) {
        try {
            long revision = Long.parseLong(value);
            return revision >= 0 ? revision : null;
        } catch (NumberFormatException ignored) {
            return null;
        }
    }

    static Double parseKomi(String value) {
        try {
            double komi = Double.parseDouble(value);
            if (!Double.isFinite(komi) || komi < -100.0d || komi > 100.0d) return null;
            double normalized = GoGame.normalizeKomi(komi);
            return Double.toString(normalized).equals(value) ? normalized : null;
        } catch (RuntimeException ignored) {
            return null;
        }
    }

    static String formatKomi(double komi) {
        return Double.toString(GoGame.normalizeKomi(komi));
    }

    static Set<Long> parseMarks(String encoded) {
        Set<Long> marks = new LinkedHashSet<>();
        if (encoded == null || encoded.isEmpty()) return marks;
        String[] points = encoded.split(";", -1);
        if (points.length > MAX_MARKS) throw new IllegalArgumentException("too many marked stones");
        for (String point : points) {
            String[] xy = point.split(",", -1);
            if (xy.length != 2) throw new IllegalArgumentException("malformed marked stone");
            int x;
            int y;
            try {
                x = Integer.parseInt(xy[0]);
                y = Integer.parseInt(xy[1]);
            } catch (NumberFormatException e) {
                throw new IllegalArgumentException("malformed marked stone", e);
            }
            if (x < 0 || x >= 19 || y < 0 || y >= 19) {
                throw new IllegalArgumentException("marked stone is outside the board");
            }
            if (!marks.add(key(x, y))) throw new IllegalArgumentException("duplicate marked stone");
        }
        return marks;
    }

    static String encodeMarks(Set<Long> marks) {
        if (marks == null || marks.isEmpty()) return "";
        if (marks.size() > MAX_MARKS) throw new IllegalArgumentException("too many marked stones");
        List<Long> sorted = new ArrayList<>(marks);
        sorted.sort(Long::compare);
        StringBuilder encoded = new StringBuilder(sorted.size() * 6);
        for (long point : sorted) {
            int x = x(point);
            int y = y(point);
            if (x < 0 || x >= 19 || y < 0 || y >= 19) {
                throw new IllegalArgumentException("marked stone is outside the board");
            }
            if (encoded.length() > 0) encoded.append(';');
            encoded.append(x).append(',').append(y);
        }
        return encoded.toString();
    }

    static String digest(Set<Long> marks) {
        return digestEncoded(encodeMarks(marks));
    }

    static boolean hasValidDigest(Set<Long> marks, String expected) {
        return expected != null && MessageDigest.isEqual(
                digest(marks).getBytes(StandardCharsets.US_ASCII),
                expected.getBytes(StandardCharsets.US_ASCII));
    }

    static long key(int x, int y) {
        return ((long) x << 32) | (y & 0xffffffffL);
    }

    static int x(long point) {
        return (int) (point >> 32);
    }

    static int y(long point) {
        return (int) point;
    }

    private static String digestEncoded(String encoded) {
        try {
            byte[] bytes = MessageDigest.getInstance("SHA-256")
                    .digest(encoded.getBytes(StandardCharsets.UTF_8));
            StringBuilder hex = new StringBuilder(bytes.length * 2);
            for (byte value : bytes) hex.append(String.format("%02x", value & 0xff));
            return hex.toString();
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is unavailable", e);
        }
    }
}
