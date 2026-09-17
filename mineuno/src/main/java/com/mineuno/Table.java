package com.mineuno;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Consumer;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Particle;
import org.bukkit.Sound;
import org.bukkit.World;
import org.bukkit.entity.ArmorStand;
import org.bukkit.entity.Display;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Interaction;
import org.bukkit.entity.ItemDisplay;
import org.bukkit.entity.Player;
import org.bukkit.entity.TextDisplay;
import org.bukkit.inventory.ItemStack;
import org.bukkit.scheduler.BukkitTask;
import org.bukkit.util.Transformation;
import org.bukkit.util.Vector;
import org.joml.Quaternionf;
import org.joml.Vector3f;

/** 3D 桌面：座位、实体牌堆、动画、音效与粒子。 */
public class Table implements Game.Events {

    public record Hit(Table table, boolean draw) {}

    public static final Map<UUID, Hit> HITS = new HashMap<>();

    private final MineUnoPlugin plugin;
    private final Game game;
    private final World world;
    private final Location center;
    private final double surfaceY;
    private final double cardScale;
    private final double rx, rz;
    private final boolean animations, particles, sounds;

    private final Map<UUID, Seat> seats = new HashMap<>();
    private final Map<UUID, Hand> hands = new HashMap<>();
    private final Map<UUID, ArmorStand> seatsEntities = new HashMap<>();
    private final List<ItemDisplay> pileDisplays = new ArrayList<>();
    private final List<Entity> temp = new ArrayList<>();
    private final List<BukkitTask> tasks = new ArrayList<>();

    private final List<ItemDisplay> pile = new ArrayList<>();
    private ItemDisplay tableDisc, discardTop, colorDisc, dirDisc;
    private TextDisplay drawLabel, colorLabel, dirLabel;
    private Interaction drawHit, handHit;
    private final double lift;
    private double handDistance, handHeight, handSpacing, handScale, handTilt, handYawOffset, handYawSpread;
    private final int handMax, rowsMax, pileShow;
    private final double rowGap, rowOffset, pileSpread, pileAngle;
    private TurnMarker marker;
    private UUID pendingTurn;
    private boolean skipAnimating;
    private float dirYaw;

    private static class Seat {
        Location stand;
        Location base;
        Location handBase;
        Vector tangent;
        Vector inward;
        float yaw;
        float handYaw;
        ItemDisplay[] backs = new ItemDisplay[5];
        TextDisplay count;
    }

    /** 玩家的 3D 手牌（只对自己可见）。 */
    private static class Hand {
        ItemDisplay[] cards = new ItemDisplay[0];
        final List<Card> shown = new ArrayList<>();
        int selected = -1;
    }

    public Table(MineUnoPlugin plugin, Game game) {
        this.plugin = plugin;
        this.game = game;
        this.world = plugin.arena().world();
        this.center = plugin.arena().center();
        this.surfaceY = plugin.cfg("table.height", 1.05);
        this.cardScale = plugin.cfg("table.card-scale", 1.5);
        this.rx = plugin.cfg("table.seat-radius-x", 3.8);
        this.rz = plugin.cfg("table.seat-radius-z", 3.0);
        this.animations = plugin.cfg("visual.animations", true);
        this.particles = plugin.cfg("visual.particles", true);
        this.sounds = plugin.cfg("visual.sounds", true);
        this.lift = plugin.cfg("table.lift", 0.125);
        this.handDistance = plugin.cfg("hand.distance", 1.30);
        this.handHeight = plugin.cfg("hand.height", 1.10);
        this.handSpacing = plugin.cfg("hand.spacing", 0.26);
        this.handScale = plugin.cfg("hand.scale", 0.72);
        this.handTilt = plugin.cfg("hand.tilt", -45);
        this.handYawOffset = plugin.cfg("hand.yaw-offset", 0);
        this.handYawSpread = plugin.cfg("hand.yaw-spread", 15);
        this.handMax = plugin.cfg("hand.max", 16);
        this.rowsMax = plugin.cfg("hand.rows-max", 8);
        this.rowGap = plugin.cfg("hand.row-gap", 0.34);
        this.rowOffset = plugin.cfg("hand.row-offset", 0.12);
        this.pileShow = plugin.cfg("pile.show", 12);
        this.pileSpread = plugin.cfg("pile.spread", 0.35);
        this.pileAngle = plugin.cfg("pile.angle", 20);
    }

    /** 热更新手牌参数（/uno hand 命令用）。 */
    public void reloadHand() {
        handDistance = plugin.cfg("hand.distance", 1.30);
        handHeight = plugin.cfg("hand.height", 1.10);
        handSpacing = plugin.cfg("hand.spacing", 0.26);
        handScale = plugin.cfg("hand.scale", 0.72);
        handTilt = plugin.cfg("hand.tilt", -45);
        handYawOffset = plugin.cfg("hand.yaw-offset", 0);
        handYawSpread = plugin.cfg("hand.yaw-spread", 7);
        for (Map.Entry<UUID, Seat> e : seats.entrySet()) {
            e.getValue().handBase = e.getValue().stand.clone()
                    .add(e.getValue().inward.clone().multiply(handDistance)).add(0, handHeight, 0);
            e.getValue().handBase.setYaw(0);
            e.getValue().handBase.setPitch(0);
            renderHand(e.getKey());
        }
    }

    public Game game() {
        return game;
    }

    // ---------- 几何 ----------

    /** 水平面内绕 Y 轴旋转（右手系，与 yaw 增量一致）。 */
    private Vector rotateY(Vector vector, double angle) {
        double cos = Math.cos(angle), sin = Math.sin(angle);
        return new Vector(vector.getX() * cos + vector.getZ() * sin, 0, -vector.getX() * sin + vector.getZ() * cos);
    }

    /** 玩家手牌扇形的圆心（第 0 排）。 */
    private Location handCenter(Seat s) {
        Location at = s.stand.clone().add(s.inward.clone().multiply(handDistance)).add(0, handHeight, 0);
        at.setYaw(0);
        at.setPitch(0);
        return at;
    }

    private Location headLocation(Seat s) {
        return s.stand.clone().add(0, 2.35, 0);
    }

    private Location surface(double dx, double dz) {
        return center.clone().add(dx, surfaceY, dz);
    }

    private Location onFloor(double dx, double dz) {
        return center.clone().add(dx, 0, dz);
    }

    private Location seatSurface(Seat s) {
        return s.base;
    }

    public Location stand(UUID player) {
        Seat s = seats.get(player);
        return s == null ? null : s.stand.clone();
    }

    /** 隐形坐骑：玩家骑上去就是原版坐姿，且无法走动。 */
    private ArmorStand spawnSeat(Location stand) {
        Location at = stand.clone().add(0, plugin.cfg("seat.y-offset", -0.9), 0);
        return world.spawn(at, ArmorStand.class, a -> {
            a.setVisible(false);
            a.setMarker(true);
            a.setGravity(false);
            a.setInvulnerable(true);
            a.setPersistent(false);
            a.setSilent(true);
            a.setBasePlate(false);
            a.setRotation(stand.getYaw(), 0);
        });
    }

    /** 调整坐骑高度（/uno seat 用）。 */
    public void reloadSeat() {
        for (Map.Entry<UUID, ArmorStand> e : seatsEntities.entrySet()) {
            Seat seat = seats.get(e.getKey());
            ArmorStand a = e.getValue();
            if (seat == null || !a.isValid()) continue;
            Location at = seat.stand.clone().add(0, plugin.cfg("seat.y-offset", -0.9), 0);
            at.setYaw(seat.stand.getYaw());
            at.setPitch(0);
            a.teleport(at);
        }
    }

    /** 让玩家坐上隐形座位。 */
    public void mount(UUID player) {
        ArmorStand seatEntity = seatsEntities.get(player);
        Player p = Bukkit.getPlayer(player);
        if (seatEntity == null || !seatEntity.isValid() || p == null) return;
        if (!seatEntity.getPassengers().contains(p)) seatEntity.addPassenger(p);
    }

    /** 玩家偏离座位就拉回来（保留视角朝向）。 */
    public void keepSeat(UUID player) {
        Seat seat = seats.get(player);
        Player p = Bukkit.getPlayer(player);
        if (seat == null || p == null) return;
        double radius = plugin.cfg("game.seat-lock-radius", 0.6);
        ArmorStand seatEntity = seatsEntities.get(player);
        if (seatEntity == null || !seatEntity.isValid()) return;
        if (seatEntity.getWorld() != seat.stand.getWorld()
                || seatEntity.getLocation().distanceSquared(seat.stand) > radius * radius) {
            Location at = seat.stand.clone().add(0, plugin.cfg("seat.y-offset", -0.9), 0);
            at.setYaw(seat.stand.getYaw());
            at.setPitch(0);
            seatEntity.teleport(at);
        }
        if (!seatEntity.getPassengers().contains(p)) seatEntity.addPassenger(p);
    }

    public List<UUID> seatOwners() {
        return new ArrayList<>(seats.keySet());
    }

    // ---------- 实体工具 ----------

    private <T extends Entity> T spawn(Location loc, Class<T> type, Consumer<T> init) {
        return world.spawn(loc, type, e -> {
            e.setPersistent(false);
            e.setInvulnerable(true);
            e.setGravity(false);
            e.setSilent(true);
            init.accept(e);
        });
    }

    /**
     * item/generated 的 GROUND 变换是竖直面片（rotation 0, translation [0,2,0], scale 0.5），
     * 这里把它放平（绕 X -90°）、按 yaw 在平面内旋转，并抵消模型自带的 2/16 抬高。
     */
    private Transformation transform(double scale, float yaw) {
        float s = (float) scale;
        Quaternionf rot = new Quaternionf()
                .rotateX((float) -Math.PI / 2)
                .rotateZ((float) Math.toRadians(yaw));
        Vector3f offset = rot.transform(new Vector3f(0f, (float) (lift * scale), 0f));
        return new Transformation(offset.negate(), rot, new Vector3f(s, s, s), new Quaternionf());
    }

    private ItemDisplay display(ItemStack item, Location surfacePoint, double scale, float yaw) {
        return spawn(surfacePoint, ItemDisplay.class, d -> {
            d.setItemStack(item);
            d.setItemDisplayTransform(ItemDisplay.ItemDisplayTransform.GROUND);
            d.setTransformation(transform(scale, yaw));
            d.setBrightness(new Display.Brightness(15, 15));
            d.setShadowRadius(0f);
            d.setShadowStrength(0f);
            d.setViewRange(1.5f);
        });
    }

    // ---------- 手牌（3D 实体，仅本人可见） ----------

    /** 手牌保持竖直面片正对玩家，tilt 为向后仰的角度。 */
    private Transformation transformHand(double scale, float yaw, float tilt, boolean selected) {
        float s = (float) (scale * (selected ? 1.15 : 1.0));
        Quaternionf rot = new Quaternionf()
                .rotateY((float) Math.toRadians(yaw))
                .rotateX((float) Math.toRadians(tilt));
        Vector3f offset = rot.transform(new Vector3f(0f, (float) (lift * s), 0f));
        return new Transformation(offset.negate(), rot, new Vector3f(s, s, s), new Quaternionf());
    }

    private ItemDisplay handDisplay(Location at) {
        at = at.clone();
        at.setYaw(0);
        at.setPitch(0);
        Location finalAt = at;
        return spawn(finalAt, ItemDisplay.class, d -> {
            d.setItemStack(Card.back());
            d.setItemDisplayTransform(ItemDisplay.ItemDisplayTransform.GROUND);
            d.setTransformation(transformHand(0.01, 0, (float) handTilt, false));
            d.setBrightness(new Display.Brightness(15, 15));
            d.setShadowRadius(0f);
            d.setShadowStrength(0f);
            d.setViewRange(1.2f);
        });
    }

    private void makePrivate(Entity entity, UUID owner) {
        for (Player p : Bukkit.getOnlinePlayers()) {
            if (!p.getUniqueId().equals(owner)) p.hideEntity(plugin, entity);
        }
    }

    /** 新玩家进服时，隐藏其他人的手牌。 */
    public void hidePrivateFrom(Player player) {
        for (Map.Entry<UUID, Hand> e : hands.entrySet()) {
            if (e.getKey().equals(player.getUniqueId())) continue;
            for (ItemDisplay d : e.getValue().cards) if (d.isValid()) player.hideEntity(plugin, d);
        }
    }

    private void buildHands() {
        for (Map.Entry<UUID, Seat> e : seats.entrySet()) {
            Hand h = new Hand();
            h.cards = new ItemDisplay[handMax];
            for (int i = 0; i < handMax; i++) {
                ItemDisplay d = handDisplay(e.getValue().handBase);
                h.cards[i] = d;
                makePrivate(d, e.getKey());
            }
            hands.put(e.getKey(), h);
            renderHand(e.getKey());
        }
    }

    public void renderHand(UUID player) {
        Hand h = hands.get(player);
        Seat s = seats.get(player);
        if (h == null || s == null) return;
        List<Card> cards = new ArrayList<>(game.hand(player));
        cards.sort(Comparator.comparingInt((Card c) -> c.color().ordinal())
                .thenComparingInt(c -> c.type() == Card.Type.NUMBER ? c.number() : 100));
        h.shown.clear();
        h.shown.addAll(cards);
        int n = Math.min(cards.size(), handMax);
        List<HandLayout.Slot> slots = HandLayout.layout(n, rowsMax);
        if (h.selected >= n) h.selected = -1;
        for (int i = 0; i < h.cards.length; i++) {
            ItemDisplay d = h.cards[i];
            if (!d.isValid()) continue;
            if (i >= n) {
                d.setTransformation(transformHand(0.01, 0, (float) handTilt, false));
                continue;
            }
            Card card = cards.get(i);
            boolean selected = i == h.selected;
            HandLayout.Slot slot = slots.get(i);
            double angle = HandLayout.angle(slot.indexInRow(), slot.rowSize(), handYawSpread);
            Location pivot = s.stand.clone().add(0, handHeight + slot.row() * rowGap, 0);
            Location loc = pivot.add(rotateY(s.inward, Math.toRadians(angle)).multiply(handDistance + slot.row() * rowOffset));
            loc.setYaw(0);
            loc.setPitch(0);
            d.setItemStack(card.item(game.canPlay(player, card)));
            d.setTransformation(transformHand(handScale,
                    s.handYaw + (float) handYawOffset + (float) angle, (float) handTilt, selected));
            d.setTeleportDuration(4);
            if (selected) loc.add(s.inward.clone().multiply(-0.24)).add(0, 0.10, 0);
            d.teleport(loc);
        }
    }

    /** 每 2 tick 更新一次选中牌：只有准星真的落在某张牌上（视线到牌心很近）才高亮。 */
    public void aim(Player player) {
        Hand h = hands.get(player.getUniqueId());
        if (h == null || h.shown.isEmpty()) return;
        int n = Math.min(h.shown.size(), h.cards.length);
        Location eye = player.getEyeLocation();
        Vector dir = eye.getDirection();
        int best = -1;
        double bestPerp = Double.MAX_VALUE;
        double radius = plugin.cfg("hand.aim-radius", 0.22);
        for (int i = 0; i < n; i++) {
            ItemDisplay d = h.cards[i];
            if (!d.isValid() || d.getWorld() != eye.getWorld()) continue;
            Vector to = d.getLocation().toVector().subtract(eye.toVector());
            double depth = to.dot(dir);
            if (depth <= 0.05 || depth > 4) continue;
            Vector closest = dir.clone().multiply(depth);
            double perp = closest.subtract(to).length();
            if (perp <= radius && perp < bestPerp) {
                bestPerp = perp;
                best = i;
            }
        }
        if (best != h.selected) {
            h.selected = best;
            renderHand(player.getUniqueId());
        }
    }

    public int selectedCard(UUID player) {
        Hand h = hands.get(player);
        if (h == null || h.selected < 0 || h.selected >= h.shown.size()) return -1;
        return h.shown.get(h.selected).id();
    }

    public Location handBase(UUID player) {
        Seat s = seats.get(player);
        return s == null ? null : s.handBase.clone();
    }

    private TextDisplay text(Location at, String mini, float scale) {
        return spawn(at, TextDisplay.class, t -> {
            t.text(MineUnoPlugin.mm(mini));
            t.setBillboard(Display.Billboard.CENTER);
            t.setSeeThrough(true);
            t.setDefaultBackground(false);
            t.setBackgroundColor(org.bukkit.Color.fromARGB(0x55000000));
            t.setShadowed(true);
            t.setAlignment(TextDisplay.TextAlignment.CENTER);
            t.setTransformation(new Transformation(new Vector3f(), new Quaternionf(),
                    new Vector3f(scale, scale, scale), new Quaternionf()));
            t.setViewRange(1.2f);
        });
    }

    private Interaction hitbox(Location at, float width, float height) {
        return spawn(at, Interaction.class, i -> {
            i.setInteractionWidth(width);
            i.setInteractionHeight(height);
            i.setResponsive(true);
        });
    }

    private void schedule(int delay, Runnable run) {
        tasks.add(Bukkit.getScheduler().runTaskLater(plugin, run, delay));
    }

    private void sound(Sound sound, Location at, float volume, float pitch) {
        if (sounds) world.playSound(at, sound, volume, pitch);
    }

    private void dust(Location at, org.bukkit.Color color, int count, double spread) {
        if (!particles) return;
        world.spawnParticle(Particle.DUST, at, count, spread, 0.15, spread, 0, new Particle.DustOptions(color, 1.1f));
    }

    private void dust(Location at, Card.Color color, int count) {
        dust(at, rgb(color), count, 0.6);
    }

    private static org.bukkit.Color rgb(Card.Color color) {
        return switch (color) {
            case RED -> org.bukkit.Color.fromRGB(0xFF5555);
            case YELLOW -> org.bukkit.Color.fromRGB(0xFFAA00);
            case GREEN -> org.bukkit.Color.fromRGB(0x55FF55);
            case BLUE -> org.bukkit.Color.fromRGB(0x5599FF);
            case WILD -> org.bukkit.Color.fromRGB(0xFF55FF);
        };
    }

    /** 两段弧线飞行：起点 -> 中点抬高 -> 终点。 */
    private void fly(ItemStack item, Location from, Location to, int ticks) {
        if (!animations) return;
        ItemDisplay d = display(item, from, cardScale, 0);
        temp.add(d);
        Location mid = from.clone().add(to).multiply(0.5).add(0, 0.45, 0);
        int half = Math.max(1, ticks / 2);
        d.setTeleportDuration(half);
        schedule(1, () -> d.teleport(mid));
        schedule(half + 1, () -> d.teleport(to));
        schedule(ticks + 3, d::remove);
    }

    // ---------- 构建 ----------

    public void build() {
        tableDisc = display(Card.icon("table", "<gold>UNO 桌面"), surface(0, 0), plugin.cfg("table.size", 4.6) * 2, 0);

        for (int i = 0; i < 3; i++) {
            pile.add(display(Card.back(), surface(-1.05, 0).add(0, 0.006 * i, 0), cardScale, 0));
        }
        drawLabel = text(surface(-1.05, 0).add(0, 0.5, 0), "<white>牌堆", 0.55f);

        discardTop = display(Card.back(), surface(1.05, 0), cardScale, 0);
        discardTop.setTransformation(transform(0.01, 0));
        for (int i = 0; i < pileShow; i++) {
            pileDisplays.add(display(Card.back(), discardSpot(), 0.01, 0));
        }

        colorDisc = display(Card.icon("color/red", "<white>当前颜色"), surface(0, 0.75), 1.1, 0);
        colorLabel = text(surface(0, 0.75).add(0, 0.45, 0), "<white>当前颜色", 0.5f);
        dirDisc = display(Card.icon("dir/cw", "<white>方向"), surface(0, -0.75), 1.1, 0);
        dirLabel = text(surface(0, -0.75).add(0, 0.45, 0), "<white>方向：顺时针", 0.5f);

        drawHit = hitbox(surface(-1.15, 0).add(0, 0.35, 0), 3.0f, 1.4f);
        HITS.put(drawHit.getUniqueId(), new Hit(this, true));
        handHit = hitbox(surface(1.15, 0).add(0, 0.35, 0), 3.0f, 1.4f);
        HITS.put(handHit.getUniqueId(), new Hit(this, false));

        marker = new TurnMarker(plugin, world);
        buildSeats();
        buildHands();
        update();
    }

    private void buildSeats() {
        List<UUID> players = game.order();
        int n = players.size();
        for (int i = 0; i < n; i++) {
            UUID p = players.get(i);
            double angle = Math.PI * 2 * i / n;
            double cos = Math.cos(angle), sin = Math.sin(angle);
            Seat s = new Seat();
            s.stand = onFloor(rx * cos, rz * sin);
            s.stand.setDirection(center.toVector().subtract(s.stand.toVector()).setY(0));
            double ix = rx * 0.56, iz = rz * 0.56;
            s.base = surface(ix * cos, iz * sin);
            s.tangent = new Vector(-sin, 0, cos);
            s.yaw = (float) Math.toDegrees(Math.atan2(cos, sin));
            s.inward = center.toVector().subtract(s.stand.toVector()).setY(0).normalize();
            s.handBase = s.stand.clone().add(s.inward.clone().multiply(handDistance)).add(0, handHeight, 0);
            s.handBase.setYaw(0);
            s.handBase.setPitch(0);
            s.handYaw = (float) Math.toDegrees(Math.atan2(-s.inward.getX(), -s.inward.getZ()));
            seatsEntities.put(p, spawnSeat(s.stand));

            for (int k = 0; k < 5; k++) {
                s.backs[k] = display(Card.back(), s.base, 0.01, 0);
            }
            double ox = cos * (ix + 0.62), oz = sin * (iz + 0.62);
            s.count = text(surface(ox, oz).add(0, 0.28, 0), "<white>×0", 0.7f);
            seats.put(p, s);
        }
    }

    private String name(UUID player) {
        String name = Bukkit.getOfflinePlayer(player).getName();
        if (name == null) name = player.toString().substring(0, 8);
        return game.away.contains(player) ? "<gray>" + name + "（离线）" : name;
    }

    // ---------- 刷新 ----------

    public void update() {
        updateHands(5);
        updateDiscard();
        updateColor();
        updateDir();
        for (UUID p : seats.keySet()) renderHand(p);
    }

    private void updateHands(int cap) {
        for (Map.Entry<UUID, Seat> e : seats.entrySet()) {
            int hand = game.hand(e.getKey()).size();
            Seat s = e.getValue();
            int shown = Math.min(5, Math.min(hand, cap));
            for (int k = 0; k < 5; k++) {
                boolean visible = k < shown;
                double offset = k - (shown - 1) / 2.0;
                s.backs[k].setTransformation(transform(visible ? cardScale : 0.01, s.yaw + (float) (offset * 5)));
                if (visible) {
                    s.backs[k].teleport(seatSurface(s).clone().add(s.tangent.clone().multiply(offset * 0.32)).add(0, 0.006 * k, 0));
                }
            }
            s.count.text(MineUnoPlugin.mm((hand == 1 ? "<red>" : "<white>") + "×" + hand));
        }
    }

    private void updateDiscard() {
        int shown = Math.min(pileDisplays.size(), game.discard.size());
        for (int i = 0; i < pileDisplays.size(); i++) {
            ItemDisplay d = pileDisplays.get(i);
            if (i >= shown) {
                d.setTransformation(transform(0.01, 0));
                continue;
            }
            Card card = game.discard.get(game.discard.size() - 1 - i);
            PileLayout.Spot spot = PileLayout.spot(card, pileSpread, pileAngle);
            d.setItemStack(card.item());
            d.setTransformation(transform(cardScale, spot.yaw()));
            d.teleport(discardSpot().clone().add(spot.dx(), 0.004 + (shown - 1 - i) * 0.004, spot.dz()));
        }
        discardTop.setTransformation(transform(0.01, 0));
        int layers = Math.min(pile.size(), 1 + game.draw.size() / 10);
        for (int i = 0; i < pile.size(); i++) {
            ItemDisplay d = pile.get(i);
            if (i >= layers) {
                d.setTransformation(transform(0.01, 0));
                continue;
            }
            d.setTransformation(transform(cardScale, 0));
            d.teleport(surface(-1.05, 0).add(0, 0.006 * i, 0));
        }
        drawLabel.text(MineUnoPlugin.mm("<white>牌堆 <gray>×" + game.draw.size()));
    }

    private void updateColor() {
        colorDisc.setItemStack(Card.icon("color/" + game.activeColor.tag, "<white>当前颜色"));
        colorLabel.text(MineUnoPlugin.mm("<" + game.activeColor.tag + ">当前颜色：" + game.activeColor.cn));
    }

    private void updateDir() {
        boolean cw = game.dir > 0;
        dirDisc.setItemStack(Card.icon("dir/" + (cw ? "cw" : "ccw"), "<white>方向"));
        dirLabel.text(MineUnoPlugin.mm("<white>方向：" + (cw ? "顺时针" : "逆时针")));
    }

    // ---------- 动画入口 ----------

    public void deal() {
        updateDiscard();
        updateColor();
        updateDir();
        updateHands(0);
        int cards = game.startingCards;
        for (int r = 0; r < cards; r++) {
            for (UUID p : game.order()) {
                int delay = r * 3;
                Seat s = seats.get(p);
                if (s == null) continue;
                schedule(delay, () -> fly(Card.back(), surface(-1.05, 0), seatSurface(s), 5));
            }
            int round = r + 1;
            schedule(r * 3 + 5, () -> {
                sound(Sound.ITEM_BOOK_PAGE_TURN, surface(-1.05, 0), 0.5f, 1.5f);
                updateHands(round);
            });
        }
        schedule(cards * 3 + 12, () -> {
            sound(Sound.BLOCK_LEVER_CLICK, surface(1.05, 0), 0.8f, 0.8f);
            dust(surface(1.05, 0), game.activeColor, 20);
            update();
            game.beginPlay();
        });
    }

    @Override
    public void onPlay(UUID player, Card card) {
        if (!card.wild()) updateColor();
        Seat s = seats.get(player);
        if (s == null) return;
        sound(Sound.ITEM_BOOK_PAGE_TURN, discardSpot(), 0.7f, 1.1f);
        dust(discardSpot(), card.wild() ? game.activeColor : card.color(), 10);
        if (animations) {
            PileLayout.Spot spot = PileLayout.spot(card, pileSpread, pileAngle);
            int shown = Math.min(pileDisplays.size(), game.discard.size());
            Location to = discardSpot().clone().add(spot.dx(), 0.004 + Math.max(0, shown - 1) * 0.004, spot.dz());
            Location from = handCenter(s);
            Location mid = from.clone().add(to).multiply(0.5).add(0, 0.5, 0);
            discardTop.setItemStack(card.item());
            discardTop.setTransformation(transform(cardScale, spot.yaw()));
            discardTop.teleport(from);
            discardTop.setTeleportDuration(3);
            schedule(1, () -> discardTop.teleport(mid));
            schedule(4, () -> discardTop.teleport(to));
            schedule(11, () -> {
                updateDiscard();
                renderHand(player);
            });
        } else {
            updateDiscard();
            renderHand(player);
        }
    }

    private Location discardSpot() {
        return surface(1.05, 0);
    }

    @Override
    public void onDraw(UUID player, int count, Card card) {
        Seat s = seats.get(player);
        if (s == null) return;
        Location to = handCenter(s);
        for (int i = 0; i < count && i < 8; i++) {
            schedule(i * 3, () -> fly(Card.back(), surface(-1.05, 0), to, 6));
        }
        schedule(count * 3, () -> sound(Sound.ITEM_BOOK_PAGE_TURN, to, 0.5f, 1.7f));
        schedule(count * 3 + 8, () -> {
            updateHands(5);
            updateDiscard();
            renderHand(player);
        });
    }

    @Override
    public void onSkip(UUID target) {
        Seat s = seats.get(target);
        Location at = s != null ? s.base : center;
        dust(at, org.bukkit.Color.fromRGB(0xFF5555), 15, 0.5);
        sound(Sound.BLOCK_NOTE_BLOCK_BASEDRUM, at, 0.7f, 1.4f);
        if (s == null || marker == null) return;
        // 标识先到被跳过者头顶停留，再移动到下一个人
        skipAnimating = true;
        marker.show(headLocation(s), "<red><bold>⊘ " + name(target) + " 被跳过", 4);
        schedule(18, () -> {
            skipAnimating = false;
            if (pendingTurn != null) {
                showTurn(pendingTurn);
                pendingTurn = null;
            }
        });
    }

    private void showTurn(UUID player) {
        Seat s = seats.get(player);
        if (s == null || marker == null) return;
        marker.show(headLocation(s), "<gold><bold>▶ <" + game.activeColor.tag + ">" + name(player) + " <gold>的回合", 6);
    }

    @Override
    public void onReverse() {
        dirYaw += 180;
        dirDisc.setInterpolationDuration(6);
        dirDisc.setTransformation(transform(1.1, dirYaw));
        sound(Sound.ITEM_TRIDENT_RIPTIDE_2, surface(0, -0.75), 0.6f, 1.6f);
        updateDir();
    }

    @Override
    public void onColor(Card.Color color) {
        updateColor();
        sound(Sound.BLOCK_BEACON_POWER_SELECT, surface(0, 0.75), 0.8f, 1.5f);
        if (particles) {
            for (int i = 0; i < 24; i++) {
                double a = Math.PI * 2 * i / 24;
                world.spawnParticle(Particle.DUST, surface(Math.cos(a) * 2.4, Math.sin(a) * 2.4), 1, 0, 0, 0, 0,
                        new Particle.DustOptions(rgb(color), 1.4f));
            }
        }
    }

    @Override
    public void onTurn(UUID player) {
        Seat s = seats.get(player);
        if (s != null) {
            dust(s.base, org.bukkit.Color.WHITE, 8, 0.45);
            sound(Sound.BLOCK_NOTE_BLOCK_PLING, s.base, 0.4f, 1.8f);
            for (UUID p : seats.keySet()) renderHand(p);
            if (skipAnimating) pendingTurn = player;
            else showTurn(player);
        }
    }

    @Override
    public void onUno(UUID player, boolean safe) {
        Seat s = seats.get(player);
        Location at = s != null ? s.base : center;
        sound(Sound.ENTITY_EXPERIENCE_ORB_PICKUP, at, 0.9f, safe ? 1.4f : 0.6f);
        if (s != null) seatText(s, safe ? "<gold><bold>UNO!" : "<red>忘喊 UNO!", 30);
    }

    @Override
    public void onCaught(UUID player, int count) {
        Seat s = seats.get(player);
        Location at = s != null ? s.base : center;
        dust(at, org.bukkit.Color.fromRGB(0xFF5555), 20, 0.6);
        sound(Sound.ENTITY_ITEM_PICKUP, at, 0.8f, 0.6f);
        if (s != null) seatText(s, "<red>被抓 UNO +2", 30);
    }

    @Override
    public void onChallenge(UUID offender, UUID challenger) {
        sound(Sound.BLOCK_NOTE_BLOCK_PLING, center.clone().add(0, surfaceY, 0), 0.9f, 0.7f);
        centerText("<yellow>对 <white>" + name(challenger) + " <yellow>的 +4 质疑中…", 40);
    }

    @Override
    public void onChallengeResult(boolean success, UUID offender, UUID challenger) {
        centerText(success ? "<green><bold>质疑成功！" : "<red><bold>质疑失败！", 40);
        sound(success ? Sound.ENTITY_PLAYER_LEVELUP : Sound.BLOCK_ANVIL_LAND, center.clone().add(0, surfaceY, 0), 1f, 1f);
        Seat s = seats.get(success ? offender : challenger);
        if (s != null) dust(s.base, org.bukkit.Color.fromRGB(success ? 0x55FF55 : 0xFF5555), 25, 0.7);
    }

    @Override
    public void onRoundEnd(UUID winner) {
        if (marker != null) marker.hide();
        centerText(winner != null ? "<gold><bold>" + name(winner) + " 获胜！" : "<gray>本局结束", 60);
    }

    @Override
    public void onMatchEnd(UUID winner) {
        if (winner == null) return;
        Seat s = seats.get(winner);
        Location at = s != null ? s.base.clone().add(0, 0.6, 0) : center.clone().add(0, surfaceY + 0.6, 0);
        if (particles) {
            world.spawnParticle(Particle.FIREWORK, at, 60, 1.2, 0.8, 1.2, 0.15);
            world.spawnParticle(Particle.TOTEM_OF_UNDYING, at, 40, 1, 0.8, 1, 0.2);
        }
        sound(Sound.ENTITY_FIREWORK_ROCKET_BLAST, at, 1f, 1f);
        sound(Sound.UI_TOAST_CHALLENGE_COMPLETE, at, 1f, 1f);
    }

    private void seatText(Seat s, String mini, int ticks) {
        TextDisplay t = text(s.base.clone().add(0, 0.95, 0), mini, 0.9f);
        temp.add(t);
        t.setInterpolationDuration(4);
        t.setTransformation(new Transformation(new Vector3f(), new Quaternionf(), new Vector3f(0.01f), new Quaternionf()));
        schedule(1, () -> t.setTransformation(new Transformation(new Vector3f(0, 0.2f, 0), new Quaternionf(),
                new Vector3f(1.25f), new Quaternionf())));
        schedule(ticks, t::remove);
    }

    private void centerText(String mini, int ticks) {
        TextDisplay t = text(center.clone().add(0, surfaceY + 0.85, 0), mini, 1.0f);
        temp.add(t);
        t.setInterpolationDuration(4);
        t.setTransformation(new Transformation(new Vector3f(), new Quaternionf(), new Vector3f(0.01f), new Quaternionf()));
        schedule(1, () -> t.setTransformation(new Transformation(new Vector3f(0, 0.25f, 0), new Quaternionf(),
                new Vector3f(1.4f), new Quaternionf())));
        schedule(ticks, t::remove);
    }

    // ---------- 清理 ----------

    public void remove() {
        for (BukkitTask t : tasks) t.cancel();
        tasks.clear();
        for (Entity e : temp) if (e.isValid()) e.remove();
        temp.clear();
        for (Seat s : seats.values()) {
            for (ItemDisplay d : s.backs) if (d.isValid()) d.remove();
            if (s.count.isValid()) s.count.remove();
        }
        seats.clear();
        for (Hand h : hands.values()) {
            for (ItemDisplay d : h.cards) if (d.isValid()) d.remove();
        }
        hands.clear();
        for (ItemDisplay d : pileDisplays) if (d.isValid()) d.remove();
        pileDisplays.clear();
        if (marker != null) marker.remove();
        for (ArmorStand a : seatsEntities.values()) if (a.isValid()) a.remove();
        seatsEntities.clear();
        for (Entity e : new Entity[]{tableDisc, discardTop, colorDisc, dirDisc, drawLabel, colorLabel, dirLabel, drawHit, handHit}) {
            if (e != null && e.isValid()) e.remove();
        }
        for (ItemDisplay d : pile) if (d.isValid()) d.remove();
        pile.clear();
        HITS.entrySet().removeIf(en -> en.getValue().table() == this);
    }
}
