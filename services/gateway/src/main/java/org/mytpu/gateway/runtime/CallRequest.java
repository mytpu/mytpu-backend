package org.mytpu.gateway.runtime;

import org.mytpu.tool.api.CallContext;
import tools.jackson.databind.JsonNode;

/** Вызов инструмента от любой двери: кто, с какими ограничениями, что и с какими аргументами (ещё не проверенными). */
public record CallRequest(CallContext context, CallerPolicy policy, String toolName, JsonNode arguments) {
}
