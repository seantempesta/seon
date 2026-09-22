---
type: landing
status: landed 678009fcd; proof on a scratch cluster; default not adopted
created: 2026-09-23
---

# Realities commit 4: one test request on the agent lifecycle

Commit: **`678009fcd`** (31 paths, +1,292 / −3,099).

Track 1.3d commit 4 (`lane-realities-one-lifecycle.md` §2 rows 8–9, §3 item 4).
`seon.test/run` is now a single request. Each member is an isolated agent for one
body: it gets a fresh branch off the captured commit through
`seon.cluster.agent/acquire-context!`, the body runs, the result is recorded, and
`release-context!` unlinks the branch after the body's thread exits. The canonical
fixture is the same entrance and never copies a store. The `run`/`run-owned`/`check`
ladder is deleted, along with the copied base, `Held`, the base retry/hold machinery
and the prewarm. Worker launchers and runner machinery stay for commit 5 (coordinator
scope ruling, 2026-09-23).

The first stop (`2cf063b75`, the missing acquisition source) was closed by
`entrance-supplier` (`7f6718507`). The predecessor Codex lane had begun
`bounded-result` actual-exit observation and audit rows 17/18 in `test/cache.clj`.
Both are kept and completed here.

## What changed (one loadable slice)

- `src/seon/test.clj`
  - `run [request]`: one request with members `:seon.test/execution` (an agent context
    source or handle), `:seon.test/recording-connection` and `:seon.test/policy`, plus
    optional identities, namespaces, changed, include-long? and check-time-limit-ms.
  - `run` selects through the existing `select`/`selection-admission` and excludes
    development-root hosts (`host-exclusions`: destroyers plus fixture-observation, each
    with a `bin/test --platform -- SYM` command).
  - It admits through `admit-run` on the recording connection and acquires one
    request handle at the captured commit.
  - Per member (`member-result`), it acquires a child branch, resolves the Var, runs
    `bounded-result` → `sci.eval/run-test`, records through `runner/commit-results!`,
    and releases after exit.
  - The result carries `:seon.test/passed?` (exclusions, pending and unfinished are
    unfulfilled), reused members, `:seon.test/timings` per member (branch, acquire,
    resolve, run and release ms), and `:seon.test/pending` / `:seon.test/unfinished`.
  - `tally` renders it and replaces `feedback`.
  - `bounded-result` joins the body thread under the request remainder. A live body
    fails, keeps its branch, and a watcher releases the branch only after the thread
    exits.
  - `resolve-test` skips the program digest when the member's commit equals the
    acquired commit (the same immutable value).
  - `select` loses its published-snapshot path.
  - Deleted: old `run`, `run-owned`, `check`, `check-in-process`, `check-admission`,
    `check-request-admission`, `check-adoption`, `check-request`, `check-completion`,
    `expired-result`, `feedback`, `host-admission!`, `execute-admitted!`,
    `select-snapshot`, `recorded-host-result`, `destructive-refusal`,
    `prepare-tests!`, `test-loader`, `event-backstop-ms`, `commands` and `relative-path`.
- `test/seon/test_support.clj`
  - `with-database` acquires through `acquire-context!` off the executing handle's
    commit, runs the body under the fixture's custody, and releases.
  - `execution-handle` (public) finds the handle the entrance holds, via the
    connection, the body's custody or the SCI arm governing the thread. A test outside
    `seon.test/run` gets a named refusal.
  - `fork-cluster-ctx` forks that handle's context.
  - `fresh-store?` means an empty in-memory store with the installed attribute schema.
  - Deleted: `create-base`, `close-base!`, `Held`, `adjust-holders`, `retrying-base`,
    `base-state`, `database-base`, `*held-base*` and the branch-lease atom.
- `src/seon/sci/eval.clj` `acquired-database?`: the same Datahike commit id means
  the same committed value (`reference-code/datahike/src/datahike/db.cljc:385`), so
  a fork at that commit acquires nothing again.
- `src/seon/cluster/agent.clj` `acquire-context!`: a branch opened at the captured
  commit carries the captured projection. Before, it called
  `projection-from-database`, measured at 133 ms.
- `src/seon/config.clj` `apply-compiled!`: reconciliation adopts only inherited
  config rows that no cluster entity owns. Retracting a row that another cluster's
  required `:seon.cluster/config` names refused every `seed-cluster!` on a
  live-cluster branch.
- `src/seon/issue.clj` `citation-attributes`: keys and schemas now come from one
  projection. Every incremental adoption that added a schema key had refused with
  `:malli.core/invalid-schema` (schema nil) in `seon.issue/adopt-tx`.
- Callers:
  - `src/my/test.clj`: `check` becomes an incremental request, and `run` expands to
    the new `run-owned` named request.
  - `src/seon/plan.clj` `run-issue-tests!`: one named request on the agent's handle.
  - `bin/test-check`: one request over the prepl
    (`--test`/`--ns`/`--changed`/`--policy`/`--include-long`/`--time-limit-ms`).
- Schemas
  - New in `seon.test.edn`: `:seon.test/request`, `run-result`, `execution`,
    `recording-connection`, `policy`, `timings`, `unfinished`, `pending`, `passed?`,
    `executed-count`, `reused-count` and `recording-refusal`.
  - New file `seon.test.timing.edn` holds the four phase keys.
  - `my.test.edn` gains `:my.test/run-request`.
  - Retired with their functions: `:seon.test/run-options`, `run-owned-request`,
    `check-request`, `check-result`, `expired`, `host-result` and all of
    `seon.test.check.edn`.
- `src/seon/test/cache.clj`: audit row 17, the gitlink commit id used as its own
  identity (no SHA-256), and row 18, the docstring naming the guarantee Git lacks.
- Tests
  - New class regression `test/seon/test/one_request_test.clj`: isolated members on
    distinct unlinked branches, no write reaching the execution branch, reuse with
    zero executions, a bound firing before start leaving the member pending, a member
    body on its own listed branch, and development-root exclusion with its command.
  - `test/seon/test/fixture_timing_test.clj` is rewritten for the entrance.
  - Deleted with their mechanism (question 1):
    - `test/seon/test/check_request_test.clj`, `test/seon/test/host_test.clj` (run-owned),
      `test/seon/test_expiry_test.clj` (old remaining-ms expiry)
    - the `await_test` check-completion case
    - `test_test` check-records…
    - `test_runner_test` agent-fork-callable (the old 1-arity `run`)
    - eleven check/feedback/run-in-fixture cases and `with-expiring-selection` in
      `test_reaching_test`
    - the machinery cases of `test_support_test`, `test/runner_test` and
      `test_preparation_test`, taken from the shelved B4 c1 patch
  - Converted: `test_reaching_test` `run-in-fixture`, long exclusion and opt-in to
    requests; `test_failure_facts_test` `run-probe`; `test_test` resolution without
    run-owned.
- `.agents/skills/clojure-testing/SKILL.md`: the fixtures/custody and
  execute-and-report sections are rewritten, and stale line references are corrected.
- Issues: `docs/seon/issues/a-data-only-commit-rebuilds-the-whole-sci-program.md`
  and `docs/seon/issues/development-adoption-leaves-the-loaded-program-at-the-boot-commit.md`.

## Proofs (scratch cluster, never default)

Environment:
- Source: frozen `git archive bfe3445f8` + these edits (`tmp/realities-c4-src`),
  root `tmp/realities-c4-root`.
- From-zero boot: PID 35930, start 2026-09-22T21:13:10Z, ready-ms **167,880**, wall
  **184.8 s**, exit 0, no missing layers.
- The earlier working-tree boots refused on other lanes' hunks: exit 1 at 44.9 s
  (stale kondo cache for `script/seon/operator.clj`, which I repopulated) and at
  45.7 s (`:seon.schema/form` scratch transaction).
- Coordinator row for from-zero-boot-takes-minutes: `2026-09-22 | bfe3445f8 archive
  + commit-4 files | from-zero | 167,880 | 184.81 | — | this note`.
- Schema proven incrementally too: `bin/seon --root … init --dev default --changed
  <25 paths>` adopted in 18.8 s after the `citation-attributes` fix, source commit
  `6ab2eec1-…`. That adoption then exposed the loaded-program defect (issue above), so
  the proofs below use the from-zero boot, where loaded program = rows.

| # | Proof | Evidence |
|---|---|---|
| a | a deftest runs through `acquire-context!`, its branch listed during the body, gone after | `a-member-body-runs-on-its-own-listed-branch` green in run `250346336f7b`: mode `:isolated`, `owns-branch?` true, branch on `registry/roster` during the body. The regression asserts every member branch is absent from the roster after the request. |
| b | two members on two branches off one commit, neither sees the other's writes | `one-request-runs-each-member-as-an-isolated-agent` (15 assertions) green in `250346336f7b`: writes-a/writes-b on distinct branches, each sees only its own marker, none reaches the execution branch |
| c | unchanged green answered from the record, zero executions | same regression's reuse case. Also run `6a1c0f5bbd38`: 4 members, `executed 0 / reused 4 / pass 31`, 1,189 ms |
| d | overrun fails naming the bound; exit observed | `seon.test.duration-test` both tests green through `run` (`884966d5fee2`, 23 assertions, 2,992 ms). Unfinished class at the REPL: an agent test spinning past a 4,000 ms request bound (remainder 1,100 ms) returned `:seon.test/unfinished` with branch `agent-afb1196f285c` still listed. After the SCI arm stopped the body, a second read showed the branch unlisted and no longer held (watcher release) |
| e | destructive member excluded with its command on a development root | `a-development-root-excludes-a-destructive-member-with-its-platform-command` green: excluded with `["bin/test" "--platform" "--" SYM]`, nothing excluded under another root |
| f | leaf and core wall time | leaf `my.note/add!`: reach 4 tests (gate walk 33.0 ms); incremental request `69fcb7ad40a3` 5,686 ms (see below). Core `seon.id/id`: reach **1,595** tests, gate walk 1,088 ms; **not executed**, since the extrapolated minutes need owner authorization |
| g | HEAD loads | `git archive 678009fcd` into `tmp/realities-c4-head` with linked `reference-code`, `clojure -M:test -e (require …)` of 21 namespaces (seon.test, runner, test-support, my.test, plan, issue, config, cluster.agent, cluster.source, cluster and every touched test ns) printed `:head-loads`, 17,586 ms JVM wall. `bin/test --platform` was not run: its workers cannot host `with-database` until commit 5 |
| h | named request from the MCP eval tool | the exact form is in the skill. Run on the scratch cluster (`250346336f7b`, `884966d5fee2`). **Not on default**: default runs pre-commit-2 source and was down for another lane's store restore at 21:13Z |

Leaf request `69fcb7ad40a3`:
- `:incremental` puts the declared platform tier first. The first platform member,
  `seon.cluster.registry-test/a-concurrent-create-wave-loses-nothing`, errored
  ("names branch :db, which is not the writing cluster's branch") because host bodies
  then carried member custody. They now inherit none, as before. The rest stayed
  pending by the existing platform-red rule. No re-run: the platform tier is the
  orchestrator's.

## TIMINGS (every operation over 1 s)

| operation | wall ms | phases / note |
|---|---:|---|
| from-zero boot (frozen) | 184,810 | ready-ms 167,880 — **defect** (>10 s), existing `from-zero-boot-takes-minutes.md` |
| from-zero boot attempts (working tree) | 44,915 / 45,727 | refused by foreign hunks, see above |
| incremental adoption, 25 paths | 18,811 | **defect** (>10 s); same class as `the-first-debug-page-after-an-adoption-takes-eighteen-seconds.md` family, unmeasured phases |
| JVM `clojure -M:test -e (require …)` of 17 namespaces | 17,574 | JVM start + compile; **defect** (>10 s), test JVM start is commit 5's concern |
| isolated acquisition before the fix | 1,351–1,621 | branch! 18.6, open 3.7, projection 133.0, fork 5.8, `acquire!` 941.9 → after fixes 39–43 ms when the source ctx has acquired the commit |
| `acquire!` on a stale cluster ctx (data-only head move) | 515–795 | whole `base-ctx`; issue `a-data-only-commit-rebuilds-the-whole-sci-program.md` |
| one request, 1 member, first at a new commit | 3,602 | selection-admission 954 (select ~350 + program-digest ~235 + provenance), admit tx 540, request handle 174–795, member 50 acquire + 16 resolve + 231 run + ~0 release, commit-results 101 |
| one request, all reused | 997–1,189 | selection + admission only |
| regression `one-request-runs-each-member…` body | 9,922 | three nested requests (above) plus four agent test definitions (~0.95 s each); declared `:seon.test/long-ms` 15000 with reason. The request overhead is the **defect** in the acquisition issue |
| request of the 4 regression tests | 18,203 | **defect** (>10 s): 9.9 s regression body + 1.9 s exclusion test + 0.8 s fixture timing + request overhead |
| duration tests request | 2,992 | 2 members at 36–39 ms acquire, 7 ms resolve, 78–80 ms run |
| leaf incremental request | 5,686 | platform member first (above) |
| `bin/test-check` named, one member | 1,899 | request 1,597 |
| schema loader check (`packaged-forms`) | 7,628 | JVM start |

Per member (the target): acquire **30–63 ms**, resolve **6–16 ms**, release (the
watcher's unlink on exit) under 0.01 ms of the member's clock plus ~9 ms unlink
measured directly. Fixture `with-database`: first **51.9 ms**, later p50
**53.1 ms** (11 samples, `fixture-timing-test`), vs the copied base's 4,647.8 ms
first. The per-request overhead (~1–3.5 s) is the defect filed above. Its two causes
are the whole-program `base-ctx` on every new commit and `program-digest` computed
three times per request (select, provenance, admission writer).

## Limits and what is left

- Not adopted on default, so no MCP proof on default. RESET NEEDED: none by this lane;
  default needs the orchestrator's normal adoption. That adoption depends on the
  loaded-program issue above for isolated acquisition after adoption.
- `bin/test` and `bin/test-fast` workers hold no execution handle, so every
  `with-database` test refuses there with the named `execution-handle` error. The
  replacement is `bin/test-check` / MCP `seon.test/run` on a cluster. Commit 5 deletes
  the worker launchers, slots, staged results and duplicate selection, and gives the
  platform tier its host: for now, a scratch root plus `bin/test-check --root … --policy
  platform`.
- The recording refusal "The test execution evidence does not authorize this
  transition." appeared once, when the regression body was red with nested
  `testing` reports (run `a90391457631`). It did not recur once green and is not
  investigated.
- `seon.test/verified?`, `stale`, `recorded-result` and `reach-digest` remain as
  evidence readers for issue/plan (B3). The reach-digest index stays until the
  selection rule changes (B4 c5 / B1).
- No bulk or platform tier was run (orchestrator-owned).
