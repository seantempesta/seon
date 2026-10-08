# S5-03g: schema, then use (same-pass admission design proof)

Lane s5-03g. Notes only; no production or test file changed. Every probe ran on the lane's own SCI agent branch
(agent `s5-03g`, namespace `scratch.schema`), one run per `eval_clj` call, at HEAD `9de0441f6`. Case from
`docs/seon/specs/agent-loop.md` §4.1 ("schema → use"), proof row
`seon.cluster.turn-test/a-pass-admits-a-schema-before-its-use`.

## Verdict

**Schema then use needs no early analysis, but it does need one bounded early admission of the schema's own facts, and
today's path has a producer defect that the end-of-pass design must not inherit.** Two different consumers read a
newly registered key inside one pass:

1. The **contract gate** (arming of a function, argument validation) reads the evaluation's schema delta
   (`:seon.schema.delta/candidate-forms`), not the database. It works in the same pass and in the same form.
2. The **stored facts** (the schema entity, the function's `:seon.fn/arities` input refs, the Datahike attribute)
   are produced by `derive-form` and applied by `apply-pending` onto the working value. A later form's derivation
   reads them from there. The agent's own `(seon.db/db)` and `transact!` do NOT see them until the pass settles.

So the seam for this case is: the delta is the write-time observation (already a value of the pass), and
`apply-pending` (`src/seon/batch.clj:513`) is the bounded admission that makes the next form's derivation see the
stored schema. S5-04 can analyze once at the end only if function derivation takes the delta's projection
(`arm/arming-projection`) instead of the working value's. That is the defect below.

## What exists (pinned HEAD)

- `seon.schema/register!` (`src/seon/schema.clj:1667`) adds the form to the evaluation's candidate collector and
  returns `{:seon.schema/key :seon.schema/definition}`, or RETURNS a refusal map (`registration-refusal`), admitting
  nothing.
- `seon.sci.arm/arm!` (`src/seon/sci/arm.clj:57`) arms a Var's contract at its write under
  `arming-projection request working` (`:18`): the working projection plus the delta's declarations.
- `seon.turn.batch/derive-form` (`:303`) records `:seon.batch/schema-keys` (`changed-schema-keys`, `:278`) and the
  `schema-notes` for keys the bridge cannot store (`:290`). `schema-tx` (`:408`) writes the entity and the attribute
  change through `arming-projection` as well. But `write-entity` (`:241`) builds the function row with
  `sfn/contract-facts-row (db/carried-projection db) ...` (line 272), `db` being the working value.

## Probes and observed data

1. **Schema, then contracted function, then call, three forms in one pass** (`register! :scratch.schema/label
   [:string {:min 3}]`, `(defn ^{:malli/schema [:=> [:cat :scratch.schema/label] :string]} shout ..)`, calls):
   `(shout "abc")` = `"abc!"`; `(shout "a")` returns the error `shout refused argument 0: expected
   :scratch.schema/label (a string of length at least 3) ... Contract: :scratch.schema/label` with
   `:seon.error/problems [{:path [] :offending "a" :expected :scratch.schema/label}]`. The gate uses the new schema
   in the same pass. Stored afterwards: `:seon.schema/key` `:scratch.schema/label` form `"[:string {:min 3}]"`,
   `:seon.schema.admission/source :agent`; `shout` row `:seon.fn/spec "[:=> [:cat :scratch.schema/label] :string]"` with
   `:seon.fn.arity/input-refs` resolving to `{:seon.schema/key :scratch.schema/label}`.
2. **Write and read in the same pass is refused; the next pass accepts it.** Same pass as `register! :scratch.schema/count
   [:int {:min 0 :seon.db/index true}]`: `(contains? (:schema (seon.db/db)) :scratch.schema/count)` false, and
   `(seon.db/transact! [{:db/id [:seon.agent/id "s5-03g"] :scratch.schema/count 7}])` returns a
   `:seon.db.write/validation-refusal` (`undeclared attribute :scratch.schema/count`, no datom). After the pass:
   attribute installed, same write commits (81 ms, 488 calls), read-back `7`, a write of `-1` returns
   `validation-refusal` (`expected an integer at least 0`). The spec row says "write/read": for the stored attribute this
   is a next-pass behavior today; whether the design should make it same-pass is Q7 (an agent value write needs the
   attribute on the agent's connection, which only the settlement transacts; admission to the working value does not
   change the agent's connection). I recommend the next-pass answer and a named refusal; do not add an early
   connection write.
3. **Malformed schemas refuse at their producer, admit nothing.** `[:maybe :int]` returns a refusal map
   (`:seon.error/layer :seon.schema/registration`, `:seon.error/member :seon.schema/nilable-value-schema`,
   `:seon.error/offending [:maybe :int]`, `:seon.schema/base-definition :int`); a dangling reference
   `[:map [:x :scratch.schema/nowhere]]` returns `Schema population refused ... (unresolved-reference)`. Afterwards neither
   key has a `:seon.schema/key` entity or an attribute (`[false false]` each). Refusal costs 4-5 ms for the nilable case,
   about 790 ms for the dangling one (it builds the whole population). Neither carries `:seon.error/type`
   (`registration-refusal` stamps layer and member only), so a test asserts `:seon.schema/registration-refused` and
   `:seon.error/member`.
4. **Forward use is refused loudly, and stays refused.** `(defn ^{:malli/schema [:=> [:cat :scratch.schema/later] :int]}
   early-user ..)` BEFORE `register! :scratch.schema/later`: the reply carries a diagnostic ("Not admitted to the
   program: its program facts were refused ... derive: :malli.core/invalid-schema", producer
   `seon.schema/contract-product`), no `early-user` entity, and every call, in the same pass and in the next
   one, throws `early-user is unavailable: its contract did not arm` (arm-time `refusing-root`). Registering the key later does
   not re-arm; the agent must define again. This is correct and loud; it is the "failed definition" family (S5-03h),
   not a schema-seam requirement.
5. **Producer defect: schema and contracted function in the SAME form.** `(do (schema/register! :scratch.schema/pct
   [:int {:min 0 :max 100}]) (defn ^{:malli/schema [:=> [:cat :scratch.schema/pct] :int]} half ..))`: the gate works
   (`(half 50)` = 25, `(half 500)` refused with `Contract: :scratch.schema/pct`), the schema entity is stored (`"[:int {:min 0, :max 100}]"`),
   but the reply carries the same "Not admitted to the program ... :malli.core/invalid-schema {:schema
   :scratch.schema/pct}" diagnostic and `[:seon.fn/sym half]` has no entity. Cause (verified by reading the code; the
   two-form variant of probe 1 differs only in `apply-pending` having put the schema on the working value): `arm!`
   and `schema-tx` use `arm/arming-projection request working` (delta included); `write-entity` uses
   `(db/carried-projection working)`, which lacks the form's own registration until it is applied. The fix is at the
   producer: `derive-form` passes `arming-projection` (it already holds `request` and `working`) to `write-entity` for
   `contract-facts-row`. Task: `seon.turn.batch/a-function-using-a-schema-registered-in-the-same-form-is-not-admitted`.
6. **Cost.** Registration is the dominant owner: 668-2,869 ms and 32,3xx armed calls per `register!` on this branch
   (five measurements: 776, 666, 683, 792, 2,869 ms; the last included a GC-prone moment), with
   `seon.schema/structural-schema` called 10,214-10,222 times, `compilable-form` 6,81x, `widen-component-children`
   6,81x, `contract-references` 3,40x per registration. The existing task
   `seon.schema/schema-registration-rechecks-the-whole-program-per-key` describes exactly this (about 300 ms per key then; now
   about 700 ms at a larger program); this case adds the number that it violates the sub-second rule on every
   schema-then-use pass and that it is incurred in the form, not in settlement. The defn that follows costs 20-77 ms and
   about 11,000 calls (that is `seon.call-preparation` argument facts and the contract product, not per-key). The
   function row's own analysis is one `runtime-analysis-batch` call as in S5-03b.

## Design: the smallest seam for this case

- **Observation captured at write**: the schema delta (the evaluation's candidate forms) and, per defining form, the
  arming projection derived from it. Both already exist as values (`:seon.sci.batch/delta`,
  `arm/arming-projection`). Nothing new is observed.
- **No early analysis**: schemas are not analyzed by clj-kondo. A later form's analysis input is its own source plus
  the `declared` prelude; the schema key appears as a keyword in the contract, resolved by the projection, not kondo.
- **Bounded early admission stays**: `apply-pending` applies the schema's tx (`schema-tx`, entity plus attribute
  change) so the NEXT form's derivation can resolve the key in the working projection. A cheaper replacement is
  possible only if every function-row derivation takes `arming-projection` (probe 5 fix); then the schema's stored
  facts would not have to be applied before the next form's derivation, and the end-of-pass fold could apply them
  together. The cost to price in S5-04: the attribute change tx is per changed key
  (`attribute-change-tx`, proportional to the change), and `arming-projection` is a Malli recompile of the changed
  declarations only ("Malli recompiles only what changed").
- **Falsifier of an end-only schema fold**: if function-row derivation kept using the working projection (HEAD), a
  schema and a contracted function in one pass would still need `apply-pending` between them. Probe 5 shows that
  today even `apply-pending` does not help when they share a form.

### Contract for the observation (to approve)

Per defining form: `{:projection (arm/arming-projection request working)}` used by `write-entity` for
`contract-facts-row`; no new key: the delta is `:seon.sci.batch/delta`. Implementation packet: change one argument in
`seon.turn.batch/write-entity` and pass the projection from `derive-form` (about 6 lines), plus the regression below.
The pass also carries `:seon.batch/schema-keys` and `:seon.batch/notes` as today.

### Proposed proof (S5-04 table row)

`seon.cluster.turn-test/a-pass-admits-a-schema-before-its-use` asserts, as data: (a) three forms: gate value and
`:seon.error/problems` expected `:scratch.schema/label`; stored `:seon.schema/form`, `:seon.schema.admission/source :agent`,
the function row's `input-refs` equal to the key; (b) the same-form variant (probe 5): `half` entity exists, no
diagnostic; (c) malformed variants: `:seon.schema/registration-refused` and `:seon.error/member` present, no entity,
no attribute; (d) forward use: no entity, calls refuse with the arm cause; (e) next-pass write and read of an
attribute-bearing key, plus the same-pass write refusal naming the undeclared attribute. The test does not exist at
HEAD and is not claimed green.

## Findings outside this slice

- `seon.schema/register!` costs 0.67-2.9 s and 32,3xx calls per key: existing task
  `seon.schema/schema-registration-rechecks-the-whole-program-per-key`; this note adds a fresh measurement (above).
- `seon.db/transact!` refusal hint `:seon.db/registered-candidates` lists keys that are already installed
  (`[:scratch.schema/count :scratch.schema/label]`) and omits the key that was actually undeclared
  (`:scratch.schema/size`): it reads as a candidate list of the wrong set. Producer `seon.db` refusal builder.
- `registration-refusal` returns a flat map without `:seon.error/type` (probe 3).

## Findings for root to file

- `seon.turn.batch` / `a-function-using-a-schema-registered-in-the-same-form-is-not-admitted`: `(do (register! :k [:int]) (defn ^{:malli/schema [:=> [:cat :k] :int]} f [x] x))` is armed but not admitted; fix `write-entity` to use `arm/arming-projection`; proof: probe 5 form.
- `seon.db` / `a-refused-write-names-the-undeclared-key-among-the-registered-candidates`: `:seon.db/registered-candidates` lists installed keys and omits the refused one (probe 2).
- `seon.schema` / `a-registration-refusal-carries-an-error-type`: `register!` of `[:maybe :int]` returns a map with layer and member but no `:seon.error/type`.

## Design check

The design held: the delta already carries the write-time observation for schemas, and the only fragile piece is a
single call site that reads the wrong projection. Nothing in the case needs a registry or a second path. The spec row's
"write/read" is the one place HEAD disagrees (same-pass attribute write is refused, next pass accepts); I recorded it
as a Q7 question and recommend leaving it. RESET NEEDED: none. Branch `s5-03g` left for root to retire.
