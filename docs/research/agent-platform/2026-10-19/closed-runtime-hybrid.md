---
title: "Closed runtime: the hybrid of the Opus and Astra plans"
type: research
status: hybrid of a design round; phases to be filed as tasks after the narrow check
created: 2026-10-19
---

# Closed runtime: the hybrid

Lane closed-hybrid (Fable), 2026-10-19. The hybrid of [plan A, Opus](closed-runtime-plan-opus.md) (`9a0531a17`) and
[plan B, Astra](closed-runtime-plan-astra.md) (`491025061`); I wrote neither. Shared inputs: the runtime-fix2 section of
[branch-runtime](branch-runtime.md), [requiring-resolve-inventory](requiring-resolve-inventory.md),
[runtime-rereview](runtime-rereview.md). HEAD `491025061`; default runs `a92ba5b94` (7,683 program names, 124 host-bound,
the facts both planners counted on). Every choice below was checked against the source it names; the counts were re-run
by me over default's `:seon.fn/calls` facts (method in "Reconciling 34 and 25").

## Decision in one paragraph

Take Opus's answers for every family (each names the wrong side and the owner-side change, and its probes hold), with
three corrections from Astra that the census proves necessary: **cycle 3 is not closed until `seon.program`'s two lazy
calls into the bridge (`seon.program/derived-shape`, `seon.program/derived-program-attributes`) are static** (without
them `seon.config/effective`, `seon.program/program-attributes` and `seon.sci.eval/overlay-plan` stay open); **cycle 7
stores the one derivation `seon.call-preparation/prepared-arities` already makes** (not a second "suppliable count"
beside it); and **a callback must never cross a cache lookup** (`seon.db/content-projection`'s `derivation`), which the
hybrid solves by folding the two derivations into the deriver so the lexical key's reach is static, instead of Astra's
callback-identity machinery. Astra's "capture a runtime observation at the entrant" for the frame classifier is not
taken: Opus's probe (re-run below) shows the `find-ns` clause decides nothing, and deleting it makes the classifier a
pure function of the classpath, which also removes the staleness Astra worried about. Result: **34 of the 50 open
`seon.cache` derivers close, 16 stay open, and all four projection derivers close** (Opus's number, reached only with
Astra's `seon.program` sites included). The lexical exact key lands last, gated on the measured hub row.

## Reconciling 34 and 25

Both planners ran the same fold (the open-set loop of `seon.sci.runtime/reach-index`, opening set
`seon.sci.eval/run-time-resolution`, 27 names) over the same facts, and both reproduce the baseline exactly (3,447 open
names, 78 `seon.cache/lookup`/`lookup-within` callers, 50 open). They differ in **which edits they applied**, not in
method. Re-run by me (babashka over the calls facts spit by the form in plan A §"How the numbers were taken", 0.2 s):

| Edit set applied to default's facts | open names | open derivers | the four projection derivers |
|---|---|---|---|
| none (baseline) | 3,447 | 50 | all four open |
| Astra's 28 (the runtime-fix2 hitting list) + their real callees, **without** `derived-shape` | 2,737 | 45 | `effective` open: `... seon.db/prepared-write → namespace-derivation → dropped-definition-namespaces → program-shapes-in → seon.program/shapes-in → derived-shape` |
| Astra's 28 + real callees + `derived-shape` (plan B's form, verbatim) | 1,785 | **25** | all closed |
| Opus's families (Astra's 28 + 12 more members the prose converts: `seon.schema/database-projection`, `canonical-schema-entities`, `predicate-callable`, `identity-only-descriptors-in`, `projection-state-bindings`, `register-core-predicate!`, both remaining datahike seams, `seon.error.refusal/identity-projector`, `seon.db/program-behavior-digest`, `call-preparation-snapshot`, `seon.sci.eval/copy-host-var`), without `derived-shape` | 2,668 | 44 | `effective` open, same path |
| Opus's families + `derived-shape` | 1,614 | 18 | all closed; `seon.program/program-attributes` and `seon.sci.eval/overlay-plan` still open through `seon.program/derived-program-attributes` (`requiring-resolve 'seon.schema.datahike/storable-properties-in`) |
| Opus's families + `derived-shape` + `derived-program-attributes` | 1,580 | **16** | all closed |

So: **Astra's 25 is right for what plan B modeled** (only the 28 names runtime-fix2 listed, plus the one site it found
by checking the four's reach), and **Opus's 34 is right for what plan A's prose converts, but only once the two
`seon.program` sites are converted too**, which plan A never names (its P3 says "seven + one seams" in `seon.schema`).
The hybrid's number is 34/16, with both `seon.program` sites named in P3b below. Plan A's remaining-16 table is
otherwise exact: my 16 are the same names.

Two facts both plans rest on, verified in the same run: after the families, `seon.db/as-of-key` is outside the closed
reach of `content-projection` (163 names), `declaration-projection` (100) and `build-projection` (132), and inside
`seon.config/effective`'s (553 names, which also hold `seon.db/with`: `effective → committed-value-identity →
current-database-value → resolve-database-value → request-view → with`). A hub change re-derives `effective` and
nothing else of the four; that re-derivation is correct and, with the nested projections hitting, costs tens of ms.

## The families: which answer, and why

### Cycle 2 (`seon.db` → `seon.error`)

Landed (`2f7e1c043`, `seon.error.problem` leaf). Nothing to do; Astra is right that the ten lines are not future
savings, and the census applies the landed edges (my "landed-cycle-2" edit) rather than deleting them.

### Cycle 7 (`seon.db` → `seon.call-preparation`): **Astra's fact, Opus's producers**

Both verify root's ruling (no boot-built writer; Astra counts 126 stored callers of `db/transact!`/`db/with`). The
difference: Opus stores a new per-arity count `:seon.fn.arity/suppliable` and keeps `seon.db/prepared-arity-bounds`
computing `min - suppliable`; Astra stores the prepared arities themselves. Astra wins on the code: today there are two
derivations of one fact that already disagree. `seon.call-preparation/prepared-arities` keeps a variadic arity's
declared minimum ("prepare does not insert their fixed slots"), while `seon.db/prepared-arity-bounds` subtracts the
plan's slot count from every stored row by position, variadic included (`(max 0 (- minimum slots))`). Storing a count
would preserve that split; storing the producer's vector removes it (AGENTS "one derivation, every consumer").

- **Fact.** `:seon.fn/prepared-arities` on the function entity, the value `prepared-arities` returns (its value schema
  already exists as the alias `:prepared-arities :seon.fn/declared-arities` in `resources/seon/schemas/seon.fn.edn`;
  Astra is right that it is not a stored attribute today: declare it stored, `seon.call-preparation`'s, in that
  resource).
- **Producers** (Opus): the writers of the identities the gate watches, each already reaching `seon.call-preparation`
  statically (verified: `seon.fn` and `seon.turn.batch` require it transitively, `seon.call-preparation` reaches
  neither): `seon.fn/index!`, the REPL recording in `seon.turn.batch`, and the supplier-row write in
  `seon.call-preparation`. A supplier change re-asserts the facts of the functions whose slot schemas name its key;
  Opus's stored `:seon.call-preparation/slot-schemas` on the arity makes that a query. All in the one transaction.
- **Writer.** `prepared-arity-bounds` becomes a pull of the stored vector; `arity-mismatch-report` loses its
  `snapshot` argument; the three `defonce` seams go. The writer gains no check (Opus): Datahike's final-report hook
  already judges `:db-after` (Astra), which is where `validate-write-report` sits.
- **Red proof** (Astra's rows in the existing table): `seon.db-test/arity-components-and-callers-are-checked-in-the-final-state`
  gains rows for a variadic callee (settles the semantics as the producer's), a supplier row retracted in a later
  transaction (the call it admitted is refused), a caller and callee repaired in one transaction (accepted), and an
  idempotent attempted write.

### `seon.db` → `seon.program` and `seon.fn/stored-namespace-context`: **Opus**

Not a cycle (verified: neither reaches the other). `seon.db` requires `seon.program`; the pure 10-line
`stored-namespace-context` moves to `seon.program`; the five `defonce` seams go. Astra does not separate this; it is
half an hour and it must land before P3b (both touch `seon.program`).

### Cycle 3 (`seon.schema` ↔ `seon.schema.datahike`, `seon.schema.edn`): **Opus's moves, Astra's completeness, one load-order fix neither saw**

Both name the same wrong side: the bridge acquires the projection ambiently instead of consuming it. Opus's reading of
the bridge is exact (verified by grep: its only function uses of `seon.schema` are `projection-cache-value` ×4,
`projection-validator` ×2, `call-with-forms` ×2, `projection-identity` ×1 and `declaration-projection` ×2 in the two
no-argument conveniences `encode-transaction` and `decode-attribute-value`; the other `schema/` matches are schema
keywords and docstrings).

1. The five primitives move to `seon.schema.internal` (which "never requires `seon.schema`"); the two conveniences move
   up to `seon.schema` (Astra's "upper owner"); the packaged-EDN reader (`seon.schema.edn/packaged-forms`) moves below
   `seon.schema` (Astra's "resource read → forms"), into `seon.schema.internal` or a leaf of its own.
2. **Load order, found while checking plan A's P3:** `seon.schema.datahike` requires `seon.print`, and `seon.print`
   requires `seon.schema` directly, so "`seon.schema` requires the bridge statically" would cycle through the printer.
   The bridge uses one function of it, `seon.print/compare-values` (canonical storage order), whose body depends on
   nothing of `seon.print`. It moves to a leaf both can require (`seon.id` if its digest already orders values
   canonically, else a small `seon.order` leaf; the lane checks which), `seon.print` keeps calling it. Then the seven
   `seon.schema` seams and `schema-edn-packaged-forms` become calls, and `seon.schema/database-projection` and the
   `seon.program/definition-digest` resolution in `canonical-schema-entities` go as Opus says (caller passes the
   carried projection; the digest is computed by its `seon.program` caller).
3. **`seon.program` → bridge (Astra's finding, extended).** `derived-shape` resolves
   `seon.schema.datahike/vector-shaped-attr-in?` and `malli->datahike-schema-in`; `derived-program-attributes` resolves
   `storable-properties-in`. Neither namespace reaches the other (verified), so a `#?(:clj [seon.schema.datahike])`
   require beside the existing `#?(:clj [seon.schema.edn])` and three static calls close them. This one 10-line slice
   closes `seon.config/effective`, `seon.program/program-attributes` and `seon.sci.eval/overlay-plan` (the eval
   request's own plan), and is why the count is 34, not 32.

### Cycle 6 (`seon.error.refusal` → `seon.schema`): **Opus's mechanism, Astra's rule on the fallback**

Both say: the refusal reaches back for a projection the operation already holds; hand it the values. Opus's mechanism is
the concrete one and matches Malli's model (a schema operation gets its registry through its options or the one dynamic
default, never by name): the dynamic Vars `seon.schema/*projection*`, `*projection-state*`, `*packaged-forms*` move to a
portable leaf with no requires (`seon.schema.handed`, ~10 lines; a vocabulary entry for "handed" is the existing
`handed-projection`), which `seon.error.refusal` and `seon.schema` both require; the handed projection carries its
identity projector and explainer as values (`:seon.schema.projection/identity-projector`,
`:seon.schema.projection/explain`, put there by the code that builds it, so `with-predicate-functions`-style one
writer). `schema-seam`, `operation-projection`, `checking-projection`, `identity-projector` go; `cache-seam` becomes a
static require of `seon.cache` in the JVM half (the operator under babashka has no basis, which is when the seam answers
nil today).

Astra's rule is taken for the no-projection case: **no fallback builds the packaged declaration projection inside the
refusal**. With nothing handed (boot, namespace load, a bare thread: the existing
`seon.error.refusal-test/error-is-buildable-where-no-projection-is-handed` builds on a `Thread.`), the error is built
without world references and the recording boundary (`seon.error`, which holds the database) references its worlds once
with the projection it holds; that is where a copy could be stored, so "errors hold references" still holds at storage.
P4's first step counts how often the no-projection path runs on the three cost rows; if it is the common path, the
boundary hands the packaged projection at boot through the leaf's root binding, set once by the boot layer (Opus's
alternative), still with no lookup in the refusal.

### Frame classifier (`classify-namespace`, `prepared-call-prefix`, `referenced`): **Opus**

- `classify-namespace`'s `find-ns` clause: re-run of Opus's probe on default today: 614 loaded namespaces, 2 sourceless
  (`user`, `seon.flow.actions`), none with an `__init.class`, 546 loaded with one (AOT dependencies, all with source).
  The clause changes the answer only for a loaded, sourceless, AOT-compiled namespace, which is a dependency and should
  answer "not authored": the clause is wrong where it matters at all. Delete it; the classifier reads the classpath and
  the frame's class only. This also answers Astra's concern (a sourceless REPL namespace created after its name was
  cached): the answer no longer depends on load state, so the name-keyed cache cannot go stale. Astra's proof row stays:
  a namespace created in a disposable context, no source, classifies authored.
- `prepared-call-prefix`: the literal `"seon.call_preparation$preparing$"` beside `machinery-namespace?`'s literals, with
  a `seon.error.refusal-test` row asserting it equals the munged name of `#'seon.call-preparation/preparing` (Astra
  says the same: derive from the quoted name, never look up the Var).
- `referenced`'s `var?`: test the place classes directly (`IDeref` and not `clojure.lang.Var` and not
  `sci.lang.Var`) in the JVM half, so analysed and compiled agree on both Var kinds (Astra: "keep host/SCI
  representation semantics explicit", which this does). `seon.schema/projection-bindings`,
  `projection-state-bindings` and `seon.sci.eval/copy-host-var` are open only through their contracts' `[:fn
  clojure.core/var?]` (Opus's body probe; Astra notes the same origin): their output schema becomes a named
  `:seon.schema/handed-var` whose predicate is that class test.

### Projection builder (`seon.db/projection-builder`, `loaded-builder`, `connection-projection-state`): **Opus's deletion, Astra's callback point, solved more simply**

Both delete the two name lookups and the `running-instances` scan, and both make every connection constructor carry
its projection state (`carry-connection-projection-state!` is the producer; the scan is a fallback and goes with a row
asserting every constructor in `seon.cluster.boot` and the harness carries it). Opus is right that `::builder-digest` is
a hand-made exact key and goes with P7; until then `::builder` is the loaded program identity read from the carried
state, never found by name.

Astra's point stands and Opus misses it: `content-projection`'s 6-arity takes a `derivation` callback, and
`advanced-content-projection` computes `schema/advance-projection` *before* the lookup and passes the result through
its closure (`#(or advanced (schema/load-projection after base))`), so the value stored under the key taken at the
lookup site (inside `content-projection`) was computed by code outside that key's reach: a branch that changed
`advance-projection` would store its derivation under a key that does not name the change. Astra's answer (pass the callback with its code
identity, and extend lookup, present, retained and base selection to carry it) is machinery. The simpler answer: the
two derivations (`schema/load-projection` from the nearest base; `schema/advance-projection` from a held entry) are
the only callers, so `content-projection` takes the `kind` and branches on it to two named private deriving functions
in `seon.db`. No function value crosses the lookup; the lexical key's closed reach holds both. This is the rule for
P7 generally: **a deriver's derive-function is written in its own body** (runtime-fix2 counted 77 of 78 that way; this
converts the 78th).

### Predicates named in schema data: **both, with the existing mechanism named**

Both reach the same design and cite the same Malli facts (names resolve once, at compile time, from an environment the
caller supplies; the registry maps names to schemas). Astra adds a constraint the implementer must keep: the pinned
fork's `malli.core/-loaded-qualified-value` resolves a qualified symbol to the JVM Var before its SCI path even under
`disable-sci`, so **Malli is given callables, never symbols** (today's `compilable-form`/`bound-forms` already
substitute callables, line-level verified). The mechanism exists and is reused, not added:

- The projection's predicate environment is an input of `build-projection`: the `:seon.schema/predicate-functions`
  option, whose one writer is `with-predicate-functions`; `seon.sci.kernel/context-bound-projection` and
  `held-apart-var` already compute a runtime's bindings and `with-bound-predicates` recompiles the keys naming a rebound
  one. What changes: `predicate-bindings` stops calling `runtime-predicate` for the rest; the caller that holds the
  runtime supplies every predicate the population names (main: the JVM Vars; a branch: its SCI Var where it holds the
  name apart, else main's), as Opus's environment map.
- The key: `projection-fingerprint-from-data` hashes forms, contracts, admissions and exports and nothing of the
  predicates; it gains `{symbol definition-digest}` for every program predicate (69 of 78 on default, all with a stored
  `:seon.program/definition-digest`) and the library coordinate for the 9 library ones. Under P7 this is the explicit
  input Astra asks for; it is not the lexical reach, because the names come from data.
- `unloaded-predicate-functions` becomes a property of the map (a symbol with no binding maps to the refusing callable,
  a function of its name alone). `runtime-predicate` and `loaded-predicate-var` are deleted; `register-core-predicate!`
  stays as the load-time registration Malli's `predicate-schemas` also does, but takes the Var from its caller (the
  registering namespace passes `#'its/predicate`), so it resolves nothing. `predicate-callable` and
  `identity-only-descriptors-in` read the handed projection's bindings and the schema property's value, never
  `requiring-resolve`.

### The remaining 16 and whether open code should exist: **Opus's four kinds and census test; Astra's caveat recorded**

After P1-P6 the 16 open derivers are held by (shortest paths from my census): `seon.cluster.source/{file-proof,
publication}` by `seon.program/base-context-injected-symbols` (`ns-publics`, `require`) and
`seon.fn.analyzer/qualify-schema-symbols`; `member-contributions`, `members-of` by cycle 1
(`seon.cluster.source/contribution` → `seon.test`); `unmerged-count`, `seon.program/own-changed-identities`,
`content-digest` by `seon.program`'s lazy `seon.db` reads (`declaration-digest-map`, `differing-declarations`,
`projected-values`, `fold-content-digest`); `seon.context/conflicts` by `seon.ref/{at-level, named?,
library-definition, resolve-name, alias-form?, namespace-name}`; `seon.embed/corpus` by `texts-of`;
`seon.health/{problems-unit, profile-unit, replaced-roots}` by `seon.problems/stale-vars`, `seon.profile/pain`,
`seon.instrument/replaced-roots`; `seon.sci.eval/{derive-program-context, program-context, record-refusals-once!}` by
the evaluator's own acquisition plus `seon.error.refusal/{current-world, frame-function, interpreted-frames,
var-digest}` and `seon.render.value`'s cycle-8 seams; `seon.test.runner/current-results` by `loaded-identity`.

Opus's four kinds are the right taxonomy and the right end state: load-cycle seams move to their owner; names read from
data are input, resolved by the caller holding the runtime; questions about the program asked of the JVM become reads
of facts; the platform observing itself is declared host-bound (the one exception physics imposes, F2 stays). Then
`run-time-resolution` is a census test, and the open branch of the reach index and binder is deleted. Astra's caveat is
recorded and agreed: a system exposing eval and arbitrary lookup keeps open *entry* operations (kind 4), and no analyzer
rule follows quoted symbols as calls (`3ab155566`, reverted by the owner). Astra's remaining-25 list includes names
the families close (the four `seon.schema` sites above), so it is not the P8 inventory; the 16 above are.

Note for P2: after `seon.db` requires `seon.program`, the `seon.program` → `seon.db` lazy sites (8, inventory "layer
inversions") can never become static requires; those functions read the database, so they move to `seon.db` or take
their reads as input. That is P8's first lane (it closes three of the 16).

## Phases

Each phase is one loadable slice; HEAD boots after each; every deletion is in the commit that converts its last caller.
"Ordinary" commits with `bin/lane commit LANE --branch-evidence`; "platform" with `--test` through a check JVM
(schema resources, writer, boot, evaluator, load order). Estimates are what the work must do, with the measured
numbers the plans already hold; each becomes a row of the cost table (`seon.cost-estimate-test`) in P0.

| Phase | Slice (files) | Red proof | Kind | Estimate (ms / MB, proportional to) | Deletes with its last caller |
|---|---|---|---|---|---|
| **P0** | Commit the measurement and the census: `tmp/runtime-fix2/cost_test.clj`'s `cost-rows` (the three rows, declared long) as `test/seon/cost/closed_runtime_cost_test.clj`; a census deftest over `seon.sci.eval/reach-index` asserting which of the four projection derivers are closed, against a recorded expectation (test only) | `seon.sci.runtime-test/projection-derivers-are-closed` (new; red: all four open today), plus Astra's composed-runtime rows (parent A/h1 then child B/h2, R6's M2 chain; a branch-only predicate; an open value) added to `seon.sci.runtime-test/a-composed-runtime-answers-what-its-own-source-answers` (red on HEAD) | platform (test runner) | census 161 ms, ~64 MB once the calls facts are held; proportional to `:seon.fn/calls` (7,683 names) | nothing |
| **P1** | Cycle 7: `:seon.fn/prepared-arities` stored (`resources/seon/schemas/seon.fn.edn`, `seon.call-preparation`), written by `seon.fn/index!`, the `seon.turn.batch` recording and the supplier-row write, `:seon.call-preparation/slot-schemas` on the arity; `seon.db/prepared-arity-bounds` pulls; three seams and `arity-mismatch-report`'s snapshot argument deleted. First step: census of `transact!` callers writing `:seon.fn/spec` or `:seon.call-preparation/key` (must be the three producers) | `seon.db-test/arity-components-and-callers-are-checked-in-the-final-state`: variadic, supplier-retracted, same-tx repair, idempotent rows (red while the fact is unread) | platform (schema resource + writer) | per changed function one `plan-for`, 0.1-1 ms, which the gate pays today on a snapshot miss; the writer stops building the 174-188 ms snapshot; bounded one-function write < 100 ms, < 8 MB (Astra) | `seon.db/call-preparation-{snapshot,report-snapshot,plan-for}` (14 lines), `prepared-arity-bounds` shrinks 23 → ~8 |
| **P2** | `seon.db` requires `seon.program`; `seon.fn/stored-namespace-context` moves to `seon.program`; five seams deleted (`seon.db`, `seon.program`, `seon.fn`) | none new: P0's census row counts `seon.db/program-*` as non-openers (red until moved) | ordinary | 0 runtime cost (a static call replaces a delay deref) | five `defonce` seams (12 lines) |
| **P3a** | Cycle 3: five primitives → `seon.schema.internal`; the two conveniences → `seon.schema`; `packaged-forms` reader below `seon.schema`; `seon.print/compare-values` → a leaf (`seon.id` or `seon.order`) and the bridge drops `seon.print`; `seon.schema` requires the bridge; eight seams, `database-projection`, `canonical-schema-entities`' resolution deleted (`seon.schema`, `seon.schema.datahike`, `seon.schema.edn`, `seon.schema.internal`, `seon.print`, the leaf) | `seon.schema.datahike-test/encode-transaction-resolves-the-declaration-population-once` gains Astra's closed-reach assertion (the bridge's reach holds no opener), and a `seon.contracts-compile-test` row: a fresh require of `seon.schema.datahike` in a check JVM loads no `seon.schema` (red today) | platform (load order, publication: publish and reload on a branch of the live store) | 0 runtime cost; one explicit-projection transform < 20 ms, < 4 MB on the bounded test population (Astra) | eight seams (25 lines), `database-projection` (3) |
| **P3b** | `seon.program` requires `seon.schema.datahike` (`#?(:clj)`); `derived-shape`'s two and `derived-program-attributes`' one resolution become calls (`seon.program` only) | P0's census row: `seon.config/effective` closed (red until this lands; it is the only phase that closes it) | ordinary | 0 runtime cost | three `requiring-resolve` forms |
| **P4a** | Frames: delete `classify-namespace`'s `find-ns` clause; `prepared-call-prefix` literal; `referenced` and the handed-var schema test place classes (`seon.error.refusal`, `seon.error.refusal-test`, the `:seon.schema/handed-var` schema resource) | `seon.error.refusal-test`: (a) the literal equals the munged `#'seon.call-preparation/preparing`; (b) a sourceless namespace created in a disposable context classifies authored; (c) an error built analysed on a changed runtime and compiled on main from one throwable references the same worlds (red today: analysed `var?` differs) | ordinary (the schema resource row is platform) | per error: one `find-ns` and one Var lookup fewer; classification proportional to distinct namespaces in the frames (unchanged) | the `find-ns` clause, the Var lookup (14 → 1 line) |
| **P4b** | Cycle 6: `seon.schema.handed` leaf; the handed projection carries projector and explainer; the refusal reads handed values; `seon.error` references worlds once where none was handed; `schema-seam`, `operation-projection`, `checking-projection`, `identity-projector`, `cache-seam` deleted (`seon.error.refusal`, `seon.schema`, `seon.error`, the leaf). First step: count the no-projection path on the three cost rows | `seon.error.refusal-test/error-is-buildable-where-no-projection-is-handed` stays green and gains: the bare-thread error, once recorded by the boundary, references its worlds exactly once; `constructor-consumes-only-the-throwable-input`, `a-place-in-an-error-is-named-never-printed-through`, `error-constructor-builds-the-declared-alternative` run | ordinary for the refusal; platform if the boot root binding is needed (decided by the count) | per error after acquisition: < 5 ms, < 0.5 MB over ≤ 16 frames (Astra); one map get instead of a delay deref and a `find-ns` | `schema-seam` (16), `operation-projection` (11), `checking-projection` (15), `identity-projector` (4), `cache-seam` (5) |
| **P5** | Builder: `::builder` read from the carried projection state; `content-projection` branches on `kind` to two named derivers (no callback crosses the lookup); `projection-builder`'s lookups, `loaded-builder` and the `running-instances` scan deleted (`seon.db`, `seon.cluster.boot`, the harness) | `seon.db-test` row: every connection `seon.cluster.boot` and the harness construct carries `:seon.sci.eval/projection-state` (red if any source lacks it, counted first); Astra's `seon.db-test/projection-cache-uses-the-executing-builder` (new): compiled main reading a branch value derives under its own identity, the branch under its own | platform (writer, boot) | saves a `find-ns` pair and the `running-instances` scan per projection read; warm key selection < 1 ms, < 0.05 MB (Astra) | `projection-builder` (15), `loaded-builder` (65), the scan (~12 of 16) |
| **P6** | Predicates as input: the environment map supplied by the runtime's holder through `:seon.schema/predicate-functions`; fingerprint gains `{symbol digest}`; `runtime-predicate`, `loaded-predicate-var` deleted; `register-core-predicate!` takes the Var; `unloaded-predicate-functions` a property of the map; `predicate-callable`, `identity-only-descriptors-in` read handed values (`seon.schema`, `seon.sci.kernel`, `seon.print`'s and `seon.schema.edn`'s registrations) | `seon.schema-test/one-predicate-symbol-cannot-name-two-environments-callables` and `a-rebound-predicate-recompiles-the-keys-naming-it` gain Astra's opposite-cache-order and mixed-branch rows through real branch execution (red under the exact key today: the key holds no predicate digest); P0's census row goes green (all four closed) | platform (projection derivation, publication) | 78 digests read per projection derivation (facts, cached with the projection); warm validator call < 1 ms; a one-predicate change recompiles only the keys naming it, < 20 ms, < 4 MB (Astra); unchanged content compiles zero predicates | `runtime-predicate` (51 → 0), `loaded-predicate-var` (20), `unloaded-predicate-functions` (44 → a few), `predicate-callable` (12); the 161 old lines Astra measured, less the registration |
| **P7** | The lexical exact key (runtime-fix2 option 1: a derivation keyed by its deriver's closed-reach changes, held in the analysed definition, kept by `arm!`, `rearm-dependents!`, `fork-cluster-ctx`); R3 (macro expansion carries the reached code identity) and R5 (`defonce` is an observation, never re-derived) on it; `::builder-digest` deleted; the R4 host-bound report deleted (`seon.sci.eval/host-bound-reaching`, `::host-bound-reached`, `:seon.batch/host-bound-reached`, `seon.sci.runtime/host-bound-reached`, `::host-bound-reach`); R6: the differential test drives `seon.sci.arm/arm!`, `interpret-overlay!`, `fork-cluster-ctx`, changes a namespace's resolution, and holds the re-review's mutants M1, M2, M5, M7 red (`seon.sci.eval`, `seon.sci.runtime`, `seon.sci.arm`, `seon.cache`, `seon.db`) | runtime-rereview's R1, R2 (closure, lazy seq, raw thread), R3 reproductions as `seon.sci.eval-test` rows (red on HEAD); the M2 two-step chain as a deterministic row; `seon.profile/measure` on the three rows, parent against change in one run | platform (evaluator) | **gate: `:hub-behavior-change` ≤ 1.3x of 716cc7527's 897.7 MB on the same probe**, `:seon-db-nine-forms` within 1.1x, `:similar-by-name-warm` within 1.05x. Estimate: hub 1.13-1.28x (Opus, HEAD's 1,015 MB plus `effective`'s re-derivation), Astra's wider band 1.17-1.45x is the risk; a measured miss stops the key for the owner | `seon.cache/*reached*`, `seon.sci.runtime/reaching`, `reached-changes`' open branch, the R4 report (five names) |
| **P8** | The 16 by Opus's four kinds, one lane per kind, file-disjoint after a census on HEAD; first lane: `seon.program`'s lazy `seon.db` reads move to `seon.db` or take their reads as input (closes `unmerged-count`, `own-changed-identities`, `content-digest`); then `run-time-resolution` becomes the census test and the open branch of `seon.sci.runtime/reach-index` and the binder is deleted | `seon.sci.eval-test/no-runnable-src-function-resolves-a-name-at-run-time` (new, red until the last site); Astra's `seon.test.runner-test/reach-results-use-the-executed-runtime` and `seon.program-test/content-digest-needs-no-loaded-namespace` | ordinary where only pure consumers change; platform for the evaluator, runner and the host-bound producer rule | changed-input work only; each held-data digest or report < 20 ms, < 4 MB at its fixture size; no namespace enumeration | the open branch of the index and binder; `run-time-resolution` as an input |

**Order and parallelism** (file ownership from the slices above):

- Wave 1, file-disjoint, in parallel: **P0** (tests only), **P2** (`seon.db`, `seon.program`, `seon.fn`), **P3a**
  (`seon.schema`, the bridge, `seon.schema.edn`, `seon.schema.internal`, `seon.print`, the order leaf), **P4a**
  (`seon.error.refusal` and its test).
- Wave 2, after wave 1, in parallel: **P1** (`seon.db`, `seon.call-preparation`, `seon.fn`, `seon.turn.batch`, the
  schema resource; after P2 for `seon.db`), **P3b** (`seon.program`; after P2), **P4b** (`seon.schema`, the refusal,
  `seon.error`, the handed leaf; after P3a and P4a).
- Wave 3: **P5** then **P6** (both on `seon.db`'s and `seon.schema`'s projection path; P6 needs P4b's handed values).
- Wave 4: **P7**, after P6 (the gate needs the four closed). Wave 5: **P8**'s lanes.

P1, P3a, P5, P6 and P7 are platform slices (one check-JVM slot each; at most one host-bound lane per slot per
AGENTS); P2, P3b, P4a run REPL-first beside them.

## What gets deleted, and what was rejected

Old form lines displaced, from Astra's edamame spans (the honest unit; moves are not deletions): builder trio 90,
preparation seams 6, `prepared-arity-bounds` 22, program seams 8, resource/bridge seams 20, predicate resolution 161,
refusal seams 48: **355 lines**, before P7's `reaching`/`*reached*`/R4 report and P8. Opus's per-function count agrees
(about 300 net after the environment map, `arity-facts`, the two leaves and the fingerprint input). Moved, not deleted:
the five primitives (~60), `stored-namespace-context` (10), `compare-values` (~40).

Rejected, simplest first, both plans agreeing: follow quoted symbols as calls (`3ab155566`, closes 28 `src` names, owner
reverted); narrow "open" to code-yielding calls (keeps all 50); the exact key with open derivers keyed by the whole delta
(correct, 5.8x); validators on the connection for cycle 7 (no single writer); `seon.error.refusal` requiring
`seon.schema` (reverses the cycle); open code run compiled everywhere (not live). Rejected here beyond both: Opus's
`:seon.fn.arity/suppliable` (a second derivation of a fact that already has two disagreeing ones); Astra's callback
identity carried through lookup, present, retained and base selection (unneeded once no callback crosses the lookup);
Astra's runtime observation captured at the entrant for frame classification (the classifier is pure once the `find-ns`
clause goes).

## Open questions for the owner

None requires a decision before the phases are filed. One check for root at P4b: if the count shows the no-projection
path is common on the cost rows, the boot layer sets the handed leaf's root binding once (Opus's alternative), which
makes P4b a platform slice; the hybrid prefers it over an unreferenced error reaching the boundary only if that count
says so.

## Evidence (rerun)

- Calls facts: plan A §"How the numbers were taken", form 1, on default in JVM mode (40 ms, 235 MB this run; 7,683
  names, 7,679 with calls, 124 host-bound).
- The fold: plan B §"Census reproduction" verbatim, with these edit sets added in the same shape: `landed-cycle-2`
  (`seon.db/error-*` → `seon.error.problem/*`), the 12 extra family members cut, `seon.program/derived-shape` →
  `#{seon.schema.datahike/malli->datahike-schema-in seon.schema.datahike/vector-shaped-attr-in?}`,
  `seon.program/derived-program-attributes` → `#{seon.schema.datahike/storable-properties-in}`. Each row of the
  reconciliation table is one run; 0.2 s in babashka.
- Require graph: the `(ns ...)` forms of `src` read as text; `seon.print` → `seon.schema` is a direct edge;
  `seon.fn` reaches `seon.call-preparation`, which reaches neither `seon.fn` nor `seon.turn.batch`; `seon.program` and
  `seon.schema.datahike` reach neither each other nor `seon.db`.
- Sourceless-namespace probe: plan A §"Cycle 6" form, on default today: `{:loaded 614 :sourceless [user
  seon.flow.actions] :loaded-with-init-class 546 :sourceless-with-init-class ()}` (105 ms, 44 MB).
- Malli fork `-loaded-qualified-value`: plan B's two-registry experiment, not re-run; the constraint it implies
  (callables, never symbols) is already how `seon.schema/compilable-form` binds.

## Narrow check

Lane closed-check, 2026-10-19, HEAD `f636718cd`, default `a92ba5b94`. Three questions per phase: (1) every claim about the
live system reproduces on default; (2) every deletion is in the commit that converts its last caller; (3) every phase names
a red proof that resolves. Probes: JVM-mode reads on default (the sourceless-namespace form, the calls facts spit to the
scratchpad, `seon.schema/!fallback-counts`), babashka over those facts for the census, the `(ns ...)` forms of `src` read as
text for the require graph, `rg` over `src script test resources`. No SCI request, no reload.

### What reproduced (claims, all supported)

- Facts: 7,683 names, 7,679 with calls, 124 host-bound. Baseline census 3,447 open names, 241 direct openers, 78 cache
  callers, **50 open** derivers.
- Reconciliation table: baseline 50; Astra's 28 plus callees 45 open derivers (effective open); plus `derived-shape` **25**,
  all four closed; Opus families 44 (2,661 names here; plan says 2,668, my 12-name list is approximate); plus `derived-shape`
  18; plus `derived-program-attributes` **16** (1,580 names). The remaining 16 are exactly the hybrid's list.
- The four projection derivers are `seon.schema/build-projection`, `seon.schema/declaration-projection`,
  `seon.db/content-projection`, `seon.config/effective`. Closed reaches 132 / 100 / 163 / 553; `seon.db/as-of-key` is only in
  `effective`'s and `seon.db/with` too.
- `seon.print/compare-values` calls no `seon.*` function (its `:seon.fn/calls` are all `clojure.core`).
- Frame classifier: 614 loaded namespaces, sourceless `[user seon.flow.actions]`, 546 with an `__init.class`, none sourceless
  with one. `classify-namespace`'s second clause (`(not (io/resource <frame class>.class))`) already answers authored for a
  REPL-defined class. SCI's `var?` is `(instance? sci.lang.Var x)` (`sci.impl.utils/var?`), so analysed code reads a
  `clojure.lang.Var` as a place: the red for P4a (c) is real.
- Require graph (static `:require`): `seon.print` -> `seon.schema` direct; `seon.schema.datahike` -> `seon.print`, `seon.schema`
  direct; `seon.schema` -> `seon.schema.datahike` not; `seon.program` and `seon.schema.datahike` reach neither each other nor
  `seon.db`; `seon.call-preparation` reaches `seon.db` and `seon.batch` and neither `seon.fn` nor `seon.turn.batch`;
  `seon.cache` reaches no `seon.*`. The bridge's `seon.schema` uses are exactly projection-cache-value 4, projection-validator 2,
  call-with-forms 2, projection-identity 1, declaration-projection 2.
- `seon.schema/!fallback-counts` on default is `{}`: the packaged-forms fallback has not been taken in this process.

### Per-phase verdict

| Phase | Q1 claims | Q2 deletions and callers | Q3 red proof | Verdict |
|---|---|---|---|---|
| P0 | pass (census 63 ms / 228 MB to hold the facts, 0.2 s fold; plan said 161 ms / 64 MB for the fold alone) | nothing deleted | **fail** | **fail** |
| P1 | **fail** | pass with a miss (below) | **fail** | **fail** |
| P2 | pass | pass | **fail** | **fail** |
| P3a | **fail** | **fail** | **fail** | **fail** |
| P3b | pass for the edit; the effect claim fails | pass | **fail** | **fail** |
| P4a | pass | pass | pass | **pass** |
| P4b | **fail** | pass with a miss | pass with a change | **fail** |
| P5 | **fail** | **fail** | **fail** | **fail** |
| P6 | pass | **fail** | pass | **fail** |
| P7 | pass (gate numbers match plan A's table) | **fail** | uncertain | **fail** |
| P8 | **fail** | pass | pass | **fail** |

#### P0. The census row cannot be the red proof of P2, P3b or P5

The cumulative census (P1, P2, P3a, P3b, P4a, P4b, P5, P6 in that order; the `seon.db/error-*` seams already landed) is:

| After | open names | open derivers | newly closed derivers |
|---|---|---|---|
| landed cycle 2 | 3,443 | 50 | |
| P1 | 3,440 | 50 | none |
| P2 | 3,438 | 50 | none |
| P3a | 3,423 | 50 | none |
| P3b | 3,418 | 49 | `seon.program/program-attributes` |
| P4a | 3,386 | 49 | none |
| P4b | 2,893 | 46 | `seon.cluster/branch-declaration-changes`, `seon.cluster/derivation-keys`, `seon.cluster.source/merge-base` |
| P5 | 2,889 | 46 | none |
| P6 | 1,580 | **16** | the four projection derivers and 26 more, among them `seon.config/effective`, `seon.sci.eval/overlay-plan`, `seon.db/request-view`, `seon.test.runner/tests-reaching` |

(The order matters for the intermediate rows; only P6's 16 is order-independent.) So a single row "the four projection
derivers are closed" is red from P0 to P5 and gives P2, P3a, P3b, P4a and P5 nothing to turn green. **Correction.** (a) P0's
census is the exact ratchet, green on HEAD: it reads `seon.sci.eval/reach-index` and asserts the recorded open-deriver set
(50 names); each phase's commit edits the recorded set (P3b -1, P4b -3, P6 -30) and the count of open names. (b) Each phase
adds rows over `:seon.fn/calls`, which are order-independent and red before, green after: for every function the phase
converts, no member of `seon.sci.eval/run-time-resolution` is in its `:seon.fn/calls`. The named functions per phase are
in the tables below. (c) `seon.sci.runtime-test/projection-derivers-are-closed` is filed with P6, where it turns green; the
composed-runtime rows move to P7. AGENTS ("a test already red on main stops the merge") forbids landing red proofs ahead of
the phase that turns them green, so P0 itself is only the cost rows (green: they record) and the ratchet. (d) The cost rows
are the committed `tmp/runtime-fix2/cost_test.clj`; it reads `System/getenv "RR_LABEL"` and writes records into `tmp`
(`guarded`, `record!`): rewrite it to the cost-table shape of `seon.cost-estimate-test` (estimate in the row, measurement
by `seon.profile/measure`, nothing written to `tmp`).

#### P1. Cycle 7

- **Fail, Q1.** The hybrid says `seon.fn` and `seon.turn.batch` "require `seon.call-preparation` transitively". `seon.turn.batch`
  does (through `seon.sci.eval`); **`seon.fn` does not** (its `:require` closure holds neither `seon.call-preparation` nor a
  namespace requiring it). The edge is legal (`seon.call-preparation` reaches `seon.db` but not `seon.fn`), so P1 adds a static
  require of `seon.call-preparation` to `seon.fn` for `index!`; say so.
- **Fail, Q1/Q3: the attribute name is taken.** `:seon.fn/prepared-arities` is already a registered key
  (`resources/seon/schemas/seon.fn.edn`, alias of `:seon.fn/declared-arities`, a vector of maps) and a member of the arity
  mismatch report. The stored form must be written down: card-many component refs of `:seon.fn.arity/min`/`max`, as
  `:seon.fn/arities` is (the attribute and the report member then share the key with one meaning, the pull of the stored
  rows being the report's vector). Datahike cannot store the vector value the report uses.
- **Fail, Q1 (cost).** `seon.call-preparation/plan-for` takes the `current` snapshot (`supply-index`). The producers pay one
  `seon.call-preparation/snapshot` (the memo keyed by the supplier rows) per supplier change, not 0.1-1 ms per function. Name
  it in the estimate; the snapshot memo is what the writer pays today.
- **Q1, supported.** `seon.call-preparation/prepared-arities` has **no `src` caller today** (only
  `seon.call-preparation-test` line 844); the live gate is `seon.db/prepared-arity-bounds`. The variadic disagreement (the
  producer keeps the minimum, the gate subtracts the slot count by position) is real, so the variadic row is red on HEAD.
- **Q2.** The three `defonce` seams and their derefs (`seon.db` 5198-5232, `prepared-arity-bounds`, `arity-mismatch-report`'s
  snapshot argument) have no caller outside `seon.db`. **Miss:** `seon.call-preparation/report-snapshot` loses its only `src`
  caller (kept alive only by `seon.call-preparation-test` line 593): delete it in the P1 commit with its test, or the phase
  leaves a dead public function. `plan-for` stays (the producers call it; 15 test sites).
- **Fail, Q3.** The proof's fixtures are `test-support/program-fn-entity` and `program/with-contract-facts`, which produce the
  entity without the new fact (`:seon.fn/arities` is derived in `seon.program/canonical-row`, which `seon.program` cannot
  extend with a `seon.call-preparation` call: P2 makes `seon.db` require `seon.program`, and `seon.call-preparation` requires
  `seon.db`). The fixture helpers are a fourth producer: they must write the fact through the canonical path, and the census
  first step ("`transact!` callers writing `:seon.fn/spec` or `:seon.call-preparation/key` must be the three producers") must
  count them: `:seon.fn/spec` is written in `my.program`, `seon.cluster.instruction`, `seon.effect`, `seon.sci.eval`,
  `seon.sci.lookup`, `seon.test.runner`, `seon.turn` and `test/seon/test_support`. Only the variadic row is red on HEAD by
  construction; the supplier-retracted row is **uncertain** (written to be red only if the snapshot memo can answer a stale
  supplier set; reproduce on HEAD before filing it). Write the four rows as exact fixtures (callee contract, call arity,
  expected refusal member) before the task is filed.

#### P2. `seon.db` requires `seon.program`

- Q1 pass: no `seon.program` -> `seon.db` edge, no `seon.db` -> `seon.program` edge, `seon.program` reaches no `seon.fn`.
- Q2 pass: the five `defonce`s are all in `seon.db` (lines 5459-5467); their deref sites are `definition-digest-of`,
  `namespace-digest-of`, `behavior-digest`, `namespace-digests-tx`, `write-digest-error` and `dropped-definition-namespaces`.
  `seon.fn/stored-namespace-context` has no other `src` caller; `seon.db`'s docstring at line 5754 and three `docs/seon/issues`
  pages cite it (convert the docstring; the issues are history).
- **Fail, Q3.** "P0's census row counts them as non-openers" turns nothing green (P2 closes no deriver; table above).
  **Correction:** the row asserts that the six functions above have no direct opener in `:seon.fn/calls` (red on HEAD,
  green after), and a require-graph assertion that `seon.db` requires `seon.program` (read the `(ns ...)` form with edamame,
  as the census does).

#### P3a. Cycle 3 and the printer leaf

- **Fail, Q1: the packaged-forms reader cannot move "below `seon.schema`".** `seon.schema.edn/packaged-forms` ->
  `packaged-population` -> `resource-population` -> `derive-config-forms` -> `config-dial-entries` and
  `config-registration-defaults`, which call `seon.schema/structural-schema` (lines 67 and 136); the same namespace also calls
  `schema/core-predicate-registered?`, `projection-with-schema`, `assert-complete-contract!`, `current-projection`,
  `handed-projection`. Moving the reader means moving that dependency set too. **Correction (simpler, and AGENTS says no
  fallbacks):** the seam exists for one place, `seon.schema/packaged-forms` (the warned classpath fallback,
  `warn-classpath-fallback!`), whose count on default is `{}`. Delete the fallback: a caller with no population in hand
  hands one (the fallback's own warning already names the caller to convert). First step of P3a: count the fallback on the
  three cost rows and the test suite's `!fallback-counts`; if zero in steady state, `seon.schema` needs neither the seam nor
  the move, and `seon.schema.edn` stays above `seon.schema`. Only if the count is non-zero does the minimal set move.
- **Fail, Q2: `print/compare-values` has 19 other callers.** The hybrid says "moves to a leaf, `seon.print` keeps calling it".
  A moved function converts every caller in the same commit: `seon.test` (2), `seon.db` (2), `seon.fn` (11), `seon.turn`,
  `seon.reconcile` (cljc), `seon.program` (cljc), `seon.config`, `seon.cluster`, the bridge (2) and its own uses in
  `seon.print`. That is a scripted sweep over eight files other lanes hold (P1, P2 edit `seon.db`, `seon.fn`, `seon.program`
  in the same wave). Either schedule P3a's sweep after wave 1 (claim the eight files in one window), or reduce it: only the
  bridge needs the leaf, so make the bridge's two calls use `seon.id`'s new function and leave one definition (the leaf's)
  with `seon.print/compare-values` deleted and its callers converted; there is no thin alias.
- Q2 also: `seon.schema/database-projection` has a test caller (`test/seon/test_support_test.clj`); `canonical-schema-entities`
  has about 40 caller sites in `src` and `test` (the hybrid deletes only its `seon.program/definition-digest` resolution,
  which is right, but the test files must be in the slice).
- Q1 pass: the eight seams are exactly `schema-edn-packaged-forms` and seven bridge seams (lines 42-59); the bridge's
  `seon.print` use is only `compare-values` (lines 523 and 546).
- **Fail, Q3.** "A fresh require of `seon.schema.datahike` in a check JVM loads no `seon.schema`" in
  `seon.contracts-compile-test` is impossible in that namespace (it requires `seon.schema` itself) and needs a JVM per row.
  **Correction:** a deftest in `seon.schema.datahike-test` that reads the `(ns ...)` forms of `src` with edamame and asserts the
  transitive `:require` closure of `seon.schema.datahike` does not contain `seon.schema` or `seon.print`. Red today (the
  bridge requires both). The existing `encode-transaction-resolves-the-declaration-population-once` is green on HEAD and
  stays so; its closed-reach addition is fine as a second row.

#### P3b. `seon.program` requires the bridge

- Q1/Q2 pass: `seon.program` and the bridge reach each other not; the three `requiring-resolve` sites are
  `seon.program` lines 62, 320-325 (`derived-shape`'s two and `derived-program-attributes`').
- **Fail, Q3 (a false claim).** "`seon.config/effective` closed; it is the only phase that closes it" is contradicted:
  after P3b alone the four projection derivers stay open and only `seon.program/program-attributes` closes (49 open
  derivers); `effective` closes at P6. **Correction:** the proof is the P3b row of the P0 ratchet (open derivers 50 ->
  49, `seon.program/program-attributes` leaves the set) plus the direct-opener row for `derived-shape` and
  `derived-program-attributes`. The statement that the slice "closes `effective`, `overlay-plan`" holds only with P6.

#### P4a. Frames: pass

Q1: reproduced above; the literal is `seon.call_preparation$preparing$` (munge of the current derivation). Q2: `classify-namespace`,
`prepared-call-prefix`, `referenced` have callers only inside `seon.error.refusal`; `identity-projector` hits in
`seon.sci.admit` are a map key and `schema/identity-only-projector`, not the refusal's. Q3: refusal-test rows (a)-(c) resolve
to the existing namespace; `error-is-buildable-where-no-projection-is-handed` exists (line 463) and is green by reading.
Note for the implementer: `seon.error.refusal` is `^{:seon/babashka true}` and loaded by `script/seon/operator.clj`, so every
edit must stay babashka-loadable (the class test names `sci.lang.Var`, which babashka has).

#### P4b. Cycle 6

- **Fail, Q1: the cache seam cannot become a static require.** `seon.error.refusal` is a `.clj` namespace loaded under
  babashka by `script/seon/operator.clj` and `central_round.clj`; `seon.cache` is Caffeine. "A static require of `seon.cache`
  in the JVM half" needs a `.cljc` (a `#?(:bb ...)` branch) or does not exist. **Correction:** rename
  `seon.error.refusal` to `.cljc` with a reader conditional, or hand the cache lookup as a value through the handed leaf like
  the projector; pick the one that keeps one definition. Without it P4b breaks the operator.
- **Fail, Q1/Q3: the "no fallback" route contradicts a test that must stay green.**
  `error-is-buildable-where-no-projection-is-handed` builds `:seon.error/base` (valid) and `:no.such/error` (undeclared,
  layer `:seon.error.refusal/construction`) on a bare `Thread.`, which needs a declaration population to tell the two
  apart. Without a handed projection the refusal cannot, so the bare-thread error cannot be "built without world references
  and referenced once at the boundary" and still pass. The test's own comment says the MCP thread, boot and namespace load
  are exactly that case. **Correction:** decide now, not after the count: the handed leaf's root binding is set once by the
  boot layer (Opus's alternative), which makes P4b a platform slice. The count step then only measures, it does not choose.
- Q2: `schema-seam`, `operation-projection`, `checking-projection`, `cache-seam` have callers only in
  `seon.error.refusal` (6, 4, 2, 2 sites); `identity-projector` too. Pass with the miss above.

#### P5. Builder

- **Fail, Q1: the constructor list is wrong.** The hybrid says "every constructor in `seon.cluster.boot` and the harness". The
  carriers of `:seon.sci.eval/projection-state` are `seon.sci.eval`, `seon.cluster.agent`, `seon.test`; the connections are made
  by `seon.cluster.store` (lines 640, 724, 761, 777) and `seon.db` (137), and about 20 test sites call `d/connect` directly
  and carry nothing (`test/seon/db_test`, `turn_work_test`, `flow_test`, `test_support`, `store_transact_test`,
  `retired_attributes_test`, `acquisition_cost_test`, `branch_execution_test`, `web_test`, `admission_test`, ...). Removing the
  `running-instances` scan makes every one of them read no state. The proof row's census must list these sites and the phase
  must convert them to the canonical constructor (or the scan has a remaining user).
- **Fail, Q1: the producer of the loaded identity is not named.** `loaded-builder` today folds `seon.sci.eval/loaded-identity`
  (adoption record, mixed state, boot commits that disagree). "`::builder` read from the carried projection state" requires the
  state map to hold the loaded program identity and the adoption path (`seon.sci.eval`, where `loaded-identity` is reset)
  to write it; say which key, and who writes it, or the phase cannot be implemented.
- **Fail, Q2: test callers missed.** `seon.db/projection-builder` (`test/seon/db_test.clj`) and `loaded-builder`
  (`test/seon/db_test.clj`, `test/seon/cluster/program_change_test.clj`) are quoted by tests; they convert in the commit.
  `connection-projection-state` has one caller (`seon.db` line 359).
- **Fail, Q3.** `projection-cache-uses-the-executing-builder` is not specified enough to write (no fixture, no assertion).
  Specify: two values over one store, one carrying state A and one state B with different builder identities; reading both
  through `seon.db/carried-projection` yields two cache keys and two derivations, and the same value read twice yields one.

#### P6. Predicates as input

- Q1 pass (callers of `runtime-predicate` 11 sites, all in `seon.schema`; `loaded-predicate-var` 3; `unloaded-predicate-functions`,
  `predicate-callable`, `identity-only-descriptors-in` one site each in `seon.schema`).
- **Fail, Q2: `register-core-predicate!` has about 70 call sites in 25 `src` files and 7 test files**
  (`seon.flow` 8, `seon.cluster.store` 5, `seon.sci.eval` 5, `seon.db` 4, `seon.print` 4, `seon.turn.world` 3, `my.*`,
  `seon.render.*`, `seon.sci.admit`, `seon.test.runner`, ...), not "`seon.print`'s and `seon.schema.edn`'s registrations". Its
  docstring says its call sites are "queued for deletion". After the change it resolves nothing, so it is a no-op: **delete
  the function with its call sites in one scripted sweep (its own commit, before the rest of P6)**; "takes the Var from its
  caller" would convert 70 sites to keep a no-op. `core-predicate-registered?` (uses `runtime-predicate`) and the tests that quote
  private vars (`seon.schema-test` lines 1165, 1716; `seon.instrument-test` docstring 1204) are callers to convert too.
- Q3: the named tests (`one-predicate-symbol-cannot-name-two-environments-callables`, `a-rebound-predicate-recompiles-the-keys-naming-it`)
  exist in `test/seon/schema_test.clj` (lines 599, 1642) and are green by reading; the added rows are the red part. The
  census row `projection-derivers-are-closed` is filed here (see P0).

#### P7. The exact key

- Q1 pass: the gate (hub row <= 1.3x of 897.7 MB = 1,167 MB, nine-forms 1.1x, warm 1.05x) matches plan A's table; R1-R6, M1, M2,
  M5, M7 exist in `runtime-rereview.md` with their dangling commits.
- **Fail, Q2: the R4 deletion misses callers.** `host-bound-reached` is read by `seon.sci.arm` (4 sites), `seon.turn.batch` (2),
  `resources/seon/schemas/seon.batch.edn` (3), `seon.sci.eval-test`, `seon.sci.runtime-test`; `host-bound-reaching` by
  `seon.sci.eval-test`. The slice's file list omits `seon.turn.batch` and the two tests; `seon.db`'s `builder-digest` has 5 test
  sites in `seon.db-test`.
- Q3 uncertain: the R1-R3 and M2 rows "red on HEAD" were not reproduced here (they need SCI on a branch, which this check does
  not run). The M2 chain exists as a dangling commit (`df9737e97`, `3c79c77b8`); reproduce before filing.

#### P8. The 16

- **Fail, Q1: the first lane's list does not close the three it names.** Converting the four helpers the hybrid names
  (`declaration-digest-map`, `differing-declarations`, `projected-values`, `fold-content-digest`) leaves
  `unmerged-count`, `own-changed-identities` and `content-digest` open, because `content-digest` and `own-changed-identities`
  hold direct `requiring-resolve` calls themselves (also `changed-identities`, `declaration-changed-identities`, `overrides`).
  Re-run with exactly these six converted: **16 -> 13**, those three close. **Correction:** the lane converts
  `seon.program/{content-digest, own-changed-identities, declaration-digest-map, differing-declarations, projected-values,
  fold-content-digest}` (`changed-identities`, `declaration-changed-identities`, `overrides` are not needed for the count).
- Q2 pass (the open branch of the index and `run-time-resolution` have one consumer each, `seon.sci.eval/reach-index`).
- Q3: `no-runnable-src-function-resolves-a-name-at-run-time`, `reach-results-use-the-executed-runtime` and
  `content-digest-needs-no-loaded-namespace` are new; their namespaces exist (`seon.sci.eval-test`, `seon.test.runner-test`,
  `seon.program-test`). The first is writable exactly: over `:seon.fn/calls` of every non-host-bound `src` function, no member of
  `run-time-resolution` after the last site; it is red until then.

### Corrections that change the plan

1. P0: census as a green exact ratchet with per-phase direct-opener rows; red proofs move to P6 (census) and P7 (composed rows).
2. P1: add `seon.fn` -> `seon.call-preparation`; specify the stored shape of `:seon.fn/prepared-arities`; count the fixture helpers as a producer; delete `report-snapshot`.
3. P3a: delete the packaged-forms fallback (count first) instead of moving the reader; `compare-values` move is a 19-caller sweep or a single definition; proof is a require-closure test.
4. P3b: its effect is `program-attributes` only; `effective` closes at P6.
5. P4b: `seon.error.refusal` is babashka-loaded (`.cljc` or a handed lookup); the boot root binding is required, not conditional.
6. P5: constructors are in `seon.cluster.store`, `seon.db` and ~20 test sites; name the state key and its writer; convert the tests.
7. P6: delete `register-core-predicate!` and its ~70 sites first, as a scripted sweep.
8. P7: add `seon.turn.batch` and the tests to the R4 deletion.
9. P8: convert the six named `seon.program` functions, not the four.
