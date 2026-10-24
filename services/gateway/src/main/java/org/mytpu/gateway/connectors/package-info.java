/**
 * Коннекторы из консоли: простое REST/JSON-API без кода (specs/console-and-connectors). Это ещё одна интеграция
 * ({@link org.mytpu.gateway.connectors.ConnectorIntegration}) — ядро шлюза не меняется. Только {@code GET},
 * защита от SSRF в DNS-резолвере HTTP-клиента, секреты — AES-256-GCM.
 */
@org.springframework.modulith.ApplicationModule(allowedDependencies = "runtime")
package org.mytpu.gateway.connectors;
