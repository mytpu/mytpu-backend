# rag — поиск по документам ТПУ

Гибридный поиск документа 19: векторы (pgvector `halfvec`, HNSW) + полнотекст `russian`, склейка RRF k=60 в одном SQL.
Модель векторов (multilingual-e5-small int8, ONNX) работает внутри процесса — отдельного сервиса векторов нет.
Наружу rag не виден: его вызывает только gateway (`POST /internal/search` с сервисным ключом).

| Модуль | Что |
|---|---|
| `rag-api` (Java) | контракт `DocsSearch`, `SearchFilter`, `SearchResult`, `DocHit` — им пользуется gateway |
| `rag-search` (Kotlin) | схема и миграции (`db/rag`), модель векторов, нарезка, загрузка, поиск, замер; автоконфигурация — чтобы войти в монолит |
| `rag-app` (Kotlin) | сервис: `/internal/search`, профили `ingest` (загрузка) и `eval` (замер) |

## Модель векторов
Полный словарь XLM-R (250 тыс. токенов) давал ~680 МБ памяти уже на старте, поэтому словарь урезан до русского и английского
(60 302 токена; веса те же, векторы совпадают с исходными — скрипт проверяет косинус):
```bash
python tools/rag/trim_e5_vocab.py --model <каталог с model.onnx и tokenizer.json Xenova/multilingual-e5-small> \
  --pruned-tokenizer <tokenizer.json из d0rj/e5-small-en-ru> --out <каталог>/e5-small-en-ru-int8
```
Источники: `Xenova/multilingual-e5-small` (`onnx/model_quantized.onnx`, `tokenizer.json`), `d0rj/e5-small-en-ru` (`tokenizer.json`, MIT).
Файлы модели в git не кладём; путь — `RAG_MODEL_DIR` (в compose — том из `RAG_MODEL_HOST_DIR`). `model_version` — `e5-small-en-ru-int8-v1`.

## Загрузка корпуса и замер
```bash
# стартовый корпус хакатона: help-tpu.json (32 статьи) и tpu-site.jsonl (94 страницы)
RAG_DATASET_HOST_DIR=<helpdesk-ai/data/raw> docker compose -f deploy/compose.dev.yml --profile ingest run --rm rag-ingest
# замер качества на eval/questions.jsonl (46 вопросов, 9 из них — без ответа в базе)
java -jar services/rag/rag-app/target/rag-app-1.0-SNAPSHOT.jar --spring.profiles.active=eval   # с RAG_DB_URL, RAG_MODEL_DIR, SECRETS_DIR
```
Результат на ПК (19.10): 126 документов, 362 раздела, 698 кусков; hit@3 86,5 %, hit@5 89,2 %, ложных «нашёл» 0 %, p95 23 мс.
Неизменившиеся документы при повторной загрузке не пересчитываются; исчезнувшие из источника — удаляются.

## «Нашлось» или нет
Решает порог, а не модель: сходство лучшего куска ≥ 0,87 — или ≥ 0,85, если он заметно (≥ 0,025) отрывается от десятого.
У e5 сходства сжаты (0,79–0,93), один порог не разделяет вопросы с ответом и без. Пороги — `mytpu.rag.search.*`;
подобраны на 46 вопросах — при росте набора прогнать `eval` и пересмотреть.

## Память (бюджет — 380 МБ)
Куча JVM — 30 % лимита (`JDK_JAVA_OPTIONS=-XX:MaxRAMPercentage=30`), остальное — модель и нативные библиотеки ONNX вне кучи.
ONNX без пула памяти и предупаковки, пакеты по 4 текста, 2 потока. Загрузка корпуса — отдельный контейнер `rag-ingest`
с лимитом 512 МБ и 2 ядрами: в 380 МБ пик загрузки упирается впритык.
