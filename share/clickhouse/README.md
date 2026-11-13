# ClickHouse: приём логов и статистики из Kafka (UPM-2238)

ClickHouse сам читает топики `event_dwh_log` и `event_dwh_stat` через Kafka engine. Materialized view раскладывают сообщения по таблицам `logs` и `stats`, а битые сообщения — в `kafka_errors`. dwh-service в ClickHouse не пишет.

```
Kafka event_dwh_log  ──▶ kafka_dwh_log  ──mv_dwh_log──────────▶ logs
                                        └─mv_dwh_log_errors───▶ kafka_errors
Kafka event_dwh_stat ──▶ kafka_dwh_stat ──mv_dwh_stat─────────▶ stats
                                        └─mv_dwh_stat_errors──▶ kafka_errors
```

## Файлы

| Файл | Что делает |
|---|---|
| `01_tables.sql` | Создаёт таблицы `logs`, `stats`, `kafka_errors`; на уже созданной `logs` добавляет `device_id` (`ADD COLUMN IF NOT EXISTS`) |
| `02_kafka_log.sql` | Kafka-таблица и MV для логов |
| `03_kafka_stat.sql` | Kafka-таблица и MV для статистики |
| `04_ttl.sql` | Меняет сроки хранения без пересоздания таблиц |
| `05_drop_mv.sql` | Удаляет MV перед пересозданием с новым определением (только вместе с `02` и `03`) |
| `apply.sh` | Подставляет переменные окружения и выполняет скрипты |

Скрипты идемпотентны (`IF NOT EXISTS`), повторный запуск безопасен.

## Переменные окружения

| Переменная | По умолчанию | Описание |
|---|---|---|
| `CLICKHOUSE_HOST` | — | Тест: `185.32.84.48` снаружи k8s, `clickhouse-events` внутри |
| `CLICKHOUSE_NATIVE_PORT` | `9000` | Тест снаружи k8s: `30902` |
| `CLICKHOUSE_USER` | `default` | |
| `CLICKHOUSE_PASSWORD` | — | **Секрет.** Только в окружении, в git не попадает |
| `CLICKHOUSE_DB` | `default` | |
| `DWH_CH_KAFKA_BROKERS` | — | Список брокеров, например `kafka-1:9092,kafka-2:9092`; обязателен для `02` и `03` |
| `DWH_CH_KAFKA_NUM_CONSUMERS` | `2` | Не больше числа партиций топика |
| `DWH_LOG_TOPIC` / `DWH_STAT_TOPIC` | `event_dwh_log` / `event_dwh_stat` | |
| `DWH_CH_LOG_TTL_DAYS` | `90` | Срок хранения `logs` |
| `DWH_CH_STAT_TTL_DAYS` | `365` | Срок хранения `stats` |
| `DWH_CH_ERRORS_TTL_DAYS` | `30` | Срок хранения `kafka_errors` |
| `DWH_CH_MAX_EVENT_AGE_DAYS` | `30` | Окно времени события: `ts` старше этого числа дней или впереди больше чем на сутки заменяется временем вставки. Не больше 2700 |

### Почему время события ограничено

`ts` приходит от отправителя, в том числе с часов телефона.
- Если в одном блоке Kafka engine окажется больше 100 разных месяцев, вставка упадёт с ошибкой `max_partitions_per_insert_block`. Offset не закоммитится, и чтение топика навсегда встанет на этом блоке.
- Запись со сбитыми часами (1970, секунды вместо мс) сразу оказалась бы старше TTL и молча удалилась бы.

Поэтому MV заменяет неправдоподобное время временем вставки. dwh-service делает то же для мобильного приложения ещё до Kafka и сохраняет исходное значение в `attributes.clientTimestamp` / `tags.clientTimestamp`.

Если ClickHouse не читал Kafka дольше `DWH_CH_MAX_EVENT_AGE_DAYS`, старые сообщения при догоне получат время вставки.

**SASL-доступ ClickHouse к Kafka** (логин и пароль брокера) в скриптах не задаётся:
- Managed-кластер — в настройках кластера;
- свой кластер — в `config.xml` (секция `<kafka>`) или через named collection.

## Порядок

```bash
cd share/clickhouse
export CLICKHOUSE_HOST=185.32.84.48 CLICKHOUSE_NATIVE_PORT=30902 CLICKHOUSE_USER=default
export CLICKHOUSE_PASSWORD=...            # не сохранять в истории: можно через read -s
export DWH_CH_KAFKA_BROKERS=...
DRY_RUN=1 ./apply.sh                      # посмотреть SQL после подстановки
./apply.sh                                # 01, 02, 03
```

**Окно при первом создании.** Чтение топика начинается, как только к Kafka-таблице подключён первый MV. Скрипт создаёт `mv_*` и `mv_*_errors` подряд. Битые сообщения, пришедшие в эти миллисекунды, в `kafka_errors` не попадут — это допустимо.

Если нужно без окна: `DETACH TABLE kafka_dwh_log` сразу после создания MV для данных, затем создать `mv_dwh_log_errors` и `ATTACH TABLE kafka_dwh_log`.

**Сменить срок хранения:**

```bash
DWH_CH_LOG_TTL_DAYS=30 ./apply.sh 04_ttl.sql
```

`04_ttl.sql` меняет TTL у всех трёх таблиц. Переменные, которые не задали, берутся по умолчанию — проверьте их через `DRY_RUN=1`.

> ⚠️ `MODIFY TTL` запускает мутацию по всей таблице: пересчёт TTL и удаление устаревших данных. На больших таблицах это долго и нагружает диск. Выполнять вне пиковой нагрузки, прогресс смотреть в `system.mutations`.

## Обновление MV на уже настроенном кластере

`CREATE MATERIALIZED VIEW IF NOT EXISTS` молча пропускает существующий MV. Если скрипты уже выполнялись, повторный `./apply.sh` новое определение **не применит** — например, окно времени события.

Проверить, созданы ли MV и какое у них определение:

```sql
SELECT name FROM system.tables WHERE name LIKE 'mv_dwh_%';
SHOW CREATE TABLE mv_dwh_log;   -- в новой версии есть toIntervalDay(<DWH_CH_MAX_EVENT_AGE_DAYS>)
```

Пересоздать (таблицы и данные не трогаются):

```bash
DRY_RUN=1 ./apply.sh 05_drop_mv.sql 02_kafka_log.sql 03_kafka_stat.sql   # посмотреть SQL
./apply.sh 05_drop_mv.sql 02_kafka_log.sql 03_kafka_stat.sql
```

`05_drop_mv.sql` удаляет сначала `mv_*_errors`, потом основной MV. Без подключённых MV Kafka engine не читает, offset группы стоит на месте, сообщения ждут в Kafka. Затем `02`/`03` создают основной MV и `mv_*_errors`. Валидные сообщения не теряются. В короткий промежуток между созданием двух MV могут не попасть в `kafka_errors` только битые.

После пересоздания:
- `SHOW CREATE TABLE mv_dwh_log` содержит окно и `device_id`;
- проверки 5–7 из раздела «Проверка» проходят.

`./apply.sh 01_tables.sql` перед этим добавит колонку `device_id` в уже созданную `logs`.

## Изменение схемы

Изменение схемы (новая колонка, разбор новой `schemaVersion`):
1. `DETACH TABLE kafka_dwh_log` — чтение останавливается, сообщения копятся в Kafka.
2. `ALTER TABLE logs ADD COLUMN ...`, затем `DROP VIEW mv_dwh_log` и создать заново с новым разбором.
3. `ATTACH TABLE kafka_dwh_log` — чтение продолжается с сохранённого offset.

Срок хранения топика в Kafka должен быть больше максимального простоя.

## Мониторинг

```sql
-- последнее чтение и ошибки консьюмеров Kafka engine
SELECT database, table, consumer_id, last_poll_time, num_messages_read, exceptions.text
FROM system.kafka_consumers;

-- битые сообщения за последний час
SELECT topic, error, count() FROM kafka_errors
WHERE received_at > now() - INTERVAL 1 HOUR GROUP BY topic, error;
```

Алерты:
- lag групп `clickhouse_dwh_log` и `clickhouse_dwh_stat` в Kafka;
- исключения в `system.kafka_consumers`;
- рост `kafka_errors`.

## Проверка на тестовом кластере

1. В `logs` попадают:
   - валидный лог;
   - лог без `data.timestamp` (берётся `sender.timestamp`);
   - лог без `eventId` и `schemaVersion`;
   - лог с пустыми `attributes`.
2. В `kafka_errors` попадают битый JSON и лог без `data.level` или `data.message`. Остальные сообщения пачки доходят до `logs`.
3. `DETACH TABLE logs`, отправить сообщения, `ATTACH TABLE logs` — сообщения доходят.
4. `DWH_CH_LOG_TTL_DAYS=30 ./apply.sh 04_ttl.sql` — `SHOW CREATE TABLE logs` показывает новый TTL.
5. **Разные месяцы в одном блоке.** Отправить подряд 150 логов с `data.timestamp` в 150 разных месяцах (например, 1 число каждого месяца с 2000 года):
   - чтение не встаёт;
   - все 150 записей в `logs` с `ts` ≈ время вставки;
   - lag группы `clickhouse_dwh_log` уходит в 0.
6. **Лог до входа** (`/public/v1/log/add-batch` с `deviceId`) виден в `logs` с пустым `jpid` и `device_id` = `deviceId`.
7. **Сбитые часы.** Лог с `data.timestamp = 1000` (1970) виден в `logs` с `ts` ≈ время вставки и не удаляется TTL.
8. То же для статистики (`data.metric`, `data.value`).
