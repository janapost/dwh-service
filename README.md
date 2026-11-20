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
- **S2S-события** (UPM-2661): client-service публикует бизнес-события и ID устройств в Kafka `event_s2s`, dwh-service отправляет их провайдерам. Сейчас провайдер один — AppsFlyer (перенесён из client-service), под Adjust иерархия готова. План и переключение — `context-files/dwh-service/UPM-2661-plan.md`.

## S2S (UPM-2661)

```
event_s2s ─▶ S2SEventProcessor (jpevent) ─▶ S2SDispatcher ─▶ default-bean + parallel-beans
                                                              └─ AppsflyerS2SService_19_11_2026 ("appsflyer_s2s_sender")
```

- **Иерархия:** `service/s2s/S2SService` (контракт провайдера) → `AbstractS2SService` (общий конвейер: резерв по ключу дедупликации до отправки, `NO_IDENTITY`, ретрай, теневой режим) → провайдеры. Новый провайдер — бин с именем, затем имя в `S2S_DEFAULT_BEAN_NAME` / `S2S_PARALLEL_BEANS`. Неизвестное имя — сервис не стартует.
- **AppsFlyer:** запрос тот же байт в байт, что в client-service (тело, имена, параметры, `eventTime`). Это проверяет `AppsflyerS2SService_19_11_2026Test` против дословной копии кода client-service (`ClientServiceAppsflyerReference`). Журнал — коллекции `AppsflyerEventPO` / `AppsflyerIdentityPO`, та же схема, что в client-service.
- **Отличия от client-service:** таймауты HTTP (`APPSFLYER_CONNECT_TIMEOUT` / `APPSFLYER_READ_TIMEOUT`), лимиты ретрая из свойств, теневой режим `APPSFLYER_SEND_ENABLED=false` (по умолчанию): ID и строки журнала сохраняются, в AppsFlyer ничего не уходит.
- **Ошибки:** сбой хранилища до резерва → событие Kafka повторяется (jpevent `RETRY`, до 24 ч); сбой HTTP → строка `FAILED`, её повторяет `S2SScheduler` (раз в 5 мин, до `APPSFLYER_RETRY_MAX_ATTEMPTS`). Повторы безопасны: уникальный `dedupKey`.
- **Mongo-скрипт:** `scripts/s2s/s2s-indexes.js` — индексы, запускать до включения консьюмера.

## HTTP-коды ответов

Тело всегда `GenericResult`, а HTTP-код соответствует статусу (`exception/ResultHttpStatus`, ставит `GenericResultHttpStatusAdvice`):
`0` → 200, `-1` / `-2` / `-101` → 400, `-NNN` (`-401`, `-413`, `-415`, `-429` …) → NNN, `-3` → 503, `-90` и прочее → 500.
Для 429 добавляется заголовок `Retry-After`. Совместимость с декодерами `kz.janapost:feign-sdk`
(`GenericResultFeignConfig`: 429 / 5xx повторяются, остальные 4xx → `FeignCallException` со статусом из тела)
проверяет `FeignSdkCompatibilityTest`.

## Мониторинг и логи сервиса

- `dwh_mobile_publish_failed_total{topic}` (Prometheus, порт 9090) — пачки, которые Kafka не приняла (ответ 503). Алерт — на рост этой метрики, а не на строки в логе.
- `exception-mobile-kafka-publish` и `mobile-rate-limit` (SimpleLogger) пишутся не чаще раза в период (`util/ThrottledLog`: минута / `redis-cooldown-ms`), с числом пропущенных записей. В них только техническое описание ошибки — без тела пачки, `jpid` и IP.

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
| `DWH_TRUSTED_PROXIES` | точный CIDR подов ingress / gateway, от которых принимается `X-Real-IP`. По умолчанию пусто: заголовку не доверяем, лимит — по адресу соединения |
| `DWH_MOBILE_RL_PUBLIC_LOCAL_MAX` / `DWH_MOBILE_RL_CLIENT_LOCAL_MAX` | лимит `/public` / `/client` в секунду на под, пока Redis недоступен (по умолчанию 50 / 200) |
| `MONGO_HOST`, `MONGO_PORT`, `MONGO_USER`, `MONGO_PWD`, `MONGO_DB`, `MONGO_AUTH_DB` | **обязательны**: своя база dwh-service (журнал S2S, коллекция jpevent `Event_dwh_service`). Без Mongo сервис не стартует |
| `S2S_CONSUMER_ENABLED` | читать `event_s2s` (по умолчанию `false`) |
| `S2S_DEFAULT_BEAN_NAME` / `S2S_PARALLEL_BEANS` | основной провайдер / параллельные через запятую (по умолчанию `appsflyer_s2s_sender` / пусто) |
| `APPSFLYER_SEND_ENABLED` | отправлять в AppsFlyer (по умолчанию `false` — теневой режим). При `true` пустой `APPSFLYER_S2S_KEY` не даст сервису стартовать |
| `APPSFLYER_S2S_KEY`, `APPSFLYER_ANDROID_APP_ID`, `APPSFLYER_IOS_APP_ID` | ключ и app id AppsFlyer (те же, что у client-service); ключ — секрет |
| `APPSFLYER_EVENT_CLEANUP_ENABLED` | ночная очистка `SENT` / `SKIPPED` старше 90 дней (по умолчанию `false`; включать после индекса `(status, createTime)`) |

## Безопасность и ограничения

- **Персональные данные в логах приложения.** До Kafka маскируются:
  - токены (`Bearer`, JWT, `X-TokenInfo`);
  - email;
  - номера карт (проверка Луна);
  - телефоны KZ / KG / UZ / TJ;
  - ИИН / БИН (12 цифр с контрольной цифрой).

  Маскирование эвристическое, приложение не должно логировать ПДн.
- **Kafka.** Трафик идёт только внутри сети: dev1 — Kafka в кластере (`PLAINTEXT`), stage / prod — Yandex Managed Kafka по внутренним адресам (`SASL_PLAINTEXT`, SCRAM-SHA-512, как у остальных сервисов). Пароль SCRAM по сети не передаётся, но сами сообщения (логи, `jpid`, `client_id`) не шифруются. Переход на `SASL_SSL`: `KAFKA_SECURITY_PROTOCOL=SASL_SSL`, TLS-порт брокера и CA-сертификат Yandex в truststore образа. Это решение по инфраструктуре для всех сервисов.
- **Redis недоступен.** Запросы не блокируются, а ограничиваются локально, в памяти пода (переменные выше).
- **Логи до входа (`/public`) — недоверенные данные.** Без токена клиент сам задаёт `clientId`, `appVersion`, платформу и текст. Такие строки в `logs` помечены `untrusted = 1` (`sender.params.untrusted = true`), в аналитике их нужно отделять: `WHERE untrusted = 0`. От злоупотреблений защищают флаг, лимиты частоты, размер пачки и тела. Подпись или app attestation для `/public` пока нет.

### Перед включением `/public` (`DWH_MOBILE_PUBLIC_ENABLED=true`)

Код принимает `X-Real-IP` только от доверенных прокси (`DWH_TRUSTED_PROXIES`, по умолчанию пусто). Если сам ingress / gateway пропускает значение клиента, лимит «на IP» обходится. Перед включением на окружении:

0. Узнать CIDR подов ingress / gateway, через которые приходят запросы к dwh-service, и задать `DWH_TRUSTED_PROXIES` только им. Не все частные сети: иначе любой под кластера сможет подставить чужой IP.
1. В конфигурации ingress / gateway для маршрута `/dwh-service` найти перезапись заголовка: для nginx — `proxy_set_header X-Real-IP $remote_addr;` (или аналог). Он должен **перезаписывать** заголовок, а не пропускать клиентский.
2. Проверить запросом извне:

   ```bash
   curl -X POST https://<gateway>/dwh-service/public/v1/log/add-batch -H 'Content-Type: application/json' -H 'X-Real-IP: 1.2.3.4' --data '{"items":[{"level":"INFO","message":"x-real-ip-check"}]}'
   ```

   В Redis должен появиться ключ `dwh-mobile-rl:ip:<ваш внешний IP>`, а **не** `dwh-mobile-rl:ip:1.2.3.4`.
3. Записать результат (кто, когда, где проверил) в описании задачи и только потом включать флаг в `argocd`.
| `CLICKHOUSE_*` | заглушка: сервис в ClickHouse пока не ходит |

Значения по окружениям — в репозитории `argocd`: `services/dwh-service/janapost-dev1.yaml` (dev1) и `services/dwh-service/stage/values.yaml` (stage).

Mongo обязателен с UPM-2661: jpevent при старте создаёт коллекцию `Event_dwh_service` и без базы падает через 30 с. Перед выкаткой версии с S2S в `argocd` должны быть `MONGO_*`.

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
