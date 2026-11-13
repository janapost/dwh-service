#!/usr/bin/env bash
# UPM-2238: runs ClickHouse SQL scripts with environment variables substituted.
#
# Usage:
#   ./apply.sh                      # 01_tables.sql, 02_kafka_log.sql, 03_kafka_stat.sql
#   ./apply.sh 04_ttl.sql           # only the listed scripts
#   DRY_RUN=1 ./apply.sh            # print the SQL after substitution, do not execute
#
# Required: CLICKHOUSE_HOST, CLICKHOUSE_PASSWORD (unless DRY_RUN), DWH_CH_KAFKA_BROKERS (for 02/03).
# Requires: bash, envsubst (gettext), clickhouse-client.
set -euo pipefail

cd "$(dirname "$0")"

export CLICKHOUSE_NATIVE_PORT="${CLICKHOUSE_NATIVE_PORT:-9000}"
export CLICKHOUSE_USER="${CLICKHOUSE_USER:-default}"
export CLICKHOUSE_DB="${CLICKHOUSE_DB:-default}"
export DWH_CH_LOG_TTL_DAYS="${DWH_CH_LOG_TTL_DAYS:-90}"
export DWH_CH_STAT_TTL_DAYS="${DWH_CH_STAT_TTL_DAYS:-365}"
export DWH_CH_ERRORS_TTL_DAYS="${DWH_CH_ERRORS_TTL_DAYS:-30}"
export DWH_CH_KAFKA_NUM_CONSUMERS="${DWH_CH_KAFKA_NUM_CONSUMERS:-2}"
export DWH_LOG_TOPIC="${DWH_LOG_TOPIC:-event_dwh_log}"
export DWH_STAT_TOPIC="${DWH_STAT_TOPIC:-event_dwh_stat}"
export DWH_CH_KAFKA_BROKERS="${DWH_CH_KAFKA_BROKERS:-}"
# Event time older than this (or more than 1 day ahead) is replaced with the insert time in the MVs.
export DWH_CH_MAX_EVENT_AGE_DAYS="${DWH_CH_MAX_EVENT_AGE_DAYS:-30}"

# Only these variables are substituted; any other '$' in SQL stays as is.
SUBST_VARS='${DWH_CH_LOG_TTL_DAYS} ${DWH_CH_STAT_TTL_DAYS} ${DWH_CH_ERRORS_TTL_DAYS} ${DWH_CH_KAFKA_NUM_CONSUMERS} ${DWH_LOG_TOPIC} ${DWH_STAT_TOPIC} ${DWH_CH_KAFKA_BROKERS} ${DWH_CH_MAX_EVENT_AGE_DAYS}'

for days in "$DWH_CH_LOG_TTL_DAYS" "$DWH_CH_STAT_TTL_DAYS" "$DWH_CH_ERRORS_TTL_DAYS" "$DWH_CH_KAFKA_NUM_CONSUMERS" "$DWH_CH_MAX_EVENT_AGE_DAYS"; do
  if ! [[ "$days" =~ ^[1-9][0-9]*$ ]]; then
    echo "TTL days, DWH_CH_KAFKA_NUM_CONSUMERS and DWH_CH_MAX_EVENT_AGE_DAYS must be positive integers, got: '$days'" >&2
    exit 1
  fi
done
# One insert block may touch at most 100 monthly partitions (max_partitions_per_insert_block):
# the allowed event-time window must stay well below 100 months.
if (( DWH_CH_MAX_EVENT_AGE_DAYS > 2700 )); then
  echo "DWH_CH_MAX_EVENT_AGE_DAYS must be <= 2700 (about 90 months), got: $DWH_CH_MAX_EVENT_AGE_DAYS" >&2
  exit 1
fi

if [[ $# -gt 0 ]]; then
  scripts=("$@")
else
  scripts=(01_tables.sql 02_kafka_log.sql 03_kafka_stat.sql)
fi

for script in "${scripts[@]}"; do
  if [[ ! -f "$script" ]]; then
    echo "No such script: $script" >&2
    exit 1
  fi
  if [[ "$script" == 02_* || "$script" == 03_* ]] && [[ -z "$DWH_CH_KAFKA_BROKERS" ]]; then
    echo "DWH_CH_KAFKA_BROKERS is required for $script" >&2
    exit 1
  fi
done

# 05_drop_mv.sql stops reading Kafka: it must be followed by 02 and 03 in the same run.
args=" ${scripts[*]} "
if [[ "$args" == *" 05_drop_mv.sql "* ]] && ! [[ "$args" =~ \ 05_drop_mv\.sql\ .*02_kafka_log\.sql\  && "$args" =~ \ 05_drop_mv\.sql\ .*03_kafka_stat\.sql\  ]]; then
  echo "05_drop_mv.sql must be followed by 02_kafka_log.sql and 03_kafka_stat.sql: ./apply.sh 05_drop_mv.sql 02_kafka_log.sql 03_kafka_stat.sql" >&2
  exit 1
fi

client_config=""
if [[ "${DRY_RUN:-0}" != "1" ]]; then
  : "${CLICKHOUSE_HOST:?CLICKHOUSE_HOST is required}"
  : "${CLICKHOUSE_PASSWORD:?CLICKHOUSE_PASSWORD is required}"
  # The password goes to a temporary client config (mode 600), not to the command line,
  # so it is not visible in the process list or shell history.
  client_config="$(mktemp)"
  trap 'rm -f "$client_config"' EXIT
  chmod 600 "$client_config"
  # XML-escape: '<', '&' and quotes in the password must not break the config.
  # sed, not ${s//...}: in bash 5.2 '&' in the replacement means the matched text (patsub_replacement).
  # printf is a builtin, so the value goes through a pipe, not through process arguments.
  xml_escape() {
    printf '%s' "$1" | sed -e 's/&/\&amp;/g' -e 's/</\&lt;/g' -e 's/>/\&gt;/g' -e 's/"/\&quot;/g' -e "s/'/\\&apos;/g"
  }
  {
    printf '<config>\n'
    printf '    <user>%s</user>\n' "$(xml_escape "$CLICKHOUSE_USER")"
    printf '    <password>%s</password>\n' "$(xml_escape "$CLICKHOUSE_PASSWORD")"
    printf '</config>\n'
  } > "$client_config"
fi

for script in "${scripts[@]}"; do
  echo ">>> $script"
  sql="$(envsubst "$SUBST_VARS" < "$script")"
  if [[ "${DRY_RUN:-0}" == "1" ]]; then
    echo "$sql"
    continue
  fi
  clickhouse-client \
    --config-file "$client_config" \
    --host "$CLICKHOUSE_HOST" \
    --port "$CLICKHOUSE_NATIVE_PORT" \
    --database "$CLICKHOUSE_DB" \
    --multiquery <<< "$sql"
done

echo "Done."
