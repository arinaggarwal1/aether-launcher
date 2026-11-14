#!/usr/bin/env bash
# Signed custom builds only. Never installs or changes a TV.
set -euo pipefail
cd "$(dirname "$0")"
if [[ -z "${JAVA_HOME:-}" ]] && [[ "$OSTYPE" == darwin* ]]; then
    export JAVA_HOME="$(/usr/libexec/java_home -v 17)"
fi
if [[ -n "${JAVA_HOME:-}" ]]; then export PATH="$JAVA_HOME/bin:$PATH"; fi
exec python3 tools/release.py build "$@"
