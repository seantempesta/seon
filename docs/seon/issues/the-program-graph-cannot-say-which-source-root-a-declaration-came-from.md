---
type: issue
status: open
severity: friction
tags: [issue, schema, program-graph, class/p2]
---

# The program graph cannot say which source root a declaration came from

Measured on `default` 2026-09-16 (basis 536871343): 37 public source-bearing
functions carry no `:seon.fn/doc`. Of those, 25 are in namespaces that declare
deftests and 6 are `defrecord` constructors; the fix a steward would want
applies to a handful. No stored fact separates `src/` from `test/`:
`:seon.fn.file/path` is an absolute path, `:seon.schema.admission/source` is
only `:core` or `:agent`, and a namespace entity carries no root.

So every detector over the function population — the docstring standard, the
reaching-test standard (303 subjects), the generator standard — is forced to
either file test-helper findings as if they were production findings, or to
invent a name rule, which is one of the three banned substitutes.
[detectors-and-standards §2](../../prds/steward-platform/research/detectors-and-standards-2026-09-16.md)
reached the same conclusion from the other side: 72 of a full run's 733
subjects would land on one test namespace.

`seon.issue.detect/public-without-doc` ships WITHOUT a root scope, so its 31
subjects on `default` include the 25 test-namespace helpers. That is deliberate:
a wrong exclusion is worse than an honest over-report, and the fix belongs where
the fact is written.

Acceptance: an indexed declaration carries its declared source root as a fact
(for example `:seon.fn.file/root`, written where the publication already knows
the classpath root it read the file from), a detector scopes on that fact, and
one regression asserts a first-party production function and a test helper are
distinguished by the query rather than by their names.
