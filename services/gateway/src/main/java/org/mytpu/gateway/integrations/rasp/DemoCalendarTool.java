package org.mytpu.gateway.integrations.rasp;

import java.time.LocalDate;
import java.time.format.DateTimeParseException;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import org.mytpu.gateway.runtime.Messages;
import org.mytpu.tool.api.CallContext;
import org.mytpu.tool.api.Channel;
import org.mytpu.tool.api.ErrorCode;
import org.mytpu.tool.api.Tool;
import org.mytpu.tool.api.ToolDescriptor;
import org.mytpu.tool.api.ToolResult;

final class DemoCalendarTool implements Tool<DemoCalendarTool.Input, DemoCalendar.Answer> {
    private final ToolDescriptor descriptor;
    private final boolean weekly;
    private final DemoCalendar calendar;
    private final Messages messages;

    DemoCalendarTool(String name, boolean weekly, DemoCalendar calendar, Messages messages) {
        this.descriptor = ToolDescriptor.read(name, RaspIntegration.SCOPE)
                .input(Input.class).output(DemoCalendar.Answer.class).build();
        this.weekly = weekly;
        this.calendar = calendar;
        this.messages = messages;
    }

    public record Input(@NotBlank @Size(max = 100) String group,
                        @NotBlank @Pattern(regexp = "[0-9]{4}-[0-9]{2}-[0-9]{2}") String date) { }

    @Override
    public ToolDescriptor descriptor() {
        return descriptor;
    }

    @Override
    public boolean availableOn(Channel channel) {
        return channel == Channel.CHAT;
    }

    @Override
    public ToolResult<DemoCalendar.Answer> call(CallContext context, Input input) {
        LocalDate date;
        try {
            date = LocalDate.parse(input.date());
        } catch (DateTimeParseException error) {
            return ToolResult.failed(ErrorCode.INVALID_ARGUMENTS, messages.text("rasp.demo.invalid_date"));
        }
        return calendar.read(input.group(), date, weekly)
                .<ToolResult<DemoCalendar.Answer>>map(answer -> ToolResult.ok(messages.text("rasp.demo.summary"), answer))
                .orElseGet(() -> ToolResult.denied(ErrorCode.NOT_FOUND, messages.text("rasp.demo.not_found")));
    }
}
