---
type: issue
status: open
severity: blocker
tags: [issue, runtime, database, schema]
---

# Read-side attribute admission fails open (silent empty results, :all fallback)

## Overnight triage — 2026-07-23

**FOLD-INTO-UNIT — P3 read-side admission.** P3 owns applying the existing
query/pull dependency extractor as a fail-closed gate against the committed
projection.

## Evidence (verified from source)

- Write side is gated: `seon.db.internal/validate-attrs!`
  (`src/seon/db/internal.cljc:351`) rejects unregistered transaction
  attributes with steering.
- Read side has the EXTRACTION but not the GATE:
  `seon.db/read-attribute-dependencies` (`src/seon/db.cljc:532`) already
  derives the exact attribute set for queries AND pulls deep in the
  Datahike parser (`datahike.query/query-dependency-plan`,
  `datahike.pull/pull-dependency-plan`). It is consumed only for
  interest/edge projection; a query or pull naming a misspelled or
  unregistered attribute returns empty/nil SILENTLY.
- Both fallbacks are fail-open: `read-attribute-dependencies` catches
  Throwable → `:all`; `seon.db/transact!` validation is skipped whenever
  the leaf `schema-validation?` context fn reports false
  (`src/seon/db.cljc:483-488`) — the projection-empty window.

## Class

Misspelled/unregistered attribute reads are the read-side sibling of the
drill's `:seon.agent.run/current-turn` write rejection. P3's
"pull-pattern admission" closes it by REUSING the one existing extractor
against the committed projection — steering errors that distinguish
derived projection keys from stored attributes. No second parser.

## Acceptance

- A query/pull naming an unregistered attribute returns a steering error
  naming the attribute and the nearest registered candidates.
- Derived projection keys are distinguished from stored attributes in the
  steering.
- The `:all` fallback remains legal for INTEREST only; admission never
  falls open.
- One class regression per side (query, pull).
