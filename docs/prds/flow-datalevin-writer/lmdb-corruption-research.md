---
type: prd
status: draft
tags: [prd, database, flow]
---
# LMDB Corruption After SIGKILL — Research Findings

## Root Cause: NOT LMDB Corruption

**The `rm -rf data/datalevin/` workaround has been masking the real issue. LMDB with default flags IS crash-safe. The corruption we see is likely from a different cause.**

## What LMDB Guarantees

LMDB uses a copy-on-write B+ tree with two alternating meta pages. On commit:

1. New pages are written to free space (never overwriting live pages)
2. `fdatasync()` flushes data pages to disk
3. The meta page is updated atomically (single page write)
4. `fdatasync()` flushes the meta page

After SIGKILL, LMDB recovers by reading whichever meta page has the last valid committed transaction. Uncommitted writes are simply lost — the old pages are still intact.

**This guarantee holds ONLY when these flags are NOT set:**

- `MDB_NOSYNC` — skips `fdatasync()`, so data pages may not be on disk when meta page is written
- `MDB_WRITEMAP` — maps the file read/write; partial page writes during crash can corrupt
- `MDB_MAPASYNC` — async msync with writemap; crashes can lose recent commits
- `MDB_NOMETASYNC` — skips meta page sync; safer than NOSYNC but still risky

## What Flags Datalevin Uses

From `reference-code/datalevin/src/datalevin/constants.clj:92`:

```clojure
(def default-env-flags
  "Default LMDB env flag is `#{:nordahead}`. See
  [[datalevin.core/set-env-flags]] for a full list of flags."
  #{:nordahead})
```

**Default is `#{:nordahead}` only.** This is `MDB_NORDAHEAD` which just disables OS read-ahead — it has NO impact on crash safety. The dangerous flags (`:nosync`, `:writemap`, `:mapasync`) are NOT set by default.

From `reference-code/datalevin/src/datalevin/binding/cpp.clj:1508-1524`, `open-kv*` uses `c/default-env-flags` unless overridden. The only place `:nosync` is auto-added is for `temp?` databases.

**Our Seon code passes no custom flags** — confirmed by grep of `src/seon/db/` showing zero matches for `kv-opts`, `:flags`, `nosync`, or `writemap`.

**Conclusion: Datalevin opens LMDB with safe defaults. SIGKILL should NOT cause corruption.**

## So Why Does It Corrupt?

The assertion `mp->mp_pgno != pgno` in `mdb_page_touch()` means a page references itself in the B+ tree. With safe flags, this should be impossible after SIGKILL. Likely causes:

### 1. Multiple Processes Accessing the Same LMDB Environment (MOST LIKELY)

LMDB uses `lock.mdb` for multi-process coordination. The critical scenario:

1. `pkill -9` sends SIGKILL to the Seon JVM
2. The process dies immediately — no cleanup, no lock release
3. **But the Datalevin server is a separate thread in the same JVM** — it dies too
4. You run `./bin/run` again
5. New JVM starts, new Datalevin server opens the LMDB environment
6. **The `lock.mdb` file from the killed process is stale**

LMDB handles stale locks by checking if the PID that holds the lock is still alive. On macOS, this usually works. BUT:

- If the new process gets the **same PID** as the old one (PID recycling), LMDB thinks the old reader is still alive
- If the lock file is in a corrupted state (partially written during SIGKILL), the new process may misinterpret it

### 2. The Datalevin Server's Internal Write Lock

From `reference-code/datalevin/src/datalevin/db.clj:681-685`, Datalevin temporarily sets `:nosync` during certain operations:

```clojure
(let [flags   (get-env-flags lmdb)
      nosync? (:nosync flags)]
  (set-env-flags lmdb #{:nosync} true)
  ;; ... do work ...
  (when-not nosync? (set-env-flags lmdb #{:nosync} false)))
```

**If SIGKILL arrives while `:nosync` is temporarily enabled**, the last committed transaction may not be fsynced. This is a real window of vulnerability.

### 3. The `lock.mdb` Stale Reader Table

LMDB's `lock.mdb` contains a shared reader table. Each reader slot records a PID and a transaction ID. When a process dies:

- Its reader slots become stale
- LMDB's `mdb_reader_check()` can clean these up
- But if the reader was mid-transaction at death, the cleanup may not happen correctly on the next open

**On macOS specifically**, file locks (flock/fcntl) are released on process death, but the mmap'd reader table in `lock.mdb` retains the stale PID entries until explicitly cleaned.

## Current State of data/datalevin/

The directory exists and has data:

- `data.mdb` — 245,760 bytes (server-level, small — just metadata)
- `lock.mdb` — 65,664 bytes (reader table)
- Multiple hex-named subdirectories (per-database LMDB environments)
- Each subdirectory has its own `data.mdb` and `lock.mdb`

The corruption could be in the top-level server DB or any of the per-database environments.

## Recovery Options

### Option 1: Delete Only lock.mdb Files (Try First)

```bash
find data/datalevin -name 'lock.mdb' -delete
```

LMDB recreates `lock.mdb` on next open. If the corruption is only from stale locks (not actual data page corruption), this fixes it without data loss.

### Option 2: Use mdb_copy for Recovery

If LMDB tools are available:

```bash
# mdb_copy compacts and rebuilds the B+ tree
mdb_copy -c data/datalevin/73656F6E output_dir
```

This reads valid committed data and writes a clean copy. Skips corrupted pages.

### Option 3: Datalevin's copy Function

```clojure
;; From datalevin.interface/copy — calls mdb_env_copy2
(datalevin.core/copy db "/path/to/backup" true)  ; compact=true
```

This only works if the DB can be opened at all.

### Option 4: Nuclear — rm -rf (Current Workaround)

Delete everything and rebuild. Acceptable for development but not production.

## Recommended Fixes

### Fix 1: Never Use SIGKILL (Immediate)

Replace `pkill -9` with graceful shutdown:

```bash
# Instead of: pkill -9 -f "java.*seon"
# Use:
pkill -15 -f "java.*seon"   # SIGTERM — triggers JVM shutdown hook
sleep 3                       # Wait for graceful shutdown
pkill -9 -f "java.*seon"    # Only if still alive
```

Seon already has a JVM shutdown hook in `src/seon/core.clj:247-262` that:

1. Calls `db/shutdown-writers!` (flushes async write flows)
2. Backs up ctx instances
3. Calls `stop-app` (Integrant halt)

**This hook runs on SIGTERM but NOT on SIGKILL.** SIGTERM is the fix.

### Fix 2: Clean Stale Locks on Startup (Defensive)

Before opening any LMDB environment, delete `lock.mdb` if no process holds it:

```clojure
;; Pseudocode for startup
(defn clean-stale-locks! [root-dir]
  (doseq [lock-file (find-files root-dir "lock.mdb")]
    ;; LMDB auto-recreates lock.mdb
    (io/delete-file lock-file true)))
```

This is safe because:

- If no Datalevin process is running, the lock is stale by definition
- LMDB recreates `lock.mdb` fresh on `mdb_env_open()`
- Our startup already checks that no Seon is running (port checks)

### Fix 3: Sync Before Close (Belt and Suspenders)

Add explicit `(datalevin.core/sync db)` calls:

- In the shutdown hook before `stop-app`
- In the Integrant `:halt-key!` for the Datalevin server component

### Fix 4: Update MEMORY.md / Operational Notes

Change the kill command from:

```
Kill JVMs: `pkill -9 -f "java.*seon"`
```

To:

```
Kill JVMs: `pkill -15 -f "java.*seon" && sleep 3 && pkill -9 -f "java.*seon"`
After forced kill: delete lock.mdb files, NOT data.mdb
```

## Is This a Datalevin Bug?

**Partially.** The temporary `:nosync` flag in `datalevin.db` (lines 681-685) creates a vulnerability window. If SIGKILL arrives during that window, crash safety is lost. This is arguably a Datalevin design issue — it should either:

1. Not temporarily disable sync
2. Do an explicit `fdatasync` before re-enabling sync

However, SIGKILL is explicitly documented as unsafe for LMDB when nosync is active. The real fix is to not use SIGKILL.

## Summary

| Factor | Impact | Fix |
|--------|--------|-----|
| SIGKILL bypasses shutdown hook | Writer flows not flushed, locks not released | Use SIGTERM (fix 1) |
| Stale `lock.mdb` after crash | New process may see inconsistent reader state | Clean on startup (fix 2) |
| Temporary `:nosync` in datalevin.db | Small window where crash can corrupt | Sync before close (fix 3) |
| `rm -rf data/datalevin/` as workaround | Destroys all data unnecessarily | Delete only `lock.mdb` (option 1) |

**The immediate action is: stop using `pkill -9` and try deleting just the `lock.mdb` files to recover the current database.**
