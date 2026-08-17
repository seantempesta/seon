---
type: issue
status: open
severity: blocker
tags: [issue, test, operator, wave/program-graph-indexing]
---

# `bin/seon init` cannot index the script-only Markdown var

`bin/seon init` refuses during static program analysis because
`test/seon/dev/markdown_test.clj:203` and `:211` call
`seon.dev.markdown/validate-repository-pins`, while that public var is defined
only in `script/seon/dev/markdown.clj:1112`. The source publication analyzes
the first-party `src/` and `test/` roots, so clj-kondo cannot resolve the var
from the test namespace and emits a blocking `:unresolved-var` finding.

The failure is present at HEAD with both files clean. An isolated invocation,
`bin/seon --root tmp/core-call-init-before.EhUpvA init`, refused before source
publication completed on 2026-08-17. Its 17.97-second wall time is a failed
attempt, not a usable initialization baseline.

## Impact

Any lane required to prove or time a complete `bin/seon init` is blocked before
its own indexing changes execute. The refusal output also includes every
warning, obscuring the single blocking finding; that separate diagnostic defect
is already recorded in
`docs/seon/issues/context-wave-leaves-three-small-honesty-defects.md`.

## Acceptance

The repository-pin regression and its owner occupy one indexed source model,
and a fresh isolated `bin/seon init` completes without an unresolved-var
finding for `md/validate-repository-pins`.
