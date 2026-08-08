---
type: issue
status: resolved
severity: blocker
tags: [issue, render, agent]
---

# Give the transcript's floor-rendered values a root identity

## Resolution (2026-08-08, render floor repair lane)

Fixed at cause in `seon.render.transcript` (`src/seon/render/transcript.clj`,
commit `d4ac2ba40`). Two lanes converged on this file; the landed shape is the
one below.

`history` now derives each entry's durable identity ONCE, from the entity's own
declared unique identity attribute (`identity-attributes` over `(:schema db)` —
a query, never a per-kind rule), and carries it as `::root`. `projected-entry`
threads that root onto the unit before building any text:

```clojure
(let [unit (assoc unit :seon.render.value/root (::root entry))]
  …)
```

So every `floor-text`, `bounded-scalar`, and `rendered-family` call inside an
entry renders the value instead of the refusal, and a new entry kind is rooted
without anyone remembering to add a rule. `:seon.render.value/root` (rather
than the block name) is deliberate: it also becomes the print
`:seon.print/requery-id`, so an elided value now names an identity the reader
can actually pull — `requery by [:seon.cluster.eval/id "…"]` — instead of
"requery refused: the value has no durable blob or entity identity". The entity
id is the honest fallback if a pulled entity ever carries no identity
attribute.

- `seon.render.transcript-test`: **28 failures → 0** (12 tests, 207
  assertions). The error face, capped face, and bounded scalar assert the
  value.
- Class regression: the generative totality property
  `every-generated-history-is-ordered-total-and-token-bounded` now also asserts
  that neither the AI text nor the HTML of ANY generated history contains
  `:seon.render.value/missing-root-identity` — the class is dead for every
  entry kind and detail level, not just the three faces that were noticed.
- Live falsifier (cluster `default`, `eval_clj`, namespaces reloaded): the root
  agent's real transcript renders 8,967 characters of actual REPL forms,
  printed output, and a `#:my.run{:disposition :completed, :result "…"}`
  receipt face with **zero** `missing-root-identity` strings. Same live unit,
  root removed → the refusal returns; root supplied → the value:

  ```text
  without-root → #:seon.error{:kind :seon.render.value/missing-root-identity, …}
  with-root    → #:my.run{:disposition :completed}
  ```

The one remaining `seon.render.transcript-test` failure was a SEPARATE class —
reasoning inflating the attempt block's child count — root-caused and fixed in
the same session:
[Model reasoning perturbs the agent AI projection's elision](reasoning-attribute-perturbs-the-agent-ai-walk-projection.md).
The namespace is now fully green (0 failures, 0 errors), as is
`bin/test --changed src/seon/ai.clj --changed src/seon/render/transcript.clj`
(213 tests, 1,161 assertions).

## Problem

Since `71b93870b` ("Require supplied rendered-value identities", 2026-08-06)
`seon.render.value/node-id` refuses when a unit carries no
`:seon.render.call/id`, `:seon.render.value/root`, `:db/id`, or
`:seon.render.block/name`. `seon.render.transcript/floor-text`
(`src/seon/render/transcript.clj:453-455`) calls `value/render-ai` with the
transcript's OWN request unit, which carries none of those, so every value the
transcript renders through its floor comes back as the refusal text instead of
the value:

```text
#:seon.error{:kind :seon.render.value/missing-root-identity,
             :message "A rendered value root requires a caller-supplied block id.",
             :data {:seon.cluster.agent/id "transcript-agent",
                    :seon.render.data/path []}}
```

That string is what an agent reads where its own evaluation result, execution
error, or capped receipt should be. It is also ugly output by the standing
order: it names an internal render contract at a reader who asked for a value.

## Evidence

`bin/test seon.render.transcript-test` on `031f8438f`: **28 failures, 0
errors**, ten deftests. Verbatim, from the error-face test:

```text
expected: (some #{"No such namespace: missing.function"} (str/split-lines ai))
  actual: (not (some #{"No such namespace: missing.function"}
    ["user=> (missing.function/call)"
     "#:seon.error{:kind :seon.render.value/missing-root-identity, …}"]))
```

The refusal replaces the error face, the capped-receipt face, and the bounded
scalar in `capped-state-is-derived-from-receipt-size-without-a-boolean`,
`error-receipt-without-triage-has-an-execution-error-face`,
`every-generated-history-is-ordered-total-and-token-bounded`, and seven more.

Found by the token-calibration repair lane while running the consumers of
`seon.ai.tokens`; nothing in that lane's diff touches render (its own one-line
`transcript.clj` change is arithmetically identical to what it replaced, and
`seon.effect-test` in the same run is clean).

Uncertainty worth stating: the blast radius on a live page was not measured by
this lane. The unit reaching `floor-text` is built by the render request rather
than by the test fixture, so the same absence should occur live, but that was
not falsified against a running cluster.

## Acceptance

- The transcript supplies the root identity its floor renders under — the entry
  it is rendering already has one (the receipt/eval entity's `:db/id` or the
  block name), so the caller passes it rather than the value owner inventing
  one.
- `seon.render.transcript-test` is green, with the error face, the capped face,
  and the bounded scalar asserting the value rather than a refusal.
- One live check on a page or an agent prompt confirms a receipt face shows its
  value, so the fix is proven where a reader actually meets it.
