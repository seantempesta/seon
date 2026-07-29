---
type: issue
status: open
severity: blocker
tags: [issue, sci, reader, program-graph]
---

# Resolve reader special forms by executable operator identity

## Problem

`seon.sci.reader` decides special-form semantics from the unqualified NAME of
an operator and recursively searches all nested data for the same names. That
is still hand-maintained name matching. It confuses executable
`clojure.core/ns`, `clojure.core/in-ns`, `defn`, and `deftest` forms with
qualified lookalikes and inert quoted occurrences.

The dangerous direction is silent misattribution: `(other/in-ns 'b)` is treated
as the core operation, so a following declaration is indexed under `b` even
though the call does not change the reading namespace.

## Evidence

- `src/seon/sci/reader.cljc:289-304` recognizes every operator named `ns` as a
  namespace declaration, regardless of its namespace.
- `src/seon/sci/reader.cljc:198-205,247-255` similarly turns `foo/defn` and
  `foo/deftest` into false function and test facts.
- `src/seon/sci/reader.cljc:306-322` says the rule is "never a list" while
  testing the literal set `#{"ns" "in-ns"}` after discarding symbol
  qualification with `name`.
- `src/seon/sci/reader.cljc:324-349` repeats the name-only comparison for the
  actual reading-context transition.
- An isolated source probe over
  `(ns audit.a) (other/in-ns 'audit.b) (defn f [] :ok)` emitted the complete
  row `audit.b/f`; no refusal fired.
- `(ns audit.a) '(in-ns 'audit.b) (defn f [] :ok)` instead cleared
  attribution and refused `f`, even though quoted data cannot change the
  namespace. An `in-ns` occurrence inside a function body has the same false
  effect.
- Isolated probes made `(foo/ns audit.b)` emit a phantom namespace,
  `(foo/defn ghost [] 1)` emit a function fact, and
  `(foo/deftest ghost)` emit a test fact.

The issue archived at
`archive/indexer-namespace-allowlist-dropped-two-thirds-of-the-program-graph.md`
removed the old namespace-stable allowlist. Its namespace-tracking half is not
resolved: the replacement changed the list's polarity and traversal rather
than deriving executable operator identity.

## Owner

`seon.sci.reader/resolved-operation`, function/test declaration recognition,
`declaration-facts`,
`namespace-changing-mention?`, and `next-reading-context`.

## Acceptance

- Real `ns` and `in-ns`, including correctly resolved core qualification,
  receive namespace semantics.
- `fake/ns` and `other/in-ns` remain ordinary calls.
- Qualified lookalikes named `defn`, `defn-`, or `deftest` do not become
  declaration facts.
- Quoted, syntax-quoted, literal, comment, and non-operator occurrences do not
  clear attribution.
- Executable nested `(do (in-ns 'other))` still makes attribution unproven.
- Following declarations are correctly attributed or loudly refused.
- No name-only comparison, regex, namespace-prefix rule, per-file case, or
  safety allowlist owns the decision.
