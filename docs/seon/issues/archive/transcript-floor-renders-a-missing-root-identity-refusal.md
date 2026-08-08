---
type: issue
status: resolved
severity: blocker
tags: [issue, render, agent]
---

# Give the transcript's floor-rendered values a root identity

## Resolution (2026-08-08, render floor repair lane)

Fixed at cause in `seon.render.transcript/projected-entry`
(`src/seon/render/transcript.clj`). The projection now derives a stable root
identity from the entry's OWN declared block name and threads it onto the unit
before building any text:

```clojure
(let [unit (assoc unit :seon.render.block/name (entry-name entry))]
  …)
```

`entry-name` was already the entry's stable identity — `:seon.transcript.<kind>/<id>`,
the same block name the HTML list uses at `block/surface-id` — so nothing is
invented. `value/node-id` accepts `:seon.render.block/name` as a root address,
so every `floor-text`, `bounded-scalar`, and `rendered-family` call inside an
entry now renders the value instead of the refusal. `:seon.render.block/name`
(not `:seon.render.value/root`) was chosen deliberately: it supplies the node
id WITHOUT changing the print requery-id, so no unrelated pager/requery
semantics shift. `entry-name` was moved above `projected-entry` to resolve.

- `seon.render.transcript-test`: **28 failures → 0 refusal failures** (207
  assertions), stable across three runs. The error face, capped face, and
  bounded scalar now assert the value. One residual, unrelated failure remains
  and is its own note (see below).
- Live proof (scratch cluster `rr-scratch`, isolated root): the root agent's
  `/agent/root/debug` page — rendered through the full production pipeline,
  13 eval receipts — contains **zero** `missing-root-identity` strings and
  shows real receipt faces (`=> error`, `=> (help)…`) where results/errors
  belong.

The one remaining `seon.render.transcript-test` failure is a SEPARATE class in
a foreign owner, not the refusal: see
[Model reasoning perturbs the agent AI projection's elision](../reasoning-attribute-perturbs-the-agent-ai-walk-projection.md).

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
