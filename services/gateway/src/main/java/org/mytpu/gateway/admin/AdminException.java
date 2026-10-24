package org.mytpu.gateway.admin;

import org.springframework.http.HttpStatus;

/** Отказ API консоли: статус, код для интерфейса и ключ текста в {@code messages.properties} ({@code admin.error.<code>}). */
public class AdminException extends RuntimeException {
    private final HttpStatus status;
    private final String code;
    private final transient Object[] args;

    public AdminException(HttpStatus status, String code, Object... args) {
        super(code, null, false, false);
        this.status = status;
        this.code = code;
        this.args = args;
    }

    public static AdminException badRequest(String code, Object... args) {
        return new AdminException(HttpStatus.BAD_REQUEST, code, args);
    }

    public static AdminException notFound(String code, Object... args) {
        return new AdminException(HttpStatus.NOT_FOUND, code, args);
    }

    public HttpStatus status() {
        return status;
    }

    public String code() {
        return code;
    }

    public Object[] args() {
        return args;
    }
}
