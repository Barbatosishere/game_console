package com.wzz.game_console.client.screens.games.landlord;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;

class AIPlayerListIsolationTest {

    private final AIPlayer ai = new AIPlayer();

    @Test
    void followingPlaySortsCopyWithoutChangingInputOrder() {
        Card six = card(Card.Rank.SIX);
        Card four = card(Card.Rank.FOUR);
        Card five = card(Card.Rank.FIVE);
        List<Card> hand = new ArrayList<>(List.of(six, four, five));

        List<Card> result = ai.chooseCardsToPlay(hand, List.of(card(Card.Rank.THREE)), true);

        assertEquals(List.of(four), result);
        assertSame(six, hand.get(0));
        assertSame(four, hand.get(1));
        assertSame(five, hand.get(2));
    }

    @Test
    void activeSingleResultIsMutableAndIndependentFromInput() {
        assertMutableIndependentResult(
                new ArrayList<>(List.of(card(Card.Rank.SIX))),
                List.of());
    }

    @Test
    void followingSingleResultIsMutableAndIndependentFromInput() {
        assertMutableIndependentResult(
                new ArrayList<>(List.of(card(Card.Rank.SIX), card(Card.Rank.FOUR))),
                List.of(card(Card.Rank.THREE)));
    }

    @Test
    void followingPairResultIsMutableAndIndependentFromInput() {
        assertMutableIndependentResult(
                new ArrayList<>(List.of(
                        card(Card.Rank.SIX), card(Card.Rank.FOUR),
                        new Card(Card.Suit.HEARTS, Card.Rank.FOUR))),
                List.of(card(Card.Rank.THREE), new Card(Card.Suit.HEARTS, Card.Rank.THREE)));
    }

    @Test
    void followingTripleResultIsMutableAndIndependentFromInput() {
        assertMutableIndependentResult(
                new ArrayList<>(List.of(
                        card(Card.Rank.FOUR),
                        new Card(Card.Suit.HEARTS, Card.Rank.FOUR),
                        new Card(Card.Suit.DIAMONDS, Card.Rank.FOUR))),
                List.of(
                        card(Card.Rank.THREE),
                        new Card(Card.Suit.HEARTS, Card.Rank.THREE),
                        new Card(Card.Suit.DIAMONDS, Card.Rank.THREE)));
    }

    private void assertMutableIndependentResult(List<Card> hand, List<Card> lastCards) {
        List<Card> originalOrder = new ArrayList<>(hand);
        List<Card> result = ai.chooseCardsToPlay(hand, lastCards, true);

        assertDoesNotThrow(() -> {
            result.clear();
            result.add(card(Card.Rank.BIG_JOKER));
        });
        assertEquals(originalOrder, hand);
    }

    private static Card card(Card.Rank rank) {
        Card.Suit suit = rank == Card.Rank.SMALL_JOKER || rank == Card.Rank.BIG_JOKER
                ? Card.Suit.JOKER
                : Card.Suit.SPADES;
        return new Card(suit, rank);
    }
}
