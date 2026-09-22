---
type: issue
status: open
severity: blocker
created: 2026-09-23
tags: [issue, sci, acquisition, adoption, reload]
---

# Development adoption leaves the loaded program at the boot commit

## Problem

After a successful `bin/seon --root … init --dev default --changed …` on a
scratch cluster (18.8 s; 25 changed paths, among them `src/seon/test.clj`,
`src/seon/config.clj`, `src/seon/issue.clj`, `src/seon/sci/eval.clj`), an
isolated acquisition through `seon.cluster.agent/acquire-context!` refused with
`Program acquisition refused.`: 423 `:seon.sci.eval/refused-function` entries,
for example `seon.config/apply-compiled!` ("Unable to resolve symbol:
managing-process-identity"), `seon.error/commit-call`, `seon.render/render-ai`,
`seon.test.runner/commit-results!` (evidence blob
`16886a24d1b321fc3fc0676b47c167a1871733367b951b2e9e641a6a04783cc2` on that
root, since reset).

The context's `::loaded-database` is materialized once, at context
construction, from the cluster's recorded `:seon.source/commit-id`
(`loaded-program`, `src/seon/sci/eval.clj:843`; carried by `acquire!` at
`:2318` and every fork at `:2450`). Adoption reloads the changed namespaces in
the JVM but the context keeps the boot commit as "loaded", so every adopted row
is classified overridden and its callers affected, and SCI is asked to
interpret host-bound definitions it cannot.

## Owner and acceptance

The adoption/reload owner (1.2b / reload-per-declaration) together with
`seon.sci.eval` acquisition. Acceptance: after an adoption that reloads a
namespace, the cluster context's loaded program names the adopted commit; an
isolated acquisition immediately afterward installs zero interpreted rows for
unchanged-since-adoption definitions and refuses nothing; one regression
adopts, acquires, and asserts both.
