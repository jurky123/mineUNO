package com.mineuno;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.configuration.ConfigurationSection;

/** 竞技场坐标集合：每个正在进行的牌桌占用一个独立坐标，互不重叠。 */
public class Arena {

    public record Point(String world, double x, double y, double z) {}

    private final MineUnoPlugin plugin;
    private final List<Point> points = new ArrayList<>();
    private final Set<Integer> used = new HashSet<>();

    public Arena(MineUnoPlugin plugin) {
        this.plugin = plugin;
        load();
    }

    public void load() {
        points.clear();
        List<?> list = plugin.getConfig().getList("arenas");
        if (list != null) {
            for (Object entry : list) {
                if (entry instanceof ConfigurationSection section) {
                    points.add(new Point(section.getString("world", "hub"),
                            section.getDouble("x"), section.getDouble("y"), section.getDouble("z")));
                }
            }
        }
        if (points.isEmpty()) {
            // 兼容旧配置：arena: {world,x,y,z}
            var legacy = plugin.getConfig().getConfigurationSection("arena");
            if (legacy != null) {
                points.add(new Point(legacy.getString("world", "hub"),
                        legacy.getDouble("x"), legacy.getDouble("y"), legacy.getDouble("z")));
                plugin.getConfig().set("arena", null);
                saveAll();
            } else {
                points.add(new Point("hub", 0.5, 0.0, 0.5));
            }
        }
    }

    public int count() {
        return points.size();
    }

    public String name(int slot) {
        return points.get(slot).world() + " " + (int) points.get(slot).x() + "," + (int) points.get(slot).y()
                + "," + (int) points.get(slot).z();
    }

    /** 占用一个空闲竞技场，没有空闲返回 -1。 */
    public int allocate() {
        for (int i = 0; i < points.size(); i++) {
            if (!used.contains(i)) {
                used.add(i);
                return i;
            }
        }
        return -1;
    }

    public void release(int slot) {
        used.remove(slot);
    }

    public Location center(int slot) {
        Point point = points.get(Math.max(0, Math.min(points.size() - 1, slot)));
        World world = Bukkit.getWorld(point.world());
        if (world == null) world = Bukkit.getWorlds().get(0);
        return new Location(world, point.x(), point.y(), point.z());
    }

    public void save(int slot, Location location) {
        while (points.size() <= slot) {
            points.add(new Point(location.getWorld().getName(), location.getX(), location.getY(), location.getZ()));
        }
        points.set(slot, new Point(location.getWorld().getName(), location.getX(), location.getY(), location.getZ()));
        saveAll();
    }

    private void saveAll() {
        List<java.util.Map<String, Object>> list = new ArrayList<>();
        for (Point point : points) {
            list.add(java.util.Map.of("world", point.world(), "x", point.x(), "y", point.y(), "z", point.z()));
        }
        plugin.getConfig().set("arenas", list);
        plugin.saveConfig();
    }
}
