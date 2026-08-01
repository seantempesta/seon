---
type: issue
status: resolved
severity: friction
tags: [issue, render]
---

# `opened-window` shows one fewer item than the configured page size

## Problem

`seon.render.value/opened-window` computes `available` as `(max 0 (dec size))`
and then pages to `available`, so a configured window of `size` items only
ever displays `size - 1` of them. At `size` 1 it displays NOTHING while
simultaneously reporting `:seon.render.value/more? true` — an empty panel
that claims there is more to see, with no way to reach it.

The `(dec size)` is doing double duty. The `(inc available)` in the `head`
computation is the correct lookahead-by-one for detecting `more?`; decrementing
`available` as well spends that lookahead slot twice, once as detection and
once as a lost row.

## Evidence

Measured 2026-08-01 against the real namespace (not a replication):

```text
$ clojure -M:dev  ;; #'seon.render.value/opened-window
(opened-window [:a :b :c] 0 1)
=> {:window [] :steps [] :offset 0 :shown 0 :total 3 :more? true}

(opened-window (vec (range 20)) 0 10)
=> {... :shown 9 :more? true}      ; page size 10 renders 9 rows

(opened-window [:a :b] 0 3)
=> {:window [:a :b] ... :shown 2 :more? false}   ; correct only because
                                                 ; the value is short
```

Because `prepare` folds `:seon.render.value/more?` into `truncated?`, every
exactly-full page also renders the "elided — this value is larger than the
configured window" note when nothing was actually elided.

## Acceptance criteria

- A window of size N displays N items when N are available.
- `more?` stays correct: it is true exactly when an N+1st entry exists.
- Size 1 displays one item, not zero.
- One regression covers the boundary triple (fewer than N, exactly N,
  more than N) and asserts `shown`, `more?`, and the derived `truncated?`.

## Owner

`src/seon/render/value.cljc` (`opened-window`, and the `truncated?` fold in
`prepare`). Found while building the long-context grader for
`docs/prds/sci-execution-runtime/research/flash-quality-interrogation-2026-08-01.md`.

Resolved same-day at the orchestrator level: `available` no longer
spends the lookahead slot twice (value.cljc:90); the routed-page test
now asserts the true semantics (page of 3 shows 3, next offset 3) and
a size-1 regression proves one item shown with `more?` intact. Found
by the flash quality-interrogation lane asking the model about the
real namespace — the ask-the-model bug-hunting modality's first
confirmed kill.
