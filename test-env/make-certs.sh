#!/usr/bin/env bash
# Creates a throw-away node keystore (certs/node.jks) and the CA file to trust in Studio
# (certs/node.pem: TLS tab -> truststore path, type PEM). Login: cassandra / cassandra.
# Also creates the SSH test keys for the "jmx" profile (in ssh/, kept when present):
#   ssh/id_test           client key, no passphrase, authorised for user "studio" on every test sshd
#   ssh/host_ed25519_key  host key shared by every test sshd; ssh/known_hosts lists it for each address
set -euo pipefail
cd "$(dirname "$0")"
mkdir -p certs
rm -f certs/node.jks certs/node.pem
keytool -genkeypair -alias node -keyalg RSA -keysize 2048 -validity 365 -dname "CN=cassandra-test-env" \
  -ext "SAN=dns:localhost,ip:127.0.0.1" -keystore certs/node.jks -storetype JKS -storepass changeit -keypass changeit
keytool -exportcert -rfc -alias node -keystore certs/node.jks -storepass changeit -file certs/node.pem
echo "certs/node.pem is the truststore to use in Studio"

mkdir -p ssh
keygen() { # ssh-keygen from the host, or from the sshd image when the host has none
  if command -v ssh-keygen >/dev/null; then
    ssh-keygen "$@"
  else
    docker run --rm --user "$(id -u):$(id -g)" -v "$PWD/ssh:/ssh" -w / --entrypoint ssh-keygen \
      linuxserver/openssh-server:10.3_p1-r1-ls238 "$@"
  fi
}
[ -f ssh/id_test ] || keygen -q -t ed25519 -N "" -C studio-test -f ssh/id_test
[ -f ssh/host_ed25519_key ] || keygen -q -t ed25519 -N "" -C test-env-host -f ssh/host_ed25519_key
chmod 600 ssh/id_test ssh/host_ed25519_key
hostkey=$(cut -d' ' -f1,2 ssh/host_ed25519_key.pub)
{
  for p in 2200 2201 2202 2203; do echo "[127.0.0.1]:$p $hostkey"; done
  for h in east1 east2 west1; do echo "[$h]:2222 $hostkey"; done
} > ssh/known_hosts
echo "ssh/id_test logs in as studio@127.0.0.1 port 2201..2203 (nodes) or 2200 (bastion)"
