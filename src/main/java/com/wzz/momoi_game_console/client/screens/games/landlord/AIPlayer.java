package com.wzz.momoi_game_console.client.screens.games.landlord;

import java.util.*;

public class AIPlayer {
    private Random random = new Random();
    private LandlordGame gameReference; // 添加游戏引用以访问牌型分析
    
    public void setGameReference(LandlordGame game) {
        this.gameReference = game;
    }
    
    public boolean decideBid(List<Card> hand, boolean isFirst) {
        // 简单AI：计算手牌强度
        int strength = calculateHandStrength(hand);
        
        if (isFirst) {
            return strength > 60; // 第一个叫地主需要更强的牌
        } else {
            return strength > 40; // 后面叫地主要求稍低
        }
    }
    
    public List<Card> chooseCardsToPlay(List<Card> hand, List<Card> lastCards, boolean isMyTurn) {
        if (lastCards.isEmpty()) {
            // 主动出牌，优先选择较小的组合
            return chooseActivePlay(hand);
        }
        
        // 被动出牌，尝试找能打过的最小组合
        List<Card> result = findMinimalBeat(hand, lastCards);
        
        // 如果手牌很少，更积极地出牌
        if (hand.size() <= 3) {
            return result != null ? result : new ArrayList<>();
        }
        
        // 30%概率选择过牌（如果不是最后几张牌）
        if (result != null && hand.size() > 5 && random.nextDouble() < 0.3) {
            return new ArrayList<>(); // 过牌
        }
        
        return result != null ? result : new ArrayList<>();
    }
    
    private List<Card> chooseActivePlay(List<Card> hand) {
        Map<Integer, List<Card>> groups = groupByValue(hand);
        Collections.sort(hand);
        
        // 优先出三带一或三带二
        for (int tripleValue : groups.keySet()) {
            if (groups.get(tripleValue).size() >= 3) {
                // 尝试三带一
                for (int singleValue : groups.keySet()) {
                    if (singleValue != tripleValue && groups.get(singleValue).size() >= 1) {
                        List<Card> result = new ArrayList<>();
                        result.addAll(groups.get(tripleValue).subList(0, 3));
                        result.add(groups.get(singleValue).get(0));
                        return result;
                    }
                }
                
                // 尝试三带二
                for (int pairValue : groups.keySet()) {
                    if (pairValue != tripleValue && groups.get(pairValue).size() >= 2) {
                        List<Card> result = new ArrayList<>();
                        result.addAll(groups.get(tripleValue).subList(0, 3));
                        result.addAll(groups.get(pairValue).subList(0, 2));
                        return result;
                    }
                }
                
                // 如果没有合适的带牌，出纯三张
                return groups.get(tripleValue).subList(0, 3);
            }
        }
        
        // 出对子
        for (int value : groups.keySet()) {
            if (groups.get(value).size() >= 2) {
                return groups.get(value).subList(0, 2);
            }
        }
        
        // 最后出单牌
        return Arrays.asList(hand.get(0));
    }
    
    private int calculateHandStrength(List<Card> hand) {
        int strength = 0;
        
        // 统计各种牌型
        Map<Integer, Integer> rankCount = new HashMap<>();
        for (Card card : hand) {
            rankCount.merge(card.getValue(), 1, Integer::sum);
        }
        
        // 大小王加分
        strength += rankCount.getOrDefault(16, 0) * 15; // 小王
        strength += rankCount.getOrDefault(17, 0) * 20; // 大王
        
        // 2和A加分
        strength += rankCount.getOrDefault(15, 0) * 8; // 2
        strength += rankCount.getOrDefault(14, 0) * 6; // A
        
        // 对子、三张、炸弹加分
        for (int count : rankCount.values()) {
            if (count == 2) strength += 3;
            else if (count == 3) strength += 8;
            else if (count == 4) strength += 25; // 炸弹
        }
        
        // 检查王炸
        if (rankCount.containsKey(16) && rankCount.containsKey(17)) {
            strength += 30; // 王炸额外加分
        }
        
        return strength;
    }
    
    private List<Card> findMinimalBeat(List<Card> hand, List<Card> lastCards) {
        Collections.sort(hand);
        if (gameReference == null) {
            return findSimpleBeat(hand, lastCards);
        }
        CardPattern targetPattern = gameReference.analyzeCards(lastCards);
        if (targetPattern == null) return null;
        return switch (targetPattern.getType()) {
            case SINGLE -> findMinimalSingle(hand, targetPattern.getValue());
            case PAIR -> findMinimalPair(hand, targetPattern.getValue());
            case TRIPLE -> findMinimalTriple(hand, targetPattern.getValue());
            case TRIPLE_WITH_ONE -> findMinimalTripleWithOne(hand, targetPattern.getValue());
            case TRIPLE_WITH_PAIR -> findMinimalTripleWithPair(hand, targetPattern.getValue());
            case STRAIGHT -> findMinimalStraight(hand, targetPattern.getValue(), targetPattern.getLength());
            case PAIR_STRAIGHT -> findMinimalPairStraight(hand, targetPattern.getValue(), targetPattern.getLength());
            case TRIPLE_STRAIGHT ->
                    findMinimalTripleStraight(hand, targetPattern.getValue(), targetPattern.getLength());
            case BOMB -> findMinimalBomb(hand, targetPattern.getValue());
            case JOKER_BOMB -> null;
        };

    }

    private List<Card> findSimpleBeat(List<Card> hand, List<Card> lastCards) {
        if (lastCards.size() == 1) {
            return findMinimalSingle(hand, lastCards.get(0).getValue());
        } else if (lastCards.size() == 2) {
            return findMinimalPair(hand, lastCards.get(0).getValue());
        } else if (lastCards.size() == 3) {
            return findMinimalTriple(hand, lastCards.get(0).getValue());
        } else if (lastCards.size() == 4) {
            // 可能是三带一或炸弹，尝试用炸弹
            return findAnyBomb(hand);
        }
        return findAnyBomb(hand);
    }
    
    private List<Card> findMinimalSingle(List<Card> hand, int targetValue) {
        for (Card card : hand) {
            if (card.getValue() > targetValue) {
                return Arrays.asList(card);
            }
        }
        return findAnyBomb(hand);
    }
    
    private List<Card> findMinimalPair(List<Card> hand, int targetValue) {
        Map<Integer, List<Card>> pairs = groupByValue(hand);
        
        for (int value : pairs.keySet()) {
            if (value > targetValue && pairs.get(value).size() >= 2) {
                return pairs.get(value).subList(0, 2);
            }
        }
        return findAnyBomb(hand);
    }
    
    private List<Card> findMinimalTriple(List<Card> hand, int targetValue) {
        Map<Integer, List<Card>> groups = groupByValue(hand);
        
        for (int value : groups.keySet()) {
            if (value > targetValue && groups.get(value).size() >= 3) {
                return groups.get(value).subList(0, 3);
            }
        }
        return findAnyBomb(hand);
    }
    
    private List<Card> findMinimalTripleWithOne(List<Card> hand, int targetValue) {
        Map<Integer, List<Card>> groups = groupByValue(hand);
        
        // 找合适的三张
        for (int tripleValue : groups.keySet()) {
            if (tripleValue > targetValue && groups.get(tripleValue).size() >= 3) {
                // 找一张单牌
                for (int singleValue : groups.keySet()) {
                    if (singleValue != tripleValue && groups.get(singleValue).size() >= 1) {
                        List<Card> result = new ArrayList<>();
                        result.addAll(groups.get(tripleValue).subList(0, 3));
                        result.add(groups.get(singleValue).get(0));
                        return result;
                    }
                }
            }
        }
        return findAnyBomb(hand);
    }
    
    private List<Card> findMinimalTripleWithPair(List<Card> hand, int targetValue) {
        Map<Integer, List<Card>> groups = groupByValue(hand);
        
        // 找合适的三张
        for (int tripleValue : groups.keySet()) {
            if (tripleValue > targetValue && groups.get(tripleValue).size() >= 3) {
                // 找一对
                for (int pairValue : groups.keySet()) {
                    if (pairValue != tripleValue && groups.get(pairValue).size() >= 2) {
                        List<Card> result = new ArrayList<>();
                        result.addAll(groups.get(tripleValue).subList(0, 3));
                        result.addAll(groups.get(pairValue).subList(0, 2));
                        return result;
                    }
                }
            }
        }
        return findAnyBomb(hand);
    }
    
    private List<Card> findMinimalStraight(List<Card> hand, int targetValue, int length) {
        // 简化实现：不处理顺子，尝试用炸弹
        return findAnyBomb(hand);
    }
    
    private List<Card> findMinimalPairStraight(List<Card> hand, int targetValue, int length) {
        // 简化实现：不处理连对，尝试用炸弹
        return findAnyBomb(hand);
    }
    
    private List<Card> findMinimalTripleStraight(List<Card> hand, int targetValue, int length) {
        // 简化实现：不处理飞机，尝试用炸弹
        return findAnyBomb(hand);
    }
    
    private List<Card> findMinimalBomb(List<Card> hand, int targetValue) {
        Map<Integer, List<Card>> groups = groupByValue(hand);
        
        for (int value : groups.keySet()) {
            if (value > targetValue && groups.get(value).size() == 4) {
                return new ArrayList<>(groups.get(value));
            }
        }
        
        // 尝试王炸
        return findJokerBomb(hand);
    }
    
    private List<Card> findAnyBomb(List<Card> hand) {
        Map<Integer, List<Card>> groups = groupByValue(hand);
        
        // 优先找普通炸弹
        for (int value : groups.keySet()) {
            if (groups.get(value).size() == 4) {
                return new ArrayList<>(groups.get(value));
            }
        }
        
        // 最后考虑王炸
        return findJokerBomb(hand);
    }
    
    private List<Card> findJokerBomb(List<Card> hand) {
        boolean hasSmallJoker = hand.stream().anyMatch(c -> c.getValue() == 16);
        boolean hasBigJoker = hand.stream().anyMatch(c -> c.getValue() == 17);
        
        if (hasSmallJoker && hasBigJoker) {
            List<Card> jokers = new ArrayList<>();
            for (Card card : hand) {
                if (card.getValue() == 16 || card.getValue() == 17) {
                    jokers.add(card);
                }
            }
            return jokers;
        }
        
        return null;
    }
    
    private Map<Integer, List<Card>> groupByValue(List<Card> hand) {
        Map<Integer, List<Card>> groups = new HashMap<>();
        for (Card card : hand) {
            groups.computeIfAbsent(card.getValue(), k -> new ArrayList<>()).add(card);
        }
        return groups;
    }
}