package com.mineuno.packhost;

import com.sun.net.httpserver.HttpServer;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.NetworkInterface;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.Enumeration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Executors;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import java.util.zip.ZipOutputStream;
import net.kyori.adventure.text.minimessage.MiniMessage;
import org.bukkit.Bukkit;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerResourcePackStatusEvent;
import org.bukkit.plugin.java.JavaPlugin;

/** 通用资源包管理：合并 packs/ 下的所有 zip，通过内置 HTTP 服务下发给客户端。 */
public final class PackHostPlugin extends JavaPlugin implements Listener, CommandExecutor, TabCompleter {

    private HttpServer server;
    private File output;
    private byte[] hash = new byte[0];
    private String url = "";

    @Override
    public void onEnable() {
        saveDefaultConfig();
        rebuild();
        startServer();
        getCommand("packhost").setExecutor(this);
        getCommand("packhost").setTabCompleter(this);
        Bukkit.getPluginManager().registerEvents(this, this);
        getLogger().info("PackHost 已启用，材质包地址: " + url);
    }

    @Override
    public void onDisable() {
        stopServer();
    }

    // ---------- 合并资源包 ----------

    public void rebuild() {
        File packsDir = new File(getDataFolder(), "packs");
        File looseDir = new File(getDataFolder(), "pack");
        packsDir.mkdirs();
        looseDir.mkdirs();
        output = new File(getDataFolder(), "output/pack.zip");
        output.getParentFile().mkdirs();

        Map<String, byte[]> entries = new LinkedHashMap<>();
        addDirectory(looseDir, entries);
        File[] zips = packsDir.listFiles((dir, name) -> name.toLowerCase().endsWith(".zip"));
        if (zips != null) {
            Arrays.sort(zips, Comparator.comparing(File::getName));
            for (File zip : zips) addZip(zip, entries);
        }
        entries.putIfAbsent("pack.mcmeta", defaultMeta());

        try (ZipOutputStream out = new ZipOutputStream(new FileOutputStream(output))) {
            for (Map.Entry<String, byte[]> e : entries.entrySet()) {
                ZipEntry entry = new ZipEntry(e.getKey());
                entry.setTime(0);
                out.putNextEntry(entry);
                out.write(e.getValue());
                out.closeEntry();
            }
        } catch (IOException e) {
            getLogger().severe("合并资源包失败: " + e.getMessage());
            return;
        }
        hash = sha1(output);
        url = "http://" + address() + ":" + getConfig().getInt("port", 8123) + "/pack.zip";
        getLogger().info("已合并 " + entries.size() + " 个文件 -> " + output.getName() + " (" + output.length() / 1024 + " KB)");
    }

    private byte[] defaultMeta() {
        return ("{\"pack\":{\"pack_format\":88,\"supported_formats\":{\"min_inclusive\":1,\"max_inclusive\":999},"
                + "\"description\":\"PackHost\"}}").getBytes(StandardCharsets.UTF_8);
    }

    private void addDirectory(File dir, Map<String, byte[]> entries) {
        File[] files = dir.listFiles();
        if (files == null) return;
        for (File f : files) {
            if (f.isDirectory()) {
                addDirectory(f, entries);
            } else {
                try {
                    entries.put(relative(dir, f), Files.readAllBytes(f.toPath()));
                } catch (IOException ignored) {
                }
            }
        }
    }

    private String relative(File base, File file) {
        return base.toPath().relativize(file.toPath()).toString().replace('\\', '/');
    }

    private void addZip(File zip, Map<String, byte[]> entries) {
        try (ZipInputStream in = new ZipInputStream(new FileInputStream(zip))) {
            ZipEntry entry;
            while ((entry = in.getNextEntry()) != null) {
                if (entry.isDirectory() || entry.getName().startsWith("META-INF/")) continue;
                ByteArrayOutputStream buffer = new ByteArrayOutputStream();
                in.transferTo(buffer);
                entries.put(entry.getName(), buffer.toByteArray());
            }
        } catch (IOException e) {
            getLogger().warning("读取 " + zip.getName() + " 失败: " + e.getMessage());
        }
    }

    private byte[] sha1(File file) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-1");
            return digest.digest(Files.readAllBytes(file.toPath()));
        } catch (Exception e) {
            return new byte[0];
        }
    }

    private String address() {
        String configured = getConfig().getString("public-address", "");
        if (configured != null && !configured.isBlank()) return configured.trim();
        String ip = Bukkit.getIp();
        if (ip != null && !ip.isBlank() && !ip.equals("0.0.0.0")) return ip;
        try {
            Enumeration<NetworkInterface> interfaces = NetworkInterface.getNetworkInterfaces();
            while (interfaces.hasMoreElements()) {
                for (InetAddress addr : java.util.Collections.list(interfaces.nextElement().getInetAddresses())) {
                    if (!addr.isLoopbackAddress() && addr.getHostAddress().indexOf(':') < 0) return addr.getHostAddress();
                }
            }
        } catch (Exception ignored) {
        }
        return "127.0.0.1";
    }

    // ---------- HTTP ----------

    private void startServer() {
        stopServer();
        int port = getConfig().getInt("port", 8123);
        try {
            server = HttpServer.create(new InetSocketAddress("0.0.0.0", port), 0);
            server.createContext("/pack.zip", exchange -> {
                byte[] data = output.exists() ? Files.readAllBytes(output.toPath()) : new byte[0];
                exchange.getResponseHeaders().set("Content-Type", "application/zip");
                if ("HEAD".equalsIgnoreCase(exchange.getRequestMethod())) {
                    exchange.sendResponseHeaders(200, -1);
                } else {
                    exchange.sendResponseHeaders(200, data.length);
                    exchange.getResponseBody().write(data);
                }
                exchange.close();
            });
            server.createContext("/", exchange -> {
                byte[] body = ("PackHost\n" + url + "\n").getBytes(StandardCharsets.UTF_8);
                exchange.getResponseHeaders().set("Content-Type", "text/plain; charset=utf-8");
                exchange.sendResponseHeaders(200, body.length);
                exchange.getResponseBody().write(body);
                exchange.close();
            });
            server.setExecutor(Executors.newFixedThreadPool(2));
            server.start();
        } catch (IOException e) {
            getLogger().severe("HTTP 服务启动失败: " + e.getMessage());
        }
    }

    private void stopServer() {
        if (server != null) {
            server.stop(0);
            server = null;
        }
    }

    // ---------- 下发 ----------

    public void send(Player player) {
        if (url.isEmpty()) return;
        MiniMessage mm = MiniMessage.miniMessage();
        boolean required = getConfig().getBoolean("required", false);
        String prompt = getConfig().getString("prompt", "<gold>服务器材质包");
        player.setResourcePack(UUID.randomUUID(), url, hash, mm.deserialize(prompt), required);
    }

    @EventHandler
    public void onJoin(PlayerJoinEvent event) {
        if (!getConfig().getBoolean("send-on-join", true)) return;
        Player player = event.getPlayer();
        Bukkit.getScheduler().runTaskLater(this, () -> {
            if (player.isOnline()) send(player);
        }, 30);
    }

    @EventHandler
    public void onStatus(PlayerResourcePackStatusEvent event) {
        switch (event.getStatus()) {
            case FAILED_DOWNLOAD -> {
                event.getPlayer().sendMessage(MiniMessage.miniMessage()
                        .deserialize("<red>材质包下载失败，请检查 " + url + " 是否可访问"));
                getLogger().warning(event.getPlayer().getName() + " 材质包下载失败");
            }
            case DECLINED -> {
                if (getConfig().getBoolean("required", false)) {
                    event.getPlayer().kick(MiniMessage.miniMessage().deserialize("<red>需要接受服务器材质包才能游玩"));
                }
            }
            case INVALID_URL -> getLogger().warning(event.getPlayer().getName() + " 材质包地址无效: " + url);
            default -> {
            }
        }
    }

    // ---------- 命令 ----------

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        String sub = args.length == 0 ? "status" : args[0].toLowerCase();
        switch (sub) {
            case "reload" -> {
                reloadConfig();
                rebuild();
                startServer();
                for (Player p : Bukkit.getOnlinePlayers()) send(p);
                sender.sendMessage("PackHost 已重载：" + url);
            }
            case "url" -> sender.sendMessage(url);
            case "status" -> sender.sendMessage("PackHost: " + url + " | " + (output != null && output.exists() ? output.length() / 1024 + " KB" : "无资源包"));
            case "send" -> {
                if (args.length < 2) {
                    sender.sendMessage("用法：/packhost send <玩家>");
                    return true;
                }
                Player target = Bukkit.getPlayerExact(args[1]);
                if (target == null) {
                    sender.sendMessage("玩家不在线");
                    return true;
                }
                send(target);
                sender.sendMessage("已下发材质包给 " + target.getName());
            }
            default -> sender.sendMessage("用法：/packhost reload|url|status|send <玩家>");
        }
        return true;
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        if (args.length == 1) {
            return match(List.of("reload", "url", "status", "send"), args[0]);
        }
        if (args.length == 2 && args[0].equalsIgnoreCase("send")) {
            List<String> names = new ArrayList<>();
            for (Player player : Bukkit.getOnlinePlayers()) names.add(player.getName());
            return match(names, args[1]);
        }
        return List.of();
    }

    private static List<String> match(List<String> options, String prefix) {
        String lower = prefix.toLowerCase();
        return options.stream().filter(option -> option.toLowerCase().startsWith(lower)).toList();
    }
}
