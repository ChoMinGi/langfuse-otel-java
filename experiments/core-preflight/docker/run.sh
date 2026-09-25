#!/usr/bin/env bash

set -euo pipefail
umask 077

script_dir="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)"
readonly script_dir
repository_root="$(cd -- "${script_dir}/../../.." && pwd)"
readonly repository_root
readonly compose_file="${script_dir}/compose.yaml"
readonly expected_langfuse_revision="d84020653a619a374bd36e4563d52a5db5590129"
readonly expected_langfuse_version="4.41.0"

for tool in docker curl jq openssl keytool java awk; do
    if ! command -v "${tool}" >/dev/null 2>&1; then
        echo "Required command not found: ${tool}" >&2
        exit 1
    fi
done

docker compose version >/dev/null
docker info >/dev/null

work_dir="$(mktemp -d "${TMPDIR:-/tmp}/langfuse-otel-java-e2e.XXXXXX")"
readonly work_dir
readonly ca_dir="${work_dir}/ca"
readonly cert_dir="${work_dir}/tls"
readonly truststore="${work_dir}/cacerts"
readonly curl_config="${work_dir}/curl.conf"
compose_project="langfuse-otel-e2e-$$-$(openssl rand -hex 4)"
readonly compose_project
run_id="$(date -u +%Y%m%dT%H%M%SZ)-$$"
readonly run_id
compose_started=0

random_hex() {
    openssl rand -hex "$1"
}

LANGFUSE_PUBLIC_KEY="pk-lf-$(random_hex 16)"
LANGFUSE_SECRET_KEY="sk-lf-$(random_hex 24)"
LANGFUSE_E2E_POSTGRES_PASSWORD="$(random_hex 16)"
LANGFUSE_E2E_CLICKHOUSE_PASSWORD="$(random_hex 16)"
LANGFUSE_E2E_REDIS_PASSWORD="$(random_hex 16)"
LANGFUSE_E2E_MINIO_PASSWORD="$(random_hex 16)"
LANGFUSE_E2E_SALT="$(random_hex 16)"
LANGFUSE_E2E_ENCRYPTION_KEY="$(random_hex 32)"
LANGFUSE_E2E_NEXTAUTH_SECRET="$(random_hex 32)"
LANGFUSE_E2E_INIT_PASSWORD="$(random_hex 16)"
LANGFUSE_E2E_CERT_DIR="${cert_dir}"
export LANGFUSE_PUBLIC_KEY
export LANGFUSE_SECRET_KEY
export LANGFUSE_E2E_POSTGRES_PASSWORD
export LANGFUSE_E2E_CLICKHOUSE_PASSWORD
export LANGFUSE_E2E_REDIS_PASSWORD
export LANGFUSE_E2E_MINIO_PASSWORD
export LANGFUSE_E2E_SALT
export LANGFUSE_E2E_ENCRYPTION_KEY
export LANGFUSE_E2E_NEXTAUTH_SECRET
export LANGFUSE_E2E_INIT_PASSWORD
export LANGFUSE_E2E_CERT_DIR

compose=(docker compose --project-name "${compose_project}" --file "${compose_file}")

redact_logs() {
    sed \
        -e "s/${LANGFUSE_SECRET_KEY}/<redacted>/g" \
        -e "s/${LANGFUSE_E2E_POSTGRES_PASSWORD}/<redacted>/g" \
        -e "s/${LANGFUSE_E2E_CLICKHOUSE_PASSWORD}/<redacted>/g" \
        -e "s/${LANGFUSE_E2E_REDIS_PASSWORD}/<redacted>/g" \
        -e "s/${LANGFUSE_E2E_MINIO_PASSWORD}/<redacted>/g" \
        -e "s/${LANGFUSE_E2E_SALT}/<redacted>/g" \
        -e "s/${LANGFUSE_E2E_ENCRYPTION_KEY}/<redacted>/g" \
        -e "s/${LANGFUSE_E2E_NEXTAUTH_SECRET}/<redacted>/g" \
        -e "s/${LANGFUSE_E2E_INIT_PASSWORD}/<redacted>/g"
}

cleanup() {
    local exit_code=$?
    set +e

    if ((compose_started)); then
        if ((exit_code != 0)); then
            echo "Docker E2E failed; container status and recent logs follow." >&2
            "${compose[@]}" ps >&2
            "${compose[@]}" logs --no-color --tail 60 \
                langfuse-web langfuse-worker tls-proxy 2>&1 | redact_logs >&2
        fi
        if ! "${compose[@]}" down --volumes --remove-orphans >/dev/null 2>&1; then
            echo "Failed to remove the Docker E2E project ${compose_project}." >&2
            if ((exit_code == 0)); then
                exit_code=1
            fi
        fi
    fi

    case "${work_dir}" in
        "${TMPDIR:-/tmp}"/langfuse-otel-java-e2e.*)
            rm -rf -- "${work_dir}"
            ;;
        *)
            echo "Refusing to remove unexpected temporary directory: ${work_dir}" >&2
            ;;
    esac

    return "${exit_code}"
}

trap cleanup EXIT
trap 'exit 130' INT
trap 'exit 143' TERM

mkdir -p "${ca_dir}" "${cert_dir}"
openssl req -x509 -newkey rsa:2048 -nodes -sha256 -days 1 \
    -subj "/CN=langfuse-otel-java-e2e-ca" \
    -keyout "${ca_dir}/ca.key" \
    -out "${ca_dir}/ca.crt" >/dev/null 2>&1
openssl req -newkey rsa:2048 -nodes -sha256 \
    -subj "/CN=127.0.0.1" \
    -keyout "${cert_dir}/server.key" \
    -out "${ca_dir}/server.csr" >/dev/null 2>&1
openssl x509 -req -sha256 -days 1 \
    -in "${ca_dir}/server.csr" \
    -CA "${ca_dir}/ca.crt" \
    -CAkey "${ca_dir}/ca.key" \
    -CAcreateserial \
    -extfile "${script_dir}/server.ext" \
    -out "${cert_dir}/server.crt" >/dev/null 2>&1
chmod 600 "${ca_dir}/ca.key" "${cert_dir}/server.key"

java_home="$(java -XshowSettings:properties -version 2>&1 \
    | awk -F'= ' '/^[[:space:]]*java.home =/ { print $2; exit }')"
if [[ -z "${java_home}" || ! -f "${java_home}/lib/security/cacerts" ]]; then
    echo "Could not locate the active JDK truststore." >&2
    exit 1
fi
cp "${java_home}/lib/security/cacerts" "${truststore}"
# Some JDK distributions ship a read-only truststore; only our disposable copy is modified.
chmod 600 "${truststore}"
keytool -importcert -noprompt -trustcacerts \
    -alias langfuse-otel-java-e2e \
    -file "${ca_dir}/ca.crt" \
    -keystore "${truststore}" \
    -storepass changeit

printf 'user = "%s:%s"\ncacert = "%s"\n' \
    "${LANGFUSE_PUBLIC_KEY}" \
    "${LANGFUSE_SECRET_KEY}" \
    "${ca_dir}/ca.crt" > "${curl_config}"
chmod 600 "${curl_config}"

"${compose[@]}" config --quiet
compose_started=1
"${compose[@]}" up --detach

assert_langfuse_image() {
    local service="$1"
    local container_id
    local image_id
    local revision
    local version

    container_id="$("${compose[@]}" ps --quiet "${service}")"
    if [[ -z "${container_id}" ]]; then
        echo "Container did not start: ${service}" >&2
        return 1
    fi

    image_id="$(docker inspect --format '{{.Image}}' "${container_id}")"
    revision="$(docker image inspect --format \
        '{{ index .Config.Labels "org.opencontainers.image.revision" }}' "${image_id}")"
    version="$(docker image inspect --format \
        '{{ index .Config.Labels "org.opencontainers.image.version" }}' "${image_id}")"

    if [[ "${revision}" != "${expected_langfuse_revision}" \
        || "${version}" != "${expected_langfuse_version}" ]]; then
        echo "Unexpected ${service} image: revision=${revision}, version=${version}" >&2
        return 1
    fi
}

assert_langfuse_image langfuse-web
assert_langfuse_image langfuse-worker

proxy_address="$("${compose[@]}" port tls-proxy 443)"
if [[ "${proxy_address}" != 127.0.0.1:* ]]; then
    echo "Unexpected TLS proxy address: ${proxy_address}" >&2
    exit 1
fi
export LANGFUSE_HOST="https://${proxy_address}"

echo "Waiting for the disposable Langfuse project at ${LANGFUSE_HOST}..."
api_ready=0
for ((attempt = 1; attempt <= 90; attempt++)); do
    if curl --config "${curl_config}" --fail --silent --show-error \
        --get "${LANGFUSE_HOST}/api/public/v2/observations" \
        --data-urlencode "fields=core" \
        --data-urlencode "limit=1" \
        --output "${work_dir}/api-ready.json"; then
        api_ready=1
        break
    fi
    sleep 2
done
if ((api_ready == 0)); then
    echo "Langfuse Observations API did not become ready within 180 seconds." >&2
    exit 1
fi

cd "${repository_root}"
export PREFLIGHT_LIVE=true
PREFLIGHT_COMMIT="$(git rev-parse HEAD)"
PREFLIGHT_RELEASE="$(./mvnw -q -DforceStdout help:evaluate -Dexpression=project.version)"
export PREFLIGHT_COMMIT PREFLIGHT_RELEASE
./mvnw -B -ntp -f experiments/core-preflight/pom.xml \
    -Dtest=LiveV4Test \
    "-DargLine=-Djavax.net.ssl.trustStore=${truststore} -Djavax.net.ssl.trustStorePassword=changeit" test
