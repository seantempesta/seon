---
type: research
status: complete
created: 2026-09-19
tags: [schema, data-model, audit]
---

# Schema audit C — `seon.ns.import` … `seon.wake`, 2026-09-19

Read-only audit of files **141–210** of `ls resources/seon/schemas/*.edn`
(`seon.ns.import.edn` … `seon.wake.edn`, 70 files, 5 768 lines): the ns-binding,
operator, plan, print, problems, program, reconcile, render, repl, runtime,
schedule, schema, sci, search, source, store, test, turn and wake families.
No JVM, no gate, no cluster operation, no edit but this file.

**Read end to end before looking at a schema:** AGENTS.md §2–§3;
[the data-modeling decision guide](../../../seon/architecture/data-modeling-guide.md)
(all 837 lines); [the data-modeling skill](../../../../.agents/skills/data-modeling/SKILL.md);
[the datahike skill](../../../../.agents/skills/datahike/SKILL.md);
`src/seon/schema/datahike.clj` and `src/seon/schema/form.cljc` (the bridge and
what makes an attribute a database attribute at all).
**Skimmed for prior art, and cited below wherever a finding is already
recorded:** [schema design review](schema-design-review-2026-09-17.md),
[datahike modeling study](datahike-modeling-study-2026-09-17.md),
[message, wake and provenance modeling](message-wake-and-provenance-modeling-2026-09-17.md),
[the test system is the database PRD](../plan/test-system-is-the-database-prd-2026-09-17.md),
[the namespace data model](../plan/namespace-data-model-2026-09-16.md).

## 0. The three facts that decide how to read everything below

1. **An attribute is installed as a database attribute by TWO independent
   routes** (`src/seon/schema/form.cljc:130-157`): it appears as an entry of a
   map carrying `:seon.db/attributes true`, **or** it carries any of
   `:seon.db/identity`, `/unique`, `/index`, `/component`, `/no-history?`,
   `:db.secondary/only` (`:9-15`). The second route installs attributes no
   entity map declares, which is how several findings below are storable
   despite appearing only in in-memory contracts.
2. **A mixed `[:or …]` union stores as an EDN string** (`src/seon/schema/datahike.clj:150-168`):
   the bridge collects its members' types, and when more than one survives it
   falls to `:db.type/string`. That is the mechanism behind C4.
3. **`:any` and `:maybe` are clean in this slice.** All 17 occurrences carry
   `:seon.schema.admission/exemption :seon.schema.admission/polymorphic-boundary`
   with a written reason, confirming the review's §A.8 for these 70 files. The
   residue is laundering, not raw `:any` — see C16.

## 1. Ranked class table

| # | Class | Instances | Severity | One-line fix rule |
|---|---|---|---|---|
| C1 | Authority facts whose docstring says "required at admission" while the entity map marks them `{:optional true}` | 28 entries across 2 entity maps | **blocker** | If the writer always supplies it, declare it required so a sweep refuses instead of leaving authority silently absent. |
| C2 | Absence read as health in a gate reader, over a cardinality-many key that stores nothing when empty | 1 reader + 1 entity map | **blocker** | Give the adoption a required positive observation fact; guard on that fact, never on the membership. |
| C3 | A purely derived report key installed as a stored attribute with no writer | 1 | **blocker** | Delete it from the entity map; the derivation is the answer. |
| C4 | One render-pair key meaning both "the renderer" and "the rendered output", which forces the symbol to store as an EDN string | 3 | **blocker** | Close the union to `:qualified-symbol`; the output meaning already has `:seon.render/rendered`. |
| C5 | Retired-vocabulary, untyped polymorphic refs with no declared deletion behaviour | 2 | blocker | Delete with the third answeredness path the turn PRD retired. |
| C6 | Events encoded as booleans or as the absence of a request key | 5 | friction | A transaction ref for an observed event; a named enum arm for a request default. |
| C7 | Stored derived state and materialized aggregates | 11 | friction | Delete and join; a run's own facts are not copied onto the domain entity. |
| C8 | Hand-written pulled-shape mirrors of an entity schema | 8 | friction | Derive the pulled shape from the entity schema under the reader's selector. |
| C9 | `:seon.db/ref` declarations with no docstring naming cascade/sweep/refuse/value | 60 of 89 ref mentions | friction | Every ref states its lifecycle consequence in its own docstring. |
| C10 | Identity minted where none is needed, or declared on a ref | 2 | friction | Identity is a natural key on a value, never an eid and never on a component row. |
| C11 | Unconstrained `:map` / bare predicate at a load-bearing boundary | 13 | friction | Declare the real shape; `map?` on the schema projection is the worst of them. |
| C12 | Inert or false schema properties and docstrings | 5 | cleanup | An unread property is deleted; a docstring that contradicts a sibling attribute is a defect. |
| C13 | Two clocks in one family (`-at` instant beside `-tx` refs) | 4 | cleanup | Recording time is a transaction ref; only a genuinely external observation stays an instant. |
| C14 | One concept declared two or more times | 5 clusters | cleanup | One declaration; the others alias it. |
| C15 | Render pair declared per attribute instead of per entity schema | 2 | cleanup | One AI/HTML pair per entity schema. |
| C16 | An exempted `:any` alias (`:seon.schema/value`) reused as an ordinary member type | 31 uses | cleanup | An exemption is granted per boundary, not inherited by every member that names the alias. |

Measured: 89 `:seon.db/ref` mentions in the slice; 29 carry a `:description`
inside the declaration (four-line window heuristic, stated as such). 31 uses of
`:seon.schema/value`. 6 `{:seon.db/identity false}` negations repository-wide.

---

## 2. Findings per class

### C1 — "Required at admission" is not a stored fact (blocker)

`seon.test.run.edn` and `seon.test.member.edn` are the best-documented files in
the slice: nearly every ref names its sweep. The declarations then undo it.

- `resources/seon/schemas/seon.test.run.edn:8` —
  `:cluster [:and {:description "… Required at admission; the optional stored ref sweeps on cluster deletion, leaving authority unavailable."} :seon.db/ref]`,
  and the entity map marks it `{:optional true}` (`:88`). Same shape at
  `:selection-tx` (`:12` vs `:97`), `:covered-by` (`:11` vs `:96`),
  `:members` (`:10` vs `:95`).
- The `:seon.test.run/run` map (`:79-99`) marks **15 of its 17 entries
  optional**; only `/id` and `/at` are required. `:seon.test.member/member`
  (`:19-35`) marks **13 of 15 optional**; only `/symbol` and `/reasons` are
  required.

This is exactly the phrase the modeling study rejected as a deletion guarantee
(`datahike-modeling-study-2026-09-17.md:386`, "Optional refs required 'when
present' — **Reject that phrase as a deletion guarantee**"). The consequence is
the project's named failure class in the gate's own authority: "this run has no
cluster" and "the cluster was deleted" are byte-identical, and so are "zero
members were selected" and "the selection transaction was removed".

**Fix.** `/cluster`, `/selection-tx`, `/basis-t`, `/policy`, `/input-digest`,
`/include-long?` become required entries of `:seon.test.run/run`; `/worker`,
`/claim-tx` stay optional (a member legitimately has no claim yet) but
`/completed-tx` gains the stated invariant that counts require it. Migration:
reset-only for the schema; consumers by `rg`:
`src/seon/test/runner.clj`, `src/seon/test.clj`, `test/seon/test/runner_test.clj`,
`test/seon/test/selection_test.clj` (the last two are another lane's
uncommitted edits at the time of writing).

### C2 — The adoption gate reads an empty collection as "nothing changed" (blocker)

`resources/seon/schemas/seon.test.edn:284-288`:

```clojure
:seon.test/adoption
[:map {:seon.db/attributes true}
 [:seon.test/adoption-cluster :seon.test/adoption-cluster]
 [:seon.test/adoption-identities {:optional true} :seon.test/adoption-identities]
 [:seon.test/adoption-inputs {:optional true} :seon.test/adoption-inputs]]
```

Both membership keys are cardinality-many, so `#{}` emits no datoms
(`db/transaction.cljc:739-770`, `:718-737`). The reader is
`seon.test/check-adoption` (`src/seon/test.clj:1495-1525`). Its docstring says
*"Missing adoption facts are unknown, never green"* (`:1499`), and it then
guards **only** on `:seon.test/adoption-cluster` (`:1518`), passing
`(mapv program/row-identity (:seon.test/adoption-identities adoption))` — `[]`
when the key has no datoms — into `check`. `seon.test/check` takes its
"nothing changed" arm on `(or (seq changed) (seq file-symbols))`
(`src/seon/test.clj:1216`, `:1227`) and reports unchanged skips. So **"the
adoption recorded no changes" and "the recorder failed to write its changes"
produce the same green-ish check**, and the docstring asserting otherwise is
false.

**Fix.** G4: a required positive observation fact on the adoption
(`:seon.test/adoption-observed-tx`, a transaction ref, required in the map), so
`adopted? = presence` and "adopted, nothing changed" is that fact with no
members. Guard `check-adoption` on it. The adoption row also has **no identity
attribute** (it is selected by `(max ?t)` over `:seon.test/adoption-cluster`,
`:1506-1507`) and `:seon.test/adoption-identities [:set :seon.db/ref]`
(`seon.test.edn:281`) is an untyped ref set: deleting any cited declaration
sweeps it silently, which is the same disease one layer down. Migration:
reset + `src/seon/cluster.clj:2579-2581` (writer) and
`src/seon/test.clj:1495-1525` (reader).

### C3 — A derived report key installed as a stored attribute with no writer (blocker)

`resources/seon/schemas/seon.turn.edn:27-29` makes
`:seon.turn.work/situation` an entry of `:seon.turn/turn`, which carries
`:seon.db/attributes true` (`:4`) — so it is installed as a
`:db.type/keyword` attribute on every turn. `rg 'seon\.turn\.work/situation'`
over `src/` returns **zero writers and zero readers**: the only occurrences are
the three schema files (`seon.turn.edn`, `seon.turn.loop.edn:218`,
`seon.turn.work.edn:50-79`) and 14 test assertions over *derived reports*
(`test/seon/cluster/turn_test.clj`, `test/seon/bootstrap_test.clj`,
`test/seon/no_provider_test.clj`, `test/seon/background_test.clj`,
`test/seon/cluster/agent_test.clj`). The situation is computed by
`seon.turn/next-agent-work`; `seon.turn.work/next` is a
`[:multi {:dispatch :seon.turn.work/situation}]` over it (`seon.turn.work.edn:49-82`).

A reader of the schema — including an agent rendering a turn — is told the turn
records its situation. It does not. Under the owner's goal ("attributes and
values and refs on the agent determine ALL states") this is the worst kind of
entry: a stored state that is always absent.

**Fix.** Delete the entry from `:seon.turn/turn` (one line pair,
`seon.turn.edn:27-29`). `:seon.turn.work/situation` stays as the in-memory
dispatch key it is. Migration: reset-only; no `src/` consumer.

### C4 — The render pair cannot be stored as a symbol (blocker)

`resources/seon/schemas/seon.render.edn:19` `:ai [:or :qualified-symbol :string]`
and `:185` `:html [:or :qualified-symbol :seon.render/hiccup]`. Already found as
B.12 in the schema design review; **still present**, and the storage
consequence was not recorded there:

- `:seon.render/ai` and `:seon.render/html` are authored as **properties** on
  entity maps (70 `:seon.render/ai` mentions across this slice alone), and
  `seon.schema.datahike/database-attributes-for-in`
  (`src/seon/schema/datahike.clj:325-337`) installs every namespaced property
  that `storable-attribute-in?` admits.
- `form->datahike-value-type-in` on `[:or :qualified-symbol :string]` collects
  two distinct types and falls through to the EDN-string codec
  (`src/seon/schema/datahike.clj:150-168`).
- Therefore the render pair is stored with `:db/valueType :db.type/string`,
  which **violates the owner's 2026-09-17 ruling that anything that IS a symbol
  is stored as a symbol**, and makes `[?s :seon.render/ai ?sym]` unqueryable
  with a symbol binding — `seon.program/render-declared-by`
  (`resources/seon/schemas/seon.program.edn:141-143`) must read the decoded
  value rather than join on it.

`:seon.render/form` compounds it: AGENTS.md §3's vocabulary table lists it as a
**legacy spelling**, yet it is declared live (`seon.render.edn:176`), admitted
by `:seon.render/output` (`:192-193`), carried in `:seon.render/rendered`
(`:194`), required by `:seon.render.walk/request` (`seon.render.walk.edn:42-44`)
and authored as a property on entity maps in this slice
(`seon.ns.edn:15`, `seon.schema.edn:100`, `seon.agent.edn:127`,
`seon.message.edn:29`, `:66`). Either the table is stale or those declarations
are. It is one or the other and no source says which.

**Fix.** `:ai` and `:html` become `:qualified-symbol`; the rendered-output
meaning moves to `:seon.render/rendered` (`:194`), which already exists.
Migration: reset + `src/seon/render.clj` (the `raw-output`/`present-output`
seam, `:1266-1277`), `src/seon/fn.clj:1748-1815`, `src/seon/schema.clj:1561`.

### C5 — `trigger`: a retired spelling, untyped, undocumented, still live

- `resources/seon/schemas/seon.turn.edn:45` —
  `:trigger [:and #:seon.db{:index true :seon.wake/context-inert true} :seon.db/ref]`.
  No `:description` at all. It is optional on the turn (`:11`), on
  `:system-run-request` (`:151`) and on `:generated-run-request` (`:158`).
- `resources/seon/schemas/seon.runtime.edn:7` —
  `:trigger [:and {:description "The fact whose wake opened the latest turn."} :seon.db/ref]`,
  optional on `:seon.runtime/entity` (`:15`).

It is written with **two unrelated target families**: a message
(`src/seon/bootstrap.clj:124`, `:883`) and an issue (`src/seon/issue.clj:936`),
so no schema states what it points at — the untyped-ref defect N12 named for
`:seon.eval/origin`, unresolved here. It is read by three Datalog clauses
(`src/seon/bootstrap.clj:188`, `src/seon/eval/drive.clj:115`,
`src/seon/bootstrap_drive.clj:115`) and rendered in `:seon.agent/situation`
(`resources/seon/schemas/seon.agent.edn:137-139`). "trigger" is on the turn
PRD's retired-spellings list, and
[message-wake §3.6](message-wake-and-provenance-modeling-2026-09-17.md) rules it
deleted with `close-call`'s third answeredness derivation. §1h's other halves
have **landed** (`:seon.message/to` is the listened opens-turn edge,
`seon.message.edn:19-30`; `about` is a value token, `:31-33`;
`:seon.message/assignment` is its own attribute, `:34-36`; `:seon.turn/handled`
is the handling-turn claim with a docstring that names its sweep,
`seon.turn.edn:44`). `trigger` did not travel with them.

**Fix.** Delete both attributes with the retired derivation. Migration: reset +
`src/seon/bootstrap.clj`, `src/seon/issue.clj`, `src/seon/eval/drive.clj`,
`src/seon/bootstrap_drive.clj`, `resources/seon/schemas/seon.agent.edn:137-139`.

### C6 — Events as booleans, and a request default that is an absence

| Instance | file:line | Why it is wrong |
|---|---|---|
| `:seon.test.member/began?` / `/ended?` | `seon.test.member.edn:14-15`, optional at `:32-33` | Docstrings say "Whether the **begin-test-var event was observed**". An observed event is a datom; a boolean plus optionality makes three states (`true`, `false`, absent) for two observations, and nothing says which absence means "not observed" versus "not recorded". Fix: `/began-tx` and `/ended-tx` transaction refs; absence is "not observed". |
| `:seon.turn.work/answered?` | `seon.turn.work.edn:5-6` — `[:= :any]` | A one-member enum whose *absence* is the other arm: the request docstring says "absent means the unanswered ones, `:any` means every one of them" (`:9-10`). Fix: `[:enum :unanswered :any]`, required. The message-wake note measured that the `:any` mode returned **0 for every agent** because the two readers hand different databases to the same seek (`message-wake-and-provenance-modeling-2026-09-17.md` §2); with `:seon.message/inbox` now gone the seek should agree, which is verifiable only on a live cluster (§5). |
| `:seon.test.run/terminated?` | `seon.test.run.edn:44` | Boolean for "caller observed execution return or exact process exit; a bound firing alone is false" — three real outcomes (returned, exited, bound fired) compressed to two. The sibling `/terminated-tx` (`seon.test.member.edn:16`) is the right construction; the boolean is its duplicate. |
| `:seon.operator/flow` | `seon.operator.edn:89` — `[:or [:enum :unknown] :map]` | A second encoding of "unknown" beside `:seon.operator/health [:enum :observed :unknown]` on the same entity (`:90`, both in `:observation` at `:91-98`). Fix: one unknown, on `health`; `flow` becomes a declared shape or absent. |
| `:seon.operator.claim/live?` | `seon.operator.claim.edn:18` | A boolean liveness flag beside the process census that derives liveness. Derive-or-die; the claim record is a file, not a datom, so this is friction, not a datom defect. |

### C7 — Stored derived state and materialized aggregates

Already ruled and **still present**:

- `:seon.test.failure/first-run`, `/last-run`, `/seen-count`, `/last-seen-at`
  (`seon.test.failure.edn:18-21`), **all four required** on `:failure`
  (`:54-57`). Review rows N41/J4 marked them delete-and-derive. Note the second
  consequence of requiring them: retracting a run entity now **refuses** on
  every failure that names it, because the required swept key fails
  re-validation (`src/seon/db.clj:3052`) — a lock on run deletion that no
  docstring mentions.
- `:seon.test/run-at` and `/run-basis-t` (`seon.test.edn:30-31`, on the entity
  at `:132-137`) are copies of `:seon.test.run/at` and `/basis-t`
  (`seon.test.run.edn:1`, `:5`) onto the domain entity, which AGENTS §3
  forbids. Review row N40, still present.
- `:seon.test/failing-assertions` (`seon.test.edn:37-39`, entity `:141-143`)
  beside the `:seon.test/failures` component list (`:41`). Review row B.3,
  still present.
- **New:** outcome counts now live in **two** places —
  `:seon.test/pass-count`/`fail-count`/`error-count` on the test row
  (`seon.test.edn:123-131`) and `:seon.test.member/pass-count`/… on the member
  (`seon.test.member.edn:11-13`, `:29-31`). The test-system PRD's stage-0
  amendment ruled "counts on the member"
  ([PRD §0b](../plan/test-system-is-the-database-prd-2026-09-17.md)); the test-row
  copies were not deleted.
- **New, a docstring contradiction:** `:seon.test/host`
  (`seon.test.edn:227-229`) declares *"Where a test runs, **derived from its
  program-graph reach, never declared on the test**"* — and
  `:seon.test.member/host` (`seon.test.member.edn:9`, stored at `:26`) declares
  exactly that value on the member. One of the two sentences is false. If the
  member's fact is the *observation* of where it actually ran, say so; today a
  reader cannot tell the derivation from the observation.

### C8 — Hand-written pulled-shape mirrors (the D1 class), all still present

| Mirror | file:line | Of what |
|---|---|---|
| `:seon.test.failure/value` | `seon.test.failure.edn:58-71` | `:seon.test.failure/failure` (`:35-57`), including the per-attribute widening the pulled-shape study names: `[:seon.test.failure/file {:optional true} [:map [:db/id :int] [:seon.fn.file/relative-path …]]]` (`:69`). |
| `:seon.test.failure/report` | `seon.test.failure.edn:23-33` | the same entity again, nine keys, all optional. |
| `:seon.test.report/report` | `seon.test.report.edn:6-23` | **a third encoding of one failure**, new since the 2026-09-17 review: an identity, a symbol and 13 of `seon.test.failure`'s own attributes. Three representations of one concept now exist in one family. |
| `:seon.test.run/provenance` | `seon.test.run.edn:67-74` | `:seon.test.run/run` (`:79-99`). Review row B.10 said derive one from the other; instead `/run` has since grown 12 keys and the two have diverged further. |
| `:seon.test/result` | `seon.test.edn:61-83` | a `select-keys` of `:seon.test/test` (`:85-146`) plus `[:vector :seon.test.failure/value]`. |
| `:seon.wake/unanswered` | `seon.wake.edn:30-40` | `[:db/id :int]` plus the attribute and `:t` of a wake datom. |
| `:seon.render.transcript/pulled-transaction` | `seon.render.transcript.edn:12-17` | `[:db/id :int] [:db/txInstant {:optional true} :inst]`. |
| `:seon.message/pulled` | `seon.message.edn:83-102` | `:seon.message/message` (`:61-82`) with every ref widened to `:seon.db/ref`. |

### C9 — Refs with no declared lifecycle

60 of 89 `:seon.db/ref` mentions in the slice have no `:description` inside the
declaration. The reset batch's standing policy is *"Document the chosen
behavior; no universal enforcement"*
([guide §2.3](../../../seon/architecture/data-modeling-guide.md)). The
undocumented ones that matter most:

- `resources/seon/schemas/seon.schedule.task.edn:2-4` — `:owner`, `:function`,
  `:schedule`, all required refs, **not one docstring in the file**.
  `:function [:and {:seon.db/index true :seon.fn/reference-to :seon.fn/sym} :seon.db/ref]`
  is review row N9, still present, and `:seon.fn/reference-to` is the
  data-modeling skill's named tell for a name-observation wearing a ref.
- `resources/seon/schemas/seon.schedule.fire.edn:3` — `:task`, a required ref
  into a deletable task, no docstring. Its sibling `:agent` (`:7-14`) is the
  best-documented ref in the slice; the file is a one-line-apart demonstration
  of the gap.
- `resources/seon/schemas/seon.test.failure.edn:3` — `:test :seon.db/ref`,
  required (`:38`), a back-pointer up the `:seon.test/failures` component edge
  (`seon.test.edn:41`). The datahike skill names this exact pair and says
  *"one fact in two encodings, which the next writer can put out of step. Do
  not add one."* It is there.
- `resources/seon/schemas/seon.test.failure.edn:14` — `:file`, the resolved ref
  beside `/reported-file` (`:22`). Review row N11, still present.
- `resources/seon/schemas/seon.schema.edn:81` — `:shape :seon.db/ref`,
  `resources/seon/schemas/seon.schema.shape.child.edn:3` and
  `.entry.edn:4` — `:schema :seon.db/ref`: the shared-shape refs the modeling
  study says to keep as ordinary refs (O2). Correct, and undocumented, so the
  next reader cannot tell a deliberate ordinary ref from an unconsidered one.

The **error-declaration manifests added 2026-09-18** are a uniform, correct
counter-example worth preserving: 26 component refs across the slice
(`:seon.operator/operation-observation`, `:seon.program/error-subject`,
`:seon.render.walk/step-observation`, …) all declare
`:seon.db/component true` with `:seon.db/component-schema`, which is the
cascade behaviour stated structurally. They carry no prose, but the property
says what prose would.

### C10 — Identity where none is needed

- `resources/seon/schemas/seon.runtime.edn:1-2` —
  `:agent [:and {:seon.db/identity true :description "The agent owning this runtime component."} :seon.db/ref]`.
  This installs `:db/unique :db.unique/identity` with `:db/valueType
  :db.type/ref` (`src/seon/schema/datahike.clj:271`, `:258-261`), i.e. **an
  entity id as an identity value**, on a row that is already a
  `:seon.db/component` of the agent (`seon.agent.edn:2`). The deletion study
  §8.1 says do not invent identity attributes on component rows to make a
  selector see them; the component's owning-value validation
  (`src/seon/db.clj:3071`) is the mechanism that makes it unnecessary. Fix:
  drop `:seon.db/identity`, keep `:seon.db/index`; the runtime is reached
  through `:seon.agent/runtime`.
- `resources/seon/schemas/seon.problems.edn:15` —
  `:id [:string {:min 1, :seon.db/identity true}]`. No `:seon.db/attributes`
  map in the population contains it, yet the identity property alone installs
  it (`src/seon/schema/form.cljc:150-154`), and it is asserted onto evaluation
  rows (`src/seon/turn.clj:2541`, declared at `seon.cluster.eval.edn:74-76`).
  `:db.unique/identity` participates in **upsert**
  (`db/transaction.cljc:641-713`): two evaluations that ever compute the same
  problem id silently become one entity. `seon.problems/problem-id` is derived
  per (turn, ordinal) (`src/seon/problems.clj:212`), so it holds today by
  arithmetic, not by declaration. Fix: `:seon.db/index true`, not identity —
  unless a problem is genuinely its own entity, in which case it needs a map.

### C11 — Unconstrained `:map` and bare predicates at load-bearing boundaries

| Declaration | file:line |
|---|---|
| `:seon.schema/projection map?` | `seon.schema.edn:73` |
| `:seon.schema/compiled-validator fn?` | `seon.schema.edn:74` |
| `:seon.schema/explanation :map` | `seon.schema.edn:47` |
| `[:seon.schema/database-value :map]` | `seon.schema.edn:60` |
| `:seon.reconcile/desired [:vector [:map]]` | `seon.reconcile.edn:3` |
| `:seon.problems/request [:map]` | `seon.problems.edn:88` |
| `[:seon.render.call/static-evidence :map]` | `seon.render.call.edn:5` |
| `[:seon.render/selection-inspection {:optional true} [:map]]` | `seon.render.edn:37` |
| `[:map-of … [:vector :seon.schema/value] [:vector [:map]]]` | `seon.render.edn:47-48` |
| `:seon.render.transcript/entries [:vector … :map]` | `seon.render.transcript.edn:7-11` |
| `[:seon.sci.admit/caps :map]` | `seon.sci.admit.edn:53` |
| `[:seon.sci.eval/metadata :map]` | `seon.sci.eval.edn:85` |
| `[:tx-meta {:optional true} :map]` | `seon.store.edn:39` |

Two deserve naming. **`:seon.schema/projection map?`** is the value that law
2.1 is about — every contract, every bridge derivation and every write
validation takes it — and its declared shape admits `{}`. The projection's
real members are already named elsewhere
(`:seon.schema.projection/forms` at `src/seon/schema/datahike.clj:38`,
`:seon.config.db/validation-node-limit` carried by it,
`src/seon/schema.clj:317`), so the shape is writable today.
**`[:seon.sci.admit/caps :map]`** (`seon.sci.admit.edn:53`) sits in the same
request whose `[:or …]` arm requires the properly declared
`:seon.sci.admit/caps` (`:25-30`, `:65-67`) — the weak arm is a typo-grade
hole in a bounded-execution seam.

### C12 — Inert properties and false docstrings

- **`:seon.db/cardinality :many`** appears on exactly two attributes —
  `seon.test.edn:38` (`:failing-assertions`) and `seon.test.accretion.edn:64`
  (`:gate-tests`) — and is **declared nowhere and read nowhere**:
  `rg 'seon\.db/cardinality' src/ resources/` returns only those two lines.
  The bridge derives cardinality from the collection head
  (`src/seon/schema/datahike.clj:196-202`), and the property is not in
  `database-attribute-properties` (`src/seon/schema/form.cljc:9-15`). It reads
  as authority and does nothing. Delete both.
- `:seon.turn/reply [:string #:seon.db{:no-history? true …}]`
  (`seon.turn.edn:126`) has **no description at all**, while `:db/noHistory`
  deliberately removes the retraction guarantee
  (`db/transaction.cljc:440-484`): "no reply" and "a reply that was
  overwritten" converge. O8/N42 require the docstring to say so.
- `:seon.test/host` versus `:seon.test.member/host` (C7 above).
- `seon.test/check-adoption`'s docstring (C2 above).
- `:seon.ns/steward`'s docstring, "**The one agent** that stewards this
  namespace" (`seon.ns.edn:36-37`) — see §4.

### C13 — Two clocks in one family

`seon.test.run.edn` records its own opening as `:at :inst` (`:1`, required at
`:83`) while every other transition in the same family is a transaction ref:
`/selection-tx` (`:12`), `:seon.test.member/claim-tx` (`:8`), `/completed-tx`
(`:10`), `/terminated-tx` (`:16`). `:seon.test.member/claimed-at`
(`seon.test.member.edn:7`) is a second instant beside its own `claim-tx`.
`:seon.schedule.fire/observed-at` (`seon.schedule.fire.edn:6`) is recording
time and should be a `-tx`; its sibling `/nominal-at` (`:4-5`) is a genuinely
external schedule time and correctly stays an instant — J3's split, in one
file, half applied.

### C14 — One concept, several declarations

1. **Digest formats, five spellings.** `:seon.source/digest`
   (`seon.source.edn:10-11`, hex-constrained and carrying
   `:seon.db/identity true` **on the format**, review row N36 — still present,
   with 6 `{:seon.db/identity false}` negations repository-wide, including
   `seon.test.edn:5`, `seon.test.run.edn:4` and `:17`,
   `seon.test.failure.edn:16`); `:seon.test/failure-identity
   [:string {:min 64, :max 64}]` (`seon.test.edn:35-36`, **not** hex-constrained
   — review row N35, still present); `:seon.test.failure/id
   [:string {:min 12 :max 12 …}]` (`seon.test.failure.edn:2`);
   `:seon.test.run/git-sha [:re "^[0-9a-f]{40}$"]` (`seon.test.run.edn:2`,
   legitimately different — a git sha, not a SHA-256);
   `:seon.program/analyzed-source-digest [:string {:min 64 :max 64}]`
   (`seon.program.edn:1-3`, also unconstrained as to hex, and this one is now
   **required** on `:seon.test/test`, `seon.test.edn:91`).
2. **Branch.** `:seon.source/branch :keyword` (`seon.source.edn:1`) and
   `:seon.store/branch :keyword` (`seon.store.edn:1`): two keys, one noun,
   used in one family apiece (`seon.test.run/branch` aliases the first,
   `seon.operator.collect/branch` uses the second).
3. **Process identity, three spellings.** `:seon.cluster.process/identity`
   (`seon.operator.claim.edn:11`), `:seon.dev.process/identity`
   (`seon.operator.process-census.edn:35`), and an **inline anonymous map** of
   `:seon.db.process/pid` + `/start-instant` in
   `seon.test.run.edn:31-33` (`/dead-workers`) and `:38-39` (`/claim-request`).
4. **The typed-sibling key family, declared three times** — review row C.4,
   still present: `seon.schema.map-entry.edn` (11 attributes, including
   `:key-kind` with a `:nil` arm at `:3`, a derived stamp of the stored value)
   pasted into `seon.schema.shape.entry.edn:17-46`.
5. **`:seon.problems/author :seon.agent/id`** (`seon.problems.edn:25`) beside
   `[:seon.agent/id :seon.agent/id]` (`:24`) in the **same map**: two keys for
   which agent, one of them an alias of the other's type.

Two smaller ones: `:seon.test.accretion/report-edn`
(`seon.test.accretion.edn:68`) is the N23 hand-rolled EDN string the bridge
codec already owns; `:seon.schema.shape.child/value-edn`
(`seon.schema.shape.child.edn:4`) is the N22 instance in this slice. Both
still present.

### C15 — Render pairs declared per attribute

The data-modeling skill: *"One entity schema declares one AI/HTML render pair
… Never declare a pair per scalar attribute."* In this slice:
`:seon.turn/agent` (`seon.turn.edn:51-58`) declares
`render-history-ai`/`render-history-html` on a plain scalar ref, and
`:seon.runtime/turns` (`seon.runtime.edn:3-6`) declares the same pair on a
component collection — while `:seon.turn/turn` (`:4-6`) and
`:seon.runtime/entity` (`seon.runtime.edn:10-12`) each already declare their
own. `seon.problems.edn:62-73` carries an excellent inline comment explaining
precisely why a pair on an open one-key map is dangerous (it is selected for
every row carrying that key); the same reasoning applies to a pair on a ref.

### C16 — `:any`, laundered through an alias

`:seon.schema/value` is `[:any {:seon.schema.admission/exemption …}]`
(`seon.schema.edn:44-46`), and the slice names it **31 times** as an ordinary
member type: `:seon.program/definition [:map-of :qualified-keyword :seon.schema/value]`
(`seon.program.edn:201-203`), `:seon.source/upsert-row` (`seon.source.edn:67-71`),
`:seon.program/identity [:tuple :seon.program/identity-attribute :seon.schema/value]`
(`seon.program.edn:56-57`), `:seon.reconcile/adopt-identities`
(`seon.reconcile.edn:1`), `:seon.render.walk/entity-lookup`
(`seon.render.walk.edn:21-23`), `:seon.repl/key` (`seon.repl.edn:161`), and 25
more. The exemption's own reason — *"arbitrary in-memory Clojure values"* — is
a statement about **one** boundary; every reuse inherits a licence it was not
granted. Two of these compound with a dependency behaviour: inside a
`[:tuple …]`, `check-tuple` refuses only when the member results *differ*
(`db/transaction.cljc:1037`), so a tuple with an `:any` member checks the other
member alone.

---

## 3. Agent-state derivability

"We have a set of attributes and values and refs on the agent and that needs to
accurately determine ALL states." The agent entity is small
(`resources/seon/schemas/seon.agent.edn:3-26`): `id` (identity, arming),
`archived-tx?`, `namespace?`, `plan` (component), `settings` (component),
`runtime` (component). Everything else is an incoming ref or a derivation.

| # | State | Derivable? | From what, or why not |
|---|---|---|---|
| 1 | exists | **yes** | `:seon.agent/id` datom; it is also the arming attribute (`seon.agent.edn:73-79`). |
| 2 | archived | **yes** | `:seon.agent/archived-tx` presence (`seon.agent.edn:1`, `:20`). Ruling 1g has **landed**; the guide still marks it [TARGET]. |
| 3 | has an open turn | **yes** | `:seon.agent/runtime` → `:seon.runtime/turns` → no `:seon.turn/closed-tx`; decided at the writer (`src/seon/turn.clj:189`, `:417`). |
| 4 | which process owns that open turn | **only through transaction provenance** | The turn entity carries no process attribute by ruling; the answer is `:seon.db/process` on the opening transaction. Whether that stamp is actually written on a turn-opening transaction is not checkable from bytes (§5). |
| 5 | crashed-open versus currently-running | **no** | Identical datoms. Boot recovery closes open turns (`src/seon/turn.clj:1680`), so the distinction exists only between process lifetimes, and nothing on the row says which process is alive. |
| 6 | turn proc armed / parked / dead | **no** | The graph, routing and SCI contexts are process-local atoms (`:seon.agent/routing`, `:seon.agent/context-state`, `seon.agent.edn:89-100`), so no datom distinguishes a parked proc from a dead one. Open issue: [a parked turn proc pings "unknown" on a healthy agent](../../../seon/issues/a-parked-turn-proc-pings-unknown-on-a-healthy-agent.md). **The largest undeterminable state in the family.** |
| 7 | unanswered wake | **yes** | Listened `:seon.message/to` (`seon.message.edn:19-30`), `:seon.schedule.fire/agent` (`seon.schedule.fire.edn:7-14`) and the other listened attributes, by `:t` versus `seon.turn/latest-answering-turn-t`. §1h has landed; `:seon.message/inbox` and `read-tx` are gone from the schema. |
| 8 | wake inside versus outside | **yes, but fail-open** | `:seon.message/from` is the sole `:seon.wake/inside` marker (`seon.message.edn:109-115`) — the ruled construction. It is `{:optional true}` (`:70-72`), so "a human sent it" and "the writer omitted the sender" are the same bytes, and the absence arm is the **expensive** one (it resets the paid turn bound). |
| 9 | turns remaining / budget exhausted | **derived, not a fact** | From the latest outside wake's `:t` and the turn count; surfaced only as the in-memory `:seon.turn/turns-remaining` (`seon.turn.edn:125`) inside `:seon.agent/situation` (`seon.agent.edn:140-141`). No datom; a reader cannot ask "which agents are out of turns" as a query. |
| 10 | assigned task | **yes** | `:seon.message/assignment` (`seon.message.edn:34-36`), a value that survives evaluation deletion. |
| 11 | declined assignment | **no** | The attribute's docstring covers "assigned **or** declined" with one value and no arm distinguishing them; the direction of the message is the only signal, and that is not a fact about the assignment. |
| 12 | message handled | **yes** | `:seon.turn/handled` (`seon.turn.edn:44`), the handling-turn claim ruled by 70/1h, with a docstring that states its sweep and that it does **not** decide answering. |
| 13 | namespace responsibility | **yes, but singular** | `:seon.ns/_steward` reverse (`seon.ns.edn:34-38`). See §4. |
| 14 | assigned working namespace | **yes** | `:seon.agent/namespace` (`seon.agent.edn:84-88`), explicitly not unique. |
| 15 | namespace unstewarded versus its steward deleted | **no** | `:seon.ns/steward` is an optional ref (`seon.ns.edn:27-30`); the sweep leaves the same bytes as never-assigned. Recorded in the review's §A.2 table; still present. |
| 16 | private layer / isolated SCI context | **no, by ruling** | In memory only; the durable record is the shown text. Correctly undeterminable. |
| 17 | plan state | **yes** | `:seon.agent/plan` component → `my.plan.item/*-tx` refs. |
| 18 | settings / config overlay | **yes** | `:seon.agent/settings` component. |
| 19 | provider attempts and failover | **yes** | `:seon.turn/attempts` components (`seon.turn.edn:130-134`). |
| 20 | never spoke versus compacted | **yes, indirectly** | Compaction retracts evaluations, not turns (`src/seon/turn.clj:2207`), so surviving turn rows with no evaluations distinguish the two. It is a join, not a fact, and nothing declares the invariant. |
| 21 | the turn's own situation | **derived only** | `:seon.turn.work/situation` is declared on the stored turn and never written — C3. |

**Summary: 14 derivable, 1 derivable only through transaction provenance,
4 undeterminable (5, 6, 11, 15), 1 correctly undeterminable by ruling (16),
1 derivable but unqueryable as a fact (9).**

## 4. `:seon.ns/steward` is cardinality-one; what widening costs

`resources/seon/schemas/seon.ns.edn:34-38` declares
`:steward [:and {:description "The one agent that stewards this namespace …"} :seon.db/ref]`
— a scalar ref, optional on `:seon.ns/ns` (`:27-30`). The owner's position is
that several agents can be responsible for a namespace, so the docstring is
already false as intent.

`rg` finds **11 consumer sites in 5 files**, and they split cleanly:

**Unchanged by a `[:set :seon.db/ref]` widening** (Datalog clauses bind a
member either way):
`src/seon/error.clj:1483`, `:1954`, `:2008` (fault routing),
`src/seon/cluster/agent.clj:144`, `:434`,
`src/seon/problems.clj:278` (`(not [?namespace :seon.ns/steward _])`, the
unstewarded detector).

**Broken silently** — three pull readers expect a single nested map and would
receive a vector, so `get-in … :seon.agent/id` returns `nil`, i.e. "no
steward", which is absence read as a state:
`src/seon/cluster/agent.clj:190`, `:278`, and `src/seon/agent.clj:18`, `:30-31`
(which then assocs a single `:my.agent/steward` into the agent-facing read —
a contract narrowing, so the `my.*` surface changes too).

**Changes meaning** — `src/seon/cluster/agent.clj:150` writes
`[:db/add [:seon.ns/name namespace-name] :seon.ns/steward …]`. On a
cardinality-one attribute that replaces; on cardinality-many it **accumulates**,
so "reassign the steward" becomes "add another steward" with no retraction
anywhere. That is the real cost of the widening: the writer must retract
explicitly, and `steward-call` currently has no reason to.

**Behaviour decision, not a schema change** — `src/seon/error.clj:1954`, `:2008`
route a fault to *the* steward. With several, the owner must rule: notify all,
or pick by a declared rule. `src/seon/error.clj:518` already records that this
routing *"routed exactly nothing"*, and the namespace data model measured
**2 stewards for 411 namespaces**
([namespace-data-model-2026-09-16.md:48](../plan/namespace-data-model-2026-09-16.md)),
so the widening is cheap to land today and expensive to land later.

Total: 5 files, 11 sites, 3 of them silent breakages; reset-only for the schema.

## 5. The three highest-leverage refactors in this slice

### R1 — The test run's authority facts become required, and the adoption gets its event (C1 + C2)

```clojure
;; resources/seon/schemas/seon.test.run.edn — :seon.test.run/run
-  [:seon.test.run/cluster {:optional true} :seon.test.run/cluster]
-  [:seon.test.run/basis-t {:optional true} :seon.test.run/basis-t]
-  [:seon.test.run/policy {:optional true} :seon.test.run/policy]
-  [:seon.test.run/input-digest {:optional true} :seon.test.run/input-digest]
-  [:seon.test.run/include-long? {:optional true} :seon.test.run/include-long?]
-  [:seon.test.run/selection-tx {:optional true} :seon.test.run/selection-tx]
+  [:seon.test.run/cluster :seon.test.run/cluster]
+  [:seon.test.run/basis-t :seon.test.run/basis-t]
+  [:seon.test.run/policy :seon.test.run/policy]
+  [:seon.test.run/input-digest :seon.test.run/input-digest]
+  [:seon.test.run/include-long? :seon.test.run/include-long?]
+  [:seon.test.run/selection-tx :seon.test.run/selection-tx]
```

and, in `resources/seon/schemas/seon.test.edn`:

```clojure
+:seon.test/adoption-observed-tx
+[:and {:description "The transaction recording one completed development
+ adoption observation. Its presence is `adopted?`; an adoption with this fact
+ and no identities genuinely changed nothing. Required peer ref: removing the
+ transaction refuses rather than making the adoption read as never-observed."}
+ :seon.db/ref]

 :seon.test/adoption
 [:map {:seon.db/attributes true}
+ [:seon.test/adoption-observed-tx :seon.test/adoption-observed-tx]
  [:seon.test/adoption-cluster :seon.test/adoption-cluster]
  [:seon.test/adoption-identities {:optional true} :seon.test/adoption-identities]
  [:seon.test/adoption-inputs {:optional true} :seon.test/adoption-inputs]]
```

with `seon.test/check-adoption` (`src/seon/test.clj:1518`) guarding on the new
fact instead of on `:seon.test/adoption-cluster`, and its docstring made true.
Cost: reset for the schema; writers `src/seon/cluster.clj:2579-2581` and the
test-run recorder in `src/seon/test/runner.clj`; reader `src/seon/test.clj:1495-1525`.
**Severity: blocker** — this is the gate deciding what to run.

### R2 — Close the render-pair unions so a symbol stores as a symbol (C4)

```clojure
;; resources/seon/schemas/seon.render.edn
-:ai [:or :qualified-symbol :string],
+:ai [:qualified-symbol {:description "The declared AI render function for one
+ entity schema. The rendered text is :seon.render/rendered, never this key."}],
...
-:html [:or :qualified-symbol :seon.render/hiccup],
+:html [:qualified-symbol {:description "The declared HTML render function for
+ one entity schema. The produced Hiccup is :seon.render/rendered."}],
```

This is the one edit in the slice that makes a *stored* type correct rather
than a contract tidier: today the pair installs as `:db.type/string` through
the EDN codec, so the program graph's own render edges are strings in a
database whose owner ruled that symbols are stored as symbols. It also forces
the `:seon.render/form` decision (legacy per AGENTS §3, live in five entity
maps in this slice). Cost: reset + `src/seon/render.clj:1266-1277`,
`src/seon/fn.clj:1748-1815`, `src/seon/schema.clj:1561`.
**Severity: blocker.**

### R3 — Delete the two stored non-facts on the turn (C3 + C5)

```clojure
;; resources/seon/schemas/seon.turn.edn — :seon.turn/turn
-  [:seon.turn/trigger {:optional true} :seon.turn/trigger]
...
-  [:seon.turn.work/situation {:optional true} :seon.turn.work/situation]
;; and the declarations
-:trigger [:and #:seon.db{:index true :seon.wake/context-inert true} :seon.db/ref],
;; resources/seon/schemas/seon.runtime.edn
-:trigger [:and {:description "The fact whose wake opened the latest turn."} :seon.db/ref]
```

`situation` has no writer at all; `trigger` is a retired spelling pointing at
two unrelated families and is the third answeredness derivation the turn PRD
retired and [message-wake §3.6](message-wake-and-provenance-modeling-2026-09-17.md)
ruled deleted. Removing both means every entry remaining on `:seon.turn/turn`
is a fact something actually writes — the precondition for the owner's "the
attributes determine ALL states". Cost: reset + `src/seon/bootstrap.clj:124,158-161,188,883`,
`src/seon/issue.clj:936`, `src/seon/eval/drive.clj:115`,
`src/seon/bootstrap_drive.clj:115`, `resources/seon/schemas/seon.agent.edn:137-139`.
**Severity: blocker for C3, friction for C5.**

## 6. What could not be verified without a JVM

1. **Live datom counts for every attribute named above.** Every "still present"
   claim is about the checked-out declaration, not about how many rows carry
   it. In particular, whether `:seon.turn.work/situation` has zero *datoms* (as
   opposed to zero writers in `src/`) needs one query.
2. **Whether the `:any` mode of `unanswered-wakes` now works.** The message-wake
   note measured 0 for every agent because a retracted `:seon.message/inbox`
   was absent from the live index. `inbox` is gone from `seon.message.edn`; the
   two readers (`src/seon/turn.clj:3084` live, `:2774` history) should now
   agree, and only a live cluster can say.
3. **Whether a turn-opening transaction actually carries `:seon.db/process`.**
   State 4 of the derivability table depends on it and it is a writer
   behaviour, not a declaration.
4. **Whether `:seon.render/ai` datoms are in fact `:db.type/string` in an
   installed schema.** The bridge path is read end to end
   (`src/seon/schema/datahike.clj:150-168`, `:325-337`), but the installed
   attribute would be confirmed by one `storable-attribute-in?` /
   `malli->datahike-attr` call.
5. **Whether requiring `:seon.test.failure/first-run` already refuses run
   deletion in practice.** The chain is read (`src/seon/db.clj:3052`,
   `db/transaction.cljc:998-1015`, `:1206-1216`); no deletion was attempted.
6. **`bin/test`, `src/seon/test.clj`, `src/seon/test/runner.clj`,
   `resources/seon/schemas/seon.test.selection.edn` and the two test files
   carried uncommitted edits from another lane** at the time of this read
   (`git status` at entry). `seon.test.selection.edn` is untracked; its line
   numbers will move. A lane landing between now and the reset must re-anchor
   every line number in this note rather than trust it.

## Verification boundary

Every `file:line` above was opened in this session in the `steward-platform`
working tree, with other lanes' uncommitted edits present. No JVM was started,
no gate was run, no cluster was operated, no MCP evaluation was spent, and the
only file written is this one. Counts of `:seon.db/ref`, `:seon.schema/value`
and `{:seon.db/identity false}` are `grep` totals over the 70 files of the
slice (the ref-docstring split is a four-line-window heuristic and is labelled
as such where it appears). Claims about which attributes are *installed* are
readings of `src/seon/schema/form.cljc:130-157` and
`src/seon/schema/datahike.clj:123-337`, not measurements of an installed
schema.
