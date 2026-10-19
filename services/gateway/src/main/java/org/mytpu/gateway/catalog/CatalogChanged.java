package org.mytpu.gateway.catalog;

/** Набор инструментов изменился (интеграция добавила или убрала инструмент) — реестр пересобирается на ходу. */
public record CatalogChanged(String integrationId) {
}
