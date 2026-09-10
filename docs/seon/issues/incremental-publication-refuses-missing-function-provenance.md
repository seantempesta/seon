---
type: issue
status: open
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
