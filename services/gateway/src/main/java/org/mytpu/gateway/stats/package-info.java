/**
 * Чтение журналов для консоли: обзор по поминутным агрегатам, журнал вызовов и действий администратора.
 * Только чтение; пишет журналы модуль {@code runtime} (вызовы) и {@code admin} (действия).
 */
@org.springframework.modulith.ApplicationModule(allowedDependencies = "runtime")
package org.mytpu.gateway.stats;
