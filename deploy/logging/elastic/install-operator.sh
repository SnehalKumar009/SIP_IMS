#!/usr/bin/env bash
# =============================================================================
# Installs (or upgrades) the ECK operator (Elastic Cloud on Kubernetes) into the
# `elastic-system` namespace.
#
# ECK owns the Elastic CRDs (Elasticsearch, Kibana, Logstash, ...) and turns the
# resources in deploy/logging into running clusters: it generates TLS certificates,
# the `elastic` superuser password, and performs rolling upgrades. Installed from the
# pinned Helm chart, so a re-run never drifts to a newer version. Idempotent.
#
# Usage:   ./deploy/logging/elastic/install-operator.sh      (as your normal user)
# =============================================================================
set -euo pipefail

ECK_VERSION="3.5.0"
NAMESPACE="elastic-system"

echo ">> Ensuring namespaces..."
microk8s kubectl apply -f "$(dirname "$0")/../../microk8s/namespaces.yaml"

echo ">> Installing ECK operator ${ECK_VERSION}..."
microk8s helm3 upgrade --install elastic-operator eck-operator \
  --repo https://helm.elastic.co \
  --version "${ECK_VERSION}" \
  --namespace "${NAMESPACE}" \
  --wait --timeout 5m

echo ">> Operator ready:"
microk8s kubectl -n "${NAMESPACE}" get statefulset elastic-operator
