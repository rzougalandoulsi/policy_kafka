#!/usr/bin/env bash
# Least-privilege ACLs. Runs inside the kafka container as the super user "admin".
# Nobody but the governance principal may write requests.manifest, so clients cannot bypass Governance.
set -euo pipefail
B=kafka:9092
C=/etc/kafka/admin.properties
cat > $C <<CFG
security.protocol=SASL_PLAINTEXT
sasl.mechanism=PLAIN
sasl.jaas.config=org.apache.kafka.common.security.plain.PlainLoginModule required username="admin" password="${KAFKA_ADMIN_PASSWORD}";
CFG
acl() { kafka-acls --bootstrap-server $B --command-config $C --add --allow-principal "User:$1" "${@:2}" >/dev/null; }

echo ">> creating topics"
for spec in "requests.manifest:3" "response.manifest:3" "teams.repo.manifest:3:compact" "teams.changes:3" "request.status:3" "teams:1:compact" "team-a.status:1"; do
  IFS=: read -r name parts policy <<<"$spec"
  extra=(); [ "${policy:-}" = "compact" ] && extra=(--config cleanup.policy=compact)
  kafka-topics --bootstrap-server $B --command-config $C --create --if-not-exists --topic "$name" --partitions "$parts" --replication-factor 1 "${extra[@]}" >/dev/null
done

# Note: the CLI `kafka-topics --describe` additionally needs DescribeConfigs; the services themselves only produce/consume.
echo ">> governance: write commands, read responses"
acl governance --operation Write --operation Describe --topic requests.manifest
acl governance --operation Read --operation Describe --topic response.manifest
acl governance --operation Read --group governance

echo ">> orchestrator: read commands, write responses, dispatch actions, read statuses"
# The real Orchestrator (Kafka Streams) additionally needs its internal topics/group/transactional ids
# (prefixed with its application id) plus teams.repo.manifest read/write; see docs/orchestrator-integration.md.
acl orchestrator --operation Read --operation Describe --topic requests.manifest
acl orchestrator --operation Write --operation Describe --topic response.manifest
acl orchestrator --operation Write --operation Describe --topic teams.changes
acl orchestrator --operation Read --operation Describe --topic request.status
acl orchestrator --operation Read --group orchestrator-simulator

echo ">> processor: read actions, write statuses"
acl processor --operation Read --operation Describe --topic teams.changes
acl processor --operation Write --operation Describe --topic request.status
acl processor --operation Read --group processor-simulator

echo ">> schema registry"
acl schemaregistry --operation All --topic _schemas
acl schemaregistry --operation All --group schema-registry
acl schemaregistry --operation Describe --cluster
echo ">> ACLs applied"
