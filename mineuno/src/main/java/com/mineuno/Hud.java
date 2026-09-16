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
        String turn = cur == null ? "<gray>等待中" : "<white>" + name(cur) + " <gray>的回合";
        Card top = game.top();
        String last = top == null ? "<dark_gray>—" : top.coloredName();
        bar.name(MineUnoPlugin.mm(turn + " <dark_gray>| <" + game.activeColor.tag + ">" + game.activeColor.cn
                + " <dark_gray>| <white>" + remain + "s <dark_gray>| <gray>上一张 " + last
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
        if (game.phase == Game.Phase.CHALLENGE && id.equals(game.w4challenger)) {
            long remain = game.deadline <= 0 ? 0 : Math.max(0, (game.deadline - System.currentTimeMillis() + 999) / 1000);
            return "<red>" + name(game.w4offender) + " 对你使用了 +4 <dark_gray>| <white>接受或质疑 <gray>(" + remain + "s)";
        }
        if (id.equals(game.current()) && (game.phase == Game.Phase.PLAYING || game.phase == Game.Phase.POST_DRAW)) {
            if (game.phase == Game.Phase.POST_DRAW) return "<yellow>摸到了 <white>" + (game.drawn == null ? "?" : game.drawn.name())
                    + " <dark_gray>| <white>左键出牌 · 潜行+左键结束回合";
            return "<green>轮到你 <dark_gray>| <white>准星选牌 → 左键出牌 · 右键摸牌";
        }
        StringBuilder sb = new StringBuilder();
        if (game.hands.get(id).size() == 1 && id.equals(game.vulnerable)) {
            sb.append("<red>⚠ 你只剩 1 张牌，快喊 /uno uno！");
        } else if (id.equals(game.vulnerable)) {
            sb.append("<gray>你已喊过 UNO");
        } else {
            sb.append("<gray>等待 <white>").append(name(game.current())).append(" <gray>出牌");
            sb.append(" <dark_gray>| <white>手牌 ×").append(game.hand(id).size());
            sb.append(" <dark_gray>| <white>牌堆 ×").append(game.draw.size());
        }
        return sb.toString();
    }
}
