# «Мой ТПУ» — инструкции для ИИ-агентов (Codex, Claude и др.)

Бэкенд «Мой ТПУ»: MCP-шлюз `services/gateway` (Java 25, Spring Boot 4.1), поиск по документам `services/rag` (Kotlin),
чат-помощник `services/assistant` (Java), консоль администратора `console/` (Angular 22, zoneless, signals),
стенды `deploy/`. ml-server (прокси к языковой модели) — отдельный репозиторий.

## С чего начать
1. `specs/README.md` — все спецификации и их статус; текущий шаг — в спецификации целиком.
2. `docs/ARCHITECTURE.md`, `docs/DECISIONS.md` (почему так), `docs/SECURITY.md` (модель защиты), `docs/LLM.md`.
3. Состояние проекта, следующий шаг, карта кода и полная карта знаний — в базе знаний команды (Obsidian, отдельный
   приватный репозиторий): заметки `01_Projects/mytpu/Current-State.md` и `01_Projects/mytpu/Agent-Handoff.md`.
   Код читать точечно: сначала карта кода `01_Projects/mytpu/Code/Index.md`, потом нужный файл.

## Правила
- Задача больше ~30 строк или больше двух файлов — сначала спецификация `specs/<имя>/spec.md` (проверяемые критерии,
  non-goals, план шагов с файлами и способом проверки), код — после «ок» владельца.
- Коммит и пуш — только по просьбе владельца, от его имени (git-идентичность репозитория не менять), сообщение
  по-русски (`add:` / `fix:` / `docs:`), без упоминаний ИИ и строк `Co-Authored-By`. Без `push --force`.
- Секреты не читать и не печатать: `.env`, `deploy/secrets/*`, `deploy/test-server/server.env`, ключи `mtpu_…`.
- Консоль: тексты — только `console/src/i18n/ru.json`, цвета и размеры — только `console/src/theme/tokens.css`,
  страница ≤ 200 строк, запросы раздела — в его `*.api.ts`. Режим zoneless: всё, что должно перерисовать экран, — сигнал.
- Структурное решение — строка в `docs/DECISIONS.md` (дата — что — почему — отвергнутая альтернатива).
- После шага — отметка в спецификации; после работы — остановить свои процессы (dev-серверы, Java/Gradle, Docker).

## Проверка
- Бэк: `bash .claude/verify.sh` (сборка всех модулей и тесты шлюза на JDK 25).
- Консоль: `cd console && npx ng build` и `npx ng build --configuration demo`; без сервера — `npm run start:demo`
  (ключ `demo`, `demo-viewer` — только чтение).
- Тестовый стенд на сервере: `deploy/test-server/README.md` (`pack.sh` → `upload.sh` → `up.sh` → `seed.sh`,
  консоль — `bash deploy/test-server/console.sh`).
