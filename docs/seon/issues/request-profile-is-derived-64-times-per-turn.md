---
type: issue
status: resolved
severity: friction
created: 2026-09-16
resolved: 2026-09-16
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

## Resolution — 2026-09-16

The title's premise was half right. Measured live on `default` (PID 95853) by
wrapping the var with a carried/derived classifier, the 64 were CALLS, not
derivations, and the render-request callers named above were not the owners:

- `seon.cluster.prompt/prompt` over agent `root` with a cold render cache made
  146 calls and **1** derivation (3.2 ms); the other 145 read the carried value
  for 0.26 ms in total. `seon.render.web/derive-context!`
  (`src/seon/render/web.clj:2407`) already derives once and carries it.
- `seon.turn/evaluate-sources` over six forms made 12 calls and **6**
  derivations for **14.7 ms** — one per form, inside the source loop. That is
  the entire 15 ms the research recorded.

One seam, one fix: `evaluate-sources` (`src/seon/turn.clj:4442`) now derives the
profile ONCE before the loop, from the basis it already captures for
`ai/agent-overlay`, and carries it on every evaluation request.
`seon.render/request-profile` is unchanged — a carried profile short-circuits,
an absent one derives, and a request with neither profile nor projection still
returns the typed `::render/missing-projection` refusal (§2.4). No cache keyed
on a database value, no atom, no second mechanism (§2.5).

Six-form `evaluate-sources` on live `default`, before → after: **6 → 1**
derivations, **14.7 → 2.4 ms** derived, **48.8 → 29.4 ms** whole call.

Class regression:
`seon.cluster.evaluate-sources-test/one-turn-derives-the-render-profile-exactly-once`
asserts the derivation count itself (six forms, exactly one derivation) plus
the shown text of all six evaluations, so dropping the profile cannot make the
count pass. In-process on PID 95853: 3 pass, 0 fail, 0 error.

Numbers, the falsified premise, the exoneration probe for the unrelated
`:seon.turn/agent-already-running` failure, and the remaining bookkeeping
attribution are in the dated section of
[turn-bookkeeping-cost-2026-09-16.md](../../prds/context-generation/research/turn-bookkeeping-cost-2026-09-16.md).
