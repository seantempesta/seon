---
type: issue
status: resolved
severity: blocker
tags: [schema, datahike, bridge, error, class/p1]
---

# Inherited error facets select ordinary identity write schemas

## Resolution, 2026-09-21

The owner ruled separate observation members for the five error schemas.
Both bootstrap errors now use the existing `:seon.error/run`; accretion and
runner errors use namespace-owned qualified-symbol values. Admission refuses
any error schema with a declared identity attribute member. Writer selection
uses all required identity schemas again, with no row-schema exception.

The bridge scratch publication and `s2` boot succeeded. Its actual startup
turn `12a2b18544e6` was stored, and the compiled writer selects only
`:seon.turn/turn` for `:seon.turn/id`. A synthetic direct probe refuses both
required and optional identity members with schema and attribute named.
The canonical regression is retained; its final recorded rerun awaits the
published-base refresh described in the bridge note. Historical evidence follows.

The bridge step-2 compiled conversion exposes a writer selection decision.
`seon.db/write-entity-schemas` selects entity schemas with
`:seon.db/attributes` that require an identity attribute. Compiled entity
properties follow the base reference in an error facet; the old writer's
one-argument `schema.form/schema-properties` did not follow that reference.

`resources/seon/schemas/seon.bootstrap.edn` declares
`:seon.bootstrap/unmatched-source-error` and `prefix-drift-error` as
conjunctions of `:seon.error/base` and maps requiring `:seon.turn/id`.
They therefore become write schemas for an ordinary turn under the compiled
conversion. `:seon.turn/id` has no `:seon.program/row-schema` declaration,
so the authorized program-identity exception does not exclude these facets.

Observed in the armed HEAD-plus-bridge snapshot at
`acb39cb6372919d94f409ea61c1c4e07526fc35f`, JVM 7478:
`seon.ai-stream-fold-test/a-real-jdk-provider-status-commits-with-its-attempt`
rejects its turn with missing `:seon.error/at`. The same failure recurs in
the reasoning settlement and per-agent stop fixtures. Evidence:
`tmp/bridge-step2-retirement-fast.log`, first refusal at line 116.
The run was stopped at this decision, with no completed tally.

This is not a native attribute parity difference. The step-2 specification
forbids changing writer selection guarantees without a ruling. Options are
recorded in the [bridge note](../../prds/steward-platform/research/bridge-step2-walker-2026-09-21.md):
preserve explicit write-declaration selection using compiled nodes, declare
row schemas on every identity, or separate observed identity tokens in
the error facets. No writer-policy repair has been applied.

Resolution must prove an ordinary turn writes successfully while required
error-facet members and final whole-entity validation remain enforced.
