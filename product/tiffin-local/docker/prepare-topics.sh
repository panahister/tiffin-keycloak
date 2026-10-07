#!/bin/bash
set -euo pipefail

: "${KAFKA_BOOTSTRAP_SERVERS:?KAFKA_BOOTSTRAP_SERVERS is required}"

for topic in \
  tiffin.identity.user-events.v1 \
  tiffin.identity.admin-events.v1 \
  tiffin.identity.security-events.v1 \
  tiffin.identity.dead-letter.v1
do
  /opt/kafka/bin/kafka-topics.sh \
    --bootstrap-server "$KAFKA_BOOTSTRAP_SERVERS" \
    --create \
    --if-not-exists \
    --topic "$topic" \
    --partitions 3 \
    --replication-factor 1 \
    --config min.insync.replicas=1
done

for topic in \
  tiffin.identity.user-events.v1 \
  tiffin.identity.admin-events.v1 \
  tiffin.identity.security-events.v1 \
  tiffin.identity.dead-letter.v1
do
  /opt/kafka/bin/kafka-topics.sh \
    --bootstrap-server "$KAFKA_BOOTSTRAP_SERVERS" \
    --describe \
    --topic "$topic"
done
