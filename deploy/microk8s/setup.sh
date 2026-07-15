#!/usr/bin/env bash
# =============================================================================
# MicroK8s bootstrap for the IMS SIP Platform (run on the Ubuntu host).
#
# Brings up a single-node MicroK8s able to route high-volume L4 UDP/TCP SIP
# traffic plus an RTP NodePort band, and enables the built-in registry +
# observability addons.
#
# Usage:   ./setup.sh          (run as a normal user; do NOT use sudo)
# Requires: snap, sudo. Idempotent — safe to re-run.
#
# IMPORTANT: run this as your normal user, NOT with sudo. MicroK8s addon
# commands call `sudo -E` internally; wrapping the whole script in sudo drops
# the snap environment (you'll see "-E is ignored" warnings) and enables like
# `rbac` silently fail. Only `snap install` / `usermod` below use sudo.
# =============================================================================
set -euo pipefail

MICROK8S_CHANNEL="1.30/stable"

if [ "${EUID:-$(id -u)}" -eq 0 ]; then
  echo "!! Do NOT run this script as root/sudo. Run it as your normal user" >&2
  echo "   (a member of the 'microk8s' group). Re-run: ./setup.sh" >&2
  exit 1
fi

echo ">> Installing MicroK8s (${MICROK8S_CHANNEL})..."
if ! command -v microk8s >/dev/null 2>&1; then
  sudo snap install microk8s --classic --channel="${MICROK8S_CHANNEL}"
  sudo usermod -aG microk8s "$USER"
  echo "!! Added $USER to the 'microk8s' group. Log out/in (or run 'newgrp microk8s') then re-run."
  exit 0
fi

# Verify we can talk to microk8s without sudo (group membership must be active).
if ! microk8s status >/dev/null 2>&1; then
  echo "!! Cannot run 'microk8s' as this user yet. Activate the group first:" >&2
  echo "       newgrp microk8s   # (or log out and back in), then re-run ./setup.sh" >&2
  exit 1
fi

echo ">> Waiting for MicroK8s to be ready..."
microk8s status --wait-ready

# Enable an addon and verify it actually turned on via `microk8s status -a`.
enable_addon() {
  local addon="$1"
  if microk8s status -a "${addon}" 2>/dev/null | grep -q '^enabled'; then
    echo ">> Addon already enabled: ${addon}"
    return 0
  fi
  echo ">> Enabling addon: ${addon}"
  local attempt
  for attempt in 1 2 3; do
    microk8s enable "${addon}" || true
    microk8s status --wait-ready >/dev/null
    if microk8s status -a "${addon}" 2>/dev/null | grep -q '^enabled'; then
      echo "   ${addon} is enabled."
      return 0
    fi
    echo "   ${addon} not enabled yet (attempt ${attempt}); retrying..."
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
microk8s kubectl apply -f "$(dirname "$0")/namespaces.yaml"

echo ">> Cluster info:"
microk8s kubectl get nodes -o wide

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
