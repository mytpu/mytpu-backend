package org.mytpu.gateway.access;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Semaphore;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.DisposableBean;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * Фоновые записи учёта ключей (события, подсети): вызов {@code /mcp} их не ждёт и не падает, если БД недоступна.
 * К БД одновременно — не больше {@code mytpu.gateway.key-watch.background-writes} записей (поток событий при атаке
 * не забирает соединения пула у настоящих запросов); всплеск ждёт в очереди до {@code background-queue} задач,
 * сверх неё записи отбрасываются — память не растёт.
 */
@Component
public class BackgroundWriter implements DisposableBean {
    private static final Logger log = LoggerFactory.getLogger(BackgroundWriter.class);

    private final ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor();
    private final Semaphore slots;
    private final int maxQueued;
    private final AtomicInteger queued = new AtomicInteger();
    private final AtomicLong dropped = new AtomicLong();

    public BackgroundWriter(@Value("${mytpu.gateway.key-watch.background-writes:2}") int slots,
                            @Value("${mytpu.gateway.key-watch.background-queue:500}") int maxQueued) {
        this.slots = new Semaphore(slots);
        this.maxQueued = maxQueued;
    }

    public void submit(String what, Runnable task) {
        if (queued.incrementAndGet() > maxQueued) {
            queued.decrementAndGet();
            long total = dropped.incrementAndGet();
            if (Long.bitCount(total) == 1) {
                log.warn("Фоновая запись «{}» отброшена: очередь полна (всего отброшено {})", what, total);
            }
            return;
        }
        executor.execute(() -> {
            try {
                slots.acquire();
                try {
                    task.run();
                } finally {
                    slots.release();
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            } catch (RuntimeException e) {
                log.warn("Фоновая запись «{}» не выполнена: {}", what, e.getMessage());
            } finally {
                queued.decrementAndGet();
            }
        });
    }

    @Override
    public void destroy() {
        executor.close();
    }
}
