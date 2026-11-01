package org.mytpu.gateway.access;

import java.util.Optional;

/**
 * Итог поиска ключа: найден, не найден или «занято» — бюджет поисков в БД на эту секунду исчерпан. «Занято» нельзя
 * отвечать как «не найден»: клиент MCP счёл бы настоящий ключ отозванным.
 */
public record KeyLookup(Status status, ApiClient client) {
    public enum Status { FOUND, NOT_FOUND, BUSY }

    static final KeyLookup NOT_FOUND = new KeyLookup(Status.NOT_FOUND, null);
    static final KeyLookup BUSY = new KeyLookup(Status.BUSY, null);

    static KeyLookup found(ApiClient client) {
        return new KeyLookup(Status.FOUND, client);
    }

    public boolean busy() {
        return status == Status.BUSY;
    }

    public Optional<ApiClient> found() {
        return Optional.ofNullable(client);
    }
}
