---
type: prd
status: active
tags: [prd, runtime, agent]
---

# Transfer prompt — 2026-08-13 session

You are inheriting Seon after the self-generating-context arc (2026-08-11/12):
50 owner rulings sealed, W1/W2 landed and proven, the evolving-session
implementation spec ruled-complete, the integration gate RED on two
never-returning agent properties. Your job begins with UNDERSTANDING, not
motion.

## Do not rush. Do not assume. Confirm the plan with the owner first.

The failure mode this prompt exists to prevent: launching lanes on a
partially-understood plan. The owner iterates in chat, decides fast, and
hates re-litigating — but he hates unmarked assumptions more. So:

1. READ END TO END before anything (never grep a named authority):
   [self-generating-context-prd-2026-08-11.md](self-generating-context-prd-2026-08-11.md)
   (the one ordered ruling list 1-50),
   [evolving-session-implementation-2026-08-12.md](evolving-session-implementation-2026-08-12.md)
   (the executable spec), [unsettled.md](unsettled.md) (the working edge,
   the handoff, and the issue campaign), and [README.md](README.md)
   (`gate -> proof -> drive -> phases`).
2. VERIFY the load-bearing claims live before building on them
   (PROVEN-LIVE / CLAIMED / UNKNOWN discipline): the gate evidence at
   `tmp/test-runs/run.ZyS5O7` (the two wedged properties + the
   virtual-thread dump), that generated episodes actually derive on a
   scratch root at HEAD, and the state of any lane summary you rely on.
3. THEN present to the owner, BEFORE any lane launches: your understanding
   of the plan in your own words, the first 2-3 moves you propose, and
   every open question as AskUserQuestion rounds with 2-4 priced options
   and a recommendation first. He answers in rounds and enjoys it. Never
   leave a decision parked in a document "awaiting markup" — ask the
   moment it exists. Proceed only after he confirms you have it right.

## The expected first moves (PROPOSE these; do not presume them)

1. Fix the two never-returning `seon.cluster.agent-test` properties (the
   property/fixture lifecycle under the new generate machinery — dump
   retained), then rerun ONE `bin/test --all` for the honest tally; green
   lifts the standing no-fleet constraint.
2. Run the [rebirth capability proof](../research/rebirth-systems-sweep-2026-08-12.md)
   — probe committed at `tmp/rebirth/`; the side-by-side lived-vs-reborn
   history is the owner's "onto something big" artifact.
3. The first drive on a fully generated episode (flash only; independent
   observer; MINIMUM re-measure).
4. Then the implementation phases per the spec, with the issue campaign's
   class-kill lane always running beside the spine.

## Hard-won session lessons (standing)

- Worked examples and taught forms must be things the machinery GENUINELY
  emits — the owner rejected invented content twice; ruling 37 is the scar.
- Every design must pass rulings 47/48: current state renders from facts
  alone; prose dies at rebirth by design.
- Lanes: bin/codex-agent run/resume BARE (never piped); commits as
  heartbeats; a foreign lane's breakage never blocks your commit; a lane
  emitting `collab: Wait` is STUCK (no task tree exists in a lane) — stop
  + resume with "execute directly, never wait on or spawn agents".
- The issue index gate blocks everyone — reconcile it at boundaries, never
  let lanes trade conflicts over it.
- Sober summaries, broken things first, full repo-relative markdown links.
