package com.mineuno;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.title.Title;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Sound;
import org.bukkit.attribute.Attribute;
import org.bukkit.attribute.AttributeInstance;
import org.bukkit.entity.Player;
import org.bukkit.scheduler.BukkitTask;

/** 房间管理：多桌、计时、断线、表现层调度。 */
public class MatchManager {

    private final MineUnoPlugin plugin;
    private final Map<UUID, Game> games = new LinkedHashMap<>();
    private final Map<UUID, Game> playerGame = new HashMap<>();
    private final Map<UUID, Table> tables = new HashMap<>();
    private final Map<UUID, Integer> slots = new HashMap<>();
    private final Map<UUID, Table.Hit> hits = new HashMap<>();
    private final Map<UUID, Hud> huds = new HashMap<>();
    private final Map<UUID, Location> back = new HashMap<>();
    private final Map<UUID, Long> grace = new HashMap<>();
    private final Map<UUID, Integer> lastSeq = new HashMap<>();
    private final Map<UUID, Double> reachBackup = new HashMap<>();
    private final Map<UUID, String> tuning = new HashMap<>();
    private BukkitTask ticker;
    private BukkitTask aimTicker;
    private int counter;

    public MatchManager(MineUnoPlugin plugin) {
        this.plugin = plugin;
    }

    public void start() {
        ticker = Bukkit.getScheduler().runTaskTimer(plugin, this::tick, 40, 10);
        aimTicker = Bukkit.getScheduler().runTaskTimer(plugin, this::tickAim, 20, 2);
    }

    public void shutdown() {
        if (ticker != null) ticker.cancel();
        if (aimTicker != null) aimTicker.cancel();
        for (Game g : new ArrayList<>(games.values())) cleanup(g, false);
    }

    public Collection<Game> games() {
        return java.util.Collections.unmodifiableCollection(games.values());
    }

    public Game game(UUID id) {
        return id == null ? null : games.get(id);
    }

    public Game gameOf(UUID player) {
        return playerGame.get(player);
    }

    private Table tableOf(Game g) {
        return tables.computeIfAbsent(g.id, id -> new Table(plugin, g, plugin.arena().center(0)));
    }

    public Table table(Game g) {
        return tableOf(g);
    }

    public void reloadHand() {
        for (Table table : tables.values()) table.reloadHand();
    }

    public void reloadSeat() {
        for (Table table : tables.values()) table.reloadSeat();
    }

    // ---------- 滚轮实时调参 ----------

    public boolean tuning(Player player) {
        return tuning.containsKey(player.getUniqueId());
    }

    public void startTune(Player player, String key) {
        tuning.put(player.getUniqueId(), key);
        showTune(player);
    }

    public void stopTune(Player player) {
        String key = tuning.remove(player.getUniqueId());
        if (key != null) plugin.saveConfig();
        player.sendActionBar(Component.empty());
    }

    public void tuneScroll(Player player, int previous, int now) {
        String key = tuning.get(player.getUniqueId());
        if (key == null) return;
        int delta = now - previous;
        if (delta > 4) delta -= 9;
        if (delta < -4) delta += 9;
        if (delta == 0) return;
        double step = switch (key) {
            case "distance", "height", "scale" -> 0.05;
            case "aim-radius" -> 0.02;
            case "yaw-spread" -> 1;
            default -> 5;
        };
        double value = plugin.getConfig().getDouble("hand." + key, 0) + delta * step;
        if (key.equals("scale") || key.equals("aim-radius")) value = Math.max(0.05, value);
        plugin.getConfig().set("hand." + key, value);
        reloadHand();
        showTune(player);
    }

    private void showTune(Player player) {
        String key = tuning.get(player.getUniqueId());
        if (key == null) return;
        player.sendActionBar(MineUnoPlugin.mm("<gold>调整 <white>hand." + key + " <gold>= <yellow>"
                + String.format("%.2f", plugin.getConfig().getDouble("hand." + key, 0))
                + " <dark_gray>| <gray>滚轮调整 · <white>/uno handtune <gray>保存并退出"));
    }

    public int selectedCard(Player player) {
        Game g = gameOf(player.getUniqueId());
        if (g == null || g.phase == Game.Phase.WAITING) return -1;
        return tableOf(g).selectedCard(player.getUniqueId());
    }

    /** 手牌只对本人可见，新进服的玩家需要隐藏其他人的手牌。 */
    public void hidePrivateFrom(Player player) {
        for (Table table : tables.values()) table.hidePrivateFrom(player);
    }

    public void registerHit(UUID entityId, Table.Hit hit) {
        hits.put(entityId, hit);
    }

    public Table.Hit hit(UUID entityId) {
        return hits.get(entityId);
    }

    public void unregisterHits(Table table) {
        hits.entrySet().removeIf(entry -> entry.getValue().table() == table);
    }

    private void tickAim() {
        for (Game g : games.values()) {
            if (g.phase == Game.Phase.WAITING) continue;
            Table table = tables.get(g.id);
            if (table == null) continue;
            for (UUID id : g.hands.keySet()) {
                if (g.away.contains(id)) continue;
                Player p = Bukkit.getPlayer(id);
                if (p != null) table.aim(p);
            }
        }
    }

    private Hud hud(Game g) {
        return huds.computeIfAbsent(g.id, id -> new Hud(plugin, g));
    }

    // ---------- 房间操作 ----------

    public Game create(Player host, Game.Mode mode) {
        if (gameOf(host.getUniqueId()) != null) return null;
        Game g = new Game("UNO #" + (++counter), mode,
                plugin.cfg("rules.wild4-challenge", true),
                plugin.cfg("game.starting-cards", 7),
                plugin.cfg("game.max-players", 8));
        g.events = events(g);
        g.join(host.getUniqueId());
        games.put(g.id, g);
        playerGame.put(host.getUniqueId(), g);
        return g;
    }

    /** 让房间里所有打开着界面的玩家刷新大厅。 */
    public void refreshLobby(Game g) {
        for (UUID id : g.hands.keySet()) {
            Player p = Bukkit.getPlayer(id);
            if (p != null) plugin.menus().refresh(p);
        }
    }

    public boolean join(Player player, Game g) {
        if (gameOf(player.getUniqueId()) != null) {
            MineUnoPlugin.msg(player, "<red>你已经在其他房间中了");
            return false;
        }
        if (g.phase != Game.Phase.WAITING || !g.join(player.getUniqueId())) {
            MineUnoPlugin.msg(player, "<red>无法加入该房间");
            return false;
        }
        playerGame.put(player.getUniqueId(), g);
        send(g, "<yellow>" + name(player.getUniqueId()) + " 加入了房间 <gray>(" + g.count() + "/" + g.maxPlayers + ")");
        MineUnoPlugin.msg(player, "<gray>输入 <white>/uno <gray>可随时重新打开房间界面");
        refreshLobby(g);
        return true;
    }

    public void leave(Player player) {
        Game g = gameOf(player.getUniqueId());
        if (g == null) return;
        if (g.phase == Game.Phase.WAITING) {
            boolean wasHost = player.getUniqueId().equals(g.host);
            playerGame.remove(player.getUniqueId());
            g.leave(player.getUniqueId());
            if (g.count() == 0) {
                destroy(g);
            } else {
                send(g, "<yellow>" + name(player.getUniqueId()) + " 离开了房间");
                if (wasHost) send(g, "<gold>房主已转移给 <white>" + name(g.host));
                refreshLobby(g);
            }
        } else {
            forfeit(g, player.getUniqueId());
        }
    }

    /** 准备 / 取消准备（GUI 与命令共用同一入口）。 */
    public void toggleReady(Player player) {
        Game g = gameOf(player.getUniqueId());
        if (g == null || g.phase != Game.Phase.WAITING) return;
        if (!g.ready.remove(player.getUniqueId())) {
            g.ready.add(player.getUniqueId());
            send(g, "<green>" + name(player.getUniqueId()) + " 已准备");
        } else {
            send(g, "<yellow>" + name(player.getUniqueId()) + " 取消准备");
        }
        refreshLobby(g);
    }

    public boolean start(Player player, Game g) {
        if (g.phase != Game.Phase.WAITING) {
            MineUnoPlugin.msg(player, "<red>游戏已经开始或结束");
            return false;
        }
        if (g.host == null || !g.host.equals(player.getUniqueId())) {
            MineUnoPlugin.msg(player, "<red>只有房主可以开始游戏");
            return false;
        }
        int min = plugin.cfg("game.min-players", 2);
        if (g.count() < min) {
            MineUnoPlugin.msg(player, "<red>人数不足：至少需要 " + min + " 人（当前 " + g.count() + " 人）");
            return false;
        }
        List<String> notReady = new ArrayList<>();
        for (UUID id : g.hands.keySet()) if (!g.ready.contains(id)) notReady.add(name(id));
        if (!notReady.isEmpty()) {
            MineUnoPlugin.msg(player, "<red>还有人未准备：<white>" + String.join("、", notReady) + " <gray>（也可以让他们点准备）");
            return false;
        }
        try {
            startMatch(g);
        } catch (RuntimeException e) {
            plugin.getLogger().log(java.util.logging.Level.SEVERE, "开局失败", e);
            cleanup(g, true);
            MineUnoPlugin.msg(player, "<red>开局失败：" + e.getMessage());
            return false;
        }
        return true;
    }

    private void startMatch(Game g) {
        int slot = plugin.arena().allocate();
        if (slot < 0) throw new IllegalStateException("没有空闲牌桌（等待其他对局结束，或在配置里增加竞技场坐标）");
        slots.put(g.id, slot);
        Table table = new Table(plugin, g, plugin.arena().center(slot));
        tables.put(g.id, table);
        table.build();
        for (UUID id : g.order()) {
            Player p = Bukkit.getPlayer(id);
            if (p == null) continue;
            p.closeInventory();
            back.put(id, p.getLocation());
            Location seat = table.stand(id);
            if (seat != null) p.teleport(seat);
            hud(g).show(p);
            grantReach(p);
            plugin.menus().reset(id);
        }
        send(g, "<gold>" + g.name + " <gray>开始！模式：<white>" + g.mode.label);
        g.startRound();
    }

    public void destroy(Game g) {
        games.remove(g.id);
        tables.remove(g.id);
        huds.remove(g.id);
        lastSeq.remove(g.id);
        for (UUID id : new ArrayList<>(g.hands.keySet())) {
            playerGame.remove(id);
            back.remove(id);
            grace.remove(id);
            plugin.menus().reset(id);
        }
    }

    public void cleanup(Game g, boolean teleportBack) {
        if (!games.containsKey(g.id)) return;
        Table table = tables.remove(g.id);
        if (table != null) table.remove();
        Integer slot = slots.remove(g.id);
        if (slot != null) plugin.arena().release(slot);
        Hud hud = huds.remove(g.id);
        for (UUID id : new ArrayList<>(g.hands.keySet())) {
            Player p = Bukkit.getPlayer(id);
            if (p != null) {
                if (hud != null) hud.hide(p);
                p.sendActionBar(Component.empty());
                revokeReach(p);
                if (teleportBack && back.containsKey(id)) p.teleport(back.get(id));
            }
            reachBackup.remove(id);
        }
        destroy(g);
    }

    // ---------- 玩家动作 ----------

    public void play(Player player, Game g, int cardId) {
        int before = g.turn;
        UUID beforeCur = g.current();
        boolean ok = g.play(player.getUniqueId(), cardId);
        plugin.log("[play] " + player.getName() + " card#" + cardId + " ok=" + ok
                + " phase=" + g.phase + " turn " + before + "->" + g.turn
                + " cur " + name(beforeCur) + "->" + name(g.current()));
        if (!ok) MineUnoPlugin.msg(player, "<red>现在不能打出这张牌");
    }

    public void draw(Player player, Game g) {
        if (!g.drawCard(player.getUniqueId())) MineUnoPlugin.msg(player, "<red>现在不能摸牌");
    }

    public void pass(Player player, Game g) {
        if (!g.pass(player.getUniqueId())) MineUnoPlugin.msg(player, "<red>现在不能结束回合");
    }

    public void callUno(Player player, Game g) {
        if (!g.callUno(player.getUniqueId())) MineUnoPlugin.msg(player, "<red>现在不需要喊 UNO");
    }

    public void chooseColor(Player player, Game g, Card.Color color) {
        g.chooseColor(player.getUniqueId(), color);
    }

    public void accept(Player player, Game g) {
        g.acceptWild4(player.getUniqueId());
    }

    public void challenge(Player player, Game g) {
        g.challenge(player.getUniqueId());
    }

    public boolean catchUno(Player actor, String targetName) {
        Game g = gameOf(actor.getUniqueId());
        if (g == null) return false;
        for (UUID id : g.hands.keySet()) {
            String n = Bukkit.getOfflinePlayer(id).getName();
            if (n != null && n.equalsIgnoreCase(targetName)) return g.catchUno(actor.getUniqueId(), id);
        }
        return false;
    }

    // ---------- 断线 ----------

    public void quit(Player player) {
        stopTune(player);
        Game g = gameOf(player.getUniqueId());
        if (g == null) return;
        if (g.phase == Game.Phase.WAITING) {
            boolean wasHost = player.getUniqueId().equals(g.host);
            playerGame.remove(player.getUniqueId());
            g.leave(player.getUniqueId());
            if (g.count() == 0) {
                destroy(g);
            } else {
                if (wasHost) send(g, "<gold>房主已转移给 <white>" + name(g.host));
                refreshLobby(g);
            }
            return;
        }
        g.away.add(player.getUniqueId());
        grace.put(player.getUniqueId(), System.currentTimeMillis());
        tableOf(g).update();
        send(g, "<gray>" + name(player.getUniqueId()) + " 断线了，等待重连…");
    }

    public void reconnect(Player player) {
        Game g = gameOf(player.getUniqueId());
        if (g == null) return;
        if (g.phase == Game.Phase.WAITING) {
            plugin.menus().openLobby(player, g);
            return;
        }
        if (g.away.remove(player.getUniqueId())) {
            grace.remove(player.getUniqueId());
            Location seat = tableOf(g).stand(player.getUniqueId());
            if (seat != null) player.teleport(seat);
            hud(g).show(player);
            grantReach(player);
            tableOf(g).update();
            send(g, "<green>" + name(player.getUniqueId()) + " 重新连接");
            if (g.phase != Game.Phase.ENDED) plugin.menus().openPanel(player, g);
        }
    }

    private void forfeit(Game g, UUID id) {
        Player p = Bukkit.getPlayer(id);
        if (p != null) {
            Hud hud = huds.get(g.id);
            if (hud != null) hud.hide(p);
            p.sendActionBar(Component.empty());
            revokeReach(p);
            if (p.getVehicle() != null) p.leaveVehicle();
            if (back.containsKey(id)) p.teleport(back.get(id));
        }
        playerGame.remove(id);
        back.remove(id);
        grace.remove(id);
        reachBackup.remove(id);
        g.forfeit(id);
        if (games.containsKey(g.id)) send(g, "<red>" + name(id) + " 已离开牌局");
    }

    /** 坐下时放宽交互距离，方便点到桌面的牌堆。 */
    private void grantReach(Player player) {
        AttributeInstance attribute = player.getAttribute(Attribute.ENTITY_INTERACTION_RANGE);
        if (attribute == null) return;
        reachBackup.putIfAbsent(player.getUniqueId(), attribute.getBaseValue());
        attribute.setBaseValue(Math.max(attribute.getBaseValue(), plugin.cfg("game.interaction-range", 6.0)));
    }

    private void revokeReach(Player player) {
        Double old = reachBackup.remove(player.getUniqueId());
        if (old == null) return;
        AttributeInstance attribute = player.getAttribute(Attribute.ENTITY_INTERACTION_RANGE);
        if (attribute != null) attribute.setBaseValue(old);
    }

    /**
     * 一次性修复旧版本 UNO 污染过的属性（/uno repairlegacy 用）。
     * 只匹配我们当年写坏的具体值，绝不按阈值"修数据"，避免覆盖其他插件的设置。
     * 注意：Bukkit 的 setWalkSpeed(x) 内部把属性存成 x/2，正常速度要用 0.2f。
     */
    public boolean repairLegacy(Player player) {
        AttributeInstance speed = player.getAttribute(Attribute.MOVEMENT_SPEED);
        boolean changed = false;
        if (speed != null) {
            double value = speed.getBaseValue();
            if (value <= 0.0001 || (value > 0.04 && value < 0.06)) {
                player.setWalkSpeed(0.2f);
                changed = true;
            }
        }
        AttributeInstance jump = player.getAttribute(Attribute.JUMP_STRENGTH);
        if (jump != null && jump.getBaseValue() <= 0.0001) {
            jump.setBaseValue(0.42);
            changed = true;
        }
        AttributeInstance reach = player.getAttribute(Attribute.ENTITY_INTERACTION_RANGE);
        if (reach != null && Math.abs(reach.getBaseValue() - 6.0) < 0.01) {
            reach.setBaseValue(3.0);
            changed = true;
        }
        return changed;
    }

    // ---------- 计时 ----------

    private boolean needsAction(Game g) {
        return switch (g.phase) {
            case PLAYING, POST_DRAW, PICK_COLOR, CHALLENGE -> true;
            default -> false;
        };
    }

    private int seconds(Game g) {
        return g.phase == Game.Phase.CHALLENGE ? plugin.cfg("game.challenge-time", 8) : plugin.cfg("game.turn-time", 30);
    }

    private void tick() {
        long now = System.currentTimeMillis();
        for (Game g : new ArrayList<>(games.values())) {
            if (g.phase == Game.Phase.WAITING) {
                for (UUID id : g.hands.keySet()) {
                    Player p = Bukkit.getPlayer(id);
                    if (p != null) p.sendActionBar(MineUnoPlugin.mm(
                            "<gray>房间 <white>" + g.name + " <dark_gray>| <white>" + g.count() + "/" + g.maxPlayers
                                    + " <dark_gray>| <yellow>/uno <gray>打开房间界面"));
                }
                continue;
            }
            {
                Integer last = lastSeq.get(g.id);
                if (last == null || last != g.turnSeq) {
                    lastSeq.put(g.id, g.turnSeq);
                    g.deadline = needsAction(g) ? now + seconds(g) * 1000L : 0;
                }
                if (g.deadline > 0 && now >= g.deadline) {
                    g.deadline = 0;
                    if (g.current() != null) {
                        Player p = Bukkit.getPlayer(g.current());
                        if (p != null) MineUnoPlugin.msg(p, "<red>超时，自动处理");
                    }
                    g.forceResolve();
                }
                hud(g).update();
                keepNear(g);
            }
        }
        Iterator<Map.Entry<UUID, Long>> it = grace.entrySet().iterator();
        while (it.hasNext()) {
            Map.Entry<UUID, Long> e = it.next();
            if (now - e.getValue() <= plugin.cfg("game.reconnect-grace", 60) * 1000L) continue;
            it.remove();
            Game g = playerGame.get(e.getKey());
            if (g != null && g.hands.containsKey(e.getKey())) {
                send(g, "<red>" + name(e.getKey()) + " 断线超时，视为弃权");
                forfeit(g, e.getKey());
            }
        }
    }

    private void keepNear(Game g) {
        Table table = tables.get(g.id);
        if (table == null) return;
        for (UUID id : g.hands.keySet()) {
            if (g.away.contains(id)) continue;
            if (Bukkit.getPlayer(id) != null) table.keepSeat(id);
        }
    }

    private void schedule(long delay, Runnable run) {
        Bukkit.getScheduler().runTaskLater(plugin, run, delay);
    }

    private String name(UUID id) {
        if (id == null) return "???";
        String n = Bukkit.getOfflinePlayer(id).getName();
        return n == null ? "???" : n;
    }

    public void send(Game g, String mini) {
        Component message = MineUnoPlugin.mm(mini);
        for (UUID id : g.hands.keySet()) {
            if (g.away.contains(id)) continue;
            Player p = Bukkit.getPlayer(id);
            if (p != null) p.sendMessage(message);
        }
    }

    private String scoreLine(Game g) {
        List<String> parts = new ArrayList<>();
        for (UUID id : g.hands.keySet()) parts.add(name(id) + " " + g.scores.getOrDefault(id, 0));
        return String.join(" <dark_gray>| <white>", parts);
    }

    // ---------- 游戏事件 -> 表现 ----------

    /** 轮到某人的提示（标题 + 音效）。 */
    private void notifyTurn(Game g, UUID player) {
        if (!games.containsKey(g.id)) return;
        Player p = Bukkit.getPlayer(player);
        if (p == null) return;
        p.playSound(p.getLocation(), Sound.UI_TOAST_CHALLENGE_COMPLETE, 0.8f, 1.5f);
        if (plugin.cfg("turn.title", true)) {
            p.showTitle(Title.title(MineUnoPlugin.mm("<gold><bold>▶ 你的回合"),
                    MineUnoPlugin.mm("<white>准星选牌 · 左键出牌 · 右键摸牌"), 5, 25, 8));
        }
    }

    private Game.Events events(Game g) {
        return new Game.Events() {
            @Override
            public void onDeal() {
                tableOf(g).deal(() -> {
                    if (games.containsKey(g.id) && g.phase == Game.Phase.DEALING) g.beginPlay();
                });
            }

            @Override
            public void onPlay(UUID player, Card card) {
                tableOf(g).onPlay(player, card);
                Player p = Bukkit.getPlayer(player);
                if (card.wild() && p != null) plugin.menus().openColor(p, g);
                plugin.menus().refresh(Bukkit.getPlayer(player));
            }

            @Override
            public void onDraw(UUID player, int count, Card card) {
                tableOf(g).onDraw(player, count, card);
                Player p = Bukkit.getPlayer(player);
                if (count >= 2) {
                    if (p != null) {
                        p.showTitle(Title.title(MineUnoPlugin.mm("<red><bold>+" + count),
                                MineUnoPlugin.mm("<white>摸 " + count + " 张牌并跳过回合"), 5, 35, 10));
                    }
                    send(g, "<white>" + name(player) + " <red>摸了 " + count + " 张牌");
                } else {
                    send(g, "<white>" + name(player) + " <gray>摸了一张牌");
                }
                if (p != null && card != null) {
                    MineUnoPlugin.msg(p, "<gray>你摸到了 " + card.coloredName()
                            + (count > 1 ? " <gray>（共 " + count + " 张）" : ""));
                }
                plugin.menus().refresh(p);
            }

            @Override
            public void onSkip(UUID target) {
                tableOf(g).onSkip(target);
                Player p = Bukkit.getPlayer(target);
                if (p != null) {
                    p.playSound(p.getLocation(), Sound.BLOCK_ANVIL_LAND, 0.7f, 1.4f);
                    if (plugin.cfg("turn.title", true)) {
                        p.showTitle(Title.title(MineUnoPlugin.mm("<red><bold>你被跳过"),
                                MineUnoPlugin.mm("<gray>本轮失去出牌机会"), 3, 35, 10));
                    }
                }
            }

            @Override
            public void onReverse() {
                tableOf(g).onReverse();
                send(g, "<aqua>方向反转！");
            }

            @Override
            public void onColor(Card.Color color) {
                tableOf(g).onColor(color);
                send(g, "<white>颜色变为 <" + color.tag + ">" + color.cn);
                for (UUID id : g.hands.keySet()) plugin.menus().refresh(Bukkit.getPlayer(id));
            }

            @Override
            public void onTurn(UUID player) {
                tableOf(g).onTurn(player);
                if (tableOf(g).skipAnimating()) {
                    // 刚有人被跳过：等"被跳过"标识停留结束再提示下家
                    int delay = plugin.cfg("turn.skip-stay", 30);
                    schedule(delay, () -> notifyTurn(g, player));
                } else {
                    notifyTurn(g, player);
                }
                plugin.menus().refresh(Bukkit.getPlayer(player));
            }

            @Override
            public void onUno(UUID player, boolean safe) {
                tableOf(g).onUno(player, safe);
                if (safe) {
                    send(g, "<gold>" + name(player) + " 喊出了 UNO！");
                } else {
                    send(g, "<red>⚠ " + name(player) + " 忘记喊 UNO！ <click:run_command:'/uno catch " + name(player)
                            + "'><hover:show_text:'点我抓 UNO'><gold>[抓 UNO]</gold></hover></click>");
                }
            }

            @Override
            public void onCaught(UUID player, int count) {
                tableOf(g).onCaught(player, count);
                send(g, "<white>" + name(player) + " <red>被抓 UNO，罚摸 " + count + " 张");
            }

            @Override
            public void onChallenge(UUID offender, UUID challenger) {
                tableOf(g).onChallenge(offender, challenger);
                Player p = Bukkit.getPlayer(challenger);
                if (p != null) {
                    plugin.menus().openChallenge(p, g);
                    p.showTitle(Title.title(MineUnoPlugin.mm("<red><bold>+4!"),
                            MineUnoPlugin.mm("<white>" + name(offender) + " 对你使用了 +4 · 接受或质疑"), 5, 50, 10));
                }
                send(g, "<yellow>" + name(challenger) + " 正在决定是否质疑…");
            }

            @Override
            public void onChallengeResult(boolean success, UUID offender, UUID challenger) {
                tableOf(g).onChallengeResult(success, offender, challenger);
                send(g, success
                        ? "<green>质疑成功！<white>" + name(offender) + " <green>违规 +4，罚摸 4 张"
                        : "<red>质疑失败！<white>" + name(challenger) + " <red>罚摸 6 张");
            }

            @Override
            public void onRoundEnd(UUID winner) {
                tableOf(g).onRoundEnd(winner);
                if (winner != null) send(g, "<gold>" + name(winner) + " 赢得本局！");
                boolean matchOver = g.mode == Game.Mode.QUICK || g.scores.getOrDefault(winner, 0) >= 500;
                if (g.mode == Game.Mode.CLASSIC) {
                    send(g, "<gray>比分：<white>" + scoreLine(g));
                    if (!matchOver) send(g, "<gray>10 秒后开始下一局…");
                }
                if (!matchOver) {
                    schedule(200, () -> {
                        if (games.containsKey(g.id) && g.phase == Game.Phase.ROUND_END) g.startRound();
                    });
                }
            }

            @Override
            public void onMatchEnd(UUID winner) {
                tableOf(g).onMatchEnd(winner);
                if (winner == null) {
                    send(g, "<gray>牌局结束");
                } else {
                    for (UUID id : g.hands.keySet()) {
                        Player p = Bukkit.getPlayer(id);
                        if (p == null) continue;
                        p.showTitle(Title.title(
                                MineUnoPlugin.mm(id.equals(winner) ? "<gold><bold>你赢了！" : "<red><bold>游戏结束"),
                                MineUnoPlugin.mm("<white>" + name(winner) + " <gray>获胜"),
                                10, 60, 20));
                    }
                }
                schedule(100, () -> cleanup(g, true));
            }
        };
    }
}
