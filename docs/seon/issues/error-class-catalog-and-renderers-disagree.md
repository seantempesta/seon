---
type: issue
status: open
severity: friction
tags: [issue, error, schema, render, testing]
---

# Reconcile the error-class catalog with declared schemas and renderers

## Problem

The dated error-class census no longer agrees with the queryable declaration
registry. It also contradicts itself about the refusal subtotal. Treating the
dated catalog as an exact name oracle would now delete accreted classes or
restore a superseded class name.

## Evidence

The W1 registry query on 2026-08-06 found 231 declarations carrying
`:seon.error/class true`. The catalog records 225, while the hand-maintained
test oracle it originally supplied contained only 218 identities. The current
registry includes later accretion such as the `my.web/*` classes,
`:seon.config/missing-effective-error`, and
`:seon.search/unavailable-error`.

The catalog names `:seon.sci.eval/session-blob-unavailable-error`; the declared
class is now `:seon.sci.eval/agent defs-blob-unavailable-error`. The catalog also says
there are eleven refusal classes but enumerates ten, and the registry query
likewise returns ten.

The 2026-08-12 discriminator audit found further material drift. A parsed
source census now reports 442 executable or contract occurrences of
`:seon.error/kind` in 64 `src/` files: 289 writes, 137 direct reads, fifteen
schema/path uses, and one Datalog use. There are fourteen exact-dispatch
occurrences covering twelve real error classes plus one absence sentinel,
not the five classes recorded by the dated catalog. Several exact dispatches
also lack a same-named current marker, so neither the dated name list nor a
mechanical kind-to-marker rename is an admissible conversion oracle. Full
evidence and the reproducible census are in
[`error-kind-audit-2026-08-12.md`](docs/prds/sci-execution-runtime/research/error-kind-audit-2026-08-12.md).

Commit `9c55c8aef` removed the stale identity list from
`test/seon/error_class_schema_test.clj`. Its recurring gate now derives every
class and intentional producer directly from schema properties, so accretion
does not require another copied census. The catalog remains a dated research
artifact whose exact-count and refusal-count claims need an explicit
correction rather than silent reinterpretation. The earlier archived issue
`docs/seon/issues/archive/error-catalog-undercounted-class-vocabulary.md`
incorrectly says the 225 correction and query-derived gate fully resolved the
problem.

## Owner

The error-model catalog and its source-derived census method in
`docs/prds/sci-execution-runtime/research/error-catalog-2026-08-03.md`.

## Acceptance

The catalog records the temporal boundary for its census, corrects the
225-versus-218 arithmetic and the ten-versus-eleven refusal claim, and records
the `session` to `desk` rename plus subsequent accretion without turning the
dated inventory back into a runtime hand list. The inaccurate archived
resolution is corrected or superseded. The error-model W2–W5 work derives its
producer and dispatch inventory from the parsed source census and the current
`:seon.error/class true` registry, reconciles the twelve real exact-dispatch
classes plus the absence sentinel, and graduates only when source, schema, and
fresh-cluster datom queries find no remaining `:seon.error/kind`.

## Owner direction 2026-08-12 (timing + fix model)

The kind->shape migration is ruled worth doing; timing is the
orchestrator's call: SCHEDULED AFTER the generator endgame and the
integration gate land (it crosses the same owners currently in flight and
wants the fast suite). The fix model, stated for the wave and for the
datahike/data-modeling skills if their guidance needs sharpening: an error
IS the presence of its attributes, values, and refs — the declared
class-marked shape carries the classification; dispatch is shape matching;
:seon.error/kind is deleted producer-and-consumer together per the audit's
migration plan (research/error-kind-audit-2026-08-12.md).
