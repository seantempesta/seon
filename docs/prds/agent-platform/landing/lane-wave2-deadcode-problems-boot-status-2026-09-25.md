---
type: landing
status: landed (0db2517fe, 19c2bbbc3); item 2 found nothing to delete in boot.clj; tests owed to root
lane: wave2-deadcode-problems-boot-status
created: 2026-09-25
---

# Lane wave2-deadcode-problems-boot-status (2026-09-25)

## Broken or unproven first

- **No test request was run** (LOAD RULE). The changes were checked with clj-kondo (0 errors), a read-only
  JVM read of problems.clj (28 forms), and one bb run of the new operator regression (7/7 pass). Default has not
  adopted problems.clj or its tests. Root runs the commands below.
- **Item 2 deleted nothing.** Every dead boot function the wave-2 landing lists is in `src/seon/cluster.clj`,
  which the harness S2 lane holds. Default's stored reverse edges show `seon.cluster.boot` has only three
  definitions with no stored caller, and all three are live:
  - `install-uncaught-handler!` is called by the `uncaught-handler` defonce delay (boot.clj:391).
  - `record-uncaught!` is called by the handler through its Var (boot.clj:384).
  - `request!` is called by the operator's request form (operator.clj:232, :368).
- **`seon.error/log-line` was not deleted.** It lives in error.clj, which this lane does not own. Its only
  remaining caller is `seon.error-test/rendered` (stored edges). Deleting it means removing `log-line`
  (error.clj:1201), plus `error_test` `rendered` and the tests that use it (:148, :701, :715, :1483-1485).
- The 1.5 MB status output is not reproducible now: live status is 13.7 KB. Its likely producer is
  `:seon.dev.mcp/problems-unavailable` or unbounded `:seon.dev.mcp/open-faults` in
  `seon.cluster/mcp-runtime-observation` (cluster.clj:646, held). That is inferred, not observed. The operator
  now bounds its terminal face whatever the observation holds.

## Commits

| Item | Files | Commit | Lines |
|---|---|---|---|
| Delete `seon.problems/log-report` | src/seon/problems.clj; test/seon/{problems_test, test_failure_facts_test, cluster/agent_namespace_test}.clj | 0db2517fe | +10 −80 (src −64) |
| Bounded `bin/seon status` | script/seon/operator.clj (`status-summary`, `cluster-summary`, `top`, `short-message`, `status-top`), test/seon/operator_test.clj | 19c2bbbc3 | +81 (src +59, test +22) |

## Evidence

- `log-report` is dead. `rg` over src, test, script, bin and resources finds only its definition, its docstring
  mention at html-report, and test uses. Default's `:seon.fn/calls` has no caller for
  `seon.problems/log-report`. Its test assertions now target `ai-prose`/`html-report`. The absent-facts test
  checks that each value reaches `html-report` exactly once, and its row count is checked against the html rows.
- Status bound, measured with bb:
  - live default `bin/seon status`: 13,708 bytes before, 2,966 after. `--verbose` is still whole at 15,594 bytes.
  - a synthetic response of 3,050,399 bytes (500 open faults with 3,000-character messages, plus a 1.5 MB refusal)
    summarizes to 1,909 bytes.
  - the summary takes under 0.2 s wall for the whole bb process.
- The summary selects whole entries and never clips. A message over 300 characters is replaced by
  `:seon.operator/message-chars N`. Each family names `:seon.operator/count` and `:seon.operator/omitted`.
  `:seon.operator/requery` names MCP runtime_status and `--verbose`.

## Root runs (serially)

- `bin/test-check default --ns seon.problems-test`
- `bin/test-check default --test seon.test-failure-facts-test/failure-readers-use-the-structured-claims`
- `bin/test-check default --test seon.cluster.agent-namespace-test/source-bearing-namespaces-without-a-steward-derive-one-problem-line`
- `bin/test-check default --test seon.operator-test/status-prints-a-bounded-summary-of-500-failed-tests`

## Held-file follow-ups (exact edits)

- cluster.clj (S2 lane): delete `boot-phase` :243, `reserve-cluster!` :824, `release-reservation!` :836,
  `create-directories!` :844 and `require-cluster-target!` :850. None has a stored caller or an rg reference.
  `read-advertisement` is already gone. Delete `publication-base!` :3009 with
  test/seon/cluster/publication_export_test.clj:28 and the fixture symbol at
  test/seon/sci/branch_execution_test.clj:439.
- error.clj: delete `log-line` as described above.
- The status producer: bound `:seon.dev.mcp/open-faults` and the refusal carried in
  `:seon.dev.mcp/problems-unavailable` at `mcp-runtime-observation` (cluster.clj:646-711), so MCP
  runtime_status is bounded too.

Released: src/seon/problems.clj, src/seon/cluster/boot.clj (untouched), script/seon/operator.clj and the test
files above.
