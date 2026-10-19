---
title: "Closed runtime: remove run-time name resolution from the derivation path (plan, Opus)"
type: research
status: plan, one of two independent plans of the "closed runtime" design round
created: 2026-10-19
---

# Closed runtime: remove run-time name resolution from the derivation path

Lane closed-opus, 2026-10-19. One of two independent plans; written without the other. Research and REPL evidence
only: no `src` or `test` change. HEAD `2cdfa6a85`. Default runs `a92ba5b94` (its program facts: 7,683 names, the same
fact set lane runtime-fix2 counted on).

Builds on [branch-runtime](branch-runtime.md) (lane runtime-fix2's section), [requiring-resolve-inventory](requiring-resolve-inventory.md),
[runtime-rereview](runtime-rereview.md). Landed waves taken as given: `2f7e1c043` (cycle 2), `9f7caa06a`, `e7af24f14`.

## Headline

- **Every one of the seven families has a wrong side, and none needs a lookup at run time.** Cycles 3, 6 and 7 are
  functions in the wrong namespace or a fact computed in the wrong place; the frame classifier's `find-ns` decides no
  answer (REPL below); the projection builder's process lookup is a hand-made version of the exact key itself; and a
  predicate named in a schema is input to the projection, which Malli already resolves when it compiles the schema.
- **Closed count, by the reach index over default's `:seon.fn/calls` with each change applied as an edge edit:** the
  open `seon.cache` derivers fall from **50 of 78 to 16 of 78**, and all four projection derivers close
  (`seon.db/content-projection`, `seon.schema/declaration-projection`, `seon.schema/build-projection`,
  `seon.config/effective`). Open program names fall from 3,447 to 1,583 of 7,683; in production `src` from 2,519 to
  965 of 5,621. **Every family is necessary**: leaving any one out keeps at least one of the four open (table below).
- **After closing, the hub change no longer reaches the projection derivers.** `seon.db/as-of-key` is outside the
  closed reach of `content-projection` (163 names), `declaration-projection` (100) and `build-projection` (132). So
  the exact key derives main's projection on the branch, and the 4.4 s of `:seon.db/projection` misses in the hub row go.
- **Predicted rows against 716cc7527, exact key landed after phase 6:**

  | Row | 716cc7527 (MB / ms) | exact today (fix2) | predicted after this plan | ratio (MB) |
  |---|---|---|---|---|
  | `:seon-db-nine-forms` | 1,203.7 / 1,182 | 2,964.3 / 2,925 (2.46x) | 1,240-1,300 / 1,000-1,250 | 1.03-1.08x |
  | `:hub-behavior-change` | 897.7 / 1,175 | 5,210.5 / 6,403 (5.80x) | 1,010-1,150 / 1,250-1,500 | 1.13-1.28x |
  | `:similar-by-name-warm` | 25.8 / 51 | 23.8 / 91 (0.92x) | 26-27 / 35-50 | 1.0-1.05x |

  The estimate is HEAD's measured row (1,015 / 1,240 / 26 MB) plus the misses the exact key still pays (method below).
  It is an estimate until phase 7 measures it.
- **Open code then should not exist in `src`.** The 16 open derivers that remain are held open by four kinds of site:
  more load-cycle seams (cycles 1, 5 and `seon.sci.lookup`), names read from data, reflection on the host JVM's
  namespaces and Vars, and the evaluator's own acquisition. Each kind has a closed form: move the function, read a
  fact, take the resolved definition as input, or declare the function host-bound. After that the reach index needs no
  open class, and `run-time-resolution` becomes a census test asserting that no runnable `src` function calls one.
- **Phases**: 7 slices plus the exact key (P0 measurement and red proofs, P1 cycle 7 as stored facts, P2 the
  `seon.db` → `seon.program` seams, P3 cycle 3 into the `seon.schema.internal` leaf, P4 cycle 6 and the frame
  classifier, P5 the builder lookup deleted, P6 predicates as projection input, P7 the exact key). Deletes about
  380 lines of seams, lookups and fallbacks across `seon.db`, `seon.schema` and `seon.error.refusal` (counted per
  function below).

## How the numbers were taken (rerun forms)

1. Default, JVM mode, read-only (`eval_clj` root/default `:mode jvm`): the reach index's inputs exactly as
   `seon.sci.eval/reach-index` reads them, written to a scratch file:

   ```clojure
   (let [db (seon.db/db (seon.cluster.boot/connection "default"))
         names (seon.db/q '[:find [?sym ...] :where [?f :seon.fn/sym ?sym]] db)
         called (seon.db/q '[:find ?sym (distinct ?callee) :where [?f :seon.fn/sym ?sym] [?f :seon.fn/calls ?callee]] db)
         hb (seon.db/q '[:find [?sym ...] :where [?f :seon.fn/host-bound? true] [?f :seon.fn/sym ?sym]] db)]
     (spit "<scratch>/calls.edn" (pr-str {:calls (into (zipmap names (repeat #{})) (map (fn [[s c]] [s (set c)])) called)
                                          :host-bound (set hb)})))
   ```

   351 ms, 702 MB allocated (the whole call graph, once).
2. Babashka over that file: the open-set fold of `seon.sci.runtime/reach-index` and the opening set
   `seon.sci.eval/run-time-resolution`, copied verbatim. It reproduces runtime-fix2's census exactly: 7,683 names,
   **3,447 open**, 241 direct openers, 78 `seon.cache/lookup`/`lookup-within` callers, **50 open**, and the same 91-name
   hitting set.
3. Each planned change is applied as an edit of the calls map: the changed function loses its opening calls and gains
   the static edges the change gives it (a seam becomes a call of its target; a value passed in or a fact read adds no
   edge). The open set is recomputed after each family, and once more with each family left out.

The scripts lived in the session scratchpad; the forms above and the edge edits named per family below are the whole
method. The calls facts are `a92ba5b94`'s analysis; the three landed waves are applied as edits too (cycle 2's five
seams lose their resolution; their callers already call the `seon.error.problem` leaf).

| After | open names | open `src` | open derivers | four projection derivers open |
|---|---|---|---|---|
| default's facts | 3,447 | 3,316 | 50 | all 4 |
| + landed cycle 2 (`2f7e1c043`) | 3,439 | 3,308 | 50 | all 4 |
| + P1 cycle 7 | 3,436 | 3,305 | 50 | all 4 |
| + P2 `seon.db` → `seon.program` | 3,435 | 3,304 | 50 | all 4 |
| + P3 cycle 3 | 3,415 | 3,284 | 49 | all 4 |
| + P4 cycle 6, frame classifier, `referenced` | 2,918 | 2,797 | 46 | all 4 |
| + P5 builder | 2,914 | 2,793 | 46 | all 4 |
| + P6 predicates | **1,583** | **1,508** | **16** | **none** |

Leave one family out (all others applied):

| Left out | open derivers | still open | why (shortest path to an opener) |
|---|---|---|---|
| landed cycle 2 | 40 | `seon.config/effective` | `effective` → `seon.db/committed-value-identity` → ... → `seon.db/write-attribute-error` → `seon.db/error-scalar-text` |
| P1 cycle 7 | 40 | `effective` | ... `seon.db/with` → `prepared-write` → `validate-write-report` → `arity-verdict` → `call-preparation-report-snapshot` |
| P2 db → program | 41 | `effective` | ... `prepared-write` → `namespace-derivation` → `dropped-definition-namespaces` → `program-shapes-in` |
| P3 cycle 3 | 48 | all 4 | `build-projection` → `projection-build` → `validate-contracts!` → `assert-complete-contract!` → `schema-datahike-storable-attribute-in?` |
| P4 cycle 6 / frames | 48 | all 4 | `load-projection` → `refuse-projection-source` → `seon.error.refusal/diagnostic` → `operation-projection` (`find-ns`) |
| P4's `referenced` | 48 | all 4 | `diagnostic` → `references` → `referenced` (`var?`) |
| P5 builder | 41 | `effective` | `effective` → `seon.db/carried-projection` → `projection-builder` (`find-ns`, `ns-resolve`) |
| P6 predicates | 46 | all 4 | `projection-of-population` → `unloaded-predicate-functions` (`find-ns`) |

So no family is optional for the four, and the writer (`seon.db/with`, `prepared-write`) sits in `effective`'s reach:
every error the writer can build used to open it.

## 1. Each family: which side is wrong, and the change at its owner

The require graph (ns forms of HEAD's `src`, read with `read`, 2026-10-19): `seon.error.refusal` is a leaf;
`seon.schema` → `seon.error.refusal`; `seon.schema.datahike`, `seon.schema.edn`, `seon.program` → `seon.schema`;
`seon.db` → `seon.schema`; `seon.call-preparation`, `seon.fn`, `seon.config`, `seon.sci.eval` → `seon.db`.
`seon.db` and `seon.program` do not reach each other; `seon.schema` reaches none of `seon.schema.datahike`,
`seon.schema.edn`, `seon.program`, `seon.db`. `seon.schema.internal` (Malli only) is already the leaf `seon.schema`
requires.

### Cycle 7: `seon.db` → `seon.call-preparation` (`call-preparation-snapshot`, `report-snapshot`, `plan-for`)

What it does: the writer's arity gate (`seon.db/arity-verdict`, `arity-mismatch-report`, `prepared-arity-bounds`)
asks, for each callee whose call counts no stored arity admits, how many slots per arity call preparation can supply,
and admits `declared min - suppliable`. It runs only when a write changes `:seon.fn/sym`, `:seon.test/sym`,
`:seon.schema/key` or `:seon.call-preparation/key` identities (`seon.db/validate-write-report`).

Wrong side: the writer derives a call-preparation fact by calling up into its consumer. **Root's ruling verified:**
the suppliable slot count per arity is a fact of the program, a function of the callee's contract and the supplier
rows, both already facts; the writer should compare stored facts. Refuted part of the alternative: validators on the
connection need one boot-built writer, and there are about 25 connection sources.

Change, model taken from Datahike (a derived fact is written by its producer in the same transaction; the writer
checks only what the transaction holds):

- `seon.call-preparation` owns a new component attribute on each arity, `:seon.fn.arity/suppliable` (the count of
  slots it can supply), and one producer, `seon.call-preparation/arity-facts` (database value, callee symbols →
  tx-data). Its callers are the producers of the identities the gate watches: the indexer (`seon.fn/index!`), the
  REPL's admission (`seon.turn.batch` recording) and a supplier row's writer. A supplier row change re-asserts the facts
  of the functions whose slot schemas name its key (a stored `:seon.call-preparation/slot-schemas` on the arity makes
  that a query, not a program derivation).
- `seon.db/prepared-arity-bounds` reads `:seon.fn.arity/suppliable` from the pulled arity. The three seams and the
  snapshot argument of `arity-mismatch-report` are deleted.
- The writer gains no new check: a write that changes a contract without its arity facts reads the old ones, so the
  red proof of this phase is that a contract change made through each producer refuses a bad call count (see P1).

Cost: the gate stops building the call-preparation snapshot at the writer (174-188 ms on a speculative value, per
`report-snapshot`'s docstring, avoided by its memo); the producer pays one `plan-for` per changed function at write
time, which it already pays today when the snapshot misses. The fact is small (one long per arity).

### `seon.db` → `seon.program` (`program-definition-digest`, `program-behavior-digest`, `program-shapes-in`, `program-canonical-entity`) and `stored-namespace-context`

Not a cycle: `seon.program` does not reach `seon.db`. The four are "lazy by choice" and become `:require
[seon.program :as program]`. `seon.fn/stored-namespace-context` is a pure 10-line function of a namespace row (`seon.fn`
→ `seon.db` is the cycle); it moves to `seon.program`, which `seon.fn` and `seon.db` both require. Deletes the five
`defonce` seams.

### Cycle 3: `seon.schema` → `seon.schema.datahike` (seven seams) and `seon.schema.edn` (`packaged-forms`)

Wrong side: `seon.schema.datahike` requires `seon.schema` for five projection primitives only, read from its source:
`projection-cache-value` (4 calls), `projection-validator` (2), `projection-identity` (1), `call-with-forms` (2) and
`declaration-projection` (2, both in the two no-argument conveniences `encode-transaction` and
`decode-attribute-value`). The primitives are pure functions of the projection map over Malli and `core.cache`:
`projection-cache-value` is a `cache/lookup` on `:seon.schema.projection/compiled`, `projection-validator` is
`mr/schema` plus `m/validator` on `:seon.schema.projection/registry`.

Change: move the primitives (`projection-cache`, `projection-cache-value`, `projection-validator`,
`projection-identity` with its fingerprint derivation, and the dynamic population Vars `call-with-forms` binds) into
`seon.schema.internal`, the leaf that exists for exactly this ("never requires `seon.schema`"). `seon.schema`
re-exports nothing; callers convert to `internal`. The two no-argument conveniences move to `seon.schema` (they are
the only reason the translation needed the declaration projection) or their callers pass the projection they hold.
`seon.schema.datahike` then requires `seon.schema.internal` instead of `seon.schema`, and `seon.schema` requires
`seon.schema.datahike` statically. Same for `seon.schema.edn/packaged-forms`: what `seon.schema.edn` uses of
`seon.schema` is `build-projection`, `structural-schema` and the contribution functions; `packaged-forms` (reading the
packaged EDN resources) moves to a function `seon.schema` can call without them, in `seon.schema.edn`'s own leaf part
or in `seon.schema.internal`.

The other two seams in `seon.schema` on the derivation path: `database-projection` (`requiring-resolve
'seon.db/carried-projection`; `seon.db` requires `seon.schema`) is the wrong side, since the schema owner asks the
database owner for a projection: its caller holds the database and passes the carried projection. And
`canonical-schema-entities` resolving `seon.program/definition-digest` (`seon.program` requires `seon.schema`): the
digest is computed by its caller in `seon.program`, or the entity's digest is passed in.

### Cycle 6 and the frame classifier: `seon.error.refusal` (`schema-seam`, `cache-seam`, `operation-projection`, `checking-projection`, `prepared-call-prefix`, `classify-namespace`, `referenced`)

This one holds 48 of the 50 open derivers: every error constructor reaches it.

- **`classify-namespace`'s `find-ns` decides no answer.** It answers "authored" for a sourceless namespace when it is
  loaded OR its frame class is not a classpath resource. Probe on default (JVM, read only):

  ```clojure
  (let [path #(-> (str %) (.replace \- \_) (.replace \. \/))
        sourceless (remove #(or (io/resource (str (path (ns-name %)) ".clj")) (io/resource (str (path (ns-name %)) ".cljc"))) (all-ns))]
    {:loaded (count (all-ns)) :sourceless (count sourceless)
     :sourceless-with-init-class (keep #(when (io/resource (str (path (ns-name %)) "__init.class")) (ns-name %)) sourceless)})
  ;; => {:loaded 614, :sourceless 2 (user, seon.flow.actions), :sourceless-with-init-class []}
  ```

  A loaded namespace without source and without an AOT `__init.class` has only dynamically generated classes, which are
  not classpath resources, so the second clause already answers true. The `find-ns` clause changes the answer only for
  a loaded, sourceless, AOT-compiled namespace, which is a dependency (not authored) anyway. Delete the clause; the
  classifier reads the classpath and the frame's class, both facts of the host, never the program.
- **`prepared-call-prefix` reads the Var `seon.call-preparation/preparing` to munge its name.** The answer is a
  constant, `"seon.call_preparation$preparing$"`. It becomes a literal next to `machinery-namespace?`'s literal names,
  and a row in `seon.error.refusal-test` asserts it equals the munged name of `#'seon.call-preparation/preparing`, so a
  rename turns that test red (the proof that already exists for renames).
- **`schema-seam`, `operation-projection`, `checking-projection`, `identity-projector`.** Wrong side: the refusal
  (portable, no Malli under babashka) reaches back into `seon.schema` for the projection it references worlds by.
  The projection is a value the operation already holds: `seon.schema/call-with-projection` binds it. Model taken from
  Malli: a registry reaches a schema operation through its `options` or the one dynamic default registry
  (`malli.registry/*registry*` with `mr/dynamic-registry`), never by name. Change:
  - The dynamic Vars that hand a projection to an operation (`*projection*`, `*projection-state*`, `*packaged-forms*`)
    move to a new portable leaf with no requires, `seon.schema.handed`, which `seon.error.refusal` and `seon.schema`
    both require. A handed projection carries its own identity projector and explainer as values
    (`:seon.schema.projection/identity-projector`, `:seon.schema.projection/explain`), put there by the code that builds
    it, so the refusal calls values it was handed, never names.
  - With no projection handed (boot, namespace load, a thread no operation handed one), the refusal builds the error
    without world references, and the entrant boundary that records or delivers it (`seon.error`, which requires
    `seon.schema` and holds the database) references its worlds once with the projection it holds. This is where a copy
    could be stored, so the rule "errors hold references, never copies" still holds at storage. The fallback
    `declaration-projection` over `packaged-forms` inside the refusal is deleted. P4's first step counts how often the
    fallback runs on the cost rows; if it is the common path, the alternative is that the boundary hands the packaged
    declaration projection at boot through `seon.schema.handed`'s root binding, set once by the boot layer.
  - `malli.error/humanize` comes with the explainer value; `cache-seam`'s `requiring-resolve 'seon.cache/lookup`
    becomes a static require of `seon.cache` in the JVM half of the `.clj` (`seon.cache` is Caffeine; the babashka
    operator already has no basis, which is when the seam answers nil). If `seon.error.refusal` must stay loadable
    under babashka, the authored-namespace classification's memo moves to `seon.error` (JVM only), which already
    calls the refusal per frame.
- **`referenced`'s `var?`** keeps a Var from being named as a place. Run analysed it receives `sci.lang.Var`s, which
  the compiled `var?` reads as non-Vars: a value-type question, not resolution. Change: test the place classes
  directly, `(and (instance? clojure.lang.IDeref value) (not (instance? clojure.lang.Var value)) (not (instance?
  sci.lang.Var value)))` in the JVM half, so the compiled and analysed answers agree on both Var kinds.

### The projection builder: `seon.db/projection-builder`, `loaded-builder`, `connection-projection-state`

`projection-builder` finds `seon.sci.eval/loaded-identity` and `seon.operator.runtime/running-instances` by name to
compute `::builder-digest`: the digest of the loaded code that builds projections, joined to every projection key.
**That is a hand-made form of the exact key**: "a derivation is keyed by the code computing it". Under the lexical
exact key (P7), a closed deriver's key already holds the changes in its closed reach, so main and every runtime that
left the projection code alone share one projection, and a branch that changed it derives its own. Change: delete
`projection-builder`'s lookups and `loaded-builder` (80 lines); the projection key is the content key plus the
deriver's reach changes. Until P7 lands, P5 keeps `::builder` as the loaded commit passed by the connection's owner
(the cluster holds it at boot) instead of found by name.

`connection-projection-state` falls back to scanning `running-instances` for the instance holding the connection when
the connection's metadata lacks `:seon.sci.eval/projection-state`. The primary path is the producer
(`carry-connection-projection-state!` at construction). Change: every connection constructor carries the state (the
producer), and the scan is deleted, with a regression that each connection source in `seon.cluster.boot` and the test
harness carries it.

### Predicates looked up by name: `seon.schema/runtime-predicate`, `loaded-predicate-var`, `unloaded-predicate-functions`, `projection-bindings`, `predicate-callable`, `identity-only-descriptors-in`

How Malli itself resolves a predicate (our fork, `reference-code/malli` at `dd13ff81`): `malli.core/-fn-schema`
compiles `[:fn sym]` through `malli.core/eval`, whose JVM branch `-loaded-qualified-value` does `find-ns` then
`ns-resolve` and returns the Var, so a re-evaluated `defn` stays live; `malli.core/predicate-schemas` registers each
core predicate by name and value (`-register-var`) once, when the registry is built; and the resolution environment is
an option, `::m/sci-options`. So in Malli the name → function mapping is resolved **once, at compile time, from an
environment the caller supplies**. The registry is the value that maps names to schemas.

Wrong side: Seon resolves the name inside the derivation (`build-projection` → `predicate-bindings` →
`runtime-predicate`), so the derivation's identity depends on names its input names, and the reach index cannot see
them. The predicates are in the input: default's packaged forms name 78 predicate symbols (probe:
`(#'seon.schema/predicate-symbols-in (seon.schema.edn/packaged-forms))`), 69 program predicates, **all 69 with a
stored `:seon.program/definition-digest`**, and 9 library predicates (`clojure.core/bytes?`, `ifn?`, `seq?`, `some?`,
`tagged-literal?`, `var?`, `volatile?`, `clojure.string/blank?`, `malli.core/schema?`), constants of the dependency
version.

Change:

- The projection's derivation takes, as part of its input, the predicate environment: a map from each predicate
  symbol its forms name to the runtime's binding of it (the JVM Var on main, the runtime's SCI Var where the runtime
  binds one; `seon.sci.kernel/context-bound-projection` already computes exactly that set) and is keyed by
  `{symbol definition-digest}` for those symbols (library predicates by their library coordinate). The map is built by
  the caller holding the runtime (the evaluator or `seon.db` from the database value: digests are facts) and passed as
  Malli's own option, `::m/sci-options {:namespaces ...}`, or, simpler, as a registry of `[:fn var]` entries the
  projection registry includes, as `predicate-schemas` does.
- `runtime-predicate` keeps one job outside the derivation: the load-time sentinel `register-core-predicate!`. Its
  `requiring-resolve` loads a namespace from a name in data, which "compilation must never load code" already forbids
  for `loaded-predicate-var`; both are deleted from the projection path.
- `unloaded-predicate-functions` (a branch's predicate no file holds yet compiles to a refusing callable) becomes a
  property of the environment map: a symbol the runtime holds no binding for maps to the refusing callable, a function
  of its name alone, as today.
- `projection-bindings` and `projection-state-bindings` are open only because their contracts say `[:fn
  clojure.core/var?]` (body probe: no opening call in either body; same for `seon.sci.eval/copy-host-var`). The
  contract's predicates are reached as `:seon.fn/calls`. Their output schema becomes `[:map-of :seon.schema/handed-var
  ...]`, a named schema whose predicate is the place-class test above.

## 2. Closed count and predicted cost

**Closed count: 50 open derivers become 16; 34 close, including all four projection derivers.** The 16 that stay open
and what holds each (shortest path, after all changes):

| Deriver | Held open by |
|---|---|
| `seon.cluster.source/file-proof`, `publication` | `seon.fn/interpreted-namespaces` → `seon.program/base-context-injected-symbols` (`ns-publics`, `require`) |
| `seon.cluster.source/member-contributions`, `members-of` | cycle 1, `seon.test` ↔ `seon.cluster.source` (`requiring-resolve`) |
| `seon.cluster.source/unmerged-count`, `seon.program/own-changed-identities`, `content-digest` | `seon.program` → `seon.db` by `requiring-resolve` (no cycle; `seon.program` claims to be pure) |
| `seon.context/conflicts` | `seon.ref/at-level` → `seon.sci.lookup/function-documentation` (cycle `seon.sci.lookup` → `seon.ref`) |
| `seon.embed/corpus` | `seon.embed/texts-of` resolves a configured text function |
| `seon.health/problems-unit` | `seon.problems/stale-vars` (`all-ns`, `ns-interns`) |
| `seon.health/profile-unit` | `seon.profile/pain` resolves `seon.config/defaults` (cycle) |
| `seon.health/replaced-roots` | `seon.instrument/replaced-roots` (`bound?`, `find-var`) |
| `seon.sci.eval/derive-program-context`, `program-context` | `seon.sci.eval/acquire-program!` (`find-ns`) |
| `seon.sci.eval/record-refusals-once!` | `seon.db/transact!` → `stamp-transaction` (`var?`) |
| `seon.test.runner/current-results` | `seon.test.runner/loaded-identity` (cycle `seon.sci.eval` → `seon.test.runner`) |

None of the 16 is on the hub row's miss list beyond `seon.sci.eval/program-context` (117 ms exact, 71 ms HEAD) and
`seon.test.runner/current-results` (101 against 84).

**Predicted cost.** Method: start from HEAD's measured row (runtime-fix2 `cost-rows-headk`) and add what the exact key
still derives once the derivers are closed. In the exact run, the hub row's extra derivation was `:seon.db/projection`
4,418 ms (2 misses), `:seon.schema/projection` 645, `:seon.config/effective` 554 (8 misses),
`:seon.schema/declaration-projection` 539; everything else within 50 ms of HEAD. After this plan:

- The three projection kinds are derived by closed derivers whose reach does not hold `seon.db/as-of-key`: they hit
  main's entries. Gone.
- `seon.config/effective` is closed but **does reach `as-of-key`** (its reach is 546 names, through the database
  reads), so a hub change keys it again: exact, and correct. Its 554 ms in the exact run is mostly the nested schema
  projection, which now hits; HEAD's 7 misses cost 4 ms in all, so its re-derivation should cost tens of ms.
- 1,105 closed functions reach `as-of-key`; they are bound analysed on the branch with or without this plan (the
  reach rule of f06474e75), so their cost is in HEAD's row already.
- The open functions are no longer re-bound on every change (exact today binds every open analysed function again per
  change); 1,508 open `src` names remain against 3,316, so that cost falls by more than half before phase 8 removes it.

Hence the table in the headline: hub 1,010-1,150 MB (1.13-1.28x of 716cc7527's 898 MB; HEAD is 1,015), nine forms
1,240-1,300 MB (1.03-1.08x; its nine forms re-evaluate equal text, so the exact key's whole excess was the open set),
similar-by-name 26 MB (unchanged). The owner's 1.3x bar is met by the estimate's upper end; P7 measures it on the same
probe (`tmp/runtime-fix2/cost_test.clj`, committed as P0's measurement) before the exact key lands.

## 3. The remaining open functions, and whether open code should exist

After the plan, production `src` holds 148 functions that call a run-time resolution directly (965 open names). They
are four kinds, and each has a closed form. **The reach index should not learn to follow names; open code should be
removed**, because the only way to follow `(requiring-resolve sym)` exactly is to know `sym`, and when `sym` is static
the right change is a static call, and when it is data it is input.

1. **Load-cycle seams with a quoted symbol** (cycles 1, 4, 5, 8, 9, 10 and the single-site ones of the inventory;
   `seon.profile/pain`, `seon.test.runner/loaded-identity`, `seon.ref/at-level`'s `seon.sci.lookup`). Removed by
   moving the function to its owner, as cycles 2, 3, 6 and 7 here. A quoted symbol followed as a call was tried
   (3ab155566) and reverted by the owner's ruling; it would also leave the wrong dependency in place.
2. **Names read from data** (`seon.embed/texts-of`, `seon.schedule/invoke-handler`, `seon.web.jvm/handler-var` and
   `projection`, `seon.cluster.source/resolve-population`, `seon.maintenance/result-entity`, `seon.ai/coercion-function`,
   `seon.call-preparation/supply-from-row`). The name is input. The derivation takes the runtime's binding of the name
   as an argument, resolved by the caller that holds the runtime, and the key holds the name's definition digest. Where
   the set is closed and small (the segmenter of `9f7caa06a`), an `[:enum]` and a map of Vars replace the lookup.
3. **Questions about the program asked of the JVM** (`seon.ref/named?`, `library-definition`,
   `seon.error/candidate-calling-form`, `library-calling-form`, `seon.instrument/definition-location`,
   `jvm-arglists`, `example-forms`, `seon.problems/stale-vars`). On a branch the answer must be the branch's. "Does this
   name exist, what are its arglists, where is it defined" are facts: `:seon.fn/sym`, `:seon.fn/arities`, the source
   span, read from the database value the caller holds. Library names are the reference resolver's library index.
   The read is then input, and the function is closed.
4. **The platform observing itself** (`seon.sci.eval/acquire-program!`, `build-base-ctx`, `copy-host-var`,
   `install-jvm-root!`, `load-host-namespace`; `seon.instrument/apply!`, `collect-contracts!`, `replaced-roots`;
   `seon.cluster/development-arming-identities`; `seon.test.runner/run-selected-tests`). Their subject is the JVM's
   compiled program by definition. They are the one exception physics imposes: declare them host-bound (the fact
   `:seon.fn/host-bound?`, judged by the producer `seon.fn/host-bound-callers`), so they run compiled on every runtime and
   their reach is reported, not opened. Their callers are entrants, which run main by design.

With those four done, `seon.sci.eval/run-time-resolution` is no longer an input to the reach index; it is a census:
`seon.sci.eval-test/no-runnable-src-function-resolves-a-name-at-run-time` asserts that no SCI-runnable `src` function
outside host-bound declarations calls one. The `::runtime/closed` set and the open branch of the binder are deleted.

## 4. Phases

Each phase is one loadable slice; HEAD boots after each; each deletion lands with the conversion of its last caller.
"Ordinary" = REPL-first on a lane branch, committed with `--branch-evidence`. "Platform" = `bin/lane commit --test`
through a check JVM (schema resources, boot, writer, evaluator).

| Phase | Slice | Red proof (new or row) | Kind | Estimate |
|---|---|---|---|---|
| P0 | Commit the measurement: `seon.cost-estimate-test/cost-rows-probe` (from `tmp/runtime-fix2/cost_test.clj`) and the closed-count census as a test that prints nothing and asserts the four projection derivers' openness against a recorded expectation | `seon.sci.eval-test/projection-derivers-are-closed`: red today (all four open), green after P6 | platform (test only) | 0.5 h; census 161 ms, the probe as runtime-fix2 ran it |
| P1 | Cycle 7: `:seon.fn.arity/suppliable` facts from `seon.call-preparation/arity-facts`, written by the indexer, the REPL recording and supplier writes; `seon.db/prepared-arity-bounds` reads them; the three seams deleted | new rows in the existing arity table of `seon.db-test` (`arity-mismatches` refuses a call count only a supplier admits, after the supplier row is retracted in a later transaction): red while the fact is unread | platform (schema resource + writer) | 2 h; write cost +1 `plan-for` per changed function (~0.1-1 ms), the gate saves its snapshot build |
| P2 | `seon.db` requires `seon.program`; `stored-namespace-context` moves to `seon.program`; five seams deleted | none new: a mechanical move; the census row of P0 counts `seon.db/program-*` as non-openers (red until moved) | ordinary | 0.5 h; zero runtime cost |
| P3 | Cycle 3: projection primitives into `seon.schema.internal`; `seon.schema.datahike` and the packaged-forms reader below `seon.schema`; seven + one seams, `database-projection`, `canonical-schema-entities`' resolution deleted | `seon.contracts-compile-test` loads `seon.schema.datahike` without `seon.schema` (new row: a fresh require of `seon.schema.datahike` in a check JVM loads no `seon.schema`), red today | platform (load order, schema publication) | 3 h; no runtime cost (a static call replaces a delay deref) |
| P4 | Cycle 6 + frames: `seon.schema.handed` leaf; refusal reads handed values; `find-ns` clause, `prepared-call-prefix` lookup, `schema-seam`, `cache-seam`, `operation-projection`'s lookup and `checking-projection`'s fallback deleted; `referenced` tests classes | `seon.error.refusal-test`: (a) the prefix literal equals `#'seon.call-preparation/preparing`'s munged name; (b) an error built analysed on a changed runtime and compiled on main from the same throwable references the same worlds (red today: the analysed `var?` differs); (c) an error built with no handed projection is referenced once by the recording boundary (counted first on the cost rows) | ordinary for the refusal functions, platform for the boot binding if the fallback is common | 3 h; per error: one map get instead of a delay deref and a `find-ns` |
| P5 | Builder: the connection's owner passes the loaded commit; `projection-builder`'s lookups, `loaded-builder` and the `connection-projection-state` scan deleted | `seon.db-test` row: every connection the test harness and `seon.cluster.boot` construct carries `:seon.sci.eval/projection-state` (red if any source lacks it, counted first) | platform (writer, boot) | 2 h; saves a `find-ns` pair per projection read |
| P6 | Predicates as projection input: the environment map keyed by definition digests; `runtime-predicate` and `loaded-predicate-var` off the derivation path; `unloaded-predicate-functions` as a property of the map | `seon.sci.runtime-test/a-composed-runtime-answers-what-its-own-source-answers` gains a row: a branch changes a predicate, the branch's projection validates with it and main's with main's, in both derivation orders (red today under the exact key: the key does not hold the predicate's digest); P0's census turns green | platform (projection derivation, publication) | 4 h; key cost: 78 digests read per projection derivation (facts, cached with the projection) |
| P7 | The lexical exact key of runtime-fix2's option 1 (a derivation keyed by its deriver's closed reach changes, held in the analysed definition), R3 and R5 on it, R6's differential rows through `arm!` and the overlay | runtime-rereview's R1 and R2 reproductions as `seon.sci.eval-test` rows (red on HEAD); `seon.profile/measure` on the three rows, parent against change, same run: hub at most 1.3x of 716cc7527 | platform (evaluator) | 1 day; the table above |
| P8 | The remaining open sites by the four kinds of section 3, one lane per kind; then `run-time-resolution` becomes the census test and the open branch of `seon.sci.runtime/reach-index` and the binder is deleted | `seon.sci.eval-test/no-runnable-src-function-resolves-a-name-at-run-time` (red until the last site) | mixed; kind 4 is a producer rule (platform) | 3-4 lanes |

Order: P1, P2, P3 are file-disjoint except `seon.db` (P1, P2 touch it: P2 first, it is half an hour). P4 needs P3's
leaf only if `seon.schema.handed` is folded into `seon.schema.internal`; kept separate (portable, no Malli) it runs
beside P3. P5 and P6 both touch `seon.db`'s projection path: P5 then P6. P7 waits for P6 (the measured bar needs the
four closed). P8's lanes run after P7.

## 5. What gets deleted, and the alternative rejected

Counted from HEAD's source by top-level form (lines per function, read with `read` and line numbers):

- `seon.db`: `call-preparation-snapshot`, `report-snapshot`, `plan-for` seams (14), `program-*` and
  `stored-namespace-context` seams (12), `projection-builder` (15), `loaded-builder` (65), `connection-projection-state`'s
  scan (about 12 of 16); `prepared-arity-bounds` shrinks (23 → about 8). About **125 lines**.
- `seon.schema`: the eight datahike/edn seams (25), `database-projection` (3), `runtime-predicate` (51, reduced to the
  sentinel's few lines), `loaded-predicate-var` (20), `unloaded-predicate-functions` (44, replaced by a few lines on
  the environment map), `predicate-callable` (12). About **140 lines** net of the environment map.
- `seon.error.refusal`: `cache-seam` (5), `schema-seam` (16), `operation-projection` (11), `checking-projection` (15),
  `identity-projector` (4), `prepared-call-prefix` (14 → 1 literal), `classify-namespace`'s `find-ns` clause. About
  **60 lines**.
- Moved, not deleted: the projection primitives into `seon.schema.internal` (about 60 lines), `stored-namespace-context`
  into `seon.program` (10). New: `seon.schema.handed` (about 10), `seon.call-preparation/arity-facts` and its attribute
  (about 30).
- After P7: `seon.cache/*reached*`, `seon.sci.runtime/reaching`, `reached-changes`, the open branch of the binder, and
  the host-bound report (`seon.sci.eval/host-bound-reaching`, `::host-bound-reached`, `:seon.batch/host-bound-reached`,
  `seon.sci.runtime/host-bound-reached`, `::host-bound-reach`) per root's ruling on R4.

Net about **-300 lines** in P1-P6, before P7 and P8.

**Rejected, simplest first:**

1. **Follow quoted symbols as calls in the analyzer** (3ab155566): closes 28 `src` names, because the derivers are
   held open by data-named and reflective sites, not by quoted ones; and the owner ruled `requiring-resolve` a hack to
   remove. Measured by runtime-f7b.
2. **Narrow "open" to the calls that yield code** (`resolve`, `requiring-resolve`, `find-var`, `eval`, ...): keeps all
   50 open (runtime-fix2's census); `requiring-resolve` alone keeps 49.
3. **Key open derivers by the whole delta** (exact today): correct, 5.8x on the hub row.
4. **Validators on the connection for cycle 7** (root rejected it): needs one boot-built writer; there are about 25
   connection sources.
5. **Make `seon.error.refusal` require `seon.schema`**: reverses the cycle instead of removing it; `seon.schema`'s
   own errors are built by the refusal.
6. **Run open code compiled everywhere** (runtime-fix2's option 3): HEAD's cost, but a change reached only through
   open code (45% of the program today, 17% of production `src` after P6) is not live, which the owner's requirement
   rules out.

## Verification limits

- The counts are the reach index's algorithm over `a92ba5b94`'s `:seon.fn/calls` facts with the changes applied as
  edge edits, not over a re-analysis of edited code. Each phase's P0 census row re-counts on the real analysis.
- The cost table is an estimate from runtime-fix2's measured rows and miss attribution, not a measurement of these
  changes. P7 is the measurement, and a miss over 1.3x on the hub row stops P7 for the owner.
- P4's "no handed projection" frequency is not measured; it decides between building unreferenced errors and a boot
  root binding.
- P1's fact needs a check that every writer of a contract or supplier row is one of the three named producers; the
  census of `transact!` callers writing `:seon.fn/spec` or `:seon.call-preparation/key` is P1's first step.
