package org.mytpu.gateway.runtime;

import java.time.Duration;
import java.util.Map;

import org.mytpu.tool.api.ToolDescriptor;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Лимиты инструментов из {@code application.yml} ({@code mytpu.gateway.tools.limits.<имя>.*}): срок кэша, таймаут, вызовов в минуту.
 * Что не задано — берётся из описания инструмента. Так значения меняются без пересборки (позже — и из консоли).
 */
@ConfigurationProperties("mytpu.gateway.tools")
public record ToolSettings(Map<String, Limits> limits) {

    public record Limits(Duration cacheTtl, Duration timeout, Integer ratePerMinute) {
    }

    public ToolSettings {
        limits = limits == null ? Map.of() : Map.copyOf(limits);
    }

    public Duration cacheTtl(ToolDescriptor descriptor) {
        Limits l = limits.get(descriptor.name());
        return l != null && l.cacheTtl() != null ? l.cacheTtl() : descriptor.cacheTtl();
    }

    public Duration timeout(ToolDescriptor descriptor) {
        Limits l = limits.get(descriptor.name());
        return l != null && l.timeout() != null ? l.timeout() : descriptor.timeout();
    }

    public int ratePerMinute(ToolDescriptor descriptor) {
        Limits l = limits.get(descriptor.name());
        return l != null && l.ratePerMinute() != null ? l.ratePerMinute() : descriptor.ratePerMinute();
    }
}
