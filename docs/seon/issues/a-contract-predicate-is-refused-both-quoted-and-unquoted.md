---
type: issue
status: open
severity: friction
created: 2026-09-26
tags: [issue, contract, schema, dogfood]
---

# A function contract's predicate is refused both quoted and unquoted

Found by dogfood lane dogfood-author-opus through MCP only (branch `dogfood-author-opus-1`, default pid 19859).

## Observed

Unquoted, as a Clojure developer writes a Malli predicate schema:

```clojure
(defn now-inst "Now." {:malli/schema [:=> [:cat] inst?]} [] (java.util.Date.))
;; seon.test.accretion/data-contract! refused :seon.fn/spec at []: expected the declared requirement
;; ("a data-only function contract"), got a vector [:=> [:cat] #object[clojure.core/inst?]].
;; Fix: Function install refused: contracts are data; use a registered predicate schema or a quoted symbol
;; naming an admitted predicate.
```

Quoted, as that fix says:

```clojure
(defn now-inst "Now." {:malli/schema [:=> [:cat] 'inst?]} [] (java.util.Date.))
;; seon.schema/canonical-definition refused return value at []: expected must be a parseable, EDN-readable
;; Malli form …, got a vector [:=> [:cat] (quote inst?)].
```

The first check reads the evaluated metadata (a function object). The second reads the authored source form
(`(quote inst?)`). Each refuses what the other one needs. The only form that installs is a registered schema
key (`[:=> [:cat] :notes-index.note/created-at]`, where that key is registered as `'inst?`).

`seon.schema/register!` has the same split: `(register! :k inst?)` refuses with "must be a parseable,
EDN-readable Malli form", and `(register! :k 'inst?)` works, because `register!` is an ordinary call whose
argument is evaluated.

## Expected

Either form installs, as at `clojure.main` with Malli: `inst?` names Malli's built-in predicate schema, and
`'inst?` is the same symbol after evaluation. The refusal's fix must name a form that the next stage accepts.

## Model that is wrong

Two admission stages read two different values of one contract: `data-contract!` the evaluated metadata,
`canonical-definition` the unevaluated source. One stage should own the contract value (the evaluated
metadata, as `defn` defines it, with a function object mapped back to its Var's symbol) and every later stage
should read that value.

Related: [a-search-contract-predicate-cannot-be-made-durable](a-search-contract-predicate-cannot-be-made-durable.md)
(an unnamed callable in a first-party contract).
