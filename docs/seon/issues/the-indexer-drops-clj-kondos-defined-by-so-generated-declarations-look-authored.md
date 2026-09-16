---
type: issue
status: open
severity: friction
tags: [issue, program-graph, indexer, clj-kondo, detector, facts-over-inference]
---

# The indexer drops clj-kondo's :defined-by, so generated declarations look authored

## Problem

Nothing in the program facts says which MACRO interned a declaration. A
`deftype`'s constructor, a `defrecord`'s two constructors, a `defprotocol`'s
method vars and a `defmulti` are all stored as ordinary `:seon.fn/sym` rows,
indistinguishable from a hand-written `defn`. Every standard over the
population therefore over-reports on them, and no exclusion can be written
without falling back to a name rule — one of the three banned substitutes.

Measured on cluster `default`, 2026-09-17: of the 8 `src` subjects
`seon.issue.detect/public-without-contract` names, six are declarations no
author can put a `:malli/schema` on —

```
seon.flow/->CountedDroppingBuffer   seon.flow/->RefusingBuffer     ; deftype constructors
seon.print/-close   seon.print/-fragment   seon.print/-open   seon.print/-token  ; defprotocol methods
```

— and `seon.issue.detect/public-without-doc` reports the same two `seon.flow`
constructors (recorded already in decision 5 of
`docs/prds/steward-platform/plan/owner-decisions-2026-09-17.md`). The existing
shared-`:seon.fn/form-span` exclusion catches only the pairs
(`->X`/`map->X` from one `defrecord` form); a `deftype` interns one
constructor, so its span is unshared and the exclusion misses it.

clj-kondo already emits the fact and the analyzer already keeps it: its
var-definition projection lists `:defined-by` and `:defined-by->lint-as`
(`src/seon/fn/analyzer.clj:103-111`, from clj-kondo's `reg-var!` attrs at
`reference-code/clj-kondo/src/clj_kondo/impl/analysis.clj:94-95`). It is the
ROW OWNER that drops it: the only reader of either key in the first-party tree
is `src/seon/fn.clj:322`, which uses `::analyzer/defined-by->lint-as` to spot a
`defmulti`, and no `:seon.fn/*` attribute in `resources/seon/schemas/seon.fn.edn`
stores it. The analysis carries the answer to every run and the database never
learns it.

## Done when

The declaration row keeps the `defined-by` the analyzer already hands it, as a
declared program fact (schema key and docstring in `resources/seon/schemas/seon.fn.edn`),
`seon.issue.detect`'s standards exclude declarations interned by a macro that
writes them for the author — by that fact, never by name — and a regression on
the canonical fixture asserts that a `defprotocol` method and a `deftype`
constructor are not subjects of either standard while a `defn` beside them is.

Found by the first-task-detector lane, 2026-09-17
([landing note](../../prds/steward-platform/research/first-task-detectors-2026-09-17.md)).
