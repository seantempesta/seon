---
type: research
status: active
tags: [research, agent, flow]
---

# Multi-agent coordination primitives — current state + gaps (2026-06-28)

Read-only survey of the Seon CLJS pod's multi-agent coordination machinery,
grounded in the live `.cljs` source (every claim cites a line read, not memory).
Answers: where are we on waking agents, inter-agent messaging, cron, spawn, and
the other primitives any multi-agent system needs.

## TL;DR

| Subsystem | State | Proof (file:line) | Key gap |
|---|---|---|---|
| Wake / run lifecycle | WORKING | loop.cljs:307-399, run.cljs:212-272, agent.cljs:378-391 | none — tx-trigger-driven, no polling |
| Inter-agent messaging | WORKING | message.cljs:120-143, loop.cljs:322-339, warn.cljs:567 | no delivery-receipt / dead-letter for hop-cap refusals |
| Cron / schedule fire | PARTIAL | schedule.cljs:300-352, loop.cljs:491-521 | scheduled `:fn` NEVER invoked (schedule.cljs:224-228, DEFERRED) |
| Spawn / terminate | WORKING | agent.cljs:414-516, lifecycle.cljs:132-150 | no `/call` capability gate; no in-process spawn-and-wake arm |
| Deadlines / bounds | WORKING | run.cljs:451-478, loop.cljs:257-269 | watchdog kills mid-flight LLM; no parent restart policy |

## 1. Wake / run lifecycle — WORKING

Purely tx-driven, **no polling for wakes**. `install-wake-trigger!`
(client.cljs:2028, armed at boot via `boot-one-agent!`). `wake-handler`
(loop.cljs:307-399) opens a fresh run (idle→running) or renews the current one.
Atomic open via `open-run!` (run.cljs:212-272): `[:db.fn/cas [:seon.agent/id id]
:seon.agent/run nil …]` (266-268) — concurrent wakes race, loser renews the
winner. Wake gate `inbound-msg-datom?` (agent.cljs:378-391): only messages with
to=me, from≠me, origin ∈ {:human :agent}. Every tx leads with a work-fence CAS
(run.cljs:196-202) asserting the agent's run pointer still names this run; lost
CAS aborts the whole tx. The ONE ticker (loop.cljs:507-521, `setInterval`) is the
deadline watchdog + schedule firing — NOT message-wake (wakes are 100% reactive).

## 2. Inter-agent messaging — WORKING

Single writer `message!` (message.cljs:145-240). Message entity (agent.cljs:60-70):
id/from(ref)/to(vector of refs)/content/at/hops/origin. `waking-hops`
(message/internal.cljs:39-65) climbs from the newest inbound so ping-pongs reach
the cap. Waking inbound rule (message.cljs:120-133): from≠me AND origin∉{:core}
(`:core` substrate nudges never wake). **Hop-cap = 4** (warn.cljs:567), enforced
**at WAKE** (loop.cljs:322-339): hops ≥ cap → loud `console.error` refusal, loop
does not start; the row stays in the DB (transcript renders a check-hop-exhausted
warning). Self-message refused (message.cljs:280). The message transact (232)
commits the row in the same tx; the tx-listener fires synchronously → recipient
wakes. No separate delivery step, **no ack path**.

**Gap:** hop-cap refusals are logged but never surface back to the sender (no
dead-letter / callback) — a long chain hitting the cap fails opaquely.

## 3. Cron / schedule-as-data — PARTIAL (the real hole)

Cron matching is pure + tested; firing opens a run; **the scheduled fn is never
executed.** Schema (schedule.cljs:34-47): id/cron(5-field)/fn(qualified symbol)/
timezone/concurrency-policy. Pure logic `parse`/`due?`/`next-fire-at`
(schedule.cljs:126-215) — explicit instants, testable, no implicit clock.
`fire-due-schedules!` (schedule.cljs:300-352, the schedule half of the ticker,
loop.cljs:491-505): for each agent owning schedules, fires ONLY if idle
(derive.cljs:157), with a double-fire guard (fired-this-minute?), opens a run with
trigger `:schedule`, kicks `drive!`.

**CRITICAL GAP (schedule.cljs:224-228, explicitly DEFERRED):** the
`:seon.agent.schedule/fn` (code-as-data "run THIS when due") is never invoked — it
isn't placed on the run (needs a new run attr) and execution depends on the
one-exec-service routing. A schedule fire OPENS a run with zero context about which
schedule fired or what to execute; a human/agent must message the awakened agent to
direct it. **Cron is firing-structure only, not cron action.** This is the #1
missing primitive for autonomous timed workflows (task #66). Also: `:timezone` is
stored but matching uses host-local time (schedule.cljs:16-17).

## 4. Spawn / terminate / supervision — WORKING, with gaps

`create!` (agent.cljs:414-467, idempotent upsert, seed-copies default ctx, does NOT
arm the wake trigger). `start!` (agent.cljs:482-516, wraps create!, writes
`:seon.agent/parent` from the ALS scope, mints a 14-char id). Root base case
(agent.cljs:77-87). `boot-one-agent!` (client.cljs:1998-2033) is where an agent
becomes WAKEABLE (arms the trigger at 2028). `terminate!` (lifecycle.cljs:132-150,
sets `:seon.agent/terminated-at`, closes the run, agent now unwakeable).
`complete!` (lifecycle.cljs:70-94) closes the run and messages the parent (89) →
parent wakes naturally.

- **Gap #1 — no `/call` capability gate:** `start!` is an ungated public verb; any
  agent can spawn. No `granted-fn?`/roles-as-capability-sets (grep: NOT FOUND).
  Task #31 (Phase-5 open half).
- **Gap #2 — no in-process spawn-and-wake:** `start!` mints an idle child; waking
  it needs a separate message round-trip. Task #30.
- **Gap #3 — no crash supervision:** LLM error → run closes `:error`, agent idle.
  No parent restart/backoff/escalation.

## 5. Deadlines / bounds — WORKING

Turn-limit (soft, loop-checked, default 20, run.cljs:170-177 / loop.cljs:164-165)
and wall-clock deadline (hard, watchdog, default 600000ms, run.cljs:179-186 /
loop.cljs:168). Pause/resume BANKS remaining-ms (run.cljs:384-433) so a long pause
doesn't blow the clock. Watchdog `close-overdue-runs!` (run.cljs:451-478, from the
ticker) closes overdue non-paused runs `:deadline-exceeded`. Asymmetry: deadline
kills mid-flight LLM (the async promise isn't aborted, but the next iteration's beat
CAS fails → clean halt, no orphan).

## Gaps ranked by load-bearing impact

1. **Schedule `:fn` execution (#66, HIGH)** — cron can wake but not act.
2. **`/call` capability gate (#31, HIGH for secure spawn)** — ungated spawn.
3. **In-process spawn-and-wake (#30, MEDIUM)** — parent-child latency.
4. **Dead-letter / hop-cap ack (MEDIUM)** — refusals invisible to sender.
5. **Crash supervision / restart policy (MEDIUM)** — silent agent death.
6. **Delivery receipt / await-completion (LOW)** — no explicit acks.
7. **Timezone-aware cron (LOW)** — host-tz only.

The system is production-ready for message-driven, deadline-bounded agent
lifecycles with atomic run-state fencing and hop-cap guards. It is incomplete for
autonomous cron-driven workflows (fn execution unbuilt) and capability-gated secure
spawn (no policy framework). The gaps are known design decisions / future work, not
bugs.
