---
type: issue
status: open
severity: friction
created: 2026-09-16
tags: [render, turn, performance, class/p1]
---

# `seon.render/request-profile` is derived 64 times per turn

## Problem

The gate session's bookkeeping research (`c2972178b`) measured
`seon.render/request-profile` (`src/seon/render.clj:70`) deriving the
cluster's agent render profile 64 times in one turn. Its callers
(`src/seon/render.clj:116`, `:535`, `:565`) each re-derive from the
projection at call time instead of receiving the profile with the request —
the §2.1 fetch-at-call-time class. The turn is ~100 ms warm today, so this
is not the fixture cost the research attributed (that is
`seon.config/apply!` running twice per fixture cluster); it is the next
redundant-derivation storm on the turn path.

## Fix shape

Derive the profile once where the turn or render request is built and
carry it on the request map; `request-profile` returns the carried value and
derives only when the request has none (its current typed refusal for a
missing projection stays).
