#!/usr/bin/env bash
# Run from the committed checkout; pass a new disposable operator root.
set -euo pipefail
proof_root="${1:?Pass a disposable operator root}"
proof_evidence="${2:?Pass an evidence directory}"
mkdir -p "$proof_root" "$proof_evidence"
trap 'bin/seon --root "$proof_root" down > "$proof_evidence/down.log" 2>&1' EXIT
bin/seon --root "$proof_root" start --head > "$proof_evidence/first.log" 2>&1
python3 tmp/astra-analysis-simple-20260928/measure.py "$proof_root" "$proof_evidence/first.json"
bin/seon --root "$proof_root" start --head > "$proof_evidence/second.log" 2>&1
python3 tmp/astra-analysis-simple-20260928/measure.py "$proof_root" "$proof_evidence/second.json"
# For the dependency-bump leg, use an owned archive repository, apply
# dependency-bump.diff to its owned Datahike checkout and commit only that file.
# Commit only that repository's gitlink, then repeat start --head and measure.
# Read the new cache with cache-proof.clj; never change the shared submodule.
