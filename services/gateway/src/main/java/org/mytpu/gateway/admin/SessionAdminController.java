package org.mytpu.gateway.admin;

import java.util.Map;
import java.util.TreeSet;

import jakarta.servlet.http.HttpServletRequest;
import org.mytpu.gateway.access.ApiClient;
import org.mytpu.gateway.access.McpKeyFilter;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/** Вход в консоль: интерфейс проверяет ключ и узнаёт роль одним запросом. */
@RestController
public class SessionAdminController {

    @GetMapping("/admin/api/me")
    public Map<String, Object> me(HttpServletRequest request) {
        ApiClient actor = McpKeyFilter.caller(request);
        return Map.of("clientId", actor.id(), "owner", actor.owner(), "roles", new TreeSet<>(actor.roles()),
                "canChange", actor.roles().contains(ApiClient.CONSOLE_ADMIN));
    }
}
