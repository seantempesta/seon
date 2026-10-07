# S5-03a: def, redef, use (same-pass admission design proof)

Notes only; no production or test change. Probes ran on agent branch `s5-03a` (SCI mode, scratch namespace
`my.s5-03a`) at HEAD `0e3c33020`; the host measurements are read-only profile and thread-allocation reads in JVM
mode. Case from `docs/seon/specs/agent-loop.md` §4.1 ("def → redef → use").

## Verdict

The case does not need analysis before a later form. Nothing a later form's code can read comes from an earlier
form's facts, so the one seam is **observation captured per form** and **one analysis at the end of the pass**.
The case also found a defect in today's path (redefinition armed with the earlier contract, below), which the
captured-at-write design removes by construction.

## What exists (pinned HEAD)

- Per form: write observer (`src/seon/sci/observe.clj:42-53`) arms the written Var (`seon.sci.arm/arm!`,
  `src/seon/sci/arm.clj:44`) under `arming-projection request working`, then records the write. `derive-step`
  (`src/seon/batch.clj:543`) calls `seon.turn.batch/derive-form` (`src/seon/turn/batch.clj:303`): one
  `analyze-form` (`:226`, one `seon.fn/runtime-analysis-batch` call with ONE request) and `write-entity` (`:241`)
  per final Var write, reading `@v` and `(meta v)` after the form ended.
- Between forms: `apply-pending` (`src/seon/batch.clj:513`) puts the previous form's facts on the working value
  (`seon.turn.batch/apply-facts`, `db/with`) only when a later form follows.
- `seon.turn.batch/fold-change` folds the changes by identity: the later row for a symbol replaces the earlier
  (`assoc-in [:entities sym]`). One settlement writes the folded program.
- `seon.fn/runtime-analysis-batch` (`src/seon/fn.clj:945`) already takes a vector of requests and runs the analyzer
  once. Caller closure today: `turn/batch.clj:238` with a single request.

## Probe results (agent `s5-03a`)

All numbers from the MCP replies and stored evaluations; run `result` handles are not durable.

1. **Values.** One pass: `(defn ^{:malli/schema [:=> [:cat :int] :int]} f "old" [x] (inc x))`, `(def early (f 1))`,
   redef `f` `[x] (+ x 2)`, `(def late (f 1))`, `[early late]` returned `[2 3]`. Earlier use gets the earlier body, later
   use the later body. The Var is the same object across both writes (`(identical? v1 #'g)` true) and its metadata
   changed in place (`:doc "old"` gone, `:malli/schema` replaced).
2. **Stored facts.** One entity `my.s5-03a/f`, source = the second form's exact text, `:seon.program/definition-digest`
   `1315015c...`. History of `:seon.fn/source` for the symbol holds one datom (the second form). The first
   definition never reaches the store; only the folded program does.
3. **Evaluation rows.** Six evaluations share one run (`:seon.cluster.eval/run`), ordinals 0-5. Both defining forms
   `:code-evidence :complete`; both `(def ... (f 1))` forms `:code-evidence :unknown`. The same use in a LATER pass
   (`(def cross (f 1))`) is `:complete`. Cause (`seon.turn/code-evidence`, `turn.clj:4869`): the in-pass call has
   executed key `[f nil]` because the arm at write has no definition digest yet
   (`install-function-contract!` gets no `:seon.program/definition-digest`; `instrument.clj:1284` says the same for
   the JVM path). `executed` itself is stored only beside read evidence (`turn.clj:136-143`), so the observable fact
   is the `:unknown` evidence. §4.1 deletes `code-evidence` with its only consumer in S4.
4. **Visibility.** Inside the pass, agent code does not see the earlier form's facts: after `(defn vis1 ...)`,
   `(seon.db/q ... (seon.db/db))` and `pull` of `[:seon.fn/sym vis1]` in the next form returned absent; the next pass
   sees it. `apply-pending`'s working value is read only by the batch's own stages: `arm!` (projection, call
   preparation snapshot), `derive-form` (the rebound/withdraw check `dbu/entid working`, the analyzer's
   `available-functions` and `program-targets`, `contract-facts-row`), `schema-notes`, `changed-schema-keys`.
5. **Defect found.** Redefinition with a changed contract in the same pass is armed with the earlier contract:
   `(defn ^{:malli/schema [:=> [:cat :int] :int]} h [x] (inc x))`, `(defn ^{:malli/schema [:=> [:cat :int :int] :int]} h [x y] (+ x y 100))`,
   `(h 1 2)` returned `my.s5-03a/h refused argument count: expected a declared argument count (1), got an argument
   count of 2`. In the next call `(h 1 2)` = 103, the Var meta and the stored spec hold the new contract. Repeated for
   `g`, `k`, `q2`. No-contract then contract works (`q1` -> 103); same-arity changes cannot show it, which is why
   `seon.cluster.turn-test/contracted-redefinition-exactly-replaces-the-entity` passes. Suspected cause, NOT verified:
   the redefinition arms under the working value after `apply-pending` applied the earlier row, and
   `compiled-wrapper` prefers the projection's function contract over the authored one
   (`instrument.clj:827`). I could not reproduce the projection step in isolation in the time box
   (`entity-facts` refused my hand-built row). Task: `seon.sci.arm/a-redefinition-in-one-pass-is-armed-with-the-earlier-contract`.
6. **Falsifier for a naive end-of-pass derive.** Reading `(meta v)` and `@v` at the end gives every write of the Var
   the LAST metadata (result 1). `derive-form` is correct today only because it runs right after each form. The
   seam must capture, per write, what `write-entity` reads from the live Var.

## Costs (this pass shape)

| Item | Measure |
|---|---|
| `runtime-analysis-batch`, one call, N forms (default JVM, median of 7, synthetic one-line defns) | N=1 11.5 ms, 5 13.9 ms, 10 13.7 ms, 20 16.3 ms |
| the same N forms as N calls (today's shape) | 13.0, 62.6, 122.1, 232.5 ms |
| `seon.turn.batch/apply-facts` (the `apply-pending` validation of one defn row) | 111 ms in a clean one-defn pass; lifetime mean 64 ms over 97 calls |
| `analyze-form` | 22 ms per call (45 ms for 2 calls in the same clean pass) |
| `arm!` first defn of a fresh name | 223-347 armed calls, 2-3 ms |
| `arm!` redefinition after an applied change | 9,263-11,095 armed calls, 12-31 ms; repeated `seon.call-preparation/argument-facts` 5,973, `arity-facts` 2,936 (the call-preparation snapshot of the changed working value, rebuilt) |

Allocation (MB per pass) could not be isolated: the shared JVM's process-wide thread-allocation delta moved by
0.4 MB between two idle reads and by 636 MB and 3.5 GB around two small passes, dominated by other lanes. Not
reported; per-thread numbers need the pass thread's id, which the entrant does not expose.

So the term to remove is mostly `apply-pending`'s `db/with` per defining form (50-110 ms), then N analyses
(about 12-22 ms each, 1 ms marginal in one call), then the redefinition's snapshot rebuild.

## Proposed seam for this case (what root approves)

1. **Capture at write** (write observer, one small value per final Var write of a form): symbol, `(meta v)` subset
   the contract and row need (`:malli/schema`, `:doc`, `:arglists`, `:private`, `:test`), `fn?`/test flag, and the
   form ordinal. Source and span stay what `entry-coords` already derives from the form's original slice.
2. **Capture at form end** (one value per form): the namespace row (`namespace-entity`, aliases at that position,
   which also serves S5-03d), the `declared` set so far. Cheap, no kondo, no db.
3. **Arm from the authored contract at the write**, under the pass-start projection plus the batch delta candidate
   forms (`arming-projection` already merges `delta`); never from a working value an earlier form changed. This is
   the producer fix for the defect in result 5.
4. **One `runtime-analysis-batch` over the vector of defining requests** at the end of the pass (existing multi-request
   API), requests carrying their captured namespace row and cumulative `declared`; then `write-entity` per captured
   write using the captured metadata, folded by `fold-change`'s existing identity rule.
5. **No `apply-pending` db/with per form.** The one settlement validates the folded program at the writer
   (`program-facts`, `changed-since-basis`), which already refuses a basis change.

Consumers of the working value that remain to be proven by the other cases, not by this one: the rebound/withdraw
check (replaceable by the ordered write list the `program` reduce in `derive-form` already uses), analyzer
`available-functions`/`program-targets` for callees defined earlier in the pass (`declared` supplies names; edges
come from the analysis), and schema registered earlier in the pass (S5-03g).

Cost of the proposal for this case: analysis proportional to the defining forms but one process-level call
(about 12 ms + ~0.25 ms/form versus about 12-22 ms/form); no per-form validation. If the `declared`-only prelude
loses an edge for an in-pass callee, the design states the cost: analysis before the later form, i.e. today's
per-form shape for that form only.

## Proof specified (`test/seon/cluster/turn_test.clj`, lands with S5-04, not authored here)

`seon.cluster.turn-test/a-pass-records-the-definition-each-form-observed`, one pass, asserting data:

| Assertion | Today |
|---|---|
| `early` = earlier body's value, `late` = later body's value | passes (`[2 3]`) |
| the entity for the symbol has the latest `:seon.program/definition-digest` and exactly one `:seon.fn/source` datom in history | passes |
| a redefinition with a CHANGED arity: the later call with the new arity returns the new body's value, no refusal | **fails today** (result 5) |
| each defining evaluation's call/diagnostics carry the metadata it observed (contract of its own write, not the final Var's) | cannot be asserted today; fails if derivation reads the Var at pass end |
| `:code-evidence` of the use forms | `:unknown` today; deleted with its consumer in S4, so not asserted |

Existing comparison proof `seon.cluster.turn-test/contracted-redefinition-exactly-replaces-the-entity` does not
cover a contract change; the row above does.

## Findings and tasks

- New: `seon.sci.arm/a-redefinition-in-one-pass-is-armed-with-the-earlier-contract` (result 5; proof is the changed-arity row).
- Existing, still true in part: `seon.turn.batch/every-form-is-analyzed-because-its-result-handle-is-a-binding`.
  Today plain calls are not analyzed (`(def n1-v (n1 1))`'s sibling `[z1 z2 z3]` forms and `(in-ns ...)` produced no
  analysis), but every `def` of a non-function is: three `(def zN N)` forms = 3 `analyze-form` calls, 50 ms,
  zero entities and zero `apply-facts` calls. `write-entity` answers `:not-a-function` AFTER the analysis, so the
  analysis is wasted. The task's text says "every evaluated form"; root should retitle or close it as partly fixed.
- A redefinition's armed-call count (9-11k calls, 12-31 ms) is over the 1 s rule only in aggregate, but it is
  repeated `call-preparation` work on a changed working value (see Costs); the seam above removes it by not
  changing the working value per form.
- Not measured: allocation per pass (see Costs). Probes added `user/watch`, `snap`, `s0`, `mx` Vars in default's
  JVM while measuring; they were removed with `ns-unmap` before this note was written.

## Design check

The design held against the real code for this case: the batch already has the multi-request analysis entry, the
fold already resolves redefinitions by identity, and agent code never reads in-pass facts. Awkward: arming reads the
working value, so removing the per-form apply changes what `arm!` sees; that is the intended fix for result 5 but
makes S5-04 depend on the cause being confirmed (task above) before code. Nothing here needs a new registry,
cache, atom or printer. No RESET NEEDED.
