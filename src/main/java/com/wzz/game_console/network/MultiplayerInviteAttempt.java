package com.wzz.game_console.network;

import java.util.UUID;

/** Strict wire representation for one invitation attempt. */
public final class MultiplayerInviteAttempt {
    private static final String PREFIX = "INV1|";

    private MultiplayerInviteAttempt() {}

    public static String encode(UUID nonce) {
        if (nonce == null) throw new IllegalArgumentException("Invitation nonce is required");
        return PREFIX + nonce;
    }

    public static UUID parse(String data) {
        if (data == null || !data.startsWith(PREFIX)) return null;
        String value = data.substring(PREFIX.length());
        if (value.isEmpty() || value.indexOf('|') >= 0) return null;
        try {
            return UUID.fromString(value);
        } catch (IllegalArgumentException ignored) {
            return null;
        }
    }

    public static boolean matches(String data, UUID expected) {
        return expected != null && expected.equals(parse(data));
    }
}
