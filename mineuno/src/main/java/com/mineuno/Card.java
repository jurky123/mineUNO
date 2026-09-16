package com.mineuno;

import io.papermc.paper.datacomponent.DataComponentTypes;
import java.util.List;
import net.kyori.adventure.key.Key;
import net.kyori.adventure.text.format.TextDecoration;
import org.bukkit.Material;
import org.bukkit.inventory.ItemStack;

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

    public static boolean models() {
        return MineUnoPlugin.instance().cfg("visual.use-item-models", true);
    }

    public ItemStack item() {
        return item(false);
    }

    public ItemStack item(boolean glow) {
        boolean models = models();
        Material material = models ? Material.PAPER : switch (color) {
            case RED -> Material.RED_DYE;
            case YELLOW -> Material.YELLOW_DYE;
            case GREEN -> Material.LIME_DYE;
            case BLUE -> Material.BLUE_DYE;
            case WILD -> type == Type.WILD4 ? Material.FIRE_CHARGE : Material.NETHER_STAR;
        };
        ItemStack item = ItemStack.of(material);
        if (models) item.setData(DataComponentTypes.ITEM_MODEL, Key.key("mineuno", "card/" + model()));
        item.editMeta(meta -> {
            meta.displayName(MineUnoPlugin.mm("<" + color.tag + ">" + name()).decoration(TextDecoration.ITALIC, false));
            if (glow) meta.setEnchantmentGlintOverride(true);
        });
        return item;
    }

    public static ItemStack back() {
        boolean models = models();
        ItemStack item = ItemStack.of(models ? Material.PAPER : Material.GRAY_DYE);
        if (models) item.setData(DataComponentTypes.ITEM_MODEL, Key.key("mineuno", "card/back"));
        item.editMeta(meta -> meta.displayName(MineUnoPlugin.mm("<dark_gray>UNO 牌背")));
        return item;
    }

    public static ItemStack icon(String model, String name) {
        boolean models = models();
        ItemStack item = ItemStack.of(models ? Material.PAPER : fallbackIcon(model));
        if (models) item.setData(DataComponentTypes.ITEM_MODEL, Key.key("mineuno", model));
        item.editMeta(meta -> meta.displayName(MineUnoPlugin.mm(name).decoration(TextDecoration.ITALIC, false)));
        return item;
    }

    private static Material fallbackIcon(String model) {
        if (model.startsWith("color/")) return switch (model.substring(6)) {
            case "red" -> Material.RED_CONCRETE;
            case "yellow" -> Material.YELLOW_CONCRETE;
            case "green" -> Material.LIME_CONCRETE;
            case "blue" -> Material.BLUE_CONCRETE;
            default -> Material.MAGENTA_CONCRETE;
        };
        if (model.startsWith("dir/")) return Material.ARROW;
        return Material.WHITE_CARPET;
    }
}
