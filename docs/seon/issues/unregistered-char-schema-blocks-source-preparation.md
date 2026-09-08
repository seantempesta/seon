---
type: issue
status: open
severity: blocker
tags: [issue, schema, test]
---

# Unregistered character schema blocks source preparation

Observed 2026-09-08 by record-render at snapshot
`628025af69ae1d60c19e480b00cd02c50507c75c`.
`src/seon/id.clj:45` declares `:char` in `seon.id/symbol-in`'s contract.
The installed Malli registry rejects it as
`{:type :malli.core/invalid-schema, :data {:schema :char, :form :char}}`.

`bin/test my.plan-test seon.render.ns-test` exited 1 preparing its shared
published base in `seon.schema/build-projection`; no tests ran. Development
adoption rejected the same schema, and evaluating
`(try ((requiring-resolve 'malli.core/schema) :char)
      (catch Exception e (ex-data e)))`
through MCP in default JVM mode reproduced it.

The owning identity/schema change must use a registered character contract
and prove ordinary source publication plus the canonical test-base path.
Record-render stopped without modifying that concurrent change. Evidence:
[record-render landing](../../prds/context-generation/research/record-render-landing-2026-09-08.md).
