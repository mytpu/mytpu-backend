package org.mytpu.gateway.access;

import java.io.IOException;
import java.net.InetAddress;
import java.util.List;

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
 * Консоль ({@code /admin/api/**} и {@code /internal/**}) живёт только на своём порту {@code mytpu.admin.port}
 * (второй коннектор — {@link ConsolePortConfig}). На основном порту эти пути — всегда 404, как будто консоли нет.
 * На порту консоли отвечают только консоль и {@code /actuator/health}, и только соединениям с адресов из
 * {@code mytpu.admin.allowed-networks}; остальным — 404. Стоит до проверки ключа: с чужого адреса ключ даже не проверяется.
 * Адрес — только адрес соединения: {@code X-Forwarded-For} здесь не читается (его подставит любой процесс хоста),
 * поэтому прокси перед портом консоли ставить нельзя. Ответы консоли не угадываются браузером и не исполняются как страница.
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 5)
public class AdminNetworkFilter extends OncePerRequestFilter {
    private static final UrlPathHelper PATHS = new UrlPathHelper();

    private final List<Cidr> allowed;
    private final int consolePort;

    public AdminNetworkFilter(@Value("${mytpu.admin.allowed-networks:127.0.0.1/32,::1/128}") List<String> networks,
                              @Value("${mytpu.admin.port:8090}") int consolePort,
                              @Value("${server.port:8080}") int mainPort) {
        // Неверная сеть или порт — отказ запуска: молча закрытая или открытая консоль хуже
        if (consolePort <= 0 || consolePort == mainPort) {
            throw new IllegalStateException("mytpu.admin.port должен быть своим портом, не server.port: " + consolePort);
        }
        this.allowed = networks.stream().filter(n -> !n.isBlank()).map(String::trim).map(Cidr::parse).toList();
        this.consolePort = consolePort;
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        return request.getLocalPort() != consolePort && !console(PATHS.getPathWithinApplication(request));
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        String path = PATHS.getPathWithinApplication(request);
        boolean console = console(path);
        if (request.getLocalPort() != consolePort
                || !(console || path.equals("/actuator/health"))
                || !allowed(request.getRemoteAddr())) {
            response.setStatus(HttpServletResponse.SC_NOT_FOUND);
            return;
        }
        if (console) {
            response.setHeader("X-Content-Type-Options", "nosniff");
            response.setHeader("Content-Security-Policy", "default-src 'none'; frame-ancestors 'none'");
            response.setHeader("Cache-Control", "no-store");
        }
        chain.doFilter(request, response);
    }

    private static boolean console(String path) {
        return path.equals("/admin/api") || path.startsWith("/admin/api/") || path.startsWith("/internal/");
    }

    private boolean allowed(String remote) {
        InetAddress address = InetAddress.ofLiteral(remote);
        return allowed.stream().anyMatch(cidr -> cidr.contains(address));
    }
}
