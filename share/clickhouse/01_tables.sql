-- UPM-2238: target tables for logs and stats read from Kafka.
-- Executed by apply.sh: ${DWH_CH_*_TTL_DAYS} are substituted from environment variables.
-- To change a retention period later use 04_ttl.sql (no table re-creation).

CREATE TABLE IF NOT EXISTS logs
(
    event_id       String,
    schema_version UInt8,
    service_name   LowCardinality(String),
    ts             DateTime64(3, 'UTC'),
    level          LowCardinality(String),
    message        String,
    stack_trace    String,
    trace_id       String,
    attributes     Map(String, String),
    app_version    String,
    device_model   String,
    os_version     String,
    jpid           String,
    client_id      String,
    untrusted      UInt8,
    received_at    DateTime64(3, 'UTC') DEFAULT now64(3),
    INDEX idx_trace_id trace_id TYPE bloom_filter GRANULARITY 4,
    INDEX idx_jpid jpid TYPE bloom_filter GRANULARITY 4,
    INDEX idx_client_id client_id TYPE bloom_filter GRANULARITY 4
)
ENGINE = MergeTree
PARTITION BY toYYYYMM(ts)
ORDER BY (service_name, level, ts)
TTL toDateTime(ts) + INTERVAL ${DWH_CH_LOG_TTL_DAYS} DAY;

-- Upgrades of a table created by an earlier version of this script. All idempotent and metadata-only.
-- Run with the MVs dropped (mv_dwh_log writes into these columns):
--   ./apply.sh 05_drop_mv.sql 01_tables.sql 02_kafka_log.sql 03_kafka_stat.sql
--
-- client_id (sender.params.clientId: app install id, required in all mobile endpoints) used to be device_id
-- (sent only with logs before login). Rename keeps the existing values; then add the column if neither existed.
ALTER TABLE logs RENAME COLUMN IF EXISTS device_id TO client_id;
ALTER TABLE logs ADD COLUMN IF NOT EXISTS client_id String AFTER jpid;
-- Index applies to new parts; old parts are scanned as before (MATERIALIZE INDEX is not needed for test data).
ALTER TABLE logs ADD INDEX IF NOT EXISTS idx_client_id client_id TYPE bloom_filter GRANULARITY 4;

-- untrusted = 1: the record came without a token (/public, logs before login); everything in it is client-supplied.
-- Analytics must treat such rows as untrusted. Existing rows read as 0.
ALTER TABLE logs ADD COLUMN IF NOT EXISTS untrusted UInt8 AFTER client_id;

CREATE TABLE IF NOT EXISTS stats
(
    event_id       String,
    schema_version UInt8,
    service_name   LowCardinality(String),
    ts             DateTime64(3, 'UTC'),
    metric         LowCardinality(String),
    value          Float64,
    tags           Map(String, String),
    app_version    String,
    jpid           String,
    client_id      String,
    received_at    DateTime64(3, 'UTC') DEFAULT now64(3),
    INDEX idx_client_id client_id TYPE bloom_filter GRANULARITY 4
)
ENGINE = MergeTree
PARTITION BY toYYYYMM(ts)
ORDER BY (service_name, metric, ts)
TTL toDateTime(ts) + INTERVAL ${DWH_CH_STAT_TTL_DAYS} DAY;

ALTER TABLE stats ADD COLUMN IF NOT EXISTS client_id String AFTER jpid;
ALTER TABLE stats ADD INDEX IF NOT EXISTS idx_client_id client_id TYPE bloom_filter GRANULARITY 4;

CREATE TABLE IF NOT EXISTS kafka_errors
(
    topic       LowCardinality(String),
    partition   UInt64,
    offset      UInt64,
    raw_message String,
    error       String,
    received_at DateTime64(3, 'UTC') DEFAULT now64(3)
)
ENGINE = MergeTree
ORDER BY (topic, received_at)
TTL toDateTime(received_at) + INTERVAL ${DWH_CH_ERRORS_TTL_DAYS} DAY;
