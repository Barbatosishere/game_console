package com.wzz.game_console.client.screens.games.landlord;

import java.util.*;
import java.util.logging.Logger;

public class LandlordGame {
    private static final Logger LOGGER = Logger.getLogger(LandlordGame.class.getName());
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
    private int[] scores = new int[3];
    /** 各玩家实际出牌次数（不出空过不计），用于春天/反春天判定 */
    private final int[] playCounts = new int[3];
    /** 结算前是否有人打出过王炸（火箭），翻倍用 */
    private boolean rocketPlayed = false;

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
        // scores 不在重发/重开时清零：跨局累计积分
        Arrays.fill(playCounts, 0);
        rocketPlayed = false;
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

            // 移除卡牌——multiset 原子校验：先在副本上逐张扣减，全部成功才应用到真实手牌。
            // 修复：原版"先 contains 全部、再逐个 remove"两段式，远端 PLAY 报文含重复牌时
            // （deserializeCards 不去重），第二次 remove 静默落空 → 手牌少扣一张且
            // lastPlayedCards 记牌数虚高，联机状态永久失真。
            List<Card> playerHand = playerHands.get(player);
            List<Card> remaining = new ArrayList<>(playerHand);
            for (Card card : cards) {
                if (!remaining.remove(card)) {
                    return false; // 玩家没有这张牌，或重复牌数超出持有数
                }
            }
            playerHand.clear();
            playerHand.addAll(remaining);

            lastPlayedCards = new ArrayList<>(cards);
            lastPlayer = player;
            Arrays.fill(passed, false);

            // 出牌统计：春天/反春天判定与王炸翻倍
            playCounts[player]++;
            CardPattern played = analyzePattern(cards);
            if (played != null && played.getType() == CardPattern.Type.JOKER_BOMB) {
                rocketPlayed = true;
            }

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
        
        // 四带两单（6张）/ 四带两对（8张）
        if (size == 6 && countToValues.containsKey(4) && countToValues.get(4).size() == 1) {
            return new CardPattern(CardPattern.Type.FOUR_WITH_TWO_SINGLES, countToValues.get(4).get(0), 1);
        }
        if (size == 8 && countToValues.containsKey(4) && countToValues.get(4).size() == 1
                && countToValues.containsKey(2) && countToValues.get(2).size() == 2) {
            return new CardPattern(CardPattern.Type.FOUR_WITH_TWO_PAIRS, countToValues.get(4).get(0), 1);
        }

        // 飞机检查：主体必须是恰好三张且牌值连续，翅牌不能占用主体牌值。
        if (countToValues.containsKey(3)) {
            List<Integer> tripleValues = new ArrayList<>();
            for (Map.Entry<Integer, Integer> entry : valueCount.entrySet()) {
                if (entry.getValue() == 3) tripleValues.add(entry.getKey());
            }
            Collections.sort(tripleValues);
            if (tripleValues.size() >= 2 && isConsecutiveValues(tripleValues)) {
                int tripleCount = tripleValues.size();
                int expectedSize = tripleCount * 3;
                Set<Integer> bodyValues = new HashSet<>(tripleValues);
                int wingCards = 0;
                boolean validSingleWings = true;
                boolean validPairWings = true;
                int pairWingValues = 0;
                for (Map.Entry<Integer, Integer> entry : valueCount.entrySet()) {
                    if (bodyValues.contains(entry.getKey())) continue;
                    int count = entry.getValue();
                    wingCards += count;
                    if (count >= 3) validSingleWings = false;
                    if (count != 2) validPairWings = false;
                    if (count == 2) pairWingValues++;
                }

                // 纯飞机
                if (size == expectedSize && wingCards == 0) {
                    return new CardPattern(CardPattern.Type.TRIPLE_STRAIGHT, tripleValues.get(0), tripleCount);
                }
                // 飞机带单：每个翅占一张，允许同值散牌，但不能带出额外三张。
                if (size == expectedSize + tripleCount && wingCards == tripleCount && validSingleWings) {
                    return new CardPattern(CardPattern.Type.TRIPLE_STRAIGHT_WITH_SINGLE,
                            tripleValues.get(0), tripleCount);
                }
                // 飞机带对：每个翅是不同牌值的完整对子，不能拆三张或炸弹。
                if (size == expectedSize + tripleCount * 2 && wingCards == tripleCount * 2
                        && validPairWings && pairWingValues == tripleCount) {
                    return new CardPattern(CardPattern.Type.TRIPLE_STRAIGHT_WITH_PAIR,
                            tripleValues.get(0), tripleCount);
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
            // 倍数：春天（农民零出牌）/反春天（地主仅出过一手）/王炸 各×2，可叠加
            int multiplier = 1;
            boolean landlordWins = playerHands.get(landlordPlayer).isEmpty();
            int farmerA = (landlordPlayer + 1) % 3, farmerB = (landlordPlayer + 2) % 3;
            if (landlordWins && playCounts[farmerA] == 0 && playCounts[farmerB] == 0) {
                multiplier *= 2; // 春天
            }
            if (!landlordWins && playCounts[landlordPlayer] == 1) {
                multiplier *= 2; // 反春天
            }
            if (rocketPlayed) {
                multiplier *= 2; // 火箭（王炸）
            }
            if (landlordWins) {
                // 地主获胜
                scores[landlordPlayer] += baseScore * 2 * multiplier;
                scores[farmerA] += -baseScore * multiplier;
                scores[farmerB] += -baseScore * multiplier;
            } else {
                // 农民获胜
                scores[landlordPlayer] += -baseScore * 2 * multiplier;
                scores[farmerA] += baseScore * multiplier;
                scores[farmerB] += baseScore * multiplier;
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
    public boolean applyState(String data, int myPlayerIndex, List<Card> myHand) {
        try {
            if (data == null || myHand == null || myPlayerIndex < 0 || myPlayerIndex >= 3) {
                throw new IllegalArgumentException("invalid player index or null state");
            }
            String[] parts = data.split("\\|", -1);
            if (parts.length != 9) throw new IllegalArgumentException("invalid state field count");
            GameState parsedState = GameState.valueOf(parts[0]);
            int parsedCurrent = parseRequiredPlayerIndex(parts[1]);
            int parsedLandlord = parsePlayerIndex(parts[2], true);
            int parsedLast = parsePlayerIndex(parts[3], true);

            List<Card> parsedHand = deserializeCardsStrict(parts[4]);
            List<Card> parsedLastCards = deserializeCardsStrict(parts[5]);
            List<Card> parsedBottom = deserializeCardsStrict(parts[8]);
            if (parsedHand == null || parsedLastCards == null || parsedBottom == null) {
                throw new IllegalArgumentException("invalid card list");
            }
            String[] cnts = parts[6].split(",", -1);
            String[] scs = parts[7].split(",", -1);
            if (cnts.length != 3 || scs.length != 3) throw new IllegalArgumentException("invalid array length");
            int[] parsedCounts = new int[3];
            int[] parsedScores = new int[3];
            for (int i = 0; i < 3; i++) {
                parsedCounts[i] = parseNonNegativeInt(cnts[i]);
                if (parsedCounts[i] > 54) throw new IllegalArgumentException("invalid hand count");
                parsedScores[i] = parseInt(scs[i]);
            }
            if (parsedCounts[myPlayerIndex] != parsedHand.size()
                    || parsedHand.size() > 54 || parsedLastCards.size() > 54 || parsedBottom.size() > 3) {
                throw new IllegalArgumentException("invalid card list size");
            }

            List<List<Card>> newHands = new ArrayList<>();
            for (int i = 0; i < 3; i++) newHands.add(new ArrayList<>());
            newHands.set(myPlayerIndex, parsedHand);
            for (int i = 0; i < 3; i++) {
                if (i != myPlayerIndex) {
                    for (int k = 0; k < parsedCounts[i]; k++) {
                        newHands.get(i).add(new Card(Card.Suit.SPADES, Card.Rank.THREE));
                    }
                }
            }
            gameState = parsedState;
            currentPlayer = parsedCurrent;
            landlordPlayer = parsedLandlord;
            lastPlayer = parsedLast;
            playerHands = newHands;
            lastPlayedCards = parsedLastCards;
            System.arraycopy(parsedScores, 0, scores, 0, scores.length);
            if (landlordPlayer == -1) landlordCards = parsedBottom;
            myHand.clear();
            myHand.addAll(parsedHand);
        } catch (RuntimeException e) {
            LOGGER.warning("[斗地主] 丢弃非法状态报文: " + e);
            return false;
        }
        return true;
    }

    private static int parseInt(String value) {
        if (value == null || value.isEmpty()) throw new IllegalArgumentException("empty integer");
        return Integer.parseInt(value);
    }

    private static int parseNonNegativeInt(String value) {
        int result = parseInt(value);
        if (result < 0) throw new IllegalArgumentException("negative integer");
        return result;
    }

    private static int parsePlayerIndex(String value, boolean allowSentinel) {
        int result = parseInt(value);
        if ((allowSentinel && result == -1) || result >= 0 && result < 3) return result;
        throw new IllegalArgumentException("invalid player index");
    }

    private static int parseRequiredPlayerIndex(String value) {
        int result = parseInt(value);
        if (result < 0 || result >= 3) throw new IllegalArgumentException("invalid player index");
        return result;
    }

    public static List<Card> deserializeCardsStrict(String value) {
        if (value == null || value.isEmpty()) return new ArrayList<>();
        List<Card> result = new ArrayList<>();
        for (String part : value.split(",", -1)) {
            String[] fields = part.split("_", -1);
            if (fields.length != 2) return null;
            try {
                int suitIndex = parseInt(fields[0]);
                int rankValue = parseInt(fields[1]);
                Card.Suit[] suits = Card.Suit.values();
                if (suitIndex < 0 || suitIndex >= suits.length || rankValue < 3 || rankValue > 17) return null;
                Card.Suit suit = suits[suitIndex];
                Card.Rank rank = Arrays.stream(Card.Rank.values()).filter(r -> r.getValue() == rankValue).findFirst().orElse(null);
                if (rank == null || (suit == Card.Suit.JOKER) != (rankValue >= 16)) return null;
                result.add(new Card(suit, rank));
            } catch (RuntimeException e) {
                return null;
            }
        }
        return result;
    }
}