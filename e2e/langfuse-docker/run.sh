#!/usr/bin/env bash

set -euo pipefail
umask 077

script_dir="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)"
readonly script_dir
repository_root="$(cd -- "${script_dir}/../.." && pwd)"
readonly repository_root
readonly compose_file="${script_dir}/compose.yaml"
readonly expected_langfuse_revision="3a0e61cece00a8328a6e5684de4db55e100cb58a"
readonly expected_langfuse_version="4.0.0-rc.2"

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
keytool -importcert -noprompt -trustcacerts \
    -alias langfuse-otel-java-e2e \
    -file "${ca_dir}/ca.crt" \
    -keystore "${truststore}" \
    -storepass changeit >/dev/null

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
export LANGFUSE_E2E_COMMIT
LANGFUSE_E2E_COMMIT="$(git rev-parse HEAD)"

echo "Installing the current reactor artifacts..."
./mvnw -B -ntp \
    -DskipTests \
    -Djacoco.skip=true \
    -Dmaven.javadoc.skip=true \
    clean install

verify_framework_trace() {
    local framework="$1"
    local trace_name="$2"
    local child_name="$3"
    local model="$4"
    local expected_input="$5"
    local expected_output="$6"
    local input_tokens="$7"
    local output_tokens="$8"
    local total_tokens="$9"
    local from_time="${10}"
    local to_time="${11}"
    local root_json="${work_dir}/${framework}-root.json"
    local trace_json="${work_dir}/${framework}-trace.json"
    local trace_id=""

    for ((attempt = 1; attempt <= 30; attempt++)); do
        if curl --config "${curl_config}" --fail --silent --show-error \
            --get "${LANGFUSE_HOST}/api/public/v2/observations" \
            --data-urlencode "name=${trace_name}" \
            --data-urlencode "fields=core,basic,io,metadata,model,usage,trace_context" \
            --data-urlencode "fromStartTime=${from_time}" \
            --data-urlencode "toStartTime=${to_time}" \
            --data-urlencode "limit=10" \
            --output "${root_json}"; then
            trace_id="$(jq -r \
                'if (.data | length) == 1 then .data[0].traceId else empty end' \
                "${root_json}")"
        fi
        [[ -n "${trace_id}" ]] && break
        sleep 2
    done
    if [[ -z "${trace_id}" ]]; then
        echo "Could not read back the ${framework} root observation." >&2
        return 1
    fi

    verify_rows() {
        jq -e \
            --arg root "${trace_name}" \
            --arg child "${child_name}" \
            --arg model "${model}" \
            --arg expected_input "${expected_input}" \
            --arg expected_output "${expected_output}" \
            --arg framework "${framework}" \
            --arg marker "${LANGFUSE_E2E_MARKER}" \
            --arg commit "${LANGFUSE_E2E_COMMIT}" \
            --argjson input_tokens "${input_tokens}" \
            --argjson output_tokens "${output_tokens}" \
            --argjson total_tokens "${total_tokens}" '
                def row($name): .data[] | select(.name == $name);
                (row($root)) as $root_row |
                (row($child)) as $child_row |
                ((.data | length) == 2) and
                ((.meta.cursor // null) == null) and
                (([.data[].name] | sort) == ([$root, $child] | sort)) and
                ($root_row.type == "SPAN") and
                ($root_row.parentObservationId == null) and
                ($root_row.input == ($framework + "-root-input-" + $marker)) and
                ($root_row.output == ($framework + "-root-output-" + $marker)) and
                ($child_row.type == "GENERATION") and
                ($child_row.parentObservationId == $root_row.id) and
                (($child_row.model // $child_row.providedModelName) == $model) and
                (($child_row.input | tostring) | contains($expected_input)) and
                (($child_row.output | tostring) | contains($expected_output)) and
                ($child_row.metadata["attributes.gen_ai.system"] == $framework) and
                ([.data[].metadata.framework] | all(. == $framework)) and
                ([.data[].version] | all(. == $commit)) and
                ([.data[].release] | all(. == "docker-e2e")) and
                ([.data[].environment] | all(. == "docker-e2e")) and
                ([.data[].traceName] | all(. == $root)) and
                (
                    ($input_tokens < 0) or
                    (($child_row.usageDetails.input // $child_row.inputUsage) == $input_tokens)
                ) and
                (
                    ($output_tokens < 0) or
                    (($child_row.usageDetails.output // $child_row.outputUsage) == $output_tokens)
                ) and
                (
                    ($total_tokens < 0) or
                    (($child_row.usageDetails.total // $child_row.totalUsage) == $total_tokens)
                )
            ' "${trace_json}" >/dev/null
    }

    for ((attempt = 1; attempt <= 30; attempt++)); do
        if curl --config "${curl_config}" --fail --silent --show-error \
            --get "${LANGFUSE_HOST}/api/public/v2/observations" \
            --data-urlencode "traceId=${trace_id}" \
            --data-urlencode "fields=core,basic,io,metadata,model,usage,trace_context" \
            --data-urlencode "fromStartTime=${from_time}" \
            --data-urlencode "toStartTime=${to_time}" \
            --data-urlencode "limit=10" \
            --output "${trace_json}" \
            && verify_rows; then
            echo "${framework} Docker E2E passed (trace ${trace_id})."
            return 0
        fi
        sleep 2
    done

    echo "${framework} observations did not satisfy the E2E contract:" >&2
    jq '{data, meta}' "${trace_json}" >&2
    return 1
}

run_framework_test() {
    local framework="$1"
    local pom="$2"
    local test_class="$3"
    local trace_prefix="$4"
    local child_name="$5"
    local model="$6"
    local prompt_prefix="$7"
    local response_prefix="$8"
    local input_tokens="$9"
    local output_tokens="${10}"
    local total_tokens="${11}"
    local marker="${run_id}-${framework}"
    local expected_output
    local from_time
    local to_time

    export LANGFUSE_E2E_MARKER="${marker}"
    if [[ "${framework}" == "spring-ai" ]]; then
        expected_output="${response_prefix}"
    else
        expected_output="${response_prefix}-${marker}"
    fi
    from_time="$(date -u +%Y-%m-%dT%H:%M:%S.000Z)"

    ./mvnw -B -ntp \
        -f "${pom}" \
        -Dtest="${test_class}" \
        -DfailIfNoTests=true \
        -Djavax.net.ssl.trustStore="${truststore}" \
        -Djavax.net.ssl.trustStorePassword=changeit \
        clean test

    to_time="$(date -u +%Y-%m-%dT%H:%M:%S.999Z)"
    verify_framework_trace \
        "${framework}" \
        "${trace_prefix}-${marker}" \
        "${child_name}" \
        "${model}" \
        "${prompt_prefix}-${marker}" \
        "${expected_output}" \
        "${input_tokens}" \
        "${output_tokens}" \
        "${total_tokens}" \
        "${from_time}" \
        "${to_time}"
}

run_framework_test \
    spring-ai \
    consumer-tests/spring-boot-consumer/pom.xml \
    SpringBootConsumerLangfuseDockerIT \
    spring-ai-docker-e2e \
    stub.chat \
    smoke-model \
    spring-ai-input \
    "smoke response" \
    -1 -1 -1

run_framework_test \
    langchain4j \
    consumer-tests/langchain4j-spring-boot-consumer/pom.xml \
    LangChain4jConsumerLangfuseDockerIT \
    langchain4j-docker-e2e \
    stublangchain4j.chat \
    langchain4j-smoke-model \
    langchain4j-input \
    "langchain4j response: langchain4j-input" \
    4 5 9

echo "Langfuse Docker E2E passed for Spring AI and LangChain4j."
