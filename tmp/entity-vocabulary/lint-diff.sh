#!/bin/sh
# Lint HEAD and HEAD+sweep diff from git archives with --cache false; print new findings.
set -e
S=${S:?scratch dir}
cd /Users/sean/src/seon
rm -rf "$S/base" "$S/new"; mkdir -p "$S/base" "$S/new"
git archive HEAD | tar -x -C "$S/base"; git archive HEAD | tar -x -C "$S/new"
git diff HEAD -- $(cat tmp/entity-vocabulary/changed.txt) > "$S/sweep.patch"
(cd "$S/new" && git apply "$S/sweep.patch")
(cd "$S/base" && clj-kondo --cache false --lint src script test > "$S/base-lint.txt" 2>&1 || true)
(cd "$S/new" && clj-kondo --cache false --lint src script test > "$S/new-lint.txt" 2>&1 || true)
tail -1 "$S/base-lint.txt" "$S/new-lint.txt"
sed -E 's/^[^ ]*\/(base|new)\///; s/:[0-9]+:[0-9]+:/:/' "$S/base-lint.txt" | sort > "$S/b.txt"
sed -E 's/^[^ ]*\/(base|new)\///; s/:[0-9]+:[0-9]+:/:/' "$S/new-lint.txt" | sort > "$S/n.txt"
comm -13 "$S/b.txt" "$S/n.txt"
