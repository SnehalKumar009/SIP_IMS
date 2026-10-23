#!/usr/bin/env bash
# =============================================================================
# Loads the IMS log index configuration into Elasticsearch (ims-es):
#
#   ilm-logs-ims.json             lifecycle policy logs-ims: daily rollover, 7-day delete
#   index-template-logs-ims.json  template for data stream logs-ims-default: ECS
#                                 mappings, 0 replicas (single node), policy logs-ims
#
# The requests run inside the Elasticsearch pod against localhost, using the
# ECK-generated `elastic` password, so nothing is exposed outside the cluster.
# PUT replaces the stored object, so re-running after an edit applies the change
# (existing backing indices keep their settings until the next rollover). Idempotent.
#
# Usage:   ./deploy/logging/elastic/index-setup/apply.sh
# =============================================================================
set -euo pipefail

DIR="$(cd "$(dirname "$0")" && pwd)"
NS="logging"
POD="ims-es-es-default-0"
PW="$(microk8s kubectl -n "$NS" get secret ims-es-es-elastic-user -o jsonpath='{.data.elastic}' | base64 -d)"

es_put() {
  local path="$1" file="$2"
  echo ">> PUT ${path}"
  microk8s kubectl -n "$NS" exec -i "$POD" -c elasticsearch -- \
    curl -sS --fail-with-body -k -u "elastic:${PW}" \
      -X PUT "https://localhost:9200${path}" \
      -H 'Content-Type: application/json' --data-binary @- < "${DIR}/${file}"
  echo
}

es_put "/_ilm/policy/logs-ims"           ilm-logs-ims.json
es_put "/_index_template/logs-ims"       index-template-logs-ims.json
