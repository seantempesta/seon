---
type: research
status: active
tags: [research, runtime]
---

# Soft-reference retention setting probe

## Verdict

Do **not** add `-XX:SoftRefLRUPolicyMSPerMB` to the operator launch as the
repair. A larger value measurably delays HotSpot's age-based clearing of
softly reachable dynamic classes, but it does not retain them for the JVM
lifetime. Deliberate heap exhaustion cleared the class at every measured
setting, including `60000` and `1000000`, after which the still-live caller
failed with `NoClassDefFoundError` and `ClassNotFoundException`.

The setting can be a probabilistic delay only. The repair needs a strong
lifetime reference to the required class or defining loader, or reloadable
class bytes. No launch-line change is justified by this probe.

I read
[the long-lived JVM issue](../../../seon/issues/long-lived-jvm-loses-soft-referenced-dynamic-classes.md)
and
[the in-server test research](in-server-tests-2026-08-03.md)
end to end before designing the probe. The issue's incident attribution stays
uncertain: this probe proves that soft-cache eviction can cause the reported
failure family; it does not prove which event caused either historical live
incident.

## Dependency ledger

- Seon selects `org.clojure/clojure` 1.12.5 (`deps.edn:15`). The maintained
  source is vendored at commit
  `b18d3adc5b5f4d5d0ccea966203fb67a614d5c3d` under
  `reference-code/clojure/`.
- `DynamicClassLoader` owns the static cache and resolution behavior:
  `reference-code/clojure/src/jvm/clojure/lang/DynamicClassLoader.java:24-82`.
  `RT.makeClassLoader` and `RT.baseLoader` construct the loaders and their
  parents (`reference-code/clojure/src/jvm/clojure/lang/RT.java:2160-2173`).
  `Compiler.eval` constructs a fresh loader for every top-level evaluation
  (`reference-code/clojure/src/jvm/clojure/lang/Compiler.java:7717-7730`),
  while one source load binds an outer parent loader around its forms
  (`Compiler.java:8190-8253`); each form's eval still creates its own child.
- The measured VM was Homebrew OpenJDK 26.0.1, build `26.0.1`. The matching
  OpenJDK 26.0.1 GA source commit is
  [`fcc00fc4aee7337bef93840b7ae2fddf1fa5c428`](https://github.com/openjdk/jdk26u/tree/fcc00fc4aee7337bef93840b7ae2fddf1fa5c428).
  HotSpot computes the clearing interval from free heap multiplied by
  `SoftRefLRUPolicyMSPerMB` in
  [`referencePolicy.cpp`](https://github.com/openjdk/jdk26u/blob/fcc00fc4aee7337bef93840b7ae2fddf1fa5c428/src/hotspot/share/gc/shared/referencePolicy.cpp#L52-L83).
  The Java contract says softly reachable referents are cleared before an
  `OutOfMemoryError` and otherwise places no timing constraint in
  [`SoftReference.java`](https://github.com/openjdk/jdk26u/blob/fcc00fc4aee7337bef93840b7ae2fddf1fa5c428/src/java.base/share/classes/java/lang/ref/SoftReference.java#L42-L72).
- The operator already admits explicit JVM options immediately before its
  `-M:dev` alias in `script/seon/fresh_operator.clj:254-283`. The launch seam
  can carry the flag; mechanical feasibility is not the question.
- The shortest falsifier is one strongly retained caller class whose
  not-yet-resolved callee was defined by a separate `DynamicClassLoader` and
  is reachable only through `classCache`. Clear the soft reference, invoke
  the caller, and inspect the reference/class/loader reachability plus the
  cause chain.

## Mechanism proved

`DynamicClassLoader.classCache` is a process-static
`ConcurrentHashMap<String, Reference<Class>>` (`DynamicClassLoader.java:26-31`).
`defineClass` stores the newly defined class under a `SoftReference`
(`:44-48`). Every dynamic loader consults this global cache before delegating
to its parent (`:64-81`). If the referent has cleared, `findInMemoryClass`
removes the dead entry and returns no class (`:51-61`). No byte array is kept
there.

The reusable
[probe](scripts/soft-reference-retention-probe.clj)
uses Clojure's bundled ASM to define two tiny classes in separate sibling
`DynamicClassLoader`s:

- the caller stays strongly reachable with its loader, but has not resolved
  the callee yet;
- the callee class and its loader have only weak diagnostic references in the
  harness; the only retaining production mechanism is the static cache's soft
  class reference; and
- after pressure, the caller invokes the callee by name. A surviving cache
  entry returns `42`; a cleared entry is removed and resolution falls through
  to the classpath, where no class bytes exist.

This is the relevant separate-evaluation shape. A new top-level Clojure eval
gets a fresh dynamic loader, and the live in-server probe observed different
loader identities on successive prepl evaluations. Retaining one sibling
loader does not retain the loader that defined a class reached later through
the global cache.

The decisive cleared state was consistent in every failing trial:

```clojure
{:cache-key-present? true
 :cache-referent-alive? false
 :callee-class-alive? false
 :callee-loader-alive? false
 :caller-loader-retained? true}
```

Invocation then produced
`InvocationTargetException -> NoClassDefFoundError -> ClassNotFoundException`,
and `findInMemoryClass` removed the dead cache key. Thus the class and its
defining loader were not strongly retained; the caller and its loader
remaining alive could not recover the missing bytecode.

## Commands and measured results

The classpath was the exact project classpath from `clojure -Spath`. Each row
ran in a new constrained-heap child JVM:

```sh
probe_cp=$(clojure -Spath)
java -Xmx64m -XX:SoftRefLRUPolicyMSPerMB=0 \
  -cp "$probe_cp" clojure.main \
  docs/prds/sci-execution-runtime/research/scripts/soft-reference-retention-probe.clj \
  gc-only

java -Xmx64m -XX:SoftRefLRUPolicyMSPerMB=60000 \
  -cp "$probe_cp" clojure.main \
  docs/prds/sci-execution-runtime/research/scripts/soft-reference-retention-probe.clj \
  moderate

java -Xmx64m -XX:SoftRefLRUPolicyMSPerMB=60000 \
  -cp "$probe_cp" clojure.main \
  docs/prds/sci-execution-runtime/research/scripts/soft-reference-retention-probe.clj \
  exhaustion
```

The second command-line argument, when present, ages the unopened reference
for that many milliseconds before the selected pressure profile.

| Heap | Actual policy, ms/MB | Age | Pressure | Cache/class/loader | Invocation |
|---|---:|---:|---|---|---|
| 64 MiB | default `1000` | 0 | GC only | alive | `42` |
| 64 MiB | `0` | 0 | GC only | cleared | NCDFE/CNFE |
| 64 MiB | default `1000` | 0 | moderate, 45% of available heap | alive | `42` |
| 64 MiB | `0` | 0 | moderate | cleared | NCDFE/CNFE |
| 64 MiB | `60000` | 0 | moderate | alive | `42` |
| 48 MiB | default `1000` | 45 s | GC only | cleared | NCDFE/CNFE |
| 48 MiB | `60000` | 45 s | GC only | alive | `42` |
| 48 MiB | `1000000` | 45 s | GC only | alive | `42` |
| 64 MiB | default `1000` | 0 | exhaust to OOME, then release | cleared | NCDFE/CNFE |
| 64 MiB | `0` | 0 | exhaust to OOME, then release | cleared | NCDFE/CNFE |
| 64 MiB | `60000` | 0 | exhaust to OOME, then release | cleared | NCDFE/CNFE |
| 64 MiB | `1000000` | 0 | exhaust to OOME, then release | cleared | NCDFE/CNFE |

The exhaustion profile allocated 28 MiB of retained one-MiB arrays before
the caught `OutOfMemoryError` in every recorded 64 MiB run. Repeated key
profiles were deterministic across three fresh JVMs each:

- policy `0` plus ordinary GC: 3/3 cleared and failed;
- policy `60000` plus moderate pressure: 3/3 retained and returned `42`; and
- policy `60000` plus exhaustion: 3/3 cleared and failed.

`-XX:+PrintFlagsFinal` reported `1000 {default}` without an override and
reported `0`, `60000`, or `1000000` as `{command line}` when supplied. The
probe independently read the effective value through
`HotSpotDiagnosticMXBean`; every output row carried the expected value.

## What the setting changes

For the measured max-heap policy, HotSpot keeps a soft reference while its
age is no greater than:

```text
(MaxHeapSize - used-at-last-GC, in MiB) * SoftRefLRUPolicyMSPerMB
```

The setting therefore scales an age threshold. The 45-second comparison is
the direct evidence that `60000` is materially different from the default.
It is not a pin:

- the Java contract permits earlier clearing and guarantees clearing before
  the VM reports allocation failure;
- exhaustion cleared the referent even with the 1000-times-default
  `1000000` value;
- the value applies process-wide to every soft-reference cache, not only
  Clojure's dynamic classes; and
- lengthening retention consumes the heap headroom the soft-reference design
  exists to recover, so it can move pressure into more collection work or a
  later allocation failure without making class bytes recoverable.

`0` is actively harmful for this use: an ordinary explicit full GC cleared
the newly created class immediately. `60000` and `1000000` reduce the
probability of age-based eviction but cannot satisfy the process-lifetime
contract. Keeping the existing default likewise leaves the proven failure
unchanged.

## Candidate disposition

| Candidate | Disposition | Evidence |
|---|---|---|
| Add a high `SoftRefLRUPolicyMSPerMB` on operator launch | Reject as repair | Delays clearing, but exhaustion clears and permanently breaks the caller; broad process-wide memory trade-off |
| Set the value to `0` to make cache behavior predictable | Reject | Makes a normal full GC reproduce the defect immediately |
| Leave the default and rely on recent-use bias | Reject as posture | Aged references clear under ordinary GC and the live incident already exceeded that window |
| Strongly retain required dynamic classes/loaders or make class bytes reloadable | Mechanism candidates for the repair owner | These change reachability/recovery rather than only HotSpot's eviction timing |

The probe intentionally does not select between strong lifetime retention and
reloadable disk bytes; that decision requires the full Datahike and
first-party loader-lifecycle evidence owned by the repair lane.

## Proof boundary

This child-JVM harness is an honest recurring mechanism probe: it controls
reachability and pressure and makes the clear/fail transition deterministic.
It proves the JVM option cannot guarantee the required lifetime.

It does not prove that a repaired Seon JVM survives hours of its real workload,
nor does it reproduce the exact generated Datahike class names. A long-running
live JVM must still demonstrate that the chosen repair keeps transactions
working across realistic GC and heap pressure, and the Datahike-specific
reproduction must show reload or retention of its actual generated class.

## Tool and render feedback

The probe prints one compact, namespaced EDN result per child JVM. It reports
the effective flag, cache state, class/loader reachability, and bounded cause
class chain without emitting repeated stack traces. That shape was readable
and sufficient to compare trials.

The reported production failure emitted seven identical large stack traces
for one cause. This lane did not touch that fault path. The repair should
preserve the stated standard: one bounded fault fact per cause, with duplicate
observations folded into that cause rather than seven repeated traces.
