#!/usr/bin/env bash
set -e
if command -v gradle >/dev/null 2>&1; then
  exec gradle "$@"
fi
printf '%s\n' 'Gradle is not installed or on PATH. Install Gradle or use the GitHub workflow to build the AAR.' >&2
exit 1
