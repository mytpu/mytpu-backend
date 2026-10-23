package org.mytpu.gateway.access;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Semaphore;
import java.util.concurrent.atomic.AtomicLong;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.DisposableBean;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * Фоновые записи учёта ключей (события, подсети): вызов {@code /mcp} их не ждёт и не падает, если БД недоступна.
 * Одновременно — не больше {@code mytpu.gateway.key-watch.background-writes} задач, лишние отбрасываются:
 * поток событий при атаке не забирает соединения пула у настоящих запросов.
 */
@Component
public class BackgroundWriter implements DisposableBean {
    private static final Logger log = LoggerFactory.getLogger(BackgroundWriter.class);

    private final ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor();
    private final Semaphore slots;
    private final AtomicLong dropped = new AtomicLong();

    public BackgroundWriter(@Value("${mytpu.gateway.key-watch.background-writes:2}") int slots) {
        this.slots = new Semaphore(slots);
    }

    public void submit(String what, Runnable task) {
        if (!slots.tryAcquire()) {
            long total = dropped.incrementAndGet();
            if (Long.bitCount(total) == 1) {
                log.warn("Фоновая запись «{}» отброшена: очередь занята (всего отброшено {})", what, total);
            }
            return;
        }
        executor.execute(() -> {
            try {
                task.run();
            } catch (RuntimeException e) {
                log.warn("Фоновая запись «{}» не выполнена: {}", what, e.getMessage());
            } finally {
                slots.release();
            }
        });
    }

    @Override
    public void destroy() {
        executor.close();
    }
}
