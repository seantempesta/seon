---
title: "Reach reply: a seon.db defn on an SCI branch"
type: research
status: plan (lane reach-reply, 2026-10-15, SCI mode as agent `reach-reply`; default OOMed mid-lane)
created: 2026-10-15
---

# Reach reply: a `seon.db` defn on an SCI branch (lane reach-reply, 2026-10-15)

Context defect (plan.md §0). HEAD f5aaad218, agent `reach-reply`, SCI mode, default under load.

## Broken first

1. **Default died of `OutOfMemoryError: Java heap space` during this lane's second probe** (`seon.log`, pid 84529,
   ~02:43:05Z, heap dump `data/heap/java_pid84529.hprof`, 4.4 GB). The probe was one real-body `defn` of
   `seon.db/database-value?`. Its automatic reaching run allocated 15.5 GB in 12.3 s and left
   `seon.cluster.turn-test/a-refused-definition-stays-in-its-agents-defs` live past its bound ("thread 356 remains
   live"). The log already showed SEON MEMORY PRESSURE before the run, so the reaching run was the last straw, not
   the only cause. **RESTART NEEDED.** No further REPL measurements were possible, so items 2 and 4 below are reads
   of the code. Their counts need the probe in §Probe after the restart.
2. The unchanged-body control still costs 4.1 s and 5.7 GB per request, with no tests at all (below). The cost is in
   the `defn`'s evaluation and recording, not in test reach.
3. Every reply delivers a recorded error, `seon.sci.eval/acquisition-refusal` "Program row could not be installed"
   (signature `c1a9ebcf…`, branch `:agent-0e582f5a0bb7`, `:at` 02:42:28). Its count went from 14 to 30 between two
   of this agent's requests. The error names no row and gives no requery: it is a context defect of its own (the
   error should name the row and its acquisition evidence).

## Measured (before)

| probe | reach | ran / reused | ms (eval / request) | alloc MB (eval / request) | calls | reply |
|---|---|---|---|---|---|---|
| `(seon.context/current-state)` (first use) | — | — | 46 / 1,235 | 62 / 1,750 | 11,746 | ~150 tokens |
| `defn seon.db/database-value?`, body unchanged | 0 (no test summary) | 0 / 0 | 2,177 / 4,106 | 1,698 / 5,693 | 524,042 | ~350 tokens (findings + the foreign error) |
| same `defn`, body `(boolean (db.utils/db? value))` | `:reaching-count 2376`, `:nearest 187` | 187 selected; 11 red, ~170 pending at the step bound; 0 reused | 1,319 / 12,320 | 1,539 / 15,503 | 2,540,547 | **~6,000 tokens**: 11 red names, 11 failure maps of ~3.5k chars each, 8 errored names and ~170 pending names (the printer elided 50 of them) |

## Answers

**Q1. Should an unchanged-body re-defn reach anything? No, and today it does not.**
`seon.test/request-changes` keeps only `behavior-changed` symbols, so an equal behavior digest yields `[]`, and
`seon.turn/test-step` returns nil when nothing is changed or stale. Measured: the reply had no test summary. The 4.1 s
and 5.7 GB belong to evaluating and admitting a definition in a 6,522-line namespace (eval 2.2 s / 1.7 GB) plus
recording. That is plan.md §0 C13's class: file it there, not as reach.

**Q2. Is 2,376 the correct reach for a real change to `seon.db/database-value?`? No.** `seon.fn/reverse-closure`
(through `seon.fn/closure-walk`) walks every caller, host-bound ones included. A branch, though, never changes the
platform: `seon.sci.eval` (the `host-bound-row?` / `compiled?` branch of row installation, and
`seon.sci.eval/interpret-overlay!`'s "a host-bound caller keeps the compiled Var") binds every unchanged
host-bound caller to its compiled Var. A compiled caller calls the compiled `database-value?`, never the branch's.
So a test that reaches the change only through a host-bound caller runs code that cannot see the change: it proves
nothing about the change, and its reach digest should not move. The correct reach is the closure walk that **does not
extend through host-bound callers**. A host-bound caller is itself reached and interpreted only when the branch
changed it, and then `seon.turn/platform-proof` names its tests. The same cut also shrinks what
`interpret-overlay!` interprets: interpreting a non-host-bound caller above a compiled one is useless work, and it is
exactly what produces the reds below.

- Owner of selection: `seon.fn/closure-walk`, which serves `seon.fn/reverse-closure`, `seon.fn/gate-sets` →
  `seon.test/reaching` and `seon.turn/partial-reaching`'s `:seon.turn.loop/reaching-count`. One edge rule fixes all
  three: a caller entity whose `:seon.fn/host-bound?` is not `false` (`seon.sci.eval/host-bound-row?`'s rule) joins
  `affected` but is never added to the frontier. Tests calling it directly are not selected unless they also call an
  interpreted walked function.
- This is the open task `seon.fn/a-file-proof-two-compiled-callers-away-never-sees-the-branch-redefinition`, whose plan
  asks for exactly this reconciliation. Its existing proofs are `seon.fn-test` line 1110 and
  `seon.sci.branch-execution-test` line 658. Do this task; do not file a new one.
- What the agent pays after the cut: only the selected tests whose reach digest changed. Reuse by reach digest already
  works (`:seon.test/unchanged` results answer from their record, `seon.turn/executed`). With the cut, a compiled
  path's digest no longer includes the branch definition, so those tests stay reused.

**The 11 reds are not the change's.** `:seon.turn.loop/broken` (`seon.turn/step-reds`, the merge preview) was absent,
so the done rule judged none of them to be the change's own. They are interpreted tests handing SCI values to compiled
code: `sci.lang.Var` to `seon.instrument/var-symbol`, `seon.flow/var-process` and `seon.sci.eval/copy-host-var`; an
uninstalled attribute `:seon.cluster/name` on a test store; one bound overrun. They are interpreted only because
the walk crossed host-bound callers, so the cut above removes them from this reach. The reply still printed them in
full as `:red` with their failures.

**Q3. The AI face of the reaching summary.** `:seon.turn.loop/test-summary` (`seon.turn/summary`,
`seon.turn/request-summary`) has no render pair. The AI face should be counts plus the reds the change owns:

```clojure
{:seon.turn.loop/reaching
 {:seon.test/passed? false          ; or the stated extent when partial
  :seon.turn.loop/reaching-count 2376 :seon.turn.loop/nearest 187
  :ran 17 :reused 170 :pending 0
  :broken [sym ...]                 ; seon.turn/step-reds: the change's own reds, each with its first assertion's type+message
  :red-not-yours 11                 ; red, but not judged the change's (red on main or not caused by it)
  :seon.render/operations [(seon.test/run-result "<run-id>") ...]}}   ; the full run, requeryable
```

- The full value stays whole: the run entity (`:seon.test.run/id`, its results and `:seon.test.failure/reports`) and
  the step summary under the reply's result handle. The HTML face renders it all: every red, failure and pending test.
- Pending names are a count plus the queue's requery. The queue is already data (`seon.turn/summary-queue`).
- Render pair in `seon.turn`, declared on `:seon.turn.loop/test-summary` in `resources/seon/schemas/seon.turn.edn`.
  `seon.turn` is a critical-path file (chain lanes), so this goes in as a patch or in a chain slice.

**Q4. "Isolation requires a fresh owned branch."** Producer: `seon.cluster.agent/acquire-context!`, the
`(when (and create? (not (:seon.cluster/created? created))) (throw …))` after `seon.cluster.registry/fork!`. `create?`
is true when the acquisition is isolated, its registry has no entry for `[branch agent-id]`, and either
`:seon.agent/isolate?` is set or **no `:seon.agent/branch` option was passed**. In the second case the branch came from
the agent's stored `:seon.agent/branch`, and that branch already exists in the store. `fork!` answers
`:created? false` and the acquisition throws. The automatic SCI reaching run executes the db<->flow tests in a fresh
child registry, and they acquire agents by id whose rows name existing branches (fixture rows, or rows inherited from
main). Every such first acquisition therefore throws. The census already found this class (red-baseline-census
2026-09-25 rows 8/B; open-items "Custody inferred from a label"). The fault is the inference "no branch option means
allocate one". Fix it at the owner: create only a branch this call minted (`:seon.agent/isolate?`), and open an
agent's stored branch (`store/open-branch!`, the existing `borrowed` path). The throw then goes, because nothing can
reach it. Not done here: the file is critical path, and the cause in the failing db-flow tests was not reproduced
(the JVM was down). Under the walk cut, these tests mostly leave the `seon.db` reach anyway.

## Plan (phases, each with its red proof)

1. **Walk stops at compiled callers** (`seon.fn/closure-walk`; task
   `seon.fn/a-file-proof-two-compiled-callers-away-never-sees-the-branch-redefinition`). Red proof: a table row in
   `seon.fn-test` with seed `s`, an interpreted caller `a` with test `ta`, and a host-bound caller `h` with test `th`
   and a non-host-bound caller `c` above it with test `tc`. `reverse-closure` returns `{ta}`, not `{ta th tc}`. Run it
   with the branch-execution proof that `tc` sees the compiled `s`. Cost proof: `seon.profile/measure` of the probe
   below on parent and change.
2. **Render pair for `:seon.turn.loop/test-summary`** (`seon.turn`, patch to the chain lane). Red proof: the AI face's
   data for a summary with 11 reds of which 0 are `:broken` holds counts and no failure maps. The full value stays
   under the handle.
3. **`acquire-context!` creates only what it minted** (`seon.cluster.agent`, chain lane). Red proof: acquiring an
   agent whose stored branch exists, in a fresh registry, returns a handle on that branch.
4. **Unchanged-body `defn` cost** is C13's: 2.2 s and 1.7 GB to evaluate one `defn` in `seon.db`.

## Probe (rerun after restart; replace the before numbers)

```clojure
;; agent reach-reply, SCI mode
(in-ns 'seon.db)
(defn database-value? "True for any Datahike database value." {:malli/schema …as HEAD…} [value] (db.utils/db? value))           ; control
(defn database-value? "True for any Datahike database value." {:malli/schema …as HEAD…} [value] (boolean (db.utils/db? value))) ; change
;; count the cut without changing code:
(let [db (seon.db/db)
      hb? #(not (false? (:seon.fn/host-bound? (seon.db/pull db [:seon.fn/host-bound?] [:seon.fn/sym %]))))]
  (count (filter hb? (:seon.fn/affected (seon.fn/reverse-closure {:seon.db/db db :seon.fn/seeds #{'seon.db/database-value?}})))))
```
