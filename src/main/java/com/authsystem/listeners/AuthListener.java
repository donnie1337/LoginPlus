package com.authsystem.listeners;

import com.authsystem.AuthSystem;
import com.authsystem.util.IpResolver;
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
import java.util.Locale;
import java.util.UUID;

/** Controla o estado de autenticacao depois do handshake e a protecao de login. */
public class AuthListener implements Listener {
    private final AuthSystem plugin;
    private static final List<String> COMANDOS_PERMITIDOS = List.of("/login", "/registro", "/register", "/cadastrar");

    public AuthListener(AuthSystem plugin) { this.plugin = plugin; }

    private String msg(String path, String fallback, String... replacements) {
        return plugin.getMessagesManager().getChat(path, fallback, replacements);
    }

    @EventHandler
    public void onPreLogin(AsyncPlayerPreLoginEvent event) {
        String ip = event.getAddress().getHostAddress();
        if (plugin.getLoginProtection().estaBloqueado(ip)) {
            long restante = plugin.getLoginProtection().segundosRestantes(ip);
            event.disallow(AsyncPlayerPreLoginEvent.Result.KICK_OTHER, "Muitas tentativas de login incorretas.\nTente novamente em " + restante + " segundos.");
            return;
        }
        if (plugin.getConfig().getBoolean("bloquear-conexao-quando-limite-ip-atingido", true)) {
            int limiteContas = plugin.getConfig().getInt("max-contas-por-ip", 1);
            if (limiteContas > 0 && plugin.getSessionManager().countAuthenticatedFromIp(ip) >= limiteContas
                    && plugin.getSessionManager().hasOtherAuthenticatedFromIp(ip, event.getUniqueId())) {
                event.disallow(AsyncPlayerPreLoginEvent.Result.KICK_OTHER,
                        msg("premium.limite-ip", "&cEste IP já atingiu o limite de {limite} conta(s) autenticada(s) ao mesmo tempo. Aguarde uma vaga para entrar.", "{limite}", String.valueOf(limiteContas)));
            }
        }
    }

    @EventHandler
    public void onJoin(PlayerJoinEvent event) {
        Player player = event.getPlayer();
        plugin.getServer().getScheduler().runTask(plugin, () -> processJoin(player));
    }

    private void processJoin(Player player) {
        if (!player.isOnline()) return;
        String ip = IpResolver.getPlayerIp(player);
        UUID verificacaoPremium = plugin.getPremiumAuthenticator().consumeVerified(player.getName(), ip, player.getUniqueId());

        if (verificacaoPremium != null && autenticarPremium(player, verificacaoPremium, ip)) return;

        boolean registrado = plugin.getPlayerDataManager().isRegistered(player.getName());
        if (registrado) {
            iniciarTitleAutenticacao(player, plugin.getMessagesManager().getTitleBemVindo(), plugin.getMessagesManager().getTitleLogin());
            player.sendMessage(msg("join.registrado", "&eEsta conta possui registro. Use /login <senha> para entrar."));
        } else {
            iniciarTitleAutenticacao(player, plugin.getMessagesManager().getTitleBemVindo(), plugin.getMessagesManager().getTitleRegistro());
            player.sendMessage(msg("join.nao-registrado", "&eBem-vindo! Use /registro <senha> <confirmar-senha> para criar sua conta."));
        }

        int timeoutSegundos = Math.max(1, plugin.getConfig().getInt("tempo-limite-login-segundos", 60));
        BukkitTask task = plugin.getServer().getScheduler().runTaskLater(plugin, () -> {
            if (player.isOnline() && !plugin.getSessionManager().isAuthenticated(player)) {
                player.kickPlayer(msg("join.timeout", "&cVocê demorou muito para fazer login/registro."));
            }
        }, timeoutSegundos * 20L);
        plugin.getSessionManager().setTimeoutTask(player, task);
    }

    private boolean autenticarPremium(Player player, UUID premiumUuid, String ip) {
        int limiteContas = plugin.getConfig().getInt("max-contas-por-ip", 1);
        if (!plugin.getSessionManager().tryRegisterAuthenticatedIp(ip, player.getUniqueId(), limiteContas)) {
            player.sendMessage(msg("premium.limite-ip", "&cEste IP já atingiu o limite de {limite} conta(s) autenticada(s) ao mesmo tempo. Você permanece conectado, mas precisa aguardar uma vaga para autenticar.", "{limite}", String.valueOf(limiteContas)));
            return false;
        }

        int limiteIps = plugin.getConfig().getInt("max-ips-por-conta", 1);
        if (!plugin.getPremiumAccountManager().tryAddIp(premiumUuid, ip, limiteIps)) {
            plugin.getSessionManager().unregisterAuthenticatedIp(ip, player.getUniqueId());
            player.sendMessage(msg("premium.limite-ips-conta", "&cEsta conta original já atingiu o limite de {limite} IP(s) permitido(s). Autenticação automática bloqueada; aguarde ou entre novamente quando houver vaga.", "{limite}", String.valueOf(limiteIps)));
            return false;
        }

        plugin.getSessionManager().markPremium(player.getUniqueId());
        plugin.getSessionManager().setAuthenticated(player, true);
        enviarTitleAutenticacao(player, plugin.getMessagesManager().getTitleBemVindo(), plugin.getMessagesManager().getTitlePremium());
        player.sendMessage(msg("premium.sucesso", "&aConta original verificada! Login automático realizado."));
        return true;
    }

    private void iniciarTitleAutenticacao(Player player, String titulo, String subtitulo) {
        player.sendTitle(titulo, subtitulo, 10, 60, 10);
        BukkitTask task = plugin.getServer().getScheduler().runTaskTimer(plugin, () -> {
            if (!player.isOnline() || plugin.getSessionManager().isAuthenticated(player)) {
                plugin.getSessionManager().cancelTitleTask(player);
                return;
            }
            player.sendTitle(titulo, subtitulo, 0, 60, 10);
        }, 40L, 40L);
        plugin.getSessionManager().setTitleTask(player, task);
    }

    private void enviarTitleAutenticacao(Player player, String titulo, String subtitulo) { player.sendTitle(titulo, subtitulo, 10, 60, 10); }

    @EventHandler public void onQuit(PlayerQuitEvent event) {
        plugin.getSessionManager().clear(event.getPlayer());
        plugin.getPremiumAuthenticator().clear(event.getPlayer().getName(), IpResolver.getPlayerIp(event.getPlayer()), event.getPlayer().getUniqueId());
    }
    @EventHandler public void onKick(PlayerKickEvent event) {
        plugin.getSessionManager().clear(event.getPlayer());
        plugin.getPremiumAuthenticator().clear(event.getPlayer().getName(), IpResolver.getPlayerIp(event.getPlayer()), event.getPlayer().getUniqueId());
    }

    private boolean precisaBloquear(Player player) { return !plugin.getSessionManager().isAuthenticated(player); }

    @EventHandler(priority = EventPriority.LOWEST)
    public void onMove(PlayerMoveEvent event) {
        if (!precisaBloquear(event.getPlayer())) return;
        if (event.getFrom().getX() != event.getTo().getX() || event.getFrom().getY() != event.getTo().getY() || event.getFrom().getZ() != event.getTo().getZ()) event.setTo(event.getFrom());
    }
    @EventHandler public void onCommand(PlayerCommandPreprocessEvent event) {
        if (!precisaBloquear(event.getPlayer())) return;
        String message = event.getMessage().trim();
        int separator = message.indexOf(' ');
        String cmd = (separator >= 0 ? message.substring(0, separator) : message).toLowerCase(Locale.ROOT);
        if (!COMANDOS_PERMITIDOS.contains(cmd)) {
            event.setCancelled(true);
            event.getPlayer().sendMessage(msg("protecao.comando-bloqueado", "&cFaça login ou se registre antes de usar comandos."));
        }
    }
    @EventHandler public void onChat(AsyncPlayerChatEvent event) {
        if (precisaBloquear(event.getPlayer())) {
            event.setCancelled(true);
            event.getPlayer().sendMessage(msg("protecao.chat-bloqueado", "&cFaça login ou se registre antes de conversar no chat."));
        }
    }
    @EventHandler public void onDamage(EntityDamageEvent event) { if (event.getEntity() instanceof Player player && precisaBloquear(player)) event.setCancelled(true); }
    @EventHandler public void onFoodLevelChange(FoodLevelChangeEvent event) { if (event.getEntity() instanceof Player player && precisaBloquear(player)) event.setCancelled(true); }
    @EventHandler public void onBlockBreak(BlockBreakEvent event) { if (precisaBloquear(event.getPlayer())) event.setCancelled(true); }
    @EventHandler public void onBlockPlace(BlockPlaceEvent event) { if (precisaBloquear(event.getPlayer())) event.setCancelled(true); }
    @EventHandler public void onInteract(PlayerInteractEvent event) { if (precisaBloquear(event.getPlayer())) event.setCancelled(true); }
    @EventHandler public void onDropItem(PlayerDropItemEvent event) { if (precisaBloquear(event.getPlayer())) event.setCancelled(true); }
}
