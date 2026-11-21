package com.mineuno;

import java.util.UUID;
import net.kyori.adventure.bossbar.BossBar;
import net.kyori.adventure.text.Component;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;

/** BossBar + ActionBar HUD。 */
public class Hud {

    private final MineUnoPlugin plugin;
    private final Game game;
    private final BossBar bar = BossBar.bossBar(Component.empty(), 1f, BossBar.Color.WHITE, BossBar.Overlay.PROGRESS);

    public Hud(MineUnoPlugin plugin, Game game) {
        this.plugin = plugin;
        this.game = game;
    }

    private int seconds(Game.Phase phase) {
        return phase == Game.Phase.CHALLENGE ? plugin.cfg("game.challenge-time", 8) : plugin.cfg("game.turn-time", 30);
    }

    private String name(UUID player) {
        String n = Bukkit.getOfflinePlayer(player).getName();
        return n == null ? "???" : n;
    }

    private BossBar.Color color() {
        return switch (game.activeColor) {
            case RED -> BossBar.Color.RED;
            case YELLOW -> BossBar.Color.YELLOW;
            case GREEN -> BossBar.Color.GREEN;
            case BLUE -> BossBar.Color.BLUE;
            case WILD -> BossBar.Color.PURPLE;
        };
    }

    public void show(Player player) {
        player.showBossBar(bar);
    }

    public void hide(Player player) {
        player.hideBossBar(bar);
    }

    public void update() {
        long remain = game.deadline <= 0 ? 0 : Math.max(0, (game.deadline - System.currentTimeMillis() + 999) / 1000);
        int total = Math.max(1, seconds(game.phase));
        UUID cur = game.current();
        String phaseText = switch (game.phase) {
            case PICK_COLOR -> " <gold>(选色中)";
            case CHALLENGE -> " <red>(+4 质疑中)";
            default -> "";
        };
        String turn = cur == null ? "<gray>等待中" : "<white>" + name(cur) + " <gray>的回合" + phaseText;
        Card top = game.top();
        String last = top == null ? "<dark_gray>—" : top.coloredName();
        String timeTag = remain <= 5 && game.deadline > 0 ? "<red><bold>" + remain + "s" : "<white>" + remain + "s";
        bar.name(MineUnoPlugin.mm(turn + " <dark_gray>| <" + game.activeColor.tag + ">" + game.activeColor.cn
                + " <dark_gray>| " + timeTag + " <dark_gray>| <gray>上一张 " + last
                + " <dark_gray>| " + (game.dir > 0 ? "<white>↻" : "<white>↺")));
        bar.color(color());
        bar.progress(game.deadline <= 0 ? 1f : Math.min(1f, remain / (float) total));

        for (UUID id : game.hands.keySet()) {
            Player p = Bukkit.getPlayer(id);
            if (p == null) continue;
            p.sendActionBar(MineUnoPlugin.mm(actionBar(p)));
        }
    }

    private String actionBar(Player player) {
        UUID id = player.getUniqueId();
        if (game.phase == Game.Phase.CHALLENGE) {
            long remain = game.deadline <= 0 ? 0 : Math.max(0, (game.deadline - System.currentTimeMillis() + 999) / 1000);
            if (id.equals(game.w4challenger)) {
                return "<red><bold>" + name(game.w4offender) + " 对你使用了 +4 <dark_gray>| <white>接受或质疑 <gray>(" + remain + "s)";
            }
            return "<gray>等待 <white>" + name(game.w4challenger) + " <gray>决定是否质疑 +4 <dark_gray>| <white>手牌 ×" + game.hand(id).size();
        }
        if (game.phase == Game.Phase.PICK_COLOR) {
            if (id.equals(game.current())) {
                return "<gold><bold>请选择颜色 <dark_gray>| <gray>点击选色界面中的红/黄/绿/蓝";
            }
            return "<gray>等待 <white>" + name(game.current()) + " <gray>选择颜色 <dark_gray>| <white>手牌 ×" + game.hand(id).size();
        }
        if (id.equals(game.current()) && (game.phase == Game.Phase.PLAYING || game.phase == Game.Phase.POST_DRAW)) {
            if (game.phase == Game.Phase.POST_DRAW) return "<yellow>摸到了 <white>" + (game.drawn == null ? "?" : game.drawn.name())
                    + " <dark_gray>| <white>左键出牌 · 潜行+左键结束回合";
            return "<green>轮到你 <dark_gray>| <white>准星选牌 → 左键出牌 · 右键摸牌";
        }
        int handSize = game.hand(id).size();
        if (id.equals(game.vulnerable)) {
            return "<red><bold>⚠ 你忘喊 UNO 了！<white>快输入 <yellow>/uno uno <white>或点面板补喊";
        }
        if (game.vulnerable != null) {
            return "<gold>⚡ " + name(game.vulnerable) + " 剩 1 张没喊 UNO！<white>可输入 <yellow>/uno catch " + name(game.vulnerable);
        }
        StringBuilder sb = new StringBuilder();
        if (handSize == 1) {
            sb.append("<green>✔ 你已喊过 UNO");
        } else {
            sb.append("<gray>等待 <white>").append(name(game.current())).append(" <gray>出牌");
        }
        sb.append(" <dark_gray>| <white>手牌 ×").append(handSize);
        sb.append(" <dark_gray>| <white>牌堆 ×").append(game.draw.size());
        return sb.toString();
    }
}
