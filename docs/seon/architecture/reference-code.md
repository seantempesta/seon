---
type: architecture
status: active
created: 2026-09-21
tags: [architecture, dependencies, reference-code]
---

# Read the dependency at its owning seam

`reference-code/` makes dependency semantics inspectable. Before adding a mechanism,
read the dependency implementation and the first-party caller that uses it. A
behavior observed in a small probe is not the dependency's complete guarantee.
The [implementation plans](../../prds/agent-platform/plan/README.md) retain exact
probe forms, historical revisions and proof gates; this guide identifies the seams.

A Git submodule checkout, the gitlink recorded by Seon and the code actually loaded
in a JVM can differ. Derive them separately: `git ls-tree HEAD reference-code/`,
`git -C reference-code/<dependency> rev-parse HEAD`, `git submodule status`, and the
resolved dependency/classpath source used by the host. `deps.edn` supplies direct
and transitive resolution. Absence of a local/root override does not mean a
library is design-only: Clojure, core.async and konserve can execute from artifacts.
No fixed repository count or maintained keep/delete roster replaces those reads.

## Read before you build

Before implementing in an area, read the library functions in its row and their callers, then Seon's first-party
owner. Record which guarantee you reuse, what the library does not provide, and the inputs and cost of your
composition. Copy the idiom, not the surrounding architecture: a similar function's size is not proof that a Seon
obligation (custody, contracts, references instead of copies, evidence, the one value printer) can be dropped. Keep
apart an upstream seam, our maintained fork, installed behavior and a target design. Paths are under
`reference-code/`; pins are listed in the [data-oriented-clojure skill](../../../.agents/skills/data-oriented-clojure/SKILL.md),
and the citations were checked against those checkouts on 2026-10-07. How Seon code and tests look is in that skill
and the clojure-testing skill.

| Seon area | Read first | Pattern to copy, and its limit |
|---|---|---|
| Errors ([spec](../specs/errors.md)) | `malli/src/malli/core.cljc:203-207`; `datahike/src/datahike/db/transaction.cljc:27-32`; `clj-reload/src/clj_reload/core.clj:367-381`; `clojure/src/clj/clojure/core_print.clj:473-507` | One constructor and one thrower; raise a data map with a type key where the failure is detected; return the same fields when answering is the job (clj-reload adds `:exception` to the returned map), else throw. The owning boundary walks the cause chain once with Seon's cycle-safe walk (`seon.error.refusal/chain`: cause and suppressed edges, a seen-set) and passes it on; `core_print.clj:473-507` shows the link shape, not a walk to use (it has no seen-set and drops suppressed throwables). Store references, never a dependency's raw payload. |
| Contracts, instrumentation | `malli/src/malli/core.cljc:2210-2245` (`-instrument-f`; reports at `:2233-2240`), `:3166-3187` (`-instrument`); `malli/src/malli/error.cljc:332-390` (`with-error-messages`, `with-spell-checking`, `humanize`); `malli/src/malli/util.cljc:128` (`closed-schema`); `malli/docs/function-schemas.md:526-530` | The wrapper validates and calls `report` with a kind and `{:input :args :schema}`; if `report` returns, the call proceeds, so Seon's report must throw to prevent entry. Installed: Seon's report is `seon.instrument/boundary-refusal`. TARGET (errors cut): one adapter over Malli's five report kinds builds the error from `m/explain`; spelling suggestions explain against `closed-schema` on the refusal path only. Contracts are one line of `:malli/schema` metadata. |
| Schemas, registry | `malli/src/malli/registry.cljc:11-96` (`Registry` protocol; `fast`, `simple`, `composite`, `lazy`); `malli/src/malli/core.cljc:353-361` (`-cached`), `:2672-2712` (`validator`, `explainer`); `malli/docs/reusable-schemas.md:116-120` | Registries compose; derived functions are cached on the compiled schema object (reuse the object, or key a `seon.cache` kind by content). Refer to keys by name with naked map entries. |
| Data modeling, refs, components, writer validation ([data-modeling skill](../../../.agents/skills/data-modeling/SKILL.md)) | `datahike/src/datahike/db/transaction.cljc:998-1015` (incoming ref sweep), `:1172-1173` (`:db.fn/call` gets the in-transaction db), `:1225-1234,1296` (`validate-report`) | A decision that can change is made at the writer; one final-report validator judges the attempted datoms, and any non-nil result rejects. Seon owner: `seon.db/transact!` with `seon.db/write-owned-values-error` validating the final owning values. |
| Database reads | `datahike/src/datahike/api/impl.cljc:30-42` (`transact!`); `datahike/src/datahike/api/specification.cljc:429`; `datahike/src/datahike/pull_api.cljc:541-555` (`pull-many`) | The public function is a thin delegate; Datahike errors are `ex-info` with `:error`, raised where detected. Reads are batched and input-aligned. Seon owner: `seon.db` ([datahike skill](../../../.agents/skills/datahike/SKILL.md)). |
| Central content-keyed cache | Caffeine (a `deps.edn` jar, not under `reference-code/`); Seon owner `seon.cache/lookup` and `seon.cache/status` | One cache, kinds keyed by content, weighed at insert, bounded by one byte budget; nothing is invalidated. Count misses; a dependency's own cache (Datahike, clj-kondo) stays its own. |
| Branch and store lifecycle | `datahike/src/datahike/versioning.cljc:212-277` (`branch!`), `:279` (`delete-branch!`), `:494-501` (`release-materialized-db`); `datahike/src/datahike/gc.cljc:22-81,144-167` | A branch shares immutable indexes; deleting one is not erasure, GC reclaims what no head reaches. Seon owners: `seon.cluster.registry/branch!`, `seon.cluster.registry/retire-branch!`, `seon.cluster.agent/acquire-context!` and `release-context!`; konserve owns durable storage. |
| Source analysis, exact-span write-back | `clj-kondo/src/clj_kondo/core.clj:67` (`run!`), `:242-270`; `clj-kondo/src/clj_kondo/impl/cache.clj:171-215`; `rewrite-clj/src/rewrite_clj/zip.cljc:275,309-311,335,343` (`of-string`, `root-string`, `replace`, `edit`) | Analysis is data joined to source by exact span; an edit is a zipper walk ending in `root-string`. Seon adds digest fencing, refusal data and write-back equivalence (reindex to prove equivalent declarations). Seon owners: `seon.fn.analyzer/analyze`, `seon.edit/exact`, `seon.edit/form`, `my.edit/exact!`. |
| REPL path, reading and evaluation ([agent loop spec](../specs/agent-loop.md)) | `sci/src/sci/impl/interpreter.cljc:94-114` (`eval-string*`); `sci/src/sci/core.cljc:260-276,334-340` (`intern`, `fork`); `nrepl/src/clojure/nrepl/middleware/interruptible_eval.clj:72-180` (`evaluator`: `::read-fn`/`::eval-fn` seams); `edamame/src/edamame/core.cljc:126` | Reader and evaluator are stages passed in as functions; a context is `init` once, then `fork`. SCI reads and evaluates each form and returns the last result; nREPL sends a response per form. Seon's fold records every evaluated form in its single settlement. |
| Value printing | `fipp/src/fipp/edn.cljc:9-21`, `fipp/src/fipp/visit.cljc:32-52`, `fipp/src/fipp/engine.cljc:10-46`; `nrepl/src/java/nrepl/out/QuotaBoundWriter.java:26-37` | Fipp shows document construction and one small method per node kind. The quota writer truncates characters at a sink and then throws: it can cut syntax and supplies no retrieval. Seon's value printer keeps the original value, spends one token budget over whole nodes and emits runnable retrieval forms for each cut; never add a character quota or a second clipping layer. |
| Reload, adoption | `clj-reload/src/clj_reload/core.clj:185-191` (`topo-sort-fn`), `clj-reload/src/clj_reload/parse.clj:163-175` (`topo-sort`), `core.clj:270-302` (`ns-unload`, `ns-load`), `:304-381` (`unload`, `reload`) | Order dependents with a pure topological sort, unload in reverse, load in order, return `{:unloaded :loaded}`. `scan` (`:206-229`) reads the clock and filesystem; it is not pure. Seon adds custody, private-state preservation, arming and recorded adoption. |
| Agent loop, Flow ([spec](../specs/agent-loop.md)) | `core.async/src/main/clojure/clojure/core/async/flow.clj:165-200` (`process`), `:291-309` (`map->step`); `core.async/src/test/clojure/clojure/core/async/flow_test.clj:34-41` (`minimal-step`) | A transform `(state in msg) -> [state out]`; state lives in the proc and `describe` declares the workload; upstream an escaped exception goes to Flow's error channel and the loop resumes; Seon's `seon.flow/var-process` catches it first and records it, so a transform that records its own errors answers its own requests and must not rethrow them. One transform per pass and one settlement are Seon's reviewed design, not something core.async proves. Context composition is being redesigned (context-design notes); |
| Render selection | `clerk/src/nextjournal/clerk/viewer.cljc:1470-1497` (`find-viewer`, `viewer-for`) | The value's explicit viewer, else the first predicate match in the supplied order. Seon's order is fixed by CLAUDE.md (value override, viewer-namespace render, schema pair along ancestry, none). |
| Web, SSE | `hyperlith/src/hyperlith/impl/datastar.clj:122-181` (`render-handler`), `:42-49` (`send!`); `http-kit/src/org/httpkit/server.clj:345-350` (`write-state`); `http-kit/src/java/org/httpkit/server/ServerAtta.java:44-66`; `datastar-clojure/libraries/sdk-http-kit/src/main/starfederation/datastar/clojure/adapter/http_kit.clj:23-76` (`->sse-response`) | Subscribe before the first render; a `(a/dropping-buffer 1)` coalesces replaceable wake signals. It does not bound socket bytes: `send!` does not wait for drain. Bound output separately with `write-state`'s pending bytes and drain-or-close completion, retaining the connection until it completes. |
| Test runner | `kaocha/src/kaocha/testable.clj:105-137` (`-run`, `run-hook`); `kaocha/src/kaocha/type/var.clj:30-45`; `kaocha/src/kaocha/plugin/filter.clj:10-99`; `clojure/src/clj/clojure/test.clj:708-737` | Tests are a tree of data; `-run` dispatches on type; filtering is a function over the plan; the result merges the testable with its counts. Seon adds recorded evidence, member worlds and actual-exit ownership. |
| Shell | `babashka-process/src/babashka/process.cljc:95-114` (`check`), `:165-173` (`destroy-tree`), `:674-708` (`shell`) | Merge an options map with defaults once. The process map is babashka's error data; Seon's error refers to a process by identity. `destroy-tree` signals; waiting for actual exit is separate. |
| LLM provider ([skill](../../../.agents/skills/llm-providers/SKILL.md)) | `langchain4clj/src/langchain4clj/core.clj:249-262` (`build-model` on `:provider`) | Provider differences live in a multimethod or table from config to request. langchain4clj builds Java clients and forwards streaming callbacks; it is not a model for Seon's wire request, SSE fold, usage normalization or cache fields. Read Seon's owners and the provider skill for those. |

## The mechanisms Seon composes

| Dependency seam | Guarantee and limit | First-party consumer / specification |
|---|---|---|
| `reference-code/datahike/src/datahike/versioning.cljc:212-277` | Branch creation shares immutable indexes and updates branch/roster state; it is not complete cluster startup | `seon.cluster.registry/branch!`; [B1](../../prds/agent-platform/plan/lane-b1-one-publication-path.md), [D1](../../prds/agent-platform/plan/lane-d1-isolation-merge-writeback.md) |
| `reference-code/datahike/src/datahike/versioning.cljc:323-367,457-501,550-588` | Guarded head movement needs exclusive custody; immutable commit materialization needs release (`release-materialized-db`, `:494-501`); whole-store copying can tear under concurrent writes (`:584-588`) | Source publication and B4 independent-store fixtures |
| `reference-code/datahike/src/datahike/versioning.cljc:738-756`; `reference-code/datahike/src/datahike/writing.cljc:883-911` | Merge takes caller-supplied content and parents through the serialized writer; `merge-writer!` is the ordinary `transact!`, so the arg-map's `:datahike/expected-basis-t` fence and report validation apply (only through the arg-map arity of `merge!`: the 3- and 4-arity forms pass no basis). Datahike supplies lineage, not semantic conflict resolution; verify Seon's caller supplies the fields, do not rebuild the seam | D1's maintained-fork merge composition; `seon.cluster.source` checks touched digests in its transaction function |
| `reference-code/datahike/src/datahike/db/transaction.cljc:998-1015,1225-1234,1296` | Incoming ref sweep and final-report validation, including attempted writes; a non-nil validator result rejects | `seon.db/write-owned-values-error`; [A2](../../prds/agent-platform/plan/lane-a2-datahike-one-answer.md) |
| `reference-code/datahike/src/datahike/query.cljc:3010-3029`; `reference-code/datahike/src/datahike/db.cljc:423-442` | Revision-based read currency (generation and revision comparison; cache-context advancement). Any outstanding transaction-time correction is unverified: name its producer and failing case before prescribing one | `seon.db` read evidence; A2 |
| `reference-code/datahike/src/datahike/datom.cljc:271-315,325-359`; `reference-code/datahike/src/datahike/pull_api.cljc:16,315-323` | Index ordering delegates to `compare-value`: read its supported types before admitting a new value type. The default many-pull limit is nil (`+default-limit+`); an explicit limit truncates without a completeness marker | Schema bridge and complete reads; A2 |
| `reference-code/datahike/src/datahike/gc.cljc:22-81,144-167` | Reachable parents/heads and cutoff govern collection; retraction or branch deletion is not all-snapshot erasure | Store maintenance and D1 evidence retention |
| `reference-code/malli/src/malli/core.cljc:2210-2245,2672-2712,3166-3187` | Compiled schemas retain derived operations; instrumentation receives child contracts; report-return does not itself stop entry | `src/seon/instrument.clj`; [A1](../../prds/agent-platform/plan/lane-a1-projection-carried.md) |
| `reference-code/malli/src/malli/registry.cljc:17-30` | Fast registry copies a HashMap; simple registry retains persistent data | Projection construction; count copied work as well as compilation |
| `reference-code/malli/src/malli/error.cljc:44-172,288-306,374-390` | Declared error messages and structured humanization | B3 error prose composed from schema evidence |
| `reference-code/sci/src/sci/core.cljc:260-276,334-340`; `reference-code/sci/src/sci/impl/utils.cljc:410` | Intern, fork and copy-on-write Var update; shared JVM roots and mutable objects remain outside this isolation | `seon.sci.eval/fork-for-turn`, `seon.sci.eval/fork-cluster-ctx`; [B2](../../prds/agent-platform/plan/lane-b2-walk-flow-fork.md) |
| `reference-code/sci/doc/interrupt.md:52,63-87` | Interpreted function/loop interruption does not stop host calls | Evaluation and B4 termination |
| `reference-code/core.async/src/main/clojure/clojure/core/async/flow/impl.clj:29-36,257-260,271-320` | Future creation and serial proc loop; the compute transform's timed `.get` (`:257-260`) is not cancellation or exit | Agent completion/permit ownership; B2 |
| `reference-code/core.async/src/main/clojure/clojure/core/async/flow.clj:136-155,265-286` | Ping returns responders; compute timeout belongs to compute transforms | Boot readiness and admitted workloads |
| `reference-code/clj-kondo/src/clj_kondo/core.clj:242-270`; `reference-code/clj-kondo/src/clj_kondo/impl/cache.clj:171-215` | Cache processing precedes final findings; `sync-cache*` retains in-run definitions, so disabling the disk cache does not erase in-run namespace resolution | `src/seon/fn/analyzer.clj`; B1 rejected/overlapping analysis proofs |
| `reference-code/clojure/src/clj/clojure/test.clj:710-737`; `reference-code/clojure/src/clj/clojure/core/server.clj:275` | Canonical test fixture dispatch and io-prepl framing | B4 run authority and B1 reachable tooling |
| `reference-code/hyperlith/src/hyperlith/impl/datastar.clj:122-181`; `reference-code/http-kit/src/org/httpkit/server.clj:345-350`; `reference-code/http-kit/src/java/org/httpkit/server/ServerAtta.java:44-66` | Subscribe-before-first-render and compute dispatch; `write-state` exposes pending bytes and a drain-or-close completion, which a caller must observe to bound output (it imposes no bound itself) | `seon.render.web/write-package!`; B2 UI |

Konserve owns durable key/blob storage and reclamation. Read the selected backend
before relying on copy, enumeration, fsync or size behavior; a stored digest proves
only what the staging and write owner actually verified. The current store/registry
and blob owners stay the first-party integration points. Do not add a storage API
merely because an older review once requested one.

## Our forks

Owner ruling (2026-10-08): where a library lacks a seam we need, change our fork instead of building around it. Each
fork change is an upstream-quality commit with the library's own tests; general ones are offered upstream once there
is a community. Each fork keeps the short divergence list below, revised in place when its gitlink or pin moves.
Derived 2026-10-08 from `git log <upstream>..<pin>` in each checkout (merges excluded); the commit lists themselves are
the fork's own history.

| Fork | Seon pins | Branch, upstream base | What it changes |
|---|---|---|---|
| Malli | gitlink and `:local/root` `418df441` | `seon-ref-scope` over `metosin/malli` master (10 commits) | instrumentation: `-strument!` passes `:registry` to `m/-instrument`, `-strument-each!` continues past a failing Var; `primitive-fn?` and `malli.error` levenshtein public; a passing armed call allocates no regex driver, argument vector or guard pair; a registry pointer and an outermost ref share their target's cached validator; `::invalid-schema-at-call` carries the arm's schema and checked values, and a throwing validator reports it; the resolving registry is the ref validation scope; SCI support resolves loaded qualified Vars and keeps a re-evaluated `defn` live; a symbol `:gen/gen` resolves |
| SCI | gitlink and `:local/root` `010c86df`; the checkout is at `97de2004` (gitlink bump: ledger FK-SCI2) | `seon-env-hook` over `babashka/sci` master (26 commits to `010c86df`, 2 more to `97de2004`) | observation: producer write and expansion observers, a write's origin on its event, executed built-in calls and host interop observed; Vars: copy-on-write forked Vars and metadata, read-only shared stock Vars, protected compiled Var metadata, generation stamps on Vars born in a fork, fact-safe root installation; namespace state: exact state, intern snapshots, effective bindings, required namespaces, serializable imports; options: unknown keys refused, `:classes {:allow :all}` resolves any host class, unresolved symbol in analysis error data, Clojure 1.11 core functions; `sci.addons.future` takes an injectable `future-call`; `alias` and `require :as` refuse a retarget as Clojure does; (`97de2004`) range checks interruption once per chunk, keeping `LongRange`'s O(1) count and chunked reduce |
| Datahike | gitlink and `:local/root` `e65599de` | `seon/identity-join-first` over `replikativ/datahike` main (159 commits) | writer: final transaction-report validation, expected-basis precondition shared by merge, refusals delivered and logged without values or stack traces, listener failures never strand commits, every accepted write resolved on fatal exit; versioning: `fork-database`, `force-branch!` returns its commit, branch roster mutations serialized per store, unified branch and connection lifecycle; caches: committed query results shared by store and commit across branches, revisions name commits across connections (`86c3301a`), a commit that changes no schema reuses its schema-meta key, node cache shared between connections to one store; query planner and engine: identity joins first, fused joins, union and `or-join` estimates, NOT and `get-else` semantics, card-many merges, bounded query and pull resources, exact query dependency plans and pull evidence; pull: complete collections unless a limit is asked, `pull-many` ordered for missing entities; history: temporal indexes hold only what left the current index; store: `:datahike/overlay` private memory writes over a shared store; gc: each commit and node marked once per collection; schema: refuse removing a schema that carries data; commits: a branch head only names its commit and each commit writes its root record once (`e65599de`, CC-3/CC-4); also Proximum secondary-index and ClojureScript fixes |
| konserve | `deps.edn` `:git/url` and gitlink `e4334756` | `main` over `replikativ/konserve` main (14 commits) | `bassoc` takes metadata and compresses binary values through the store compressor; LZ4 frames use 64 KiB blocks; filestore: immutable new keys defer their sync by opt-in, bounded and streamed binary IO, idempotent deletion; tiered: a warm never replaces a frontend value; GC: dry-run candidates without deletion, fixed deletion batches exposed; legacy header compatibility ported to 0.9.359 |
| clj-kondo | gitlink and `:local/root` `57252e07` | detached at `v2026.07.24` plus 2 commits (branch `seon` holds the same) | analysis resolves source metadata keywords and attribute-map metadata keywords in the source namespace. Planned: exact bindings from the existing parse and `run!` taking a resolved configuration (ledger FK-KONDO) |
| core.async | none: `deps.edn` uses Maven `1.10.874-alpha3`; gitlink `dc35f3e` is upstream | local branch `seon/flow-stop-errors`, one commit `fb21fc0` over `dc35f3e`, unpushed (no fork remote exists) | `flow`: errors thrown while handling `::flow/stop` are delivered. Planned: `create-flow` takes an `:error-chan` (ledger FK-ASYNC, which needs the fork remote and a `deps.edn` change from root) |

## Maintained changes and verification

Dependency changes land at the existing owner with public API, implementation,
regressions and caller conversion together. Push the maintained fork and record its
revision before a Seon gitlink depends on it. Verify both the changed boundary and
Seon's ordinary path; a dependency-only test does not prove loaded integration.

The active plans require specific proofs, not every historical fork proposal:
A2's currency/comparator/complete-read work; D1's merge guard/validator composition;
B1's publication/cache use; B2's existing SCI and Flow behavior. B2 requires no new
Flow timeout hook, and generation equality alone does not justify a new SCI privacy
mechanism. A filename-existence cache cannot replace captured input identity.

Dependency retirement follows recomputed code/document usage, resolved runtime use
and preservation of local fork changes. Research and older PRDs may still be the
only evidence for a claim. This revision neither declares them deleted nor treats
an unused direct dependency entry as proof its repository can be removed.
