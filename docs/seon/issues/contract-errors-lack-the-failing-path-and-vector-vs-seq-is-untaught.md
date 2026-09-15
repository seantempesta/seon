---
type: issue
status: open
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
