package com.authsystem.manager;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.concurrent.ConcurrentHashMap;

/** Limita consultas de verificacao premium por IP para evitar abuso da Mojang. */
public final class MojangRateLimiter {
    private static final long JANELA_MS = 60_000L;
    private final ConcurrentHashMap<String, Deque<Long>> consultasPorIp = new ConcurrentHashMap<>();

    public boolean podeConsultar(String ip, int limitePorMinuto) {
        if (ip == null || ip.isBlank() || limitePorMinuto <= 0) {
            return true;
        }

        long agora = System.currentTimeMillis();
        Deque<Long> consultas = consultasPorIp.computeIfAbsent(ip, chave -> new ArrayDeque<>());
        synchronized (consultas) {
            removerExpiradas(consultas, agora);
            if (consultas.size() >= limitePorMinuto) {
                return false;
            }
            consultas.addLast(agora);
            return true;
        }
    }

    public void limpar(String ip) {
        if (ip != null) {
            consultasPorIp.remove(ip);
        }
    }

    /** Remove entradas de IP que nao possuem consultas dentro da janela atual. */
    public void cleanupExpired() {
        long agora = System.currentTimeMillis();
        consultasPorIp.entrySet().removeIf(entry -> {
            Deque<Long> consultas = entry.getValue();
            synchronized (consultas) {
                removerExpiradas(consultas, agora);
                return consultas.isEmpty();
            }
        });
    }

    private static void removerExpiradas(Deque<Long> consultas, long agora) {
        while (!consultas.isEmpty() && agora - consultas.peekFirst() >= JANELA_MS) {
            consultas.removeFirst();
        }
    }
}
