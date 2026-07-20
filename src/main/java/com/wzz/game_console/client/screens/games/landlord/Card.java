package com.wzz.game_console.client.screens.games.landlord;

public class Card implements Comparable<Card> {
    public enum Suit {
        SPADES, HEARTS, DIAMONDS, CLUBS, JOKER
    }

    public enum Rank {
        THREE(3), FOUR(4), FIVE(5), SIX(6), SEVEN(7), EIGHT(8), 
        NINE(9), TEN(10), JACK(11), QUEEN(12), KING(13), 
        ACE(14), TWO(15), SMALL_JOKER(16), BIG_JOKER(17);

        private final int value;
        Rank(int value) { this.value = value; }
        public int getValue() { return value; }
    }

    private final Suit suit;
    private final Rank rank;

    public Card(Suit suit, Rank rank) {
        this.suit = suit;
        this.rank = rank;
    }

    public Suit getSuit() { return suit; }
    public Rank getRank() { return rank; }
    public int getValue() { return rank.getValue(); }

    @Override
    public int compareTo(Card other) {
        return Integer.compare(this.getValue(), other.getValue());
    }

    @Override
    public String toString() {
        if (suit == Suit.JOKER) {
            return rank == Rank.SMALL_JOKER ? "小王" : "大王";
        }
        String suitStr = switch (suit) {
            case SPADES -> "♠";
            case HEARTS -> "♥";
            case DIAMONDS -> "♦";
            case CLUBS -> "♣";
            default -> "";
        };
        String rankStr = switch (rank) {
            case ACE -> "A";
            case JACK -> "J";
            case QUEEN -> "Q";
            case KING -> "K";
            case TWO -> "2";
            default -> String.valueOf(rank.getValue());
        };
        return suitStr + rankStr;
    }
}