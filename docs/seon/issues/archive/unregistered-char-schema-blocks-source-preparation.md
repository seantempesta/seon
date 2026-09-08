---
type: issue
status: resolved
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
[record-render landing](../../../prds/context-generation/research/record-render-landing-2026-09-08.md).

## Resolution — config-apply, 2026-09-08

The owner's restart rule authorized repairing this prerequisite once the file
had no concurrent edits. `symbol-in` now references `:seon.id/character`, declared
as Malli's built-in `char?` in `resources/seon/schemas/seon.id.edn`. Declaring it
in EDN preserves the built-in symbol: first-party metadata normalization had
qualified an inline predicate to the unregistered `clojure.core/char?` spelling.
There is no new predicate implementation or generator. The live JVM verified
character acceptance, string refusal, and seeded generation of an actual
character. Malli declares the predicate in
`reference-code/malli/src/malli/core.cljc:2930`.

Source publication passed at commit `6aa05414-2e41-5dc4-b8fe-877523080259`, digest
`9f14658e002b978f0f53e078ceaf9ff0d232a477dae3ceae4cc6962125176115`.
The canonical gate includes `seon.id-test`; final tallies are in the
[config-apply landing](../../../prds/context-generation/research/config-apply-landing-2026-09-08.md).
