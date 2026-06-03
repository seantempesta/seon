---
type: prd
status: draft
tags: [prd, agent, database, flow]
---

# Reactive agent topology — agents, subagents, and reactive context

> The unit of work is not a conversation. It is a standing set of derived views
> that agents keep current. A conversation is one window onto them.

Vocabulary is canonical per [glossary](glossary.md) (agent, subagent,
subscription, summary, render function, reactive engine, notification, cluster).

## Thesis

Other harnesses give a user "separate chats": each chat re-establishes context
from scratch, nothing watches the world between chats, and the computation an
agent did is thrown away when the chat ends. Seon replaces that with a **shared
immutable database** over which **agents are standing reactive computations**.

- An **agent** registers a **subscription** (a standing reactive query), observes
  (the database plus external effects — web, MCP, tools), and writes an AI-text
  and HTML **summary** to its own entity (`:seon.render/ai` + `:seon.render/html`).
  It wakes cheaply on each transaction, re-runs its query, and only updates /
  notifies when its result actually changes. Asleep, it costs zero tokens.
- An agent that is **managing other agents** subscribes to its **subagents'**
  summaries and chats with the user. The user interface is a second consumer of
  the same summaries. Both re-render when a summary changes — "React with a
  central store," applied to an agent's context and to the UI at once.

Agents and subagents are **the same kind of thing** (same capabilities, same DB
access, same code-writing) — "manages others / talks to the user" is what an
agent *does*, not a separate kind. The top-level agent watching a subagent is
just an agent subscribed to another agent's output.

This extends the existing [reactive-context](../../seon/concepts/reactive-context.md)
principle (a surface is a function of the DB at render time; self-healing because
nothing stored needs clearing) from cheap pure Datalog sections to expensive
LLM-authored derivations, and the [code-as-data-runtime](../../seon/concepts/code-as-data-runtime.md)
principle (the agent's defining forms are entities) to the agent's subscription
and summary.

## The mental model: a team of agents

Tell it as a team if it helps the intuition: a top-level **agent** is given a
goal by the user — "watch the eastern approach, report movements, notify me if
anything changes." It launches a **subagent** to do the narrow work. The subagent
takes a **subscription** and monitors. It feeds the top-level agent AI + HTML
**summaries**. It stays in place, re-rendering cheaply, until **no agent is
subscribed to it** — at which point it goes dormant. It is never deleted: its
code and summaries remain in the database, queryable and resumable. A later
top-level agent (a different "chat") can discover existing subagents and
re-subscribe rather than re-launching the work.

This is the differentiator stated precisely: separate chats hold **disjoint
state, re-derived every time**; here we have **one shared substrate, different
subscriptions**. Two agents can subscribe to one email-monitoring subagent — its
work is reused, not re-run. That reuse is structurally impossible in the
separate-chat model.

## Formal model

The math is worth stating because it names the problems we must own. It also
shows that one design choice (summary-as-function) dissolves about half of them.

### State and clock

The database is a single value on a monotonic clock — datahike's `basis-t`:

```text
D_0 --Δ_1--> D_1 --Δ_2--> D_2 --> ...     t in ℕ

```

Each transaction `Δ` has a **write-set** `W(Δ) ⊆ Attrs` (the attributes it
touches). `D_t` is the db value at basis `t`.

### Views, patterns, the wake condition

Every derived thing — a section, an agent's summary, the UI — is a function of
the DB, `f_i : D → V_i`, reading only part of `D`: its dependencies `R(f_i) ⊆
Attrs`. The incremental-view-maintenance fact:

```text
f_i(D_t) ≠ f_i(D_{t-1})  ⟹  W(Δ_t) ∩ R(f_i) ≠ ∅

```

So a subscription has **patterns** `I_i` (derived from its query) and wakes iff
`W(Δ_t) ∩ I_i ≠ ∅`. The ideal is `I_i = R(f_i)`. Two failure modes:

- under-match (`I_i ⊊ R(f_i)`) → misses a relevant change → stale summary;
- over-match (`I_i ⊋ R(f_i)`) → wakes on irrelevant txns → wasted re-runs.

Datalog sections get `I_i = R(f_i)` for free — the query *is* the dependency
declaration. See [Wake-up precision](#wake-up-precision).

### The system is self-referential — a dynamical system, not a clean fixpoint

Agents are entities *in* `D`, and their summaries are part of `D`. So with the
external world `E` (email, web, MCP):

```text
s_i = f_i(D, E)        D = (⋃_i s_i) ∪ (external-ingested data)

```

i.e. `D = F(D, E)`. Reactivity is the system relaxing toward a fixpoint as `E`
changes. But `f_i` is non-deterministic and depends on `E`, and the dependency
graph is itself data in `D` that agents rewrite (changing subscriptions,
launching/stopping subagents). It is a **self-modifying dataflow graph**. The
realistic target is not "unique fixpoint" but **eventual quiescence given stable
`E`**: a `T` after which no agent's change-gate fires. The design questions become
*does it settle* and *does it oscillate*, not *does it converge to the answer*.

### The two-timescale collapse (the big simplifier)

A summary can be **materialized** (`s_i = f_i(D_t)` — a baked string that goes
stale) or **virtual** (`ŝ_i = λD. g_i(D)` — a cheap pure projection re-evaluated
against current `D`, never stale). We choose virtual, and split the agent's job
onto two timescales:

- **Slow loop (expensive, LLM, rare):** when its understanding no longer fits the
  data, the agent *recompiles* its render function `g_i`.
- **Fast loop (cheap, pure, every render):** `g_i(D)` projects the live DB.
  Values update reactively for free; no LLM in the path.

```text
f_i(D, E)  ⇝  g_i  (a pure D → V)        summary value = g_i(D_current)

```

The LLM is a **compiler** from "messy world + goal" to "cheap reactive
projection." This collapses summaries and `:seon.render/*` section functions into
the same mechanism — the agent authors a render function; the runtime evaluates it
like any other section. It dissolves staleness (virtual views never go stale
between recompiles) and most propagation cost (value-only changes never invoke the
LLM).

This split is exactly the **Convex query/action distinction**: a pure re-runnable
query (`g_i`, safe to re-execute on every overlapping write) vs an effectful
action (the LLM recompile, deliberately invoked, never auto-re-run). **Rule: a DB
change re-runs the render; it never re-runs the LLM.** The recompile is gated on a
real structural mismatch plus a relevance judgment.

### The cascade, and controlling update cycles

An agent's write changes `D`, which can wake the agent watching it, whose write
can wake the UI. Termination requires either acyclicity (the dependency graph is a
DAG) or **contraction** — each re-derivation produces a change "smaller" than its
trigger. The contraction is the **semantic change-gate**: propagate only if a
distance `d(s_old, s_new) > ε`. Two hazards:

- **Non-determinism breaks quiescence.** If `d` measures token identity, a
  reworded-but-equivalent summary reads as changed and the system never settles.
  Compare *semantic content* — or, with the two-timescale split, compare the
  *function* `g_i` (a structural diff, far more stable than prose).
- **Unbounded launching.** Since agents launch subagents, quiescence needs a
  launch budget / depth limit.

For anything user-visible, render against a **single pinned `db-after` value** (a
logical timestamp) so consumers see a coherent snapshot, never a half-settled
cascade. datahike's immutable db values *are* that timestamp. Genuinely cyclic
reactivity (A wakes B wakes A) is not solved by DAG-based frameworks; only
differential dataflow's iteration timestamps handle it. We avoid cycles where we
can and damp the rest with the change-gate.

### Cost is the objective

```text
Cost = Σ_i (recompile-rate_i · c_i)   subject to   staleness_i ≤ bound_i

```

Debounce falls out of the lossy-stream model: an agent samples `D` at its own
processing rate, so a burst of inputs during one derivation collapses to one
catch-up. The two-timescale split crushes the constant factor: recompile-rate ≪
wake-rate, because most wakes are cheap re-renders.

## Wake-up precision

How a subscription's **patterns** are derived is the keystone. The mechanism is
the same one Convex / Solid / MobX use: the dependency set is observed or
extracted, not hand-declared. We have a spectrum, and the design combines tiers:

1. **Static extraction from the query (Posh-grade).** A Datalog query is data;
   its `:where` clauses name the attributes (and any literal e/a/v) it reads.
   Walk them to get the patterns. Cheap, leans on code-as-data, and cannot
   under-match because it reads the real query. Posh refines this: `pull` (rooted
   at an eid) yields entity-precise patterns; `q` yields attribute+literal
   patterns. See the [posh port research](research/posh-port-and-platform-state-2026-06-03.md).
2. **Filtered-DB upper bound (always-correct safety net).** If an agent only sees
   `σ_i(D)`, then `R(g_i) ⊆ attrs(σ_i)`, so the filter bounds what can wake it —
   simultaneously a scope/capability bound and a wake-up bound. datahike's
   `filter` gives this.
3. **Runtime read-tracking (Convex-grade, the eventual general mechanism).** Wrap
   the db handed to the function in a recording proxy; the attributes it touches
   during evaluation *are* the patterns, observed not declared — making
   under-match structurally impossible.

Only observed/extracted patterns make the wake condition both *sound* (never miss
a relevant change) and *tight* (never spurious). The dual of this — a tx's
*modified* attributes — is what datahike already computes internally
(`propagate-query-cache`), so the two operands of `W(Δ) ∩ I_i` are both cheaply
available.

## Reuse: Posh as the reactive engine

Posh (vendored at `reference-code/posh/`) is this topology minus the LLM. Its
engine is a `cache` of `{query → {:reload-patterns, :results, :pass-patterns}}`
plus a **single tx-listener** that, per commit, runs a **two-gate dispatch**
(`posh.core/after-transact` → `cache-changes`):

1. **Cheap gate:** `datom-match?` of each query's `:reload-patterns` against the
   tx datoms — a pure pattern match, no query run. Most txns die here.
2. **Re-run + change gate:** only on a match, re-run the query; keep it as
   `really-changed` only if the new result `≠` the cached one.

This is precisely the agent lifecycle: cheap to wake, re-run, and if nothing
changed go back to sleep. Posh is datastore-agnostic via a `dcfg` map
(`:q :pull* :entid :transact! :db :filter :conn?`); the reagent ratom layer is
optional — the pure engine is `posh.core` + `posh.lib`, driven by our own
on-change off `after-transact`'s `:changed`.

Verified this session:

- datahike's native `listen!` fires **synchronously** after commit with the full
  `TxReport` (`reference-code/datahike/.../core.cljc:206`, fired at
  `writer.cljc:247`).
- `datahike.query/q` runs **synchronously** over the realized `:db-after` value
  the listener hands us (confirmed in the CLJS guest). This is what makes Posh's
  synchronous-inside-the-listener engine viable on datahike.

### Where the engine lives: host-side, one per conn

In the V2 platform the reactive engine runs on the **JVM host**, inside datahike's
`listen!` callback — not in the (async, wire-only) guests. It is a single
**subscription manager** (Convex model) per datahike conn:

- The wire broadcast changes from "raw datoms to every subscriber" to a second
  event type: **"the agent summaries that really-changed + their new results"**
  (keeping the raw `tx` event for cache-priming and own-tx dedup). The host does
  the pattern routing once; each guest is told exactly what changed. This is a
  strict win over the current broadcast model.
- **Multi-cluster falls out:** engine-per-conn maps 1:1 onto the database
  registry. Agents that *share* a database share one engine and see each other's
  writes — the shared substrate for running parallel agent strategies and
  selecting for competence.

### Our optimizations over stock Posh

- Entity-precise patterns for `q` (bind qvars from results), not just `pull`.
- Drop Posh's in-memory `:dbs` for `(d/db conn)` / `as-of` (datahike has real
  basis-t time-travel; Posh maintained its own because datascript does not).
- Do **not** reuse datahike's internal query cache as the routing table.
- Split pure in-listener observation from the debounced, relevance-gated LLM
  recompile (the query/action split above) — never block the writer thread on an
  LLM call.

### Risks

datahike `Datom` seq-access inside the matcher, exact `entid` / lookup-ref
semantics vs datascript, temporal/`as-of` dbs interacting with Posh's `dbs`, and
multi-conn. None are blockers; all are first-milestone test targets.

## Messaging is transacting; notifications are ephemeral

The whole topology rides on one invariant that keeps the single-source-of-truth
intact even as agents steer subagents:

- **All facts are transactions.** Messages, commands, summaries, subscriptions,
  observations, agent code — every authoritative thing is a datom. "Telling a
  subagent to do X" is a *write* to the subagent's entity; the subagent (a
  subscriber of its own entity) wakes and reads it. There is no direct
  agent-to-agent channel.
- **Notifications carry no authoritative information.** The wake-up signal only
  says "your subscription matched, go look." It is lossy-safe: drop it and nothing
  is lost, because the data is in the database (recover by querying basis-t). A
  notification *may* carry a copy of already-transacted data as a cache
  optimization; it may never be the only copy of anything.
- **Effects live at the edges.** Inbound effects (web, email, MCP, timers, the
  user typing) *write* what they observe. Outbound effects (LLM calls, sending to
  the user) *read* an intent datom and act, then write a "done" datom
  (outbox/idempotency, so a replay never double-sends). The interior is pure
  reactive readers.

The consistency test: **the entire system replays from the tx-log alone.** A
channel that carried a message payload would fail that test; a pure wake-up does
not, because the tx that should have woken you is in the log. This is also why the
MVP (one database) and the multi-cluster version (N databases) run the **same
interior code** — multi-cluster is "the same readers, N databases."

## Current platform state (what we build on)

From the [platform + posh-port research](research/posh-port-and-platform-state-2026-06-03.md)
and the platform track's [clusters + multi-DB wiring plan](clusters-and-multi-db-wiring-2026-06-03.md):

- `src/seon/server/` (7 ns), ~61 tests green. All wire ops work; `as-of` /
  basis-t time-travel wired; the database registry exists and is tested
  (`seon.server.registry`, currently `session.clj` pending the P1 rename:
  `{db-name → conn}` + `{agent-id → db-name}`).
- **No reactive engine exists** — `d/listen!` is called nowhere server-side;
  broadcast is raw datoms, fired imperatively after commit
  (`wire.clj:276` → `broadcast.clj`), unfiltered.
- datahike embedded, one conn per database, `:memory` + `:file`,
  `:keep-history? true`.

### Prerequisite gaps (owned by the platform track's P1)

1. **Broadcast ↔ database registry not wired** — `db-name` is hardcoded
   `"default"`, the pub-chan is always nil. No reactive routing can target a
   specific database until this is connected.
2. **No agent / summary / subscription / function entities persisted
   server-side** — that machinery is V0-pod-only. Schema is authored **fresh
   server-side** (decided 2026-06-03), not ported from the pod.

## Lifecycle and persistence

- An agent is a database entity. Its subscription (its **patterns**), its raw
  observation (its query `:results`), and its **summary** (`:seon.render/ai` +
  `:seon.render/html` on its own entity) are all data — glanceable: you can see
  what an agent watches, what wakes it, and what it reports, by querying.
- **Activity is tied to subscription**, not to a conversation. An agent with ≥1
  subscriber stays active; with none it goes dormant. Dormant ≠ deleted — code and
  summaries persist; the agent is resumable at any basis-t.
- **Agent lifecycle is decoupled from conversation lifecycle.** Monitors outlive
  the conversation that spawned them — that is the point. The user needs a derived
  view over the agent registry ("what is running on my behalf") and the ability to
  subscribe / unsubscribe / reap.
- The top-level agent's view of N subagents must be **hierarchical**: a one-line
  status manifest per subagent, expand-on-demand (a query), so its own context
  window does not fill with summaries as subagent count grows.

## Proactive interruption (the riskiest surface)

"Wake the top-level agent, which proactively messages the user" is the novel
product win and the thing that makes the system feel magical or insufferable. The
default must be **ambient** (the summary is silently always-current; the user
glances when they want). Proactive push is a deliberate escalation an agent
decides per item, against a user-set policy expressed in conversation. Treat "may
I interrupt the user" as a first-class, conservative, tunable gate from day one.

## Milestones

1. **Headless posh-on-datahike proof (~1 day).** A `posh.clj.datahike` dcfg +
   `posh.core` driven by a real `d/listen!` on the JVM host. Prove an agent's
   subscription wakes *only* on relevant txns (cheap gate) and reports via
   `:changed` only when its result actually moves. No LLM, no wire — pure engine on
   the real DB.
2. **Wire the prerequisite gaps** (platform track P1). Connect broadcast ↔
   database registry (kill the hardcoded `"default"` / nil pub-chan); persist
   agent entities as datoms (fresh schema).
3. **Changed-summary broadcast event.** Add the second wire event type carrying
   really-changed agent summaries + new results; keep raw `tx` for dedup.
4. **Summary as render-function.** An agent writes a `:seon.render/ai` +
   `:seon.render/html` render function; the runtime evaluates it reactively. The
   patterns extracted from its query are the wake interest.
5. **Consumers.** A top-level agent subscribes to subagent summaries; the UI
   subscribes too. Both re-render on `:changed`, against a pinned `db-after`.
6. **Multi-cluster.** Engine-per-conn over the database registry; shared-database
   populations for parallel agent strategies + competence selection.

## Open decisions

- Patterns tier to ship first: Posh-grade static extraction (recommended) vs
  building Convex-grade runtime read-tracking. Filtered-DB bound applies either
  way.
- Whether the semantic change-gate compares rendered text or the function `g_i`
  (favor the function — stabler against non-determinism).
- Launch budget / cascade-depth limits for quiescence.
- The proactive-interruption policy language.

## References

- [glossary](glossary.md) — canonical names for both tracks.
- [clusters-and-multi-db-wiring-2026-06-03](clusters-and-multi-db-wiring-2026-06-03.md)
  — the platform track's P1; this design's Milestone 2.
- [reactive-databases-survey-2026-06-03](research/reactive-databases-survey-2026-06-03.md)
  — Convex, Differential Dataflow, Solid/MobX read-tracking; the prior art.
- [posh-port-and-platform-state-2026-06-03](research/posh-port-and-platform-state-2026-06-03.md)
  — current platform state + the port plan with verbatim code.
- [reactive-db-sandbox-design-2026-05-29](research/reactive-db-sandbox-design-2026-05-29.md)
  — the wire/wasm reactive plumbing (lossy wake-up, basis-t catch-up).
- [reactive-context](../../seon/concepts/reactive-context.md) and
  [code-as-data-runtime](../../seon/concepts/code-as-data-runtime.md) — the
  principles this extends.
