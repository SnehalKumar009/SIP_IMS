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

echo ">> Enabling core addons..."
sudo microk8s enable dns
sudo microk8s enable hostpath-storage
sudo microk8s enable rbac
sudo microk8s enable registry            # local image registry at localhost:32000
sudo microk8s enable metrics-server

echo ">> Enabling observability (kube-prometheus-stack: Prometheus + Grafana + Alertmanager)..."
sudo microk8s enable observability

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
