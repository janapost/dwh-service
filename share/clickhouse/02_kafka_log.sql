-- UPM-2238: ClickHouse reads topic event_dwh_log itself (Kafka engine + materialized views).
-- Message = jpevent envelope: { topic, sender{appName, jpid, timestamp, params}, eventName, data }.
-- Required in data: level, message. Everything else is optional.
-- Kafka SASL credentials are NOT here: they live in the cluster settings (Managed) or in config.xml / named collection.

CREATE TABLE IF NOT EXISTS kafka_dwh_log
(
    raw String
)
ENGINE = Kafka
SETTINGS kafka_broker_list = '${DWH_CH_KAFKA_BROKERS}',
         kafka_topic_list = '${DWH_LOG_TOPIC}',
         kafka_group_name = 'clickhouse_dwh_log',
         kafka_format = 'JSONAsString',
         kafka_num_consumers = ${DWH_CH_KAFKA_NUM_CONSUMERS},
         kafka_max_block_size = 65536,
         kafka_flush_interval_ms = 7500,
         kafka_handle_error_mode = 'stream';

-- ts outside [now - DWH_CH_MAX_EVENT_AGE_DAYS, now + 1 day] (broken clock, seconds instead of ms, missing value)
-- is replaced with the insert time. Otherwise one Kafka block with >100 different months fails the insert
-- (max_partitions_per_insert_block), the offset is not committed and reading stops on that block forever;
-- and a record older than TTL would be silently dropped. The original value stays in event_ts only for the check.
CREATE MATERIALIZED VIEW IF NOT EXISTS mv_dwh_log TO logs AS
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
    JSONExtractString(raw, 'data', 'level')                                AS level,
    JSONExtractString(raw, 'data', 'message')                              AS message,
    JSONExtractString(raw, 'data', 'stackTrace')                           AS stack_trace,
    JSONExtractString(raw, 'data', 'traceId')                              AS trace_id,
    JSONExtract(raw, 'data', 'attributes', 'Map(String, String)')          AS attributes,
    JSONExtractString(raw, 'sender', 'params', 'appVersion')               AS app_version,
    JSONExtractString(raw, 'sender', 'params', 'deviceModel')              AS device_model,
    JSONExtractString(raw, 'sender', 'params', 'osVersion')                AS os_version,
    JSONExtractString(raw, 'sender', 'jpid')                               AS jpid,
    JSONExtractString(raw, 'sender', 'params', 'deviceId')                 AS device_id
FROM kafka_dwh_log
WHERE length(_error) = 0
  AND JSONHas(raw, 'data', 'level')
  AND JSONHas(raw, 'data', 'message');

CREATE MATERIALIZED VIEW IF NOT EXISTS mv_dwh_log_errors TO kafka_errors AS
SELECT
    _topic                                                                 AS topic,
    _partition                                                             AS partition,
    _offset                                                                AS offset,
    if(length(_error) > 0, _raw_message, raw)                              AS raw_message,
    if(length(_error) > 0, _error, 'missing required fields: data.level, data.message') AS error
FROM kafka_dwh_log
WHERE length(_error) > 0
   OR NOT (JSONHas(raw, 'data', 'level') AND JSONHas(raw, 'data', 'message'));
