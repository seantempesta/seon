---
type: issue
status: resolved
severity: blocker
created: 2026-09-17
tags: [render, contracts, totality, class-p4]
---

# A time-limited render producer passes nil where the typed unknown requires a map

## Problem

`seon.render/invocation-unknown` (`src/seon/render.clj:995`) always assoc's
`:seon.error/value`:

```clojure
    (unknown (cond-> {:seon.render.unknown/reason reason
                      :seon.render.unknown/producer selected
                      :seon.error/value (:seon.sci.admit/value result)}
```

A producer stopped by `time-limit` has no value, so `:seon.error/value` is nil,
and `seon.render/unknown`'s `:seon.render/unknown-request` requires a map there.
The armed contract throws:

```
seon.render/unknown refused argument 0 (0-based) at [:seon.error/value]:
expected a map, got nil.  Contract: :seon.render/unknown-request.
```

The boundary whose entire purpose is to make a refusal TOTAL throws instead —
a §2.4 violation, and AGENTS.md §3's rule besides: absent = no key, never
stored nil.

## Evidence

`seon.render-coverage-test/a-refused-render-producer-contributes-a-stable-typed-unknown`
errors on it (batch 55, and still 12 pass / 0 fail / 1 error after the fixture
repair in `ac95db78a`). Trace: `instrument.clj:414`, caller
`seon.render (render.clj:999)`.

## Fix shape

Move the key under the `cond->`:

```clojure
    (unknown (cond-> {:seon.render.unknown/reason reason
                      :seon.render.unknown/producer selected}
               (:seon.sci.admit/value result)
               (assoc :seon.error/value (:seon.sci.admit/value result))
               …
```

The deftest above is the regression; it needs no change.

## Why it is filed rather than fixed

Found by the render-root-address lane (2026-09-17), whose owned paths are
`src/seon/render/value.clj` and `test/seon/render_coverage_test.clj`.
`src/seon/render.clj` was explicitly excluded. Full evidence:
[render-root-address-2026-09-17.md](../../prds/context-generation/research/render-root-address-2026-09-17.md).

## Resolution (2026-09-17, render-repl-cold-reds lane)

Fixed as filed: `:seon.error/value` moved under the `cond->` in
`seon.render/invocation-unknown` (`src/seon/render.clj:1022`), so an absent
value is no key rather than a stored nil.

The producer that actually threw was not the time-limited one: the live
probe showed `:seon.sci.admit/value` is also absent when ADMISSION refuses
an over-bound failure value, which is the contract-refusal case. Either way
the fix shape is the same and the throw is gone.
`seon.render-coverage-test/a-refused-render-producer-contributes-a-stable-typed-unknown`
went from 12 pass / 0 fail / 1 error to 22 pass / 1 fail / 0 error in-process
on `default`. The remaining failure is a distinct root cause, filed as
[a-render-producers-contract-refusal-is-too-large-to-admit-so-the-typed-unknown-loses-its-kind.md](a-render-producers-contract-refusal-is-too-large-to-admit-so-the-typed-unknown-loses-its-kind.md);
it was previously unreachable because this throw aborted the deftest first.
