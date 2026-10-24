package org.mytpu.gateway.runtime.check;

import java.util.Comparator;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

import jakarta.validation.ConstraintViolation;
import jakarta.validation.Validator;
import org.mytpu.gateway.runtime.DescribedInput;
import org.mytpu.gateway.runtime.Messages;
import org.mytpu.tool.api.ErrorCode;
import org.mytpu.tool.api.ToolResult;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.DatabindException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.JsonNodeFactory;

/**
 * Аргументы разбираются в record инструмента и проверяются Bean Validation. Ошибка — инструкция с именем поля:
 * слабая модель по ней исправляет вызов (docs/LLM.md, раздел 4). Лишние поля игнорируются.
 */
@Component
@Order(40)
public class ArgumentsCheck implements ToolCheck {
    private final JsonMapper json;
    private final Validator validator;
    private final Messages messages;

    public ArgumentsCheck(JsonMapper json, Validator validator, Messages messages) {
        this.json = json;
        this.validator = validator;
        this.messages = messages;
    }

    @Override
    public Optional<ToolResult<?>> check(CallState state) {
        JsonNode args = state.request().arguments() == null ? JsonNodeFactory.instance.objectNode() : state.request().arguments();
        if (state.tool().tool() instanceof DescribedInput described) {
            DescribedInput.Validated validated = described.validate(args);
            if (!validated.errors().isEmpty()) {
                return invalid(String.join("; ", validated.errors()));
            }
            state.input(validated.input());
            return Optional.empty();
        }
        Object input;
        try {
            input = json.treeToValue(args, state.tool().descriptor().inputType());
        } catch (DatabindException e) {
            String field = e.getPath().isEmpty() ? "аргументы" : e.getPath().getLast().getPropertyName();
            return invalid(messages.text("error.invalid_arguments.type", field));
        } catch (JacksonException e) {
            return invalid(messages.text("error.invalid_arguments.json"));
        }
        Set<ConstraintViolation<Object>> violations = validator.validate(input);
        if (!violations.isEmpty()) {
            String details = violations.stream()
                    .sorted(Comparator.comparing(v -> v.getPropertyPath().toString()))
                    .map(v -> v.getPropertyPath() + ": " + v.getMessage())
                    .collect(Collectors.joining("; "));
            return invalid(details);
        }
        state.input(input);
        return Optional.empty();
    }

    private Optional<ToolResult<?>> invalid(String details) {
        return Optional.of(ToolResult.denied(ErrorCode.INVALID_ARGUMENTS, messages.text("error.invalid_arguments", details)));
    }
}
