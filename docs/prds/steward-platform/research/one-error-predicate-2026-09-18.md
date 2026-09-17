---
type: research
status: open
created: 2026-09-18
tags: [errors, predicate, contracts]
---

# One error predicate

## Result at the held-file boundary

Eight of the nine private predicate copies now delegate to the existing
`seon.error/error?`. `seon.call-preparation` resolves that Var once through a
delay because `seon.error` already requires `seon.call-preparation`; the other
owners use the namespace directly. `src/seon/db.clj` remains unchanged because
the shared tree showed uncommitted edits owned by `publication-report-projection`.

The same change makes the two recorder existence reads and the message
recipient read preserve a recognized error value instead of applying `some?`
and reporting that the refused read found an entity. The complete B1 proof is
not claimable until the database owner is released: marker-only upstream
database errors still pass through `seon.db/error-value?`, the ninth held copy,
before these consumers can receive them.

## Dated site inventory

The representative value is the declared marker class
`{:my.fs/not-found "tmp/absent" :seon.error/message "File not found."}`. It has
no `:seon.error/kind`.

| Owner | Before | After at this boundary |
|---|---|---|
| `src/seon/db.clj` | Required kind plus message and therefore read the representative value as ordinary data. | **Held and unchanged.** Must add the existing lazy `seon.error/error?` Var and use it at every current call, including `transact-call`'s verbatim refusal arm. |
| `src/seon/operator.clj` | Required kind plus message. | Calls `seon.error/error?`; the representative value is a refusal. |
| `src/seon/plan.clj` | Required a kind. | Calls `seon.error/error?`; the representative value is a refusal. |
| `src/seon/call_preparation.clj` | Required kind plus message. | Calls the once-resolved `seon.error/error?`; the representative value is a refusal without introducing the existing load cycle. |
| `src/seon/note.clj` | Required a kind. | Calls `seon.error/error?`; the representative value is a refusal. |
| `src/seon/cluster/message.clj` | Required a kind. | Calls `seon.error/error?`; inbox/send paths refuse the representative value. Its recipient lookup also returns an upstream read error verbatim. |
| `src/seon/render/ns.clj` | Required a kind. | Calls `seon.error/error?`; namespace rendering returns the representative value instead of rendering it as a row. |
| `src/seon/instrument.clj` | `flat-error-value?` required kind plus message. | Buried-error detection calls `seon.error/error?`; an armed boundary preserves the representative value as its own face. |
| `src/seon/schedule.clj` | `flat-error?` required kind plus message. | Maintenance settlement calls `seon.error/error?`; the representative result enters error settlement. |

`seon.error/agent-exists?`, `seon.error/entity-exists?`, and
`seon.cluster.message/agent-exists?` now classify the query result first. A
recognized read error is returned unchanged; only a successful query result is
converted to a boolean.

## Verification

- Requiring all nine edited non-database namespaces in one JVM completed with
  `:loaded`.
- `git diff --check` passed for the source changes.
- Requested fast command:
  `bin/test-fast --paths src/seon/operator.clj src/seon/plan.clj src/seon/call_preparation.clj src/seon/note.clj src/seon/cluster/message.clj src/seon/render/ns.clj src/seon/instrument.clj src/seon/schedule.clj src/seon/error.clj -- seon.error-test seon.db-test seon.plan-test seon.cluster.message-test seon.schedule-test seon.instrument-test`.
- Fast tally: **no tests launched**. The snapshot listed the nine owned source
  paths and refused before its JVM because no published program graph exists;
  it instructed the orchestrator to run `bin/test --prepare-head-base`.
- Seon runtime MCP evaluation was unavailable in this session. No live
  evaluation or adoption proof is claimed.

## Work remaining after release

1. Replace `src/seon/db.clj`'s held private predicate with a once-resolved
   `seon.error/error?` and delete the ninth copy.
2. Prove `transact-call` returns a thrown marker-class refusal verbatim without
   `:seon.db/transaction-outcome-unknown`; retain that classification for a
   genuinely unconfirmed bounded write.
3. Add the one armed canonical-fixture class regression covering all nine
   consumers plus `transact-call` and the two recorder existence helpers with
   a marker-only poisoned database value.
4. Rerun the requested fast namespaces after the orchestrator publishes a
   program-graph base. The orchestrator still owes the cold and platform gates.

## Foreign boundaries

The main tree had concurrent uncommitted changes in `src/seon/db.clj` and later
became syntactically unreadable in `test/seon/test/selection_test.clj`. Work
continued from detached HEAD `49a26d725` in
`tmp/one-error-predicate-wt`, with the main checkout's `reference-code` linked
for dependency loading. No held source, schema resource, default-cluster
lifecycle, foreign lane, or foreign session was operated.
