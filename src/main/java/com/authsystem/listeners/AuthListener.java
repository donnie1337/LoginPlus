package com.authsystem.listeners;

import com.authsystem.AuthSystem;
import com.authsystem.util.PremiumAuthenticator;
import org.bukkit.ChatColor;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.entity.FoodLevelChangeEvent;
import org.bukkit.event.player.AsyncPlayerChatEvent;
import org.bukkit.event.player.AsyncPlayerPreLoginEvent;
import org.bukkit.event.player.PlayerCommandPreprocessEvent;
import org.bukkit.event.player.PlayerDropItemEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerKickEvent;
import org.bukkit.event.player.PlayerMoveEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.scheduler.BukkitTask;

import java.util.List;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/** Handles the post-handshake authentication state and login protection. */
public class AuthListener implements Listener {
    private final AuthSystem plugin;
    private final ConcurrentHashMap<UUID, Boolean> preLoginPremiumResult = new ConcurrentHashMap<>();

    private static final List<String> COMANDOS_PERMITIDOS =
            List.of("/login", "/registro", "/register", "/cadastrar");

    public AuthListener(AuthSystem plugin) {
        this.plugin = plugin;
    }

    @EventHandler
    public void onPreLogin(AsyncPlayerPreLoginEvent event) {
        String ip = event.getAddress().getHostAddress();
        if (plugin.getLoginProtection().estaBloqueado(ip)) {
            long restante = plugin.getLoginProtection().segundosRestantes(ip);
            event.disallow(AsyncPlayerPreLoginEvent.Result.KICK_OTHER,
                    ChatColor.RED + "Muitas tentativas de login incorretas.\n"
                            + ChatColor.RED + "Tente novamente em " + restante + " segundos.");
            return;
        }

        // CRITICAL: never call PremiumChecker here. A Mojang name lookup does not prove
        // ownership of the account. Only the cryptographic PacketEvents handshake can do that.
        UUID verifiedUuid = plugin.getPremiumAuthenticator().consumeVerified(event.getName());
        boolean premium = verifiedUuid != null;
        preLoginPremiumResult.put(event.getUniqueId(), premium);
    }

    @EventHandler
    public void onJoin(PlayerJoinEvent event) {
        Player player = event.getPlayer();
        boolean premium = preLoginPremiumResult.getOrDefault(player.getUniqueId(), false);
        preLoginPremiumResult.remove(player.getUniqueId());

        if (premium) {
            plugin.getSessionManager().markPremium(player.getUniqueId());
            plugin.getSessionManager().setAuthenticated(player, true);
            player.sendMessage(ChatColor.GREEN + "Conta original verificada! Login automatico realizado.");
            return;
        }

        plugin.getSessionManager().setFrozenLocation(player, player.getLocation());
        boolean registrado = plugin.getPlayerDataManager().isRegistered(player.getName());
        if (registrado) {
            player.sendMessage(ChatColor.YELLOW + "Bem-vindo de volta! Use /login <senha> para entrar.");
        } else {
            player.sendMessage(ChatColor.YELLOW + "Bem-vindo! Use /registro <senha> <confirmar-senha> para criar sua conta.");
        }

        int timeoutSegundos = plugin.getConfig().getInt("tempo-limite-login-segundos", 60);
        BukkitTask task = plugin.getServer().getScheduler().runTaskLater(plugin, () -> {
            if (player.isOnline() && !plugin.getSessionManager().isAuthenticated(player)) {
                player.kickPlayer(ChatColor.RED + "Voce demorou muito para fazer login/registro.");
            }
        }, timeoutSegundos * 20L);
        plugin.getSessionManager().setTimeoutTask(player, task);
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        plugin.getSessionManager().clear(event.getPlayer());
        plugin.getPremiumAuthenticator().clear(event.getPlayer().getName());
    }

    @EventHandler
    public void onKick(PlayerKickEvent event) {
        plugin.getSessionManager().clear(event.getPlayer());
        plugin.getPremiumAuthenticator().clear(event.getPlayer().getName());
    }

    private boolean precisaBloquear(Player player) {
        return !plugin.getSessionManager().isAuthenticated(player);
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void onMove(PlayerMoveEvent event) {
        if (!precisaBloquear(event.getPlayer())) return;
        if (event.getFrom().getX() != event.getTo().getX()
                || event.getFrom().getY() != event.getTo().getY()
                || event.getFrom().getZ() != event.getTo().getZ()) {
            event.setTo(event.getFrom());
        }
    }

    @EventHandler
    public void onCommand(PlayerCommandPreprocessEvent event) {
        if (!precisaBloquear(event.getPlayer())) return;
        String cmd = event.getMessage().split(" ")[0].toLowerCase();
        if (!COMANDOS_PERMITIDOS.contains(cmd)) {
            event.setCancelled(true);
            event.getPlayer().sendMessage(ChatColor.RED + "Faca login ou se registre antes de usar comandos.");
        }
    }

    @EventHandler
    public void onChat(AsyncPlayerChatEvent event) {
        if (precisaBloquear(event.getPlayer())) {
            event.setCancelled(true);
            event.getPlayer().sendMessage(ChatColor.RED + "Faca login ou se registre antes de conversar no chat.");
        }
    }

    @EventHandler
    public void onDamage(EntityDamageEvent event) {
        if (event.getEntity() instanceof Player player && precisaBloquear(player)) event.setCancelled(true);
    }

    @EventHandler
    public void onFoodLevelChange(FoodLevelChangeEvent event) {
        if (event.getEntity() instanceof Player player && precisaBloquear(player)) event.setCancelled(true);
    }

    @EventHandler
    public void onBlockBreak(BlockBreakEvent event) {
        if (precisaBloquear(event.getPlayer())) event.setCancelled(true);
    }

    @EventHandler
    public void onBlockPlace(BlockPlaceEvent event) {
        if (precisaBloquear(event.getPlayer())) event.setCancelled(true);
    }

    @EventHandler
    public void onInteract(PlayerInteractEvent event) {
        if (precisaBloquear(event.getPlayer())) event.setCancelled(true);
    }

    @EventHandler
    public void onDropItem(PlayerDropItemEvent event) {
        if (precisaBloquear(event.getPlayer())) event.setCancelled(true);
    }
}
