package org.mytpu.gateway.admin;

import java.io.BufferedWriter;
import java.io.OutputStreamWriter;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.List;
import java.util.Set;

import org.mytpu.gateway.stats.JournalQueries;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.StreamingResponseBody;

/**
 * Журнал вызовов (фильтры по времени, клиенту, инструменту, итогу; аргументов нет — только отпечаток), его выгрузка CSV
 * и журнал действий администратора. Страницы — «новые сверху», следующая — с {@code before} = id последней строки.
 */
@RestController
public class AuditAdminController {
    private static final Set<String> OUTCOMES = Set.of("ok", "needs_choice", "denied", "failed");
    private static final int MAX_CSV_ROWS = 100_000;
    private static final Duration DEFAULT_WINDOW = Duration.ofDays(1);
    private static final Duration MAX_WINDOW = Duration.ofDays(180);

    private final JournalQueries journal;

    public AuditAdminController(JournalQueries journal) {
        this.journal = journal;
    }

    @GetMapping("/admin/api/audit")
    public List<JournalQueries.CallRow> calls(@RequestParam(required = false) String from, @RequestParam(required = false) String to,
                                              @RequestParam(required = false) String client, @RequestParam(required = false) String tool,
                                              @RequestParam(required = false) String outcome, @RequestParam(required = false) Long before,
                                              @RequestParam(defaultValue = "50") int limit) {
        return journal.calls(filter(from, to, client, tool, outcome, before), page(limit));
    }

    @GetMapping("/admin/api/audit.csv")
    public ResponseEntity<StreamingResponseBody> csv(@RequestParam(required = false) String from, @RequestParam(required = false) String to,
                                                     @RequestParam(required = false) String client, @RequestParam(required = false) String tool,
                                                     @RequestParam(required = false) String outcome) {
        JournalQueries.CallFilter filter = filter(from, to, client, tool, outcome, null);
        StreamingResponseBody body = out -> {
            BufferedWriter writer = new BufferedWriter(new OutputStreamWriter(out, StandardCharsets.UTF_8));
            writer.write('﻿');
            writer.write("id;at;request_id;client_id;channel;tool;args_hash;outcome;error_code;cache_hit;duration_ms\n");
            journal.eachCall(filter, MAX_CSV_ROWS, row -> {
                try {
                    writer.write(String.join(";", String.valueOf(row.id()), row.at().toString(), cell(row.requestId()),
                            cell(row.clientId()), cell(row.channel()), cell(row.tool()), cell(row.argsHash()), cell(row.outcome()),
                            cell(row.errorCode()), String.valueOf(row.cacheHit()), String.valueOf(row.durationMs())));
                    writer.write('\n');
                } catch (java.io.IOException e) {
                    throw new UncheckedIOException(e);
                }
            });
            writer.flush();
        };
        return ResponseEntity.ok()
                .contentType(new MediaType("text", "csv", StandardCharsets.UTF_8))
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"gateway-audit.csv\"")
                .body(body);
    }

    @GetMapping("/admin/api/admin-audit")
    public List<JournalQueries.AdminRow> adminActions(@RequestParam(required = false) String from, @RequestParam(required = false) String to,
                                                      @RequestParam(required = false) String actor, @RequestParam(required = false) String type,
                                                      @RequestParam(required = false) String object, @RequestParam(required = false) Long before,
                                                      @RequestParam(defaultValue = "50") int limit) {
        Instant[] window = window(from, to);
        return journal.adminActions(new JournalQueries.AdminFilter(window[0], window[1], actor, type, object, before), page(limit));
    }

    private JournalQueries.CallFilter filter(String from, String to, String client, String tool, String outcome, Long before) {
        if (outcome != null && !outcome.isBlank() && !OUTCOMES.contains(outcome)) {
            throw AdminException.badRequest("outcome_unknown", OUTCOMES);
        }
        Instant[] window = window(from, to);
        return new JournalQueries.CallFilter(window[0], window[1], client, tool, outcome, before);
    }

    private static Instant[] window(String from, String to) {
        try {
            Instant end = to == null || to.isBlank() ? Instant.now() : Instant.parse(to);
            Instant start = from == null || from.isBlank() ? end.minus(DEFAULT_WINDOW) : Instant.parse(from);
            if (!start.isBefore(end) || Duration.between(start, end).compareTo(MAX_WINDOW) > 0) {
                throw AdminException.badRequest("window_range", MAX_WINDOW.toDays());
            }
            return new Instant[]{start, end};
        } catch (DateTimeParseException e) {
            throw AdminException.badRequest("time_format");
        }
    }

    private static int page(int limit) {
        return Math.max(1, Math.min(limit, JournalQueries.MAX_PAGE));
    }

    /** Ячейка CSV: в кавычках; начало, похожее на формулу (= + - @), обезврежено апострофом — имя инструмента присылает клиент. */
    static String cell(String value) {
        if (value == null) {
            return "";
        }
        String text = value;
        if (!text.isEmpty() && "=+-@\t\r".indexOf(text.charAt(0)) >= 0) {
            text = "'" + text;
        }
        return "\"" + text.replace("\"", "\"\"").replace("\n", " ").replace("\r", " ") + "\"";
    }
}
