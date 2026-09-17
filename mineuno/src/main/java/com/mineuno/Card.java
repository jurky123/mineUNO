package com.mineuno;

import java.util.Comparator;
import java.util.List;

/** 纯规则数据：不依赖 Bukkit，物品渲染见 CardItems。 */
public record Card(int id, Color color, Type type, int number) {

    public enum Color {
        RED("红", "red"), YELLOW("黄", "yellow"), GREEN("绿", "green"), BLUE("蓝", "blue"),
        WILD("彩", "light_purple");

        public final String cn;
        public final String tag;

        Color(String cn, String tag) {
            this.cn = cn;
            this.tag = tag;
        }
    }

    public enum Type {
        NUMBER(""), SKIP("跳过"), REVERSE("反转"), DRAW2("+2"), WILD("变色"), WILD4("+4 变色");

        public final String cn;

        Type(String cn) {
            this.cn = cn;
        }
    }

    public static final List<Color> COLORS = List.of(Color.RED, Color.YELLOW, Color.GREEN, Color.BLUE);

    /** 手牌统一排序：先颜色，再数字/功能。 */
    public static final Comparator<Card> SORT = Comparator
            .comparingInt((Card c) -> c.color().ordinal())
            .thenComparingInt(c -> c.type() == Type.NUMBER ? c.number() : 100);

    public boolean wild() {
        return type == Type.WILD || type == Type.WILD4;
    }

    public String model() {
        if (type == Type.WILD) return "wild";
        if (type == Type.WILD4) return "wild4";
        return color.name().toLowerCase() + "_" + (type == Type.NUMBER ? number : type.name().toLowerCase());
    }

    public String coloredName() {
        return "<" + color.tag + ">" + name();
    }

    public String name() {
        return (color == Color.WILD ? "" : color.cn + "色 ") + (type == Type.NUMBER ? String.valueOf(number) : type.cn);
    }

    public int points() {
        if (wild()) return 50;
        return type == Type.NUMBER ? number : 20;
    }
}
