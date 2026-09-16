---
type: issue
status: open
severity: blocker
tags: [issue, database, schema, wave/schema-admission]
---

# Raw write validation refuses reverse refs and partial entity maps

Data lane probe, 2026-09-09: `seon.db/write-attribute-error` refuses
`:seon.turn/_attempts` as uninstalled even though Datahike accepts the reverse
of the installed ref. `write-map-error` validates a supplied identity map as
an entire entity, so a model gauge update containing only its identity and
changed gauge refuses the model's required provider field.

The data lane's production writers now use ordinary forward `:db/add` facts
for the attempt-to-turn link and model gauges. Attempt recording returns a
refused transaction's diagnostic; the caller cannot freeze a reply or make a
second provider call after that refusal. The armed data-shape and AI tests
exercise those writers.

The general raw-write surface remains narrower than Datahike. Fix validation
at its owning writer admission seam without a racy pre-read of the entity or
another transaction engine. Regression subjects should include reverse ref
map syntax and an identity upsert that supplies only a changed optional
attribute. This is outside chart data-shape steps 3–7.

## Batch 20 reds triage, 2026-09-16 — this is now a gate blocker

Independent triage of the batch-20 named-namespace gate reproduced this class
in-process on default (pid 7595) as the dominant cause of three namespaces'
reds. `write-map-error` applies EVERY `{:seon.db/attributes true}` entity
schema that merely *lists* a row's unique-identity attribute, including as an
optional key, so an identity-only fixture map is validated as an unrelated
entity:

- `{:seon.problems/id "about-first"}` refuses as *"at [1 :seon.cluster.eval/id]
  … got a map missing :seon.cluster.eval/id"*, because
  `:seon.cluster.eval/entity` lists `:seon.problems/id` as an optional key.
- `{:seon.turn/id "gauge-run"}` refuses as *"at [0 :seon.turn/agent]"*.

Because the refusal is a flat value the fixture does not read, the whole seed
transaction silently does not happen and the test then asserts against an empty
database. Measured consequences at `cecfaf428`:
`seon.render.transcript-test` 13 of 15 failing tests (including
`populated-history-restores-the-repl-fidelity-checklist` 10/11/0 and
`about-identity-resolution-pulls-one-deterministic-ordered-id-vector` 2/7/1),
and `seon.turn-loop-test/attempt-settlement-updates-the-registered-model-gauges`
0/4/0. Evidence:
[error-graph batch 20 triage](../../prds/steward-platform/research/error-graph-2026-09-16.md).

The selection rule is the defect: an entity schema that lists an identity
attribute as OPTIONAL is not a claim that every row carrying that attribute is
that entity. Candidate fix at the owning seam — require the identity attribute
to be a REQUIRED key of the schema before that schema governs the row — with
regressions for an identity-only map whose attribute is optional elsewhere,
and for the reverse-ref map syntax already described above.

Secondary, same seam: the refusal message splices an unresolved contract into
prose — *"expected the required key :seon.turn/agent with a value satisfying
unknown error"*, *"a value satisfying invalid type"*. An unavailable expectation
is the typed unknown, never the words "unknown error" in a sentence.
