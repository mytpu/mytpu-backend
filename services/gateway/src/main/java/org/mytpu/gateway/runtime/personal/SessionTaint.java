package org.mytpu.gateway.runtime.personal;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import org.mytpu.tool.api.CallContext;
import org.springframework.stereotype.Component;

/**
 * «В этой сессии был недоверенный текст» (правило двух): пара «клиент — пользователь» помечается на
 * {@code taint-window} после ответа инструмента с {@code untrustedOutput}. Тот же признак нужен черновикам
 * (specs/mcp-key-and-agent-safety, часть В). Хранится в памяти экземпляра шлюза.
 */
@Component
public class SessionTaint {
    private final Cache<String, Boolean> tainted;

    public SessionTaint(PersonalProperties properties) {
        this.tainted = Caffeine.newBuilder().expireAfterWrite(properties.taintWindow()).maximumSize(100_000).build();
    }

    public void mark(CallContext context) {
        tainted.put(key(context), Boolean.TRUE);
    }

    public boolean tainted(CallContext context) {
        return tainted.getIfPresent(key(context)) != null;
    }

    private static String key(CallContext context) {
        return context.clientId() + '|' + context.user().orElse("-");
    }
}
