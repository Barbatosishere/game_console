package com.wzz.game_console.client.screens.games;

import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.function.IntPredicate;

/** Tracks physical keys separately from actions, including aliases and tick-based repeats. */
public final class GameInput {
    public enum Action { UP, DOWN, LEFT, RIGHT, PRIMARY, SECONDARY, RESTART, EXIT }
    private final Map<Integer, Action> bindings;
    private final Set<Integer> pressed = new HashSet<>();
    private final Set<Integer> blocked = new HashSet<>();
    private final int[] held = new int[Action.values().length];
    private final int[] ticks = new int[Action.values().length];

    public GameInput(Map<Integer, Action> bindings) { this.bindings = Map.copyOf(bindings); }

    /** Returns the action only on its first press; OS repeat and aliases do not retrigger it. */
    public Action press(int key) {
        Action action = bindings.get(key);
        if (action == null || blocked.contains(key) || !pressed.add(key)) return null;
        int index = action.ordinal();
        if (held[index]++ != 0) return null;
        ticks[index] = 0;
        return action;
    }

    public boolean release(int key) {
        blocked.remove(key);
        Action action = bindings.get(key);
        if (action == null) return false;
        if (pressed.remove(key) && --held[action.ordinal()] == 0) ticks[action.ordinal()] = 0;
        return true;
    }

    public void tick() {
        for (int i = 0; i < held.length; i++) if (held[i] > 0 && ticks[i] < Integer.MAX_VALUE) ticks[i]++;
    }

    public boolean isHeld(Action action) { return held[action.ordinal()] > 0; }
    public boolean repeats(Action action, int delay, int interval) {
        if (delay < 1 || interval < 1) throw new IllegalArgumentException("Repeat timing must be positive");
        int index = action.ordinal();
        return held[index] > 0 && ticks[index] >= delay && (ticks[index] - delay) % interval == 0;
    }

    /** Keys held across a reset must be released before they can trigger a new action. */
    public void clear() {
        blocked.addAll(pressed);
        pressed.clear();
        java.util.Arrays.fill(held, 0);
        java.util.Arrays.fill(ticks, 0);
    }

    /** Recover releases that occurred while the window did not receive keyboard events. */
    public void clearReleasedBlocks(IntPredicate physicallyDown) {
        blocked.removeIf(key -> !physicallyDown.test(key));
    }
}
