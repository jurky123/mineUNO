package com.mineuno;

import io.papermc.paper.datacomponent.DataComponentTypes;
import net.kyori.adventure.key.Key;
import net.kyori.adventure.text.format.TextDecoration;
import org.bukkit.Material;
import org.bukkit.inventory.ItemStack;

/** 卡牌物品渲染：把 Bukkit/资源包相关代码从规则数据里分离出来。 */
public final class CardItems {

    private CardItems() {
    }

    public static boolean models() {
        return MineUnoPlugin.instance().cfg("visual.use-item-models", true);
    }

    public static ItemStack item(Card card, boolean glow) {
        boolean models = models();
        Material material = models ? Material.PAPER : switch (card.color()) {
            case RED -> Material.RED_DYE;
            case YELLOW -> Material.YELLOW_DYE;
            case GREEN -> Material.LIME_DYE;
            case BLUE -> Material.BLUE_DYE;
            case WILD -> card.type() == Card.Type.WILD4 ? Material.FIRE_CHARGE : Material.NETHER_STAR;
        };
        ItemStack item = ItemStack.of(material);
        if (models) item.setData(DataComponentTypes.ITEM_MODEL, Key.key("mineuno", "card/" + card.model()));
        item.editMeta(meta -> {
            meta.displayName(MineUnoPlugin.mm(card.coloredName()).decoration(TextDecoration.ITALIC, false));
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
        if (model.startsWith("dir/") || model.startsWith("page/")) return Material.ARROW;
        return Material.WHITE_CARPET;
    }
}
