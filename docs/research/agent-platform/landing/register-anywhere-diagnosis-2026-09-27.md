# register! anywhere and the refused 22-form run: diagnosis (2026-09-27)

Lane `fix-register-anywhere-opus`. Diagnosis only, under root's hold; no source edits. Default ran 5c49f374c
(pid 60186, started 17:04:03Z). Probes: read-only JVM evaluations on `default`, plus one SCI run on the
disposable branch `design-1`.

## 1. "Schema declarations require an isolated registration delta"

**Reproduced** on `design-1` (run `1eb1f23ad83d`, 415 ms):
`(do (seon.schema/register! :design1.probe/x [:int]) :after)` refused with that message, stack
`[seon.schema/register! 1752]`.

**Where it is raised.** `src/seon/schema.clj:1311-1321` (`update-candidate-forms!`). `register!` calls it at
`schema.clj:1811`; `unregister!` (`schema.clj:1828`) and `contribute-candidate-forms!` have the same guard. It
throws whenever `*candidate-forms-overlay*` (`schema.clj:1005`) is unbound. Only `call-with-registration-delta`
(`schema.clj:3603-3620`) binds it.

**What it guards.** An agent `register!` must write into the evaluation's own delta atom. Without the guard it
would write into the process-wide candidate population. The guard dates from c7c04247a (2026-07-21, "Revert only a
failed eval's own schema changes"). At that time the evaluator wrapped **every** form in
`call-with-registration-delta`, so the delta was collected by the evaluation, whatever the form's shape.

**Where it narrowed to form position.** 10dfe0ff4 (2026-07-29, "materialize committed program registrations")
changed that. The evaluator now binds a delta only when the **reader** has classified the top-level form as a schema
declaration. Current code:

- `src/seon/sci/reader.cljc:331-341` `schema-declaration` matches only a 3-element top-level form whose resolved
  operator is `seon.schema/register!` and whose second element is a literal qualified keyword. It stamps
  `:seon.schema/key` and `:seon.schema/form` (the source expression, not the value) on the event.
- `src/seon/sci/eval.clj:470-485` `row` turns that event key into a schema row.
- `src/seon/sci/eval.clj:3877-3944` `declared-row` begins a delta only
  `(when (or (:seon.schema/key raw-row) unregister-key))` (line 3884), and evaluates the form inside it.
- `src/seon/sci/eval.clj:4827-4835`: a top-level `do` runs its children through `top-level-evaluation`
  (`eval.clj:1042-1070`). That calls `sci/eval-form` and `admit-program-change!` (Var definitions only), with
  no schema classification and no delta.

So these forms all hit the guard, because none of them is the classified shape:

- `register!` nested in `do`, `let` or `when`;
- `register!` called from a function;
- `register!` with a computed key;
- several `register!` calls in one form.

MCP branch mode takes exactly one form, so on a branch a batch of registrations can only be sent as `(do …)`, and
that is refused.

**The single-row constraint the fix meets.** An evaluation carries exactly one `:seon.program/row` to
settlement:

- `eval.clj:4857`: `var-row (first var-rows)`;
- `turn.clj:1597`: `(keep :seon.program/row evaluations)`.

`admit-program-change!` (`eval.clj:960-1025`) refuses more than one declaration per form, counting the earlier
children of the same top-level `do` (`:seon.sci.eval/multiple-declarations-error`). Several schemas collected from
one form therefore do not fit the current evaluation → settlement interface. This is the same limit that makes
`(do (defn a …) (defn b …))` refuse.

**Same class as a318b2d38.** a318b2d38 is "a nested `in-ns` loses the run": settlement upserts only namespaces
decided from the top-level form (`repl-session-entity-design-2026-09-27.md` P2a, `turn.clj:619`
`namespace-rows`). In both defects, a fact that an evaluation observes (a schema registered, a namespace entered)
is instead decided from the top-level form's syntax. Both are fixed at the same producer: the evaluation reports
what it observed (the delta's `changed-keys`, the namespaces SCI entered), and settlement admits a **vector** of
rows per evaluation. Neither is fixed by adding more reader shapes.

**Smallest composition (for the audit, not implemented).** Bind one registration delta around each top-level
evaluation, as c7c04247a did. After evaluation, derive schema rows from `schema/changed-keys` over the delta
(`schema.clj:3630-3636`), not from `:seon.schema/key` on the event. Let the evaluation carry `[:vector row]`, so
that `declared-row`'s reader-shape branch (`eval.clj:3877-3944`, about 65 lines) and the reader's
`schema-declaration` / `schema-unregister` (`reader.cljc:331-351`) can be deleted. This crosses `sci/eval.clj`,
`sci/reader.cljc`, `turn.clj` (settlement consumers of `:seon.program/row`) and the one-declaration rule, so it
belongs to the eval-path audit's pipeline slice.

## 2. Why the 22-form settlement was refused, and where the refusal went

**Evidence (design-1).** The run is `ce27ba34bd16` (entity 59962): opened tx 536879975 at 17:16:40.426Z, closed tx
536879976 at 17:17:15.898Z. It has 22 receipts (`:seon.cluster.eval/run`). Fault occurrence `c4cd72ebcfa0`
(`seon.db/transact!`, layer `:seon.db/database-write`, `:seon.error.occurrence/turn` 59962) is recorded **in the
close transaction**. Its message:

> Complete component validation refused: unowned-entity; … `#:seon.db{:entity 54842, :entity-value
> {:datahike.read/dependency-plan …}}`

Entity 54842 is an older read-evidence component of receipt 39443, owned through
`[39443 :seon.cluster.eval/read-evidence 54842]`.

**Cause: our defect in `seon.cluster/invalid-value-retraction`** (`src/seon/cluster.clj:1241-1270`).

1. The run re-registers `:seon.cluster.eval/receipt`. `seon.turn/schema-attribute-change-tx` (`turn.clj:1097-1138`)
   sends every attribute of dependent schemas through `attribute-change-tx` with the candidate projection
   (`cluster.clj:1174-1239`). A kept attribute then drops the "values its new form refuses".
2. For a cardinality-many attribute, `invalid-value-retraction` validates `(into #{} …)` (`cluster.clj:1265`).
3. `:seon.cluster.eval/read-evidence` is declared
   `[:vector {:seon.db/component true …} :seon.db/ref]` (`resources/seon/schemas/seon.cluster.eval.edn:12`). A set
   never validates against `[:vector …]`, so **every** read-evidence datom is retracted. The writer's own reader
   normalizes many-values by the declared form instead: `(if (= :set (m/type form)) set vec)` (`db.clj:3472`).
4. The component entities are left without an owner. The final owning-value validation
   (`db.clj:3540-3760`, `::unowned-entity` at 3751) then refuses the whole settlement, through Datahike's report
   validator (`reference-code/datahike/src/datahike/db/transaction.cljc:1224-1234`, fork fe1d4e26; this is the
   logged "Transaction report validation rejected").

The load notes already predicted this (`tmp/data-model-sample/load.clj:6-7`). It is now confirmed on data.

**Blast radius, measured on default with the *unchanged* projection** (read-only, 826 ms for all 117
cardinality-many attributes). `invalid-value-retraction` would retract every value of 11 attributes:

| attribute | values |
|---|---|
| `:seon.fn.arity/arguments` | 4,827 |
| `:seon.fn/arities` | 2,568 |
| `:seon.fn.binding/entries` | 888 |
| `:seon.cluster.eval/read-evidence` | 459 |
| `:seon.test.failure/contexts` | 240 |
| `:seon.fn.binding/children` | 122 |
| `:seon.render/units` | 13 |
| `:seon.ai/wire` | 9 |
| `:seon.config.ai/stop` | 1 |
| `:seon.config.fs/roots` | 1 |
| `:seon.cluster.eval/faults` | 1 |

This runs on any agent schema declaration that reaches one of these attributes through a dependent schema.
Packaged adoption (`declaration-changes`, `cluster.clj:1293`) calls the 3-arity with no projection, so it is not
affected. Where refs are components, the result is a refusal (as here). Where they are not, the values are silently
dropped.

**Fix (owner: `src/seon/cluster.clj`, not this lane's file).** Build the value in the declared form's collection
type, as `db.clj:3472` does. Better, reuse that normalization (`::normalize-many` of the attribute plan), so there
is one decoder. The regression: re-declaring an entity schema with an unchanged `[:vector …]` cardinality-many
member retracts nothing.

**Where the refusal is lost to the caller.** Recording and settlement worked. `settle-batch!`
(`turn.clj:3857-3939`) handed the refused report to `settle-batch-refusal!` (`turn.clj:4074-4166`). That committed
all 22 receipts, each keeping its `:seon.eval/shown` value with the refusal as `:seon.cluster.eval/error`, plus
`:seon.cluster.eval/diagnostics` and the fault, and closed the run. The pass reports
`:seon.turn.loop/outcome :error` with `:seon.turn.loop/refusal` (`turn.clj:5393-5396`, `5678-5682`).

The loss is in `seon.cluster.agent/evaluate-source!` (`src/seon/cluster/agent.clj:800-818`):

- The refusal arm (806-811) answers the refusal only when the run does **not** exist on the basis (refused
  before opening).
- A refused settlement of an opened run falls to the `:seon.turn.loop/outcome` arm (813-818). That answers only
  `{:seon.turn/id :seon.db/db :seon.db/durable}`, dropping both `:seon.turn.loop/refusal` and the `:error`
  outcome.

A caller that does not then read `run-observation` (`agent.clj:835-853`, which the MCP bridge does) sees success
with no error. The caller's `{:err nil :committed? false :forms 0}` is this answer. Fix: keep
`:seon.turn.loop/refusal` (as the `:seon.error` diagnostic) beside `:seon.turn/id` and `:seon.db/db` in the outcome
arm. The owner is `agent.clj`. The values are already stored in the receipts on the returned basis.

## 3. What took 35 s

These are JVM-lifetime inclusive profile aggregates (`seon.profile/top`). This run dominates them, because
`evaluate-source!` had 3 calls totalling 36.9 s with a maximum of 35.6 s.

**Whole run: 35.6 s**
- `seon.cluster.agent/evaluate-source!`: max 35,618 ms, the whole run.

**Evaluation of the 22 forms: 14.9 s**
- `seon.turn/evaluate-sources`: max 14,879 ms, about 0.68 s per form.
  - `seon.turn/with-settled` / `settled-value`: 21 calls, 11.2 s, max 4.2 s. This is one `d/with` per form onto
    the working value.
  - `seon.db/with`: 8.5 s.
  - Per-form `validate-write-report`.

**Settlement: 20.6 s** (`seon.turn/settle-batch!`)
- `db/transact-applied!` / `transact-call`: max 20,457 ms. Inside it:
  - `seon.turn/receipt-settle-call`: 46 calls, 16.0 s total, max 867 ms. 22 of these were this run's.
  - `seon.db/write-owned-values-error`: max 3.7 s. This is the complete component validation that refused.

**Projection rebuilds**
- `seon.schema/projection-from-rows` / `build-projection`: 52–56 calls, 14.4–16.1 s total, max about 630 ms.

**Hypothesis (unmeasured split).** Each schema-declaring form rebuilds the full projection twice: once at
evaluation, through `with-settled` over a new db value, and once inside the transaction, through `row-tx` and
`schema-attribute-change-tx`. The second computes `malli->datahike-schema-in` over the whole projection twice per
row, plus `carried-projection` of the mid-transaction value.

That makes the cost proportional to (forms × whole projection) rather than to the changed keys. It is the "make
cost follow changed inputs" violation. To confirm it, time one `receipt-settle-call` for a schema row on
`d/with`, split by `schema-attribute-change-tx` versus projection build.

## Findings to record (not recorded here; the owning files belong to other lanes)

- `seon.cluster/invalid-value-retraction` validates a set against `[:vector …]` forms, wiping 11 attributes' data.
  Owner: `cluster.clj`. Task edn: `resources/seon/tasks/seon.cluster.edn`.
- `seon.cluster.agent/evaluate-source!` drops `:seon.turn.loop/refusal` for an opened run. Owner:
  `cluster/agent.clj`. The existing task is `seon.turn.edn:99`; it should move to `seon.cluster.agent` or name
  `agent.clj:813`.
- A schema-declaring run costs about 0.7 s per form at evaluation and in settlement (whole-projection rebuilds).
  Owner: `turn.clj` settlement plus schema.
