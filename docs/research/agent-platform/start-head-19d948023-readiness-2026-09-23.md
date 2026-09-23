---
type: diagnosis
date: 2026-09-23
status: verified cause; implementation frozen
---

# Failed move to 19d948023: publication rejects linked gitlink inputs

Boot acquires the REPL and store, compares published input digests, then publishes changed inputs before acquiring the cluster connection. The existing publication boundary canonicalizes paths; the smallest repair is to preserve declared gitlink identities at that boundary while retaining canonical containment for ordinary files.

## Cause and failed layer

**This was a source-publication error, not a readiness deadline.** `seon.cluster/refresh-source!` rejected `reference-code/babashka-process` and `reference-code/clojure` because their archive entries are symlinks outside the archive. Canonical relativization converts both valid logical gitlink inputs into paths starting with `..`. The pre-existing containment check rejects them before publication.

PID **90420**, start instant **2026-09-23T22:18:01.056Z**, reached the REPL and acquired the store, including its valid exclusive file lock. It failed during the source-commit/publication layer at **22:18:13.806Z**, before cluster branch/connection, config result, SCI context, Flow and web readiness. The retained instance contains the listener, executors and store; it contains no source commit or cluster connection. This is observed partial-boot evidence, not merely a prediction from source.

The outer `SEON CORE FAULT (panic): no live cluster database to record this failure in.` is a secondary error from `seon.cluster.boot/request-world`, which cannot find a cluster environment at this early layer. Its cause chain retains the original publication refusal. It is not evidence that store acquisition failed.

## Exact retained evidence

Operator capture: `tmp/orchestrator/start-head-19d948023-output.txt`. Its line 5 is one large EDN line; the following are exact excerpts, not a replacement serialization of that line.

| Line | Evidence |
|---|---|
| 1 | `REPL #:seon.boot{:pid 90420, :start-instant #inst "2026-09-23T22:18:01.056-00:00", :cluster-name "default", :prepl-host "127.0.0.1", :prepl-port 53740}` |
| 2 | `:status :miss, :reason :no-matching-cache` and `:selection-ms 32`; the fill command is offered as data |
| 5 | `:seon.cluster.boot/disposition :boot-failed` |
| 5 | `:seon.error/message "Changed paths lie outside the JVM's source directory."` |
| 5 | `:seon.boot/offense {:seon.fn/root "/Users/sean/src/seon/data/source/19d9480230315517d7301e500b2f4ddac387ff1e", :seon.source/changed-paths ["reference-code/babashka-process" "reference-code/clojure"]}` |
| 5 | `[seon.cluster$refresh_source_BANG_ invokeStatic "cluster.clj" 2846]` followed downstream in the trace by `[seon.cluster.boot$stand_boot_layers_BANG_ invokeStatic "boot.clj" 190]` |
| 5 | `:seon.error/at #inst "2026-09-23T22:18:13.806-00:00"` |
| 5 | `:seon.boot/ready-ms nil, :seon.operator/total-ms 15127, :seon.operator/launch-ms 12947, :seon.operator/adopt-ms 0` |
| 5 | rollback `:restored []`, `:unlinked []`, `:rollback :restored`; fallback `:ready? true` |
| 3–4 | fallback PID 90471 starts at `2026-09-23T22:18:14.324-00:00` on ce73846828 and also reports a dependency-cache miss |
| 5 | fallback readiness `:seon.boot/ready-ms 7552` |
| 6 | `bin/seon start --head 2>&1  35.24s user 3.25s system 106% cpu 35.999 total` |

The application log does **not** contain the publication exception as a standalone log entry for this attempt. Do not invent such a line or attribute older huge diagnostic lines containing matching numbers to PID 90420. The relevant exact lines of `data/clusters/default/logs/seon.log` are:

```text
58635:2026-09-23T22:18:13.636165Z :info datahike.query.estimate :datahike/estimate-heuristic-fallback Index lacks precomputed subtree counts (old database format). Using heuristic estimates for query planning. Consider re-indexing for optimal performance.
58638:2026-09-23T22:18:27.267820Z :info datahike.query.estimate :datahike/estimate-heuristic-fallback Index lacks precomputed subtree counts (old database format). Using heuristic estimates for query planning. Consider re-indexing for optimal performance.
58639:2026-09-23T22:18:34.132Z seans-m5-macbook-pro INFO [seon.cluster:3284] - seon default view: http://127.0.0.1:7994
```

Line 58635 is within the failed attempt; lines 58638–58639 are after the fallback launch. The heuristic message is informational. The terminal exception reaches the operator through its callback: `script/seon/operator.clj:351–362` catches and writes `Throwable->map`; `:560–575` receives that map. At `:1244–1245`, any error makes `ready?` false. Thus the generic “did not reach readiness” summary does not assert a timeout. The recorded terminal error establishes what happened here.

## Source and filesystem verification

All source line references below are to **19d9480230315517d7301e500b2f4ddac387ff1e**, inspected in its retained `data/source/<sha>/` archive; historical comparisons use Git objects, not another lane's dirty source.

1. `src/seon/cluster/boot.clj:135–159` compares discovered/published path digests. `:177–193` publishes the acquired store into the partial instance and calls `refresh-source!` with the changed paths. `:440–448` preserves that instance on failure. `:457–460` enumerates required readiness layers. `:531–541` explains the secondary no-cluster-database panic.
2. `src/seon/cluster/source.clj:106–125` already treats directory inputs as logical gitlink keys and reads their recorded pin digests, rather than walking their source. The path producer is valid.
3. `src/seon/cluster.clj:2844–2847` rejects any `fs/relative-path` starting with `..`; `:2854–2856` canonicalizes the inputs a second time when constructing publication roots. Fixing only the rejection would still forward wrong identities.
4. `src/seon/fs.clj:224–236`, `absolute-path` and `relative-path`, use `File.getCanonicalPath`/`getCanonicalFile`, following symbolic links.
5. Read-only `ls -ld` observed these archive entries:

   ```text
   reference-code/babashka-process -> /Users/sean/src/seon/reference-code/babashka-process
   reference-code/clojure -> /Users/sean/src/seon/reference-code/clojure
   target -> /Users/sean/src/seon/target
   ```

   A Python filesystem-only cross-check (`Path.resolve`, then `os.path.relpath`) returned `../../../reference-code/babashka-process` and `../../../reference-code/clojure`. This confirms the filesystem topology; it is not claimed as an executed Clojure probe. The operator capture line 5 independently records both submodules as `:placed :linked` with the new pins, so the diagnosis does not depend solely on today's symlinks.
6. `git blame ce73846828 -L 2842,2847 -- src/seon/cluster.clj` assigns the rejecting check to **ca95878172**, earlier on September 23. `git diff ce73846828 19d948023 -- src/seon/fs.clj src/seon/cluster.clj src/seon/cluster/boot.clj src/seon/cluster/source.clj script/seon/operator.clj` is empty. The relevant path, boot and operator mechanisms predate all three commits.

## Attribution to the three commits

**186957285 triggered a latent archive-publication defect by changing the two gitlink pins.** Those inputs now differ from the published program and therefore traverse the pre-existing bad path check. This is a causal trigger, but the verified defect is not upstream babashka-process execution or its AOT compile order. Rolling the pins back could avoid this trigger without repairing the defect.

**309a3a74e's bounded checkout pin check did not fail here.** `dev_cache.clj:424–439` runs it only when `.git` exists; the retained archive has no `.git`. Moreover, the startup selector does not call `ensure-cache`: `script/seon/operator.clj:470–475` merely returns a fill command, and `:482–484,525–530` adds dependency classes only on a cache hit. No cache fill error appears in the terminal cause. The fallback also boots with a miss. Cache misses may cost time, but are not this failure's cause.

**19d948023 is documentation only.** None of the three commits changes the failing path check. Clojure source-pin alignment supplies the second changed gitlink; the Clojure runtime remains the declared 1.12.5 JAR. No evidence here establishes a Clojure runtime regression.

The existing issue `docs/seon/issues/an-archive-booted-default-relativizes-hook-paths-outside-its-source.md` explains why genuine external paths must be refused. This failure exposes an overbroad application of that protection to declared linked dependency inputs. No issue or issue-index file was edited.

## Smallest fix and orchestrator proof

**Owner: `src/seon/cluster.clj`, `refresh-source!`, publication/B1 owner.** At the existing input-normalization boundary, preserve the logical relative identity of an exact declared gitlink from the archive's existing pin/input inventory. Use that same admitted identity for both containment checking and publication roots. Continue canonical containment checking for ordinary files and reject external paths/traversal. Do not globally weaken `seon.fs/relative-path`, ignore all `reference-code` paths, remove changed dependencies from publication, copy the archive/store, increase the readiness bound, or fill the cache as a fix. Reuse the existing pin reader/capture owner; add no registry. Work should be proportional to supplied paths plus the existing pin inventory, with no dependency-tree walk.

No JVM reproduction is necessary to identify this recorded cause. If the orchestrator wants the exact read-only Clojure seam probe, run the following from an authorized diagnostic JVM whose classpath contains the archived `seon.fs` and its dependencies (not executed by this lane):

```clojure
(require 'seon.fs)
(mapv #(seon.fs/relative-path
         "/Users/sean/src/seon/data/source/19d9480230315517d7301e500b2f4ddac387ff1e" %)
      ["reference-code/babashka-process" "reference-code/clojure"])
;; Expected before repair:
;; ["../../../reference-code/babashka-process" "../../../reference-code/clojure"]
```

Required proof after independent review and implementation:

1. In the orchestrator's isolated boot/publication fixture, publish an old pin population, resume the same store with a committed archive whose changed recorded gitlink is linked outside the archive, and exercise the real boot/publication path. Prove its logical pin identity/digest is published, the source layer completes, and readiness has no missing layers. An empty-store boot alone misses this changed-input path.
2. In the same behavior regression, prove a genuinely external ordinary source file and traversal still refuse; prove unchanged pins do not request publication. Include the existing cached-pin-directory link case as well as links to clean checkouts.
3. Run the affected installed request on an isolated branch and the orchestrator-owned platform proof. Then, only under orchestrator control, move default to the accepted committed repair, verify exact PID/start/source, publication → loaded code → armed contracts → adoption record, and inspect web readiness separately. Cache hit/miss remains an independent observation.
4. Record source/publication and total boot time, peak memory, and rollback/previous-process evidence if it fails. The historical failed launch was 12,947 ms and complete move/fallback 35.999 s; neither is a sub-second pass. This lane measured no new boot or heap usage and cannot promise that later layers will pass once this first refusal is removed.

Scope: report only; no implementation, cache fill, test execution, JVM launch, stop/reset/restart, publication or adoption. Source/test line delta: **0/0**. Shared dirty paths were preserved. The report is the sole released owned path.
