---
type: issue
status: resolved
severity: friction
tags: [issue, tooling, edit-hook, clj-kondo]
---

# A poisoned kondo cache entry blocked every clojure.test edit

## Problem

`.clj-kondo/.cache/v1/clj/clojure.test.transit.json` (written 2026-08-17
09:06, during the wave-A session) lost its var table: `clj-kondo --lint`
reported `Unresolved var: deftest` on ANY file referring
`clojure.test/deftest`, including a minimal two-line probe. Because the
edit hook lints prospective edits at error level, this blocked every
edit to every test file in the tree — a tooling wedge unrelated to the
edited content.

## Resolution (2026-08-28)

Deleted the single poisoned cache file; kondo re-derives `clojure.test`
and the probe and `test/seon/env_test.clj` lint clean. Root cause of
the poisoning is NOT established — something linted on 2026-08-17
overwrote the entry with an empty analysis (candidate: a lint pass over
a vendored or generated tree carrying its own `clojure/test` source).
If the symptom recurs, capture the cache file before deleting and find
the writer; a recurrence upgrades this to a class issue on the edit
hook (a lint that can poison its own cache should lint vendored trees
with a separate cache dir).

## Acceptance

`clj-kondo --lint test/seon/env_test.clj` reports zero errors — held at
resolution.
