/**
 * Интеграции с сервисами ТПУ. Знают только {@code runtime} (тексты, общие типы) и контракт tool-api;
 * о смене набора инструментов сообщают событием {@link org.mytpu.tool.api.IntegrationChanged}.
 */
@org.springframework.modulith.ApplicationModule(allowedDependencies = "runtime")
package org.mytpu.gateway.integrations;
