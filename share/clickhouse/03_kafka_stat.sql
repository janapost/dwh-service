-- UPM-2238: ClickHouse reads topic event_dwh_stat itself (Kafka engine + materialized views).
-- Required in data: metric, value. Everything else is optional.

CREATE TABLE IF NOT EXISTS kafka_dwh_stat
(
    raw String
)
ENGINE = Kafka
SETTINGS kafka_broker_list = '${DWH_CH_KAFKA_BROKERS}',
         kafka_topic_list = '${DWH_STAT_TOPIC}',
         kafka_group_name = 'clickhouse_dwh_stat',
         kafka_format = 'JSONAsString',
         kafka_num_consumers = ${DWH_CH_KAFKA_NUM_CONSUMERS},
         kafka_max_block_size = 65536,
         kafka_flush_interval_ms = 7500,
         kafka_handle_error_mode = 'stream';

-- ts is bounded the same way as in 02_kafka_log.sql (see the comment there).
CREATE MATERIALIZED VIEW IF NOT EXISTS mv_dwh_stat TO stats AS
WITH fromUnixTimestamp64Milli(if(JSONHas(raw, 'data', 'timestamp'),
                                 JSONExtractInt(raw, 'data', 'timestamp'),
                                 JSONExtractInt(raw, 'sender', 'timestamp')), 'UTC') AS event_ts,
     now64(3, 'UTC') AS insert_ts
SELECT
    JSONExtractString(raw, 'data', 'eventId')                              AS event_id,
    toUInt8(if(JSONHas(raw, 'data', 'schemaVersion'),
               JSONExtractUInt(raw, 'data', 'schemaVersion'), 1))          AS schema_version,
    JSONExtractString(raw, 'sender', 'appName')                            AS service_name,
    if(event_ts BETWEEN insert_ts - toIntervalDay(${DWH_CH_MAX_EVENT_AGE_DAYS}) AND insert_ts + toIntervalDay(1),
       event_ts, insert_ts)                                                AS ts,
    JSONExtractString(raw, 'data', 'metric')                               AS metric,
    JSONExtractFloat(raw, 'data', 'value')                                 AS value,
    JSONExtract(raw, 'data', 'tags', 'Map(String, String)')                AS tags,
    JSONExtractString(raw, 'sender', 'params', 'appVersion')               AS app_version,
    JSONExtractString(raw, 'sender', 'jpid')                               AS jpid
FROM kafka_dwh_stat
WHERE length(_error) = 0
  AND JSONHas(raw, 'data', 'metric')
  AND JSONHas(raw, 'data', 'value');

CREATE MATERIALIZED VIEW IF NOT EXISTS mv_dwh_stat_errors TO kafka_errors AS
SELECT
    _topic                                                                 AS topic,
    _partition                                                             AS partition,
    _offset                                                                AS offset,
    if(length(_error) > 0, _raw_message, raw)                              AS raw_message,
    if(length(_error) > 0, _error, 'missing required fields: data.metric, data.value') AS error
FROM kafka_dwh_stat
WHERE length(_error) > 0
   OR NOT (JSONHas(raw, 'data', 'metric') AND JSONHas(raw, 'data', 'value'));
