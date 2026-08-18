package com.wzz.game_console.client.screens.games.landlord;

import java.util.*;

public class LandlordGame {
    public enum GameState {
        DEALING, BIDDING, PLAYING, ENDED
    }

    public enum PlayerType {
        HUMAN, AI_EASY, AI_HARD
    }

    private List<Card> deck;
    private List<List<Card>> playerHands;
    private List<Card> landlordCards;
    private GameState gameState;
    private int currentPlayer;
    private int landlordPlayer;
    private List<Card> lastPlayedCards;
    private int lastPlayer;
    private boolean[] passed;
    private int[] scores;

    public LandlordGame() {
        initializeGame();
    }

    private void initializeGame() {
        deck = createDeck();
        Collections.shuffle(deck);
        
        playerHands = new ArrayList<>();
        for (int i = 0; i < 3; i++) {
            playerHands.add(new ArrayList<>());
        }
        
        // 发牌：每人17张，留3张底牌
        for (int i = 0; i < 51; i++) {
            playerHands.get(i % 3).add(deck.get(i));
        }
        
        landlordCards = new ArrayList<>();
        for (int i = 51; i < 54; i++) {
            landlordCards.add(deck.get(i));
        }
        
        // 排序手牌
        for (List<Card> hand : playerHands) {
            Collections.sort(hand);
        }
        
        gameState = GameState.BIDDING;
        currentPlayer = 0;
        landlordPlayer = -1;
        lastPlayedCards = new ArrayList<>();
        lastPlayer = -1;
        passed = new boolean[3];
        scores = new int[3];
    }

    private List<Card> createDeck() {
        List<Card> cards = new ArrayList<>();
        
        // 添加普通牌
        for (Card.Suit suit : Arrays.asList(Card.Suit.SPADES, Card.Suit.HEARTS, 
                                          Card.Suit.DIAMONDS, Card.Suit.CLUBS)) {
            for (Card.Rank rank : Arrays.asList(Card.Rank.THREE, Card.Rank.FOUR,
                    Card.Rank.FIVE, Card.Rank.SIX, Card.Rank.SEVEN, Card.Rank.EIGHT,
                    Card.Rank.NINE, Card.Rank.TEN, Card.Rank.JACK, Card.Rank.QUEEN,
                    Card.Rank.KING, Card.Rank.ACE, Card.Rank.TWO)) {
                cards.add(new Card(suit, rank));
            }
        }
        
        // 添加大小王
        cards.add(new Card(Card.Suit.JOKER, Card.Rank.SMALL_JOKER));
        cards.add(new Card(Card.Suit.JOKER, Card.Rank.BIG_JOKER));
        
        return cards;
    }

    public boolean bid(int player, boolean wantToBeLandlord) {
        if (gameState != GameState.BIDDING || currentPlayer != player) {
            return false;
        }

        if (wantToBeLandlord) {
            landlordPlayer = player;
            playerHands.get(player).addAll(landlordCards);
            Collections.sort(playerHands.get(player));
            landlordCards.clear(); // 底牌已分配给地主，清理集合避免联机同步语义不一致
            gameState = GameState.PLAYING;
            currentPlayer = player;
            return true;
        } else {
            currentPlayer = (currentPlayer + 1) % 3;
            // 如果所有人都不要地主，重新开始
            if (currentPlayer == 0) {
                initializeGame();
            }
            return true;
        }
    }

    public boolean playCards(int player, List<Card> cards) {
        if (gameState != GameState.PLAYING || currentPlayer != player) {
            return false;
        }

        if (cards.isEmpty()) {
            // 桌面为空说明轮到当前玩家领出，领出必须实际出牌，禁止空过牌（让牌只允许在桌面已有牌时）
            if (lastPlayedCards.isEmpty()) {
                return false;
            }
            // 过牌
            passed[player] = true;
            
            // 检查是否其他两个玩家都过牌了
            int passedCount = 0;
            for (int i = 0; i < 3; i++) {
                if (i != lastPlayer && passed[i]) {
                    passedCount++;
                }
            }
            
            // 如果其他两人都过牌，清空桌面，下一轮由过牌的玩家开始
            if (passedCount >= 2 && lastPlayer != -1) {
                lastPlayedCards.clear();
                lastPlayer = -1;
                Arrays.fill(passed, false);
            }
        } else {
            // 检查出牌是否有效
            if (!isValidPlay(cards)) {
                return false;
            }
            
            // 移除卡牌
            List<Card> playerHand = playerHands.get(player);
            for (Card card : cards) {
                if (!playerHand.contains(card)) {
                    return false; // 玩家没有这张牌
                }
            }
            
            for (Card card : cards) {
                playerHand.remove(card);
            }
            
            lastPlayedCards = new ArrayList<>(cards);
            lastPlayer = player;
            Arrays.fill(passed, false);
            
            // 检查是否有人获胜
            if (playerHand.isEmpty()) {
                gameState = GameState.ENDED;
                calculateScores();
                return true;
            }
        }

        // 切换到下一个玩家
        currentPlayer = (currentPlayer + 1) % 3;
        return true;
    }

    private boolean isValidPlay(List<Card> cards) {
        if (cards.isEmpty()) return true; // 过牌总是有效的
        
        CardPattern pattern = analyzePattern(cards);
        if (pattern == null) return false; // 不是有效牌型
        
        // 如果是第一次出牌或者轮到自己主动出牌
        if (lastPlayedCards.isEmpty()) return true;
        
        CardPattern lastPattern = analyzePattern(lastPlayedCards);
        if (lastPattern == null) return false;
        
        return pattern.canBeat(lastPattern);
    }

    private CardPattern analyzePattern(List<Card> cards) {
        if (cards.isEmpty()) return null;
        
        List<Card> sortedCards = new ArrayList<>(cards);
        Collections.sort(sortedCards);
        
        // 统计每个牌值的数量
        Map<Integer, Integer> valueCount = new HashMap<>();
        for (Card card : sortedCards) {
            valueCount.merge(card.getValue(), 1, Integer::sum);
        }
        
        // 按数量分组
        Map<Integer, List<Integer>> countToValues = new HashMap<>();
        for (Map.Entry<Integer, Integer> entry : valueCount.entrySet()) {
            countToValues.computeIfAbsent(entry.getValue(), k -> new ArrayList<>()).add(entry.getKey());
        }
        
        // 对每组进行排序
        for (List<Integer> values : countToValues.values()) {
            Collections.sort(values);
        }
        
        int size = cards.size();
        
        // 单牌
        if (size == 1) {
            return new CardPattern(CardPattern.Type.SINGLE, sortedCards.get(0).getValue(), 1);
        }
        
        // 两张牌的情况
        if (size == 2) {
            // 王炸（大小王）
            if (valueCount.containsKey(16) && valueCount.containsKey(17)) {
                return new CardPattern(CardPattern.Type.JOKER_BOMB, 18, 1);
            }
            // 对子
            if (countToValues.containsKey(2) && countToValues.get(2).size() == 1) {
                return new CardPattern(CardPattern.Type.PAIR, countToValues.get(2).get(0), 1);
            }
        }
        
        // 三张牌
        if (size == 3) {
            if (countToValues.containsKey(3) && countToValues.get(3).size() == 1) {
                return new CardPattern(CardPattern.Type.TRIPLE, countToValues.get(3).get(0), 1);
            }
        }
        
        // 四张牌
        if (size == 4) {
            // 炸弹
            if (countToValues.containsKey(4) && countToValues.get(4).size() == 1) {
                return new CardPattern(CardPattern.Type.BOMB, countToValues.get(4).get(0), 1);
            }
            // 三带一
            if (countToValues.containsKey(3) && countToValues.containsKey(1) &&
                countToValues.get(3).size() == 1 && countToValues.get(1).size() == 1) {
                return new CardPattern(CardPattern.Type.TRIPLE_WITH_ONE, countToValues.get(3).get(0), 1);
            }
        }
        
        // 五张牌
        if (size == 5) {
            // 三带二
            if (countToValues.containsKey(3) && countToValues.containsKey(2) &&
                countToValues.get(3).size() == 1 && countToValues.get(2).size() == 1) {
                return new CardPattern(CardPattern.Type.TRIPLE_WITH_PAIR, countToValues.get(3).get(0), 1);
            }
            // 顺子
            if (isConsecutive(sortedCards, 1)) {
                return new CardPattern(CardPattern.Type.STRAIGHT, sortedCards.get(0).getValue(), size);
            }
        }
        
        // 顺子检查（5张以上）
        if (size >= 5) {
            if (isConsecutive(sortedCards, 1)) {
                return new CardPattern(CardPattern.Type.STRAIGHT, sortedCards.get(0).getValue(), size);
            }
            
            // 连对检查（必须是偶数张）
            if (size % 2 == 0 && size >= 6) {
                if (countToValues.containsKey(2) && countToValues.get(2).size() == size / 2) {
                    List<Integer> pairValues = countToValues.get(2);
                    if (isConsecutiveValues(pairValues)) {
                        return new CardPattern(CardPattern.Type.PAIR_STRAIGHT, pairValues.get(0), pairValues.size());
                    }
                }
            }
        }
        
        // 飞机检查（三张的连续）
        if (countToValues.containsKey(3)) {
            List<Integer> tripleValues = countToValues.get(3);
            if (tripleValues.size() >= 2 && isConsecutiveValues(tripleValues)) {
                int tripleCount = tripleValues.size();
                int expectedSize = tripleCount * 3; // 基础飞机大小
                
                // 纯飞机
                if (size == expectedSize) {
                    return new CardPattern(CardPattern.Type.TRIPLE_STRAIGHT, tripleValues.get(0), tripleCount);
                }
                
                // 飞机带单牌
                if (size == expectedSize + tripleCount && countToValues.containsKey(1)
                        && countToValues.get(1).size() == tripleCount) {
                    return new CardPattern(CardPattern.Type.TRIPLE_STRAIGHT, tripleValues.get(0), tripleCount);
                }
                
                // 飞机带对子
                if (size == expectedSize + tripleCount * 2 && countToValues.containsKey(2) && 
                    countToValues.get(2).size() == tripleCount) {
                    return new CardPattern(CardPattern.Type.TRIPLE_STRAIGHT, tripleValues.get(0), tripleCount);
                }
            }
        }
        
        return null; // 无效牌型
    }
    
    // 检查牌是否连续（用于顺子）
    private boolean isConsecutive(List<Card> cards, int step) {
        if (cards.size() < 2) return false;
        
        for (int i = 1; i < cards.size(); i++) {
            int currentValue = cards.get(i).getValue();
            int prevValue = cards.get(i-1).getValue();
            
            // 不能包含2(15)和王(16,17)
            if (currentValue >= 15 || prevValue >= 15) {
                return false;
            }
            
            if (currentValue != prevValue + step) {
                return false;
            }
        }
        return true;
    }
    
    // 检查数值是否连续
    private boolean isConsecutiveValues(List<Integer> values) {
        if (values.size() < 2) return false;
        
        for (int i = 1; i < values.size(); i++) {
            // 不能包含2(15)和王(16,17)
            if (values.get(i) >= 15 || values.get(i-1) >= 15) {
                return false;
            }
            
            if (values.get(i) != values.get(i-1) + 1) {
                return false;
            }
        }
        return true;
    }

    private void calculateScores() {
        int baseScore = 1;
        if (landlordPlayer != -1) {
            if (playerHands.get(landlordPlayer).isEmpty()) {
                // 地主获胜
                scores[landlordPlayer] = baseScore * 2;
                scores[(landlordPlayer + 1) % 3] = -baseScore;
                scores[(landlordPlayer + 2) % 3] = -baseScore;
            } else {
                // 农民获胜
                scores[landlordPlayer] = -baseScore * 2;
                scores[(landlordPlayer + 1) % 3] = baseScore;
                scores[(landlordPlayer + 2) % 3] = baseScore;
            }
        }
    }

    // Getters
    public GameState getGameState() { return gameState; }
    public int getCurrentPlayer() { return currentPlayer; }
    public int getLandlordPlayer() { return landlordPlayer; }
    public List<Card> getPlayerHand(int player) { return new ArrayList<>(playerHands.get(player)); }
    public List<Card> getLastPlayedCards() { return new ArrayList<>(lastPlayedCards); }
    public int getLastPlayer() { return lastPlayer; }
    public int[] getScores() { return scores.clone(); }
    public List<Card> getLandlordCards() { return new ArrayList<>(landlordCards); }

    // 重新开始游戏
    public void restart() {
        initializeGame();
    }
    
    // 在LandlordGame类中添加公共方法来分析牌型
    public CardPattern analyzeCards(List<Card> cards) {
        return analyzePattern(cards);
    }

    // ═══════════════════════════════════════════════
    //  LAN 序列化工具（供 LandlordGameScreen 使用）
    // ═══════════════════════════════════════════════

    /** 把牌列表序列化成字符串，格式 "suit_rank,suit_rank,..." */
    public static String serializeCards(List<Card> cards) {
        if (cards == null || cards.isEmpty()) return "";
        StringBuilder sb = new StringBuilder();
        for (Card c : cards) {
            if (sb.length() > 0) sb.append(",");
            sb.append(c.getSuit().ordinal()).append("_").append(c.getValue());
        }
        return sb.toString();
    }

    /** 把字符串还原成牌列表（对远端数据做防护，畸形片段直接跳过） */
    public static List<Card> deserializeCards(String s) {
        List<Card> list = new ArrayList<>();
        if (s == null || s.isEmpty()) return list;
        for (String part : s.split(",")) {
            try {
                String[] kv = part.split("_");
                if (kv.length != 2) continue;
                int suitOrd = Integer.parseInt(kv[0]);
                int val     = Integer.parseInt(kv[1]);
                // 范围校验：非法的花色/点数序号直接跳过，不抛异常
                if (suitOrd < 0 || suitOrd >= Card.Suit.values().length) continue;
                Card.Suit suit = Card.Suit.values()[suitOrd];
                Card.Rank rank = null;
                for (Card.Rank r : Card.Rank.values()) if (r.getValue() == val) { rank = r; break; }
                if (rank != null) list.add(new Card(suit, rank));
            } catch (NumberFormatException ignored) {
                // 畸形的远端数据，跳过该片段
            }
        }
        return list;
    }

    /**
     * 序列化"某个玩家视角"的完整状态：
     * phase|currentPlayer|landlordPlayer|lastPlayer|myHand|lastPlayed|cnt0,cnt1,cnt2|sc0,sc1,sc2|landlordCards
     */
    public String serializeFor(int forPlayer) {
        return gameState.name() + "|"
                + currentPlayer + "|"
                + landlordPlayer + "|"
                + lastPlayer + "|"
                + serializeCards(playerHands.get(forPlayer)) + "|"
                + serializeCards(lastPlayedCards) + "|"
                + playerHands.get(0).size() + "," + playerHands.get(1).size() + "," + playerHands.get(2).size() + "|"
                + scores[0] + "," + scores[1] + "," + scores[2] + "|"
                + serializeCards(landlordCards);
    }

    /**
     * 从序列化字符串恢复状态（客户端调用）。
     * myPlayerIndex: 本地玩家是哪一位（0/1/2）
     */
    public void applyState(String data, int myPlayerIndex, List<Card> myHand) {
        String[] parts = data.split("\\|", -1);
        if (parts.length < 9) return;
        gameState      = GameState.valueOf(parts[0]);
        currentPlayer  = Integer.parseInt(parts[1]);
        landlordPlayer = Integer.parseInt(parts[2]);
        lastPlayer     = Integer.parseInt(parts[3]);
        // 手牌：只有自己的是准确的，其他人只知数量
        List<Card> myCards = deserializeCards(parts[4]);
        playerHands.set(myPlayerIndex, myCards);
        myHand.clear(); myHand.addAll(myCards);
        lastPlayedCards = deserializeCards(parts[5]);
        String[] cnts = parts[6].split(",");
        // 更新其他玩家手牌数量（用空Card占位，渲染时只显示背面）
        for (int i = 0; i < 3; i++) {
            if (i == myPlayerIndex) continue;
            int cnt = Integer.parseInt(cnts[i]);
            List<Card> ph = playerHands.get(i);
            ph.clear();
            // 用 placeholder（不能用于真实出牌，只用于显示数量）
            for (int k = 0; k < cnt; k++) ph.add(new Card(Card.Suit.SPADES, Card.Rank.THREE));
        }
        String[] scs = parts[7].split(",");
        for (int i = 0; i < 3; i++) scores[i] = Integer.parseInt(scs[i]);
        landlordCards = deserializeCards(parts[8]);
    }
}