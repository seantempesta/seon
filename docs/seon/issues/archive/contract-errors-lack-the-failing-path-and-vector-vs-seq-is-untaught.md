---
type: issue
status: resolved
severity: friction
tags: [contracts, errors, malli, help, live-test]
created: 2026-09-15
---

# Contract violations for the agent's own functions do not name the failing path; `[:vector X]` vs lazy seq is untaught; `:fn` literals in contracts print as `#object`

## Observed (run 5; the model's account)

- `largest-customer violated its contract (invalid-input): invalid type`
  when called with a lazy seq of maps; `(vec rows)` fixed it. The message
  never said which element or path failed, nor that `[:vector X]` rejects
  a lazy seq. The model "basically guessed".
- A defn whose `:malli/schema` contained `[:fn {…} (fn [r] (seq r))]`
  failed with "No reader function for tag object": the function literal
  was printed with `pr-str` into `#object[…]` and read back. Contracts
  must be data; the refusal should say so and name the registered
  predicate alternative.
- `:example/order-row` is `[:map {:seon.db/attributes true} …]` and the
  annotation is unexplained.

## Wanted

- Contract refusals name the argument position, the schema path, and
  "expected … got …" (the doc map on refusals exists; make the message
  carry the path for agent-installed contracts too).
- A contract with a function literal is refused at install with: "contracts
  are data; use a registered predicate schema or `[:fn 'sym]`" — never
  printed as `#object`.
- `(doc :example/order-row)` (or the schema's render) explains
  `:seon.db/attributes true` in one line; help says `[:vector X]` needs a
  vector (use `vec`).

## Contracts-and-plan verification, 2026-09-15

The refusal constructor now reports the existing Malli coordinate. The live
JVM returned `argument 0 (0-based); schema path [0]; expected :vector, got
LazySeq`. The canonical SCI regression reproduces the captured run-5 call
and proves `vec` returns Ada/115.

The owner authorized the additional presentation boundary at
`seon.error/instrumentation-prose` on 2026-09-15. Its one-hunk change
prefixes the existing rendering with `:seon.error/message`; the rest of
the AI text and HTML pair are unchanged. The real SCI regression now
asserts all four details in the saved shown text as well as the error value.

The vector-help sentence and the canonical :seon.db/attributes description
are now documented. Their isolated help/example/grammar gate passed
8 tests / 274 assertions; the live help trial with those bytes scored 12/12.
The shown-message boundary is now repaired.

The exact run-5 function literal is now refused before `pr-str`, with the
registered-predicate / quoted-symbol alternatives in its shown error.
Candidate first-install and replacement isolation pass the canonical SCI
regression; the selected isolated gate passed 10 tests / 318 assertions.

Final authorized item 1 validation and the new help trial are recorded in the
[landing note](../../../prds/context-generation/research/contracts-and-plan-landing-2026-09-15.md).

Final item 1 gates: fast **42 tests / 430 assertions**, isolated **42 tests /
434 assertions**, platform **84 tests / 505 assertions**; all pass. The new
help trial remains **12/12**. The issue is resolved across constructor,
rendering, installation, and documentation.
