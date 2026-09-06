package com.authsystem.manager;

import java.util.concurrent.ConcurrentHashMap;

/**
 * Controla tentativas erradas de login por ENDERECO DE IP (nao por sessao).
 *
 * Isso e essencial pro sistema anti-bypass: se contassemos as tentativas
 * apenas na sessao do jogador (como no SessionManager), bastaria a pessoa
 * se desconectar e reconectar para "zerar" o contador e tentar de novo
 * infinitamente. Guardando por IP e com um tempo de bloqueio que sobrevive
 * a desconexao, isso deixa de ser possivel.
 */
public class LoginProtection {

    private static final long HISTORICO_EXPIRA_MS = 15 * 60_000L;

    private static class Registro {
        int tentativas;
        long bloqueadoAte; // 0 = nao bloqueado
        long ultimaTentativa;
    }

    private final ConcurrentHashMap<String, Registro> porIp = new ConcurrentHashMap<>();

    /** true se esse IP ainda esta no periodo de bloqueio. */
    public boolean estaBloqueado(String ip) {
        Registro r = porIp.get(ip);
        if (r == null) {
            return false;
        }

        long agora = System.currentTimeMillis();
        if (r.bloqueadoAte != 0) {
            if (agora <= r.bloqueadoAte) {
                return true;
            }
            porIp.remove(ip, r);
            return false;
        }

        // Erros antigos sem bloqueio nao precisam permanecer em memoria.
        if (agora - r.ultimaTentativa > HISTORICO_EXPIRA_MS) {
            porIp.remove(ip, r);
        }
        return false;
    }

    public long segundosRestantes(String ip) {
        Registro r = porIp.get(ip);
        if (r == null || r.bloqueadoAte == 0) {
            return 0;
        }
        return Math.max(0, (r.bloqueadoAte - System.currentTimeMillis()) / 1000);
    }

    /**
     * Registra mais um erro de senha para o IP informado.
     * Retorna o total de tentativas erradas acumuladas.
     * Se ultrapassar maxTentativas, o IP e bloqueado por bloqueioMs.
     */
    public int registrarErro(String ip, int maxTentativas, long bloqueioMs) {
        long agora = System.currentTimeMillis();
        Registro r = porIp.compute(ip, (chave, atual) -> {
            if (atual == null || agora - atual.ultimaTentativa > HISTORICO_EXPIRA_MS) {
                atual = new Registro();
            }
            atual.tentativas++;
            atual.ultimaTentativa = agora;
            if (atual.tentativas > maxTentativas) {
                atual.bloqueadoAte = agora + bloqueioMs;
            }
            return atual;
        });
        return r.tentativas;
    }

    /** Chamado quando o login e feito com sucesso, para limpar o historico do IP. */
    public void limparAoLogar(String ip) {
        porIp.remove(ip);
    }
}
