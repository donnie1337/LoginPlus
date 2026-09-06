package com.authsystem.manager;

import java.util.Locale;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Controla tentativas erradas de login por IP e por conta.
 *
 * O limite por IP impede ataques simples e o limite por conta impede que um
 * atacante distribua as tentativas entre varios IPs para atacar a mesma conta.
 */
public class LoginProtection {

    private static final long HISTORICO_EXPIRA_MS = 15 * 60_000L;

    private static class Registro {
        int tentativas;
        long bloqueadoAte; // 0 = nao bloqueado
        long ultimaTentativa;
    }

    private final ConcurrentHashMap<String, Registro> porIp = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, Registro> porConta = new ConcurrentHashMap<>();

    public boolean estaBloqueado(String ip) {
        return estaBloqueadoNoMapa(porIp, ip);
    }

    public boolean estaBloqueadoConta(String username) {
        return estaBloqueadoNoMapa(porConta, normalizarConta(username));
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

    public long segundosRestantesConta(String username) {
        return segundosRestantesNoMapa(porConta, normalizarConta(username));
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

    public int registrarErroConta(String username, int maxTentativas, long bloqueioMs) {
        return registrarErroNoMapa(porConta, normalizarConta(username), maxTentativas, bloqueioMs);
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

    public void limparAoLogar(String ip) {
        porIp.remove(ip);
    }

    public void limparContaAoLogar(String username) {
        porConta.remove(normalizarConta(username));
    }

    private static String normalizarConta(String username) {
        return username == null ? null : username.toLowerCase(Locale.ROOT);
    }
}
