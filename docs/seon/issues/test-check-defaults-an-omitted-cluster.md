---
type: issue
status: open
severity: friction
created: 2026-09-16
tags: [issue, config, test, wave/config-cluster-identity]
---

# Test checking silently substitutes the default cluster

## Problem

`seon.test/check` and `check-in-process` substitute `"default"` for an omitted
`:seon.boot/cluster-name`; the deferred fixture command repeats the substitution.
The request schema still marks the cluster optional. A missing input therefore
selects another cluster's configuration instead of refusing by caller name.

## Evidence

The owning `resources/seon/schemas/seon.test.edn` was concurrently edited during
the no-default-cluster assignment. The config compiler/reader and scheduler
portions are independent work in the same class and have since landed.

On 2026-09-16 the continuation found that file free and the real blocker one
level down. `:my.test/check-request` IS `:seon.test/check-request`
(`resources/seon/schemas/my.test.edn:1`), and `my.test/check`
(`src/my/test.clj:18`) is the agent-facing surface. Requiring the cluster at
that one declaration therefore refuses every agent call, because an agent has
no way to name its own cluster: `config/default.edn:497-516` declares exactly
four call-preparation suppliers — `:seon.db/db`, `:seon.db/connection`,
`:seon.agent/id`, `:seon.search/handle` — and no `:seon.boot/cluster-name`.
No `my.*` function requires that key today.

The environment already carries the name
(`resources/seon/schemas/seon.env.edn:16`), so the missing piece is one
ordinary supplier, `seon.env/supplied-cluster-name`, shaped exactly like
`seon.env/supplied-agent-id` (`src/seon/env.clj:303`), plus its one row in
`config/default.edn`. That file currently carries another slice's uncommitted
`:seon.search/handle` supplier row, whose `seon.search/supplied-handle`
(`src/seon/search.clj:101`) is itself uncommitted; committing the manifest
now would publish a supplier row naming an unpublished function. That
ordering, not a held file, is what stops this seam.

`src/seon/test.clj` was concurrently held while this was written. `a00e73e49`
(2026-09-16 16:38) added the 81-line `seon.test/check-request` to it without
the `:seon.test.check/request` declaration that contract names, so for twenty
minutes every snapshot taken from HEAD alone refused instrumentation with
`seon.instrument/apply!` / `:malli.core/invalid-schema` on
`seon.test/check-request`. `804990d63` (16:58) committed
`resources/seon/schemas/seon.test.check.edn` and closed that window; HEAD arms
again, and `src/seon/test.clj` is clean. The remaining blocker is the supplier
ordering above, not a held file.

## Owner

`src/seon/test.clj` and its request in `resources/seon/schemas/seon.test.edn`.

## Acceptance

[The landing note](../../prds/steward-platform/research/no-default-cluster-fallback-2026-09-16.md)
records the exact unapplied hunks and callers to inspect. Land
`seon.env/supplied-cluster-name` and its `config/default.edn` supplier row
FIRST, once that manifest carries no unpublished supplier. Then require the
cluster at the request declaration, delete all three substitutions, and hand
the fixture cluster through direct check requests in
`test/seon/test_reaching_test.clj`, which omits it at eleven call sites. A canonical-fixture regression
must assert an omitted cluster yields a typed refusal naming `seon.test/check`
and leaves the database basis unchanged; a non-default cluster must retain
its name in the deferred command.
