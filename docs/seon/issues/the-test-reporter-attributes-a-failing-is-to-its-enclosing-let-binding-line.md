---
type: issue
status: open
severity: friction
created: 2026-09-17
tags: [issue, seon.test.runner, diagnostics, class/absence-as-health]
---

# The test reporter attributes a failing `is` to its enclosing let-binding line

## Problem

A gate failure header names a line that holds a `let` BINDING, not the
assertion that failed. On 2026-09-17 the reported failure for
`seon.cluster.source-test/incremental-first-party-publication-retains-complete-scalar-rows`
was headed `test/seon/cluster/source_test.clj:334`, which is

```clojure
updated (db/transact! connection sparse)        ; :334 — a binding, asserted at :340
```

while the `is` that actually failed is `:341`:

```clojure
(is (= :seon.db/invalid-write (:seon.error/kind refused))
    "an incomplete create still refuses under whole-entity validation")
```

Both transactions appear in the same `let`, and the two are opposite cases
(a sparse UPSERT that must succeed, an incomplete CREATE that must refuse), so
the misattribution reads as a claim about the wrong transaction.

## Cost, measured

It sent a diagnosis the wrong way twice. The correction recorded in
[the sparse-upsert note](archive/a-sparse-program-upsert-is-now-admitted-where-the-test-expects-a-refusal.md)
concluded from the `:334` header that the writer's CREATE path was admitting
an incomplete entity; a live probe at HEAD `333b1bf2d` showed the create is
refused and the whole namespace green (17 tests / 149 assertions / 0 failures),
and the red had in fact come from the test's PREVIOUS text, before `1768b466b`.
A fix lane was launched on that reading.

## Wanted end

The reported line is the line of the failing `is` form. `clojure.test` carries
the form's own metadata; the reporter should use it rather than a frame that
resolves to the enclosing binding. Whatever the mechanism, the regression is a
test whose failing `is` sits several lines below its `let` binding and whose
reported line equals the `is` line.
