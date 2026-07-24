---
type: research
status: active
tags: [research, runtime, architecture]
---

# Pre-processing design — R45 definitive authority (2026-07-23)

Owner-directed design for rulings R45 + amendments (pre-processing pays all
derivation; applying a release to a cluster is explicit; startup never
derives, ≤10s; write-through maintenance keeps the cache current O(delta);
crash-mid-maintenance ⇒ delta-only re-derivation, loud) plus the same-day
owner addendum: CHEAP CLUSTER SPIN-UP is a primary consumer — pre-processing
runs once per release and every cluster shares its output.

Grounded in [[boot-time-design-2026-07-23]] (measured pigs),
[[u9-deletion-plan-2026-07-23]], [[sci-internals-opportunities-2026-07-21]],
and direct source reads cited per claim. Every claim is marked
VERIFIED (file:line read in this investigation), PRIOR-VERIFIED (cited from
an accepted research file), or ESTIMATED. Line numbers are from the working
tree on `codex/runtime-reliability-refactor` at this read (initpage/P1b
landed); they will drift as bootfast lands — the mechanism names are the
durable reference.

## 0. The one design sentence, and the layered model

Every derived value in the boot path is a pure function of one of exactly
three identities — the RELEASE (artifact digest), the APPLIED CLUSTER
(release × config manifest), or that cluster's DIVERGENCE (the
agent-authored layer in its own database, at a basis) — and each identity
has one explicit operation that pays its derivation (pre-process, apply,
write-through maintenance respectively), so that `start` is always
verify + load + attach and never a derivation site.

The second owner addendum makes the layering explicit and it governs the
whole design: clusters are EXPECTED to diverge — agents author schemas,
functions, everything — so the model is a shared immutable compiled base
(release-scoped, one copy on disk, shared by reference across every
cluster) plus a per-cluster divergence layer that lives IN THAT CLUSTER'S
DATABASE, including the cached derivations FOR that layer (this is exactly
where the R44 no-history db-cache permission aims). Resume identity
composes:

    resume-identity = base identity ⊕ divergence identity
    base identity       = artifact digest × core schema fingerprint
                          (release-scoped, shared, immutable)
    divergence identity = divergence fingerprint × basis :t
                          (cluster-scoped, lives in that database)

Startup of any process on any cluster = load the shared base + attach +
overlay the cluster-db-resident divergence layer. The divergence layer is
itself cached write-through in that database, so the overlay is
O(divergence) — near-zero for a fresh cluster, bounded by what agents have
actually authored for an old one, and never proportional to the corpus.

## 1. Operations and contracts

Four named operations. Names are owner-ruled vocabulary: the operation is
PRE-PROCESSING; a cluster receives a release through APPLY; processes START;
the running cluster is MAINTAINED write-through.

### 1.1 `pre-process` — once per release

- **Owner:** the build. Concretely: `:shadow.build/stage :flush` hooks in
  `script/seon/dev/program_artifact.clj` (the existing publisher —
  `publish!`/`publish-inventory!` at `program_artifact.clj:156-166`, atomic
  publish via `atomic-spit!` `:141-154`, sha256 `digest` `:123-128`) plus
  the operator's digest machinery in `script/seon/dev/artifact.clj`
  (`digest-paths` `:335`, `source-input-digest` `:369`,
  `current-execution-digest` `:446`). VERIFIED. No new hook framework: this
  extends the one existing flush-stage publisher. An operator entry
  (`bin/seon release preprocess`, name = owner taste) re-runs/verifies the
  same derivation outside a watch cycle.
- **Input:** the built artifact (Shadow analyzer state + compiled sources +
  the program-sources map the hook already computes).
- **Output:** the release directory of digest-bound sidecars (inventory in
  §2, groups A–B): program sources (exists), inventories (exist), program
  rows (new), boot projection proof + pure-data derivation (new), page plan
  (new).
- **Identity key:** the artifact digest — for the client artifact the
  existing `:seon.dev.artifact/execution-digest`; for the whole release the
  existing `:seon.dev.artifact/application-digest`
  (`artifact.clj:64-73`, VERIFIED). Each sidecar embeds/publishes its own
  sha256 and the launch descriptor binds them, exactly as
  `SEON_PROGRAM_SOURCE_DIGEST` is verified today at consumption
  (`client.cljs:1205-1235`, VERIFIED). The precedent for a keyed build
  cache already exists in this file: `:seon.dev.writer-cache/input-digest →
  writer-digest` (`artifact.clj:78-79`, VERIFIED).
- **Contract:** pure function of the artifact. Deterministic byte-for-byte
  (the row population is already deterministic by construction — wall-clock
  attrs stripped, rows sorted, `client.cljs:1801-1823`, VERIFIED).
  Publishing is atomic; a partially written sidecar can never carry a valid
  digest.
- **Cluster independence (owner addendum §1):** VERIFIED with one carve-out.
  Program sources, inventories, and program rows are functions of the
  artifact alone. The full initialization VALUE additionally takes the
  resolved configuration (`database-initialization` signature
  `client.cljs:1814-1848`: exemplar-ns selection and `initial-data` read
  `configuration`), and the page plan orders schema rows via a projection
  build (`protocol.cljc:1580-1608`). Therefore the key algebra separates:
  release-scoped sidecars keyed by artifact digest ALONE (shared by every
  cluster), and the seed/page-plan sidecar keyed by artifact digest ×
  config-manifest digest (shared by every cluster on the same manifest —
  which is every ordinary dev/test cluster, since they all seed from
  `config/system.edn`). No sidecar key ever involves a cluster basis.

### 1.2 `apply` — explicit per cluster

- **Owner:** the operator (`bin/seon`), as the release-wide extension of
  the config explicit-apply precedent (`bin/seon config apply <path>` — the
  one manifest-reconcile surface, per the src/seon ownership table).
  Surface: `bin/seon cluster apply <name>`. File-backed clusters require this
  explicit operation before `up`; startup never changes their population.
- **Input:** the preprocessed release directory + the selected config
  manifest + the target cluster.
- **Work (all of it — this is where derivation-adjacent cost lives):**
  1. verify sidecar digests against the release identity;
  2. paged database initialization through the ONE existing mechanism —
     `initialization-pages` (`protocol.cljc:1624-1687`) producing
     `:seon.db/initialization-page` frames consumed by the writer's
     ensure-database path, receipt-tracked by the
     `:seon.db.initialization/entity` row (fingerprint + page-count +
     status, `protocol.cljc:320-333`, VERIFIED) — but fed from the
     preprocessed row/page sidecars instead of runtime `index-core!`;
  3. config reconcile (`reconcile-config!` → one provenance-scoped
     `state/reconcile!`, `client.cljs:2248-2259` call site) — moved INTO
     apply, out of startup;
  4. initial agent birth (`ensure-initial-agent!`,
     `client.cljs:2267-2287`) — moved INTO apply, out of startup.
- **Identity key:** the applied-release record persisted IN the cluster's
  database: the existing initialization fingerprint
  (`(str (hasch/uuid initialization))`, `protocol.cljc:1646`, VERIFIED —
  content identity of the complete seed value) plus the already-stamped
  `:seon.execution/artifact-digest` (`:seon.db/initialization` schema,
  `protocol.cljc:279-287`, VERIFIED) plus the config-manifest digest. These
  three facts ARE the cluster's applied identity; startup compares against
  them.
- **Contract:** idempotent (re-apply of the same identity converges with no
  writes — the config-apply semantics generalized); crash-mid-apply leaves
  `:seon.db.initialization.status/in-progress`, which is provably not
  complete (the landed initpage crash-mid-seed proof); re-apply resumes or
  restarts paging. Apply is the ONLY operation that may write the seed.

### 1.3 `start` — never derives

- **Owner:** each process's existing entry (`bin/seon up` → operator →
  writer boot / `seon.client/start-runtime!` / host/web-render mains).
- **Work:** verify identity → load caches → attach → advertise ready.
  Precisely: read the cluster's applied-identity facts; compare the
  process's own launch descriptor digests; on match, admit the shared base
  materialization (§2 row 4, release file) and overlay the cluster's
  divergence-layer cache (§2 row 5, db facts — O(divergence)); skip every
  population-scale derivation; open the session; resume claims lazily.
  The base is verified against the release identity; the divergence
  overlay is verified against its own fingerprint × basis; the composed
  population's fingerprint must equal the recomputed fingerprint over the
  live rows — three checks, all hashing, no compiling.
- **On mismatch:** fail LOUD with the exact remedy — "this cluster was
  applied at release `<digest8>`/config `<digest8>`; this artifact is
  `<digest8>`; run `bin/seon cluster apply <name>`" — the config
  explicit-apply error posture. Startup NEVER falls back to deriving.
- **Target:** ≤10s process start on an applied cluster, including a
  freshly-applied cluster's first boot (owner addendum §4). Design target,
  not an R27 runtime limit; the R42 stall breaker remains the only clock.

### 1.4 `maintain` — write-through, O(delta)

- **Owner:** the same code path that performs each invalidating mutation —
  never a batch janitor, never a second flow:
  - **registration / durable defn** (agent `register!`, durable-defn
    admission): the transaction that commits the new `:seon.schema` /
    `:seon.fn` row also carries the projection-cache delta (§2 row 5) — one
    tx, atomic by construction;
  - **hot reload** (dev watch → pod/host republish): the publication that
    re-runs admission (`admission/prepare-committed!` →
    `publish-committed!`) refreshes the process-local materialization and
    commits the updated cache row at its generation;
  - **explicit apply**: rewrites the applied-identity facts and the cache
    rows wholesale.
- **Contract:** after ANY committed mutation, a process killed and
  restarted at that instant finds a cache whose key matches the database's
  own content identity — restart/resume is free at any moment. Because db
  cache deltas ride the mutating transaction itself, "crash between the
  mutation and the cache write" is unrepresentable for database-homed
  caches; the delta-only re-derivation path (R45 clause 5) exists for the
  remaining case — a cache row whose recorded basis lags the head (e.g. a
  maintenance writer running a superseded code version) — detected by key
  mismatch, repaired by deriving only rows asserted since the cached basis,
  logged loud.

## 2. Cache inventory

Every derived thing the boot path computes today, verified from source.
Homes follow R44: artifact-adjacent files for pre-session values;
`:seon.db/no-history?` attributes for post-attach derivations; process
memory for anything containing functions/registries (the R15/R32
tier-local-object discipline applied to caches).

| # | Derived value | Today computed by | Home | Key | Maintenance trigger | Crash recovery |
|---|---|---|---|---|---|---|
| 1 | Program source map | build (exists: `out/client/program-sources.edn`, 5.7MB) | release file | artifact digest | build flush | atomic publish; digest verify at load refuses partial |
| 2 | Export/terminal inventories | build (exist: `program-inventory.edn` + P1b sidecars) | release file | artifact digest | build flush | same |
| 3 | **Program rows** — the exact `:seon.ns`/`:seon.fn`/`:seon.schema` row vector (source extracts, arglists, spec strings, ns rows) | boot: `index-core!` `client.cljs:1726-1785` + `index-schemas` `:1787-1799` (~35s of the 81s window, ESTIMATED split) | **release file (new)** `program-rows.edn` | artifact digest | build flush | same |
| 4 | **Boot projection proof + pure-data derivation** — fingerprint, schema-dependencies, reverse-deps, function-deps, shape-rows/index, catalog (`build-projection` output minus registry/options, `schema.cljc:871-886`) | boot ×3: discarded gate `client.cljs:1824-1837`, admission `admission.cljs:298`, page ordering `protocol.cljc:1585` (46s each pre-D1, VERIFIED structure) | **release file (new)**, all-core population | artifact digest × schema fingerprint (`schema.cljc:866-870`) | build flush | same |
| 5 | **Divergence-layer projection delta** — the cluster's agent-authored registrations/durable defns plus the derived pure-data delta they induce over the base (their dependency edges, shape-index/catalog additions, admission provenance, pure-predicate additions) | admission rebuilds the WHOLE population every boot (`admission.cljs:298`) | **no-history db attrs in the cluster database (new)**, one cache entity — the R44a target | base fingerprint × divergence fingerprint × basis `:t` | agent registration/durable-defn tx (same-tx delta); hot-reload publication recomposes | key mismatch ⇒ delta re-derive of the divergence layer only (rows since cached basis), loud; the base is never re-derived |
| 6 | Compiled Malli registry + compile options (`:seon.schema.projection/registry`/`compile-options`) | every projection build | **process memory only** — contains fns; never persisted | rematerialized from 4/5; linear compile (Pass-3 `m/schema` loop `schema.cljc:769-770`), R44 platform-fold parallel | process start | recompute (seconds; 0.37s instrumentation evidence that warm per-item Malli work is ms) |
| 7 | Instrumentation wrappers (925 fns) | `instrument/reconcile-projection!` `admission.cljs:302-306` | process memory | projection generation | admission publish | recompute (0.37s MEASURED) |
| 8 | Seed pages / page plan | `initialization-pages` `protocol.cljc:1624-1687` incl. its own projection build for dependency ordering `:1585` and topo sort `:1550-1608` | **release file (new)** `page-plan.edn` (ordered page payloads) | artifact digest × config-manifest digest | build flush (given the manifest) or first apply | atomic publish; the db-side `:seon.db.initialization/entity` receipt governs resume |
| 9 | Seeded database population | apply (paged writes; 97 pages / 16s MEASURED) | the cluster store (authority, not a cache) | initialization fingerprint `protocol.cljc:1646` | explicit apply only | in-progress status ⇒ provably unseeded; re-apply |
| 10 | Config singleton + routes/skills | `reconcile-config!` (part of the unlogged 35s gap) | db facts (authority; exists) | config-manifest digest (add as a stored fact for identity compare) | explicit apply | reconcile is idempotent/converging (existing contract) |
| 11 | sci base context (portable slice, ~182 ns) | `build-base!` `host/context.clj:1280-1314` per host process | process memory — env atom of Var objects (fns); never persisted | rematerialize; load order/source set precomputable into 2/3 | process start | rebuild from sidecars |
| 12 | Per-agent sci fork + corpus def replay | `fork-context` + `replay-defs!` `host/context.clj:1316-1344` | process memory | corpus def rows (db authority) | agent claim (lazy — NOT on the startup path) | re-fork + replay on next claim |
| 13 | Program-graph edges / planning projection (P1/P2) | edge rows committed at pre-process-equivalent time; plans derived-never-stored (R21), cache-key over the planning projection | rows: db (exists); plans: process memory | basis + digest (P2 cache-key, exists) | edge rows ride the same registration flow; plans recompute | recompute |
| 14 | Blob/embedding/render caches | post-attach derivations | R44a no-history db attrs where measured-worthy | content identity each | their producing flows | skip-only by construction |

Rows 3, 4, 5, 8 are the new machinery; everything else is existing
mechanism confirmed in its place or explicitly process-local.

**Base ⊕ divergence composition is one mechanism, not two builders.**
`build-projection` gains a compose arity — base pure-data (row 4) +
divergence rows/delta (row 5) → the composed projection — inside
`schema.cljc`, the one owner; the monolithic arity remains the cold path
and the equivalence oracle (S7 proves compose ≡ cold on the same
identity, byte-for-byte over the pure-data). Everything derived in
`build-projection` today composes additively from a delta: dependency
maps, reverse edges, shape rows/index, catalog are all row-keyed
accumulations (`schema.cljc:771-865`, VERIFIED shapes) — the one genuinely
cross-population value is the fingerprint, which is recomputed over the
composed population (hashing, cheap). Definition of the divergence layer, precisely: the committed rows whose
(identity, form, admission-provenance) content differs from the base
population's row of the same identity, plus rows whose identity the base
lacks — a content set-difference, not merely "agent-provenance rows".
Upsert on the identity attr means the database holds ONE row per identity
(no duplicate-row case; `projection-from-rows`'s duplicate refusal at
`schema.cljc:919-925` stays the malformed-input guard), so a redefined
base identity appears in the divergence layer as its one current row and
the composed fingerprint hashes the winning form — divergence-wins is a
consequence of row identity, not a merge policy.

**The persist-vs-rematerialize rule generalized (applies to every future
cache):** pure data (EDN forms, strings, digests, sorted row vectors,
dependency maps) may persist under a content key; anything holding a
function, a compiled validator, a sci Var, an atom, or a platform object
rematerializes per process from persisted pure data. This is the R15/R32
tier-local discipline applied to caches, and it is exactly the line between
Malli-projection pure-data (persistable) and its registry (not), and
between sci corpus sources (persistable — already database rows) and sci
contexts (not).

## 3. Sci-fork internals — findings and verdicts

We own `reference-code/sci` (fork HEAD contains JIT `45bcf0f`). Read this
investigation + [[sci-internals-opportunities-2026-07-21]].

### 3.1 Analysis output cannot cross a process boundary (VERIFIED)

On :clj the analyzer's output node is `(reify sci.impl.types/Eval (eval
[this ctx bindings] ...))` — an anonymous JVM object closing over the
analysis environment (`types.cljc:249-278`, the `->Node` macro; :cljs uses
`->NodeR` holding a raw fn). `eval-form*` re-analyzes every top-level form
per eval (`interpreter.cljc:29-62`, `ana/analyze` at `:51`); sci keeps no
cross-eval analysis cache. **Verdict: analyzed structures are process-local
by construction — pre-processing can never persist them.** What IS
process-portable is everything upstream of analysis: source strings, parsed
form data, load order — all already pure data in our corpus rows.

### 3.2 Cross-fork node reuse is unsafe for defs (VERIFIED)

`analyze-def` interns the target var at ANALYSIS time into the analyzing
context's env (`init-var!` `analyzer.cljc:765-797`; env write at
`:766-770`). A node analyzed while fork A was current holds fork A's
interned Var object; replaying it into fork B would alias agent-private
state across agents. `sci/fork` copies the env value but SHARES all
existing Var objects (`core.cljc:318-323` — `(atom @env)`). **Verdict: do
not build a shared analyzed-def cache across agent forks; per-agent
`replay-defs!` re-analysis is the correct semantics.** Replay is lazy
per-claim (row 12), so it is off the startup path and O(one agent's defs).

### 3.3 The jit namespace caches nothing persistable (VERIFIED)

`sci/impl/jit.cljs` is CLJS-only experimental JS codegen: per analyzed fn
body it compiles a `js/Function` template once (ns docstring
`jit.cljs:1-13`); per-call-site deref caches are `#js [val epoch]` arrays
invalidated by the global `var-epoch` cell (`jit.cljs:401-411`;
`vars.cljc:48-56`). All of it is live JS objects, process-local, and the
tier that runs it (the Bun pod) dies at U9. **Verdict: nothing here serves
R45; do not invest (consistent with the standing self-host ruling).**

### 3.4 The env atom is a graph of mutable Var objects (VERIFIED)

`sci.lang.Var` carries mutable `root`/`meta`/`watches` fields and the root
is usually a closure (PRIOR-VERIFIED `lang.cljc:71-90`). The env atom's
namespaces map is sym → Var. **Verdict: a corpus-loaded sci env cannot be
snapshotted to disk or wire; "snapshot/restore" within one process is
already served by the base-context + `sci/fork` structure-sharing design
(`host/context.clj:1280-1323`), which is the correct and only reuse.**

### 3.5 What sci-side pre-processing CAN do, and the one fork-extension question

1. **Precompute the base-load plan (no fork change).** `build-base!`'s
   portable-slice load computes source units, dependency order, and purity
   classification at every host start (`load-portable-slice!`
   `host/context.clj:1093-1140`: `source-unit`/`dependency-order`/
   `pure-block?` over the toolkit files). All three are pure functions of
   the artifact — move them into the pre-process sidecar (join row 3), so
   host start replays a precomputed ordered block list. Saves the
   classification/ordering work and file scanning; the sci evals themselves
   remain (they must — closures).
2. **Batch eval of pre-parsed forms (public API, no fork change).**
   `sci.core/eval-form` (`core.cljc:393`) accepts already-read forms; if
   the base-load plan stores parsed forms, start skips re-parsing.
   Marginal (parsing is cheap relative to analysis) — take it only because
   it is free once the plan sidecar exists.
3. **Fork extension — NOT recommended now.** A cross-boot analysis cache
   would require making `->Node` output serializable (a bytecode/AST-data
   representation plus a re-linking loader) — a deep fork of the evaluator
   for a cost (base load) that has not been measured against the 10s
   budget. The host log currently has NO timestamps for its base load
   (boot-time doc §1) — measure first (unit S6). If measurement shows the
   sci base load alone breaks the budget, the correct extension is a
   **precompiled base-context builder**: a generated `.clj` namespace,
   emitted at pre-process, that constructs the base env via direct
   `copy-var*` (`core.cljc:111-136`) instead of interpreting the portable
   slice — compiled by the JVM once, giving JIT-compiled toolkit fns and
   near-instant base build. That is an artifact-generation strategy, not an
   interpreter change, and it stays inside our own build. Spec it only on
   measured need (owner option §8.2).
4. The two small fork patches already ranked in
   [[sci-internals-opportunities-2026-07-21]] (structured resolve ex-data;
   optional `:invoke-fn`) are unrelated to R45 — unchanged.

## 4. Startup sequence — the ≤10s budget

Topology: target R26 (writer JVM · web-render JVM · claimant JVM(s) · Bun
leaf). The Bun pod's 25s bundle-require and self-host machinery die at U9;
budget below is the target topology, with the interim pod noted. ALL
per-phase numbers are ESTIMATED unless marked measured; unit S6 makes them
measured before the target is declared met.

Sequence per process (phases 3–6 overlap where noted; R44: platform fold
for CPU, vthreads for I/O):

| Phase | Work | Est. | Basis |
|---|---|---:|---|
| 1 | JVM spawn + clojure load | 2–4s | writer "booting→ready 0.2s" is post-load; JVM+deps load dominates (ESTIMATED; measure) |
| 2 | Writer: open store + verify applied identity (3 facts read) | <0.5s | store open instant on measured run; identity read is one entity |
| 3 | Claimant/web-render: attach session + read applied identity + read the divergence cache entity (row 5) + compose over the release-file base (row 4) | <1s | O(divergence): one cache-entity read + hash checks vs today's 2,373+925-row full acquisition (7.5s measured); full rows re-read only on key mismatch; fresh cluster divergence = ∅ |
| 4 | Materialize Malli registry over the composed population (linear compile, platform fold across cores) | 1–3s | Pass-3-only compile; 0.37s instrumentation evidence for warm per-item cost; ESTIMATED |
| 5 | Instrument | 0.4s | MEASURED |
| 6 | sci base build from the precomputed load plan (claimant only; overlaps 3–5 on its own platform thread) | 1–3s | UNMEASURED today — S6's first job; precompiled-builder fallback if over budget |
| 7 | Advertise ready; agent forks/replays happen lazily per claim | ~0 | row 12 |

Total: **≈5–9s** per process, processes in parallel under the operator.
The stall breaker (R42) stays the only clock; the 10s figure gates design
acceptance, not runtime.

**S4 measured checkpoint (2026-07-24, release
`ee5015ecdf715ad553f498973d4ed2de2d2179b3d2f2ba3912199beeb5074ff5`,
isolated `s4startgate`):**

| Process / interval | Measured | Result |
|---|---:|---|
| Bun leaf, containment owner start → `auto-boot ready`, empty divergence | 2.03s | PASS |
| Bun leaf, containment owner start → `auto-boot ready`, one agent schema + one durable function in the divergence overlay | 2.46s | PASS |
| Bun `cluster open`, complete operator wall including immutable-package verification | 9.21s | PASS |
| Writer JVM, containment owner start → writer `ready` | 13.88s | **FAIL** |
| Writer JVM, post-class-load `booting` → `ready` | 0.07s | diagnostic only |
| Claimant JVM | not measured | target process is not owned by the current release package |
| Web-render JVM | not measured | target process is not owned by the current release package |

The Bun result closes S4's population-work hypothesis: its divergent restart
contains zero committed-acquisition, indexing, config-reconcile, initial-agent,
or monolithic-projection log lines. Session attach took 0.98s; config and
resumable-agent reads took 0.04s and 0.09s; compose + materialize took 0.40s;
instrumentation took 0.46s. The cache carried one schema and one function
delta, and its composed canonical data string was byte-equal to the cold
monolithic projection at fingerprint `1769298305`.

The overall ≤10s target is therefore **not yet graduated**. S4 removed the
population-scale work and proves the current per-cluster Bun START below the
budget, but the first honest writer measurement falsifies phase 1's 2–4s
estimate, and the two target JVM consumers are still unmeasured. S6 now owns
the remaining writer/class-load investigation plus claimant/web-render
measurements; no S4 result may relabel the 13.88s writer start as a pass.

What made this possible is subtraction, not optimization: phases that no
longer exist at start are index-core!/index-schemas (release file),
build-projection ×3 (proof + pure-data cached; registry compile is the only
remaining cost), full-population acquisition (cache row + fingerprint
verify), reconcile-config!/initial-agent (apply), and per-registration
`register!` asserts during namespace load (§5 note below).

**Load-time `register!` under a verified release:** the second quadratic
(the per-registration `assert-complete-contract!` with the growing
population, `schema.cljc:665-675`, VERIFIED) is redundant work when this
exact artifact's complete population already carries a pre-process proof
(row 4): the population-level gate subsumes every prefix gate. Startup
loads run `register!` in collect-only mode gated on the verified release
identity; the full assert remains for REPL/dev registration and for any
load not under a verified identity. This can only skip re-proving, never
change outcomes (§6). D1's de-quadratic remains the floor so the
unverified path is also fast.

## 5. Cheap cluster spin-up (owner addendum)

The same three-identity split answers multi-cluster directly:

1. **Release-scoped work is shared by construction.** Rows 1–4 key on the
   artifact digest alone; row 8 on artifact × config-manifest digest.
   Nothing in the release directory derives from any cluster's basis or
   store (VERIFIED §1.1). Cluster N's cost is exactly apply + attach.
2. **Apply cost for a new cluster is MEASURED by S3.** On 2026-07-24,
   `/usr/bin/time -p bin/seon cluster apply r45s3` took **46.00s** with
   98 pages at 64 rows and **36.44s** with 28 pages at 256 rows. Both were
   fresh absent-database applies through the same built code/corpus and the
   same writer, config-reconcile, initial-agent, and final-identity mechanisms;
   each config fact correctly produced a distinct artifact/config identity.
   Coarsening therefore saved **9.56s** end to end; the earlier ≈4s total
   estimate was falsified. Pages came STRAIGHT from `page-plan.edn` with zero
   apply-time recomputation. An exact-identity re-apply took **25.91s** but
   retained the identical basis transaction and commit ID, proving zero writes;
   this isolates one-shot client/base-load overhead that paging cannot remove.
   Config reconcile + initial agent remain inside the unpartitioned remainder;
   S4/S6 instrumentation must name that split before further promises.
   **The ≤10s target remains open after S3: page coarsening is material but
   insufficient, and S4 owns removal of startup/base population work.**
3. **Storage sharing:** the release directory is immutable and
   content-named — publish once under `out/` (or `releases/<digest>/`,
   owner taste) and let every cluster reference it by path+digest exactly
   as `SEON_PROGRAM_SOURCE_PATH`/`_DIGEST` do today; never copy per
   cluster. Per-cluster remains: the store
   (`data/clusters/<name>/store`), `packages/` manifests +
   `node_modules`, logs/ports/process records. The applied-identity facts
   live in each cluster's own database, so a cluster is self-describing
   about which release it carries.
4. **Ephemeral/test clusters stay the default pattern.** A lane's
   isolated cluster = `apply` (seconds-scale, above) + processes at ≤10s
   each — versus 271s measured today. Reset = wipe store + re-apply the
   same shared release; no rebuild, no re-derivation. This makes the
   fresh-reset live-proof ledger (R38) cheaper, not more precious.
5. **First boot of a freshly-applied cluster is an ordinary resume:**
   apply leaves the applied identity + an EMPTY divergence cache current,
   so the first `start` takes the same verify+load+attach path as the
   hundredth (§4 applies unchanged, phase 3 at its floor).
6. **Divergence does not erode the sharing (second addendum).** As a
   cluster's agents author schemas and functions, the shared release
   directory never changes and is never copied — divergence accumulates
   as committed rows plus the write-through row-5 delta in that cluster's
   own database. Two clusters on the same release with different
   divergence share 100% of the base artifacts and 0% of each other's
   state; a cluster's resume cost grows only with ITS divergence, and a
   later release upgrade is an explicit re-apply whose seed reconciles
   against the existing rows (R38: ordinary dev/test clusters reset
   instead — divergence-preserving upgrade is not a promised path today).

## 6. Failure modes and the poisoning-impossibility argument

- **Identity mismatch at start:** loud refusal naming both identities and
  the exact remedy command (§1.3). Never silent re-derivation — a startup
  that "helpfully" derives would reintroduce the tax and mask a stale
  apply.
- **Partial/corrupt sidecar:** atomic publish means a torn file is
  unreadable or digest-mismatched; consumption verifies sha256 before use
  (the existing `load-program-sources` posture) and refuses with the
  pre-process remedy. A stale artifact can never lie — it can only fail to
  match.
- **Crash mid-apply:** the initialization receipt is
  `in-progress`/`complete` (`protocol.cljc:322-333`); `in-progress` is
  provably-not-seeded (landed initpage proof); re-apply resumes. No
  process may attach for normal work before `complete`.
- **Crash mid-maintenance:** db-homed cache deltas ride the mutating
  transaction — atomic, so this case is unrepresentable for row 5. For
  the artifact-adjacent files, mutation = a new build = a new digest = new
  files (immutable, atomic). The residual case — a cache row whose basis
  lags head (e.g. maintenance code skew) — is detected by key mismatch at
  the next read and repaired by delta re-derivation from rows asserted
  since the cached basis, logged loud (R45 clause 5).
- **Poisoning impossibility, per cache:** a cache may only SKIP work,
  never change results, because every key is the content identity of the
  cache's complete input and every consumer verifies the key against
  independently-held identity before use:
  - rows 1–3, 8: key = sha256 of the bytes, verified at load against the
    launch descriptor's digest (which the operator computed from the
    artifact, not from the sidecar); a wrong sidecar fails the compare;
  - row 4: key includes the schema fingerprint, which is
    `portable-string-hash(canonical-data-string([forms contracts
    admissions …]))` (`schema.cljc:866-870`) — the consumer recomputes the
    fingerprint from the ACQUIRED rows (cheap: hashing, no compile) and
    admits the cache only on equality, so the cache cannot smuggle a
    different population (this is D2's proof-not-heuristic argument,
    promoted to the persistent cache);
  - row 5: the divergence cache's key names the base fingerprint it was
    computed AGAINST plus its own divergence fingerprint and basis `:t`
    (an immutable coordinate); the consumer checks base-key equality with
    the loaded row-4 cache AND recomputes the composed fingerprint over
    the live rows — a divergence cache from another base, another
    cluster, or a stale basis fails one of the three hashes and falls to
    the loud delta re-derivation;
  - row 9: the seed's fingerprint is content-derived (`hasch/uuid`) and
    receipt-checked page-by-page (existing machinery);
  - collect-only `register!` (§4) skips a proof that the release identity
    asserts was already run over a SUPERSET population of the same forms;
    if the artifact were different, the identity gate refuses before any
    skip. In every case the skipped computation is a pure function of
    inputs whose identity was just verified — recomputing could not
    produce a different answer, which is the definition of skip-only.
- **The standing regression that keeps all of this honest:** byte
  equality between cached-resume state and cold-derive state on the same
  identity (unit S7) — `canonical-data-string` equality of the projection
  pure-data + fingerprint equality + row-vector equality for the sidecars
  (the D3 parity regression generalized).

## 7. Strengthens vs must-not-create

Strengthens in place (the one-mechanism ledger):

- `schema/build-projection` stays the ONLY projection constructor
  (pre-process calls it; start rehydrates its output; D1 fixes its
  interior) — the cache stores its OUTPUT, never a second builder.
- `initialization-pages` + the writer ensure-database path stay the ONE
  seeding mechanism — the page plan is its precomputed input, so fixtures,
  fresh boots, and new clusters keep the one seeding path (F1 direction
  confirmed, not forked).
- The digest/launch-descriptor machinery (`artifact.clj`,
  `program_artifact.clj`) stays the one identity authority; new sidecars
  are more rows in the same manifest.
- `reconcile-config!`/`state/reconcile!` stay the one config surface —
  apply relocates the CALL, not the mechanism.
- Admission (`prepare/publish/admit`) stays the one publication gate;
  cache admission is a new fast path INSIDE `reconcile-committed!`, not a
  bypass around it.

Must NOT be created:

- **No second schema authority.** The persistent caches store only
  derived pure-data + proofs, never registrations; committed
  `:seon.schema`/`:seon.fn` rows remain the sole source, and a cache/row
  disagreement always resolves to the rows (fingerprint mismatch ⇒
  recompute).
- **No stored-derived-state drift.** Every cache is keyed derivation
  (R21/R44); no cache is ever read without its key check; no
  "mark-as-current" flags — currency IS key equality.
- **No second seeding or config path, no generated bootstrap authority**
  (the corpus rows are computed by the same analyzer derivation, only
  earlier), **no startup fallback derivation** (the one deliberate
  design refusal that keeps startup honest), **no per-cluster copies of
  release artifacts**, and **no janitor/batch cache-refresher process** —
  write-through or explicit apply are the only writers.

## 8. Open owner decisions (≤3 options each)

1. **Divergence-cache granularity (row 5 — home is SETTLED by the second
   addendum: db facts in the cluster database, R44a).**
   (a) one no-history cache entity holding the complete divergence
   pure-data delta as one value — simplest same-tx update, one read at
   boot; (b) per-identity delta facts on the corpus rows themselves —
   finer invalidation but a boot-time scatter read and a wider schema
   surface; (c) one entity + a bounded row cap that falls back to full
   divergence re-derive past it (breaker posture).
   **Recommendation: (a)**, with the R27-style breaker of (c) as a config
   fact — the delta value is bounded by what agents author, and one
   entity keeps the same-tx write O(1) transactions wide.
2. **sci base-load strategy if S6 measures it over budget.**
   (a) precomputed load plan only (ship regardless — it is nearly free);
   (b) precompiled base-context builder namespace emitted at pre-process
   (§3.5.3); (c) lazy base (build after ready, first claim waits).
   **Recommendation: (a) now, (b) on measured need; never (c)** — (c)
   moves the tax onto the first agent turn, which is a governor in
   disguise.
3. **Apply ergonomics.** (a) `bin/seon up` auto-applies when the cluster
   is unapplied or identity-mismatched (one command UX, still explicit in
   the log); (b) strict: `up` refuses and the human runs
   `cluster apply`; (c) auto-apply only for memory/ephemeral clusters,
   strict for file-backed.
   **Recommendation: (c)** — lanes keep one-command ephemeral clusters,
   while a durable cluster never changes population without an explicit
   operator act (the config precedent's spirit).

## 9. Implementation units (Sol lanes, ordered)

Dependency spine: S1 → S2 → S3 → S4 → S5; S6/S7 ride alongside from S2 on.
S1 is the already-dispatched bootfast lane, restated for completeness.

1. **S1 bootfast (dispatched):** D1 de-quadratic + D2 in-process
   projection reuse + D4 gap instrumentation. Acceptance: fingerprint
   byte-equality regression; existing schema/admission suites; fresh-reset
   live proof with per-phase timings; the §3 REPL probe result recorded.
2. **S2 rowsidecar (D3, now mainline):** `program-rows.edn` +
   base-load-plan emission in `program_artifact.clj`;
   `database-initialization` consumes it under digest guard; the CLJS/JVM
   row-parity regression (build-row vs `var->fn-row` byte equality over
   the full population) runs at the build gate. Acceptance: parity green;
   fresh boot consumes sidecar rows with the runtime indexer path deleted
   in the same refactor (one mechanism); measured indexing window ≈0.
3. **S3 pageplan + apply:** `page-plan.edn` (artifact × manifest key);
   `bin/seon cluster apply` absorbing seed + config reconcile + initial
   agent; applied-identity facts (release digest + init fingerprint +
   manifest digest) written at completion; idempotent re-apply proof;
   crash-mid-apply resume proof (re-run of the initpage falsifiers
   against the new entry path); new-cluster spin-up timing measured
   (addendum §2 numbers become MEASURED here).
4. **S4 startgate:** startup identity verify + loud mismatch error with
   remedy; removal of reconcile/initial-agent/derivation calls from
   `start-runtime!` and the JVM mains; the `build-projection` compose
   arity (base pure-data ⊕ divergence delta, §2) with the cold arity as
   its oracle; row-4 base admission + row-5 divergence overlay fast path
   inside `reconcile-committed!`; collect-only `register!` under verified
   identity. Acceptance: applied cluster boots with ZERO population-scale
   derivation log lines; a cluster WITH divergence (agent-registered
   schema + durable defn) resumes through the overlay path with the
   composed projection byte-equal to cold; the mismatch path proven live
   (boot old artifact against newer applied cluster ⇒ exact refusal);
   ≤10s measured per process on the target topology, budget table filled
   with measured numbers.
   **S4 checkpoint:** implementation, Bun live proof, divergence equivalence,
   and old/new mismatch refusal are complete. Graduation remains open because
   the writer measured 13.88s and the claimant/web-render JVM processes remain
   unmeasured; see §4.
5. **S5 maintain:** agent registration/durable-defn tx carries the row-5
   divergence-cache delta in the same transaction; hot-reload publication
   recomposes; kill-between-any-two-operations restart proof (restart at
   random points during a registration burst finds a current cache or
   performs a LOUD delta-only repair of the divergence layer only).
   Acceptance: the kill-matrix proof + an O(delta) timing assertion
   (cache update cost independent of total population size, proportional
   to the delta).
6. **S6 budgetproof:** boot-phase timing instrumentation across all four
   process kinds (the D4 pattern extended), including the first sci
   base-load measurement; publishes the measured budget table into the
   issue and this file's §4.
7. **S7 equivalence (standing regression):** cached-resume vs cold-derive
   byte equality on the same identity — projection pure-data
   `canonical-data-string` equality, sidecar row-vector equality, and
   fingerprint equality — wired into `bin/test-writer` (population-level)
   plus one reset-boundary live proof per schema/acquisition change (the
   standing checkpoint rule). This regression is the permanent guard that
   the caches remain skip-only.

## 10. Honesty ledger

- VERIFIED this investigation: §1.1 digest machinery and sidecar publisher;
  §1.2 initialization paging/fingerprint/receipt (`protocol.cljc:1512-1704`,
  `:279-333`); §2 rows' current compute sites (`client.cljs:1726-1848`,
  `admission.cljs:240-332`, `schema.cljc:605-886`); §3.1–3.4 sci internals
  (`types.cljc:249-278`, `interpreter.cljc:29-62`, `jit.cljs:1-13,401-411`,
  `analyzer.cljc:765-797`, `core.cljc:318-323`,
  `host/context.clj:1093-1344`); cluster independence of sidecar inputs.
- PRIOR-VERIFIED (accepted research): the measured timeline and quadratic
  structure ([[boot-time-design-2026-07-23]]); sci Var mutability and fork
  semantics detail ([[sci-internals-opportunities-2026-07-21]]).
- ESTIMATED (falsifiers named): every §4 phase number except
  instrumentation 0.37s and store-open; the 81s window's 46/35 split; the sci
  base-load cost (S6's first job). Section 5's full apply wall times and
  64-vs-256 page-count comparison are now MEASURED, while the internal
  one-shot-client/config/agent split remains UNKNOWN until D4/S6 reports.
- INFERRED (design, not source): the collect-only `register!` gate's exact
  plumbing; the row-5 same-tx delta shape (the schema for the cache entity
  is S5's to design under review); the divergence-layer content
  set-difference definition and the `build-projection` compose arity
  (design consequences of the second addendum — S4 proves compose ≡ cold
  before anything consumes it); apply command naming.
