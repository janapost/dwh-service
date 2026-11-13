-- UPM-2238: drop materialized views before re-creating them with a new definition.
-- CREATE MATERIALIZED VIEW IF NOT EXISTS in 02/03 silently skips an existing view, so a changed MV
-- (for example, the event-time window) is applied only via: ./apply.sh 05_drop_mv.sql 02_kafka_log.sql 03_kafka_stat.sql
--
-- Order matters. The Kafka engine reads only while at least one MV is attached:
--  - *_errors first: while only the main MV is attached, valid messages are still written to logs/stats;
--  - then the main MV: no MV is attached -> reading stops, the consumer group offset stays where it was.
-- 02/03 then create the main MV first and *_errors second, so in the gap only broken messages can be missed.

DROP VIEW IF EXISTS mv_dwh_log_errors;
DROP VIEW IF EXISTS mv_dwh_log;

DROP VIEW IF EXISTS mv_dwh_stat_errors;
DROP VIEW IF EXISTS mv_dwh_stat;
