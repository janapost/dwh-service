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
| `KAFKA_SECURITY_PROTOCOL` | `SASL_PLAINTEXT` по умолчанию; `PLAINTEXT` для Kafka внутри кластера (dev1) |
| `REDIS_HOST`, `REDIS_PORT`, `REDIS_PASSWORD` | Redis для лимитов частоты (при недоступности запросы пропускаются) |
| `DWH_MOBILE_ENABLED` / `DWH_MOBILE_PUBLIC_ENABLED` | включить эндпоинты после / до входа (по умолчанию `false`) |
| `DWH_MOBILE_*`, `DWH_MOBILE_RL_*` | лимиты пачки, тела и частоты — см. `share/application-yandex.properties` |
| `DWH_TRUSTED_PROXIES` | CIDR прокси, от которых принимается `X-Real-IP` (по умолчанию частные сети и localhost); от остальных — адрес соединения |
| `DWH_MOBILE_RL_PUBLIC_LOCAL_MAX` / `DWH_MOBILE_RL_CLIENT_LOCAL_MAX` | лимит `/public` / `/client` в секунду на под, пока Redis недоступен (по умолчанию 50 / 200) |

## Безопасность и ограничения

- **Персональные данные в логах приложения.** До Kafka маскируются:
  - токены (`Bearer`, JWT, `X-TokenInfo`);
  - email;
  - номера карт (проверка Луна);
  - телефоны KZ / KG / UZ / TJ;
  - ИИН / БИН (12 цифр с контрольной цифрой).

  Маскирование эвристическое, приложение не должно логировать ПДн.
- **Kafka.** Трафик идёт только внутри сети: dev1 — Kafka в кластере (`PLAINTEXT`), stage / prod — Yandex Managed Kafka по внутренним адресам (`SASL_PLAINTEXT`, SCRAM-SHA-512, как у остальных сервисов). Пароль SCRAM по сети не передаётся, но сами сообщения (логи, `jpid`, `device_id`) не шифруются. Переход на `SASL_SSL`: `KAFKA_SECURITY_PROTOCOL=SASL_SSL`, TLS-порт брокера и CA-сертификат Yandex в truststore образа. Это решение по инфраструктуре для всех сервисов.
- **Redis недоступен.** Запросы не блокируются, а ограничиваются локально, в памяти пода (переменные выше).

### Перед включением `/public` (`DWH_MOBILE_PUBLIC_ENABLED=true`)

Код принимает `X-Real-IP` только от доверенных прокси (`DWH_TRUSTED_PROXIES`). Если сам ingress / gateway пропускает значение клиента, лимит «на IP» обходится. Перед включением на окружении:

1. В конфигурации ingress / gateway для маршрута `/dwh-service` найти перезапись заголовка: для nginx — `proxy_set_header X-Real-IP $remote_addr;` (или аналог). Он должен **перезаписывать** заголовок, а не пропускать клиентский.
2. Проверить запросом извне:

   ```bash
   curl -X POST https://<gateway>/dwh-service/public/v1/log/add-batch -H 'Content-Type: application/json' -H 'X-Real-IP: 1.2.3.4' --data '{"items":[{"level":"INFO","message":"x-real-ip-check"}]}'
   ```

   В Redis должен появиться ключ `dwh-mobile-rl:ip:<ваш внешний IP>`, а **не** `dwh-mobile-rl:ip:1.2.3.4`.
3. Записать результат (кто, когда, где проверил) в описании задачи и только потом включать флаг в `argocd`.
| `CLICKHOUSE_*` | заглушка: сервис в ClickHouse пока не ходит |

Значения по окружениям — в репозитории `argocd`: `services/dwh-service/janapost-dev1.yaml` (dev1) и `services/dwh-service/stage/values.yaml` (stage).

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
