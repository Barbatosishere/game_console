package com.wzz.game_console.client.screens.games.landlord;

public class CardPattern {
    public enum Type {
        SINGLE,           // 单牌
        PAIR,             // 对子
        TRIPLE,           // 三张
        TRIPLE_WITH_ONE,  // 三带一
        TRIPLE_WITH_PAIR, // 三带二
        STRAIGHT,         // 顺子
        PAIR_STRAIGHT,    // 连对
        TRIPLE_STRAIGHT,  // 飞机
        BOMB,             // 炸弹
        JOKER_BOMB        // 王炸
    }

    private Type type;
    private int value;      // 主牌的值
    private int length;     // 长度（用于顺子、连对等）

    public CardPattern(Type type, int value, int length) {
        this.type = type;
        this.value = value;
        this.length = length;
    }

    public boolean canBeat(CardPattern other) {
        // 王炸最大
        if (type == Type.JOKER_BOMB) {
            return other.type != Type.JOKER_BOMB;
        }
        
        if (other.type == Type.JOKER_BOMB) {
            return false;
        }
        
        // 炸弹可以打任何非炸弹牌型
        if (type == Type.BOMB && other.type != Type.BOMB) {
            return true;
        }
        
        // 非炸弹不能打炸弹
        if (type != Type.BOMB && other.type == Type.BOMB) {
            return false;
        }
        
        // 同类型比较
        if (type == other.type && length == other.length) {
            return value > other.value;
        }
        
        // 不同类型或长度不同，不能打
        return false;
    }

    public Type getType() { return type; }
    public int getValue() { return value; }
    public int getLength() { return length; }
    
    @Override
    public String toString() {
        return type + " value=" + value + " length=" + length;
    }
}