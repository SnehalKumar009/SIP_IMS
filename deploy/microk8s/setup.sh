#!/usr/bin/env bash
# =============================================================================
# MicroK8s bootstrap for the IMS SIP Platform (run on the Ubuntu host).
#
# Brings up a single-node MicroK8s able to route high-volume L4 UDP/TCP SIP
# traffic plus an RTP NodePort band, and enables the built-in registry +
# observability addons.
#
# Usage:   ./setup.sh
# Requires: snap, sudo. Idempotent — safe to re-run.
# =============================================================================
set -euo pipefail

MICROK8S_CHANNEL="1.30/stable"

echo ">> Installing MicroK8s (${MICROK8S_CHANNEL})..."
if ! command -v microk8s >/dev/null 2>&1; then
  sudo snap install microk8s --classic --channel="${MICROK8S_CHANNEL}"
  sudo usermod -aG microk8s "$USER"
  echo "!! Added $USER to the 'microk8s' group. Log out/in (or run 'newgrp microk8s') then re-run."
fi

echo ">> Waiting for MicroK8s to be ready..."
sudo microk8s status --wait-ready

# Enable an addon, then wait for the apiserver to come back.
# Some addons (rbac, metrics-server) reconfigure and restart the apiserver,
# which makes `enable` exit non-zero even though it succeeded. Retry + wait.
enable_addon() {
  local addon="$1"
  echo ">> Enabling addon: ${addon}"
  local attempt
  for attempt in 1 2 3; do
    if sudo microk8s enable "${addon}"; then
      sudo microk8s status --wait-ready >/dev/null
      return 0
    fi
    echo "   ${addon} enable returned non-zero (attempt ${attempt}); waiting for apiserver..."
    sleep 5
  done
  echo "!! Failed to enable addon '${addon}' after 3 attempts." >&2
  return 1
}

echo ">> Enabling core addons..."
enable_addon dns
enable_addon hostpath-storage
enable_addon rbac
enable_addon registry            # local image registry at localhost:32000
enable_addon metrics-server

echo ">> Enabling observability (kube-prometheus-stack: Prometheus + Grafana + Alertmanager)..."
enable_addon observability

echo ">> Creating namespaces..."
sudo microk8s kubectl apply -f "$(dirname "$0")/namespaces.yaml"

echo ">> Cluster info:"
sudo microk8s kubectl get nodes -o wide

cat <<'EONOTE'

Next steps
----------
1. Alias kubectl:   alias kubectl='microk8s kubectl'   (or: microk8s config > ~/.kube/config)
2. Build & push images to the local registry:
       docker build -t localhost:32000/ims/p-cscf:dev services/p-cscf
       docker push localhost:32000/ims/p-cscf:dev
3. Deploy observability extras (Homer):  kubectl apply -k deploy/monitoring
4. SIP + RTP are exposed via NodePort/hostPort on this node (no L2/L4 load balancer).
   SIP signaling and the RTP media band map straight onto worker-node ports.

EONOTE
echo ">> MicroK8s bootstrap complete."
