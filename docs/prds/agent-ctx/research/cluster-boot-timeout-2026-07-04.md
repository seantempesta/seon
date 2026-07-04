---
type: research
status: active
tags: [research, agent, database]
---

# Cluster boot-timeout regression — root-cause (2026-07-04)

## TL;DR

The armD-thinking run's `cluster_boot_timeout` on
`long_term_planning-seed1-008` (679.8s sample, terminal event: "pod not ready
within 60s, port file exists=False") is **NOT a monotonic boot-time
degradation** and **NOT disk / leaked-cluster-dir exhaustion**. Two distinct
findings:

1. **FIXED — a real FD leak** in `seon.server.broadcast`: the pub-socket accept
   loop retained only the `OutputStream`, never the `SocketChannel`, so when a
   dead subscriber (an ephemeral pod that died on cluster-destroy / pod-restart)
   was dropped from `socket-subscribers`, its underlying socket FD was **never
   `.close`d** — leaked until GC finalization. Measured live at **+1–2 FDs per
   create/destroy (and per pod-restart) cycle**. This IS a genuine long-run
   accretion vector. It is GC-masked at rest (~200 FDs against a 10240 cap), so
   it is **almost certainly NOT the sole cause** of seed1-008 in a ~50-boot run,
   but it is the one clearly-in-scope, one-mechanism bug and is now fixed +
   live-proven (net-zero FD leak).

2. **The acute 60s timeout is a tail-latency boot stall on the UNGUARDED
   `restart_pod` path**, in a run already degrading toward the remote DeepSeek
   API limit (the run "died on API limit" per the evidence commit; the model is
   remote `deepseek-v4-pro`, provider `deepseek` — the machine is mostly idle
   waiting on thinking-mode latency, so CPU contention is NOT the amplifier).
   Boot is **stable ~14s in isolation** and does not monotonically degrade; the
   planning-row sample-time climb (177→223→388→495→541s) is dominated by
   thinking-mode LLM latency variance, not boot. The one true boot event
   (seed1-008) landed on `restart_pod`, which — unlike `create` — has **no
   supervisor-side ready gate**: `bin/seon restart pod-<n>` returns as soon as
   the process spawns (`_start_unlocked` does not `wait_ready`), so the entire
   re-boot must fit inside the harness's tight `CLUSTER_BOOT_BUDGET_S = 60`,
   with zero absorption for a transient stall that `create`'s internal 120s
   gate would have swallowed.

**Can the planning-thinking row be re-run?** Yes — with the caveats in
"Recommendations". The FD-leak fix removes one long-run vector; the residual
risk is the tight/asymmetric restart budget, which is harness config (flagged,
not band-aided).

## Evidence (from the committed run, 54352fec)

Planning row, serial samples (`executions.jsonl`), total sample time:

| sample | status | s | note |
|---|---|---|---|
| seed1-000 | pass | 177.8 | |
| seed1-001 | fail | 223.5 | |
| seed1-002 | harness_error | 40.3 | "Remote end closed connection" (pod HTTP drop) |
| seed1-003 | fail | 388.0 | |
| seed1-004 | harness_error | 495.7 | "Remote end closed connection" |
| seed1-005 | pass | 541.3 | |
| seed1-006 | pass | 304.7 | |
| seed1-007 | run_error | 254.1 | |
| seed1-008 | **cluster_boot_timeout** | 679.8 | "pod not ready within 60s … exists=False" |
| seed1-009 | fail | 560.1 | |

Times are NOT monotonic (006/007 dip). "Remote end closed connection"
harness_errors are pod-side HTTP drops consistent with remote-API degradation.
Only 008 is a boot event.

## Reproduction (this session, live default wire-server, pid 85885)

Wire-server at rest: 1 db registered, RSS 590MB, `-Xmx2g` G1GC, JVM open FDs
**200 / 10240** cap. (An early `lsof | grep unix` showed 570 "unix" rows — a
macOS lsof per-thread duplication artifact; unique numeric FDs = 199, matching
the JVM's authoritative `OperatingSystemMXBean` count. There is **no 570-FD
leak at rest**.)

### Create/destroy loop (8 iterations)

| iter | boot_s | rss_MB | jvm_fd | reg/subs | destroy_s |
|---|---|---|---|---|---|
| 1 | 14.5 | 746 | 201 | 2/2 | 0.8 |
| 2 | 14.4 | 1138 | 202 | 2/2 | 0.8 |
| 3 | 14.4 | 1245 | 203 | 2/2 | 0.7 |
| 4 | 14.4 | 1268 | 204 | 2/2 | 0.7 |
| 5 | 13.4 | 1232 | 205 | 2/2 | 0.8 |
| 6 | 13.4 | 1234 | 206 | 2/2 | 0.8 |
| 7 | 13.4 | 1244 | 207 | 2/2 | 0.8 |
| 8 | 14.4 | 1220 | 208 | 2/2 | 0.8 |

Reads: **boot time flat ~14s** (no accretion); **registry does NOT accrete**
(`delete-db!` releases the conn — 2/2 during-create, back to 1 after destroy);
**RSS climbs then plateaus** ~1.2GB (GC-bounded under Xmx2g, not a true leak);
**JVM FD climbs +1/iter monotonically** (201→208) — the leak.

### Restart-pod loop (re-attach to an already-seeded db, 5 restarts)

boot = 10.7, 10.7, 15.3, 13.2, 11.7 s — all well under 60s, RSS steady ~1210MB,
FD +1–2 per restart (210→215). Re-attach boot is not materially slower than a
fresh create.

### Under crude CPU saturation (18 `yes` loops, 18 cores)

One create boot: 14s → **18s** (only ~1.3×). Pure CPU contention is a weak
amplifier — consistent with the real run being remote-LLM-bound (idle machine),
so contention is not the mechanism.

### FD-leak fix — live proof (standalone JVM, fixed code)

20 subscribers connected then peer-closed (20 simulated dying pods):
`baseline=195 → after-connect+peerclose=215 (+20 leaked) → after one broadcast
detects them = 195 → reclaimed=20, net-vs-baseline=0`. The **old** code (still
running in the live wire-server, and what the +1/iter loop above measured)
reclaims 0 → net +20. Fix reclaims every dead subscriber's FD on the next
broadcast.

## The fix (made this session)

`src/seon/server/broadcast.clj` — `socket-subscribers` now holds
`{::ch <SocketChannel> ::out <OutputStream>}` entries instead of bare
`OutputStream`s. `broadcast!` iterates `::out` and, when a write fails, drops
the entry AND `.close`s its `::ch` (via `close-subscriber!`). `start-pub-server!`
stores the channel alongside the stream. The set is private and only touched
inside this ns (the test collector `client/start-pub-collector!` opens its own
client-side connection), so no consumer changed.

Validated: `bin/test seon.server.broadcast-routing-test
seon.server.protocol-integration-test` → 16 tests, 87 assertions, 0 failures
(the protocol-integration suite exercises the pub-socket broadcast path). Plus
the standalone FD proof above.

The dev hook reloaded + bounced the default stack after the `broadcast.clj`
edit, so the live wire-server now runs the fixed code (verified via the REPL:
`close-subscriber!` resolves and `socket-subscribers` entries are maps; the
default pod reconnected and answers on 7890). No manual hot-patch.

## Ranked residual suspects for the acute seed1-008 stall (undecided)

Boot is stable ~14s in isolation on an idle machine, so a single >60s stall is a
**tail event**, not steady degradation. Ranked:

1. **Tight + asymmetric restart budget (highest).** `restart_pod` (planning.py
   L267–268) calls `bin/seon restart pod-<n>` (no internal `wait_ready`) then
   `wait_pod_ready(60s)`. `create_cluster` gets bin/seon's internal 120s pod
   gate FIRST, so a transient stall is absorbed there; a restart has only the
   60s harness budget — ~4× headroom over a 14s baseline and zero slack. A
   single GC pause / slow ensure-db round-trip on the restart re-boot tips it
   over. This is **harness config** — flagged, not band-aided (bumping the
   budget is a stopgap, not the fix; see below).

2. **G1 pause under RSS pressure.** RSS climbs toward the 2GB Xmx over a long
   run (plateaued at ~1.2GB in 8 iters; a 90-min ~50-boot run pushes higher). A
   full GC during a restart pod's boot round-trips (ensure-db + agent re-arm
   reads through the wire-server) could add seconds — enough to matter only
   because of suspect #1's zero slack.

3. **FD leak (fixed, but slow).** +1–2 FD/cycle is far too slow to exhaust
   10240 in ~50 boots; GC-masked at rest. Real, worth fixing, but not the acute
   cause. Now removed.

## Recommendations

- **Keep the FD fix** (done) — removes a genuine long-run accretion vector.
- **Give `restart_pod` a supervisor-side ready gate OR a bounded retry**, so a
  single transient boot stall does not hard-fail a sample. Cleanest: have
  `bin/seon restart pod-<n>` `wait_ready` internally (parity with `create`), or
  wrap the harness `wait_pod_ready` in a 1-retry. This is the eval-lane /
  tooling boundary — coordinate before changing `bin/seon`'s restart semantics
  or `src-inspect-ai`.
- **Do NOT merely raise `CLUSTER_BOOT_BUDGET_S`** as the "fix" — it is a
  documented stopgap only. If used as a bridge to re-run the planning-thinking
  row before the gate/retry lands, set it explicitly and note it.
- **Re-running the planning-thinking row is safe** once the wire-server is
  restarted (picks up the FD fix) and either the restart gate/retry lands or the
  budget is bumped as a labeled stopgap. The row's other failures in the
  original run were remote-API degradation, independent of boot.

## Smells surfaced

- **Complexity artifact (accepted, pre-existing):** `restart_pod` re-boots the
  pod with NO internal ready gate while `create` has one — an asymmetry that
  makes the tight 60s the sole gate on the fragile path. Flagged above.
- The dead-peer detection in `broadcast!` only fires on the *next* broadcast
  write-failure; an idle db never prunes. Harmless here (socket subscribers see
  every cluster's txs, so the default cluster's traffic prunes promptly), but
  worth knowing — the fix closes the FD once detected, it doesn't make detection
  eager.

## Files changed

- `src/seon/server/broadcast.clj` — FD-leak fix (channel retained + closed on
  drop). NOT committed (per task).
