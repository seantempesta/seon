---
type: issue
status: open
severity: friction
tags: [issue, schema, contracts]
---

# Name `index-step`'s predicate so its contract can be made durable

## Problem

`seon.search/index-step` declares its `:ping-map-fn` slot with the
predicate written UNQUOTED:

```clojure
[:fn {:error/message "must project the search proc state to a ping map"
      :gen/gen 'seon.search/ping-map-fn-generator}
 seon.search/ping-map-fn?]
```

`defn` metadata is evaluated, so the `:gen/gen` entry survives as the
symbol it was quoted as, but the predicate itself becomes a bare function
object with no Var and no name. `seon.schema/canonical-definition` — the
inverse of the compile-time preparation, which recovers a predicate's
qualified symbol from the Var a bound predicate IS — has nothing to
recover from, and refuses:

```text
A durable Malli definition contains an unnamed callable.
```

So this contract cannot be written back as durable EDN.

## Evidence

`seon.search-test/index-step-contract-has-durable-generative-host-predicates`
errors at its first form, `schema.clj:292`
(`canonical-definition$callable-symbol`). The test names exactly the
property that is broken.

Reproduced 2026-08-08 in a load-only `clojure -M:dev`, and shown
independent of the component-entity widening that landed the same day by
disabling that widening and re-running:

```clojure
(with-redefs [sform/widen-component-children identity]
  (attempt))
;; => "A durable Malli definition contains an unnamed callable."   (unchanged)
```

The failure was previously masked: every run of this namespace errored
earlier, in the fixture, on an unrelated capability-graph refusal.

## Owner

`seon.search/index-step`'s declaration. The registry is behaving
correctly — an unnamed callable genuinely cannot be made durable.

## Acceptance

- `index-step`'s predicate is written as a quoted qualified symbol, matching
  its own `:gen/gen` entry two lines above.
- `seon.search-test/index-step-contract-has-durable-generative-host-predicates`
  passes, including the `(= definition (edn/read-string (pr-str definition)))`
  round-trip its first assertion makes.
- A sweep confirms no other first-party `:malli/schema` writes a predicate
  unquoted; the program graph already records every arity's declaration, so
  this is a query rather than a read-through.
