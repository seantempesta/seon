---
type: landing
status: landed (a79508fd1, 40b43fc1b, 20368330a); tests owed to root
lane: cluster-deadcode-and-mcp-bound
created: 2026-09-25
---

# Lane cluster-deadcode-and-mcp-bound (2026-09-25)

Two deletions and one bound in `src/seon/cluster.clj`. The deletions follow the held-file follow-ups in
`lane-wave2-deadcode-problems-boot-status-2026-09-25.md` and row 6 of `docs/research/agent-platform/lock-audit-2026-09-24.md`.

## Broken or unverified first

- **No test ran.** The LOAD RULE applies, so nothing here was proven in the installed program. Hook publication is
  paused, so default still runs the pre-lane `seon.cluster` (the "before" figures below are from that version).
  Proof in this lane: clj-kondo lint (no new warnings; the deletions remove 6 unused-private warnings and the
  now-unused imports) and a read of every top-level form in `seon.cluster` (143 forms before item 1, 136 after).
- **`seon.fn/database-manifest` (src/seon/fn.clj:3603) now has no production caller.** Its only caller was
  `publication-base!`; `test/seon/cluster/publication_test.clj:12` still calls it. That file is outside this lane,
  so it is recorded here for root to file under `docs/seon/issues/` or delete.
- **Operator summary count.** `script/seon/operator.clj` `cluster-summary` (:1452) counts
  `:seon.dev.mcp/open-faults`, which now holds at most 10 entries. Its `:seon.operator/count` should read
  `:seon.dev.mcp/open-fault-count` when that key is present. The operator file's owner needs to change it. This
  lane did not touch it.
- **Still unbounded in the observation:** `:seon.dev.mcp/readiness` (readiness minus problems),
  `:seon.dev.mcp/replaced-roots` and `:seon.dev.mcp/flow` (one row per proc). None of these was in scope.
- **The whole value.** The open faults stay in the database (their signature rows and occurrence evidence). The
  `problems-unavailable` and `program-unavailable` refusals are return values of `seon.problems/problems` and
  `sci.eval/loaded-identity`. This lane did not verify that either is committed anywhere.

## Commits

| Item | Commit | Lines |
|---|---|---|
| 1. Delete `boot-phase`, `starting`, `server-name`, `reserve-cluster!`, `release-reservation!`, `create-directories!`, `require-cluster-target!`, the `LinkOption` import | a79508fd1 | +1 −59 |
| 2. Delete `publication-base!` and the helpers only it used (`source-artifact-file`, `write-source-artifact!`, `source-artifact`), the `seon.cluster.export` require, four imports and `test/seon/cluster/publication_export_test.clj`; branch_execution_test switches its `spit` fixture to `seon.fs/write-edn-atomically!` | 40b43fc1b | +4 −163 |
| 3. Bound `mcp-runtime-observation` | 20368330a | +34 −9 |

Net: +39 −231 = **−192** (src/seon/cluster.clj −143 of that; tests −49).

## Evidence

- **Dead-code proof.** `rg` over src, test, script, bin, resources and dev found only the definitions. A read-only
  MCP query on default (`:seon.fn/calls`, `:seon.fn/references`, `:seon.fn/unresolved-references` → `:seon.fn/sym`)
  returned no callers for any of the seven item-1 symbols or for `publication-base!`. `starting` was referenced
  only by `reserve-cluster!` and `release-reservation!`. `server-name` had no caller and was deleted in the same cut.
  The query was checked against a known caller: for `seon.cluster/refused!` it returned 21 callers.
- **Replacement spit fixture.** Stored `:seon.fn/calls clojure.core/spit` lists `seon.fs/write-edn-atomically!` as the
  only other src caller besides `publication-base!`. The interpreted row's class imports (Files, CopyOption) install through
  `seon.sci.eval/install-declared-classes!` (eval.clj:1938).
- **Bound (item 3).** The observation now carries the 10 newest open faults (by `:seon.error.occurrence/last-at`).
  In each, strings over 300 characters are cut to 300 and their full length is recorded under
  `:seon.dev.mcp/omitted`. It also carries `:seon.dev.mcp/open-fault-count`. Both refusals pass through
  `bounded-summary`, which keeps scalars, cuts strings over 300 characters and drops collections, recording
  each size under `:seon.dev.mcp/omitted`. Health still derives from every open fault.
  Pure probe of the same function body:
  - A small refusal comes back unchanged (`=`), so `mcp_test` `runtime-observation-shows-unavailable-problems-with-their-cause` keeps its equality.
  - A 1,500,000-character message plus a 100,000-element `:offending` prints as 424 characters, in 0.009 ms.
  - Sorting 500 shuffled faults puts the newest first.
- **Size on default (before, the installed pre-lane function):** 16 open faults; observation 12,758 chars (faults
  9,310), 416 ms per call. The same live value put through the new shaping comes to 9,362 chars (faults
  5,892; longest message 454 → 300). The new cost is one sort of the open faults, O(n log n) with n = 16 here.
  Root should re-time the installed call after adoption.

## Root runs (serially, after adopting the three commits)

- `bin/test-check default --test seon.sci.branch-execution-test/an-interpreted-caller-resolves-its-compiled-namespace-aliases-privates-and-core`
- `bin/test-check default --test seon.cluster.mcp-test/runtime-observation-shows-unavailable-problems-with-their-cause`
- `bin/test-check default --ns seon.cluster.boot-test` (status-faulted reads `:seon.dev.mcp/open-faults` signatures; the fixture receipt is the newest fault, so it stays within the 10)
- `bin/test-check default --ns seon.operator-test`
- MCP `runtime_status` on default: check that `:seon.dev.mcp/open-fault-count` is present and that no message exceeds 300 chars.

Missing regression: `test/seon/cluster/mcp_test.clj` (not owned) needs one class regression. It should give the
observation more than 10 open faults and a refusal with a message of more than 300 characters, then assert the
10-entry cap, the count and the `:seon.dev.mcp/omitted` sizes.

Released: src/seon/cluster.clj, test/seon/cluster/publication_export_test.clj (deleted),
test/seon/sci/branch_execution_test.clj, this note. No branches, shells or JVMs were created.
`tmp/cluster-deadcode/` has been removed.
