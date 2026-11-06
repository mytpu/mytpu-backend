# Цепочка поставки: П1–П3 (только CI и сборка)

Источник — исследование безопасности 06.11.2026 (`specs/security-hardening/research.md`), меры П1–П3. До показа стенда
делаются только меры без выкладки: стенд, `deploy/` и SSH не трогаем.

## Что меняется
| Мера | Сейчас | Станет |
|---|---|---|
| П1 | Dependabot следит за `maven`, `github-actions`, `docker`; `console/package.json` — нет. CI консоли нет | блок `npm` для `/console` (weekly); в `ci-build.yml` задача `dependencies` со шагом `npm audit --omit=dev --audit-level=high` по `console/package-lock.json` |
| П2 | CVE Java-зависимостей видны только в образе assistant (trivy в `ci-cd-ai-backend.yml`); gateway и rag (PDFBox, jsoup, ONNX Runtime, DJL) не сканируются | в той же задаче `trivy fs --scanners vuln` по репозиторию: тот же образ trivy по digest и тот же порог (`--severity CRITICAL --ignore-unfixed`, стоп) |
| П3 | SBOM нет | профиль Maven `sbom` с `cyclonedx-maven-plugin` 2.9.3 (`makeAggregateBom` на фазе `package`): `target/bom.json` и `target/bom.xml` по всему реактору без тестовых зависимостей; задача `verify` в CI собирает с `-Psbom` и выгружает SBOM артефактом |

## Критерии готовности
1. `.github/dependabot.yml` содержит блок `npm` с `directory: "/console"`, `interval: weekly`.
2. `ci-build.yml`: задача `dependencies` — `npm audit --omit=dev --audit-level=high` в `console/` и `trivy fs` с порогом как у шага trivy в `ci-cd-ai-backend.yml`; падение любого шага — красная сборка.
3. Обычный `mvn verify` (и `bash .claude/verify.sh`) не запускает CycloneDX; `mvn -Psbom package` создаёт `target/bom.json` и `target/bom.xml` в корне.
4. В CI задача `verify` выгружает `target/bom.json` и `target/bom.xml` артефактом `sbom`; action закреплён по SHA, как остальные.
5. YAML обоих изменённых файлов разбирается; `bash .claude/verify.sh` зелёный.
6. Строка в `docs/DECISIONS.md` (trivy fs вместо OWASP dependency-check); `docs/SECURITY.md`, раздел 11, отражает новые проверки.

## Д2 (только сканирование, добавлено 06.11)
- `ci-build.yml`, задача `images` (матрица gateway, rag): `mvn package -pl <модуль> -am` → `docker build` тем же `spring.Dockerfile` → `trivy image` с порогом как у assistant (`--severity CRITICAL --ignore-unfixed`, стоп).
- Задача `pgvector`: отчёт `trivy image` по `pgvector/pgvector:0.8.1-pg17` (CRITICAL и HIGH), `--exit-code 0` — образ до показа не меняем.

## После показа (06.11): стенд
- `deploy/test-server/pack.sh`: pgvector — `docker pull` по digest индекса `sha256:3e8b3adf…` и тег `0.8.1-pg17` (и при
  `--skip-build`, если под тегом другой образ); trivy всех четырёх образов (HIGH — отчёт, CRITICAL с исправлением — стоп;
  `--skip-scan` для работы без сети); `mytpu-images.tar.gz.sha256` рядом с архивом.
- `upload.sh`: сверка суммы до переноса, перенос архива с суммой, сверка на сервере. `up.sh`: новый архив без суммы или с
  несовпавшей суммой не загружается (уже загруженный архив с меткой `.images_loaded` не перепроверяется).
- `ci-build.yml`, задача `pgvector`: тот же digest, порог как в `pack.sh`.
- Проверено: `bash -n` трёх скриптов; логика суммы — на подставном архиве (целый — загрузка, изменённый и без суммы —
  отказ); YAML разбирается. Не проверено: `pack.sh` целиком и trivy локально (Docker не запущен), перенос на сервер.
- cosign не делается: образы идут архивом, ключ проверки лежал бы на том же сервере — см. `docs/DECISIONS.md`.

## Non-goals
- Подпись образов и SBOM (cosign, аттестации) — вместе с реестром образов (спецификация 3), не для архива на тестовом стенде.
- Сканирование самого SBOM (`trivy sbom target/bom.json`) вместо `trivy fs` — точнее по фактически разрешённым версиям, но требует сборки в той же задаче; вернуться, если `trivy fs` по `pom.xml` будет давать ложные срабатывания.
- Запуск workflow на GitHub — проверяется первым пушем владельца.

## Шаги
| # | Шаг | Файлы | Проверка |
|---|---|---|---|
| 1 | Dependabot npm | `.github/dependabot.yml` | YAML разбирается |
| 2 | Задача `dependencies`: npm audit + trivy fs | `.github/workflows/ci-build.yml` | YAML разбирается; `npm audit --omit=dev` локально; локальный прогон trivy fs — если без нагрузки на диск C: |
| 3 | Профиль `sbom` и выгрузка артефакта | `pom.xml`, `.github/workflows/ci-build.yml` | `mvn -Psbom -DskipTests package` создаёт `target/bom.{json,xml}`; `verify.sh` зелёный |
| 4 | Документы | `docs/DECISIONS.md`, `docs/SECURITY.md`, `specs/README.md` | — |

## Статус
- Шаги 1–4 выполнены 06.11.2026. Проверено: YAML `dependabot.yml` и обоих workflow разбирается; `npm audit --omit=dev` в `console/` — 0 уязвимостей; `mvn -Psbom -DskipTests package` — `target/bom.json` и `target/bom.xml` (187 компонентов, есть PDFBox, jsoup, ONNX Runtime, DJL, Tomcat 11.0.27), без профиля CycloneDX не запускается; `bash .claude/verify.sh` — ok.
- Не проверено: workflow на GitHub не запускались; `trivy fs` локально не запускался (Docker не запущен). Сверка SBOM с OSV 06.11: критичных нет (порог CI не сработает), есть HIGH — jsoup 1.22.1 (исправлено в 1.23.2), jackson-core/databind 2.21.5 и 3.1.5 (исправлено в 2.21.7 / 3.1.7).
- Обновлено после показа (06.11): jsoup 1.24.1 — одно свойство `jsoup.version` в корневом `pom.xml` (было 1.22.1 отдельно в gateway и rag-search); jackson 2.21.8 и 3.1.8 — свойства `jackson-2-bom.version` / `jackson-bom.version` поверх BOM Boot (последние патчи той же линии, не 2.22/3.2). Проверено: `verify.sh` — ok; SBOM с новыми версиями, сверка с OSV — 0 уязвимостей из 187 компонентов; вызовы jsoup из `RaspSearchParser` и `FormatReaders.html` (тестов на них нет) повторены отдельной программой — вывод на 1.22.1 и 1.24.1 совпадает.
- Д2: YAML разбирается; `mvn -DskipTests package -pl services/gateway -am` и `-pl services/rag/rag-app -am` — успешно, в `target/` по одному jar (проверка в `spring.Dockerfile` пройдёт). Сборка образов и trivy локально не запускались (Docker не запущен).
- Ловушка: родитель Spring Boot держит `cyclonedx-maven-plugin` в `pluginManagement` с запуском `default` (SBOM внутрь каждого jar); объявление плагина его включает, поэтому в профиле он выключен (`phase none`), а наш запуск — `inherited=false`, только в корне.
