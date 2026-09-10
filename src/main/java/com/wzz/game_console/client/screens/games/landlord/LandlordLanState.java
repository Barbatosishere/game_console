package com.wzz.game_console.client.screens.games.landlord;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.Set;

final class LandlordLanState {
    private LandlordLanState() {}

    record Applied(boolean init, int seat, long token, long sequence, String rejection) {}

    static final class Receiver {
        private long token;
        private long sequence = -1;
        private int seat = -1;
        private final Set<Long> retired = new HashSet<>();

        Applied receive(LandlordGame game, String data) {
            var envelope = LandlordGame.decodeNetworkState(data);
            if (envelope == null || envelope.sequence() <= sequence || retired.contains(envelope.sessionToken())) {
                return null;
            }
            String payload = envelope.payload();
            boolean init = payload.startsWith("INIT:");
            boolean newRound = token != envelope.sessionToken();
            if (newRound && !init) return null;
            int incomingSeat = seat;
            String rejection = null;
            try {
                if (init) {
                    int split = payload.indexOf('|', 5);
                    if (split < 0) return null;
                    incomingSeat = Integer.parseInt(payload.substring(5, split));
                    if (incomingSeat < 1 || incomingSeat > 2 || (seat != -1 && seat != incomingSeat)) return null;
                    if (!game.applyState(payload.substring(split + 1), incomingSeat, new ArrayList<>())) return null;
                } else if (payload.startsWith("STATE:")) {
                    if (seat < 1 || !game.applyState(payload.substring(6), seat, new ArrayList<>())) return null;
                } else if (payload.startsWith("REJECT:")) {
                    rejection = payload.substring(7);
                } else {
                    return null;
                }
            } catch (IllegalArgumentException ex) {
                return null;
            }
            if (newRound && token != 0) retired.add(token);
            token = envelope.sessionToken();
            sequence = envelope.sequence();
            seat = incomingSeat;
            return new Applied(init, seat, token, sequence, rejection);
        }
    }

    static String encodeAction(long token, String action) {
        return LandlordGame.encodeNetworkState(token, 0, action);
    }

    static String decodeAction(long token, String data) {
        var envelope = LandlordGame.decodeNetworkState(data);
        if (envelope == null || envelope.sessionToken() != token) return null;
        String action = envelope.payload();
        return action.startsWith("BID:") || action.startsWith("PLAY:") ? action : null;
    }
}
