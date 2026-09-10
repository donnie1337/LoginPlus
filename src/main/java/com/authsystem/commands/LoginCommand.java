package com.authsystem.commands;

import com.authsystem.AuthSystem;
import com.authsystem.manager.PlayerDataManager.PasswordData;
import com.authsystem.util.IpResolver;
import com.authsystem.util.PasswordUtils;
import org.bukkit.Bukkit;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

public class LoginCommand implements CommandExecutor {
    private final AuthSystem plugin;
    private final Set<UUID> verificacoesEmAndamento = ConcurrentHashMap.newKeySet();

    public LoginCommand(AuthSystem plugin) { this.plugin = plugin; }

    private String msg(String path, String fallback, String... replacements) {
        return plugin.getMessagesManager().getChat(path, fallback, replacements);
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!(sender instanceof Player player)) {
            sender.sendMessage(msg("login.apenas-jogador", "&c✖ &fEste comando só pode ser usado por jogadores."));
            return true;
        }
        if (plugin.getSessionManager().isAuthenticated(player)) {
            player.sendMessage(msg("login.ja-logado", "&e⚠ &fVocê já está logado."));
            return true;
        }
        if (plugin.getSessionManager().isPremium(player)) {
            player.sendMessage(msg("login.premium", "&e⚠ &fSua conta original já foi verificada automaticamente. Não é preciso usar /login."));
            return true;
        }
        if (!plugin.getPlayerDataManager().isRegistered(player.getName())) {
            player.sendMessage(msg("login.sem-conta", "&c✖ &fVocê ainda não tem conta. Use /registro <senha> <confirmar-senha>."));
            return true;
        }
        if (args.length != 1) {
            player.sendMessage(msg("login.uso", "&e➜ &fUso: /login <senha>"));
            return true;
        }

        String ip = IpResolver.getPlayerIp(player);
        if (ip == null) {
            player.sendMessage(msg("login.ip-indisponivel", "&c✖ &fNão foi possível identificar seu IP. Tente entrar novamente."));
            return true;
        }

        String username = player.getName();
        if (plugin.getLoginProtection().estaBloqueado(ip)) {
            player.sendMessage(msg("login.ip-bloqueado", "&c✖ &fEste IP está temporariamente bloqueado por excesso de tentativas. Tente novamente mais tarde."));
            return true;
        }
        if (plugin.getLoginProtection().estaBloqueadoConta(username)) {
            long restante = plugin.getLoginProtection().segundosRestantesConta(username);
            player.sendMessage(msg("login.conta-bloqueada", "&c✖ &fEsta conta está temporariamente bloqueada por excesso de tentativas. Tente novamente em {restante} segundos.", "{restante}", String.valueOf(restante)));
            return true;
        }

        UUID playerId = player.getUniqueId();
        if (!verificacoesEmAndamento.add(playerId)) {
            player.sendMessage(msg("login.verificacao-andamento", "&e⚠ &fSua senha já está sendo verificada. Aguarde um instante."));
            return true;
        }

        int maxProcessamentos = Math.max(1, plugin.getConfig().getInt("seguranca.max-processamentos-pbkdf2-simultaneos", 2));
        if (!plugin.getHashProcessingLimiter().tryAcquire(maxProcessamentos)) {
            verificacoesEmAndamento.remove(playerId);
            player.sendMessage(msg("login.servidor-ocupado", "&c✖ &fO servidor está processando muitas senhas no momento. Aguarde alguns segundos e tente novamente."));
            return true;
        }

        String senha = args[0];
        PasswordData passwordData = plugin.getPlayerDataManager().getPasswordData(username);
        if (passwordData == null) {
            plugin.getHashProcessingLimiter().release();
            verificacoesEmAndamento.remove(playerId);
            player.sendMessage(msg("login.conta-indisponivel", "&c✖ &fNão foi possível verificar sua conta. Tente novamente."));
            return true;
        }

        Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {
            try {
                boolean senhaCorreta = PasswordUtils.verify(senha, passwordData.salt(), passwordData.hash(), passwordData.iterations());
                int currentIterations = plugin.getPasswordIterations();
                if (senhaCorreta && passwordData.iterations() < currentIterations) {
                    String novoSalt = PasswordUtils.generateSalt();
                    String novoHash = PasswordUtils.hash(senha, novoSalt, currentIterations);
                    Bukkit.getScheduler().runTask(plugin, () -> plugin.getPlayerDataManager().upgradePasswordHash(username, novoSalt, novoHash, currentIterations));
                }

                Bukkit.getScheduler().runTask(plugin, () -> {
                    try {
                        if (!player.isOnline() || !player.getUniqueId().equals(playerId)) return;
                        if (plugin.getSessionManager().isAuthenticated(player)) return;
                        if (senhaCorreta) concluirLogin(player, username, ip);
                        else registrarFalha(player, username, ip);
                    } finally {
                        verificacoesEmAndamento.remove(playerId);
                    }
                });
            } catch (Exception e) {
                plugin.getLogger().warning("Falha ao processar login de " + username + ": " + e.getMessage());
                verificacoesEmAndamento.remove(playerId);
                Bukkit.getScheduler().runTask(plugin, () -> {
                    if (player.isOnline() && !plugin.getSessionManager().isAuthenticated(player)) {
                        player.sendMessage(msg("login.erro-verificacao", "&c✖ &fNão foi possível verificar sua senha. Tente novamente."));
                    }
                });
            } finally {
                plugin.getHashProcessingLimiter().release();
            }
        });

        player.sendMessage(msg("login.verificando", "&e⚠ &fVerificando sua senha..."));
        return true;
    }

    private void concluirLogin(Player player, String username, String ip) {
        int limiteContas = plugin.getConfig().getInt("max-contas-por-ip", 1);
        if (!plugin.getSessionManager().tryRegisterAuthenticatedIp(ip, player.getUniqueId(), limiteContas)) {
            player.sendMessage(msg("login.limite-ip", "&c✖ &fEste IP já atingiu o limite de {limite} conta(s) conectada(s) ao mesmo tempo.", "{limite}", String.valueOf(limiteContas)));
            return;
        }
        int limiteIps = plugin.getConfig().getInt("max-ips-por-conta", 1);
        if (!plugin.getPlayerDataManager().tryAddIp(username, ip, limiteIps)) {
            plugin.getSessionManager().unregisterAuthenticatedIp(ip, player.getUniqueId());
            player.sendMessage(msg("login.limite-ips-conta", "&c✖ &fEsta conta já atingiu o limite de {limite} IP(s) permitido(s).", "{limite}", String.valueOf(limiteIps)));
            return;
        }
        plugin.getSessionManager().setAuthenticated(player, true);
        plugin.getSessionManager().cancelTimeout(player);
        plugin.getLoginProtection().limparAoLogar(ip);
        plugin.getLoginProtection().limparContaAoLogar(username);
        player.sendMessage(msg("login.sucesso", "&a✔ &fLogin efetuado com sucesso! Bem-vindo(a) de volta."));
    }

    private void registrarFalha(Player player, String username, String ip) {
        int max = Math.max(1, plugin.getConfig().getInt("max-tentativas-login", 3));
        long minutosBloqueio = Math.max(1L, plugin.getConfig().getLong("bloqueio-apos-exceder-tentativas-minutos", 5));
        long bloqueioMs = minutosBloqueio * 60_000L;
        int tentativasIp = plugin.getLoginProtection().registrarErro(ip, max, bloqueioMs);
        int tentativasConta = plugin.getLoginProtection().registrarErroConta(username, max, bloqueioMs);
        int tentativas = Math.max(tentativasIp, tentativasConta);
        if (tentativas > max) player.kickPlayer(msg("login.muitas-tentativas", "&c✖ &fMuitas tentativas de senha incorreta. Tente novamente mais tarde."));
        else player.sendMessage(msg("login.senha-incorreta", "&c✖ &fSenha incorreta! ({tentativas}/{max})", "{tentativas}", String.valueOf(tentativas), "{max}", String.valueOf(max)));
    }
}
