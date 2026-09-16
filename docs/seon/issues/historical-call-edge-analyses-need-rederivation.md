---
type: issue
status: open
severity: friction
created: 2026-09-16
tags: [issue, database, class/n7, wave/program-graph-indexing]
---

# Historical call-edge analyses need rederivation

## Problem

Fixing the declaration and evaluation constructors does not backfill older
call edges or regrade conclusions computed while those edges were absent.

## Evidence

The [dated original record](archive/agent-form-calls-to-core-namespaces-are-not-indexed.md)
contains the August ablation's missing `seon.db/q` edges and the September
Juniper test's missing agent-local function edge. Reach-digest equality over
those incomplete historical closures was observed; it cannot establish that
their tested functions were unchanged. Fresh declaration admission is repaired
by `76774d044`, with a seven-assertion real-SCI regression and a changed digest
on default's scratch agent. Neither proof reanalyzes historical entities.

## Owner

The program-graph analysis owner and the owner of the archived ablation.
Reuse `seon.fn/analyze-forms` with each historical declaration's actual
namespace context. Do not infer subjects or manufacture edges from test names.

## Acceptance

- Reanalyze surviving historical declarations from their stored source and
  namespace facts; explicitly report unavailable historical context.
- Regrade the affected ablation evidence, or supersede its conclusions with
  a named reproducible replacement when the original branch is unavailable.
- Verify reach-based test reuse only after its declaration edges are complete.

No historical data was rewritten in the fresh-admission lane. The orchestrator
gate request and adoption boundary are in
[the landing](../../prds/steward-platform/research/agent-call-edges-2026-09-16.md).
