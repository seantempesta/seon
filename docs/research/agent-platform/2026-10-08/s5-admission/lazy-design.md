# S5-03i: definition during lazy realization (admission design proof, notes only)

Lane s5-03i. Notes only; no production or test change. Probes ran on agent branch `s5-03i` (SCI mode, namespace
`my.s5-03i`) at HEAD `d11dbe2b6`, plus two host simulations in JVM mode that build a private `sci.core/init` context
and define nothing in default. Case from `docs/seon/specs/agent-loop.md` §4.1 (lazy realization).

## Verdict

Realization of a lazy value that defines a function happens in the **show stage**, inside `seon.batch`'s
observation scope, but the SCI write observer **drops the write**, so the definition is live in the Var and absent
from the program. Where a definition is realized is therefore not an admission-seam question (the S5-04 seam of
captured-at-write metadata plus one end analysis serves it unchanged): it is a defect at the producer of the
observation, `own?` in `seon.sci.observe/observe`. Nothing in a read-only render writes; the realization is the
agent's own lazy code run by the value printer.

## What exists (pinned HEAD)

- `seon.batch/evaluate-step` (`src/seon/batch.clj:350-`) runs evaluate AND print inside the observer's `run`; its
  docstring promises that "a definition made while realizing a lazy value is observed". The scope's `open` flag is
  cleared only after the observer returns.
- `seon.sci.eval` evaluates the form with `sci/eval-form` (value stays lazy); `seon.sci.admit/admit` runs inside the
  kernel boundary and does not realize a plain lazy seq (probe 2).
- `seon.sci.observe/observe` (`src/seon/sci/observe.clj:36-58`): the write observer fires only when
  `(identical? env (some-> sci.ctx-store/*ctx* :env))`. SCI binds `*ctx*` only for the duration of `eval-form`
  (`reference-code/sci/src/sci/impl/interpreter.cljc:87`, `ctx_store.cljc:38`); `bindRoot` calls the observer
  (`reference-code/sci/src/sci/lang.cljc:121`).
- `seon.turn.batch/derive-form` (`src/seon/turn/batch.clj:303`) derives per entry from that form's queued writes.

## Probes and observed data

1. **Realization site.** `(map (fn [i] ... (defn lr3 [x] (inc x)) i) [1 2])` returned as the form's value: the stack at
   the first element is `seon.render.value/seq-cells` under `render.value/project`, under `seon.turn`,
   `seon.batch`, `seon.sci.observe`, `seon.sci.batch`. So realization is in the value printer's show stage, inside
   the observation scope. Each element ran exactly once (`cnt` = 3 for a 3-element seq; the digged result does not
   re-run it).
2. **Stored facts, uncaptured fn (`(fn [i] (defn f [x] (inc x)) i)`).**

   | Form shape | Var works afterwards | Program entity |
   |---|---|---|
   | `(doall (map ... (defn leag ...)))` inside the form | yes | **yes**, source `(defn leag [x] (inc x))` |
   | the lazy seq returned (realized by print): `lret`, `lazyg`, `lr2..lr4` | yes | **none**, in that or any later pass |
   | `(def lz3 (map ...))` then `(vec lz3)` in a later form: `lvar` | yes | **none** (see finding 2) |
   | `(def lz4 (map ...))`, never realized: `lunr` | no (unbound) | none, correct |

   (A closure capturing `i` is private by design, S5-03e; my first probes captured `i` and are not evidence.)
3. **Stale entity.** `(do (defn rdef [x] 1) (map (fn [_] (defn rdef [x] 2) 0) [1]))`: `(rdef 0)` then returns 2, the
   stored entity's `:seon.fn/source` is `(defn rdef [x] 1)` and its definition digest is the first body's. Live
   behavior and the program disagree; a reload or restart would revert to body 1. This is also the "later
   redefinition does not rewrite earlier observation" row, answered the wrong way round: the later definition is
   not observed at all.
4. **Cause, simulated.** Private context, raw thread, observer bound as production binds it, lazy seq realized after
   `eval-string*` returned: `own?` is `[false false]`; the same realization wrapped in
   `(sci.ctx-store/with-ctx ctx ...)` gives `[true true]`. In the host sim `*ctx*` is bound to some other context
   outside `eval-form`, so the env identity test rejects the write. NOT verified in the production print path
   itself: calling `seon.sci.observe/observe` directly was refused by its contract (full `:seon.batch/request`
   needed). The production evidence is the absent entity (probe 2) plus the stack (probe 1); the fix test below is
   the verification.
5. **Cost.** Replies show `:ms` 0-2 for these passes. The fix is one dynamic binding of the form's context around
   the thunk (no allocation proportional to input). Not measured as a delta: there is no production change to time.

## Proposed seam (what root approves)

1. **Observe by evaluation, not by ambient binding.** In `seon.sci.observe/observe`, run `thunk` under
   `sci.ctx-store/with-ctx` of the evaluating context, so a write made while the show stage realizes the value
   passes `own?`. `own?` keeps its job (excluding writes of other contexts a host call reaches). The write then
   joins `:seon.batch/form-writes` of the same entry, whose `:seon.batch/source` contains the definition text, so
   `derive-form` joins it by origin span with no new path.
2. **Nothing else changes for admission.** Capture-at-write (S5-03a seam) already records the metadata the write
   observed; a print-time write is just a later write of the same form. Redefinition inside the same form keeps the
   write-order rule (`program` reduce): the print-time definition supersedes the body one, matching the live Var.
3. **Realization in a LATER form (finding 2).** The write belongs to the later form, whose source lacks the text.
   Today it joins nothing and is silently private. The seam must name this: either the write carries its defining
   form's source (the observer already has `:origin`, a complete span in the batch coordinates of the form it was
   authored in; resolving it needs the ordinal of the authoring form), or the write is recorded private with
   `:reason :defined-outside-this-form`. The plan does not decide; recommended simplest is the private reason, since
   it is rare input and correct (no wrong source attached).
4. Print stays read-only; nothing is added to render. No new atom, cache or printer.

## Proof specified (`test/seon/cluster/turn_test.clj`, lands with S5-04, not authored here)

`seon.cluster.turn-test/a-pass-captures-definitions-realized-after-the-form-body`, one pass, data assertions:

| Assertion | Today |
|---|---|
| a form returning `(map (fn [_] (defn f [x] (inc x)) 0) [1])`: after the pass `f` has an entity whose source is the defn text | **fails** (no entity) |
| the realized value recorded once (element side effect count 1 after the reply and after a dig) | passes |
| `(do (defn r [x] 1) (map (fn [_] (defn r [x] 2) 0) [1]))`: entity source and digest are the second body | **fails** (first body) |
| an earlier form's use of `r` keeps the value it observed; a later redefinition pass does not rewrite the earlier evaluation | passes (evaluations are immutable) |
| realization in a later form: no entity carries the wrong source (private with a reason, or joined to the authoring form) | passes today by accident (private, no reason) |

## Findings and tasks

- **Defect, filed in "Findings for root to file":** a definition realized during the show stage is dropped by
  `own?`, leaving the Var live and the program stale (probes 2 and 3). Proof: the two failing rows.
- Silent privacy: a write that joins no definition is recorded private with join `:unanalyzed`/`:rebound` and is
  stored nowhere an agent can read (no schema attribute for `:seon.batch/private`); the agent is told nothing.
  Not investigated further in this slice.
- Probe side effects: a first probe errored inside the printer (`Unable to resolve symbol: sci.ctx-store/get-ctx`),
  returned as an error value beside the form; correct behavior, my own typo.
- default `bin/seon status` is OK but its cluster state is `:faulted` (open fault `seon.turn.world/record-fault`,
  3 occurrences; four over-bound warnings); not caused or touched here.

## Findings for root to file

- `seon.sci.observe/observe`: `a-definition-realized-while-showing-a-lazy-value-is-observed`. Evidence: probes 2 and 3
  above; failing row 1 and 3 of the proof table; simulated cause probe 4.
- `seon.turn.batch/derive-form`: `a-write-defined-by-an-earlier-form-is-private-without-a-reason`. Evidence: `lvar`
  (finding 2); proof is the last table row asserting a reason.

## Design check

The design held against the real code: no new admission seam is needed, and S5-04's capture-at-write plus one
analysis serves print-time writes once they are observed. Awkward: the batch docstring and the observer docstring
already promise the behavior, so the defect hides behind an identity check that is correct for foreign contexts and
wrong for the form's own show stage. The cause is shown in a simulation, not in the production print path; S5-04's
implementer must confirm it with the failing row before changing `observe`. Probe state: agent branch `s5-03i` holds
scratch Vars in `my.s5-03i`; nothing reached default. No RESET NEEDED.
