---
type: dig
status: design round 3 research, 2026-10-12
---

# Design round 3 DIG: Immutable environment (commit-keyed SCI context)

## Goal

An agent's environment (its SCI context: namespaces, Vars, interpreted overlay, armed contracts, world) is a pure function of a commit — `(env commit-id)` — an immutable value forked (never mutated) by any number of agents, cached by content, rebuilt from Datahike/konserve on a miss.

## Mutations in SCI contexts after construction

**In `seon.sci.eval/acquire!` (lines 3725–3793):**
- Line 3744: `swap!` on `::kernel/program-snapshot` (acquired-basis)
- Line 3776: `reset!` on `:env` (fork fork-as-copy via `sci/fork`)
- Line 3782: `reset!` on `::kernel/installed-functions`
- Line 3787: `reset!` on `::kernel/program-snapshot`

**In `seon.sci.eval` (interpret-time mutations):**
- Line 487: `swap!` on `:env` (inherited-var-bindings)
- Line 952: `swap!` on `:env` (assoc refers)
- Line 1575: `swap!` on `:env` (defs)
- Line 3337: `swap!` on `:env` (generations)

**In `seon.sci.kernel`:**
- Line 59–65: thread-local arm + deadline latch (owned by guard, not agent)
- Line 90: `aset` on sampled allocation array (diagnostic, not structural)

## Environment derivation and caching today

1. **Base context** (`::base-context`): memoized at build time, keyed `[loaded-program store-id]`, shared by all agents. Built from `clojure.core`+`clojure.string` + `help`, `my.turn`, `my.message` via `seon.sci.eval/build-base-ctx`.

2. **Overlay plan** (`::overlay-context`): per-branch environment reads from `seon.cache` by key `[base-key (digest plan) dials]`; empty plan bypasses overlay and uses base as source.

3. **Agent fork**: two forks (option A, landed H5) — `branch-ctx` (program copy) and agent fork (private layer with namespace + print session).

4. **Caching kinds** (seon.cache):
   - `::base-context` (hit: ~1 ms, miss: 24–34 ms / 28–44 MB)
   - `::overlay-context` (hit: ~1 ms, miss varies, 314 MB duplicates per fork; H4 targets this)
   - `::reach-graph` (miss proportional to reached declarations; 288 MB at 50% hit rate)
   - `::commit-revisions` (bridge: H1 landed, remembers fork heads; H2 targets moving to Datahike)

## SCI fork/init: immutability boundary

From `reference-code/sci/src/sci/core.cljc`:
- **`sci/init`**: creates a fresh context, binds `:env` to a new atom holding `{:namespaces {...}, :bindings {...}}`. Context is mutable (its `:env` atom can be modified).
- **`sci/fork`**: copies `:env`'s current deref value (shallow copy of namespace/binding maps), creates a new atom, shares Var roots and global state (interruption handler, readers). The fork's `:env` atom is independent; contents are value-semantic.
- **Immutability inside `fork`**: the namespace and binding maps inside `:env` are treated as immutable (never reused), but the `:env` atom itself mutates when new namespace/binding facts arrive.

## What can/cannot live in konserve/Datahike

**Can live in Datahike:**
- Program entities (functions, tests, schemas, namespaces, render pairs, analysis, test results) keyed by definition digest
- Task, agent, evaluation, error records (all data, disposable at branch merge)
- Blobs (via `seon.blob/stage-file!`, konserve-backed, compressed, indexed by digest, threshold: `:seon.config.eval.result/blob-threshold` UTF-8 bytes)

**Cannot live in Datahike:**
- Live SCI contexts (atoms, mutable closures, Var bindings)
- Host-bound entities (deftype, defrecord, defprotocol, gen-class, proxy)
- Thread-local state (interruption arm, deadline latch)
- Execution handles (Flow graphs, channels, executors)
- Malli caches (schema validators, explainers; shared in registry, keyed by schema object identity)

**Konserve limitations:**
- Stores bytes only; closures and host objects cannot serialize
- Used only for result blobs and konserve's own node cache (Datahike dependency)

## History of earlier attempts

1. **Before 2026-09-26**: `seon.sci.eval/program-identity` (core.cache LRU) cached live contexts by program identity. Reacquisition mutated contexts in place (`acquire!` replacing `:env` atom); each agent forked twice to isolate from base mutations.

2. **H1 (astra-cache-1-2, landed)**: `fork!` calls `remember-revisions!` to cache fork-head commit revisions in `seon.cache`, bridging until Datahike stores them (H2).

3. **H2 (in progress)**: Datahike fork change to store `:modified-attrs` and `:attribute-revisions` in commits. Eliminates `seon.cache` `::commit-revisions` key and restores identity after restart/eviction.

4. **H3 (design)**: Delete `program-identity` LRU; key `base-ctx` and `overlay-plan` by `content-revisions` instead. H1 makes this measurable.

5. **H4 (design)**: Empty plan → base is source; non-empty keyed by plan digest. Prevents ~300 MB duplicate overlay entries per fork.

6. **H5 (option A, landed)**: Two-fork model off fresh start path (`branch-ctx` + agent fork). Option B (blocked): single shared immutable base + per-reacquisition rebuild would require REPL-path redesign.

## Open questions

1. **One fork vs. two (H5 option B)**: Can `base-ctx` become immutable (never mutated by `acquire!`) and supply a rebuild-per-turn path? Cost: touches `acquire!`, `acquire-context!`, `fork-for-turn`, `seon.test` world request; several hours.

2. **Commit revisions ownership (H2)**: Which Datahike fork bump version lands? Does Seon need pre-emptive migration for old commits without `:attribute-revisions`?

3. **Per-branch base mutability**: Today `acquire!` mutates the shared base context in place; agents fork from it. Is the base context the right unit to immutabilize, or does the agent's owned fork already suffice?

4. **Context cache eviction policy**: When reach entries dominate budget (288 MB at 50% hit rate), should context caches (overlay, reach) evict together or separately? Trade-off: recompute reach on a miss (expensive, 1.3–2.7 GB) vs. rebuild base (cheap, 100–200 ms).

5. **Option A's private layer rebind**: Agent's private namespace/print session today bound per turn; can it be captured at fork time and reused immutably?
