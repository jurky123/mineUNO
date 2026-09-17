package com.mineuno;

import java.util.Random;

/** 弃牌堆散布（纯函数）：位置/角度由牌 id 确定性生成，重复渲染不会抖动。 */
public final class PileLayout {

    public record Spot(double dx, double dz, float yaw) {}

    private PileLayout() {
    }

    public static Spot spot(Card card, double spread, double angle) {
        Random random = new Random(card.id() * 2654435761L);
        return new Spot(
                (random.nextDouble() * 2 - 1) * spread,
                (random.nextDouble() * 2 - 1) * spread,
                (float) ((random.nextDouble() * 2 - 1) * angle));
    }
}
