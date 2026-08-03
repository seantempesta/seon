---
type: research
status: active
tags: [research, runtime, boot]
---

# Dynamic-class disk-cache audit

## Verdict

The dependency-class disk cache is implemented, committed, and active in
operator-launched JVMs. It is not merely in flight. The implementation landed
as a sequence from `9bb559df953c63ac2aca85656f80fdb900de4075` through
`d566bf09e08f4a1c86e4a8fa7d895c7846cadc53` on 2026-08-02/03. The current
operator process record pins one immutable cache directory for PID 3885, and
that directory contains AOT classfiles for both incident owners:
`datahike.writer` and `datahike.db.transaction`.

The narrow cache is a viable repair for dependency-owned dynamic classes. In a
scratch source-loaded JVM, deliberately emptying
`DynamicClassLoader/classCache` made the next Datahike transaction fail with
`NoClassDefFoundError: datahike/db/interface/SearchContext`. In the cached
scratch JVM, the two incident class families loaded from the immutable cache
through the application classloader; emptying the same dynamic cache from 119
entries to zero left it at zero, and a real Datahike schema+data transaction
completed with five transaction datoms. That is stronger evidence than a
launch-option theory: the affected dependency bytecode has a disk owner and no
longer depends on a soft reference.

This does not close the whole issue. First-party source-loaded classes,
ordinary JVM `eval`/prepl-generated classes, and dependency namespaces outside
the observed `seon.artifact` load closure still have no disk fallback. Actual
heap-pressure eviction (rather than the deterministic equivalent of a missing
cache entry) and long-lived operation remain to be proven by the issue owner.

## Material read end to end

I read the following whole documents or sources before reaching the verdict:

- [the issue](../../../seon/issues/long-lived-jvm-loses-soft-referenced-dynamic-classes.md);
- [the in-server test research](in-server-tests-2026-08-03.md);
- [the runtime architecture](../../../seon/architecture/architecture.md);
- [`DynamicClassLoader.java`](../../../../reference-code/clojure/src/jvm/clojure/lang/DynamicClassLoader.java);
- [`dev_cache.clj`](../../../../dev_cache.clj);
- [`seon.artifact`](../../../../src/seon/artifact.clj);
- [`build.clj`](../../../../build.clj); and
- [the dependency-cache regression](../../../../test/seon/dev/dependency_cache_test.clj).

I also read the complete cached-boots ruling in
[the active plan](../plan/README.md) and the relevant current/historical
load-time blocks in [the working edge](../plan/unsettled.md), plus the exact
operator launch/cache ownership sites and the Clojure compiler/RT/prepl source
named below.

## Dependency ledger

| Dependency or owner | Selected revision | Evidence and role |
|---|---|---|
| Clojure | `org.clojure/clojure` 1.12.5 in [`deps.edn`](../../../../deps.edn); maintained source `b18d3adc5b5f4d5d0ccea966203fb67a614d5c3d` | [`DynamicClassLoader.java`](../../../../reference-code/clojure/src/jvm/clojure/lang/DynamicClassLoader.java) owns the soft cache. `javap` of the selected 1.12.5 jar matched the maintained source's `SoftReference`, lookup, and fallback instructions. |
| Datahike | local root `reference-code/datahike`, `0e8601d7f2f68c01070e13a95483bc82be04cabc` | [`datahike.db.transaction/validate-val`](../../../../reference-code/datahike/src/datahike/db/transaction.cljc) emits the reported validation inner functions; [`datahike.writer`](../../../../reference-code/datahike/src/datahike/writer.cljc) line 327 is the `go` form from the first incident. |
| Cache build | `io.github.clojure/tools.build` 0.10.5 | [`dev-cache`](../../../../dev_cache.clj) creates the basis, discovers the dependency load closure, compiles it, validates it, and publishes its manifest. |
| Seon load root | [`seon.artifact`](../../../../src/seon/artifact.clj) | Requiring this namespace is the one discovery root. Its static requires reach `seon.cluster`, `seon.cluster.source`, and `seon.cluster.store`; their dependency loads define the selected closure. |
| Development launch | [`script/seon/fresh_operator.clj`](../../../../script/seon/fresh_operator.clj) | Ensures the cache before cold launch, prepends its exact directory with a generated `:seon-cache` alias, records the cache path as a JVM property and process fact, and protects it from reaping while that exact process identity lives. |
| Standalone artifact | [`build.clj`](../../../../build.clj) | Copies the same dependency cache, first-party source, and resources into `target/seon-standalone.jar`; it does not AOT first-party source. |

Relevant Seon landings are:

- `9bb559df953c63ac2aca85656f80fdb900de4075` — first dependency-closure
  cache;
- `94727859dc4d8f640d8c6c5096e96c686d758010` — test JVMs receive the
  cache;
- `ede681707a71bdb0adecb9ee17aa65b07502d6b0` — safe cache reuse;
- `09427d65ab6e5d0d168173f21056d7f8d6454134` — immutable digest
  directories and the recurring regression; and
- `d566bf09e08f4a1c86e4a8fa7d895c7846cadc53` — one exact cache digest
  pinned per operator JVM.

## What is compiled and how it is selected

[`dev-cache/discovery-form`](../../../../dev_cache.clj) runs in an uncached
child. It observes `clojure.core/load` while requiring `seon.artifact`, maps
loaded namespace roots back to the dependency directories and archives in the
tools.build basis, and excludes first-party source because it is not in those
dependency containers. It also skips jar namespaces that already provide an
`__init.class`. [`compile-form`](../../../../dev_cache.clj) compiles each
selected namespace under one staging `*compile-path*`.

The cache validates that every selected namespace emitted an `__init.class`,
that the class is newer than its source, and that no first-party `seon`
loader class exists. The newer-than-source condition is load-bearing:
[`RT.load`](../../../../reference-code/clojure/src/jvm/clojure/lang/RT.java)
selects `__init.class` only when its class resource is newer than the source
resource. A manifest records the dependency-source digest, project digest,
namespace vector, exact sources, cache version, and duration. Publication is
an atomic move to `target/dev-dependency-classes/<cache-digest>`.

The operator constructs one generated tools.deps alias whose `:extra-paths`
contains that exact directory, launches `-M:dev:seon-cache`, and records both
`-Dseon.dependency-cache.path` and the path in the process record. Refresh
publishes a different directory; reaping keeps the selected directory and
every directory named by a live `(pid, start-instant)` record. The recurring
test proves a child can lazily load a second cached namespace after a refresh,
and that only after the child exits may the old cache be removed.

At observation time, the live default JVM's process record named cache
`a9dcf90f79f88be8356d4d77f3826bd4828e27bd69d686da328dbe7803404d84`.
Its manifest is version 3 with 405 namespaces. It contains:

- 55 Datahike namespaces;
- 225 `datahike.writer*.class` files;
- 180 `datahike.db.transaction*.class` files; and
- `datahike.db.transaction$validate_val.class` plus its two emitted inner
  function classes.

`javap -l` ties the AOT classes back to source rather than name resemblance:
`datahike.writer$fn__56248$fn__56263` has line 327 throughout its constructor
and invocation, exactly the incident's `go` form, while
`datahike.db.transaction$validate_val$fn__41521` has line 34, the nil-value
validation branch. The incident's generated suffixes were `fn__56649` /
`fn__56664` and `fn__41929`; the compiled numbers shifted, but the source
locations and function ownership did not.

The separately current cache selection had the same dependency digest and the
same counts but a different project/cache digest, demonstrating the intended
property: the old live JVM keeps its exact immutable bytes while later source
publishes a new selection. Generated numeric suffixes differ between source
loads and AOT compilations, so the stable evidence is the owning namespace and
function family, not the incident's `fn__56649` or `fn__41929` number.

## Classloader mechanism

The selected Clojure implementation makes the failure possible in four
explicit steps:

1. `DynamicClassLoader.defineClass` puts every dynamically defined `Class` in
   one static `ConcurrentHashMap<String, Reference<Class>>` as a
   `SoftReference` associated with a `ReferenceQueue`.
2. `findInMemoryClass` returns the referenced class while present and removes
   the entry when the reference is cleared.
3. `findClass` and `loadClass` then fall through to the loader parent and URL
   classpath. A dynamic loader has no own URLs by default, so a source-compiled
   class with no parent classpath bytes has no recovery source.
4. [`Compiler.eval`](../../../../reference-code/clojure/src/jvm/clojure/lang/Compiler.java)
   currently binds `Compiler/LOADER` to a newly made `DynamicClassLoader` on
   every evaluation (`if(true)` at lines 7721-7726), then pops it. The parent
   comes from the previously bound loader or thread context loader. Clojure's
   prepl calls ordinary `eval` for each read form, so the in-server research's
   observed fresh loader per prepl evaluation is explained directly by the
   compiler, not by a Seon wrapper.

Source-loading one namespace is more fragmented than “one namespace, one
loader”: `Compiler.load` binds a load loader, but each top-level form's
`Compiler.eval` still creates a child loader. The source-mode probe observed
`datahike.writer` function families spread across many distinct dynamic
loaders, while the two `validate-val` inner functions shared their form's
loader.

AOT changes the resolution mechanism rather than tuning the reference. The
cache puts the class bytes on the process classpath. The scratch probe loaded
both `datahike.writer$transact_BANG_` and
`datahike.db.transaction$validate_val$fn__41521` through
`jdk.internal.loader.ClassLoaders$AppClassLoader`, with code source equal to
the immutable cache directory. Neither class needed a
`DynamicClassLoader/classCache` entry.

## Probes

### Source path: deterministic missing-cache reproduction

A scratch `clojure -M:dev` JVM required `datahike.api`, created a memory
database, reflectively obtained the exact `DynamicClassLoader/classCache`, and
emptied it before the next schema+data transaction. The transaction failed at
the first newly resolved dependency class:

```text
NoClassDefFoundError: datahike/db/interface/SearchContext
Caused by: ClassNotFoundException: datahike.db.interface.SearchContext
```

Before removal, the source JVM held 14,702 cache entries. The observed
`SearchContext`, `validate-val` inner functions, and writer function families
were all defined by `DynamicClassLoader` instances. Emptying the map is not a
claim that GC cleared every entry in one pass; it is a deterministic probe of
the terminal condition soft-reference eviction creates: resolution begins
with no in-memory class and no disk bytes.

### Cached path: transaction survives with the cache empty

A second scratch JVM used the same `-Sdeps`/`-M:dev:seon-cache` classpath shape
as the operator. It loaded the writer and validation classes, cleared the
dynamic cache, then executed the same memory-database transaction:

```clojure
{:writer-loader "jdk.internal.loader.ClassLoaders$AppClassLoader@14dad5dc"
 :writer-location "file:.../target/dev-dependency-classes/1b87.../"
 :validate-loader "jdk.internal.loader.ClassLoaders$AppClassLoader@14dad5dc"
 :validate-location "file:.../target/dev-dependency-classes/1b87.../"
 :cache-before 119
 :cache-after-clear 0
 :cache-after-transaction 0
 :tx-datoms 5}
```

The process exited cleanly after releasing the connection and deleting the
memory database. The dynamic cache remaining empty after the transaction is
the important discriminator: success did not merely repopulate another soft
entry.

### Recurring gate

`bin/test seon.dev.dependency-cache-test` passed:

```text
Ran 1 tests containing 10 assertions.
0 failures, 0 errors.
```

That existing test proves immutable-directory refresh, exact process pinning,
lazy classpath loading after refresh, and post-exit reaping. It does not prove
the soft-cache failure or the Datahike recovery path.

The closest honest new recurring regression is one expendable child-JVM test
at this same owner: run the same Datahike operation from source and from the
cache; deterministically clear or remove the required dynamic-cache entry;
assert the source arm fails for the named missing class and the cached arm
succeeds with the incident family loaded from the immutable cache directory.
An actual constrained-heap/soft-pressure child is the next stronger
falsifier, but should supplement rather than replace the deterministic seam
test because GC policy is not a stable unit-test clock.

Only a long-running live JVM can demonstrate that realistic allocation and
collector pressure no longer stop dependency transactions over hours. That
proof must also distinguish “dependency class loaded from disk” from
first-party or eval-generated dynamic classes and retain the exact cache
directory/process identity in its evidence.

## Candidate assessment

### Adopted: narrow dependency AOT/disk cache

This is the recommended dependency repair and it already exists. It removes
the permanent-loss condition for selected dependency classes while preserving
the source of truth and runtime load behavior for first-party Seon code. The
2026-08-03 owner ruling explicitly supersedes the earlier absolute “DEV has no
AOT” statement: reusable dependency-class preparation is allowed, the cache
contains only the derived dependency closure, first-party Seon stays
source-loaded, and whole-tree AOT remains rejected.

The standalone artifact already packages this same cache beside first-party
source, so development and publication do not invent separate dependency
class mechanisms. This is narrower than whole-tree AOT and is the existing
classpath/disk-cache mechanism the issue should build on.

### Rejected as the primary repair: whole-tree AOT

Compiling first-party Seon would put its classes on disk but would violate the
live-redefinition boundary and make ordinary source edits compete with stale
loader classes. It also executes top-level forms at build time and couples
generated bytecode to the compiler/dependency basis. The current mechanism
gets the dependency safety benefit without giving up first-party REPL-first
development.

### Rejected as sufficient: soft-reference retention JVM setting

A JVM retention setting changes when soft references are collected; it does
not create recoverable bytes or guarantee that a class survives every allowed
heap condition. For the selected dependencies it is now unnecessary because
the application classloader owns disk bytes. It may be measured as an
additional mitigation for residual dynamic classes, but it is not a mechanism
repair and must not replace the disk/ownership proof.

### Not established by this audit: retaining loader chains or dynamic classes

Strongly retaining every generated loader/class for process lifetime could
cover first-party and prepl evals, but it needs a bounded ownership design and
heap-growth measurement. `Compiler.eval` creates a loader per form, so an
unbounded global retention collection would turn class unloading failure into
a process-lifetime memory leak. This audit found no existing Seon retention
owner and makes no recommendation to add one without the issue lane's
constrained-heap evidence.

## Residual exposure and gaps

- The cache is the load closure observed while requiring `seon.artifact`, not
  every source namespace present in every dependency. A dependency namespace
  reached only later through optional/dynamic resolution remains source-loaded
  unless the discovery root eventually loads it. The manifest makes this
  queryable; absence should widen discovery or produce an explicit decision,
  never be assumed safe.
- First-party `src/` is deliberately excluded and emits dynamic JVM classes
  under source loading/reload. The issue still needs an owner ruling and proof
  for that residual.
- Each JVM prepl `clojure.core/eval` creates a new dynamic loader. Definitions
  and generated classes from that path are not written to the dependency
  cache. Ordinary SCI function interpretation is a different path, but host
  JVM evals and any first-party runtime compilation remain exposed.
- The current recurring cache test never empties the class cache or executes
  a Datahike transaction. The direct probe should become a discovered child
  test before the issue closes.
- The issue text saying mitigation is “already in flight” is stale relative to
  the landed commits and live process record. It should describe the cache as
  landed and narrow the remaining acceptance boundary to dynamic residuals,
  realistic pressure, and recurring proof.

## Tool and render feedback

The source-path failure rendered one cause three times in the scratch probe:
the Datahike writer log printed the large throwable map, the async worker
printed an uncaught stack trace, and `clojure.main` printed the terminal error.
The live incident reportedly emitted seven identical large stack traces for
one cause. The dependency-cache repair does not touch that reporting path, so
it must not claim to solve the presentation defect. The standard remains one
bounded fault fact per cause, with full diagnostics retrievable on demand.

`bin/seon status` also rendered a live default row with a reachable prepl and,
in the same response, “roster unreadable” because a prepl was unreachable.
That is internally difficult to interpret and matches the already-filed status
rendering defect; it did not block this audit.
