#!/bin/zsh
# Measure the publication path from zero and through the hook's own command,
# on a throwaway worktree at HEAD and an isolated operator root.
# Usage: docs/prds/steward-platform/research/measure-publication-path-2026-09-22.sh [WORKTREE] [ROOT]
# Evidence: the operator's per-phase lines (`elapsed-ms` is the COMPLETED phase's
# wall-clock) and `init phase=lifecycle elapsed-ms=` per run. Recorded 2026-09-22
# in docs/prds/steward-platform/plan/unsettled.md (14:00, 14:40, 15:10 blocks).
set -eu
setopt no_nomatch
REPO=$(git rev-parse --show-toplevel)
WT=$(cd "$REPO" && mkdir -p "$(dirname "${1:-tmp/head-wt}")" && cd "$(dirname "${1:-tmp/head-wt}")" && pwd)/$(basename "${1:-tmp/head-wt}")
ROOT=$(cd "$REPO" && mkdir -p "${2:-tmp/head-root}" && cd "${2:-tmp/head-root}" && pwd)
[ -d "$WT" ] || git -C "$REPO" worktree add -q "$WT" HEAD
# The worktree's submodule directories are empty; the classpath needs the real vendored sources.
if [ ! -L "$WT/reference-code" ]; then
  find "$WT/reference-code" -mindepth 1 -maxdepth 1 -type d -empty -delete 2>/dev/null || true
  rmdir "$WT/reference-code" 2>/dev/null || true
  ln -s "$REPO/reference-code" "$WT/reference-code"
fi
rm -rf "$ROOT"; mkdir -p "$ROOT"
cd "$WT"
measure() {
  bb --config "$WT/bb.edn" --deps-root "$WT" \
    "$REPO/docs/prds/steward-platform/research/measure-storage-retention-2026-09-22.clj" \
    "$ROOT" "$1" >> "$ROOT/storage.edn"
}
reload_clock() {
  bb --config "$WT/bb.edn" --deps-root "$WT" \
    "$REPO/docs/prds/agent-platform/research/measure-publication-reloads-2026-09-22.clj" \
    "$ROOT" "$1"
}
run() {
  local name=$1; shift
  if [[ "$name" == adopt-* ]]; then reload_clock capture > /dev/null; fi
  local began=$EPOCHREALTIME
  { time "$@"; } > "$ROOT/$name.log" 2>&1
  echo "exit=0 elapsed-ms=$(( (EPOCHREALTIME - began) * 1000 ))" >> "$ROOT/$name.log"
  tail -1 "$ROOT/$name.log" | sed "s/^/$name: /"
  measure "$name"
  if [[ "$name" == adopt-* ]]; then
    reload_clock report > "$ROOT/$name-reloads.edn"
  fi
}
zmodload zsh/datetime
# Cold start, paid once: publish from zero, create the first cluster, start the JVM.
run start bin/seon --root "$ROOT" start head --config config/development.edn
# Fork against the RUNNING cluster - a Datahike branch, target under 1 s.
run fork      bin/seon --root "$ROOT" init head2
# Case A: the first adoption after the fork - nothing changed on disk, the cluster row has no adoption recorded yet.
run adopt-first bin/seon --root "$ROOT" init --dev head --changed src/my/note.clj
# Case A2: no change at all, cluster already at the published commit.
run adopt-nochange bin/seon --root "$ROOT" init --dev head --changed src/my/note.clj
# Case B: docstring-only edit in a non-core namespace.
perl -0pi -e 's/^  "/  "(measured edit) /m' src/my/note.clj
run adopt-noncore bin/seon --root "$ROOT" init --dev head --changed src/my/note.clj
# Case C: docstring-only edit in a core namespace, inside the producer closure of seon.fn.
perl -0pi -e 's/^  "/  "(measured edit) /m' src/seon/id.clj
run adopt-core bin/seon --root "$ROOT" init --dev head --changed src/seon/id.clj
measure sweep
reload_clock compile > "$ROOT/seon-id-compile.edn"
bin/seon --root "$ROOT" down
git -C "$WT" checkout -- src/my/note.clj src/seon/id.clj
echo "phases:"; grep -h "elapsed-ms" "$ROOT"/adopt-*.log | sed -E 's/.*completed-phase "([^"]*)".*elapsed-ms ([0-9]+).*/\2\t\1/' | sort -rn | head -20
