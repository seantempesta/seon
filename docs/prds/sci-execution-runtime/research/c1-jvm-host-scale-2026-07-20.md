---
type: research
status: active
tags: [research, agent, architecture, database]
---

# C1 — JVM sci host scale proofs (2026-07-20)

Grows the feasibility probe's JVM harness
([[../../source-cleanup/research/sci-execution-child-feasibility-2026-07-20]])
into the C1 skeleton of [[../roadmap]]: one shared loaded base, a sci
context per agent, REAL database round-trips to the LIVE default writer
over the existing UDS protocol, thread-per-eval with interrupt +
deadline, and the four scale gates (N=100 working-set marginal,
interrupt-at-scale, OOME blast radius x20, host footprint). Production
source untouched; everything lives in `tmp/sci-probe/jvm/`.

## Skeleton (reproducible)

- `tmp/sci-probe/jvm/src/probe/host.clj` — the host. Runs from the repo
  root on the exact `:writer` alias basis (the operator's own dependency
  closure: maintained Datahike fork, transit-clj, timbre, malli) plus
  sci from `reference-code/sci` and this probe path, via a `-Sdeps`
  overlay. `-Xmx512m -XX:+UseG1GC` from the `:writer` alias; GC log via
  `-Xlog:gc*`.
- `tmp/sci-probe/jvm/host-run.sh` — the driver: same stdin-gated phase
  protocol as the earlier probes, `vmmap` Physical footprint between
  phases, results as `DATA <edn>` lines. Full logs in
  `tmp/sci-probe/jvm/out/host-{run,mem,gc}.log`.

### One shared loaded base (real vs synthetic, honestly)

The base sci context is built ONCE and every agent context is
`sci/fork` of it (persistent-structure sharing; new vars stay private
to the fork — isolation re-verified below).

Real parts:

- **Portable `my.*` slice, loaded from the actual sources**: every
  `:pure`-classified defn block of `src/my/**.cljs` (the feasibility
  probe's classifier) is eval'd verbatim into sci under its real
  namespace (`my.data`, `my.plan`, ...), after a synthetic ns form that
  stands in for `seon.eval/augment-ns-source` (same aliases, pointed at
  the host namespaces). 42 pure blocks found; **25 loaded from real
  source, 17 failed**, each failure recorded with its reason — all
  failures are references to *private impure helpers or aliases the
  pure-block slice does not carry* (`protocol/`, `db.id/`,
  `ctx/read-file-text`, `tone->class`, ...), not sci semantic gaps.
  Loaded and used in anger: `my.data/sum-by`, `my.data/max-by`,
  `my.data/group-sum` (my.data loads 3/3).
- **Real compiled host cljc fns**: `seon.ai.tokens/estimate` /
  `estimate-chars` and `seon.schema/valid-candidate-value?` bound as
  host fns (the same JVM-loaded code the writer runs).
- **Real db boundary**: a `seon.db` sci namespace whose `query`/`pull`/
  `head` perform synchronous UDS round-trips through
  `seon.db.transport.uds/connect!`+`call!` (the identical client
  `script/seon/dev/branch.clj` uses) against the LIVE default writer's
  socket, read-only (`resolve-head`, `query`, `pull` operations only).

Synthetic parts (named as such):

- the per-agent admission source (10 small defns — the earlier probe's
  agent lib);
- the turn workload's plan rows (generated data, my.plan-shaped);
- `seon.schema/register!` inside sci is a no-op recorder (schema
  admission is out of C1 scope);
- the ns form aliasing (production's augment-ns-source is not run).

### Eval runner

`run-eval!` executes a context's form on the calling worker thread
under a deadline: a shared watchdog `ScheduledExecutorService`
interrupts the worker at the deadline; the base context's
`:interrupt-fn` converts `Thread/isInterrupted` into
`sci.interrupt/interrupt!` (unswallowable in-eval stop, per the
feasibility probe). Status envelope: `:ok`/`:interrupted`/`:oom`
(cause-chain classified)/`:error`, with per-eval wall ms. Waves run the
runner on a fixed thread pool.

## Live transport proof (transcript)

Phase `transport-proof`, single context, LIVE default writer over
`tmp/seon-cluster-default-req.sock` (verbatim `DATA` line, first run;
`:pull-root` was nil there due to a probe-side response-key bug —
`::protocol/result`, not `:datahike.pull/result`, carries pull results —
fixed and re-proven in the final run below):

```edn
{:probe :transport,
 :ping #:seon.db.protocol{:pong? true, :success? true},
 :head {:db-name "default", :t 536871247,
        :datahike/commit-id #uuid "6a5e742f-bbd7-5043-9c7a-7e6c3c063c94"},
 :query-agents ["crisp-needles-travel" "fresh-dancers-behave" "root"
                "smooth-humans-raise" "tame-shoes-raise"],
 :pull-root #:seon.agent{:id "root"}}

```

Ping, current-head resolution, a Datalog query enumerating the live
cluster's real agents, and a pull of the root agent — all through the
one existing transport, no citation, no mock. (First run's `:pull-root`
was nil from a probe-side response-key bug — pull results ride
`::protocol/result`, unlike query's `:datahike.query/result`; fixed,
and the transcript above is the corrected run's.)

## Scale measurements (final run)

`-Xmx512m -XX:+UseG1GC`, live default writer, 100 forked contexts.

### Gate 4 — N=100 working-set wave

Each context ran one real turn: 250 plan rows built + transformed
through the REAL loaded `my.data/group-sum` + `sum-by`, one live db
query (agent enumeration) + one live pull (root agent) — 4 UDS
round-trips per turn including per-call head resolution — plus 6
persisting defs of working state. Pool of 10 worker threads.

| Measure | Value |
|---|---|
| turns ok | **100/100** (0 errors) |
| wave wall time | **164 ms** |
| turn latency ms (min/p50/p90/max) | 5 / 12 / 43 / 43 |
| live db calls in wave | 400 (mean **1.99 ms**/call) |
| idle marginal per context (admission only) | **18.6 KB** |
| **working marginal per context (turn state included)** | **117.9 KB** |
| used heap after wave | 66 MB (settles to 55 MB) |
| GC during wave | 4 collections, **6 ms** total |
| cross-context isolation | verified (fork var does not leak) |
| admission of 100 contexts | 312 ms |

The owner's question — the working-set marginal, not the idle tax — is
~118 KB per agent context with a full turn's defs retained (plan rows,
transformed copy, query/pull results, aggregates). N=100 with working
state fits in ~12 MB of heap over the base.

### Gate 5 — interrupt at scale (10 runaway among 90 healthy)

One pool (20 threads): 10 contexts eval `(loop [i 0] (recur (inc i)))`
under a 500 ms deadline, 90 healthy contexts re-transform their
retained plan rows.

| Measure | Value |
|---|---|
| runaways interrupted | **10/10** (statuses all `:interrupted`) |
| interrupt latency vs deadline | mean 503.7 ms, max 505 ms (**≤5 ms overrun**) |
| healthy ok | **90/90** |
| healthy latency ms (p50/p99/max) | 0 / 3 / 3 |
| whole wave wall | 506 ms |

The 90 healthy evals were unaffected (sub-3 ms) while 10 tight CPU
loops burned; every runaway died within 5 ms of its deadline through
`Thread/interrupt` → `:interrupt-fn` → `sci.interrupt/interrupt!`.
Footprint note: the runaway loops' boxed-Long churn expanded G1's
committed heap (vmmap 833 MB during this phase), which decommitted back
to ~506 MB afterwards.

### Gate 6 — OOME blast radius x20

Per round: one context evals `(vec (range 4e9))` while 5 healthy evals
run concurrently on other contexts; after the bomb, 10 survivor
contexts each run a pure eval AND a real live db query.

| Measure | Value |
|---|---|
| rounds | 20 |
| bomb outcome | **20/20 `:oom` "Java heap space"** (cause-chain classified; sci wraps the OOME in ExceptionInfo) |
| process survivals | **20/20** |
| concurrent healthy evals during bombs | **100/100 ok** |
| survivor pure evals after bombs | **200/200 ok** |
| survivor live-db evals after bombs | **200/200 ok** |
| used heap after each round | 55 MB (fully recovered) |
| Full GC max pause (whole run, incl. settle `System/gc`s) | 60.9 ms |

Honest reading: the OOME landed on the bomber's thread in all 20
rounds and in the 100 concurrent healthy evals it never once hit a
bystander — far stronger than the feasibility probe's single lucky
run — but the JVM still does not *guarantee* delivery to the
allocating thread; heap pressure remains a shared-fate axis that
per-child SIGKILL does not have. 20/20 with concurrent load is
evidence of practical containment, not a proof of certainty.

### Gate 7 — host footprint at N=100 loaded

| Measure | Value |
|---|---|
| used heap, 100 contexts + working state | **55 MB** |
| RSS (ps) | 542 MB |
| vmmap Physical footprint | 505.7 MB |
| vmmap peak (during bombs/runaways) | 841 MB |
| baseline (before base/contexts) | 52 MB heap / ~525 MB RSS |
| total live db calls over the run | 805 |

Two caveats on the ~500 MB-class RSS: (1) it tracks the `-Xmx512m` G1
commit, not live data — used heap says a far smaller cap holds
N=100; (2) this process deliberately runs on the full `:writer`
classpath (all of Datahike loads via `seon.db.branch`'s `:clj` branch),
so its class/metaspace load is an upper bound — a production host needs
protocol + transport + schema, not the whole database engine. The
N=100 register row comparison stands: one ~500 MB-class process (heap
argues ~100 MB-class is achievable with a right-sized Xmx and a
client-only classpath) versus 18–22 GB of self-host Bun children.

## C1 gate verdict

**PASS.** All four scale gates hold on the corrected run:

1. N=100 one-real-turn wave: 100/100 ok in 164 ms wall; working-set
   marginal **117.9 KB/context** (idle 18.6 KB); GC during the wave
   4 collections / 6 ms.
2. Live transport: real ping/head/query/pull round-trips against the
   live default writer through the existing
   `seon.db.transport.uds` JVM client — 805 calls over the run, mean
   ~2 ms — proven end-to-end, and the db boundary inverts to plain
   blocking calls exactly as the feasibility inventory predicted.
3. Interrupt at scale: 10/10 runaways stopped within 5 ms of a 500 ms
   deadline; 90/90 healthy contexts unaffected (p99 3 ms).
4. OOME blast radius: 20/20 process survivals, 200/200 survivor evals
   (pure AND live-db) succeed after every bomb, 100/100 concurrent
   evals ok during bombs; heap fully recovers each round.

Remaining honest limits for the decision gate: OOME containment is
strong evidence, not a kill-certainty guarantee; the base is the pure
25-fn slice plus host bindings, not the full toolkit (the db-boundary
46% needs the real port and `register!` admission was stubbed); agent
code here is sci-Clojure, so the js-bound 12% tier and the async-idiom
retraining question are untouched (C2's scope).
