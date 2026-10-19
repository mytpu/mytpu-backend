package org.mytpu.gateway.access;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.util.Base64;

/**
 * Формат мастер-ключа: {@code mtpu_} + 32 случайных байта в base64url. Префикс ловят сканеры секретов и узнаёт консоль.
 * Храним SHA-256: у ключа 256 бит случайности, медленный хэш (как для паролей) не нужен.
 */
final class ApiKeys {
    static final String PREFIX = "mtpu_";
    static final int SHOWN_PREFIX_LENGTH = 12;

    private static final SecureRandom RANDOM = new SecureRandom();

    private ApiKeys() {
    }

    static String generate() {
        byte[] bytes = new byte[32];
        RANDOM.nextBytes(bytes);
        return PREFIX + Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    static byte[] hash(String key) {
        try {
            return MessageDigest.getInstance("SHA-256").digest(key.getBytes(StandardCharsets.UTF_8));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    static String shownPrefix(String key) {
        return key.substring(0, Math.min(SHOWN_PREFIX_LENGTH, key.length()));
    }

    private static final java.util.regex.Pattern FORMAT = java.util.regex.Pattern.compile("mtpu_[A-Za-z0-9_-]{43}");

    /** Точный формат ключа: случайная строка не доходит до БД. */
    static boolean wellFormed(String value) {
        return value != null && FORMAT.matcher(value).matches();
    }
}
