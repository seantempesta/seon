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

## Review revision — required changes after `3772e2f68`

Read [the orchestrator review](review-issue-task-loop-s7-3772e2f68-2026-09-17.md)
end to end and re-read PRD §1d D2. T1–T4 remain accepted; this revision changes
provenance, teaching selection, and the status-view declaration.

- Removed `issue-status-read?` and its `edn/read-string` classification. The
  existing agent renderer returns declared source blocks when it includes an
  issue opening. Each issue block carries `:seon.eval/origin` referencing that
  issue. The existing render call retains those blocks, the ordinary source
  preparation carries the origin, and both opening and system-turn writers
  retain it on the evaluation. The guard and the since-diff consult the origin
  entity's issue attributes. No source head or spelling decides the exception.
- The origin survives the incremental opening append, resumed evaluation,
  terminal settlement, cached evaluation recording, and silent refresh. It is
  a ref on the existing evaluation entity, not a second result or context path.
  Plain source renderers continue to return strings; `:seon.render/source`
  keeps its original contract. `:seon.render/source-blocks` declares source
  with provenance through the same render call and evaluator.
- `seon.bootstrap/help-value` queries the agent namespace's `:seon.ns/requires`
  and public functions. It no longer calls the cluster toolkit and subtracts
  names. Issue and conversational teaching use the same requirement query;
  conversational disposition behavior is unchanged.
- `:seon.issue/status-view` declares the derived `turns-remaining` field inline.
  It is outside the issue entity schema and has no standalone stored-attribute
  declaration. The status function declares that view as its successful output.
- `episode-runs` and `turns-left` return a typed read refusal instead of throwing
  it or applying arithmetic to an error map.

Additional seams read for this revision: `seon.render/source-return?`,
`raw-output`, `render-call` and retained invocations; `seon.turn/declared-sources`,
`system-plan`, `generate-turn`, `append-generated-call`, `receipt-row`,
`fold-evaluations`, `fold-source`, `evaluation-facts`, `record-evaluated-tx`,
`record-evaluated-call`, `recorded-evaluation`, and `receipt-terminal-attributes`;
`seon.bootstrap/help-value`; the evaluation, source-render and generated-form
request schemas; `seon.schema.datahike/storable-attribute-in?` and resource
placement validation. No protected or foreign path was edited.

The first publication refused a status-view declaration placed in a different
namespace's schema resource. It was corrected immediately: the separate view
schema lives in `seon.issue.edn`, as namespace placement requires, and the
entity schema does not contain the derived field. The pre-publication test
attempt returned that refusal without assertion counts; it is not a test pass.
A subsequent source build refused a changing source snapshot and was retried.
The lifecycle lock was held by another lane's operator publication; it was
left alone. Default remained PID 53320 throughout.

Live probes before the final regression run recognized an explicit issue
origin and did not recognize a source string merely spelling `my.issue/status`.
The default database had `:db/ident :seon.eval/origin` and no `:db/ident
:seon.issue/turns-remaining`. The opening regression now checks those two
invariants in its canonical fixture, increasing its assertions from 9 to 11.

The renewed live proof also exposed a real C4 gap: budget 4 opened turn
`7d739af1dc0f` but supplied no wake datom the parked graph listened to. Read
`src/seon/cluster/wake.clj`'s matcher, runtime-pattern and callback owners and
its canonical runtime-listens regression. `start-tx` now declares the existing
runtime listen pattern for this issue's budget in the same transaction, once,
and retains an already-open turn. No new transport, runtime registry, or manual
wake call is involved. The settlement regression verifies the actual committed
budget datom matches the assigned agent. Root messages now say ordinary turns,
matching the budget's counting rule, including calls closed before a provider.
See [the resolved resume defect](../../../seon/issues/resuming-an-issue-opens-a-turn-without-waking-its-agent.md).

The real `start!` call with budget 5 resumed agent `2393cac275ae` on the same
issue `65f46c0efa7f`. Its pending turn closed at transaction 536871739; three
additional provider turns completed, giving 5 ordinary turns spent and 0 left.
The exhausted fact points to transaction 536871756. There are two historical
exhaustion messages total: the original budget-2 message and ONE budget-5
message, whose first line is exactly:

```text
Issue 65f46c0efa7f exhausted its budget after 5 ordinary turns.
```

The new evaluations all carry origin ref 59710 (this issue). Exact shown text:

```text
Issue 65f46c0efa7f: still open; 2 turns remaining.
Public function seon.flow/->RefusingBuffer carries no docstring
Done condition: (seon.issue.detect/public-without-doc (seon.db/db))
```

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

Evaluation entity ids are 80813, 80867, 80914, respectively. The generated
issue remains open; this proof demonstrates continuation, origin retention,
and exhaustion, not a live repair of its generated constructor. The agent
page was observed with Chrome on default and shows the issue's HTML status,
budget 5, and idle worker: [review screenshot](issue-task-loop-s7-review-agent-2026-09-17.png).
Chrome was closed after capture. The existing CUA tool outage described above
still requires that fallback.

The initial review regression run produced 133 passing assertions, zero
assertion failures, and one instrumentation-drift error on the 5-assertion
budget test (removed `seon.fn/build-artifact`, `build-manifest`, `source-rows`).
A focused repeat passed all behavior assertions again but reported removed,
then added `seon.test.selection/reaching-tests` during concurrent adoption.
These are not clean runs. The updated settlement regression adds the budget
wake assertion (49 → 50); the opening regression adds provenance/storage
assertions (9 → 11). Final focused results follow below.


The next focused repeat (2026-09-16 19:49Z, default clock) returned a
240000-ms terminal bound for settlement, 0/0/1; its result did not count the
in-flight assertions. The subsequent budget test returned `:seon.test/unknown`
for `:seon.fn/destroys`: the freshly loaded runner required destruction facts
that the live program did not yet declare. No code ran for that refusal, and
it is not a pass. The owning test-runner/source paths were left untouched.
Publication was retried through the existing operator. No fixture rebuild,
manual test invocation, cold JVM, or alternate harness was used.


Final review boundary: the retry again refused `Source changed while
current-src was being analyzed; retry.` Default still carries source marker
`6aaaeabe-d99a-5eec-9e3e-9d254e71c9c6`; its program query finds no
`:seon.fn/destroys` owner, and its stored `start-tx` source does not yet contain
the budget listener. The live resume proof exercised the candidate's
hot-reloaded Vars on PID 53320, after the other provenance changes had reached
the development JVM; it is NOT a claim of converged final adoption. The final
50-assertion settlement run and a clean 5-assertion budget run remain unconfirmed.
The protected/concurrent boundary is the runner's dependency on declarations
in `src/seon/fn.clj` and the ongoing source publication, not an assertion
failure attributed to those files. No foreign file or session was operated.

Selected review run before the added wake assertion, in pass/fail/error order:

| Regression | Result |
| --- | --- |
| issue-settlement-runs-tests-and-derives-completion | 49/0/0 |
| issue-budget-counts-a-call-that-closes-before-the-provider | 5/0/1 (instrumentation drift) |
| issue-worker-opening-links-its-issue | 11/0/0 |
| generated-read-evidence-rejects-turn-activity | 6/0/0 |
| one-wake-cannot-open-a-second-turn-after-the-first-closes | 4/0/0 |
| issue-worker-creation-is-atomic | 15/0/0 |
| detector-only-issue-starts-and-settles-from-its-subject | 10/0/0 |
| started-issue-tests-retain-historical-authority | 33/0/0 |

Static verification: clj-kondo reported 0 errors across the seven edited
Clojure implementation/test files (42 existing warnings outside the revised
forms); `git diff --check` passed. The replayable probe and screenshot are
committed. Own CLI shells and headless Chrome ended; own scratch was removed.
No test JVM, gate, platform gate, restart, reset, or foreign-session operation
was performed. PERSONAL REVIEW HOLD remains in force; orchestrator review
must precede any gate, and the pending adopted-definition checks are explicit.
