-- UPM-2238: change retention periods without re-creating tables.
-- Set DWH_CH_LOG_TTL_DAYS / DWH_CH_STAT_TTL_DAYS / DWH_CH_ERRORS_TTL_DAYS and run: ./apply.sh 04_ttl.sql

ALTER TABLE logs MODIFY TTL toDateTime(ts) + INTERVAL ${DWH_CH_LOG_TTL_DAYS} DAY;

ALTER TABLE stats MODIFY TTL toDateTime(ts) + INTERVAL ${DWH_CH_STAT_TTL_DAYS} DAY;

ALTER TABLE kafka_errors MODIFY TTL toDateTime(received_at) + INTERVAL ${DWH_CH_ERRORS_TTL_DAYS} DAY;
