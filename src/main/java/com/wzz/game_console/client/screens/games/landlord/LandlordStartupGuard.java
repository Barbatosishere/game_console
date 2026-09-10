package com.wzz.game_console.client.screens.games.landlord;

final class LandlordStartupGuard {
    static final long FIRST_SEND_TICK = 5;
    static final long RETRY_INTERVAL_TICKS = 20;
    static final int MAX_SEND_ATTEMPTS = 5;
    /**
     * 45s：必须覆盖大厅邀请窗口（600 tick）——斗地主要等两名玩家都接受才发 INIT，
     * 先接受的客机可能要等主机凑齐人；主机侧由 MAX_SEND_ATTEMPTS 在约 105 tick 收敛。
     */
    static final long TIMEOUT_TICKS = 900;

    enum HostAction { NONE, SEND, ABORT }

    private LandlordStartupGuard() {}

    static HostAction hostAction(long elapsedTicks, long lastSendElapsedTicks, int sendAttempts) {
        if (elapsedTicks >= TIMEOUT_TICKS) return HostAction.ABORT;
        boolean sendDue = elapsedTicks >= FIRST_SEND_TICK
                && (lastSendElapsedTicks == Long.MIN_VALUE
                || elapsedTicks - lastSendElapsedTicks >= RETRY_INTERVAL_TICKS);
        if (!sendDue) return HostAction.NONE;
        return sendAttempts >= MAX_SEND_ATTEMPTS ? HostAction.ABORT : HostAction.SEND;
    }

    static boolean clientTimedOut(boolean waitingForInit, long elapsedTicks) {
        return waitingForInit && elapsedTicks >= TIMEOUT_TICKS;
    }
}
