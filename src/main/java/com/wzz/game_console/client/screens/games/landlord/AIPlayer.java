package com.wzz.game_console.client.screens.games.landlord;

import java.util.*;

public class AIPlayer {
    private Random random = new Random();
    private LandlordGame gameReference; // 添加游戏引用以访问牌型分析

    public void setGameReference(LandlordGame game) {
        this.gameReference = game;
    }

    public boolean decideBid(List<Card> hand) {
        // 简单AI：计算手牌强度
        return calculateHandStrength(hand) > 40;
    }

    /** 兼容入口：不带身份信息（无法识别队友，按无队友逻辑处理）。 */
    public List<Card> chooseCardsToPlay(List<Card> hand, List<Card> lastCards, boolean isMyTurn) {
        return chooseCardsToPlay(hand, lastCards, isMyTurn, -1, -1, -1, null);
    }

    /**
     * 带身份上下文的出牌决策。
     *
     * @param myIdx         我在哪一方（0/1/2，未知传 -1）
     * @param lastPlayerIdx 上一个出牌者（桌面为空时传 -1）
     * @param landlordIdx   地主是哪一方（未知传 -1）
     * @param handCounts    三家手牌数（未知传 null）
     */
    public List<Card> chooseCardsToPlay(List<Card> hand, List<Card> lastCards, boolean isMyTurn,
                                        int myIdx, int lastPlayerIdx, int landlordIdx, int[] handCounts) {
        if (lastCards.isEmpty()) {
            // 主动出牌，优先选择较小的组合
            return chooseActivePlay(hand);
        }

        boolean iAmLandlord = landlordIdx >= 0 && myIdx == landlordIdx;
        // 上家是队友：我是农民且出牌者是另一个农民
        boolean lastIsTeammate = !iAmLandlord && landlordIdx >= 0
                && lastPlayerIdx >= 0 && lastPlayerIdx != myIdx && lastPlayerIdx != landlordIdx;

        if (lastIsTeammate) {
            // 默认不压队友（炸弹只留给地主）；仅当队友报牌（剩 ≤2 张）时帮压
            boolean teammateLow = handCounts != null && lastPlayerIdx < handCounts.length
                    && handCounts[lastPlayerIdx] > 0 && handCounts[lastPlayerIdx] <= 2;
            if (!teammateLow) {
                return new ArrayList<>(); // 过牌让队友继续走
            }
        }

        // 对手（地主视角的任一农民 / 农民视角的地主）报牌 ≤2 张时，
        // 禁用过牌与保炸弹逻辑：能压必压，否则对手下一手出完直接获胜
        boolean mustPress = false;
        if (handCounts != null && myIdx >= 0) {
            for (int i = 0; i < handCounts.length && i < 3; i++) {
                if (i == myIdx) continue;
                boolean teammate = !iAmLandlord && landlordIdx >= 0 && i != landlordIdx;
                if (!teammate && handCounts[i] > 0 && handCounts[i] <= 2) {
                    mustPress = true;
                    break;
                }
            }
        }

        // ★ Bug修复：原版 findMinimalBeat 内部找不到压牌时回退到 findAnyBomb(炸),
        //   即使"用炸弹压对方一张小牌"明显不划算,联机模式下 HOST 还会再被拒收
        //   导致 AI 丢回合。先看压牌结果是否真的是 bomb,如果是且手牌较多,
        //   走"过牌"分支避免无谓消耗。result==null 仍走主动出牌兜底(对手刚出炸等场景)。
        List<Card> result = findMinimalBeat(hand, lastCards);

        // 如果手牌很少，更积极地出牌
        if (hand.size() <= 3) {
            return result != null ? result : new ArrayList<>();
        }

        // 30%概率选择过牌（如果不是最后几张牌；对手报牌时禁用）
        if (!mustPress && result != null && hand.size() > 5 && random.nextDouble() < 0.3) {
            return new ArrayList<>(); // 过牌
        }

        // 若 result 是炸弹且压的是普通牌型,手牌仍较多时优先过牌(不无谓炸)
        if (!mustPress && result != null && isBomb(result) && hand.size() > 5) {
            return new ArrayList<>(); // 过牌
        }

        return result != null ? result : new ArrayList<>();
    }

    /** 判断给定手牌组合是否为炸弹(4 张同 rank 或 王炸) */
    private boolean isBomb(List<Card> cards) {
        if (cards == null || cards.isEmpty()) return false;
        if (cards.size() == 2) {
            boolean hasJoker = false, hasBig = false;
            for (Card c : cards) {
                if (c.getValue() == 16) hasJoker = true;
                if (c.getValue() == 17) hasBig = true;
            }
            return hasJoker && hasBig;
        }
        if (cards.size() == 4) {
            int v = cards.get(0).getValue();
            for (Card c : cards) if (c.getValue() != v) return false;
            return true;
        }
        return false;
    }

    private List<Card> chooseActivePlay(List<Card> hand) {
        // ★ Bug修复：原版无空手防御,game.getPlayerHand 返回空时 hand.get(0) 抛
        //   IOOB 中断 tick 致 game 卡住
        if (hand == null || hand.isEmpty()) return new ArrayList<>();
        // groupByValue 返回 TreeMap（升序）：领出/跟牌都从最小的组开始
        Map<Integer, List<Card>> groups = groupByValue(hand);
        // 创建副本排序，避免修改原始手牌顺序
        hand = new ArrayList<>(hand);
        Collections.sort(hand);

        // 无拆牌风险的顺子领出（仅由散牌组成），避免永不领出顺子
        List<Card> safeStraight = findSafeStraightLead(groups);
        if (safeStraight != null) return safeStraight;

        // 优先出三张：先找精确三张组，实在没有才允许拆四张组（炸弹）
        Integer tripleValue = pickGroup(groups, 3, false);
        if (tripleValue == null) tripleValue = pickGroup(groups, 3, true);
        if (tripleValue != null) {
            // 尝试三带一：优先散牌当翅（不从对子/炸弹里抽）
            Integer singleValue = pickGroupExact(groups, 1, tripleValue);
            if (singleValue != null) {
                List<Card> result = new ArrayList<>(groups.get(tripleValue).subList(0, 3));
                result.add(groups.get(singleValue).get(0));
                return result;
            }
            // 尝试三带二：优先精确对子
            Integer pairValue = pickGroupExact(groups, 2, tripleValue);
            if (pairValue != null) {
                List<Card> result = new ArrayList<>(groups.get(tripleValue).subList(0, 3));
                result.addAll(groups.get(pairValue).subList(0, 2));
                return result;
            }
            // 没有合适的带牌，出纯三张
            return new ArrayList<>(groups.get(tripleValue).subList(0, 3));
        }

        // 出对子：优先精确对子（不拆三张/炸弹）
        Integer pairValue = pickGroupExact(groups, 2, -1);
        if (pairValue != null) {
            return new ArrayList<>(groups.get(pairValue).subList(0, 2));
        }

        // 最后出单牌
        return Arrays.asList(hand.get(0));
    }

    /** 找最小的组大小 ≥ size 的值（允许拆更大的组）；找不到返回 null。 */
    private Integer pickGroup(Map<Integer, List<Card>> groups, int size, boolean allowLarger) {
        for (Map.Entry<Integer, List<Card>> e : groups.entrySet()) {
            int sz = e.getValue().size();
            if (sz == size || (allowLarger && sz > size)) return e.getKey();
        }
        return null;
    }

    /** 找最小的组大小恰为 size 的值（绝不拆牌），排除 exclude 值；找不到返回 null。 */
    private Integer pickGroupExact(Map<Integer, List<Card>> groups, int size, int exclude) {
        for (Map.Entry<Integer, List<Card>> e : groups.entrySet()) {
            if (e.getKey() == exclude) continue;
            if (e.getValue().size() == size) return e.getKey();
        }
        return null;
    }

    /** 仅由散牌（该值只剩一张且在 3-A 范围）组成的 5 连顺子领出；找不到返回 null。 */
    private List<Card> findSafeStraightLead(Map<Integer, List<Card>> groups) {
        List<Integer> singles = new ArrayList<>();
        for (Map.Entry<Integer, List<Card>> e : groups.entrySet()) {
            if (e.getValue().size() == 1 && e.getKey() >= 3 && e.getKey() <= 14) singles.add(e.getKey());
        }
        for (int i = 0; i + 5 <= singles.size(); i++) {
            boolean consecutive = true;
            for (int j = 1; j < 5; j++) {
                if (singles.get(i + j) != singles.get(i) + j) { consecutive = false; break; }
            }
            if (consecutive) {
                List<Card> result = new ArrayList<>();
                for (int j = 0; j < 5; j++) result.add(groups.get(singles.get(i + j)).get(0));
                return result;
            }
        }
        return null;
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
            case TRIPLE_STRAIGHT_WITH_SINGLE ->
                    findMinimalTripleStraightWithWings(hand, targetPattern.getValue(), targetPattern.getLength(), false);
            case TRIPLE_STRAIGHT_WITH_PAIR ->
                    findMinimalTripleStraightWithWings(hand, targetPattern.getValue(), targetPattern.getLength(), true);
            case FOUR_WITH_TWO_SINGLES -> findAnyBomb(hand);
            case FOUR_WITH_TWO_PAIRS -> findAnyBomb(hand);
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
                // 找一张单牌当翅膀：★ Bug修复：原版按 size>=1 取最小值组，会把对子
                //   甚至炸弹（4张组）拆掉。改为先取精确散牌(size==1)，没有才从
                //   非炸弹的更大组里拆（见 pickWingGroup），永不碰炸弹
                Integer singleValue = pickWingGroup(groups, tripleValue, 1);
                if (singleValue != null) {
                    List<Card> result = new ArrayList<>();
                    result.addAll(groups.get(tripleValue).subList(0, 3));
                    result.add(groups.get(singleValue).get(0));
                    return result;
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
                // 找一对当翅膀：★ Bug修复：原版按 size>=2 取最小值组，会把三张
                //   甚至炸弹（4张组）拆掉。改为优先精确对子(size==2)，没有才从
                //   非炸弹的更大组里拆（见 pickWingGroup），永不碰炸弹
                Integer pairValue = pickWingGroup(groups, tripleValue, 2);
                if (pairValue != null) {
                    List<Card> result = new ArrayList<>();
                    result.addAll(groups.get(tripleValue).subList(0, 3));
                    result.addAll(groups.get(pairValue).subList(0, 2));
                    return result;
                }
            }
        }
        return findAnyBomb(hand);
    }

    /**
     * 为三带一/三带二挑翅膀组（TreeMap 升序迭代，取最小值者）：
     * 优先张数恰好匹配的组；找不到才从更大的组拆，但绝不碰 size==4 的炸弹，
     * 避免为凑翅膀拆掉炸弹（旧逻辑 size>=n 的判断会命中 4 张组）。
     *
     * @param exactSize 翅膀精确张数（单牌=1，对子=2）
     * @param exclude   三张主牌的值，不能从自身拆
     * @return 翅膀组的值；没有合法组返回 null
     */
    private Integer pickWingGroup(Map<Integer, List<Card>> groups, int exclude, int exactSize) {
        // 1) 精确张数的组（最小值优先）
        Integer exact = pickGroupExact(groups, exactSize, exclude);
        if (exact != null) return exact;
        // 2) 从更大的组拆（跳过炸弹与主牌本身）
        for (Map.Entry<Integer, List<Card>> e : groups.entrySet()) {
            int sz = e.getValue().size();
            if (e.getKey() != exclude && sz > exactSize && sz != 4) return e.getKey();
        }
        return null;
    }

    private List<Card> findMinimalStraight(List<Card> hand, int targetValue, int length) {
        Map<Integer, List<Card>> groups = groupByValue(hand);
        List<Integer> values = new ArrayList<>();
        for (int v : groups.keySet())
            if (v >= 3 && v <= 14) values.add(v);
        Collections.sort(values);
        for (int i = 0; i <= values.size() - length; i++) {
            boolean consecutive = true;
            for (int j = 1; j < length; j++) {
                if (values.get(i + j) != values.get(i) + j) { consecutive = false; break; }
            }
            if (consecutive && values.get(i) > targetValue) { // 等值压不住（canBeat 严格大于）
                List<Card> result = new ArrayList<>();
                for (int j = 0; j < length; j++)
                    result.add(groups.get(values.get(i + j)).get(0));
                return result;
            }
        }
        return findAnyBomb(hand);
    }

    private List<Card> findMinimalPairStraight(List<Card> hand, int targetValue, int length) {
        Map<Integer, List<Card>> groups = groupByValue(hand);
        List<Integer> pairValues = new ArrayList<>();
        for (int v : groups.keySet())
            if (v >= 3 && v <= 14 && groups.get(v).size() >= 2) pairValues.add(v);
        Collections.sort(pairValues);
        for (int i = 0; i <= pairValues.size() - length; i++) {
            boolean consecutive = true;
            for (int j = 1; j < length; j++) {
                if (pairValues.get(i + j) != pairValues.get(i) + j) { consecutive = false; break; }
            }
            if (consecutive && pairValues.get(i) > targetValue) { // 等值压不住
                List<Card> result = new ArrayList<>();
                for (int j = 0; j < length; j++)
                    result.addAll(groups.get(pairValues.get(i + j)).subList(0, 2));
                return result;
            }
        }
        return findAnyBomb(hand);
    }

    private List<Card> findMinimalTripleStraight(List<Card> hand, int targetValue, int length) {
        Map<Integer, List<Card>> groups = groupByValue(hand);
        List<Integer> tripleValues = new ArrayList<>();
        for (int v : groups.keySet())
            if (v >= 3 && v <= 14 && groups.get(v).size() >= 3) tripleValues.add(v);
        Collections.sort(tripleValues);
        for (int i = 0; i <= tripleValues.size() - length; i++) {
            boolean consecutive = true;
            for (int j = 1; j < length; j++) {
                if (tripleValues.get(i + j) != tripleValues.get(i) + j) { consecutive = false; break; }
            }
            if (consecutive && tripleValues.get(i) > targetValue) { // 等值压不住
                List<Card> result = new ArrayList<>();
                for (int j = 0; j < length; j++)
                    result.addAll(groups.get(tripleValues.get(i + j)).subList(0, 3));
                return result;
            }
        }
        return findAnyBomb(hand);
    }

    /** 找同长度且主值更大的飞机，并按牌型严格凑齐翅牌；不拆四张炸弹。 */
    private List<Card> findMinimalTripleStraightWithWings(List<Card> hand, int targetValue,
                                                           int length, boolean pairs) {
        Map<Integer, List<Card>> groups = groupByValue(hand);
        List<Integer> tripleValues = new ArrayList<>();
        for (int value : groups.keySet()) {
            // 新牌型不能以拆炸弹的三张作为主体。
            if (value >= 3 && value <= 14 && groups.get(value).size() == 3) tripleValues.add(value);
        }
        for (int i = 0; i <= tripleValues.size() - length; i++) {
            boolean consecutive = true;
            for (int j = 1; j < length; j++) {
                if (tripleValues.get(i + j) != tripleValues.get(i) + j) {
                    consecutive = false;
                    break;
                }
            }
            if (!consecutive || tripleValues.get(i) <= targetValue) continue;

            Set<Integer> body = new HashSet<>(tripleValues.subList(i, i + length));
            List<Card> result = new ArrayList<>();
            for (int value : body) result.addAll(groups.get(value));
            if (pairs) {
                int added = 0;
                for (Map.Entry<Integer, List<Card>> entry : groups.entrySet()) {
                    if (!body.contains(entry.getKey()) && entry.getValue().size() == 2) {
                        result.addAll(entry.getValue());
                        if (++added == length) return result;
                    }
                }
            } else {
                int added = 0;
                for (Map.Entry<Integer, List<Card>> entry : groups.entrySet()) {
                    if (body.contains(entry.getKey())) continue;
                    // 四张组是炸弹不能拆；单/对/三张组可拆作单翅。
                    if (entry.getValue().size() <= 3) {
                        int take = Math.min(entry.getValue().size(), Math.min(2, length - added));
                        for (int index = 0; index < take; index++) {
                            result.add(entry.getValue().get(index));
                            if (++added == length) return result;
                        }
                    }
                }
            }
        }
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

    /** TreeMap 升序分组：所有 findMinimal 与 chooseActivePlay 依赖迭代序取"最小可压"，HashMap 序会打出大牌浪费 */
    private Map<Integer, List<Card>> groupByValue(List<Card> hand) {
        Map<Integer, List<Card>> groups = new TreeMap<>();
        for (Card card : hand) {
            groups.computeIfAbsent(card.getValue(), k -> new ArrayList<>()).add(card);
        }
        return groups;
    }
}
