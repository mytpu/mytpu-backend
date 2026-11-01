package org.mytpu.gateway.access;

/**
 * Проверка соответствия роли ключа и пути запроса (docs/SECURITY.md, разделы 5 и 14).
 * {@code /chat/**} — только роль {@code chat.service}.
 * {@code /internal/**} и {@code /admin/api/**} — только роли консоли.
 * Ключ {@code chat.service} не имеет доступа к {@code /mcp} и консоли.
 */
public final class PathRoles {
    private PathRoles() {
    }

    public static boolean isChat(String path) {
        return path.equals("/chat") || path.startsWith("/chat/");
    }

    public static boolean isMcp(String path) {
        return path.equals("/mcp") || path.startsWith("/mcp/");
    }

    public static boolean isConsole(String path) {
        return path.startsWith("/internal/") || path.equals("/admin/api") || path.startsWith("/admin/api/");
    }

    public record AccessCheck(boolean allowed, String error, String messageKey) {
        public static AccessCheck ok() {
            return new AccessCheck(true, null, null);
        }

        public static AccessCheck forbidden(String messageKey) {
            return new AccessCheck(false, "insufficient_scope", messageKey);
        }
    }

    public static AccessCheck checkAccess(ApiClient client, String path, String method) {
        if (isChat(path)) {
            if (client.hasChatServiceRole()) {
                return AccessCheck.ok();
            }
            return AccessCheck.forbidden("access.chat_service_role");
        }
        if (client.hasChatServiceRole()) {
            if (isMcp(path) || isConsole(path)) {
                return AccessCheck.forbidden("access.mcp_role");
            }
        }
        if (isConsole(path)) {
            boolean readOnly = "GET".equalsIgnoreCase(method) || "HEAD".equalsIgnoreCase(method);
            boolean allowed = readOnly ? client.hasConsoleRole() : client.roles().contains(ApiClient.CONSOLE_ADMIN);
            if (!allowed) {
                return AccessCheck.forbidden("access.console_role");
            }
        }
        return AccessCheck.ok();
    }
}
