package org.mytpu.assistant.tools;

import java.util.Map;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;
import tools.jackson.databind.json.JsonMapper;

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.*;
import static org.springframework.test.web.client.response.MockRestResponseCreators.*;

class GatewayToolsClientTest {

    private MockRestServiceServer mockServer;
    private GatewayToolsClient client;
    private final JsonMapper json = new JsonMapper();

    @BeforeEach
    void setUp() {
        RestClient.Builder builder = RestClient.builder().baseUrl("http://gateway:8080");
        this.mockServer = MockRestServiceServer.bindTo(builder).build();
        RestClient restClient = builder.build();
        this.client = new GatewayToolsClient(restClient, json);
    }

    @Test
    @DisplayName("Успешный вызов rasp_find с извлечением ссылок и карточки")
    void shouldCallRaspFindSuccessfully() {
        String responseBody = """
                {
                  "summary": "Найдено 1: 10 корпус — пр. Ленина, 2",
                  "data": {
                    "total": 1,
                    "items": [
                      {
                        "name": "10 корпус",
                        "kind": "корпус",
                        "hint": "пр. Ленина, 2",
                        "url": "https://rasp.tpu.ru/redirect/kalendar.html?hash=abc"
                      }
                    ]
                  },
                  "hint": "Для подробностей откройте ссылку"
                }
                """;

        mockServer.expect(requestTo("http://gateway:8080/chat/tools/rasp_find"))
                .andExpect(method(HttpMethod.POST))
                .andExpect(content().contentType(MediaType.APPLICATION_JSON))
                .andRespond(withSuccess(responseBody, MediaType.APPLICATION_JSON));

        ToolAnswer answer = client.call("rasp_find", Map.of("query", "10 корпус"));

        assertEquals("Найдено 1: 10 корпус — пр. Ленина, 2", answer.summary());
        assertTrue(answer.found());
        assertEquals("Для подробностей откройте ссылку", answer.hint());
        assertEquals(1, answer.sources().size());
        assertEquals("10 корпус — пр. Ленина, 2", answer.sources().getFirst().title());
        assertEquals("https://rasp.tpu.ru/redirect/kalendar.html?hash=abc", answer.sources().getFirst().url());
        mockServer.verify();
    }

    @Test
    @DisplayName("Успешный разбор docs_search с массивом hits")
    void shouldCallDocsSearchSuccessfully() {
        String responseBody = """
                {
                  "summary": "Найдено в «Справка об обучении»: ул. Белинского 53а",
                  "data": {
                    "found": true,
                    "hits": [
                      {
                        "title": "Справка об обучении",
                        "section": "Единый деканат",
                        "url": "https://tpu.ru/student/references",
                        "excerpt": "Выдаётся в течение 5 рабочих дней"
                      }
                    ]
                  }
                }
                """;

        mockServer.expect(requestTo("http://gateway:8080/chat/tools/docs_search"))
                .andExpect(method(HttpMethod.POST))
                .andRespond(withSuccess(responseBody, MediaType.APPLICATION_JSON));

        ToolAnswer answer = client.call("docs_search", Map.of("query", "справка"));

        assertTrue(answer.found());
        assertEquals(1, answer.sources().size());
        assertEquals("Справка об обучении › Единый деканат", answer.sources().getFirst().title());
        assertEquals("https://tpu.ru/student/references", answer.sources().getFirst().url());
        mockServer.verify();
    }

    @Test
    @DisplayName("Ошибка шлюза 502 или ошибка в теле преобразуется в ToolUnavailable")
    void shouldThrowToolUnavailableOnError() {
        String errorJson = """
                {
                  "error": "upstream_timeout",
                  "message": "Расписание не ответило вовремя"
                }
                """;

        mockServer.expect(requestTo("http://gateway:8080/chat/tools/rasp_find"))
                .andRespond(withStatus(HttpStatus.BAD_GATEWAY).body(errorJson).contentType(MediaType.APPLICATION_JSON));

        ToolUnavailable ex = assertThrows(ToolUnavailable.class, () ->
                client.call("rasp_find", Map.of("query", "08-31"))
        );

        assertEquals("upstream_timeout", ex.code());
        mockServer.verify();
    }

    @Test
    @DisplayName("Отправка заголовка Authorization Bearer при наличии ключа")
    void shouldSendAuthorizationBearerHeader() {
        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer customServer = MockRestServiceServer.bindTo(builder).build();
        org.mytpu.assistant.config.GatewayToolsProperties props =
                new org.mytpu.assistant.config.GatewayToolsProperties("http://gateway:8080", "secret_key_123", null, null, null);
        GatewayToolsClient clientWithAuth = new GatewayToolsClient(GatewayToolsClient.configure(props, builder).build(), json);

        customServer.expect(requestTo("http://gateway:8080/chat/tools/rasp_find"))
                .andExpect(method(HttpMethod.POST))
                .andExpect(header(HttpHeaders.AUTHORIZATION, "Bearer secret_key_123"))
                .andRespond(withSuccess("{\"summary\":\"ок\",\"data\":{}}", MediaType.APPLICATION_JSON));

        ToolAnswer answer = clientWithAuth.call("rasp_find", Map.of("query", "8К31"));
        assertNotNull(answer);
        customServer.verify();
    }
}
