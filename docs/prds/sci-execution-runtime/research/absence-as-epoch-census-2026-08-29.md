---
type: research
status: complete
tags: [research, time, database]
---

# Absence-as-epoch census — 2026-08-29

## Verdict

At HEAD `d3b93f6249dd0ebe7312ee2d9ed8a2b243aa70b1`, the current `src/`
census has **0 MEMBER sites**. The reference defect in
`seon.schedule/fire-due!` is now **GUARDED** by commit `d38e3e093`: a task
without fire history measures dueness from the transaction that asserted its
own identity, not from the epoch (`src/seon/schedule.clj:217-235,567-640`).

The remaining epoch-like values have one of two meanings: an ordering identity
for an object that genuinely has no earlier revision, or an explicit retention
policy that keeps all history. Other latest/age readers require a real dated
fact or represent absence explicitly.

## Review constraint

> **A derived time reference may never precede its subject's identity-creation transaction: when the prior event is absent, floor the reference at that transaction's `:db/txInstant`, derived from the identity datom and never stored.**

The phrase *its subject* matters. A store-wide retention policy, a first
projection into an uninitialized value, or a query explicitly requesting the
whole inbox does not invent a subject age and therefore does not need a
creation floor.

## Scope and method

I read the repository instructions and the applicable
`data-oriented-clojure`, `datahike`, and `clojure-testing` skills before the
source review. This was a static source and dependency census only: no cluster
was started, stopped, reset, or mutated, and no test was run.

The search covered Clojure production sources under `src/` for explicit epoch
and minimum sentinels, date arithmetic, `latest`/`recent`/`stale`/`overdue`/
`age` names, Datalog `min`/`max` aggregates, temporal database operations
(`history`, `since`, and `as-of`), transaction instants, and fallback branches
around those operations. Each match was then followed through its query and
consumer; ordinary numeric counts and limits were excluded only after that
inspection.

Classification means:

- **MEMBER** — empty subject history produces behavior as though the subject
  existed at the epoch, or otherwise turns an unavailable prior event into an
  unbounded time window with the wrong behavior.
- **CORRECT-BY-MEANING** — the empty branch intentionally means all history,
  the first revision, or zero prior occurrences; there is no created subject
  whose age is being invented.
- **GUARDED** — the query requires a real dated fact, absence is represented
  explicitly, or a current-basis fallback is structurally tied to a value
  observed at that basis.

### Dependency ledger

| Boundary | Dependency evidence | First-party seam |
| --- | --- | --- |
| Subject creation instant | A Datahike datom carries its transaction id, whose transaction entity carries `:db/txInstant`; the scheduler joins those two facts directly. | `src/seon/schedule.clj:217-235` |
| Cron reference evaluation | cron-utils computes the latest nominal instant at or before the supplied reference and returns no value when none exists. | `src/seon/schedule.clj:177-193` |
| Temporal reads | `seon.db` refuses `history`/`since`/`as-of` on a database without a temporal index rather than returning an empty history. | `src/seon/db.clj:1283-1304` |
| Store collection cutoff | Datahike's `remove-before` controls how far ancestry remains reachable; its epoch default means **no snapshot erasure**, while branch heads are always retained. The independent safe point bounds what the sweep may delete. | `reference-code/datahike/src/datahike/gc.cljc:22-42,83-120,132-167`; `src/seon/cluster/registry.clj:503-530` |

## Classified census

| Site | Class | Empty-history scenario and verdict |
| --- | --- | --- |
| Scheduler latest nominal and first fire | **GUARDED** | `latest-nominal-at-or-before` may return nil, and `fire-due!` first requires a nominal. With no fire row, it compares that nominal with `task-created-at`; a nominal before creation cannot fire (`src/seon/schedule.clj:177-193,217-246,600-612`). This is the repaired reference member. |
| Run opening basis | **GUARDED** | A run without its `opened-at` datom returns a typed `missing-opening-datom` error; it never calls `as-of` with nil or zero (`src/seon/cluster/run.clj:258-279`). |
| Dead-process run recovery | **GUARDED** | Recovery is fact-based, not clock-based: open runs whose holder is absent from the live-process set are interrupted immediately. Even a run with zero receipt history gets its own interruption stamp, so missing receipts cannot look like a normal close (`src/seon/cluster.clj:1716-1757`; `src/seon/cluster/run.clj:338-370,1728-1806`). |
| Maintenance latest receipt and latest terminal instant | **GUARDED** | A task with no receipt remains an entry with no receipt facts and is classified `:not-run`; if no task has run, the renderer says exactly “no task has run yet.” It calls `latest-at` only after at least one receipt selected through a required `started-at` fact (`src/seon/maintenance.clj:239-273,309-335,389-408`). |
| AI token calibration prior | **CORRECT-BY-MEANING** | A model with no usable usage observations selects the explicit measured shipped prior (3.2 characters/token, 17 recorded samples), not a zero-valued or timeless “observation” (`src/seon/ai/tokens.cljc:45-64,66-114`; `src/seon/cluster/prompt.clj:74-120`). |
| AI “latest” model gauges | **GUARDED** | Latest latency, speed, and use time are rendered only when their current no-history gauge facts are present; a model with no observation gets no invented latest section. Gauge writes happen only after an actual settled attempt (`src/seon/ai.clj:194-259,967-991`). |
| Effect age for pending work | **GUARDED** | The pending query requires each receipt's real `opened-at`; elapsed time is current database-basis `txInstant` minus that fact and is merely floored at zero against clock skew. With no pending receipt there is no age row (`src/seon/effect.clj:724-754,798-803`). |
| Foreground-effect feedback since the last context capture | **CORRECT-BY-MEANING** | The absent previous-capture basis falls back to transaction 0, deliberately selecting every qualifying duration the agent has not previously been shown. The first capture has no earlier feedback boundary; it does not claim that an effect existed since the epoch (`src/seon/effect.clj:765-793,810-813`). |
| Message inbox without `since` | **CORRECT-BY-MEANING** | The no-options arity explicitly means the whole current inbox; an empty inbox is `[]`. A supplied basis uses `db/since`, and a temporal-read refusal is returned rather than replaced with all history (`src/my/message.clj:73-116`). |
| `my.plan` recent completions | **GUARDED** | Only items carrying a real `completed-at` enter the ordering. No completion rows yield an empty `:my.plan/recent-completions` vector and no elision, not an ancient completion or stale age (`src/my/plan.clj:790-826,828-869`). |
| Autonomous episode window | **CORRECT-BY-MEANING** | If an agent has never answered an outside trigger, the anchor transaction is 0 and every run counts. That is the declared episode definition: all such runs are autonomous continuation, and returning zero would disable the cap for exactly that case (`src/seon/cluster/work.clj:394-433`). |
| Error recurrence “since this process started” | **CORRECT-BY-MEANING** | The window is process identity, not time. No matching error facts means zero prior occurrences and the new error is occurrence one; there is no epoch substitution (`src/seon/error.clj:778-790,887-895`). |
| Problem overview's latest error per signature | **GUARDED** | `latest` is computed only inside a non-empty `group-by` bucket made from actual error facts. No errors produce no groups (`src/seon/problems.clj:99-125`). |
| Transcript recent rows and counts | **GUARDED** | Message, receipt, input, and undisposed-run queries require their actual time attributes; empty queries contribute no candidates. Count fallbacks are cardinality zero, not a time reference (`src/seon/render/transcript.clj:156-221,241-277`). |
| Transcript entry basis | **GUARDED** | `entry-basis` reduces from 0, but it is called only for a candidate pulled from an existing entity and scans that entity's datoms. A receipt-supplied read basis wins when present; no candidate means no entry (`src/seon/render/transcript.clj:901-969`). The zero is an unreachable reduction identity for an admitted candidate, not a no-history age. |
| Generic render observation basis | **CORRECT-BY-MEANING** | If a render call did not capture a separate call basis, the value was observed during the current walk and the current database basis is exact. The fallback is current, never epoch (`src/seon/render/walk.clj:821-891`). |
| Debug view's latest captured prompt | **GUARDED** | No capture returns nil and selects a separately labelled prospective prompt; it never presents a fabricated old capture as latest (`src/seon/render/web.clj:515-530,578-584`). |
| Environment projection basis | **CORRECT-BY-MEANING** | `Long/MIN_VALUE` exists only as the ordering identity for a subset environment that has never received a projection, allowing its first supplied basis. Boot environments require their complete members; this is not a subject-age calculation (`src/seon/env.clj:101-117,225-248`). |
| Call-preparation row-family basis | **CORRECT-BY-MEANING** | With no supplied-default row facts, the row family's content revision is 0. The independently stored-in-memory `checked-through-t` begins at -1 specifically so a fresh database cannot look checked; the first row transaction then advances the derived basis (`src/seon/call_preparation.clj:63-71,272-292,354-362`). |
| Reconciliation's first assertion transaction | **GUARDED** | With temporal history disabled, the first-assertion map is empty and no existing entity becomes managed through missing provenance; an existing desired identity outside the derived managed set is refused (`src/seon/reconcile.cljc:122-143,339-390`). Absence fails closed instead of becoming transaction 0. |
| Blob reachability on a history-off branch | **CORRECT-BY-MEANING** | `db/history` explicitly refuses a non-temporal database; registry and collection evidence then query its current value. With `keep-history? false`, no older temporal datoms exist to preserve. This fallback is current-only by the branch's storage contract, not “all time since epoch” (`src/seon/db.clj:1283-1304`; `src/seon/cluster/registry.clj:345-363`; `src/seon/operator.clj:689-707`). |
| Store footprint observations | **GUARDED** | Collection records immediate before/after object counts and bytes. Reclaimed bytes are the non-negative difference between those paired observations; there is no prior-maintenance timestamp or absent historical sample (`src/seon/operator.clj:653-659,800-855`). |
| `registry/collect!` default `remove-before` | **CORRECT-BY-MEANING** | `(Date. 0)` is correct here. Datahike defines it as retaining all snapshot ancestry—“no erasure”—while still retaining branch heads and applying the separate sweep safe point. It therefore collects nothing *because of age* rather than treating the store as overdue (`reference-code/datahike/src/datahike/gc.cljc:83-120,132-167`; `src/seon/cluster/registry.clj:503-530`). Seon's production dry-run, collection, and verification calls all pass an explicit current date (`src/seon/operator.clj:765-771,800-838`). |

## Shared-helper recommendation

Do **not** hoist `seon.schedule/task-created-at` to `seon.db` yet.

The census found one current consumer and no repeated per-site fix to replace.
A generic `subject-created-at` would also overstate the semantics currently
proved by the query: it returns the transaction instant of the task's current
identity assertion. A reusable database API would have to settle whether
“created” means the earliest historical assertion, the current identity
assertion after retract/reassert, or the fact visible at an `as-of` database,
and how to choose among entities carrying more than one identity attribute.
Hoisting that ambiguity now would make the name broader than its guarantee.

On the second real consumer, hoist a narrow operation to `seon.db`, because
that namespace owns Datahike reads and temporal database semantics. Prefer an
honest name such as `identity-asserted-at`, take an explicit database value and
lookup ref, join the named identity datom's transaction to `:db/txInstant`, and
return a typed error when either fact is unavailable. At that point it should
replace `seon.schedule/task-created-at` and any new no-prior-event floor; it
should not replace reconciliation's earliest-assertion provenance query or
global empty-family revision identities, whose meanings differ.

## Regression sketches for MEMBER sites

There are no current MEMBER sites, so no new regression is required by this
census. The repaired reference class already has the right regression shape:
create a task after a matching nominal instant, give it no fire history,
observe after that old nominal but before the first post-creation nominal, and
assert zero handler calls and zero fire facts
(`test/seon/schedule_test.clj:147-163`).
