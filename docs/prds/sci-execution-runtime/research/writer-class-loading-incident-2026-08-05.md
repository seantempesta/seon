---
type: research
status: active
tags: [research, runtime, boot, reliability]
---

# Writer class-loading incident: qualified mechanism verdict

## Verdict

The best-supported explanation for the 2026-08-03 writer incident is a
not-yet-linked dependency AOT classfile disappearing when the one mutable
`target/dev-dependency-classes` directory was replaced beneath the live JVM.
This is a qualified historical attribution, not unique forensic proof:

- the mutable replacement is recorded as having happened earlier in the same
  live default JVM;
- removing the delayed nested writer classes from that same classpath shape
  reproduced the incident's exact
  `NoClassDefFoundError -> ClassNotFoundException` chain while the JVM stayed
  alive;
- a real Datahike source reload created a different dynamic loader generation,
  but the existing writer then stopped completing transactions rather than
  producing the incident's linkage signature; and
- generic soft-cache clearing remains real for unrooted dynamic classes but is
  falsified as the sufficient explanation for the strongly rooted writer and
  transaction classes in a fresh source-loaded generation.

The failed JVM did not preserve its process identity, target and caller
loaders, code sources, selected cache path, or class-resource existence at the
failure instant. Those absent facts prevent a claim that no other event could
have produced the same terminal exception.

**Current implementation verdict:** the supported development-cache owner can
no longer construct the incident's replace-under-a-live-JVM path. It publishes
each complete cache to a never-replaced content-addressed directory, launches
the JVM with that exact directory, records the path with exact
`(pid, start-instant)` identity, and reaps it only after that identity is no
longer live. Arbitrary manual deletion outside that owner remains possible in
the ordinary filesystem sense, but cache refresh and cache cleanup no longer
mutate or remove a live JVM's directory.

The archived issue therefore remains resolved. The historical causal sentence
should stay qualified, while the current dependency-class availability
guarantee is directly proved.

## Material read end to end

I read repository `AGENTS.md` whole before the investigation. I also read the
following incident records and owners whole before reaching this verdict:

- [archived class-loss issue](../../../seon/issues/archive/long-lived-jvm-loses-soft-referenced-dynamic-classes.md),
  including its initial version at `c56e74faf471e460d7973c516da45420c8064bd2`;
- [in-server test incident](in-server-tests-2026-08-03.md);
- [dynamic classloader lifetime investigation](dynamic-classloader-lifetime-2026-08-03.md);
- [soft-reference setting probe](soft-reference-retention-setting-2026-08-03.md);
- [dependency disk-cache audit](dynamic-class-disk-cache-audit-2026-08-03.md);
- [load-time and mutable-cache incident](load-time-2026-08-03.md);
- [source-load issue](../../../seon/issues/source-load-is-118s-against-the-ten-second-law.md);
- [clj-reload evaluation](clj-reload-evaluation-2026-08-03.md);
- current [`dev_cache.clj`](../../../../dev_cache.clj);
- current [`fresh_operator.clj`](../../../../script/seon/fresh_operator.clj);
  and
- the recurring [dependency-cache test](../../../../test/seon/dev/dependency_cache_test.clj).

The executable investigation is the committed
[writer class-loading discriminator](scripts/writer-class-loading-discriminants.clj).
All probe logic is in that file; the evidence below quotes only its printed
output.

## Dependency ledger

| Owner | Selected identity | Relevant boundary |
|---|---|---|
| JVM | OpenJDK `26.0.1` | Child input includes G1, `MaxRAMPercentage=12.5`, and `G1PeriodicGCInterval=30000`; the observed max heap was 17,179,869,184 bytes. |
| Clojure | `org.clojure/clojure` `1.12.5`; maintained source `b18d3adc5b5f4d5d0ccea966203fb67a614d5c3d` | `DynamicClassLoader` stores dynamic classes under soft references and has no retained byte array after clearing. |
| Datahike | maintained fork `c15272730e74` | `datahike.writer/create-database :self` owns the delayed nested `go` classes; `datahike.db.transaction/validate-val` owns the second incident family. |
| Seon tree exercised | `ace4a45f599db6e1c59eb0def976fc716eff7ddb` | Includes operator-consolidation commits `7dacba8ba` and `61cbb93ed`. |
| Cache publication | `dev_cache.clj:285-291,354-366,400-425` | Cache identity selects a digest directory; admission moves into that path without replacement. |
| Process retention | `script/seon/fresh_operator.clj:163-175,273-307,1659-1676` | Launch uses the exact cache path and records it beside generation, pid, and start instant. |
| Cache cleanup | `dev_cache.clj:458-539` | Exact process identity determines live references; selected and live paths are excluded from reaping. |

## Historical event ledger

### Mutable cache replacement is a recorded event

Commit `9bb559df953c63ac2aca85656f80fdb900de4075` introduced the dependency
cache as one mutable directory and placed that directory directly on the
`:dev` classpath. The then-current `admit!` deleted
`target/dev-dependency-classes` before moving the candidate into that same
path.

The contemporaneous
[load-time record](load-time-2026-08-03.md#live-cache-replacement-incident-and-correction)
states that refreshing this directory while the default JVM ran poisoned that
JVM's later class loads, initially through Malli and test.check. The issue as
filed at `c56e74f` says that earlier failure and the later writer failure were
in the same JVM. Therefore “no refresh involved” can only mean no refresh was
run during the immediate writer call; it cannot exclude the earlier directory
replacement from that process's lifetime.

Commit `ede681707a71bdb0adecb9ee17aa65b07502d6b0` recorded the cache refresh
at 12:54 on 2026-08-03. The writer incident report landed at 13:11. The
pre-replacement directory no longer survives, so the exact incident classfile
cannot now be inspected.

### A reload occurred, but its Datahike reach is unknown

The same load-time record says recovery from the earlier failure required
dependency-ordered source reloads. That proves a loader-generation event in the
same JVM. It does not name the namespaces reloaded, and no surviving evidence
shows that `datahike.writer` or `datahike.db.transaction` participated.

Ordinary source edits did not themselves reload application namespaces. The
edit hook published program facts, and the current source-publication reload
surface explicitly reloads first-party Seon owners rather than Datahike.

### No complete lifecycle record survives

The failed process's pid and start instant are not in the incident note. The
available chronology likely places the incident in the earlier default JVM,
but later healthy evidence refers to a restarted PID 3885. No raw operator
log, class-unload log, GC log, loader census, or exact timestamp for the second
`validate-val` miss survives. An additional unrecorded event therefore cannot
be excluded, only left unsupported.

## Discriminating experiments

### Missing classfile at later linkage: reproduced exactly

The probe copied the selected dependency cache into an isolated mutable
classpath directory, loaded `datahike.writer/create-database :self` through
`AppClassLoader`, then removed 13 delayed nested writer classes before the
first database creation. The child ran with the repository's exact JVM flags.

Printed output:

```clojure
#:writer-class-loading{:deleted-count 13, :deleted-sample ["writer$fn__56266$fn__56281$G__56268__56284.class" "writer$fn__56309$fn__56324$state_machine__5665__auto____56329.class" "writer$fn__56266$fn__56281.class"]}
#:writer-class-loading{:status :failed, :causes [#:writer-class-loading{:cause-class "java.lang.NoClassDefFoundError", :cause-message "datahike/writer$fn__56266$fn__56281"} #:writer-class-loading{:cause-class "java.lang.ClassNotFoundException", :cause-message "datahike.writer$fn__56266$fn__56281"}], :process-still-running? true}
```

This establishes the complete terminal mechanism: a loaded method remains
callable, its delayed nested class is requested later, the application loader
cannot find the removed resource, and the writer operation fails without
terminating the JVM. Generated suffixes differ by compiler generation, but the
owner and nested class shape match the incident.

### Changed source loader generation: different observed failure

The source-only child created a memory database and committed a four-datom
baseline. Reloading `datahike.writer` succeeded and replaced the retained
`:self` method with a class from a different `DynamicClassLoader`. A transaction
through the already-existing writer did not complete within the probe's loud
ten-second silence backstop; the transaction virtual thread remained alive.

Printed output:

```clojure
#:writer-class-loading{:baseline-datoms 4, :old-method #:writer-class-loading{:class "datahike.writer$eval58322$fn__58324", :loader "clojure.lang.DynamicClassLoader", :loader-identity 1398560418, :code-source nil}, :reload #:writer-class-loading{:status :reloaded}, :new-method #:writer-class-loading{:class "datahike.writer$eval73187$fn__73189", :loader "clojure.lang.DynamicClassLoader", :loader-identity 2050085914, :code-source nil}, :after #:writer-class-loading{:status :transaction-did-not-complete, :thread-alive? true}}
```

This proves in-place Datahike reload is unsafe for an existing writer, but it
does not reproduce the historical exception and no record proves that such a
reload touched Datahike in the incident JVM. It is a plausible contributing
lifecycle event, not the leading attribution.

### Current immutable-cache path: incident terminal state removed

The current probe created a new isolated operator root, published
`current-src`, started a real cluster to READY, and evaluated inside that
operator JVM. Both incident owners resolved through `AppClassLoader` from the
same exact digest directory. The probe then cleared all 3,628 entries from
`DynamicClassLoader/classCache` and committed schema plus data.

Printed output:

```clojure
#:writer-class-loading{:cache-path "/Users/sean/src/seon/target/dev-dependency-classes/6705854829ffcaff9d27dd0b68cc2815f0d317d4421d3f9fcc41f39fb154b0dd", :cache-before 3628, :cache-after 0, :method-class "datahike.writer$fn__56249", :method-loader "jdk.internal.loader.ClassLoaders$AppClassLoader", :validation-class "datahike.db.transaction$validate_val", :validation-loader "jdk.internal.loader.ClassLoaders$AppClassLoader", :tx-datoms 5}
```

Success with the dynamic cache still empty proves that neither incident owner
used the soft-reference bridge. Their reloadable bytes came from the exact
directory recorded in `-Dseon.dependency-cache.path`.

### Recurring live-process retention proof: green

`bin/test seon.dev.dependency-cache-test` exercised two distinct cache
publications while a constrained child delayed class loading until after
maximal soft-reference pressure.

Printed output:

```text
#:seon.dev-cache{:live-processes 1, :protected 2, :reaped []}
#:seon.dev-cache{:live-processes 0, :protected 1, :reaped [".../cache/98fe4bbdd4bdda374caeecfa5340d54986482c3435652db08e01f6cddd3ba62f"]}
Ran 1 tests containing 10 assertions.
0 failures, 0 errors.
```

The old directory remained byte-for-byte unchanged and supplied its delayed
classes while its exact process identity was live. The same owner removed it
only after child exit.

## Candidate ranking

### 1. Retain the current immutable dependency cache

**Recommendation: retain as the mechanism repair.** It gives dependency
classes a stable application-loader resource for the exact JVM lifetime and
preserves first-party source loading. Its costs are multiple digest directories
and the correctness obligation on process recording and reaping; the recurring
test directly owns both.

The old replace-in-place path should not be restored as a performance shortcut.
Cache selection may change, but a running JVM's classpath directory must not.

### 2. Capture loader and resource facts on any future linkage failure

**Recommendation: add diagnostics at the existing core-fault owner when this
failure family recurs.** Record the target and caller class names, defining
loaders and parent chains, code sources, selected cache path, whether the exact
class resource exists, and exact process identity before restart. Bound the
rendered face and retain the complete data once; seven duplicate stack traces
do not supply this attribution evidence.

This improves future attribution without adding a loader mechanism.

### 3. Treat dependency source reload as a process-generation boundary

**Recommendation: do not reload Datahike writer namespaces beneath live
connections.** If dependency source reload is ever required, stop and reopen
the isolated operator JVM so the connection, writer threads, Vars, classes,
and classpath all share one generation. The trade-off is a restart rather than
zero-downtime dependency editing; the probe shows that an in-place reload does
not preserve writer progress.

### 4. Change `SoftRefLRUPolicyMSPerMB`

**Reject as a remedy.** A larger value delays age-based clearing but heap
exhaustion still clears soft references, and the setting applies to every soft
cache in the process. The current incident owners no longer depend on that
cache, so the flag adds no correctness property.

### 5. Retain every dynamic loader or class forever

**Reject for this incident.** It would retain unbounded eval and reload
generations. The source-loaded writer targets already had strong roots, while
the dependency cache supplies the narrower missing resource guarantee.

## Proven, hypothesized, and unknown

### Proven

- The old mutable cache directory was replaced earlier in the same live JVM.
- A removed delayed writer classfile produces the incident's exact exception
  chain while the process survives.
- Generic soft-cache eviction is not sufficient for the strongly rooted
  current source-loaded Datahike targets.
- Reloading `datahike.writer` creates a new loader generation and can leave an
  existing writer transaction incomplete.
- The current operator loads both incident owners from one exact digest
  directory and transacts with the dynamic class cache empty.
- Current refresh and cleanup preserve the exact cache directory while its
  recorded `(pid, start-instant)` is live.

### Hypothesized

- The historical writer and `validate-val` misses were delayed AOT linkages
  into bytes removed by the earlier mutable cache replacement. This is the
  strongest explanation because the precursor event is recorded and the exact
  terminal mechanism reproduces.
- The dependency-ordered reload may have contributed mixed generations, but
  no evidence names Datahike in that reload.

### Unknown

- The failed target and caller loader identities and code sources.
- Whether each incident target was AOT-loaded, source-loaded, or reloaded.
- Whether an unrecorded lifecycle event occurred between the two misses.

## Tool and render feedback

The final discriminator output is compact, namespaced data and was sufficient
to compare the three hypotheses. Two existing output defects remain visible in
the historical material:

- the original incident rendered seven duplicate large stack traces; and
- the cache manifest is one extremely long EDN line when printed wholesale.

The current operator's phase output was clear and directly showed the isolated
cluster reaching READY. No new unclear result envelope was encountered.
`clj-kondo` does report a namespace/file-name mismatch for the deliberately
standalone hyphen-named probe script, even though direct script execution is
the supported invocation and completed successfully; that diagnostic is noisy
for this evidence-file shape.
