---
type: issue
status: resolved
severity: friction
tags: [issue, operator, tooling, wave/publication-velocity]
date: 2026-09-09
---

# Incremental publication refuses missing function provenance

On the fresh page-review scratch fork, this command refused after reporting
`incremental scalar publication: 2 paths; reasons=()`:

```sh
bin/seon --root /Users/sean/src/seon/tmp/context-page-root init --dev cookbook-page --changed src/seon/bootstrap.clj
```

The function row's `:seon.schema.admission/source` was absent. The database
diagnostic reported expected `[:enum :core :agent]`, offending
`:seon.error/unknown`, cause `:malli.core/missing-key`, path
`[0 :seon.schema.admission/source]`. The enclosing error was
`the incremental source transaction was refused`, against source commit
`6aa1fc2b-56c6-5354-9da5-529dc143d3ff`.

The scratch checkout was `24adad072` plus the committed plan/component delta
and page-review patch, not the shared tree's in-flight data migration. The
changed function was `seon.bootstrap/help-value`, a wording edit. Complete
publication was used to continue the page proof. No default lifecycle action
was taken.

Probe the incremental row projection and admission provenance before changing
the validator. Closure requires an admitted same-function wording edit through
`init --changed`, retaining the function's declared provenance. This observation
does not establish the stale-JVM cause recorded in the older publication issue.

## Resolution — 2026-09-09

Fixed in `1e778e880`: `seon.fn/scalar-upsert-rows` retains each changed
row's scalar declaration, including admission source and the required
function namespace. The existing schema-derived filter excludes unchanged
components and cardinality-many attributes. Authored transaction validation
remains armed and unchanged.

The canonical real-file regression passed with the scoped source gate
(13 tests, 98 assertions) and final platform gate (84 tests, 505 assertions),
both with zero failures/errors. On default, restoring a one-file wording
probe reported `incremental scalar publication: 1 paths; reasons=()` and
exited 0. MCP observed both the cluster's `:seon.source/commit-id` and
`seon.cluster.source/current` equal to
`6aa204e6-946e-5f6e-8648-1e034bf7dc85`. The function retained `:core`
provenance and its namespace. Default remained PID 92059 throughout.

Full evidence and the protected legacy planner-test follow-up:
`docs/prds/context-generation/research/publication-provenance-landing-2026-09-09.md`.
