package org.mytpu.gateway.catalog;

import java.util.List;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/** Каталог для консолей: интеграции → scopes → инструменты → состояние. Доступ — ключом с ролью консоли. */
@RestController
public class CatalogController {
    private final IntegrationCatalog catalog;

    public CatalogController(IntegrationCatalog catalog) {
        this.catalog = catalog;
    }

    @GetMapping("/internal/catalog")
    public List<IntegrationStatus> catalog() {
        return catalog.status();
    }
}
