---
type: issue
status: open
severity: major
tags: [issue, agent-runtime, run-loop]
---

# A failed form does not stop the fold, so a run can complete with a lie

## Problem

The plan fold runs every form in order and records a terminal receipt for each.
When a form's evaluation FAILS, the receipt records the failure and the fold
continues to the next form — which then evaluates against a context where the
failed form's `def` never happened. Sci resolves the missing var to an
`Unbound` marker rather than throwing, so the later form succeeds, produces a
value containing that marker, and the run can COMPLETE with it.

The agent never learns. It has no turn between the failed form and the
completing one, so the confident wrong answer is what gets delivered — and now
that a completed run replies to the agent that asked, the wrong answer
propagates to a second agent as if it were the result.

This is not the crash model working as designed: nothing was interrupted, no
process died, and the loop had the failure in hand at the moment it decided to
run the next form.

## Evidence

Live two-agent drive, 2026-07-28
(`docs/prds/sci-execution-runtime/research/my-message-proof-2026-07-28.md` §6).
Bob's plan:

```text
form 0: (def primes-below-100 (count (filter (fn [n] …(Math/sqrt (inc n))…) (range 2 100))))
form 1: (my.run/complete (str "Alice, there are " primes-below-100 " prime numbers below 100."))

receipt 0 :error → {:seon.error/kind :seon.sci.eval/evaluation-failed,
                    :seon.error/message "Unable to resolve symbol: Math/sqrt"}
receipt 1 :done  → {:my.run/disposition :completed,
                    :my.run/result "Alice, there are Unbound: #'my.agents.bob/primes-below-100 prime numbers below 100."}
```

That string was then delivered to alice as bob's answer.

A second, smaller finding rides the same evidence: `Math/sqrt` is not resolvable
in the base sci context (`:classes` carries only `Throwable` and `Error`), which
is what a model reaches for first when asked to test primality. The callable
surface is N5's computed binding table and is deliberately not hand-extended —
but the gap is worth recording where the surface is decided.

## Owner

`src/seon/cluster/loop.cljc`, the `:resume` fold; the disposition semantics in
`src/my/run.cljc` are an input to the decision.

## Acceptance

A decision, then its mechanism. The candidates, in the order they seem honest:

1. **Stop the fold at the first failed form** and leave the run open, so the
   agent's next turn sees the error in its prompt and adapts. This matches
   "nothing wedges, the agent adapts" and costs the remaining forms, which were
   authored against a state that no longer exists.
2. **Keep folding but refuse the completion**: a run whose plan contains a
   failed receipt may not close with `:completed`. Narrower, and it stops the
   lie specifically rather than the wasted work.
3. Keep today's behaviour and make the prompt carry the failure — which does
   not help, because the completing form runs before any prompt is derived.

Whichever is chosen, the regression asserts the CLASS: a plan whose first form
fails must not produce a completion containing an `Unbound` marker, and — since
completion now replies to the asking agent — must not deliver one either.
