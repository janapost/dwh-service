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

## Переменные окружения

| Переменная | Назначение |
|---|---|
| `SWAGGER_ENABLED` | включить `/v3/api-docs` |
| `SCHEDULING_POOL_SIZE` | размер пула `@Scheduled` (по умолчанию 3) |

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
