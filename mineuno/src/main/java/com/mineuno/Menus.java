package com.mineuno;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import net.kyori.adventure.text.format.TextDecoration;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.inventory.meta.SkullMeta;

/** 全部 GUI：大厅、准备、手牌、选色、质疑。 */
public class Menus {

    public record Holder(String type, UUID game, List<UUID> rooms) implements InventoryHolder {
        public Holder(String type, UUID game) {
            this(type, game, List.of());
        }

        @Override
        public Inventory getInventory() {
            return null;
        }
    }

    private final MineUnoPlugin plugin;

    public Menus(MineUnoPlugin plugin) {
        this.plugin = plugin;
    }

    private Inventory create(String type, UUID game, int rows, String title) {
        return Bukkit.createInventory(new Holder(type, game), rows * 9, MineUnoPlugin.mm(title));
    }

    /** 不在 InventoryClickEvent 里同步换窗口，统一延后一 tick。 */
    private void later(Player player, Runnable action) {
        Bukkit.getScheduler().runTask(plugin, () -> {
            if (player.isOnline()) action.run();
        });
    }

    private static ItemStack item(Material material, String name, List<String> lore) {
        ItemStack stack = ItemStack.of(material);
        stack.editMeta(meta -> {
            meta.displayName(MineUnoPlugin.mm(name).decoration(TextDecoration.ITALIC, false));
            meta.lore(lore.stream().map(MineUnoPlugin::mm).toList());
        });
        return stack;
    }

    // ---------- 规则与教程 ----------

    private record Rule(Material material, String name, List<String> lore) {}

    private final Map<UUID, Integer> rulePages = new HashMap<>();

    private static final List<List<Rule>> RULES = rulePages();

    private static List<List<Rule>> rulePages() {
        return List.of(
                List.of(
                        new Rule(Material.NETHER_STAR, "<gold><bold>获胜条件", List.of(
                                "<gray>第一个出完手牌的人获胜",
                                "<gray>Quick：一局定胜负（默认）",
                                "<gray>Classic 500：累计 500 分")),
                        new Rule(Material.PLAYER_HEAD, "<white><bold>人数", List.of(
                                "<gray>2 ~ 8 人同桌",
                                "<gray>服务器可同时开多桌")),
                        new Rule(Material.PAPER, "<white><bold>开局", List.of(
                                "<gray>每人 7 张手牌",
                                "<gray>翻一张牌作为起始弃牌",
                                "<gray>随机一名玩家先手")),
                        new Rule(Material.BOOK, "<white><bold>出牌条件", List.of(
                                "<gray>颜色相同，或",
                                "<gray>数字相同，或",
                                "<gray>功能相同（跳过/反转/+2）",
                                "<gray>万能牌任何时候都能出")),
                        new Rule(Material.LIME_DYE, "<white><bold>摸牌规则", List.of(
                                "<gray>一个回合只摸 1 张",
                                "<gray>摸完只能出「刚摸到的那张」或结束回合",
                                "<gray>手里有可出的牌，也允许主动摸牌")),
                        new Rule(Material.CLOCK, "<white><bold>回合计时", List.of(
                                "<gray>默认 30 秒",
                                "<gray>超时自动摸 1 张并结束回合",
                                "<gray>选色超时自动选手中最多的颜色")),
                        new Rule(Material.ARROW, "<white><bold>方向", List.of(
                                "<gray>默认顺时针，反转牌切换方向"))),
                List.of(
                        new Rule(Material.BARRIER, "<red><bold>跳过 Skip", List.of(
                                "<gray>下一位玩家失去本轮出牌机会")),
                        new Rule(Material.ARROW, "<aqua><bold>反转 Reverse", List.of(
                                "<gray>出牌方向反转",
                                "<gray>两人局时等于跳过，自己再出一张")),
                        new Rule(Material.KNOWLEDGE_BOOK, "<yellow><bold>+2 Draw Two", List.of(
                                "<gray>下一位玩家摸 2 张并跳过回合",
                                "<gray>默认不允许叠牌")),
                        new Rule(Material.NETHER_STAR, "<light_purple><bold>变色 Wild", List.of(
                                "<gray>任何时候都可以打出",
                                "<gray>出牌后选择红/黄/绿/蓝作为新颜色")),
                        new Rule(Material.FIRE_CHARGE, "<light_purple><bold>+4 Wild Draw Four", List.of(
                                "<gray>出牌后选颜色",
                                "<gray>下家摸 4 张并跳过",
                                "<gray>下家可以选择质疑（见第 4 页）"))),
                List.of(
                        new Rule(Material.GOLD_INGOT, "<gold><bold>提前喊 UNO", List.of(
                                "<gray>手牌剩 2 张时输入 <white>/uno uno",
                                "<gray>或在控制面板点 <yellow>UNO!")),
                        new Rule(Material.EMERALD, "<green><bold>安全状态", List.of(
                                "<gray>提前喊过之后打出一张，剩 1 张即安全")),
                        new Rule(Material.REDSTONE, "<red><bold>忘记喊了？", List.of(
                                "<gray>剩 1 张没喊会被其他玩家抓",
                                "<gray>聊天栏点 <gold>[抓 UNO] <gray>或 <white>/uno catch <玩家>",
                                "<gray>被罚摸 2 张")),
                        new Rule(Material.CLOCK, "<white><bold>抓人窗口", List.of(
                                "<gray>直到下一位玩家行动（出牌/摸牌）之前",
                                "<gray>都可以抓人或自己补喊",
                                "<gray>谁先触发谁生效")),
                        new Rule(Material.LIME_DYE, "<green><bold>自我补救", List.of(
                                "<gray>剩 1 张时自己输入 <white>/uno uno <gray>可补喊"))),
                List.of(
                        new Rule(Material.BOOK, "<white><bold>合法条件", List.of(
                                "<gray>手里没有「当前颜色」的牌时，+4 才合法",
                                "<gray>注意：判定看的是颜色，不是数字")),
                        new Rule(Material.CLOCK, "<white><bold>质疑窗口", List.of(
                                "<gray>下家有 8 秒选择：接受 +4 或质疑",
                                "<gray>超时自动接受")),
                        new Rule(Material.LIME_CONCRETE, "<green><bold>接受 +4", List.of(
                                "<gray>摸 4 张牌并跳过回合")),
                        new Rule(Material.RED_CONCRETE, "<red><bold>质疑成功", List.of(
                                "<gray>对方违规：他摸 4 张",
                                "<gray>你保留正常回合")),
                        new Rule(Material.GRAY_CONCRETE, "<gray><bold>质疑失败", List.of(
                                "<gray>对方合法：你摸 6 张并跳过回合",
                                "<gray>判定基于出牌前的手牌，不可反悔"))),
                List.of(
                        new Rule(Material.LIME_CONCRETE, "<green><bold>Quick", List.of(
                                "<gray>第一个出完手牌即获胜",
                                "<gray>适合开一局就走")),
                        new Rule(Material.RED_CONCRETE, "<red><bold>Classic 500", List.of(
                                "<gray>数字牌 = 面值",
                                "<gray>跳过 / 反转 / +2 = 20 分",
                                "<gray>万能牌 = 50 分",
                                "<gray>赢家拿走其他人手牌的总分，先到 500 获胜",
                                "<gray>一局结束后 10 秒自动开下一局")),
                        new Rule(Material.BOOK, "<white><bold>断线与超时", List.of(
                                "<gray>断线保留座位与手牌 60 秒",
                                "<gray>超时视为弃权，手牌洗回牌堆"))),
                List.of(
                        new Rule(Material.BOOK, "<gold><bold>大厅", List.of(
                                "<gray>输入 <white>/uno <gray>打开大厅",
                                "<gray>创建 Quick / Classic 房间，或加入已有房间")),
                        new Rule(Material.EMERALD_BLOCK, "<gold><bold>准备与开始", List.of(
                                "<gray>房间界面点「准备」",
                                "<gray>房主点「开始游戏」（至少 2 人且全部准备）")),
                        new Rule(Material.ARMOR_STAND, "<gold><bold>3D 手牌", List.of(
                                "<gray>手牌是悬浮在你面前的实体牌，只有你能看见",
                                "<gray>把准星移到某张牌上：它会往外移并发光",
                                "<gray>左键打出选中的牌",
                                "<gray>超过 16 张时用左右两侧的箭头翻页")),
                        new Rule(Material.PAPER, "<gold><bold>摸牌", List.of(
                                "<gray>右键任意位置，或对准牌堆右键",
                                "<gray>摸到的牌会加入你的手牌")),
                        new Rule(Material.BARRIER, "<gold><bold>结束回合", List.of(
                                "<gray>摸牌后：<white>潜行 + 左键 <gray>结束回合",
                                "<gray>或在控制面板点「结束回合」")),
                        new Rule(Material.CHEST, "<gold><bold>控制面板", List.of(
                                "<gray>右键弃牌区（桌面右侧）打开",
                                "<gray>面板：摸牌 / 结束回合 / UNO! / 规则 / 离开牌局")),
                        new Rule(Material.COMMAND_BLOCK, "<gold><bold>常用命令", List.of(
                                "<gray>/uno uno 喊 UNO · /uno leave 离开牌局",
                                "<gray>/uno cards 列出手牌 · /uno play <序号> 指定出牌",
                                "<gray>/uno setarena [编号] 设置牌桌（OP）· /uno list 房间列表"))));
    }

    public void openRules(Player player, int page) {
        int pages = RULES.size();
        page = Math.max(0, Math.min(pages - 1, page));
        rulePages.put(player.getUniqueId(), page);
        Inventory inv = create("rules", null, 6, "<gold><bold>MineUNO</bold> <gray>规则与教程");
        inv.setItem(4, item(Material.BOOK, "<yellow><bold>第 " + (page + 1) + " / " + pages + " 页", List.of(
                "<gray>点击下方箭头翻页")));
        List<Rule> rules = RULES.get(page);
        int[] slots = {19, 20, 21, 22, 23, 24, 25};
        for (int i = 0; i < rules.size() && i < slots.length; i++) {
            Rule rule = rules.get(i);
            inv.setItem(slots[i], item(rule.material(), rule.name(), rule.lore()));
        }
        inv.setItem(45, item(Material.ARROW, "<white>上一页", List.of()));
        inv.setItem(49, item(Material.BARRIER, "<red>关闭", List.of()));
        inv.setItem(53, item(Material.ARROW, "<white>下一页", List.of()));
        later(player, () -> player.openInventory(inv));
    }

    // ---------- 大厅 ----------

    public void openMain(Player player) {
        List<Game> rooms = new ArrayList<>(plugin.matches().games());
        Inventory inv = Bukkit.createInventory(
                new Holder("main", null, rooms.stream().map(room -> room.id).toList()),
                27, MineUnoPlugin.mm("<gold><bold>MineUNO</bold> <gray>大厅"));
        inv.setItem(4, item(Material.BOOK, "<yellow><bold>规则与教程",
                List.of("<gray>玩法 / 功能牌 / UNO / 质疑 / 操作")));
        inv.setItem(11, item(Material.LIME_CONCRETE, "<green><bold>创建房间 · Quick",
                List.of("<gray>第一个出完手牌的人获胜", "<yellow>点击创建")));
        inv.setItem(13, item(Material.RED_CONCRETE, "<red><bold>创建房间 · Classic 500",
                List.of("<gray>官方计分，先到 500 分者获胜", "<yellow>点击创建")));
        inv.setItem(15, item(Material.CLOCK, "<white>刷新列表", List.of("<gray>重新载入房间列表")));
        int slot = 18;
        for (Game game : rooms) {
            if (slot > 26) break;
            inv.setItem(slot++, gameIcon(game));
        }
        later(player, () -> player.openInventory(inv));
    }

    private ItemStack gameIcon(Game game) {
        boolean waiting = game.phase == Game.Phase.WAITING;
        List<String> lore = new ArrayList<>(List.of(
                "<gray>模式：<white>" + game.mode.label,
                "<gray>人数：<white>" + game.count() + "/" + game.maxPlayers,
                "<gray>状态：<white>" + (waiting ? "等待中" : "游戏中"),
                "<gray>房主：<white>" + name(game.host)));
        lore.add(waiting ? "<yellow>点击加入" : "<red>游戏已开始");
        return item(waiting ? Material.WHITE_WOOL : Material.RED_WOOL,
                (waiting ? "<white>" : "<gray>") + game.name, lore);
    }

    private String name(UUID id) {
        if (id == null) return "-";
        String name = Bukkit.getOfflinePlayer(id).getName();
        return name == null ? "???" : name;
    }

    // ---------- 房间 ----------

    public void openLobby(Player player, Game game) {
        Inventory inv = create("lobby", game.id, 3, "<gold>" + game.name + " <gray>· " + game.mode.label);
        int slot = 0;
        for (UUID id : game.order()) {
            boolean ready = game.ready.contains(id);
            ItemStack head = ItemStack.of(Material.PLAYER_HEAD);
            head.editMeta(SkullMeta.class, meta -> {
                meta.setOwningPlayer(Bukkit.getOfflinePlayer(id));
                meta.displayName(MineUnoPlugin.mm("<white>" + name(id)
                        + (id.equals(game.host) ? " <gold>[房主]" : "")).decoration(TextDecoration.ITALIC, false));
                meta.lore(List.of(MineUnoPlugin.mm(ready ? "<green>✔ 已准备" : "<red>✘ 未准备")));
            });
            inv.setItem(slot++, head);
        }
        boolean ready = game.ready.contains(player.getUniqueId());
        inv.setItem(20, item(ready ? Material.RED_CONCRETE : Material.LIME_CONCRETE,
                ready ? "<red>取消准备" : "<green>准备", List.of()));
        int min = plugin.cfg("game.min-players", 2);
        List<String> notReady = new ArrayList<>();
        for (UUID id : game.order()) if (!game.ready.contains(id)) notReady.add(name(id));
        boolean isHost = player.getUniqueId().equals(game.host);
        List<String> startLore = new ArrayList<>();
        startLore.add("<gray>人数：<white>" + game.count() + "/" + game.maxPlayers + (game.count() < min ? " <red>（至少 " + min + " 人）" : ""));
        startLore.add(notReady.isEmpty() ? "<green>所有玩家已准备" : "<red>未准备：" + String.join("、", notReady));
        if (!isHost) startLore.add("<red>只有房主可以开始");
        inv.setItem(24, item(isHost && notReady.isEmpty() && game.count() >= min ? Material.EMERALD_BLOCK : Material.GRAY_STAINED_GLASS_PANE,
                "<yellow>开始游戏", startLore));
        inv.setItem(22, item(Material.BARRIER, "<red>离开房间", List.of()));
        inv.setItem(26, item(Material.PAPER, "<gray>关掉界面后回到房间",
                List.of("<gray>输入 <white>/uno <gray>重新打开")));
        later(player, () -> player.openInventory(inv));
    }

    // ---------- 控制面板 ----------

    /** 手牌已经是 3D 实体，这里只放操作按钮。 */
    public void openPanel(Player player, Game game) {
        Inventory inv = create("panel", game.id, 3, "<dark_gray>控制面板 · " + game.name
                + (game.mode == Game.Mode.CLASSIC ? " <gray>· <gold>" + game.scores.getOrDefault(player.getUniqueId(), 0) + " 分" : ""));
        boolean postDraw = game.phase == Game.Phase.POST_DRAW && player.getUniqueId().equals(game.current());
        inv.setItem(10, item(Material.PAPER, "<white>摸牌", List.of("<gray>从牌堆摸一张", "<gray>也可以直接右键")));
        inv.setItem(12, item(postDraw ? Material.LIME_CONCRETE : Material.GRAY_CONCRETE,
                postDraw ? "<yellow>结束回合" : "<dark_gray>结束回合",
                postDraw ? List.of("<gray>放弃打出刚摸到的牌") : List.of("<gray>摸牌后才可以结束回合")));
        inv.setItem(14, item(Material.GOLD_INGOT, "<gold><bold>UNO!", List.of("<gray>剩 2 张时提前喊，剩 1 张时可补喊")));
        inv.setItem(16, item(Material.BOOK, "<yellow>规则与教程", List.of("<gray>玩法 / 功能牌 / 质疑 / 操作")));
        inv.setItem(22, item(Material.RED_CONCRETE, "<red>离开牌局", List.of("<gray>游戏中离开视为弃权")));
        inv.setItem(26, item(Material.BARRIER, "<red>关闭", List.of()));
        later(player, () -> player.openInventory(inv));
    }

    // ---------- 选色 / 质疑 ----------

    public void openColor(Player player, Game game) {
        Inventory inv = create("color", game.id, 3, "<gold>选择颜色");
        int[] slots = {10, 12, 14, 16};
        for (int i = 0; i < 4; i++) {
            Card.Color color = Card.COLORS.get(i);
            inv.setItem(slots[i], colorIcon(color));
        }
        later(player, () -> player.openInventory(inv));
    }

    private ItemStack colorIcon(Card.Color color) {
        Material material = switch (color) {
            case RED -> Material.RED_CONCRETE;
            case YELLOW -> Material.YELLOW_CONCRETE;
            case GREEN -> Material.LIME_CONCRETE;
            case BLUE -> Material.BLUE_CONCRETE;
            default -> Material.MAGENTA_CONCRETE;
        };
        return item(material, "<" + color.tag + "><bold>" + color.cn + "色", List.of("<gray>点击选择"));
    }

    public void openChallenge(Player player, Game game) {
        Inventory inv = create("challenge", game.id, 3, "<red><bold>+4 质疑");
        inv.setItem(11, item(Material.LIME_CONCRETE, "<green><bold>接受 +4", List.of("<gray>摸 4 张牌并跳过回合")));
        inv.setItem(15, item(Material.RED_CONCRETE, "<red><bold>质疑", List.of("<gray>对方若确实违规：他摸 4 张", "<gray>对方若无违规：你摸 6 张")));
        later(player, () -> player.openInventory(inv));
    }

    // ---------- 刷新 ----------

    /** 下一 tick 时按玩家当前打开的界面类型刷新，避免用旧类型覆盖新界面。 */
    public void refresh(Player player) {
        if (player == null) return;
        Bukkit.getScheduler().runTask(plugin, () -> {
            if (!(player.getOpenInventory().getTopInventory().getHolder() instanceof Holder holder)) return;
            Game game = holder.game() == null ? null : plugin.matches().game(holder.game());
            if (game == null) return;
            switch (holder.type()) {
                case "lobby" -> openLobby(player, game);
                case "panel" -> openPanel(player, game);
                default -> {
                }
            }
        });
    }

    // ---------- 点击 ----------

    public void click(Player player, Holder holder, int slot) {
        Game game = holder.game() == null ? null : plugin.matches().game(holder.game());
        switch (holder.type()) {
            case "main" -> {
                if (slot == 4) openRules(player, 0);
                else if (slot == 11) create(player, Game.Mode.QUICK);
                else if (slot == 13) create(player, Game.Mode.CLASSIC);
                else if (slot == 15) openMain(player);
                else if (slot >= 18 && slot <= 26) {
                    int index = slot - 18;
                    if (index >= holder.rooms().size()) return;
                    Game target = plugin.matches().game(holder.rooms().get(index));
                    if (target != null && plugin.matches().join(player, target)) openLobby(player, target);
                }
            }
            case "lobby" -> {
                if (game == null || !game.hands.containsKey(player.getUniqueId())) return;
                if (slot == 20) {
                    plugin.matches().toggleReady(player);
                } else if (slot == 24) {
                    later(player, () -> plugin.matches().start(player, game));
                } else if (slot == 22) {
                    plugin.matches().leave(player);
                    openMain(player);
                }
            }
            case "panel" -> {
                if (game == null || !game.hands.containsKey(player.getUniqueId())) {
                    player.closeInventory();
                    return;
                }
                switch (slot) {
                    case 10 -> plugin.matches().draw(player, game);
                    case 12 -> plugin.matches().pass(player, game);
                    case 14 -> plugin.matches().callUno(player, game);
                    case 16 -> {
                        openRules(player, 0);
                        return;
                    }
                    case 22 -> {
                        plugin.matches().leave(player);
                        later(player, player::closeInventory);
                        return;
                    }
                    case 26 -> {
                        later(player, player::closeInventory);
                        return;
                    }
                    default -> {
                    }
                }
                openPanel(player, game);
            }
            case "color" -> {
                if (game == null) return;
                Card.Color color = switch (slot) {
                    case 10 -> Card.Color.RED;
                    case 12 -> Card.Color.YELLOW;
                    case 14 -> Card.Color.GREEN;
                    case 16 -> Card.Color.BLUE;
                    default -> null;
                };
                if (color != null) {
                    plugin.matches().chooseColor(player, game, color);
                    later(player, player::closeInventory);
                }
            }
            case "challenge" -> {
                if (game == null) return;
                if (slot == 11) plugin.matches().accept(player, game);
                else if (slot == 15) plugin.matches().challenge(player, game);
                later(player, player::closeInventory);
            }
            case "rules" -> {
                int page = rulePages.getOrDefault(player.getUniqueId(), 0);
                if (slot == 45) openRules(player, page - 1);
                else if (slot == 53) openRules(player, page + 1);
                else if (slot == 49) later(player, player::closeInventory);
            }
            default -> {
            }
        }
    }

    private void create(Player player, Game.Mode mode) {
        Game game = plugin.matches().create(player, mode);
        if (game == null) {
            MineUnoPlugin.msg(player, "<red>你已经在其他房间中了");
            return;
        }
        openLobby(player, game);
    }

    public void reset(UUID player) {
        rulePages.remove(player);
    }
}
