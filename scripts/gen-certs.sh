#!/usr/bin/env sh
# POSIX shell — runs everywhere bash/sh runs.
# All openssl + keytool work happens inside short-lived Docker containers so
# the host never needs openssl or a JDK installed.
#
# Outputs (under ./e2e/certs/):
#   ca.crt              self-signed root CA (Playwright + browser will trust this)
#   ca.key              CA private key
#   server.crt          frontend nginx server cert (CN=localhost, SAN=localhost / api / frontend)
#   server.key          frontend nginx server key
#   api-server.crt      api Spring Boot HTTPS server cert
#   api-server.key      api Spring Boot HTTPS server key
#   client.crt          mTLS client cert nginx presents to api
#   client.key          mTLS client cert key
#   truststore.p12      PKCS12 truststore containing ca.crt — mounted into api
#   keystore.p12        PKCS12 keystore for api containing api-server.{crt,key}
#
# Override with CERT_PATH=/path/to/existing/certs to skip generation entirely.

set -eu

OUT="${CERT_PATH:-$(pwd)/e2e/certs}"
PASSWORD="${KEYSTORE_PASSWORD:-threewizards}"

if [ -n "${CERT_PATH:-}" ] && [ -d "$CERT_PATH" ] && [ -f "$CERT_PATH/ca.crt" ]; then
  echo "[gen-certs] using existing CERT_PATH=$CERT_PATH (skipping generation)"
  exit 0
fi

mkdir -p "$OUT"

if [ -f "$OUT/ca.crt" ] && [ -f "$OUT/truststore.p12" ]; then
  echo "[gen-certs] $OUT already populated — skipping (delete to regenerate)"
  exit 0
fi

echo "[gen-certs] generating self-signed CA + server certs in $OUT"

# OpenSSL inside alpine: cross-platform, no host deps.
docker run --rm -v "$OUT:/out" alpine:3.20 sh -c '
  apk add --no-cache openssl >/dev/null
  cd /out

  cat > openssl.cnf <<EOF
[req]
distinguished_name = req
req_extensions     = v3_req
prompt             = no

[req_distinguished_name]
CN = three-wizards-ca

[v3_req]
keyUsage         = digitalSignature, keyEncipherment, dataEncipherment
extendedKeyUsage = serverAuth, clientAuth
subjectAltName   = @alt

[alt]
DNS.1 = localhost
DNS.2 = api
DNS.3 = frontend
DNS.4 = host.docker.internal
IP.1  = 127.0.0.1
IP.2  = ::1
EOF

  # Root CA
  openssl genrsa -out ca.key 2048 2>/dev/null
  openssl req -x509 -new -nodes -key ca.key -sha256 -days 3650 \
    -subj "/CN=three-wizards-ca" -out ca.crt 2>/dev/null

  # Server cert (frontend nginx + browser-facing)
  openssl genrsa -out server.key 2048 2>/dev/null
  openssl req -new -key server.key -subj "/CN=localhost" -out server.csr -config openssl.cnf 2>/dev/null
  openssl x509 -req -in server.csr -CA ca.crt -CAkey ca.key -CAcreateserial \
    -out server.crt -days 825 -sha256 -extensions v3_req -extfile openssl.cnf 2>/dev/null

  # API server cert (HTTPS endpoint inside the compose network)
  openssl genrsa -out api-server.key 2048 2>/dev/null
  openssl req -new -key api-server.key -subj "/CN=api" -out api-server.csr -config openssl.cnf 2>/dev/null
  openssl x509 -req -in api-server.csr -CA ca.crt -CAkey ca.key -CAcreateserial \
    -out api-server.crt -days 825 -sha256 -extensions v3_req -extfile openssl.cnf 2>/dev/null

  # Client cert (mTLS — nginx presents this to api)
  openssl genrsa -out client.key 2048 2>/dev/null
  openssl req -new -key client.key -subj "/CN=frontend-client" -out client.csr -config openssl.cnf 2>/dev/null
  openssl x509 -req -in client.csr -CA ca.crt -CAkey ca.key -CAcreateserial \
    -out client.crt -days 825 -sha256 -extensions v3_req -extfile openssl.cnf 2>/dev/null

  rm -f *.csr *.srl
  chmod 644 *.crt *.key
  # Match host user/group so make/git work without sudo afterwards.
  chown $(stat -c %u .):$(stat -c %g .) *.crt *.key 2>/dev/null || true
'

# PKCS12 keystore + truststore for the Java side via a JDK image.
docker run --rm -v "$OUT:/out" eclipse-temurin:21-jre sh -c "
  cd /out
  # Keystore (server cert + private key) for the api's HTTPS listener
  openssl pkcs12 -export \
    -in api-server.crt -inkey api-server.key -name api-server \
    -out keystore.p12 -password pass:$PASSWORD \
    -CAfile ca.crt -caname three-wizards-ca 2>/dev/null

  # Truststore (CA only) — what the api uses to validate inbound mTLS clients
  # AND to verify peer certs on outbound calls (e.g. casdoor, future cassandra TLS).
  keytool -import -trustcacerts -noprompt \
    -alias three-wizards-ca \
    -file ca.crt \
    -keystore truststore.p12 \
    -storetype PKCS12 \
    -storepass $PASSWORD >/dev/null
  chmod 644 *.p12
  chown \$(stat -c %u .):\$(stat -c %g .) *.p12 2>/dev/null || true
"

echo "[gen-certs] done — $OUT contains $(ls "$OUT" | wc -l | tr -d ' ') files"
