package org.mytpu.identity;

import java.net.URI;

import org.springframework.context.annotation.Profile;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@Profile("mock")
class MockOAuthController {
    private final MockOAuthProvider provider;

    MockOAuthController(MockOAuthProvider provider) {
        this.provider = provider;
    }

    @GetMapping(value = "/api/auth/mock", produces = MediaType.TEXT_HTML_VALUE)
    ResponseEntity<String> page(@RequestParam String state) {
        if (!AuthSecrets.valid(state)) {
            return ResponseEntity.badRequest().build();
        }
        return ResponseEntity.ok("""
                <!doctype html>
                <html lang="ru"><meta charset="utf-8"><title>Тестовый вход ТПУ</title>
                <h1>Тестовый вход ТПУ</h1>
                <p>Локальный стенд использует вымышленную учётную запись.</p>
                <form method="post" action="/api/auth/mock">
                  <input type="hidden" name="state" value="%s">
                  <button>Войти тестовым студентом</button>
                  <button name="account" value="demo-student-2">Войти вторым тестовым студентом</button>
                  <button name="cancel" value="true">Отмена</button>
                </form></html>
                """.formatted(state));
    }

    @PostMapping("/api/auth/mock")
    ResponseEntity<Void> confirm(@RequestParam String state, @RequestParam(defaultValue = "false") boolean cancel,
                                 @RequestParam(defaultValue = "demo-student") String account) {
        if (!AuthSecrets.valid(state) || !("demo-student".equals(account) || "demo-student-2".equals(account))) {
            return ResponseEntity.badRequest().build();
        }
        URI uri = URI.create(IdentitySettings.CALLBACK + "?state=" + state
                + (cancel ? "&error=access_denied" : "&code=" + provider.confirm(state, account)));
        return ResponseEntity.status(HttpStatus.SEE_OTHER).location(uri).build();
    }
}
