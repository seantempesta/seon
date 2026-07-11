---
type: prd
status: active
tags: [prd, agent, schema, flow]
---

# Agent runtime — the loop, the run, derived state, and lifecycle

> **Target design** (present tense — the system as it is when built). Current code state + the migration path live in [[roadmap]].

This doc owns the **runtime**: how an agent runs, how a **run** bounds its work,
how the loop is a fold over a data **FSM**, how **state is derived** from
primitives, how an agent is **created** and **bootstrapped**, how the
**orchestrator-root** starts and manages other agents, and the **isolation**
backend that runs agent code. The entity schemas it reads live in
[[data-model]]; the blocks/renders/pages it feeds live in [[ui]]; the agent's
action functions live in [[toolkit]]; the cross-cutting principles and the glossary
live in [[architecture]].

## The model in one paragraph

An agent is normally **`:idle`** — asleep, and the only triggerable state. A
**trigger** (an inbound **message**, or a due **schedule** fired by the ticker)
opens a **run**: the bounded unit of work. While the run is open the agent is
**`:running`** and the loop executes **turns** until a bound fires. A run carries
**two independent bounds** — a **work-quantity** bound (turn count) and a
**wall-clock** bound (deadline) — and whichever is hit first closes it. New
inbound messages **renew the lease** (slide both bounds). The clock bound is
enforced **externally** by one periodic **ticker**, because a stalled LLM burns
the clock and cannot self-detect. The **run-id is a fencing token**: a write from
a superseded or timed-out run is rejected at commit. Everything else — state,
liveness, history, the fleet view — is a **query** over the bitemporal DB. The
loop is a **function of that DB**; the only active piece is the one ticker.

## State is derived — the one projection rule

**There is no stored agent state.** The agent's FSM state is a pure projection of
three primitives, computed each read by `seon.derive/derive-state`:

- `:seon.agent/terminated-at` present ⇒ **`:terminated`**
- else no open run ⇒ **`:idle`**
- else the open run carries `:seon.agent.run/paused-at` ⇒ **`:paused`**
- else **`:running`**

```clojure
(schema/register! :seon.derive/state [:enum :idle :running :paused :terminated])
```

Each primitive (the open-run pointer, `paused-at`, `terminated-at`) is its own
control axis; the state label is only their projection. This is why state never
drifts: there is nothing to keep in sync. `seon.derive` is the **acyclic leaf** —
it requires only `seon.db` (to read) and `seon.schema` (to name shapes), so it
sits below every consumer (the loop, the prompt render, the renderer, the UI, the
ticker, the wake gate) and the `agent → ctx → render` require cycle evaporates.
Every consumer reads `seon.derive/derive-state` over the db value it already
holds; `agent-idle?` and `armable-agent-ids` are **filters** over that rule, never
re-encodings of it, so the rule cannot fork.

## The run — the bounded unit of work

A **run** (`:seon.agent.run/*` — schema in [[data-model]]) is what a trigger
opens. Its **run-id is the fencing token**; it records `started-at`, its
`trigger` (`:message` or `:schedule`), the `cause` (the triggering message), the
two bounds, a per-turn heartbeat `last-beat-at`, a `status` (`:open`/`:closed`),
and — once closed — a `closed-reason`
(`:completed`/`:waited`/`:turn-limit`/`:deadline-exceeded`/`:terminated`/
`:superseded`/`:error`). A **run replaces any "session" concept** — there is no
session entity. Runs link back to the agent via `:seon.agent.run/agent`; turns to
their run via `:seon.agent.turn/run`.

### The two bounds, the lease, the heartbeat

The two bounds are deliberately separate (the k8s split: a `backoffLimit` count
vs an `activeDeadlineSeconds` clock):

- **Work-quantity bound (derived) — denominated by the REPL mode.** The unit is
  **turns under `:batch`, forms under `:stream`** (the `:seon.config/repl-mode`
  datom — [[context]] §"The REPL mode is a datom"; the manifest-absent default is
  per-model, DeepSeek → `:stream`). Under `:batch` the effective budget is base
  `:seon.agent/default-turn-limit` (20) and the current turn is a derived count of
  `:seon.agent.turn/run` datoms — `seon.derive/run-turn-count`. Under `:stream` a
  turn evals at most one form, so the budget is **form-denominated**
  (`seon.agent.run/default-form-limit` 60) and the count is
  `seon.derive/run-form-count` — so prose/orientation turns burn nothing. Either
  way, **plus the count of inbound messages received during the run** — nothing
  per-message is stored. Every inbound that lands mid-run earns +1, so a message
  arriving during an LLM call always earns a unit to be **seen and answered**. A
  stored override appears only when a process *explicitly* bumps or stops the budget.
- **Wall-clock bound (`deadline`).** A run opens with `deadline = started-at +`
  the agent's `:seon.agent/default-deadline-ms` (or a generous global default).
  It is an **absolute instant**, so it survives restart and is a pure DB read;
  the turn-limit is the usual stopper, the deadline the backstop.
- **Lease renewal = the sliding window.** An inbound message during an open run
  slides both bounds (the work budget grows by the derived inbound count; the
  deadline pushes out). "The human keeps talking" extends the run. Any process
  may renew.
- **Heartbeat.** A coarse `last-beat-at` is written per turn — enough for fleet
  liveness and stall detection (an open run with a stale beat is stuck even
  inside its deadline), cheap enough to avoid tx churn (datahike writes are the
  measured bottleneck; never beat per-second).

### Pause vs the absolute deadline

`pause` **banks** the remaining budget (`remaining-ms = deadline − now`) on the
run; `resume` re-extends the absolute `deadline` by the banked amount, so a long
pause does not insta-kill on resume. While paused, the derived `ms-remaining`
surfaces the banked budget, not `deadline − now` (which would keep decaying).

## The turn — a value-transform, "Snap-to-Tx"

Each **turn** threads **one frozen db value** (re-read once at the top) through
`next-event`, the prompt render, and the bound checks, so the LLM reasons over a
single consistent basis-t. The **next** turn re-reads the latest store — and
because there is a single writer, that read sees every other writer's commits, so
a turn never runs in a private view.

**The in-tx work-fence.** Every WORK transaction (`beat!`, `open-turn!`,
`eval-batch!`) **leads** with an in-tx assertion:

```clojure
;; OV == NV == the current run, as a lookup-ref ([:seon.agent/run] is a REF).
[:db.fn/cas [:seon.agent/id id] :seon.agent/run [:seon.agent.run/id R] [:seon.agent.run/id R]]
```

The *database*, not a pre-read predicate, tells the loop it has lost authority: if
a watchdog, a human, or a newer run moved the `:seon.agent/run` pointer, the tx
aborts and the work never lands (`compare-and-swap` re-asserts when current==R,
RAISES otherwise → the whole tx, eval batch included, aborts). This replaces any
check-then-act ownership pre-read and fences the eval batch atomically with its
result write. **Ground in** `transaction.cljc:873` (`compare-and-swap`) +
`db/utils.cljc:109` (`entid` resolves the `:db/unique` lookup-ref entity) — read
[[library-grounding]] before building the fence. (The mindset — db is a value,
only values cross the wire, CAS-as-assertion, never memoize on a db value — is
[[datahike-primer]].)

### REPL forms — namespaces are places (settled 2026-07-10)

The eval boundary (`seon.eval/dispatch-repl-form!`) implements the real-REPL
movement/update semantics the transcript teaches, so an agent's reflexive
REPL moves work:

- **`(in-ns 'foo)` is THE movement form** — state-preserving switch of the
  current-ns accumulator (the cursor + namespaces block follow via the
  recorded `:seon.eval/ns`). A DB-known-but-unloaded ns loads through the
  one load-fn; a genuinely fresh name is CREATED with the canonical toolkit
  requires (deliberately richer than the JVM's blank-slate `in-ns`) — never
  a blank slate, never an error.
- **`(ns foo …)` declares/UPDATES** — re-eval REPLACES the require set;
  the stored `:seon.ns/source` + `:seon.ns/require-edges` heal wholesale
  (component retract cascade, no orphans).
- **A bare top-level `(require …)` is durable by default** — it loads now
  AND persists into the ns's stored declaration (`require-decl-tx` merges
  the specs into `:seon.ns/source`), so resume replays it. `(alias 'a 'ns)`
  is the same mechanism (rewritten to a require; error-as-value when the
  target exists nowhere). `:as-alias` aliases keywords WITHOUT loading and
  round-trips as an `:seon.ns.require/as-alias?` edge.
- **Redefinition IS update** — defn/schema/deftest re-eval upserts the
  projection row in place (body-only redefs rescued for deftests too); an
  incompatible `register!` re-shape of an installed attr surfaces as a
  `:seon.db/schema-divergence` envelope naming the migration move.
- **`ns-unmap` removes** — live var + analyzer def gone, the
  `:seon.fn`/`:seon.test` row retracted (resume + instrumentation forget
  it); compiled-core fns are refused (the override-guard symmetry).
  `ns-unalias` drops an alias from the analyzer, declaration, and edges.

## The loop as data — the FSM table + the fold

The loop lives in `seon.agent.loop`, shaped like a flow process for the parts
worth borrowing — **a defined initial state, one transition function, the whole
machine as data**. No channels: CLJS channels are single-threaded and buy no
parallelism; isolation is the worker tier below.

**The machine is one value** (`{state {event → next-state}}`):

```clojure
(def transitions
  {:idle       {:trigger     :running}     ; a wake (message/schedule) opens a run
   :running    {:turn-ok     :running      ; within both bounds → another turn
                :wait        :idle          ; function: park, wakeable
                :complete    :idle          ; function: finished; result delivered as a message
                :turn-limit  :idle          ; work bound hit (clean)
                :deadline    :idle          ; clock bound hit (ticker; :deadline-exceeded)
                :superseded  :idle          ; a newer run won the fence
                :error       :idle          ; the turn threw
                :pause       :paused
                :terminate   :terminated}
   :paused     {:resume      :running       ; "hold, don't kill"
                :terminate   :terminated}
   :terminated {}})                         ; terminal
```

The **effect** of each event mutates a *primitive* (open/close/pause a run, set
`terminated-at`); the agent's state is then **derived** from those primitives, never
stored. A fresh agent boots `:idle`; a run opens at `:running`. The driver is
trivial — a fold of one `transition` over events derived from the run's data each
iteration:

```
run-loop!(agent, run):
  loop:
    db    = snapshot()                          ; one frozen db value this turn
    event = next-event(db, agent, run)          ; turn-ok | wait | complete
                                                ; | turn-limit | deadline | superseded
                                                ; | error | pause | terminate
    [state', effects] = transition(state, event)
    apply!(effects)                             ; beat!, run-turn!, close-run! …
    if terminal-for-this-run: break
```

`next-event` reads the event off the run's data: still `:running`, within both
bounds, and still owning the fence → `:turn-ok` (beat + run a turn); a function inside
the turn emits `:wait`/`:complete`/`:pause`/`:terminate`; a bound or the ticker
emits `:turn-limit`/`:deadline`/`:superseded`. Because every branch is data, the
machine is itself inspectable and renderable.

**Stop policy nuances.** A turn that *attempts* forms but every form errors is not
a quiet stop — it recurs so the next turn surfaces the errors. Two consecutive
**zero-form** turns (an `empty-streak` guard) close the run cleanly — a deliberate
"thinking mode" of up to two empty turns before the loop concludes there is no
more work. A `wait` parks the agent (wakeable) with its reason on the run's
`closed-reason`; a `complete` delivers its result as a **message** (to the parent
or the human) and parks — *unless* the agent already messaged that recipient this
run: the earlier message IS the answer (derived from the run's message log, no
stored flag), so `complete` closes without sending a second, answer-clobbering
message.

**Complete-gate — a success claim must be BACKED by a real green test run.**
`complete` is the one function that asserts *success*, so it is REFUSED (an honest
errors-as-value envelope, never a throw, the run left OPEN so the agent keeps
working) when the agent's **latest** recognized test run is RED. This is purely
DERIVED from the agent's own `:seon.agent.testrun` datoms at call time — the max
testrun eid scoped to the agent (`seon.agent.testrun/latest-run`), refused when
its `failed > 0` or `errors > 0`; no stored gate flag. It is correctly SCOPED: an
agent that ran **no** recognized suite (a root orchestrator, a research agent, a
gsm8k solver) has no testrun datom, so `latest-run` is nil and `complete`
proceeds normally — the gate never touches non-test work. Latest-wins: a later
green run supersedes an earlier red (and vice versa) by higher eid. This closes
the **fabrication hole** (T4): an agent that runs a real red pytest, then in the
SAME reply fabricates an "all tests pass" echo and calls `complete`, is refused —
the real red testrun entity persisted via `testrun/record!` (forms eval
sequentially) BEFORE `complete` evaluated, so the gate reads the truth the
runtime rendered — the persisted datoms — not the
model's claim. The refusal is honest and actionable ("your latest test run is
RED (N failed) … a result you did not see the runtime render does not count"),
converting an early false-stop into a continued drive. An agent that honestly
wants to STOP with tests still red is NOT forced to lie: `complete` is the
success claim, but `pause` and `(message/user …)` are ungated — the agent reports
its real status through those, and only the success assertion is withheld.

**Durable result + outcome routing (multi-agent).** A `complete` also writes the
result as **DATA on the run** — `:seon.agent.run/result` (the short answer /
pointer) and optional `:seon.agent.run/result-ref` — *unconditionally* (even when
the answered-this-run guard skips the message). Message = wake signal; datom = the
value a parent reads back at **any** later time (a subagents-section render, a
query), surviving turns and restarts. Beyond `complete`, **every abnormal close is a
task OUTCOME the PARENT owns**: `close-run!` (the ONE choke point all closes funnel
through) messages the parent — child id + `closed-reason` + turn count, `origin
:agent` **from the child** so it WAKES the parent (never `:core`, which the wake gate
excludes) — for `:turn-limit`/`:deadline-exceeded` (with a *continue* affordance —
budget exhausted is not death, re-message to open a fresh run),
`:error`/`:no-forms`, and `:crashed`. A `:crashed` (wedge) **also escalates to
root** (deduped when the parent IS root; root's own wedge is parentless → the user).
`:waited`/`:terminated`/`:superseded` message no one. Every close stamps
`:seon.agent.run/closed-at` (the breaker's window instant; run duration is
derivable).

**Heartbeat watchdog (`:crashed`).** A wedged agent never closes its own run, so a
core scan rides the **one ticker** (`run/close-stale-runs!` — no parallel timer;
the detection core `run/stale-run-ids` is a **pure fn of (db, now)**): an OPEN,
non-paused run whose freshness anchor (`last-beat-at`, else `started-at` for a
never-beat wedge) is older than `:seon.config/watchdog-stale-ms` (default 20 min,
above the per-turn bound) is closed `:crashed` (→ the parent/root outcome notice +
the pointer retract that unsticks the agent) **and** recorded as a `:core` fault via
`seon.error/record!` — a wedge is OUR bug, so it enters the standard triage chain
(watch-faults → inspect → repro → fork; the dev `:crash` dial exits loudly). Fencing
already covers the false-positive: a late-beating driver's leading CAS aborts
against the retracted pointer (a no-op, never a double-drive). **Root self-heals
through the same path but does NOT auto-rewake** — the close unsticks it (idle +
wakeable); it resumes on the next natural contact.

**Schedule-wake circuit breaker.** With no auto-rewake, the one autonomous
repeat-wake source is schedules — a deterministic wedge + a periodic schedule is a
crash loop. The schedule wake-gate refuses to fire for an agent with ≥N `:crashed`
closes in a recent window (`derive/schedule-breaker-tripped?`, windowed over
`closed-at`; dials `:seon.config/schedule-breaker`, default N=3 / 30 min) — **derived,
no stored state**: the window sliding past re-enables it. Human/agent MESSAGES still
wake it (deliberate contact is not a loop); only schedules are gated. The refusal is
visible in the subagents-section line.

## Triggering + fencing — the reactive wake

Triggering is **DB-reactive**. Each agent installs one `db/listen!` tx-listener
(`install-wake-trigger!`, idempotent — it unlistens the prior key first, so a hot
reload never doubles up). On every commit the listener inspects the added
`:seon.agent.message/to` datoms; a datom **wakes** the agent iff:

> `to ∋ me` ∧ `from ≠ me` ∧ `origin ∈ {:human :agent}` (never `:core`) ∧ `hops < hop-cap`

The `to`-check is load-bearing (every agent installs the listener; without it one
message wakes everyone). Hop-exhausted messages are the **dead-letter** — they
stay as datoms, render as a reactive warning, and never wake. `:core`-origin
messages and eval rows are **quiet** by construction — they neither wake nor count
toward a run (this is what makes seeded bootstrap forms silent; see below).

**Fencing is two-layered, both via the single writer:**

- **The OPEN race.** Opening a run ends with `[:db.fn/cas … :seon.agent/run nil
  [:seon.agent.run/id R]]` — the pointer must be *absent* — so two concurrent wakes
  cannot both open a run; the loser's tx aborts (single-writer serialized). Order
  the tx `[{run-create-map} [:db.fn/cas …]]`: the CAS resolves its NV against the
  RUNNING in-tx db (`transaction.cljc:1138-1140`), and `entid-strict` RAISES on a
  run that doesn't exist yet, so the create map MUST precede the CAS. See
  [[library-grounding]].
- **The WORK race.** Every work tx leads with the in-tx CAS work-fence above, so a
  superseded run's writes (including its eval batch) abort at commit.

A wake that arrives while the agent is already `:running` is **absorbed** by the
open run's sliding window (it renews the lease), not a second run. A stop between
turns exits cleanly at the next `next-event`; a stop mid-turn is rejected at the
CAS (hard-aborting an in-flight LLM call is the worker-kill of the isolation tier).

## The one ticker — schedules + overdue runs

The DB is **passive about wall-clock**: `now > deadline` is true in the view but
nothing fires until something checks. So exactly **one periodic ticker** (wired at
boot beside the wake trigger) does the only active work in the system. Every N
seconds it:

1. **Fires due schedules** — opens a `:schedule`-triggered run for each due
   `:seon.agent.schedule/*` on an `:idle` agent (respecting its
   `concurrency-policy`). A schedule carries its cron expression **and the fn to
   call** when due (a qualified symbol resolved late, code-as-data) — cron is "at
   this schedule, run this fn", not merely "wake me".
2. **Closes overdue runs** — for each `:open` run where `now > deadline` (or the
   beat is stale): `terminate()` the run's worker, `close-run!` with
   `:deadline-exceeded`, surface the error, and the agent derives back to `:idle`.

The ticker is **idempotent** (it acts on db state; safe to re-run) and lives **off
the runaway's thread** — a sync runaway in a worker cannot block it, which is the
whole point of enforcing the clock bound externally.

## The derived fingerprint — `derive-status`

`seon.derive/derive-status` (map-in / map-out, `:seon.derive/status-request` →
`:seon.derive/status`) returns the agent's complete derived status in one map over
**one threaded db value**: the derived `state`, `total-turns`, `open-todo-count`,
`last-human-at`, the last run's `closed-reason`, and — present only while a run is
open — the run's `status`/`trigger`/`turn-limit`/`deadline`/`last-beat-at`, the
derived current `turn`, `turns-remaining`, and `ms-remaining`. It is a pure read
(no writes) composing the same `seon.derive` primitives every other reader reads,
so the agent-facing run-status block and the human-facing status tile agree by
construction. The run-status **block** (ai + html renders) is owned by [[ui]];
this fn is its sole data source.

## Creation = an idle agent entity

**Creating an agent does not start a loop.** Creation transacts an **idle agent
entity** and nothing more: its `:seon.agent/id`, optional
`:seon.agent/default-turn-limit` / `:seon.agent/default-deadline-ms` seeds, the
seeded `:seon.agent/ctx` block set, and a fresh `my.agent.<id>` home namespace.
There is no run, no turn, no wake until a **trigger** arrives. This keeps creation
pure and the loop strictly trigger-driven: "start an agent" (create + bootstrap,
idle) and "run an agent" (trigger-driven) are two separate acts. To make a freshly
created agent work, **send it a message** — that message is the trigger that opens
run #1.

## Bootstrap = seeded forms, run quiet before any trigger

An agent's **bootstrap is a form-vector** carried with its seed. Immediately after
creation transacts the idle entity, those forms are eval'd **synchronously in the
new agent's own scope, before any trigger can open a run**, each recorded as a
`:seon.eval` row with **`:core` origin**. The `:core` origin is what makes them
**quiet**: the wake gate and the turn counter ignore them, so no run opens and no
turn is consumed — yet because they are real eval rows in the agent's own scope,
**the agent sees its own startup** in its transcript and program graph. Bootstrap
is not hidden core magic; it is the agent's first, visible, replayable commands.

The bootstrap forms **are** the seed commands themselves:

- the batched `(ctx/install! [ … ])` that seeds the agent's complete block set
  (the install!/seed-copy mechanism is owned by [[ui]]);
- `(schema/register! :my.agent/purpose …)` plus its **refine** fn and a
  self-refining block — `:my.agent/purpose` (a markdown goal string) is the
  canonical **first per-agent seed worked-example**: the agent owns, sees, and can
  rewrite its own purpose (schema in [[data-model]], the function in [[toolkit]]);
- the home-namespace `defn`s the agent starts life knowing.

**Planning rides the same data.** An agent plans with its **`my.plan` tree** — a
todo carries a `:my.plan/parent` ref plus status, and parent progress is a derived
roll-up of its children (top = plans/milestones, leaves = actions). There is no
separate plan entity; the work-list *is* the plan tree (schema in [[data-model]],
functions in [[toolkit]]). The derived open-todo count feeds the fingerprint above.

## Cluster boot — the core seed (`boot-seed!` → `reconcile!`)

Before any agent runs, the pod seeds the view a cluster boots into. There is ONE
boot entry — `seon.client/boot-seed!` (the gym's scratch views call the SAME fn, so
they can't drift) — and it writes **two provenance layers**, never a stack of
independent per-step seeders:

- **Append-only introspection (origin `:core-seed`).** The entity-schema decomposition,
  the user + `my.kb.shared` seed, and the program-graph index (`:seon.fn` / `:seon.ns`
  / `:seon.schema` / `:seon.test` rows). This is NOT a desired set — it is conn-deduped
  introspection that only grows; it is never retracted.
- **The declarative desired set (origin `:config`).** The routes (`:seon.route/*`,
  curated by the manifest) + the scanned skills corpus (`:my.skills/*`, curated by the
  manifest) are the ONE managed declarative population, synced through
  `seon.state/reconcile!` (scope `#{:config}`). reconcile UPSERTS each row by its own
  `:db.unique/identity` (`:seon.route/name` / `:my.skills/name`) — idempotent on an Nth
  boot — AND RETRACTS any managed row absent from the desired set. So **dropping a route
  from the manifest, or a skill from disk, removes the stale datom** (it can no longer
  persist across boots); the `:core-seed` introspection is outside the scope and is
  never touched. `seon.state/reconcile!` is the ONE declarative-state primitive (seed,
  config-override, reset, restore are all expressions of it); the manifest is the config
  seam ([[data-model]] §5.6, which also holds the per-test recipe).

Each agent's block loadout is shaped from the same manifest at create
(the `install!`/seed-copy mechanism owned by [[ui]]). The identity file-blocks
(`file-block`/`-ai`/`-html`, `config/identity-file-blocks`) that re-read SOUL.md /
AGENTS.md every render are **DEPRECATED and out of the running tree** (their render
fns carry `DEPRECATED` docstrings): AGENTS.md's operating rules are being MINED
per-line into the `:seon.config/system-text` datom + the relevant block's own
teaching, and `soul` returns as a capability milestone (identity as DB state,
possibly inside system-text), not a re-read file. See [[context-rebuild]] ("The
idea inventory").

## The orchestrator-root + agent lifecycle

**Root is one ordinary agent holding capabilities others don't — not special core
machinery.** There is exactly **one** `:seon.agent/id "root"`, and it is **both**
the `/`-view owner (the UI role — its system-scoped blocks derive the all-agents
overview at `/`) **and** the system orchestrator (the lifecycle role — it starts
and manages other agents). These are two facets of the same elevated grant and the
same bootstrap; there is **never** a second supervisor or overview entity.

- **`seon.agent/start!` — the spawn function, a SOFT gate + a hard depth-cap backstop.**
  `start!` is a core function (an alias of `create!`) that transacts a new **idle** child
  agent and **writes `:seon.agent/parent` = the caller**. That write *is* the
  activation of `:seon.agent/parent`; no separate writer exists. Two gates, both
  real (there is **no `/call` capability gate** in the pod — that was aspirational):
  - **Soft gate — home-requires.** The spawn functions (`start!`/`delegate!` via the
    `seon.agent` alias) sit only in **root's** `:seon.eval/home-requires`
    (`config/system.edn`'s `:seon.config/root-context`), so an ordinary agent's
    rendered context never surfaces them. It catches the honest case.
  - **Hard backstop — a computed depth cap.** A full-qualified
    `(seon.agent/start! …)` slips past the soft gate, so `start!`'s **own body**
    walks the `:seon.agent/parent` chain (`seon.agent/spawn-depth`, cycle-guarded)
    and **refuses** when the caller's depth ≥ `:seon.config/spawn-depth-cap`
    (default **1**: root at depth 0 spawns, a depth-1 subagent may not). The refusal
    is the standard **error ENVELOPE** (`{:seon.db/ok? false …}`), datom-free (no
    child minted), never a throw. It is a **config-dialed number, never a name
    list** — raise the dial + add the spawn requires to the general agent-context to
    deepen the tree.
- **Start = create + quiet bootstrap, leaving the child idle.** `start!` runs the
  child's bootstrap form-vector (quiet `:core` evals, as above) and stops. The child
  does no work until it receives a trigger; to make it work, root (or anyone) sends
  it a message — that message opens its run #1. Two steps, one entry function.
- **Roles are capability-SETS, not a stored `:kind`/`:role`.** A role = (the set of
  granted `:seon.fn` capabilities) + (which bootstrap form-vector ran).
  "Orchestrator" = an agent granted the spawn/terminate/system fns; "worker" = an
  agent without them. The difference is **Datomic presence/absence** of grants,
  queried at the `/call` gate — never a discriminator field (the entity-kind rule,
  owned by [[data-model]]).
- **Root's own bootstrap = the cluster-boot base case.** Cluster boot seeds root the
  same way `start!` seeds a child, except root has **no parent** —
  `:seon.agent/parent` is absent, root *is* the base case of the recursion. Boot
  runs root's elevated bootstrap form-vector: install its system-scoped blocks, seed
  the `/`-view layout on root's route, grant the spawn/terminate/system fns. The
  recursion bottoms out cleanly: **boot → seed-root → root.start!(child) →
  seed-child → …** The "start an agent" affordance on the `/`-view is simply this
  orchestrator capability exposed for the human — it calls root's `start!` through
  `/call`.

## Message intake — auto-todo (write-side)

Message intake is **write-side**, independent of render (render is a pure read
projection). When an inbound `:human` message lands, `seon.agent.message/message!`
mints one **address-todo in the same tx** as the message (atomic with the
message's birth, gated on `:human` origin) — carrying a short clipped preview plus
a back-ref to the message; the agent pulls the full message by its identity attr
when it acts. "Addressed" then **derives** from that todo's completion — there is
no stored handled-flag. The todo's *render* is owned by [[ui]]; the write hook is
owned here.

## History is derived

The activity timeline — created, woken-by, turn counts, why each run ended — is a
**derived query** over the bitemporal tx-log: walk the agent's run entities
(`started-at`, `trigger`, `cause`, `closed-reason`) plus each transition's
`:db/txInstant` and the loop's transition tx-meta (`:seon.agent.loop/cause` →
the triggering message). Nothing is stored that the log doesn't already hold; the
timeline is a function of the DB at render time, self-healing (no log to clear).
The rendered timeline view lives in [[ui]]; this doc owns only the run-lifecycle
facts it reads.

## Nothing wedges — bounded execution through the one chokepoint

Nothing can permanently wedge the pod. All execution reaches the runtime
through **one door** (`seon.eval`, the exec service), and four existing
mechanisms — extended, never duplicated — make every hang a value:

- **One bound: `race-timeout`.** Every await self-bounds by racing the one
  wall-clock wrapper (the same one everywhere: agent forms, auto-test runs,
  each LLM attempt, the loop's turn await). A timeout is an **error value**
  (`:seon/error` / `:seon.ai/error` timed-out flavor) surfaced through
  warnings — never a throw, never a silent park. The bound frees the
  *awaiter*; it does not pretend to cancel the work (nothing can, on one
  event loop) — that's what the next two mechanisms absorb.
- **One reaper: the ticker.** The run deadline + `close-overdue-runs!` on
  the one 30s beat is the outer watchdog; per-await bounds are the inner
  one. There is no separate supervisor process, heartbeat service, or
  in-flight registry — in-flight work is DERIVED (open runs, pending
  `result/<id>` stashes), queryable like everything else.
- **One fence: the run-id CAS.** A late-settling await from a reaped or
  superseded run cannot corrupt state — its writes lead with the work-fence
  and abort at commit. Late results are values, absorbed or discarded.
- **One leak-bound: the `result/<id>` stash.** A never-settling Promise is
  retained under the capped result-var stash (oldest pruned), and dropped
  on restart. Pending work is re-referenceable data, not a leak.

The honest residual: a **synchronous CPU loop** blocks the event loop the
watchdog itself lives on — no eval-level mechanism can preempt it. That is
precisely the fault axis of the isolation tiers below: `eval-batch!` runs
in a Tier-1 worker, and the deadline-watchdog's `terminate()` is the
CPU-proof kill an in-process timer can't deliver. Chokepoint bounds handle
every async park; the worker tier handles the sync runaway; together the
system has no permanent-wedge class left.

## Isolation — the execution service's backend tiers

Eval, render fns, and interactions are **three doors to one service** — "run an
agent-granted fn with args, safely" (the one-service principle is stated in
[[architecture]]; agent-authored renders and route handlers go the **same** door,
covered by [[ui]]). The runtime owns the **backend** that actually runs the code,
and it is **tiered**:

| | Tier 1 — worker_threads + SCI (default) | Tier 2 — microVM (opt-in) |
|---|---|---|
| weight | ~8MB / ~30ms per worker; `terminate()` ~0.8ms | ~5MB+guest / ~125ms boot; a second kernel |
| isolation | process boundary (real kill) + SCI cage (hallucination guard) | full kernel isolation — "contain a stranger" |
| DB reads | in-process, sub-ms (great for reactive readers) | vsock/VM-exit hop (fine for LLM-paced, bad for sub-ms re-render) |
| npm | direct `require`, shared pnpm store | full Node inside; shared store via virtio-fs RO mount |
| on macOS | native | libkrun (HVF) / Apple `container` |
| use for | reactive readers, UI, the trusted single-user agent | untrusted/dangerous code; the multi-tenant case |

**Three isolation axes — `worker_threads` alone are NOT a security boundary:**

- **Fault** (a hang/crash can't take down others) → worker_threads + `terminate()`.
  SCI catches the common interpreted runaway in-process (~0.2ms); `terminate()` is
  the CPU-proof backstop SCI can't deliver (a native loop, ReDoS) and the
  deadline-watchdog's only real kill.
- **Capability** (*what* code may do) → the **SCI curated surface**. Agent code runs
  in SCI exposing only GRANTED fns (`db/query`, `message!`, the wire capabilities);
  `fs`/`child_process`/`net`/`require` aren't in scope, so a worker can't format the
  disk — the symbol doesn't resolve. A bare worker has full process perms and
  `terminate()` can't stop an instant `fs` call, so **untrusted agent code MUST go
  through SCI**; the bootstrap `cljs.js` compiler is only for *our* trusted code.
- **Resource** (runaway memory/CPU) → worker `resourceLimits` / the Tier-2 microVM.

Tier-1 covers fault + capability-by-grant + resource for the single-user,
non-adversarial case. **Tier-2 microVM** is the *kernel* boundary for genuinely
untrusted / multi-tenant code or defense-in-depth against an SCI escape: a guest
can format its own disk but not the host's.

**The pool shape** (piscina/tinypool patterns): warm `min 4 / max 8`
pre-bootstrapped SCI cages; `concurrentTasksPerWorker 1`; recycle = terminate +
respawn + re-read the DB (DB-stateless — no handoff); an `AbortSignal` →
terminate on deadline; a bootstrap-failure breaker stops respawn storms. **Eval is
offloaded**: the SCI `eval-batch!` runs in this pool, and the deadline-watchdog
terminates a runaway worker — the CPU-proof kill an in-process timer can't deliver.
The worker buffers its writes and commits them atomically through the same fenced
tx after it returns, so a terminated worker can't leave a half-committed write.

## What the DB gives us for free — and what we don't adopt

Most durable-execution machinery exists to work around the absence of a
bitemporal, reactive DB. We have one, so:

- **No continue-as-new (Temporal).** It bounds history because Temporal *replays*
  full event history. We never replay — the rendered context is a **query over a
  window** (last-N / since-T / this-run); history accumulates as cheap deltas. Bound
  the **view**, not the storage.
- **No external fencing/lease service (Chubby/etcd).** The run-id on the agent
  record IS the fence; the single writer gives the ordering.
- **No heartbeat service.** One `last-beat-at` datom; fleet status = one unfiltered
  query, live over `listen!`.
- **No core.async.flow port.** JVM-only, and CLJS channels are single-threaded — no
  parallelism. We borrow its *patterns* (initial state, transition fn, supervision),
  not its channels.
- **Dead-letter queue / event stream / state-at-T = native.** Hop-exhausted datoms
  are the dead-letter; the tx-log + `since`-replay IS the event stream; datahike
  `as-of` is "state at T". Port datahike's primitives — don't roll our own
  ([[datahike-primer]]).

## Detail docs

- [[architecture]] — the map: thesis, glossary, deployment topology, the
  cross-cutting principles (DB-as-bus, derive-everything, never-crash, roles-as-
  capabilities, code-as-data, seed-copy), the one-service principle.
- [[data-model]] — every entity + attribute + datahike facet: the agent record,
  `:seon.agent.run/*`, `:seon.agent.turn/*`, message, todo, schedule, the
  `:seon/error` model, and the `my.kb` / `my.plan` (tree) / `my.agent` domain
  schemas + data-agent-ref scoping.
- [[ui]] — the block / render / tile / slot / layout system, the seed-copy +
  variadic `install!`/`remove!` override model, the pages (root agent view / view /
  app), routing-as-data via reitit + the capability gate, and the gzip-morph
  SSE live channel.
- [[toolkit]] — the agent's `my.*` function catalog (purpose, the my.plan planning
  tree, schedules, code lifecycle, recall).
- [[roadmap]] — current code state, the gap, and the dependency-ordered,
  replace-in-place migration to this target.
- [[datahike-primer]] — the source-grounded "work in datahike's grain" mindset (db
  is a value, only values cross the wire, CAS-as-assertion, basis-t caching). Read
  before touching the loop.
