# Monitoring Platform

Платформа мониторинга доступности сайтов и SSL-сертификатов: пользователь заводит мониторы,
сервис периодически их проверяет, хранит историю и (в разработке) присылает алерты в Telegram.

Проект учебный и демонстрационный: цель — показать современный Java-стек и типовые
production-паттерны (OAuth2, transactional outbox, event-driven взаимодействие, идемпотентность),
а не заменить готовые сервисы вроде UptimeRobot.

## Статус

| Возможность | Состояние |
|---|---|
| Регистрация и вход через Keycloak (OAuth2 / OIDC) | ✅ |
| Создание, редактирование, пауза и удаление мониторов (лимит FREE: 5) | ✅ |
| Проверки HTTP и SSL, параллельно на virtual threads | ✅ |
| Автообновление статусов в UI | ✅ |
| Transactional outbox → Kafka (Avro + Schema Registry) | ✅ |
| Привязка Telegram (одноразовая ссылка, long polling) | ✅ |
| Доставка алертов в Telegram с идемпотентностью | 🚧 ветка `feature/telegram-alerts` |
| Кнопка «Подключить Telegram» в UI | 🚧 |
| DNS/WHOIS-проверка, API Gateway, OpenAPI-контракты | ⏳ не начато |

## Архитектура

```mermaid
flowchart LR
    Browser["Браузер"] --> WebUI["web-ui :8083 (Vaadin)"]
    WebUI -->|"OAuth2 login"| KC["Keycloak :8180"]
    WebUI -->|"REST + JWT пользователя"| Core["core-api :8082"]
    Core --> DB1[("Postgres: core_api")]
    Checker["checker-worker"] -->|"выборка задач, SKIP LOCKED"| DB1
    Checker -->|"outbox → Avro"| Kafka[("Kafka: monitor-status-events")]
    Kafka --> Notif["notification-service :8085"]
    Notif --> DB2[("Postgres: notification_service")]
    Notif -->|"Bot API"| TG["Telegram"]
    WebUI -->|"Admin API: регистрация"| KC
```

| Модуль | Назначение |
|---|---|
| `common-events` | Avro-схема события `MonitorStatusEvent`, общий контракт сервисов |
| `core-api` | REST API мониторов, OAuth2 Resource Server (stateless), владелец схемы БД `core_api` |
| `checker-worker` | Выполняет проверки, пишет результаты и outbox, публикует события в Kafka. HTTP-порта нет |
| `notification-service` | Читает события, привязка каналов, Telegram-бот. Своя БД, OAuth2 Resource Server |
| `web-ui` | Vaadin-интерфейс, OAuth2 Client (stateful-сессия), регистрация через Keycloak Admin API |
| `infra/` | Init-скрипт Postgres и realm Keycloak для автоимпорта |

## Ключевые решения

- **Keycloak — единственный источник идентичности.** Пользователь во всех сервисах определяется
  Keycloak `sub`. `web-ui` — OAuth2-клиент с сессией, `core-api` и `notification-service` —
  stateless Resource Server'ы, поэтому это отдельные модули.
- **Очередь задач поверх Postgres** (`SELECT ... FOR UPDATE SKIP LOCKED`): захват задачи (быстрая
  транзакция) отделён от её выполнения (медленный сетевой I/O), несколько воркеров не пересекаются.
- **Transactional outbox.** Смена статуса и событие пишутся в одной транзакции, публикация в Kafka —
  отдельным шагом. Гарантия доставки at-least-once, консьюмеры идемпотентны по `eventId`.
- **Порог неудач.** Монитор становится DOWN после N проверок подряд (`checker.failure-threshold`,
  по умолчанию 3), чтобы нестабильная сеть не порождала шквал алертов.
- **Привязка Telegram через deep-link.** `chat_id` от клиента не принимается. Токен привязки
  одноразовый, живёт 10 минут, в БД хранится только его SHA-256.
- **Одна схема — один владелец.** Миграции Liquibase `core_api` ведёт `core-api`; `checker-worker`
  использует ту же БД без собственных миграций (осознанное упрощение).

## Требования

JDK 21, Maven 3.9+, Docker Desktop, Node.js 18+ (Vaadin в dev-режиме собирает фронтенд).

## Быстрый старт

```powershell
# 1. Инфраструктура: Postgres, Keycloak (realm импортируется сам), Kafka, Schema Registry, Kafka UI
docker compose up -d

# 2. Сборка всех модулей (common-events должен собраться первым — Maven сам упорядочит)
mvn clean install

# 3. Сервисы — каждый в своём окне терминала
cd core-api;             mvn spring-boot:run
cd checker-worker;       mvn spring-boot:run
cd notification-service; mvn spring-boot:run
cd web-ui;               mvn spring-boot:run
```

Откройте http://localhost:8083. Первый запуск `web-ui` долгий: Vaadin ставит npm-зависимости.

**Telegram-бот.** Создайте бота у @BotFather, затем:
- имя бота впишите в `notification-service/src/main/resources/application.yml` (`telegram.bot-username`);
- токен передайте переменной окружения `TELEGRAM_BOT_TOKEN`. В репозиторий токен не попадает.

## Порты

| Что | Порт |
|---|---|
| Postgres (хост → контейнер) | 5334 → 5432 |
| Keycloak | 8180 |
| Kafka (listener для приложений на хосте) | 9092 |
| Schema Registry | 8081 |
| Kafka UI | 8090 |
| Zipkin (поднят, пока не используется) | 9411 |
| core-api / web-ui / notification-service | 8082 / 8083 / 8085 |

Базы в одном контейнере Postgres: `core_api`, `notification_service`, `keycloak`.

## Учётные данные для разработки

Всё ниже предназначено **только для локальной разработки** и лежит в репозитории осознанно.
В реальном проекте такие значения в git не хранят.

- Keycloak Admin Console: `admin` / `admin`, realm `monitoring-platform`
- Тестовый пользователь: `testuser` / `test1234`
- Клиенты (секреты в `infra/keycloak/realm-export.json`): `core-api-client`, `web-ui-client`, `web-ui-admin-client`

## Структура репозитория

```
.
├── pom.xml                  родительский pom
├── docker-compose.yml
├── infra/
│   ├── postgres-init/       создание баз данных
│   └── keycloak/            realm-export.json
├── common-events/           Avro-контракт
├── core-api/
├── checker-worker/
├── notification-service/
└── web-ui/
```

## Типичные проблемы

- **Порт занят** (5432, 8081, 8080): измените публикуемый порт в `docker-compose.yml` и URL в `application.yml`.
- **`docker compose down -v` удаляет данные ВСЕХ сервисов**, а не одного: том Postgres общий.
  После него realm Keycloak и базы пересоздаются автоматически, пользователи и мониторы — нет.
- **Realm не обновился после правки `realm-export.json`**: импорт срабатывает только на пустой БД Keycloak.
- **`Account is not fully set up` при входе**: у пользователя в Keycloak должны быть заполнены имя и фамилия.
- **Ошибка в `*.sh` на Windows**: проверьте, что в файле переводы строк LF (за это отвечает `.gitattributes`).

## Известные ограничения

- Нет автотестов и CI.
- Алерт теряется, если Telegram недоступен дольше минуты (dead-letter топика ещё нет).
- Время в алертах — в UTC.
- В `web-ui` нет аккуратного перехода на повторный вход при истечении сессии Keycloak.
- Предупреждение Avro про `logicalType` в `monitor-status-event.avsc` безвредно, но не исправлено.

## Работа с Git

- `main` — всегда собирающаяся рабочая версия. Напрямую в неё не коммитим.
- Каждое изменение — в своей ветке `feature/<что-делаем>`, `fix/<что-чиним>` или `docs/<что-описываем>`,
  затем Pull Request в `main` (просмотр диффа и merge на GitHub).
- Сообщения коммитов в стиле Conventional Commits: `feat: ...`, `fix: ...`, `docs: ...`, `refactor: ...`, `chore: ...`.
- Один PR — одна законченная мысль.
