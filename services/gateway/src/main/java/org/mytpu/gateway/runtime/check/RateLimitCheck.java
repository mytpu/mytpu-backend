package org.mytpu.gateway.runtime.check;

import java.time.Duration;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import org.mytpu.gateway.runtime.Messages;
import org.mytpu.gateway.runtime.ToolLimits;
import org.mytpu.tool.api.ErrorCode;
import org.mytpu.tool.api.ToolResult;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

/**
 * Лимит вызовов в минуту на пару «клиент + инструмент» (и «пользователь + инструмент», когда появится вход).
 * Окно — календарная минута; счётчики в памяти с потолком размера.
 */
@Component
@Order(30)
public class RateLimitCheck implements ToolCheck {
    private final Messages messages;
    private final ToolLimits settings;
    private final Cache<String, AtomicInteger> counters = Caffeine.newBuilder()
            .expireAfterWrite(Duration.ofMinutes(2))
            .maximumSize(50_000)
            .build();

    public RateLimitCheck(Messages messages, ToolLimits settings) {
        this.messages = messages;
        this.settings = settings;
    }

    @Override
    public Optional<ToolResult<?>> check(CallState state) {
        String tool = state.tool().name();
        int limit = state.request().policy().rateLimits().getOrDefault(tool, settings.ratePerMinute(state.tool().descriptor()));
        long minute = System.currentTimeMillis() / 60_000;
        String who = state.request().context().user().orElse(state.request().context().clientId());
        int count = counters.get(who + '|' + tool + '|' + minute, k -> new AtomicInteger()).incrementAndGet();
        return count > limit
                ? Optional.of(ToolResult.denied(ErrorCode.RATE_LIMITED, messages.text("error.rate_limited")))
                : Optional.empty();
    }
}
