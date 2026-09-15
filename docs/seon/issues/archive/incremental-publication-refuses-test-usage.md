---
type: issue
status: resolved
severity: friction
tags: [issue, operator, tooling, wave/publication-velocity]
---

# Incremental publication refuses a test row without usage

On 2026-09-10, default PID 23557 rejected the display lane's
`bin/seon init --dev default --changed src/seon/repl.clj src/seon/render/value.clj src/seon/render/web.clj`
after reporting `incremental scalar publication: 4 paths; reasons=()`.

The exact diagnostic was `Attribute :seon.test/usage expected :boolean, got
:seon.error/unknown.`, path `[0 :seon.test/usage]`, cause
`:malli.core/missing-key`. The rejecting shape was
`[:map {:seon.db/attributes true :seon.render/form my.turn/usage-form}
[:seon.test/sym :seon.test/sym] [:seon.test/usage [:= true]]]`.
The enclosing error was `:seon.cluster.source/incremental-source-refused`,
expected source commit `6aa31165-c246-57ca-9f24-20ca37b9653d`.

Complete publication is the existing operational fallback. The display lane
did not change publication, validation, or another lane's files, and did not
restart default. Its landing note records the fallback's outcome.

Verify the incremental test-row projection and matching of the usage-specific
shape on the real source fixture. A normal test wording change must publish
without inventing a usage declaration or weakening database admission.
Related, previously resolved function-row case:
[missing function provenance](incremental-publication-refuses-missing-function-provenance.md).

## Canonical SCI reproduction — 2026-09-14

The core-functions lane also reproduced this without incremental publication:
evaluate `(clojure.test/deftest example-arithmetic
(clojure.test/is (= 2 (+ 1 1))))` in the canonical agent context, then transact
`(seon.program/canonical-row (:seon.program/row evaluation))`. Admission
refuses the normal test row at `[0 :seon.test/usage]` with the same diagnostic.
This is therefore also an agent declaration admission defect. Adding explicit
usage metadata to that runtime source still produced a row without the fact.
The doc-example fixture therefore uses a statically indexed test to exercise
`my.test/run`; it does not claim runtime `deftest` admission repaired. The
schema/admission owner is outside the core-functions assignment.

## Resolution — 2026-09-14

The authorized follow-up found `:my.turn/usage-unit` incorrectly marked as a
database entity schema. It is a render request requiring usage=true, not a
constraint on every test entity. Removing its `:seon.db/attributes` marker
preserves that render contract and admits normal tests through the existing
test schema. The canonical regression evaluates a bare `deftest`, transacts
its unmodified canonical row, and verifies the stored source. Both older
auto-check fixtures that store ordinary tests also pass with admission asserted.

The separate metadata-loss observation is tracked in
[runtime test usage metadata](../sci-test-declarations-drop-explicit-usage-metadata.md).
