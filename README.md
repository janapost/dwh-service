# dwh-service

Сервис JanaPost. Каркас создан по `context-files/new-service-guide.md`.

- Java 17, Spring Boot 3.1.1 (parent `kz.janapost:central-parant:1.0.0-Release`).
- Пакет: `kz.janapost.dwhservice`.
- Порт приложения `8080`, метрики (`/actuator/health`, `/actuator/prometheus`) — `9090`.
- Prod-конфиг: `share/application-yandex.properties` → `/etc/share/application-yandex.properties` в образе.
  `src/main/resources/application.properties` — только для локального запуска.

## Сборка

```bash
./mvnw clean package   # тесты пропускаются (skipTests=true), запускать в IDE
./mvnw verify          # + checkstyle
```

## Что делает

- **Логи и статистика** (UPM-2238): сервисы шлют в Kafka (`event_dwh_log`, `event_dwh_stat`), ClickHouse читает топики сам (Kafka engine + MV). SQL и порядок — `share/clickhouse/README.md`.
- **Эндпоинты для мобильного приложения:**
  - `POST /client/v1/log/add-batch`, `POST /client/v1/stat/add-batch` — после входа;
  - `POST /public/v1/log/add-batch` — до входа.

  Проверяют данные, маскируют ПДн и публикуют в те же топики. API — `context-files/dwh-service/mobile-log-api.md`.

## Переменные окружения

| Переменная | Назначение |
|---|---|
| `SWAGGER_ENABLED` | включить `/v3/api-docs` |
| `SCHEDULING_POOL_SIZE` | размер пула `@Scheduled` (по умолчанию 3) |
| `KAFKA_BOOTSTRAP_SERVERS`, `KAFKA_USERNAME`, `KAFKA_PASSWORD` | Kafka (SASL SCRAM-SHA-512) |
| `REDIS_HOST`, `REDIS_PORT`, `REDIS_PASSWORD` | Redis для лимитов частоты (при недоступности запросы пропускаются) |
| `DWH_MOBILE_ENABLED` / `DWH_MOBILE_PUBLIC_ENABLED` | включить эндпоинты после / до входа (по умолчанию `false`) |
| `DWH_MOBILE_*`, `DWH_MOBILE_RL_*` | лимиты пачки, тела и частоты — см. `share/application-yandex.properties` |
| `CLICKHOUSE_*` | заглушка: сервис в ClickHouse пока не ходит |

Mongo приходит транзитивно (`log-manager` → `kz.janapost:util`), но его автоконфигурация выключена (`spring.autoconfigure.exclude`), пока Mongo никто не использует.

## CI/CD

| Workflow | Триггер | Тег образа |
|---|---|---|
| `base-image.yml` | ручной | `base0.0.1` |
| `check-style.yml` | pull_request | — |
| `block-dev-merge.yml` | PR в `stage` / `main` | — |
| `yc-build-dev1.yml` | push `dev` | `dev-N` |
| `yc-build-stage.yml` | push `stage` | `stage-N` |
| `yc-build.yml` | release `yc*` из `main` | `${tag}` |

Секреты репозитория: `REGPASS`, `ARGOCD_GITHUB_TOKEN`.
