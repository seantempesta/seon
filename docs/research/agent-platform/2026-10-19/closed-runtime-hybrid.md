---
title: "Closed runtime: the hybrid of the Opus and Astra plans"
type: research
status: hybrid of a design round, revised in place after the narrow check (lane closed-revise); P0 landed, P4a in flight, the other phases ready to file as tasks
created: 2026-10-19
---

Phase status: P0 landed (`seon.sci.open-derivers-test`, `seon.cost-estimate-test/closed-runtime-rows-grow-past-no-accepted-overrun`);
P2, P3b landed; P1 landed (lane closed-p1, the leaf design); P3a split: P3a-1 landed (lane closed-p3a1b: the order leaf and the
digest move; the fallback deletion moves to P4b, option c); P4a landed (`ec87c559c`); P5 landed (`7df8dbb21`,
corrections in its section); P4b landed (`66ca5108b`, lane closed-p4b; corrections in its section); P3a-2 landed (lane guards3, the smaller cut of the patch, after
`393e52821` deleted the catch in `seon.instrument/compiled-wrapper` that the guard census refused; it closes
`seon.cluster/branch-declaration-changes`, 49 -> 48 open derivers); P6a landed (`f1145501b`, lane closed-p6, corrections in its section);
P6b stopped at its design gap (lane closed-p6: census measured, options in its section) and is folded into P7 (root);
P7: the design, R4 (`c23e1f807`), the gap task (`e8aeac94d`) and the predicate fold with the exact key (`1bcb3bf65`, after
`57fa7fa7d` narrowed `projection-registry`'s catch) landed: open derivers 48 -> 17, the gate judged on the hub sequence
(1.27x of 716cc7527); R3, R5 and R6's real-path rows not started (lane closed-p7, its section); P8 not started.

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
Astra's `seon.program` sites included). The lexical exact key lands last, gated on the measured hub row. The narrow check
(below) showed the count is reached only at P6, whatever the order: P3b closes one deriver, P4b three, P6 thirty. Each
phase is therefore proven by its own direct-opener row (red before, green after) and by its edit of the exact ratchet
`seon.sci.open-derivers-test/open-derivers`, never by a census row that stays red until P6.

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

### Cycle 7 (`seon.db` → `seon.call-preparation`): **a pure leaf both require; no stored fact** (root, 2026-10-19)

Both plans stored something (Opus a per-arity count, Astra the prepared arities). P1's census (lane closed-p1) showed
the stored fact grows: every `:seon.fn/spec` writer is a producer (the shared row writer `seon.fn/reconcile-tx-in` that
`seon.merge`, `seon.cluster.source`, `seon.test` and `seon.fn/index!` use, plus the `canonical-row` and
`contract-facts-row` transactors in `seon.turn`, `seon.turn.batch`, `seon.sci.eval`, `my.program`, `seon.effect`,
`seon.test.runner`, and every raw-map fixture), a merge copying only program attributes drops a derived fact, and the
writer's change detector needs the new key. Three of the plan's named writers (`seon.cluster.instruction`,
`seon.sci.lookup`, `seon.effect`'s error member) write no spec. A fix that grows means the model is wrong, so the fact
was dropped for the smaller composition: the pure derivation the gate needs (the contract's arities joined to the
admitted supplied-default rows) moves into the leaf `seon.call-preparation.plan`, which requires only Malli,
`seon.schema`, `seon.schema.internal`, `seon.batch` and `seon.error.refusal`; `seon.db` and `seon.call-preparation`
both require it, and the writer derives each callee's plans on the value it judges. Nothing above `seon.db` was needed
except `seon.error/declared-schema-keys` (pure over the projection), which moves into the leaf. No derived copy exists
to drift, the gate and the armed wrapper share one derivation, and the variadic disagreement goes: the gate widens a
fixed arity by its slot count and keeps a variadic arity's minimum, as `seon.call-preparation/prepared-arities` does.

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

1. The five primitives move to `seon.schema.internal` (which "never requires `seon.schema`"); the two conveniences
   (`encode-transaction`, `decode-attribute-value`) move up to `seon.schema` (Astra's "upper owner"). The packaged-EDN
   reader does **not** move: `seon.schema.edn/packaged-forms` reaches `seon.schema/structural-schema` and five more
   `seon.schema` functions, so it cannot sit below it. Its seam (`seon.schema/schema-edn-packaged-forms`) exists for one
   caller, the warned classpath fallback `seon.schema/packaged-forms` inside `candidate-forms`, whose count on default
   is `{}`. The fallback is deleted: the population is handed by its holder, and the process's one holder before any
   projection exists is boot, which binds the root of `seon.schema/*packaged-forms*` once from
   `seon.schema.edn/packaged-forms` (`seon.cluster.boot`, which requires both). `!fallback-counts`,
   `warn-classpath-fallback!`, `fallback-caller`, `decade?`, `frame-namespace`, `resolution-owner-namespaces` and the
   seam go with it. P3a's first step counts `!fallback-counts` after the three cost rows and after the check's test
   namespaces; a caller it names is converted to hand its population in the same commit.
2. **Load order, found while checking plan A's P3:** `seon.schema.datahike` requires `seon.print`, and `seon.print`
   requires `seon.schema` directly, so "`seon.schema` requires the bridge statically" would cycle through the printer.
   The bridge uses one function of it, `seon.print/compare-values` (canonical storage order), which calls nothing of
   `seon`. It moves, one definition, to a new leaf **`seon.order/compare-values`** (`src/seon/order.cljc`, no `seon`
   requires; `seon.id` is identity, not order), and every caller converts in one scripted sweep in the same commit:
   `seon.print`, `seon.schema.datahike` (2), `seon.test` (2), `seon.db` (2), `seon.fn` (10), `seon.render.web` (2),
   `seon.turn`, `seon.reconcile`, `seon.program`, `seon.config`, `seon.cluster`; `seon.print/compare-values` is
   deleted, no alias. Then the seven bridge seams become calls, and `seon.schema/database-projection` and the
   `seon.program/definition-digest` resolution in `canonical-schema-entities` go as Opus says (caller passes the
   carried projection; the digest is computed by its `seon.program` caller); their test callers
   (`test/seon/test_support_test.clj` for `database-projection`, the `canonical-schema-entities` sites in `test`) are in
   the slice.
3. **`seon.program` → bridge (Astra's finding, extended).** `derived-shape` resolves
   `seon.schema.datahike/vector-shaped-attr-in?` and `malli->datahike-schema-in`; `derived-program-attributes` resolves
   `storable-properties-in`. Neither namespace reaches the other (verified), so a `#?(:clj [seon.schema.datahike])`
   require beside the existing `#?(:clj [seon.schema.edn])` and three static calls close them. Alone this slice closes
   one deriver, `seon.program/program-attributes`; `seon.config/effective` and `seon.sci.eval/overlay-plan` close only
   with P6 (narrow check). It is still why the final count is 34, not 32.

### Cycle 6 (`seon.error.refusal` → `seon.schema`): **Opus's mechanism, Astra's rule on the fallback**

Both say: the refusal reaches back for a projection the operation already holds; hand it the values. Opus's mechanism is
the concrete one and matches Malli's model (a schema operation gets its registry through its options or the one dynamic
default, never by name): the dynamic Vars `seon.schema/*projection*`, `*projection-state*`, `*packaged-forms*` move to a
portable leaf with no requires (`seon.schema.handed`, ~10 lines; a vocabulary entry for "handed" is the existing
`handed-projection`), which `seon.error.refusal` and `seon.schema` both require; the handed projection carries its
identity projector and explainer as values (`:seon.schema.projection/identity-projector`,
`:seon.schema.projection/explain`, put there by the code that builds it, so `with-predicate-functions`-style one
writer). `schema-seam`, `operation-projection`, `checking-projection`, `identity-projector` go.

`seon.error.refusal` is `^{:seon/babashka true}` and loaded under babashka by `script/seon/operator.clj` and
`central_round.clj`, where Caffeine does not exist, so `cache-seam` cannot become a static require of `seon.cache`.
It goes the same way as the projector: the handed state carries the lookup as a value,
`:seon.schema.handed/lookup` (`seon.cache/lookup`, put there by whoever binds the handed state on the JVM: boot's root
binding and every builder of a handed projection), and `authored-namespace?` calls it when present and
`classify-namespace` directly when not (babashka hands none; after P4a the classifier is a pure function of the
classpath, so the cache saves only repeated classpath probes). One definition, the namespace stays `.clj`.

The no-projection case is decided now, not after a count (narrow check):
`seon.error.refusal-test/error-is-buildable-where-no-projection-is-handed` builds `:seon.error/base` and an undeclared
`:no.such/error` on a bare `Thread.` and must tell them apart, which needs a declaration population. So **boot binds
the handed leaf's root once** (Opus's alternative): `seon.cluster.boot` sets the root of `seon.schema.handed/*projection*`
to the packaged declaration projection it already builds, and the root of `*packaged-forms*` to its forms (P3a's
holder, moved into the leaf here). A bare thread, the MCP thread and namespace load read the root; an operation binds its
own. No lookup and no fallback in the refusal. This makes P4b a platform slice (boot). The count of the no-projection
path on the three cost rows is still taken, as evidence in the commit message.

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
its projection state (`carry-connection-projection-state!` is the producer; the scan is a fallback and goes). The
connections are made in `seon.cluster.store` (four sites) and `seon.db` (one), and about 20 test sites call `d/connect`
directly and carry nothing (`seon.db-test`, `seon.turn-work-test`, `seon.flow-test`, `seon.test-support`,
`seon.store-transact-test`, `seon.retired-attributes-test`, `seon.acquisition-cost-test`,
`seon.sci.branch-execution-test`, `seon.web-test`, `seon.admission-test`, ...): P5's first step lists every `d/connect`
and `d/create-database` site in `src` and `test` by census, and the phase converts each to the canonical constructor
(the harness's `with-fresh-database` already carries; the rest call it). Opus is right that `::builder-digest` is a
hand-made exact key and goes with P7.

Until then `::builder` is read from the carried state, never found by name. The state map built by
`seon.sci.eval/projection-state` (its one constructor) gains two members its callers pass: **`:seon.source/commit-id`**,
the program the process booted (boot passes its commit; the harness passes the executing handle's), and
**`:seon.sci.eval/loaded-identity`**, the process's identity place `seon.sci.eval/loaded-identity` held by reference
(errors and states name a place by reference; development adoption already advances it with
`advance-loaded-identity!`, so no new writer). `loaded-builder` then takes `[database state]`: the adoption's commit when
the identity names one, else the state's boot commit, else the value's own builder; the "running instances booted
different programs" refusal disappears (each connection names its own boot), and the mixed-adoption branch reads its
prior from the state's own cluster connection instead of every instance's.

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
  a function of its name alone). `runtime-predicate` and `loaded-predicate-var` are deleted. `register-core-predicate!`
  has about 70 call sites in 25 `src` and 7 test files and its own docstring queues them for deletion; once the
  environment map supplies every predicate it is a no-op, so **it is deleted with every call site in one scripted sweep,
  its own commit (P6a) before the rest**, together with `core-predicate-registered?` and the test sites quoting private
  predicate Vars (`seon.schema-test`, the `seon.instrument-test` docstring). `predicate-callable` and
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
their reads as input. That is P8's first lane: `seon.program/{content-digest, own-changed-identities,
declaration-digest-map, differing-declarations, projected-values, fold-content-digest}` (the first two hold direct
`requiring-resolve` calls themselves), which closes `unmerged-count`, `own-changed-identities` and `content-digest`
(16 -> 13, narrow check).

Census fact found by P0 on HEAD (`a8926bb27` candidate): the exact open set is the 50 above plus two that hold
rather than call: `seon.sci.eval/reach-index` (its `:seon.fn/calls` hold the quoted opening set of
`run-time-resolution`, which the analysis records as held names) and the test helper
`seon.sci.runtime-test/shaped-main` (holds `seon.cache/lookup` as a value). So HEAD's ratchet starts at **52**; the 16
of the end state become 18 until P8 deletes `run-time-resolution` as an input (P8's last step closes `reach-index`).

## Phases

Each phase is one loadable slice; HEAD boots after each; every deletion is in the commit that converts its last caller,
tests and quoted symbols included. "Ordinary" commits with `bin/lane commit LANE --branch-evidence AGENT`; "platform"
with `--test` through a check JVM (schema resources, writer, boot, evaluator, load order). A red proof lands with its
phase, never before it (AGENTS: a test already red on main stops the merge), so each phase's spec names its red rows and
the phase writes them.

Two shared proofs, landed in P0, carry every phase:

- **The ratchet** `seon.sci.open-derivers-test/open-cache-derivers-are-exactly-the-recorded-set`: the open
  `seon.cache` derivers of the running program (names whose `:seon.fn/calls` hold `seon.cache/lookup` or
  `lookup-within`, less `::seon.sci.runtime/closed` of `seon.sci.eval/reach-index`) equal the private set
  `open-derivers` exactly. Green on HEAD at 52. A phase that closes derivers removes them from the set in its commit
  (the edit is the proof that the count fell); a commit that opens one is red. Platform test: it reads
  `seon.sci.eval` and `seon.sci.runtime`, which agents' SCI contexts withhold, so it runs in the check JVM.
- **The direct-opener table** `seon.sci.open-derivers-test/converted-functions-resolve-no-name-at-run-time` over the
  private vector `converted` of `{:phase :functions}` rows: no listed function's `:seon.fn/calls` holds a member of
  `seon.sci.eval/run-time-resolution`. Empty at P0. Each phase adds its row in its commit; the row is red on the
  parent (the functions resolve today) and green with the change, order-independent.

Cost rows: `seon.cost-estimate-test/closed-runtime-rows-grow-past-no-accepted-overrun` measures
`:seon-db-nine-forms`, `:hub-behavior-change` and `:similar-by-name-warm` in the table's own state (`measured`, then
`select-keys`: a shorter sequence measured the nine forms at 149,492 calls against 147,860 accepted, a different state,
not a regression) and judges them by the table's own `estimates` and `unaccepted` (open overrun task, `ratchet` 1.5x). Every phase names it with `--test` when it touches the writer, the projection path or the evaluator; P7 tightens
it into its gate.

### P0. Ratchet, opener table, cost rows (landed)

- Files: `test/seon/sci/open_derivers_test.clj` (new), `test/seon/cost_estimate_test.clj` (`closed-runtime-rows`, the
  deftest above; `unaccepted` judges only the rows measured, EQUIVALENT for the full table which measures all).
- Proof: both green in a check JVM on the candidate. Kind: test only, platform-run (check JVM, `--test`).
- Cost: census 3.4 s for both deftests in the check JVM, the reach index once per content of the calls facts (cached
  by `seon.sci.eval/reach-index`); the cost rows cost the table's one measurement, about 31 s in a check JVM, declared
  long 60 s.
- Deletes: nothing. `tmp/runtime-fix2/cost_test.clj` (gitignored) is superseded; it read `RR_LABEL` and wrote into `tmp`.

### P1. Cycle 7: the plan derivation as a leaf (platform: writer)

- Files: `src/seon/call_preparation/plan.clj` (new: `entity-query`, `argument-facts`, `arity-facts`,
  `contract-arities`, `incoherent`, `coherent-supplier`, `by-schema-key`, `world-supplied`, `world-candidates`,
  `world-member?`, `slot-of`, `arity-plan`, `supply-index`, `planned-arities`, moved from `seon.call-preparation`;
  `declared-schema-keys` moved from `seon.error`; `admitted-rows`, the coherence and validator step
  `seon.call-preparation/snapshot` derived inline), `src/seon/call_preparation.clj` (requires the leaf;
  `derive-snapshot` is `admitted-rows` plus its basis; `report-snapshot` deleted), `src/seon/db.clj` (requires the
  leaf; the three `defonce` seams deleted; `supplied-defaults` reads the rows on the judged value;
  `prepared-arity-bounds` takes the arity plans and keeps a variadic minimum; `arity-mismatch-report` takes the
  projection instead of a snapshot), `src/seon/error.clj` (calls the leaf's `declared-schema-keys`),
  `test/seon/db_test.clj`, `test/seon/call_preparation_test.clj` (the `report-snapshot` deftest deleted),
  `test/seon/sci/open_derivers_test.clj`.
- Red rows, in `seon.db-test/arity-components-and-callers-are-checked-in-the-final-state` (`testing` "prepared
  arities"): (1) variadic: callee `[:=> [:cat :seon.db/connection [:* :int]] :int]`, a call of 0 arguments is refused
  with declared and prepared `[{:min 1}]` (red on HEAD: the gate subtracted the slot, measured in a check JVM);
  (2) a row-backed supplied slot (`:sample/marker`) omitted while its row is admitted; (3) retracting the row refuses
  that caller; (4) writing the callee twice leaves the report equal; (5) the retraction with its caller repaired in
  one transaction is accepted; (6) a world-supplied slot is admitted with no written fact. Rows 2-6 are regressions,
  green on HEAD (its report snapshot derived at the report value whenever supplier rows changed). A seventh row,
  `seon.db/arity-mismatches` on a raw `d/with` value, was dropped: a value written around the writer stores
  unencoded call arities, so it proves nothing about the gate.
- Opener row: `{:phase :P1 :functions #{seon.db/call-preparation-snapshot seon.db/call-preparation-report-snapshot
  seon.db/call-preparation-plan-for}}`, built from names (deleted seams).
- Cost: a write with arity candidates pays one supplied-default query and one coherence pass over the rows (about
  1 ms on default's 4 rows with a warm projection, measured 2026-10-19), and one `planned-arities` per candidate callee
  (projection-cached contract arities); a write with no candidates pays nothing. It replaces the 174-188 ms speculative
  snapshot the gate built on a report that changed a read attribute.
- Deletes: the three `seon.db` seams, `seon.call-preparation/report-snapshot` and its deftest, the snapshot argument of
  the gate, `seon.error/declared-schema-keys` (moved).
- After P2 (both edit `seon.db`).

### P2. `seon.db` requires `seon.program` (ordinary)

- Files: `src/seon/db.clj` (require `seon.program`; the five `defonce` seams at the program seams go, their deref sites
  `definition-digest-of`, `namespace-digest-of`, `behavior-digest`, `namespace-digests-tx`, `write-digest-error`,
  `dropped-definition-namespaces` call `seon.program` directly; the docstring citing
  `seon.fn/stored-namespace-context` converts), `src/seon/program.cljc` (`stored-namespace-context` moves in),
  `src/seon/fn.clj` (definition removed).
- Red rows: in `converted`, `{:phase :P2 :functions #{seon.db/program-definition-digest seon.db/program-behavior-digest
  seon.db/stored-namespace-context seon.db/program-shapes-in seon.db/program-canonical-entity}}` (each `defonce` holds a
  `requiring-resolve` today; a seam reads as a function of its delay in `:seon.fn/calls`, so the row is red on the
  parent and green once the seam is deleted); and a new deftest
  `seon.program-test/seon-db-requires-seon-program`: the `(ns ...)` form of `src/seon/db.clj` read with edamame holds
  `seon.program` in its `:require` (red today).
- Ratchet: unchanged. Cost: none at run time (a static call replaces a delay deref).
- Deletes: five seams (12 lines); `seon.fn/stored-namespace-context` (no other `src` caller).

### P3a. Cycle 3 and the order leaf (platform: load order; publish and reload on a branch of the live store)

**Split (lane closed-p3a, root's option B).** The primitive move below converts every caller of
`seon.schema/projection-validator` (312 sites), `projection-cache-value` (64), `projection-identity` and
`call-with-forms` (10): 80 files the file list did not count, and `call-with-forms` binds `*packaged-forms*`, which
P4b moves into `seon.schema.handed`. The fallback count on default was not `{}` but one occurrence its caller
detection named as `seon.profile` (the instrumentation wrapper), and `database-projection`'s only caller is
`seon.schema/projection-from-database` (141 sites, three in `seon.error`, which `seon.db` requires), so it cannot be a
static call to `seon.db/carried-projection`. So:

- **P3a-1 (landed, lane closed-p3a1b):** `seon.order/compare-values` (leaf, no `seon` requires) with the scripted sweep,
  no alias, and the eight `seon.print` requires it left unused dropped; `seon.schema/canonical-schema-entities` returns
  rows without digests and `seon.program/schema-entities` adds them (every caller converted). Proofs:
  `seon.schema.datahike-test/the-bridge-requires-no-printer` (now `the-bridge-requires-neither-schema-nor-printer`) and the `:P3a-1` row of `converted`.
- **The fallback deletion moves to P4b (root's option c).** `seon.schema/packaged-forms`, `warn-classpath-fallback!`, the
  `schema-edn-packaged-forms` seam and boot's binding of `*packaged-forms*` stay as on HEAD, because
  `seon.config/defaults` is a compiled constant that loads the packaged population before boot ever binds a root:
  `seon.config-test/config-loads-the-packaged-population-in-a-fresh-jvm` proves it. The population then needs a home
  that exists before boot, which is `seon.schema.handed` (P4b), so the fallback, `*packaged-forms*` and the seam move
  there once.
- **P3a-2 (scheduled with P4b, so `*packaged-forms*` moves once, into `seon.schema.handed`):** the primitive sweep, the
  seven bridge seams to calls, the two conveniences up to `seon.schema`, and
  `seon.schema.datahike-test/the-bridge-requires-neither-schema-nor-printer`. `database-projection` and
  `projection-from-database` go with P4b's handed projection (the callers in `seon.error` take the projection they are
  handed).

- **P3a-2 as written (lane closed-p4b, patch `docs/research/agent-platform/2026-10-20/closed-p3a2-bridge.patch`, applies on
  `66ca5108b`): the smaller cut, not the 312-site move.** Counted on HEAD `66ca5108b`: the bridge's
  `seon.schema` uses were `projection-validator` 2, `projection-cache-value` 4, `projection-identity` 1, `call-with-forms` 2,
  `declaration-projection` 2 (the two conveniences). Moving all five primitives sweeps 312 + 64 + 10 sites in ~75 files;
  instead only what the bridge needs moved: `call-with-forms` to `seon.schema.handed` (10 sites, P4b),
  `projection-cache-value`, `projection-cache`, `projection-identity` and the fingerprint functions to
  `seon.schema.internal` (64 + 1 sites in 20 files, the identity projection symbol in `seon.schema.edn` and four task
  plans); the bridge's two validators are Malli's own (`m/validator` of `mr/schema` on the projection's registry, Malli
  caches it per schema), so `projection-validator` stays in `seon.schema` with its 312 callers. The conveniences
  `encode-transaction` and `decode-attribute-value` had no `src` caller; they are deleted and their five test sites hand
  `(schema/declaration-projection)`. `seon.schema` requires the bridge; the seven seams are static calls.
  `database-projection`/`projection-from-database` (138 sites, 60 files, three in `seon.error`, which `seon.db` requires)
  is not converted: it is its own sweep (callers that can require `seon.db` call `seon.db/carried-projection`;
  `seon.error`'s three take the handed projection).
  **Landed** (lane guards3) once `393e52821` deleted the wrapper's catch (a fence: a refusal that cannot be built is a
  defect for the entrant boundary), with the two backticked card references in `docs/seon/schedule/cards/PB-07a.md`
  and `PB-07b.md` converted. Probed in a fresh JVM from the patched tree: `seon.schema.datahike` loads without `seon.schema`,
  config defaults compile, `projection-identity` and `encode-transaction-in` answer.
- First step: count `seon.schema/!fallback-counts` after the cost rows and after the check's test namespaces; every
  caller named is converted to hand its population in this commit.
- Files: `src/seon/schema.clj` (fallback and its helpers deleted; the seven bridge seams become calls; requires
  `seon.schema.datahike`; `database-projection` deleted; `canonical-schema-entities` takes the digest from its caller),
  `src/seon/schema/datahike.clj` (primitives out, conveniences out, drops `seon.print` and `seon.schema`),
  `src/seon/schema/internal.clj` (primitives in), `src/seon/order.cljc` (new: `compare-values`),
  `src/seon/cluster/boot.clj` (root of `seon.schema/*packaged-forms*` bound once at boot), and the scripted
  `compare-values` sweep: `seon.print`, `seon.test`, `seon.db`, `seon.fn`, `seon.render.web`, `seon.turn`,
  `seon.reconcile`, `seon.program`, `seon.config`, `seon.cluster`; tests `test/seon/test_support_test.clj` and the
  `canonical-schema-entities` test sites.
- Red rows: new deftest `seon.schema.datahike-test/the-bridge-requires-neither-schema-nor-printer`: the transitive
  `:require` closure of `seon.schema.datahike` over the `(ns ...)` forms of `src` (edamame) holds neither
  `seon.schema` nor `seon.print` (red today). `encode-transaction-resolves-the-declaration-population-once` stays
  green. In `converted`, `{:phase :P3a :functions #{seon.schema/schema-edn-packaged-forms
  seon.schema/schema-datahike-storable-attribute-in? seon.schema/schema-datahike-storable-properties-in
  seon.schema/schema-datahike-assert-storable-schema! seon.schema/schema-datahike-database-attributes-in
  seon.schema/schema-datahike-malli->datahike-attr-in seon.schema/schema-datahike-storage-schema
  seon.schema/schema-datahike-value-schema seon.schema/canonical-schema-entities}}` (red: each holds a
  `requiring-resolve`).
- Ratchet: unchanged (closes none alone). Cost: 0 at run time; an explicit-projection transform < 20 ms, < 4 MB.
- Deletes: eight seams (25 lines), `database-projection` (3), the fallback and its diagnostics (~70),
  `seon.print/compare-values` (moved, no alias).
- After wave 1 (the sweep claims `seon.db`, `seon.fn`, `seon.program` in one window, after P1 and P2 release them).

### P3b. `seon.program` requires the bridge (ordinary)

- Files: `src/seon/program.cljc` only: `#?(:clj [seon.schema.datahike])`; `derived-shape`'s two and
  `derived-program-attributes`' one `requiring-resolve` become calls.
- Red rows: `{:phase :P3b :functions #{seon.program/derived-shape seon.program/derived-program-attributes}}`.
- Ratchet: `seon.program/program-attributes` leaves `open-derivers` (52 -> 51).
- Cost: 0. Deletes: three `requiring-resolve` forms. After P2 (both edit `seon.program`).

### P4a. Frames (ordinary; in flight)

Unchanged; the narrow check passed it. `seon.error.refusal` stays babashka-loadable. Ratchet unchanged.

### P4b. Cycle 6 (platform: boot binds the handed root)

**Landed (`66ca5108b`), with corrections.** (1) The root is bound by `seon.schema.edn` at load, not boot:
`seon.config/defaults` compiles at namespace load. (2) `*projection*` is not root-bound: 29 `src` readers of
`seon.schema/handed-projection` treat an unbound projection as absent, and a root would answer them with the packaged
one. The root lives in its own Var, `seon.schema.handed/packaged` (forms, a delay of their declaration projection, the
`seon.cache` lookup); `candidate-forms` falls back to it, `declaration-population` still refuses with nothing handed.
(3) The lookup is not on the projection: carried there it made `seon.schema/with-compiled-cache` an open cache deriver.
(4) The ratchet fell by two, `seon.cluster/derivation-keys` and `seon.cluster.source/merge-base`;
`seon.cluster/branch-declaration-changes` stays open. (5) `config-loads-the-packaged-population-in-a-fresh-jvm` was red
because `seon.error.refusal` named `sci.lang.Var` (P4a) before SCI loaded; the refusal now requires `sci.core`.
`seon.schema/handed-projection` (102 files) reads `seon.schema.handed/projection`; it was not swept.

- Files: `src/seon/schema/handed.cljc` (new leaf, no requires: `*projection*`, `*projection-state*`,
  `*packaged-forms*` move here), `src/seon/schema.clj` (binds the leaf; the handed projection carries
  `:seon.schema.projection/identity-projector`, `:seon.schema.projection/explain`), `src/seon/error/refusal.clj`
  (reads handed values and `:seon.schema.handed/lookup`; the five seams go), `src/seon/error.clj`,
  `src/seon/cluster/boot.clj` (roots bound once), every `binding` of the three moved Vars (scripted rename).
- Red rows: `seon.error.refusal-test/error-is-buildable-where-no-projection-is-handed` gains: on a bare `Thread.`
  after boot's root binding, `(seon.error.refusal/error :no.such/error ...)` answers layer
  `:seon.error.refusal/construction` and the base error is valid, with no `seon.schema` Var read (assert the error's
  worlds name the root projection's identity). `{:phase :P4b :functions #{seon.error.refusal/diagnostic
  seon.error.refusal/references seon.error.refusal/authored-namespace?}}`.
- Ratchet: `seon.cluster/branch-declaration-changes`, `seon.cluster/derivation-keys`,
  `seon.cluster.source/merge-base` leave (the narrow check's cumulative row).
- Cost: per error after acquisition < 5 ms, < 0.5 MB over <= 16 frames; one map get instead of a delay deref.
- Deletes: `schema-seam` (16), `operation-projection` (11), `checking-projection` (15), `identity-projector` (4),
  `cache-seam` (5). After P3a and P4a.

### P5. Builder (platform: writer, boot)

**Landed (`7df8dbb21`), with three corrections to the text below.** (1) No `seon.loaded` leaf: the loaded-identity place
is carried by reference in the projection state; the leaf (option 1) is deferred because its sweep reaches two functions
that already hold guards, `seon.test/member-result` and `seon.test.runner/current-results`, which must lose them first.
(2) `seon.schema/load-projection` from an empty base refused ("Bad entity attribute :seon.fn/spec") on a store with
schema rows and no function attributes; `seon.schema/projection-entities` now declares nothing for a range whose
attributes the store never installed (regression `seon.schema-test/a-store-without-function-attributes-loads-its-schema-rows`).
(3) The ~20 test `d/connect` sites were not converted: the builder reads the executing state, so a bare connection reads
the value's own builder; the open-derivers set is unchanged (P5 closes none) and 4 run-time resolutions went.

- First step: census of `d/connect` and `d/create-database` sites in `src` and `test`.
- Files: `src/seon/sci/eval.clj` (`projection-state` takes and stores `:seon.source/commit-id` and
  `:seon.sci.eval/loaded-identity`), `src/seon/cluster/boot.clj`, `src/seon/cluster/store.clj` (its four connection
  sites carry state), `src/seon/db.clj` (`loaded-builder [database state]`; `content-projection` branches on `kind`
  to two private derivers; `projection-builder`, the scan in `connection-projection-state` deleted), the ~20 test
  sites converted to the canonical constructor, `test/seon/db_test.clj`, `test/seon/cluster/program_change_test.clj`
  (quote `projection-builder`, `loaded-builder`).
- Red rows: new deftest `seon.db-test/every-connection-carries-its-projection-state`: every connection the census
  lists, opened through its constructor, has `:seon.sci.eval/projection-state` in its meta (red for the bare
  `d/connect` sites). New deftest `seon.db-test/projection-cache-uses-the-executing-builder`: two connections over one
  in-memory store carrying states A and B with different `:seon.source/commit-id`s; `seon.db/carried-projection` of
  each yields two cache keys and two derivations; the same value read twice yields one derivation (count by
  `seon.cache` misses of `seon.db/projection`). `{:phase :P5 :functions #{seon.db/carried-projection seon.db/content-projection seon.db/connection-projection-state}}`
  (the callers of `projection-builder` and the scan; the lane adds any the deref sites show).
- Ratchet: unchanged. Cost: warm key selection < 1 ms, < 0.05 MB; saves a `find-ns` pair and the scan per read.
- Deletes: `projection-builder` (15), `loaded-builder`'s instance scan (~30 of 65), the scan in
  `connection-projection-state` (~12 of 16). After P1 (both `seon.db`).

### P6a. Delete `register-core-predicate!` (ordinary sweep, its own commit)

**Landed (lane closed-p6), with corrections.** (1) There was no registry: `register-core-predicate!` cached nothing
since the 2026-08-07 isolation audit; its only effect was a load-time throw when the symbol did not name the callable
handed to it (every caller handed the Var's own value, so it never threw). The precondition held by construction and
was measured in a check JVM on the candidate: the stored population names 108 predicates (4,204 forms, 4,551
contracts), `predicate-bindings` binds all 108 to callables with every registration deleted, and the carried
projection's bindings equal them. (2) 59 forms in 23 `src` and 4 test files (the defonce holders in `my.fs`, `my.edit`,
`seon.blob` with them), plus the definition; 9 namespaces lost their only use of `seon.schema` and drop the require.
(3) `core-predicate-registered?` is **not** deleted here: it is `seon.schema.edn`'s admission check
(`::unregistered-predicate`), a resolution, not a registration; it goes with `runtime-predicate` in P6b. (4) The
`seon.schema-test` arm asserting that a second registration refuses is deleted with the function it exercised.

- Files: the ~70 call sites in 25 `src` files and 7 test files (one script: remove each top-level
  `(schema/register-core-predicate! ...)` form, rewrite-clj), `src/seon/schema.clj` (`register-core-predicate!`,
  `core-predicate-registered?` deleted).
- Precondition: P6b's environment map is not yet in, so the sweep needs the registrations to be unused: the lane
  first proves on its branch that the projection's predicate bindings for every program predicate come from
  `predicate-bindings` with the registry empty. If they do not, P6a lands after P6b instead.
- Red rows: none new; the reaching tests of every touched namespace run.

### P6b. Predicates as input (platform: projection derivation, publication)

**Stopped before implementation (lane closed-p6), on HEAD `f1145501b`.** Measured in a check JVM over the candidate's
own `:seon.fn/calls` (the fold of `seon.sci.eval/reach-index`, edited in memory): the four projection derivers reach
exactly eight direct openers, all in `seon.schema`: `runtime-predicate` (`requiring-resolve`, `var?`; called by
`predicate-bindings`, `declaration-projection` directly, `projection-registry`, `projection-with-declarations`,
`assert-complete-contract!`, `unloaded-predicate-functions`, `core-predicate-registered?`), `loaded-predicate-var`
(`find-ns`, `ns-resolve`; `compilable-form`, `unloaded-predicate-functions`), `unloaded-predicate-functions`
(`find-ns`), `identity-only-descriptors-in` (`requiring-resolve`, `var-get`, `var?`), `predicate-callable`
(`requiring-resolve`), `projection-bindings` and `projection-state-bindings` (`[:fn clojure.core/var?]` in their
contracts; `seon.config/effective` reaches them through `seon.db/q` -> `ask-declarations` -> `call-with-projection`),
and `core-predicate-registered?`. With only those eight stripped of their openers the open set falls **48 -> 17**
(31 close, `seon.sci.eval/reach-index` among them; the 17 are the hybrid's 16 plus
`seon.sci.runtime-test/shaped-main`). So the census claim holds and the edit set is exactly those eight.

The gap is where the callables come from. Closing them means no function a projection deriver reaches asks the JVM
for a Var, yet on main the JVM namespace table *is* the runtime, so the environment must be produced outside every
deriver's reach and reach it as a value. The plan says "the caller that holds the runtime supplies the map"; counted,
that caller is everyone: `build-projection` has 17 `src` and 129 test call sites, `declaration-projection` 17 and 53,
`load-projection` 7 and 17, `advance-projection` 5; `seon.db/content-projection` derives from stored entities on the
hub path, whose population may name a predicate loaded by adoption after boot; 25 test-local predicate symbols in 10
test files (`seon.call-preparation-test`, `seon.cluster.boot-test` and its drill child, `seon.effect-test`,
`seon.schema-test`, `seon.schema.edn-test`, `seon.sci.acquisition-cost-test`, `seon.sci.admit-test`,
`seon.sci.branch-execution-test`, `seon.dev.fresh-operator-export-test`) are resolved today by the derivation itself. The stored population names 108
predicates and 5 identity projections. Options, simplest first:

1. **A handed predicate environment** (recommended if P6b goes before P7): `seon.schema.handed` gains the environment
   (symbol -> callable, with each program predicate's `:seon.program/definition-digest`), produced only by entrants:
   `seon.schema.edn`'s load beside `packaged` (the packaged population's 108), the eval request's projection state
   (`seon.sci.eval/projection-state`: main's Vars plus the context's held-apart SCI Vars, as
   `seon.sci.kernel/context-bound-projection` computes), adoption (`seon.cluster/load-development-definitions!`
   rebinds it), and the test harness (the test namespace's predicates). The eight functions read it; an absent symbol
   is the refusing callable; `projection-fingerprint-from-data` and the `::projection` key gain `{symbol digest}`;
   `seon.schema.edn/predicate-registered?` (admission, where a name is written) keeps resolving and is outside every
   deriver's reach. Cost: about 10 `src` files (`seon.schema`, `seon.schema.handed`, `seon.schema.edn`,
   `seon.sci.eval`, `seon.sci.kernel`, `seon.cluster`, `seon.cluster.boot`, `seon.test`, `seon.test-support`), the 10
   test files, a boot/evaluator/harness platform slice; risk: any derivation on a thread that conveys no binding sees
   refusing callables (the class of `seon.schema/handed-projection`'s 29 absent-tolerant readers).
2. **Fold P6b into P7 / runtime-wire**: the runtime is already to be an immutable name -> digest map (AGENTS, "A
   branch's change is live in its whole runtime"); hand that runtime (map plus its resolver) to the deriver and key by
   the digests it reads. One mechanism instead of a predicate-only one; waits for that lane.
3. **Close only the census-visible part now**: `projection-bindings`/`projection-state-bindings` declare a named
   `:seon.schema/handed-var` (class test, as P4a did for `referenced`) and `identity-only-descriptors-in` /
   `predicate-callable` read the projection's bindings (`population-predicate-symbols` also collecting
   `:seon.schema/identity-projection` symbols). Four `converted` rows go green; no deriver closes, because
   `runtime-predicate` and `loaded-predicate-var` stay.

- Files: `src/seon/schema.clj` (`predicate-bindings` takes the environment map; `runtime-predicate`,
  `loaded-predicate-var` deleted; `unloaded-predicate-functions` becomes a property of the map;
  `predicate-callable`, `identity-only-descriptors-in` read handed values; `projection-fingerprint-from-data` gains
  `{symbol definition-digest}`), `src/seon/sci/kernel.clj` (supplies the map), `test/seon/schema_test.clj`.
- Red rows: `seon.schema-test/one-predicate-symbol-cannot-name-two-environments-callables` and
  `a-rebound-predicate-recompiles-the-keys-naming-it` gain the opposite-cache-order and mixed-branch rows through real
  branch execution (red: the key holds no predicate digest); new `seon.sci.runtime-test/projection-derivers-are-closed`:
  `seon.schema/build-projection`, `seon.schema/declaration-projection`, `seon.db/content-projection` and
  `seon.config/effective` are in `::seon.sci.runtime/closed` of `seon.sci.eval/reach-index` (red until this phase).
  `{:phase :P6 :functions #{seon.schema/predicate-callable seon.schema/identity-only-descriptors-in
  seon.schema/projection-bindings seon.schema/projection-state-bindings}}`.
- Ratchet: the four projection derivers and 26 more leave, to **18** (the 16 plus `seon.sci.eval/reach-index` and
  `seon.sci.runtime-test/shaped-main`); the lane writes the set the check observes.
- Cost: 78 digests read per projection derivation; warm validator < 1 ms; a one-predicate change recompiles only its
  keys, < 20 ms, < 4 MB; unchanged content compiles zero predicates.
- Deletes: `runtime-predicate` (51), `loaded-predicate-var` (20), most of `unloaded-predicate-functions` (44),
  `predicate-callable`'s resolution (12). After P4b and P5.

### P7. The exact key, with P6b folded in (platform: evaluator, projection derivation)

**Design (lane closed-p7, HEAD `a18793684`; root's decision: P6b's option 2, the runtime is the one source of
predicates; option 1, a handed predicate map, is rejected as the R2 class).** Census taken first (babashka over the
`(ns ...)` and top-level forms of `src` read with edamame; method in the commit): **148** call sites of the four cache
entry points (`seon.cache/lookup` 51, `lookup-within` 5, `present` 25, `evict!` 8, with aliases), **76** deriving
functions, **90** kinds. **10 kinds are read or evicted by a function other than the one that derives them**:
`:seon.test.runner/result` (`seon.test/carried-results`), `seon.db/basis` (`seon.db/folded-projection`),
`seon.render/invocation` (`seon.render/invocation`), `seon.db/projection-alias` (`seon.db/carried-projection`,
`seon.db/resolved`), `seon.sci.runtime/analysed` (`seon.sci.runtime/bind`), `seon.embed/embedding`
(`seon.embed/layered`), `seon.test.runner/current-result-head` (`seon.test.runner/current-results`),
`seon.test.runner/reach-index-by-revision` (`seon.test.runner/reach-graph`), and the kind-valued sites of
`seon.db/content-projection` and `seon.db/nearest-base`. Other counts: `seon.cache/*reached*` 2 `src` sites,
`seon.sci.runtime/reaching` 2, the R4 report 20 `src` + 3 test lines, `::builder-digest` 8 `src` + 5 test lines.

#### The key: one code identity per runtime, bound in the runtime's own context

A derivation is a function of its input and of the code computing it (R1). A changed runtime binds, **in its own SCI
context** (`seon.sci.eval/bind-analysed!`, the one place a runtime's bindings are written), the program space's Vars
of the four entry points `seon.cache/lookup`, `lookup-within`, `present` and `evict!` to the same functions applied to
`[content-key changes]`, where `changes` is the runtime's whole delta (name -> digest, `::seon.sci.eval/changes`,
already held in its program snapshot). Compiled code calls the JVM entry points with the bare content key: main's
identity. So:

- **Lexical, not dynamic (R2).** Analysed code and the runtime's own definitions hold the space Vars, so every
  derivation their bodies make keys by the runtime they run in: a returned closure, a lazy sequence realized later,
  a raw `Thread`, because an SCI function invoked on a thread that has no context binds its own (Phase 0, the fork's
  `sci.impl.fns/fun`). Nothing is bound around a call, so nothing escapes it.
- **Survives re-arming (the arm gap).** No wrapper exists: `seon.sci.arm/arm!`, `rearm-dependents!` and
  `seon.sci.eval/fork-cluster-ctx` re-root definitions freely, and a fork copies the context's `:sci/var-bindings`, so
  it keeps the binding. The arm-gap patch is not needed.
- **One identity per runtime, every site agrees.** A per-definition key (each deriver keyed by the changes in its own
  closed reach) is rejected by the census: 10 of 90 kinds are read or evicted outside their deriver, so on one runtime
  a reader and its deriver would key one kind two ways. The delta is the runtime's code identity; within a runtime
  every site uses it.
- **Sharing comes from staying compiled.** A closed function whose closed reach holds no change stays compiled
  (`seon.sci.runtime/compiled-names`) and keys by main's identity, which is exact for it; only functions that run
  analysed key by the delta. The cost a per-definition key would save is a continuation's re-derivation of the
  analysed derivers (a second, unrelated change), measured by the gate.
- **Open derivers are exact too:** they run analysed on every changed runtime and key by its delta (R1 fixed for all
  50, not only the closed). Their cost is why the fold below is a prerequisite of the gate: with the four projection
  derivers open, the delta key re-derives every projection on a branch's first change (the "exact" row, 5.80x).
- **Main's own code identity** across a development adoption stays `::seon.db/builder-digest` (adoption reloads the
  JVM's code under main's key, which no runtime delta names). It is not deleted in P7; it goes when main's key carries
  the loaded program identity (`seon.sci.eval/loaded-identity`), a P8 question.

Deletes: `seon.cache/*reached*`, `seon.cache/reached-key`, `seon.sci.runtime/reaching`, the wrapper bookkeeping of
`bind-analysed!` (`::own-roots`, `::raw`, `::wrapper`, the `reaching` merge) and `reached-changes`' use as a key.

#### Predicates named in schema data: the runtime's Vars (P6b folded)

The eight lookups the projection derivers reach (P6b's census: `runtime-predicate`, `loaded-predicate-var`,
`unloaded-predicate-functions`, `identity-only-descriptors-in`, `predicate-callable`, `projection-bindings`,
`projection-state-bindings`, `core-predicate-registered?`) ask the JVM for a Var by name. Two mechanisms close them;
both keep Malli given callables, never symbols.

1. **Routed by the running runtime (root's option 2 as stated).** A predicate symbol is bound to the program space's
   one SCI Var for that name (`seon.sci.runtime/space-var`): a call reads the executing runtime's binding (an analysed
   or own definition) and otherwise the Var's root, the loaded definition. The projection is then one value for every
   runtime (nothing runtime-specific is captured, so its key needs no predicate digest), and
   `seon.sci.kernel/context-bound-projection`, `held-apart-var`, `seon.schema/with-bound-predicates` and its
   `::bound-predicates` recompiles are deleted. Facts it needs that do not hold today: (a) the space Var of every
   program predicate must exist and follow its JVM root before any projection is derived, but `seon.config/defaults`
   compiles at namespace load, before any SCI context, and `seon.sci.eval/copy-host-var` (the space's one writer)
   runs only at base acquisition, so a development reload leaves the root stale until the next acquisition; (b) it
   reverses the installed rule "compiled callers and the checked writer keep the database's projection"
   (`seon.sci.kernel/context-bound-projection`): a compiled writer called inside a
   branch evaluation would validate with the branch's predicate. Both are owner-level.
2. **The projection is main's; the runtime binds its own (the installed semantics, closed by construction).** The
   derivers resolve a predicate to the loaded JVM Var through Java interop on the namespace object
   (`clojure.lang.Namespace/find`, `.findInternedVar`), which analysed and compiled code answer identically (SCI's
   classes are `{:allow :all}`), so the eight are closed truthfully: an analysed deriver derives what the compiled one
   does. The runtime-specific part stays where it is installed, `context-bound-projection` over the context's own
   bindings, keyed by the bound Vars. No namespace is loaded by a derivation (`loaded-predicate-var`'s rule becomes
   the only one; `requiring-resolve`'s loading moves to admission, `seon.schema.edn/predicate-registered?`, where a
   name is written). Small (eight functions in `seon.schema`), but it is not "resolves through the running runtime"
   for compiled callers.

**Recommendation: 2 now, 1 as the target once the owner amends the compiled-callers rule and the loader writes the
space.** Not implemented by this lane (see the lane's report): it is the gate's prerequisite and the choice is the
owner's.

#### R3: macros reaching changed names run analysed

`seon.sci.eval/runtime-rows!` gives macros rows (`::seon.sci.runtime/kind :macro`); a macro is due exactly when its
closed reach holds a change, and is then bound to its analysed definition in the runtime's context, so its callers'
analyses on that runtime expand with it. The analysis key (`seon.sci.runtime/analysis-key`) already carries
`[macro digest]` for each expanded macro; it gains the macro's reached changes (`reached-changes` of the macro), and
`rekeyed?` re-analyses a caller whose macro is in `reached`. Proof: R3's two probes (`seon.profile/bucket-of` reading a
changed `seon.profile/buckets`) as rows of `seon.sci.eval-test`.

#### R4: the host-bound report is deleted

`seon.sci.eval/host-bound-reaching`, `::seon.sci.eval/host-bound-reached` (in `bind-analysed!`'s analysis),
`seon.sci.runtime/host-bound-reached`, `::seon.sci.runtime/host-bound-reach` (and `reach-index`'s third argument),
`:seon.batch/host-bound-reached` (three sites in `resources/seon/schemas/seon.batch.edn`), its readers in
`seon.sci.arm` (3 lines) and `seon.turn.batch` (2), the rows in `seon.sci.eval-test` (2 sites) and
`seon.sci.runtime-test` (1), and the open-derivers test's reach-index call. F2 stays the stated exception.

#### R5: a `defonce` is an observation, a `def` a derivation

A value row whose source is a `defonce` is never evaluated again on any runtime (it keeps the process's value; the
57 `(defonce x (delay (requiring-resolve ...)))` seams go with their removal lanes). A `def` value is due when its
closed reach holds a change, or, open, when the runtime's delta differs from the delta it was last evaluated under
(today: only when not yet bound, which is R7). Proof: R5's launch-values probe and R7's shrunk generator case as rows.

#### R6: the differential test drives the real path

`seon.sci.runtime-test/a-composed-runtime-answers-what-its-own-source-answers` gains rows through
`seon.sci.eval/fork-cluster-ctx`, the overlay (`derive-overlay-ctx`) and `seon.sci.arm/arm!`, a namespace whose
resolution changes, an open value, and the M2 two-step chain (parent changes `h0` to 1, child to 2). The mutations
M1, M2, M5, M7 of runtime-rereview and the removal of the context binding of the entry points are each red.

#### Status and measurements (lane closed-p7, 2026-10-20, HEAD `c23e1f807`)

- **R4 landed** (`c23e1f807`): the report and every reader deleted, 24 insertions / 95 deletions; check green (runtime
  test, ratchet, the two macro rows, the gate) in 107 s.
- **The key, proven, not committed** (patch `docs/research/agent-platform/2026-10-20/closed-p7-key.patch`, applies on
  `c23e1f807`): `seon.sci.runtime/keyed-entries` bound by `bind-analysed!` on a changed runtime;
  `seon.cache/*reached*`, `reached-key`, `seon.sci.runtime/reaching`, `reached-changes`, the own-roots wrapper
  bookkeeping and the functions' rebind clause ("bound again to key what it newly reaches") deleted; the differential
  test's `seon.cache/lookup` is a Var as the program space copies it (a plain value in `sci/init` cannot be bound per
  context). New rows, red on HEAD (check `1b7c933d6`, HEAD plus the test file):
  `seon.sci.eval-test/a-branch-derivation-is-keyed-by-its-runtime-in-either-order` (R1, main's dial 0: branch first
  answered `[777 777]` for branch then compiled, compiled first `[0 0]`) and
  `seon.sci.eval-test/an-agent-derivation-is-keyed-by-its-runtime-wherever-it-runs` (R2: closure 20, lazy 30, thread
  40 held under main's key). With the patch (check `aac98f528`, 13 tests, 44 assertions): both green, the
  differential test, the ratchet (48, unchanged), the macro rows, the value-holding row and
  `no-src-function-a-change-can-reach-runs-its-compiled-var` green; **the gate red: `:hub-behavior-change` 1,595.5 MB
  / 362,880 calls** (1.78x of 716cc7527's 897.7 MB; limit 1,167; accepted overrun 1,358.7 / 287,628). An earlier run
  measured 1,608.7 MB / 364,453. So the key costs what the open projection derivers re-derive under a branch's delta,
  as the design predicted (the "exact" row was 5.80x with every open function also bound again on each change).
- **Predicate mechanism 2, not working yet** (patch `closed-p7-predicates-2.patch`, `seon.schema` only): the eight
  lookups read the loaded JVM Var by `clojure.lang.Namespace/find` and `.getMapping`, no loading, contracts naming a
  new predicate `seon.schema/jvm-var?`. The candidate's publication refuses ("Predicate seon.schema/jvm-var? has no
  admitted callable in the corpus projection", from `seon.cluster/rehearse-source!` -> `seon.schema/compilable-form`):
  a predicate a contract names must be admitted in the corpus before the contracts naming it compile, so the class
  test needs a registered schema (as `:seon.sci.eval/sci-var` is, `[:fn seon.sci.eval/sci-var?]`) or an existing
  predicate. Not measured past that; the census and the gate with the fold are the next lane's first numbers.

- **Root's ruling (2026-10-20): mechanism 2 now, mechanism 1 the recorded target.** Count first (a probe in a
  check JVM at HEAD `b75caf440`, over the carried projection's population and the `:seon.fn` facts): the population
  names **108 predicates: 83 `src` functions SCI can run, 25 library, 0 host-bound**; its schema forms name **78, of
  which 69 are `src` SCI-runnable**. Those 69 are what a branch can change. Mechanism 2's gap: a branch that changes
  one of them validates with main's definition in the projection its database carries until merge (only an agent
  context rebinds a predicate it holds apart). Filed as `seon.schema/a-branch-changed-predicate-is-seen-only-after-merge`
  with its red proof `seon.sci.eval-test/a-branch-changed-predicate-decides-its-branch-projection` (`e8aeac94d`; red
  on HEAD and under mechanism 2), held for mechanism 1.
- **Mechanism 2 as built** (patch `closed-p7-predicates-2.patch`, `seon.schema` only). A derivation never loads:
  loading moved out of the derivers, and the first attempt (bind the loaded Var at derivation) failed boot, because
  `seon.cluster/resolve-bootstrap` derives the packaged projection before `seon.render.var` is loaded
  (`seon.render.var/var-value?` refused). So each population predicate binds to **a callable resolved by name at each
  call** (`seon.schema/late-predicate`, one per name in `seon.cache`, the Var found by `clojure.lang.Namespace/find`
  and `.getMapping`, uninstrumented); `runtime-predicate` keeps its loading only for admission
  (`core-predicate-registered?`); the contracts read `:seon.test/var`, the registered Var schema, instead of
  `[:fn clojure.core/var?]`. **Census: 48 -> 17** (31 close: the four projection derivers, `seon.config/effective`,
  `seon.sci.eval/overlay-plan`, `reach-index`, the runner's reach graph and the rest, as P6b predicted). Reds it adds:
  `seon.sci.runtime-test/a-hundred-branches-analyse-each-distinct-digest-once` (5,783 analysed against 5,743 distinct
  digests; green on HEAD). Two other reds in the run are red on HEAD and carried by tasks
  (`every-predicate-schema-declares-what-it-accepts`, `canonical-reference-values-use-the-pull-collection-grammar`).
- **The gate, same check sequence** (MB / calls; accepted overrun 1,358.7 / 287,628 hub, 147,860 calls nine forms):

  | Candidate | `:hub-behavior-change` | `:seon-db-nine-forms` calls |
  |---|---|---|
  | HEAD (probe run) | 1,106.0 / 240,319 | 157,351 |
  | key alone | 1,595.5 / 362,880 | within its bound |
  | fold alone | 4,152.5 / 652,336 | 428,105 |
  | fold + key | 1,799.5 / 310,126 (2.0x of 897.7) | 440,200 |

  Fold alone is worst because HEAD's `*reached*` is dynamic: a now-closed `seon.config/effective` reaching the hub
  change binds its reach around every nested projection lookup, so they all re-derive (the R2 mechanism). With the key
  that cascade is gone, and what remains is below.
- **Profile of fold + key against HEAD** (one probe, `seon.cache` misses and derivation ms per kind over each request,
  after the same two warm passes): **hub**: `:seon.sci.runtime/analysed` **885 misses, 228 ms** on the candidate and
  none on HEAD; every other kind within a few ms of HEAD (`current-results` 80 / 83 ms, `program-context` 51 / 46,
  `reach-index` 45 / 45, `builder-digest` 43 / 41). No projection kind re-derives. So the hub's extra ~700 MB is
  analysing the closed reverse closure of `seon.db/as-of-key` for the first time: on HEAD those names were open, and
  every open function is analysed (and cached by content) on the warm passes' first change, so the hub row hit them;
  with the fold they are closed, stay compiled on the warm passes, and are first analysed on the hub row. Whether the
  sequence as a whole costs more was not measured. **Nine forms**: the same cache kinds and misses as HEAD (no
  analysed misses), yet 466,385 calls against 157,351: the extra is execution, not derivation, and comes with the
  fold (428k without the key); not isolated (candidates: the late callables' `apply` per validation, or
  `unloaded-predicate-functions` now binding refusing callables where `runtime-predicate` used to load the owner).

- **Landed (`1bcb3bf65`), root's rulings of 2026-10-20 applied.** (1) The gate judges `:hub-change-sequence` in
  `seon.cost-estimate-test` (every request from the first program-change pass through the hub row), bounded at 1.3x
  of 716cc7527 on the same sequence (3,886.9 MB, 644,449 calls, 5,830 reads there): the hub change's analysis of its
  reverse closure is work it must do, and on the closed program it falls in the hub row. Candidate: 4,953.2 MB
  (1.27x), 752,915 calls (1.17x), 6,126 reads (1.05x); the hub row alone 1,734.4 MB (its recorded overrun). (2) The
  nine-forms calls came back (112,965 against 716cc7527's 105,574): each predicate binds the loaded Var once per
  derivation, and the per-call name lookup (`seon.schema/late-predicate`) binds only a predicate whose namespace is
  not loaded yet; `unloaded-predicate-functions` keeps refusing a contracted predicate of a namespace with no source
  (checked on the classpath, never loaded). (3) `a-hundred-branches-analyse-each-distinct-digest-once` is not a
  keying defect: the same tests in one JVM make it red on HEAD too (41 digests twice, 386 `:seon.sci.runtime/analysed`
  evictions, cache 495 of 512 MB; the candidate 15 and 212), every duplicate a re-analysis of an evicted entry; filed
  as `seon.cache/analyses-are-evicted-from-a-full-cache-and-analysed-again`. Commit check: 124 tests, only reds
  accepted as red on HEAD (the gap proof and five carried by tasks).

#### Gate and order

Gate unchanged: `seon.cost-estimate-test/closed-runtime-rows-grow-past-no-accepted-overrun` on parent and change in
one check, `:hub-behavior-change` alloc <= 1.3x of 716cc7527 (1,167 MB). Order: R4 (deletion, no gate effect) ->
predicates (2, the gate's prerequisite: closes the four projection derivers, ratchet 48 -> about 17) -> the key ->
R3 -> R5 -> R6. The key is not committed before the fold: with the projection derivers open it is the 5.80x row.

### P8. The remaining open derivers (ordinary per lane; platform for evaluator and runner)

- Lane 1 (`seon.program`, ordinary): `seon.program/{content-digest, own-changed-identities, declaration-digest-map,
  differing-declarations, projected-values, fold-content-digest}` move to `seon.db` or take their reads as input;
  ratchet 18 -> 15 (`seon.cluster.source/unmerged-count`, `seon.program/own-changed-identities`, `seon.program/content-digest`); its opener row lists the six.
- Lanes 2-4 by Opus's kinds, each with its census on HEAD first (load-cycle seams; names read from data; program
  questions as fact reads).
- Last lane (platform): `run-time-resolution` becomes the census test and the open branch of
  `seon.sci.runtime/reach-index` and the binder is deleted; red row new
  `seon.sci.eval-test/no-runnable-src-function-resolves-a-name-at-run-time` (over `:seon.fn/calls` of every
  non-host-bound `src` function, no member of `run-time-resolution`; red until the last site), plus Astra's
  `seon.test.runner-test/reach-results-use-the-executed-runtime` and
  `seon.program-test/content-digest-needs-no-loaded-namespace`.
- Cost: changed-input work only; each held-data digest or report < 20 ms, < 4 MB at fixture size.

**Order and parallelism** (file ownership from the slices above):

- Wave 1, in parallel: **P0** (landed), **P2** (`seon.db`, `seon.program`, `seon.fn`), **P4a** (in flight,
  `seon.error.refusal`).
- Wave 2, in parallel after P2: **P1** (`seon.db`, `seon.fn`, the spec writers, the schema resource; platform) and
  **P3b** (`seon.program` only; ordinary).
- Wave 3: **P3a** after P1 and P3b release `seon.db`, `seon.fn`, `seon.program` (its sweep touches them; platform).
  **P6a** may run beside P1/P3b if its precondition holds and its sweep's files are disjoint from theirs; otherwise
  after P6b.
- Wave 4: **P4b** (after P3a and P4a; platform) beside **P5** (after P1; platform) only if two check slots are free:
  they share no file but `seon.cluster.boot`, so run them one after the other unless boot's edits are split as patches.
- Wave 5: **P6b** (after P4b and P5). Wave 6: **P7**. Wave 7: **P8**'s lanes, lane 1 any time after P2 and P3b.

Platform slices: P1, P3a, P4b, P5, P6b, P7, P8's last lane (one check-JVM slot each). Ordinary: P2, P3b, P4a, P6a, P8 lane 1.

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

None. The choices the first draft left open are decided in the phases above by "Choosing the design": P1 stores the
producer's value under a new key (`:seon.call-preparation/prepared-arities`) and keeps the report member's; P3a deletes
the packaged-forms fallback instead of moving the reader, and `compare-values` gets one definition in `seon.order`; P4b
binds the handed root at boot (decided, not counted) and hands the cache lookup as a value, keeping the refusal `.clj`;
P5 names its state members and their writer; P6 deletes `register-core-predicate!` first.

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
