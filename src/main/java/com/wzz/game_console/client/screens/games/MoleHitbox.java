package com.wzz.game_console.client.screens.games;

final class MoleHitbox {
    private MoleHitbox() {}

    static boolean containsVisiblePart(int holeX, int holeY, int holeSize, int moleSize,
                                       float moleOffset, boolean hasMole,
                                       double mouseX, double mouseY) {
        if (!hasMole) return false;
        int moleX = holeX + (holeSize - moleSize) / 2;
        int moleY = (int) (holeY + holeSize - moleSize + moleOffset);
        int visibleTop = Math.max(holeY, moleY);
        int visibleBottom = Math.min(holeY + holeSize, moleY + moleSize);
        return visibleTop < visibleBottom
                && mouseX >= moleX && mouseX < moleX + moleSize
                && mouseY >= visibleTop && mouseY < visibleBottom;
    }
}
