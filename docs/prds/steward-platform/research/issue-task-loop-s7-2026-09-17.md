---
type: research
status: review
created: 2026-09-17
tags: [issue, turn, runtime, verification]
---

# S7 — issue task loop

The implementation is ready for personal review; final verification details follow. No cold gate or test JVM has been launched. Default remains PID 53320.

## Authorities and seams read

Read the program-facts PRD end to end, including §1b T1–T5 and §4 S7;
re-read §1c C1–C8 after the owner’s continuation. Read owner decisions Part 1,
decisions 4 and 8b, AGENTS.md sections 0–5 and 7, and
`tmp/orchestrator/wave2/repl-rule.txt` end to end.

The binding seam ledger:

- `src/seon/issue.clj`: creation, `start!`, `start-tx`, `check-form`, generated
  issue reconciliation, status, and `resolved-tx` settlement.
- `src/seon/plan.clj`: `stale-issue-tests`, `run-issue-tests!`, done-query
  evaluation, completion transaction data, and `settle-call`.
- `src/seon/turn.clj`: `system-turn` and generated-read admission, ordinary
  opening, `next-agent-work`, `continuing-reply?`, `closing-settlement?`,
  evaluation/batch settlement, ordinary close, and the complete budget change
  in `97d1f69e0`.
- `src/seon/issue/opening.clj` and both
  `resources/seon/schemas/seon.issue.edn` and `seon.turn.edn`, end to end.
- Turn PRD `docs/prds/context-generation/plan/agent-record-and-turn-loop-prd-2026-09-07.md`
  §§14–15, end to end.
- `src/seon/db.clj`: retention admission; `src/seon/cluster/agent.clj`: identity
  rendering and source submission; `src/seon/bootstrap.clj`: generated help;
  `src/seon/render.clj` / `src/seon/render/value.clj`: source intent, schema
  selection, and conversion of pulled refs into transaction shape;
  `src/seon/render/web.clj`: agent debug acquisition and rendering;
  `src/seon/repl.clj`: saved and live evaluation rendering.
- Canonical fixtures and existing virtual-turn/lifecycle helpers in
  `test/seon/test_support.clj`, `test/seon/turn_test.clj`,
  `test/seon/issue_test.clj`, and `test/seon/issue_settlement_test.clj`.

Loaded skills: data-oriented-clojure, repl, clojure-testing, data-modeling,
datahike, seon-flow-architecture, and datastar-web-ui. Dependency ledger:
Datahike’s `reference-code/datahike/src/datahike/db/transaction.cljc:1152`
supplies the current transaction database to `:db.fn/call`; its existing
writer owns serialization. SCI’s existing evaluation and stored shown-text
path remains the execution owner. No new fixture or execution machinery.

## Changes

- Admission requires tests or a detector inside `start-tx`. Retention prevents
  removing an existing assigned test set, while allowing a detector-only issue.
  The plan uses the issue owner’s one done-query: tests win, else the detector.
- An assigned issue continues while unresolved and within its total budget.
  The budget anchor is the assignment transaction; incoming messages do not
  refill it. Closed ordinary calls also consume it if they fail before a provider
  attempt; generated openings and same-transaction system turns remain free. Conversational disposition behavior is unchanged, so the
  conditional transcript-orphan deletion and conversational assertion were
  deliberately left alone.
- `my.issue/status` includes test results, failure text, and remaining turns.
  Nested refs retain entity IDs so schema selection reaches the issue’s
  curated AI/HTML pair. The agent’s existing generated opening emits the
  ordinary status read. The existing since-diff refreshes it after closing a
  turn; unchanged shown values append nothing. That issue read may observe
  budget evidence; other generated reads retain the turn-activity guard.
- Exhaustion asserts `:seon.issue/budget-exhausted-tx` and delivers one root
  message in the writer after completion is checked. A larger-budget `start!`
  keeps the issue and agent, retracts exhaustion, and opens the next turn.
- Opening variants name the exact tests or detector in one done-condition
  block. Issue help omits `my.shell`, `my.edit`, and session-disposition advice.
- Scoped generation passes `:seon.fn.file/relative-root` to the detector and
  reconciles only that root’s previous generated function issues.

## Measured verification

All runs use `seon.test/run` with the runner’s own test loader, explicit
connection/provenance, and 180,000–240,000 ms bounds. The fixture namespace was
never reloaded. The retained probe file gives the exact repeatable forms.

Before T2–T4, T1’s focused checks passed 90 assertions: creation 15,
detector-only start/settlement 10, retention 33, settlement 32. These were
hot-loaded in-process results, not a converged adoption or a cold gate.

The generated-read guard and conversational wake regression then passed
6 and 4 assertions respectively, with no failures or errors.

The extended issue-loop regression passed 49 assertions with zero failures or
errors before the closed-call budget addition. With that addition, its next run
passed all 49 assertions but reported one worker-global instrumentation drift
error (27 added definitions, including source and SCI owners) and a concurrent
schema change naming four `seon.operator.collect` keys. The final repeat passed **49 assertions, 0 failures, 0 errors**. The interrupted run is a shared-JVM boundary; only the clean repeat is claimed green.
The new closed-call budget regression passed 5/0/0; the opening regression
passed 9/0/0; generated-read guard 6/0/0; conversational wake 4/0/0.
Creation and detector-only tests passed 15/0/0 and 10/0/0; retention passed 33/0/0.

The canonical settlement history records these exact shown texts:

```text
Issue settlement-fixture: still open; 1 turns remaining.
Settle from test evidence
Done condition: (my.test/check
  {:seon.test/changed
   ["my.agents.issue-settlement/steady-test" "my.agents.issue-settlement/success-test"]})
my.agents.issue-settlement/steady-test: passed
my.agents.issue-settlement/success-test: failed
expected: (= 1 (answer))
actual: (not (= 1 0))
```

```text
Issue settlement-fixture: resolved; 0 turns remaining.
Settle from test evidence
Done condition: (my.test/check
  {:seon.test/changed
   ["my.agents.issue-settlement/steady-test" "my.agents.issue-settlement/success-test"]})
my.agents.issue-settlement/steady-test: passed
my.agents.issue-settlement/success-test: passed
```

Earlier iterations exposed a stale renderer contract, missing nested ref IDs,
and a fixture accepting opening/reply in the same transaction. The regression
now uses the existing ordinary turn lifecycle and canonical transactions.

## Live boundary

On default, the actual request
`(seon.issue/generate! {:seon.db/connection (seon.operator/connection "default")
:seon.issue/detector "seon.issue.detect/public-without-doc"
:seon.fn.file/relative-root "src" :seon.issue/severity :cleanup})`
returned 2 forms, 2 open issues, and 0 resolved:
`65f46c0efa7f` for `seon.flow/->RefusingBuffer` and `7c333dd8c160` for
`seon.flow/->CountedDroppingBuffer`.

The monitored publication completed its branch but refused development
adoption with `the adopted source commit is unavailable`,
`:seon.cluster.source/source-absent`, naming
`6aaad49e-a55e-52ad-981d-4df826c4bb8f`. This confirms the separately recorded
`docs/seon/issues/development-adoption-refuses-an-unavailable-source-basis.md`.
The cause of the missing commit is not established. No default reset, source
basis substitution, protected-path edit, or foreign-session intervention was
performed. The exhaustion schema and current render definitions later became
available on default without restarting it. The proof below exercised those
live definitions and current SCI projection; it does not claim that the stale
source marker converged.

`start!` admitted issue `65f46c0efa7f` with budget 2 as agent `2393cac275ae`.
On default (the Juniper development fixture), DeepSeek Flash made two successful
provider calls at 19:01:50Z and 19:02:12Z on 2026-09-16. Turns `35931a1fdf8f`
and `0e407f8de457` returned ordinary read forms without any disposition.
Their token totals were 2,356 and 2,948 (5,304 total); the second reused 2,176
cached prompt tokens. No provider settings were changed.
The existing generated read `(my.issue/status {:seon.issue/id "65f46c0efa7f"})`
recorded the opening plus exactly two later evaluations:

```text
Issue 65f46c0efa7f: still open; 1 turns remaining.
Public function seon.flow/->RefusingBuffer carries no docstring
Done condition: (seon.issue.detect/public-without-doc (seon.db/db))
```

```text
Issue 65f46c0efa7f: still open; 0 turns remaining; budget exhausted.
Public function seon.flow/->RefusingBuffer carries no docstring
Done condition: (seon.issue.detect/public-without-doc (seon.db/db))
```

The issue has the typed exhaustion fact, `next-agent-work` returns nil, and
message `0289db4abb7f` addresses root with the issue ref and exact content:

```text
Issue 65f46c0efa7f exhausted its budget after 2 provider turns.
Issue 65f46c0efa7f: still open; 0 turns remaining.
Public function seon.flow/->RefusingBuffer carries no docstring
Done condition: (seon.issue.detect/public-without-doc (seon.db/db))
```

The issue remains open; no claim of a live repair is made. The canonical
regression proves resume from budget 2 to 4 on the same issue and agent,
and writes `resolved-tx` after the test fix.

CUA failed before browser acquisition with
`CUA_REPL_ENABLED_SURFACES is required`. Local headless Chrome rendered
`http://127.0.0.1:7994/agent/2393cac275ae`; the inspected 1440×1100 screenshot
shows the curated issue block with exhaustion, remaining turns and exact
detector form. The agent page is idle. The [screenshot](issue-task-loop-s7-agent-2026-09-17.png) is retained beside this
note; the browser was closed. The connector failure has its own issue.

Protected S1 paths were read where needed and never changed:
`src/seon/sci/eval.clj`, `src/seon/fn.clj`, `src/seon/program.cljc`, and
`test/seon/program_test.clj`. Other lanes’ edits and sessions were preserved.

## Static checks and cleanup

`git diff --check` passed. clj-kondo reports 0 errors and 70 warnings across
selected existing namespaces. Its initial unresolved `parser.type/->Variable`
was verified as a `deftrecord`-generated constructor in
`reference-code/datalog-parser/src/datalog/parser/type.cljc:41`; refreshing that
dependency cache with `deftrecord` linted as `clojure.core/defrecord` removed it.
No production reference was changed to satisfy the cache. No default process
or foreign session was stopped. The lane's screenshot browser exited; its
scratch worktree and scratch directory were removed before reporting.

## Review hold

No `bin/test`, `bin/test-fast`, platform gate, or additional JVM was used for
verification. A cold gate remains blocked on the orchestrator’s personal
review, as assigned. The live two-turn status and page proof passed with the source-adoption boundary
above. Full namespace and platform gates belong to the orchestrator after review.
