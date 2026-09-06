package com.authsystem.util;

import javax.crypto.SecretKeyFactory;
import javax.crypto.spec.PBEKeySpec;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.security.spec.InvalidKeySpecException;
import java.util.Base64;

/** Gera e verifica hashes de senha usando PBKDF2 com salt aleatorio. */
public final class PasswordUtils {
    public static final int DEFAULT_MIN_PASSWORD_LENGTH = 7;
    public static final int DEFAULT_MAX_PASSWORD_LENGTH = 16;
    public static final int DEFAULT_ITERATIONS = 10_000;
    public static final int MIN_ITERATIONS = 100;
    public static final int MAX_ITERATIONS = 10_000;
    public static final int LEGACY_ITERATIONS = 65_536;
    private static final int KEY_LENGTH_BITS = 256;
    private static final SecureRandom RANDOM = new SecureRandom();

    private PasswordUtils() {}

    /** Valida as regras de senha definidas no config.yml. */
    public static String validatePassword(String password, int minLength, int maxLength) {
        if (password == null) return "Senha invalida.";
        if (minLength < 1 || maxLength < minLength) return "Configuracao de senha invalida.";
        if (password.length() < minLength) return "Sua senha precisa ter pelo menos " + minLength + " caracteres.";
        if (password.length() > maxLength) return "Sua senha pode ter no maximo " + maxLength + " caracteres.";
        if (!password.matches("^[A-Za-z0-9]+$")) return "Sua senha pode conter apenas letras e numeros, sem espacos ou caracteres especiais.";
        if (!password.matches(".*[A-Za-z].*") || !password.matches(".*[0-9].*")) return "Sua senha precisa conter pelo menos uma letra e um numero.";
        return null;
    }

    public static String generateSalt() {
        byte[] salt = new byte[16];
        RANDOM.nextBytes(salt);
        return Base64.getEncoder().encodeToString(salt);
    }

    public static String hash(String password, String saltBase64) {
        return hash(password, saltBase64, DEFAULT_ITERATIONS);
    }

    public static String hash(String password, String saltBase64, int iterations) {
        try {
            if (iterations < MIN_ITERATIONS || iterations > MAX_ITERATIONS) {
                throw new IllegalArgumentException("Numero de iteracoes PBKDF2 fora do intervalo permitido: " + iterations);
            }
            byte[] salt = Base64.getDecoder().decode(saltBase64);
            PBEKeySpec spec = new PBEKeySpec(password.toCharArray(), salt, iterations, KEY_LENGTH_BITS);
            try {
                SecretKeyFactory factory = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256");
                return Base64.getEncoder().encodeToString(factory.generateSecret(spec).getEncoded());
            } finally {
                spec.clearPassword();
            }
        } catch (IllegalArgumentException | NoSuchAlgorithmException | InvalidKeySpecException e) {
            throw new RuntimeException("Erro ao gerar hash da senha", e);
        }
    }

    public static boolean verify(String password, String saltBase64, String expectedHash, int iterations) {
        if (password == null || saltBase64 == null || expectedHash == null || iterations < 1) return false;
        try {
            byte[] actual = Base64.getDecoder().decode(hash(password, saltBase64, iterations));
            byte[] expected = Base64.getDecoder().decode(expectedHash);
            return MessageDigest.isEqual(actual, expected);
        } catch (IllegalArgumentException | RuntimeException e) {
            return false;
        }
    }
}
