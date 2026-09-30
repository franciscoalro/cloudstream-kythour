#!/usr/bin/env bash
set -Eeuo pipefail

ROOT_DIR="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)"
LOG_FILE="${1:-/tmp/kythour-apk-build.log}"
REDROID_CONTAINER="${REDROID_CONTAINER:-redroid11}"
REDROID_WAS_RUNNING=0

cleanup() {
    local exit_code=$?
    "$ROOT_DIR/gradlew" --stop >/dev/null 2>&1 || true
    if [[ "$REDROID_WAS_RUNNING" == "1" ]]; then
        docker start "$REDROID_CONTAINER" >/dev/null 2>&1 || true
    fi
    exit "$exit_code"
}
trap cleanup EXIT INT TERM

if command -v docker >/dev/null 2>&1 && \
   docker ps --format '{{.Names}}' 2>/dev/null | grep -Fxq "$REDROID_CONTAINER"; then
    REDROID_WAS_RUNNING=1
    echo "Stopping $REDROID_CONTAINER temporarily to free build memory..."
    docker stop "$REDROID_CONTAINER" >/dev/null
fi

# Reclaim stale Gradle processes and file cache before launching the only JVM.
"$ROOT_DIR/gradlew" --stop >/dev/null 2>&1 || true
if [[ -w /proc/sys/vm/drop_caches ]]; then
    sync
    echo 3 > /proc/sys/vm/drop_caches || true
fi

echo "Building :app:assemblePrereleaseDebug (log: $LOG_FILE)..."
cd "$ROOT_DIR"
./gradlew :app:assemblePrereleaseDebug --stacktrace --no-daemon --max-workers=1 2>&1 | tee "$LOG_FILE"
