package com.authsystem.util;

import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URI;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.Scanner;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Logger;

/**
 * Verifica, de forma heuristica, se um nome de jogador pertence a uma
 * conta Minecraft original (premium), consultando a API publica da Mojang.
 *
 * ATENCAO - leia isso com calma:
 * Essa verificacao confirma apenas que o NOME existe como conta premium
 * na Mojang. Como o servidor roda em offline-mode (para aceitar tambem
 * jogadores "piratas"), ela NAO prova de forma criptografica que quem
 * esta conectando agora e o verdadeiro dono daquela conta - alguem
 * poderia, em teoria, digitar o nick de outra pessoa. E a abordagem mais
 * simples e mais usada em plugins desse tipo, mas nao e 100% inviolavel.
 *
 * Para uma verificacao realmente segura seria necessario reimplementar o
 * handshake de autenticacao da Mojang (o que plugins como o FastLogin
 * fazem, usando ProtocolLib para interceptar os pacotes de criptografia
 * durante o login). Isso foge do escopo de um plugin simples de login.
 */
public final class PremiumChecker {

    private static final Logger LOGGER = Logger.getLogger("AuthSystem");
    private static final String API_URL = "https://api.mojang.com/users/profiles/minecraft/";
    private static final ConcurrentHashMap<String, Boolean> CACHE = new ConcurrentHashMap<>();

    private PremiumChecker() {
    }

    /**
     * Chamada BLOQUEANTE (faz uma requisicao HTTP de verdade).
     * So deve ser chamada dentro do AsyncPlayerPreLoginEvent, que ja roda
     * fora da thread principal do servidor - por isso e seguro bloquear aqui.
     */
    public static boolean isPremium(String username) {
        String key = username.toLowerCase();
        Boolean cached = CACHE.get(key);
        if (cached != null) {
            return cached;
        }

        boolean premium = false;
        HttpURLConnection connection = null;
        try {
            URL url = URI.create(API_URL + username).toURL();
            connection = (HttpURLConnection) url.openConnection();
            connection.setRequestMethod("GET");
            connection.setConnectTimeout(4000);
            connection.setReadTimeout(4000);

            int status = connection.getResponseCode();
            if (status == 200) {
                try (InputStream in = connection.getInputStream();
                     Scanner scanner = new Scanner(in, StandardCharsets.UTF_8).useDelimiter("\\A")) {
                    String body = scanner.hasNext() ? scanner.next() : "";
                    premium = body.contains("\"id\"");
                }
            }
            // status 204/404 => nao existe conta premium com esse nome -> tratado como "pirata"
        } catch (Exception e) {
            LOGGER.warning("Nao foi possivel verificar se '" + username + "' e premium: " + e.getMessage());
            premium = false; // em caso de falha de rede, por seguranca, exige login/registro normal
        } finally {
            if (connection != null) {
                connection.disconnect();
            }
        }

        CACHE.put(key, premium);
        return premium;
    }

    public static void clearCache(String username) {
        CACHE.remove(username.toLowerCase());
    }
}
