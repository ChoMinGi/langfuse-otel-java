#!/usr/bin/env bash
set -euo pipefail

operations_root="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
cd "$operations_root"
operations_command=(./mvnw -B -ntp -f experiments/core-operations/pom.xml test
  -Dtest=LoadTest -Doperations.load=true -Doperations.profile=soak
  -Doperations.warmupSeconds=600 -Doperations.cycles=24 -Doperations.measureSeconds=300
  -Doperations.concurrency=32 -Doperations.rate=3000
  -Doperations.output=target/operations/load-soak.json)

# This assertion lasts only as long as Maven; the clock guard also rejects suspend/clock gaps.
if [[ "$(uname -s)" == Darwin ]]; then
  exec /usr/bin/caffeinate -i "${operations_command[@]}"
else
  exec "${operations_command[@]}"
fi
