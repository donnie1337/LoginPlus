package com.authsystem.listeners;

import com.authsystem.AuthSystem;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.EntityPickupItemEvent;
import org.bukkit.event.entity.EntityTargetEvent;
import org.bukkit.event.entity.ProjectileLaunchEvent;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryOpenEvent;
import org.bukkit.event.inventory.InventoryType;
import org.bukkit.event.player.PlayerBedEnterEvent;
import org.bukkit.event.player.PlayerInteractEntityEvent;
import org.bukkit.event.player.PlayerItemConsumeEvent;
import org.bukkit.event.player.PlayerTeleportEvent;
import org.bukkit.event.vehicle.VehicleEnterEvent;
import org.bukkit.projectiles.ProjectileSource;

/**
 * Camada extra do sistema anti-bypass: cobre formas INDIRETAS de burlar o
 * congelamento do login que o AuthListener sozinho nao pega - teleporte,
 * abrir inventarios/bau, entrar em veiculos, atirar projeteis, mobs mirando
 * no jogador travado, o proprio jogador travado causando dano em algo, etc.
 *
 * Junto com o AuthListener (que trava movimento/chat/comandos/quebra de
 * bloco) e o LoginProtection (bloqueio por IP apos varias senhas erradas),
 * isso forma o sistema anti-bypass completo.
 */
public class AntiBypassListener implements Listener {

    private final AuthSystem plugin;

    public AntiBypassListener(AuthSystem plugin) {
        this.plugin = plugin;
    }

    private boolean bloqueado(Player player) {
        return !plugin.getSessionManager().isAuthenticated(player);
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void onTeleport(PlayerTeleportEvent event) {
        if (bloqueado(event.getPlayer()) && event.getCause() != PlayerTeleportEvent.TeleportCause.PLUGIN) {
            event.setCancelled(true);
        }
    }

    @EventHandler
    public void onTarget(EntityTargetEvent event) {
        // Impede que mobs comecem a perseguir/atacar quem ainda nao logou.
        if (event.getTarget() instanceof Player player && bloqueado(player)) {
            event.setCancelled(true);
        }
    }

    @EventHandler
    public void onInventoryOpen(InventoryOpenEvent event) {
        if (!(event.getPlayer() instanceof Player player) || !bloqueado(player)) {
            return;
        }
        InventoryType type = event.getInventory().getType();
        if (type == InventoryType.CRAFTING || type == InventoryType.PLAYER) {
            return; // o proprio inventario do jogador nao oferece risco
        }
        event.setCancelled(true);
    }

    @EventHandler
    public void onInventoryClick(InventoryClickEvent event) {
        if (event.getWhoClicked() instanceof Player player && bloqueado(player)) {
            event.setCancelled(true);
        }
    }

    @EventHandler
    public void onBedEnter(PlayerBedEnterEvent event) {
        if (bloqueado(event.getPlayer())) {
            event.setCancelled(true);
        }
    }

    @EventHandler
    public void onInteractEntity(PlayerInteractEntityEvent event) {
        // Cobre montar em cavalo/barco, interagir com aldeao, item frame, etc.
        if (bloqueado(event.getPlayer())) {
            event.setCancelled(true);
        }
    }

    @EventHandler
    public void onVehicleEnter(VehicleEnterEvent event) {
        if (event.getEntered() instanceof Player player && bloqueado(player)) {
            event.setCancelled(true);
        }
    }

    @EventHandler
    public void onItemConsume(PlayerItemConsumeEvent event) {
        if (bloqueado(event.getPlayer())) {
            event.setCancelled(true);
        }
    }

    @EventHandler
    public void onProjectileLaunch(ProjectileLaunchEvent event) {
        ProjectileSource source = event.getEntity().getShooter();
        if (source instanceof Player player && bloqueado(player)) {
            event.setCancelled(true);
        }
    }

    @EventHandler
    public void onPickupItem(EntityPickupItemEvent event) {
        if (event.getEntity() instanceof Player player && bloqueado(player)) {
            event.setCancelled(true);
        }
    }

    @EventHandler
    public void onDamageByEntity(EntityDamageByEntityEvent event) {
        // Impede que o proprio jogador travado cause dano em algo (mob, item frame, etc).
        if (event.getDamager() instanceof Player player && bloqueado(player)) {
            event.setCancelled(true);
        }
    }
}
