---
type: research
status: active
tags: [research, agent, flow]
---

# Loop / Cycle Naming Precedent — Industry Vocabulary for the Agent Runtime

## TL;DR — recommended naming

Adopt **durable-execution + actor** vocabulary; it maps almost 1:1 onto what
we already built, and it gives us the wall-clock-timeout model for free.

| OUR concept (today) | Recommended name | Borrowed from |
|---|---|---|
| `:idle` / `:active` / `:terminated` | keep `:idle`/`:running`/`:terminated` (rename `:active`→`:running`) | Akka actor lifecycle; statecharts |
| wake event (inbound msg, cron) | **trigger** (umbrella); kinds `:message` and `:schedule` (cron) | Temporal "Signal" / k8s CronJob trigger |
| the bounded unit of work ("cycle") | **run** (an entity); the act of working = **episode** if you want the RL flavor — but **run** is the cleaner DB noun | Temporal "Workflow Run"; k8s "Job run"; RL "episode" |
| turn budget (count vs max) | `turn` + **`turn-limit`** (NOT "max-iterations"); per-grant default = **budget** | LangGraph `recursion_limit`; RL "horizon" |
| sliding/extendable budget | **extend the deadline / top-up the budget** (lease-renewal semantics) | distributed leases; Temporal heartbeat-reset |
| **wall-clock timeout (NEW)** | **deadline** (an absolute `:inst`) + **`DeadlineExceeded`** failure reason → reset | k8s `activeDeadlineSeconds`; Temporal start-to-close |
| cron entity | **schedule** with a 5-field `cron` + `timezone` + `concurrency-policy` | k8s CronJob |

The single most important borrowed distinction: **separate the
work-quantity bound from the wall-clock bound, and name them differently.**
k8s does exactly this — `backoffLimit` (quantity of attempts) vs
`activeDeadlineSeconds` (wall clock), and "whichever limit is hit first stops
the Job." Our `turn-limit` is the quantity bound; our new `deadline` is the
wall-clock bound. They must be two attributes with two distinct exceeded-reasons.

## Wall-clock-timeout design (the key question)

**Recommended model: an absolute `deadline` timestamp on the run entity +
a watchdog that fires `DeadlineExceeded` → reset.** This is the k8s Job model,
hardened with Temporal's "the server, not the worker, enforces the timeout"
insight and lease/fencing semantics for the reset.

Three primitives, named after the best precedents:

1. **`opened-at` / `deadline`** (k8s `.status.startTime` + `activeDeadlineSeconds`).
   Store an absolute `deadline :inst`, not a relative duration — it survives
   process restarts and is a pure DB read ("is `now > deadline`?"). k8s computes
   the same thing from `startTime + activeDeadlineSeconds`.
2. **External enforcement** (Temporal). The agent's own LLM loop **cannot** be
   trusted to notice it blew the deadline — a stalled LLM burns wall clock while
   doing nothing, exactly the failure your turn-budget can't catch. Temporal's
   rule: "The Temporal Server relies on the Start-To-Close Timeout to force
   Activity retries when a Worker loses communication with the Server or
   crashes." Mirror this: a **separate watchdog** (a derived section / a timer
   process outside the agent loop) reads `now > deadline` and forces the reset.
   In our reactive-context world this is a section function over the DB, not a
   stored timer.
3. **`DeadlineExceeded` → reset, idempotently** (k8s reason + lease semantics).
   On expiry: write the failure (`closed-reason :deadline-exceeded`), generate
   the error, and **reset** the agent to `:idle`. Lease theory warns about the
   "slow holder wakes up after expiry and still writes" hazard — protect with a
   **fencing token** (a monotonic run-id / generation number): a late write from
   the timed-out run carries a stale generation and is rejected. We already have
   tx-meta provenance (cause/stop-reason) and a run entity — make the run-id the
   fence.

How the top systems separate wall-clock from work-quantity:

- **Kubernetes Jobs** — `activeDeadlineSeconds` (wall clock; "Once a Job reaches
  activeDeadlineSeconds, all of its running Pods are terminated and the Job
  status will become type: Failed with reason: DeadlineExceeded") vs
  `backoffLimit` (attempt count). "A Job's `.spec.activeDeadlineSeconds` takes
  precedence over its `.spec.backoffLimit` … whichever limit is hit first stops
  the Job." Plus `ttlSecondsAfterFinished` for auto-GC of finished runs — a
  pattern we may want for closing out old run entities.
- **Temporal** — four orthogonal timeouts, only two of which we need:
  **Start-To-Close** ("Limits the maximum execution time of a single
  execution" — our per-run wall clock) and **Heartbeat** ("Limits the maximum
  time between Heartbeats … If the Temporal Service does not receive a Heartbeat
  within a Heartbeat Timeout time period, the Activity will be considered failed
  and another Activity Task Execution may be scheduled according to the Retry
  Policy"). Heartbeat is the precedent for our "sliding window": **each new
  inbound message is a heartbeat that resets/extends the deadline.** Critically:
  "if setting heartbeat timeout, it is critical to also heartbeat from the
  activity, otherwise timeout is ignored" — i.e. liveness must be actively
  emitted, silence ≠ alive.
- **Distributed leases (etcd/ZooKeeper/Chubby)** — "a lock with an expiry date …
  the holder must renew it with a heartbeat before the timer runs out … if the
  node crashes, pauses, or gets partitioned away, the lease quietly expires and
  another node can take over." This is the cleanest mental model for **reset**:
  the run *holds a lease on the agent*; the deadline is the lease TTL; new
  messages renew it; expiry releases the agent back to `:idle`. Pair with a
  **fencing token** to neutralise zombie writes.

Naming verdict: use **`deadline`** (absolute timestamp) for the wall-clock
bound, **`turn-limit`** for the quantity bound, **`DeadlineExceeded`** for the
expiry reason, and describe the message-bump-extends behaviour as
**"heartbeat renews the lease."** Avoid the word "timeout" as a stored attr —
it's ambiguous between duration and instant; store the instant, call it
`deadline`.

## Lifecycle states

Akka's actor lifecycle is the closest precedent and validates our shape:
"Once an actor finished its processing loop, it goes back into an Idle state
(if there were any messages left, we simply reschedule)." Terminated is
terminal: "Once an actor terminates … it will free up its resources, draining
all remaining messages from its mailbox into the system's dead-letter mailbox."

- Keep **`:idle`** (the only wakeable state) — matches Akka "Idle" exactly.
- Rename **`:active` → `:running`** — "running" is the universal term across
  statecharts (XState), k8s pod phase (`Running`), and process state; "active"
  is vaguer and collides with our doc `status: active`.
- Keep **`:terminated`** — exact Akka term.

Three states is right; durable-execution engines don't expose more to the
caller. (Temporal internally has `Running`/`Completed`/`Failed`/`Terminated`/
`ContinuedAsNew`/`TimedOut` — but those are *run* statuses, not *agent* states.
That maps to our run entity's `closed-reason`, not the agent FSM.)

## The unit of work: "run" vs "episode" vs "cycle"

Strongest precedents, in order of fit:

- **Temporal "Workflow Run"** — a Run is one execution from start to a
  terminal status, identified by a run-id; Continue-As-New ends one Run and
  starts another with the same workflow-id but new run-id and **fresh history**.
  This is *exactly* our "woken → works → sleeps, next wake is a new unit"
  shape, and it hands us the run-id-as-fencing-token for free.
- **k8s "Job run"** — has `startTime`/`completionTime`/`failed`/`succeeded`,
  `activeDeadlineSeconds`, `backoffLimit`. Our run entity's
  opened-at/deadline/turn-limit/status/closed-reason is structurally a Job spec.
- **RL "episode"** — "a complete sequence of interactions … from the initial
  state to a terminal state (or until a max time limit is reached)." The
  "or until a max time limit" clause is *precisely* our turn-limit/deadline
  termination. "Horizon = the number of time-steps in each episode" is the RL
  name for our turn-limit. Good conceptual flavour, but "episode" is an awkward
  DB noun and implies RL training semantics we don't have.

**Recommendation: name the entity `run`** (DB-clean, Temporal-aligned,
gives us run-id fencing), and you may *describe* it as "an episode" in prose.
Avoid "cycle" — it implies repetition/circularity; a run is linear and bounded.
Avoid "session" — overloaded (we already have session/turn/message log; a
session usually spans many runs).

## Turn budget naming

- Current count: **`turn`** (or `turn-count`) — RL "timestep … position within
  an episode sequence."
- The cap: **`turn-limit`**. Prefer this over `max-iterations` (AutoGPT) /
  `recursion_limit` (LangGraph) because "iteration"/"recursion" describe a graph
  traversal mechanism, while we mean a budget of agent turns. RL "horizon" is
  the academically precise term but obscure.
- Per-grant default (20): **`budget`** / `default-turn-limit`.
- **Heed LangGraph's warning verbatim**: "Don't simply set the recursion limit
  to 1,000; if your agent is stuck in an infinite loop, you'll just be paying
  for 1,000 API calls instead of 25. The most common reason for hitting a
  recursion limit isn't that your task is 'too complex,' but that your agent is
  stuck." → a high turn-limit is not a substitute for the wall-clock deadline;
  they catch different failures (stuck-but-spinning vs stuck-and-silent).

## Cron scheduling — conventions & gotchas

Model the schedule as a **CronJob-style entity**. Standard 5-field cron, plus
the fields k8s learned the hard way it needed:

- **`timezone`** — always store it; cron without a TZ is a footgun (DST,
  servers in UTC). k8s CronJob added `spec.timeZone` exactly because the
  unspecified-TZ default caused incidents.
- **`concurrency-policy`** — `Allow` (default) / `Forbid` (skip the new fire if
  the prior run is still open) / `Replace` (kill the running run, start fresh).
  For a single-agent-per-process runtime, **`Forbid`** is almost certainly the
  right default: a scheduled self-wake should not start a second run while the
  agent is already `:running`. (k8s: "Forbid — does not allow concurrent runs,
  skipping the new Job run if the previous hasn't finished yet.")
- **missed-fire / catch-up** — `startingDeadlineSeconds` semantics: "If the
  difference [between expected creation time and now] is higher than that limit,
  it will skip this execution." Decide our missed-fire policy explicitly: after
  downtime, do we fire once, fire all missed, or skip? k8s default is "fire
  once if within the deadline window, else skip; >100 missed within the window →
  warn and don't fire." Quartz calls the equivalent **misfire policies**
  (fire-now / do-nothing / reschedule). Pick one and store it; don't leave it
  implicit.

## Patterns we're MISSING (evaluate / adopt)

Ranked by value to us:

1. **Continue-As-New (for ever-growing history) — HIGH.** Temporal's answer to
   exactly our "long-lived agent, history grows forever" problem. "An agent loop
   that runs 500 iterations … accumulates thousands of history events,
   eventually approaching or exceeding the history limit, which terminates the
   workflow." Solution: at a checkpoint, **atomically end the run and start a
   fresh one carrying forward only essential state, with a new run-id and fresh
   history.** Maps directly onto our run entity + Datalog log: a run boundary is
   a natural truncation point for what the agent carries into context. Strongly
   recommend we name and adopt this — it's the difference between bounded and
   unbounded context growth. (Bonus quote: "Every agent loop must have a maximum
   iteration count enforced in code, not left to the model's judgment." — we
   already do this with turn-limit; good.)

2. **Heartbeat / liveness within a long run — HIGH.** We have a turn budget but
   no liveness signal *between* turns. A single LLM call that hangs burns the
   deadline silently. Adopt Temporal's heartbeat: the run emits a liveness
   tick; the watchdog distinguishes "working but slow" (heartbeating, extend)
   from "stuck/dead" (silent past heartbeat-timeout, reset). This is also the
   mechanism that powers our sliding window — an inbound message IS a heartbeat.

3. **Fencing token for the reset — HIGH (correctness).** When the watchdog
   resets a timed-out run and a new run starts, the old run's in-flight write
   must not corrupt the new run. The lease/fencing pattern (monotonic run-id;
   resource rejects writes with a stale token) is the standard fix. Cheap given
   we already have run-ids and tx-meta.

4. **Supervision / restart strategy + restart intensity — MEDIUM.** OTP's
   "let it crash, restart in a fresh state" is our `:terminated`→reset story.
   Borrow **restart intensity**: "limit the number of restarts which can occur
   in a given time interval (intensity + period) before giving up." A crash-loop
   agent that resets every second forever is a real failure mode; cap it. Our
   one-process-per-agent maps to OTP `one_for_one`.

5. **Dead-letter for hop-exhausted messages — MEDIUM.** We already have a
   hop-cap; the industry name for "message that can't be delivered/processed
   after N attempts" is **dead-letter queue**. Akka literally drains an
   undeliverable mailbox into a "dead-letter mailbox." Adopt the term and make
   hop-exhausted messages land in a derived dead-letter view rather than
   vanishing — "isolate problematic messages for later analysis without
   affecting the overall system." (Reactive-context fit: a section function that
   surfaces messages whose hop-count == cap.)

6. **Idempotency key — MEDIUM.** If a wake can be retried (cron re-fire after a
   crash, message redelivery), dedupe on a key so the same trigger doesn't open
   two runs. "Client sends a unique key … if the key already exists with a
   completed status, the stored response is returned without re-executing."

7. **Backoff/retry policy — LOW/MEDIUM.** Distinguish permanent vs transient
   failure: "a malformed payload goes to DLQ immediately since retrying won't
   help, while a network timeout gets exponential backoff retries first." If a
   run fails on a transient error (LLM 503), exponential-backoff-with-jitter the
   re-wake rather than hammering.

8. **Graceful vs hard cancellation — LOW.** Akka's PoisonPill (drains mailbox
   first) vs immediate stop is the precedent for "finish the current turn vs
   kill now." If "other processes can stop a run" (your point 5), define which:
   graceful (let the current turn complete) vs hard (reset immediately).
   `ttlSecondsAfterFinished` is the related GC concept for reaping closed runs.

## Source quotes & links (verbatim, preserved)

**Kubernetes Jobs** — <https://kubernetes.io/docs/concepts/workloads/controllers/job/>
- "Once a Job reaches activeDeadlineSeconds, all of its running Pods are
  terminated and the Job status will become type: Failed with reason:
  DeadlineExceeded."
- "A Job's `.spec.activeDeadlineSeconds` takes precedence over its
  `.spec.backoffLimit` … whichever limit is hit first stops the Job."
- "ttlSecondsAfterFinished … automatically deletes finished Jobs after a
  specified number of seconds."

**Kubernetes CronJob** — <https://kubernetes.io/docs/concepts/workloads/controllers/cron-jobs/>
- "Allow (default) … Forbid — does not allow concurrent runs, skipping the new
  Job run if the previous hasn't finished yet. Replace — replaces the currently
  running Job run with a new Job run."
- "If the `.spec.startingDeadlineSeconds` field is set … if the difference
  [between expected creation time and now] is higher than that limit, it will
  skip this execution."
- ">100 missed schedules within the startingDeadlineSeconds window → the
  controller logs a warning and does not create a Job."

**Temporal — Activity timeouts** — <https://temporal.io/blog/activity-timeouts>
- Start-To-Close: "Limits the maximum execution time of a single execution …
  We recommend ALWAYS setting this!"
- Heartbeat: "Limits the maximum time between Heartbeats … If the Temporal
  Service does not receive a Heartbeat within a Heartbeat Timeout time period,
  the Activity will be considered failed and another Activity Task Execution may
  be scheduled according to the Retry Policy."
- "if setting heartbeat timeout, it is critical to also heartbeat from the
  activity, otherwise timeout is ignored."

**Temporal — Continue-As-New** — <https://docs.temporal.io/workflow-execution/continue-as-new>
and <https://www.xgrid.co/resources/temporal-ai-agent-orchestration-failure-patterns/>
- "Continue-as-new atomically completes the current workflow execution and
  starts a new one with the same workflow ID, carrying forward any state you
  provide … a different run ID, and starts its own event history."
- "Every agent loop must have a maximum iteration count enforced in code, not
  left to the model's judgment."

**Akka actor lifecycle** — <https://doc.akka.io/libraries/akka-core/current/typed/actor-lifecycle.html>
- "Once an actor finished its processing loop, it goes back into an Idle state."
- "Once an actor terminates … draining all remaining messages from its mailbox
  into the system's dead-letter mailbox."

**Distributed leases** — <https://singhajit.com/distributed-systems/lease/>
- "A lease is a lock with an expiry date … the holder must renew it with a
  heartbeat before the timer runs out … if the node crashes, pauses, or gets
  partitioned away, the lease quietly expires and another node can take over."
- "a monotonically increasing fencing token … the resource tracks the highest
  token it has seen and rejects any request with a lower token."

**LangGraph recursion limit** — <https://docs.langchain.com/oss/python/langgraph/errors/GRAPH_RECURSION_LIMIT>
- Default 25. "Don't simply set the recursion limit to 1,000 … The most common
  reason for hitting a recursion limit isn't that your task is 'too complex,'
  but that your agent is stuck."

**RL episodes/horizon** — <https://chinmayhegde.github.io/dl-notes/notes/lecture10/>,
<https://arxiv.org/pdf/1712.00378> (Time Limits in RL)
- "an episode is a complete sequence of interactions … from the initial state
  to a terminal state (or until a max time limit is reached)."
- "the horizon is the number of time-steps in each episode."

**OTP supervision** — <https://www.erlang.org/doc/system/sup_princ.html>
- one_for_one / one_for_all / rest_for_one strategies; "let it crash, then
  restart the process in a fresh state."
- "limit the number of restarts which can occur in a given time interval
  (intensity + period) before giving up."

**Dead-letter / idempotency / backoff** —
<https://bugfree.ai/knowledge-hub/retry-dlq-idempotency-message-processing>
- DLQ: "isolate problematic messages for later analysis without affecting the
  overall system."
- "distinguishing permanent errors from recoverable ones — a malformed payload
  goes to DLQ immediately since retrying won't help, while a network timeout
  gets exponential backoff retries first."
