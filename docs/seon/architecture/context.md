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
**last-updated tile** — a function of the db (the block-tile whose data most
recently changed), so the human's focus follows what the agent is actively
doing with zero ceremony. Override: the agent pins the canvas to a specific
tile (`:seon.render.live-tile/content`) to feature it regardless of recency.
Derive the default, store only the pin — the same rule as everywhere else.

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
