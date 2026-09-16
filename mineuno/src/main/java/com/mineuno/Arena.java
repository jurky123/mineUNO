package com.mineuno;

import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.World;

public class Arena {

    private final MineUnoPlugin plugin;
    public String world;
    public double x, y, z;

    public Arena(MineUnoPlugin plugin) {
        this.plugin = plugin;
        load();
    }

    public void load() {
        var c = plugin.getConfig();
        world = c.getString("arena.world", "hub");
        x = c.getDouble("arena.x", 8.5);
        y = c.getDouble("arena.y", 64.0);
        z = c.getDouble("arena.z", 3.5);
    }

    public void save(Location loc) {
        var c = plugin.getConfig();
        c.set("arena.world", loc.getWorld().getName());
        c.set("arena.x", loc.getX());
        c.set("arena.y", loc.getY());
        c.set("arena.z", loc.getZ());
        plugin.saveConfig();
        load();
    }

    public World world() {
        World w = Bukkit.getWorld(world);
        return w != null ? w : Bukkit.getWorlds().get(0);
    }

    public Location center() {
        return new Location(world(), x, y, z);
    }

    public boolean ready() {
        return Bukkit.getWorld(world) != null;
    }
}
