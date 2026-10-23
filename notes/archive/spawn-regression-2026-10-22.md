# Spawn regression (~30-40x slower agent start): cause, evidence, fix

Lane: spawn research, 2026-10-22. Worktree `~/src/seon-jolt/next-blobs` (branch `jolt/blobs`, HEAD 15e65d6), binary `target/seon` built 13:58.
Scratch (all mine): `next-blobs/target/lane-spawn/` (`run.sh`, `samp.sh`, `probebench/`, `logs/`). No source in next-blobs was edited. Nothing of mine is left running.

## Cause in one paragraph

`native/probe.c:29` (`mp_probe`, since 80797ce, 2026-10-20) walks every VM region with `mach_vm_region(..., VM_REGION_EXTENDED_INFO, ...)`. For that flavor the kernel looks up **every page of the region** (its cost grows with the region's size, not with resident pages). Commit **6739d89** (2026-10-21 22:03, `:seon.config.store/lmdb-map-bytes` 1 GiB -> 64 GiB) made LMDB's map a 64 GiB `MAP_SHARED` file mapping. Each agent holds it **twice** (the one inherited from root, which `seon.db/after-fork!` `src/seon/db.clj:290-300` forgets but does not unmap, plus its own from `konserve.jolt.lmdb/open-env!`): 128 GiB = ~8.4 M 16 KB pages walked per probe. Every agent probes at `hello` (`seon.agent/hello!` `src/seon/agent.clj:478`, via `mem-mb` `agent.clj:433-442` -> `seon.lib/real-mb` `src/seon/lib.clj:81-88`), and again on every eval reply (`src/seon/repl.clj:456`) and stats (`agent.clj:466`). All agents map the same file, so their walks run over the one shared VM object. They contend with each other, with siblings' `mdb_env_open` (the `open()`/`pread` the earlier sample showed), and with the fork server. One probe goes from 14 ms to about 1.2 s (p50), and spawn->all-ready grows linearly with N.

## Evidence (measurements I ran, N=30 unless noted)

| run | map | probe | spawn->all-ready | start-ms p50 | probe ms p50 / max |
|---|---|---|---|---|---|
| big30 | 64 GiB | shipped | 5751 ms | 2387 | - |
| small30 | 1 GiB | shipped | 249 ms | 203 | - |
| big30t | 64 GiB | shipped + timed | 5938 ms | 2275 | **1246 / 3050** (102 probes) |
| small30t | 1 GiB | shipped + timed | 212 ms | 142 | 14.4 / 30.4 (40 probes) |
| **big30skip** | 64 GiB | regions > 1 GiB not page-walked | **263 ms** | 225 | 10.8 / 18.2 |
| **big100skip** | 64 GiB, N=100 | same | **739 ms** | 617 | - |

- Only the probe library changed between big30t and big30skip (`SEON_PROBE_LIB`, `:seon.config.native/probe-lib` `resources/seon/config/default.edn:379`). Same binary, same 64 GiB map: 5.9 s -> 0.26 s. At N=100: 18-19 s -> 0.74 s, close to the 640 ms of the 2026-10-21 17:02 check.
- eval+transact p50 also recovers (seconds -> ~55 ms), because every eval reply probes too.
- `sample` of agents during a slow run (`logs/sampbig/s-4156{1,2,3}.txt`): 1505/1611, 1540/1695 and 1388/1697 samples in `mp_probe -> mach_vm_region -> mach_msg2_trap`.
- `vmmap` during a run (`logs/v-ps.txt`): root and the fork server each map `data.mdb` once (64 GiB, `7cb2000000-8cb2000000`); an agent maps it twice (`7cb2000000-9cb2000000` = 128 GiB) and also holds two `lock.mdb` mappings.
- A C bench of the walk alone (`probebench/pb.c`), one process, one map: EXTENDED walk 1.45 / 3.45 / 9.63 ms at 1 / 8 / 64 GiB, 14.6 ms at 2x64 GiB. A BASIC walk is 0.05 ms. `pbt.c`: TOP_INFO 0.06 ms at both 1 and 64 GiB, against EXTENDED 2.4 vs 19.2 ms.
- Contention, in C (`probebench/pbn.c`): 30 forked children each map the file and walk it once.
  - 1 GiB: all done in 36 ms; walks 1.5-6 ms; 30 forks took 33 ms in total.
  - 64 GiB: all done in 437 ms; walks 16-73 ms; 30 forks took 408 ms. Concurrent walks slow fork about 12x too.
  - Seon amplifies this further: 2 maps per agent, and probes on replies and stats, under nice 15.
- What was ruled out or found irrelevant:
  - The fork server's own `:fork-ms` p50 stays 0.68 ms. The 30 forks happen before any child reaches `hello`.
  - The writer: `:batch-p50-ms` 0.34.
  - LMDB open alone: 0.6 ms (earlier lane).
  - The probe's region count: ~650 regions at both sizes.
  - Open question: one `sample` of the fork server at t+1.5 s showed 1772/1777 samples in `__fork`. A standby or other fork done while the walks hold the shared object would fit pbn.c, but I did not identify which fork it was.
- The kernel mechanism (XNU `osfmk/vm/vm_map.c`, `vm_map_region_walk`): it loops page by page with `vm_map_region_look_for_page` under the object lock. That is from XNU source and was not read here. The size-proportional cost and the constant TOP_INFO cost above are measured.

## Spawn path, step by step (what each step must do, ballpark)

1. `seon.check/scale` calls `c/spawn!` for each agent (pmap); root makes a socketpair. ~0.1 ms.
2. Writer `:spawn` (`src/seon/writer.clj:159-164`): `d/branch!` writes a branch pointer (one LMDB write txn, NOSYNC, ~0.3 ms measured as batch p50), then `:fork` goes to the fork-server proc with the fd (SCM_RIGHTS).
3. Fork server `:fork` (`src/seon/fork_server.clj:52-61`): `lib/c-fork` from a single-threaded process. ~0.7 ms measured (copies the map entries, CoW).
4. Child: `seon.repl/run!` (`src/seon/repl.clj:503-511`) runs `agent/init!` (`agent.clj:541-569`):
   - `sock/clofork!`, trip and heap cap, stderr to a file: ~0.1 ms;
   - `after-fork!` drops the env and connections, then sets the lease: 3.4 ms measured (earlier lane);
   - `d/connect` the branch: open-env (mmap mapsize, `open()` of data and lock, a reader slot, one write txn to open the dbi, `lmdb_ffi.jolt:51-70`) 0.6 ms, plus Datahike's connect and root read 2.4 ms;
   - `make-ns!`, `interrupt-self-test`: ~ms.
   - Expected total ~10 ms per agent.
5. `hello!` (`agent.clj:478`): `:start-ms` from the request, then `mem-mb` -> `mp_probe`. Expected ~1.5 ms (the docstring at `agent.clj:434`), measured 14 ms at 1 GiB and **1.2 s at 64 GiB under 30-way contention**.
6. Root's agent proc `:hello` (`src/seon/agent_proc.clj:124-137`) marks the agent ready (`:forked-ms`).

The probe's cost grew with the map, which nothing measures. The docstring's "~1.5 ms" is an estimate with no cost proof behind it (AGENTS §3, "estimates are proofs").

## Fix at the cause

**`native/probe.c` `mp_probe`: replace the page-walking `VM_REGION_EXTENDED_INFO` with `VM_REGION_TOP_INFO`.** TOP_INFO costs the same per region (0.06 ms for the whole task at 1 and 64 GiB) and gives `share_mode`, `private_pages_resident` and `shared_pages_resident` from the objects' counts.
- `real-mb` (`lib.clj:81-88`) is the only reader of the probe's per-region fields (grep: nothing else reads `:snp :prv-res :dirty :swap :cow-res :oth-res`). It wants pages this process owns: compute it as the sum of `private_pages_resident` over SM_PRIVATE, SM_PRIVATE_ALIASED and SM_COW regions. For a CoW region that count is the top shadow object's pages, the copied ones, which is what `pages_shared_now_private + prv_res` approximated.
- Drop `:dirty :swap` (not read anywhere), or keep them from `task_info`.
- Proofs to write first:
  1. A cost proof: `mp_probe` at 1, 8 and 64 GiB of mapped file is flat (O(regions), not O(pages)). Red today: 1.45 -> 9.6 ms.
  2. Agreement: `real-mb` from the new probe is within a few % of the old one on a fresh agent with a 1 GiB map.
  3. `bin/check scale` at 64 GiB: spawn->all-ready <= ~1 s at N=100.
- Expected result, measured with the skip-big stand-in: N=30 ~260 ms and N=100 ~740 ms at 64 GiB, against 5.9 s and 18 s today. The map can stay 64 GiB (6739d89's reason holds: the file grows only as used).
- Secondary, not needed for the fix: `after-fork!` (`db.clj:298`) could `munmap` the inherited env's map instead of only forgetting it. That halves an agent's mapped size. `mdb_env_close` in the child must not be used: it would clear reader slots tagged with the parent's pid. This needs a konserve/lmdb_ffi "forget after fork" function (the dependency's owner: a cross-owner change, so root's go).
- Do not "fix" by shrinking the map: it brings back 6739d89's fatal-commit-error at 1 GiB.

When it was introduced: 6739d89 (2026-10-21 22:03) is the trigger. The latent cause is `native/probe.c` from 80797ce (2026-10-20). The 17:02 check that measured 640 ms ran at 1 GiB.

## Profiling this system: standing tooling (owner's added requirement)

What exists and works here today (checked):
- **`sample PID secs -file f`**: no root needed for our own processes. Native frames are named (libsystem, liblmdb, libprobe). Chez/Jolt code shows as `??? (in <unknown binary>) [0x12e1...]`. It was enough to find this regression, because the cause was in native code.
- **`xcrun xctrace record --template 'Time Profiler' --attach PID --time-limit Ns`**: verified to work without root here (Xcode installed). It records kernel and user stacks for all threads and opens in Instruments. It has the same `???` problem for Chez code.
- **dtrace and fs_usage**: unavailable. SIP is enabled, and `dtrace` says "requires additional privileges"; fs_usage needs root. Do not plan on them.
- **`vmmap -wide PID`, `vm_stat`, `ps -M`**: no root needed; good for mappings and threads.
- **`seon.profile`** (`src/seon/profile.clj`): per-contracted-function call counts, one call in 64 timed, allocated bytes, and epochs. It is in-process, Clojure-level, exact counts. It cannot see FFI/kernel time (the probe's 1.2 s would show only as time inside `seon.lib/mp-probe`, if that is a contracted function).
- **`seon.cost`** (`src/seon/cost.clj`): cost proofs, estimate first, measured at sizes, overrun = red. It would have caught this if `mp-probe` had a proof sized by mapped bytes.
- **Events**: `step/event` (`src/seon/step.clj:60`) stamps `:at-ms`. Root emits `:spawned` (`root.clj:349`, with the server's `:fork-ms`) and `:ready` (`agent_proc.clj:137`, with `:start-ms`). Everything in between is invisible.

What to add (each extends an existing mechanism; none is a new runner):
1. **A per-spawn timeline as facts on the events channel.**
   - Each process records monotonic `lib/now-ns` stamps at fixed points, in the one map that already travels: the writer's `:spawn` reply, the fork server's `:forked` event, and the agent's `hello`. Stamps: request received (root), branch made (writer), fork done (server), init steps in the child (after-fork!, open-env, connect, ns, probe), hello sent, and ready (root).
   - The child already has `t-req-ns`. Its steps go as a `:steps {k ns}` map in `hello`, so they cost one assoc each and no new message.
   - CLOCK_MONOTONIC is system-wide on macOS, so stamps from different processes compare directly.
   - Root's agent proc writes them as one `:spawn-timeline` event / fact per agent. The dashboard renders it as a waterfall (a render on its schema). The scale check prints p50/p95 per step. Each step then gets a cost estimate, and an overrun is a problem fact, not a log line.
2. **Native sampling as a check mode, not a script.**
   - `bin/check profile` (or `seon.check/profile`) runs a named workload (scale N) and samples root, the fork server and K agents with `sample`, or `xctrace` for kernel stacks.
   - It writes `target/check/<time>/profile/` and ends PROFILE OK.
   - It summarizes the top-of-stack per process into data (fn -> samples).
   - Content-keyed by binary + workload, per §3.
3. **Symbolizing Chez/Jolt frames.**
   - **A symbol map Jolt emits.** After `lib/static!` (`fork_server.clj:68`, static generation: code objects no longer move), walk the code objects and write `addr size name` lines. Primitives: `inspect/object` on a procedure gives `'code`, then `'name` and `'source`; `#%$object-address` gives the address. `sample`'s `???` addresses then resolve offline by range lookup, and the summary in item 2 names Clojure fns.
     - Code that is not static moves at every collection. For it, the map must be re-emitted after a collection, or the process sampled only after static!. Agents fork after static!, so agent code is mostly static.
     - Whether Instruments can read a perf-map-like file directly on macOS was not verified. The offline resolver is safe.
   - **Chez's own profilers.**
     - `compile-profile` with `profile-dump-html` gives source-level counts. It requires instrumented compilation, so a build flag for a profiling binary, not production.
     - An in-process sampler is possible: Chez's `timer-interrupt-handler` with `set-timer` capturing the continuation, walking frames with `inspect/object` (`'link`, `'code` -> `'name`). It names Clojure frames exactly. It is a measurement, not a decision, so it fits §2. It should be an owner-run mode of `seon.profile`, not always on.
4. **Cost proofs on the native probes.** `mp_probe`, `real-mb` and the env open get `seon.cost` proofs sized by what they touch (mapped bytes, regions), so a config change like 6739d89 turns a proof red instead of a check slower.
5. **Root's problem facts.** When a step of the spawn timeline exceeds its estimate by 3x (§1, "a ratio past 3x is a finding"), root records a problem fact naming the step. The dashboard shows it the moment a regression lands.

## Files

- Harness: `next-blobs/target/lane-spawn/run.sh` (TAG, N, BIN, --set; keeps logs), `samp.sh` (samples the run's processes).
- Probes: `probebench/probe-timed.c` / `libprobe-timed.dylib` (logs `pid t ms nregions largest-MB` to `$PROBE_LOG`), `probe-skipbig.c` / `libprobe-skipbig.dylib` (the fix's stand-in), `pb.c`, `pbn.c`, `pbt.c` (C benches).
- Logs: `logs/{big30,small30,big30t,small30t,big30skip,big100skip,big30s,big30v}.log`, `logs/probe-{big,small,skip,skip100}.txt`, `logs/sampbig/`.
