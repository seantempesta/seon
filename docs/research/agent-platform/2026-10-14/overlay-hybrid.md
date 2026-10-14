---
title: "Hybrid: a branch's program context is derived from its previous one, by content"
type: research
status: hybrid of overlay-opus.md (64d645732) and overlay-astra.md (1dace59dc); design only, nothing installed
date: 2026-10-14
lane: overlay-hybrid
basis: "HEAD 6c505ca3f; default pid 88215 (archive 243683f1d) read in JVM mode only; src/seon/sci/eval.clj as read at HEAD"
---

# Overlay hybrid

Problem (install-row-reruns.md, 8ad2a751b): a branch's program context (`seon.sci.eval/program-context`) is one
`seon.cache` entry per program content, but on a miss `seon.sci.eval/derive-program-context` forks the **loaded base**
and `seon.sci.eval/interpret-overlay!` interprets the **whole plan**, so every program change re-runs every branch value
row's init (`install-row!` 11–17 per unrelated `defn`, 5 ms and 9 MB per branch row, the same refusal recorded 2–3 times
per change). Owner direction (gemini/handoff.md §2): everything immutable is shared, across SCI contexts and branches,
and work is incremental and minimal. This note takes the best of the two independent plans and states the one design.

## 1. Agreement, differences, what both missed

### Agreement (adopted without change)

- Derive a changed program's context from the **previous frozen program context** P (a `sci.core/fork` of a frozen
  value is cheap and writes nothing shared: both measured P unchanged after deriving q from it), interpreting only the
  changed rows and what reaches them. Keep `program-content-key` exactly as it is: content only, so equal programs on
  two branches still share one value; a predecessor is a derivation input, never part of the key.
- Δ = the rows whose definition digest differs between P's plan and q's (`seon.program/definition-digest`, which
  includes the namespace resolver context). Value rows and macros in `seon.fn/reverse-closure` of Δ always re-run
  (Opus measured v1, v3, v4 re-run after `f1` changed; Astra's `value` too).
- Interpretation and recording are two defects with two fixes; `seon.sci.eval/base-ctx`'s direct
  `record-acquisition-refusals!` beside `acquire!`'s `record-refusals-once!` is a second producer of one fact and is
  deleted; carried refusals are data, never replayed as new work.
- Option 3 of install-row-reruns.md: the defining reply carries its own rows' install refusals.
- No new cache kind, atom or stamp; never scan `seon.cache/retained` for a parent; never use the live agent context as
  the parent (86430115b, ab8f218d4); an evicted or absent predecessor falls back to today's derivation from the base.
- Plan derivation (`derive-overlay-plan`, Astra: 6.80 ms / 10.7 MB on three definitions) is a second measured slice.

### Differences, decided

| # | Question | Opus | Astra | Decision and measured reason |
|---|---|---|---|---|
| D1 | Interpreted functions in reverse-closure(Δ) | no work: Vars are late-bound through the executing context (g1 saw f1 9 inside a fork) | re-interpret them: a fn called with no active context runs in its **defining** context (`:without-context 1`) | **Astra's rule, Opus's mechanism.** `sci.impl.fns/in-defining-ctx` (reference-code/sci/src/sci/impl/fns.cljc:40) binds the defining context on a context-less call, so a P-interpreted caller kept as is would call P's callee from a raw thread, a Flow proc or a host callback; the E5 task (`seon.sci.eval/a-settled-program-context-equals-its-rebuild`, resources/seon/tasks/seon.sci.eval.edn) already names that probe (result/e159d4b5702f4). The fix is to remove them from `already` so `interpret-overlay!` treats them as it treats compiled callers today: `bind-lazy-members!` rebinds each to a `lazy-root` in q (one Var bind now, interpretation in q on first call), keeping eager installs at Δ plus value rows (Opus: 1 row, 7.9 ms; Astra eager: 3 rows, 15.3 ms; today 32–50 ms). The raw-thread proof row decides lazy; if it fails, eager is the fallback and still halves today's cost. |
| D2 | A changed row's old root | reinstall over it | `sci.impl.vars/unbind` (SciVar protocol, reference-code/sci/src/sci/impl/vars.cljc:109) the selected Vars in the child first, keeping Var identity | **Unbind.** `install-selected!` copies inherited roots into the new generation (`bind-root!` loop, "Copy inherited Vars into this generation") before analyzing; without unbind a Δ row whose install **refuses** leaves P's root callable under q's content key (a stale Var by another route), and `defonce` keeps the old value. Count: 80 `defonce` rows on main, all file-derived (compiled, never interpreted in an overlay), 0 agent-authored; the refused-install case is every install refusal, so it decides. |
| D3 | Inits that read the database | run once per content (Clojure load semantics) | capture read evidence; re-run when the read changes (phase) | **Once per content; no read-evidence phase.** Phase 1 removes the ambient read (`seon.db/db` refuses in every derivation); the overlay path captures no read evidence today (dig §2, verified); the owner ruled a REPL def is a file's def (de1f48c23); no case is observed where an init reaches a database through an explicit host connection. Building currency checks before one is observed is a fence. Owner question 1 states the semantic. |
| D4 | What q weighs | its own interpreted rows only (delta) | the cumulative branch structure relative to the loaded base | **Cumulative (today's `overlay-weight`).** `interpreted-count` is counted after the merge into `::interpreted`, so q already pays P's rows plus its own; a delta charge undercharges when P is evicted and q survives. The cache is at 511.8 of 512 MB (runtime_status, this note's basis); undercharge is the only unsafe direction. Astra's eviction-chain row joins the existing weight test as a measurement, not a phase. |
| D5 | Finding P | the key the agent's context holds, two `seon.cache/present` probes | pass the frozen value the caller holds | **Key probe**, with Astra's constraints. A presence probe does no work and pins nothing beyond cache policy; the one-derivation-per-key `seon.cache/lookup` already joins the agent's acquisition and the test world's on one q. Correction to Opus (see "missed" (a)): the key held on the snapshot today is `program-key-input`'s, not the `::program-key` cache's. |
| D6 | Recording identity | `[::produced-in row target]` at the acquisition owner | an idempotent observation identity at the error writer (`seon.error/commit-tx` contract) | **Opus now, Astra at the errors cut.** The production is a content-keyed event that happens once per key, so the acquisition owner knows it; the writer-level identity is an `seon.error` contract change that belongs to errors-cut-plan.md and is filed there, to be built when a double count from `::refusal-recording` eviction is observed. |
| D7 | Removals and macros | fall back to the base derivation | include old and new reach; table rows for macro expansion | **Fallback**, counted first: main holds 0 agent-authored macros (re-run read-only, JVM, this note); removals cannot be counted on main (no delete identity attribute exists in `seon.program/identity-attributes`; a removal is a row retraction). Owner question 2. |
| D8 | Plan reuse | later | a phase: held plan plus the committed change identities, zero `derive-overlay-plan` against the loaded base | **A phase after the main win**, measured on a 20-row branch first; it touches `seon.turn`, `seon.test`, `seon.cluster.agent` (held by lane-lifecycle-branch today). |

### What both missed

(a) **The predecessor key is not the cache key.** `base-ctx` stores `program-key-input` on the snapshot
(`[store revisions dial-revisions loaded-basis]`); `program-context` caches `::program-key` under
`[store revisions dial-revisions (commit-id supplied-loaded)]`. Opus's two presence probes need the second; the
snapshot must hold the **content key** of the value it was forked from (`::program-content-key`, set in `base-ctx`
from `program-context`'s result), one probe, no translation.
(b) **This is E5.** `seon.sci.eval/a-settled-program-context-equals-its-rebuild` already exists with the raw-thread
proof; the hybrid implements it rather than filing an umbrella. Its text deletes `acquire!`, `acquired-database?`,
`loaded-program-current?`, `transfer-evaluated-roots!`, `program-equal`; each deletion waits for the commit converting
its last caller (Astra's caution), so the phases below delete only what they convert.
(c) **`::interpreted` is the wrong shape.** `interpret-overlay!` computes `already` from a **set** of symbols, which can
only mean "installed at some time": that is why both prototypes had to subtract Δ from it by hand. Holding
`::interpreted` as `{sym definition-digest}` lets `interpret-overlay!` derive Δ itself (a symbol is current when its
held digest equals the database's) and makes the rule local to the one function that applies it.
(d) **Frequency can only be counted on agent branches.** After root's reset main holds 0 agent-admitted function rows,
so "how often removals and macros occur" is answered by a day's branches (`my.agent/branches`), not by main.
(e) **The defining request's derivation pre-warms the next request**, so option 3 costs nothing net: the Δ derivation
moves from the next request to this one.

## 2. The derivation design

### 2.1 Inputs and identity

A program context is a function of content only: `[store loaded-commit]`, the plan digest (held definition digests,
namespace rows by name/source/bindings, test rows), `projection-delta` and `armed-dials`: `program-content-key`,
unchanged. It is **not** a function of the deriving thread, connection, branch or agent: `derive-base-ctx` and
`derive-overlay-ctx` run with `seon.db/*conn*` and `seon.db/*read-database*` bound to nil, so `(def d (seon.db/db))`
refuses identically everywhere (Opus finding 1: derived on root's thread L2 installed, inside an agent request it
refused, and the cache kept whichever came first).

Derivation input (not key): the predecessor P, found by the content key the acquiring context's snapshot holds. P is
reusable when `seon.cache/present` answers it, its `cluster-base-key`, `projection-delta` and `armed-dials` equal q's,
and Δ (below) contains no removed row or namespace and no macro. Otherwise today's derivation from the loaded base.

### 2.2 Which rows re-interpret

`derive-overlay-plan` returns `::held {sym digest}` (the digest already computes from it) and P keeps its plan on
`::acquisition`. From P to q:

| Rows | Work | Why |
|---|---|---|
| Δ: digest differs or row new | unbind (if P holds the Var), then install now | the content changed; the digest includes resolver context, so an alias/refer change puts the row in Δ |
| value rows and macros in reverse-closure(Δ) that P interpreted | unbind, install now | an init evaluated its callees at install; a macro's expansion is baked into callers |
| interpreted functions in reverse-closure(Δ) | removed from `already`; `bind-lazy-members!` rebinds to a `lazy-root` in q | a context-less call runs in the defining context (D1) |
| compiled callers in reverse-closure(Δ) | as today (`bind-lazy-members!`), unless P already did | unchanged path |
| tests in reverse-closure(Δ) that P interpreted | removed from `already-tests`, re-enter on run | same as callers |
| everything else | the identical root from P | an unchanged init runs once per content of itself and its declared callees |

Dependents come only from declared edges (`seon.fn/reverse-closure` over `:seon.fn/calls`, value-def inits included),
never symbol scanning.

Fallbacks (derive from the loaded base, today's one path, no third mechanism): a removed row or namespace (callers
analyzed against a Var the base would refuse to resolve), a changed or reverse-reached macro, a different loaded commit,
projection delta or armed dials, no held or evicted P.

### 2.3 Recording once per production

Each refusal carries `::produced-in`, the content key of the derivation that ran the row, set where it is made in
`interpret-overlay!`; carried refusals keep theirs. The recording key in `record-refusals-once!` becomes
`[produced-in row target]`. A re-run (an input changed) is a new production and records once; a carried refusal records
nothing. `base-ctx` no longer records; its callers holding custody record through `record-refusals-once!` as `acquire!`
does. The error owner's count then means what it says: how many times the init really ran and refused.

### 2.4 Option 3: the defining reply

A program-changing request derives q before its reply (the test step does this today when a proof is held; when none
is, the request derives it anyway: it is the next request's work moved earlier). Refusals whose `::produced-in` is q's
key and whose row is among the request's changed rows ride beside the value in that reply's errors through the one-path
fold. The original value stays; nothing is re-interpreted to produce the diagnostic.

### 2.5 Correctness: where a stale Var could be visible, and why it cannot be

A stale Var (F57 class: a context answering an old body under a key that claims the new program) needs a root that was
produced against inputs the new content does not have. Each route and what closes it:

| Route | Closed by |
|---|---|
| the changed row itself kept because it is in `::interpreted` (both prototypes reproduced: 0 installs, `f1` still 5) | `already` excludes Δ (and (c): `::interpreted` holds digests, so currency is computed, not assumed) |
| a P-interpreted caller called with no active context runs P's callee (Astra: `:without-context 1`) | callers in reverse-closure(Δ) rebind in q (D1); proof row: a raw `Thread` call answers q's binding |
| an init or macro expansion that consumed the old callee | value rows and macros in reverse-closure(Δ) re-run |
| a refused reinstall leaving P's copied root; `defonce` keeping P's value | unbind before install (D2); a refused row is unbound in q exactly as the base derivation never binds it |
| a caller analyzed against a removed Var; a changed macro's baked expansions | fallback to the base derivation |
| a context that depends on who derived it (thread bindings) | phase 1: no binding is visible in a derivation |
| a different loaded commit, projection or dials | part of the content key; not a reusable P |
| P's refusal recorded again as if produced | `::produced-in` |

Path dependence that remains, stated: two histories of one content differ only in which unchanged callers are lazily
interpreted versus compiled (same behavior: interpreted source equals compiled source at the loaded commit) and in an
init that reads host state through an explicit host call (Clojure's semantics; owner question 1). Both are already true
of today's cache.

### 2.6 Cost

| Operation | Today | Proposed |
|---|---|---|
| program-changing request, overlay derivation | whole plan: ≈5 ms, 9 MB per branch row (36–74 ms, 77–113 MB at 9–12 rows) | ∝ Δ + value rows/macros in reverse-closure(Δ) + one Var bind per reached caller: 7–13 ms, 10–19 MB for one row; 15–30 ms with 2–3 dependents |
| finding P | — | one `seon.cache/present` probe |
| plan derivation | reads ∝ authored rows; 6.8 ms / 10.7 MB at 3 rows | unchanged until phase 5 |
| unrelated defn: refusal recordings, failed inits | 2–3 and 4 | 0 and 0 |
| non-program request | cache hit | unchanged |
| defining reply | refusal one or more replies later | in the reply; the next request hits |

## 3. Phases

Each loadable alone; each red proof is a deftest asserting data, and each cost phase carries a
`seon.profile/measure` assertion on the end-to-end probe (`seon.mcp/call` requests on a branch, parent and change on a
quiet JVM). Files unless noted: `src/seon/sci/eval.clj`, `test/seon/sci/eval_test.clj`,
`test/seon/sci/acquisition_cost_test.clj`.

| # | Phase | Red proof | Cost per change | Deletes |
|---|---|---|---|---|
| 1 | **Derivation free of the deriving thread's bindings**: `derive-base-ctx` and `derive-overlay-ctx` bind `seon.db/*conn*` and `seon.db/*read-database*` to nil | `seon.sci.eval-test/a-program-context-does-not-depend-on-who-derives-it`: deriving a branch holding `(def d (seon.db/db))` and `(def L2 (seon.ref/brief d …))` under a bound root connection yields an acquisition whose refusal set equals the unbound derivation's (today: L2 installs under the binding, refuses without) | none | nothing |
| 2 | **Δ derivation from P**: `::held` on the plan and on P's `::acquisition`; `::program-content-key` on the snapshot (missed (a)); `::interpreted` as `{sym digest}` (missed (c)); `interpret-overlay!` excludes Δ and its reached interpreted members from `already`/`already-tests`; unbind before install; reusability test and fallbacks of §2.1–2.2; `derive-program-context` takes the predecessor | cost: `seon.sci.acquisition-cost-test/a-change-reinterprets-only-its-dependent-declarations`: on a branch with ≥ 8 rows, an unrelated `defn` through `seon.mcp/call` makes `(seon.profile/calls-of m 'seon.sci.eval/install-row!)` ≤ 2 (today 11–14) and the request's `derive` + `test-step` ms and alloc below the parent's on the same probe. Behavior table (green today on the base path, guards the stale case): after `(defn f1 [] 8)`, `(defn g1 [] (+ 100 (f1)))`, `(def v4 (g1))`, `(defn f1 [] 9)`, `(defonce o 2)` over `(defonce o 1)`, a Δ row whose init refuses: q's values equal a cold derivation's; P still answers its old values; `caller` called from a raw `Thread` with no context answers q's binding (E5 row); the refused row is unbound in q; `::interpreted-count` after a 10-edit chain equals the cold derivation's | derivation ∝ Δ + reached value rows; one Var bind per reached caller | `derive-overlay-plan`'s opaque `::digest` input list (becomes `::held`); the whole-plan re-interpretation on a covered change |
| 3 | **Record once per production**: `::produced-in` on refusals; `record-refusals-once!` keyed `[produced-in row target]`; `base-ctx` stops recording (its custody-holding callers already go through `record-refusals-once!` via `acquire!`; the other `base-ctx` callers in the dig §1 are converted in this commit) | `seon.sci.acquisition-cost-test/an-unrelated-defn-leaves-a-value-rows-refusal-count-unchanged` (the 8ad2a751b task's proof): define `d` and `L2`, read L2's `:seon.error.occurrence/count`, `(defn other [] 2)`, count unchanged and `install-row!` on L2 zero (today +2 to +3). Second row: the agent's acquisition and the test world acquiring one q record one occurrence | 0 recordings per unrelated change | `base-ctx`'s `record-acquisition-refusals!` call; the `[program target]` key and its "an evicted entry records again" note. Retires task `seon.sci.eval/a-program-change-reruns-every-branch-value-rows-init` |
| 4 | **Defining-reply delivery** (owner question 3): the program-changing request derives q before its reply; its own rows' refusals ride in the reply's errors | `seon.repl-test/a-defs-install-refusal-is-in-its-own-reply`: the reply of `(def L2 …)` carries L2's `:seon.sci.eval/interpretation-error` beside the value; the next request derives nothing (`interpret-overlay!` 0 calls) | one Δ derivation moved earlier; the next request hits | the one-reply lag |
| 5 | **Plan from P's plan** (D8): the request's committed change identities (the writer's transaction report) update `::held`; `derive-overlay-plan` against the loaded base runs only with no P | `seon.sci.eval-test/a-settled-program-context-equals-its-rebuild` (E5) gains: `derive-overlay-plan` with the loaded commit as base 0 calls on a covered change and its following request; definition-digest reads ∝ Δ; `seon.profile/measure` on the same end-to-end probe below phase 2's numbers on a 20-row branch (measure first: 6.8 ms at 3 rows; the phase is dropped if the 20-row plan derivation is under 2 ms) | plan ∝ Δ | whole-plan `function-symbols`/`namespace-digests` reads per change; `acquire!`'s derivation against the loaded base when a P exists (the E5 deletions follow their last callers here) |

Phases 2 and 3 may land in one commit if root prefers: 3's proof stays red until both are in. Phase 1 alone is a
correctness fix and lands first (its proof is red today).

## 4. Narrow check

(a) **Live-system claims and their evidence.** Whole-plan re-interpretation and 11–17 installs per unrelated defn:
overlay-opus.md §1 table; overlay-astra.md "Complete requests" table (9–10 installs). 5 ms / 9 MB per row:
overlay-opus.md §1 second table. Δ prototype 1 row 7.9 ms / 13 MB, fn with 2–3 dependents 18–30 ms: overlay-opus.md §2;
Astra 7.2 ms unrelated, 15.3 ms changed callee with 3 installs: overlay-astra.md prototype tables. Thread dependence of
the cached value: overlay-opus.md §3 finding 1 (observed). Context-less call uses the defining context:
overlay-astra.md `result/e3e462826d122`, and `in-defining-ctx` read at reference-code/sci/src/sci/impl/fns.cljc:40 in
this note. `defonce` keeps the old root on a fork: `result/ec62877119579`. Refusal count 1 → 3 after an unrelated defn:
install-row-reruns.md and overlay-astra.md `result/e0772eb89d3dc`. Re-run read-only here (JVM mode, root, one form,
59 ms): agent-admitted fn rows on main 0, agent macros 0, agent value rows 0, `defonce` rows 80 (all file-derived),
no delete identity attribute in `seon.program/identity-attributes`, 2 `::program-context` entries retained, cache
511.8 of 512 MB. Source facts verified at HEAD in this note: `interpret-overlay!` removes `already` from `overridden`
(the stale-row skip); `install-selected!` copies inherited roots with `bind-root!` before analysis; `derive-overlay-ctx`
sets `::custody {}` and binds no dynamic Var; `base-ctx` calls `record-acquisition-refusals!` directly and `acquire!`
calls `record-refusals-once!`; `program-key-input` and the `::program-key` cache key differ in their fourth element;
`sci.impl.vars/unbind` is a SciVar protocol method; `seon.cache/present` is a presence probe.
Unverified and stated so: removal frequency (needs a day of agent branches); whether `lazy-root` promotion from a
context-less first call lands in q's frozen value (phase 2's raw-thread row decides lazy versus eager).

(b) **Deletions in the commit converting their last caller.** Phase 2 replaces the plan's `::digest` input list with
`::held` in the same function. Phase 3 deletes `base-ctx`'s recording while converting every `base-ctx` caller holding
custody (`seon.cluster.agent/acquire-context!`, `seon.turn/prove-turn` sites, `seon.test` sites: dig §1) to
`record-refusals-once!`. Phase 5 deletes `acquire!`'s base-plan derivation when a P exists, and the E5 deletions
(`acquired-database?`, `loaded-program-current?`, `program-equal`) only once `acquire!` no longer reads them. Nothing
already deleted (`transfer-evaluated-roots!`, `base-bindings`) is listed again.

(c) **Red proofs named.** Phase 1 `seon.sci.eval-test/a-program-context-does-not-depend-on-who-derives-it`; phase 2
`seon.sci.acquisition-cost-test/a-change-reinterprets-only-its-dependent-declarations` (cost) and its behavior table;
phase 3 `seon.sci.acquisition-cost-test/an-unrelated-defn-leaves-a-value-rows-refusal-count-unchanged`; phase 4
`seon.repl-test/a-defs-install-refusal-is-in-its-own-reply`; phase 5 the E5 proof
`seon.sci.eval-test/a-settled-program-context-equals-its-rebuild` extended. Existing tests kept in reach:
`seon.sci.acquisition-cost-test/a-second-acquisition-of-an-unmoved-branch-interprets-nothing`,
`seon.sci.eval-test/equal-programs-on-two-branches-share-one-program-context`,
`seon.sci.eval-test/a-program-context-weighs-its-own-declarations` (gains the eviction-chain row, D4), both
`seon.test.redefined-callee-test` stale-body regressions.

## 5. Owner questions (three)

1. **Semantics of an init.** An unchanged value row's init runs once per content of itself and its declared callees
   (clj-reload semantics: a dependent init re-runs, an unrelated one never), with phase 1 making the result independent
   of who derived it; an init that reads host state through an explicit host call keeps Clojure's file-load semantics
   (no read-evidence currency check until a case is observed). Options: (a) accept, recommended; (b) Astra's
   read-evidence currency now (O(R) per acquisition, a new capture on the overlay path, no observed case);
   (c) refuse inits that call host state (a fence on every computed def).
2. **Removals and macros fall back to the full derivation** until counted frequent on a day's agent branches.
   Options: (a) accept, recommended; (b) re-analyze reverse-closure(removed) on P now (Astra's old-plus-new reach),
   untested and unmeasured; (c) refuse `ns-unmap!` on an overlaid branch (a fence).
3. **Option 3, delivered in the defining reply** (phase 4), the program-changing request deriving q before it replies.
   Options: (a) yes, recommended: no net cost, the agent acts at the moment it can; (b) deliver on the next request as
   today; (c) deliver only when the test step already derived it (unassigned agents keep the lag).

## 6. Where it sits in the schedule

TASK-CHAIN (docs/seon/schedule/cards/TASK-CHAIN.md §7) is the path. This design is the cost of **every** agent's
program change, every value row's init and refusal on every branch, and the live-chain runs of S4b/S5 pay it on each
`defn` of each step; S3a's runs would produce the 1 → 3 occurrence counts as noise in every context block (context
sanity defect 2 lists each red once; repeated refusals would reappear). Proposal: phases 1–3 **before S3a**, beside
S1b/S1c/S2 (they are file-disjoint from them, below), as the E5 task already filed; phase 4 with S3a-2 (the block a
reply carries is rendered after its test step: the same reply-order owner); phase 5 after lane-lifecycle-branch
releases `seon.cluster.agent`.

Files: phases 1–3 own `src/seon/sci/eval.clj`, `test/seon/sci/eval_test.clj`,
`test/seon/sci/acquisition_cost_test.clj`, `resources/seon/tasks/seon.sci.eval.edn`: none is named by S1b-1
(`seon.task`, its schema and tests), S1b-2 (`seon.task`, `seon.cluster.registry`, `seon.cluster.boot`), S1c
(`seon.task`), S2a/S2b (`seon.test`, `seon.test.runner`, `seon.cluster.source`) or S3a (`seon.context`, `seon.turn`,
`seon.task`, `seon.cluster.db-flow-test`), and no live lane holds them (`bin/lane status` at this note's basis:
db-caches, lane-lifecycle-branch, perf-analyzer, perf-request, s1b-1, s3d-1a). Phase 3's caller conversion touches
`src/seon/cluster/agent.clj` (lane-lifecycle-branch) and `src/seon/turn.clj`, `src/seon/test.clj` (S2a/S2b, S3a-2):
one-line patches to those owners. Phase 4 shares `src/seon/turn.clj` with S3a-2 and `test/seon/repl_test.clj`;
phase 5 shares `seon.turn`, `seon.test`, `seon.cluster.agent` with S2a/S3a and lane-lifecycle-branch, so it runs
after them. Model: Opus for phases 2 and 5 (the one REPL path's acquisition semantics), Sonnet for 1, 3 and 4 once 2 is
in. Review: fresh Opus plus Astra, each finding reproduced before it is fixed.
