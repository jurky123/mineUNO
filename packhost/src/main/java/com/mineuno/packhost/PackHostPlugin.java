package com.mineuno.packhost;

import com.sun.net.httpserver.HttpServer;
import java.io.BufferedOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.URI;
import java.net.InetSocketAddress;
import java.net.NetworkInterface;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.security.DigestInputStream;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.Enumeration;
import java.util.HashSet;
import java.util.Set;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Executors;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import java.util.zip.ZipOutputStream;
import net.kyori.adventure.resource.ResourcePackInfo;
import net.kyori.adventure.resource.ResourcePackRequest;
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

    /** 本插件资源包的固定 ID：发送与状态事件都以此为准，绝不处理其他插件的资源包。 */
    private static final UUID PACK_ID = UUID.fromString("6d756e6f-0001-4000-8000-000000000001");

    private HttpServer server;
    private java.util.concurrent.ExecutorService executor;
    private volatile File output;
    private volatile byte[] hash = new byte[0];
    private volatile String url = "";
    private int runningPort = -1;

    @Override
    public void onEnable() {
        saveDefaultConfig();
        rebuild(false, null);
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

    /**
     * 合并资源包并原子发布。
     * async=true 时在后台线程构建，主线程只做原子切换（/packhost reload 用）；
     * 失败时保留旧包不动。
     */
    public void rebuild(boolean async, Runnable onDone) {
        File looseDir = new File(getDataFolder(), "pack");
        File packsDir = new File(getDataFolder(), "packs");
        looseDir.mkdirs();
        packsDir.mkdirs();
        File out = new File(getDataFolder(), "output/pack.zip");
        out.getParentFile().mkdirs();
        output = out;

        Runnable build = () -> {
            File temp = new File(out.getParentFile(), "pack.zip.tmp");
            try {
                int files = build(temp, looseDir, packsDir);
                byte[] built = sha1(temp);
                Runnable publish = () -> {
                    try {
                        move(temp, out);
                    } catch (IOException e) {
                        getLogger().severe("替换资源包失败，保留旧包: " + e.getMessage());
                        return;
                    }
                    hash = built;
                    url = "http://" + address() + ":" + getConfig().getInt("port", 8123) + "/pack.zip";
                    getLogger().info("已合并 " + files + " 个文件 -> " + out.getName() + " (" + out.length() / 1024 + " KB)");
                    if (onDone != null) onDone.run();
                };
                if (async) Bukkit.getScheduler().runTask(this, publish);
                else publish.run();
            } catch (IOException e) {
                getLogger().severe("合并资源包失败，保留旧包: " + e.getMessage());
            }
        };
        if (async) {
            Bukkit.getScheduler().runTaskAsynchronously(this, build);
        } else {
            build.run();
        }
    }

    private void move(File from, File to) throws IOException {
        try {
            Files.move(from.toPath(), to.toPath(), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (IOException atomicFailed) {
            Files.move(from.toPath(), to.toPath(), StandardCopyOption.REPLACE_EXISTING);
        }
    }

    /**
     * 两遍构建：先只收集 entry 名单决定归属（散装目录 < 按文件名排序的 zip），再流式写出。
     * 全程不把包内容整体读进内存。
     */
    private int build(File temp, File looseDir, File packsDir) throws IOException {
        File[] zips = packsDir.listFiles((dir, name) -> name.toLowerCase().endsWith(".zip"));
        if (zips == null) zips = new File[0];
        Arrays.sort(zips, Comparator.comparing(File::getName));

        Map<String, Integer> owner = new LinkedHashMap<>();
        Map<String, Integer> conflicts = new LinkedHashMap<>();
        Set<Integer> used = new HashSet<>();
        for (String name : listDir(looseDir, looseDir)) {
            register(owner, conflicts, name, -1);
            used.add(-1);
        }
        for (int i = 0; i < zips.length; i++) {
            for (String name : listZip(zips[i])) {
                register(owner, conflicts, name, i);
                used.add(i);
            }
        }
        if (conflicts.isEmpty()) {
            getLogger().info("资源包合并：无同名冲突");
        } else {
            String sample = String.join(", ", conflicts.keySet().stream().limit(5).toList());
            getLogger().warning("资源包合并：有 " + conflicts.size() + " 个同名文件被覆盖（顺序：散装目录 < 按文件名排序的 zip），例如 " + sample);
        }

        int written = 0;
        try (ZipOutputStream zipOut = new ZipOutputStream(new BufferedOutputStream(new FileOutputStream(temp)))) {
            if (used.contains(-1)) written += writeDir(looseDir, looseDir, zipOut, owner, -1);
            for (int i = 0; i < zips.length; i++) {
                // 损坏/空的 zip 直接跳过，不影响其它资源包合并
                if (used.contains(i)) written += writeZip(zips[i], zipOut, owner, i);
            }
            if (!owner.containsKey("pack.mcmeta")) {
                writeEntry(zipOut, "pack.mcmeta", defaultMeta());
                written++;
            }
        }
        return written;
    }

    private void register(Map<String, Integer> owner, Map<String, Integer> conflicts, String name, int source) {
        if (owner.containsKey(name)) conflicts.put(name, owner.get(name));
        owner.put(name, source);
    }

    private List<String> listDir(File root, File dir) {
        List<String> names = new ArrayList<>();
        File[] files = dir.listFiles();
        if (files == null) return names;
        for (File f : files) {
            if (f.isDirectory()) names.addAll(listDir(root, f));
            else names.add(relative(root, f));
        }
        return names;
    }

    private List<String> listZip(File file) {
        List<String> names = new ArrayList<>();
        try (ZipFile zip = new ZipFile(file)) {
            Enumeration<? extends ZipEntry> entries = zip.entries();
            while (entries.hasMoreElements()) {
                ZipEntry entry = entries.nextElement();
                if (!entry.isDirectory() && !entry.getName().startsWith("META-INF/")) names.add(entry.getName());
            }
        } catch (IOException e) {
            getLogger().warning("读取 " + file.getName() + " 失败: " + e.getMessage());
        }
        return names;
    }

    private int writeZip(File file, ZipOutputStream out, Map<String, Integer> owner, int source) throws IOException {
        int written = 0;
        try (ZipFile zip = new ZipFile(file)) {
            Enumeration<? extends ZipEntry> entries = zip.entries();
            while (entries.hasMoreElements()) {
                ZipEntry entry = entries.nextElement();
                if (entry.isDirectory() || entry.getName().startsWith("META-INF/")) continue;
                if (!Integer.valueOf(source).equals(owner.get(entry.getName()))) continue;
                out.putNextEntry(entry(entry.getName()));
                try (InputStream in = zip.getInputStream(entry)) {
                    in.transferTo(out);
                }
                out.closeEntry();
                written++;
            }
        }
        return written;
    }

    private int writeDir(File root, File dir, ZipOutputStream out, Map<String, Integer> owner, int source) throws IOException {
        int written = 0;
        File[] files = dir.listFiles();
        if (files == null) return 0;
        for (File f : files) {
            if (f.isDirectory()) {
                written += writeDir(root, f, out, owner, source);
                continue;
            }
            String name = relative(root, f);
            if (!Integer.valueOf(source).equals(owner.get(name))) continue;
            out.putNextEntry(entry(name));
            try (InputStream in = Files.newInputStream(f.toPath())) {
                in.transferTo(out);
            }
            out.closeEntry();
            written++;
        }
        return written;
    }

    private ZipEntry entry(String name) {
        ZipEntry entry = new ZipEntry(name);
        entry.setTime(0);
        return entry;
    }

    private void writeEntry(ZipOutputStream out, String name, byte[] data) throws IOException {
        out.putNextEntry(entry(name));
        out.write(data);
        out.closeEntry();
    }

    private byte[] defaultMeta() {
        return ("{\"pack\":{\"description\":\"PackHost\",\"min_format\":[1,0],\"max_format\":999}}")
                .getBytes(StandardCharsets.UTF_8);
    }

    private String relative(File base, File file) {
        return base.toPath().relativize(file.toPath()).toString().replace('\\', '/');
    }

    /** 流式计算 SHA-1，不把整包读进内存。 */
    private byte[] sha1(File file) {
        try (DigestInputStream in = new DigestInputStream(Files.newInputStream(file.toPath()),
                MessageDigest.getInstance("SHA-1"))) {
            in.transferTo(OutputStream.nullOutputStream());
            return in.getMessageDigest().digest();
        } catch (Exception e) {
            getLogger().warning("计算资源包摘要失败: " + e.getMessage());
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
                File file = output;
                if (file == null || !file.exists()) {
                    exchange.sendResponseHeaders(404, -1);
                    exchange.close();
                    return;
                }
                byte[] current = hash;
                String etag = current.length == 0 ? "" : "\"" + hex(current) + "\"";
                if (!etag.isEmpty()) {
                    exchange.getResponseHeaders().set("ETag", etag);
                    if (etag.equals(exchange.getRequestHeaders().getFirst("If-None-Match"))) {
                        exchange.sendResponseHeaders(304, -1);
                        exchange.close();
                        return;
                    }
                }
                exchange.getResponseHeaders().set("Content-Type", "application/zip");
                if ("HEAD".equalsIgnoreCase(exchange.getRequestMethod())) {
                    exchange.sendResponseHeaders(200, -1);
                    exchange.close();
                    return;
                }
                exchange.sendResponseHeaders(200, file.length());
                try (InputStream in = Files.newInputStream(file.toPath());
                     OutputStream out = exchange.getResponseBody()) {
                    in.transferTo(out);
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
            executor = Executors.newFixedThreadPool(2);
            server.setExecutor(executor);
            server.start();
            runningPort = port;
        } catch (IOException e) {
            getLogger().severe("HTTP 服务启动失败: " + e.getMessage());
        }
    }

    private void stopServer() {
        if (server != null) {
            server.stop(0);
            server = null;
        }
        if (executor != null) {
            executor.shutdownNow();
            executor = null;
        }
    }

    // ---------- 下发 ----------

    public void send(Player player) {
        if (url.isEmpty()) return;
        boolean required = getConfig().getBoolean("required", false);
        String prompt = getConfig().getString("prompt", "<gold>服务器材质包");
        player.sendResourcePacks(ResourcePackRequest.resourcePackRequest()
                .packs(ResourcePackInfo.resourcePackInfo(PACK_ID, URI.create(url),
                        hash.length == 0 ? "" : hex(hash)))
                .required(required)
                .prompt(MiniMessage.miniMessage().deserialize(prompt))
                .build());
    }

    private static String hex(byte[] bytes) {
        StringBuilder builder = new StringBuilder(bytes.length * 2);
        for (byte b : bytes) builder.append(String.format("%02x", b));
        return builder.toString();
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
        if (!PACK_ID.equals(event.getID())) return;
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
                int oldPort = runningPort;
                reloadConfig();
                if (getConfig().getInt("port", 8123) != oldPort) {
                    startServer();
                    sender.sendMessage("端口已变更，HTTP 服务已重启");
                }
                rebuild(true, () -> {
                    for (Player p : Bukkit.getOnlinePlayers()) send(p);
                    sender.sendMessage("PackHost 已重载：" + url);
                });
                sender.sendMessage("正在后台重建资源包…（失败会保留旧包）");
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
