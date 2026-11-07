#!/usr/bin/env bash
# Run a command in the Tuna Mail build environment (tools/build-env/Containerfile).
#
#   tools/build-env/run.sh --build-image          build or refresh the image
#   tools/build-env/run.sh ./gradlew :app-tunamail:assembleDebug
#   tools/build-env/run.sh                        interactive shell
#
# The current directory is mounted at /work and the command runs there, so the
# same image also builds the native backend libraries from their own checkouts.
# Caches persist under $TUNAMAIL_BUILD_CACHE (default ~/android-build/cache).
#
# The build host is shared and short on memory (its VMs and services come first):
# - jobs are serialized: every run holds $TUNAMAIL_BUILD_LOCK
#   (default ~/android-build/build.lock) with flock(1), and other Gradle,
#   cargo-ndk or gomobile jobs on the host must take the same lock;
# - once it holds the lock, it refuses to start when MemAvailable is under
#   $TUNAMAIL_MIN_FREE_MB (default 3072);
# - the container is limited to $TUNAMAIL_MEMORY (default 4500m), Gradle is
#   capped by tools/build-env/gradle.properties, cargo runs 6 jobs, and the
#   Gradle daemon is stopped afterwards.
set -euo pipefail

here=$(cd "$(dirname "$0")" && pwd)
image=${TUNAMAIL_BUILD_IMAGE:-localhost/tunamail-build-env:latest}
cache=${TUNAMAIL_BUILD_CACHE:-$HOME/android-build/cache}
lock=${TUNAMAIL_BUILD_LOCK:-$HOME/android-build/build.lock}
min_free_mb=${TUNAMAIL_MIN_FREE_MB:-3072}
memory=${TUNAMAIL_MEMORY:-4500m}

if [ -z "${TUNAMAIL_BUILD_LOCK_HELD:-}" ]; then
    mkdir -p "$(dirname "$lock")"
    exec env TUNAMAIL_BUILD_LOCK_HELD=1 flock "$lock" "$0" "$@"
fi

if [ "${1:-}" = "--build-image" ]; then
    exec podman build --pull=newer -t "$image" -f "$here/Containerfile" "$here"
fi

available_mb=$(awk '/^MemAvailable:/ { print int($2 / 1024) }' /proc/meminfo)
if [ "$available_mb" -lt "$min_free_mb" ]; then
    echo "run.sh: only ${available_mb} MiB available, need ${min_free_mb} MiB; not starting." >&2
    echo "run.sh: wait until the host has memory to spare, and try again." >&2
    exit 75
fi

mkdir -p "$cache"/{gradle,gomod,gocache,cargo-registry,cargo-git,android}
cp "$here/gradle.properties" "$cache/gradle/gradle.properties"

tty=()
[ -t 0 ] && [ -t 1 ] && tty=(-it)
[ "$#" -eq 0 ] && set -- bash

# Stop the Gradle daemon whatever the command's outcome, and keep its status.
script='"$@"; status=$?; if [ -x ./gradlew ]; then ./gradlew --stop >/dev/null 2>&1 || true; fi; exit $status'

exec podman run --rm "${tty[@]}" \
    --memory "$memory" --memory-swap "$memory" \
    --security-opt label=disable \
    -v "$PWD:/work" -w /work \
    -v "$cache/gradle:/cache/gradle" \
    -v "$cache/gomod:/cache/gomod" \
    -v "$cache/gocache:/cache/gocache" \
    -v "$cache/android:/root/.android" \
    -v "$cache/cargo-registry:/opt/cargo/registry" \
    -v "$cache/cargo-git:/opt/cargo/git" \
    -e CARGO_BUILD_JOBS=6 \
    -e TUNAMAIL_GOOGLE_CLIENT_ID -e TUNAMAIL_MICROSOFT_CLIENT_ID -e TUNAMAIL_MICROSOFT_SIGNATURE_HASH \
    "$image" bash -c "$script" run.sh "$@"
