---
type: plan
status: implementation specification; eight destructive drills pending
created: 2026-09-21
tags: [agent-platform, lane-b1b, operator, boot, rewrite]
---

# B1b — one operator request, one boot sequence

Binding order: [README §4](README.md#4-ownership-and-order); reset ruling:
[README §7](README.md#7-decisions-and-proof-gates), commit `4f935b64e`.
This replaces B1 §2c and commit 11. Source citations below are repository-relative
at that commit; `S` = `src/seon/`, `O` = `script/seon/fresh_operator.clj`,
`T` = `src/seon/operator/state.clj`, `C` = `src/seon/cluster.clj`.
Tables prescribe the replacement; citations identify existing behavior or the seam
being replaced, not evidence that the replacement has run.

## 0. For the owner: guarantees found while reading

Write the operator anew. One JVM owns the physical store; reset launches its replacement
with a destructive option, and that replacement remains running. One store FileLock,
no cross-process handoff. The replacement gap is explicitly not exclusive.
The owner's subsequent instruction permits temporary boot, REPL and MCP breakage and
changes to the tools inside this slice. Restore them at the slice boundary; do not
retain an old operator or introduce a compatibility implementation to avoid that work.

| Found while reading, beyond the command/boot tables | Keep/drop and why | Evidence |
|---|---|---|
| REPL before store; above-REPL failure remains diagnosable | KEEP. Minimal Clojure server before loading the program; partial boot is visible, never ready | C:3276,3324,3388 |
| Advertisement includes cluster, host, bound port and exact process identity | KEEP all five fields; port alone cannot support discovery or safe termination | C:3340; `script/seon/dev/mcp.clj:104` |
| A concurrent loser could overwrite the winner's advertisement | PREVENT. Open REPL first, but publish its shared advertisement only after acquiring the store; losing lock acquisition closes its own server and exits | C:3340,3355; S`cluster/store.clj:306` |
| Parent knows the launched child before readiness; early failures retain output | KEEP Process/ProcessHandle and redirected log; wait on readiness OR actual exit, with a declared bound. Delete Python/process-record handshake, not child accounting | O:56,2129,2266 |
| Readiness wait is already event-driven | KEEP that property. The old wait is not merely file polling; do not replace it with a sleep loop | O:2266 |
| Exact target root, no symlink traversal, validate before destruction | KEEP explicit canonical root, admitted store path and no-follow recursive deletion; never delete the held lock inode | T:1558,1585; S`fs.clj:35,293` |
| Healthy sibling cluster survives named stop; stale instance cannot stop its replacement | KEEP instance-addressed stop, sibling store holdings and identity checks | C:3404,3504; O:653 |
| Failed database release retains store exclusion and diagnostic REPL | KEEP. A timeout/cancel is not proof the writer or proc exited | S`cluster/store.clj:518`; C:3556 |
| Existing branch is sovereign; refork is explicit and exact-commit based | KEEP; branch creation/reset remains registry work, no automatic ordinary-cluster adoption | S`cluster/registry.clj:269,304` |
| Recovery marks interrupted work; configuration and root seeding occur before agents start | KEEP existing owners, no replay of interrupted execution | C:2287,2504,3103 |
| Bootstrap supplies dependencies, test classpath and environment | KEEP one resolved launch classpath including tests; `.env` remains data and invoking environment wins | O:472,491,496,563,2129 |
| Collection verifies retained roots; log rotation preserves the live inode; disk observation is bounded by its requested scope | KEEP surviving maintenance in its existing domain owner, including dry-run refusal semantics; do not delete it with operator plumbing | S`operator.clj:515,796,1112`; T:1457; S`schedule.clj:44` |
| Claims, cross-root reaping/census, generation UUID, lifecycle/holder files and phase recovery logs | DROP with their scheduled producers, schema readers and tests. Process identity, live observations and explicit scratch cleanup replace the needed operations; no automatic cross-root reaper is recreated | T:413,646,801,1199,1320; O:3008 |
| Offline roster/test-reader JVMs; reset continuation from logs | DROP. Status states unavailable without a live answer; reset starts afresh, with destructive intent explicit | O:881,986,3555 |
| CLI source preflight and digest rechecks | DROP duplicate analysis; validate argv/path/config shape before down, canonical publication still refuses bad source. A later source failure may follow deletion and must say so | O:330; C:2164 |
| Raw logs and `issues-index` work without a JVM | KEEP local file/argv operations; neither should require a healthy system to diagnose it | O:131; `bin/seon:17` |
| MCP source independence and test-check's one request | KEEP behavior; replace imports and discovery implementation that depend on deleted files, as explicitly authorized by the owner | `script/seon/dev/mcp.clj:93,201`; `bin/test-check:3,13` |

No guarantee is inferred from old line count or old green tests. The eight replacement
drills are pending, not executed during this documentation assignment.

## 1. Goal, size and ownership

| New/reworked file | Measured target, not a ceiling | Responsibility |
|---|---:|---|
| `bin/seon` | ~80 | argv, canonical root, validation, help; invoke the one client |
| `script/seon/operator.clj` | ~400 | `seon.operator`: launch, exact-process down/reset, advertisement discovery, prepl transport, terminal result |
| `src/seon/cluster/boot.clj` | ~420 | minimal REPL-first entry, request functions, explicit layer sequence, readiness and reverse release |
| Existing owners and tools | measure separately | store's destructive option; maintenance/filesystem moves; MCP/test-check/hook callers and contracts |

The ≈900 measured target covers the three replacement files, not tests or behavior
moved to existing owners. The landing note reports actual `wc -l` per new file and
explains any overrun; the right design wins over the count (README §7, `1cff03d6a`).
Record net additions, deletions and moves separately; charge moves where they land.
Old measured files: O 3,727; S`operator.clj` 1,219; T 1,627.
The boot deletion must include `stand-cluster-runtime!` at C:3103 and the end of
`stop!` at C:3566; the brief's 3187–3560 range cuts both functions incorrectly.
Retain unrelated publication, agent/turn, rendering and recovery implementations.

Owned implementation paths include the three files above, their replaced files,
S`cluster/store.clj`, S`cluster/process.clj`, `resources/seon/operator/runtime.clj`, S`fs.clj`,
S`maintenance.clj`, S`schedule.clj`, relevant schema resources and exact callers,
`script/seon/dev/mcp.clj`, `bin/test-check`, `bin/seon-hook` and operator tests.
The holder stays at `resources/seon/operator/runtime.clj:1`, outside indexed source,
so process-root holdings survive program reload (C:806).
Coordinate these paths with A2/B3/B4 before the cut; no changes to another lane's
uncommitted work. This document authorizes no implementation or process operation.

## 2. Requests, boot and exclusion

### 2a. Exact command data

These are target maps, not today's API. `R` is the canonical process root, `N` a
validated cluster name, `I` exactly `{:seon.boot/pid p :seon.boot/start-instant t}`.
Every JVM command map below is merged with `{:seon.operator/managed-root R}` and
sent as one `(seon.cluster.boot/request! <map>)` form. Connected requests also carry
`:seon.boot/pid p :seon.boot/start-instant t`; the receiver checks them against itself
before effects. `P` is the vector of changed paths, `M` the parsed manifest, `U` the
served URL, `K` the exact source commit, `B` the branch keyword, `V` a live status
vector. Optional fields are omitted, never nil. No argv becomes executable source.

Success maps are exactly the column below (plus explicitly stated optional fields).
Each refusal is the shared flat diagnostic constructor's map, not a second envelope:
`{:seon.error/at now :seon.error/layer :seon.operator/operation
 :seon.error/operation 'seon.cluster.boot/request! :seon.error/message message
 :seon.error/diagnostic-layer layer :seon.error/diagnostic-operation operation
 :seon.error/diagnostic-member member :seon.error/diagnostic-expected expected
 :seon.error/diagnostic-offending offending :seon.error/diagnostic-cause cause
 :seon.error/diagnostic-evidence evidence}`.
Preserve underlying refusal evidence; maps have declared open Malli contracts.
`E(x)` below names the diagnostic cause, not a separate error taxonomy.

| CLI | Request map / transport | Success reply map | No live JVM / command-specific refusal | Current seam |
|---|---|---|---|---|
| `start [N] [--config path]` | `{:seon.operator/command :start :seon.boot/cluster-name N}`; optional `:seon.config/manifest M`; cold launch carries same data | merge I `{:seon.boot/cluster-name N :seon.boot/readiness readiness}` | cold launch allowed; duplicate, boot incomplete, store locked | O:608,2413; C:3276 |
| `init [--changed P…]` | `{:seon.operator/command :init}`; optional `:seon.source/changed-paths P` | `{:seon.source/commit-id K}` | E(no JVM), publication refusal | C:2164; O:694 |
| `init N [--force]` | `{:seon.operator/command :init :seon.boot/cluster-name N}`; optional `:seon.operator/force? true` | `{:seon.boot/cluster-name N :seon.store/branch B :seon.source/commit-id K :seon.cluster/created? created?}` | E(no JVM); existing branch without force; branch still connected; missing source | S`cluster/registry.clj:269,304`; O:694 |
| `init --dev N [--changed P…]` | `{:seon.operator/command :init :seon.operator/development-cluster N}`; optional changed-paths | `{:seon.boot/cluster-name N :seon.source/commit-id K}` only after adoption completes | E(no JVM), absent cluster, publication/reload/arming refusal; previous adopted commit retained | C:2027,2164 |
| `status [--verbose]` | `{:seon.operator/command :status}`; optional `:seon.operator/verbose? true` | merge I `{:seon.operator/clusters V}`; verbose adds `:seon.operator/footprint footprint` | local E(no JVM/unresponsive); no offline reader launched | S`operator.clj:180`; T:1457 |
| `open [N]` | `{:seon.operator/command :open :seon.boot/cluster-name N}` | `{:seon.boot/cluster-name N :seon.render.web/url U}`; CLI opens U | E(no JVM/absent cluster/web unavailable); OS opener failure reported | C:2524,3422 |
| `stop [N]` | `{:seon.operator/command :stop :seon.boot/cluster-name N}` | `{:seon.boot/cluster-name N :seon.operator/stopped? true}` | E(no JVM/ambiguous target/release failed); absent named instance is explicit absence | C:3504; O:653 |
| `down [--force]` | healthy: `{:seon.operator/command :down}`; otherwise no prepl required, operate on captured I | `{:seon.operator/stopped-processes [I…]}`; empty vector when positively no selected process exists | identity unavailable/mismatch or actual exit missing; never select a replacement after capturing I | T:271; O:3436 |
| `reset --force` | CLI captures old I, downs it, launches `{:seon.operator/command :start :seon.boot/cluster-name "default" :seon.store/destroy? true}` | merge new I `{:seon.boot/cluster-name "default" :seon.boot/readiness readiness :seon.source/commit-id K}` | no JVM needed; invalid force/root/config, exact down failed, lock refused, delete/publication/boot failed | S`cluster/store.clj:421`; README §7 |
| `logs [N]` | local `{:seon.operator/command :logs :seon.boot/cluster-name N}`; no prepl | `{:seon.boot/log-dir L :seon.operator.log/path F}`; CLI streams that file | no JVM needed; missing/unreadable log, not fictitious empty success | O:131; S`operator.clj:515` |
| `config apply [N] path` | `{:seon.operator/command :config-apply :seon.boot/cluster-name N :seon.config/manifest M}` | `{:seon.boot/cluster-name N :seon.reconcile/result result}` from actual writer | E(no JVM), invalid manifest, absent cluster, writer refusal | S`config.clj:842` |

Default N is `default` for start/config/logs; bare stop/open selects the sole live
cluster or refuses ambiguity. `stop --force N` may terminate its hosting process
only when no sibling would be killed; otherwise name siblings and require `down`.
`down --force` skips the graceful request, never identity verification. Positive OS
absence makes stale advertisements non-authoritative; unavailable OS identity is a
refusal. Local transport errors use the same diagnostic shape with the client operation.
CLI returns nonzero on refusal. `:ret` with `:exception`, EOF, malformed result and
timeout are failures, not success. Readiness includes each missing layer explicitly;
V preserves unknown Flow observations (C:3422; S`operator.clj:180`; O:1166,1820).
No response means outcome unknown, not permission to retry a destructive command.
Child exit, socket silence and boot readiness retain their separately declared bounds
(O:81,2266; T:271); publication retains B1's phase bounds. No numeric fallback or
unbounded wait is added. `logs` prints the requested finite contents; following a
live stream is not part of this command contract.

### 2b. Boot layers and release

Boot is an explicit sequence of values, not a generic layer framework. Publish only
successfully acquired values into the existing instance holder; readiness derives from
them. The REPL opens before store acquisition; "lock first" means before store access.
Startup failures after acquisition retain the partial instance and REPL for diagnosis.
The child sends its early REPL coordinates directly to its launching parent before
shared advertisement publication; append logs, never truncate a competing child's log.
Lock refusal is the deliberate exception: the losing child releases its own listener
and exits without touching the winner's files. C:3324,3388; S`cluster/store.clj:421`.

| Layer | Input → published value | Readiness fact / refusal | What stop releases | Existing seam |
|---|---|---|---|---|
| Process / REPL | closed bootstrap paths/bind/log/bounds → I, `:seon.boot/prepl-server`, actual port | bound socket; identity/bind failure | server last; launcher retains child handle until ready or failed | C:3324; S`cluster/process.clj:13`; core server:85 |
| Store | store path, optional destroy → `:seon.store/store` | held FileLock and readable main connection; held/unknown owner, deletion/open refusal | main connection then lock, once last owner releases | S`cluster/store.clj:421,518` |
| Advertisement | acquired store + listener + I → `:seon.boot/advertisement` | atomic EDN replacement, all five fields; write failure | remove only the exact owned advertisement, never another process's | C:929,3340,3539 |
| Source | held store → exact `:seon.source/commit-id` | published commit exists; publication refusal | release borrowed materialized DB values; do not retract publication | C:2164; S`cluster/source.clj:139` |
| Branch | store + N + selected commit → `:seon.store/branch` | registry operation completed; missing source/connected branch refusal | branch remains durable | S`cluster/registry.clj:269,304` |
| Connection | store + branch → `:seon.boot/cluster-connection` | readable db; branch/open refusal | branch connection after dependent work has exited | S`cluster/store.clj:538` |
| Facts / projection | database, compiled manifest → config result, recovery result, carried projection | config and recovery writes succeeded; explicit projection absent/refused | immutable values; no mirror or registration delta | C:3103; S`config.clj:787`; A1-3 |
| Context / environment | connection + projection + effective config + executors → `:seon.sci.eval/ctx`, environment | armed context acquired once for this boot; acquisition refusal | context/private objects are in-memory only | C:3128,3151; B2 acquisition seam |
| Graphs | environment and existing graph definitions → work launcher, shared/agent graph handles | actual owner readiness/completions; missing proc response is unknown | disarm producers, await actual completion, stop plumbing before DB release | C:2862,3014,3156 |
| Web | graph/render values + config → `:seon.render.web/served` | actual bound URL, explicitly reported fallback; bind refusal | close web/SSE/render resources before their dependencies | C:2524,3185 |

Existing branches use their own recorded source; absent branches fork the selected
published commit. Cold empty-store start/reset calls the existing publisher, then
forks; it does not invent a second indexer. Publication/adoption receives the same
held store through the existing root holder (C:947), never opens it a second time.
Root executors remain shared, released only at process shutdown (C:802,3504).
Remove coherence/accretion/search layers with their B1/A1/B3 replacements; do not
pull later turn/context redesign into this plumbing cut (README §4).

### 2c. One lock and exact process identity

`open-store!` gains optional `:seon.store/destroy? true`: canonical path → acquire
existing sibling FileLock → admit deletion → delete only store → create/open.
Do not call `open-store!` and then delete its live connection. Do not unlink the lock,
open a second descriptor to probe one's own lock, or release between publication
and boot (S`cluster/store.clj:300,421,518`). Reset does not wipe the checkout,
logs, current listener advertisement or arbitrary siblings under `data/`.
Old advertisement files are harmless when their exact process is absent.

The client captures the old process once, verifies `(pid,start-instant)` before
each signal, awaits `ProcessHandle.onExit` under the declared exit bound, then
launches one replacement. It never reselects a new winner to kill after a race.
The replacement's lock refusal reports store/lock paths and independently verified
advertised identity when available; otherwise "store locked; holder unknown".
An advertisement proves process identity, not kernel lock ownership: do not label
an unrelated live advertised process the holder. T:271; S`cluster/process.clj:13,47`.
Keep the canonical `-Dseon.operator.root` process argument for exact-root discovery
when an advertisement is unavailable (T:1199). Never select by command substring.

The store lock excludes other processes through delete → publish → boot, not across
old-process exit → new lock acquisition. There is no lifecycle lock, holder file,
claim, phase log, generation UUID, retry queue or truth/repair pass. A failed reset
is retried deliberately, never continued from a log (README §7).
Within the JVM, prepl connections run concurrently: core server:115 starts one
thread per connection. Retain existing cluster reservation, source-refresh monitor
and Datahike writer/roster serialization; "one prepl request" is not a mutex.
These are resource-owner operations, not another cross-process lifecycle lock
(C:818,1571; Datahike `versioning.cljc:212`; S`cluster/registry.clj:178`).

## 3. Reading list and seams that are not rewritten

Read end to end: the brief; README §4/§7; B1 §2c/commit 11; O; S`operator.clj`;
T; C:3103–3566; `bin/seon`; `bin/test-check`; S`cluster/store.clj:300–360,421–538`;
S`cluster/registry.clj`; S`cluster/process.clj`; MCP:93–114 (also discovery:201).
Dependency ledger: Clojure core server:85,115,228,275 (socket, per-client threads,
terminal events); Datahike `reference-code/datahike/src/datahike/versioning.cljc:212`
(branch/commit source, existing roster synchronization); JDK
[ProcessHandle](https://docs.oracle.com/en/java/javase/26/docs/api/java.base/java/lang/ProcessHandle.html)
(`info`, `startInstant`, `onExit`, `destroy`),
[FileLock](https://docs.oracle.com/en/java/javase/26/docs/api/java.base/java/nio/channels/FileLock.html)
(VM lifetime, channel close, no holder-PID API).
Keep `refresh-source!`/development adoption (C:2027,2164), registry branch semantics,
store connection/release semantics except the ruled destructive admission, and MCP
evaluation semantics. Tools may change to call the new owners; no second transport.

## 4. REPL protocol and evidence boundary

Documentation review ran one successful read-only JVM probe on `default` before the
instruction to document drill 8 only; no reset or competing process was run:

```clojure
(let [identity (seon.cluster.process/current-identity)
      instance (get @seon.operator.runtime/running-instances "default")
      store (:seon.store/store instance)
      lock (:seon.store/lock store)]
  {:seon.probe/process identity
   :seon.probe/lock-path (:seon.store/lock-file store)
   :seon.probe/valid? (.isValid ^java.nio.channels.FileLock lock)
   :seon.probe/channel-open? (.isOpen (.channel ^java.nio.channels.FileLock lock))
   :seon.probe/identity-matches? (seon.cluster.process/live? identity)
   :seon.probe/wrong-start-matches?
   (seon.cluster.process/live?
     (assoc identity :seon.boot/start-instant (java.util.Date. 0)))})
```

Exact submitted form above. Recorded values: pid 25658,
start `2026-09-21T20:47:17Z`, `/Users/sean/src/seon/data/store.lock`, true/true/true/false,
11 ms. An earlier private-Var lookup failed to resolve `held-flocks`; it proved
nothing about exclusion. `.isValid` alone does not prove foreign exclusion; drill 8
does. Retain exact future forms and complete envelopes in the landing note.
At implementation use real `seon.test/run` with explicit cluster/custody and declared
bound (S`test.clj:541`); do not use a raw clojure.test runner or mock store/SCI.

## 5. Ordered implementation slice and recovery

| Order inside ONE rewrite slice | Conversion / deletion |
|---|---|
| 1 | Write the three replacement files and store option; declare contracts. Reuse publication, registry, config, process and resource owners. Temporary broken tools/boot are permitted by owner. |
| 2 | Move JVM connection/status/refork entry points into boot; convert every direct and symbol-valued caller. Put surviving maintenance in S`maintenance.clj`, filesystem measurements in S`fs.clj`; update S`schedule.clj:44` and its schema/seed consumers. Retire claim/reap/census schedules with their mechanism. |
| 3 | Convert MCP discovery/imports, `bin/test-check`, hook and other tooling callers to the new client/boot owners. Keep script-only launcher code out of the indexed runtime program; no duplicate `seon.operator` namespace on the classpath. |
| 4 | Delete O, T, S`operator.clj` and replaced boot functions in C. Delete `test/seon/dev/fresh_operator_test.clj`, `fresh_operator_reset_test.clj`, `test/seon/operator_test.clj`; retain their surviving maintenance assertions under their new owner. Replace operator cases in `test/seon/cluster/boot_test.clj` with §7. |
| 5 | Restore loadable HEAD and working tools; run the named drills and cut-level platform checkpoint. Orchestrator replaces default once and observes JVM/SCI evaluation, DB access and web independently. No full suite per edit. |

`export` survives only until B4 removes its base-store callers: delegate the still-used
operation in the new client, then delete it with those callers in cut 2. It is not a
second operator implementation (B1 commit 14; README §4). Convert store's references
to T (`store.clj:25,151,409`) and all boot callers in the deletion slice. Mechanical
call conversion is required scope; unrelated publication/registry redesign is not.
Recover with `git revert <rewrite-commit>`, then the reverted `bin/seon reset --force`.
Revert restores code, not discarded data. No lane resets default or pushes this slice.

## 6. Better than the floor

No extra supervisor, lock handoff or reset-continuation mechanism. The winning reset
JVM is the final JVM. No boot-time directory census, no per-command classpath rebuild,
no index per cluster; charge cold indexing to publication and fork to registry.
Measure launch, indexing, branch, context and readiness separately; no claim that
900 lines or fewer JVMs proves speed (O:563; C:3187; B1 §2b).

## 7. Eight drills — specified, NOT run in this review

Each row is a named `deftest` in `test/seon/cluster/boot_test.clj`, admitted/recorded
through `seon.test/run`, with a scratch root below project `tmp/`, real file store,
canonical program/contracts and child ProcessHandle. No fixed sleeps: explicit
test-side latches/child events with declared bounds. Always reap owned children and
remove owned roots in finally; plant an external symlink sentinel. Test controls
stay in fixtures, not a production pause API (S`fs.clj:293`; testing skill).

| # / deftest | Scenario and assertions |
|---|---|
| 1 `cold-start` | Empty root, real CLI; prepl answers before store, then publication/branch/context/graphs/web stand. Launcher exits and JVM remains usable. Read one fact, evaluate JVM and SCI, verify exact advertised identity and real URL. Inject a later-layer failure: partial boot is explicit and REPL remains usable. |
| 2 `concurrent-start` | Two cold launches for one root held at lock acquisition; one store owner. Loser refuses before connect/delete and cannot overwrite/remove winner's advertisement. Verified identity or explicit holder unknown, never invented PID. |
| 3 `stop-instance` | Named stop awaits work completion and releases only its resources; sibling remains usable. A delayed old-instance stop leaves replacement alive. Failed DB release retains lock and diagnostic access. |
| 4 `down-unresponsive` | SIGSTOP the owned scratch JVM; down succeeds by exact identity plus actual exit, without a REPL reply. Wrong start-instant cannot kill a live process. Unknown identity refuses. |
| 5 `reset-one-jvm` | Seed distinguishable data, reset; replacement deletes before connecting, republishes/forks/boots and stays. Old data absent, new program usable, lock file retained, symlink target intact. Invalid force/root/config causes no destruction. |
| 6 `start-during-reset` | Run the winner IN the test JVM: call `open-store!` with `:seon.store/destroy? true` on the scratch store and hold its real sequence after lock acquisition, before deletion, under fixture control. Keep the winning boot in that JVM for the before-ready interval too. In each interval launch the competing cold start as a real child; it refuses, then release the fixture hold and let the winner complete. No child pause or production pause API. |
| 7 `reset-loses-replacement-race` | Reset captures/stops old I; another start acquires before reset replacement. Reset child refuses, never deletes and never kills/reselects winner. This is the precise reset-during-start race; no claim that explicit force-reset can never stop an already selected booting process. |
| 8 `same-lock-through-reset-boot` | Two actual processes compete; put a sentinel at deletion entry, observe loser never enters it. Winner retains identical FileLock/channel from destructive admission through ready. Kill winner, await its actual exit, prove a fresh process acquires the same retained lock file. Checking `.isValid` alone is insufficient. |

Only cold-from-zero boot belongs to the platform tier; process/concurrency drills
remain named destructive tests, with extra processes strictly as their subjects.
No agent/provider calls. Declare cold boot/index allowances with measured reasons;
ordinary operations retain their declared bounds. Missing child event, cleanup or
recorded result fails the test. Pending drills are not a green baseline.

## 8. Done, landing and stop

Done: old mechanisms and their consumers are gone, HEAD loads, all eight drills have
recorded outcomes, MCP JVM/SCI/status reconnect to the replacement, `test-check`
works, and the orchestrator's cut checkpoint plus one default boot are observed.
Record actual file counts, caller moves, exact probe forms/results and failed proofs
in `docs/prds/agent-platform/landing/lane-b1b.md`; this spec stays integrated, not a log.
Stop the dependent implementation at unsafe deletion, unproved exclusion, missing
termination evidence, an unconverted caller or a new owner-level guarantee decision.
Temporary unavailable tools inside the rewrite are not a stop condition; failure to
restore them at the exit is. No implementation/drill/pass is claimed by this document.
