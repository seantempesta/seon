---
type: research
status: active
tags: [research, runtime, boot]
---

# Dynamic classloader lifetime: eviction is real, target attribution is not

## Verdict

The general failure mechanism is real and reproduced: Clojure compiles forms
into sibling `DynamicClassLoader`s, uses one global soft-reference cache to
bridge classes between those siblings, and permanently loses an in-memory-only
class when both its cache reference and its otherwise-unrooted defining loader
are collected. A later sibling lookup then ends in `ClassNotFoundException`.
Retaining only the defining loader across the same pressure kept the class live
and made the lookup succeed.

The current issue's attribution of the two observed Datahike failures to that
mechanism is **not proved and is contradicted by the current source-loaded
generation**. On a fresh load, the `validate-val` Var strongly retains the
loader that defines both of its nested function classes. The `create-database
:self` method is strongly retained by its `MultiFn` method table, and its
nested `go` classes share that defining loader. Under deliberate OOM-level
soft-reference pressure, all 213 `datahike.writer` and all 177
`datahike.db.transaction` classes watched by weak reference remained live;
Datahike then created a database and committed a schema plus entity
successfully. Only disposable `eval...` wrapper generations unloaded.

Therefore the live incident remains a real blocker, but “the static soft cache
was the only strong owner of these particular classes” is not an adequate
cause. The missing evidence is the failing JVM's **exact target class, defining
loader, caller loader, their parent chains, and strong roots at the moment
before loss**. A stale/replaced generation or another loader-lifecycle edge may
make the incident classes soft-only; the live cache snapshot showing only that
the names were absent cannot distinguish those cases.

I read
[long-lived-jvm-loses-soft-referenced-dynamic-classes.md](../../../seon/issues/long-lived-jvm-loses-soft-referenced-dynamic-classes.md)
and
[in-server-tests-2026-08-03.md](in-server-tests-2026-08-03.md)
end to end before this investigation. I also read the complete vendored
`DynamicClassLoader.java` and `clojure.core.server` sources, the relevant
`Compiler.java`, `RT.java`, and `clojure.core` loading paths, both named
Datahike call sites, and Seon's prepl/MCP evaluation path.

## Dependency ledger

- Runtime: OpenJDK `26.0.1`, Clojure CLI `1.12.5.1654`, and project coordinate
  `org.clojure/clojure 1.12.5` (`deps.edn:15`).
- Clojure source grounding: vendored revision
  `b18d3adc5b5f4d5d0ccea966203fb67a614d5c3d`. It is post-1.12 master
  (`1.13.0-master-SNAPSHOT`), while the runtime coordinate is 1.12.5; the
  loader paths below were also exercised reflectively in the running 1.12.5
  classes.
- Datahike source and final measurements: vendored revision
  `574c5f0f0db9411d1982769f14512cb24ef719da`. The checkout advanced from
  `0e8601d7f2f68c01070e13a95483bc82be04cabc` during the investigation; every
  final pressure, transaction, and repeated prepl observation below was rerun
  after the advance.
- Clojure owners:
  `reference-code/clojure/src/jvm/clojure/lang/DynamicClassLoader.java:24-81`,
  `reference-code/clojure/src/jvm/clojure/lang/Compiler.java:7717-7777,8194-8232`,
  `reference-code/clojure/src/jvm/clojure/lang/RT.java:2159-2173,2192-2215`, and
  `reference-code/clojure/src/clj/clojure/core/server.clj:220-296`.
- Datahike owners:
  `reference-code/datahike/src/datahike/writer.cljc:320-335` and
  `reference-code/datahike/src/datahike/db/transaction.cljc:25-51`.
- Seon callers:
  `src/seon/cluster.clj:344-351,1854-1862` and
  `script/seon/dev/mcp.clj:524-533`. The MCP JVM form adds an explicit inner
  `clojure.core/eval` to the prepl's own evaluation.
- Reproducer:
  [dynamic-classloader-lifetime-probe.clj](scripts/dynamic-classloader-lifetime-probe.clj).

## What Clojure actually owns

`DynamicClassLoader` has one static
`ConcurrentHashMap<String, Reference<Class>>`. `defineClass` installs a
`SoftReference<Class>` (`DynamicClassLoader.java:24-48`). `loadClass` tries, in
order, the requesting loader's already-defined classes, that global cache, and
then normal parent/URL lookup (`DynamicClassLoader.java:51-81`). The cache is
therefore not just a cache of bytes; for sibling loaders it is the only lookup
bridge. No byte array is retained for reconstructing an evicted class.

The compiler topology makes that bridge important:

1. Every `Compiler.eval` unconditionally pushes a new loader. The source still
   spells the condition `if(true)` and ignores the apparent `freshLoader`
   choice (`Compiler.java:7717-7727`).
2. `Compiler.load` pushes a source loader, then calls `eval` for each top-level
   form, so each form is defined in another child loader
   (`Compiler.java:8194-8232`).
3. `RT.makeClassLoader` parents each new loader from the currently bound
   compiler loader, otherwise the context/application loader
   (`RT.java:2159-2173`).
4. `clojure.core.server/prepl` calls `eval` once per received form
   (`core/server.clj:230-254`). Seon's MCP JVM wrapper then calls
   `clojure.core/eval` again inside that form (`script/seon/dev/mcp.clj:524-533`).

Thus two ordinary prepl evaluations are siblings, not one accumulating loader.
A `require` that actually reads source creates descendant source/form loaders;
a `require` of an already-loaded namespace defines no dependency classes.
Those prepl/eval loaders are not merely harmless wrappers when a source load or
reload occurs beneath them: they become ancestors of the new definition
generation and remain alive for as long as any descendant definition is
strongly rooted.

## Real prepl and source-load observations

The probe fed three forms through the real `clojure.core.server/prepl`
function. The first two independently compiled anonymous functions:

```text
first  loader=635001030 parent=1220524164
second loader=1682157864 parent=1220524164
```

The distinct loaders with one identical parent confirm the sibling shape.

The third form attempted `(require 'datahike.db.transaction :reload)` in that
fresh JVM. It failed, without pressure, while importing
`datahike.db.HistoricalDB`:

```text
java.net.URLClassLoader/findClass
clojure.lang.DynamicClassLoader/findClass:69
clojure.lang.DynamicClassLoader/loadClass:77
...
datahike.db.transaction$...$loading__... transaction.cljc:1
```

The same direct require fails at a raw `clojure -M:dev -e` surface, so this is
not unique to Seon's prepl. Loading the complete `datahike.api` closure first
succeeds because its dependency order is different. This is a second concrete
class-availability edge in the same loader topology and makes “just `require
:reload` the missing namespace” an unsafe general recovery: a reload may
itself choose an order whose current loader cannot reach a dependency class.
This probe did not isolate why `HistoricalDB` was unavailable despite the
global cache, so that immediate failure is evidence about the lookup boundary,
not a completed attribution of its own.

## Datahike pressure falsifier

The production-shaped run loaded `datahike.api` from source, captured only
identity numbers plus weak references (never a strong `Class` or loader in the
watch), filled a constrained heap until `OutOfMemoryError`, released the
allocation, requested three collections, and only then exercised the writer
and transaction paths.

```bash
clojure \
  -J-Xmx256m \
  -J-XX:SoftRefLRUPolicyMSPerMB=0 \
  -J-Xlog:class+unload=info:file=tmp/dynamic-classloader-datahike-transaction.log:uptime,level,tags \
  -M:dev \
  docs/prds/sci-execution-runtime/research/scripts/dynamic-classloader-lifetime-probe.clj \
  pressure
```

Before pressure:

- cache: 13,366 live entries;
- `datahike.writer`: 213 classes across 21 defining loaders;
- writer roots: 30 function/method values across 23 loaders;
- `datahike.db.transaction`: 177 classes across 56 defining loaders; and
- transaction roots: 58 function values across 57 loaders.

The many loaders are expected: top-level forms each get one. A Var root keeps
its function instance, which keeps its `Class`, which keeps its defining
loader. A `MultiFn` method table supplies the equivalent root for a `defmethod`.

The run held 111 one-megabyte arrays before OOM. After collection, 115 global
cache references were cleared in the final transaction run, but the watch
reported:

```clojure
{:writer-watch {[true true] 213}
 :transaction-watch {[true true] 177}
 :writer-cleared-count 0
 :transaction-cleared-count 0}
```

The class-unload log did contain 11 `datahike.writer$eval...` and four
`datahike.db.transaction$eval...` wrapper classes. It did not unload the
watched function classes. Those wrapper unloads are healthy: no later durable
path should resolve an unrooted evaluation wrapper.

After pressure the probe created a memory database, connected, transacted an
attribute schema plus one entity, released the connection, and deleted the
database:

```clojure
{:outcome :created-and-transacted
 :transaction-count 5}
```

That transaction executed `validate-val`; this is the direct falsifier for the
second reported failing path.

Two focused root inspections make the target mismatch explicit:

- `datahike.db.transaction$validate_val` and its two current nested classes
  (`$fn__43620`, `$fn__43624` in that JVM) had the same defining loader. The
  private Var's raw root held the outer function instance throughout pressure.
- The `create-database :self` method class and all six classes generated for
  its nested `go` state machine had the same defining loader. The `MultiFn`
  method table held the method function throughout pressure.

Generated numeric suffixes vary with load order, so these are the current
structural equivalents of the incident's
`datahike.writer$fn__56649$fn__56664` and
`datahike.db.transaction$validate_val$fn__41929`, not claims that the numeric
names match across JVMs.

## Deliberate sibling-loader reproduction

The `synthetic` mode creates an anonymous Clojure function in one eval loader,
creates a retained caller function in a sibling loader, and makes that caller
perform a late `.loadClass` by the target's generated class name. It then
unmaps the target Var, leaving only the soft cache reference, and applies the
same pressure.

```bash
clojure \
  -J-Xmx128m \
  -J-XX:SoftRefLRUPolicyMSPerMB=0 \
  -J-Xlog:class+unload=info:file=tmp/dynamic-classloader-synthetic.log:uptime,level,tags \
  -M:dev \
  docs/prds/sci-execution-runtime/research/scripts/dynamic-classloader-lifetime-probe.clj \
  synthetic
```

Observed:

- target loader `1123166613` and caller loader `1412564235` were siblings with
  parent `1702478809`;
- the target cache entry was live before pressure and cleared after it;
- weak references to both target `Class` and defining loader were nil;
- the unload log named the exact target function class; and
- the retained sibling caller threw `ClassNotFoundException` through
  `DynamicClassLoader.findClass:69` / `loadClass:77`.

This is the hypothesized mechanism with every link observed, not inferred.

The `synthetic-retain-loader` mode retained only the defining loader across the
same 60 MiB allocation/OOM pressure. The weak class and loader references
remained live, the cache entry remained live, and the sibling caller returned a
new target function instance. This establishes that loader lifetime is a
sufficient repair for the reproduced class, without requiring a parallel
strong class registry.

## Candidate implications

### Retain defining loaders or classes

**Mechanically valid.** The paired synthetic runs prove it. Retaining only a
common parent is insufficient because a parent cannot see a class defined by a
child/sibling. A correct implementation must retain every defining loader
whose in-memory classes can be named later, or retain the classes themselves.

The cost is potentially unbounded metadata, class, constant, and loader-chain
retention across hot reloads. A lifetime registry must therefore name the
generation boundary it owns. “Retain every eval loader forever” would repair
availability by disabling legitimate unloading and needs a measured memory
falsifier before adoption.

### Put dependency class bytes on disk

**Mechanically valid for the vendored dependency closure.** A class available
to the stable application/classpath loader no longer depends on the global
in-memory bridge, and an evicted dynamic generation can be resolved from bytes.
This is the strongest dependency-specific candidate and matches the load-time
class-cache work already in flight.

It does not fix first-party hot-reloaded or arbitrary eval-defined classes, and
it must preserve the explicit source-preferred, REPL-first posture for those
owners. It also needs a proof that the runtime loader actually has the cache
directory in its parent-visible classpath; merely writing bytes that no loader
searches changes nothing.

### Tune `SoftRefLRUPolicyMSPerMB`

**Not a correctness repair.** The VM accepted both measured command-line
values (`0` and `1000000`, confirmed by `-XX:+PrintFlagsFinal`). Repeating the
synthetic proof with `1000000` still cleared the target at heap exhaustion and
produced the same `ClassNotFoundException`. The setting may delay age-based
clearing under moderate pressure; it cannot promise that a softly reachable
class survives the pressure under which soft references exist to be cleared.

### Reload after `NoClassDefFoundError`

**Recovery only, and not generically safe.** Reloading can define a new
generation, but a caller whose symbolic resolution has already failed may need
reload as well, and the direct `datahike.db.transaction` source load above
already fails on `HistoricalDB` without pressure. Any recovery must derive and
reload the dependency/caller closure in a proved order, then retry once. It is
not the mechanism repair.

### Repair a specific loader-lifecycle defect

**Preferred if the failing live generation supplies the missing evidence.**
The current targets are strongly rooted, so finding why the incident generation
was not may reveal one replace/reload path that dropped its loader while a
sibling caller still needed a late resolution. That would be smaller and more
honest than globally retaining every loader. Capture the failing caller and
target loader identities before choosing this candidate.

## Closest recurring proof and long-running limit

A focused regression can honestly express the deterministic structural
contract:

- two eval definitions use sibling loaders;
- an unrooted target is available only through the global cache;
- retaining the target's defining loader preserves late sibling resolution;
  and
- dependency-cache/AOT mode resolves the same target from disk after its
  dynamic generation is gone.

GC timing and soft-reference policy are deliberately nondeterministic, so an
ordinary fast test should not require “the collector must clear this reference
now.” The probe's OOM-backed mode is suitable for a long test or manual live
proof, not the default gate.

Only a long-running JVM can demonstrate the production claim: real hot reload
and eval history, natural heap pressure, and later execution of a path that has
not resolved its nested class before. That proof must record the target/caller
loaders and roots before and after pressure, not only the terminal exception or
an absent cache name.

The issue should remain open until the repaired mechanism passes both the
deterministic sibling-loader proof and a long-running Datahike create/transact
proof in a JVM whose class-loading history resembles the incident.

## Tool and render feedback

- The first probe rendered every Var root and parent chain in one 37,000-token
  line. The retained script now reports counts, depth distributions, bounded
  examples, weak-reference outcomes, and bounded exception stacks. Loader
  diagnostics should default to this bounded shape.
- The observed production cause was logged as seven identical large stack
  traces. This audit did not touch the fault path, but the runtime standard
  should be one bounded fault fact per cause, with a count/provenance link for
  repeats rather than seven copies of the same stack.
- Clojure CLI repeatedly printed a `:java-home` overwrite warning before the
  probe result. It did not affect the mechanism, but it is noise in a tool path
  where the classloader result itself is already dense.
