# S5-03c: unmap and remove-ns admission design

Lane s5-03c, HEAD cd8a481c2. Notes only; probes ran on agent branch `s5-03c` (SCI mode), nothing in default's JVM.
Question (reconciled-plan Q7): can a pass analyze once at its end if a form removes a declaration other forms used?

## What exists

- SCI observer emits `:unmap {sym}` and `:remove-ns {ns}` writes in order (`sci/arm.clj:82-83`, accepted by
  `batch.clj:315-316`).
- `turn.batch/derive-form` (`:303`) folds a form's writes by identity: `:unmap` -> `[:removed]`, `:remove-ns` drops
  every entry of that namespace and marks `[:removed-ns]` (`:350-353`). Removals become `:seon.batch/removals`.
- `derive-step` (`batch.clj:543`) builds the facts on the working value; `apply-pending` (`:513`) applies them
  before the next form reads, and only when a form follows.
- `turn.batch/removal-facts` (`:480`) retracts through `my.program/deletion-report` on the working value. No stored
  row means `[]`; a surviving referrer means the owner's refusal, which is that form's facts error.
- Removal needs no clj-kondo analysis: `derive-form` runs `analyze-form` only when the form has `:root` binds
  (`finals`), so a pure unmap/remove-ns form does zero analysis.

## Observed (finite forms through the one entrance, agent s5-03c)

| Case | Observation |
|---|---|
| defn dbl, (dbl 4), `ns-unmap`, `(resolve 'dbl)`, `(dbl 4)` in ONE run | defn 20 ms/9254 calls, call 8 (1 call), unmap nil (1 call), resolve nil, last form `Unable to resolve symbol: dbl`. Next run: no `:seon.fn/sym` row. Earlier use keeps shown `8`, call-count 1; the unmapped form's error is on its own evaluation only. |
| defn in `ns.tmp`, call, `my.program/remove-ns!` in the SAME run | `remove-ns!` answers not-found "No program declaration names ns" (3 ms) and SCI keeps the namespace; the call still works. Same call in the NEXT run: 396 ms, 7007 calls, ns gone, call errors unresolved. |
| core `(remove-ns 'ns)` same pass | Refused by `native-call-refusal` (`my.program:791`, hook list `sci/eval.clj:424`) with the same not-found, advice to use `remove-ns!`. |
| defn base1, defn caller1 (calls base1), next run core `ns-unmap` base1 | Reply `nil`, no diagnostic, no stored error. `(resolve 'base1)` nil in the pass; DB row `base1` stays; the Var is back in the next run. The writer refused, nothing said. |
| same, `my.program/ns-unmap!` | Refuses before mutating, names `caller1`, 50 ms/1973 calls; SCI unchanged. |
| defn pa, defn pb (calls pa), `ns-unmap` pa, ONE run | Same silent outcome: both rows remain, `(resolve 'pa)` false in-pass, true next run. The refusal came from pb being in the WORKING value only because `apply-pending` applied form 2 before form 3. |

Allocation (MB) was not measured; ms and armed calls come from the replies above. Not a timing claim for S5-04.

## Findings

1. Core `ns-unmap` is not routed to `my.program/ns-unmap!` (hook list has no `ns-unmap`), and its writer refusal is
   not delivered: `seon.turn:5025` admits `not-admitted` only when the form bound a Var or registered a schema, and an
   unmap binds nothing. Task `seon.turn/a-refused-core-ns-unmap-is-silent` (id 9aa64cea580c). Whether the
   last-form (settlement revalidate) path also drops it was observed (alone-unmap showed no diagnostic) but its cause
   is unverified.
2. `my.program/remove-ns!` and the native `remove-ns` decide against the committed head, so a namespace defined
   earlier in the same pass is not found. Task `my.program/remove-ns-refuses-a-namespace-defined-earlier-in-the-same-pass`
   (id a3c08b7b99b2). Existing cost task: `my.program/ns-unmap-and-remove-ns-cost-quarter-thousand-tokens-and-most-of-a-second`
   (396 ms observed for remove-ns!, 50 ms for refused ns-unmap!).

## Seam verdict for S5-04

- Removals need no early ANALYSIS: they carry no source and the end-of-pass analysis can ignore them.
- Removals do need early FACT APPLICATION. The refusal rule reads referrers on the working value (pa/pb case).
  If facts are deferred to the pass's end, a removal of something defined in the same pass finds no stored row,
  `removal-facts` answers `[]`, and a surviving same-pass referrer is silently allowed. So `apply-pending`'s
  "apply when a later form follows" must stay for any pending change that holds entities or removals, or the
  removal must be checked against the pass's accumulated program map (`:seon.batch/program`, folded by
  `fold-change`) instead of the DB.
- Order matters and is already right inside one form (`derive-form` reduce over writes) and across forms
  (`fold-change` assoc then `:removed`). An end-of-pass analysis must propose entities only for the last write per
  sym that is not followed by a removal: that is the existing `finals` + `program` reduce, extended across forms.
- Smallest contract for the proposed test `seon.cluster.turn-test/a-pass-records-unmap-and-namespace-removal-in-order`
  (design, not green): define/use/unmap and create-ns/define/remove-ns in one pass, asserting (a) the later
  resolution is absent, (b) the earlier use's evaluation keeps its value and call count, (c) the final DB has no row
  for the removed sym/ns, (d) a surviving referrer, including one defined in the same pass, yields the owner's
  refusal as a diagnostic beside the unmap's value (today missing: finding 1).

## Design check

The design held against the real code for ordering and analysis-free removal. The falsifier for "one analysis
and deferred application" is the same-pass referrer. The plan's S5-04 cannot drop `apply-pending` for removals;
it can drop per-form kondo only. Findings 1 and 2 should be fixed at their producers before S5-04 asserts (d).

## Limits

No code changes, no new contracts, no allocation numbers. Default is `:faulted` with unrelated open faults at
probe time (`runtime_status`); no RESET NEEDED from this lane.
