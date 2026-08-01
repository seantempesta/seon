---
type: prd
status: active
tags: [prd, architecture, sci, context, agent]
---

# State of the design — end of 2026-08-01

Written at the owner's wind-down as the one synthesis of the day: what
the design now is, why it hangs together, what is actually built, and
what genuinely remains unknown. Read this first after a context restart;
`unsettled.md` carries the operational edge, `README.md` the rulings.

## The one picture

An agent lives in a real Clojure REPL. Everything else follows from
that sentence, and — this is the day's result — the pieces stopped being
separate mechanisms:

- **The cluster's sci context IS its program graph** (rulings #27/#29).
  Built once at cluster start (measured 0.1 ms), live for the cluster's
  lifetime, shared by every agent in it so one agent's improvement is
  immediately another's. Never rebuilt per turn.
- **The defining form is the truth; a stored value is a cache**
  (stateless-resume finding). So the transcript, the replay, and the
  session image are ONE artifact read three ways: print it and you have
  the context, evaluate it and you have the state, fork the branch and
  you have someone else's session.
- **One bounded walk, one print dispatch, two sinks** (ruling #26).
  Admission stays the single safety walk; `seon.print` is a
  print-method-shaped dispatch over its closed grammar, writing text for
  the agent, hiccup for the human, or both in one pass. The context and
  the debug display are the same bytes in two projections — not two
  renderers that must be kept in agreement.
- **Facts and content-addressed blobs** (ruling #25). Generous caps that
  only fire on genuine garbage, with the full value surviving as a blob
  keyed by digest — so branching is free, dedup is automatic, aging is a
  render-time choice, and nothing is destroyed at commit.
- **The platform exercises itself.** Graders and the overseer work
  entirely in fact-space on Datahike branches: prepare a surface by
  transacting rows, grade by forking the ending commit, iterate the
  bootstrap by comprehension over prior generations — no second
  toolchain, and every grading action is itself an auditable session.

The elegance test the owner set — "is this simpler than it was?" — the
day passes it in one specific way: four things we were treating as
separate machinery (context rendering, debug UI, replay, session
persistence) turned out to be the same two mechanisms (the fact-derived
form log, and the one bounded print). Nothing was added to make that
true; things were deleted or unified.

## What is actually BUILT (not designed)

- REPL-native door edges: Clojure-shaped arity errors, bare `dir`/`doc`,
  `doc` derived from `:seon.fn` facts.
- Contract-violation messages bounded by the one general printer.
- `seon.db` `q`/`pull`/`pull-many` with dynamic custody — agents can
  query the graph (the exam query returns 7 uncapped through the door).
- MCP toolset: bounded messages, cause + first-party-frame error
  envelopes, alive-first `runtime_status`, trim-in-place truncation,
  positioned multi-form refusals.
- Admission `inst?` hotspot: 4,436 → 380 bytes per node.
- In flight at wind-down: the caps/blob/print-floor wave; the REPL
  parity gate (the 59-row mined checklist as tests asserting STOCK
  behavior so our failings surface as named divergences).

Everything else in "the one picture" is designed and measured with a
slice 1 ready, but NOT built: per-cluster contexts, the live-graph
change, stateless resume, the print dispatch, the grader loop.

## Where iteration is genuinely still needed

Three kinds, honestly separated:

**1. Mechanical, decided, just unbuilt.** Per-cluster ctx + live graph
(283 ms leaves every turn), stateless resume slice 1, the print path,
the parity gate's divergence backlog, `acquire!` per-row containment on
the cold path, the sci 17-var residue, the `admission-source`
memoization. No unknowns; these are queued work.

**2. Designed but awaiting one owner decision.** Stateless resume:
forms-only slice 1 versus forms+value/blob (orchestrator lean:
forms-only — the honest core, cache later, derived not tuned). The
`:my/*` key set. Namespace-lane ownership enforcement.

**3. Genuinely unknown — needs experiment, not more design.**
- **The bootstrap content.** What actually teaches a model to work
  here is an empirical question. The plan: hand-build it right, get
  evidence agents can work at all, then let the overseer optimize
  ordering and instructions, then per-model calibration plus one tested
  generic. No amount of further design substitutes for running it.
- **The agent write surface.** Agents can read the graph but have no
  way to record their own domain facts; today writes are values the
  driver commits. Shape undecided (return-a-transaction versus a
  `transact!` through the door). This is the largest hole in "agents can
  do everything within reason."
- **Java interop policy.** A computed rule over sci's `:classes`, never
  a hand list — mechanism not yet designed.
- **The effect door.** `seon.effect` does not exist; capability
  crossings (fs, web, llm) are unbuilt, which is why replay-safety is
  trivially true today and will not stay that way.
- **Store economics at generation scale.** ~1.5 MB per transaction
  regardless of payload, ~1.9 GB projected per 20×20 generation, GC that
  reclaims only after retirement, and the unadopted index-root fusion
  (~5× win) waiting in our pin.

## The answer to "elegant, or more research?"

The CORE is elegant and settled: the design collapsed four mechanisms
into two, every load-bearing claim is measured, and the remaining
mechanical work has slice 1s with acceptance evidence. The BOOTSTRAP is
not a design problem at all — it is an experiment we have not yet run,
and the platform for running it (graders in fact-space) is itself
designed but unbuilt. The one real design hole is the agent write
surface.

So: build the queued mechanical slices, decide the two open questions,
design the write surface, and then the first real experiment tells us
what the bootstrap should say. More planning past that point would be
planning without evidence.
