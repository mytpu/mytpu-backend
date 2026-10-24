package org.mytpu.gateway.connectors;

import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;

import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

/**
 * Секреты коннекторов ({@code connector_secrets}): AES-256-GCM, мастер-ключ сейфа — файл-секрет {@code connector_vault_key}
 * (32 байта в base64url), AAD = id коннектора + имя секрета + origin ({@link ConnectorSpec#secretOrigin()}) — строку нельзя
 * переставить к другому коннектору, а секрет — отправить по другому адресу: версия с иным origin его не получит.
 * Значения не возвращаются ни одним ответом API и не пишутся в журналы; без мастер-ключа секреты недоступны.
 */
@Component
public class ConnectorSecrets {
    private static final int NONCE_BYTES = 12;
    private static final int TAG_BITS = 128;

    private final JdbcClient jdbc;
    private final SecretKeySpec key;
    private final SecureRandom random = new SecureRandom();

    public ConnectorSecrets(JdbcClient jdbc, @Value("${connector_vault_key:}") String vaultKey) {
        this.jdbc = jdbc;
        this.key = vaultKey.isBlank() ? null : new SecretKeySpec(decode(vaultKey.trim()), "AES");
    }

    public boolean available() {
        return key != null;
    }

    public void put(String connectorId, String name, String origin, String value) {
        requireKey();
        byte[] nonce = new byte[NONCE_BYTES];
        random.nextBytes(nonce);
        byte[] sealed = crypt(Cipher.ENCRYPT_MODE, nonce, aad(connectorId, name, origin), value.getBytes(StandardCharsets.UTF_8));
        jdbc.sql("""
                        INSERT INTO connector_secrets (connector_id, name, origin, nonce, ciphertext, updated_at)
                        VALUES (:id, :name, :origin, :nonce, :data, now())
                        ON CONFLICT (connector_id, name) DO UPDATE SET origin = EXCLUDED.origin, nonce = EXCLUDED.nonce,
                            ciphertext = EXCLUDED.ciphertext, updated_at = now()
                        """)
                .param("id", connectorId).param("name", name).param("origin", origin).param("nonce", nonce).param("data", sealed).update();
    }

    /** Секрет для этого origin; задан для другого — пусто (см. {@link #exists}). */
    public Optional<String> get(String connectorId, String name, String origin) {
        if (key == null) {
            return Optional.empty();
        }
        return jdbc.sql("SELECT nonce, ciphertext FROM connector_secrets WHERE connector_id = :id AND name = :name AND origin = :origin")
                .param("id", connectorId).param("name", name).param("origin", origin)
                .query((rs, n) -> new String(crypt(Cipher.DECRYPT_MODE, rs.getBytes("nonce"), aad(connectorId, name, origin),
                        rs.getBytes("ciphertext")), StandardCharsets.UTF_8))
                .optional();
    }

    /** Какие секреты заданы для этого origin — только имена. */
    public Set<String> names(String connectorId, String origin) {
        return new TreeSet<>(jdbc.sql("SELECT name FROM connector_secrets WHERE connector_id = :id AND origin = :origin")
                .param("id", connectorId).param("origin", origin).query(String.class).list());
    }

    public boolean exists(String connectorId, String name) {
        return jdbc.sql("SELECT count(*) FROM connector_secrets WHERE connector_id = :id AND name = :name")
                .param("id", connectorId).param("name", name).query(Long.class).single() > 0;
    }

    public boolean delete(String connectorId, String name) {
        return jdbc.sql("DELETE FROM connector_secrets WHERE connector_id = :id AND name = :name")
                .param("id", connectorId).param("name", name).update() > 0;
    }

    private byte[] crypt(int mode, byte[] nonce, byte[] aad, byte[] data) {
        try {
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(mode, key, new GCMParameterSpec(TAG_BITS, nonce));
            cipher.updateAAD(aad);
            return cipher.doFinal(data);
        } catch (GeneralSecurityException e) {
            // Подделанная строка или чужой мастер-ключ: секрет не расшифровывается — коннектор не вызывается
            throw new IllegalStateException("секрет коннектора не расшифрован", e);
        }
    }

    private void requireKey() {
        if (key == null) {
            throw new IllegalStateException("нет секрета connector_vault_key — секреты коннекторов недоступны");
        }
    }

    private static byte[] aad(String connectorId, String name, String origin) {
        return (connectorId + "\u0000" + name + "\u0000" + origin).getBytes(StandardCharsets.UTF_8);
    }

    private static byte[] decode(String value) {
        byte[] bytes = Base64.getUrlDecoder().decode(value.replace('+', '-').replace('/', '_'));
        if (bytes.length != 32) {
            throw new IllegalStateException("connector_vault_key — 32 байта в base64url (bash deploy/dev-secrets.sh)");
        }
        return bytes;
    }
}
