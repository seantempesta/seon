---
type: issue
status: open
severity: friction
created: 2026-09-17
tags: [issue, error-model, instrumentation, render, honest-boundary]
---

# A collection member refusal does not name the member's index

`940f4b426` ("Preserve refusal cause evidence") made a homogeneous
collection violation report the failing MEMBER and its element schema
instead of the container-level contradiction. That is the ruled direction:
the error-entities PRD's "Offending value" row
(`docs/prds/steward-platform/plan/error-entities-prd-2026-09-17.md:56`) says
the fault retains the bounded projection of its own LEAF, and the member
wording is what `seon.error/explain-problem`
(`src/seon/error.clj:859-872`) now produces.

`seon.error/collection-member-problem` (`src/seon/error.clj:812`) replaces
the problem's `:schema` and `:value` with the member's, but leaves
`:seon.error/path` as the CONTAINER's path. Measured 2026-09-17 through
`seon.instrument-test/refusal-value-projection-obeys-the-profile-and-html-keeps-the-whole-value`
on the fast harness, the rendered sentence for a `[:vector :int]` input whose
ninth member is a 40-element vector reads:

```text
my.agents.audit/vector-input refused argument 0 (0-based) at []: expected a
collection member satisfying an integer (:int), got a collection member that
is a vector [100 101 102 ...]. Fix: Supply an integer at [].
```

Two members of that sentence are not true of the value the reader holds:

- `at []` locates the refusal at the whole argument, not at the member. The
  member's own index is known at the seam — `collection-member-problem`
  already walks the collection to find it — and is dropped.
- `Supply an integer at []` instructs a repair at the container. Following it
  literally replaces the argument, not the member.

The evidence itself is intact: the whole checked argument is still retained
unprojected at `[:seon.error/data :seon.error/diagnostic-offending]`
(`src/seon/instrument.clj:388`), and the regression named above asserts that.
The defect is the reported LOCATION, not lost data.

Consequence for the HTML face: because the rendered sentence narrowed to the
leaf, the container no longer appears in `seon.error/render-html` at all. The
leaf is kept whole there (no presentation clipping, verified in the same
regression), and the container remains reachable as a fact, so this is
acceptable today — but a reader of the page cannot see WHERE in the argument
the member sat, which is the same missing index.

Fix: carry the member's index from `collection-member-problem` into
`:seon.error/path` and into the fix sentence, so the path names the leaf the
offending value now is. One regression asserting a nonempty path and an
index-bearing fix for a collection member violation kills the class.

Out of scope for the lane that measured it (it owned
`test/seon/instrument_test.clj` and `src/seon/instrument.clj`; the root is
`src/seon/error.clj`).
