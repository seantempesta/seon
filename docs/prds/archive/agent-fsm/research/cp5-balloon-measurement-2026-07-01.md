---
type: research
status: completed
tags: [research, agent, config, flow]
---

# CP-5 context-balloon measurement — escape-clipping-full × eval-decay

The gym battery scenarios are 1-few turns, so they cannot exercise the context
BALLOON that escape-clipping-full (render blocks whole) could cause on a
long-running agent that accumulates many large evals. This synthetic
measurement closes that blind spot with real render-path numbers.

## TL;DR

**escape-clipping-full renders the working set whole; the eval-result age-decay
ages old eval bodies down to stubs — so total context stays BOUNDED as large
evals accumulate.** With 12 large evals (~10k tokens each) across 12 turns:

- newest evals (offset 0-1) render near-full: **4,197 tokens each**;
- middle (offset 2-4) partial: **476 tokens**;
- old (offset 5+) clipped to a stub + `result/<id>` handle: **150-151 tokens**;
- **oldest is 28× smaller than newest**;
- **total 10,874 tokens** for all 12 — vs ~50k+ if each stayed near-full forever,
  and vs **120,000** if each rendered its full ~10k body with no decay.

The decay is the safety net that makes "render blocks full" (owner intent)
bounded rather than unbounded.

## The decay schedule (v1 default, seeded on the transcript block)

`:seon.agent.ctx.transcript/result-decay` = `[{0 16384} {2 1500} {5 200}]`
(char caps). `decay-cap-for-offset` selects by turn-offset (current turn − the
eval's `::turn-idx`): the largest `from-turn-offset` ≤ offset wins.

| turn-offset | decay char-cap | rendered eval tokens |
|-------------|----------------|----------------------|
| 0 (this turn) | 16384 | 4197 |
| 1 | 16384 | 4197 |
| 2 | 1500 | 476 |
| 3-4 | 1500 | 476 |
| 5 | 200 | 150 |
| 6-11 | 200 | 150-151 |

## Method

Rendered actual eval rows through `seon.agent.ctx/format-eval-row` (the real
render path, escape-clipping ON = default) with a big citable result value
(`(pr-str (apply str (repeat 40000 "x")))`) and the decay cap threaded per age
(exactly as `transcript-block` threads it). Measured rendered tokens via
`seon.ai.tokens/estimate`. Live on the pod (root), 2026-07-01.

```clojure
;; per-eval rendered tokens by turn-offset:
[[0 4197] [1 4197] [2 476] [3 476] [4 476] [5 150] [6 150] [7 150] [8 150]
 [9 150] [10 151] [11 151]]
;; total 10,874 tokens for 12 large evals.
```

## Interpretation

- **The working set stays full.** The 2 most recent turns' evals render near-full
  (escape-clipping frees the small source/stdout caps; the result body caps at
  16384 = effectively whole for normal values). The agent sees its recent work
  in full fidelity.
- **The tail shrinks.** By offset 5 an eval body is a 200-char stub — but it keeps
  its `result/<id>` handle, so the agent can still re-reference the whole live
  value if it needs it.
- **Total is bounded.** The sum converges: each additional old eval adds only
  ~150 tokens, not ~10k. A 100-turn agent with a big eval every turn lands near
  `2×4197 + 3×476 + 95×150 ≈ 24k tokens` for the eval bodies — not 1M.

## Caveat

This measures EVAL bodies (the balloon source). Message content under
escape-clipping-full renders whole with NO decay (messages are the human
conversation, deliberately never evicted/shrunk) — a pathological paste-heavy
conversation is bounded only by `::turns-retained` + `::tiers` (the transcript
window), which is OFF by default (empty tiers = render-all). If message-paste
ballooning is observed in practice, configure a `::tiers` schedule on the
transcript block (the mechanism is wired; the default is render-all for parity).
