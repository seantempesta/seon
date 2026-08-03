---
type: issue
status: resolved
severity: blocker
tags: [issue, runtime, boot]
---

# Long-lived JVM dependency-class loss is repaired by immutable AOT caches

## Problem

On 2026-08-03, a long-lived JVM stopped transacting while its database and
process remained healthy. Calls failed to resolve
`datahike.writer$fn__56649$fn__56664` and later
`datahike.db.transaction$validate_val$fn__41929`. Restarting the JVM restored
operation and the database on disk remained correct.

The initial diagnosis attributed the absent classes to soft-reference
eviction from Clojure's `DynamicClassLoader/classCache`. That attribution was
explicitly uncertain. The investigation preserved that uncertainty and tested
the necessary classloader relationships instead of treating an absent cache
entry as a cause.

## Diagnosis

Clojure 1.12.5 stores dynamically defined classes in a process-global map of
`SoftReference<Class>` values. When a referent clears,
`findInMemoryClass` removes the entry and has no byte array from which to
reconstruct it. `Compiler.eval` also creates a new sibling
`DynamicClassLoader` for each evaluation, including each prepl form. The
mechanism is grounded in
[`DynamicClassLoader.java`](../../../../reference-code/clojure/src/jvm/clojure/lang/DynamicClassLoader.java),
[`Compiler.java`](../../../../reference-code/clojure/src/jvm/clojure/lang/Compiler.java),
and the complete
[loader-lifetime investigation](../../../prds/sci-execution-runtime/research/dynamic-classloader-lifetime-2026-08-03.md).

The generic failure is real. A constrained sibling-loader probe left a target
class reachable only through the soft cache, exhausted the heap, observed the
target class and defining loader unload, then reproduced
`ClassNotFoundException` from the retained sibling caller. Retaining only the
defining loader made the same lookup succeed.

That was not the current Datahike shape. In a fresh source load:

- the `validate-val` Var root retained the function class and the loader that
  also defined both nested function classes;
- the `create-database :self` `MultiFn` method table retained its method class
  and the loader that also defined the nested `go` classes; and
- OOM-level pressure cleared 115 other cache entries, but all 213 watched
  `datahike.writer` classes and all 177 watched
  `datahike.db.transaction` classes remained live. Database creation and a
  five-datom transaction then succeeded.

The old development dependency cache did have a separate permanent-loss
defect: rebuilding deleted and replaced the single directory already present
on a live JVM's classpath. A delayed nested AOT class then had neither its
original classfile nor a safe in-memory reconstruction source. Deleting one
not-yet-resolved nested AOT class in a scratch JVM reproduced the exact
`ClassNotFoundException` shape; restoring the classfile made the same
constrained-heap child succeed.

The historical JVM did not record enough loader and code-source evidence to
distinguish that cache mutation from a stale definition generation or another
loader-lifecycle edge. The former claim that these particular Datahike classes
were retained only by the soft cache is therefore rejected. The operational
repair does not depend on recovering that unavailable attribution.

## Repair

The dependency-cache mechanism landed immediately after this issue was filed:

- `09427d65ab6e5d0d168173f21056d7f8d6454134` publishes dependency AOT
  output to immutable content-addressed directories, records the selected
  directory, and reaps only directories not referenced by a live exact
  `(pid, start-instant)` process identity;
- `d566bf09e08f4a1c86e4a8fa7d895c7846cadc53` launches each operator JVM
  with that exact directory on its classpath and records the path with the
  process; and
- `389afa77b` strengthens the recurring lifecycle regression so a constrained
  child delays a nested AOT class until after a new cache publication and
  maximal soft-reference pressure.

The owners are [`dev_cache.clj`](../../../../dev_cache.clj),
[`fresh_operator.clj`](../../../../script/seon/fresh_operator.clj), and
[`dependency_cache_test.clj`](../../../../test/seon/dev/dependency_cache_test.clj).
No new loader registry, recovery retry, or second class mechanism was added.

This is dependency AOT, not whole-program AOT. Vendored dependency bytecode is
a derived, content-addressed launch cache; first-party Seon namespaces remain
source-loaded and REPL-first. Ordinary first-party functions are retained by
their Vars, and discarded eval wrappers may unload. SCI agent definitions do
not emit JVM classes. A future observed first-party late sibling lookup would
need its exact caller and defining-loader roots captured before choosing a
lifetime owner; the synthetic possibility alone does not justify retaining
every eval loader forever.

## Rejected candidates

- **`-XX:SoftRefLRUPolicyMSPerMB`: rejected.** The
  [measured setting study](../../../prds/sci-execution-runtime/research/soft-reference-retention-setting-2026-08-03.md)
  showed that a larger value delays age-based clearing but does not guarantee
  lifetime. Heap exhaustion cleared the class and reproduced the failure even
  at `1000000`, one thousand times the default. The setting would also change
  every soft-reference cache in the process.
- **Retain every loader or class forever: rejected.** Retaining the defining
  loader repairs the synthetic case, but the two current Datahike targets
  already have strong roots. A global registry would retain every eval and hot
  reload generation without a bounded lifecycle.
- **Whole-tree AOT: rejected.** It would make first-party live redefinition
  compete with stale classpath generations. The dependency-only cache provides
  stable bytes for the failed owners without giving up source-first Seon code.
- **`require :reload` plus retry: rejected.** It is symptom recovery, can leave
  an already failed caller generation unresolved, and a direct isolated
  Datahike namespace reload exposed a separate dependency-order failure.
- **Operator JVM flag: rejected.** The launch line was inspected, but the
  measured retention flag supplied no correctness property, so
  [`fresh_operator.clj`](../../../../script/seon/fresh_operator.clj) was not
  changed for this repair.

## Evidence

The complete durable investigations are:

- [dynamic classloader lifetime and Datahike pressure](../../../prds/sci-execution-runtime/research/dynamic-classloader-lifetime-2026-08-03.md),
  with its committed
  [reproduction script](../../../prds/sci-execution-runtime/research/scripts/dynamic-classloader-lifetime-probe.clj);
- [soft-reference JVM setting measurements](../../../prds/sci-execution-runtime/research/soft-reference-retention-setting-2026-08-03.md),
  with its committed
  [setting probe](../../../prds/sci-execution-runtime/research/scripts/soft-reference-retention-probe.clj); and
- [dependency disk-cache audit](../../../prds/sci-execution-runtime/research/dynamic-class-disk-cache-audit-2026-08-03.md).

Verification completed on 2026-08-03:

- source-loaded Datahike under `-Xmx256m` and
  `-XX:SoftRefLRUPolicyMSPerMB=0`: 213/213 writer classes and 177/177
  transaction classes survived OOM-level pressure; create plus transact
  returned five datoms;
- cached Datahike under the same heap and policy: all Datahike roots resolved
  through `AppClassLoader`, there were zero writer or transaction entries in
  `DynamicClassLoader/classCache`, pressure retained 118 MiB before OOM, and
  create plus transact again returned five datoms;
- deterministic cached-path probe: clearing the entire dynamic class cache
  from 119 entries to zero left it at zero while the Datahike transaction
  succeeded; and
- `bin/test seon.dev.dependency-cache-test`: one test, ten assertions, zero
  failures and zero errors. The child JVM used `-Xmx96m` and policy `0`,
  exhausted the heap, then loaded a previously unused nested function class
  and a second namespace from the old process-pinned cache after refresh.

Only an hours-long live JVM can add operational soak evidence about natural
allocation timing and workload history. It cannot strengthen the repaired
class-availability invariant beyond the deterministic proof: the dependency
classes are application-classloader classes backed by an immutable directory
that remains present for that exact process lifetime. Future live diagnosis
should nevertheless record the target class, caller class, defining loaders,
parent chains, code sources, and strong roots before restart; an absent soft
cache entry alone is not attribution.

## Tool and render feedback

The original cause rendered as seven identical large stack traces. The
dependency-cache repair does not touch fault committing or rendering, so it
does not claim to repair that presentation defect. The standard remains one
bounded fault fact per cause, with repeat count and provenance attached and
full diagnostics retrievable on demand.

The committed probes follow that standard: they emit compact namespaced data,
bounded loader samples, and bounded exception cause chains instead of repeated
stacks. An early loader probe emitted one 37,000-token line; the final probe
replaced it with counts, depth distributions, and small examples.

## Resolution

Resolved by the immutable dependency cache commits
`09427d65ab6e5d0d168173f21056d7f8d6454134` and
`d566bf09e08f4a1c86e4a8fa7d895c7846cadc53`, with the pressure/lazy-load
regression in `389afa77b`. The failed dependency owners now have reloadable
bytes for the exact lifetime of every operator JVM, and the same constrained
pressure scenario no longer loses them.
