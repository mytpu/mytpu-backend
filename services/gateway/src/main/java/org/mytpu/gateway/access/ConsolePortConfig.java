package org.mytpu.gateway.access;

import org.apache.catalina.connector.Connector;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.tomcat.servlet.TomcatServletWebServerFactory;
import org.springframework.boot.web.server.WebServerFactoryCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Второй порт Tomcat только для консоли ({@code mytpu.admin.port}). Что на нём отвечает — решает {@link AdminNetworkFilter}.
 * В Docker порт публикуется только на {@code 127.0.0.1} хоста; прокси на него не ставить.
 */
@Configuration(proxyBeanMethods = false)
public class ConsolePortConfig {

    @Bean
    WebServerFactoryCustomizer<TomcatServletWebServerFactory> consoleConnector(@Value("${mytpu.admin.port:8090}") int port) {
        return factory -> {
            Connector connector = new Connector(TomcatServletWebServerFactory.DEFAULT_PROTOCOL);
            connector.setPort(port);
            // server.tomcat.* действует только на основной порт: ограничения соединений — свои, консоли хватает немногих
            connector.setProperty("connectionTimeout", "5000");
            connector.setProperty("maxConnections", "20");
            connector.setProperty("acceptCount", "10");
            factory.addAdditionalConnectors(connector);
        };
    }
}
