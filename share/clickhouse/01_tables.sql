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
    device_id      String,
    received_at    DateTime64(3, 'UTC') DEFAULT now64(3),
    INDEX idx_trace_id trace_id TYPE bloom_filter GRANULARITY 4,
    INDEX idx_jpid jpid TYPE bloom_filter GRANULARITY 4
)
ENGINE = MergeTree
PARTITION BY toYYYYMM(ts)
ORDER BY (service_name, level, ts)
TTL toDateTime(ts) + INTERVAL ${DWH_CH_LOG_TTL_DAYS} DAY;

-- device_id (sender.params.deviceId: app install id, sent with logs before login) was added after the first
-- version of the table. For a table created earlier: metadata-only change, existing rows read as ''.
ALTER TABLE logs ADD COLUMN IF NOT EXISTS device_id String AFTER jpid;

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
    received_at    DateTime64(3, 'UTC') DEFAULT now64(3)
)
ENGINE = MergeTree
PARTITION BY toYYYYMM(ts)
ORDER BY (service_name, metric, ts)
TTL toDateTime(ts) + INTERVAL ${DWH_CH_STAT_TTL_DAYS} DAY;

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
