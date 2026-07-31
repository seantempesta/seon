---
type: prd
status: active
tags: [prd, agent, context, render]
---

# The context MVP — drive real turns on the walk, ASAP

Owner directive 2026-07-31: an MVP that drives agent turns for live
experimentation. The thesis to test: an agent that SEES it is in a REPL
from the first byte — because its context visibly IS the output of
`(seon.render/walk)` and every function in view is really callable —
bootstraps itself without pages of prose. "To make it real it has to be
real functions and really how the data is rendered and returned."

Contract authority: `context-render-data-model-spec.md` + README rulings
#1–#13. This file scopes the MVP cut only.

## In (the cut)

1. **The walk labeling layer** (`seon.render.walk`, ai projection):
   `;;` headers per unit — path (get-in drill handle), depth,
   provenance; grouped last-changed order, stable front / churn tail /
   ties clustered by branch; ONE walk header line with root + basis +
   depth.
2. **Assembly = the walk** (`seon.cluster.prompt`): delete block
   composition; context = walk projection at the turn's basis,
   displayed as the agent's opening eval. No receipt written for it.
   The REPL state line (namespace, basis, time) is the LAST line —
   the deliberate cache boundary.
3. **Ensure-entity init**: idempotent create-if-absent (id, namespace,
   cluster ref); existing entity always resumes untouched.
4. **The transcript branch** inside the walk (W2b's projection wired
   as the message/run branch renderer).
5. **`:seon.cluster/toolkit`** on the cluster entity — computed
   membership (public contracted `my.*` namespaces from the corpus),
   rendered as compact cards at d2.
6. **The `:getting-started` instruction row** — deliberately tiny
   (draft, owner-editable, the whole point is the walk teaches):

   > This is a live Clojure REPL. Everything above is the output of
   > `(seon.render/walk)` — run it yourself with `:depth`/`:root` to
   > see more. Your reply is read as forms and evaluated in your
   > namespace. A `defn` with `:malli/schema` becomes permanent;
   > anything else is scratch. Talk to other agents with
   > `(my.message/send! …)`. Prose lines are kept as `;;` comments.

7. **The seeded-block deletion** (the eleven, plus the five superseded
   `context.clj` projections) — dies in the same commit assembly
   converts; tests pinning them die with them.
8. **The drive harness**: a tmp/ REPL script (committed) that creates
   a nursery agent in a scratch cluster, sends it messages, and prints
   each turn's exact context + reply — the experiment surface. Local
   Ollama first, DeepSeek for real runs (both pre-authorized).

## Out (explicitly deferred)

- The `:my/*` rename (ruled #15; dispatches as one atomic wave after
  this lane lands — MVP ships on current keys).
- HTML page membership inversion + floor checkbox — NOT deferred
  (owner, 2026-07-31 evening: "full rendering working for both ai and
  html"): W4-html runs as the PARALLEL track, implementation
  dispatching on its falsified plan, landing alongside or immediately
  behind this lane rather than after the MVP proves anything.
- Per-agent render proc, call-grain cache wiring, attribute-revision
  invalidation (context derives fresh each turn — correct under
  freshness-outranks-cache, just uncached; optimization lands after
  the MVP proves the shape).
- Packages/keyframes delivery; lifecycle surface; oversight anything.

## Exit measure

A fresh nursery agent, told ONE SENTENCE by message, completes a real
multi-turn task that requires: writing a contracted defn, calling a
toolkit function, and reading something from its own walk (e.g. answer
"what schemas does my.message use?"). Judged as an agent eval on the
live drive, not a code review. Second exit: the `seon.flow`-owner
birth context prints sanely at d1/d2 budgets.
