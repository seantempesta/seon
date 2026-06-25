---
type: issue
status: active
tags: [issue, flow]
---

# bin/seon supervisor — startup/teardown race audit (2026-06-25)

Read-only audit of `bin/seon` (the bash process supervisor) for races in
start / stop / restart / `cluster reset` / `nuke`. Scope: the locking,
ready-gates, and teardown→wipe→restart orderings. No code was changed.

## TL;DR

The supervisor is genuinely multi-agent-safe **for single-process
operations** (`start`/`stop`/`restart <name>`): the mkdir mutex is truly
atomic, the whole start/stop body runs under the lock, and the `start all`
ready-gates are correctly ordered (pod cannot start before the wire-server
socket is accepting). That core claim holds.

The real gaps are all in **multi-step, multi-process orchestrations**
(`cluster reset`, `nuke`, `restart all`) plus one classic PID-file hazard:

1. `cmd_stop` never confirms the process is actually dead after `kill -9`
   — it returns the instant SIGKILL is *sent*. (P1, root cause of #2/#3.)
2. `cluster reset` / `nuke` can `rm -rf` the store while a SIGKILL'd
   wire-server is still dying. (P1)
3. `nuke` / `cluster reset` hold **no lock spanning teardown→wipe→restart**,
   so a concurrent `bin/seon start wire-server` from another agent can
   interleave with the data wipe. (P1)
4. `is_running` / `_stop_unlocked` trust the PID file blindly — PID reuse
   can make `stop` SIGKILL an innocent process. (P2)

## Findings table

| # | Title | Severity | Where |
|---|-------|----------|-------|
| 1 | `cmd_stop` returns without confirming death after SIGKILL | P1 | `_stop_unlocked` 581-609 |
| 2 | Store `rm -rf` races a still-dying wire-server | P1 | `cluster_reset` 761-771 · `cmd_nuke` 838-855 |
| 3 | `nuke`/`cluster reset` hold no lock across teardown→wipe→restart | P1 | `cmd_nuke` 834-866 · `cluster_reset` 759-789 |
| 4 | PID-file trusted blindly — reuse can kill an innocent process | P2 | `is_running` 529-538 · `_stop_unlocked` 590-608 |
| 5 | Synchronous cold prep holds the per-process lock for minutes | P2 | `_start_unlocked`→`maybe_prep_deps` 566 |
| 6 | owner-file write window after `mkdir` lock | P2 | `acquire_lock` 482-503 |
| 7 | `stop` during the pod's `css:build` window can orphan npm child | P2 | `process_command` pod 178 |
| 8 | `restart all` is per-process atomic, not stack-atomic (documented) | P2 | `cmd_restart` 665 · `start_all`/`stop_all` |

## What is already correct (verified, not manufactured)

- **mkdir mutex is truly atomic.** `mkdir "$lock"` (482) is the
  create-or-fail primitive; on POSIX/macOS it's atomic and needs no flock.
  Lock state observed clean right now (no stale `lock/` dirs under
  `tmp/proc/*`).
- **No TOCTOU between `is_running` and spawn within one invocation.** The
  entire `_start_unlocked` body (is_running check → prep → nohup spawn →
  pid-file write, 553-578) runs while the per-process lock is held
  (`cmd_start` 646-648). Two concurrent `start pod` cannot both spawn.
- **`restart <name>` is atomic.** One lock spans stop+start (666-669); the
  `_unlocked` worker split exists precisely so restart doesn't self-deadlock.
  Other agents see "stopped" or "started", never an exploitable gap.
- **`start all` ready-gates are correctly ordered.** `start_all` (611-622)
  walks `cljs-watch → wire-server → pod` and calls `wait_ready` on each
  before the next. The pod (which `exec`s `out/client/main.js` and
  fail-loud-pings the wire socket) therefore starts only after (a)
  cljs-watch produced the build and (b) the wire-server socket is
  **actually accepting** — `ready_check wire-server` (224-236) does a real
  `nc -U` connect (not `nc -z`, which the comment correctly notes is broken
  on macOS), gated behind socket-exists + repl-port-file. The owner's
  specific worry ("can the pod start before the wire-server socket is
  accepting?") is answered: **no**, in the `all`/`cluster reset` paths.
- **Stale-lock reclaim works for the common case.** A SIGKILL'd `bin/seon`
  leaves `lock/owner`; the next acquirer reads it, `kill -0`s the dead PID,
  and reclaims (493-497). The ownerless-lock-after-0.5s branch (498-502)
  covers a lock abandoned mid-init.
- **Socket cleanup order is right** in `cluster reset`: stop wire-server
  *then* `rm -f` the UDS sockets (764-766) so the fresh bind gets a clean
  path. `jvm` exclusion from `all` is a deliberate, well-documented policy
  (header 16-27), not a bug.
- **`exec` discipline** keeps the recorded PID equal to the real long-lived
  process for pod (compound cmd → `exec node`) and the single-command
  wire-server/cljs-watch (bash-`-c` exec-optimizes to java). Stop targets
  the right PID in steady state.

## Per-finding detail

### 1 — `cmd_stop` returns before confirming the process is dead (P1)

`_stop_unlocked` (581-609): SIGTERM, poll `kill -0` for ≤2.5s, then if
still alive `kill -9` **and immediately `rm -f pid_file` and return**.
There is no post-SIGKILL wait. `kill -9` only *delivers* the signal; the
kernel may not have reaped the process (or released its file handles / LMDB
locks) by the time `cmd_stop` returns.

Race window: any process that ignores SIGTERM for the full 2.5s (a busy
wire-server mid-compaction, a JVM in GC) gets SIGKILL'd and the caller
proceeds as if it's gone while it is still tearing down.

Severity P1: by itself it's a "stopped too early" lie; it becomes the
enabling condition for #2 (store wipe) and #3.

Fix direction: after `kill -9`, loop `kill -0 "$pid"` until it fails (bounded,
e.g. ≤3s) before returning — `_stop_unlocked` should not return until the
PID is confirmed gone. Single fix; closes the window for restart, cluster
reset, and nuke at once.

### 2 — Store `rm -rf` races a still-dying wire-server (P1)

`cluster_reset` (761-771): `cmd_stop pod` → `cmd_stop wire-server` →
`rm -f` sockets → `rm -rf "$store_dir"`. `cmd_nuke` (838-855): stop loop →
`rm -rf data/clusters/*`. Because of #1, `cmd_stop wire-server` can return
while a SIGKILL'd writer is still alive and possibly mid-flush to the LMDB
store. The subsequent `rm -rf` then races that dying writer.

Race window: SIGTERM-ignoring wire-server → SIGKILL sent → `cmd_stop`
returns → `rm -rf store` begins → dying writer's last `fsync`/file-create
lands in a half-deleted directory.

Consequence: usually self-healing (the fresh wire-server mints a new store),
but can leave orphan files in the store dir or, worst case, interleave
`rm` with a writer file-create. It directly violates the owner's
"are processes fully DOWN before the data wipe?" requirement. **They are
not guaranteed to be.**

Severity P1 (can leave a dirty store dir / undermines the clean-slate
guarantee; not reliably corrupting because the dir is being deleted anyway).

Fix direction: fix #1 (confirm death), and additionally fence the store:
only `rm -rf` after every writer PID is confirmed dead. Optionally verify
the UDS socket is no longer accepting before the wipe.

### 3 — No lock spans teardown→wipe→restart (P1)

The locks are **per-process and held only for the duration of one
`cmd_start`/`cmd_stop`**. `cmd_nuke` (834-866) and `cluster_reset` (759-789)
are sequences of individually-locked `cmd_*` calls with **unlocked work
(the `rm -rf` wipe, the socket cleanup) in between, and no lock held across
the whole sequence**. A second agent running `bin/seon start wire-server`
(or another `cluster reset`, or `nuke`) can interleave:

Race window: nuke finishes its stop loop → begins `rm -rf data/clusters/*`
→ concurrently agent B's `start wire-server` acquires the wire-server lock
(nuke isn't holding it) and spawns a writer that creates the store dir
while nuke is deleting it. Result: a wire-server pointed at a
partially-deleted store, or nuke's later `start_all` no-ops because B's
instance is "already running" on top of a half-wiped store.

Severity P1: genuine cross-invocation corruption/wedge window, gated only
by how often two agents touch the stack at once (the supervisor's whole
premise is that they do).

Fix direction: introduce a coarse "stack" / "global" lock that
`nuke`, `cluster reset`, and `restart all` acquire for their entire
duration (in addition to, or instead of, the per-process locks), so no
other invocation can start a competing process during a multi-step
orchestration. The per-process locks remain for simple ops.

### 4 — PID file trusted blindly; reuse can kill an innocent (P2)

`is_running` (529-538) and `_stop_unlocked` (590-608) act on the recorded
PID with no cross-check against the recorded `cmd` (the `cmd_file` is
written but never validated). If the managed process died (e.g. `kill -9`
from outside, OOM) and the OS reused its PID for an unrelated process:

- `is_running` returns true (false positive) → `start` no-ops a dead stack;
- worse, `_stop_unlocked` SIGTERM/SIGKILLs whatever now owns that PID.

Race window: process death → PID recycle (heavy box) → next
`stop`/`restart`. Low probability, high blast radius.

Severity P2 (rare trigger, but can kill an unrelated process).

Fix direction: verify the live PID's command matches the recorded `cmd`
(e.g. `ps -p "$pid" -o command=` contains a stable token) before treating
it as managed / before killing it.

### 5 — Cold prep holds the per-process lock for minutes (P2)

`_start_unlocked` calls `maybe_prep_deps` (566) **while holding the lock**;
a fresh git-dep prep "can take minutes" (header 298-300). Meanwhile a
concurrent `start wire-server` from another agent spins in `acquire_lock`
and **aborts after only 10s** (`tries > 100`, 505-510) with "could not
acquire lock". Correctness is preserved (no double start), but the second
agent gets a hard failure instead of waiting for prep to finish.

Severity P2 (UX/flakiness, not corruption).

Fix direction: either run prep before taking the lock, or make the 10s
acquire timeout aware that the holder is mid-prep (longer bound / clearer
message pointing at `logs/<name>.log`).

### 6 — owner-file write window after `mkdir` (P2)

In `acquire_lock` (482-487) the lock dir is created atomically, but
`echo "$$" > "$lock/owner"` happens a few instructions later. A competitor
that finds the lock present but ownerless waits `tries > 5` (~0.5s) then
reclaims it (498-502). If the original holder is descheduled for >0.5s
between `mkdir` and the `echo` (very heavy load), the competitor could
reclaim a lock that is actually live → two holders.

Severity P2 (needs a >0.5s stall in a 2-line window; very unlikely).

Fix direction: write the owner file as part of lock creation, e.g. create a
temp dir with the owner file inside, then `mv`/`mkdir` atomically; or widen
the ownerless-reclaim grace well beyond any plausible scheduling stall.

### 7 — `stop` during the pod's `css:build` can orphan npm (P2)

The pod command is `npm run css:build && exec ... node` (178). Only after
the css build does `exec` replace bash with node. During the build window
the recorded PID is the `bash -c` running npm; a `stop`/`restart` then
SIGTERMs bash, and the npm/node-sass child can be orphaned (and `is_running`
later sees the PID gone but the orphan lingers).

Severity P2 (narrow window, cosmetic — orphan exits on its own).

Fix direction: kill the process group (`kill -- -$pid` with `setsid`/`set
-m`) rather than the single PID, or move css:build out of the supervised
command.

### 8 — `restart all` is per-process atomic, not stack-atomic (P2, documented)

`cmd_restart all` → `stop_all; start_all` (665), each a sequence of
per-process-locked `cmd_*`. Two concurrent `restart all` invocations can
interleave (A stops pod, B starts pod, …). The header (663) and code
comments already call this out. Same root cause as #3; the coarse stack
lock proposed there fixes both.

Severity P2 (documented, self-correcting in practice).

## Recommended fix priority

1. **#1** — add a post-SIGKILL confirm-dead loop to `_stop_unlocked`. One
   small change, removes the enabling condition for the two store-wipe P1s.
2. **#3** — coarse stack/global lock around `nuke` / `cluster reset` /
   `restart all`. Closes the cross-invocation wipe-vs-start window (#3) and
   the `restart all` interleave (#8).
3. **#2** — with #1 + #3 in place, the store `rm -rf` is fenced; optionally
   add an explicit "writer dead + socket not accepting" assert before the
   wipe.
4. #4–#7 as hardening.

Note: `nuke` deliberately stops the shared `jvm` nREPL (838) — per its
own contract (`--yes`, header 836-837) but it WILL sever every other
agent's live REPL session. Not a race, but worth the loud callout the script
already gives.
