---
type: research
status: active
tags: [research, agent, database]
---

# Multi-reader SQLite spike — datahike-cljs + konserve-sqlite-cljs

## TL;DR — Verdict: RED

Two Node processes **cannot** concurrently open the same datahike-cljs SQLite
store. Not RO+RO, not RO+RW. The blocker is **inside `node-sqlite3-wasm`**:
its Node VFS implements SQLite file locking by `mkdir`-ing a sibling directory
`<dbfile>.lock` as an exclusive mutex. Mkdir-as-mutex is single-holder by
definition — there is no shared-read mode. The second opener races, loses,
and gets `SQLITE_BUSY` → propagated as `SQLite3Error: database is locked`.

This is a property of the **WASM SQLite VFS shim**, NOT of SQLite itself.
Native SQLite on the same OS would happily allow N readers + 1 writer via
POSIX `fcntl` byte-range locks; WAL mode would even let readers proceed
during a writer commit. The mkdir-mutex was a deliberate simplification by
the `node-sqlite3-wasm` author (WASM can't call `fcntl`), and it collapses
the entire shared-locking model to "one open at a time."

Step 3 (1 writer + 2 readers concurrent) was **not run** because Step 2 (2
readers, no writer) already fails — the multi-process story dies at "open."

## Build command

```
cd pod-host/libdatahike-cljs
npm install                                # node-sqlite3-wasm 0.8.57
npx shadow-cljs compile spike-multireader  # 11s, 0 warnings
./spikes/multi-reader/run.sh               # driver

```

Build target added: `:spike-multireader` in `shadow-cljs.edn`, output
`out/spike-multireader.js`, main `seon.podhost.libdatahike.spike-multireader/main`.
Dispatch on `argv[2]` ∈ `{writer, reader, rwwriter, ropoll}`. Source:
`src/seon/podhost/libdatahike/spike_multireader.cljs` (~150 LOC).
Store path: `tmp/multi-reader/store.sqlite`.

## Step 1 — writer (single process, fresh store)

```
$ node out/spike-multireader.js writer tmp/multi-reader/store.sqlite
[writer pid=41248 ...] creating fresh store at tmp/multi-reader/store.sqlite
[writer pid=41248 ...] DONE basis-t= 536870914  entity-count= 100
exit=0

```

GREEN. Schema + 100 entities, basis-t advances to `536870914`. Inspect the
on-disk artefacts:

```
$ ls -la tmp/multi-reader/
-rw-------    1 sean  staff  49152 May 24 14:20 store.sqlite
drwxr-xr-x    2 sean  staff     64 May 24 14:20 store.sqlite.lock   ← !!!

```

Note the **leftover `store.sqlite.lock` directory**. The writer called
`js/process.exit 0` mid-go-block, so it never closed the SQLite connection,
so the VFS never `rmdir`'d its lock. Stale lock = bricked store from the
filesystem's perspective.

## Step 2 — two concurrent readers

After manually `rmdir`-ing the stale lock, spawning two readers in parallel
against the static (post-writer, no concurrent writer) store:

```
$ node ...reader... > /tmp/r1.log & P1=$!
$ node ...reader... > /tmp/r2.log & P2=$!
$ wait $P1; wait $P2
exits=0,2

```

`/tmp/r1.log`:

```
[reader pid=41575 ...] opening store at tmp/multi-reader/store.sqlite
[reader pid=41575 ...] basis-t= 536870914  result-count= 100
                      first3= (["ent-0" 0] ["ent-1" 1] ["ent-2" 2])

```

`/tmp/r2.log`:

```
[reader pid=41576 ...] opening store at tmp/multi-reader/store.sqlite
[reader pid=41576 ...] ERROR No protocol method IDeref.-deref defined for
  type object: #error {:message "database is locked",
                       :data {},
                       :cause #object[SQLite3Error SQLite3Error:
                                      database is locked]}

```

**One wins, one fails.** The two processes raced for `mkdir
store.sqlite.lock` — whoever called `mkdir` first owns the file; the other
got `EEXIST`, which the VFS translates to `SQLITE_BUSY`, which surfaces as
"database is locked." Datahike's `connect` call then resolves to an error
value, and `@conn` (which is `cljs.core/deref` on the result) blows up with
the `IDeref.-deref` missing-protocol message — that's a downstream symptom,
not the root cause.

Also notable: even the WINNING reader leaves its own `store.sqlite.lock`
directory behind after `process.exit`. So a successful read also bricks the
store for the next process until that lock is manually removed.

## Step 3 — 1 RW writer + 2 RO pollers

**Not run.** Step 2 already proves the multi-process model fails at the
SQLite VFS layer. Step 3 would only confirm the same failure with more
processes in flight.

## Does `konserve-sqlite-cljs` open RO at the SQLite layer?

**No, and it can't usefully**:

- Line `src/konserve_sqlite_cljs/core.cljs:97` opens `(Database. path #js {})`
  — empty options. `node-sqlite3-wasm`'s `Database` constructor accepts
  `{ fileMustExist?: boolean; readOnly?: boolean }` per its `.d.ts`. So passing
  `#js {:readOnly true}` would set `SQLITE_OPEN_READONLY` on the underlying
  `sqlite3_open_v2`.
- But the konserve adapter unconditionally runs `ensure-schema!` immediately
  after open: `CREATE TABLE IF NOT EXISTS konserve(...)`, `PRAGMA
  journal_mode=WAL`, `PRAGMA synchronous=NORMAL`. All three are **writes**
  from SQLite's perspective. Even a `CREATE ... IF NOT EXISTS` against an
  already-existing table issues an exclusive lock. So passing `:readOnly true`
  would just move the failure earlier (the `CREATE TABLE` would fail with
  `SQLITE_READONLY` instead of the open succeeding).
- More importantly, the **VFS lock is acquired BEFORE the open mode even
  matters** — `node-sqlite3-wasm`'s lock function (decompiled):

  ```js
  function _nodejsLock(fi, level) {
    if (!_isLocked(fi)) {
      try { fs.mkdirSync(`${_path(fi)}.lock`); }
      catch (err) { return err.code == "EEXIST"
                           ? SQLITE_BUSY
                           : SQLITE_IOERR_LOCK; }
    }
    ...
  }

  ```

  This runs on **every** lock-state transition (NONE → SHARED → RESERVED →
  EXCLUSIVE), and there is exactly one `.lock` directory regardless of mode.
  There is no "shared lock" code path — `_isLocked` is a single boolean.
  Read-mode and write-mode collapse to "the directory exists or it doesn't."

**Implication:** `konserve-sqlite-cljs` over `node-sqlite3-wasm` is a
**single-process** store. It will work fine for one Node process holding
both reads and writes (the in-process `conn-cache` atom at
`core.cljs:91` ensures that). It will not work for two Node processes,
regardless of intent (RO/RW), regardless of WAL, regardless of timing.

The `PRAGMA journal_mode=WAL` at `core.cljs:77` is irrelevant under this
VFS — WAL would matter if multiple connections could simultaneously acquire
read-locks via `fcntl`. Here, mkdir gates everything before WAL ever runs.

## Concrete failure modes observed

1. **Stale `.lock` directory after `process.exit`** — every process that
   opens the store leaves `<path>.lock/` behind unless it explicitly closes
   the SQLite handle before exit. In production this would manifest as: any
   ungraceful crash bricks the database until someone manually `rmdir`s the
   lock. `konserve-sqlite-cljs`'s `drop-conn!` (line 103-108) would close
   cleanly, but its `SqliteConn.close` is only triggered by an explicit
   `drop-conn!` call — there is no `process.on('exit')` hook.

2. **`@conn` blows up with `missing-protocol IDeref.-deref`** — when
   `d/connect` fails internally, it returns an error VALUE on the channel
   (rather than throwing or closing), so `(<! ...)` produces a non-Atom
   error map, and `@conn'` then fails because `cljs.core/deref` has no
   protocol impl for raw JS Error / cljs error maps. This is a separate
   datahike-cljs ergonomic issue — errors should propagate as throws, not
   as silently-bad return values that explode at the next `@`.

## What to try next

Three rough options, in increasing order of effort:

1. **Give up on multi-process and treat datahike-cljs as single-process.**
   This is what the existing pod design already assumes. Cross-process
   sharing would happen via a different mechanism (broadcast over IPC, a
   read-replica process polling a snapshot file, etc.). The multi-runtime
   architecture research note (2026-05-24) already implicitly assumes this.

2. **Swap the SQLite binding.** `better-sqlite3` (native node module, not
   WASM) uses real POSIX locking via SQLite's standard Unix VFS, supports
   WAL, and allows N readers + 1 writer concurrently across processes. It
   would also need a konserve adapter port (konserve-sqlite-cljs is
   currently tied to `node-sqlite3-wasm`'s sync API; `better-sqlite3` is
   also sync so the port is small). Note: native modules complicate the
   wasm-Tauri containment story — better-sqlite3 won't run inside a
   `wasm32-wasip2` component. So this only helps the current V0 pod, not
   the long-term wasmtime substrate.

3. **Replace the `node-sqlite3-wasm` Node VFS shim with one that does
   `fcntl`-via-N-API.** Substantial work; effectively forking the library.
   Not worth it given option 1 is the design intent and option 2 exists for
   the v0 pod if perf demands.

## Verdict summary

| Step                                  | Result | Why                                                                            |
|---------------------------------------|--------|--------------------------------------------------------------------------------|
| 1 — single writer                     | GREEN  | 100 entities, basis-t=536870914                                                |
| 2 — two concurrent RO openers         | RED    | `mkdir`-mutex VFS lock in `node-sqlite3-wasm`; one wins, one gets `SQLITE_BUSY` |
| 3 — 1 RW + 2 RO concurrent            | n/a    | Skipped; subsumed by Step 2 failure                                            |
| RO-flag at SQLite layer (`readOnly`)  | RED    | Would work at open, but VFS lock is mode-agnostic and `ensure-schema!` writes  |

**One-line root cause:** `node-sqlite3-wasm`'s WASM-side Node VFS uses
`fs.mkdirSync('<file>.lock')` as the SQLite exclusive lock primitive, with
no shared-lock mode — so any two processes that touch the same SQLite file
serialise at the directory-creation step.
