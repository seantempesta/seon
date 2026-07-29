---
type: research
status: active
tags: [research, corpus]
---

# Code graph end to end

## Scope and method

This is the dependency-mining record for the fresh-tree N5 corpus round trip.
It separates mechanisms by historical revision instead of combining them into
one system that never existed. Production implementation is out of scope.

The investigation follows one state transition:

```text
publish/build
  -> bootstrap ancestor
  -> cluster fork + initialization
  -> acquire one database value at a basis
  -> evaluate namespace-changing forms
  -> exact-set/upsert/retract program facts
  -> crash
  -> reopen + acquire the newer committed graph
```

## Executive verdict

Seon did have a real self-indexing program database, but not as one unchanged
implementation. Three generations must remain separate:

1. The June/early-July CLJS build indexed compiler-known namespaces and vars,
   then the pod's self-host evaluator maintained agent-authored declarations.
2. By July 23-24, build output froze program rows and ordered initialization
   pages; database open consumed those pages, while authored-code publication
   and basis acquisition still lived in the pod.
3. On July 26, the build producer moved to the JVM and an applied, closed
   Datahike directory could be cloned as an at-rest template. This was the last
   pre-deletion initialization mechanism, not the current branch ancestor.

The owning source boundaries for those generations are respectively
`src-old/seon/client/indexing.clj:23-108` plus
`src-old/seon/client.cljs:892-1102`,
`src-old/seon/db/program.clj:321-484` plus
`src-old/seon/db/writer.clj:1629-2209`, and
`script/seon/dev/program_indexer.clj:49-576` plus
`c669c2f6b:script/seon/dev/cluster.clj:42-150,382-437`.

The owner's remembered invariant is therefore source-supported: at the last
revision before the pod evaluator was deleted, build facts and later authored
facts shared the same `:seon.ns/name`, `:seon.fn/sym`, and
`:seon.schema/key` identities; redefining a declaration changed those facts,
and restart acquired the database's newer authored program. The important
qualification is that the old acquisition path reconstructed and evaluated
declaration source inside the CLJS self-host compiler. That made resume
incremental relative to the compiled package, but it is exactly the old-engine
shape the fresh JVM/SCI design must not revive. The exact runtime proof and its
post-deletion gap are developed below
(`333b21b574cc:src/seon/eval.cljs:2281-2602,2828-3649`;
`e05a6b8edde:src/seon/execution.cljs:609-922`).

## Dependency ledger

| dependency or Seon mechanism | selected revision / coordinate | source read for this audit | question it answers |
|---|---|---|---|
| SCI | submodule `8fac6e88f32d53a5fd82ebe80640881e317b84fd`; root `deps.edn` uses the local root | `reference-code/sci/src/sci/core.cljc`, `reference-code/sci/src/sci/impl/analyzer.cljc`, and the fresh reader owner | parse-time namespace state and durable var installation constraints |
| Datahike | maintained submodule `9a7a9ef10a954c32075e60d929f9101a9ac8abd9`; root `deps.edn` uses the local root | `reference-code/datahike/src/datahike/db/transaction.cljc`, `writing.cljc`, and `writer.cljc` | identity upsert, retraction, serialization, transaction reports, and bases |
| ClojureScript analyzer | historical dependency `org.clojure/clojurescript`; vendored source revision `946d75f3483c0c8e784e6668bff2c71a25619a77` used to verify semantics | `reference-code/clojurescript/src/main/clojure/cljs/analyzer.cljc`; historical `seon.client.indexing` and `seon.eval` call sites | which historical build indexer enumerated compiled namespaces/vars and how the self-host compiler installed them |
| ClojureScript self-host evaluator | historical CLJS `1.12.145`; the vendored `1.12.41` evaluator/analyzer/compiler has the same inspected `eval-str` and async seams | `reference-code/clojurescript/src/main/cljs/cljs/js.cljs:810-847,1138-1164`, `reference-code/clojurescript/src/main/clojure/cljs/analyzer.cljc:2110-2121,2325-2341`, `reference-code/clojurescript/src/main/clojure/cljs/compiler.cljc:945-959` | what compile state recorded, what emitted into the JS runtime, and why acquisition reconstructed current source rather than restoring an opaque compiler snapshot |
| Malli | `0.20.0` in root `deps.edn`; vendored source `80138076960e7820523b4cb932c5b5d1936d4e7f` | first-party `m/schema -> m/form -> pr-str` producer sites plus `reference-code/malli/` | canonical contract and schema-form projection |
| old program producers | repository history | `script/seon/dev/program_indexer.clj`, `src-old/seon/db/program.clj`, `src-old/seon/program/edge.cljc` | build-time facts and exact-set transaction data |
| initialization-pages protocol | repository history | `src-old/seon/db/protocol.cljc` plus its producer and writer consumer | page order, phases, validation, and commit handoff |
| bootstrap ancestor / template store | `c669c2f6b` and surrounding commits | historical source plus fresh `seon.cluster.ancestor` | when indexing moved out of cluster creation |
| eval publication / acquisition | repository history | owning eval, index, database, and SCI namespaces to locate | how runtime definitions became durable and later callable |

The JVM indexer did **not** use tools.analyzer. It streamed top-level forms
with Clojure's reader, resolved loaded vars for metadata/contracts, and passed
function forms to Seon's own direct-edge walker
(`script/seon/dev/program_indexer.clj:151-227`,
`script/seon/dev/program_indexer.clj:331-390`). The earlier CLJS producer did use the ClojureScript compiler
analysis environment: its macros read `cljs.env/*compiler*` and delegated
inventory selection to `seon.dev.program-inventory`
(`src-old/seon/client/indexing.clj:23-31`,
`src-old/seon/client/indexing.clj:46-78`,
`src-old/seon/client/indexing.clj:80-108`). Calling both
of these “the analyzer” would erase a real replacement.

## Historical entity and attribute inventory

### `:seon.fn`

| attribute | value / cardinality | producer revision | consumer | semantics |
|---|---|---|---|---|
| `:seon.fn/sym` | unique identity string, card-one | build indexers + eval tee | lookup refs, exact-set reconciliation, acquisition | fully qualified symbol such as `"my.calc/inc2"` |
| `:seon.fn/ns` | ref, card-one | build indexers + eval tee | reverse `:seon.fn/_ns` pull | owning namespace; build used a lookup ref, authored tee used nested identity upsert |
| `:seon.fn/source` | exact form string, card-one | build indexers + successful literal-`defn` tee | context, acquisition/reconstruction | durable declaration source, replaced on redefinition |
| `:seon.fn/source-fingerprint` | digest string, card-one | authored tee | generation checks and graduation | digest of authored source; absent from build rows |
| `:seon.fn/spec` | canonical Malli form string, optional card-one | var metadata projection | schema projection, instrumentation, tool admission | presence is the contract; omission means unspecced and must retract an older value |
| `:seon.fn/schema-error` | string, optional card-one | authored tee | warning/render path | unparseable metadata contract; mutually exclusive with `spec` |
| `:seon.fn/arglists`, `/doc`, `/private?`, `/fn-var?` | strings/booleans, card-one | analyzer or loaded-var projection | context/render/instrument selection | presentation and callable metadata; old design stored false booleans |
| `:seon.fn/execution-tier` | enum, card-one | authored tee | graduation | always `:nursery` for eval-authored rows; obsolete under fresh rulings |
| `:seon.fn/created-at` | instant, card-one | old producers | display/provenance-adjacent consumers | non-semantic wall clock stripped by build exact-set paths |
| `:seon.fn/read-attrs` | qualified-keyword many | authored tee | reactive render selection | legacy literal-read set, exact-retracted before adds |
| `:seon.program.edge/generation` | digest string, card-one | custom edge analyzer | planning/cache | hash of one direct-edge bundle |
| `:seon.program.edge/calls` | **string many, not refs** | custom edge analyzer | reachability/planning | direct qualified call symbols; this revision did not model calls as Datahike refs |
| `:seon.program.edge/read-attributes`, `/written-attributes` | qualified-keyword many | custom edge analyzer | planning/interest derivation | statically visible database dependencies |
| `:seon.program.edge/all-at-basis?` | boolean, card-one | custom edge analyzer | planning | query/entity access not narrowed to specific attrs |
| `:seon.program.edge/uncertainties` | enum many | custom edge analyzer | fail-closed planning | unresolved/dynamic/macro/HOF analysis scars |
| `:seon.program.edge/terminal-refs` | ref many | custom edge analyzer | execution planning | refs to terminal entities, which own effect, generation, and required bindings |

The canonical last-pre-deletion entity shape is declared at
`333b21b574cc:src/seon/schema.cljc:409-464`; the direct-edge attribute
registrations are at
`333b21b574cc:src/seon/program/edge.cljc:8-89`. The distinction between
string-valued call edges and actual terminal refs is source fact, not
terminology: `transition-tx` adds call strings directly but adds terminal
entity refs (`333b21b574cc:src/seon/program/edge.cljc:524-564`).

### `:seon.ns`

| attribute | value / cardinality | producer revision | consumer | semantics |
|---|---|---|---|---|
| `:seon.ns/name` | unique identity symbol, card-one | build indexers, namespace tee, nested upserts | every fn/schema/test owner ref and acquisition | namespace identity |
| `:seon.ns/source` | string, card-one | build indexer or authored namespace/require/unalias transition | namespace reconstruction | latest declaration; final old loader merged effective analyzer edges back into it |
| `:seon.ns/doc`, `/summary` | strings, optional card-one | build source parser | namespace render/context | derived from namespace docstring |
| `:seon.ns/require-edges` | component ref many | build source parser + analyzer-state tee | cold source reconstruction, alias/refer resolution, planning | exact set; old component entities are recursively retracted before replacement |
| `:seon.ns.require/target` | symbol, card-one on component | namespace parser/analyzer projection | loader/topology | required namespace |
| `:seon.ns.require/alias` | symbol, optional card-one | namespace parser/analyzer projection | loader/reader resolution | `:as` or `:as-alias` name |
| `:seon.ns.require/refers` | symbol many | namespace parser/analyzer projection | loader/symbol resolution | referred members |
| `:seon.ns.require/refer-all?`, `/as-alias?` | booleans, optional card-one | namespace parser/analyzer projection | source reconstruction | disambiguate `:refer :all` and load-free reader aliases |

The namespace/require declaration is current in the quarry at
`src-old/seon/ns/source.cljc:14-51`; the final old entity shape is at
`333b21b574cc:src/seon/schema.cljc:474-482`.

### `:seon.schema`

| attribute | value / cardinality | producer revision | consumer | semantics |
|---|---|---|---|---|
| `:seon.schema/key` | unique identity keyword, card-one | canonical build registry + successful runtime registration tee | database schema projection and contract compiler | global schema identity; not per-agent |
| `:seon.schema/form` | canonical Malli form string, card-one | `m/form`/registration snapshot | acquisition and candidate projection | complete ordinary-data schema form |
| `:seon.schema/ns` | ref, optional card-one | build/tee | reverse namespace pull | nested identity upsert for namespaced keys; omitted for single-segment entity keys |
| `:seon.schema/created-at` | instant, optional card-one | old producers | display | non-semantic wall clock |
| `:seon.db.id/generator` | optional card-one enum/data | schema form properties | ID allocation | generator facet derived from a registration |

Canonical row types and the entity shape are at
`333b21b574cc:src/seon/schema.cljc:397-407`,
`333b21b574cc:src/seon/schema.cljc:465-473`. The runtime builder canonicalized the form, derived a generator,
and deliberately used a nested namespace upsert only for namespaced keys
(`333b21b574cc:src/seon/eval.cljs:1900-1924`).

### Eval, source, and provenance edges

| attribute / edge | from -> to | durable role | historical owner |
|---|---|---|---|
| `:seon.agent.turn/evals` | turn -> eval component | makes terminal eval a turn member | `record-eval!` |
| `:seon.eval/id`, `/source`, `/ns`, `/status`, `/ok?` | eval receipt/outcome | identifies exact executed source and terminal state | `start-eval!`, `record-eval!` |
| shared asserting transaction | eval terminal + program rows + exact-set ops | atomic causal connection; there was no separate eval->fn ref | `record-eval!` terminal transaction |
| transaction `:seon.db/user` | tx -> agent/root | author provenance | database transaction context |
| transaction `:seon.db/process` | tx -> boot/repl process | distinguishes build facts from authored facts and protects core | database transaction context; queried by reconciliation/eval guard |
| `:seon.fn/ns` | fn -> namespace | program ownership | indexer/tee |
| `:seon.schema/ns` | schema -> namespace | schema name connection when it exists | canonical row/tee |
| `:seon.ns/require-edges` | namespace -> component edges | structural requires as refs | namespace parser/analyzer projection |
| `:seon.program.edge/terminal-refs` | fn -> terminal facts | actual program dependency refs | direct-edge analyzer |

The atomic terminal transaction places the receipt fence, merged terminal eval
row, and accepted tee data in one `db/transact!`
(`333b21b574cc:src/seon/eval.cljs:2866-2886`,
`333b21b574cc:src/seon/eval.cljs:2967-2985`). Provenance is observable on the source/form datom transaction:
both the core-override query and exact-set reconciler join
`:seon.fn/source`'s transaction to `:seon.db/process`
(`333b21b574cc:src/seon/eval.cljs:3352-3369`;
`src-old/seon/db/program.clj:59-81`).

## Producer and consumer call chains

### Build and initialization

```text
June/early July CLJS generation
  shadow-cljs analyzer env
    -> seon.client.indexing/{first-party-fn-vars,first-party-ns-strs}
    -> seon.client/{var->fn-row,ns-row,index-core!,index-schemas}
    -> complete desired program rows

July 26 JVM generation
  source files (.clj/.cljc)
    -> first-ns-form + namespace-info-from-source
    -> require-closure + loaded vars
    -> reduce-forms
       -> function-row
       -> seon.program.edge/analyze-function
       -> namespace/schema/test rows
    -> seon.db.program/compile-tx-data(empty-db, desired)
    -> compile-initialization-pages
    -> program rows + sources + base projection + page-plan artifacts

fresh database open
  seon.db.protocol/initialization-pages (precomputed only)
    -> one ensure-database request per ordered page
    -> seon.db.writer/initialize-program-page!
    -> schema -> attributes -> program -> initial-data -> completion
    -> complete initialization fact + durable page receipts
```

### Runtime eval and living graph

```text
parsed entry
  -> start-eval! commits :running receipt
  -> snapshot analyzer defs + schema registry
  -> cljs.js/eval-str mutates compile-state/runtime namespace
  -> on failure:
       remove newly-created phantom analyzer defs
       restore schema snapshot
       terminalize receipt as :error, no tee
  -> on success:
       changed-defs + body-only-redefinition rescue
       changed schema keys
       build ns/fn/schema/test rows
       acquire database value + boot provenance + prior ns declaration
       compile:
         identity rows
         omitted optional-field retracts
         exact require-edge/read-attr/direct-edge retract+adds
         candidate schema projection + divergence cache
       db/transact!:
         running->done CAS
         terminal eval row
         complete accepted program transaction data
       publish committed projection

special ns-unmap
  -> acquire symbol/core provenance at database value
  -> terminal eval transaction + retractEntity(fn identity)
                              + retractEntity(test identity)
  -> only after commit remove analyzer def and live JS property
```

### Reopen and acquisition

```text
parent owns immutable database value Dn
  -> prepare-invocations!(Dn, plans)
     -> read target :seon.fn/source at Dn
     -> attach source digest + Dn to invocation
  -> per-agent execution child
     -> open database session, publish committed base, signal ready
     -> receive invocation carrying Dn
     -> acquire-program!(Dn)
        -> page AEVT identities
        -> provenance-filter ns/fn/test source at Dn
        -> pull exact require components at Dn
        -> read schema forms + fn contracts at Dn
        -> canonical sort/dedup + whole-program digest
     -> verify target source digest against Dn
     -> if child has no authored program:
          bootstrap compiler state
          load current authored namespace projections
        if digest unchanged:
          load only additional selected targets
        if digest changed:
          return reload-required
          parent retires child and retries once in a fresh child

unexpected process exit
  -> acquire one current database value
  -> CAS run ownership + mark open run crashed
  -> mark running turn/evals interrupted
  -> later invocation repeats the Dn acquisition above
```

## End-to-end state transition

### Publish and build

The final old-tree build producer identifies `.clj` and `.cljc` files, reads
their first namespace form with `:clj` reader conditionals, and derives
namespace documentation plus structural require edges
(`script/seon/dev/program_indexer.clj:49-90`). It computes a closure from the
AOT roots and recursively discovered first-party requires; it then enlarges
that closure with namespaces owning canonical schema attributes
(`script/seon/dev/program_indexer.clj:92-149`). This last step repaired a real
gap where a schema could mention an attribute keyword without requiring its
owner.

Top-level forms are streamed with `read+string`, retaining both the data form
and its exact source slice (`script/seon/dev/program_indexer.clj:151-190`).
A recognized `defn`/`defn-` row uses the fully qualified symbol string as its
identity, a lookup ref to the owning namespace, exact source, var metadata, and
the canonicalized public Malli contract when available
(`script/seon/dev/program_indexer.clj:192-227`). Namespace rows carry full
source and component require edges (`script/seon/dev/program_indexer.clj:229-241`).
The producer calls Seon's syntax walker for direct calls, database attribute
reads/writes, uncertainty, all-at-basis, generation, and terminal refs
(`script/seon/dev/program_indexer.clj:331-390`); it does not obtain these
edges from tools.analyzer.

Schema rows come from the canonical registration population plus emitted
Datahike facet schemas (`script/seon/dev/program_indexer.clj:464-480`). The
full namespace/function/schema/test population is exact-set compiled against
an empty Datahike value, then used to publish four deterministic artifacts:
sources, program rows, base projection, and page plan
(`script/seon/dev/program_indexer.clj:496-560`,
`script/seon/dev/program_indexer.clj:562-576`). This generation
arrived in `1867980cc` and was repaired through `be9b572ee`, `74b16197e`,
`e6a42bf87`, `f0de5e1dc`, and `bee74572c`; it replaced the CLJS runtime
introspection producer rather than extending it.

### Bootstrap ancestor and cluster fork

The old page artifact is an ordered, fingerprinted population, not a database
snapshot. `compile-initialization-pages` first closes and topologically orders
the schema rows needed for genesis, then emits schema pages, attribute pages,
ordinary program pages, initial-data pages, and a final completion page
(`src-old/seon/db/program.clj:321-412`,
`src-old/seon/db/program.clj:427-484`). The protocol makes the
precomputed page vector mandatory—there is deliberately no runtime
recompilation fallback—and permits an ensure request to carry one page
(`src-old/seon/db/protocol.cljc:1846-1875`). Its schema names the five phases
and requires the common fingerprint, ordinal, count, and page size
(`src-old/seon/db/protocol.cljc:544-605`).

The writer establishes minimal genesis identities and schema, assigns durable
request IDs to genesis and each page, and detects reuse of an ordinal with
different content (`src-old/seon/db/writer.clj:1629-1776`). For program rows it
uses the established identity attributes, retracts all old component require
edges before assertion, and retracts omitted optional contract/generator
values, giving each page exact replacement semantics
(`src-old/seon/db/writer.clj:1821-1872`). Completion reconstructs the desired
identity set from page receipts, refuses an incomplete ns/fn/schema
population, retracts stale boot-origin entities while preserving agent home
namespaces, and only then marks initialization complete
(`src-old/seon/db/writer.clj:1881-1942`,
`src-old/seon/db/writer.clj:2000-2018`). Page order and
predecessor receipts are enforced (`src-old/seon/db/writer.clj:1944-1979`);
`initialize-program-page!` is idempotent by receipt and retries only a stale
database-value race (`src-old/seon/db/writer.clj:2020-2085`). File database
creation is consequently gated on receiving initialization, and opening an
in-progress database without a page fails
(`src-old/seon/db/writer.clj:2127-2209`).

Commit `c669c2f6b` added a separate optimization *after* this logical
initialization. An apply stopped the writer and copied the closed database to
`tmp/seon-template-stores/<application-digest>/<cluster-name>/db`; reset
deleted the target and cloned that directory with APFS clone or Linux reflink
when present
(`c669c2f6b:script/seon/dev/cluster.clj:42-55`,
`c669c2f6b:script/seon/dev/cluster.clj:78-150`,
`c669c2f6b:script/seon/dev/cluster.clj:382-437`). Thus c669's “template store” was an at-rest physical
Datahike tree keyed by release and cluster name. It was not a shared database
branch, did not perform row-level acquisition, and is not the fresh B2
bootstrap ancestor. On a cache miss, reset still left an absent database for
the page consumer to create.

### Acquire at a basis

The last complete acquisition owner existed immediately before execution
namespaces were deleted in `2911dfbba`; the cited parent is
`e05a6b8edde42aa4796d8fac850daa09d2c4da9b`. The parent first queried each
requested authored function's current source against one supplied immutable
database value and attached both that database value and a digest of that
source to the invocation
(`e05a6b8edde:src/seon/execution.cljs:609-644`). The host did not silently
refresh it: `invoke-plans!` passed its caller's database value into that
preparation and then into each queued invocation
(`e05a6b8edde:src/seon/execution/host.cljs:1241-1269`). This was the first
basis handoff.

Inside the child, `acquire-identity-pages!` walked AEVT in pages of 32 and
passed the **same** invocation database value into every index, query, and
pull request
(`e05a6b8edde:src/seon/execution.cljs:646-707`). Namespace acquisition paired
identity datoms with provenance-qualified source and exact component require
edges (`e05a6b8edde:src/seon/execution.cljs:711-752`). Functions and tests
were likewise admitted only when source had REPL transaction provenance, or
when a package-stamped row matched its installed wrapper namespace
(`e05a6b8edde:src/seon/execution.cljs:346-423`,
`e05a6b8edde:src/seon/execution.cljs:777-800`). Consequently the child did
not re-evaluate the compiled core from its database display rows.

`acquire-program!` selected that provenance contract from installed schema,
read ns/fn/test sources, then read every committed schema form and function
contract from the same database value
(`e05a6b8edde:src/seon/execution.cljs:814-853`). `canonical-program` removed
database IDs, sorted require edges, functions, tests, schemas, contracts, and
namespaces, and produced a stable current-state value
(`e05a6b8edde:src/seon/execution.cljs:462-564`). The child hashed that value
and built a symbol-to-source map
(`e05a6b8edde:src/seon/execution.cljs:802-812`). It then compared the target's
source digest with the parent-pinned digest, so a target changed between
parent selection and child acquisition failed rather than calling an
unintended generation
(`e05a6b8edde:src/seon/execution.cljs:855-866`).

This chain was basis-correct in the important sense: every database operation
was explicitly parameterized by the immutable value embedded in the
invocation. It was not a compact “since basis” delta protocol. A fresh child
paged the whole *admitted authored projection* at that basis; the efficiency
came from excluding the much larger compiled base, reconstructing current
declarations instead of historical evals, and retaining one compiler per
agent between calls. Calling the result “the newer delta” is therefore a
semantic description—authored facts newer than the package—not a Datahike
`since` query.

### Evaluate a namespace-changing sequence

The strongest historical implementation is the parent
`333b21b574cc024fccf5235d6725349eeccdfd36` of deletion commit
`fbc6b28b5`. Its evaluator explicitly promises that defs persist in the shared
self-host compile state and that the ending namespace of one call becomes the
starting namespace of the next
(`333b21b574cc:src/seon/eval.cljs:13-42`). Before each form, it commits a
`:running` receipt, snapshots analyzer defs and the schema registry, then runs
`cljs.js/eval-str` in the current namespace
(`333b21b574cc:src/seon/eval.cljs:2828-2864`,
`333b21b574cc:src/seon/eval.cljs:4318-4369`).

That promise needs the self-host qualifications, not the behavior of a JVM
REPL. `cljs.js/eval-str` analyzes a form into the supplied compiler-state atom
and separately evaluates emitted JavaScript
(`reference-code/clojurescript/src/main/cljs/cljs/js.cljs:810-847,1138-1164`);
the analyzer installs a def entry before analyzing its initializer
(`reference-code/clojurescript/src/main/clojure/cljs/analyzer.cljc:2110-2121`).
Thus analyzer namespace/def state and the live JS namespace object were two
pieces of process-local state. Ordinary function defs were usable across
calls, but the owning evaluator explicitly documented that a bare value
`(def x 42)` followed by `x` returned `nil`
(`333b21b574cc:src/seon/eval.cljs:23-30`). That limitation is another reason
the durable graph admitted literal functions and declarations, not arbitrary
runtime values.

The actual runtime producer was `seon.analyzer-info`, not the build indexer.
It read `:cljs.analyzer/namespaces` directly, digested semantically relevant
var-map fields, returned added/redefined defs, and projected the analyzer
var-map into persistable symbol/arglist/doc/private/contract data
(`333b21b574cc:src/seon/analyzer_info.cljs:1-24`,
`333b21b574cc:src/seon/analyzer_info.cljs:71-94`,
`333b21b574cc:src/seon/analyzer_info.cljs:160-224`,
`333b21b574cc:src/seon/analyzer_info.cljs:316-349`). Require edges came from the same analyzer
namespace entry's `:requires`, `:uses`, and `:as-aliases`
(`333b21b574cc:src/seon/analyzer_info.cljs:271-314`).

Successful literal single-form `defn`/`defn-` source alone became a durable
function declaration; bare `def`, wrapped definitions, and multi-form sources
ran only as scratch, specifically to avoid re-firing effects during cold
reconstruction (`333b21b574cc:src/seon/eval.cljs:1926-1969`). A metadata-only
digest could not see a body-only redefinition, so `changed-defs` reparsed the
source's defining head and recovered the live analyzer entry for the named
symbol (`333b21b574cc:src/seon/eval.cljs:1971-2030`). `build-tee-entities`
then produced the full function row, schema rows for changed registration
keys, and the namespace row for an explicit namespace form
(`333b21b574cc:src/seon/eval.cljs:2281-2448`).

This worked, but it was complex because runtime state changed *before* durable
admission. On ordinary eval failure the code removed only newly-created
phantom defs and restored the exact prior schema registry
(`333b21b574cc:src/seon/eval.cljs:4427-4445`). A failed redefinition of an
existing function was explicitly outside `remove-phantom-defs!`'s removal
set (`333b21b574cc:src/seon/analyzer_info.cljs:226-269`); the function runtime
value also had no general rollback. Likewise, if candidate compilation rejected
a successful eval, the tee became empty and the schema registry was restored,
but the already-emitted function body was not reversed
(`333b21b574cc:src/seon/eval.cljs:3465-3555`,
`333b21b574cc:src/seon/eval.cljs:4526-4544`). The intended invariant was runtime/database parity; this is a
source-visible gap in the implementation.

### Exact-set, upsert, and retract

Identity entity maps gave add/redefinition upsert semantics. Datahike resolves
an entity map's `:db.unique/identity` values through AVET and reuses the entity,
rejecting conflicting identities
(`reference-code/datahike/src/datahike/db/transaction.cljc:616-704`). A
card-one assertion replaces the old value, but omission does nothing, so the
tee explicitly retracted absent `:seon.fn/spec` and
`:seon.fn/schema-error` (`333b21b574cc:src/seon/eval.cljs:2450-2470`).
Cardinality-many state also accumulates, so require components, literal read
attrs, and every direct-edge attribute were first whole-attribute retracted,
then exactly reasserted
(`333b21b574cc:src/seon/eval.cljs:2561-2602`;
`333b21b574cc:src/seon/program/edge.cljc:524-564`). Datahike's
`retractAttribute` removes all matching datoms and returns component retractions
for recursive cleanup
(`reference-code/datahike/src/datahike/db/transaction.cljc:1043-1071`).

The transaction was basis-fenced. The tee acquired the current immutable
database value plus prior namespace source and boot provenance, compiled the
entire read-dependent delta, and passed that same value as both database and
expected database (`333b21b574cc:src/seon/eval.cljs:3387-3436`,
`333b21b574cc:src/seon/eval.cljs:3465-3598`,
`333b21b574cc:src/seon/eval.cljs:3600-3649`). Datahike's maintained writer performs the
expected-basis comparison and transaction inside one serialized operation
(`reference-code/datahike/src/datahike/writing.cljc:862-879`). A stale result
caused reacquisition and recompilation of the **frozen outcome**, never
re-execution of the agent form.

Deletion was explicit rather than inferred from absence. `ns-unmap` acquired
both authored identities and boot provenance, compiled `retractEntity` for
the fn and test identities, committed those with the eval outcome, and only
after commit deleted the analyzer entry and live JS property
(`333b21b574cc:src/seon/eval.cljs:3694-3739`,
`333b21b574cc:src/seon/eval.cljs:4675-4701`,
`333b21b574cc:src/seon/eval.cljs:4809-4845`). That commit-first local mutation order is the
cleanest old precedent for current N5 deletion.

### Crash, reopen, and acquire the newer delta

The child booted from the verified compiled artifact, opened a database
session, admitted the committed base, and signalled readiness with the
database value it had opened
(`e05a6b8edde:src/seon/execution.cljs:1435-1468`,
`e05a6b8edde:src/seon/execution.cljs:1493-1512`). On its first authored call,
`ensure-program!` reacquired the invocation database value's program and
`install-program!` initialized the bootstrap compiler, verified the target
identity, and called `load-authored-program!`
(`e05a6b8edde:src/seon/execution.cljs:868-922`). A retained child could add
previously unselected targets only while the canonical program digest was
unchanged. Any changed program demanded a fresh child
(`e05a6b8edde:src/seon/execution.cljs:890-916`); the parent killed the stale
child and retried the invocation once
(`e05a6b8edde:src/seon/execution/host.cljs:951-965`).

The loader reconstructed one source string per current namespace from its
latest namespace declaration, effective require edges, and current fn/test
source rows
(`333b21b574cc:src/seon/eval.cljs:719-786`). It installed the acquired schema
projection, asked `cljs.js` to load only selected namespaces absent from the
live child, and recursively served authored dependencies from that same
source map (`333b21b574cc:src/seon/eval.cljs:788-855`,
`333b21b574cc:src/seon/eval.cljs:857-936`). It did **not** scan eval receipts,
re-run old scratch expressions, reproduce intermediate redefinitions, repeat
effects, or replay transaction history. Only the latest declaration facts
necessary to materialize the current authored namespace were evaluated.

This was reconstruction, but it was still **evaluation of current declaration
source** through the old self-host compiler. It regenerated analyzer entries
and live JS vars; it did not deserialize the prior compiler-state atom or JS
objects. Async definitions were not a different persistence channel:
`^:async` is analyzer metadata on the function environment and the compiler
emits a native `async function`
(`reference-code/clojurescript/src/main/clojure/cljs/analyzer.cljc:2325-2341`;
`reference-code/clojurescript/src/main/clojure/cljs/compiler.cljc:945-959`).
The eval-batch boundary auto-awaited returned native Promises before recording
the value, while raw `seon.eval/eval` did not
(`333b21b574cc:src/seon/eval.cljs:1180-1240`,
`333b21b574cc:src/seon/eval.cljs:4318-4546`). Neither a pending Promise nor
the process-scoped `result/<id>` JS stash was acquired after restart. Those
were runtime conveniences, not program facts.

Unexpected-exit recovery was a different transaction, not program replay. It
queried current run, turn, and running-eval identities at one database value
(`e05a6b8edde:src/seon/runtime/recovery.cljs:134-251`), then basis-fenced a
single transaction that CAS-checked the agent's run pointer, closed the open
run as `:crashed`, marked running turns and eval receipts `:interrupted`, and
stored one recovery anchor
(`e05a6b8edde:src/seon/runtime/recovery.cljs:351-416`,
`e05a6b8edde:src/seon/runtime/recovery.cljs:429-527`). A subsequent child
therefore started from the compiled package and acquired the database's
current authored declarations—including all successfully committed
redefinitions after that package was built—without executing the interrupted
form again. The compiler state was disposable; the program facts and receipt
terminalization were durable.

## Chronology

| revision | mechanism introduced, replaced, or deleted | what was actually live | owning-source evidence |
|---|---|---|---|
| `99b552371` / `d33b29cf9` (June 8-9) | runtime source indexing expanded to the compiled first-party surface | CLJS analyzer macros supplied compiled vars/namespaces; `client.cljs` read source and produced ns/fn facts | `d33b29cf9:src/seon/indexing.clj:1-76`; `src-old/seon/client.cljs:892-946,1024-1102` |
| `45d288afb` (July 2) | compiled namespace set became build-derived | fn-less compiled roots no longer depended on a hand-maintained extra list | `45d288afb:src/seon/indexing.clj:112-150` |
| `7c385f616` (July 3) | stored require edges and direct read-set facts joined authored tee rows | runtime namespace and function graph carried structural dependencies, with known fallback scars | `7c385f616:src/seon/analyzer_info.cljs:240-311`; `7c385f616:src/seon/eval.cljs:1659-1848` |
| `e643728a4` (July 12) | core program changed from unconditional boot seed to one exact desired reconciliation | boot-origin facts were upserted/retracted as a population while agent-authored identities were protected | `src-old/seon/db/program.clj:132-317` |
| `3a8b4ca59` (July 16) | session open unified database initialization | initialization and database acquisition shared one explicit open boundary | `3a8b4ca59:src/seon/client.cljs:856-930` |
| `2884c41b1` (July 16) | pod-wide replay was deleted | the immediately preceding implementation still replayed ordered stored definitions; the deletion removed that boot owner before scoped child acquisition replaced it | `2884c41b1^:src/seon/client.cljs:936-1200` |
| `86db045d6` / `b09374436` (July 16) | an isolated child gained authored-source loading, then database-authority acquisition | one persistent child compiler materialized selected current declarations from an immutable database value | `e05a6b8edde:src/seon/execution.cljs:814-922` |
| `2ab904129` / `b3beba014` (July 16) | child invocations and shared-program replacement were proven against database values | the database value, not mutable pod state, selected the authored generation | `e05a6b8edde:src/seon/execution.cljs:609-667,855-916` |
| `5131d53d7` (July 22) | child acquisition became identity-paged and preserved read errors | restart no longer required one unbounded program read | `e05a6b8edde:src/seon/execution.cljs:646-800` |
| `3acb02c1c` (July 23) | fresh initialization became paged | the writer committed an ordered population rather than one oversized frame | `src-old/seon/db/writer.clj:1944-2085` |
| `4b3d32093` / `7a1c5de68` (July 23) | program rows became build artifacts and read-side acquisition became paged | build/apply work moved off unchanged startup | `src-old/seon/db/program.clj:321-484`; `e05a6b8edde:src/seon/execution.cljs:646-853` |
| `dd919ebf6` / `1fc076d44` / `9a885319f` (July 24) | page plans were published, admitted, and consumed | runtime accepted precomputed pages only | `src-old/seon/db/protocol.cljc:1846-1875`; `src-old/seon/db/writer.clj:2020-2209` |
| `1867980cc` through `bee74572c` (July 26) | JVM indexer became the sole source/page producer, then closed source, portability, schema-owner, and facet gaps | the final old initialization artifact was CLJ-produced; CLJS no longer derived it at boot | `script/seon/dev/program_indexer.clj:49-241,331-390,464-576` |
| `c669c2f6b` (July 26) | applied closed databases were physically cloned on reset | a release/cluster-keyed at-rest template accelerated reset after a successful page/config apply | `c669c2f6b:script/seon/dev/cluster.clj:42-55,78-150,382-437` |
| `2911dfbba` (July 24 authored, later history position) | the execution child/acquisition namespaces were deleted | the last CLJS materialization owner was the parent source removed by this commit | `e05a6b8edde:src/seon/execution.cljs:609-922,1435-1512` |
| `fbc6b28b5` (July 24 authored, later history position) | pod self-host evaluator and its living tee/acquisition machinery were deleted | the parent still owned the living graph; deletion ended the historical end-to-end path | `333b21b574cc:src/seon/eval.cljs:2281-2602,2828-3649,4318-4845` |

## Worked example across database bases

This example describes the strongest old generation. `D0` is an immutable
database value after initialization; `Tn` is one transaction report whose
`:db-before` is `Dn` and `:db-after` is `D(n+1)`.

1. At `D0`, initialization already contains compiled base rows. Evaluating
   `(ns my.agent.a)` commits `T0`: the terminal eval and a
   `{:seon.ns/name 'my.agent.a, :seon.ns/source ...}` identity upsert share one
   transaction. Its first transaction provenance is REPL, so later acquisition
   admits it (`333b21b574cc:src/seon/eval.cljs:2281-2448,2866-2985`;
   `e05a6b8edde:src/seon/execution.cljs:346-423,711-752`).
2. At `D1`, evaluating a literal `(defn f {:malli/schema ...} [x] ...)`
   mutates the live compiler, then compiles `T1` against `D1`. `T1` upserts
   `"my.agent.a/f"`, connects it to `[:seon.ns/name 'my.agent.a]`, stores exact
   source/spec and direct edges, and terminalizes the eval. The exact source
   gate and row builder are at
   `333b21b574cc:src/seon/eval.cljs:1926-2030` and
   `333b21b574cc:src/seon/eval.cljs:2281-2448`.
3. At `D2`, re-evaluating the same symbol with a new body compiles `T2` against
   `D2`. Datahike resolves the unique `:seon.fn/sym` to the existing entity;
   card-one source/spec/generation replace, while direct-edge many attributes
   are whole-attribute retracted then reasserted. No second function entity is
   created (`reference-code/datahike/src/datahike/db/transaction.cljc:616-704`;
   `333b21b574cc:src/seon/program/edge.cljc:524-564`).
4. At `D3`, a successful `schema/register!` changes the registry snapshot.
   `T3` upserts `:seon.schema/key`, canonical form, optional namespace ref, and
   generator facet with the eval receipt
   (`333b21b574cc:src/seon/eval.cljs:1900-1924`,
   `333b21b574cc:src/seon/eval.cljs:2281-2448`). A failed or rejected candidate
   restores the registry and does not publish this row.
5. At `D4`, an explicit namespace declaration adding
   `(:require [my.lib :as lib])` yields `T4`. The namespace identity is reused;
   old component require edges are retracted recursively, and the analyzer's
   current alias/refers projection is asserted as the complete new set
   (`333b21b574cc:src/seon/analyzer_info.cljs:271-314`;
   `333b21b574cc:src/seon/eval.cljs:2561-2602`). A function defined at `D5` may
   now have `"my.lib/g"` in its call-string set.
6. At `D6`, `(ns-unmap 'my.agent.a 'f)` is treated specially. `T6` checks that
   the identity is not protected compiled core, retracts the fn/test entities,
   and terminalizes the eval. Only after `T6` commits does the owner remove
   `f` from the analyzer and live JS object
   (`333b21b574cc:src/seon/eval.cljs:3694-3739`,
   `333b21b574cc:src/seon/eval.cljs:4809-4845`). At `D7`, acquisition can no
   longer select `f`; there is no tombstone or replay instruction.
7. Suppose a later `T7` successfully defines `g`, producing `D8`, and the
   process then crashes during a different form. Recovery marks only the
   running receipt interrupted. A new child invoked with `D8` pages the current
   program, reconstructs `my.agent.a` with `g` but not `f`, and loads it over
   the compiled base. The earlier definitions of `f`, its redefinition, and
   the interrupted form are never executed
   (`333b21b574cc:src/seon/eval.cljs:719-936`;
   `e05a6b8edde:src/seon/runtime/recovery.cljs:351-527`).

## What worked and what failed

| revision / mechanism | worked | bug or gap | evidence |
|---|---|---|---|
| build exact-set reconciliation | identity upsert, optional-field removal, exact component edges, stale boot-row GC | preserved authored overrides by provenance; completeness guards could refuse partial populations | `src-old/seon/db/program.clj:205-317` |
| analyzer detect-and-tee | new defs, metadata changes, schemas, namespace changes and direct edges committed with terminal receipt | analyzer digest omitted body, requiring a source-head rescue; several parser paths accumulated | `333b21b574cc:src/seon/eval.cljs:1926-2030`, `333b21b574cc:src/seon/eval.cljs:2281-2602` |
| failed eval cleanup | new phantom defs removed; schema registry restored; failed form had no program tee | existing-var/runtime redefinition was not generally rolled back | `333b21b574cc:src/seon/eval.cljs:4427-4445`; `333b21b574cc:src/seon/analyzer_info.cljs:226-269` |
| precommit candidate + atomic tee | invalid schema/contract candidates were rejected; accepted rows and receipt shared one transaction | successful eval already mutated runtime before candidate rejection; later post-commit publication could also fail and leave generations unequal | `333b21b574cc:src/seon/eval.cljs:3465-3598`, `333b21b574cc:src/seon/eval.cljs:4526-4546`; `docs/seon/issues/archive/post-commit-program-publication-leaves-admission-open.md:8-28` |
| `ns-unmap` | durable retract and local analyzer/runtime deletion agreed, with core protection and stale-basis retry | only explicit special forms deleted rows; ordinary source absence did not imply authored deletion | `333b21b574cc:src/seon/eval.cljs:3694-3739`, `333b21b574cc:src/seon/eval.cljs:4809-4845` |
| multi-form eval | writer transaction order reflected committed per-form sequence | no durable contiguous ordinal proved an attempted form was not lost | `docs/seon/issues/archive/multi-form-eval-order-is-not-durable.md:16-38` |
| post-deletion claim-native driver | none of the old effectful tee was ported, consistent with great deletion | terminal transactions no longer committed authored corpus facts; the old end-to-end invariant was temporarily absent | `docs/seon/issues/archive/driver-terminal-transactions-do-not-commit-authored-corpus-facts.md:8-27` |
| cold child acquisition | exact database value, target source digest, canonical program digest, and fresh-child replacement prevented stale invocation | it paged the entire admitted authored projection rather than a true `since` delta; changed program forced child replacement | `e05a6b8edde:src/seon/execution.cljs:609-667`, `e05a6b8edde:src/seon/execution.cljs:829-922` |
| cold namespace reconstruction | latest declaration plus exact effective require edges restored ordinary aliases and current fn/test definitions | repeated bare namespace re-entry once lost retained aliases; persisted dependency errors could wedge normal invocation after restart | `333b21b574cc:src/seon/eval.cljs:740-786`; `docs/seon/issues/archive/repeated-namespace-reentry-lost-cold-aliases.md:8-48`; `docs/seon/issues/archive/persisted-program-error-prevents-agent-repair.md:16-53` |
| self-host compile/runtime state | function defs and `^:async` functions persisted in one live compiler/runtime generation | compiler analyzer state and JS objects were separate; bare value defs did not reliably read across `eval-str`; pending Promises and result stashes were process-only | `333b21b574cc:src/seon/eval.cljs:13-30,1180-1240`; `reference-code/clojurescript/src/main/cljs/cljs/js.cljs:810-847`; `reference-code/clojurescript/src/main/clojure/cljs/compiler.cljc:945-959` |
| persisted-program repair | complete load failure retained the compiler, source map, and eval door; live restart proof repaired invalid source | initial load diagnostics still lacked direct namespace/form attribution, and instrumentation was once omitted from the cold load | `e05a6b8edde:src/seon/execution.cljs:926-951`; `docs/seon/issues/archive/persisted-program-error-prevents-agent-repair.md:55-84`; `docs/seon/issues/archive/execution-child-program-load-omitted-instrumentation.md:16-33` |

## Fresh-tree mapping

| concern | already present | missing | reuse / adapt | reconceive / delete |
|---|---|---|---|---|
| one accepted-source reader | `seon.sci.reader/read` uses SCI's source reader and `parse-next+string`, returns ordered exact source/spans, and fails closed on reader tags and uncertain namespace-changing calls (`src/seon/sci/reader.cljc:28-114,264-358,360-425`) | the evaluator still calls `sci/parse-string`, so one-parser ownership is not complete (`src/seon/sci/eval.clj:298-360`; `test/seon/sci/reader_test.clj:378-410`) | reuse the read-event shape and source/span tests verbatim; adapt consumers to pass those events, not source to another reader | delete the second accepted-source parse in eval; do not add a separate graph parser |
| namespace facts | parse-time `ns` facts contain identity, doc, and exact require component maps, while later events carry fail-closed namespace attribution (`src/seon/sci/reader.cljc:116-186,264-355`) | the event does not itself add `:seon.ns/source`; current run freezing stores only a namespace ref and discards declaration facts (`src/seon/cluster/run.cljc:365-432`) | combine the event's exact source with its namespace projection at the one producer boundary; retain require edges as components and exact-set them | do not fabricate namespace stubs or infer runtime namespace from the agent after parsing |
| function/test facts | literal `defn`/`defn-` events lift symbol, arglists, private, doc, optional spec and workload; tests lift identity (`src/seon/sci/reader.cljc:196-262`; `test/seon/sci/reader_test.clj:324-364`) | events do not add fn/test namespace refs or source themselves; terminal run transactions do not publish them (`src/seon/cluster/run.cljc:365-432,444-610`) | connect event namespace and exact source to canonical rows; retain literal-declaration admission and the tree round-trip fixture (`test/seon/sci/reader_test.clj:13-83`) | do not inspect JVM Vars as a second producer; do not port analyzer-state diffing or body-head rescue |
| row schema | canonical `:seon.fn`, `:seon.ns`, and `:seon.schema` attributes and entity maps remain registered (`src/seon/schema.cljc:494-575,1853-1873`) | reader emits symbol-valued `:seon.fn/sym`, while the canonical schema declares a string identity; reader emits `:seon.fn/workload`, which is not registered in that canonical block (`src/seon/sci/reader.cljc:229-241`; `src/seon/schema.cljc:501-515`) | reuse identities/connections/source/spec/require shapes only after sealing one representation; add no `:type` or `:kind` | remove old execution-tier, nursery, fn-var, created-at, read-attrs and schema-error projections unless a current consumer derives a justified fact |
| schema EDN and runtime schema candidate | classpath `schema/*.edn` has one loader/admission path; isolated registration deltas stage candidate forms and can identify changed keys (`src/seon/schema/edn.clj:118-231,262-352`; `src/seon/schema.cljc:1720-1794`) | no terminal path converts an accepted agent delta to canonical committed schema rows and activates only `:db-after` | reuse the candidate admission gate, canonical `:seon.schema/key`/`form` rows, and database projection; adapt the overlay so database commit precedes activation | do not treat the process-local registry as durable or publish a schema merely because a namespace loaded |
| database-derived projection | `projection-from-rows` derives schemas, function contracts, dependencies, provenance, and activation from committed rows (`src/seon/schema.cljc:1486-1698`) | no run/eval handoff rebuilds and installs an N5 program projection from the terminal transaction report | reuse basis-parametric query-row inputs and activation-after-validation | derive schema references, renderer eligibility, binding tables, placement, and workload reachability; do not store them as entity kinds or cached flags |
| build-time base | the branch ancestor is content-addressed, forkable, and has an injected population seam; current population commits attributes, processes, and canonical schema rows (`src/seon/cluster/ancestor.clj:47-104,168-256`; `src/seon/cluster.clj:321-404,847-882`) | it explicitly leaves indexed code to N5; no fresh producer adds source-tree ns/fn/test rows (`src/seon/cluster.clj:364-388`) | reuse the ancestor fork/seal boundary, deterministic pages/rows, and zero-write convergence ideas | do not clone c669's closed directory mechanism into the branch ancestor and do not re-index per cluster |
| living runtime producer | one per-run SCI `ctx` preserves definitions between forms in that run; crash recovery records interruption and never re-executes (`src/seon/sci/eval.clj:72-97`) | successful namespace/schema/defn events are not converted to program transaction data; parse-time namespace can differ from eval because every form is rebound to the agent namespace (`src/seon/sci/eval.clj:335-348`; `test/seon/gen/loop_test.clj:262-301`) | reuse the same analyzed row shape as build and commit accepted program rows in the form's one terminal transaction | reconceive publication as commit-first facts plus derived SCI installation; do not restore the CLJS tee's mutate-then-rollback shape |
| basis acquisition | immutable database values, transaction reports, database-derived schema projection, and near-instant ancestor forks already exist | no current owner queries admitted current program rows at a basis and installs them into an SCI `ctx`; current evaluator says the N5 binding table is absent (`src/seon/sci/eval.clj:83-90`) | reuse old basis pinning, target identity/source verification, deterministic current-state projection, and namespace-whole acquisition where SCI loading requires it | CLJ-only SCI installs current forms into a context; no CLJS compiler child, package leaf, JS global, Promise/result recovery, or eval-history replay |
| exact-set/upsert/delete | `seon.reconcile` computes cardinality-one/many retractions, population stale-entity retracts, and recomputes inside the writer transition (`src/seon/reconcile.cljc:261-430`) | no code-graph scope/identity policy or explicit durable namespace/function deletion transition is wired | adapt pure desired/current diff, identity upsert, omitted optional retracts, exact component replacement, and commit-first `ns-unmap` lesson | provenance scope must be transaction-derived and code-specific; do not copy config-process ownership or infer an authored deletion from an incomplete scan |

## Recommended N5 contract boundaries and falsifiers

Analysis only; no implementation is authorized here.

The following are boundaries to seal, not production design or permission to
implement.

1. **One read event is the producer input.** Build and runtime both consume
   `seon.sci.reader/read` events. A program producer may analyze the already
   parsed form further, but may not read the source again. Falsifier: the
   standing reader-surface search becomes empty for accepted Clojure source,
   and the full fresh `src/` tree still round-trips byte spans and forms
   (`test/seon/sci/reader_test.clj:13-83,378-410`).
2. **One canonical row contract serves two producers.** Build-time and runtime
   producers emit the same ns/fn/schema/test identities, connections, source,
   contract, require components, direct calls, and analyzer uncertainty.
   Falsifier: indexing a literal contracted function through both paths yields
   equal normalized rows; no JVM Var inspection, CLJS analyzer diff, or second
   call-graph builder is needed. Calls must settle as walkable connections if
   current consumers require graph reachability; the old call strings are
   evidence, not an automatic field decision
   (`333b21b574cc:src/seon/program/edge.cljc:524-564`).
3. **Admission precedes durable publication, publication precedes live
   installation.** A transaction function receives the current database value,
   validates existing namespace/schema connections, exact-sets the identity,
   and returns one transaction report containing receipt plus program change.
   SCI installation derives from `:db-after`. Falsifier: rejected or
   basis-stale redefinition leaves both the durable row and callable SCI Var at
   the prior generation; retry never re-executes the authored form.
4. **Namespace and schema forms have their own identities.** A defn references
   an already committed namespace and complete committed contract; it does not
   fabricate either. A schema registration stages through the existing delta
   and gate, commits canonical rows, then activates the projection from
   `:db-after` (`src/seon/schema.cljc:1720-1794`;
   `src/seon/schema/edn.clj:262-352`). Falsifier: missing namespace, missing
   complete contract, unregistered schema key, and conflicting identity all
   refuse without a datom or callable change.
5. **Exact replacement is stated per owned attribute.** Redefinition retracts
   omitted optional attributes and replaces card-many/component sets;
   deletion is an explicit identity transition. Falsifier: `defn -> re-defn`
   removes old doc/spec/calls; require removal retracts the old component;
   delete makes both query and SCI resolution absent; repeating any converged
   operation writes zero datoms.
6. **Acquisition is basis-parametric current-state materialization.** It reads
   one immutable database value, validates target identity/source, derives the
   complete schema/program projection needed by a namespace, and installs it
   into an agent flow's SCI `ctx`. Falsifier: a ctx booted from package state
   at `D0` and acquired at `D8` calls the `D8` function, cannot resolve a
   function deleted at `D6`, and never executes intermediate definitions,
   scratch forms, prior effects, interrupted receipts, or result values.
7. **Runtime ownership follows current rulings.** Every agent's own flow owns
   its ctx lifetime; a var redefinition can change referenced proc behavior,
   while a graph topology change rebuilds the flow. Binding tables, schema
   dependencies, render eligibility, placement, and workload are derived from
   attributes and connections. Falsifier: no central evaluator/dispatcher,
   entity-kind stamp, stored distance, stored owner projection, CLJS child, or
   replay queue appears in the dependency graph
   (`docs/prds/sci-execution-runtime/plan/README.md:799-880,893-939`).
8. **Crash is receipt recovery plus later acquisition.** Recovery marks the
   running work interrupted, re-derives the flow, and lets the agent adapt; it
   never decides whether an uncommitted effect happened by replaying a form.
   Falsifier: kill after eval start but before terminal commit, reopen, observe
   interrupted receipt, acquire only committed program rows, and observe no
   duplicate effect (`src/seon/sci/eval.clj:92-97`).

The minimum worked N5 proof should run the report's `ns -> defn -> re-defn ->
schema -> require -> delete -> crash/reopen` sequence across recorded database
values. It must assert both datoms and callable SCI behavior at each basis.
Tests of only the pure row transformer, only fixture loading, or only a
long-lived ctx do not falsify the end-to-end failure class.

## Uncertainties

- Git history contains authored dates and later rewritten topology; chronology
  above is ordered by the source mechanism and replacement commits, not by an
  assumption that commit timestamps alone describe deployment order.
- This audit proves source-level mechanisms and archived live incidents, not
  that one surviving binary can still run the deleted end-to-end path.
- The final old program graph stored ordinary calls as strings and only
  terminal dependencies as refs. Current “attributes + connections” rulings
  require a new field decision; this report does not silently relabel the old
  strings as refs.
- Fresh reader facts use symbol-valued `:seon.fn/sym`, while the canonical
  database schema declares a string identity
  (`src/seon/sci/reader.cljc:229-241`;
  `src/seon/schema.cljc:501-515`). N5 must settle one representation before
  producer equality, lookup refs, or upsert tests can be meaningful.
- `:seon.fn/workload` is emitted by the reader but is absent from the current
  canonical registration block
  (`src/seon/sci/reader.cljc:229-241`;
  `src/seon/schema.cljc:494-575`). Whether the existing schema is elsewhere
  intentionally pending or simply missing remains unresolved; it is a
  seal-blocker, not permission to add it here.
- Reader declaration facts deliberately keep source and namespace attribution
  on the enclosing event rather than duplicating them in the fn map
  (`src/seon/sci/reader.cljc:340-353`). The canonical producer assembly
  boundary is not yet implemented.
- Schema candidate deltas are process-local overlays. Their exact
  commit-before-activation integration with a transaction function and
  concurrent agent flows remains unsettled
  (`src/seon/schema.cljc:1720-1794`).
- The old custom edge walker captured eight fail-closed uncertainty classes.
  Whether N5 extends analysis over the already parsed SCI form or uses an SCI
  analyzer output is unsettled; “one parser” forbids a second source reader,
  not necessarily a pure analysis pass. There must still be only one graph
  producer per build/runtime event.
- Old acquisition paged a complete admitted authored projection. It did not
  prove a Datahike `since` delta or incremental in-place replacement when the
  graph digest changed.
- The exact N5 row contract is still subject to current owner rulings and the
  adversarial renderable-corpus findings. This report recommends boundaries
  and falsifiers only.

## Sources read

Authorities and plans:

- `CLAUDE.md`/`AGENTS.md`; `docs/prds/sci-execution-runtime/AGENTS.md`;
  `docs/prds/sci-execution-runtime/plan/{README,handbook,unsettled,history}.md`;
  `docs/seon/architecture/{architecture,data-model}.md`.
- `.agents/skills/{data-oriented-clojure,data-modeling,datahike,clojurescript}/SKILL.md`.
- `docs/prds/sci-execution-runtime/research/{n5-plan-2026-07-27,renderable-corpus-plan-2026-07-28,renderable-corpus-falsification-2026-07-28}.md`.

Historical owning source and history:

- `src-old/seon/{AGENTS.md,db/AGENTS.md}` and
  `src-old/seon/db/{protocol,program,writer}.clj[c]`,
  `src-old/seon/{client.cljs,client/indexing.clj,program/edge.cljc,ns/source.cljc}`.
- `script/seon/dev/program_indexer.clj`; its history from `1867980cc` through
  `bee74572c`; page/index commits `3acb02c1c`, `4b3d32093`,
  `7a1c5de68`, `dd919ebf6`, `1fc076d44`, and `9a885319f`.
- `c669c2f6b:script/seon/dev/cluster.clj` plus its parent/surrounding history.
- `333b21b574cc:src/seon/{eval.cljs,analyzer_info.cljs,schema.cljc,program/edge.cljc}`;
  deletion `fbc6b28b5`.
- `e05a6b8edde:src/seon/{execution.cljs,execution/host.cljs,runtime/recovery.cljs}`;
  deletion `2911dfbba`, plus acquisition history `86db045d6`,
  `b09374436`, `2ab904129`, `b3beba014`, and `5131d53d7`.
- Archived issue/research evidence cited in the failure table, including
  analyzer-driven extraction/resume, post-commit publication, multi-form
  order, repeated namespace re-entry, persisted-program repair, and omitted
  child instrumentation.

Dependency source:

- `reference-code/datahike/src/datahike/{db/transaction.cljc,writing.cljc,writer.cljc}`.
- `reference-code/clojurescript/src/main/{cljs/cljs/js.cljs,clojure/cljs/analyzer.cljc,clojure/cljs/compiler.cljc}`.
- `reference-code/sci/src/sci/{core.cljc,impl/analyzer.cljc,impl/load.cljc,lang.cljc,interrupt.cljc}` and `reference-code/sci/doc/interrupt.md`.

Fresh source and proof:

- `src/seon/sci/{reader.cljc,eval.clj,admit.clj}`;
  `src/seon/cluster/{ancestor.clj,reply.cljc,run.cljc}`;
  `src/seon/{cluster.clj,reconcile.cljc,schema.cljc}`;
  `src/seon/schema/edn.clj` and all `src/seon/schema/*.edn`.
- `test/seon/sci/reader_test.clj`, `test/seon/gen/loop_test.clj`, current
  schema admission tests, ancestor/run tests, and source-tree searches for all
  program attributes and accepted-source reader sites.
