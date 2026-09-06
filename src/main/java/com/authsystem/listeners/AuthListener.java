package com.authsystem.listeners;

import com.authsystem.AuthSystem;
import com.authsystem.util.PremiumChecker;
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

/**
 * Controla todo o fluxo de autenticacao:
 *  - No pre-login (assincrono), consulta a Mojang para saber se a conta e original.
 *  - No join, ou libera o jogador (se for premium) ou "congela" ele ate logar/registrar.
 *  - Enquanto nao autenticado: bloqueia movimento, chat, comandos, dano, fome,
 *    quebra/colocacao de blocos e interacoes.
 */
public class AuthListener implements Listener {

    private final AuthSystem plugin;

    // Resultado da checagem premium feita no pre-login, aguardando o PlayerJoinEvent.
    private final ConcurrentHashMap<UUID, Boolean> preLoginPremiumResult = new ConcurrentHashMap<>();

    private static final List<String> COMANDOS_PERMITIDOS =
            List.of("/login", "/registro", "/register", "/cadastrar");

    public AuthListener(AuthSystem plugin) {
        this.plugin = plugin;
    }

    // Este evento ja roda FORA da thread principal por padrao do Bukkit,
    // entao e seguro fazer aqui a chamada HTTP (bloqueante) para a Mojang.
    @EventHandler
    public void onPreLogin(AsyncPlayerPreLoginEvent event) {
        String ip = event.getAddress().getHostAddress();

        // Anti-bypass: se esse IP errou a senha demais recentemente, nem
        // deixa conectar - assim desconectar e reconectar nao adianta nada.
        if (plugin.getLoginProtection().estaBloqueado(ip)) {
            long restante = plugin.getLoginProtection().segundosRestantes(ip);
            event.disallow(AsyncPlayerPreLoginEvent.Result.KICK_OTHER,
                    ChatColor.RED + "Muitas tentativas de login incorretas.\n"
                            + ChatColor.RED + "Tente novamente em " + restante + " segundos.");
            return;
        }

        boolean premium = PremiumChecker.isPremium(event.getName());
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
            player.sendMessage(ChatColor.GREEN + "Conta original detectada! Login automatico realizado.");
            return;
        }

        // Jogador "pirata": precisa fazer /login ou /registro.
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
    }

    @EventHandler
    public void onKick(PlayerKickEvent event) {
        plugin.getSessionManager().clear(event.getPlayer());
    }

    private boolean precisaBloquear(Player player) {
        return !plugin.getSessionManager().isAuthenticated(player);
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void onMove(PlayerMoveEvent event) {
        if (!precisaBloquear(event.getPlayer())) {
            return;
        }
        // Permite olhar em volta, mas nao andar, enquanto nao logado.
        if (event.getFrom().getX() != event.getTo().getX()
                || event.getFrom().getY() != event.getTo().getY()
                || event.getFrom().getZ() != event.getTo().getZ()) {
            event.setTo(event.getFrom());
        }
    }

    @EventHandler
    public void onCommand(PlayerCommandPreprocessEvent event) {
        if (!precisaBloquear(event.getPlayer())) {
            return;
        }
        String cmd = event.getMessage().split(" ")[0].toLowerCase();
        if (!COMANDOS_PERMITIDOS.contains(cmd)) {
            event.setCancelled(true);
            event.getPlayer().sendMessage(ChatColor.RED + "Faca login ou se registre antes de usar comandos.");
        }
    }

    // Observacao: a partir do sistema de chat assinado (1.19+), o Spigot
    // ainda mantem esse evento por compatibilidade, mas se notar problemas
    // no seu build especifico do 26.2, considere migrar para o listener de
    // chat mais novo da API (ou usar ProtocolLib).
    @EventHandler
    public void onChat(AsyncPlayerChatEvent event) {
        if (precisaBloquear(event.getPlayer())) {
            event.setCancelled(true);
            event.getPlayer().sendMessage(ChatColor.RED + "Faca login ou se registre antes de conversar no chat.");
        }
    }

    @EventHandler
    public void onDamage(EntityDamageEvent event) {
        if (event.getEntity() instanceof Player player && precisaBloquear(player)) {
            event.setCancelled(true);
        }
    }

    @EventHandler
    public void onFoodLevelChange(FoodLevelChangeEvent event) {
        if (event.getEntity() instanceof Player player && precisaBloquear(player)) {
            event.setCancelled(true);
        }
    }

    @EventHandler
    public void onBlockBreak(BlockBreakEvent event) {
        if (precisaBloquear(event.getPlayer())) {
            event.setCancelled(true);
        }
    }

    @EventHandler
    public void onBlockPlace(BlockPlaceEvent event) {
        if (precisaBloquear(event.getPlayer())) {
            event.setCancelled(true);
        }
    }

    @EventHandler
    public void onInteract(PlayerInteractEvent event) {
        if (precisaBloquear(event.getPlayer())) {
            event.setCancelled(true);
        }
    }

    @EventHandler
    public void onDropItem(PlayerDropItemEvent event) {
        if (precisaBloquear(event.getPlayer())) {
            event.setCancelled(true);
        }
    }
}
