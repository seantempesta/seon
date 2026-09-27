---
type: design
lane: shrink-eval-opus
date: 2026-09-27
status: design revision 2, for Astra review; no source edits
responds-to: docs/research/agent-platform/review-eval-shrink-plan-2026-09-26.md, verdict "slice-2 design at 987a90b62 — REVISE" (ca792f0b4)
owning task: d215b1ebde7c acquisition-rebuilds-the-whole-program
source: HEAD 056abea62. eval.clj line numbers are the review's (7e8043de1) unless marked
---

# Slice 2 design (revision 2): a shared loaded base, and receiving acquisition by complete delta

**What exists.** `base-ctx` memoizes one whole interpreted program per `base-ctx-key`, and that key is
connection-scoped. So every agent, MCP and test branch derives the whole program again: 235 ms mean, and 1-3 s
cold. Only the branch's differences from the loaded program distinguish its context.

**Principal approach (the review accepts the split).**

- One **shared base** per `[store, L]`, where L is the loaded program commit resolved from the adopting authority.
  It is built only from L and fixed process inputs.
- Each context receives it through `fork-cluster-ctx`'s receiving-world composition.
- A **receiving acquisition** then installs the complete delta between the branch and L.
- The receiving acquisition's identity is the delta's content, never a connection or commit stamp.

This revision answers the review's five gaps (§2-§6) and its construction rules (§1). **§7 prices the result.**
It is larger than the first design, so I recommend landing it in two correctness-ordered cuts, the first of
which is small.

## 1. Shared base: built only from L (review "construction rules")

| rule | design |
|---|---|
| L's authority | Resolve L once per acquisition through `loaded-source(snapshot, V)` (`::loaded-connection`, the adopting cluster's live connection; eval.clj:1620). The branch's own adoption record is never used. Unknown or failed adoption refuses as today and is never cached. The start/end loaded-identity check (`call-detecting-program-change`, :1614) stays. The one-argument `base-ctx` resolves L the same way before lookup. |
| materialization | Derive from `commit-as-db` through the **authority connection's** store, which lives as long as the cluster, not the caller's branch store. The base retains no database value; that is today's invariant (`derive-base-ctx` dissocs `::loaded-database`), and the retention test keeps proving it. Materialization is ~3 ms and is not cached separately. |
| projection (item C) | The loaded projection is `schema/projection-from-database` of L's materialization. Its construction inputs are L's forms and contracts plus the process compile options. The base key therefore carries the projection's construction key (`schema/build-projection`'s `[forms contracts options]`, schema.clj:2403), which is already computed, as `[store, L, projection-construction-key]`. It is never a fingerprint. |
| recorder | Removed from shared derivation. The base returns its refusals as data (`::acquisition-refusals`). The receiver records and delivers them through its own recorder, with dedup keyed `[store, receiving acquisition identity, target]`. Only successful recordings are retained, and failures retry (§5). |
| loader | `:seon.test/class-loader` (eval.clj:3427) moves off the base and becomes a receiver attachment set by the test owner's execution. |
| late host loads (item A) | On every receiving acquisition, including an empty delta and an equal identity, the receiver binds **admitted identities that are missing an SCI binding** by copying their host root under the receiver's loader. This is `install-first-party-namespaces!`'s selection restricted to missing bindings. Deleted identities and delta overrides are never bound (tombstones in §2). Cost: O(admitted identities) map lookups, measured in the slice. |
| cache | `seon.cache` kind `::loaded-base`, under the one byte budget. The weight is calibrated in the slice against the retained structure (namespace bindings plus descriptors, measured), not `256 × Vars`. A derivation that throws is not retained. A base carrying refusal data is retained, because it is a value of L. |

Receiving composition: `copy-base-ctx` alone is insufficient, as the review says. Receiving goes through
`fork-cluster-ctx`'s existing custody, projection, environment and recorder composition (eval.clj:3766).
Schema-declared injected bindings are refreshed when the receiving projection's injected population differs
from the base's (`program/base-context-injected-symbols`, program.cljc:136).

## 2. Delta completeness (review gap B)

B1 is withdrawn. The delta is computed from **narrow, complete selection reads** on both values: the branch value
V and L's materialization. Each read is compared as a set. Datahike's dependency-aware query cache reuses each read
across V's own commits that leave its attributes untouched (query.cljc:2582).

| read (both sides) | tuple | covers |
|---|---|---|
| functions | `[sym digest admission host-bound? private? calls-set]` | source/definition change, authorship-independent differences (branch law), host-bound change, calls-only or analysis-only change (`calls-set` is the row's own `:seon.fn/calls` targets) |
| tests | `[sym source-digest admission ns-name]` | test changes and removals |
| namespaces | `[name source admission requires aliases imports refers]`, with refs **normalized to names** (namespace components are not compared by entity id) | reader-context changes and removals |
| declared invocation edges | `[holder-identity attribute target]` for every attribute a `:seon.fn/invokes` declaration names (fn.clj:1714) | invocation targets held on data rows |

- **Selection.** `changed = (symmetric difference by identity)`, and `seeds = changed functions ∪ removed
  functions ∪ edge holders whose edges changed`.
- **Closure.** `(seon.fn/reverse-closure V seeds)` on the branch, including absent seeds (fn.clj:1938), returns
  `:affected` and `:tests`.
- **Acquisition identity:** `[store, L, projection-construction-key(V), arming inputs (§3), sorted changed tuples
  of V, closure result]`.
  - The closure result is in the identity, so removing a formerly affected caller changes it.
- **Missing evidence refuses.** A function row without a digest, or without analysis where calls are expected,
  refuses by name as `::incoherent-delta`. It never counts as equal.
- **Memo.** The identity is memoized in `seon.cache` by `[store, committed-value identity of V, L]`, using
  `db/committed-value-identity`, which excludes `d/with` values (db.clj:3001). A candidate `d/with` world computes
  its identity fresh.
- **Cost.** O(N) set comparison per new committed value (N = program rows), with reads reused by Datahike.
  Installation is O(changed + closure).

**Tombstones.** A removed identity is unmapped and purged from the receiver's kernel snapshot (source,
namespace descriptor, installed marker; kernel.clj:207, :261). Late binding (§1) skips tombstones.

**A surviving compiled caller of a removed function.** The caller is in the closure, so it is interpreted
against the branch program and fails loudly at the missing name, as Clojure would. It does not reach the loaded
implementation.

**Namespace deltas.** Replacing or removing an alias, refer or import updates the namespace's bindings (the old
ones are removed). A changed function in an unchanged namespace gets its namespace's full branch bindings.

- A namespace-only change seeds every function in that namespace, because its resolver context changed.
- If a seeded function's analysis predates the namespace row, it refuses as not current.

**Install order is preserved exactly** (eval.clj:3031-3114): classes, then namespace bindings, then caller host
bindings, then generation-local declares, then functions in namespace order, then tests.

**Effective kernel snapshot.** This is the loaded descriptors with delta replacements and removals, including
namespace descriptors. It is published before any install. `install-row!`, lazy acquisition (:1377) and test
resolution (§4) all read it. The host-bound refusal checks both the branch row and the loaded row (:1788), as
today.

## 3. Receiving arming (review: config, caps, supplied defaults)

- **Arming inputs.** These are exactly what `install-function-contract!` reads: the receiving projection's
  construction key, `:seon.config.error/max-evidence-bytes`, and the `call-preparation/snapshot` content (its
  declaration rows' key, schema and supplier identity; call_preparation.clj:111, :285).
  - `result-caps` is read but not used by `compiled-wrapper` (instrument.clj:554). **The read is dropped at its
    owner** (`instrumentation-config`, eval.clj:1334), so caps do not enter the identity.
- **Rearm without replay.** If only arming inputs changed, the receiver rearms its interpreted roots without
  re-evaluating source. It uses the existing `install-function-contract!` loop that `fork-cluster-ctx` already runs
  (eval.clj:3764).
  - A config refusal surfaces as the named refusal.
  - Recovery is a new identity, which rearms.
- **Compiled roots under a branch schema or default change.** Today a compiled host root carries the JVM's arming
  of L. This design **states that semantics and keeps it** (it is the existing loaded-program behaviour):
  - a branch's schema-only or default-only change reaches a function's calls only when that function is in the
    delta or closure;
  - making every compiled function whose contract names a changed schema key interpretable is a separate
    decision, listed for the owner in §7 (option C).

## 4. Test execution (review gap: the executor chooses by authorship)

`seon.test/resolve-test` (test.clj:1514) runs a test in SCI only when its admission is `:agent`. The receiving
acquisition publishes its **interpreted-tests set**: the changed tests plus the closure's `:seon.fn/tests`. Tests
in that set resolve in the receiving SCI context; every other test resolves its JVM Var under the test loader, as
today.

This is a patch to test.clj, which shrink-test owns and root applies. The branch-law fix is authorship-independent,
so a changed file-derived test on a branch runs the branch's version.

## 5. Adoption identity and consumers (review: consumer table)

| consumer | conversion |
|---|---|
| `acquired-database?` | the identity (§2) equals the context's `::acquired-identity`; L is resolved from the authority each time. The same branch value after an adoption has a new L, so a new identity |
| `install-evaluated-rows!` / `installation-covers-program-change?` | after the proved installation, the context's `::acquired-identity` advances to the identity of the settled `:db-after`, computed incrementally: the settled rows are the only changed tuples, plus a closure refresh. No revision comparison remains |
| `regenerate-agent-context!` | receives through §6's order |
| both `base-ctx` arities | resolve L from the authority, then look up the shared base, then receive |
| `fork-cluster-ctx` | keeps its acquisition of the supplied value (47d9d4419); the test owner relies on it (3a14c33ef) |
| lazy install (`acquire-function-from-database!`, :1377) | reads the effective snapshot (§2) |
| `record-refusals-once!` | key `[store, receiving identity, target]`; only successful outcomes are retained; failures retry (eval.clj:3568) |
| `::program-identity` listeners | **retired in code**: until every live connection is replaced, `cluster-ctx*` and `fork-cluster-ctx` call `d/unlisten connection ::program-identity` (idempotent; datahike core.cljc:213) where they used to `d/listen`. A later cut deletes the unlisten |
| deleted | `revision-basis`, `program-basis`, `commit-id-of`, `remember-program-identity!`, `program-identity`, `program-identity-cache`, `acquisition-attributes`, `loaded-basis`, `loaded-program-current?`, the lineage branch of `acquired-database?`, `base-context-cache`, `refusal-recording-cache`, `program-cache-policy`, `watch-program-identity!`, `overridden-row?`'s authorship test, the `authored` filter |

## 6. Live-root preservation (review gap 5)

A receiving acquisition on a **continuing** context proceeds in this order:

1. Compute the new identity. If it is equal, do nothing except the late-binding refresh (§1).
2. Compute `delta-of-delta`: the tuples that differ between the context's previous identity and the new one.
3. **Carry first.** Every owned root whose row is unchanged in the delta-of-delta keeps its Var and its root,
   carried before any analysis. This covers agent data defs (atoms), privates, and closures over them. It extends
   `acquire!`'s own-intern carry (eval.clj:3639) and `regenerate-agent-context!`'s generation filter (:3214) to
   program data-def rows. Newly analyzed callers then resolve the carried Var, not a new one.
4. **Install only changed rows** (and their closure). Initializers run only for rows whose content changed. A
   program row that left the delta because an adoption made it equal to L keeps its continuing root; the root is
   not replaced by the host root.
5. **Rearm-only** when only arming inputs changed (§3). No initializer runs.

A **new** branch context evaluates its own agent data-def roots once, on its own fork. Roots are never shared
across siblings, because deltas are never cached across branches; only the base is shared.

## 7. Cost, size and three priced options

The review's required regressions (its seven bullets) are adopted unchanged as the acceptance list.

**Size estimate.**

| file | added | deleted | notes |
|---|---:|---:|---|
| eval.clj | ~+320 | ~−330 | loaded-base derivation extracted from `acquire-program!`, the delta reads, the receiving install, identity, live-root carry, consumer conversion |
| test.clj | ~+15 | ~−5 | patch for root (§4) |
| instrument / call-preparation | 0 | 0 | the `result-caps` read is dropped in eval.clj |

That is net ≈ −0, and **well over the 100-added-line bound**. CLAUDE.md requires stopping and pricing it:

- **A. (Recommended first) Shared base plus namespace-and-test-only receiving deltas.** The receiver installs only
  namespace rows and agent tests over the shared base. Any function or edge delta falls back to today's full
  per-branch derivation, keyed by the new identity (§2), so it is correct.
  - *Guarantee:* the common agent, MCP and test branch has a namespace row, possibly tests, and no function
    delta. It pays fork + binding refresh + selection reads, not a 235 ms-1 s derivation. No live-root
    replacement risk arises, because no function row is installed on a continuing world.
  - *Cost:* ~1 day; ~+150/−200 in eval.clj (the identity, base and consumer deletions of §5; no §6 carry).
  - *Sacrifice:* branches with function overrides still derive fully, as today.
  - *Also needed:* the branch-law fix (authorship-independent selection and resolve-test) lands as its own
    correctness slice, B0.
- **B. The full design above.**
  - *Guarantee:* cost follows changed rows for every branch.
  - *Cost:* ~3 days; the size above.
  - *Sacrifice:* the live-root carry (§6) is new machinery that must be proven by the review's mutated-atom
    regression before it lands.
- **C. A and B, plus compiled functions whose contracts reference a changed schema or default key become
  interpreted on the branch (§3).**
  - *Guarantee:* branch schema and default changes reach every call.
  - *Cost:* B plus a keyword-reach seed (`:seon.fn/keywords`, fn.clj:600) and its test.
  - *Sacrifice:* a schema change on a branch can interpret large closures. This is an owner decision.

**Measured 2026-09-27 01:55Z on default (pid 73942, archive 03103544a).**

- **Method.** A bounded read-only poller of `base-context-cache` entries ran during one run of seven test
  namespaces: sci kernel, eval, entry-gate and restoration; test accretion; test-test; entity-pairs. The run took
  217 s.
- **Derivations.** There were 29 derivations and 33 `base-ctx` calls. The poller captured 30 new keys.
- **Empty function delta.** 18 of 30 (60%) had `interpreted-count` 0. The other 12 had 1-4 interpreted
  functions. These are tests that write function rows by design, so the window is biased toward program-change
  tests.
- **Cost.** Derivation totalled 4,427 ms over the window, ~130 ms mean; that is about 2% of the test wall time.
  `acquire!` ran 313 times, and 284 of those took the fast path.
- **What option A saves.** It removes the 60% share: ~2.3 s per such run here, and the 235 ms-1 s first
  acquisition of every agent or MCP branch with no function change.

The earlier plan was: measure first, from a bounded sample of real acquisitions on default recording empty
function delta vs. non-empty. a bounded sample of real acquisitions on default recording empty function delta
vs. non-empty. That is the review's "complete empty deltas", not `interpreted-count`. A counts only the former.

## Verification limits

Everything above is design. No production source was changed for slice 2. The line estimates are estimates. The
claims that the listed reads cover `acquire-program!`'s inputs follow the review's input table plus a re-read of
eval.clj:2803-3120. They are proven only by the regressions.
