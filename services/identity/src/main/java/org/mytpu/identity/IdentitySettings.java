package org.mytpu.identity;

import java.net.URI;
import java.util.Set;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.core.env.Environment;
import org.springframework.core.env.Profiles;
import org.springframework.stereotype.Component;

@ConfigurationProperties("identity")
record IdentitySettings(URI webOrigin, URI callbackOrigin, URI mobileReturn,
                        String clientId, String clientSecret) {
    static final String CALLBACK = "/api/auth/callback";

    URI callback() {
        return callbackOrigin.resolve(CALLBACK);
    }

    boolean permitsOrigin(String origin) {
        return webOrigin.toString().equals(origin);
    }

    @Override
    public String toString() {
        return "IdentitySettings";
    }

    @Component
    static class Validation {
        Validation(IdentitySettings settings, Environment environment) {
            boolean mock = environment.acceptsProfiles(Profiles.of("mock"));
            validateOrigin(settings.webOrigin, mock);
            if (!settings.webOrigin.equals(settings.callbackOrigin)) {
                throw new IllegalStateException("Адрес возврата identity должен совпадать с origin веб-клиента");
            }
            if (mock && !loopback(environment.getProperty("server.address", ""))) {
                throw new IllegalStateException("Профиль mock доступен только на локальном адресе");
            }
            URI mobile = settings.mobileReturn;
            boolean debugLink = mock && URI.create("mytpu-demo://auth/return").equals(mobile);
            if (!debugLink && (!"https".equals(mobile.getScheme()) || mobile.getHost() == null
                    || !"/auth/return".equals(mobile.getPath()) || mobile.getRawQuery() != null
                    || mobile.getRawFragment() != null || mobile.getRawUserInfo() != null)) {
                throw new IllegalStateException("IDENTITY_MOBILE_RETURN должен быть HTTPS-адресом /auth/return");
            }
            if (!mock && (settings.clientId.isBlank() || settings.clientSecret.isBlank())) {
                throw new IllegalStateException("Требуются настройки OAuth приложения ТПУ");
            }
        }

        private static void validateOrigin(URI uri, boolean mock) {
            boolean local = loopback(uri.getHost());
            if ((mock && !local) || (!"https".equals(uri.getScheme()) && !(mock && "http".equals(uri.getScheme())))
                    || uri.getHost() == null
                    || !uri.getPath().isEmpty() || uri.getRawQuery() != null
                    || uri.getRawFragment() != null || uri.getRawUserInfo() != null) {
                throw new IllegalStateException("Для identity требуется HTTPS origin без пути");
            }
        }

        private static boolean loopback(String host) {
            return host != null && Set.of("localhost", "127.0.0.1", "::1", "[::1]").contains(host);
        }
    }
}
