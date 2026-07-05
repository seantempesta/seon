---
type: architecture
status: active
tags: [architecture, agent]
---

# Context — functions applied to the db

> **Target design** (present tense). The block/render machinery lives in
> [[ui]]; turn replay + inspection in [[observability]]; the measured laws
> that constrain this in [[laws]]. We-are-here: [[roadmap]]. This doc keeps
> to Clojure primitives — `ns`, `defn`, `require`, var metadata, a db value
> — and reserves only the names backed by real code (`block` =
> `:seon.agent.ctx/block`, `render` = `:seon.render/*`, `db` = `seon.db`).

The prompt is nothing more than **functions applied to the db, in a stable
order**:

```clojure
context = (str/join (map #(% db) (render-fns-in-scope agent)))
```

Every turn re-derives the whole thing from one frozen db value. Nothing is
accumulated. Which functions are in scope, and in what order, is the entire
design.

## A render fn is a block and a tile — the twins

A `defn` whose input accepts the db and whose output carries a render key is
a **renderer**, and the keys present decide where it goes:

- `{:seon.render/ai …}` → a **block**: its string joins the agent's prompt.
- `{:seon.render/html …}` → a **tile**: its own hiccup surface on the
  agent's page (each block has its own separate tile — not a merged vector).
- **both keys → twins**: one value, two projections — the agent's context
  and the human's screen showing the same thing.

The **canvas** is a distinct, focal tile. Default: it shows the
**last-updated tile** — a pure function of the db
(`seon.agent.ctx.render-fns/last-updated-tile`): among the agent's own
authored tile fns (its `:seon.fn` rows whose tx provenance names the agent
and whose output schema declares the hiccup twin), the one most recently
*touched* — redefined, or a write (or retraction — the history view) to any
attr in its declared read-set (the stored `:seon.fn/read-attrs` — the
qualified keyword literals the tee walked off the read form; a regex over
the source text only for pre-structural rows), read off the datoms'
tx column. So the human's focus follows what the agent is actively doing
with zero ceremony: author a plan tile, write plan data, and the plan tile
is the canvas. Override: the agent pins the canvas to a specific tile
(`:seon.render.live-tile/content`) to feature it regardless of recency;
retract the pin to fall back to derived; with neither, the core welcome.
Derive the default, store only the pin — the same rule as everywhere else.
(Honest bound: a tile that reaches attrs only dynamically — never naming
them — follows only its own redefinitions.)

This is the block's two renders (`:seon.render/ai` / `:seon.render/html`),
now emitted by any in-scope `defn`, not only by seeded blocks. Its args are
the db value (all data is reachable from it — [[think-in-clojure]]); it
`require`s only the *code* it calls. It is pure over the frozen db, so it
re-runs safely every turn, is bounded + errors-as-values through the exec
service (a throw becomes a `:seon/error` tile, never a crash), and replays
byte-identically at `as-of t` ([[observability]]).

## Shared view — the agent knows the human sees it

Because the *same* function feeds both the prompt and the tile vector, the
agent and the human look at one derived value. An agent working in `my.plan`
runs its plan-view `defn`: the `:ai` twin puts the full plan in its own
context, the `:html` twin puts the full plan on the human's page. The agent
can rely on "my human is seeing this" — it is structurally true, no
messaging required. Planning in full detail *is* showing the human the plan.

## What puts a fn in scope — writing it, or pinning it

Two ways, one mechanism (a render fn run over the db); they differ only in
what makes the fn visible:

- **Being in its namespace (derived, zero ceremony).** The render fns of the
  agent's current `ns` are in scope. Authoring context is just writing a
  `defn` in the namespace it belongs to; move to that `ns` (`in-ns`, plain
  REPL) and its renderers run. Nothing stored — pure derivation from
  code-in-the-graph + `*ns*`.
- **`install!` (explicit override).** Pins a render fn to run *regardless* of
  `*ns*`, at a chosen priority, in a chosen agent's scope. Storage is the
  exception, for the non-derivable: always-on blocks (the plan anchor,
  warnings, the transcript), a hand-set priority, seeding another scope.

Rule: **derive the derivable, store only the overrides.** Both paths resolve
into one ordered list of `(render-fn, position)` the renderer walks — never
two rendering systems.

## Explicit dependencies — injected at the eval boundary

A tool or render fn's dependencies (the db, the calling agent, the current
time) are **declared in its request schema and injected once at the eval
boundary** — never read from an ambient dynamic var deep in the body. The
contract:

- A map-in fn declares an injectable as an **optional** request key —
  `:seon.db/db`, `:seon.agent/id` ("me"), `:seon.render/at` (now/basis-t),
  and whatever else the registry grows to hold. It is `{:optional true}` to
  the *caller* (may omit) but the wrapper guarantees it *present in the body*.
- On an agent call, the eval boundary inspects the fn's request schema, and
  for every **injectable key the schema declares that the caller left
  absent**, fills the current value from the eval context. Declared-and-
  present is never overwritten (explicit args win — the agent, a test, or a
  forensic replay can pass a different db/agent).
- The injectable **registry** is a small explicit map `injectable-key → (fn
  [eval-ctx] value)`: `:seon.db/db` → the turn's frozen db, `:seon.agent/id`
  → whose turn is running, etc. Adding a dependency = add one registry entry
  + fns declare the key. One mechanism; no second wrapper.
- The injectable contract has **one named request shape**:
  `:seon.render/section-request` (registered in `seon.render`) — an OPEN map
  naming exactly the registry's keys, each `{:optional true}` and referencing
  its registered schema. Every block/section/converter fn the render engine
  calls declares `[:cat :seon.render/section-request]`, never a bare
  `[:cat :map]` — the contract is greppable, and a wrong-shaped injectable
  (e.g. a string `:seon.render/at`) rejects at the instrumented boundary
  naming the schema. Open on purpose: the engine composes extra per-call
  keys (`:seon.render/node`, `:seon.agent/entity`, …); a semantically
  richer request (e.g. `:seon.agent.inspect/request`) stays its own schema.

This rides the **one instrumentation layer** (every schema'd fn whose shape
takes a wrapper is Malli-instrumented off the program graph — at boot, on
`start-agent!`, and re-asserted after every hot reload; the structural
exception is `^:async` non-simple shapes like `transact!`/`eval`, which
validate in their own body — and coverage is itself a derived invariant:
the root world's `:instrumentation-gaps` section recomputes the census per
render) — inject-then-validate-input, so the filled map satisfies
the `:map`. The result: a fn's spec IS the honest statement of what it needs,
the eval log shows real data flowing, and the value is reproducible at
`as-of t`. This is the one boundary of "clear magic" that lets `with-agent`
/ ALS stay the core's internal *source* for the injection without leaking
into every fn body.

The **scope-by-signature** rule falls out: a fn that declares `:seon.agent/id`
reads/writes **per-agent** data (it stamps `:my.plan/agent me` and filters by
it); a fn that does not is **global** (`my.kb`). You know where data goes by
reading the arglist — not from an invisible binding.

## Auto-run — the current `ns`'s render fns become context

The current-`ns` render fns don't need the agent to call them: the render
pass **queries the program graph** for fns in the current namespace whose
output schema is a render type (`:seon.render/ai` / `:seon.render/html`) and
**runs each through the same injecting wrapper** (they're map-in fns declaring
`:seon.db/db` + `:seon.agent/id`), bounded + errors-as-values. Their outputs
are the block/tile twins, positioned right after the stable code they belong
to. So a `defn` in the agent's namespace becomes live context automatically —
authoring context is writing a specced render fn, and the injection makes it
run with no arguments the agent has to supply.

## Order = stability, so the cache holds

Position is sorted by **change-time** (a property of the var / the source),
so the prompt reads most-stable → most-dynamic and the provider prefix-cache
survives most turns. A fn busts cache only when *its own* code or *its own*
db-inputs changed — invalidation stays local to the fn that moved, because
order is deterministic:

1. **reference-code namespaces** — vendored source, effectively frozen.
2. **the agent's code namespaces** — current `ns` full source (+ its
   `require`s as compact cards, + the schemas those fns reference), sorted by
   last-modified so rarely-touched code sits earliest and edit churn sinks to
   this group's end.
3. **the current `ns`'s render fns** — the twins above; they *follow* the
   stable code they belong to. Their output moves with the db, so this is
   where the cache prefix ends.
4. **the transcript** — recent doing, windowed by age with per-band caps and
   eval-result decay; **aged clips render byte-identical forever** (re-flowing
   busts the cache — the cache-stability law). Only the leading edge moves.
   What must outlive the window goes to the DB (plan, kb, blobs), not
   transcript residue; a large inbound payload clips to a blob ref.
5. **predicted relevance, last** — the only recompute-every-step region, a
   capped token budget (config dial): fns whose *input* specs match the
   shapes the agent is holding (a graph query — [[think-in-clojure]] §1) and
   embedding neighbors for the current activity. Competes with nothing
   cached; vanishes when its queries return empty; every element earns its
   place in drives.

Code grows slowly against tokens spent running things, so groups 1–2 are the
compounding asset: as the agent persists schemas, fns, and tests, its own
code becomes the majority of its context — self-reinforcing, cheap, cached.

## Inspectability — the human twin of every position

The `:html` twin means every context position has a view the human can
inspect: the per-block prompt-text + hiccup panes with per-block token counts
(`/agent/{id}/debug`), the agent's page showing the same tiles, and — through
[[observability]] — the exact historical context of any turn (`inspect/turn`,
`turn-diff`, the prompt at `as-of t`, the prompt blob as byte ground truth).
The human debugging an agent and the forensic agent debugging it read the
same derived views.

## Configuration

Every dial is manifest data (`:seon.config/*`): which namespaces render full,
the presence-set pins, the transcript band schedule + decay, render caps, the
predicted-relevance token cap, per-agent overrides in agent scope. Absent
config = the default seed, byte-identical. No env-var side doors.

## See also

- [[ui]] — the block, its two renders, the tile vector, `install!`/`remove!`,
  the live channel.
- [[data-model]] — `my.plan` (the worked example: its plan-view `defn` is the
  twin an agent sees and the human watches), the `my.*` schemas.
- [[observability]] — turn record, replay verbs, the blob store.
- [[laws]] — cache-stability, render-prominence, always-on-beats-skills.
- [[think-in-clojure]] — a fn's specced in/out is the query substrate for
  both rendering and running.
