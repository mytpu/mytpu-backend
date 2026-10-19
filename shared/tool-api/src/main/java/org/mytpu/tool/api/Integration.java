package org.mytpu.tool.api;

import java.util.List;

/**
 * Подключённый к шлюзу сервис: расписание, поиск по документам, коннектор из консоли.
 * Новое API = новая реализация этого интерфейса; ядро шлюза при этом не меняется (services/gateway/README.md, «Добавить API кодом»).
 * Набор инструментов изменился на ходу — опубликовать {@link IntegrationChanged}.
 */
public interface Integration {

    /** Короткий id: {@code rasp}, {@code docs}. */
    String id();

    /** Название для консоли: «Расписание ТПУ». */
    String title();

    /** Разрешения, которые открывает интеграция. У каждого инструмента scope — одно из них. */
    List<ScopeInfo> scopes();

    /** Имена нужных секретов; без них интеграция «не настроена» и её инструменты не публикуются. */
    default List<String> requiredSecrets() {
        return List.of();
    }

    default HealthStatus health() {
        return HealthStatus.up();
    }

    List<Tool<?, ?>> tools();
}
