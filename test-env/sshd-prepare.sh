#!/usr/bin/env bash
# Entrypoint of the test sshd containers (linuxserver/openssh-server): pin the shared host key
# from ssh/, allow TCP forwarding (the image turns it off), then start the image's own /init.
set -euo pipefail
mkdir -p /config/ssh_host_keys
cp /keys/host_ed25519_key /config/ssh_host_keys/ssh_host_ed25519_key
cp /keys/host_ed25519_key.pub /config/ssh_host_keys/ssh_host_ed25519_key.pub
chmod 600 /config/ssh_host_keys/ssh_host_ed25519_key
sed -i 's/^AllowTcpForwarding no/AllowTcpForwarding yes/' /etc/ssh/sshd_config
exec /init
