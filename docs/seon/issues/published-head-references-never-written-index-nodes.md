---
type: issue
status: open
severity: critical
created: 2026-09-23
tags: [issue, datahike, store, write-path, durability]
---

# A published head references index nodes that were never written

## Observation

Twice on 2026-09-23 default's store refused a read with Datahike's
`Node not found in storage.` (`persistent_set.cljc:436-439`): nodes
`6ab361f1-9fef…`, `6ab36206-bc18…` on pid 90963 (before its heap death at
05:40Z), and `6ab376ef…`, `6ab377b7…`, `6ab378a2…` on pid 31476 (around 06:51–06:58Z).
The first store survives as `tmp/orchestrator/heap-2026-09-23/store-broken`.
The second was nuked at 08:40Z and left no evidence.

## Evidence (first store; APFS copy opened read-only through konserve, 2026-09-23 ~13:55Z)

The collector did not delete them. **No collection ever ran on this store.**
- The roster holds 9 branches. 1,639 retired branch-head keys are still on
  disk, the oldest `:building-source-24492-…` written at 01:09:09Z, about two
  minutes after genesis.
- `gc-storage!` sweeps every unreachable key older than its cutoff
  (`reference-code/datahike/src/datahike/gc.cljc:84-169`), so any collection
  after 01:09Z would have removed all of them.
- The new default has no maintenance receipts. The only runtime caller of
  `registry/collect!` is the scheduled `seon.maintenance/collect!` (Sunday
  03:00 UTC, floored at task creation, `schedule.clj:227-254, 665-719`).

**Two commits were accepted as committed while none of their writes reached the directory.**

1. The `publish!` source seal, on the scratch branch.
   - `current-src`'s head (commit `6ab361f3`, max-tx 536871088, head written
     05:21:56, commit record 05:21:55) inlines fused roots.
   - Every child those roots gained in their flush is absent: seven
     `6ab361f1-*` nodes (squuid second 05:21:53) across eavt, aevt, avet,
     temporal-eavt and temporal-aevt. No `6ab361f1-*` key exists at all.
   - Transaction 088 is `source.clj` ~636-646's seal (the
     `:seon.source/digest` change, read from temporal-avet).
   - Its scratch branch `building-source-…-959aeadc` has no 088 record:
     its head stayed at 087 (`6ab361b8`, written 05:21:03, parent
     `6ab360bd`).
   - Still `force-branch!` (`source.clj:660`) published an 088 database from
     `@connection`. Datahike advances `@connection` only after `commit!`
     returns (`writer.cljc:245-257`), so the scratch writer believed 088 was
     durable.
2. An ordinary writer commit, on `cluster-default`.
   - Of 1,040 `cluster-default` commit records, exactly one parent is absent:
     `6ab36209` (written 05:22:19) names parent `6ab36206-db57…` (05:22:14).
   - Neither that commit record nor any `6ab36206-*` node exists.
   - `6ab36209`'s roots reference the missing `6ab36206-bc18…`, and about
     60 later records (cluster-default, forked agent heads, probe branches)
     share it.
   - A fork taken by `registry/branch!` at about 05:22:20
     (`probe-c8-parent-…`) branched from `6ab361f5`, the head before
     `6ab36206`. So `6ab36206`'s head write never landed either, while the
     writer threaded it as `last-cid` into the next commit.

Both instances fall within 25 seconds, while pid 90963 was nineteen minutes
from heap exhaustion (old generation 10.26/10.74 GB, GC about 62 s of every 120 s).
Writes landed about 0.6 s per three files, and 087's flush began 63 s after its
transaction (squuid 05:20:56 vs transaction instant 05:19:53).

## What the source and disposable reproductions rule out

Reproductions ran in-process on disposable file stores under
`tmp/store-damage/`, using Seon's configuration: fused roots, branching
factor 4096, diff buffer 256, history on. `tmp/store-damage/probe.clj`
holds the forms. Each run was verified by a fresh connection walking all six
indexes.
- Sequential publication (fork from the published commit, two
  transactions, then `force-branch!` of `@scratch`), 10 rounds: no loss.
- The same with a concurrent branch writer and agent forks
  off the main connection, 10 rounds: no loss.
- Two queued transactions batched into one commit: no loss.
- `force-branch!` while the commit is in flight publishes the previous
  committed database (in-flight transactions are not published), not a
  dirty one: no loss. Losing the in-flight transaction is a separate
  caller-correctness issue.
- In Datahike, `-flush` stores through the set's own storage
  (`persistent_set.cljc:219-221`). `commit!` and `force-branch!` drain
  `(:storage (:store db))` (`writing.cljc:37-47, 493`;
  `versioning.cljc:400`). `stored->db` binds every index to its
  connection's storage (`writing.cljc:253-270`). No path advances a
  connection without `commit!` returning.

## Mechanism (reproduced 2026-09-23 ~14:28Z, in-process, disposable file stores)

An `Error` inside a konserve write is swallowed, and the writer takes the swallowed write for a successful one.
- superv.async 0.3.50 (the version on our classpath) defines `go-try-` so that its body is wrapped
  in `(catch Exception e e)` (`superv/async.cljc:174-181`).
- Its `<?-` rethrows only when the value it takes is an `Exception`
  (`throw-if-exception-`, `superv/async.cljc:87-98`).
- An `Error` (OutOfMemoryError, AssertionError, StackOverflowError)
  therefore escapes the `go` block. core.async hands it to the uncaught-exception
  handler and closes the block's channel, so the awaiting `<?-` receives `nil`.
- Every awaiting layer reads that `nil` as success. On the ordered multi-key path those layers are
  konserve's `-multi-assoc` (`impl/defaults.cljc:680-688`), `k/multi-assoc`
  (`core.cljc:486-509`) and Datahike's `commit!` (`writing.cljc:535`).
  On the per-key path they are `write-pending-kvs!` (`writing.cljc:356-366`) and the
  `k/assoc` it awaits.
- `commit!` then returns, and the writer installs the database and threads its commit id
  into the next commit (`writer.cljc:245-257, 279-280`).
- Upstream superv.async `main` (5929e31) is still `Exception`-only (`async.cljc:116, 199, 230`).

Reproduction: `tmp/store-damage/inject.clj`. A store bound to konserve's
`*multi-write-stage-hook*` throws an `AssertionError` at the second
`:blob-moved` of one commit.
- `d/transact` returned a report, and the connection advanced to `6ab3e1e9-1cf8…`
  while the disk head stayed at the prior commit.
- One more ordinary transaction then published a head on top of the phantom.
- A fresh connection refused: `Node not found in storage` `6ab3e1e9-0ee1…`.
  That is the incident's signature, end to end.
- The same injection with an `ExceptionInfo` failed the commit loudly
  (`:datahike/writer-shutdown`).
- Per-key path, same hole: `k/assoc` whose write hook throws an `Error`
  delivers `nil` (a successful write delivers `[nil 9]`), and
  `write-pending-kvs!` returns `nil` either way.
- Heap exhaustion is the production trigger. `prepare-multi-assoc` serializes every
  value of the batch before writing any (`impl/defaults.cljc:417-470`), so an
  OutOfMemoryError there loses the whole batch, as observed.

Reverting our filestore multi-key implementation (konserve fork `5b39fdd`,
owner ruling) moves Datahike to its per-key path. It does not close the class.

## Recommendations: each part fails alone and loudly

**(a) Every Throwable in the write path fails the commit, and the connection does not advance.**
The single seam is superv.async's two definitions: `go-try-`/`go-try` catch
`Throwable`, and `throw-if-exception-` tests `Throwable`. That is 4 lines, and it covers
every konserve and Datahike await at once.

| Option | Cost | What it gives up |
|---|---|---|
| 1. Fork superv.async (`seantempesta/superv.async`) and pin it in `deps.edn` (**recommended**) | 4 lines; a new fork repository plus a pin | nothing |
| 2. konserve-local and Datahike-local macros, swapped in by one script over 19 namespaces (8 konserve, 11 Datahike: 120 `go-try-` and 302 `<?-` sites) | about 20 lines plus the swap | other superv callers keep the hole |
| 3. Catch only `VirtualMachineError` at Datahike's `commit!` | smallest edit | cannot see nested blocks, so it does not close the class |

Datahike's `commit!` already converts an `Error` raised in its own block
(`writing.cljc:565-569`); nested blocks are the gap.
**(b) The JVM exits on OutOfMemoryError.** Add `-XX:+ExitOnOutOfMemoryError` and
`-XX:+HeapDumpOnOutOfMemoryError -XX:HeapDumpPath=data/heap` to the JVM options at
`deps.edn:150`.
- Exiting costs one boot (about 27 s ready, 41 s wall measured today).
- Limping cost two stores today (two nukes, about 56 s each plus the lost state) and hours of lanes blocked.
- A dump costs disk the size of the heap (up to 10.7 GB); keep the newest only.
**(c) Opening a store proves that its heads' nodes exist.** For every roster head, check
that each child address of the six fused roots exists, with `k/exists?` (a file stat).
- On the preserved store it took 60 ms for 9 heads (6,188 checks).
- It found every damaged head: `current-src` 7 missing, `cluster-default` 2, two agent heads 2 each.
- It is proportional to root fan-out × heads (depth-1 trees at branching factor 4096).
  Deeper trees add their interior nodes. Sub-second either way.
- Boot then refuses a damaged store by name instead of failing later inside a test.

## Before whole-store collection is turned on (`:seon.config.maintenance/collect?`, `ba173845c`)

On the fork, prove that a collection loses no reachable node while a context holds an old
database value and while a transaction is in flight. Today a zero-window
collection sweeps an adopted source commit
(`an-explicit-collection-sweeps-the-clusters-adopted-source-commit.md`).

## Owner and regression

Owners: superv.async's `go-try-`/`<?-`, plus konserve's and Datahike's awaits.

Wanted regression: inject an `Error` into one konserve write. The commit fails,
the connection stays at the prior head, and a fresh connection reads every node of that head.
