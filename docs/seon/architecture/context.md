---
type: architecture
status: active
tags: [architecture, agent]
---

# Context — the dynamic context system

> **Target design** (present tense). The render/block mechanics live in
> [[ui]] (context and page are twin projections of the same blocks); turn
> replay and inspection live in [[observability]]; the measured laws that
> constrain this design live in [[laws]]. We-are-here: [[roadmap]].

The prompt is a **function**: `context = f(db, location, window, tail)` —
one frozen db value, the agent's current namespace, a sliding transcript
window, and a fully-dynamic relevance tail. Nothing is accumulated;
every turn re-derives the whole thing from blocks sorted by
`:seon.agent.ctx/priority`. Priority order IS cache architecture: the
context is assembled as a **gradient of dynamism**, stable bytes first,
churning bytes last, because everything after the first changed byte is
uncached regardless.

## Band 1 — the stable prefix (cheap because it holds its bytes)

The front of the prompt barely moves between turns, so the provider
prefix-cache holds across the agent's whole life:

- **System text + purpose** — fixed role, the agent's `purpose` (the
  why that outlives every run).
- **The plan anchor** — the agent's open plan: goal narrative, steps
  done/current/next, a "you are executing step N" position line. This is
  the long-term memory of *what I'm doing*; it is why the transcript
  window can stay small. (Plan modeling: [[data-model]]; the todo-tree
  evolves into plan semantics in place.)
- **Location — the current namespace is the agent's cursor.** Navigation
  uses plain REPL mechanics (`in-ns`); the context re-centers around
  wherever the agent stands: the current ns renders FULL real source
  (code + tests — it's all executable), its `:require`s render as compact
  cards (one-line docstring heads + verbatim `register!` calls), and the
  schemas referenced by those fns pull in even when declared elsewhere —
  so how data flows through the neighborhood is always visible. Everything
  else is dropped. Per-agent pins (`::full-source` / `::with-tests`
  presence-sets) widen the full tier; `my.*` composition verbs stay full
  everywhere (the render-prominence law).
- **Ordering inside the band**: namespaces sort by last-modified, so the
  rarely-touched code sits at the very front and edit churn sinks toward
  the band's end — the prefix-cache survives most turns even while the
  agent writes code.

Code grows slowly relative to tokens spent running things, so this band
is the compounding asset: as the agent persists schemas, fns, and tests,
its own code becomes the majority of its context — self-reinforcing,
cheap, and cached.

## Band 2 — the sliding window (the transcript)

Recent doing, windowed: the transcript renders in **age bands** with
per-band token caps and eval-result decay (a big result shrinks as it
ages). Two hard rules: **aged clips render byte-identical forever**
(re-flowing old text busts the cache every turn — the cache-stability
law), and the window is for *what I'm doing right now* — anything that
must survive longer belongs in the DB (plan items, kb rows, blobs), not
in transcript residue. Large inbound payloads clip-with-pointer once
stored (the blob ref replaces the paste). Only the window's leading edge
moves between turns.

## Band 3 — the free tail (fully dynamic, constrained, last)

The section rendered LAST, immediately before the generation point —
editor-typeahead for agents. It may **completely re-derive every step**
at zero extra cache cost; its only budget is a token cap (a config dial,
constrained by default). Its contents are *predicted relevance*:

- **Affordance surfacing** — the schemas of the values in the agent's
  recent evals joined against every fn's input spec (any namespace):
  "here is what can process what you're holding." This is a Datalog query
  over the program graph, possible because every public fn is fully
  specced over namespaced schemas — relevance is defined
  programmatically, not curated ([[think-in-clojure]] §1).
- **Retrieval hits** — semantic neighbors from the ONE embedding index
  (relevant fns, kb rows) for the current activity.
- **Ephemeral anchors** — anything useful enough to show *this step* that
  would be noise if it persisted.

The tail is an accepted, adjustable cost: it competes for tokens with
nothing cached, it vanishes when the queries return empty, and every
element in it must earn adoption in drives (measured, like everything).

## Inspectability — the UI twin of every band

Context and page are twin projections, so **every band has an html
representation the human can inspect**: the per-block prompt-text +
html-twin panes with per-block token bars (`/agent/{id}/debug`), the
canvas/world view of the same blocks, and — through [[observability]] —
the exact historical context of any turn (`inspect/turn`, `turn-diff`,
`ctx-preview` at any t, the prompt blob as byte ground truth). Watching
what the agent sees is a first-class UI surface, not a log dump: the
user debugging an agent and the forensic agent debugging it read the
same derived views.

## Configuration

Every dial is manifest data (`:seon.config/*`): which nses are always
full, the presence-set pins, transcript band schedule + decay, render
caps, the tail's token cap, per-agent overrides in agent scope. Absent
config = the default seed, byte-identical. No env-var side doors.

## See also

- [[ui]] — blocks, the two renders, seed-copy, `install!`/`remove!`,
  slots/layouts/pages, the live channel.
- [[observability]] — turn record, replay verbs, the blob store,
  the forensic agent.
- [[laws]] — cache-stability, render-prominence, always-on-beats-skills.
- [[think-in-clojure]] — affordance surfacing as the skills-killer;
  the meta-system.
