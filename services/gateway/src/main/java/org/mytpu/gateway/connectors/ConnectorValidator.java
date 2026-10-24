package org.mytpu.gateway.connectors;

import java.net.URI;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.mytpu.gateway.connectors.ConnectorSpec.AuthType;
import org.mytpu.gateway.connectors.ConnectorSpec.Param;
import org.mytpu.gateway.connectors.ConnectorSpec.ParamIn;
import org.mytpu.gateway.connectors.ConnectorSpec.ToolSpec;
import org.springframework.stereotype.Component;
import tools.jackson.core.JsonPointer;

/**
 * Проверка описания коннектора перед сохранением: адреса (только https в интернете), имена, параметры пути и запроса,
 * указатели ответа, шаблон фразы, лимиты. Ошибки — коды текстов {@code connector.error.<code>} с подстановками.
 * Пересечение scope и имён инструментов с другими интеграциями проверяет вызывающий — у него каталог.
 */
@Component
public class ConnectorValidator {
    public static final Pattern ID = Pattern.compile("[a-z][a-z0-9_]{1,31}");
    private static final Pattern TOOL = Pattern.compile("[a-z][a-z0-9_]{1,63}");
    private static final Pattern PARAM = Pattern.compile("[a-z][a-zA-Z0-9_]{0,31}");
    private static final Pattern FIELD = Pattern.compile("[a-zA-Z][a-zA-Z0-9_]{0,31}");
    private static final Pattern SCOPE = Pattern.compile("[a-z][a-z0-9_]{1,31}\\.read");
    private static final Pattern HEADER = Pattern.compile("[A-Za-z0-9-]{1,64}");
    private static final Pattern PATH_PLACEHOLDER = Pattern.compile("\\{([^}]*)}");
    private static final Set<String> FORBIDDEN_HEADERS = Set.of("host", "content-length", "transfer-encoding", "connection", "cookie");
    static final int MAX_TOOLS = 20;
    static final int MAX_FIELDS = 20;

    public record Problem(String code, Object... args) {
    }

    private final SsrfGuard guard;

    public ConnectorValidator(SsrfGuard guard) {
        this.guard = guard;
    }

    public List<Problem> validate(String id, ConnectorSpec spec, Set<String> foreignScopes, Set<String> foreignTools) {
        List<Problem> p = new ArrayList<>();
        if (id == null || !ID.matcher(id).matches()) {
            p.add(new Problem("id"));
        }
        if (spec == null) {
            p.add(new Problem("spec_required"));
            return p;
        }
        text(p, spec.title(), 100, "title");
        url(p, spec.baseUrl(), "base_url");
        if (spec.baseUrl() != null && spec.baseUrl().contains("{")) {
            p.add(new Problem("base_url_placeholder"));
        }
        auth(p, spec.auth());
        if (spec.scope() == null || spec.scope().name() == null || !SCOPE.matcher(spec.scope().name()).matches()) {
            p.add(new Problem("scope_name"));
        } else if (foreignScopes.contains(spec.scope().name())) {
            p.add(new Problem("scope_taken", spec.scope().name()));
        }
        if (spec.scope() != null) {
            text(p, spec.scope().title(), 100, "scope_title");
        }
        List<ToolSpec> tools = spec.tools() == null ? List.of() : spec.tools();
        if (tools.isEmpty() || tools.size() > MAX_TOOLS) {
            p.add(new Problem("tools_count", MAX_TOOLS));
        }
        Set<String> names = new HashSet<>();
        for (ToolSpec tool : tools) {
            tool(p, tool, foreignTools, names);
        }
        return p;
    }

    private void tool(List<Problem> p, ToolSpec t, Set<String> foreignTools, Set<String> names) {
        String name = t.name() == null ? "?" : t.name();
        if (!TOOL.matcher(name).matches()) {
            p.add(new Problem("tool_name", name));
        } else if (!names.add(name) || foreignTools.contains(name)) {
            p.add(new Problem("tool_taken", name));
        }
        if (t.description() == null || t.description().strip().length() < 20 || t.description().length() > 1000) {
            p.add(new Problem("tool_description", name));
        }
        List<Param> params = t.params() == null ? List.of() : t.params();
        Set<String> pathParams = new HashSet<>();
        Set<String> paramNames = new HashSet<>();
        for (Param param : params) {
            String pn = param.name() == null ? "?" : param.name();
            if (!PARAM.matcher(pn).matches() || !paramNames.add(pn)) {
                p.add(new Problem("param_name", name, pn));
            }
            if (param.in() == null || param.type() == null) {
                p.add(new Problem("param_kind", name, pn));
            }
            if (param.in() == ParamIn.PATH) {
                pathParams.add(pn);
            }
            if (param.description() == null || param.description().isBlank() || param.description().length() > 300) {
                p.add(new Problem("param_description", name, pn));
            }
            if (param.enumValues() != null && (param.enumValues().isEmpty() || param.enumValues().size() > 50)) {
                p.add(new Problem("param_enum", name, pn));
            }
        }
        path(p, name, t.path(), pathParams);
        result(p, name, t);
        Long ttl = t.cacheTtlSeconds();
        Long timeout = t.timeoutMs();
        Integer rate = t.ratePerMinute();
        if ((ttl != null && (ttl < 0 || ttl > 86_400)) || (timeout != null && (timeout < 100 || timeout > 10_000))
                || (rate != null && (rate < 1 || rate > 600))) {
            p.add(new Problem("tool_limits", name));
        }
    }

    private static void path(List<Problem> p, String tool, String path, Set<String> pathParams) {
        // Только латиница, цифры и /_.~-{} — иначе адрес не соберётся (пробел, %, кириллица) или выйдет за путь
        if (path == null || !path.matches("[A-Za-z0-9/_.~{}-]+") || !path.startsWith("/") || path.contains("..")
                || path.contains("//") || path.length() > 300) {
            p.add(new Problem("path", tool));
            return;
        }
        Set<String> used = new HashSet<>();
        Matcher m = PATH_PLACEHOLDER.matcher(path);
        while (m.find()) {
            used.add(m.group(1));
        }
        if (!used.equals(pathParams)) {
            p.add(new Problem("path_params", tool, used, pathParams));
        }
    }

    private static void result(List<Problem> p, String tool, ToolSpec t) {
        if (t.result() == null || t.result().fields() == null || t.result().fields().isEmpty()
                || t.result().fields().size() > MAX_FIELDS) {
            p.add(new Problem("result_fields", tool, MAX_FIELDS));
            return;
        }
        Set<String> fields = new HashSet<>();
        pointer(p, tool, t.result().pointer() == null ? "" : t.result().pointer());
        for (ConnectorSpec.Field f : t.result().fields()) {
            if (f.name() == null || !FIELD.matcher(f.name()).matches() || !fields.add(f.name())
                    || SummaryTemplate.BUILT_IN.contains(f.name())) {
                p.add(new Problem("field_name", tool, f.name()));
            }
            pointer(p, tool, f.pointer());
        }
        Integer max = t.result().maxItems();
        if (max != null && (max < 1 || max > ResponseMapper.MAX_ITEMS)) {
            p.add(new Problem("max_items", tool, ResponseMapper.MAX_ITEMS));
        }
        if (t.summary() == null || t.summary().isBlank() || t.summary().length() > 300) {
            p.add(new Problem("summary", tool));
            return;
        }
        for (String placeholder : SummaryTemplate.placeholders(t.summary())) {
            if (!fields.contains(placeholder) && !SummaryTemplate.BUILT_IN.contains(placeholder)) {
                p.add(new Problem("summary_placeholder", tool, placeholder));
            }
        }
    }

    private static void pointer(List<Problem> p, String tool, String pointer) {
        try {
            if (pointer == null || (!pointer.isEmpty() && !pointer.startsWith("/"))) {
                throw new IllegalArgumentException();
            }
            JsonPointer.compile(pointer);
        } catch (RuntimeException e) {
            p.add(new Problem("pointer", tool, pointer));
        }
    }

    private void auth(List<Problem> p, ConnectorSpec.Auth auth) {
        AuthType type = auth == null || auth.type() == null ? null : auth.type();
        if (type == null) {
            p.add(new Problem("auth_type"));
            return;
        }
        if ((type == AuthType.HEADER || type == AuthType.QUERY) && (auth.name() == null || !HEADER.matcher(auth.name()).matches()
                || FORBIDDEN_HEADERS.contains(auth.name().toLowerCase(java.util.Locale.ROOT)))) {
            p.add(new Problem("auth_name"));
        }
        if (type == AuthType.OAUTH2) {
            url(p, auth.tokenUrl(), "token_url");
        }
    }

    private void url(List<Problem> p, String url, String what) {
        String problem = url == null ? "url_invalid" : SsrfGuard.urlProblem(url);
        if (problem != null) {
            p.add(new Problem(problem, what));
        } else if (!guard.resolvesPublic(URI.create(url).getHost())) {
            p.add(new Problem("url_private", what));
        }
    }

    private static void text(List<Problem> p, String value, int max, String what) {
        if (value == null || value.isBlank() || value.length() > max) {
            p.add(new Problem("text", what, max));
        }
    }
}
