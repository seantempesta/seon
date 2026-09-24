---
type: audit
status: proposed
created: 2026-09-24
scope: Every lock and coordination mechanism in src, script, resources and bin, each with a keep, replace or delete verdict.
---
# Lock audit — every coordination mechanism (2026-09-24)

Lane audit-locks, read-only. Owner 2026-09-24: locks are a smell; for program change,
"just detect and push back if there's a conflict". Scope: `src/`, `script/`, `resources/`,
`bin/seon-hook`, and test helpers that production relies on. Evidence is source reading
at HEAD 523ece370 only: no JVM, no MCP probe, and **no contention was measured**. The
"how often" column is inferred from call sites, not observed.

Model seams used for REPLACE verdicts:
- the Datahike writer serializes transactions (`:writer {:backend :self}`, store.clj:182);
- Datahike's own connection registry reserves with a plain atom, not a monitor
  (`reference-code/datahike/src/datahike/connections.cljc:37` `reserve-connection-opening!`,
  :94 complete, :113 fail; fork rev 4157916b). That is the pattern to copy: reserve with
  `swap-vals!`, do IO outside, then publish or remove;
- detect-and-refuse by comparing identities (commit id, `identical?` token);
- a delay inside a core.cache or atom entry for memoized work (already used in
  sci/eval.clj:2732 `record-refusals-once!`).

## Ranked by harm (contention × deadlock risk × hidden order)

| # | Site | Protects / contenders / frequency | Verdict |
|---|------|-----------------------------------|---------|
| 1 | cluster/agent.clj:872 `(locking contexts …)` in `acquire-context!` | The cluster's context-state atom. The monitor is held across `registry/branch!` (a Datahike transaction plus branch creation), `store/open-branch!`, `sci.eval/acquire!` (context fork plus program install; seconds when cold) and `db/transact!`. Contenders: every turn-start reacquisition, `arm!`, isolated forks and test members, on every turn. It nests inside the routing monitor (#2) and contains `branch-open-monitor` (#8). | **REPLACE.** Reserve the `[branch agent]` key with `swap-vals!`, storing a delay of the acquisition. Same-key callers deref the one delay; other keys run in parallel. `registry/branch!` already detects a lost race (`:seon.cluster/created?` false means refuse). A failed delay removes its own entry (compare with `identical?`). About +8/−4 lines. |
| 2 | cluster/agent.clj:787 `stop-owned-graphs!`, :1018 `arm!`, :1236 `disarm!`: `(locking routing …)` | One execution handle's routing atom (armed graphs, channels, released?). `disarm!` holds the monitor through `flow/stop` and `join-graph!`, which can take up to `turn-completion-backstop-ms`. `arm!` holds it through `acquire-context!` (#1) and graph start. Every arm and disarm on the handle serializes behind the slowest join. A transform that arms while its own graph is being disarmed blocks until the join deadline. The order routing → contexts → branch-open is hidden. | **REPLACE.** Keep per-agent lifecycle state in the routing atom. Claim `::arming` or `::disarming` with `swap-vals!` using an `identical?` token, and run Flow start, stop and join outside any monitor. A concurrent claim refuses by name ("detect and push back"). `:seon.agent/released?` is already a value in that atom. Net about 0 lines. Also correct the docstring at sci/eval.clj:1941, which says acquisition runs "under the arming monitor". |
| 3 | cluster.clj:2196 `program-change` (a fair ReentrantReadWriteLock), plus :2215 predicate, :2225 `*member-lock*`, :2231 `call-excluding-adoption`, :2252 `call-with-program-change` (tryLock at :2271/:2275); test.clj:123–127, :141, :1561; test/seon/test/one_request_test.clj:150–157 (CountDownLatch gate plus the RRWL) | Loaded Var roots. Member bodies hold the read side for the whole run. Adoption takes the write side, JVM-wide, waiting up to the longest declared member bound. Because the lock is fair, a queued adoption also holds back every later member in the JVM. | **Already replaced by lane detect-program-change** (da9910fec, 2bd861329; S3a d77a9461a, S3b 389d2d265, S3c 548d7b6f4 recorded the loaded commit). It compares the loaded program identity at member start and end and reads a mismatch as unknown. `program-validity` (:2201) is a value, not a lock; that lane decides whether it survives. About −70 lines. |
| 4 | fs/jvm.clj:32 and :677 `write-serialization` (one JVM-global Object) | Every `my.fs` write from every agent in every cluster serializes, including content staging (up to `max-write-bytes`) and `FileChannel.force`. It does not protect against external writers such as editors or other lanes. The real detection is the precondition digest (`:my.fs/stale-digest-error`). | **REPLACE (narrow).** Stage and fsync outside the monitor, and hold it only for precondition check → atomic move. The option the ruling allows is to delete it: the in-JVM check-then-move race is then the same one external writers already have. About ±3 lines. |
| 5 | test/runner.clj:885 `(locking holder …)` plus `alter-meta! holder assoc ::reach-index` | The reach index cached per database `:config` (holder atom at runner.clj:860). Concurrent test requests queue behind a `reach-refresh`, which is whole-program in the worst case. The index is kept as a **stamp in atom metadata** while the atom's value stays nil, which breaks the law "Derive without stamps". | **REPLACE.** Make the index the atom's value. Derive outside the monitor and `swap!` keeping the newer basis; a duplicate derivation under a race is acceptable. Alternatively, store a delay keyed by `value-identity` in the projection cache. About −3 lines. |
| 6 | cluster/boot.clj:405, :472, :576 `(locking running-instances …)`, the atom in resources/seon/operator/runtime.clj:11 | The per-JVM table of cluster instances, touched on start, stop and force-stop (rare). `locking` an atom does not exclude `swap!` writers: `publish!` at boot.clj:413 swaps without the monitor. Mutual exclusion holds only because the CAS writer `reserve-cluster!` (cluster.clj:824–842, CAS at :831) has no caller. Force-stop holds the monitor and then halts; halt runs no hooks, so the monitor adds nothing to a plain read. | **REPLACE** the start reservation and the stop claim with `swap-vals!` plus an `identical?` compare (about ±0). Force-stop becomes a plain deref. **DELETE** the dead `starting`, `reserve-cluster!` and `release-reservation!` (cluster.clj:818–842, about −20). |
| 7 | cluster.clj:970, :1000 `(locking root-store-holder …)` and cluster/store.clj:317, :346 `(locking held-flocks …)` | Two per-process tables for one fact, "this process's store holdings", both keyed by canonical path. Acquire holds the monitor across `store/open-store!` (Datahike connect). maintenance.clj:518 `acquire-operation-store!` reads the holder **outside** the monitor and opens a store that bypasses the refcount, a race the monitor does not cover. Contenders: boot, reset, maintenance and export (rare). | **KEEP** the flock and the rule to reserve before opening a second descriptor (a genuine OS constraint: fcntl close drops every process lock, store.clj:294–305). **REPLACE** both monitors with `swap-vals!` reservation followed by publish or remove, as Datahike does. Merge the two tables into one entry (flock, store, holders), and route maintenance through the refcount. About −15. |
| 8 | cluster/store.clj:538, :551 `branch-open-monitor` (JVM-global) | Check-then-`d/connect` of a branch, refusing `::branch-already-open`. Branch opens are rare, but the monitor nests inside #1. | **REPLACE** with detection: connect, then read the registry entry's `:count` (connections.cljc:94–111). If it is above 1, release and refuse by name. About −2. |
| 9 | cluster/agent.clj:546, :550, :1126: `locking` an AtomicReference turn-thread holder | Publishing and clearing the thread that runs a turn transform, against the `Thread.interrupt` from stop. Flow runs transforms on the proc loop thread (core.async flow/impl.clj:305). A late interrupt would reach Flow's `alts!!` and eat `::flow/stop`. Two parties, each section 2–3 statements, taken on every turn. | **KEEP.** `Thread.interrupt` has no conditional form, so the set / interrupt / clear-and-absorb steps must exclude each other. |
| 10 | script/seon/dev/state.clj:58 `(locking held-locks …)` around the check-and-conj; :63–68 `FileChannel.tryLock` | Tooling transitions (`run-changed!`, changed_test.clj:320) across processes. | **KEEP** the file lock (cross-process). **REPLACE** the monitor with `swap-vals!` (1 line). |
| 11 | script/seon/operator.clj:510–520 blocking `.lock` on `dev-dependency-cache-references.lock` | Ordering between the cache-reference record and `dev-cache/reap` deletion, across launcher processes. | **KEEP** (cross-process). |
| 12 | bin/seon-hook:82 (hook log), :1239 (pending-review state) file locks | Several Claude sessions run the hook concurrently. | **KEEP** (cross-process). |
| 13 | maintenance.clj:491–492 `locking System/out` / `System/err` | Copy-and-truncate of the live log inode against PrintStream writes. | **KEEP.** PrintStream synchronizes on itself, so this is the Java contract. |
| 14 | resources/seon/operator/prepl.clj:69 and cluster.clj:564 `(locking lock (prn …))` | Framing prepl output lines. | **KEEP.** It matches upstream `io-prepl` (reference-code/clojure/src/clj/clojure/core/server.clj:289). See finding F1. |
| 15 | script/seon/dev/mcp.clj:973 `stdout-lock` | JSON-RPC line framing on stdout from several threads. | **KEEP** (one stream, whole lines). |
| 16 | sci/eval.clj:3430, :3459 `(locking printed-text …)` | One evaluation's StringBuilder and byte bound, when the evaluation's futures print. Uncontended. | **KEEP.** StringBuilder is not thread-safe; this follows java.io.Writer's own lock idiom. |
| 17 | profile.clj:52, :136, :187 synchronizedSet over a WeakHashMap | Profile cells: written at installation, iterated by `cells` and `begin`. | **KEEP.** The `Collections.synchronizedSet` javadoc requires manual synchronization while iterating. |
| 18 | flow.clj:373–374, :406 `compare-and-set! status`; RefusingBuffer AtomicLong (flow.clj:~361) | The exactly-once settlement state machine (queued → running → completed) and the admission bound. | **KEEP.** CAS on values with no critical section; this is the Clojure seam. |
| 19 | turn.clj:5456, :5600 `compare-and-set! backstop-state backstop nil` | Clearing only this turn's own backstop. | **KEEP** (identity compare). |
| 20 | flow.clj:560 `drained` promise; shell/jvm.clj:89; await.clj:20; operator.clj:380 and artifact.clj:109 `@(promise)` | Completion signals and park-forever in the main thread. | **KEEP.** These are not serialization. |
| 21 | render/web.clj:2884 `painting` volatile | SSE writer cancellation, set by http-kit on-close. | **KEEP.** A visibility flag, not a mutex. |
| 22 | sci/eval.clj:2732 delay in `refusal-recording-cache`; kernel.clj:96 `process-guard` delay; boot.clj:391 `uncaught-handler` delay; runtime.clj:17 executor delay | Memoized, once-only construction. | **KEEP** (the memo seam). |
| 23 | turn.clj:2853, :2878, :4205 "busy fence" | Derived from committed facts (an open turn outranks a trigger). | **KEEP.** This is the target model: facts, not a place. |
| 24 | Datahike writer (store.clj:182); konserve per-key locks inside `k/*` calls (reference-code/konserve/src/konserve/core.cljc:158) | Transaction serialization and store key IO. | **KEEP.** These are dependency contracts and the serialization authority. |
| 25 | cluster/store.clj:99–119 `fresh-file-lock` generator | Samples for the `:seon.store/lock` schema. Each sample creates a `tmp/schema-generator/<uuid>.lock` file and holds its descriptor until the sample is released. | **KEEP** for now. Sample files and descriptors leak if a sample is never released. See finding F2. |

## Totals

- 38 lock-shaped sites across 25 rows.
- **KEEP 17 rows**: OS or cross-process file locks (7, 10, 11, 12), host Java contracts (9, 13, 14, 15, 16, 17), CAS or memo seams (18–22), fact-derived fences (23), dependency contracts (24), and the generator (25).
- **REPLACE 7 rows**: 1, 2, 4, 5, 6, 7, 8, plus the monitor half of row 10.
- **Replaced by another lane, 1 row**: row 3 (detect-program-change).
- **DELETE**: the dead `reserve-cluster!` family (inside row 6).
- Estimated lines removed: about 70 from row 3 by the other lane, about 20 of dead code, and about 20 net from the replacements. **About 110 net.** The value is not the line count. It is removing the monitors held across IO, joins and transactions (rows 1, 2, 4, 5), which serialize all agents or all tests.
- Suggested order:
  1. Row 6's deletion plus rows 8 and 10 (mechanical and tiny).
  2. Rows 1 and 2 together (one owner, agent.clj; they share the nesting).
  3. Rows 4, 5 and 7.

## Out-of-scope findings (the issue index is root's; nothing filed by this lane)

- **F1** Two prepl writers exist: `seon.cluster/mcp-io-prepl` (cluster.clj:553) and `seon.operator.prepl/io-prepl` (resources/seon/operator/prepl.clj). Both are forks of upstream `io-prepl`. Verify which one is live and delete the other.
- **F2** `fresh-file-lock` generator samples write lock files under `tmp/` that are never removed.
- **F3** maintenance.clj:518–530 opens a second store holder outside `acquire-root-store!`'s refcount (see row 7). This is a correctness risk independent of locking.
