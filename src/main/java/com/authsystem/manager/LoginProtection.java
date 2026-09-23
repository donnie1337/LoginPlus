package com.authsystem.manager;

import java.util.Locale;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Controla tentativas erradas de login por IP e por combinacao de conta/IP.
 *
 * O limite por IP dificulta ataques simples. O limite conta/IP nao permite que
 * tentativas feitas por um IP bloqueiem globalmente o dono de uma conta.
 */
public class LoginProtection {

    private static final long HISTORICO_EXPIRA_MS = 15 * 60_000L;

    private static class Registro {
        int tentativas;
        long bloqueadoAte; // 0 = nao bloqueado
        long ultimaTentativa;
    }

    private final ConcurrentHashMap<String, Registro> porIp = new ConcurrentHashMap<>();
    // O bloqueio por conta inclui o IP de origem. Um atacante nao pode
    // bloquear globalmente a conta de um administrador com tentativas erradas.
    private final ConcurrentHashMap<String, Registro> porContaEIp = new ConcurrentHashMap<>();

    public boolean estaBloqueado(String ip) {
        return estaBloqueadoNoMapa(porIp, ip);
    }

    public boolean estaBloqueadoConta(String username, String ip) {
        return estaBloqueadoNoMapa(porContaEIp, chaveContaIp(username, ip));
    }

    private boolean estaBloqueadoNoMapa(ConcurrentHashMap<String, Registro> mapa, String chave) {
        if (chave == null || chave.isBlank()) return false;
        Registro r = mapa.get(chave);
        if (r == null) return false;

        long agora = System.currentTimeMillis();
        if (r.bloqueadoAte != 0) {
            if (agora <= r.bloqueadoAte) return true;
            mapa.remove(chave, r);
            return false;
        }

        if (agora - r.ultimaTentativa > HISTORICO_EXPIRA_MS) mapa.remove(chave, r);
        return false;
    }

    public long segundosRestantes(String ip) {
        return segundosRestantesNoMapa(porIp, ip);
    }

    public long segundosRestantesConta(String username, String ip) {
        return segundosRestantesNoMapa(porContaEIp, chaveContaIp(username, ip));
    }

    private long segundosRestantesNoMapa(ConcurrentHashMap<String, Registro> mapa, String chave) {
        if (chave == null || chave.isBlank()) return 0;
        Registro r = mapa.get(chave);
        if (r == null || r.bloqueadoAte == 0) return 0;
        return Math.max(0, (r.bloqueadoAte - System.currentTimeMillis()) / 1000);
    }

    public int registrarErro(String ip, int maxTentativas, long bloqueioMs) {
        return registrarErroNoMapa(porIp, ip, maxTentativas, bloqueioMs);
    }

    public int registrarErroConta(String username, String ip, int maxTentativas, long bloqueioMs) {
        return registrarErroNoMapa(porContaEIp, chaveContaIp(username, ip), maxTentativas, bloqueioMs);
    }

    private int registrarErroNoMapa(ConcurrentHashMap<String, Registro> mapa, String chave, int maxTentativas, long bloqueioMs) {
        if (chave == null || chave.isBlank()) return 0;
        long agora = System.currentTimeMillis();
        Registro r = mapa.compute(chave, (key, atual) -> {
            if (atual == null || agora - atual.ultimaTentativa > HISTORICO_EXPIRA_MS) atual = new Registro();
            atual.tentativas++;
            atual.ultimaTentativa = agora;
            if (atual.tentativas > maxTentativas) atual.bloqueadoAte = agora + bloqueioMs;
            return atual;
        });
        return r.tentativas;
    }

    /** Remove entradas expiradas mesmo quando os mesmos IPs/contas nao voltam a acessar. */
    public void cleanupExpired() {
        long agora = System.currentTimeMillis();
        limparMapa(porIp, agora);
        limparMapa(porContaEIp, agora);
    }

    private void limparMapa(ConcurrentHashMap<String, Registro> mapa, long agora) {
        mapa.entrySet().removeIf(entry -> {
            Registro r = entry.getValue();
            if (r.bloqueadoAte != 0 && agora <= r.bloqueadoAte) return false;
            return agora - r.ultimaTentativa > HISTORICO_EXPIRA_MS;
        });
    }

    public void limparAoLogar(String ip) {
        porIp.remove(ip);
    }

    public void limparContaAoLogar(String username, String ip) {
        porContaEIp.remove(chaveContaIp(username, ip));
    }

    private static String normalizarConta(String username) {
        return username == null ? null : username.toLowerCase(Locale.ROOT);
    }

    private static String chaveContaIp(String username, String ip) {
        String conta = normalizarConta(username);
        if (conta == null || ip == null || ip.isBlank()) return null;
        return conta + "|" + ip;
    }
}
