#!/usr/bin/env bash
# Creates a throw-away node keystore (certs/node.jks) and the CA file to trust in Studio
# (certs/node.pem: TLS tab -> truststore path, type PEM). Login: cassandra / cassandra.
set -euo pipefail
cd "$(dirname "$0")"
mkdir -p certs
rm -f certs/node.jks certs/node.pem
keytool -genkeypair -alias node -keyalg RSA -keysize 2048 -validity 365 -dname "CN=cassandra-test-env" \
  -ext "SAN=dns:localhost,ip:127.0.0.1" -keystore certs/node.jks -storetype JKS -storepass changeit -keypass changeit
keytool -exportcert -rfc -alias node -keystore certs/node.jks -storepass changeit -file certs/node.pem
echo "certs/node.pem is the truststore to use in Studio"
