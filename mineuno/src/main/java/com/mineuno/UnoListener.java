package com.mineuno;

import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.entity.ArmorStand;
import org.bukkit.entity.Interaction;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.EntityDismountEvent;
import org.bukkit.event.inventory.ClickType;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryCloseEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.event.player.PlayerAnimationEvent;
import org.bukkit.event.player.PlayerInteractEntityEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerItemHeldEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.player.PlayerRespawnEvent;

public class UnoListener implements Listener {

    private final MineUnoPlugin plugin;

    public UnoListener(MineUnoPlugin plugin) {
        this.plugin = plugin;
    }

    @EventHandler
    public void onClick(InventoryClickEvent event) {
        if (!(event.getInventory().getHolder() instanceof Menus.Holder holder)) return;
        event.setCancelled(true);
        if (!(event.getWhoClicked() instanceof Player player)) return;
        if (event.getClickedInventory() == null
                || !(event.getClickedInventory().getHolder() instanceof Menus.Holder)) return;
        // 只把左右键当按钮动作，shift/数字键/double click 不触发
        if (event.getClick() != ClickType.LEFT && event.getClick() != ClickType.RIGHT) return;
        plugin.menus().click(player, holder, event.getSlot());
    }

    @EventHandler
    public void onClose(InventoryCloseEvent event) {
        if (event.getInventory().getHolder() instanceof Menus.Holder holder && holder.type().equals("rules")
                && event.getPlayer() instanceof Player player) {
            plugin.menus().reset(player.getUniqueId());
        }
    }

    @EventHandler
    public void onDrag(InventoryDragEvent event) {
        if (event.getInventory().getHolder() instanceof Menus.Holder) event.setCancelled(true);
    }

    /** 左键：出牌 / 潜行左键结束回合。 */
    @EventHandler
    public void onSwing(PlayerAnimationEvent event) {
        Player player = event.getPlayer();
        Game game = plugin.matches().gameOf(player.getUniqueId());
        if (game == null || game.phase == Game.Phase.WAITING || game.phase == Game.Phase.ENDED) return;
        if (plugin.matches().tuning(player)) return;
        if (player.isSneaking()) {
            plugin.matches().pass(player, game);
            return;
        }
        int cardId = plugin.matches().selectedCard(player);
        if (cardId < 0) {
            MineUnoPlugin.msg(player, "<gray>把准星移到你想出的牌上再左键（潜行左键 = 结束回合）");
            return;
        }
        plugin.matches().play(player, game, cardId);
    }

    /** 右键：摸牌。 */
    @EventHandler
    public void onRightClick(PlayerInteractEvent event) {
        if (event.getAction() != Action.RIGHT_CLICK_AIR && event.getAction() != Action.RIGHT_CLICK_BLOCK) return;
        Player player = event.getPlayer();
        Game game = plugin.matches().gameOf(player.getUniqueId());
        if (game == null || game.phase == Game.Phase.WAITING || game.phase == Game.Phase.ENDED) return;
        if (plugin.matches().tuning(player)) return;
        plugin.matches().draw(player, game);
    }

    /** 滚轮实时调参。 */
    @EventHandler
    public void onHeld(PlayerItemHeldEvent event) {
        if (!plugin.matches().tuning(event.getPlayer())) return;
        event.setCancelled(true);
        plugin.matches().tuneScroll(event.getPlayer(), event.getPreviousSlot(), event.getNewSlot());
    }

    @EventHandler
    public void onInteractEntity(PlayerInteractEntityEvent event) {
        Table.Hit hit = plugin.matches().hit(event.getRightClicked().getUniqueId());
        if (hit == null) return;
        event.setCancelled(true);
        Player player = event.getPlayer();
        Game game = hit.table().game();
        if (plugin.matches().gameOf(player.getUniqueId()) != game) {
            MineUnoPlugin.msg(player, "<gray>这是 <white>" + game.name + " <gray>的牌桌");
            return;
        }
        if (hit.owner() != null && !hit.owner().equals(player.getUniqueId())) {
            MineUnoPlugin.msg(player, "<gray>这是别人的手牌");
            return;
        }
        switch (hit.kind()) {
            case DRAW -> plugin.matches().draw(player, game);
            case PANEL -> plugin.menus().openPanel(player, game);
            case PAGE_PREV -> hit.table().page(player.getUniqueId(), -1);
            case PAGE_NEXT -> hit.table().page(player.getUniqueId(), 1);
        }
    }

    @EventHandler
    public void onDamage(EntityDamageByEntityEvent event) {
        if (event.getEntity() instanceof Interaction && plugin.matches().hit(event.getEntity().getUniqueId()) != null) {
            event.setCancelled(true);
        }
    }

    /** 对局中不许从座位上跳下来。 */
    @EventHandler
    public void onBreak(BlockBreakEvent event) {
        if (inActiveGame(event.getPlayer())) event.setCancelled(true);
    }

    @EventHandler
    public void onPlace(BlockPlaceEvent event) {
        if (inActiveGame(event.getPlayer())) event.setCancelled(true);
    }

    /** 只有真正开局后才限制建造：等待中的房间不影响正常游戏。 */
    private boolean inActiveGame(Player player) {
        Game game = plugin.matches().gameOf(player.getUniqueId());
        return game != null && game.phase != Game.Phase.WAITING && game.phase != Game.Phase.ENDED;
    }

    /** 对局中不许从座位上下来。 */
    @EventHandler
    public void onDismount(EntityDismountEvent event) {
        if (!(event.getDismounted() instanceof ArmorStand)) return;
        if (!(event.getEntity() instanceof Player player)) return;
        if (inActiveGame(player)) event.setCancelled(true);
    }

    /** 对局中死亡重生：直接在座位复活，避免跑到出生点。 */
    @EventHandler
    public void onRespawn(PlayerRespawnEvent event) {
        Player player = event.getPlayer();
        Game game = plugin.matches().gameOf(player.getUniqueId());
        if (game == null || game.phase == Game.Phase.WAITING) return;
        Location seat = plugin.matches().table(game).stand(player.getUniqueId());
        if (seat != null) event.setRespawnLocation(seat);
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        plugin.matches().quit(event.getPlayer());
    }

    @EventHandler
    public void onJoin(PlayerJoinEvent event) {
        Player player = event.getPlayer();
        plugin.matches().hidePrivateFrom(player);
        Bukkit.getScheduler().runTaskLater(plugin, () -> plugin.matches().reconnect(player), 20);
    }
}
