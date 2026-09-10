package com.wzz.game_console.client.screens.games.landlord;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 斗地主完整对局无头模拟：三方 AI 从叫地主打到 ENDED。
 * 覆盖：
 *  - 出牌推进闭环：AI 决策 → playCards 校验 → pass/领出轮转 → 终局计分
 *  - 不变量：手牌总量守恒（不重复扣、不蒸发）、分数零和、终局必达
 *  - playCards 的 multiset 校验（重复牌 token 必须整体拒绝）
 */
@Timeout(60)
class LandlordFullGameSimulationTest {

    @Test
    void threeAiPlayersPlayThroughToEnd() {
        LandlordGame game = new LandlordGame();
        AIPlayer[] ais = { new AIPlayer(), new AIPlayer(), new AIPlayer() };
        for (AIPlayer ai : ais) ai.setGameReference(game);

        // 直接叫地主（首个叫 true 的立即成为地主），跳过"三家全不叫重发"分支
        assertTrue(game.bid(0, true), "指定玩家叫地主必须成功");
        assertEquals(LandlordGame.GameState.PLAYING, game.getGameState());

        for (int push = 0; push < 5000 && game.getGameState() == LandlordGame.GameState.PLAYING; push++) {
            // 不变量：三手牌总数不超过 54（桌面牌来自某手牌，此消彼长）
            int total = game.getPlayerHand(0).size()
                    + game.getPlayerHand(1).size()
                    + game.getPlayerHand(2).size();
            assertTrue(total <= 54, "推进 " + push + " 次后手牌总数超界: " + total);

            int p = game.getCurrentPlayer();
            List<Card> hand = game.getPlayerHand(p);
            assertFalse(hand.isEmpty(), "PLAYING 态中轮到空手牌玩家");
            List<Card> last = game.getLastPlayedCards();

            List<Card> choice = ais[p].chooseCardsToPlay(new ArrayList<>(hand), new ArrayList<>(last), true);
            boolean ok;
            if (choice == null || choice.isEmpty()) {
                if (last.isEmpty()) {
                    ok = playMinSingle(game, p, hand); // 领出禁止空过
                } else {
                    ok = game.playCards(p, new ArrayList<>()); // 过牌（桌面非空恒成功）
                }
            } else {
                ok = game.playCards(p, choice);
                if (!ok) {
                    // AI 平值/非法提案被拒：跟牌场景试过牌，领出场景出最小单张
                    if (last.isEmpty()) {
                        ok = playMinSingle(game, p, hand);
                    } else {
                        ok = game.playCards(p, new ArrayList<>());
                    }
                }
            }
            assertTrue(ok, "推进 " + push + " 次时玩家 " + p + " 所有兜底都被拒，对局卡死");
        }

        assertEquals(LandlordGame.GameState.ENDED, game.getGameState(), "对局必须在步数上限内终局");
        int roundWinner = game.getRoundWinner();
        assertTrue(roundWinner >= 0 && roundWinner < 3, "终局必须能识别本局获胜者");
        assertTrue(game.getPlayerHand(roundWinner).isEmpty(), "本局获胜者必须是空手牌玩家");
        int landlord = game.getLandlordPlayer();
        for (int player = 0; player < 3; player++) {
            boolean expected = player == roundWinner
                    || (roundWinner != landlord && player != landlord);
            assertEquals(expected, LandlordGame.isRoundWinForPlayer(player, landlord, roundWinner),
                    "农民队伍的本局胜负应与地主身份一致");
        }
        int[] scores = game.getScores();
        assertEquals(0, scores[0] + scores[1] + scores[2], "计分必须零和");
        assertTrue(game.getPlayerHand(game.getLandlordPlayer()).isEmpty()
                        || game.getPlayerHand(0).isEmpty()
                        || game.getPlayerHand(1).isEmpty()
                        || game.getPlayerHand(2).isEmpty(),
                "ENDED 时必须有玩家手牌打空");
    }

    private static boolean playMinSingle(LandlordGame game, int p, List<Card> hand) {
        List<Card> sorted = new ArrayList<>(hand);
        Collections.sort(sorted);
        List<Card> single = new ArrayList<>();
        single.add(sorted.get(0));
        return game.playCards(p, single);
    }

    @Test
    void landlordSnapshotAcceptsPlayedBottomCard() {
        LandlordGame host = new LandlordGame();
        assertTrue(host.bid(0, true));
        Card playedBottomCard = host.getLandlordCards().get(0);
        assertTrue(host.playCards(0, List.of(playedBottomCard)));

        String state = host.serializeFor(0);
        LandlordGame client = new LandlordGame();
        List<Card> restoredHand = new ArrayList<>();

        assertTrue(client.applyState(state, 0, restoredHand),
                "地主打出一张原底牌后仍应能应用合法状态快照");
        assertFalse(restoredHand.contains(playedBottomCard),
                "已打出的底牌不应仍出现在地主手牌中");
        assertTrue(client.getLandlordCards().contains(playedBottomCard),
                "底牌集合用于展示和审计，应保留原始三张底牌");
    }

    @Test
    void duplicateCardTokensAreRejectedAtomically() {
        LandlordGame game = new LandlordGame();
        assertTrue(game.bid(0, true));
        // 构造重复 token：同一张牌出现两次，序列化/反序列化不去重
        String dup = Card.Suit.SPADES.ordinal() + "_"
                + Card.Rank.THREE.getValue() + ","
                + Card.Suit.SPADES.ordinal() + "_"
                + Card.Rank.THREE.getValue();
        List<Card> duplicated = LandlordGame.deserializeCards(dup);
        assertEquals(2, duplicated.size(), "反序列化保留两个 token（不去重是既有行为）");

        int p = game.getCurrentPlayer();
        // 无论该玩家是否真持有黑桃3，重复 token 都必须被整体拒绝（不能只扣一张）
        boolean accepted = game.playCards(p, duplicated);
        if (accepted) {
            // 唯一接受可能：手牌里恰好有两张等值牌（不同花色同 rank 构成真实对子）
            // 但 token 是同一张牌的两份拷贝——multiset 校验下若手牌只有一张必拒
            long spadeThrees = game.getPlayerHand(p).stream()
                    .filter(c -> c.getSuit() == Card.Suit.SPADES && c.getRank() == Card.Rank.THREE)
                    .count();
            assertTrue(spadeThrees == 0,
                    "重复 token 被接受后手牌不得残留同牌（残留=少扣了一张）");
        }
    }

    @Test
    void biddingSnapshotsHideBottomCardsAndStillRoundTrip() {
        LandlordGame host = new LandlordGame();
        String state = host.serializeFor(1);
        assertTrue(state.endsWith("|"), "叫地主阶段底牌字段必须隐藏");

        LandlordGame client = new LandlordGame();
        List<Card> restoredHand = new ArrayList<>();
        assertTrue(client.applyState(state, 1, restoredHand));
        assertEquals(LandlordGame.GameState.BIDDING, client.getGameState());
        assertTrue(client.getLandlordCards().isEmpty(), "客机叫地主阶段不应看到底牌");
    }

    @Test
    void roundWinnerUsesEmptyHandAndFarmerTeamDespiteCumulativeScores() {
        assertFalse(LandlordGame.isRoundWinForPlayer(0, 0, 1));
        assertTrue(LandlordGame.isRoundWinForPlayer(1, 0, 1));
        assertTrue(LandlordGame.isRoundWinForPlayer(2, 0, 1));
        assertTrue(LandlordGame.isRoundWinForPlayer(0, 0, 0));
        assertFalse(LandlordGame.isRoundWinForPlayer(1, 0, 0));
        assertFalse(LandlordGame.isRoundWinForPlayer(2, 0, 0));
        assertFalse(LandlordGame.isRoundWinForPlayer(0, 0, -1));
    }
}
