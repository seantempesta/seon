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
