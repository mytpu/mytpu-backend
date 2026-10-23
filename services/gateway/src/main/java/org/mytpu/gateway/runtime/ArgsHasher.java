package org.mytpu.gateway.runtime;

import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.util.HexFormat;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.SerializationFeature;
import tools.jackson.databind.json.JsonMapper;

/**
 * Отпечаток аргументов вызова для журнала ({@code audit.args_hash}): HMAC-SHA256 с ключом шлюза (секрет
 * {@code gateway_audit_key}). Обычный SHA-256 по журналу обращается перебором, если в аргументах короткое значение
 * (номер студента, группа); без ключа — нет. Канонический JSON (ключи по алфавиту) — один отпечаток у отказа и успеха.
 */
@Component
public class ArgsHasher {
    private static final String ALGORITHM = "HmacSHA256";

    private final SecretKeySpec key;
    private final JsonMapper json;

    public ArgsHasher(@Value("${gateway_audit_key:}") String key, JsonMapper json) {
        if (key.isBlank()) {
            throw new IllegalStateException("gateway не запущен: нет секрета gateway_audit_key (bash deploy/dev-secrets.sh "
                    + "или файл в /run/secrets/) — без него отпечатки аргументов в журнале обратимы перебором");
        }
        this.key = new SecretKeySpec(key.trim().getBytes(StandardCharsets.UTF_8), ALGORITHM);
        this.json = json;
    }

    public String of(JsonNode arguments) {
        Object plain = arguments == null ? null : json.treeToValue(arguments, Object.class);
        String canonical = json.writer().with(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS).writeValueAsString(plain);
        try {
            Mac mac = Mac.getInstance(ALGORITHM);
            mac.init(key);
            return HexFormat.of().formatHex(mac.doFinal(canonical.getBytes(StandardCharsets.UTF_8)));
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException(e);
        }
    }
}
