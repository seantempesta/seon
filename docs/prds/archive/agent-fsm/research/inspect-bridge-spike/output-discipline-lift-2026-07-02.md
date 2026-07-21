---
type: research
status: completed
tags: [research, agent]
---

# Output-discipline lift — memory-QA pass^4 0.000 → 1.000 (D1)

## TL;DR

The B2 baseline (`pass^4 = 0.000` / raw `0.328`) failed on OUTPUT DISCIPLINE,
not memory. A single GENERAL standing teaching in the always-on `system-text`
— "FINISHING IS AN ACT, NOT A DRIFT" — lifted the same 16-sample memory-QA
battery to **`pass^4 = 1.000` / raw `1.000`** (all 64 epoch cells correct). The
fix is not answer-shaped and not benchmark-aware — the dataset was never read.
KEPT (SHA `17379b4a`).

- **pass^4:** 0.000 → **1.000** (0/16 → 16/16 samples correct on all 4 epochs)
- **raw cell accuracy:** 0.328 → **1.000** (21/64 → 64/64)
- **closed_reason:** 64/64 drives `:completed` (baseline rambled to `:turn-limit`
  / `:timeout`); mean **3.3 turns** vs baseline 19-20.

## Diagnosis — why the weak agent rambled

Read from the ACTUAL agent-facing context (`seon.agent.ctx/system-text`) + the
live `/solve` drive (agent `aXJ-2607020909`: 19 turns → `timeout` → empty reply,
reproducing the baseline exactly) + the baseline's live-verified transcripts
(orion e1: *"the full cycle works"* then narrates → hits 20 turns).

The context has STRONG "keep acting" pressure and a WEAK, mechanics-only "you're
done" signal:

1. **Completion was framed as lifecycle MECHANICS, not goal recognition.** The
   only concrete "finishing means delivering + closing" text was gated behind
   *"IF YOU WERE SPAWNED BY ANOTHER AGENT"* — a conditional the weak model does
   not reliably map to itself. "WHEN YOU ARE DONE, say so with a verb" was buried
   after a wall of delegation/spawn doctrine.
2. **Several teachings actively invite the post-goal ramble:** "SHOW, DON'T TELL:
   your live tile is your PRIMARY surface" (→ tile-fiddling), "you can message
   EVERY turn … silence is the failure" (→ re-narration), "Storing IS the
   deliverable" (→ re-storing what was already stored).
3. **The done-signal was conflated across two mechanisms.** Doctrine said "once
   no todos remain … your done-signal", but finishing a *todo* ≠ closing the
   *run*. The baseline prompt sample proves it: the message-todo was already
   `✓ completed` yet the agent kept going. No general principle said: *the goal
   existing is the done-signal — deliver it and stop.*
4. **Cap-pressure steering fires too late.** The readline nudge escalates at
   half-cap (loop 10/20); a ~3-turn task never reaches it during the productive
   window, so the only "wrap up" arrives after 10 turns of ramble.

## The change (general, accretive; `src/seon/agent/ctx.cljs`, `system-text`)

Added ONE standing teaching + de-conditioned the close verb:

- **"FINISHING IS AN ACT, NOT A DRIFT."** A task has a GOAL — the thing you were
  asked to produce. The moment it EXISTS and you have delivered it, the task is
  DONE — end the loop that turn. Re-confirming a computed value, re-storing what
  you stored, restyling a tile, re-announcing "it works / I'm ready" are not
  progress. The done-signal is the GOAL being satisfied, not open todos or the
  turn cap. Each turn ask: "do I already have what was asked for?" If yes,
  deliver and stop.
- **"call (complete …) the turn the goal is met … whoever asked (a human or a
  parent agent)"** — removes the spawn-only framing of the close verb.

General to how ANY agent uses memory + acts; nothing about the questions, no
"say the answer" coaching. +229 system tokens (3936 → 4165).

## Measurement

- Harness: `memory_qa_bench.py@memory_qa_bench`, 16 samples × K=4, host-side
  `includes` scorer, driven through `/solve` on the live default pod.
- Chunked 4 × (4 samples × K4) via `--sample-id`, `--max-samples 1`, merged from
  the four `.eval` logs (`logs/postfix-battery/`). Chunking is FORCED by the ~1hr
  bg-task cap; each chunk ran ~8-9 min (vs baseline 25-35 — agents complete now).
  Driver: `scratchpad/run_chunk.sh`.
- Config trend key: git `17379b4a`.

Per-sample: all 16 `4/4 PASS`. The exact hardest baseline samples flipped —
orion (baseline 4× ramble→turn-limit), solstice (4× ramble), pinnacle (4×
ramble), meridian/verdant/tidal (genuine-wrong + ramble) — are all pass^4=1.0.

## Finding — this is a strong result on a weak model

The ceiling was NOT the model. DeepSeek stores+retrieves fine (baseline already
proved this); it just lacked a general "close when the goal is met" instinct.
The near-total lift is real (dataset unseen, general instruction, 64/64 cells,
mean 3.3 turns, 0 turn-limit). Caveat for honesty: 1.000 on THIS 16-sample
memory-QA case-1 is a ceiling on a comparatively short task; harder/longer
multi-step tasks may still ramble and are the next battery to watch — but the
output-discipline root cause identified by B2 is fixed.

## Flagged smell (out of scope — not this task's lane)

The baseline prompt sample (`baseline-prompt-sample.txt`) shows the `:transcript`
context section rendering a `;; ⚠ [:transcript] render failed: :malli.core/invalid-input`
line (the render guard caught it — assembly not broken). Not reproduced on a
fresh 0-turn agent; likely a specific eval-row shape tripping the transcript
converter's input schema. Worth a focused Core look — a transcript that fails to
render is a data-integrity risk independent of output discipline.

Co-Authored-By: Claude Opus 4.8 (1M context) <noreply@anthropic.com>
