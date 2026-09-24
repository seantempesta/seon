---
type: landing
status: committed a6236daa1 4b487b5ac, code only (owner load rule); scratch-namespace probes only; unadopted
lane: locks-fs-and-runner-stamp
created: 2026-09-24
design: docs/research/agent-platform/lock-audit-2026-09-24.md rows 4 and 5
---
# Lane locks-fs-and-runner-stamp (2026-09-24)

Lock audit rows 4 and 5 (`docs/research/agent-platform/lock-audit-2026-09-24.md`,
9437d0e01). Owner ruling: detect and push back; values over places.

## Broken or unproven first

- **No test request was run (LOAD RULE).** The installed test namespaces have not
  been exercised. Root runs these:
  - `bin/test-check default --test seon.fs.jvm-test/concurrently-staged-writes-have-one-whole-winner`
  - `bin/test-check default --test seon.fs.jvm-test/racing-digest-fenced-writes-have-one-winner`
  - `bin/test-check default --test seon.test-reaching-test/concurrent-reach-reads-of-one-value-derive-once`
  - `bin/test-check default --test seon.test-reaching-test/reach-digests-follow-only-changed-closures`
  - `bin/test-check default --ns seon.test.reach-test`
- **Not adopted.** Hook publication is paused, so default still runs the HEAD Vars.
  Root adopts `src/seon/fs/jvm.clj` and `src/seon/test/runner.clj` at a checkpoint.
- **The fs write keeps a narrow in-JVM monitor.** No filesystem compares a digest at
  the rename. `SecureDirectoryStream.move` is atomic but unconditional, so deleting
  the monitor would let two in-JVM writers both pass the recheck and both move (a
  lost update). The monitor now covers only the precondition recheck and the move.
  Staging, the `content-info` digest pass and `FileChannel.force` run outside it.
  External writers are still detected, not excluded (`:my.fs/stale-digest`).
- **The regression cannot prove that staging overlaps.** No hook exists without
  redefinitions. It proves the wanted outcome over 8 rounds of 2 MB concurrent
  writers: one whole winner, one refusal by name, no stage residue.
- **Cold reach derivation costs more than 1 s. This lane did not cause it and
  changed nothing about it.** A first read of 20 tests not yet in the index took
  1,068–1,146 ms on HEAD and on this change alike. The cost is spent in
  `seon.test.runner/reach-facts` → `seon.db/q` (284 calls; 240,340
  `query-binding-values`; `seon.db/with-declarations` 1,421 ms inclusive). It is
  proportional to the transitive closure that is read per query round. Filed as
  `docs/seon/issues/cold-reach-derivation-exceeds-one-second.md` (index not edited).
- `reach-cache`/`reach-entries` have no Malli contract. The gap already existed and
  I did not widen it.

## What changed

1. `src/seon/fs/jvm.clj` `write` (a6236daa1). `locking write-serialization` moves
   off the whole write and onto the recheck plus the atomic move. It is +6/−3 when
   whitespace is ignored; the rest is re-indentation.
2. `src/seon/test/runner.clj` `reach-cache`, `reach-entries` (4b487b5ac).
   `(locking holder …)` and `alter-meta! holder assoc ::reach-index` are deleted.
   - **Defect found:** the old holder was `(:seon.sci.eval/projection-state (meta database))`.
     That is the live SCI environment atom, whose value holds the whole environment (I
     probed its keys: projection, connection, store, work launcher and others). This is
     why the index had to be kept as metadata. Storing the index as that atom's value
     would have overwritten the environment.
   - The holder is now always a separate `(atom nil)` from
     `schema/projection-cache-value` on `db/carried-projection`. I probed default: the
     carried projection is the same object as the state's projection, and it has a cache.
   - The atom's value is a delay of the newest index. Equal `committed-value-identity`
     values that cover the requested tests deref that delay. Otherwise a reader claims
     with `compare-and-set!`, derives from the usable previous index, and on a lost CAS
     re-reads. A failed claim is withdrawn by CAS, so a cached exception never becomes
     the cache (I probed that Clojure 1.12.5 `Delay` caches a thrown exception).
   - Older or foreign values still derive on their own and never replace the cached
     index. `::reach-derivations` counts misses.
   - `test/seon/test_reaching_test.clj`: the existing test now reads the index through
     `reach-cache` instead of metadata, and asserts an unchanged derivation count where
     it used to assert `reach-computed 0`. A new regression is added.

## Evidence (default JVM, scratch namespaces, no default Var redefined)

- fs: I loaded the edited source as `tmp.locks-fs-jvm` and ran 8 rounds of two
  concurrent 2 MB writers with the same expected digest. The frequencies were
  `{[1 winner, 1 stale-digest, disk digest = winner's after-digest, 1 entry] 8}`,
  taking 509 ms total (64 ms/round, 4 MB each).
- runner: I loaded the edited source as `tmp.locks-runner-probe` with its two
  `register-core-predicate!` forms stripped.
  - 4 concurrent reads of one default db value for 20 tests gave `:derivations 1`,
    all results equal, and results equal to HEAD's `reach-digests`.
  - The warm hit took 0.48 ms.
  - With the order swapped over new symbol sets, the first mover took 1,068 ms on HEAD
    and 1,141 ms on the change; the second mover took 547 ms on HEAD and 741 ms on the
    change. Equal results: `[true true]`. Neither version is slower; the spread follows
    the order in which each ran.
- Lint: 0 errors in all four files; the warnings were already there (private
  handlers resolved through `ns-resolve`, and `future` in tests that deref it).

## Lines

- fs: +6/−3 source (ignoring whitespace), +31 test.
- runner: +28/−12 source, +28/−4 test.
- Net source is +19. The runner change is not net-negative: the CAS claim, the
  failure withdrawal and the derivation count cost about 10 lines more than the
  deleted lock.

Released paths after root verifies: `src/seon/fs/jvm.clj`, `src/seon/test/runner.clj`,
`test/seon/fs/jvm_test.clj`, `test/seon/test_reaching_test.clj`, this note.
