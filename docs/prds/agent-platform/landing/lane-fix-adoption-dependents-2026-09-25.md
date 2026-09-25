# Lane fix-adoption-dependents-opus — 2026-09-25

What exists: `development-source-refresh!` reads the adopted commit, the
published changes, then `development-namespaces` reads reload facts, digests,
namespace presence and the `:seon.ns/requires` closure over both program
values. Each read's refusal was checked by `:seon.error/at`. The principal
fix: check the member every `seon.db` read refusal declares,
`:seon.db/invalid-read` (`:seon.db/invalid-read-error`,
`resources/seon/schemas/seon.db.edn:314`), not a key some refusal paths omit.

## Broken / unresolved first

- The original leak did NOT reproduce on the current JVM (pid 99448, started
  03:26:38Z). Root's refusal (`tmp/orchestrator/init-after-fix.txt`, written
  03:25:49Z) came from the pre-restart JVM. The first, at-less refusal is
  unrecorded; the producer class in `seon.db/q` is recorded in
  `docs/seon/issues/read-refusal-checks-test-error-at-not-invalid-read.md`
  (seon.db not owned by this lane).
- The regression passes before and after this change on an armed JVM: armed
  contracts already convert the refused-source path into an at-carrying
  refusal. It pins the wanted behaviour (a refused read refuses adoption by
  name); it cannot reproduce an unarmed at-less refusal without redefining
  Vars.
- Other `(:seon.error/at X)` checks on database reads in `src/seon/cluster.clj`
  are outside this lane's row; listed in the issue.

## Evidence (MCP JVM, default)

- Stepwise probe: `published-changes` 3836 ms, 7258 identities
  (`seon.fn/report-identities` 3113 ms, `seon.db/pull-many` x2 3070 ms);
  `development-namespaces` over 2794 changed declarations 98–107 ms, 411
  namespaces, with or without the packaged projection bound.
- `(seon.cluster/refresh-source! "/Users/sean/src/seon/data/clusters" [] "default")`
  (before this edit): 34 382 ms, adopted commit 6ab5eab8-b8c0-5cb9-a6f9-c5c802ba06c0,
  86 reloaded namespaces, 3530 arming identities. Profile:
  `development-source-refresh!` 18 997 ms, `full-source-refresh!` 15 180 ms,
  `seon.fn/analyze-rows` 12 245 ms, `seon.db/transact!` x3 11 963 ms.
- After the edit, ONE `bin/seon init --dev default`: exit 0, 17.40 s wall,
  commit 6ab5ebdd-ccea-5d0e-82c5-c55fd7667b4e, cluster record equals
  `source/current`; `seon.cluster/development-namespaces` among the adopted
  identities. Profile: `full-source-refresh!` 15 676 ms,
  `seon.fn/analyze-rows` / `analyzed-files` 10 785 ms,
  `seon.fn.analyzer/analyze` 5 699 ms, `seon.db/with-declarations` x36 937
  5 280 ms; adoption ~1.7 s. Raw: `tmp/fix-adoption-dependents-init.txt`.
- `seon.problems/stale-vars` on default after init: 0.
- Loaded `development-namespaces` over a refused previous value throws
  "Declaration reload facts could not be read."
- Every init is over one second: publication analysis
  (`seon.fn/analyze-rows`, proportional to the whole analyzed file set) is the
  responsible armed function; the 39.5 s earlier run added a full-program
  adoption (no prior commit read → all identities) and 86-namespace reload.

## Changes

- `src/seon/cluster.clj`: four checks in `development-namespaces` and the
  prior-commit pull in `development-source-refresh!` (a refused pull no longer
  reads as "no adopted commit", which would adopt every row). +10 −6.
- `src/seon/cluster/source.clj`: `changed-identities` checks
  `:seon.db/invalid-read` on `seon.fn/report-identities`. +1 −1.
- `test/seon/cluster/reload_per_declaration_test.clj`:
  `a-refused-dependents-read-refuses-adoption`. +19.
- Net: +30 −7 lines.

## Root runs

- `bin/test-check default --test seon.cluster.reload-per-declaration-test/a-refused-dependents-read-refuses-adoption`
- `bin/test-check default --ns seon.cluster.reload-per-declaration-test`
- `bin/test-check default --ns seon.cluster.publication-delta-test`
