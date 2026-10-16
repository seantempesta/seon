---
title: "Render bench: first run (7d74a762f)"
type: research
status: lane render-bench (Sonnet) report, saved by root 2026-10-16 (the lane could not write it)
---

# Render bench, first run

`script/seon/render_bench.clj` (88a525cce) shows every pinned sample on both faces through `seon.render.value/show` at
the agent profile's budget (6,144 tokens), and records per row:
- ms and allocation (median of 5);
- the render's output tokens and the printed tokens;
- cuts as `[path omitted total bound-by]`;
- the selected producer;
- whether the render returned text;
- any error.

Runs are stored in `results/<commit>.edn`; `diff-runs` compares two runs. Owner, 2026-10-16: "That way we can tune the
render functions and the value printer and understand what changes are doing."

## Inputs

- **Pairs** come from the registry, the same properties selection reads: 332 schemas.
- **Samples:** 392, pinned in `samples.edn`, each with its entity id and content digest:
  - 100 real values of 36 schemas (the largest, a middle and the smallest);
  - 282 generated values, one per schema;
  - 10 printer-only values with no render.
- **Re-sampling** is the explicit `resample!` (3.7 s). A full run takes 24 s for 784 rows.

## Noise

Measured by running the same commit twice (781 rows, 95th percentile):

| Measure | Band |
|---|---|
| ms, relative | 43% (sub-millisecond rows) |
| ms, absolute | 1.34 ms |
| Allocation, relative | 0.43% |

A diff flags a change only beyond twice the band (`noise.edn`). A lone small allocation flag of about 1.6% is noise.

## Findings

**Renders the printer cuts.** All are cut by the token budget.

| Render | Sample | Cuts | Render output (tokens) | Printed (tokens) | Read |
|---|---|---|---|---|---|
| `seon.render.test/render-ai` | `seon.test/test#0` | 142 | 8,413 | 6,097 | the render should select less |
| `seon.render.ns/render-ai` | `seon.ns/ns#2` | 2 | 11,684 | 6,143 | the render should select less |
| `seon.cluster.agent/render-identity-ai` | `seon.agent/agent#0` to `#2` | 2 each | about 300 | about 295 | cut at its operation forms far under budget: printer allocation (P1b) |

**Printer-only values cut:** `plain/tuples-1500` (372 cuts), `plain/vector-2000-maps` (257), `plain/wide-and-deep`
(29), plus one cut each for the long strings and vectors.

**Slow (over 50 ms):**
- `seon.fn/fn#1` on the HTML face: 103 ms.
- Printer-only values: `plain/vector-2000-maps` about 100 ms, `plain/vector-5000-numbers` about 60 ms,
  `plain/wide-and-deep` about 52 ms.
- No AI render passes 50 ms. The slowest are `seon.render.ns/function-ai` (30 ms, 54 MB) and
  `seon.render.test/render-ai` (30 ms).

**Text-returning AI renders (23 rows, 10 renders).** They violate the ruling that AI renders return data, and belong
to printer P2's faces group:
- `seon.ai/model-ai`
- `seon.ai/provider-ai`
- `seon.background/render-ai`
- `seon.config/render-ai`
- `seon.effect/render-ai`
- `seon.error/elision-prose`
- `seon.error/mcp-prose`
- `seon.render/unknown-ai`
- `seon.render.transcript/render-run-ai`
- `seon.repl/render-ai`

Statically, 25 of the 47 sampled AI renders declare a contract that admits a string.

**Errors (6 rows):**
- `show` with outputs `#{:hiccup}` only throws when the root render fails: it estimates tokens of a nil text. Samples:
  `seon.ai/attempt#0`, `seon.db.write/entity-refusal#0`, `seon.test.accretion/install-refused-error#0`. The task text
  is in `task-show-hiccup-only.edn`.
- `seon.ai/attempt-ai`, and `seon.error/render-ai` and `seon.test.accretion/render-ai` on generated samples, answered
  "The selected renderer did not return an observation".

## Coverage gaps

- **Unreached renders.** A value alone reaches only 23 of the 58 declared AI renders and 20 of the 50 HTML renders.
  A function-return schema's render needs `:seon.render/schema` on the request, which arrives in printer P1c. The
  HTML count is a lower bound, because the HTML face does not tag its producer.
- **14 schemas have no sample:**
  - `:seon.agent/namespace`
  - `:seon.agent/settings`
  - `:seon.cluster.status/concern`
  - `:seon.context/current-state`
  - `:seon.db/transaction-report`
  - `:seon.error/agent`
  - `:seon.error/chain`
  - `:seon.error/of-steward`
  - `:seon.message/to`
  - `:seon.render/call-request`
  - `:seon.render/selection-request`
  - `:seon.render.var/var`
  - `:seon.task/of-agent`
  - `:seon.turn/agent`

  Each needs a canonical-writer builder.
- **No viewer renders.** No agent namespace defines one yet.

## The proof test

`seon.render-bench-test/no-declared-render-output-is-cut-by-the-printer` is red by design. It names each render the
printer cuts, starting with `seon.render.test/render-ai`. It is the proof row the clipped-output template's tasks turn
green (`../self-improvement-loop.md`).
