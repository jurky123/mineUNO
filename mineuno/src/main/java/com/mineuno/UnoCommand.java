package com.mineuno;

import java.util.ArrayList;
import java.util.List;
import org.bukkit.Bukkit;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;

public class UnoCommand implements CommandExecutor, TabCompleter {

    private final MineUnoPlugin plugin;

    public UnoCommand(MineUnoPlugin plugin) {
        this.plugin = plugin;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!(sender instanceof Player player)) {
            if (args.length > 0 && args[0].equalsIgnoreCase("reload")) {
                plugin.reloadAll();
                sender.sendMessage("MineUNO 配置已重载");
                return true;
            }
            sender.sendMessage("该命令只能由玩家执行");
            return true;
        }
        Game game = plugin.matches().gameOf(player.getUniqueId());
        String sub = args.length == 0 ? "" : args[0].toLowerCase();
        switch (sub) {
            case "" -> {
                if (game == null) {
                    plugin.menus().openMain(player);
                } else if (game.phase == Game.Phase.WAITING) {
                    plugin.menus().openLobby(player, game);
                } else {
                    plugin.menus().openPanel(player, game);
                }
            }
            case "rules", "help" -> plugin.menus().openRules(player, 0);
            case "create" -> {
                Game.Mode mode = args.length > 1 && args[1].equalsIgnoreCase("classic")
                        ? Game.Mode.CLASSIC : Game.Mode.QUICK;
                Game created = plugin.matches().create(player, mode);
                if (created == null) MineUnoPlugin.msg(player, "<red>你已经在其他房间中了");
                else plugin.menus().openLobby(player, created);
            }
            case "join" -> {
                List<Game> games = new ArrayList<>(plugin.matches().games());
                if (games.isEmpty()) {
                    MineUnoPlugin.msg(player, "<red>当前没有房间");
                    return true;
                }
                Game target = null;
                if (args.length > 1) {
                    String key = args[1].replace("#", "").trim();
                    for (Game g : games) if (g.name.endsWith(key) || g.name.equalsIgnoreCase(args[1])) target = g;
                }
                if (target == null) for (Game g : games) if (g.phase == Game.Phase.WAITING) target = g;
                if (target == null) {
                    MineUnoPlugin.msg(player, "<red>没有可加入的房间");
                    return true;
                }
                if (plugin.matches().join(player, target)) plugin.menus().openLobby(player, target);
            }
            case "leave" -> {
                if (game == null) MineUnoPlugin.msg(player, "<red>你不在任何房间中");
                else plugin.matches().leave(player);
            }
            case "start" -> {
                if (game == null || !plugin.matches().start(player, game)) {
                    MineUnoPlugin.msg(player, "<red>无法开始：需要至少 2 人且全部准备，且你是房主");
                } else {
                    player.closeInventory();
                }
            }
            case "ready" -> {
                if (game == null) {
                    MineUnoPlugin.msg(player, "<red>你不在任何房间中");
                } else {
                    if (!game.ready.remove(player.getUniqueId())) {
                        game.ready.add(player.getUniqueId());
                        plugin.matches().send(game, "<green>" + player.getName() + " 已准备");
                    } else {
                        plugin.matches().send(game, "<yellow>" + player.getName() + " 取消准备");
                    }
                    plugin.matches().refreshLobby(game);
                }
            }
            case "hand", "panel" -> {
                if (game == null || game.phase == Game.Phase.WAITING) MineUnoPlugin.msg(player, "<red>你不在牌局中");
                else plugin.menus().openPanel(player, game);
            }
            case "seat" -> {
                if (!player.hasPermission("mineuno.admin")) {
                    MineUnoPlugin.msg(player, "<red>没有权限");
                    return true;
                }
                if (args.length < 2) {
                    MineUnoPlugin.msg(player, "<gray>当前 seat.y-offset = <yellow>"
                            + plugin.getConfig().getDouble("seat.y-offset", -0.9) + " <gray>用法：/uno seat <数值>");
                    return true;
                }
                try {
                    double value = Double.parseDouble(args[1]);
                    plugin.getConfig().set("seat.y-offset", value);
                    plugin.saveConfig();
                    plugin.matches().reloadSeat();
                    MineUnoPlugin.msg(player, "<green>seat.y-offset = " + value + " <gray>（已应用到进行中的牌桌）");
                } catch (NumberFormatException e) {
                    MineUnoPlugin.msg(player, "<red>数值不合法");
                }
            }
                case "handtune" -> {
                if (!player.hasPermission("mineuno.admin")) {
                    MineUnoPlugin.msg(player, "<red>没有权限");
                    return true;
                }
                handTune(player, args);
            }
            case "draw" -> {
                if (game != null) plugin.matches().draw(player, game);
            }
            case "pass" -> {
                if (game != null) plugin.matches().pass(player, game);
            }
            case "uno" -> {
                if (game != null) plugin.matches().callUno(player, game);
            }
            case "catch" -> {
                if (args.length < 2) {
                    MineUnoPlugin.msg(player, "<red>用法：/uno catch <玩家>");
                } else if (!plugin.matches().catchUno(player, args[1])) {
                    MineUnoPlugin.msg(player, "<red>抓 UNO 失败");
                }
            }
            case "accept" -> {
                if (game != null) plugin.matches().accept(player, game);
            }
            case "challenge" -> {
                if (game != null) plugin.matches().challenge(player, game);
            }
            case "list" -> {
                if (plugin.matches().games().isEmpty()) {
                    MineUnoPlugin.msg(player, "<gray>当前没有房间");
                } else {
                    for (Game g : plugin.matches().games()) {
                        MineUnoPlugin.msg(player, "<white>" + g.name + " <gray>| <white>" + g.mode.label
                                + " <gray>| " + g.count() + "/" + g.maxPlayers + " <gray>| "
                                + (g.phase == Game.Phase.WAITING ? "<green>等待中" : "<red>游戏中"));
                    }
                }
            }
            case "setarena" -> {
                if (!player.hasPermission("mineuno.admin")) {
                    MineUnoPlugin.msg(player, "<red>没有权限");
                    return true;
                }
                plugin.arena().save(player.getLocation());
                MineUnoPlugin.msg(player, "<green>竞技场中心已设为当前位置：" + player.getWorld().getName()
                        + " " + (int) player.getX() + "," + (int) player.getY() + "," + (int) player.getZ());
            }
            case "reload" -> {
                if (!player.hasPermission("mineuno.admin")) {
                    MineUnoPlugin.msg(player, "<red>没有权限");
                    return true;
                }
                plugin.reloadAll();
                MineUnoPlugin.msg(player, "<green>MineUNO 配置已重载");
            }
            default -> {
                MineUnoPlugin.msg(player, "<gold>MineUNO 命令：");
                MineUnoPlugin.msg(player, "<white>/uno <gray>打开大厅 · <white>/uno rules <gray>规则与教程 · <white>/uno hand <gray>手牌");
                MineUnoPlugin.msg(player, "<white>/uno ready <gray>准备 · <white>/uno leave <gray>退出 · <white>/uno list <gray>房间列表");
                MineUnoPlugin.msg(player, "<white>/uno uno <gray>喊 UNO · <white>/uno catch <玩家> <gray>抓 UNO · <white>/uno setarena <gray>设置竞技场（OP）");
                MineUnoPlugin.msg(player, "<white>/uno seat <数值> <gray>调坐姿高度（OP）· <white>/uno handtune <gray>调手牌（OP）");
            }
        }
        return true;
    }

    private void handTune(Player player, String[] args) {
        List<String> keys = List.of("yaw-offset", "tilt", "distance", "height", "scale", "yaw-spread", "aim-radius");
        if (args.length >= 3 && keys.contains(args[1].toLowerCase())) {
            try {
                double value = Double.parseDouble(args[2]);
                plugin.getConfig().set("hand." + args[1].toLowerCase(), value);
                plugin.saveConfig();
                plugin.matches().reloadHand();
                MineUnoPlugin.msg(player, "<green>hand." + args[1].toLowerCase() + " = " + value);
            } catch (NumberFormatException e) {
                MineUnoPlugin.msg(player, "<red>数值不合法");
            }
            return;
        }
        if (args.length >= 2 && keys.contains(args[1].toLowerCase())) {
            plugin.matches().startTune(player, args[1].toLowerCase());
            return;
        }
        MineUnoPlugin.msg(player, "<gold>手牌参数（当前值）：");
        for (String key : keys) {
            MineUnoPlugin.msg(player, "<white>" + key + " <gray>= <yellow>"
                    + String.format("%.2f", plugin.getConfig().getDouble("hand." + key, 0)));
        }
        MineUnoPlugin.msg(player, "<gray>/uno handtune <参数> 进入滚轮调整，再输入 /uno handtune 保存退出");
        plugin.matches().stopTune(player);
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        if (args.length == 1) {
            List<String> subs = new ArrayList<>(List.of("create", "join", "leave", "start", "ready", "hand",
                    "panel", "draw", "pass", "uno", "catch", "accept", "challenge", "list", "rules", "help"));
            if (sender.hasPermission("mineuno.admin")) {
                subs.add("setarena");
                subs.add("reload");
                subs.add("handtune");
                subs.add("seat");
            }
            return match(subs, args[0]);
        }
        if (args.length == 2) {
            switch (args[0].toLowerCase()) {
                case "handtune" -> {
                    return match(List.of("yaw-offset", "tilt", "distance", "height", "scale", "yaw-spread", "aim-radius"), args[1]);
                }
                case "create" -> {
                    return match(List.of("quick", "classic"), args[1]);
                }
                case "join" -> {
                    List<String> names = new ArrayList<>();
                    for (Game g : plugin.matches().games()) {
                        if (g.phase == Game.Phase.WAITING) names.add(g.name.replace("UNO #", ""));
                    }
                    return match(names, args[1]);
                }
                case "catch" -> {
                    List<String> names = new ArrayList<>();
                    if (sender instanceof Player player) {
                        Game g = plugin.matches().gameOf(player.getUniqueId());
                        if (g != null && g.vulnerable != null) {
                            String name = Bukkit.getOfflinePlayer(g.vulnerable).getName();
                            if (name != null) names.add(name);
                        }
                    }
                    if (names.isEmpty()) {
                        for (Player online : Bukkit.getOnlinePlayers()) names.add(online.getName());
                    }
                    return match(names, args[1]);
                }
                default -> {
                }
            }
        }
        return List.of();
    }

    private static List<String> match(List<String> options, String prefix) {
        String lower = prefix.toLowerCase();
        return options.stream().filter(option -> option.toLowerCase().startsWith(lower)).toList();
    }
}
