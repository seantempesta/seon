---
type: issue
status: open
severity: blocker
tags: [issue, agent-runtime, run-loop, generate-code]
---

# An owner can never fix a red form into settlement

## Problem

`seon.cluster.work/form-settlement` derives `:owner-fixed` from a receipt that
is NOT red plus a live assignment:

```clojure
(cond
  …
  (and red? assignment?) :routed
  red? :unrouted-red
  assignment? :owner-fixed
  :else :succeeded)
```

An assignment is only ever emitted for a red form, and a receipt is immutable —
`:seon.cluster.eval/error` is asserted once and never retracted, and
`red-receipt?` reads exactly that fact plus the frozen `result-edn`. So the
premise "the routed problem stops deriving at the current basis"
(generate-code v0 plan §2.4) has nothing that could stop it: no repair by the
owner, in any namespace, at any later basis, changes the redness of the receipt
the problem derives from.

Two of the model's three settling arms are therefore unreachable in production:

- `:owner-fixed` — reachable only for a form that was never red, which no
  production path produces;
- `:succeeded` after repair — the receipt is frozen red forever.

Only `:owner-declared-cant` (a declination joined by `about`) actually settles
a red form. That makes plan settlement a one-way ratchet: an owner who FIXES
the problem leaves the plan permanently unsettled, while an owner who declines
settles it. The incentive is exactly backwards from the design's intent.

## Evidence

`test/seon/cluster/problem_routing_test.clj:123`
(`every-form-has-exactly-one-of-the-seven-derived-states`) reaches
`:owner-fixed` only by planting a receipt with `result-edn "5"` and NO error,
then attaching an assignment to it — a combination the loop cannot commit,
because `problems/form-problem` returns nil for a receipt that is not red. The
suite is green for the wrong reason.

Live-shaped confirmation, `test/seon/gen/loop_test.clj`
(`a-goal-is-a-message-and-the-attempt-routes-its-own-failures`): alpha receives
its assignment, defines the missing function in its own run, completes — and
the planner's form 2 is still `:routed` at the resulting basis, with the plan
unsettled.

## Expected owner

`seon.cluster.work` (the derivation) together with the generate-code v0 plan's
§2.4, which specifies the state model.

## Acceptance criteria

An owner's repair moves its form to a settled state through facts, not through
a claim, and the mechanism is named rather than assumed. The candidates the
fix must choose between (this note does not pick one):

- settlement is derived over the LATEST attempt of a form's work rather than
  over the original receipt, so a re-attempt that succeeds settles it;
- the problem derives from a condition the repair can actually change (for
  example, "the symbol this form failed on is now resolvable at this basis"),
  which is what "a problem stops being a problem when the facts stop saying so"
  means for this class; or
- `:owner-fixed` is retired from the state model and the plan says plainly
  that a red form is settled only by a declination or a successful
  re-attempt.

Whichever lands, `every-form-has-exactly-one-of-the-seven-derived-states` must
reach the state through a receipt shape the loop can actually commit.
