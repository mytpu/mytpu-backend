# Консоль администратора «Мой ТПУ»

Интерфейс администратора шлюза MCP и базы знаний (rag).
Стек: Angular 22 (standalone-компоненты, signals, zoneless, без SSR).

## Быстрый старт

### 1. Предварительные требования
Для работы консоли необходим запущенный шлюз `gateway` на порту `8081`:
```bash
./gradlew :services:gateway:bootRun
```

Ключ администратора или наблюдателя выпускается утилитой `issue-key`:
```bash
# Ключ администратора с полным доступом
./gradlew :tools:issue-key --args="--roles=console.admin --owner=Администратор"

# Ключ наблюдателя (только чтение)
./gradlew :tools:issue-key --args="--roles=console.viewer --owner=Наблюдатель"
```

### 2. Запуск консоли
```bash
cd console
npm start
```
Консоль доступна по адресу [http://localhost:4200/console/](http://localhost:4200/console/).
Все запросы к `/admin/api` автоматически проксируются на `http://127.0.0.1:8081` через `proxy.conf.json`.

В dev-режиме (`environment.development.ts`) таймер неактивности настроен на 1 минуту для быстрой проверки окна «Продлить сессию».

## Структура проекта

```
console/
├── proxy.conf.json              # Проксирование /admin/api на gateway (8081)
├── src/
│   ├── index.html
│   ├── styles.css               # Глобальные стили, шрифты Onest и JetBrains Mono
│   ├── theme/
│   │   └── tokens.css           # Токены дизайн-системы (светлая/тёмная тема, отступы, шрифты)
│   ├── i18n/
│   │   └── ru.json              # Все интерфейсные тексты приложения
│   ├── environments/
│   │   ├── environment.ts       # idleMinutes: 30, idleWarnSeconds: 60
│   │   └── environment.development.ts # idleMinutes: 1, idleWarnSeconds: 60
│   └── app/
│       ├── app.config.ts        # Zoneless, withViewTransitions, withInterceptors
│       ├── app.routes.ts        # Маршрутизация: login, shell c guard, placeholder
│       ├── core/
│       │   ├── api/             # Базовый HTTP-клиент, AdminError, разбор ошибок
│       │   ├── i18n/            # I18n сервис и pipe 't'
│       │   ├── session/         # SessionStore (ключ в памяти), interceptor, guard, IdleService
│       │   └── theme/           # ThemeService (светлая/тёмная тема, dataset.theme)
│       ├── ui/                  # Компоненты дизайн-системы:
│       │   ├── badge/           # Бейджи (ok, info, warn, danger, neutral, graphite)
│       │   ├── button/          # Кнопки (primary, outline, ghost, danger, dark, quiet)
│       │   ├── card/            # Карточки с меткой-заголовком и ссылкой
│       │   ├── dialog/          # Модальные окна на CDK Dialog
│       │   ├── empty-state/     # Пустые состояния
│       │   ├── field/           # Поля ввода с меткой, иконкой, ошибкой
│       │   ├── icon/            # SVG-иконки 24×24 (линия 2px, скругления round)
│       │   ├── logo/            # Логотип ТПУ (крест 3×3 на токенах логотипа)
│       │   ├── skeleton/        # Скелетная загрузка
│       │   ├── stat/            # Плитки-числа со статистикой
│       │   ├── steps/           # Шаги мастера
│       │   ├── table/           # Таблицы со скроллом и заголовками-метками
│       │   └── toggle/          # Переключатели
│       ├── layout/
│       │   ├── shell/           # Оболочка: боковое меню 248px, шапка, сессия, подвал DB-IP
│       │   └── placeholder/     # Заглушка для экранов следующих шагов
│       └── features/
│           └── login/           # Экран 01: вход по ключу, обработка 401/403/404/429/сеть
```

## Главные правила разработки

1. **Тексты — только в `src/i18n/ru.json`**:
   - В шаблонах запрещены строковые литералы: все тексты выводятся через `{{ 'key' | t }}`.
   - Ошибки API маппятся на ключи `errors.*`.
2. **Цвета и размеры — только через `tokens.css`**:
   - В `src/app` строго запрещены цветовые литералы (`#hex`, `rgb`, `hsl`).
   - Используются только CSS-переменные: `var(--bg-page)`, `var(--text-primary)`, `var(--green-action)` и т. д.
3. **Безопасность сессии**:
   - Ключ консоли хранится **исключительно в памяти** (`SessionStore.key` signal).
   - Запрещено сохранять ключ в `localStorage`, `sessionStorage`, `cookie` или `IndexedDB`.
   - Ключ никогда не логируется.
   - 30 минут без действий пользователя (в проде) автоматически завершают сессию.
4. **Ограничение на размер**:
   - Ни один файл компонента или страницы не должен превышать 200 строк.
   - Общих классов «на всё» (`*Manager`) нет.

## Как обновлять `tokens.css`

Файл `src/theme/tokens.css` генерируется автоматически из макетов FigCraft (переменные «ТПУ»):
```bash
variable.export css -> src/theme/tokens.css
```
Ручная правка цветов в `tokens.css` запрещена — изменения вносятся на уровне палитр и экспортируются заново.
