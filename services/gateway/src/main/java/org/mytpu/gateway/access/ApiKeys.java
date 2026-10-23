package org.mytpu.gateway.access;

import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.util.regex.Pattern;
import java.util.zip.CRC32;

/**
 * Формат мастер-ключа v2: {@code mtpu_} + 43 символа base62 (32 случайных байта) + 6 символов base62 CRC32.
 * Контрольная сумма отсекает опечатки и мусор до запроса к БД и снижает ложные срабатывания сканеров секретов.
 * Ключи v1 ({@code mtpu_} + 43 символа base64url) действуют до своего срока, новые не выдаются.
 * Храним SHA-256: у ключа 256 бит случайности, медленный хэш (как для паролей) не нужен.
 */
final class ApiKeys {
    static final String PREFIX = "mtpu_";
    static final int SHOWN_PREFIX_LENGTH = 12;

    private static final String BASE62 = "0123456789ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz";
    private static final int BODY_LENGTH = 43;
    private static final int CHECKSUM_LENGTH = 6;
    private static final Pattern V1 = Pattern.compile("mtpu_[A-Za-z0-9_-]{43}");
    private static final Pattern V2 = Pattern.compile("mtpu_[0-9A-Za-z]{49}");
    private static final SecureRandom RANDOM = new SecureRandom();

    private ApiKeys() {
    }

    static String generate() {
        byte[] bytes = new byte[32];
        RANDOM.nextBytes(bytes);
        String body = PREFIX + base62(new BigInteger(1, bytes), BODY_LENGTH);
        return body + checksum(body);
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

    static boolean isV1(String value) {
        return value != null && V1.matcher(value).matches();
    }

    /** Точный формат ключа и, для v2, верная контрольная сумма: случайная строка не доходит до БД. */
    static boolean wellFormed(String value) {
        if (value == null) {
            return false;
        }
        if (V2.matcher(value).matches()) {
            int split = value.length() - CHECKSUM_LENGTH;
            return checksum(value.substring(0, split)).equals(value.substring(split));
        }
        return V1.matcher(value).matches();
    }

    private static String checksum(String body) {
        CRC32 crc = new CRC32();
        crc.update(body.getBytes(StandardCharsets.US_ASCII));
        return base62(BigInteger.valueOf(crc.getValue()), CHECKSUM_LENGTH);
    }

    private static String base62(BigInteger value, int length) {
        BigInteger base = BigInteger.valueOf(BASE62.length());
        char[] out = new char[length];
        for (int i = length - 1; i >= 0; i--) {
            BigInteger[] qr = value.divideAndRemainder(base);
            out[i] = BASE62.charAt(qr[1].intValue());
            value = qr[0];
        }
        if (value.signum() != 0) {
            throw new IllegalStateException("Число не помещается в " + length + " символов base62");
        }
        return new String(out);
    }
}
