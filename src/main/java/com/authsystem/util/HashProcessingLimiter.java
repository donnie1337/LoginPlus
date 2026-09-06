package com.authsystem.util;

import java.util.concurrent.atomic.AtomicInteger;

/** Limita globalmente os PBKDF2 caros para evitar saturacao de CPU por muitos jogadores. */
public final class HashProcessingLimiter {
    private final AtomicInteger ativos = new AtomicInteger();

    public boolean tryAcquire(int limite) {
        int maximo = Math.max(1, limite);
        while (true) {
            int atual = ativos.get();
            if (atual >= maximo) return false;
            if (ativos.compareAndSet(atual, atual + 1)) return true;
        }
    }

    public void release() {
        ativos.updateAndGet(valor -> Math.max(0, valor - 1));
    }

    public int getActive() {
        return ativos.get();
    }
}
