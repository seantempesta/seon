---
type: landing
status: in progress (Seon regression waits for default's restart on the new dependencies)
created: 2026-09-23
lane: store-damage (Opus 5.5)
issue: docs/seon/issues/published-head-references-never-written-index-nodes.md
---

# Lane store-damage — 2026-09-23

**What the dependency already does here.** superv.async's `go-try-` already turns a
failure inside a go block into a value on its channel, and `<?-` rethrows that
value. It just stops at `Exception`. The form below returns `nil` on 0.3.50; the
patched fork returns `ExceptionInfo` caused by the `AssertionError`:
`(<!! (go-try- (<?- (go-try- (throw (AssertionError. "inner Error"))))))`.

**Smallest change that improves it.** Widen that one mechanism from `Exception` to
`Throwable`: four catch sites and eight `instance?` tests in superv.async
(`501b429`). That makes every konserve and Datahike await fail loudly, with no
wrapper, guard or second mechanism beside it.

## Verdict

The orchestrator suspected that zero-window collection deleted the missing store
nodes. The evidence refutes that. The preserved store never saw a collection: 1,639
retired branch heads from 01:09Z onward survive. The missing nodes were never
written.

Root cause, reproduced: superv.async's `go-try-`/`<?-` handle only `Exception`.
An `Error` inside one konserve write, whose production trigger was heap exhaustion,
closes the go block's channel. Every awaiting layer (konserve's `multi-assoc`,
Datahike's `write-pending-kvs!` and `commit!`) reads the resulting `nil` as a
durable write. The writer then advances the connection past nodes it never stored.
The issue note has the `file:line` evidence.

## Algorithmic analysis

- **The fix.** It costs nothing per operation: one `catch` class and one `instance?`
  test at each await, O(1). The simplest alternative considered was catching
  `VirtualMachineError` at Datahike's `commit!` only. It was rejected because
  nested konserve go blocks sit outside that catch.
- **Store check on open** (recommended, not built). It is proportional to root
  fan-out × roster heads: 6,188 existence checks in 60 ms on the preserved store.
- **Collection flag.** It is O(1) per scheduled fire: absent means off, and no store
  is opened.

## Commits

| Repository | Commit | What |
|---|---|---|
| Seon | `14d154914`, `fe57496d0` | issue note: evidence, mechanism, priced recommendations |
| Seon | `ba173845c` | `:seon.config.maintenance/collect?` flag (absent = off), typed disabled outcome, schedule regression |
| superv.async fork | `501b429` (on upstream `main` 5929e31) | every `Throwable` fails `go-try-`/`go-try` and the `throw-if-exception` family; regression |
| konserve fork | `5b39fdd` | revert of 737697d (filestore multi-key); `open-existing-blob` kept for 403503f and 3ae14f6; GC `batch-issued` also fires on the per-key path |
| Datahike fork | `84a20308` | single-source query-cache key `(vec (rest args))`, as upstream (it had retained the argument array and its database) |
| Datahike fork | `c79cd03a` | Datahike's own `deps.edn` resolves konserve `5b39fdd` and superv.async `501b429` |

## Proof

- **Mechanism reproduced** (`tmp/store-damage/inject.clj`). An `AssertionError` at
  the second file move of one commit:
  - `d/transact` returned.
  - The connection advanced while the disk head stayed.
  - The next commit published on top of the phantom.
  - A fresh connection refused with `Node not found` `6ab3e1e9-0ee1…`.
  - An `ExceptionInfo` at the same point failed loudly.
- **Per-key path, same hole.** A write hook that throws an `Error` makes `k/assoc`
  deliver `nil`; a good write delivers `[nil 9]`.
- **superv.async fix, checked in default's JVM** (throwaway namespace, 482 ms). An
  inner `AssertionError` arrives as `ExceptionInfo` caused by the `AssertionError`;
  values still flow (`42`). The installed 0.3.50 returns `nil`.
- **Dependency suites, each in its own repository:**
  - superv.async: 31 tests, 59 assertions, 0 failures, 36 s.
  - konserve: 84 tests, 1,312 assertions, 0 failures, 40 s; the same with the
    superv fork pinned, 42 s.
  - Datahike `:clj-pss`, with konserve `5b39fdd` and superv `501b429`: 904 tests,
    4,898 assertions, 9 failures. None of the failures is new:
    - The parent `2cc313a6`, on its old dependencies (a `git archive` in the
      scratchpad), fails the same 8 in `query-test`, `pull-api-test`,
      `query-cache-test` and `connector-release-test`.
    - `optimistic-test/tx-report-happy-path-converges-to-conn` fails 1 of 2
      runs on the parent and 2 of 2 on HEAD: flaky before this change.
    - The new query-cache regression passes.
- **Resolution** (`clojure -Spath -M:dev:test`): exactly one
  `superv.async/501b429…/src`, and `konserve/5b39fdd…`.

## Waits for default's restart

The Seon regression is
`seon.cluster.store-test/an-error-inside-one-store-write-fails-the-commit-and-keeps-the-prior-head`.
Under `:panic`, `seon.db/transact!` throws, the connection stays at the prior head, and a
fresh connection reads every datom of that head. Also waiting: one small commit timed
against writer-cost-2's 248 ms.

## Timings over one second

| Operation | Wall | Proportional to / why |
|---|---|---|
| Preserved-store copy, key listing, byte scan | 3.5, 3.8, 8.4 s | file count, file headers, 8.8 GB of store bytes; one-off diagnosis |
| Commit-chain census | 6.5 s | decoding every commit record; one-off |
| Datahike `:clj-pss` suite; parent's focused namespaces | several minutes; 74 s | the dependency's whole suite and five namespaces in fresh JVMs; over 10 s, and run once |
| superv.async and konserve suites | 36, 40, 42 s | each dependency's whole suite in a fresh JVM; over 10 s, and run once per fork change |
| `bin/test-check` named maintenance and schedule run (7bf04621c723) | 127 s | defect: every member refused on another lane's host-bound `reload_measure` declaration |
