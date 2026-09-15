---
type: issue
status: open
severity: blocker
tags: [issue, runtime, schema, class/p1]
---

# Development adoption can mix host and SCI generations

Extracted on 2026-09-08 from the instrumentation ownership issue: stable
JVM wrappers and projection-local contracts do not make source reload atomic.
The following dated evidence remains a separate adoption-owner obligation.

## 2026-09-06 development adoption has two mixed-generation races

The remaining interval is broader than one missing wrapper. The generated
operator form reloads the schema, source index, database, evaluation, and
publication namespaces sequentially before invoking `refresh-source!`
(`script/seon/fresh_operator.clj:2366-2407`). Each `require :reload` replaces
the affected JVM Var roots immediately. The publication monitor serializes
publishers only (`src/seon/cluster.clj:1403-1407,1931`); running agent, web,
and REPL callers do not acquire it. A call during this interval can therefore
enter new source under an old wrapper or no wrapper before the final
instrumentation restoration (`script/seon/fresh_operator.clj:2429-2435`).

SCI does not isolate that interval. First-party SCI bindings deliberately
forward the actual host Vars (`src/seon/sci/eval.clj:1093-1160`), so even an
already-created or isolated SCI fork observes a replaced host root immediately.
Staging only SCI namespace state cannot make JVM source generation atomic.

There is a second race during adoption. `development-source-refresh!` changes
the cluster declarations and program rows, reloads selected namespaces, calls
`acquire!` on the shared base context, and only afterward advances its projection
and writes the adopted source commit (`src/seon/cluster.clj:1771-1905`).
Acquisition changes SCI namespace state through multiple swaps and publishes
the kernel program snapshot through a separate atom
(`src/seon/sci/eval.clj:1558-1670`; `src/seon/sci/kernel.clj:108-115`). SCI's
`fork` copies the context environment in one dereference
(`reference-code/sci/src/sci/core.cljc:345-351`), but that does not atomically
include the separate program snapshot or projection carrier. The run loop forks
without consulting the eventual source-commit fact
(`src/seon/cluster/loop.clj:1633-1645`). A concurrent turn can therefore combine
a partial resolver state, a different program snapshot, a forwarded new host
root, and an old projection.

### Required coherent-adoption seam

The preferred repair preserves the accumulated live cluster and has one
bounded quiescence and publication owner:

1. Acquire and hold every armed agent's existing turn-completion permit, so an
   active turn settles and queued wakes cannot begin a new turn. Quiesce the
   shared render/web work through its existing graph completion events as well;
   `flow/pause` alone only sends an asynchronous command and is not an
   acknowledgement
   (`reference-code/core.async/src/main/clojure/clojure/core/async/flow/impl.clj:174-189`).
2. While quiesced, reload JVM source, build and validate the prospective
   projection, and acquire the prospective SCI namespace and program state in
   an isolated context.
3. Publish one generation value containing the namespace state, program
   snapshot, and projection. Swap the live base context to that complete value,
   install JVM wrappers for the same projection, then write the adopted source
   commit.
4. Release the held completion permits and resume acknowledged shared work only
   after every step succeeds. On failure, retain or restore the prior complete
   generation before releasing work.

This requires consolidating the currently separate SCI environment, kernel
program snapshot, and projection carrier at their existing acquisition owner.
It does not require a second evaluator, cache, or publication registry.

Orderly stop/refork/start is a coherent fallback because agent disarm waits for
the exact turn-completion event (`src/seon/cluster/agent.clj:915-951`) and a
fresh start constructs one database/context/projection generation. It is not
the recommended development behavior: the current refork operation replaces
the cluster branch and therefore destroys the live cluster's accumulating
agent facts, directly violating the owner's preservation requirement. A
process-wide read/write lock around every callable boundary would cover JVM
REPL calls too, but adds a pervasive second admission mechanism and is likewise
rejected.

## Re-verified at HEAD (2026-09-15)

Basis: `7e35df2131c71f476a85c6a38bfc8eb292cb36f5` (committed source; concurrent working-tree edits excluded).

UNVERIFIABLE-WITHOUT-GATE. HEAD `src/seon/cluster.clj:1868` still reconciles database state before `require :reload` at `:1965`, shared SCI acquisition at `:1967`, projection advance and final instrumentation/source-commit publication. `refresh-source!` at `:2027` locks publishers, while `src/seon/sci/eval.clj:957` still forwards host Vars. These establish a remaining race candidate, not a reproduced mixed generation. The old per-turn-fork description is historical. Verification needs a bounded concurrency fixture pausing adoption between reload/acquisition while an ordinary agent call runs; that would require an isolated lifecycle drill or a new test, outside this no-test-edit triage. No adoption or lifecycle mutation was attempted.

surface: adoption-publication

Required namespaces: `seon.cluster.source-test` and `seon.custody-stability-test`, with an adoption/ordinary-call overlap fixture. New JVM launches are prohibited by the owner correction.

## Deterministically reproduced on 2026-09-15

The isolated `freshness` cluster, running checkout `806659e06`, reproduced a
mixed contract state after a refused adoption. A one-shot progress callback
restored the previous source file at `development loaded definitions`.
Publication had admitted `:my.adoption-freshness/third-request`; the JVM loaded
`:my.adoption-freshness/new-request`. Adoption then refused with `Source changed
during development adoption` after SCI acquisition and JVM instrumentation.
The previous adopted commit remained recorded, but the program row already
named the new published contract. The next converging adoption repaired this
particular state in 74.21 seconds.

This supersedes the no-live-proof limitation in the earlier dated entry for
**settled refusal state only**. Concurrent agent/render-call atomicity remains
unverified. Ordinary source and schema-resource edits both converged with
current contracts in the same probe; run 9's stale contract after convergence
has not been reproduced on this checkout.

Evidence and exact transcripts:
[adoption contract freshness](../../prds/context-generation/research/adoption-contract-freshness-2026-09-15.md).
The repair decision and class regression are in progress; this issue remains open.

The test-first class regression subsequently failed at the wanted invariant
on the real armed child-JVM adoption path (`bin/test-fast --paths
 test/seon/adoption_contract_freshness_test.clj --
 seon.adoption-contract-freshness-test`, completed 2026-09-15T18:21:42Z).
The child had already verified the changed SCI contract before forcing the
refusal. Repair and final gates remain pending the owner design decision.
