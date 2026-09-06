package com.authsystem.util;

import javax.crypto.SecretKeyFactory;
import javax.crypto.spec.PBEKeySpec;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.security.spec.InvalidKeySpecException;
import java.util.Base64;

/**
 * Gera e verifica hashes de senha usando PBKDF2WithHmacSHA256, que ja vem
 * embutido no Java (nao precisa de nenhuma biblioteca externa como o BCrypt).
 * Nunca guardamos a senha em texto puro, apenas o hash + o salt aleatorio.
 */
public final class PasswordUtils {

    public static final int DEFAULT_MIN_PASSWORD_LENGTH = 7;
    public static final int DEFAULT_MAX_PASSWORD_LENGTH = 16;

    private static final int ITERATIONS = 65536;
    private static final int KEY_LENGTH_BITS = 256;
    private static final SecureRandom RANDOM = new SecureRandom();

    private PasswordUtils() {
    }

    /**
     * Valida todas as regras de uma senha de cadastro usando os limites configurados no plugin.
     * Retorna uma mensagem pronta quando houver erro, ou null quando a senha for valida.
     */
    public static String validatePassword(String password, int minLength, int maxLength) {
        if (password == null) {
            return "Senha invalida.";
        }

        if (minLength < 1 || maxLength < minLength) {
            return "Configuracao de senha invalida.";
        }

        if (password.length() < minLength) {
            return "Sua senha precisa ter pelo menos " + minLength + " caracteres.";
        }

        if (password.length() > maxLength) {
            return "Sua senha pode ter no maximo " + maxLength + " caracteres.";
        }

        if (!password.matches("^[A-Za-z0-9]+$")) {
            return "Sua senha pode conter apenas letras e numeros, sem espacos ou caracteres especiais.";
        }

        if (!password.matches(".*[A-Za-z].*") || !password.matches(".*[0-9].*")) {
            return "Sua senha precisa conter pelo menos uma letra e um numero.";
        }

        return null;
    }

    public static String generateSalt() {
        byte[] salt = new byte[16];
        RANDOM.nextBytes(salt);
        return Base64.getEncoder().encodeToString(salt);
    }

    public static String hash(String password, String saltBase64) {
        try {
            byte[] salt = Base64.getDecoder().decode(saltBase64);
            PBEKeySpec spec = new PBEKeySpec(password.toCharArray(), salt, ITERATIONS, KEY_LENGTH_BITS);
            SecretKeyFactory factory = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256");
            byte[] hashBytes = factory.generateSecret(spec).getEncoded();
            return Base64.getEncoder().encodeToString(hashBytes);
        } catch (NoSuchAlgorithmException | InvalidKeySpecException e) {
            throw new RuntimeException("Erro ao gerar hash da senha", e);
        }
    }

    public static boolean verify(String password, String saltBase64, String expectedHash) {
        String actualHash = hash(password, saltBase64);
        return constantTimeEquals(actualHash, expectedHash);
    }

    /** Comparacao em tempo constante para evitar timing attacks. */
    private static boolean constantTimeEquals(String a, String b) {
        if (a.length() != b.length()) {
            return false;
        }
        int result = 0;
        for (int i = 0; i < a.length(); i++) {
            result |= a.charAt(i) ^ b.charAt(i);
        }
        return result == 0;
    }
}
