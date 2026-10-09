package com.wzz.game_console.util;

import java.util.ArrayList;
import java.util.List;

/** Wraps menu controls into centered rows; rendering and hit testing share these bounds. */
public final class GameMenuLayout {
    public record Button(int index, int x, int y, int width, int height) {
        public boolean contains(double mouseX, double mouseY) {
            return mouseX >= x && mouseX < x + width && mouseY >= y && mouseY < y + height;
        }
    }
    private GameMenuLayout() {}
    public static List<Button> wrap(int screenWidth, int top, int height, int gap, int[] widths) {
        if (height < 1 || gap < 0) throw new IllegalArgumentException("Invalid control spacing");
        int available = Math.max(1, screenWidth - 20);
        List<Button> result = new ArrayList<>();
        int first = 0, y = top;
        while (first < widths.length) {
            int end = first, rowWidth = 0;
            while (end < widths.length) {
                int buttonWidth = Math.max(1, Math.min(available, widths[end]));
                int candidate = rowWidth + (end == first ? 0 : gap) + buttonWidth;
                if (end > first && candidate > available) break;
                rowWidth = candidate;
                end++;
            }
            int x = Math.max(0, (screenWidth - rowWidth) / 2);
            for (int index = first; index < end; index++) {
                int buttonWidth = Math.max(1, Math.min(available, widths[index]));
                result.add(new Button(index, x, y, buttonWidth, height));
                x += buttonWidth + gap;
            }
            first = end;
            y += height + gap;
        }
        return List.copyOf(result);
    }
}
