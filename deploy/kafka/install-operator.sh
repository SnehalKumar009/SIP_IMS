#!/usr/bin/env bash
# =============================================================================
# Installs (or upgrades) the Strimzi Cluster Operator into the `kafka` namespace.
#
# The operator owns the Kafka CRDs (Kafka, KafkaNodePool, KafkaTopic, ...) and turns
# the resources in this directory into running brokers. It is installed from the
# pinned Helm chart attached to the Strimzi GitHub release, so a re-run never drifts
# to a newer version. Idempotent.
#
# Usage:   ./deploy/kafka/install-operator.sh      (as your normal user)
# =============================================================================
set -euo pipefail

STRIMZI_VERSION="1.2.0"
CHART_URL="https://github.com/strimzi/strimzi-kafka-operator/releases/download/${STRIMZI_VERSION}/strimzi-kafka-operator-helm-3-chart-${STRIMZI_VERSION}.tgz"
NAMESPACE="kafka"

echo ">> Ensuring namespace ${NAMESPACE}..."
microk8s kubectl apply -f "$(dirname "$0")/../microk8s/namespaces.yaml"

echo ">> Installing Strimzi Cluster Operator ${STRIMZI_VERSION}..."
microk8s helm3 upgrade --install strimzi-cluster-operator "${CHART_URL}" \
  --namespace "${NAMESPACE}" \
  --wait --timeout 5m

echo ">> Operator ready:"
microk8s kubectl -n "${NAMESPACE}" get deploy strimzi-cluster-operator
