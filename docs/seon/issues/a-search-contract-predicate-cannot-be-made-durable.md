---
type: issue
status: open
severity: friction
tags: [issue, schema, class/n6, wave/live-drive-context]
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

The quoted-symbol repair landed in `375f79b01`, but exposed the other half of
the same contract boundary. When Malli compiles the now-durable `:gen/gen`
symbol during `seon.instrument/apply!`, `malli.sci/evaluator` calls the pinned
SCI with the removed `:preset` option. The cohost platform regression therefore
refuses before the second cluster boot, even in a fresh single-test JVM.

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

Reproduced again on 2026-08-10 after `375f79b01` through both
`bin/test seon.cluster.cohost-boot-test` and a direct invocation of its fixture.
The complete cause data identifies `seon.search/index-step`; Malli's nested
exception is:

```text
Unsupported option passed to sci/init: [:preset]
```

The parallel runner's full gate stopped correctly after 73 platform tests in
49.5 seconds and reproduced the same failure in its isolated confirmation JVM.
No bulk test was submitted.

The failure was previously masked: every run of this namespace errored
earlier, in the fixture, on an unrelated capability-graph refusal.

## Owner

`seon.search/index-step`'s declaration and Malli's generator-symbol resolution
against the pinned SCI interface. The durable form and live compilation must
be one valid contract.

## Acceptance

- `index-step`'s predicate is written as a quoted qualified symbol, matching
  its own `:gen/gen` entry two lines above.
- Compiling and instrumenting that durable definition uses only options the
  pinned SCI declares; no compatibility option is silently ignored.
- `seon.search-test/index-step-contract-has-durable-generative-host-predicates`
  passes, including the `(= definition (edn/read-string (pr-str definition)))`
  round-trip its first assertion makes.
- `bin/test seon.cluster.cohost-boot-test` passes in a fresh isolated root.
- A sweep confirms no other first-party `:malli/schema` writes a predicate
  unquoted; the program graph already records every arity's declaration, so
  this is a query rather than a read-through.
