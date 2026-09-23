---
type: research
status: proposal for B4 §2f; independent implementation review still required
created: 2026-09-23
scope: docs only; agent-authored tests, schema injection, prepared worlds
---

# Simple tests are ordinary agent functions

The system already acquires agent contexts, carries database values, validates declarations and records test evidence. Pass those acquired values to ordinary functions; share immutable inputs and fork only the resources whose declared use requires mutation.

**Recommendation:** keep `clojure.test/deftest` for zero-argument tests; admit ordinary `defn` tests with a Malli argument schema and one world metadata key. Use the existing agent entrance for admission, preparation, execution and release. Add no fixture DSL, custom `deftest`, test-only interpreter, dependency-injection framework or scheduler.

This implements the interface requested by the owner in [B4's opening ruling](../../prds/agent-platform/plan/lane-b4-tests-in-process.md), added at `e5f649f94`: tests are authored by agents through the same REPL and strict entry checks as other functions; schema declarations request real internal values and test versions of external effects. The earlier [README §7 ruling](../../prds/agent-platform/plan/README.md#7-decisions-and-proof-gates) supplies the lifecycle. This research feeds §2f; it does not edit that plan or authorize implementation.

## 1. Installed seam versus proposed interface

Source observations below use the shared checkout on 2026-09-23, around HEAD `a1a4cdec936084ea09f2126836f24c2dacd0ce8d`; `src/seon/test.clj` and dependency files include foreign edits. Line anchors identify inspected bodies, not proof that the running JVM adopted them. History read: `678009fcd0` (branch fixtures/one request), `d7b3930ff` (agent fixture conversion), `ba4e30f0f` (reach-index reuse), `f3a4b33d9` (content-keyed member evidence). B4's historical worker descriptions are explicitly superseded; do not rebuild them.

**Precise boundary for the assignment's premise:** schema-based argument preparation already exists for agent calls; the inspected runner supplies branch custody but does not directly invoke typed test-function signatures through it. `seon.test/member-result` (`src/seon/test.clj:1447`, acquisition at `:1473`, custody at `:1530`) acquires a child through `seon.cluster.agent/acquire-context!`. SCI tests receive its connection; host tests receive the member handle and no database custody. `seon.sci.eval/run-test` (`src/seon/sci/eval.clj:3686`) delegates to `run-tests` (`:3670`), then `seon.test.runner/run-vars!` (`src/seon/test/runner.clj:350`). That owner requires callable `:test` metadata and binds supplied custody using `seon.db/call-with-custody` (`src/seon/db.clj:369`). Elided **database-function arities** then reach that connection. It does not inspect a test's Malli arguments and `apply` the requested values.

**The existing injection owner is `seon.call-preparation`, not a new test facility.** `prepare` (`src/seon/call_preparation.clj:1371`) takes the acquired snapshot, explicit environment, invocation plan and authored arguments, preserves explicit arguments and supplies omitted values; unavailable, invalid or ambiguous supplies refuse before entry. `hook` (`:1450`) receives the executing SCI context. Supplier declarations already use `:seon.call-preparation/key`, `/schema` and `/supplier` (`resources/seon/schemas/seon.call-preparation.edn:17–40`). `seon.env/supplied-agent-id` (`src/seon/env.clj:335`) is an actual existing supplier. Matching is by declared schema identity, not structural similarity; required request-map entries also match their declared key (`call_preparation.clj:778`). **Invoke admitted typed tests through this same preparation path.** Extend its ordinary supplier rows for worlds/external effects, not a parallel test resolver. This reconciles README's intention with the narrower installed test dispatch.

The fixture adds another child: `with-branched-database` (`test/seon/test_support.clj:627`) acquires from the executing handle and calls `run-database-body` (`:576`), which passes the connection to the fixture callback. This is the concrete duplication to remove when direct injection lands. `with-database` is at `:643`; constructor helpers are `namespace-row :686`, `agent-tx :697`, `program-fn-row :741`, `apply-config! :754`, `seed-cluster! :773`.

Stock `clojure.test/deftest` defines a **zero-argument** body (`reference-code/clojure/src/clj/clojure/test.clj:624`). `(deftest t [db] ...)` puts `[db]` into the body; it binds nothing. A live macroexpansion confirmed this (§9). Do not quietly replace the standard macro. Plain `defn` already provides the desired signature.

Every example below is **proposed author syntax**, except ordinary `deftest`. New schema/metadata names are explicitly proposals. Today `resolve-test` (`src/seon/test.clj:1264`) also requires callable `:test` metadata: admitting typed `defn` tests needs a small extension at that same owner and the declaration producer. Do not set `:test true` and pretend it satisfies clojure.test. Preserve its assertion/reporting semantics in the existing capture owner without mutating a host Var's metadata or root during execution.

## 2. The smallest author interface

```clojure
(ns example.tests
  (:require [clojure.test :refer [deftest is testing]]
            [seon.db :as db]))

(deftest arithmetic
  (is (= 4 (+ 2 2))))

(def program-world [])

(defn program-is-present
  {:seon.test/world 'example.tests/program-world
   :malli/schema [:=> [:cat :seon.db/database-value] :nil]}
  [database]
  (is (seq (db/q database '[:find ?s :where [?e :seon.fn/sym ?s]])))
  nil)
```

`database` is an ordinary local; rename it freely. The schema, not the parameter spelling, requests the value. The **one proposed metadata key** `:seon.test/world` references a plain data definition and also marks this `defn` as a test declaration. An empty vector means the captured program world, not an empty store. Ordinary `deftest` remains discoverable through its standard metadata; its zero-argument contract is admitted through the existing declaration path. All helper functions still require their normal complete schemas.

No special syntax is necessary for named arguments either:

```clojure
(defn writer-sees-its-world
  {:seon.test/world 'example.tests/program-world
   :malli/schema
   [:=> [:catn [:seon.db/connection :seon.db/connection]
               [:seon.db/db :seon.db/database-value]] :nil]}
  [connection before]
  (is (= (db/basis-t before) (db/basis-t (db/db connection))))
  nil)
```

`:seon.db/db` is the **argument label**, whereas the inspected existing value schema is `:seon.db/database-value`. Do not invent a competing database type because the request-map key is named `/db`. An injected database is captured at entry; after writing, read `(db/db connection)` explicitly. The runner supplies both arguments from one world and one custody by calling the existing preparation owner. No generic recursive object construction is needed: Malli `m/type`, `m/children`, `m/form` and named references describe the declared signature.

### Resolution table — proposed, finite and explicit

| Argument schema / named slot | Value supplied by the existing agent owner | Sharing decision and preparation |
|---|---|---|
| `:seon.db/database-value` (optionally named `:seon.db/db`) | Acquired immutable database with its projection | Same prepared value for all readers; no per-member branch |
| `:seon.db/connection` | Connection to the member's branch of the prepared committed world | One private branch per writer, made before bodies start |
| `:seon.agent/id` in a named `:seon.agent/id` slot | Identity produced by the real agent creation owner in this world | Immutable identity may be shared; acquiring a runnable agent requires the handle below |
| `:seon.agent/execution-handle` | Actual acquired one-body agent handle, with branch/context and its real lifecycle | Treat as write-capable; private branch, SCI fork and owned graph resources |
| **Proposed** `:seon.test/world` value schema | Open immutable map with `:seon.db/db` and canonical named subject refs from preparation; never a connection | Read-sharing allowed; no implicit live settings or mutable handles |
| **Proposed** `:seon.ai/complete-fn` | Contracted callable at the external completion seam, resolved from the execution's declared effects | Pure deterministic reply function shares; a stateful scripted response sequence gets per-member state |
| An existing named capability schema, with its declared resolver/effect identity | Actual capability, or its declared external-effect test implementation | Pure capability shares; connection-bearing capability forks; process/store-global capability follows the existing platform classification |

This table describes the **existing supplied-default row mechanism**, not a new dispatch map. Resource suppliers read the admitted agent environment; the runner arranges the resources before calling preparation. A proposed completion row has the exact existing declaration shape:

```clojure
{:seon.call-preparation/key :seon.ai/complete
 :seon.call-preparation/schema [:seon.schema/key :seon.ai/complete-fn]
 :seon.call-preparation/supplier [:seon.fn/sym seon.effect/supplied-completion]}
```

`seon.effect/supplied-completion` is a proposed named, contracted supplier, installed by normal initialization/publication, not authored per test. It reads the execution's declared external-effect implementation; it never replaces an internal function. The same pattern uses `:seon.test/world` and its named value schema for a world supplier. This is ordinary program data at the existing owner, not a registry alongside it. Schema references to internal values use the same suppliers for agents and tests. Unknown, ambiguous union, variadic resource request, mismatched world or unbound capability refuses at admission with the test, argument and schema named. The first test interface can require fixed arities while preserving the agent preparation owner's existing broader call semantics; do not tighten that owner globally. Malli validates types; it does **not** infer effect safety from a type name. Capability schemas must reference their owning resource/effect declaration, and those dependencies enter the program graph.

Cost: argument resolution is O(number of declared arguments), target **<1 ms** once contracts are compiled; this is an acceptance target, not a measurement. It removes callback fixtures, body-level context acquisition and arity/name guessing. The only preparation for `program-is-present` is acquisition already held by the request. `writer-sees-its-world` adds a branch, not a store build.

## 3. External effects are arguments too

Concrete proposed schema declaration, using the existing AI request/result families:

```clojure
{:seon.ai/complete-fn
 [:=> [:cat :seon.ai/request] :seon.ai/completion]}
```

This is proposed synchronous completion-callable semantics, not a claim about today's transport arities. A streaming callable must instead declare the real streaming contract and completion event. Publish any such declaration through normal schema admission before admitting its user.

```clojure
(defn completion-has-text
  {:seon.test/world 'example.worlds/one-completion
   :malli/schema
   [:=> [:catn [:seon.ai/complete :seon.ai/complete-fn]
               [:seon.ai/request :seon.ai/request]] :nil]}
  [complete request]
  (let [reply (complete request)]
    (is (= "hello" (:seon.ai/text reply))))
  nil)
```

The world supplies a valid request and the declared external reply `{:seon.ai/text "hello"}`; the effect owner supplies a callable of the **same contract** as production. The successful-completion shape and text member are present in `resources/seon/schemas/seon.ai.edn:395–408`. A refusal is a real declared completion result and makes this assertion fail, rather than becoming an untyped mock value.

The concrete injection rule is named slot `:seon.ai/complete` → its schema/effect declaration → the world's declared external response source → an armed callable; `:seon.ai/request` → the prepared world's canonical request. The test chooses neither a global Var nor a mocking implementation. A production function under test must receive that callable through its normal argument/capability flow. If it currently closes over an HTTP global, change the production effect seam first; never patch the global in a fixture. Preparation fails if the requested external effect lacks a declared test response. It never silently purchases a real model completion.

Real query, writer, analyzer, contract, turn, settlement and SCI functions are never replaced. Provider parsing tests inject HTTP bytes at the transport seam and execute the real parser; turn tests can inject parsed completion values when provider parsing is outside their subject. The selected seam determines the evidence claim. No fake internal `seon.db`, no `with-redefs` over `seon.ai`, no test-only agent loop.

Cost: dispatch target **<1 ms** plus declared response processing; a sequence allocates only O(number of scripted replies) member state. It deletes global redefinitions, sleeping fake providers and shared response cursors. CPU/stream work remains timed. Test responses and their implementation digests are evidence inputs, so changing either invalidates affected green results.

## 4. Worlds are ordinary data, built once

A world is a quoted vector of ordinary agent forms, or equivalent EDN requests to existing writers. It is not a vector of fabricated persisted entity rows. Here is exact author data using the installed `my.note/add!` surface (`src/my/note.clj:24`):

```clojure
(def one-note
  '[(my.note/add! {:my.note/id "subject"
                  :my.note/content "original"})])

(def two-notes
  (conj one-note '(my.note/add! {:my.note/id "peer"
                               :my.note/content "second"})))
```

The preparation handle supplies the real agent identity and connection through ordinary call preparation, as an agent receives them today; note requests are constructor inputs, not raw entity rows. Preparing that handle uses `seon.cluster.agent/creation-tx` (`src/seon/cluster/agent.clj:186`) through normal admission/acquisition; no invented `my.agent/create` is required. Tests needing several agents declare those subject requests as world data, with scope supplied by the preparation handle, not inferred from all cluster rows. World output retains the producer's actual subject refs, scope and request values. There are no author-supplied cluster names, synthetic digests or hidden singleton-cluster inference. World forms execute through the ordinary REPL entrance with supplied custody, strict schema/test checks and armed writers; a refusal aborts preparation and names the form before any dependent test body runs.

A `def` holding quoted forms is convenient authoring data. Ordinary private `def` alone is not durable program content today. At test admission, resolve that reference in the author's context, record the immutable EDN and its digest with the admitted declaration, and record resolved namespace/alias dependencies. Never store a live Var/atom as the world's authority, or rely on finding a private `def` after restart. This small admission change is required for reproducibility.

Key preparation by **what it reads**: world content, resolved writer/constructor definitions, relevant schema/config/resource digests, fixed seed and clock inputs, plus the admitted base data/read evidence. A commit id supplies provenance and retention, but unrelated record writes must not invalidate a prepared world. Until complete read evidence is available, conservative captured-basis invalidation is honest but fails the desired reuse optimization; report that limitation rather than assert unrelated-write reuse. Do not rebuild a whole program to compute each key.

Group equal keys once, prepare each missing world once, retain its database value and acquired context, then inject that value into all readers. For writers, retain a committed prepared head and branch from that head. Use the existing derived-cache owner, Clojure cache tools where no dependency cache exists, and Datahike retention; no atom registry of remembered "current worlds". Retained worlds pin their ancestry only while needed; bounded eviction and ordinary unlink/retention release them. Hold memory is shared index nodes plus each distinct delta/context, never N copied stores.

Preparation cost is O(unique missing world forms + their affected datoms/definitions); cache hits target **<1 ms** resolution, small new worlds target **<100 ms**, both unmeasured. A larger world owes a measured number. It deletes repeated canonical seed transactions, per-test fixtures and repeated projection/index construction. Sharing the world's data does not share a running agent graph or mutable object embedded in SCI.

## 5. Share reads; fork writes; use `d/with` when persistence is not the subject

The author already declared the decision in §2. A reader gets only the immutable value. A writer requests a connection or executable agent and gets a private branch. A read world must not contain a hidden connection. Today `call-preparation/hook` reads the environment connection and dereferences it (`src/seon/call_preparation.clj:1480–1495`), while supplied database defaults are documented as current-at-call. A frozen reader therefore receives its prepared database **explicitly** through `prepare` (explicit arguments remain unchanged), and read-only execution must keep writable custody unavailable. Extending the shared agent entrance to carry that frozen basis is required before claiming branch-free readers. Do not silently change ordinary agents' current-at-call semantics or put a dummy connection in a read world. The same value may be used concurrently; a branch pointer can retain the shared prepared world without granting readers its writer.

A schema is a resource declaration, **not proof that a JVM body cannot have effects**. Compiled code can reach globals, Java interop or dynamically resolved functions. Admission must combine the signature with the existing declared call/capability graph: a db-only test reaching an undeclared writer/effect refuses, and the ordinary effect/database boundary refuses missing custody at runtime. Unknown effect reach is not permission to parallelize. This is a rule for agents as well as tests, not a new test sandbox. Existing host/global/platform tests retain their classified isolation until their real seams prove otherwise.

**Zero branch for immutable derivation:** Datahike `d/with` applies transactions to values and returns a report. Put the existing Seon final-report validator on that path, just as the canonical writer does; Datahike's attribute checks alone do not enforce Seon's entity rules. The inspected precedent is `test/seon/turn_work_test.clj:131–163`, which uses `write-report-validator` and `:datahike/validate-report`.

Proposed ordinary author code (the small public `db/with` seam is a target, not installed):

```clojure
(defn speculative-change-keeps-its-ancestor
  {:seon.test/world 'example.worlds/one-note
   :malli/schema [:=> [:cat :seon.db/database-value] :nil]}
  [before]
  (let [report (db/with before
                {:tx-data [[:db/add [:my.note/id "subject"]
                            :my.note/content "renamed"]]})]
    (is (not (:seon.error/at report)))
    (is (= "original" (:my.note/content
                         (db/pull before [:my.note/content]
                                  [:my.note/id "subject"]))))
    (is (= "renamed"
           (:my.note/content
             (db/pull (:db-after report) [:my.note/content]
                      [:my.note/id "subject"])))))
  nil)
```

The note already exists through its canonical writer; this transaction changes one declared content field. The concrete API recommendation is to expose the existing validated transaction computation at `seon.db`, preserving its complete error union and projection; do not put a copied validator in `test-support`. Testing a change may legitimately author transaction operations; the prohibited entity maps are **duplicated production construction**, not every literal map used as data. Explicit invalid-input tests still need invalid data.

An immutable trial performs **zero branch/open/retire operations**. Its ms cost is transaction delta/index/validation work; target **<1 ms for a tiny delta**, unmeasured and not constant in database or validation work. Pure reads need no `with` at all. A speculative value has no committed branch head: if writer tests need the same world, prepare a committed version once through the real writer. Do not try to branch from an uncommitted `:db-after` or silently equate it with its ancestor's commit id.

This proves transformation and validation, not durable publication, listeners, writer serialization or recovery. Those tests request a connection and use real transactions. Transaction functions with external effects are not made pure by `with`; they must be excluded from the speculative path by their ordinary effect declarations.

## 6. Prepare before executing, using the same lifecycle

No extra author syntax: all examples above already contain the required inputs.

1. `select` resolves required members using current reverse reach and each member's evidence. Reuse valid green first; reused bodies need no world preparation.
2. The ordinary admission owner compiles each changed signature once, resolves world/effect dependencies and groups required preparations by content.
3. Prepare every unique world needed by this admitted batch, then acquire all writer branches and member SCI forks before starting its bodies. Preparation failures are named member failures, never skipped green.
4. Execute independent bodies in parallel under existing agent execution bounds. Each has separate assertion counters, observations, effect sequence state and actual termination evidence. Preserve namespace fixtures/hooks for unconverted legacy tests; they do not become parallel-safe just because the new path is.
5. Observe actual exit, record each result with its tested world/program/input evidence, release member resources, and unlink through the agent owner. A timeout is not exit. Prepared worlds remain reusable according to retention, not a test cleanup convention.

Pre-preparing **all worlds** does not require unbounded simultaneously open connections. Compute the whole required preparation set, materialize unique reusable worlds, and use existing admission batches for per-member handles when resource bounds require it. This reconciles advance preparation with memory limits without inventing a second scheduler. Preparation and execution time are reported separately **and** included in the request bound; moving slow work before a stopwatch is not an optimization.

Targets/cost model: reader admission **<1 ms** excluding assertions; bare SCI fork **~0.02 ms planning assumption**, not end-to-end context acquisition; private writer prep **~30 ms planning assumption** plus open/acquire cost. Historical receipts differ (§9). Total preparatory work is O(unique missed worlds + writer members), execution follows the bodies' actual work; the parallel critical path cannot be shorter than its longest body and any serialized writer/roster work. Measure p50/p95 and retained heap, not just one average.

Delete the second fixture child, repeated world construction and serial waiting between independent bodies. Do not delete actual-exit handling, per-member recording, cross-request/adoption exclusion or the platform host. Branches isolate datoms; they do not isolate JVM Vars, store-wide blobs/GC, provider credentials or arbitrary shared SCI objects.

## 7. Evidence reuse and prior art worth keeping

No author cache annotations:

```clojure
(deftest addition-is-commutative
  (is (= (+ 2 3) (+ 3 2))))
```

That definition's content and declared dependencies determine whether its previous completed green is reusable. `seon.test/select` (`src/seon/test.clj:576`) and `reach-digests` (`src/seon/test/runner.clj:898`) already own this. Extend their inputs with world, constructor, effect response, seed and capability-definition content. Do not build a separate world-test reach index. The test's source alone is insufficient; changed schema, dynamic invocation declarations and resource inputs matter. Missing evidence executes/refuses conservatively. Observed calls remain diagnostic, not permission to exclude tests. Cost is changed/reached graph edges plus candidate evidence, target sub-second for a focused request; no milliseconds measured here. Reuse removes all body and setup work for that member, not its required evidence validation.

| Prior art / primary source | Borrow | Do not import |
|---|---|---|
| [clojure.test source](https://github.com/clojure/clojure/blob/master/src/clj/clojure/test.clj) | Ordinary `deftest`, `defn`, `is`, `testing`, reports and composable functions | A new assertion language or secretly parameterized standard macro |
| [Malli function schemas](https://github.com/metosin/malli#function-schemas) | `:=>`, `:cat`/`:catn`, named schemas, existing compiled validators | General-purpose DI or duplicated schema traversal |
| [test.check guide](https://clojure.org/guides/test_check_beginner) and [defspec integration](https://clojure.github.io/test.check/clojure.test.check.clojure-test.html) | Generated data, seeded replay and shrinking with normal test reporting | A custom property engine or branch per immutable trial |
| [Kaocha metadata filtering](https://cljdoc.org/d/lambdaisland/kaocha/0.0-138/doc/4-focusing-and-skipping) | Familiar named/namespace/metadata selection and readable failure output; cited historical API | Another runner, lifecycle or selection authority; filtering is not proof of safe parallelism |
| [Datomic `with`](https://docs.datomic.com/client-api/datomic.client.api.html#var-with) | Test hypothetical transactions as immutable values; durable database unchanged | Datomic Client's `with-db` requirements transplanted onto Datahike |
| [DataScript](https://github.com/tonsky/datascript) | Database values as cheap, immutable inputs with structural sharing | Replacing Datahike in tests, which would test a different writer/schema/history implementation |

World properties use the same typed test function. Example after admitting the domain generator and predicate through their ordinary schemas:

```clojure
(defn world-transitions-preserve-invariants
  {:seon.test/world 'example.worlds/one-note
   :malli/schema [:=> [:cat :seon.db/database-value] :nil]}
  [database]
  (let [result
        (clojure.test.check/quick-check
          100
          (clojure.test.check.properties/for-all
            [steps example.generators/valid-transitions]
            (example.invariants/holds-after? database steps))
          :seed 42)]
    (seon.test-support/assert-check! result))
  nil)
```

`example.*` are explicitly domain-owned examples, not new runner APIs. Generate and shrink **requests/forms**, never compiled contexts or raw production rows. The predicate composes canonical transaction construction with validated `with`; each trial begins from the same immutable world. Persist seed, trial count and smallest counterexample in existing result evidence. Pure trials cost O(trials × delta), with zero per-trial branch cost; mutating-agent properties require independently acquired contexts/branches and their measured preparation. Compare one generated case with the real writer to verify the speculative path's intended equivalence. The existing `assert-check!` (`test_support.clj:545`) rejects zero trials and preserves failure detail. This deletes repeated per-trial setup without weakening the subject.

## 8. The whole author skill, and migration

The proposed author instruction is these ten lines; runtime implementers need the detail above, ordinary test authors do not:

1. Write tests through the same checked REPL entrance as your other functions.
2. Use normal `deftest`, `is` and `testing` when no injected input is needed.
3. Otherwise write `defn`, a complete `:malli/schema`, and `:seon.test/world 'ns/world`.
4. Ask for `:seon.db/database-value` to read, `:seon.db/connection` to write.
5. Ask for the declared agent handle, world or capability schema when that is your subject.
6. A world is data containing ordinary agent requests/forms; use canonical writers.
7. Internal values are real; declare external effects to receive their contracted test versions.
8. Assert the subject's behavior and use the supplied identities and explicit inputs.
9. Do not write fixtures, cleanup, cluster names, replacement Vars or fabricated production rows.
10. Run `my.test/run` or a focused `bin/test-check`; a refusal, timeout or missing evidence is not green.

Strict entry stays strict: contracts, analyzed sources and reaching-test checks apply to tests as ordinary declarations; use the existing admission semantics for initial test/subject dependencies, never a test-only waiver or an infinite demand that every test first invent another test. A missing valid admission order is a shared REPL/admission defect to resolve there. Only admitted test definitions execute or merge. A candidate test must run its candidate definition; resolving an older host Var is not proof.

**Proposed refusal enforcement:** use the existing analyzer/admission boundary to name resolved uses of `with-redefs`, root mutation, raw fixture/store creation, cluster lookup and unauthorized external effects. Use producer contracts to reject invalid inputs and missing custody. Literal map syntax alone cannot establish that someone fabricated a production row; do not ban all maps or claim perfect provenance detection. Unknown dynamic behavior cannot certify read-sharing. Platform tests intentionally exercising global/store/process mechanisms remain classified by their actual subject, not hidden behind an author escape flag. This enforcement is not claimed installed today.

Migration follows [the root-cause study](red-root-causes-2026-09-23.md), keeping surviving behavior proofs:

| Class | Conversion at the owner | Regression / deletion |
|---|---|---|
| R1: duplicated production declarations (24) | Prepare world forms through real constructors and checked writers; stop before body on refusal | Incremental schema accretion leaves canonical construction valid. Delete hand-built agent/namespace/function rows and repeated seed helpers; no placeholder digests |
| R2: inherited world mistaken for empty (16) | World returns explicit subject refs and scope; readers use them or a before/after comparison | Add unrelated facts and another cluster without changing the subject result. Delete singleton-cluster inference and accidental global counts; a branch is not an empty store |
| R3: bypassed acquisition/arming (13) | Inject acquired database, projection-bearing value and actual agent handle | Real SCI refuses invalid input and sees the correct program. Delete nested fixture acquisition, raw unacquired reads and hand-built contexts; do not restore ambient fallback |
| R4: old contracts/mutable defaults (12) | World fixes render/config/clock inputs and uses current declared error/result families | Assert declared behavior and evidence fields; change a dependency and show reuse invalidates. Delete copied obsolete unions/default assumptions, retain actual refusal and rendering proofs |

These 65 R1–R4 identities are historical evidence, not 65 predicted automatic passes. R5's semantic-equality/reporter defect remains a separate correction. Script mechanical caller conversions once; keep one regression per behavior class and prove the new subject before deleting the old fixture.

## 9. Dependency seams, measurements and limits

| Pinned dependency at inspected HEAD | Source and guarantee | Inputs, recomputation and proportionality |
|---|---|---|
| Clojure `b18d3adc5b5f4d5d0ccea966203fb67a614d5c3d` | `reference-code/clojure/src/clj/clojure/test.clj:624`, `:710`: zero-argument test metadata and normal test invocation | Source form expands once; body work per execution |
| Malli `8725a8cbd9d595f4a970ce53a2eefdbe7211b96d` | `reference-code/malli/src/malli/core.cljc:2515`, `:2600`: `type`, `children`; use held compiled schema and normal validators | Compile on schema/registry-content change; inspect signature O(args), not program-wide per call. Checkout is dirty; no claim all inspected bytes equal pin |
| Datahike `c79cd03a44427ac1734d917c7484c3e529c77716` | `reference-code/datahike/src/datahike/versioning.cljc:212–277`: selected source root, new head/roster, shared indexes; `api/impl.cljc:134`: `with`; `db/transaction.cljc:1224`, `:1295`: final-report validator | Branch work includes roster coordination and attached secondary indexes, not a datom copy; `with` cost follows transaction delta, index updates and validation |
| SCI `fcbd8862800e638dc0f8f5521111f999279cbcd2` | `reference-code/sci/src/sci/core.cljc:345`: env fork with generation; first-party `fork-cluster-ctx` at `src/seon/sci/eval.clj:2732` | Held context in, fork per independently mutable context; no reindexing. Mutable objects are not deep-copied |

Upstream verification matters: [Datahike's upstream branch implementation](https://raw.githubusercontent.com/replikativ/datahike/main/src/datahike/versioning.cljc) has branches, but its GC/secondary-index details differ from this pin. [Upstream SCI fork](https://raw.githubusercontent.com/babashka/sci/master/src/sci/core.cljc) creates an env atom and documents new-Var separation; Seon's inspected generation-based redefinition isolation is **fork-specific**, not an upstream guarantee. The inspected `:call-preparation-hook` at local `core.cljc:310` is also a maintained-fork seam; the upstream source retrieved here does not establish that hook. Final-report validation wiring is also a maintained dependency extension; do not advertise it as generic Datomic/DataScript validation. These seams justify composition, not importing every local guarantee into a library claim.

**No new branch benchmark was run:** branching writes runtime state and this assignment authorizes read-only probes. Owner-supplied ~30 ms branch and ~0.02 ms SCI fork are planning figures. The [tests-as-agents data pack](tests-as-agents-data-pack-2026-09-22.md) records SCI fork **0.00123 ms**, and §9 records historical branch **74.65 ms** plus open **25.62 ms**. The [fixture study](../../prds/steward-platform/research/test-system-fork-2026-09-23.md) records later fixture p50 **37.0355 ms**, but first acquisition **4,647.819208 ms**, including projection **3,995.958667 ms**. These are different operations and revisions; none establishes today's full acquire/arm/record/release latency. Above-one-second acquisition is a defect to remove at its owner, not a bound to copy.

`bin/seon status` returned in **58.3 ms**; MCP status was reachable with no missing layers, but reported 16 error signatures, 34 errored receipts and one failed run. Historical profile maxima included `seon.test/run` **97,608 ms**, `seon.test/member-result` **14,790 ms**, `seon.schema/call-with-projection` **14,690 ms**, and `seon.test-support/with-database` **11,820 ms**. **Directive for their owning lanes: explain and fix the non-sub-second work before treating the proposed preparation targets as achieved.** These cumulative observations are not timings of this research and contain no proven causal attribution. This docs-only lane neither repairs nor excuses them.

Runtime source was archive `ce73846828a5cc32798ef630b5a574f777646f30`, hook publication **off**. Both exploratory probes used MCP JVM mode, explicit root `/Users/sean/src/seon`, cluster `default`, private session `test-simplicity-research-20260923`, throwaway namespace `research.test-simplicity-20260923`, `read_only true`, timeout 3,000 ms. No default Var was redefined and no test body ran.

Exact probes:

```clojure
(let [started (System/nanoTime)
      conn (seon.cluster.boot/connection "default")]
  {:connection? (some? conn)
   :deftest-expansion
   (macroexpand-1 '(clojure.test/deftest example [db]
                    (clojure.test/is (some? db))))
   :run-test-arglists (:arglists (meta #'seon.sci.eval/run-test))
   :elapsed-ms (/ (- (System/nanoTime) started) 1e6)})

(let [started (System/nanoTime)
      expanded (macroexpand-1
                 '(clojure.test/deftest example [db]
                    (clojure.test/is (some? db))))]
  {:test-body (:test (meta (second expanded)))
   :schema-type (malli.core/type [:=> [:cat :int] :boolean])
   :input-type (malli.core/type
                (first (malli.core/children [:=> [:cat :int] :boolean])))
   :elapsed-ms (/ (- (System/nanoTime) started) 1e6)})
```

Complete relevant returned values: first `{:connection? true, :deftest-expansion (def example (clojure.core/fn [] (clojure.test/test-var (var example)))), :run-test-arglists ([request]), :elapsed-ms 0.101375}`; second `{:test-body (clojure.core/fn [] [db] (clojure.test/is (some? db))), :schema-type :=>, :input-type :cat, :elapsed-ms 0.049208}`. Each MCP envelope reported `ret`, **2 ms**, `windowed? false`, runtime `clj`, cluster alive; no error event. The second probe only inspects schema structure, not injection, schema admission or speculative execution.

## 10. Smallest landing and proof boundary

The minimum set is **typed ordinary functions + immutable prepared worlds + existing acquisition/effect/evidence owners**. Sharing, speculative trials and advance preparation are consequences of the values and declared capabilities; they do not need new author modes. Reject a custom parameterized `deftest` and general DI graph as extra mechanisms. The world schema/supplier extensions and defn-test admission must be reviewed together before implementation; this bounded research lane launches no subagents.

Implement through the agent seam first, then convert the runner caller: (a) strict admission and fixed-arity resource injection; (b) prepared immutable/committed worlds and external-effect declarations; (c) remove nested fixtures, enable proven independent parallel execution and extend evidence inputs. These are research recommendations, not another lane's rewritten implementation schedule. Any slice approaching hundreds of lines stops for a smaller composition review.

Required implementation probes: typed reader gets the captured world; writer gets a private descendant; two readers share identical immutable input without writable custody; read-declared write refuses; external completion uses the supplied contracted function; internal writer is real; world preparation refusal prevents execution; equal content builds once; changed constructor/schema/effect/seed invalidates; unrelated recording does not; speculative and actual transaction derivations agree; body/child termination precedes release; reporting remains member-specific under concurrency; retained heap does not grow with completed requests. Measure preparation, acquire, body, record, release and end-to-end separately, including first use. Any missing proof remains unknown.

Owned path: this document only. Net source **0**, test **0** lines. No source reload, publication, branch mutation, paid effect, `seon.test/run`, gate, platform proof, worktree, foreign session interaction or push. A test request would write evidence and exceeds this assignment's read-only runtime boundary; it is not necessary to validate a documentation-only research result. No HEAD-load or runtime implementation pass is claimed. Foreign edits and the archived JVM remain explicit evidence boundaries, not reasons to stop research. Documentation validation and path-only commit are the landing proof; commit identity is supplied in the final handoff.

Documentation checks: all 14 Markdown links were inspected (relative file targets exist); no trailing whitespace. A third read-only MCP request read, without evaluation, all ten Clojure blocks with `*read-eval* false`: forms per block `[4 1 1 1 1 2 1 1 1 2]`, **0.177958 ms**, envelope **2 ms**, `ret`, unwindowed, alive. This proves reader syntax only; proposed names and runtime semantics are not compiled or tested. Net source/test lines remain zero.
