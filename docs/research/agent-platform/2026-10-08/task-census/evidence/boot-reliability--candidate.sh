#!/usr/bin/env bash
# Build the archive `bin/seon check PATH...` rehearses (HEAD + PATHs), with the
# working-tree operator, and print its directory. Until root applies
# tmp/boot-reliability/patches/01-boot-check-request.patch and restarts, the
# rehearsal itself is called over MCP: (seon.cluster/rehearse-source! ...).
set -euo pipefail
cd "$(dirname "$0")/../.."
export SEON_CHECKOUT="$PWD"
paths=""
for p in "$@"; do paths="$paths \"$(cd "$(dirname "$p")" && pwd -P)/$(basename "$p")\""; done
bb --config /dev/null --classpath "script:src:resources" -e "
(require 'seon.operator)
(let [sha (#'seon.operator/candidate-revision! \"$PWD\" [$paths])
      source (seon.operator/committed-source! \"$PWD\" sha)]
  (println sha (:seon.operator/source-root source)))"
