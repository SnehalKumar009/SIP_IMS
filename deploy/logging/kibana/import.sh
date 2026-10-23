#!/usr/bin/env bash
# =============================================================================
# Loads the IMS Kibana objects (data view, saved searches, Lens charts, dashboard)
# from ims-logs.ndjson into Kibana (ims-kibana). Existing objects with the same IDs
# are overwritten, so this is also how a change made in the repo is applied.
#
# The request runs inside the Kibana pod against localhost, using the ECK-generated
# `elastic` password. Idempotent.
#
# Usage:   ./deploy/logging/kibana/import.sh
# =============================================================================
set -euo pipefail

DIR="$(cd "$(dirname "$0")" && pwd)"
NS="logging"
POD="$(microk8s kubectl -n "$NS" get pod -l kibana.k8s.elastic.co/name=ims-kibana \
        -o jsonpath='{.items[0].metadata.name}')"
PW="$(microk8s kubectl -n "$NS" get secret ims-es-es-elastic-user -o jsonpath='{.data.elastic}' | base64 -d)"

echo ">> Importing ims-logs.ndjson into ${POD}..."
microk8s kubectl -n "$NS" exec -i -c kibana "$POD" -- \
  curl -sS --fail-with-body -k -u "elastic:${PW}" -H 'kbn-xsrf: true' \
    -X POST "https://localhost:5601/api/saved_objects/_import?overwrite=true" \
    -F "file=@-;filename=ims-logs.ndjson" < "${DIR}/ims-logs.ndjson"
echo
