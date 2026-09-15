---
type: issue
status: resolved
severity: blocker
tags: [issue, turn, provider, bounds, faults, live-test]
created: 2026-09-15
---

# The turn-completion backstop fired at 10,000 ms on provider turns that legitimately took 22–39 s

## Observed (run 4)

Three faults: `Agent "juniper" run "7b254915ca94" did not publish turn
completion within 10000 ms` (also f3b3fda9b944, e2e89497c3c2). Provider
turn durations: 2, 1, 2, 2, 3, 2, **22**, 2, 3, 2, 2, 2, 2, 9, 2, 3, 10,
3, 2, 2, 3, 3, 3, 2, 11, 2, **39**, 4, 3, 2 seconds. The fixture sets
`:seon.config.eval/time-limit-ms 10000` (an SCI evaluation bound); the
cluster's `:seon.config.agent/turn-completion-backstop-ms` is 600,000. The
backstop that fired used the evaluation limit as the turn bound, so
provider latency (which is not evaluation time) tripped it, and the
interrupted turns lost their replies.

## Wanted

- The turn-completion bound derives from the turn's actual parts: provider
  timeout (`:seon.config.ai/timeout-ms`, with retries) plus the evaluation
  bound per form, never the evaluation bound alone.
- A bound firing names what never arrived (provider response vs evaluation).
- Regression: a fixture with a 10 s eval limit and a 20 s simulated provider
  latency completes the turn.

## Resolution — 2026-09-15

The accompanying backstop commit removes both evaluation-limit minima. The
existing observer follows resolved provider attempts/retry delays and each
admitted evaluation, with the lifecycle allowance for completion. Its fault
names the missing provider response or evaluation completion; orderly disarm
joins that same observation. A real canonical turn with 10-second evaluation
limit and 20-second simulated provider latency completes without a fault.

Exact gates and live verification are in the
[landing note](../../../prds/context-generation/research/backstop-and-misc-landing-2026-09-15.md).
