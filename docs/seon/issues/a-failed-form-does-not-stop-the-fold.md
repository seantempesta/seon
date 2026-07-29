---
type: issue
status: open
severity: friction
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

## Acceptance — RULED 2026-07-29 (owner, plan README rulings batch 4)

The decision is made, and it is **none of the three candidates below**: the
fold **continues per-form**, and a red form becomes a **routed problem**
addressed to its namespace's owner agent, carrying the planner's context. Only
an owner's explicit can't-fix bubbles back as failure. **Completion = all
forms settled** (succeeded | owner-fixed | owner-declared-can't), so a plan
with an unsettled routed problem simply is not complete. The lie dies because
unsettled work keeps the plan open — not because evaluation halts.

The candidates considered and superseded:

1. ~~Stop the fold at the first failed form~~ — superseded: it costs sibling
   progress, which the routing model keeps.
2. ~~Keep folding but refuse the completion~~ — close, but "refuse" is a
   negative check; settlement is the positive derivation that replaces it.
3. ~~Keep today's behaviour and let the prompt carry the failure~~ — rejected
   for the reason stated above (the completing form runs first).

**The evidence case needs one more rule than routing supplies.** Routing alone
does not stop form 1 from computing on form 0's missing definition and
completing with `Unbound: #'…/primes-below-100`. The proposed closure is a
computed rule at the one admission gate: **a result carrying an unbound-var
reference is itself red**, and routes like any other red form. The admit codec
already renders a Var as `:seon.sci.admit/reference`, so the marker is
detectable exactly where every value is already bounded — this needs
confirmation from the `seon.sci.admit` owner that the marker is visible at
that seam.

The regression asserts the CLASS, unchanged: a plan whose first form fails
must not produce a completion containing an `Unbound` marker, and — since
completion replies to the asking agent — must not deliver one either. Added
by the ruling: **such a plan must not derive as settled.**

## Related

- `docs/prds/sci-execution-runtime/plan/generate-code-v0-plan-2026-07-29.md`
  §2.3–2.4 — the settled-form state model this ruling produces, and unit E2′
  (the routing implementation plus the unbound-var rule), which is the
  first consumer that cannot ship until this issue closes.
- `docs/seon/issues/a-frozen-disposition-can-close-against-newer-facts.md` —
  same failure family, different cause.
