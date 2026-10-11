---
title: "Plan (Opus): the one path, starting an agent's Flow from a point in time"
type: research
status: independent plan for Fable's hybrid; design only, no code
basis: HEAD 2bf3ee3bb read by symbol; JVM probes on default (loaded commit with W1, S2, R4), 2026-10-11, on disposable
  branches forked from main's head and retired in the same form; Astra's start-a-flow-benchmark.md read, not its runs
---

# The one path: from a commit and a task to a running (or paused) agent Flow

## 1. The design in one paragraph

`db<->flow` writes the attempt (task, agent, branch, intent, **fork-point commit id**) and the agent row in one main
transaction, then calls one function, `seon.flow/start-flow!`, with that row. `start-flow!` composes five owned parts
and nothing else: (1) **branch** = `datahike.api/branch!` from the fork-point commit id, a pointer and nothing more (no
branch transaction: the agent row and attempt are already on main, so the fork inherits them); (2) **world** =
`seon.turn.world/world` over the branch connection; (3) **environment** = ONE `sci/fork` of the cached program
source, found by the program's *content* identity, which a fresh fork shares with its parent because the commit it
opens at resolves to its parent's revisions; (4) **context** = `seon.context/current-state` over the task, whose
parts (task basis, proof reach, contract references) are `seon.cache` hits for the same reason; (5) **graph** =
`flow/create-flow` + `flow/start` on its own executor, and `flow/resume` only when the intent is `:running` (Flow
starts paused). A restart is the same call with `branch!` answering `created? false`; a test run is the same call with
the graph omitted and the fork point the agent's current commit; root's and problem tasks are the same call on main.
The one correctness-and-cost fix underneath everything: **a reopened commit must know which commit its program
equals** — today it does not, so every fresh fork misses every content-keyed cache (measured below), duplicates the
SCI program in `seon.cache` and evicts the reach entries the next agent needs.

## 2. Data per part: where it lives, its key, measured now vs floor

Probes ran in default's JVM (`seon.profile/measure`, warm JVM, fresh committed fork of main's head per row; allocation
is thread allocation). "Floor" is what the part must do: its reads, writes and object construction.

| Part | Data it needs, where it lives | Cache key | Measured now | Floor and why |
|---|---|---|---|---|
| main write (attempt + agent rows) | task, roster facts; Datahike main | none (a write) | raw `d/transact` 12–14 ms; `seon.db/transact!` 15–25 ms | ~13 ms: one durable Datahike commit (fsync-bound); Seon's writer adds ~5–10 ms of validation |
| branch | fork-point commit id; Datahike branch roster | identity: branch name derived from attempt | `fork!` 40–44 ms (raw `d/branch!` 17 ms + fork-entity tx ~25 ms) | **17 ms**: the pointer write is Datahike's; the fork-entity transaction is removable (P6) |
| connection | branch head | — | `open-branch!` 1.3 ms | ~1 ms |
| commit → program identity | revisions per attribute; process memory today | commit id | **fresh fork: all 171 acquisition attributes differ from main's** (fork-head commit never remembered) | a lookup: the fork head resolves to its parent's revisions (P1) |
| world | identity, custody, environment; values in hand | none (construction) | 0.16 ms, <0.1 MB, 7 keys | same |
| SCI environment | cluster base + interpreted overlay; `seon.cache` `::base-context`, `::overlay-context` | `[store loaded-commit]`; `[base-key program-basis dials]` | `base-ctx` on fresh fork 24–34 ms / 28–44 MB (overlay-plan + overlay-ctx **miss**); then `fork-cluster-ctx` 7.3 ms, `acquire!` 0.9 ms, `fork-for-turn` 13–17 ms / 10–13 MB (5 queries, 12 ms, for an agent with no history) ≈ **50–60 ms, ~60 MB, three forks** | **~1 ms, <1 MB**: one `sci/fork` of a cached source + custody; `base-ctx` on main measured 1.0 ms / 1 MB |
| overlay cache | — | as above | `::overlay-context` 314 MB = 62% of the 512 MB budget, 9 misses / 1 hit; `::overlay-plan` 0/9 hits | a fork with no program change holds **no** overlay entry; changed programs keyed by the plan's content (P3) |
| first context: proofs (reach) | task's proofs, program call graph; Datahike + `seon.cache` `::reach-graph`, `::reach-attribute` | `[content-revisions tests]` | fresh fork 124 ms / 138 MB, 28,876 calls; **same fork with its head remembered: 9.8 ms / 4 MB**; main cold (cache full, evicted) 1,325 ms / 2.7 GB for one proof | 2–5 ms on a hit; a true miss proportional to the reached graph (CACHE-1/2, lane astra-cache-1-2) |
| graph | blueprint (procs, conns), executor | none (live object in the roster) | `arm!` 14 ms / 15 MB (R1 doc); Astra measuring paused start | ~2–5 ms + one platform thread per proc (2 procs after D2) |
| **start, attempt row to first context** | | | **≈ 250 ms, ≈ 210 MB** (R1's sum; this probe's legs agree) | **≈ 40–55 ms, < 20 MB**, of which ~30 ms is two durable writes (main commit + branch pointer) |

The finding behind the table (exact form, 2026-10-11): `registry/branch!` from main's commit, `store/open-branch!`,
write the fork entity, release, reopen. `seon.db/content-revisions` over program attributes equals main's at open
(`true`) and right after the write (`true`), but **not after reopening** (`false`); after
`(seon.db/remember-revisions! (:db-after tx))` the reopen equals main's again (`true`). Cause: Datahike opens a
connection with its head commit as the conservative revision (`datahike.connector/connect`; its stored commits keep
no revision map, `datahike.versioning/attached-cache-context`), and Seon remembers the source commit
(`seon.cluster.registry/branch!`) but never the branch's own fork-entity commit, so the chain
`seon.db/content-revision` follows stops at the unknown head. `seon.sci.eval/program-identity` is a second,
independent copy of the same idea (a `clojure.core.cache` LRU outside `seon.cache`, fed by a `d/listen` on main only)
with the same gap.

## 3. Reuse, change, delete (by symbol)

**Reused as they are.** `datahike.api/branch!`, `commit-as-db`, `delete-branch!`; `seon.cluster.store/open-branch!`;
`seon.turn.world/world`; `seon.sci.eval/base-ctx` (its lookups) and `copy-base-ctx`; `seon.cache/lookup`;
`seon.db/content-revisions`, `remember-revisions!`; `seon.context/current-state`; `seon.cluster.agent/graph-definition`
(trimmed), `mailbox-step`, `turn-step`; `seon.flow/graph-executor`, `join-graph!`; `clojure.core.async.flow`
`create-flow`/`start`/`resume`/`pause`/`stop`/`ping`; `seon.task/frontier`, `seon.task/attempt-tx` (S3).

**Changed.**
- `seon.cluster.store/release-branch!` remembers the head it leaves (`seon.db/remember-revisions!` of `@connection`):
  the producer of a head someone will reopen. One cache entry per released head, weighed by its revision map.
- `seon.sci.eval/base-ctx`, `overlay-plan`: key by `seon.db/content-revisions` over `acquisition-attributes`; an empty
  plan answers the cluster base as the source (no overlay entry); a non-empty plan keys the interpreted source by
  `[base-key (digest plan)]`, so two branches with the same change share it.
- `seon.sci.eval/fork-for-turn`: a fresh agent's print session and settled handles are read from the agent entity in
  one pull (absent agent → nothing), not five queries joining runs and evaluations.
- `seon.flow/start-graph!`: takes the intent; resumes only for `:running`.
- `seon.cluster.agent/graph-definition` / `:seon.agent/blueprint-request`: the world, the agent id and its channels,
  not a merged 25-key cluster handle carrying every dial (today's contract needs connection, process id, caps, five
  dials and four channels to describe two procs).

**Deleted.**
- `seon.sci.eval/program-identity`, `program-identity-cache`, `remember-program-identity!`, `inherited-basis`,
  `revision-basis`, `watch-program-identity!`, `program-cache-policy` (one identity mechanism: `seon.db`'s, in
  `seon.cache`). `acquired-database?` and `acquired-basis` compare content revisions instead.
- On the start path: `seon.sci.eval/fork-cluster-ctx` and `acquire!` (the context `base-ctx` returns is already a
  fork with custody); `fork-for-turn`'s second `sci/fork` for a fresh agent.
- `seon.cluster.agent/arm!`, `arm-graph!`, `acquire-context!`'s create path, `seon.cluster.host/host!`/`start!` and
  the per-branch armer graph fold into `seon.flow/start-flow!` (as R1 already decided; this plan supplies its body).
- The branch's own first transaction: `registry/fork!`'s fork-entity write and `acquire-context!`'s
  `:seon.agent/branch` row, once the merge base reads the attempt's fork-point commit id (P6).

## 4. Phases (each about fifteen minutes, one owner, red first)

**P1 — a reopened head resolves to its parent's program.** `seon.cluster.store/release-branch!` remembers the head.
Red proof `seon.db-test/a-reopened-fork-head-equals-its-parents-content-revisions`: fork from main, write one fork
entity, release, reopen; `content-revisions` over `seon.program/program-attributes` equals main's (red today, probe
above). Cost row `seon.context-test/proofs-on-a-fresh-fork-of-an-unchanged-program-reuse-mains-reach` through
`seon.profile/measure`: `seon.context/proofs` on that fork under 20 ms and 20 MB after one call on main (measured
124 ms / 138 MB red, 9.8 ms / 4 MB with the fix). Unblocks: CACHE-1's fresh-fork miss (tell lane astra-cache-1-2: its
reach key may already be right), W2's per-member cost, every later phase's hit.

**P2 — one program identity.** Delete `seon.sci.eval/program-identity` and its LRU; `overlay-plan` and `base-ctx` key
by `seon.db/content-revisions`. Red proof `seon.sci.eval-test/a-fresh-fork-of-an-unchanged-program-acquires-from-the-base`:
`base-ctx` on a fresh fork calls neither `derive-overlay-plan` nor `derive-overlay-ctx` (`:seon.profile/called`) and
costs under 3 ms / 3 MB (measured 24–34 ms / 28–44 MB). Opus lane: `acquired-database?` sits on every turn.

**P3 — no duplicate programs in the cache.** An empty overlay plan uses the base as the source; a changed program keys
by the plan's digest. Red proof `seon.sci.eval-test/branches-without-program-changes-add-no-overlay-entry`: acquire
three fresh forks with no program write; `::overlay-context` bytes unchanged (today +~60 MB weighed per fork; 314 MB
held). Unblocks: ~300 MB of cache budget back to reach and context entries.

**P4 — one fork per agent.** `start-flow!`'s environment is `base-ctx` on the branch value plus the agent's private
layer applied to that same fork (namespace, print session from one pull, no settled handles for a fresh agent). Red
proof `seon.cluster.agent-test/a-fresh-agents-environment-costs-one-fork`: on a fresh fork after P1–P3, environment
under 5 ms / 5 MB (measured ≈50–60 ms / ~60 MB), and the agent's ctx custody is the branch connection (behavior row).

**P5 — the intent decides resume.** `start-graph!` takes the intent; `:paused` leaves Flow paused. Red proof
`seon.cluster.agent-test/a-paused-intent-starts-a-pingable-graph-that-runs-nothing`: every proc pings `:paused`, a wake
offered runs no turn, `flow/resume` runs it; graph start under 10 ms / 5 MB with two procs (after D2). This is R2's
`:paused` row; it lands with D2 or before it with three procs and a 15 ms bound.

**P6 — the branch is a pointer.** The attempt carries `:seon.task.attempt/fork-point` (commit id); the merge base of an
attempt's branch is that commit (Datahike lineage, `datahike.versioning/parent-commit-ids`, never `t`, which repeats
across forks). `registry/fork!` stops writing the fork entity on the new branch. Red proofs
`seon.cluster.registry-test/a-fork-writes-no-transaction-on-its-branch` (branch head commit = fork-point commit;
fork under 25 ms) and `seon.cluster.source-test/the-merge-base-of-an-attempt-is-its-fork-point-commit` (the diff since
it equals today's diff since `:seon.branch/base-t` on the two-leaf fixture). Measure first who reads
`:seon.branch/base-t` (`target-advanced-base-t`, `seon.cluster.source/compare-branch`); if more than one owner's
afternoon, keep the entity and land P6 last.

**P7 — `seon.flow/start-flow!`, the one path.** Body: branch (P6, or `fork!` until then) → open → world → environment
(P4) → graph (P5) registered in the roster under `:seon.agent/started`, route registered, wake primed; `graph? false`
returns the world alone (test member). Folds in `arm!`, `arm-graph!`, `host!`/`start!`, test-support launch helpers.
Red proofs: R1's `seon.cluster.agent-test/starting-one-agent-on-a-task-costs-under-a-second-and-300-mb`, **tightened**
to attempt-row-to-first-context under 150 ms and 40 MB warm (floor ~50 ms / <20 MB; the bound leaves room for the
writer and the first reach on a new task), legs recorded as profile evidence so a slower run than main's is red;
`seon.cluster.agent-test/a-restart-starts-the-same-attempt-on-its-existing-branch` (`branch!` answers `created? false`,
no second branch, same agent id); `seon.test-test/a-member-world-comes-from-start-flow-without-a-graph`.

Order: P1 → P2 → P3 (P2/P3 one owner, `seon.sci.eval`); P4 after P2; P5 any time (with D2); P6 independent, measured
first; P7 after P4 and P5 (and S3's `attempt-tx`). R1's `db<->flow`/`plan` lands on P7's `start-flow!`.

## 5. Risks, and what to measure first

1. **Measure first: P1's red on a committed fork, then the remembered entry's weight.** `remember-revisions!` weighs
   `64 × (4 + attributes revised since the connection opened)`; a long-lived branch connection's map grows with what
   it wrote. Measure the map size at release on default's busiest branch; if large, remember only the delta plus the
   opening commit (which is what the context already is) — the chain walk in `content-revision` handles it.
2. **Eviction and restart lose the bridge.** Remembered revisions live in `seon.cache`; after a restart or an
   eviction every branch head reads as conservative and pays one overlay-plan derivation (~30 ms) and one reach miss
   per proof (~100 ms+) — correct, slower. The durable fix is upstream in our Datahike fork: persist the revision map
   (or its delta and the parent) in the commit's `:meta`, so `connect` restores it. Price it before choosing.
3. **The cold reach is the dominant cliff, not the path.** One proof's reach on main after eviction cost 1.3 s and
   2.7 GB. The cache is at 506/512 MB, 62% of it duplicate overlay programs; P3 is as much a reach fix as a SCI fix.
4. **`acquired-database?` semantics (P2).** It currently compares by program identity on every turn; switching the
   identity source must keep "a data-only write keeps the acquired program" (its existing test,
   `seon.sci.eval-test/an-acquired-context-retains-bases-and-reads-its-connection`).
5. **The fork entity (P6) is merge machinery's input.** Deleting it is a cross-owner change; keep it if the merge
   readers cannot convert in one slice.
6. **Allocation the graph does (15 MB) is unattributed.** Astra's benchmark separates proc threads; read it before
   setting P5's bound.

## 6. Where I am unsure

- Whether `release-branch!` is the right producer, or `seon.db`'s writer (remember every commit's `db-after`) — the
  writer is more general but pays an insert per commit on main; release covers every head someone reopens. I chose
  release; a parent-commit walk in `content-revision` (Datahike's `parent-commit-ids` + the commit's own tx
  attributes) would avoid memory entirely at a read per hop.
- Whether a test run needs a branch per member at all: members of one run could share one run branch and isolate
  writes with `d/with` (no durable writes), making a member's start ~1 ms. CLAUDE.md's harness rule ("branches writes
  from a captured commit") favors a branch; the cost argument favors `d/with`. Needs the owner.
- `fork-for-turn`'s 12 ms of queries for an agent with no history: likely Datahike planning a join from
  `:seon.agent/id` through runs to evaluations without the agent bound early; unverified.
- Whether `max-running` and the 16 GB budget still need the per-agent SCI estimate (`context-weight` = 12 KB × program
  functions ≈ 60 MB per entry) once forks share the base: the estimate may itself be far from retained bytes.
- I did not re-measure the graph leg or `current-state` end to end on a real attempt (S3's attempt facts are not
  wired); those rows come from R1 and Astra.
