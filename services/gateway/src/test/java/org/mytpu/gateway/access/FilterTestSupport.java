package org.mytpu.gateway.access;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.util.Locale;

import org.mytpu.gateway.runtime.Messages;
import org.springframework.context.support.StaticMessageSource;
import tools.jackson.databind.json.JsonMapper;

/** Общие заготовки для тестов фильтров: тексты ответов и ключ клиента. */
final class FilterTestSupport {
    private FilterTestSupport() {
    }

    static Messages messages() {
        StaticMessageSource source = new StaticMessageSource();
        source.setUseCodeAsDefaultMessage(true);
        source.addMessage("access.key_lookup_busy", Locale.forLanguageTag("ru"), "занято");
        return new Messages(source);
    }

    static JsonMapper json() {
        return JsonMapper.builder().build();
    }

    static ApiClient client(String id) {
        return client(id, java.util.Set.of());
    }

    static ApiClient client(String id, java.util.Set<String> roles) {
        ApiClient client = mock(ApiClient.class);
        when(client.id()).thenReturn(id);
        when(client.roles()).thenReturn(roles);
        when(client.hasConsoleRole()).thenReturn(roles.contains(ApiClient.CONSOLE_ADMIN) || roles.contains(ApiClient.CONSOLE_VIEWER));
        when(client.hasChatServiceRole()).thenReturn(roles.contains(ApiClient.CHAT_SERVICE));
        return client;
    }
}
