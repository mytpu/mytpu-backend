package org.mytpu.assistant.llm;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.io.InputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mytpu.assistant.dto.ChatRequest;
import org.mytpu.assistant.services.ChatService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.codec.ServerSentEvent;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/**
 * Путь с моделью целиком (specs/assistant/spec.md, шаги 9–10): настоящий клиент Spring AI против заглушки ml-server,
 * которая отвечает {@code tool_calls}, и заглушки шлюза {@code /chat/tools/docs_search}. Ожидаемое поведение — из
 * критериев spec 5: «ответ сходится с данными, иначе шаблон», «ссылки только *.tpu.ru», «неверные аргументы — одна
 * повторная попытка, потом уточняющий вопрос», «сбой модели — шаблон», «прямой путь не зовёт ml-server».
 */
@SpringBootTest(properties = {
        "spring.ai.openai.api-key=test-key",
        "spring.ai.openai.chat.options.model=test-model",
        "spring.ai.openai.max-retries=0",
        "spring.ai.openai.chat.max-retries=0",
        "mytpu.assistant.model-timeout=5s",
        "mytpu.assistant.gateway-key=test-gateway-key"})
class ModelPathEndToEndTest {

    private static final String DOC_URL = "https://tpu.ru/students/spravki";
    private static final String EXCERPT = "Справку об обучении заказывают в личном кабинете, срок 3 рабочих дня.";
    private static final HttpServer SERVER = start();

    /** Что модель скажет после данных инструмента. */
    private static final AtomicReference<String> FINAL_ANSWER = new AtomicReference<>();
    /** Аргументы, с которыми модель зовёт инструмент. */
    private static final AtomicReference<String> TOOL_ARGUMENTS = new AtomicReference<>();
    private static final AtomicReference<Integer> MODEL_STATUS = new AtomicReference<>(200);
    private static final AtomicInteger MODEL_CALLS = new AtomicInteger();
    private static final AtomicInteger GATEWAY_CALLS = new AtomicInteger();
    private static final AtomicReference<Boolean> DOC_FOUND = new AtomicReference<>(true);

    @Autowired
    private ChatService chat;

    @DynamicPropertySource
    static void urls(DynamicPropertyRegistry registry) {
        String base = "http://127.0.0.1:" + SERVER.getAddress().getPort();
        registry.add("spring.ai.openai.base-url", () -> base + "/v1");
        registry.add("mytpu.assistant.gateway-url", () -> base);
    }

    @BeforeEach
    void reset() {
        FINAL_ANSWER.set(EXCERPT + " Источник: " + DOC_URL);
        TOOL_ARGUMENTS.set("{\"query\":\"справка об обучении\"}");
        MODEL_STATUS.set(200);
        MODEL_CALLS.set(0);
        GATEWAY_CALLS.set(0);
        DOC_FOUND.set(true);
    }

    @AfterAll
    static void stop() {
        SERVER.stop(0);
    }

    @Test
    @DisplayName("Модель вызывает docs_search и отвечает по данным — ответ модели доходит как есть")
    void modelAnswerMatchingDataIsReturned() {
        String answer = ask("Как получить справку об обучении?");

        assertEquals(EXCERPT + " Источник: " + DOC_URL, answer);
        assertEquals(2, MODEL_CALLS.get(), "запрос с инструментами и запрос с результатом");
        assertEquals(1, GATEWAY_CALLS.get());
    }

    @Test
    @DisplayName("Время, которого нет в данных, → шаблон с выдержкой и источником")
    void inventedTimeFallsBackToTemplate() {
        FINAL_ANSWER.set("Справку выдают в 14:30 в корпусе. Источник: " + DOC_URL);

        String answer = ask("Как получить справку об обучении?");

        assertFalse(answer.contains("14:30"));
        assertTrue(answer.contains(EXCERPT), answer);
        assertTrue(answer.contains(DOC_URL), answer);
    }

    @Test
    @DisplayName("Ссылка не на tpu.ru → шаблон")
    void foreignLinkFallsBackToTemplate() {
        FINAL_ANSWER.set(EXCERPT + " Подробнее: https://example.com/spravka");

        String answer = ask("Как получить справку об обучении?");

        assertFalse(answer.contains("example.com"));
        assertTrue(answer.contains(EXCERPT), answer);
    }

    @Test
    @DisplayName("Неверные аргументы дважды → уточняющий вопрос, шлюз не вызывается")
    void badArgumentsTwiceAsksToClarify() {
        TOOL_ARGUMENTS.set("{\"q\":\"\"}");

        String answer = ask("Как получить справку об обучении?");

        assertTrue(answer.startsWith("Уточните"), answer);
        assertEquals(0, GATEWAY_CALLS.get());
    }

    @Test
    @DisplayName("ml-server недоступен → помощник сам ищет в документах и отвечает выдержкой")
    void modelFailureFallsBackToDocsTemplate() {
        MODEL_STATUS.set(500);

        String answer = ask("Как получить справку об обучении?");

        assertTrue(answer.contains(EXCERPT), answer);
        assertTrue(answer.contains(DOC_URL), answer);
        assertEquals(1, GATEWAY_CALLS.get());
    }

    @Test
    @DisplayName("docs_search ответил found=false → шаблон «не нашёл, куда обратиться», а не текст модели")
    void notFoundGivesTemplate() {
        DOC_FOUND.set(false);
        FINAL_ANSWER.set("Справку можно получить у декана.");

        String answer = ask("Как получить справку об обучении?");

        assertTrue(answer.startsWith("Не нашёл ответа"), answer);
    }

    @Test
    @DisplayName("Поток: ответ модели после проверки — card, sources, delta, done")
    void streamCarriesVerifiedAnswer() {
        List<ServerSentEvent<?>> events = chat.streamChat(new ChatRequest("Как получить справку об обучении?"))
                .collectList().block();

        List<String> names = events.stream().map(ServerSentEvent::event).toList();
        assertEquals(List.of("meta", "status", "card", "sources", "delta", "done"), names);
    }

    private String ask(String question) {
        return chat.chat(new ChatRequest(question), () -> { }).message();
    }

    private static HttpServer start() {
        try {
            HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            server.createContext("/v1/chat/completions", ModelPathEndToEndTest::model);
            server.createContext("/chat/tools/docs_search", ModelPathEndToEndTest::gateway);
            server.start();
            return server;
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }

    private static void model(HttpExchange exchange) throws IOException {
        MODEL_CALLS.incrementAndGet();
        String request = read(exchange.getRequestBody());
        if (MODEL_STATUS.get() != 200) {
            send(exchange, MODEL_STATUS.get(), "{\"error\":{\"message\":\"down\"}}");
            return;
        }
        boolean hasToolResult = request.contains("\"role\":\"tool\"");
        boolean argumentsWereBad = request.contains("bad_arguments");
        String message;
        String finish;
        if (hasToolResult && !argumentsWereBad) {
            message = "{\"role\":\"assistant\",\"content\":" + quote(FINAL_ANSWER.get()) + "}";
            finish = "stop";
        } else {
            message = "{\"role\":\"assistant\",\"content\":null,\"tool_calls\":[{\"id\":\"call_" + MODEL_CALLS.get()
                    + "\",\"type\":\"function\",\"function\":{\"name\":\"docs_search\",\"arguments\":"
                    + quote(TOOL_ARGUMENTS.get()) + "}}]}";
            finish = "tool_calls";
        }
        send(exchange, 200, "{\"id\":\"c1\",\"object\":\"chat.completion\",\"created\":1,\"model\":\"test-model\","
                + "\"choices\":[{\"index\":0,\"message\":" + message + ",\"finish_reason\":\"" + finish + "\"}],"
                + "\"usage\":{\"prompt_tokens\":1,\"completion_tokens\":1,\"total_tokens\":2}}");
    }

    private static void gateway(HttpExchange exchange) throws IOException {
        GATEWAY_CALLS.incrementAndGet();
        read(exchange.getRequestBody());
        if (!DOC_FOUND.get()) {
            send(exchange, 200, "{\"summary\":\"Не нашёл\",\"data\":{\"found\":false,\"hits\":[]},\"hint\":null}");
            return;
        }
        send(exchange, 200, "{\"summary\":\"Нашёл в «Справки»\",\"data\":{\"found\":true,\"hits\":[{\"title\":\"Справки\","
                + "\"section\":null,\"url\":\"" + DOC_URL + "\",\"excerpt\":" + quote(EXCERPT) + "}]},\"hint\":null}");
    }

    private static String read(InputStream in) throws IOException {
        return new String(in.readAllBytes(), StandardCharsets.UTF_8);
    }

    private static void send(HttpExchange exchange, int status, String body) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().add("Content-Type", "application/json");
        exchange.sendResponseHeaders(status, bytes.length);
        exchange.getResponseBody().write(bytes);
        exchange.close();
    }

    private static String quote(String s) {
        return "\"" + s.replace("\\", "\\\\").replace("\"", "\\\"") + "\"";
    }
}
