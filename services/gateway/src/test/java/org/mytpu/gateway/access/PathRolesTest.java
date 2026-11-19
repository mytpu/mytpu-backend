package org.mytpu.gateway.access;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Set;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * Роли ключа и пути (docs/SECURITY.md: раздел 14 — {@code chat.service}, раздел 15 — роли консоли).
 */
class PathRolesTest {
    private final ApiClient viewer = FilterTestSupport.client("cli_viewer", Set.of(ApiClient.CONSOLE_VIEWER));
    private final ApiClient admin = FilterTestSupport.client("cli_admin", Set.of(ApiClient.CONSOLE_ADMIN));
    private final ApiClient plain = FilterTestSupport.client("cli_plain");
    private final ApiClient chat = FilterTestSupport.client("cli_chat", Set.of(ApiClient.CHAT_SERVICE));

    private static boolean allowed(ApiClient client, String method, String path) {
        return PathRoles.checkAccess(client, path, method).allowed();
    }

    @ParameterizedTest
    @ValueSource(strings = {"/admin/api", "/admin/api/keys", "/internal/catalog"})
    void viewerReadsConsole(String path) {
        assertThat(allowed(viewer, "GET", path)).isTrue();
    }

    @Test
    void viewerHeadIsAllowed() {
        assertThat(allowed(viewer, "HEAD", "/admin/api/keys")).isTrue();
    }

    @ParameterizedTest
    @ValueSource(strings = {"POST", "PUT", "PATCH", "DELETE", "OPTIONS"})
    void viewerCannotChangeConsole(String method) {
        PathRoles.AccessCheck check = PathRoles.checkAccess(viewer, "/admin/api/keys", method);

        assertThat(check.allowed()).isFalse();
        assertThat(check.error()).isEqualTo("insufficient_scope");
        assertThat(check.messageKey()).isEqualTo("access.console_role");
        assertThat(allowed(viewer, method, "/internal/catalog")).isFalse();
    }

    @ParameterizedTest
    @ValueSource(strings = {"GET", "HEAD", "POST", "PUT", "DELETE"})
    void adminDoesEverythingOnConsole(String method) {
        assertThat(allowed(admin, method, "/admin/api/keys")).isTrue();
        assertThat(allowed(admin, method, "/internal/catalog")).isTrue();
    }

    @ParameterizedTest
    @ValueSource(strings = {"GET", "POST"})
    void keyWithoutConsoleRoleIsForbiddenOnConsole(String method) {
        PathRoles.AccessCheck check = PathRoles.checkAccess(plain, "/admin/api/me", method);

        assertThat(check.allowed()).isFalse();
        assertThat(check.error()).isEqualTo("insufficient_scope");
        assertThat(allowed(plain, method, "/internal/catalog")).isFalse();
        assertThat(allowed(plain, method, "/admin/api")).isFalse();
    }

    @Test
    void chatServiceOnlyOnChat() {
        assertThat(allowed(chat, "POST", "/chat/tools/docs_search")).isTrue();
        assertThat(allowed(chat, "POST", "/chat")).isTrue();
        for (String path : new String[]{"/mcp", "/mcp/key/rotate", "/admin/api/me", "/internal/catalog"}) {
            PathRoles.AccessCheck check = PathRoles.checkAccess(chat, path, "POST");
            assertThat(check.allowed()).as(path).isFalse();
            assertThat(check.error()).isEqualTo("insufficient_scope");
            assertThat(allowed(chat, "GET", path)).as(path).isFalse();
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {ApiClient.CONSOLE_ADMIN, ApiClient.CONSOLE_VIEWER})
    void conflictingRolesCannotUseAnyProtectedChannel(String role) {
        ApiClient mixed = FilterTestSupport.client("cli_mixed", Set.of(ApiClient.CHAT_SERVICE, role));

        assertThat(allowed(mixed, "GET", "/admin/api/me")).isFalse();
        assertThat(allowed(mixed, "GET", "/internal/catalog")).isFalse();
        assertThat(allowed(mixed, "POST", "/mcp")).isFalse();
        assertThat(allowed(mixed, "POST", "/mcp/key/rotate")).isFalse();
        assertThat(allowed(mixed, "POST", "/chat/tools/docs_search")).isFalse();
    }

    @ParameterizedTest
    @ValueSource(strings = {"GET", "POST", "HEAD", "DELETE"})
    void consoleRolesNeverAllowMcp(String method) {
        for (ApiClient client : new ApiClient[]{viewer, admin}) {
            for (String path : new String[]{"/mcp", "/mcp/key/rotate"}) {
                var check = PathRoles.checkAccess(client, path, method);
                assertThat(check.allowed()).as("%s %s %s", client.id(), method, path).isFalse();
                assertThat(check.error()).isEqualTo("insufficient_scope");
                assertThat(check.messageKey()).isEqualTo("access.mcp_role");
            }
        }
    }

    @Test
    void otherKeysAreForbiddenOnChat() {
        for (ApiClient client : new ApiClient[]{plain, viewer, admin}) {
            PathRoles.AccessCheck check = PathRoles.checkAccess(client, "/chat/tools/x", "POST");
            assertThat(check.allowed()).as(client.id()).isFalse();
            assertThat(check.messageKey()).isEqualTo("access.chat_service_role");
        }
    }

    @Test
    void plainKeyUsesMcp() {
        assertThat(allowed(plain, "POST", "/mcp")).isTrue();
        assertThat(allowed(plain, "GET", "/mcp")).isTrue();
        assertThat(allowed(plain, "POST", "/mcp/key/rotate")).isTrue();
    }

    @Test
    void lookalikePathsAreNotConsoleOrChat() {
        assertThat(PathRoles.isConsole("/admin/apix")).isFalse();
        assertThat(PathRoles.isConsole("/internalx/a")).isFalse();
        assertThat(PathRoles.isChat("/chatx")).isFalse();
        assertThat(PathRoles.isMcp("/mcpx")).isFalse();
        assertThat(PathRoles.isConsole("/admin/api")).isTrue();
        assertThat(PathRoles.isMcp("/mcp/key/rotate")).isTrue();
    }
}
