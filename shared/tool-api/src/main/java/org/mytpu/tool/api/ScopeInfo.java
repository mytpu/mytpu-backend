package org.mytpu.tool.api;

/**
 * Разрешение, которое включается мастер-ключу.
 *
 * @param name        {@code rasp.read}
 * @param title       «Расписание»
 * @param description для консоли и экрана согласия: что именно откроется
 */
public record ScopeInfo(String name, String title, String description) {
}
