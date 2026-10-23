package org.mytpu.assistant.config;

import java.util.ArrayList;
import java.util.List;

import org.springframework.boot.context.event.ApplicationEnvironmentPreparedEvent;
import org.springframework.context.ApplicationListener;
import org.springframework.core.env.ConfigurableEnvironment;

/**
 * Проверка до запуска контекста: без адреса ml-server, ключа или модели assistant не стартует и говорит, что задать
 * (иначе Spring AI падает невнятной ошибкой URI, а раньше молча брал перепутанные старые переменные).
 */
public class RequiredSettings implements ApplicationListener<ApplicationEnvironmentPreparedEvent> {
    private static final String[][] REQUIRED = {
            {"spring.ai.openai.base-url", "ML_BASE_URL (адрес ml-server с /v1)"},
            {"spring.ai.openai.api-key", "секрет ml_api_key (или ML_API_KEY для запуска без Docker)"},
            {"spring.ai.openai.chat.options.model", "ML_CHAT_MODEL"},
    };

    @Override
    public void onApplicationEvent(ApplicationEnvironmentPreparedEvent event) {
        ConfigurableEnvironment env = event.getEnvironment();
        List<String> missing = new ArrayList<>();
        for (String[] setting : REQUIRED) {
            String value = env.getProperty(setting[0], "");
            if (value.isBlank()) {
                missing.add(setting[1]);
            }
        }
        if (!missing.isEmpty()) {
            throw new IllegalStateException("assistant не запущен: не задано " + String.join(", ", missing));
        }
    }
}
