---
title: Cost design — changed inputs and focused instrumentation
type: research
status: proposed
created: 2026-10-08
---

# Cost design: changed inputs and focused instrumentation

Research lane `cost-design-astra`, 2026-10-08. Documentation only. No source changes, default writes in probe bodies, reloads, cache clearing, restarts, or other lane communication. The other cost-design answer was not read.

Seon already has immutable Datahike values, attribute revisions, read evidence, content-keyed reach nodes, compiled Malli schemas, and a shared Caffeine cache. The recommendation is to compose these: acquire only the requested dependencies, reuse their unchanged derivations, and advance cheap owner-specific data transformations from exact transaction deltas. Separately, reduce repeated validation of unchanged values, retaining validation at their producing and admitting boundaries.

## Evidence and limits

Default was available but **faulted**, not healthy: initial `bin/seon status` and MCP `runtime_status` reported five open faults, 25 errored recordings, 17 over-bound warnings, and a cache-discipline warning. Readiness was complete. Heap used was 3,387,521,704 bytes, last live heap 1,680,439,976, maximum 4,294,967,296; central cache weight was 511,000,708 of 512,000,000 bytes. These are shared-process observations, not this lane's retained allocation. High cache occupancy makes eviction/cold acquisition relevant; it does not establish which entry caused a miss.

The initial checkout HEAD was `13cfd08dab65e04785c5acaedfc40cc56e961f9c`; it advanced during research. Default's reported committed source archive was `a5fc4cb493ac5f5f2ec11ed4175497cdc9fdd651`. Source inspection is not installation evidence. Live probes below name the installed seams and values; no edited source was loaded. In particular, the installed `seon.profile/measure` returns `:seon.profile/called`, whereas the in-flight profile edit initially inspected used ranked fields. The corrected probes read the installed map.

Dependency pins inspected end to end at the relevant seams:

| Dependency | Checkout/gitlink | Inspected seam and guarantee |
|---|---|---|
| Malli maintained fork | `418df4417827b66846b799b5e87267a344dffc29` | `malli.core/-instrument`, `malli.core/-=>-schema`, `malli.core/validator`, `malli.instrument/-strument-var!`, `malli.instrument/-collect!`: compiled input/output/guard validators and explicit scope; metadata collection; Var selection. Fork commits already optimize passing calls and shared validator compilation. |
| Datahike maintained fork | `86c3301a00de8c49b2690083b2b1e69677bf0c8d` | `datahike.db/advance-cache-context`, `datahike.db/speculative-cache-context`, `datahike.db/committed-value-identity`, `datahike.query/advance-query-cache-context`, `datahike.versioning/attached-cache-context`: immutable revision witnesses, including conservative changes and detached/speculative values. These revision APIs are fork seams, not claimed as upstream APIs. |
| Datahike maintained fork, same pin | same | `datahike.core/listen!`, `datahike.writer/transact!`, `datahike.writer/notify-listeners!`, `datahike.db.transaction/validate-report`: listeners follow committed delivery; the final validator receives the actual transaction candidate before commitment. These are different boundaries. |
| Caffeine | Seon's dependency is 3.2.4 | `seon.cache/create`, `seon.cache/lookup`, `seon.cache/claim`, `seon.cache/settle!`: shared weighted AsyncCache and one retained computation per key. Existing estimated weights are not measured retained heap. |

Malli's upstream documentation confirms that scope and Var filters are ordinary library options; Seon's fork is not needed to invent focusing. [Malli function schemas](https://github.com/metosin/malli/blob/master/docs/function-schemas.md#defn-instrumentation). Caffeine provides cache statistics via `recordStats()` and `Cache.stats()`; existing Seon per-kind LongAdders are an implementation to reconcile with the cache law, not a reason to add counters here. [Caffeine statistics](https://github.com/ben-manes/caffeine/wiki/Statistics).

Notation below: **S** = supplied assignment evidence; **L** = existing ledger/task evidence, not remeasured here; **P** = this lane's read-only JVM probe. Do not add rows' times or allocations: parent/child costs overlap. A call count is not by itself a time attribution.

Read authorities: AGENTS.md; ledger §3.1, §3.23, §3.25 and its referenced current rows; data-oriented Clojure, REPL, Datahike, and testing skills. Several skill examples carry older pins/line references. This note uses inspected symbols and the pins above, not those examples as installation evidence. This is evidence for the owner's existing schedule, not a second issue index.

## A. Whole-program work per call

### Algorithm first

Let N be all declarations/datoms in the relevant population, Δ the effective logical datoms changed, K the union of dependencies actually needed by this request, and R the reverse dependency region affected by a change. Ordinary unchanged work should be a bounded acquisition plus work on the requested values; a relevant change should cost O(Δ + R), with indexed lookup/update factors. Cold acquisition should follow K, which legitimately can equal N for a global request. No algorithm promises constant work when a schema change really affects every function, or a requested test reaches the entire program.

The common defect is doing O(N) work to discover that zero or one declaration changed. A cache lookup whose key copies N datoms, or whose miss reconstructs an entire attribute range, retains that defect. Revision equality is a cheap sufficient witness of unchanged inputs; a different commit is not evidence that all inputs changed.

### Census

| Operation / existing owner | Observed cost and current source | Expected work / removal |
|---|---|---|
| Proof verification: `seon.test/verified?` → `seon.test.runner/reach-digests` | **S:** 5.9 s / 4.2 GB cold per assignment. **P:** one small test, first observed call 301.04 ms / 697,831,424 allocated bytes / 11 reads / 4,080 instrumented calls; second 0.65 ms / 203,840 bytes / zero reads / 121 calls. It reached 98 names and 489 schema nodes, while declaration acquisition held 8,374 declarations and 3,503 schemas. | Read one current recorded result and its current input/reach evidence. Cold reach should fetch the requested closure, not every declaration. Batch the task's proof set into one union closure, not one cold graph per proof. Missing or unknown evidence still refuses reuse. |
| Reach acquisition: `reach-declarations`, `reach-attribute`, `reach-graph`, `reach-entries` in `seon.test.runner` | Current code keys eleven attribute maps separately by content revisions, then caches graph/index. A changed digest attribute still reconstructs **all datoms of that attribute**. The index key also builds a schema-leaf map from the reached schemas; collection contracts and key construction remain work on warm calls. SCC computation is already shared within a requested graph, not a closure per test. | Reuse unchanged attributes; acquire/replace changed entities rather than entire changed ranges. Demand acquisition on a cold small request. Retain the existing SCC algorithm for cycles. Keep keys proportional to the witnesses held, not a freshly copied graph. |
| Branch reach reuse: `seon.db/content-revisions`, `remember-revisions!`, `branch-database` | **S:** reach cache keyed per branch. **Correction:** `ead65e695`/LR-CACHEb already replaced that design; `2ec29004e` split attribute acquisition. **L, LR-BRANCHREACH:** branch-as-value still misses all reach attributes; zero declaration-cache hits in nine misses, 86 MB, 2.3 s load. Source explains why: `datahike.versioning/attached-cache-context` gives another materialized commit its own conservative revision, and Seon's source-revision history is evictable. | Equal dependency content shares a value regardless of branch. An evicted witness means a miss, never false equality. Fix lineage/read witnesses in Datahike or requery the requested closure; simply removing connection/generation from existing keys is unsound. |
| Automatic tests: `seon.turn/run-proofs`, `run-reaching`, `seon.test/run`, `changes`, `digest-differences`, `clean-request-handle` | **S:** a task-bearing `:ping` performs 67 queries, 64 pulls, about 200 distinct instrumented functions, 53,697 calls, and two selections. Source calls proofs only when present and reaching only when change is found: this is not universal to every ping. `digest-differences` still reads both complete digest populations; called from change discovery and overlay construction. **L:** about 12,000 rows per side, 41–54 ms per check; world acquisition 146–212 ms. | Derive one comparison/selection input per request, carry it to world construction; retain unchanged selections/evidence by their actual inputs. Proofs still precede reaching execution. Data-input changes can stale proofs without a defn; zero program change alone cannot certify completion. |
| Definition projection: `seon.db/declaration-content-key`, `speculative-projection`, `seon.schema/load-projection`, `range-differences` | **S/L PB-08:** about 30,000 datoms copied, 17.8 MB per defn. Source retains a content scan on revision miss. `advanced-projection` parses/compiles only changed rows but discovers them by walking full AEVT ranges and building the full held-identity set. | From a held basis and exact report, identify changed declarations on both sides, patch persistent maps, compile only the changed schemas/contracts and their dependents. Preserve declaration admission and final owning-value checks. No whole-content scan just to promote speculative work to committed work. |
| Deletion: `my.program/remove-ns!`, `retract-operation!`, `deletion-report`, `breaks` | **S/L task `my.program/remove-ns-allocates-two-gigabytes`:** 2,061 MB / 839 ms for a tiny namespace. Source builds a root report plus a report per owned declaration, repeats the decision in `:db.fn/call`, and derives affected data again after the transaction. **Correction:** PB-15b (`33462f71f`) landed during this lane: namespace gating is now one union reverse walk. | Query the namespace's owned identities and surviving incoming references in sets; one union walk per immutable value. Keep the writer's fresh decision, because a preflight cannot authorize a later deletion. Reuse its exact result/held preflight only when the basis proves equivalence. Measure before attributing the 2 GB specifically to any one of these stages. |
| Ordinary identity reads: `seon.turn/terminal-agent-ordinal` and the PB-09 readers | **L:** settlement 14–16 MB/request; ordinal scan 4.4–4.8 MB / 2.6–3.6 ms at 742 evaluations, versus 31 KB / 0.04 ms by entity/index. | Bound the query by the known agent/run/entity. An index seek removes work without another cache. Retired recording machinery should be deleted by its owning cut rather than optimized. |
| Schema-only work: `seon.error/occurred-since`, `declared-schema-keys`; `seon.sci.eval/install-function-contract!`; `seon.call-preparation/snapshot` | **L PB-07/PB-13:** 25–37 MB extra after a defn, 49k extra instrumented calls; empty error window 12.8 MB; installation 4.6 MB versus about 3 KB for bare Malli instrumentation. Some schema derivations live under a whole projection; configuration is read per installed contract. | Seek whether an error exists before deriving its selector. Key schema-only derivations by forms/admissions/predicate inputs, excluding unrelated function bodies. Carry configuration once per request and reuse compiled contracts/plans until their dependencies change. `report-snapshot` already demonstrates reuse of before when no read attribute changed. |
| Context acquisition: `seon.sci.eval/base-bindings`, `regenerate-agent-context!` | **L PB-11:** 10 MB on defn, 30 MB on the next request. Entire-program Var enumeration to preserve a small private layer. PB-02b describes repeated program-movement checks. | Advance changed program bindings; preserve only the agent's private bindings. This is the same dependency principle, but lifecycle semantics belong to the SCI owner and are not redesigned here. |
| Dependency query/cache/planner work | **L DH-5:** one-clause new-constant query 0.38 MB / 0.2 ms versus native datoms 5 KB / 6 µs; `datahike.query/inheritable-entry` scans retained result buckets, `get-or-create-plan` varies with substituted constants. **L DH-4b:** node LRU map rewrite 58% of a four-clause query. **L DH-3:** planner range count reads 239 nodes on 1.16M datoms. | Fix these in Datahike/the sorted-set dependency: indexed cache lookup, parameterized plan reuse, byte-weighted node cache, subtree counts. They magnify repeated Seon reads but are separate from Seon's program algorithm. Do not reproduce a query engine or planner in Seon. |

The 5.9 s / 4.2 GB assignment result and the one-test 301 ms result are different workloads and states; the latter is not proof the former is fixed. `remove-ns!` was not run because it writes. A whole `:ping` request was not reprofiled: the above read probes measure their bodies, not MCP recording, rendering, or automatic testing.

### One general mechanism, in existing owners

Use **a pure derivation plus its actual read dependencies**, retained in `seon.cache`; Datahike supplies value identities, revision witnesses, queries, indexes and reports. This is one design rule, not a new service, registry, listener roster, mutable index, or evaluator.

1. **Hold the inputs.** An operation receives one immutable database and its already acquired configuration/compiled schemas. Pass results already derived to later stages. For a known identity, use EAVT/AVET or a bound query before considering caching.
2. **Prove unchanged cheaply.** An exact commit identity is a fast first match. Otherwise compare only the dependency's revision/read evidence, including conservative revision, schema/predicate/builder semantics and negative reads. `seon.db/read-evidence-current?` already owns currency, including supported semantic replay. Equal absent attribute revisions from unrelated connection generations prove nothing. Existing `content-revisions` is stronger only where it resolves commits through valid lineage.
3. **Acquire only missing inputs.** Reach cold acquisition should read the union frontier of requested names/schema keys, using indexed reads or `pull-many` for a frontier that is fully consumed. Reuse per-node data; preserve unresolved names and absence evidence so a later definition is noticed. When K really is the whole program, a single union graph traversal is appropriate. Neither N single queries nor a separate closure for every proof is appropriate.
4. **Advance the derivations that have a simple delta.** For a held basis plus exact effective logical report, fold additions/retractions into the existing persistent declaration maps; reconstruct only touched entity rows using before/after for renamed/deleted identities and owned cells. A transformation whose update is not simple rederives its affected input region. Do not implement generic incremental Datalog. Graph edge edits can merge/split SCCs; rerun the existing SCC calculation on the required graph/affected region unless a measured need justifies a library-backed incremental algorithm. No claim of O(Δ) for arbitrary dynamic SCCs.
5. **Cache the result under its inputs, not its owner.** Include derivation semantics and the actual dependency witness. Do not use a branch name, bare basis-t, or just a definition digest when derived edges can change without source changing. Existing reach tests cover that edge-retarget case. Avoid whole-map hashing/copying on every lookup. Cache weights include retained keys and shared values without pretending a small output means a small entry. Eviction changes cost, not correctness; restart reacquires facts, not serialized runtime objects.

**The transaction/listener seam matters.** The final writer validator sees the precise speculative `:db-after` and attempted as well as effective datoms; use that report for final admission. A committed listener report can have the batch's final `:db-after` but only one transaction's `:tx-data`. Applying that one delta and labelling it with the final commit would create a falsely current derivation. Also the transaction promise is delivered before listener callbacks, so a synchronous reader can overtake a listener.

For immediate reuse, use exact report data already held by the writer/entrant, or Datahike's revision witness on the database the reader actually holds. A listener is an optional way to prepare future cache entries, never proof of freshness by its silence. Register before the initial read; verify coverage; reject a discontinuity and acquire from authoritative data. If advancing a committed batch requires its complete delta and the dependency exposes no suitable owned seam, **that is the exact fork gap for the owner**, not permission to add a second queue or infer all intervening datoms. `datahike.committed-report/offer-committed!` exists in the fork but still receives those per-transaction reports; its mere existence does not solve batch completeness.

Across branches, a merge/conservative change invalidates the cheap witness conservatively. Branch histories and numeric eids are not interchangeable. A lineage witness may permit reuse; identical content from unrelated histories can still miss safely. Guarantee sound reuse first; universal cache hits are not the contract.

### Ranked options for A

| Rank | Option | Guarantee | Estimated cost | Sacrifice |
|---|---|---|---|---|
| 1 — simplest viable | Remove repeated acquisition within one request; point reads for known identities; use existing revision keys and shared results | Same decisions with fewer duplicate reads; unchanged-request reuse can be constant in N | 4–6 bounded implementation slices, roughly 1–1.5 hours plus proof/gate time, after exact owner cards | Cold reads and relevant changes can still scan whole attributes/programs; this alone does not finish A |
| **2 — recommended** | Option 1, then demand-driven reach acquisition and report-driven updates of the existing declaration derivations | Work follows requested dependencies and changed/affected data; unchanged branches can share; unknown witnesses never certify reuse | Roughly 10–14 fifteen-minute slices across the existing owners, 2.5–3.5 hours plus dependency proof/review; batch/lineage design may need a separate research slice | More careful handling of retractions, schema changes, branches and missed reports; cold global requests and truly global dependency changes remain O(N) |
| 3 | Eagerly derive one complete graph/population at publication, shared by readers | Fast warm reads with one authoritative complete derivation per relevant program version | About 6–8 slices, 1.5–2 hours plus publication proof | Moves O(N) work and memory to every relevant publication; worsens small edits and branch proliferation; does not satisfy changed-input cost as well as option 2 |

Prices are implementation estimates, not promises or authorization to launch. Option 2 is staged because a general incremental dependency engine would be much larger and would duplicate the dependency. Optimize the few transformations whose deltas are plain maps/sets; use ordinary indexed reads elsewhere.

## B. Focused instrumentation

### Algorithm first

A boundary should validate the value it newly accepts or produces. A helper should receive the facts it actually needs. A constant-time identity predicate recognizes a dependency's runtime value; it must not traverse that value, query a database, compile a schema, resolve resources or stringify it. A complete contract is useful even when its runtime enforcement policy is separately declared.

Repeated deep validation costs O(H × V) when H helper crossings each validate the same V-element immutable report. Compile-once removes schema compilation; it does **not** remove these V-element walks. Make the owning validation O(V + affected owning values), and make helper checks proportional to their narrow arguments. A's excessive calls and B's per-call amplification are independent: reducing one does not excuse the other.

### Census and where the checks matter

| Boundary / check | Real defect it catches | Repeated work / expected cost |
|---|---|---|
| `seon.instrument/compiled-wrapper` via `malli.core/-instrument` | Wrong argument/arity before body, wrong result/error union at the producer, relational guard failure | Input/output/guard all hardcoded for every wrapper. Retained compiled schema already reused; do not propose compiling every call or changing `:on-core-error` to evade enforcement. |
| `seon.db/database-value?` | A refusal/map/nil passed as a database; recognizing Datahike DB and supported views | **S:** 1,812,010 invocations in one old namespace `breaks`. Current `datahike.db.utils/db?` already uses three JVM interface `instance?` checks, O(1), not a DB scan. **P:** 10,000 current wrapped checks 1.46 ms / 1,760,400 allocated bytes; captured original 0.16 ms / 88 bytes. This is wrapper/profiling/dispatch amplification, not evidence the predicate's algorithm is slow. |
| Read entrance and internal stages: `seon.db/pull`, `pull-call`, `with-declarations`, `read-declarations`, evidence capture | Correct source, query/selector grammar, installed schema availability, native/logical conversion, exact read evidence | **S:** roughly ten wrapper calls/read. **P:** one tiny warm pull made 39 calls, including 14 database predicates and three `schema-database` calls; 0.12–0.25 ms / 61,776–65,000 bytes across three samples. Once the immutable DB is recognized, these are repeatedly checking the same identity; keep the newly derived arguments/results and domain checks. |
| Writer: `validate-write-report`, `write-report-error`, `write-owned-values-error`, `write-digest-error` | Final complete owning values, required ref survival, components, cycles/multiple ownership, definition digest accuracy, call-arity coherence; same-transaction repairs count | **S/L PB-10:** 21k `transaction-report-datom?` calls for 157 datoms (~134 checks/datom), 14 MB. Several contracts accept the full `:seon.db/transaction-report`, whose `:tx-data` is a vector of full datom schemas. **Do not delete the distinct invariants as “three redundant validations.”** It is the repeated report-shape traversal that can be shared/avoided. Final affected-owner expansion can legitimately exceed Δ. |
| `seon.call-preparation/arm-plan`, `preparing`, `supply-from-row` | Correct custody/default by declared schema name, caller arguments winning, missing required member, invalid supplier result | Plan is already built when installing the wrapper. `preparing` already avoids acquiring the calling environment for a complete arity with no map-entry fills. A supplied value may be checked by supplier output, supplier-specific validator and callee input; sharing is sound only for the same exact schema/value and pure validation. A fresh database must still be dereferenced at each applicable call after a write. |
| Pull result validation | Selector-specific shape after decoding, particularly refs/components/`:as`/defaults | Writer validation of a stored entity does not prove the shape of a pulled/decoded result. `validate-pulled-result` is currently called only for an explicitly requested schema key; do not claim every read fully revalidates stored entities. |
| Entire eval request | The full entrant's correctness/evidence | **S:** `:ping` 53,697 instrumented calls. This includes genuine production work and validators; not 53,697 independent domain decisions. Cost proofs need per-request and per-value counts, not only a faster loop over a predicate. |

The captured-original measurement used `malli.instrument/-f->original` as a **local control**. No Var or runtime instrumentation was changed. It is not a proposed production escape. At this sample's rate, 1.8M wrapped identity calls would imply substantial allocation, but that extrapolation is not an attribution of the historical call's allocation.

### Malli's actual options, and their limits

- `malli.core/validator` caches a validator on the compiled schema object. Reuse that object, as `seon.schema/projection-validator` already does. `m/validate` over a fresh form is not validation-result memoization; Malli has no general “this value was checked earlier” option.
- `malli.core/-instrument` accepts `:scope #{:input :output :guard}`. Scope is an instrumentation property, not an automatic per-argument switch, and putting a `:scope` property inside an arbitrary schema does not wire Seon's wrapper. Malli's metadata route collects `:malli/scope` into the function-schema registration. `malli.instrument/instrument!` supports filters by namespace, Var, or schema. [Upstream options](https://github.com/metosin/malli/blob/master/docs/function-schemas.md#defn-instrumentation).
- Input checking happens before invocation; output and guards happen afterwards. A guard cannot replace pre-effect admission. A report callback that merely logs can let execution continue; Seon's throwing report is essential. Narrowing scope preserves the selected checks, not the omitted guarantee.
- Seon currently ignores a focusing policy here: `compiled-wrapper` passes the literal full scope. Host and interpreted functions share this owner; a change must wire both and rearm when scope/schema/predicate dependencies change. No new wrapper implementation is needed.
- Scope controls call-time checks; the inspected Malli implementation still acquires input/output validators when creating the wrapper. It does not selectively memoize aggregate arguments. A scalar output check on a total predicate is cheap and useful; repeatedly checking its unrestricted input adds no value. Full collection validation remains linear in the collection size however fast the leaf predicate becomes.

Live library check: a local function with contract `[:=> [:cat :int] :int]` deliberately returned `:bad`. Scope `#{:input}` returned `:bad` for input 1 and refused `:bad` input; scope `#{:output}` refused both outputs; full scope refused the bad output for input 1 and refused bad input before invocation. This confirms the scope seam is installed **and** shows precisely the guarantee lost when a side is omitted. It is not a Seon policy change.

### The contract rule really does need an owner decision

“Contract every admitted function, private ones included” does **not** require reconstructing values, whole-program reads, or recompilation on every call. Those can be fixed without a policy exception. But the accompanying instruction **“Arm every valid contract: invalid inputs prevent entry, invalid outputs refuse”**, together with full aggregate schemas on every helper, does require repeated deep checks. There is no sound argument that a function is safe merely because it is private: agents can call it deliberately.

The first implementation step should keep that rule: move full report admission to its actual entrance, pass narrower facts to helper functions, reuse compiled schemas/plans, and keep functions returning the same large report from needlessly carrying that report as output. This preserves the full input/output promise on the functions that remain. Do not replace a complete report schema with `:map`, `vector?`, an arbitrary “checked” flag, or a forged metadata stamp and call it equivalent. PB-10's proposed checked-report name needs this same semantic scrutiny.

If the owner chooses broader focusing, amend the enforcement sentence explicitly: **every admitted function retains a complete contract and reaching tests; runtime scopes are declared using Malli, with full input/output/guard enforcement at arbitrary authored/external entry, mutation admission and newly produced domain values; a narrower internal scope must name the earlier exact validation and the immutable value it preserves.** A direct call into such an internal function no longer has the omitted entry guarantee. This sacrifice must be visible in its declared policy. Full checks in tests improve evidence but are not a proof of all future calls.

Do not apply blanket namespace/private-Var filters, random validation sampling, broad `:any` substitutions, or globally uninstrument predicates. Keep profiling separate: changing validation scope does not require losing call cost evidence. First reduce invocation count through A and narrow value flow; retain cheap boolean-output validation of predicate producers. `malli.generator/check` and law tests are evidence for predicates, not a replacement for admission of mutable/untrusted values.

### Ranked options for B

| Rank | Option | Guarantee | Estimated cost | Sacrifice |
|---|---|---|---|---|
| 1 — simplest viable under today's law | Keep full Malli scopes. Pass narrow data, reuse compiled products, remove duplicate acquisition/forwarding boundaries, keep O(1) identity predicates | Every surviving function still prevents invalid input entry and refuses invalid output; final writer checks remain | 4–6 fifteen-minute slices, 1–1.5 hours plus proof/gate time | Some repeated validation and per-function wrapper cost remains; no claim that report-datom checks become exactly one per write without examining all returning boundaries |
| **2 — recommended, after the owner rules** | Option 1 plus declared Malli scopes at specifically proven internal boundaries; full enforcement at admission and production | New/changed values and external calls remain checked; omitted redundant checks have an explicit owner and scope; contracts and profiling remain | 3–5 additional slices, 45–75 minutes plus writer/adoption proofs; law/card revision first | Loses the omitted validation guarantee on deliberate direct calls to those internal functions; cannot safely apply where a different schema or mutable value is involved |
| 3 | Preserve full scopes and optimize Malli wrapper/validator implementation further in the maintained fork, guided by an allocation profile | Same per-call contract semantics everywhere | 4–8 research/implementation slices, 1–2 hours plus fork/integration suite; stop if no measured remaining seam | Reduces constants only; it cannot remove H repeated V-element walks. The fork already removed passing-call argument-vector/regex-driver costs, so another large gain is unproven |

The recommended order is B1 now as design, then B2 only after an explicit enforcement decision. This lane authorizes neither source edits nor a change of law.

## Implementation slices and proofs for the owner

Each row is one roughly fifteen-minute slice **after its design/preconditions are settled**, including a small before/after probe. If it cannot fit, stop with evidence and split; the aggregate estimates above include additional consumer conversions. Existing tests below are named by real Vars. Proposed assertions are labelled **new** and must first demonstrate the bad work; no test is asserted green merely because its name appears here. Files are suggested future ownership, not claims by this lane.

All cost proofs use `seon.profile/measure` or the existing isolated call sink, the same world/input before and after, and report wall time, allocation scope, GC and call/read counts. For scaling, hold the selected result fixed and add unrelated admitted declarations; double Δ separately. Do not raise bounds, clear shared caches, redefine Vars, or time setup as if it were the operation. Include first acquisition and warm operation. A wall-time claim belongs in evidence; the regression asserts a deterministic work count/invariant.

| Slice | Ownership / smallest change | Measured-cost proof and correctness protection |
|---|---|---|
| A1 | `seon.test` and `seon.turn`: carry one immutable comparison/selection input from change discovery to request-world construction | **New** row through `seon.mcp/call`: one digest comparison per changed request, zero full-population comparisons for a cached unchanged program; record S ping baseline and new query/pull totals. Proof completion still tests current data-input evidence; no task/proof means no invented success. Existing task `seon.test/a-check-compares-every-definition-digest-of-the-program-twice`. |
| A2 | `seon.test.runner`: demand acquisition of the requested reach frontier, preserving existing graph/digest functions | Extend `seon.test-reaching-test/reach-digests-follow-only-changed-closures`: add unrelated declarations and assert fetched entities follow K, not N; compare digest results. Baseline P is 11 whole-range reads / 697.8 MB for a 98-name closure. Also run the edge-retarget and concurrent-reach tests. |
| A3 | `seon.test.runner`: update one held reach attribute/entity map from exact delta; then convert the remaining attribute shapes in subsequent slices | **New** addition/retraction table: one changed declaration reads/replaces its own facts, unchanged node derivations zero, digest equals authoritative rederivation. Include an edge change under an unchanged definition digest and an unresolved name becoming defined. Measure Δ counts rather than “cache hit” alone. |
| A4 | Datahike fork + its tests, then a separate Seon gitlink/consumer slice: recover valid branch-value dependency witnesses | Extend `seon.test-reaching-test/a-fresh-branch-with-unchanged-program-entities-derives-nothing` to `branch-database`, not just connection branches, and assert declaration acquisition too, not only final reach-index equality. **New** cache-eviction, divergent sibling, merge and re-open cases must never reuse stale values. LR-BRANCHREACH supplies the 0/9 baseline. |
| A5 | `seon.db`: replace speculative declaration-content key creation with a basis-plus-exact-delta key at the writer seam | PB-08 **new** proof: a one-definition candidate copies no unrelated declaration datoms; compare candidate projection to authoritative load, including delete/rename and rejection. Baseline ~30k copied / 17.8 MB. Batch completeness is a prerequisite, not a post-implementation patch. |
| A6 | `seon.schema`: advance changed declaration rows using the supplied delta instead of `range-differences` over all ranges | **New** change one schema/contract and assert parsed rows and compiled reverse dependents only; same resulting registry/validation behavior as full load. Include schema replacement plus its data repair in one transaction. Measure reading separately from compilation; PB-08 owner carries the incremental publication proof. |
| A7 | `my.program`: one deletion relation acquisition for the union of owned declarations; retain authority inside writer | Extend the existing namespace/deletion behavior tests; **new** work-count case adds unrelated declarations with a fixed tiny namespace. Preserve required-reference refusal and component cascade. Compare preflight and writer on changed bases. Historical baseline 2,061 MB / 839 ms must be remeasured after landed PB-15b before naming remaining cost. |
| A8 | One PB-09 identity-reader owner, then one PB-07 schema-only derivation owner per slice | Ordinal proof: unrelated agents' evaluations are not read. Empty error window: no selector build. Contract-only edit: zero schema-only recomputations. Ledger supplies respective 4.4–4.8 MB, 12.8 MB and 25–37 MB baselines. These are independent changes, not one sweep. |
| A9 | Datahike DH-5, query-result lookup first; plan parameterization is another slice | Dependency test counts candidate buckets inspected as unrelated cached buckets grow; one shape/new constant keeps one plan. Record the 0.38 MB vs 5 KB baseline. DH-3/4 remain separate dependency tasks, not prerequisites for removing Seon's scans. |
| B1 | `seon.db` read family: acquire declarations/source once, pass them to internal transforms | Extend `seon.db-overhead-test/query-preparation-has-bounded-armed-work` with the tiny-pull case. P baseline 39 calls / 14 DB predicates / ~62 KB; assert one entrance, correct value and read evidence. Keep malformed database/query/selector refusals. Counts must follow the number of values actually transformed. |
| B2 | `seon.db` writer family: inventory full-report consumers, then narrow one family of helper inputs per slice | PB-10 **new** proof counts `transaction-report-datom?` through one admitted small write as datoms scale. Separate shape checks from owning/digest/retention checks. Retain `seon.db-overhead-test/final-validation-asks-cell-storage-of-written-attributes-only` and final-owning-value tests. Baseline 21k/157; report improvement without declaring distinct domain checks redundant. |
| B3 | `seon.instrument` + its schema/authoring consumer after policy decision: carry Malli scope into the one shared wrapper | **New** table for input/output/guard policy in host and SCI; full-scope invalid input never enters body, invalid output refuses, guard runs. Scope change alone rearms affected wrappers. Existing `seon.instrument-test/host-boundaries-enforce-per-arity-declared-schemas-in-both-modes` and `seon.instrument-test/jvm-and-interpreted-functions-arm-the-declared-guard` protect full enforcement. Compare calls/allocations with B2's held report. |
| B4 | `seon.call-preparation`: one duplicate acquisition/validation proved by a measured call, without changing custody semantics | Existing `seon.call-preparation-test/a-call-matching-a-declared-arity-is-never-prefixed`, `seon.call-preparation-test/an-unrelated-commit-reads-the-held-snapshot`, and `seon.call-preparation-test/an-entity-published-after-a-warm-plan-is-seen-synchronously`; **new** work count for the identified duplicate. Explicit database wins; omitted database after a write is fresh. |
| B5 | Malli fork only if B1/B2 leave a measured wrapper allocation bottleneck | Table/property over identical valid and invalid arities, unions and guards before/after; profile 10k calls against P's 1.46 ms / 1.76 MB control. Prove semantic equality and no new reentrant validation. Do not launch merely because a microbenchmark has a ratio. |

There is deliberately no fifteen-minute “build an incremental engine” slice, no blanket instrumentation toggle, and no new cache outside `seon.cache`. Tests concerning eviction, batching and branches are part of the design's correctness, not optional stress tests after landing.

## Owner decisions

1. Choose A2's staged changed-input design over merely moving full graph work to publication. Authorize the dependency research needed for a complete committed-batch/branch-lineage witness only if the inspected writer seam cannot supply it. The correctness condition is explicit above.
2. Keep the full enforcement law and take B1, or approve the proposed declared-scope wording and B2. “Keep contracts” and “check every nested crossing” are different decisions; no scope change is implied by this research assignment.
3. For PB-10, does “checked report” mean a smaller helper input derived after full validation, or relaxed input enforcement for a full report? The former can preserve today's law; the latter needs decision 2. An unforgeable-by-convention map flag is neither.
4. Accept cold acquisition measured against K, with O(N) allowed only when the requested dependency closure itself is N. Do not certify the system using warm cache hits alone. First-use reach allocation here is already covered by W-27/LR-BRANCHREACH and the existing test-cost tasks; root should connect this evidence rather than file another generic cache issue.
5. Keep this document's proof run distinct from implementation acceptance: it validates selected existing seams, not proposed algorithms. No implementation lane was launched and no source/task/index file was edited.

## Reproducible read-only probe forms

All forms used MCP `eval_clj`, `mode: jvm`, `cluster: default`, `agent: cost-design-astra`, repository root selected by the configured tool. The tool has no explicit root argument. Connections were explicit. The normal MCP entrance records its own evaluations; the bodies do not transact. No SCI program was changed. Replies carried no error/diagnostic; their elapsed times were 1, 7, 8, 309, 7 and 55 ms. The envelope did not expose allocation; the measured forms supply thread allocation explicitly. Counts and GC in `measure` are process-wide and may include concurrent work; allocation here was `:thread` and excludes work handed to other threads. These are observations, not an isolated benchmark distribution.

Installation check:

```clojure
(let [database @(seon.cluster.boot/connection "default")]
  {:database-class (.getName (class database))
   :seams (mapv (fn [s] [s (boolean (find-var s))])
                '[seon.db/content-revisions seon.db/database-value?
                  seon.test.runner/reach-digests seon.profile/measure
                  malli.core/-instrument malli.core/validator
                  datahike.query/advance-query-cache-context])})
```

Returned `datahike.db.DB` and true for all seven seams. An initial attempt to read commit metadata from `(meta database)` returned `{}`; the corrected identity read is `seon.db/commit-id`, which returned `#uuid "6ac7764f-e045-5bd0-8dbd-5fc75ae8f85e"` during the census. This distinction avoids confusing Clojure metadata with Datahike's commit data.

The measured forms below are the same operations used for the tables; retain their values rather than comparing printed text:

```clojure
(let [database @(seon.cluster.boot/connection "default")
      one #(seon.db/pull database [:db/ident] [:db/ident :seon.fn/sym])]
  (mapv (fn [_]
          (let [m (seon.profile/measure one)]
            (assoc (select-keys m [:seon.profile/value :seon.profile/ms
                                  :seon.profile/alloc-bytes :seon.profile/alloc-scope
                                  :seon.profile/calls :seon.profile/reads])
                   :hot (vec (take 8 (sort-by val > (:seon.profile/called m)))))))
        (range 3)))

(let [database @(seon.cluster.boot/connection "default")
      tests ['seon.id-test/an-evaluation-id-is-stable-short-and-a-symbol]]
  (mapv (fn [_]
          (let [m (seon.profile/measure
                   #(seon.test.runner/reach-digests database tests))]
            (assoc (select-keys m [:seon.profile/value :seon.profile/ms
                                  :seon.profile/alloc-bytes :seon.profile/alloc-scope
                                  :seon.profile/calls :seon.profile/reads :seon.profile/gc-count])
                   :hot (vec (take 6 (sort-by val > (:seon.profile/called m)))))))
        (range 2)))

(let [database @(seon.cluster.boot/connection "default")
      direct (malli.instrument/-f->original seon.db/database-value?)
      wrapped seon.db/database-value?
      sample (fn [label f]
               (let [m (seon.profile/measure #(dotimes [_ 10000] (f database)))]
                 (assoc (select-keys m [:seon.profile/ms :seon.profile/alloc-bytes
                                       :seon.profile/alloc-scope :seon.profile/calls
                                       :seon.profile/reads]) :probe label)))]
  {:costs [(sample :current-wrapper wrapped) (sample :captured-original direct)]
   :malli-scope
   (mapv (fn [scope]
           (let [f (malli.core/-instrument
                    {:schema [:=> [:cat :int] :int] :scope scope}
                    (fn [_] :bad))]
             [scope (mapv (fn [input]
                            (try (f input)
                                 (catch clojure.lang.ExceptionInfo e
                                   (:type (ex-data e)))))
                          [1 :bad])]))
         [#{:input} #{:output} #{:input :output :guard}])})
```

Reach returned the same digest twice: `77a308c6ed47db699503b0039400cc5df78e5595744724851724c08d60d601d0`. The first call counted one GC; the second zero. Pull returned `{:db/ident :seon.fn/sym}` in every sample. Initial single-pull acquisition before those samples was 0.66 ms / 374,768 bytes / 45 calls; it is not silently mixed with the three warmed results. The initial 1,000 predicate-call sample was 0.24 ms / 176,400 bytes / 1,000 calls. Installed predicate metadata retained the authored total-input → boolean contract.

The reach population census was obtained without a write:

```clojure
(let [database @(seon.cluster.boot/connection "default")
      tests ['seon.id-test/an-evaluation-id-is-stable-short-and-a-symbol]
      graph (#'seon.test.runner/reach-graph database tests)
      declarations (#'seon.test.runner/reach-declarations database)]
  {:commit (seon.db/commit-id database)
   :reached-names (count (:seon.test.runner/nodes graph))
   :reached-schemas (count (:seon.test.runner/schemas graph))
   :all-declarations (count (:seon.test.runner/rows declarations))
   :all-schemas (count (:seon.test.runner/forms declarations))})
```

## Landing and verification

Owned path: this document only. Source/test delta: **0 / 0 lines**. No proposal in this document is installed by its commit. Foreign boundary: the shared checkout contained edits to database, schema, profile, test and program owners and changed HEAD while research ran; none belongs to this lane. The measurements instead name default's committed archive. No foreign file/session was edited or resumed.

Requested broad lint was run before commit: `clj-kondo --lint src script test --cache false`. It completed in **6,792 ms, 0 errors, 1,489 warnings** (exit 2 for warnings). This is not zero findings. The command's full temporary output belongs to this lane; the durable result is recorded here. No warning was suppressed or source changed to make the research green.

Commit/check outcome will be recorded here after the narrow gate. The selected tests verify current reach currency and full boundary enforcement; they do not prove any proposed performance improvement. There is no runtime test that “reaches” a Markdown design note, so the empirical forms, source seams, explicit limitations and independent review remain the proof of this research slice.
