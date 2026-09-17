package com.mineuno;

import java.util.ArrayList;
import java.util.List;

/** 手牌排布几何（纯函数）：围绕玩家的圆弧；张数超过每排上限时自动分上下两排。 */
public final class HandLayout {

    /** row: 0=下排，1=上排；indexInRow/rowSize 用来算排内角度。 */
    public record Slot(int row, int indexInRow, int rowSize) {}

    private HandLayout() {
    }

    public static List<Slot> layout(int count, int rowsMax) {
        List<Slot> slots = new ArrayList<>(Math.max(0, count));
        if (count <= 0) return slots;
        int rows = count <= rowsMax ? 1 : 2;
        int per = (count + rows - 1) / rows;
        for (int i = 0; i < count; i++) {
            int row = i / per;
            int index = i % per;
            slots.add(new Slot(row, index, Math.min(per, count - row * per)));
        }
        return slots;
    }

    /** 排内第 index 张相对扇形中心的角度（度）。 */
    public static double angle(int indexInRow, int rowSize, double step) {
        return (indexInRow - (rowSize - 1) / 2.0) * step;
    }
}
