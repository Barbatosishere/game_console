package com.wzz.game_console.client.screens.games.landlord;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 斗地主逻辑包无头模拟（纯 JDK）。
 * 覆盖：
 *  - 发牌不变量：3×17 + 底牌 3 = 54 张全唯一
 *  - 牌型识别与压制关系（canBeat 单调性、炸弹/王炸通吃）
 *  - LAN 序列化往返一致
 *  - AI 叫分与出牌决策产出合法（出牌是手牌子集）
 */
@Timeout(60)
class LandlordSimulationTest {

    @Test
    void dealSatisfiesFiftyFourUniqueCardsInvariant() {
        LandlordGame game = new LandlordGame();
        Set<String> seen = new HashSet<>();
        List<Card> all = new ArrayList<>();
        for (int p = 0; p < 3; p++) {
            List<Card> hand = game.getPlayerHand(p);
            assertEquals(17, hand.size(), "玩家 " + p + " 手牌必须 17 张");
            all.addAll(hand);
        }
        List<Card> bottom = game.getLandlordCards();
        assertEquals(3, bottom.size(), "底牌必须 3 张");
        all.addAll(bottom);

        assertEquals(54, all.size(), "总牌数必须 54");
        for (Card c : all) assertTrue(seen.add(c.getSuit() + "#" + c.getRank()),
                "出现重复牌: " + c);
    }

    @Test
    void cardPatternDominanceRelationsHold() {
        List<Card> pairSmall = patternOf(Card.Rank.FIVE, 2);
        List<Card> pairBig = patternOf(Card.Rank.TEN, 2);
        CardPattern ps = new LandlordGame().analyzeCards(pairSmall);
        CardPattern pb = new LandlordGame().analyzeCards(pairBig);
        assertEquals(CardPattern.Type.PAIR, ps.getType());
        assertEquals(Card.Rank.FIVE.getValue(), ps.getValue(), "对子主值必须等于牌面点数");
        assertTrue(pb.canBeat(ps), "大对子必须压小对子");
        assertFalse(ps.canBeat(pb), "小对子不得反压大对子");

        // 炸弹通吃非炸，王炸通吃炸弹
        List<Card> bomb = new ArrayList<>();
        for (Card.Suit s : new Card.Suit[]{Card.Suit.SPADES, Card.Suit.HEARTS,
                Card.Suit.DIAMONDS, Card.Suit.CLUBS})
            bomb.add(new Card(s, Card.Rank.SEVEN));
        CardPattern bombP = new LandlordGame().analyzeCards(bomb);
        assertEquals(CardPattern.Type.BOMB, bombP.getType());
        assertTrue(bombP.canBeat(pb), "炸弹必须压普通牌型");

        List<Card> jokerBomb = List.of(
                new Card(Card.Suit.JOKER, Card.Rank.SMALL_JOKER),
                new Card(Card.Suit.JOKER, Card.Rank.BIG_JOKER));
        CardPattern jb = new LandlordGame().analyzeCards(jokerBomb);
        assertEquals(CardPattern.Type.JOKER_BOMB, jb.getType());
        assertTrue(jb.canBeat(bombP), "王炸必须压炸弹");
        assertFalse(ps.canBeat(bombP), "小对子不得压炸弹");
    }

    private static List<Card> patternOf(Card.Rank rank, int n) {
        // 同 rank 只有一个 Suit.JOKER，其余用四花色；n ≤ 4 的场景
        Card.Suit[] suits = {Card.Suit.SPADES, Card.Suit.HEARTS, Card.Suit.DIAMONDS, Card.Suit.CLUBS};
        List<Card> list = new ArrayList<>();
        for (int i = 0; i < n; i++) list.add(new Card(suits[i], rank));
        return list;
    }

    @Test
    void serializationRoundTripIsLossless() {
        LandlordGame game = new LandlordGame();
        List<Card> hand = game.getPlayerHand(0);
        String enc = LandlordGame.serializeCards(hand);
        assertFalse(enc.isEmpty());
        List<Card> dec = LandlordGame.deserializeCards(enc);
        assertEquals(hand.size(), dec.size(), "往返后张数必须一致");
        assertEquals(enc, LandlordGame.serializeCards(dec), "再序列化字符串必须逐字一致");
        assertTrue(LandlordGame.deserializeCards("").isEmpty(), "空串应还原为空列表");
    }

    @Test
    void aiDecisionsAreAlwaysSubsetsOfOwnHand() {
        LandlordGame game = new LandlordGame();
        AIPlayer ai = new AIPlayer();
        ai.setGameReference(game);

        for (int p = 0; p < 3; p++) {
            List<Card> hand = game.getPlayerHand(p);
            ai.decideBid(hand);

            List<Card> lead = ai.chooseCardsToPlay(hand, new ArrayList<>(), true);
            if (lead != null && !lead.isEmpty()) {
                assertTrue(hand.containsAll(lead), "玩家 " + p + " 首出的牌不在自己手里");
            }
        }

        // 跟牌：给一个真实存在的单张压一压
        List<Card> hand = game.getPlayerHand(0);
        List<Card> lastSingle = new ArrayList<>(hand.subList(0, 1));
        List<Card> follow = ai.chooseCardsToPlay(hand, lastSingle, true);
        if (follow != null && !follow.isEmpty()) {
            assertTrue(hand.containsAll(follow), "跟牌必须是手牌子集");
        }
    }
}
