# Why agents start slowly with many agents and a 64 GiB LMDB map

Lane: lmdb research (2026-10-22), next-blobs at 50aab91. Scratch: `~/src/seon-jolt/next-blobs/target/lane-lmdb/`.
Nothing was landed. Nothing of this lane is still running.

## Cause, in one paragraph

LMDB is not the cause. Every agent calls `seon.agent/hello!` (src/seon/agent.clj:476-478) as soon as it starts,
and `hello!` sends `(mem-mb)`, which calls `lib/real-mb` → `lib/mp-probe` → `mp_probe` (src/seon/lib.clj:79-88,
native/probe.c:29). `mp_probe` calls `mach_vm_region(VM_REGION_EXTENDED_INFO)` on every region of the process.
In XNU that flavor calls `vm_map_region_walk(..., range = entry->vme_end - start, look_for_pages = TRUE)`
(xnu-12377.1.9 osfmk/vm/vm_map.c:15793). The walk takes the region's VM object lock (vm_map.c:16000) and then
does a `vm_page_lookup` for **every page of the mapped range** (the loop at vm_map.c:16008-16051, lookup at
:16178). The range is the whole mapping, past the end of the file too. An agent holds two LMDB mappings of
data.mdb: the env it inherited from root (seon.db/after-fork! forgets it but does not unmap it, db.clj:290-300)
and the env it opens itself. At 64 GiB that is 2 × 4,194,304 lookups of 16 KiB pages. Every process that maps
data.mdb shares **one** VM object, the file's, so all these walks run one at a time on that object's lock. While
they run, other agents' `open()` of data.mdb (mdb.c:6374) and `pread` of its meta pages (mdb.c:4752) wait for the
same lock. Start time therefore grows as N × map size: about 195 ms per agent at 64 GiB and 3 ms at 1 GiB.
Commit 6739d89 raised the map from 1 to 64 GiB and made the probe 64× slower. The docstring of `mem-mb`
(agent.clj:434: "walks the VM regions, ~1.5 ms") was right only at 1 GiB.

## Evidence (all measured on this machine: 18 cores, 128 GiB, macOS 27.0.1, nice 15)

### 1. The real system, `seon scale N` (scratch harness target/lane-lmdb/scale.sh, a fresh store each run)

| N | map | probe | spawn→all-ready | start-ms p50 |
|---|-----|-------|-----------------|--------------|
| 30 | 1 GiB | stock | 343 ms | 290 |
| 30 | 64 GiB | stock | 5,943 ms (again 5,719) | 3,049 |
| 100 | 1 GiB | stock | 602 ms | 464 |
| 100 | 64 GiB | stock | 18,683 ms | 9,526 |
| 30 | 64 GiB | **fixed** (`SEON_PROBE_LIB=target/lane-lmdb/libprobe.dylib`) | **277 ms** | 249 |
| 100 | 64 GiB | **fixed** | **647 ms** | 530 |
| 100 | 1 GiB | fixed | 546 ms | 463 |

With the fixed probe, 64 GiB is as fast as 1 GiB. This is the end-to-end proof.

`sample` of one starting agent (30 agents, 64 GiB; target/lane-lmdb/agent-sample.txt): all 893 of 893 samples
were in `mdb_env_open` (mdb.c:6374) → `mdb_fopen` (mdb.c:5431) → `open`, blocked in the kernel. This matches the
earlier sample (open 728, pread 476). Those agents are the victims. The agents walking the map at that moment hold
the lock.

### 2. A C reproduction of the agent pattern (target/lane-lmdb/agents.c; results in matrix.txt)

The parent ("root") opens the env at map M and reads every key. It forks a server, and the server forks N
children. Each child keeps the inherited env (never closed), opens its own env (MDB_NOTLS|MDB_NOSYNC, 126
readers, as `konserve.jolt.lmdb-ffi/open-env` does), runs one read txn over every key, optionally runs
`mp_probe` (linked from native/probe.c), and then stays alive.

| N | M | probe | all ready | open avg / max (ms) | probe avg / max (ms) |
|---|---|---|---|---|---|
| 10 / 30 / 100 | 1 or 64 | off | 3 / 7 / 20 ms at **both** sizes | 0.07-0.10 / ≤0.24 | – |
| 10 | 1 | on | 17 ms | 1.1 / 4.2 | 3.6 / 6.1 |
| 10 | 64 | on | 1,991 ms | 274 / 1,372 | 890 / 1,377 |
| 30 | 1 | on | 47 ms | 1.4 / 3.9 | 3.4 / 4.6 |
| 30 | 64 | on | **5,981 ms** | 292 / 1,196 | 773 / 1,199 |
| 100 | 1 | on | 151 ms | 1.7 / 6.3 | 3.7 / 6.4 |
| 100 | 64 | on | **19,181 ms** | 326 / 1,340 | 735 / 1,345 |
| 30 | 64 | fixed | 42 ms | 0.17 / 0.35 | 6.8 / 13.6 |
| 100 | 64 | fixed | 89 ms | 0.11 / 0.21 | 4.3 / 8.3 |

The C reproduction gives the same numbers as the real system (6.0 s and 19.2 s against 5.9 s and 18.7 s).
Without the probe, map size changes nothing. The reader table never had more than 8 slots in use out of 126.

### 3. What the probe costs alone (target/lane-lmdb/probe1.c, one process)

A shared map of data.mdb, probed: 1 GiB 3.2 ms, 4 GiB 6.8 ms, 16 GiB 24.8 ms, 64 GiB 98 ms, two 64 GiB maps
195 ms. That is linear, about 1.5 ms per GiB of map, or 23 ns per 16 KiB page. An anonymous 64 GiB map takes
2.0 ms: it has no VM object, so the walk returns early (vm_map.c:15972-15978).

Serialization: 10 processes each probing a 64 GiB map of the **same** file take 943-989 ms in total
(≈ 10 × 95 ms). 10 processes probing maps of 10 **different** files with resident pages take 140 ms in total,
so they run in parallel. The serial resource is therefore the shared file's VM object lock.

### 4. The fixed probe returns the same numbers (target/lane-lmdb/cmp.c)

Old and fixed `mp_probe` were run in one forked process holding 2 × 64 GiB maps. `:snp` and `:prv-res` came out
identical in every class, and those are the only fields `real-mb` sums (lib.clj:86-88). The old probe took 220 ms
and the fixed one 1.9 ms. The only change: the file map's `:res`/`:oth-res` (11.8 MB of the shared page cache)
is no longer counted in `:anon0`. Nothing in src reads `:res` or `:oth-res`.

## The store open itself: what each step costs, from source and measured

- `mdb_env_open` (mdb.c:6267). It opens lock.mdb (mdb_env_setup_locks, mdb.c:5957). It takes an fcntl F_SETLK
  write lock on byte 0, which fails while others are open, and then waits on an F_SETLKW read lock
  (mdb_env_excl_lock, mdb.c:5819-5856). It maps the reader table, about 8 KiB at 126 readers (mdb.c:6006-6032),
  and checks the SysV semaphore set with two `semctl` calls (robust build: MDB_USE_ROBUST on Apple selects
  MDB_USE_SYSV_SEM, mdb.c:192-198; sem ops mdb.c:452-484). It opens data.mdb RDWR (mdb.c:6374) and preads both
  meta pages (mdb_env_read_header, mdb.c:4752). It mmaps the **whole map size** PROT_READ MAP_SHARED with no
  ftruncate, since there is no WRITEMAP (mdb_env_map, mdb.c:5149-5157). It opens data.mdb a third time, O_DSYNC,
  for meta writes (mdb.c:6401). These are about 3 open() calls, 2 preads, 2 mmaps and 2 fcntl calls: tens of µs
  uncontended. Measured: 0.07-0.10 ms at N=100 at both map sizes.
- mmap size costs nothing at open or fork: forkbench gave 0.8 ms at either size. Only what walks the map's pages
  (the probe) pays for it.
- Reader slots with MDB_NOTLS. Each read txn takes the reader mutex (one SysV semop with SEM_UNDO), scans the
  table for a free slot, and publishes it (mdb_txn_renew0, mdb.c:3320-3353). The first read txn of an env also
  sets an fcntl pid lock at offset pid (mdb_reader_pid, mdb.c:3258-3290). Abort releases the slot
  (MDB_END_SLOT, mdb.c:3735-3737, 3854). So slots are held only during a txn. Measured use was at most 8 of 126,
  which is not a limit.
- Fork and the inherited env. lmdb.h:102 says not to use an env after fork(). `after-fork!` drops it from
  `konserve.jolt.lmdb/envs` without closing it (db.clj:298), and that is right. `mdb_env_close` in the child
  would close lock.mdb, and POSIX then drops every fcntl lock this process holds on the file, including the new
  env's locks if they were already taken (lmdb.h:104-106). With excl 0 it also tries the exclusive lock and, if
  it got it, would IPC_RMID the shared semaphore set (mdb.c:6565-6573). Keep "forget, don't close". The inherited
  mapping's only cost is the probe walk, and the fix removes that.
- Datahike connect reads the branch's head and root nodes through `konserve.jolt.lmdb/-create-blob`, one short
  read txn each (lmdb.jolt:113-114). The earlier Jolt measurement: connect 2.4 ms at both sizes.

## The fix, at its cause

1. **native/probe.c `mp_probe`: do not page-walk shared file maps** (prototype in target/lane-lmdb/probe_fix.c).
   For each region, first call `mach_vm_region_recurse` with `VM_REGION_SUBMAP_SHORT_INFO_COUNT_64`. That is
   O(1): `look_for_pages = FALSE` (vm_map.c recurse, "original_count < VM_REGION_SUBMAP_INFO_V0_COUNT_64"), and
   it gives share_mode, user_tag, external_pager and shadow_depth. Call `VM_REGION_EXTENDED_INFO` only when the
   region is not (SM_SHARED or SM_TRUESHARED, external pager, no shadow). Such a region adds nothing to
   `pages_shared_now_private` (which needs a shadow, vm_map.c:16179) or to private resident pages. Expected and
   measured: the probe takes about 2 ms whatever the map size, and 100 agents start in 0.65 s at 64 GiB. Net src:
   about +8 lines of C. Proof to write: a deftest that maps a 64 GiB file region in a forked child and asserts
   `(real-mb)` is equal with and without it and takes under 10 ms (a cost proof that does not grow with the map).
2. Same owner: fix the docstring of `seon.agent/mem-mb` (agent.clj:434), and wherever the probe's cost is cited.
   Other callers of real-mb in processes that map the store also pay 100-200 ms per call today and hold the shared
   object lock while they do: fork_server.clj:66 (fork-server status), check.clj:480/826/835 and e2e.clj:94 (root).
   The fix covers them too.
3. Map-size policy can stay at 64 GiB. With (1) in place the map is only address space, which was the intent of
   6739d89. No change is needed to the LMDB usage, the konserve binding, reader slots (126 is plenty) or
   after-fork! handling.

## Other findings, to file as tasks (not fixed here)

- **912 SysV semaphore sets have leaked** (`ipcs -s | grep -c '^s '`). Each robust-LMDB store gets one
  (mdb.c:6085-6094), and it is removed only when the last process closes the env holding the exclusive lock
  (mdb.c:6565-6573). Stores that are deleted (scale/check runs, worktrees) leave theirs behind. The set is keyed
  `ftok(lock.mdb,'M')` (mdb.c:6086), which keeps only 16 bits of the inode. With about 900 stale keys, a new
  store has roughly a 1.4% chance of reusing a key. If it does, its first open runs `semctl SETALL {1,1}` on a
  set that may belong to another **live** store (mdb.c:6091), which would break that store's writer lock. Cause:
  mdb.c:6086 plus no cleanup on store deletion. Fix candidates: remove the set (`semctl IPC_RMID`, via an
  lmdb-ffi helper that reads `mti_semid`) when a store directory is deleted; or have bin/root/check clean sets
  whose lock file no longer exists. Needs root's go: it touches the LMDB build and store lifecycle.

## Profiling the store layer: standing tooling (recommendation)

Goal: see per process what the store layer does and how long it waits, without one-off scripts.

1. **Count and time every LMDB call per process, in konserve.jolt.lmdb-ffi.** Wrap the binding's `defcfn`s
   (env-open, txn-begin rd/wr, commit, abort, get, put, del, cursor-get, env-sync) once, at definition, with the
   mechanism `seon.profile` already uses: a Chez vector cell per op holding exact count, sampled ns (1 in 64,
   first call always), max ns and bytes moved (src/seon/profile.clj header). Expose
   `(konserve.jolt.lmdb-ffi/stats)` → `{op {:n :ns-est :max-ns :bytes}}` plus `mdb_env_info` (`me_numreaders`,
   `me_last_txnid`, map size). Read it per agent through the door (`seon_eval <agent> (konserve.jolt.lmdb-ffi/stats)`).
   Root can collect it with `:stats` (agent.clj:466) and render it as a dashboard tile: a render on the value, not
   a log. Cost: about 1 ns per call (profile.clj measured 0.9 ns). This extends seon.profile; no new mechanism.
2. **The reader table and lock state, live.** Build `mdb_stat` against the same robust liblmdb in `bin/build lmdb`
   (target/mdb_stat, one more `cc` line). A POSIX-sem build of mdb_stat would refuse the lock-file format.
   `mdb_stat -e -r <store>` prints map size, pages used, last txnid, readers used/max and every reader slot
   (pid, thread, txnid). Stale slots and long readers show here (verified on target/lane-lmdb/store). Write
   lock state: `ipcs -s -t` shows the env's semaphore set and its last op time. Bind `mdb_reader_list` (callback)
   or read the reader table through lmdb-ffi so root can render it as a tile, and record a stuck or long reader
   as a problem fact, not a poll (§2 pillars): reader_check runs when an agent dies (its exit is a fact).
3. **LMDB's own trace (MDB_TRACE).** It exists only in an `-DMDB_DEBUG=1` build (mdb.c:620-647): every API call
   with pid, args and handles, written to stderr as `>pid:func: ...`. `mplay.c` replays such a log (Makefile:120).
   Use it as a separate `bin/build lmdb-trace` output (target/lib/liblmdb-trace.dylib), selected per run by
   `JOLT_LMDB_LIB` (lmdb_ffi.jolt:8-10 already reads it; under §3 laws that becomes a config key). It is too heavy
   for normal runs. Every process on a store must use the same LMDB build, so switch the whole run.
4. **macOS native profiling that needs no root and no SIP change.** Checked here:
   - `sample PID 1 -file out.txt`: works on our own processes, and liblmdb frames resolve to mdb.c:line because
     target/lib/liblmdb.dylib.dSYM exists. This is what found the open() stalls.
   - `xctrace record --template 'Time Profiler' --attach PID --time-limit 5s --output x.trace`, then
     `xctrace export --input x.trace --toc`: works without root on our own processes (verified). It gives
     kernel+user stacks across threads; `--all-processes` needs more privilege.
   - `vmmap PID` / `vmmap -summary`: works without root and shows each LMDB map and its size. It walks pages like
     the probe, so it is slow on 64 GiB maps.
   - Need root: `fs_usage` ("must be run as root", verified), `spindump`, and `dtrace` ("DTrace requires
     additional privileges"; with SIP on, the syscall/fbt providers stay restricted even with sudo; the pid
     provider on our own unsigned binaries works with sudo). These are the owner's call.
5. **Symbolizing Jolt/Chez frames shown as `???`.** Chez code objects live in anonymous heap segments, with no
   Mach-O symbols, so `sample` and xctrace print `???` for them. Native frames below them (libsystem, liblmdb,
   libprobe) do resolve. A standing way, not verified in this lane, so it should be its own task: Jolt writes a
   per-binary-generation symbol map (address range → code object name and source) by walking code objects with
   Chez's inspector (`inspect/object` on a procedure → `code` → `name`, `source`). Forked agents share the
   precompiled program copy-on-write at the same addresses, so one map per generation serves every child. Code
   compiled at the REPL needs a per-process append. A small `bin/symbolize` then rewrites `???` addresses in a
   `sample` file. Until then, `seon.profile` gives per-function counts and times, and Chez's `compile-profile`
   gives source-level counts.

## Files (scratch, target/lane-lmdb/)

agents.c / agents_fix (the reproduction), probe1.c (cost vs map size, one file vs many), probe_fix.c (the
proposed probe), libprobe.dylib (built from it), cmp.c (old vs new output), matrix.txt, agent-sample.txt,
scale.sh, mdb_stat, vm_map.c (XNU source, xnu-12377.1.9 main, for the citations; the running kernel is 27.0.0, and
its measured behaviour matches), store/ (a 6 MB copy of the earlier forkbench store).
