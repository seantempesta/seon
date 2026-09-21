---
type: issue
status: resolved
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

## 2026-09-16 — resolved

`925ca19fe` makes the walked source root a fact on the file entity
(`:seon.fn.file/root`), written where `seon.fn/artifact` mints the row and
replaced with it on re-index. `seon.issue.detect/public-without-doc` keeps its
unscoped over-report and gains an optional `{:seon.fn.file/root "src"}` scope
that joins positively on the fact. Adopted on `default` at `:current-src`
commit `6aaa4925-cb32-5a6e-b004-105078c43e7a`: 335 file entities, **106 `src`**,
**228 `test`**, one under no declared root; the docstring standard's 31 subjects
are **2** under `src`, **28** under `test`, and one agent-admitted declaration
with no file. Evidence, including the cold-gate boundary for the fixture
regressions, is in
[source-root-fact-2026-09-16](../../prds/steward-platform/research/source-root-fact-2026-09-16.md).

### The blocker this had to clear first

The fix was scoped and verified live, then stopped: the
file row is minted through `seon.program/canonical-row`, which keeps only the
attributes named in `seon.program/shapes` for `:seon.fn.file/path`
(`src/seon/program.cljc:41`). A `:seon.fn.file/root` written by the indexer is
silently dropped there, and re-index replacement uses the same list, so the
fact needs one element added to that vector in `src/seon/program.cljc` — a file
protected by the concurrent reach-closure verification; the coordinator cleared
exactly that one element, and it is the second time in a day that mirror
silently stripped an owned attribute
([the mirror issue](program-shapes-mirror-the-schema-row-maps-by-hand.md)).

Two findings that constrain the eventual fix:

- `seon.fn/source-roots` is `["src" "test"]` and the indexer has no `script/`
  handling; `seon.cluster/source-roots` widens only the source snapshot.
- Default holds 335 `seon.fn.file` entities: 106 under `src/`, 228 under
  `test/`, and one under `docs/` (a lane probe script minted by the edit hook's
  changed-path seam, which admits any `.clj` regardless of root). The root
  attribute must therefore be OPTIONAL, absence must mean "under no declared
  root", and consumers must join positively on the root value — a negation
  would read absence as `src` and re-create the class this project keeps
  hitting.

Evidence and the ready-to-land edits:
[source-root-fact-2026-09-16](../../prds/steward-platform/research/source-root-fact-2026-09-16.md).
