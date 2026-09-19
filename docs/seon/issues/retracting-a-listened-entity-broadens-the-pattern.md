---
type: issue
status: open
severity: blocker
created: 2026-09-19
tags: [issue, database, schema, agents]
---

# Retracting a listened entity broadens its pattern

## Problem and evidence

`resources/seon/schemas/seon.listen.edn:2` makes the entity constraint an
optional ref and defines absence as matching any entity. Datahike retracts
incoming ref datoms when the target is deleted. The remaining pattern is
valid, but `src/seon/cluster/wake.clj:430` now takes its nil entity as a
wildcard. A targeted subscription can become a broader subscription without
the agent requesting it.

This is a source-derived deletion consequence, not a live mutation proof.
The audit found zero current instances; that does not make the model safe.
Full evidence is in
[audit B](../../prds/steward-platform/research/schema-audit-b-supplement-2026-09-19.md).

## Owner and acceptance

The listen schema and existing wake matcher own the semantics. Preserve
the distinction between an intentionally unconstrained pattern and a
constraint whose subject was deleted. Choose a modeled refusal, removal of
the owned pattern, or persistent observed target token according to the
intended lifetime; do not interpret a swept ref as authorization to broaden.
Canonical-fixture regression: create targeted and wildcard patterns, retract
the target, and show that unrelated entities wake only the intended wildcard.
