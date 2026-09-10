package com.wzz.game_console.client.screens.games;

import org.junit.jupiter.api.Test;

import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.*;

class LanSessionSequencerTest {
    private static final UUID SENDER = UUID.fromString("aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa");
    private static final UUID FIRST = UUID.fromString("11111111-1111-1111-1111-111111111111");
    private static final UUID SECOND = UUID.fromString("22222222-2222-2222-2222-222222222222");

    @Test
    void retiredSessionsCannotTakeOverAfterRotation() {
        LanMultiplayerScreen.SessionSequencer sequencer = new LanMultiplayerScreen.SessionSequencer();
        assertTrue(sequencer.acceptSession(SENDER, "GAME_MOVE", FIRST, 0));
        assertTrue(sequencer.acceptSession(SENDER, "GAME_MOVE", SECOND, 0));
        assertTrue(sequencer.acceptSession(SENDER, "GAME_MOVE", SECOND, 1));
        assertFalse(sequencer.acceptSession(SENDER, "GAME_MOVE", FIRST, 0));
        assertFalse(sequencer.acceptSession(SENDER, "GAME_MOVE", FIRST, 1));
    }

    @Test
    void sendersAndChannelsHaveIndependentSequenceWatermarks() {
        LanMultiplayerScreen.SessionSequencer sequencer = new LanMultiplayerScreen.SessionSequencer();
        UUID otherSender = UUID.randomUUID();
        assertTrue(sequencer.acceptSession(SENDER, "GAME_MOVE", FIRST, 10));
        assertTrue(sequencer.acceptSession(SENDER, "GAME_STATE_SYNC", FIRST, 2));
        assertTrue(sequencer.acceptSession(otherSender, "GAME_MOVE", FIRST, 1));
        assertFalse(sequencer.acceptSession(SENDER, "GAME_MOVE", FIRST, 9));
        assertTrue(sequencer.acceptSession(SENDER, "GAME_MOVE", FIRST, 12));
    }

    @Test
    void invalidSessionSwitchDoesNotConsumeCurrentSequence() {
        LanMultiplayerScreen.SessionSequencer sequencer = new LanMultiplayerScreen.SessionSequencer();
        assertTrue(sequencer.acceptSession(SENDER, "GAME_MOVE", FIRST, 5));
        assertFalse(sequencer.acceptSession(SENDER, "GAME_MOVE", SECOND, 10));
        assertFalse(sequencer.acceptSession(SENDER, "GAME_MOVE", FIRST, -1));
        assertTrue(sequencer.acceptSession(SENDER, "GAME_MOVE", FIRST, 6));
        assertTrue(sequencer.acceptSession(SENDER, "GAME_MOVE", SECOND, 0));
        assertFalse(sequencer.acceptSession(SENDER, "GAME_MOVE", FIRST, 100));
    }

    @Test
    void sameSessionRequiresStrictlyIncreasingSequences() {
        LanMultiplayerScreen.SessionSequencer sequencer = new LanMultiplayerScreen.SessionSequencer();
        assertTrue(sequencer.acceptSession(SENDER, "GAME_STATE_SYNC", FIRST, 0));
        assertFalse(sequencer.acceptSession(SENDER, "GAME_STATE_SYNC", FIRST, 0));
        assertTrue(sequencer.acceptSession(SENDER, "GAME_STATE_SYNC", FIRST, 2));
        assertFalse(sequencer.acceptSession(SENDER, "GAME_STATE_SYNC", FIRST, 1));
    }

    @Test
    void gateRejectsForeignAndUnknownSendersWithoutConsumingSlots() {
        LanMultiplayerScreen.SessionSequencer sequencer = new LanMultiplayerScreen.SessionSequencer();
        UUID outsider = UUID.randomUUID();

        assertFalse(sequencer.acceptFromPeer(null, SENDER, "GAME_MOVE", FIRST, 0));
        assertFalse(sequencer.acceptFromPeer(outsider, SENDER, "GAME_MOVE", FIRST, 0));
        assertFalse(sequencer.acceptFromPeer(SENDER, null, "GAME_MOVE", FIRST, 0));
        // 被拒绝的外来 sender 不得占用 sequence 水位：合法对端随后可从 0 正常开始
        assertTrue(sequencer.acceptFromPeer(SENDER, SENDER, "GAME_MOVE", FIRST, 0));
        assertTrue(sequencer.acceptFromPeer(SENDER, SENDER, "GAME_MOVE", FIRST, 1));
        assertFalse(sequencer.acceptFromPeer(outsider, SENDER, "GAME_MOVE", FIRST, 0));
    }

    @Test
    void concurrentAcceptsStayConsistentWithoutDeadlock() throws Exception {
        LanMultiplayerScreen.SessionSequencer sequencer = new LanMultiplayerScreen.SessionSequencer();
        int threads = 8;
        int attemptsPerThread = 2_000;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch start = new CountDownLatch(1);
        AtomicLong accepted = new AtomicLong();
        for (int t = 0; t < threads; t++) {
            final UUID session = t % 2 == 0 ? FIRST : SECOND;
            pool.submit(() -> {
                try {
                    start.await();
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return;
                }
                for (int i = 0; i < attemptsPerThread; i++) {
                    // 混合并发序列/会话轮换/非法输入，sequencer 必须保持同步且不抛异常
                    long sequence = switch (i % 4) {
                        case 0 -> i;
                        case 1 -> -1L;
                        case 2 -> i + 1L;
                        default -> i;
                    };
                    UUID incoming = i % 16 == 15 ? UUID.randomUUID() : session;
                    if (sequencer.acceptSession(SENDER, "GAME_MOVE", incoming, sequence)) {
                        accepted.incrementAndGet();
                    }
                    if (Thread.currentThread().isInterrupted()) return;
                }
            });
        }
        start.countDown();
        pool.shutdown();
        assertTrue(pool.awaitTermination(60, TimeUnit.SECONDS), "sequencer 并发压力出现死锁");
        assertTrue(accepted.get() > 0);
        assertTrue(accepted.get() < (long) threads * attemptsPerThread,
                "重复/非法序列不应全部被接受");
    }

    @Test
    void retiredSessionsStayBoundedPerKey() throws Exception {
        LanMultiplayerScreen.SessionSequencer sequencer = new LanMultiplayerScreen.SessionSequencer();
        assertTrue(sequencer.acceptSession(SENDER, "GAME_MOVE", FIRST, 0));
        // 连续 200 次换新会话，每次轮换都会退役上一个会话
        for (int i = 0; i < 200; i++) {
            assertTrue(sequencer.acceptSession(SENDER, "GAME_MOVE", UUID.randomUUID(), 0));
        }
        // 最近一次轮换产生的新会话是当前会话，可继续接收
        UUID current = UUID.randomUUID();
        assertTrue(sequencer.acceptSession(SENDER, "GAME_MOVE", current, 0));
        assertTrue(sequencer.acceptSession(SENDER, "GAME_MOVE", current, 1));
        // 反射核验退役集合不超过上限
        java.lang.reflect.Field field =
                LanMultiplayerScreen.SessionSequencer.class.getDeclaredField("retiredSessions");
        field.setAccessible(true);
        @SuppressWarnings("unchecked")
        java.util.Map<Object, java.util.Set<UUID>> retired = (java.util.Map<Object, java.util.Set<UUID>>) field.get(sequencer);
        assertEquals(1, retired.size());
        assertTrue(retired.values().iterator().next().size() <= 64,
                "retired sessions must stay bounded");
    }
}
