---
type: issue
status: resolved
severity: friction
tags: [issue, sci, render]
---

# `admit` projects one fewer item than `max-collection`

## Problem

`seon.sci.admit/project-entries` cuts on
`cut-for-width? (and after (>= (inc taken) width))`. After projecting the
first item `taken` is 1, so at `width` 2 the test is `(>= 2 2)` — true — and
the collection is cut with only ONE item shown. Every admitted collection
renders `max-collection - 1` items and then an elision marker.

This is the same off-by-one class as
[[archive/opened-window-shows-one-fewer-item-than-the-page-size]], which was
fixed in `seon.render.value` earlier the same day. That fix did not reach
this sibling, and `admit` is the more consequential one: it bounds what every
agent SEES of its own evaluation results, so every agent has been reading
collections one element shorter than the configured cap.

`project-map` carries the same shape in its own `cut-for-width?`.

## Evidence

Measured 2026-08-01 in the REPL against current source:

```clojure
(admit/admit {:seon.sci.admit/value (vec (range 10))
              :seon.sci.admit/caps {… :seon.config.eval.result/max-collection W …}
              :seon.sci.admit/interrupt-fn (fn [])
              :seon.config/on-core-error :record})

W = 2 -> items shown: 1
W = 3 -> items shown: 2
W = 5 -> items shown: 4
```

Exact projection at `max-collection` 2 on `[1 2 3]`:

```clojure
#:seon.print{:face :seon.print/vector
             :items [#:seon.print{:face :seon.print/number :value 1}
                     #:seon.print{:face :seon.print/elided}]}
```

No test pins the boundary: `instrument_test.clj` uses `max-collection` 8 and
4 but asserts nothing about the resulting item count.

## Acceptance criteria

- `max-collection` N projects N items when N are available, then the elision
  marker only if an N+1st item exists.
- `project-map` gets the same correction.
- One regression covers the boundary triple (fewer than N, exactly N, more
  than N) for both a sequential value and a map, asserting the projected item
  count — the gap that let this survive the `value.cljc` fix.

## Provenance

Found by the flash quality-interrogation lane's ask-the-model bug-hunting
modality (`deepseek-v4-flash`, thinking OFF, 10.9 s, one call), then
REPL-confirmed. In the same response the model also claimed `max-nodes` 1 on
`42` elides the value — **falsified**, it projects `42` correctly. Evidence:
`docs/prds/sci-execution-runtime/research/flash-quality-interrogation-2026-08-01.md`.

Resolved same-night at the orchestrator level: both `cut-for-width?`
sites (sequences and maps) now cut only once `width` items are shown
and more remain; the last-item guard stays (a marker replacing the
final item is strictly less informative than the item). Admit suite
green. Found by the model-interrogation lane — the same off-by-one
class as `opened-window`, in the sibling that fix missed.
