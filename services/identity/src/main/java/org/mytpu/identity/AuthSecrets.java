package org.mytpu.identity;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.regex.Pattern;

final class AuthSecrets {
    private static final Pattern TOKEN = Pattern.compile("[A-Za-z0-9_-]{43}");
    private static final SecureRandom RANDOM = new SecureRandom();
    private static final Base64.Encoder ENCODER = Base64.getUrlEncoder().withoutPadding();

    private AuthSecrets() {}

    static String generate() {
        byte[] bytes = new byte[32];
        RANDOM.nextBytes(bytes);
        return ENCODER.encodeToString(bytes);
    }

    static boolean valid(String value) {
        return value != null && TOKEN.matcher(value).matches();
    }

    static String challenge(String verifier) {
        try {
            return ENCODER.encodeToString(MessageDigest.getInstance("SHA-256")
                    .digest(verifier.getBytes(StandardCharsets.US_ASCII)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    static boolean matches(String first, String second) {
        return valid(first) && valid(second) && MessageDigest.isEqual(
                first.getBytes(StandardCharsets.US_ASCII), second.getBytes(StandardCharsets.US_ASCII));
    }
}
