package org.mytpu.gateway.access;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class StrictPathFilterTest {

    @ParameterizedTest
    @ValueSource(strings = {
            "/mcp/..;/admin/api/me", "/mcp;x", "/mcp/../admin/api/me", "/mcp/./x", "/mcp/%2e%2e/admin",
            "/mcp%2F..%2Fadmin", "/mcp/%2E%2E/admin", "//admin/api/me", "/mcp\\..\\admin", "/mcp/%3b", "/mcp/%252e",
            "/..", "/.", ""})
    void rejectsAmbiguousPaths(String uri) {
        assertThat(StrictPathFilter.suspicious(uri)).isTrue();
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "/mcp", "/mcp/key/rotate", "/actuator/health", "/actuator/health/liveness", "/admin/api/me",
            "/admin/api/keys/cli_1ddbb49d040d4494/decoy-group", "/internal/catalog", "/mcp/v1.2/x", "/a..b"})
    void allowsNormalPaths(String uri) {
        assertThat(StrictPathFilter.suspicious(uri)).isFalse();
    }
}
