---
type: issue
status: open
severity: friction
created: 2026-09-24
tags: [issue, render, problems, agent-platform]
---

# A declared render pair on an all-optional map is never selected

Observed on default pid 99265, 2026-09-24, lane fix-problems-prose-bound.

`:seon.problems/problems` declares `#:seon.render{:ai seon.problems/ai-prose
:html seon.problems/html-report}` (`resources/seon/schemas/seon.problems.edn:68`).
The value validates against it (`malli.core/validate` true on the live value), yet:

- `(seon.schema/matching-shapes-in projection value)` answers `()` for it, so
  `seon.render/schema-producers` (`src/seon/render.clj:353`) never selects the pair.
- Under the readiness attribute `:seon.problems/problems` the child is not
  attribute-scoped (`project-node*` dissocs `:seon.render.walk/attribute` below the
  root, `src/seon/render.clj:1142`), so `attribute-producer` is not asked either.
- SCI `(seon.problems/problems (seon.db/db) {})` and
  `{:seon.problems/problems (seon.problems/problems (seon.db/db) {})}` both render
  structurally (bounded, elisions named), never through `ai-prose`.

So the declared AI prose reaches no reader through the renderer; only direct calls
(tests, probes) run it. Owner: `seon.schema/matching-shapes-in` / `seon.render`
selection. Decide whether a map schema with no required attributes is a shape.

## 2026-09-24 — selection fixed; live adoption gap remains

Decided: a map schema that declares a `:seon.render/*` pair is a shape even with no
required attribute (`seon.schema/shape-row-in`), indexed by its declared entries exactly
as all-optional entity rows already are. A value is of it when it carries one of those
declared attributes and validates. Regression:
`seon.schema-test/an-all-optional-map-declaring-a-render-pair-is-a-shape`. Census: of
358 render-declaring schemas, 347 are map shapes and only `:seon.problems/problems` lacked
a row; 202/203 generated samples with an AI renderer were selected, the exception being
this one (lane-fix-render-pair-selection-2026-09-24.md).

Still open (owner: `seon.db/carried-projection`, `src/seon/db.clj:1410`): projections are
memoized by declaration content/revision and rebuilt incrementally, so a change to the
projection builder's code (`shape-row-in`) never reaches a cached projection. On default
pid 99265 after adoption the loaded `shape-row-in` returns the problems row but the live
projection still has none, so `schema-producers` still answers `[]`. The key omits an
input the derivation reads. Until that owner fixes it, the live change needs a restart.
