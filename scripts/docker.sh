#!/bin/sh
set -eu
root=${M3_PROJECT_ROOT:-$(CDPATH= cd -- "$(dirname -- "$0")/.." && pwd)}
action=${1:-build}
if [ "$#" -gt 0 ]; then shift; fi
case "$action" in build|compose) ;; *) echo 'Usage: docker.sh build|compose [Docker arguments]' >&2; exit 1;; esac
community=$root
scale=false
if grep -q '<artifactId>m3-scale</artifactId>' "$root/pom.xml"; then
    community=$(CDPATH= cd -- "$root/../m3" && pwd)
    scale=true
fi
M3_VERSION=$(sed -n 's/.*<revision>\([^<]*\)<\/revision>.*/\1/p' "$community/build-config/pom.xml")
printf '%s\n' "$M3_VERSION" | grep -Eq '^[0-9]+\.[0-9]+\.[0-9]+(-[0-9A-Za-z.-]+)?$' || { echo 'Invalid application version' >&2; exit 1; }
export M3_VERSION
if [ "$action" = compose ]; then
    exec docker compose --project-directory "$root" "$@"
fi
if [ "$scale" = true ]; then
    M3_IMAGE=${M3_IMAGE:-m3:$M3_VERSION}
else
    M3_IMAGE=${M3_IMAGE:-m3:community-$M3_VERSION}
fi
export M3_VERSION M3_IMAGE
if [ "$scale" = true ]; then
    exec docker build --build-arg "APP_VERSION=$M3_VERSION" --build-context "community=$community" -t "$M3_IMAGE" "$@" "$root"
else
    exec docker build --build-arg "APP_VERSION=$M3_VERSION" -t "$M3_IMAGE" "$@" "$root"
fi
