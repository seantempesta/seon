---
type: issue
status: resolved
severity: blocker
tags: [issue, agent, schema, database]
---

# Use a current database value in the frozen-prompt regression

## Problem

The frozen-prompt regression supplied a retired string-based Datahike
connection ID. Both its request and rendered-prompt schema assertions therefore
failed at their shared database value before Malli examined a prompt-specific
field.

## Evidence

`tmp/plan-evidence/test-writer-2026-07-26-prompt-diagnosis-before.log`
reproduces two failures in
`seon.agent.prompt-test/composes-the-established-frozen-prompt-projections`.
The fixture in `test/seon/agent/prompt_test.clj` supplied:

```clojure
{:store-id ["prompt-test" "main"]}
```

`tmp/plan-evidence/prompt-malli-explain-2026-07-26.log` records
`seon.schema/explain-candidate-value` for both rejected values. Each explanation
contains only these three errors:

- path `[:seon.db/db :store-id 0]` expected `:uuid`, received
  `"prompt-test"`;
- path `[:seon.db/db :store-id 1]` expected `:keyword`, received `"main"`;
- path `[:seon.db/db :store-id]` rejected the remote-writer three-element
  tuple by `:malli.core/tuple-size`.

No prompt, context, configuration, or rendered projection field appears in
either explanation.

The current owner is `src/seon/db/protocol.cljc`'s registered
`:seon.db/db` schema. It accepts Datahike's self-writer connection ID
`[store-id branch]` and remote-writer ID `[store-id branch backend]`, where
`store-id` is a UUID and `branch` and `backend` are keywords.
`src/seon/db/writer.clj`'s `database-value` takes `:store-id` directly from
Datahike's committed value identity. The neighboring retained fixtures in
`test/seon/db/protocol_test.clj` and `test/seon/db/portable_test.cljc` already
use `[uuid :db]`.

Dependency ledger:

- Malli `0.20.0` is pinned in `deps.edn`; maintained source is
  `reference-code/malli` at
  `80138076960e`.
  `malli.core/explain` returns the rejecting paths used above.
- Datahike is the local root in `deps.edn`; maintained source is
  `reference-code/datahike` at
  `caf526850084`.
  `datahike.store/connection-id` constructs `[store UUID, branch]` for
  `:self` and appends the writer backend for remote writers.

This is root-cause class **(a)**: the test fixture drifted from the real prompt
input shape. The registered schema and prompt producer both describe and carry
the surviving database-value mechanism correctly.

## Owner

`test/seon/agent/prompt_test.clj` owns this frozen prompt fixture. The
production database-value schema remains owned by `seon.db.protocol`; the
prompt code must continue to pass that complete value through unchanged.

## Acceptance

- The fixture uses the self-writer connection ID shape that the production
  database-value producer emits.
- Both prompt candidate schemas validate the real `prompt/render` request and
  result.
- The focused prompt writer tests pass.
- The full writer gate loses exactly these two failures while retaining the
  separate ordered-collection contract failure.

## Resolution

The fixture now uses a current self-writer connection ID,
`[#uuid "10000000-0000-0000-0000-000000000001" :db]`. No production prompt
code or registered schema changed.

`tmp/plan-evidence/test-writer-2026-07-26-prompt-focused-after.log` records
two tests and 16 assertions with zero failures and zero errors.
`tmp/plan-evidence/test-writer-2026-07-26-frozen-prompt-final.log` records
549 tests and 3,865 assertions with one failure and zero errors. The only
remaining failure is the separate ordered-collection contract with its seven
recorded offenders.

Resolved by the path-limited commit that archives this note.
