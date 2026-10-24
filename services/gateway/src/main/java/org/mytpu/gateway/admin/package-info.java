/**
 * API консоли администратора ({@code /admin/api/**}, specs/console-and-connectors): по контроллеру на раздел.
 * Доступ (сеть, роль) решает модуль {@code access} до контроллеров; каждое изменение — строка в {@link AdminAuditLog}.
 */
@org.springframework.modulith.ApplicationModule(allowedDependencies = {"access", "catalog", "runtime", "stats", "connectors"})
package org.mytpu.gateway.admin;
