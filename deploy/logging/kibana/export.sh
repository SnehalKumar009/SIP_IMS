#!/usr/bin/env bash
# =============================================================================
# Writes the IMS Kibana objects back to ims-logs.ndjson after they were edited in the
# Kibana UI, so the change can be committed. Exports the dashboard and every saved
# search with everything they reference (data view, Lens charts).
#
# Objects you add in the UI are only included if their ID is listed in OBJECTS below
# or they are referenced by one that is.
#
# Requires jq on the host.
#
# Usage:   ./deploy/logging/kibana/export.sh
# =============================================================================
set -euo pipefail

DIR="$(cd "$(dirname "$0")" && pwd)"
NS="logging"
POD="$(microk8s kubectl -n "$NS" get pod -l kibana.k8s.elastic.co/name=ims-kibana \
        -o jsonpath='{.items[0].metadata.name}')"
PW="$(microk8s kubectl -n "$NS" get secret ims-es-es-elastic-user -o jsonpath='{.data.elastic}' | base64 -d)"

OBJECTS='[
  {"type": "dashboard", "id": "ims-dashboard-overview"},
  {"type": "search",    "id": "ims-search-all"},
  {"type": "search",    "id": "ims-search-p-cscf"},
  {"type": "search",    "id": "ims-search-i-cscf"},
  {"type": "search",    "id": "ims-search-s-cscf"},
  {"type": "search",    "id": "ims-search-hss"}
]'

echo ">> Exporting from ${POD}..."
printf '{"objects": %s, "includeReferencesDeep": true, "excludeExportDetails": true}' "$OBJECTS" |
  microk8s kubectl -n "$NS" exec -i -c kibana "$POD" -- \
    curl -sS --fail-with-body -k -u "elastic:${PW}" -H 'kbn-xsrf: true' \
      -H 'Content-Type: application/json' \
      -X POST "https://localhost:5601/api/saved_objects/_export" --data-binary @- \
  | jq -c 'del(.updated_at, .created_at, .updated_by, .created_by, .version)' \
  > "${DIR}/ims-logs.ndjson.tmp"
# The fields dropped above change on every save; without them a re-export only diffs
# when an object really changed. Import does not need them.
mv "${DIR}/ims-logs.ndjson.tmp" "${DIR}/ims-logs.ndjson"
echo ">> $(wc -l < "${DIR}/ims-logs.ndjson") objects written to ims-logs.ndjson"
