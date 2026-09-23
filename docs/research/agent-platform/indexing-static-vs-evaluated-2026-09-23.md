---
type: research
status: current
created: 2026-09-23
---

# Static versus evaluated indexing, and devices

**VERIFIED (source): G6 needs no indexer repair for an ordinary function edit.**
Seon compares owned attributes, retracts only changed attributes, and asserts only
changed attributes plus identity. An unchanged `:seon.fn/doc` retains its assertion
transaction. `retractEntity` is for removed identities, not edited functions
(`src/seon/fn.clj:3211–3287`; `src/seon/program.cljc:1282–1349`).

**VERIFIED (source + native probes): G2 is a missing projection, not a requirement
to evaluate all documentation.** clj-kondo emits a data `def`'s docstring, metadata,
and location, but not its initializer value. Seon's file indexer recognizes literal
constants and stores source; the SCI admission path does not turn arbitrary map
values into program facts (`reference-code/clj-kondo/src/clj_kondo/impl/analysis.clj:87–124`;
`src/seon/fn/analyzer.clj:485–519`; `src/seon/fn.clj:663–689`;
`src/seon/sci/eval.clj:426–457`).

**VERIFIED (native probes): G5's analysis and docstring extraction run without a
JVM on this Mac. UNVERIFIED (recommendation): author where analysis is supported;
deliver admitted facts and rendering capabilities to consuming devices.** This
avoids making every device a compiler host. It does not establish that the current
Seon runtime runs on mobile or in a browser (`src/seon/sci/eval.clj:189–234`).

## Evidence boundary

**VERIFIED (repository inspection):** branch `refactor/agent-platform`, inspected
HEAD `db0ebf75ef740f7d5f0588e73f93106472a2a6f5`. Dependency checkouts:
Datahike `c79cd03a44427ac1734d917c7484c3e529c77716`;
clj-kondo `57252e07975710aa579b24f0d1b2b1e04195caa2`;
babashka `12872c10c22206170ec6eeb0130e2d9c8d2ce672`.
These are `git submodule status` results, not inferred dependency versions.
The installed binaries report clj-kondo `2026.07.24` and bb `1.12.212`;
`file` identifies both as Mach-O arm64 executables. Source and binary revisions
are therefore distinguished throughout.

**VERIFIED (scope):** the draft, laws, toy schemas/source/tests/analysis and first
wiki page were read. Only this deliverable was edited. Native probes used
`tmp/wiki-toy` as cwd, disabled kondo caches and used `/dev/null` as configuration
location; no JVM, live Seon operation, system tests, reset, publication or push ran.
**UNVERIFIED:** installation on default, live publication timings, Datahike runtime
behavior under this exact checkout, and device deployments. Source proof below is
not installed proof. The pre-existing dirty `reference-code/http-kit` was untouched.

## G6 — what actually changes a datom's transaction

**VERIFIED (source): publication retains the previous database history.**
`seon.cluster.source/publish!` checks input digests and skips unchanged publication
at `src/seon/cluster/source.clj:554–584`. Otherwise it branches from the published
commit, opens that branch and supplies its previous database to population
(`:592–623`). `seon.cluster` forwards that database to `seon.fn/index!`
(`src/seon/cluster.clj:1713–1729`). Publication promotes the scratch database with
the previous published commit as parent (`src/seon/cluster/source.clj:650–657`).
It does not rebuild all program rows in a new empty store on each edit.

**VERIFIED (source): reconciliation decides the write against the writer's world.**
`index!` submits `reconcile-call` in one transaction, with an optional expected-head
guard (`src/seon/fn.clj:3647–3659`). `reconcile-tx-in`:

- **VERIFIED:** computes removed identities separately (`src/seon/fn.clj:3222–3233`).
- **VERIFIED:** skips equal normalized rows (`src/seon/fn.clj:3243–3257`).
- **VERIFIED:** selects changed attributes plus identity for surviving rows
  (`src/seon/fn.clj:3262–3267`).
- **VERIFIED:** takes only the retraction part of `exact-replacement-tx-in`
  (`src/seon/fn.clj:3268–3276`). That helper compares owned attribute values and
  retracts only changed, previously present attributes
  (`src/seon/program.cljc:1282–1299,1316–1334`).
- **VERIFIED:** uses `:db/retractEntity` only for the removed identities/citations
  computed above (`src/seon/fn.clj:3283–3287`).

**VERIFIED (source): “only changed datoms” needs qualification.** The granularity
is an attribute: a changed cardinality-many attribute is retracted as a whole and
reasserted, including any surviving members. This does not affect an unchanged
scalar docstring (`src/seon/program.cljc:1323–1334`). Schema replacement is another
exception: affected data is dropped and its source files re-derived
(`src/seon/cluster.clj:1680–1688,1733–1748`). Do not use per-edge assertion age as
original authorship age without accounting for those operations.

**VERIFIED (dependency source): idempotent assertions preserve current datoms.**
Datahike `transact-report` compares the current value for cardinality-one upserts;
for cardinality-many assertions it checks whether the EAV already exists. Only
`changed?` updates the database and effective transaction data
(`reference-code/datahike/src/datahike/db/transaction.cljc:586–618`). Attempts
remain visible internally to validation; the public report gets effective datoms
at `:1286–1289`. A retract followed by reassertion is different: the current datom
is absent when the add runs, so its assertion becomes new (`:603–618`).

**VERIFIED (dependency source): unchanged facts do not imply zero transactions.**
Datahike advances `:max-tx` even after an empty transaction (`:1290–1291`). Seon's
publication digest shortcut avoids the normal unchanged build, but `index!`'s
reconciliation path still calls transact (`src/seon/cluster/source.clj:583–584`;
`src/seon/fn.clj:3650–3659`). The draft's “re-indexing identical content adds no
transaction” is stronger than the indexer itself guarantees. What G6 needs is
unchanged **attribute assertion transactions**, not an unchanged database basis.

**UNVERIFIED (query proposal):** for one selected function in one branch database,
join `[?e :seon.fn/source ?source ?code-tx]` and
`[?e :seon.fn/doc ?doc ?doc-tx]`, then compare `?code-tx > ?doc-tx`.
Return “definition changed since prose changed,” with missing docs a separate
finding. Bind the selected identity first; target cost is indexed lookup plus two
attribute reads per selected entity, not a full history scan. No live query or
query-plan timing was performed.

**VERIFIED (source), with UNVERIFIED (semantic interpretation):** source stores the
whole definition and the definition digest includes authored doc/source fields
(`src/seon/fn.clj:664–689`; `src/seon/program.cljc:361–394`). Therefore neither is a
body-only semantic clock. Formatting, metadata and resolver-context changes can
produce alerts; unchanged correct prose can remain flagged indefinitely. Same-tx
prose edits suppress the alert without proving correctness. The query is a review
candidate generator. Compare transactions only within the selected lineage;
export/re-import and branch adoption must be separately probed before promising
preservation of authorship chronology.

| G6 option | Guarantee, cost and sacrifice — all UNVERIFIED estimates |
|---|---|
| **A — recommend: existing assertion-age query** | 2–4 engineering hours for query, missing-doc treatment and focused publication regression; zero indexer changes. Flags potential drift; cannot record “reviewed and unchanged.” |
| B — review evidence keyed by definition and doc content | 1–2 days for one declared evidence shape, writer and tests. Records deliberate approval without fake prose edits; adds a real review workflow. |
| C — semantic body digest | 2–4 days for a precise definition of executable content and producer/tests. Reduces formatting/prose noise; still cannot prove prose correctness and creates another derivation. |

**UNVERIFIED (proposed verification):** publish baseline; body-only edit; unrelated
file edit; doc edit; removal/readdition. Check the doc and source datom transactions
and effective report at each step on a disposable store branch. Also probe schema
replacement and selected-cluster adoption. If a different installed revision
retracts unchanged doc attributes, the smallest repair is the changed-attribute
selection already present here; no timestamp field or transaction stamp is needed.

## G2 — task declarations and evaluated values

**VERIFIED (dependency source):** `analyze-def` separates the optional docstring
from the initializer and analyzes the initializer expression
(`reference-code/clj-kondo/src/clj_kondo/impl/analyzer.clj:1852–1906`). `reg-var!`
exports a selected field set, including `:doc`, and optional user `:meta`; it
exports no initializer/value field (`reference-code/clj-kondo/src/clj_kondo/impl/analysis.clj:87–124`).
Seon's adapter retains that same distinction (`src/seon/fn/analyzer.clj:120–128`).

**VERIFIED (native probe):** the toy task at
`tmp/wiki-toy/test/toy/workout_test.clj:21–25` produced this selected output:

```clojure
{:ns toy.workout-test :name accept-rest-days
 :defined-by clojure.core/def :meta nil :row 21 :end-row 25
 :doc "A rest day is a real entry: the schema allows `:toy.workout/kind :rest` with no\n  sets, but `log-entry` throws on it, so athletes cannot record recovery."}
```

**VERIFIED (native probe):** no `:seon.task/ns` or `:seon.task/delivers` value was
present in that var-definition record. The adjacent test carried
`:meta #:seon{:task (quote accept-rest-days)}`. Its bare local target was not
qualified in the metadata payload: static metadata is reader-shaped data, not the
result of runtime evaluation. Use qualified symbols and declared normalization;
do not assume kondo metadata reproduces the Clojure compiler's syntax quote.
The source producer is `reference-code/clj-kondo/src/clj_kondo/impl/analysis.clj:119–121`.

**VERIFIED (source): literal data does not inherently need evaluation.** Seon
already parses kondo nodes and recognizes recursively literal values and quoted
data (`src/seon/fn/analyzer.clj:485–519`). Recognized constants qualify for source
rows and get `:seon.fn/constant?`, source and doc fields
(`src/seon/fn.clj:330–334,663–689`). **UNVERIFIED:** extending this to extract a
restricted literal task initializer could work without SCI, but it is an explicit
new projection; arbitrary initializer expressions still require evaluation.
The toy's exact syntax-quoted initializer classification was not run through Seon.

**VERIFIED (source): execution is already part of SCI evaluation, but value
persistence is selective.** `eval!` calls `sci/eval-form` and preserves the actual
result (`src/seon/sci/eval.clj:3425–3482`). Schemas evaluate in a registration delta
and derive their declarations from the registered value (`:2801–2845`);
`definition-row` analyzes changed interns only when they are tests or function
values (`:426–455`). Reader-owned rows cover schema/namespace declarations
(`:328–343`). Thus an ordinary task-map `def` may exist in scratch SCI, but its
map is not automatically an admitted task entity. Source for a constant is also
not equivalent to task attributes being queryable as facts.

**VERIFIED (probe): metadata does have a prose home.** A stdin probe using
`(deftest ^{:doc "why" :seon/task {:seon.task/ns 'probe
:seon.task/delivers #{'probe/f}}} t (is true))` returned top-level `:doc "why"`,
`:test true`, and the full metadata map. **VERIFIED (source):** today's Seon test
row constructor retains selected markers/edges/subject but neither generic task
metadata nor doc (`src/seon/fn.clj:629–661`). Static capability is not installed
storage support. Quoted symbols in the probe remain `(quote ...)` forms in analysis.

**VERIFIED (source): EDN initialization already has an admission/reconciliation
route.** `config/default.edn:511` starts initialization rows; the reader admits
maps, declared keys, matching attribute values and entity identity
(`src/seon/config.clj:255–336,356–374`). Config application includes initialization
in desired facts, compares them, and recomputes through its writer callback
(`src/seon/config.clj:642–709`). Generic reconciliation skips a converged write
(`src/seon/reconcile.cljc:418–445`). New task schemas and lifecycle rules remain
necessary; existing support for EDN rows does not prove task admission is installed.

| G2 option | Static analysis / drift / familiarity / devices | Price, guarantee and sacrifice — UNVERIFIED estimates |
|---|---|---|
| **A — EDN task rows; recommend initially** | Entire payload readable without evaluation; reconciliation provides the existing facts path. Symbols link separate tests; prose is a declared data field. Ordinary EDN on author/consumer. | 0.5–1 day adapter/validation proof after common task schemas exist. No initializer evaluator. Sacrifice docstring syntax and immediate test adjacency; require link/digest checks for drift. |
| B — literal metadata on tests, with `:doc` | Kondo reports payload and prose together; adjacency reduces omission, not semantic drift. Standard metadata; symbols require reader-form handling. Static authoring is device-friendly. | 1–2 days projection/normalization/reconciliation tests. Good for one test's obligation; multi-test tasks need one canonical declaration and links to it. Avoid duplicating task maps on every test. |
| C — evaluated task Var | Doc/source statically visible, computed value unavailable until evaluation. Familiar `def`; dependencies and external inputs can change value without source edits. Device needs appropriate evaluation host or authored artifact. | 2–4 days admission/value validation/provenance and repeatability tests. Most expressive; creates a new evaluator-to-facts boundary. Use existing SCI and explicit inputs; never load an entire test namespace just to index prose. |

**UNVERIFIED (recommendation):** choose one authoritative declaration producer;
do not support all three initially. EDN is the smallest current composition.
If docstring ergonomics win, constrain task Vars to literal data and extend the
existing parsed-facts projection instead of admitting arbitrary execution merely
to read a task. Task definitions and live task instances also need a declared
partition: the current laws classify tasks as non-merging data (`AGENTS.md:100–103`),
while the design draft proposes task-defined deliverables
(`docs/research/agent-platform/design-namespace-agent-context-draft-2026-09-23.md:34–52`).
Do not silently make a runtime task instance into a function/program row.

## G5 — native analysis versus an on-device runtime

**VERIFIED (source + upstream):** clj-kondo is a static analyzer compiled with
GraalVM (`reference-code/clj-kondo/README.md:83–88`;
[upstream README](https://github.com/clj-kondo/clj-kondo)). Babashka embeds SCI and
is itself native (`reference-code/babashka/README.md:95–96,337`;
[upstream README](https://github.com/babashka/babashka)). Extracting docstrings is
part of analysis; reading the EDN result needs no JVM or Markdown parser.

**VERIFIED (source + probe):** bb can call clj-kondo as a pod. Its documented
`pods/load-pod "clj-kondo"` exposes `run!`
(`reference-code/clj-kondo/README.md:327–346`;
[upstream pod instructions](https://github.com/clj-kondo/clj-kondo#babashka-pod)).
This is a native subprocess boundary, not clj-kondo interpreted inside SCI:
the binary selects pod mode from `BABASHKA_POD`
(`reference-code/clj-kondo/src/clj_kondo/main.clj:128,156`), and its pod dispatches
EDN arguments to compiled `clj-kondo/run!` over bencode stdio
(`reference-code/clj-kondo/src/pod/borkdude/clj_kondo.clj:19–41,54–84`).

**VERIFIED (source): the SCI relationship also runs in the opposite direction.**
Kondo executes analysis hooks in SCI
(`reference-code/clj-kondo/doc/hooks.md:275–284`;
[upstream hooks](https://github.com/clj-kondo/clj-kondo/blob/master/doc/hooks.md#macroexpand)).
**UNVERIFIED:** interpreting the entire analyzer in an arbitrary SCI context;
no such seam was established. The analyzer's JVM dependency surface is visible
at `reference-code/clj-kondo/src/clj_kondo/core.clj:1–15`. Hosting a compiled
function in SCI or invoking a pod does not prove portable source interpretation.

**UNVERIFIED (recommended split):** authoring does analysis, schema/task admission,
tests and production of versioned facts. A read-only consumer needs the selected
facts, query capability, renderers and their required runtime—not source parsing.
A device that edits code or runs self-improving agents also needs admission/test
execution locally or through an explicit authoring service. “Query + render only”
is consequently a product constraint, not a universal device architecture.
Current JVM bindings (`src/seon/sci/eval.clj:189–234`) are not a mobile port.

| G5 option | Guarantee, cost and sacrifice — all UNVERIFIED estimates |
|---|---|
| **A — recommend: author-side indexing, consumer facts** | 1–2 days to specify/export/verify one bounded read-only fact slice; no full-device implementation included. No analyzer on consumer. Sacrifice offline code authoring; device query/render integration still needs proof. |
| B — native CLI/pod on supported desktop devices | 1–2 days packaging and capability/error checks for one OS/architecture, plus existing admission host. Local parsing demonstrated here; binary/process support required. Mobile/browser support remains unproved. |
| C — whole authoring runtime on each target | 1 day feasibility spike per target before any implementation estimate. Prove parser, SCI bindings, database, contracts and test execution. Potential offline autonomy; largest scope and no measured portability guarantee. |

## Reproducible measurements and landing limits

**VERIFIED (native measurements):** Darwin arm64, supplied toy's three input files,
cache disabled, no Seon load. One fresh CLI process took 0.01 s wall and 43,270,144 B
maximum RSS; kondo reported 9 ms. Exit 2 was one unused `clojure.test/testing`
warning (`tmp/wiki-toy/test/toy/workout_test.clj:2`), not a parse failure.
The pod session took 0.05 s wall, bb maximum RSS 52,609,024 B (not aggregate child
memory): load 8.550 ms; requests 16.328, 6.811, 6.593 ms; analyzer times 8, 4, 5 ms.
Each request reported three files, zero errors, one warning and five Var docstrings.
Reading existing `kondo.edn` in bb took 1.810 ms internally, 0.01 s process wall,
42,139,648 B maximum RSS; it contained nine var-definitions and two namespace-definitions.

**VERIFIED (reproduction commands):** run from `tmp/wiki-toy`; output is stdout,
not a new artifact. These inline scripts preserve the probes within the sole
permitted deliverable. The first pod attempt had a missing closing parenthesis;
it failed at bb parse time and was corrected before the successful measurements.

```sh
/usr/bin/time -l clj-kondo --lint src test schemas --cache false \
  --config-dir /dev/null --config \
  '{:output {:format :edn} :analysis {:var-definitions {:meta true} :namespace-definitions {:meta true} :keywords true :symbols true}}'
/usr/bin/time -l bb --config /dev/null -e '
(require (quote [babashka.pods :as pods]))
(let [t (System/nanoTime) p (pods/load-pod "clj-kondo")]
  (require (quote [pod.borkdude.clj-kondo :as k]))
  (println :load-ms (/ (- (System/nanoTime) t) 1e6))
  (dotimes [i 3]
    (let [t (System/nanoTime)
          r ((resolve (quote k/run!))
             {:lint ["src" "test" "schemas"] :cache false :config-dir "/dev/null"
              :config {:analysis {:var-definitions {:meta true}
                                  :namespace-definitions {:meta true}
                                  :keywords true :symbols true}}})]
      (prn {:run i :ms (/ (- (System/nanoTime) t) 1e6)
            :summary (:summary r) :findings (:findings r)
            :docs (count (filter :doc (get-in r [:analysis :var-definitions])))})))
  (pods/unload-pod p))'
/usr/bin/time -l bb --config /dev/null -e '
(require (quote [clojure.edn :as e]))
(let [t (System/nanoTime) a (:analysis (e/read-string (slurp "kondo.edn")))]
  (prn {:read-ms (/ (- (System/nanoTime) t) 1e6)
        :sections (into {} (map (fn [[k v]] [k (count v)]) a))
        :docs (mapv :doc (filter :doc (:var-definitions a)))}))'
```

**UNVERIFIED (scale limits):** toy times are not whole-repository bounds, device
benchmarks, admission costs or publication measurements. Markdown code-block
extraction, Datahike/Seon execution in bb, and a mobile build were not tested.
No result requires a parser replacement or a new scheduler/cache. Recommended
production delta for G6 is zero; G2/G5 prices exclude the shared task schema and
runtime work. **VERIFIED (landing scope):** one research Markdown file; net
production/test source change 0/0 lines. Commit identity is reported in the final
handoff; this file is released after that documentation-only commit.
