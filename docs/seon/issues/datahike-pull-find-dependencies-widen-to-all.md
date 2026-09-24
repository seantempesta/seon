---
type: issue
status: open
severity: friction
created: 2026-09-24
tags: [issue, database, query, datahike, cache, upstream-delta]
---

# Datahike widens a find pull's attribute dependency to `:all`

Seen 2026-09-24 by lane fix-datahike-var-attr. The fork's own
`datahike.test.query-test` fails four assertions at `c79cd03a` (before that
lane's change) and after it (`620721d6`):
`query-attribute-dependencies-are-public-and-conservative` at
`query_test.cljc:58`, `:69` and `:75`, and
`query-dependency-plans-are-execution-aware-and-source-scoped` at `:152`
(line numbers after `620721d6`). Each expects a find pull such as
`(pull ?e [:person/id {:person/friend [:person/email]}])` to contribute its
realized attributes, but `query-attribute-dependencies` answers `:all`. The
cause is not yet diagnosed: `extract-find-pull-source-deps`
(`reference-code/datahike/src/datahike/query.cljc`) calls
`dpa/pull-spec-attribute-dependencies`, so start there.

Consequence: every query with a find pull depends on all attributes, so every
commit invalidates its cached result. `query_cache_test.cljc:770`
(`config-mismatch-does-not-open-or-alter-cache-generation`) also fails on both
trees, and it was already listed as red on 2026-09-23 in
`docs/prds/agent-platform/landing/lane-datahike-fncall-revisions-2026-09-23.md`.
