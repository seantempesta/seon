---
type: issue
status: resolved
severity: friction
tags: [issue, database]
---

# reference-code/konserve checkout diverged from the deps.edn pin

## Problem

`deps.edn` pins konserve at `:git/sha b5c99bc0…` while the
`reference-code/konserve` submodule sat at `df6818d4`, and the pin was not
an ancestor of the checkout. Agents grounding plans in
`reference-code/konserve` read different source than the build resolves,
violating the read-exactly-what-the-build-runs rule.

## Root cause

Commit `c5c76da9` ("deps(store): pin current Datahike and Konserve forks",
2026-07-15) deliberately advanced the deps.edn pin to the tip of the fork
branch `seon-0.9.359-legacy-header` (commit dated 2026-07-15), whose
`fbdccc9` ports the legacy header compatibility onto upstream konserve
0.9.359 and adds the delete-store fixes (#152–#154). The superproject
gitlink was never advanced with it, so the submodule stayed on the older
`sync-only` branch tip (`df6818d`, the 0.9.356 merge, 2026-07-12). The two
tips diverge from merge-base `1291653`; everything on the stale side is
subsumed by the 0.9.359 port.

## Fix

Advanced the submodule checkout to the pinned SHA `b5c99bc0` (branch
`seon-0.9.359-legacy-header`) and committed the gitlink. `deps.edn` is
unchanged — the pin was already the deliberate truth.

## Proof

- `git submodule status reference-code/konserve` reports
  `b5c99bc02a7175652a610324215288b78551801f`.
- `bin/test-writer` full suite after the move: 231 tests, 1891 assertions,
  0 failures, 0 errors (the build resolves the same SHA from the git dep,
  so behavior is unchanged by construction).
