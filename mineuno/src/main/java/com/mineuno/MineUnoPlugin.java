package com.mineuno;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.MiniMessage;
import org.bukkit.Bukkit;
import org.bukkit.command.CommandSender;
import org.bukkit.plugin.java.JavaPlugin;

public final class MineUnoPlugin extends JavaPlugin {

    public static final MiniMessage MM = MiniMessage.miniMessage();
    private static MineUnoPlugin instance;

    private Arena arena;
    private Menus menus;
    private MatchManager matches;

    @Override
    public void onEnable() {
        instance = this;
        saveDefaultConfig();
        arena = new Arena(this);
        menus = new Menus(this);
        matches = new MatchManager(this);
        UnoCommand command = new UnoCommand(this);
        getCommand("uno").setExecutor(command);
        getCommand("uno").setTabCompleter(command);
        Bukkit.getPluginManager().registerEvents(new UnoListener(this), this);
        matches.start();
        getLogger().info("MineUNO 已启用，竞技场: " + arena.world + " " + arena.x + "," + arena.y + "," + arena.z);
    }

    @Override
    public void onDisable() {
        if (matches != null) matches.shutdown();
    }

    public static MineUnoPlugin instance() {
        return instance;
    }

    public Arena arena() {
        return arena;
    }

    public Menus menus() {
        return menus;
    }

    public MatchManager matches() {
        return matches;
    }

    public void reloadAll() {
        reloadConfig();
        arena.load();
    }

    public int cfg(String path, int def) {
        return getConfig().getInt(path, def);
    }

    public double cfg(String path, double def) {
        return getConfig().getDouble(path, def);
    }

    public boolean cfg(String path, boolean def) {
        return getConfig().getBoolean(path, def);
    }

    public static Component mm(String text) {
        return MM.deserialize(text);
    }

    public static void msg(CommandSender to, String text) {
        to.sendMessage(mm(text));
    }

    public void log(String text) {
        getLogger().info(text);
    }
}
