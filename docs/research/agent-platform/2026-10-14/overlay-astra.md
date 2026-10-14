---
title: "Incremental program acquisition: derive from the held frozen value"
type: research
status: independent Astra proposal; measured prototype, no implementation installed
date: 2026-10-14
lane: overlay-astra
---

# Proposal

`seon.sci.eval/program-context` caches a frozen SCI context by program content, but a changed program is derived by interpreting its whole difference from the loaded program. Keep that content identity and derive a changed value from the preceding **frozen program context**, interpreting the changed declarations and their affected closure. Never copy roots out of the evaluating agent.

The smallest correct first implementation reinterprets the affected closure, including already interpreted callers. Merely rebinding a changed function is insufficient: a retained caller invoked without an active SCI context still uses its defining context. This was reproduced below. The proposal continues the unfinished E5 of [the October 12 hybrid](../2026-10-12/hybrid-immutable-env.md#4-phases-about-fifteen-minutes-each-red-proof-first-cost-bounds-through-seonprofilemeasure), not a second context architecture.

Recommend option 3 of [install-row-reruns](install-row-reruns.md#options-owner--opus-decision-link-3-is-the-one-repl-paths-re-acquisition): deliver an acquisition failure beside the defining reply's original value. Acquisition and recording need separate corrections; reducing interpretation alone does **not** establish once-only recording.

## Evidence and boundaries

Read the assignment, AGENTS.md, the data-oriented Clojure, REPL, Datahike and testing skills, `install-row-reruns.md`, `dig-overlay-rebuild.md`, `ctx-cost.md`, the agent-loop spec, the earlier immutable-context hybrid, relevant history, callers and dependency sources. This proposal has not read the other designer's proposal. Only this note is owned and changed.

Initial default: PID 10712, archive `de1f48c23ce0da42f5961bbc44a2dec5a03e5ab6`. Both status surfaces reported `:faulted`: 17 untested-hot warnings, 4 agent-error signatures, 65 errored receipts, one failed test and one open fault. These are a degraded baseline, not green evidence. Its cache was approximately at its 512 MB budget. Shell HEAD initially `c31780f9d`; the working tree had many other lanes' edits.

Default restarted during the experiment. The bridge first answered no live JVM, then starting; no restart was requested by this lane. Reconnected to PID 88215, archive `243683f1d8794948bdd6c07ca773bab72362b37f`. The pre-restart agent branch was `:agent-79eeda8a2f98`; the resumed agent received `:agent-dec28655d10f`. Post-restart correctness agent `overlay-astra-correctness` held `:agent-ea4fd385a8d1`. Thus the two sets below are explicitly different populations and JVMs, not a before/after implementation benchmark.

SCI probes used these own agents. The first form read `seon.context/current-state`. JVM mode rejected a named non-root measurement agent with "JVM mode is root's"; subsequent host measurements were root JVM forms invoking the ordinary MCP entrance with **this lane's SCI agent**, never root SCI or host source reload. The rejected measurement agent was `overlay-astra-measure`. Its cleanup is included below.

### Complete requests on the original JVM

Setup, one request:

```clojure
(ns probe.overlay-astra)
(defn f {:malli/schema [:=> [:cat] :int]} [] 1)
(defn caller {:malli/schema [:=> [:cat] :int]} [] (f))
(def value (f))
(def unrelated-value 10)
```

Each subsequent line was a separate request, wrapped in `seon.profile/measure` around `seon.mcp/eval-clj` in root JVM mode. The target remained agent `overlay-astra`, mode SCI, cluster default. Read the returned request cost, rather than using the host wrapper's allocation as the target request's allocation.

| Request | Complete measured ms | MCP request ms | Request step allocation MB | `install-row!` entries | Overlay derivations |
|---|---:|---:|---:|---:|---:|
| Setup above | 466.52 | 461.0 | 374.4 | 9 | 1 |
| `(defn unrelated {:malli/schema [:=> [:cat] :int]} [] 2)` | 419.53 | 413.9 | 278.2 | 10 | 1 |
| `(defn f {:malli/schema [:=> [:cat] :int]} [] 3)` | 254.26 | 248.8 | 229.2 | 10 | 1 |
| `(def unrelated-value 11)` | 203.55 | 197.6 | 165.3 | 10 | 1 |
| `[(caller) value unrelated-value]` → `[3 3 11]` | 73.21 | 65.4 | 8.3 | 0 | 0 |

The first three changes ran test-step for 140.9, 78.2 and 82.1 ms respectively. They made one request-owned recording transaction each. The plan also contained an inherited override of `seon.design-prototype-test/sections`; this is a foreign program input, not a declaration this lane changed. The earlier 37-install number in `ctx-cost.md` is a **first held-task request**, not evidence of 37 installs for every unchanged or changed request. This experiment does not reuse that number as its baseline.

Result handles for the measured wrappers: `result/e3bf0603bfdd6`, `result/e38b671e06410`, `result/e080db1fe45b9`, `result/e800219e94763`. Their printed values include the complete nested replies. `seon.profile/measure` call counters are JVM-window observations, subject to concurrent work; request step allocation belongs to the request. No retained-heap reduction is inferred from allocation volume.

### Acquisition prototype, alternating full and incremental derivations

Capture a prior immutable database and `program-context` in an evaluation result, change the program through the agent's ordinary REPL, then compare:

- full: `derive-overlay-ctx` of the loaded program context and the complete overlay plan;
- incremental: the same function on the held frozen program context, with only the selected declarations in the plan and those declarations removed from the inherited `::interpreted` set.

This is a prototype of the acquisition stage, not an installed replacement for the host-bound request machinery. Neither branch's production owners nor default's compiled Vars were redefined. Full and incremental samples use the same target database. The outer agent evaluation records the experiment normally. Context construction, interpretation, contracts and freeze are inside the measured thunk; complete request recording and key discovery are not.

| Change / original JVM | Full installs → incremental | Full ms, all samples | Incremental ms, all samples | Full allocation MB, all samples | Incremental allocation MB, all samples |
|---|---:|---|---|---|---|
| New unrelated function | 11 → 1 | 37.52, 31.00, 29.08 | 7.24, 6.82, 6.42 | 60.294, 57.354, 57.356 | 10.355, 10.355, 10.355 |
| Changed unrelated value, 11 → 19 | 11 → 1 | 34.22, 42.20, 43.48 | 7.52, 7.26, 7.67 | 57.474, 57.437, 57.436 | 9.556, 9.556, 9.556 |

Results were respectively `[3 3 42]` and `[7 7 19]` through both contexts. Handles: `result/ea6a20a2992c7`, `result/ecbacdd35bd71`. These examples have no dependents of the changed declaration. The incremental thunk still lets the existing owner discover the selected seed's closure.

An initial changed-function prototype installed only `f` and `value`, retained `caller`, and returned `[7 7 11]` inside SCI in 4.03–4.84 ms / 7.099 MB versus 30.38–32.86 ms / 57.43–58.78 MB. **That version is rejected**, despite its attractive number, for the context-less call counterexample below. Its handle is `result/eeb74f47e363d`; it is not the proposed performance result.

On the restarted JVM, a fresh clean probe namespace had `f`, `caller`, `value`, `unrelated-value` and `unrelated`. Change `f` from 3 to 7. The incremental thunk now discovers the reverse closure **inside** the measurement and reinstalls all three members: `f`, `caller`, `value`.

| Correct changed-function prototype | Installs | ms, all samples | Allocation MB, all samples | SCI result | Context-less caller |
|---|---:|---|---|---|---:|
| Full derivation | 7 | 34.78, 32.14, 32.38 | 34.756, 33.546, 33.545 | `[7 7 11]` | 7 |
| Incremental derivation | 3 | 16.21, 14.98, 15.32 | 15.954, 15.954, 15.954 | `[7 7 11]` | 7 |

The prior frozen context still answered `[3 3]`. Median acquisition time improved 32.38 → 15.32 ms (52.7%); allocation approximately 33.546 → 15.954 MB (52.4%). Handle `result/e2a0f38af867f`. The preceding **unmodified** complete defn request took 240.7 ms / 165.5 MB step allocation, with test-step 55.0 ms. There is no measured complete-request result for an installed incremental implementation; that remains the implementation's required proof.

### Two correctness counterexamples

SCI's pinned `sci.impl.fns/in-defining-ctx` uses the defining context when a function is called with no active context. In an own-agent SCI evaluation:

```clojure
(let [a (sci.core/init {})
      _ (sci.core/eval-string* a "(defn f [] 1) (defn caller [] (f))")
      old (sci.core/freeze a)
      b (sci.core/fork old)
      _ (sci.core/eval-string* b "(defn f [] 2)")
      root (sci.core/eval-string* b "caller")]
  {:inside-old (sci.core/eval-string* (sci.core/fork old) "(caller)")
   :inside-new (sci.core/eval-string* b "(caller)")
   :without-context (binding [sci.ctx-store/*ctx* nil] (root))})
;; {:inside-old 1, :inside-new 2, :without-context 1}
```

Handle `result/e3e462826d122`, 4 ms / 0.4 MB for the outer form. Therefore a callable's derivation depends on its transitive declared reach even when normal SCI calls late-bind Vars. Reinterpret affected callers; do not optimize them away based only on an in-context result.

Second, evaluating `(defonce v 2)` on a fork retaining `(defonce v 1)` answers 1; a cold context answers 2 (`result/ec62877119579`). A derivation cannot retain an old bound root while claiming the new initializer's content. SCI already owns the operation needed to rebuild selected declarations without replacing Var identities:

```clojure
(let [a (sci.core/init {})
      _ (sci.core/eval-string* a "(ns oa.defonce) (defonce v 1)")
      old (sci.core/freeze a)
      b (sci.core/fork old)
      v (sci.core/resolve b 'oa.defonce/v)]
  (binding [sci.ctx-store/*ctx* b] (sci.impl.vars/unbind v))
  {:new (sci.core/eval-string* b "(in-ns 'oa.defonce) (defonce v 2) v")
   :old (sci.core/eval-string* (sci.core/fork old) "oa.defonce/v")
   :same-var (identical? v (sci.core/resolve b 'oa.defonce/v))})
;; {:new 2, :old 1, :same-var true}
```

Handle `result/e3e73615a2d70`, 1 ms / 0.2 MB. An earlier probe incorrectly resolved `user/v` although the nested interpreter inherited the outer namespace, yielding nil; the explicit namespace above removes that probe error. This is a dependency experiment, not a new evaluator. The proposed unbinding happens only in the program builder, not in the agent's live REPL, where Clojure's ordinary `defonce` semantics remain.

### Refusal recurrence and delayed delivery reproduced

On restarted default, separate own-agent requests:

```clojure
(def branch-db (seon.db/db))
(def broken (seon.ref/brief branch-db 'seon.edit/single-form))
;; Next request: read occurrence ids/counts.
(defn untouched {:malli/schema [:=> [:cat] :int]} [] 22)
;; Next request: read occurrence ids/counts again.
```

The defining reply returned both Vars with no acquisition error, 527.8 ms / 380.6 MB step allocation / three transactions. The next request delivered `install-row!`'s failure for `broken`, signature `9eb599f195809f6f3e7f996316f179342b54670fd4182375ca3422adefedb480`, count 1. After the unrelated defn, the next reply delivered count 3. Handles `result/e9b641ce22250`, `result/ebd25d2eb96f8`, `result/e0772eb89d3dc`. The defn request took 828.2 ms / 465.1 MB step allocation. Its JVM-window allocation included other work and is not assigned to this request.

Source correction to the dig: `base-ctx` calls `record-acquisition-refusals!` **directly**. Later `acquire!` also calls `record-refusals-once!`. Thus the latter's `[program-revisions target]` cache does not own every recording entrance. Furthermore `interpret-overlay!` extends the parent's acquisition, including previous refusals. Keeping that accumulated vector while narrowing selected declarations would still report old diagnostics as new work. Fix both producers; merely changing the refusal cache key would hide the symptom.

The value population is not rare: the fresh correctness branch contained **979** declarations with `:seon.fn/arglists "()"`, of which **95** declared a call to `clojure.core/delay`. The probe value's stored calls were `[clojure.core/def probe.overlay-astra-correct/f]` (`result/ed1c1a1ad6b3a`). These are producer-declared edges, not symbol scanning.

## Existing flow and library guarantees

`seon.cluster.agent/acquire-context!` / the test request → `seon.sci.eval/base-ctx` → `program-context` → program/dial revisions → `overlay-plan` / `derive-overlay-plan` → `program-content-key` → `seon.cache/lookup` → `derive-program-context` → `derive-overlay-ctx` → `interpret-overlay!` → `install-selected!` → `install-row!` → `freeze-program`. Program facts came from the ordinary REPL recording transaction. The cached value has no custody; a caller's fork gets custody. This separation stays.

| Dependency and pin | Seam verified in source | Guarantee / cost relevant here |
|---|---|---|
| SCI `eb3290d5c5ed72133ae1947492ecd324413868ed` | `sci.core/fork`, `sci.core/freeze`, `sci.core/bind-root!`, `sci.impl.vars/unbind`, `sci.impl.fns/in-defining-ctx` | Fork a frozen environment without writing it; contextual root bindings retain Var identity; a context-less callable uses its defining context. Fork is structural sharing, not interpretation. |
| Datahike `c9e57c6fb65365d5ce92b59854fb02ea59054fe7` | `datahike.versioning/parent-commit-ids`, `datahike.versioning/commit-as-db`, `datahike.db/since-pred`, `datahike.db`'s `clojure.data/Diff` implementation | Commits/bases identify values. `since` filters by transaction time; DB diff walks sorted EAVT datoms. Neither inspected seam is a changed-node-only program diff. |
| Malli `dd13ff81139159778edb0a67b491eccc0baa991d` | `malli.core/validator`, `malli.core/-instrument` | Compile/retain validators and build contract wrappers from schemas; do not build an overlay-specific validator or wrapper. |
| Existing Seon cache | `seon.cache/lookup` | One concurrent derivation per retained content key, byte-bounded; no new mutex, atom or cache. `seon.cache/retained` walks all entries and is diagnostic-only, not a nearest-parent lookup. |
| Existing dependency/read owners | `seon.fn/reverse-closure`, `seon.db/read-evidence`, `seon.db/read-evidence-current?` | Declared invocation/value/macro-use edges; database dependency evidence with revisions and semantic replay. No scanned symbols or commit-wide freshness stamp. |

SCI's **forked Var isolation and freeze are maintained-fork behavior**, not a claim about upstream SCI. The local `origin/master` version of `sci.core/fork` only copies the env atom and has no `freeze`. No dependency bump is proposed. Historical architecture text claiming that `bind-root!` copies each inherited Var is superseded by the pinned implementation and these probes; that text is outside this note's ownership.

The previous stale-base fixes `86430115b` and `ab8f218d4` prohibit transferring evaluated agent roots or trusting a mutable execution base as the target program. A held frozen source value plus an actual derivation satisfies that boundary; changing a basis label does not.

## Data and derivation

### Inputs and content identity

Keep the loaded `[store loaded-commit]`, canonical plan content, projection content and arming dials in `program-content-key`. Branch name, target connection, requesting agent, error-recorder identity and a predecessor's commit are **not** semantic cache inputs. Equal programs still share the identical cached value. A supplied prior frozen value is only a derivation input; the target key determines the result.

Carry the frozen value the caller already holds explicitly into `program-context`'s derivation request. The automatic test step must receive the same prior program value and committed change data. It must not rediscover a parent by scanning `seon.cache/retained`, walk arbitrary commit history looking for a cached ancestor, or use the live agent context as the parent. When there is no held parent, use the existing loaded-base derivation. A platform reload changes the loaded base and derives against that base.

The frozen program value needs a manifest keyed by declaration identity, containing the already held definition digest and declared dependency inputs. Use the existing `:seon.program/identity`, `:seon.program/definition-digest`, namespace bindings, contract/schema references and `:seon.db/read-evidence` shapes. The program snapshot is their existing runtime owner; no database copy of this derived manifest. Name its enclosing schema at `seon.sci.eval` before implementation and compile it with `malli.core/validator`. Proposed runtime members must first be registered in the vocabulary/schema phase; none is claimed installed here.

A declaration's reusable derivation is determined by its own admitted definition, resolver bindings, applicable contract/schema/dial content, declared transitive dependencies and any captured database reads. This is a **validity relation over the held manifest**, not a cache of naked function roots. Avoid recursively hashing every reachable subgraph for every declaration; compare changed input identities once, then walk reverse reach once. Cyclic function groups are handled as the closure the existing owner returns, not recursive cache lookups waiting on themselves.

### One incremental derivation

1. Read one committed target database. The writer's transaction report and the request's committed declaration identities already name its changed candidates. Compare their target digests against the held manifest; include retractions and namespace/contract changes. For acquisition lacking that change value, compare the existing canonical manifests. Never assert that a context acquired a target because the writer says it changed something.
2. Compute the affected set from changed identities through declared edges. For removal/retargeting, include old and new reach: a deleted edge must not erase the evidence that an old caller depends on the removed Var. Resolver changes select declarations analyzed under those namespace bindings. Contract/schema changes use `seon.schema/dependent-schema-keys` and the schema owner's function dependents, not a full wrapper reinstrumentation.
3. Fork the held **frozen program value** using SCI. Remove retracted namespace entries through the existing declaration-install owner. Unbind selected interpreted declarations in the child, using SCI's contextual `unbind`, preserving their Var identity. This is rebuilding the selected definition from its declared inputs, including `defonce`; it also prevents a failed replacement from leaving a successful old root callable. Host-bound platform declarations retain their installed compiled semantics under the existing classification.
4. Install selected namespace bindings, then the affected declarations in the existing namespace/definition order. `install-selected!` already declares forward Vars and orders value initializers by declared calls. Reinterpret affected already-interpreted callers and tests as well as changed definitions; retain existing lazy acquisition only where it can preserve the context-less callable guarantee. Do not use inherited membership in `::interpreted` as evidence that a changed derivation is current.
5. Replace the selected declarations' acquisition outcomes in the manifest; retain untouched outcomes by identity. An unchanged refusal remains diagnostic data, not a newly produced failure. Freeze and return the target program value through the existing content-keyed cache. Update the owning execution only at its ordinary idle boundary. No writes into a held context and no root transfer from the author's context.

Database-read dependencies need one further input to acquisition: a program whose initializer reads data can change meaning without a program-attribute write. Validate only the held initializer read evidence against the target database before declaring an unchanged context current. Keep per-read source identity; an explicit historical database input stays historical. `read-evidence-current?` already owns revision/replay semantics. Changing unrelated data must not run the initializer; changing its read must add that declaration and its dependent closure. An absent observation is not proof of empty dependencies. The current overlay path does not capture this evidence, so this is a required phase, not an installed guarantee.

### What cost follows

Let P be the loaded declaration population, B the branch difference, D the changed identities, A their affected declarations and edges, R retained initializer read dependencies, and S the selected source size.

| Operation | Proposed work | Current residual / qualification |
|---|---|---|
| Unchanged source, no read-dependent initializers | Revision checks and a content-cache hit | No declaration interpretation, no fork for an unchanged held agent. |
| Derive with a held parent and committed change identities | D digest lookups, affected closure, S parsing/evaluation, changed namespace bindings, freeze | Interpretation O(A + S), independent of unrelated B. |
| Content-key construction | Update the held canonical manifest for D; hash its existing digest data | Canonical whole-plan hashing remains O(B). Do not label the whole request O(D). |
| Closure discovery | Existing indexed call edges plus declared edges | Current `closure-walk` also acquires declared invokers and scans test subjects. This is a measured owner cost, not strictly O(A) today. |
| Initializer read currency | Check R captured dependencies; changed ones seed the same closure | At least O(R) unless the existing dependency revisions discharge a shared check. No new refresh index without a measured need. |
| Missing parent / restart | Existing cold loaded-base + branch derivation | O(P) for a missing loaded base; O(B + reach) for cold branch interpretation. |
| Stored error / shown reply | Only new acquisition observations plus the existing error owner's recording; shown references/data | No re-execution to display a refusal. |

Measured `derive-overlay-plan` on the restarted three-definition branch alone: **6.80 ms / 10.695 MB**, three `definition-digest-of`, one `function-symbols`, two `namespace-digests` (`result/ef01553175665`). That is comparable to the narrow derivation, so plan discovery belongs in the second measured slice. Retain and update the already held plan from committed changes instead of rereading all function symbols and namespaces. Reusing a held canonical digest map still hashes O(B) small digest data; it eliminates O(P) database discovery, not the hash's linear work. A new Merkle structure or a per-row cache is not justified by the present measurements.

Do not describe `seon.db/entities-changed-after` as O(D): the inspected implementation filters an attribute's native index by transaction time. Nor is `clojure.data/diff` over Datahike a subtree diff. If later measurements show manifest hashing dominates, bring that exact library gap to the owner instead of inventing a transaction stamp or secondary registry.

## Refusals, defining replies and lifetime

The defining request's test preparation already needs the newly committed program context. Return that derivation's acquisition diagnostics through the same reply fold, with the source declaration and result handle, after its actual outcome is known. Preserve the original evaluated Var/value. A failed initializer must be visible even when no test body is selected. No second interpretation merely to produce the diagnostic, no binding the shared context to the author's connection, and no error converted into success.

Separate **the content-derived failure observation** from **a consumer reading that observation**. `derive-overlay-ctx` produces an outcome per selected declaration; a cached or inherited outcome is read-only data. The recording entrant records a new observation once through the existing error writer and all consumers refer to it. Replace the accumulated refusal-vector replay in `interpret-overlay!`, the direct recording in `base-ctx`, and `record-refusals-once!`'s whole-program/connection cache as one slice. Otherwise the existing two consumers can still increment the count even after interpretation is fixed.

The error owner's identity must name the declaration's derivation inputs, not the whole plan or connection. The writer must provide idempotent acceptance of the **same observation identity** and count genuinely distinct observations; no acquisition-side signature filter. This is a required contract change at `seon.error/recording` / `seon.error/commit-call`, with an explicit proof for two consumers of one observation. Existing `:seon.error/obligation` is **not** that seam: it names a wake, and `commit-call` still increments its count. Do not overload it. This design specifies the semantic input; the new observation identity's exact schema is for that narrow error-owner phase, after checking the ongoing errors work.

There is an unavoidable scope decision. An evicted arbitrary live value cannot be reconstructed without running its initializer, and a restarted JVM has no old object. A byte-bounded cache cannot promise process-eternal execution-once for arbitrary Clojure initializers. Existing source-load semantics already allow cold initialization. Recommend: **one initialization per valid retained derivation; unrelated warm changes never reinitialize; cold acquisition may initialize anew**. Keep a content-derived diagnostic's durable identity separate from that cache lifetime. If the owner means no re-execution even after eviction/restart, it requires a different value model (durable, reproducible result data or permanently retained objects), beyond this overlay cut. The note does not quietly claim that stronger guarantee.

Similarly, a clock, random draw, external I/O or a mutable object dereference is not automatically captured database read evidence. Do not certify content purity that the producer cannot observe. Ordinary Clojure source-load behavior remains; stronger semantics for such inputs need an owner decision, not a refusal fence on all computed defs.

Memory is part of correctness: a child can keep roots/closures originating in an ancestor builder alive. Charge the cumulative branch-specific reachable program structure relative to the loaded base, not merely the last edit's allocation; retain no explicit predecessor chain once the child is frozen. Extend `seon.program-context-weight/retained-bytes` to a long edit sequence and parent eviction before changing `overlay-weight`. Existing allocation results do not establish that retained-memory bound. Do not add pinning to hide an undercharge.

## Phases and proofs

All phases are **proposed, not implemented or green**. Extend the existing E5 task rather than file a second umbrella task. Each phase carries red behavior/cost evidence before its body. Proposed deftest names below are names to create, not references to installed Vars.

1. **Selected derivation from a frozen parent.** Owner `seon.sci.eval`; resources for its runtime contract; acquisition tests. Declare the manifest/request contracts, then implement parent input, changed-selection replacement and contextual unbinding using the existing installer. Red proof: proposed `seon.sci.acquisition-cost-test/a-change-reinterprets-only-its-dependent-declarations`. Through `seon.mcp/call`, table the unrelated function, changed callee and changed value requests; compare small and larger unrelated programs. Assert unchanged values retain identity, selected results agree with a cold derivation, old contexts keep old results, and installation counts follow the selected closure. Include context-less calls, `defonce`, macro expansion, captured function values, deletion/re-add, namespace aliases and a cycle of ordinary functions. A `seon.profile/measure` assertion covers each complete request under the existing ordinary-operation bound; parent/change measurements of the same end-to-end probe must show a gain before commit. The 15.32 ms stage result is not that proof.
2. **Pass committed change data and reuse the held plan.** Owners `seon.turn`, `seon.test`, `seon.cluster.agent`, `seon.sci.eval`. Red proof: extend proposed E5 `seon.sci.eval-test/a-settled-program-context-equals-its-rebuild`: zero `derive-overlay-plan` against the loaded base on a covered change and its following unchanged request; definition-digest reads follow D, not P/B; two branches reaching equal program content share a context; no mutable agent root enters it. Measure complete MCP request time/allocation before/after. Preserve both installed `seon.test.redefined-callee-test` stale-body regressions. Do not delete `acquire!` or all its callers speculatively in the first slice; delete an entrance only in the commit converting its last caller.
3. **Read-dependent initializer currency.** Owners `seon.sci.eval` and the existing read-evidence capture entrance. Red proof: proposed `seon.sci.acquisition-cost-test/an-initializer-reuses-only-current-read-evidence`, with a canonical fixture: unrelated data write leaves its initializer uncalled and root identical; the captured read changing recomputes it and its dependents, including an empty query becoming nonempty; an explicit historical DB stays historical. Assert through the complete request's `seon.profile/measure`, not just a key helper. Same-source data changes must not be hidden by the program-revisions fast path.
4. **Record one observation and deliver it in the defining reply.** Owners `seon.error`, `seon.sci.eval`, reply/test-step owner. Red proof: proposed `seon.sci.acquisition-cost-test/an-unrelated-definition-does-not-repeat-an-initializer-refusal`, reproducing `branch-db` / `broken` above. The defining reply contains the failure beside the Var; a second consumer and an unrelated defn leave the occurrence count unchanged and the initializer uncalled. Changing its actual inputs can produce a new observation. Include a rejected recording result beside the value; missing durability is never success. This proof must fail on the measured 1 → 3 behavior. A separate table row proves two concurrent consumers accept the same observation once at the writer; it is not a process-cache deduplication test.
5. **Retained-memory acceptance.** Extend installed `seon.sci.eval-test/a-program-context-weighs-its-own-declarations` with successive edits and eviction of intermediate values, using the existing retained-size tool on an isolated check JVM. The red is a demonstrated undercharge, if present, not a guessed multiplier. If retention grows by history despite a fixed final program, fix the SCI/context owner before accepting the optimization; no unbounded ancestor cache. Run the end-to-end cost probe again only if that fix changes the implementation.

Existing tests to keep in the implementation's reaching set include the unchanged acquisition/content-sharing/weight tests, `seon.test.redefined-callee-test/a-test-step-runs-the-redefinitions-its-eval-request-made`, `seon.test.redefined-callee-test/a-branch-acquired-from-a-writers-base-runs-the-program-its-head-holds`, and acquisition refusal/custody tests in `seon.sci.eval-test`. Select by current reach at implementation time; historical green is not current evidence.

## Decisions and deletions

| Option | Cost and guarantee | Sacrifice |
|---|---|---|
| **Frozen parent + affected closure (recommended)** | Existing SCI/cache/install machinery; measured 1 install for unrelated edits and 3 for the changed-callee case; eager work proportional to affected source. | Must fix manifest inputs, errors and memory accounting; canonical key hashing remains O(B); cold reinitialization remains. |
| Per-declaration cached roots | Potentially reuse a declaration after every parent is evicted. | Roots capture Var identities and defining contexts. Correct composition needs more than source/reach digests; F57-class risk. Not justified by the measured incremental win. |
| Keep full derivation and suppress duplicate errors | Small visible-output change. | Whole-plan initialization and allocation remain. Rejected: it changes reporting around the wrong work. |

Delete full-plan interpretation on a covered warm change, repeated discovery of all branch/loaded function names on that path, inherited `::interpreted` membership as a changed-row skip, old refusal-vector accumulation for selected rows, and the two acquisition recording entrances with their whole-program target cache. Keep cold derivation, the one content cache, SCI's context/Var mechanisms, the error owner and the single REPL path. Already deleted root-transfer machinery is not proposed for deletion again.

Owner decisions before implementation: confirm cold initialization semantics; confirm content-derived acquisition observations versus execution-error recurrences; and retain the context-less-call guarantee from E5. The measured counterexample resolves the last question in favor of reinterpreting the affected callable closure. No new task lifecycle clause is needed.

## Reproduction of the correct measured derivation

Use a new own agent. Define the five-function/value setup described above, then evaluate the capture expression; keep its returned result handle as `prior` in the later expression (a lexical binding, not a program `def` of a host context):

```clojure
(let [database (seon.db/db)
      loaded ((resolve 'seon.sci.eval/loaded-program) database)]
  {:database database :loaded loaded
   :context (seon.sci.eval/program-context database loaded)
   :seon.render/ai {:commit (seon.db/commit-id database)}})
```

After changing `probe.overlay-astra-correct/f` from 3 to 7 through the ordinary REPL, the measured core was:

```clojure
(let [prior result/e8493993a20d0 ; replace with this run's capture handle
      database (seon.db/db)
      loaded (:loaded prior)
      projection (seon.db/carried-projection database)
      plan ((resolve 'seon.sci.eval/overlay-plan) database loaded)
      base (seon.sci.eval/program-context loaded loaded)
      derive (resolve 'seon.sci.eval/derive-overlay-ctx)
      sample
      (fn [kind]
        (let [m (seon.profile/measure
                 #(if (= kind :full)
                    (derive base database loaded projection plan)
                    (let [closure (seon.fn/reverse-closure
                                   {:seon.db/db database
                                    :seon.fn/seeds #{'probe.overlay-astra-correct/f}})
                          selected (:seon.fn/affected closure)
                          parent (update-in (:context prior)
                                            [:seon.sci.kernel/program-snapshot
                                             :seon.sci.eval/interpreted]
                                            (fn [held] (apply disj held selected)))
                          delta {:seon.sci.eval/overridden selected
                                 :seon.sci.eval/added #{}
                                 :seon.sci.eval/namespaces []
                                 :seon.sci.eval/tests []
                                 :seon.sci.eval/closure selected
                                 :seon.sci.eval/closure-tests (:seon.fn/tests closure)}]
                      (derive parent database loaded projection delta))))
              ctx (sci.core/fork (:seon.profile/value m))
              root (sci.core/eval-string* ctx "probe.overlay-astra-correct/caller")]
          {:kind kind :ms (:seon.profile/ms m)
           :bytes (:seon.profile/alloc-bytes m)
           :install (seon.profile/calls-of m 'seon.sci.eval/install-row!)
           :value (sci.core/eval-string* ctx
                    "[(probe.overlay-astra-correct/caller) probe.overlay-astra-correct/value probe.overlay-astra-correct/unrelated-value]")
           :without-context (binding [sci.ctx-store/*ctx* nil] (root))}))]
  {:old (sci.core/eval-string* (sci.core/fork (:context prior))
          "[(probe.overlay-astra-correct/caller) probe.overlay-astra-correct/value]")
   :samples (mapv sample [:full :delta :full :delta :full :delta])})
```

For the unrelated cases, select only the changed symbol, retain the appropriate prior capture, omit the supplied closure so the installer walks it, and compare the corresponding values. No cache eviction or other lane's resources are needed. This prototype does not implement the proposed unbinding, read-evidence, recording or key-discovery changes; their smaller counterexamples and phase proofs are stated above.

## Verification and cleanup

The note changes no runtime test-selection input. Full `clj-kondo --lint src script test --cache false` completed in 9,087 ms: **0 errors, 1,665 warnings** (exit 2 for warnings). This is not a zero-findings claim.

An isolated `bin/seon check` of HEAD plus this note passed these existing tests:

- `seon.sci.acquisition-cost-test/a-second-acquisition-of-an-unmoved-branch-interprets-nothing`
- `seon.sci.eval-test/equal-programs-on-two-branches-share-one-program-context`
- `seon.test.redefined-callee-test/a-branch-acquired-from-a-writers-base-runs-the-program-its-head-holds`

Run `59fb8f5678fa`, candidate `ea4245c06600cceb258fd03bedbe2ceb24e42993`: **3 executed, 19 assertions passed, 0 failures, 0 errors**, tests 8,269 ms. Total operator time was 52,980 ms, including 16,148 ms waiting for other throwaway JVMs, 16,629 ms boot and 12,557 ms reported readiness (phase numbers overlap). This is the required isolated platform check, not an ordinary-operation performance claim. The commit names the same three tests. No current red was observed in this named set; broad tiers were not run by this lane.

Foreign boundary: the working tree and HEAD moved under other lanes, and default restarted from `de1f48c23` to `243683f1d`. The isolated check used only this note over its captured HEAD; it reported `deps.edn` changed relative to its live seed. No dependency, source, test, other proposal or other lane's file was edited here. The initial degraded runtime and inherited `seon.design-prototype-test/sections` override are recorded above; neither is silently counted as this lane's work or a green baseline.

After the check exited, root JVM cleanup called `my.agent/retire-branch!` with the explicit default store/routing/connection for exactly the two owned live branches, `:agent-dec28655d10f` and `:agent-ea4fd385a8d1`. Both returned `:my.agent/retired? true`; the combined cleanup took 188.9 ms / 37.5 MB request step allocation. A subsequent registry read verified both branches and their base refs absent. The pre-restart `:agent-79eeda8a2f98` was already absent; no `overlay-astra-measure` agent/branch fact remained after the restart. No other branch was retired. Measurement forms and printed observations are preserved in this note; pre-restart live handles are no longer available.

This is a design-only commit: no installed performance gain, green proposed phase proof, or retained-heap improvement is claimed.
