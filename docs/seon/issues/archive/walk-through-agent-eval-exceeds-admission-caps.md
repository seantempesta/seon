---
type: issue
status: resolved
tags: [issue, render, sci, test]
---

# `seon.render/walk` through an agent eval exceeds admission caps

Resolved by the 2026-08-01 admission-cap raise. The earlier 4,096-character
string ceiling clipped an ordinary `seon.render/walk` result. Owner ruling #25
raised the measured string ceiling to 262,144 characters, under which the same
agent evaluation returns its complete string with
`:seon.sci.admit/capped? false`.

Proof: `bin/test seon.config-test seon.sci.admit-test seon.sci.eval-test`
passes with `public-walk-is-callable-through-an-agent-sci-eval` asserting the
uncapped result. The fix strengthens the one admission mechanism; it does not
special-case the walk.
