---
type: issue
status: open
severity: blocker
tags: [issue, test, wave/publication-provenance]
---

# Admitted test results are invisible to legacy consumers

`seon.test.runner/record-tx` (`src/seon/test/runner.clj:2874`) routes admitted
runs to `complete-members`. That writer records immutable member outcomes and
failure reports, without the old latest-result attributes on test declarations.
Unadmitted runs still use `record-latest-tx`; migrating `seon.test/run` to shared
admission changes which facts its consumers can observe.

The read-only
[published-authority probe](../../prds/steward-platform/research/host-result-consumer-probe-2026-09-20.edn)
on 2026-09-20 returned four completed members for run `a9a52dedca74`, with
pass counts 16, 29, 5, 1 and zero failures/errors. Pulling the corresponding
test declarations returned three symbol-only maps and one absent declaration;
none had the legacy result attributes. The absent declaration is the honest
snapshot-only test, not an assertion that the checkout was published. MCP
reported 7,035 ms, no elision, and the default cluster alive. No runtime
mutation, reload, publication, or lifecycle operation was performed.

Consumers still reading the old facts include:

- `src/seon/issue.clj:822`: `tests-done-query` requires positive
  `:seon.test/pass-count` and zero fail/error attributes on the test row.
- `src/seon/issue.clj:661`: issue opening data pulls those same attributes.
- `src/seon/problems.clj:348`: failure discovery queries test-row counts.
- `src/seon/test.clj`: `stale` and `verified?` read test-row results/reach.
- `src/seon/render/test.clj`: renders the old counts and failure family.

Therefore converting host admission and its direct callers alone cannot preserve
issue settlement and failure reporting. This is a reader migration dependency,
not a reason to duplicate stored latest-result projections in the new recorder.
`test/seon/problems_test.clj` has foreign edits; its ownership must be respected.

The owning
[landing note](../../prds/steward-platform/research/results-reuse-everywhere-2026-09-20.md)
records the passing draft host regression and the three scoped options.
Acceptance: the same admitted green/red member facts drive host reuse, issue
completion, failure discovery and rendering; no second latest-result fact family
is introduced, and every changed reader has canonical fixture coverage.
