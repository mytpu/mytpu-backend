package org.mytpu.gateway.access;

import java.io.IOException;
import java.net.InetAddress;
import java.util.List;
import java.util.Optional;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;
import org.springframework.web.util.UrlPathHelper;

/**
 * Консоль ({@code /admin/api/**} и {@code /internal/**}) отвечает только с адресов из {@code mytpu.admin.allowed-networks};
 * остальным — 404, как будто консоли нет. Стоит до проверки ключа: с чужого адреса ключ администратора даже не проверяется.
 * Адрес неизвестен (запрос от доверенного прокси без X-Forwarded-For) — тоже 404. По умолчанию — только localhost
 * (вход через SSH-туннель) до входа через ТПУ. Ответы консоли не угадываются браузером и не исполняются как страница.
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 5)
public class AdminNetworkFilter extends OncePerRequestFilter {
    private static final UrlPathHelper PATHS = new UrlPathHelper();

    private final List<Cidr> allowed;
    private final ClientAddress addresses;

    public AdminNetworkFilter(@Value("${mytpu.admin.allowed-networks:127.0.0.1/32,::1/128}") List<String> networks,
                              ClientAddress addresses) {
        // Неверная сеть — отказ запуска: молча закрытая или открытая консоль хуже
        this.allowed = networks.stream().filter(n -> !n.isBlank()).map(String::trim).map(Cidr::parse).toList();
        this.addresses = addresses;
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        String path = PATHS.getPathWithinApplication(request);
        return !(path.equals("/admin/api") || path.startsWith("/admin/api/") || path.startsWith("/internal/"));
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        Optional<InetAddress> address = addresses.known(request);
        if (address.isEmpty() || allowed.stream().noneMatch(cidr -> cidr.contains(address.get()))) {
            response.setStatus(HttpServletResponse.SC_NOT_FOUND);
            return;
        }
        response.setHeader("X-Content-Type-Options", "nosniff");
        response.setHeader("Content-Security-Policy", "default-src 'none'; frame-ancestors 'none'");
        response.setHeader("Cache-Control", "no-store");
        chain.doFilter(request, response);
    }
}
