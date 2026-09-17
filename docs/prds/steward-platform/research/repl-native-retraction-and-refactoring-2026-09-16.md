---
type: research
status: active
created: 2026-09-16
tags: [repl, program-graph, refactoring, deletion, retraction, sci, issues, agents, refusal]
---

# Retraction and refactoring from the agent's own REPL

Date 2026-09-16. Branch `steward-platform`, working tree at `b2530c886` plus
several lanes' uncommitted edits (verification boundary, §11). Read-only: no
source edit but this note, no test JVM, no `bin/seon` state change, three
read-only `mcp__seon__eval_clj` calls against cluster `default` (mode `jvm`,
custody `(seon.operator/connection "default")`), all pure reads. Nothing was
transacted.

The owner's words this note answers, verbatim:

> *"Agents can actually rewrite functions by just redefining them, same with
> schema changes and overwriting tests. We do need ways to retract them that
> are REPL friendly. … Everything should be able to be done within a repl env
> that the agent is controlling."*

and, the same evening:

> *"we need to come up with a full vocab and code them up so they work
> perfectly with our system. properly rejecting problems and suggesting
> solutions and even returning refactoring plans we can just launch."*

and, correcting this note's first draft:

> *"even if clojure has native versions we need our own so we can update the
> database. keep that in mind."*

**That correction is the spine of §4 and §5.** Every operation below is OUR
function — a `my.*` protocol over a `seon.*` owner — whose one job is to update
the database as the authority. Clojure's or SCI's native call is at most what
our function performs inside the agent's SCI context as a consequence; it is
never the entry point. Clojure's *names* stay, because the vocabulary law says
they win; the *function* is ours.

The mission is [the program-facts PRD §0a](../plan/program-facts-are-the-runtime-prd-2026-09-17.md):
AI-first, no file editing, the program graph in the database, certain failures
impossible, refactoring taught with data. The connection inventory is
[unbreakable-connections-2026-09-16.md](unbreakable-connections-2026-09-16.md);
this note is its REPL surface.

---

## 0. The four sentences

**One.** Redefinition already works end to end and is genuinely good: an
agent's `defn` is analysed by the indexer's own analyser, upserts the same
entity by identity, recomputes its call edges, and is gated by the tests that
reach it before it installs — `definition-row` (`src/seon/sci/eval.clj:392`) →
`seon.fn/source-rows` (`src/seon/fn.clj:1135`) → `gate-function-install`
(`src/seon/turn.clj:3285`).

**Two.** Retraction reaches the database through exactly two forms today —
`clojure.core/ns-unmap`, which the reader marks (`src/seon/sci/reader.cljc:379`)
and whose identities `row-tx`'s deletion arm writes (`src/seon/turn.clj:1302-1345`),
and `seon.schema/unregister!`, which is already our own function
(`src/seon/schema.clj:1476`, recognised at `src/seon/sci/reader.cljc:370`) — and
both write the identity-only tombstone through
`seon.program/exact-replacement-tx` (`src/seon/program.cljc:1055`), so every
caller's ref still resolves and nothing anywhere notices. `remove-ns`,
`ns-unalias`, `intern` and `alter-var-root` are all callable in the agent's SCI
context and produce **no program fact at all**: they change what runs without
changing what the database says runs, which is the exact failure the owner's
correction names.

**Three.** The refusal seam is not hypothetical: `seon.db/write-report-error`
(`src/seon/db.clj:3109`) already refuses a whole transaction on two program-graph
invariants in the working tree right now — recorded call arities that disagree
with the final declared arities (`:3145-3161`) and a render pair naming a
function absent from the final program (`write-render-target-error`, `:3076`).
The deletion admission contract is the third check in that same function, in
the same shape, with the same constructor.

**Four.** "A refactoring plan we can just launch" needs no new mechanism:
`seon.issue/generate` (`src/seon/issue.clj:501`) already turns a **detector
read** into one issue per subject, on an identity that upserts across runs and
**resolves itself when the detector stops naming the subject**
(`subject-row`, `:448`), and `seon.issue/start!` (`:990`) creates the worker,
its plan component, its budget and its opening turn in one transaction. The
refusal's plan is a detector plus a `start!`, not a new authoring path.

---

## 1. Authorities read end to end

- [AGENTS.md](../../../../AGENTS.md) §1–§4, including the vocabulary table rows
  for SCI context, evaluation, *every function is callable*, the `my.*`/`seon.*`
  split, and `doc`/`dir`.
- [program-facts-are-the-runtime PRD](../plan/program-facts-are-the-runtime-prd-2026-09-17.md)
  §0a, §1 (R1–R5), §1b–§1e and §1f (G1–G6), §2, §3, §4 S1–S4.
- [unbreakable-connections-2026-09-16.md](unbreakable-connections-2026-09-16.md)
  in full: C1–C16, the four seams, the four operations of §6, the honest gaps
  of §7.
- [deletion-semantics-program-graph-2026-09-16.md](deletion-semantics-program-graph-2026-09-16.md)
  §2, in particular §2.6's three deletion origins.
- [the turn PRD](../../context-generation/plan/agent-record-and-turn-loop-prd-2026-09-07.md)
  §13, §14 and §15 — what an evaluation is, what is durable, what is private.
- [seon.issue.edn](../../../../resources/seon/schemas/seon.issue.edn),
  [my.plan.edn](../../../../resources/seon/schemas/my.plan.edn),
  [my.plan.item.edn](../../../../resources/seon/schemas/my.plan.item.edn) and
  the plan component declaration in
  [seon.agent.edn](../../../../resources/seon/schemas/seon.agent.edn) (`:154-156`).

Source opened: `src/seon/sci/eval.clj`, `src/seon/sci/reader.cljc`,
`src/seon/sci/admit.clj`, `src/seon/turn.clj`, `src/seon/program.cljc`,
`src/seon/fn.clj`, `src/seon/db.clj`, `src/seon/schema.clj`, `src/seon/repl.clj`,
`src/seon/issue.clj`, `src/seon/test.clj`, `src/seon/note.clj`, every file in
`src/my/`, `test/seon/cluster/turn_test.clj`,
`reference-code/sci/src/sci/core.cljc` and
`reference-code/sci/src/sci/impl/namespaces.cljc`.

---

## 2. What works today from an agent's REPL

### 2.1 Redefine a function — works, in place, with full analysis and a test gate

The path, in order:

1. The reader marks the form as a function declaration and carries the agent's
   live namespace context — aliases, refers, requires read out of SCI's own
   table, never re-derived (`reader-context`, `src/seon/sci/eval.clj:459`,
   over `sci/namespace-bindings`).
2. After evaluation, `definition-row` (`src/seon/sci/eval.clj:392`) finds the
   interned Var whose **value** changed this fork generation (`turn-interns`,
   `:348`; SCI's `:sci/generation` stamp, `reference-code/sci/src/sci/core.cljc:345`)
   and hands the **submitted source text plus the namespace row** to
   `seon.fn/source-rows` (`src/seon/fn.clj:1135`) — the indexer's own clj-kondo
   analysis over a source string, with a prelude of stubs built from the
   database's `:seon.fn/arglists` and `:seon.fn/private?`
   (`runtime-analysis-batch`, `src/seon/fn.clj:778`). SCI contributes only what
   analysis cannot know: the normalised `:malli/schema` contract, the
   `:seon.workload` tag, the test markers (`src/seon/sci/eval.clj:408-419`).
   PRD §2.2's claim that the evaluation seam does not analyse is stale; S1
   landed at `6312fcef0`.
3. `gate-function-install` (`src/seon/turn.clj:3285`) derives the reaching test
   set with `seon.fn/gate-set` (`src/seon/fn.clj:1407`), runs those tests plus
   the generative auto-check in a candidate SCI context
   (`sci.eval/evaluate-candidate`, `src/seon/sci/eval.clj:2724`), and installs
   only on green (`accept-candidate!`, `:2657`); otherwise
   `sci.eval/refuse-install` (`:2673`) records the red tests with their shown
   text and the row is **not** installed. This is the strongest guarantee in
   the tree and it exists only for agent-authored functions.
4. The row is written by `row-tx` (`src/seon/turn.clj:1301`) in the turn's one
   settlement transaction, through `program/exact-replacement-tx`
   (`src/seon/program.cljc:1055`): every attribute the new row does not carry
   is retracted, the new row is asserted **at the same `:db/id`**. Datahike's
   `upsert-eid` keeps the entity because `:seon.fn/sym` is
   `:db.unique/identity`.

**Answers.** The row updates in place; the identity, its eid, and every
incoming ref survive; edges are re-analysed from the new source, so a call the
new body dropped disappears and a call it added appears. A `defn` with no
`:malli/schema` is not installed at all and the agent is told so in its own
evaluation note (`def-note`, `src/seon/repl.clj:118`: *"… was not installed:
every function needs a :malli/schema contract to become part of the program."*).

### 2.2 Redefine a schema key — works, with an isolated candidate projection

`(seon.schema/register! :some/key form)` is recognised by the reader
(`schema-declaration`, `src/seon/sci/reader.cljc:359`). `declared-row`
(`src/seon/sci/eval.clj:1931`) opens an isolated **registration delta**
(`schema/begin-registration-delta`), evaluates the form inside it, refuses when
the executed form did not register the identity the reader saw
(`:1968-1975`, `::schema-refused`), builds a candidate projection with
`schema/projection-with-schema` and validates the row's contract facts against
it. The terminal transaction repeats the same pure validation against its
mid-transaction database value.

**What happens to the projection**: the candidate projection is isolated until
settlement; the live projection advances only through `install-row!`
(`src/seon/sci/eval.clj:781`) after the transaction commits
(`advance-context-projection!`, `:623`).

**What happens to armed contracts**: `seon.instrument/arm-var!` re-arms a
wrapper whose contract or a transitively referenced declaration changed
(`contract-definitions` / `current-wrapper?`, `src/seon/instrument.clj`);
unrelated wrappers keep identity (AGENTS.md §1).

**What happens to entities already stored under the old shape**: nothing. There
is no data migration and none is wanted — database data is disposable by ruling
(PRD §1f G6). The one guard that does fire is for *removal*, not change:
`assert-schema-data-unused!` (`src/seon/turn.clj:1088`, called at `:1319`)
refuses an unregistration while the attribute still has datoms.

Live: 2,790 schema keys, 4 of them `:seon.schema.admission/source :agent`.

### 2.3 Redefine a test — works; the old result evidence does not survive

A `deftest` in SCI is analysed by the same `source-rows` path and carries
`program/test-markers` (`src/seon/sci/eval.clj:419`). `row-tx` resolves
`:seon.test/subject` to the subject's entity when it exists, and otherwise
stores `:seon.test/pending-subject` (`src/seon/turn.clj:1345-1358`), which is
resolved later by `pending-relation-resolution-tx` (`:1256`) when the subject
arrives.

**Old evidence.** `exact-replacement-tx` retracts every attribute the new row
does not carry. A re-evaluated `deftest` therefore drops the previous
`:seon.test/reach` and result facts unless the new row carries them — reach is
recorded per run by the runner, not by the declaration — so redefining a test
voids its recorded reach and its green basis. `seon.test/changed-since-green`
then re-reads the survivors as if the closure had always been smaller, which is
C7's silent case. Running it again through `my.test/run` (`src/my/test.clj:20`,
a macro over `seon.test/run-owned`, `src/seon/test.clj:380`) restores the
evidence.

### 2.4 `ns-unmap` a function or test — works, and writes a tombstone

`(clojure.core/ns-unmap 'ns 'name)` is marked by the reader
(`namespace-unmap`, `src/seon/sci/reader.cljc:379`) **without evaluating its
arguments**; the evaluator derives the removed identities from SCI's own
before/after intern tables (`removed-program-identities`,
`src/seon/sci/eval.clj:311`), so the dynamic spellings work too —
`(clojure.core/ns-unmap (find-ns 'my.agents.agent-a) (symbol "dynamic-obsolete"))`
is proven durable by
`qualified-dynamic-ns-unmap-is-durable-in-a-fresh-context`
(`test/seon/cluster/turn_test.clj:607`). The literal-quoted form has a second,
purely syntactic path in `program/deletion-row` (`src/seon/program.cljc:1085-1097`).

What is written: `row-tx`'s deletion arm (`src/seon/turn.clj:1302-1345`) pulls
the current declaration and calls `exact-replacement-tx` with **the identity
alone**, so every definition attribute is retracted and the identity datom
survives. The agent sees `nil`, the SCI context loses the Var immediately
(`absent-foreign-ns-unmap-commits-and-mutates-the-run-sci-ctx`,
`test/seon/cluster/turn_test.clj:628`), and a fresh acquisition cannot resurrect
it. `ns-unmap-retracts-the-owned-function-after-the-terminal-commit`
(`:548`) asserts exactly this and calls the result
`stable-program-identity-row?` — a tombstone.

**Nothing refuses.** Callers keep their `:seon.fn/calls` ref to the surviving
identity row, so the flagship failure — delete a function with five live
callers — is silent, as C1 states.

### 2.5 The native forms the agent can already call, and whether they reach the writer

| Form | SCI implementation | Reaches our writer? |
|---|---|---|
| `ns-unmap` | `sci-ns-unmap`, `reference-code/sci/src/sci/impl/namespaces.cljc:567` (installed at `:2001`) | tombstone, as above |
| `remove-ns` | `sci-remove-ns`, `namespaces.cljc:604` (`:2047`) — one `dissoc` of the namespace from `env` | **none**: the `:seon.ns` row, its 176 declarations and its require edges all survive |
| `ns-unalias` | `sci-ns-unalias`, `namespaces.cljc:590` (`:2002`) | only through the namespace context row (`namespace-context-row`, `src/seon/sci/eval.clj:592`) when the namespace state changed |
| `intern` | `sci-intern`, `namespaces.cljc:610` (`:1910`) | a definition row **only if** the interned value differs and analysis produces one; an interned non-function value is a `def` and is private by §15 |
| `alter-var-root` | `sci-alter-var-root`, `namespaces.cljc:816` (`:1717`) | **none**: the Var's behaviour changes with no row, no analysis, no gate |
| `seon.schema/unregister!` | ours, `src/seon/schema.clj:1476`; refuses outside a registration delta (`:1487-1491`) | schema-key tombstone via the same deletion arm, after `assert-schema-data-unused!` |

`remove-ns`, `ns-unalias`, `intern` and `alter-var-root` are the holes. Every
one of them is ordinary Clojure, reachable from an agent's REPL right now, and
every one of them leaves the program facts stale. §5.5 specifies what our
operations do about each.

### 2.6 What an agent sees when something is refused

Three distinct shapes, and they are not interchangeable:

1. **An install refusal** — the gate's red tests. `refuse-install`
   (`src/seon/sci/eval.clj:2673`) drops `:seon.program/row` and appends
   `accretion/install-refusal`'s report to the evaluation's output. The
   evaluation still commits; the agent reads the failures with their shown text
   in its own history through `seon.repl/text` (`src/seon/repl.clj:263`).
2. **A flat `:seon.error` value returned by a function** — the ordinary agent
   boundary. Built by `seon.error/diagnostic` (`src/seon/error.clj:312`), which
   requires layer, operation, member, expected, offending, cause and evidence,
   and types unavailable evidence as `:seon.error/unknown` rather than omitting
   it. It renders as `#:seon.repl{:value …}` like any other value.
3. **A refused settlement transaction** — and this one matters for the design.
   `settle-batch!` (`src/seon/turn.clj:3629`) commits the whole turn's
   evaluations, rows and side effects in ONE transaction. When
   `seon.db/transact!` refuses it, `settle-batch-refusal!` (`:3747`) re-settles
   **every** evaluation of the batch with the refusal's serialized flat error as
   its shown text, `dissoc`s `:seon.program/row` from all of them, records the
   fault and closes the turn (`:3760-3790`).

Consequence to state plainly: **a write-admission refusal is delivered to the
agent, but it costs the whole turn's installs, not just the offending form.**
That is why the surface in §5 gives the agent a pure read (`breaks`) and a
pre-flight refusal at the operation, while seam B stays the authority. The
pre-read is legitimate under AGENTS.md §2.1's owner law precisely because it is
advisory: it does not decide, it informs; the writer re-decides on `:db-after`.

### 2.7 The live graph, measured

Cluster `default`, 2026-09-16, two read-only `seon.db/q` bundles:

| Question | Answer |
|---|---|
| function identity rows with no `:seon.fn/source` | **826** (802 carry `:seon.fn/ns`, **0** carry `:seon.fn/file`) |
| caller rows naming one of those through `:seon.fn/calls` | **5,573** |
| `:seon.fn/pending-calls` datoms | **0** |
| function rows with provenance `:agent` / `:core` | **3** / **5,087** |
| schema keys, of which `:agent` | **2,790** / **4** |
| arity mismatches (`seon.fn/arity-mismatches` at HEAD) | **0** mismatching, 8,939 checked, **46,999 unchecked** |

**The first row is a live instance of this project's named failure class and it
corrects a natural reading of the inventory.** "No `:seon.fn/source`" does not
mean "deleted": the samples are `babashka.fs/absolutize`,
`babashka.fs/create-sym-link`, `:clj-kondo/unknown-namespace/thrown?` — identity
rows minted for external call targets so the population invariant holds. A
deletion tombstone and a never-analysed external target are **byte-identical**
today, which is exactly what PRD §1f G4 requires to stop being spellable, and it
is why the deletion contract cannot be built on "has no source". It must be
built on **the identity row being gone** (G1) with **edges as values** (G2).

The 46,999 unchecked call sites are the honest coverage of the arity check:
most name `clojure.core` and other targets that carry no arity rows.

---

## 3. The gaps — what has no REPL spelling at all

| Operation | Today | Why it is a gap |
|---|---|---|
| retract a schema key | **has one, and it is already ours**: `seon.schema/unregister!` (`src/seon/schema.clj:1476`) | not a gap, and it is the worked precedent for every operation in §5: our function, our writer, the native effect as a consequence. What it lacks is the referrer refusal (C4/C5) |
| retract a test | `ns-unmap` reaches it (`removed-program-identities` emits both `:seon.fn/sym` and `:seon.test/sym`, `src/seon/sci/eval.clj:311-325`) | works, but nothing reports which functions lose their only reaching test |
| retract a namespace | bare `remove-ns` changes SCI only; no operation of ours exists | the row, its declarations and 3,215 require edges are untouched, and nothing refuses |
| rename | **none** | not a text substitution: define new, rewrite callers, retract old, one transaction |
| move between namespaces | **none** | identity is the qualified symbol, so a move is a rename plus a namespace change plus, later, a file/span change (C15) |
| "what breaks if I delete X" | **none** | the query exists in pieces (`gate-set`, reverse `:seon.fn/calls`); nothing composes them |
| "who calls X" | **none agent-facing** | Datahike's reverse lookup `:seon.fn/_calls` answers it in one pull |
| "which tests reach X" | `seon.fn/tests-reaching` (`src/seon/fn.clj:1417`) exists | not exposed under `my.*`; live probe shows no `my.*` function rows were returned by a predicate query (§11) |
| "what reads this key" | `seon.fn/functions-using` (`src/seon/fn.clj:1467`) exists for keyword membership; `:seon.fn.arity/*-refs` carries the contract half | honest membership only, never a claim (C11) |
| change a contract and see every call site that no longer fits | arity: yes, once the seam-B check lands; **shape: never** | `:type-mismatch` is deliberately downgraded (`src/seon/fn/analyzer.clj:20-26`); the reachable guarantee is the test gate |
| retract an agent's own override and fall back to the base | **none** | acquisition decides by namespace kind, not provenance (`build-base-ctx`, `src/seon/sci/eval.clj:184`); PRD S3 is unstarted and there are 3 `:agent` rows live |
| `alter-var-root` / `remove-ns` producing no fact | — | behaviour changes with no program fact; both are in the agent's context now |

---

## 4. The vocabulary

Rows in the AGENTS.md §3 table shape. Two laws apply together and neither
yields:

- **The name is Clojure's** (then the integration seam's, then a coinage) —
  AGENTS.md §3's vocabulary law.
- **The function is ours, and the database write is the act** (owner,
  2026-09-16). Every row below names a `my.program` (or existing `seon.*`)
  function. The native call, where one exists, is what our function performs
  *inside* the agent's SCI context after the writer has accepted the change —
  the consequence, never the entry point. `my.program/ns-unmap!` is the
  operation; `clojure.core/ns-unmap` is what it does to the context.

Exactly two names below are coinages, and both are recorded with sources on
both sides.

| Term | Meaning and grounding | Legacy spellings |
|---|---|---|
| **redefinition** | Re-evaluating `defn`/`def`/`deftest` in the agent's SCI context. Clojure's own operation. The row upserts at the same identity; Datahike's word for the write is **upsert** (`upsert-eid`), and `seon.program/exact-replacement-tx` (`src/seon/program.cljc:1055`) is its one constructor. **Redefinition is not deletion.** | rewrite, patch, overwrite, edit |
| **`seon.schema/register!`** | Declare or redeclare one schema key from the REPL; recognised by the reader as a declaration event (`src/seon/sci/reader.cljc:359`), validated inside an isolated registration delta (`src/seon/sci/eval.clj:1959-1988`) | schema migration, alter-schema |
| **`seon.schema/unregister!`** | Retract one schema key. Ours, already implemented (`src/seon/schema.clj:1476`); refuses outside a delta; the data half is guarded by `assert-schema-data-unused!` (`src/seon/turn.clj:1088`) | delete-schema, drop-attribute |
| **`my.program/define!`** | Install or replace one function, test or render pair from source, with its contract. Today's mechanism is a `defn`/`deftest` re-evaluation reaching `definition-row` (`src/seon/sci/eval.clj:392`) → `gate-function-install` (`src/seon/turn.clj:3285`) → `install-evaluated-rows!` (`src/seon/sci/eval.clj:928`); the named function is that path with a contract and a refusal payload, and the SCI intern is its consequence | defn (as an operation name), install-fn |
| **`my.program/ns-unmap!`** | **Ours.** Retract one function or test declaration: write the retraction, then `clojure.core/ns-unmap` the Var in the agent's context. Clojure's name (SCI's `sci-ns-unmap`, `reference-code/sci/src/sci/impl/namespaces.cljc:567`), our function, our writer. The bare native form reaches `row-tx`'s deletion arm today (`src/seon/turn.clj:1302`) and keeps working; `ns-unmap!` is what carries the refusal and the plan | delete!, remove-fn, retract-fn |
| **`my.program/remove-ns!`** | **Ours.** Retract one namespace and every declaration it owns, then `clojure.core/remove-ns` the context entry. The bare native form (`sci-remove-ns`, `namespaces.cljc:604`) is SCI-only and leaves 176 declarations and 3,215 require edges untouched; §5.5 forbids it | delete-namespace!, drop-ns |
| **`my.program/ns-unalias!`** | **Ours.** Retract one alias from the namespace row and then from the context. The bare `ns-unalias` (`namespaces.cljc:590`) updates the namespace context row only when the state changed (`namespace-context-row`, `src/seon/sci/eval.clj:592`) | unalias!, drop-alias |
| **`seon.schema/register!` / `unregister!`** | Already ours in exactly this shape: a function whose act is the database write, executed inside an isolated registration delta (`src/seon/schema.clj:1476`). They are the worked precedent every row above follows | schema migration, drop-attribute |
| **`clojure.core/intern`, `alter-var-root`** | Native SCI Var mutation (`sci-intern`, `namespaces.cljc:610`; `sci-alter-var-root`, `:816`). They produce **no** program fact and have no `my.program` equivalent: the operation an agent wants is `define!`. §5.5 refuses them for identities the program graph owns | inject, hot patch |
| **retraction** | Datahike's `[:db/retractEntity …]` / `retractAttribute`. The ONE word for removal, ruled by PRD §1f G1. The past is `history` / `as-of` / `since` | deletion, tombstone, retirement, soft delete |
| **tombstone** | The identity-only row `row-tx` writes today (`src/seon/turn.clj:1336-1339`). A **legacy** shape, retired by G1/G3; the word survives only to name what is being deleted | retired row, husk |
| **referrer** | An entity alive in `:db-after` that names a retracted identity through a declared edge attribute. The deletion admission contract's own noun (`deletion-semantics-program-graph-2026-09-16.md` §2.1) | dependent, user, consumer |
| **caller** | A referrer through `:seon.fn/calls`. Datahike spells the reverse lookup `:seon.fn/_calls`, which is how the read is written | usage, call-site owner |
| **reference** | A referrer through `:seon.fn/references` — a `#'f` var-quote, deliberately weaker than a call: "no invocation or arity is asserted" (`resources/seon/schemas/seon.fn.edn:42`) | caller (wrong: never merge the two) |
| **reach** | `:seon.test/reach` — evidence about a **past** run. Never refuses a retraction; the retraction reports it as advisory (C7) | coverage, dependency |
| **subject** | `:seon.test/subject` — a **claim** about the present. It does refuse | target |
| **gate set** | The tests selected for one identity: `seon.fn/gate-set` (`src/seon/fn.clj:1407`), `gate-sets` for several (`:1376`). Already the name the install gate uses | reaching tests (prose only), test closure |
| **provenance** | `:seon.schema.admission/source`, `:core` or `:agent`. Decides loading per identity under PRD S3 (I3) | layer one/two (retired, C8), override flag |
| **override** | An identity whose current admitted row is `:agent` under a `src` file root. A **query**, never a stored flag (PRD S3). SCI's own mechanism is `fork` + `copy-var*` (`reference-code/sci/src/sci/core.cljc:345`, `:112`) | shadow, monkey patch |
| **revert** | Re-assert an identity's definition facts read through Datahike's `as-of` at the transaction preceding the override. Not git's revert; the word is used because the operation is literally "assert the previous value", and its authority is `as-of` | unpatch, restore, rollback |
| **breaks** | *(coinage 1 of 2)* The pure read returning every referrer a retraction or contract change would strand, plus the gate set and the typed unknowns. Ours because the concept is ours — the program graph's answer to "what does this connection cost". Grounded on our side by C1–C16 and `seon.db/write-report-error` (`src/seon/db.clj:3109`), on the dependency side by Datahike's AVET reverse lookup | impact analysis, blast radius, dependents |
| **plan (of a refusal)** | *(coinage 2 of 2, and it is not new)* `:seon.issue/*` facts the refusal hands back, in the exact shape `seon.issue/generate` (`src/seon/issue.clj:501`) writes and `seon.issue/start!` (`:990`) launches. The word is already the project's: `my.plan` is the agent's plan component (`resources/seon/schemas/seon.agent.edn:154`) | work packet, task list, TODO |
| **detector** | `:seon.issue/detector` — a function whose program entity plus a subject's identity value **is** the issue's identity, so runs upsert and a subject it stops naming is resolved (`src/seon/issue.clj:448-476`, `:501-540`) | checker, linter, rule |
| **`my.program`** | The thin agent-facing protocol over `seon.program` / `seon.fn` / `seon.db`, exactly as `my.message` is over `seon.cluster.message` (AGENTS.md §3). One fact family, one owning mechanism, never a second writer | refactor API, my.refactor, my.code |
| **the writer is the act** | The rule behind every row: an operation is complete when `seon.db/transact!` accepted it. A form that changed the SCI context and not the database has done nothing, and a form that changed the database is live for every agent on the branch (PRD R1) | "it worked, the Var is there" |

Two names deliberately NOT coined: there is no `delete!` (the operation is
`my.program/ns-unmap!`, because the thing it does is Clojure's `ns-unmap`), and
there is no `impact` / `blast-radius` (it is `breaks`).

---

## 5. The surface — signatures, contracts, returns

All of `my.program` takes and returns ONE namespaced map (AGENTS.md §3), with
the connection and agent id supplied by call preparation. Every write returns
the changed entity; every read returns small maps with their own namespaced
keys; every failure is a flat `:seon.error/value`.

### 5.1 Reads (pure, no transaction)

```clojure
(my.program/breaks {:seon.program/subject 'seon.turn/open?})
(my.program/breaks {:seon.program/subject :seon.db/connection})
(my.program/breaks {:seon.program/subject 'seon.turn
                    :seon.program/contract '[:=> [:cat :seon.db/db :seon.turn/id] :boolean]})
```

```clojure
{:malli/schema
 [:=> [:cat [:map [:seon.db/db :seon.db/database-value]
             [:seon.program/subject :seon.program/subject]
             [:seon.program/contract {:optional true} :seon.fn/spec]]]
  [:or :seon.program/breakage :seon.error/value]]}
```

`:seon.program/subject` is `[:or :qualified-symbol :qualified-keyword]` — a
function or test symbol, a namespace symbol, or a schema key. The return,
`:seon.program/breakage`, is the union of every group the inventory's four
operations name, each keyed by the attribute that carries it, each group
absent when empty (absent = no key, never stored nil):

```clojure
#:seon.program{:subject seon.turn/open?
               :kind :seon.fn/sym                       ; which identity attribute answered
               :callers      #{seon.turn/recover-call seon.turn/require-open-run}
               :references   #{seon.cluster.agent/agent-graph}   ; #'f var-quotes, never merged with callers
               :call-sites   [#:seon.fn{:sym seon.turn/require-open-run
                                        :call-arity 1
                                        :form-span [48210 48533]
                                        :declared-arities [#:seon.fn.arity{:min 2 :max 2}]}]
               :subject-of   #{seon.turn-test/open-is-derived}   ; BLOCKING: a test's claim
               :stale-reach  #{…54 test symbols…}                ; ADVISORY: evidence about the past
               :gating       [seon.turn-test/open-is-derived …]  ; seon.fn/gate-set: the proof of repair
               :render-declared-by #{#:seon.schema{:key :seon.turn/turn
                                                   :property :seon.render/ai}}
               :capability-of #{my.fs/read}
               :schedule-tasks #{"rotate-logs"}
               :writes-of     #{seon.issue/add!}                 ; schema-key subject only
               :contract-refs #{seon.db/transact! …}             ; :seon.fn.arity/*-refs
               :schema-references #{:seon.db/connection}         ; :seon.schema/references
               :requiring-namespaces #{seon.render seon.cluster}  ; namespace subject only
               :owned-declarations #{…176 symbols…}              ; namespace subject only
               :data-in-use   #:seon.schema{:key :seon.db/connection :datoms 412}
               :unknown [#:seon.program{:gap :dispatch-not-modelled
                                        :message "defmethod and protocol bodies have no row (C13); implementations are not in this answer."}
                         #:seon.program{:gap :arity-unknown-sites :count 12}
                         #:seon.program{:gap :macro-mediated :macros #{seon.db/with-custody}}
                         #:seon.program{:gap :unresolvable-callers :sites [...]}]
               :plan  #:seon.program{:issues [...] :launch (quote (my.program/launch! {:seon.program/subject (quote seon.turn/open?) :seon.issue/budget 8}))}}
```

`:seon.program/unknown` is required and never empty-by-omission: §7 of the
inventory lists the gaps, and each one that applies to this subject says so. A
`breaks` that returned no `:unknown` key would be the absence-as-health defect
the whole project is organised against.

Two convenience reads, both thin and both over existing owners, so no new
derivation is introduced:

```clojure
(my.program/callers {:seon.program/subject 'seon.turn/open?})   ; :seon.fn/_calls reverse pull
(my.program/tests-reaching {:seon.program/subject 'seon.turn/open?}) ; seon.fn/gate-set, src/seon/fn.clj:1407
(my.program/reads-key {:seon.schema/key :seon.db/connection})   ; seon.fn/functions-using + :seon.fn.arity/*-refs
(my.program/overrides {})                                       ; identities whose admitted row is :agent under a src root
```

### 5.2 Writes

Every write's refusal is the same `:seon.program/breakage` value carried on
`:seon.error/diagnostic-offending` and `:seon.error/data`, built by
`seon.error/diagnostic` (`src/seon/error.clj:312`).

**Retraction.** Our function, Clojure's name, the database write as the act:

```clojure
(my.program/ns-unmap! {:seon.program/ns 'seon.turn :seon.program/name 'open?})
(my.program/remove-ns! {:seon.program/ns 'seon.turn})
(my.program/ns-unalias! {:seon.program/ns 'seon.turn :seon.program/alias 'str})
(seon.schema/unregister! :seon.db/connection)   ; already ours, unchanged
```

```clojure
;; my.program/ns-unmap!
{:malli/schema
 [:=> [:cat [:map [:seon.db/connection :seon.db/connection]
             [:seon.agent/id :seon.agent/id]
             [:seon.program/ns :seon.ns/name]
             [:seon.program/name :symbol]]]
  [:or :seon.program/change :seon.error/value]]}

;; my.program/remove-ns!  — same map minus :seon.program/name; the retraction
;; covers the namespace row AND every declaration it owns, in ONE transaction,
;; because a partial namespace retraction is refused by :seon.fn/ns being a
;; required ref (C6, the one place the existing schema already does this right).
{:malli/schema
 [:=> [:cat [:map [:seon.db/connection :seon.db/connection]
             [:seon.agent/id :seon.agent/id]
             [:seon.program/ns :seon.ns/name]]]
  [:or :seon.program/change :seon.error/value]]}
```

Each writes first and touches the SCI context second, so a refused write leaves
the agent's context exactly as it was — there is no state in which the Var is
gone and the row is not. The bare `(ns-unmap 'seon.turn 'open?)` keeps reaching
`row-tx`'s deletion arm and keeps being refusable; it is the legacy entry point
and its refusal names `my.program/ns-unmap!` as the operation that also returns
the plan.

`my.program` then adds the operations Clojure has no name for:

```clojure
(my.program/rename! {:seon.program/from 'seon.turn/open?
                     :seon.program/to   'seon.turn/turn-open?
                     :seon.program/callers [#:seon.fn{:sym seon.turn/recover-call
                                                      :source "(defn recover-call …)"}]})
{:malli/schema
 [:=> [:cat [:map [:seon.db/connection :seon.db/connection]
             [:seon.agent/id :seon.agent/id]
             [:seon.program/from :qualified-symbol]
             [:seon.program/to :qualified-symbol]
             [:seon.program/callers {:optional true}
              [:vector [:map [:seon.fn/sym :qualified-symbol]
                             [:seon.fn/source :seon.fn/source]]]]]]
  [:or :seon.program/change :seon.error/value]]}
```

`rename!` is *define `to`, install every supplied caller source, retract `from`*
— one transaction, so the caller repairs and the retraction are the same
`:db-after` and the deletion check simply passes (deletion-semantics §2.1,
consequence 1). With no `:seon.program/callers`, it refuses and hands back each
site **with its `:seon.fn/form-span`** so the agent edits bytes it can locate.

`move!` is `rename!` with a different namespace part of `to`, plus the file
coordinates when write-back exists. It is the same function; a separate verb
would be a second mechanism.

```clojure
(my.program/change-contract! {:seon.fn/sym 'seon.db/transact!
                              :seon.fn/spec "[:=> [:cat :seon.db/connection :map] :map]"})
{:malli/schema
 [:=> [:cat [:map [:seon.db/connection :seon.db/connection]
             [:seon.agent/id :seon.agent/id]
             [:seon.fn/sym :seon.fn/sym]
             [:seon.fn/spec :seon.fn/spec]]]
  [:or :seon.program/change :seon.error/value]]}
```

In practice a contract change is a redefinition, so the check belongs to the
redefinition path and to the publication writer; the explicit function exists
for the case where only the contract changes. Its refusal carries the accretion
verdict (widening free; narrowing an input, requiring an optional key, or
promising less in an output = breakage, AGENTS.md §2.5) **plus** the gate set's
red results with their shown text, which `gate-function-install` already
produces.

```clojure
(my.program/revert! {:seon.fn/sym 'seon.turn/open?})
{:malli/schema
 [:=> [:cat [:map [:seon.db/connection :seon.db/connection]
             [:seon.agent/id :seon.agent/id]
             [:seon.fn/sym :seon.fn/sym]]]
  [:or :seon.program/change :seon.error/value]]}
```

`revert!` reads the identity's definition facts through `seon.db/as-of` at the
transaction preceding its `:agent` assertion and re-asserts them, which restores
the `:core` provenance row and, under PRD S3, makes acquisition load the JVM Var
again. It is an ordinary write through the same gate: reverting is a
redefinition and must pass the same reaching tests.

```clojure
(my.program/launch! {:seon.program/subject 'seon.turn/open?
                     :seon.issue/budget 8
                     :seon.issue/severity :friction})
{:malli/schema
 [:=> [:cat [:map [:seon.db/connection :seon.db/connection]
             [:seon.agent/id :seon.agent/id]
             [:seon.program/subject :seon.program/subject]
             [:seon.issue/budget :seon.issue/budget]
             [:seon.issue/severity {:optional true} :seon.issue/severity]]]
  [:or [:vector :seon.issue/status-view] :seon.error/value]]}
```

`:seon.program/change`, the success value of every write, is the changed entity
and nothing else:

```clojure
#:seon.program{:changed [#:seon.fn{:sym seon.turn/turn-open? :spec "…" :calls #{…}}
                         #:seon.fn{:sym seon.turn/recover-call :calls #{…}}]
               :retracted #{seon.turn/open?}
               :gating [seon.turn-test/open-is-derived]
               :stale-reach #{…}}
```

### 5.3 What our operation does inside the SCI context, after the write

| Operation | Native call it performs, after `transact!` returns | Where |
|---|---|---|
| `define!` | intern the evaluated root into the live base | `transfer-evaluated-roots!` / `install-row!` (`src/seon/sci/eval.clj:912`, `:781`), already the mechanism |
| `ns-unmap!` | `clojure.core/ns-unmap` in the executing fork and in the cluster base | `sci-ns-unmap` (`namespaces.cljc:567`); the existing path already mutates the run ctx (`absent-foreign-ns-unmap-commits-and-mutates-the-run-sci-ctx`, `test/seon/cluster/turn_test.clj:628`) |
| `remove-ns!` | `clojure.core/remove-ns` | `sci-remove-ns` (`namespaces.cljc:604`) |
| `ns-unalias!` | `clojure.core/ns-unalias` | `sci-ns-unalias` (`namespaces.cljc:590`) |
| `rename!` / `move!` | intern `to`, re-intern each repaired caller, `ns-unmap` `from` | as above |
| `revert!` | intern the restored root; under PRD S3 the base reloads by provenance | `build-base-ctx` (`src/seon/sci/eval.clj:184`) |
| `unregister!` | drop the key from the registration delta | `src/seon/schema.clj:1476`, already |

### 5.4 Where each operation is enforced

| Operation | Advisory read | Authority |
|---|---|---|
| redefine a function | `gate-function-install` (`src/seon/turn.clj:3285`) | seam B entity validation + arity check (`src/seon/db.clj:3145`) |
| register!/unregister! a schema key | `declared-row`'s candidate projection (`src/seon/sci/eval.clj:1959`) | `assert-schema-data-unused!` (`src/seon/turn.clj:1088`) + seam B referrer check |
| `my.program/ns-unmap!`, `remove-ns!` | `my.program/breaks`, called by the operation itself | seam B deletion admission contract |
| `rename!` / `move!` | `breaks` on `from` | seam B: `to` defined and callers repaired in the same `:db-after` |
| `change-contract!` | the gate set in a candidate context | seam B arity check; shape is the test gate only (C3b) |
| `revert!` | `breaks` on the override | the ordinary install gate |

### 5.5 Forbidding the native forms that bypass the database

SCI gives a fork two interception seams, and Seon installs both already at
`build-base-ctx`:

- **`:call-preparation-hook`** — `(hook ctx var args)`, called at call time on
  the calling thread for every direct call whose resolved callee is a Var, in
  the context executing the call. **Its return may be a `reduced` value, which
  becomes the call's result without entering the callee**
  (`reference-code/sci/src/sci/core.cljc:310-320`;
  `reference-code/sci/src/sci/impl/analyzer.cljc:1793-1801`). Seon installs it at
  `src/seon/sci/eval.clj:213` for supplied defaults.
- **`:built-in-call-observer`** — one arg, the built-in's qualified symbol,
  observation only, return ignored (`core.cljc:303`); installed at
  `src/seon/sci/eval.clj:207`.

SCI's third option, `:deny` (`core.cljc:291`), is analyzer-level and raises a
raw throw, which is the wrong boundary: an agent mistake must be a flat
`:seon.error` value, never a throw into the loop (AGENTS.md §2.4).

So the rule, one arm each in the call-preparation hook:

| Native form | Our behaviour | Why |
|---|---|---|
| `clojure.core/remove-ns` | **refused** — `reduced` flat error naming `my.program/remove-ns!` and the declarations that would be stranded | a bare call leaves 176 declarations and every require edge asserting a namespace the context no longer has |
| `clojure.core/ns-unalias` | **refused** — names `my.program/ns-unalias!` | the namespace row's `:seon.ns/aliases` components would disagree with the context |
| `clojure.core/alter-var-root` | **refused for any identity the program graph owns**; permitted on an agent's own private `def` | it changes behaviour with no row, no analysis and no gate — the purest version of the owner's concern |
| `clojure.core/intern` | **refused for a program identity**, permitted for a private binding | the same, plus it bypasses the contract requirement |
| `clojure.core/ns-unmap` | **permitted**, because it already reaches the writer; the refusal it now carries names `my.program/ns-unmap!` for the plan | it is the one native form whose act already IS a database write |

Each refusal is data, not prose: `:seon.error/diagnostic-member` is the native
symbol, `:seon.error/diagnostic-expected` is our operation's symbol, and
`:seon.error/data` carries the `:seon.program/breakage` the operation would have
returned — so a refused native call still teaches, and still hands back a plan.

### 5.6 New schema keys this needs

Declared under `resources/seon/schemas/`, one file `my.program.edn` plus
`seon.program` additions:

- `:seon.program/subject` `[:or :qualified-symbol :qualified-keyword]`
- `:seon.program/breakage` — the entity map of §5.1, every group optional, with
  `:seon.program/subject`, `:seon.program/kind` and `:seon.program/unknown`
  required, and its own `:seon.render/ai` / `:seon.render/html` pair
- `:seon.program/change` — `:changed`, `:retracted`, `:gating`, `:stale-reach`
- `:seon.program/gap` `[:enum :dispatch-not-modelled :arity-unknown-sites
  :macro-mediated :unresolvable-callers :keyword-mentions :outside-graph]` —
  a genuinely bounded, closed set, which is the narrow exception AGENTS.md §3
  allows for an enum-valued attribute
- `:seon.program/plan` — the issue rows of §6

---

## 6. The refusal is a plan, and the plan launches with one call

The owner asked for refusals that suggest solutions and return refactoring
plans "we can just launch". The existing issue family does all of it; nothing
new is authored.

### 6.1 Identity, so re-refusing never duplicates

Two identity functions exist and they are not interchangeable:

- **Authored**: `add-tx` (`src/seon/issue.clj:1004`) uses
  `(seon.id/id [title (sort-by pr-str functions)])` (`:1016`) and **refuses** an
  existing identity (`:1017-1018`).
- **Detected**: `subject-row` (`src/seon/issue.clj:448`) uses
  `(seon.id/id (sorted-map {:seon.issue/detector <symbol> <identity-attribute> <value>}))`
  (`:469-470`), **upserts** across runs, and `generate` (`:501`) resolves an
  issue whose subject the detector no longer yields (`:537-545`) and reopens one
  it yields again.

**Use the detected path.** It is the only one with the property the owner asked
for — the retraction's work list re-derives itself, and a repaired caller's
issue resolves without anyone asserting anything.

### 6.2 Grouping: one issue per caller function, not per namespace

`subject-row` requires each subject to carry **exactly one** installed identity
attribute (`src/seon/issue.clj:456-460`), so both `:seon.fn/sym` (a caller) and
`:seon.ns/name` (a namespace) are expressible. Choose the caller, for three
reasons read out of the code, not from taste:

1. It is the unit the refusal names, and `:seon.issue/functions` cites
   `:seon.fn/sym` (`resources/seon/schemas/seon.issue.edn:8`).
2. It resolves per caller: the namespace-level issue would stay open until the
   last caller was fixed and would tell no one which ones were done.
3. `create-tx` derives the worker's namespace from the first cited function's
   namespace anyway (`src/seon/issue.clj:855-857`), so the namespace is already
   carried; and `:seon.ns/steward` (`seon.ns.edn:34-37`, live on 3 of 438
   namespaces) is where an assignment is *routed*, not where the work is *cut*.

The consequence to state honestly: detector-plus-subject identity means one
caller stranded by two different retractions gets ONE issue, not two. That is
the right shape here — the issue's subject is "this caller names identities with
no definition", and its `:seon.issue/functions` cites every such name. It
resolves when the caller names none.

### 6.3 The detector

One function, and it answers two questions at once:

```clojure
(seon.program/unresolved-callers database)
;; => [{:seon.fn/sym 'seon.turn/recover-call
;;      :seon.issue/title "seon.turn/recover-call names identities with no definition"
;;      :seon.issue/problem "…the names, their spans, and the gate set…"
;;      :seon.issue/namespaces #{'seon.turn}}]
```

It is the **positive** unresolved-set report the complete-republish case already
requires (deletion-semantics §2.6 row (c)): at a reset the deletion rule is
vacuous and the equivalent check is "report every edge symbol with no row". The
same read is the detector during ordinary operation. This is the dissolution: one
query serves the refusal, the plan, and the reset report.

It becomes truthful only after PRD §1f G2 — with `:seon.fn/calls` as
`[:set :qualified-symbol]`, "A calls a name with no row" is one Datalog clause.
Until then the existing `:seon.fn/pending-calls` (`seon.fn.edn:99-101`, currently
**0** datoms live) is the same fact in string form and is the precedent the
retype generalises.

### 6.4 The tests the plan carries

`require-test-refs!` (`src/seon/issue.clj:835`) demands that every
`:seon.issue/tests` member be an existing `:seon.test/sym` row, and `start-tx`
refuses an issue with neither tests nor a detector (`:920-922`, `:1007-1009`) —
PRD T1. So:

- **`:seon.issue/tests`** = `(seon.fn/gate-set database <caller>)` — the tests
  that reach the caller and must stay green. They exist as rows, so admission
  passes.
- **`:seon.issue/detector`** = `seon.program/unresolved-callers` — the done
  condition proper. `done-query` (`src/seon/issue.clj:828`) already reads
  "tests decide done when present; otherwise the detector does"; the issue
  family's settlement owns which wins, and a lane must not invent a second rule.
- The **new red** the owner mentions is not a fabricated failing test: it is the
  detector naming the caller. Writing a deliberately failing test would be a
  hand-maintained mirror of a query.

### 6.5 Launching

```clojure
(my.program/launch! {:seon.program/subject 'seon.turn/open? :seon.issue/budget 8})
```

is, exactly:

1. `(seon.issue/generate database {:seon.issue/detector 'seon.program/unresolved-callers
                                   :seon.issue/severity :friction})` →
   transaction data, upserting one issue per stranded caller and resolving the
   rest (`src/seon/issue.clj:501`);
2. one `seon.db/transact!`;
3. `(seon.issue/start! {:seon.issue/id … :seon.issue/budget …})` per open issue
   the subject produced (`src/seon/issue.clj:990`), each of which creates the
   worker, its `:seon.agent/plan` component with one step whose
   `:my.plan.item/done-query` is the issue's `done-query` and whose
   `:my.plan.item/subject` is the issue (`create-tx`, `:871-887`), sets the
   budget overlay, requires `my.issue` and `my.test` into the worker's
   namespace (`:895-896`), and opens its first turn.

The retraction itself is **not** deferred and no new state records an intent.
The agent (or root) calls
`(my.program/ns-unmap! {:seon.program/ns 'seon.turn :seon.program/name 'open?})`
again when the plan is green, and it commits because no surviving referrer names
the identity in `:db-after`. There is no "was it fixed?" flag because there cannot be one.

---

## 7. Suggesting solutions — what each refusal adds beyond the affected set

| Refusal | The solution it carries | Source of the data |
|---|---|---|
| retract a function with callers | each caller's `:seon.fn/sym` **and** `:seon.fn/form-span` (`seon.fn.edn:13`, half-open UTF-8 byte offsets), so the agent locates the exact bytes without searching; plus the gate set that will prove the repair | `:seon.fn/calls` reverse lookup + `seon.fn/gate-set` |
| rename | the same, plus the **replacement candidate**: `from` → `to` is known, so each site's suggested new source is mechanical and the refusal says which sites it could **not** infer and why — a dynamic call, a name built at runtime, a caller outside the graph | `:seon.program/unresolvable-callers` (a positive fact: the analyser sees the `resolve` call) |
| change a contract | the **contract diff** as the accretion verdict — which clause narrowed, which key became required, which output promised less (AGENTS.md §2.5) — plus every call site whose recorded arity no longer fits, with the arity it uses and the arities now declared | `accretion/data-contract!`; `arity-mismatches` (`src/seon/db.clj:3034`) already returns caller, callee, call arity and declared arities |
| retract a schema key | grouped by attribute: `:seon.fn/writes` (4 functions), `:seon.schema/references` (32 keys), `:seon.fn.arity/input-refs` (146 arities as their owning function symbols) — and the rename recipe stated plainly, because a key's semantics never change: declare the new key, rewrite the referrers, retract the old | C4/C5 |
| retract a render pair's function | which schema key loses its render and in which direction | already implemented as a refusal in the working tree: `write-render-target-error` (`src/seon/db.clj:3076`) |
| retract a namespace | the 176 owned declarations that must be retracted in the same transaction and the 67 requiring namespaces, each of which becomes its own subject | C6 |
| any of them | the `:seon.program/plan` of §6 and the single `launch!` form that starts it | `seon.issue/generate` + `start!` |

**How each is taught.** No teaching prose is added. `(dir my.program)` returns
the per-function data the agent already gets — symbol, arglists, the
docstring's first line, and the input/output contract (`directory-value`,
`src/seon/sci/eval.clj:1280`) — and `(doc my.program/rename!)` returns the full
docstring with its contract (`documentation-value`, `:1312`). Every `my.*`
docstring in the tree carries a runnable `Example:` form
(`src/my/plan.clj:112-117`, `src/my/note.clj:49-52`) and `my.program`'s do the
same. The refusal itself reads in the REPL grammar through `seon.repl/text`
(`src/seon/repl.clj:263`):

```
my.agents.juniper=> (my.program/ns-unmap! {:seon.program/ns 'seon.turn :seon.program/name 'open?})
#:seon.repl{:error #:seon.error{:kind :seon.db/invalid-write, :message "seon.turn/open? is named by 5 surviving callers and 1 test subject. Repair them in the same transaction, or launch the plan.", :diagnostic-offending #:seon.program{:callers #{seon.turn/recover-call seon.turn/require-open-run …}, :subject-of #{seon.turn-test/open-is-derived}, :gating [seon.turn-test/open-is-derived …], :stale-reach #seon.print{:elided 54, :requery (my.program/breaks {:seon.program/subject (quote seon.turn/open?)})}, :plan #:seon.program{:launch (my.program/launch! {:seon.program/subject (quote seon.turn/open?), :seon.issue/budget 8})}}}}
```

The 54-member stale-reach set is the one place §2.4's elision ruling bites: the
**fact** carries every member and the AI render function elides it under the
profile as an elision value naming the count and the requery form
(`resources/seon/schemas/seon.print.edn` ↔ `src/seon/print.cljc`). Losing a
member would be a database defect; eliding it is the render contract. They never
trade, and the clipping happens in the AI render function and the value
renderer only.

---

## 8. Durable and private, per operation (turn PRD §15)

| Operation | Durable | Private, lost with the JVM |
|---|---|---|
| `define!` / redefine a function/test | the `:seon.fn`/`:seon.test` row, its analysis, its contract, the evaluation's source and shown text | the SCI Var object and any closure it captured |
| `def` of a value or atom | the evaluation's source and **shown text only** | the object itself; `result/e<id>` while the JVM lives |
| `register!` a schema key | the `:seon.schema` row and its form | the candidate projection of the delta |
| `ns-unmap!` / `unregister!` | the retraction transaction; the prior facts remain in `history` | the Var, removed from the context immediately |
| bare `remove-ns` | **nothing today**; refused under §5.5 | the whole namespace, in SCI only |
| `my.program/remove-ns!` | the namespace row and every declaration it owned, retracted in one transaction | the context entry |
| bare `alter-var-root` / `intern` | **nothing**; refused under §5.5 for program identities | the mutated root |
| `rename!` / `move!` / `change-contract!` / `revert!` | the changed entities and the evaluation that produced them | nothing extra |
| `breaks` | its evaluation's shown text, and its read evidence (`src/seon/db.clj:682`, `:849`), so the system turn re-evaluates it when the graph changes | the returned map, as `result/e<id>` |

`breaks` being a read with recorded read evidence is what makes it a **live**
answer in the agent's context: the since-diff appends a fresh evaluation of the
same form in a system turn when any fact it observed changed (turn PRD §14),
so an agent that asked "what breaks" at turn 3 sees the updated answer at turn 7
without asking again.

**"What did this function look like before?"** is a history query and needs no
new fact:

```clojure
(seon.db/q '[:find ?source ?t ?added :in $ ?sym :where
             [?f :seon.fn/sym ?sym] [?f :seon.fn/source ?source ?t ?added]]
           (seon.db/history (seon.db/db)) "seon.turn/open?")
```

Under G1 the identity row is gone after a retraction, so `history` — not the
live index — is the only reader, which is the ruled design. `as-of` recovers the
whole entity at a transaction; `since` answers what changed after one. The
agent-facing spelling belongs on `my.program` as `(my.program/history
{:seon.program/subject 'seon.turn/open?})` returning the ordered
`#:seon.fn{:source … :seon.db/t … :seon.db/added?}` rows, and it is the same
derivation the `revert!` of §5.2 reads.

---

## 9. The honest ordering

**One afternoon, on seams that already exist and with no reset.**

1. `my.program/breaks`, `callers`, `tests-reaching`, `reads-key`, `history`,
   `overrides` — all pure reads over `seon.fn/gate-set`, `functions-using`,
   `arity-mismatches`, and reverse `:seon.fn/calls` pulls. They are teachable
   immediately, they are the honest detector for PRD F7's first two agent tasks,
   and they break nothing. Ship these first: an agent that can ask before it
   acts is most of the value.
2. The regressions that pin the two refusals that already work by accident —
   `:seon.schedule.task/function` and `:seon.fn/ns` are required keys, so the
   sweep already fails the survivor's own schema (C10, C6) — so the next reset
   cannot quietly relax them to optional.
3. The call-preparation-hook arms of §5.5: `remove-ns`, `ns-unalias`,
   `alter-var-root` and `intern` become typed `reduced` refusals naming our
   operation. The hook is already installed (`src/seon/sci/eval.clj:213`) and
   `reduced` short-circuits without entering the callee, so this is one arm per
   symbol and no fork of `clojure.core`.
4. `my.program/ns-unmap!`, `remove-ns!` and `ns-unalias!` themselves — thin over
   the writer paths that exist, with §5.1's refusal payload. They can ship
   before the seam-B contract: until it lands they refuse from `breaks` alone
   and say so in their own `:seon.program/unknown`, which is honest and is
   already better than silence.

**Needs the reset (G1/G2/G4 land together, PRD §1f G6).**

5. The deletion admission contract in `write-report-error`
   (`src/seon/db.clj:3109`), as the third check beside the arity and render
   checks that are landing there now. It needs edges as values, the tombstone
   machinery deleted (G3), and the required producing-identity fact (G4) —
   without G4 a never-analysed caller and a repaired one are the same zero
   datoms and the refusal inherits the defect it exists to remove. §2.7's 826
   source-less identity rows are the live proof of that conflation.
6. `rename!` / `move!`, which are only expressible once a retraction can be
   refused: the whole point is that the repairs and the retraction share one
   `:db-after`.
7. `seon.program/unresolved-callers` and `my.program/launch!`, which need (5)'s
   value edges to be truthful.

**Needs write-back (PRD R5/S5).**

8. `revert!` restoring what the **JVM** runs, not only what agents run. Until
   S3 lands, provenance does not decide loading (`build-base-ctx`,
   `src/seon/sci/eval.clj:184`) and an override is a shadow in one fork; until
   S5 lands, the turn loop, the writer and the web server keep running the
   compiled definition. The `doc` of an overridden first-party function must say
   so in one line, derived from the override query — a typed state, never
   silence.
9. `move!` re-pointing `:seon.fn/file` and `:seon.fn/form-span` (C15).

One thing to decide before (5), and it is the only genuine design question in
this note: **where the deletion refusal is raised.** Seam B is the authority and
must stay so, but §2.6 shows a refused settlement costs the whole turn's
installs. The recommendation is that `my.program/ns-unmap!` calls `breaks`
itself and returns the flat refusal as its own value — the form evaluates to
an error value, the turn settles normally, and the writer never sees a deletion
row. Seam B keeps its check as the backstop for every other origin (the edit
hook's publication, a system-side writer), where a whole-transaction refusal is
the correct outcome (deletion-semantics §2.6 row (b)).

---

## 10. What this note does not establish

- **The cost of the seam-B lookup** at 63,469 edges: argued from the AVET index,
  not measured. Probe before the contract lands.
- **Whether `my.*` declarations are queryable by predicate**: a pull of
  `[:seon.fn/sym "my.note/forget!"]` returns the row with its
  `:seon.fn/ns → :seon.ns/name my.note`, while a `seon.db/q` clause pairing
  `[?f :seon.fn/sym ?s]` with `[(clojure.string/starts-with? (str ?s) "my.")]`
  returned **empty** in the same call. Either the predicate clause meets an
  encoding boundary in `seon.db/q` or the query is wrong; it is one probe and it
  must be settled before any implementation writes a predicate-filtered
  program-graph query. Reported as a boundary, not as a finding about the data.
- **Argument shapes are not checkable** (C3b), and nothing here promises they
  are. The reachable guarantee for a contract change is the test gate.
- **Protocol and multimethod implementations have no row** (C13), so every
  retraction result must say dispatch is not in the answer.

---

## 11. Verification boundary

- Working tree at `b2530c886` with several lanes' uncommitted edits present;
  `git status` names `src/seon/db.clj`, `src/seon/fn.clj`, `src/seon/sci/eval.clj`,
  `src/seon/schema.clj`, `src/seon/render.clj`, `src/seon/turn.clj` is clean,
  and others. **Every `file:line` in this note is a working-tree line I opened
  in this session**, not a HEAD line. Two citations are load-bearing and moved
  while I read: `seon.db/arity-mismatches` (`src/seon/db.clj:3034`) and
  `write-render-target-error` (`:3076`) are **uncommitted** — at HEAD,
  `arity-mismatches` lives at `src/seon/fn.clj:1511` and is not wired into
  `write-report-error` at all. Re-check both before writing a spec against them.
- The running `default` JVM predates that edit: `seon.db/arity-mismatches` did
  not resolve there, which is how the discrepancy was found.
- Three read-only `mcp__seon__eval_clj` calls (mode `jvm`, custody
  `(seon.operator/connection "default")`), all pure reads; the first failed on
  the unresolved var above. Nothing was transacted; no SCI-mode probe was taken,
  so the shared context was not mutated.
- No gate was run, no test JVM launched, no `bin/seon` state changed.
- One PRD claim is superseded, as the connection inventory already recorded:
  PRD §2.2's "the evaluation seam does not analyse" is stale since `6312fcef0`.
