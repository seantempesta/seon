---
type: issue
status: superseded
severity: friction
tags: [issue, render, schema, class/p1]
---

# The database identity face cannot satisfy its own declared input

## Problem

`seon.render.value/render-database-identity-ai` declares
`{:malli/schema [:=> [:cat :seon.render/unit] :string]}`
(`src/seon/render/value.clj:61-67`), and `:seon.render/unit` is
`[:map-of :qualified-keyword …]`
(`resources/seon/schemas/seon.render.edn`). The unit the render seam actually
hands it is built by `seon.render/producer-argument`, which merges the VALUE's
own keys into the render custody map — and the value here is the identity
projection of a Datahike database, carrying Datahike's own **unqualified**
`:db-name` and `:t` (the producer reads both, so this is not incidental).

Every key of the argument is therefore not a qualified keyword, and on any JVM
whose contracts are armed the producer is refused before its body runs with
`:seon.instrument/contract-violated`.

## Why it was invisible

`seon.render/project-node*` used to answer a nested producer's refusal by
falling back to the unprojected print node, so the page and the prompt silently
showed the raw node instead of the identity face and nothing named the
producer — the recurring "absence reads as health" class (AGENTS.md §2.4, and
the disease the whole `:seon.render/unknown` wave exists to end). Since the
typed unknown landed (2026-09-08, commit `0e63e606c`) the same refusal is a
stable value naming the producer and its refusal kind, which is how this was
found.

## Evidence

`seon.render-simplification-test/nested-values-render-their-declared-faces` is
red at `31fb0b0b4` and at HEAD — the verdict is unchanged by the typed unknown,
only its text. It now reads:

```
#:seon.render.unknown{:producer seon.render.value/render-database-identity-ai,
                      :reason :refused,
                      :refusal :seon.instrument/contract-violated, …}
```

## Fix

Declare the input this producer actually accepts, rather than one no caller can
supply. The three keys it reads are the dependency's names for a database
identity, so the honest declaration is a `:map` (open, so the merged render
custody keys are ignored) naming them — not a `:map-of :qualified-keyword`,
which is the wrong question for an argument that carries a foreign value's own
keys. Check the sibling faces built the same way while there.

One regression proving the class: an armed-contract render of a nested database
value returns the identity face, not a typed unknown.

## Fix applied, proof pending

2026-09-08: the producer declares its input as `:seon.db/database-value-identity` (`src/seon/render/value.clj`). The proof `nested-values-render-their-declared-faces` cannot yet go green because `seon.render.value-test` is 17/17 red on stale pre-ruling expectations (a lane is rewriting it); resolve when that lands.

## Superseded — 2026-09-15 verification

`551d8353c` fixed the declared input to name the database identity shape.
The direct armed producer on default returned
`database :cluster-default at basis transaction 536871456 commit 6aa99da3-a457-5bce-8013-22d448397f75`.
Its original impossible input contract is no longer the failure.

The nested-values regression was rerun in the isolated armed P1 gate. It
instead reached a Datom-to-Map.Entry ClassCastException and raw transaction
report rendering; fresh-worker confirmation reproduced those failures.
That distinct observable is now tracked in
[nested-database-rendering-treats-datoms-as-map-entries.md](../nested-database-rendering-treats-datoms-as-map-entries.md).
The gate predates bisect's `ee8d54dca` renderer/admission changes and does not
claim a verdict on those newly landed bytes. The direct-input defect is
closed; nested rendering is not falsely called green.

Evidence and exact boundary:
[P1 landing](../../../prds/context-generation/research/p1-ambient-state-2026-09-15.md).

## Final P1 carriage handoff — 2026-09-15

Implementation: `b80f78a7c`. The [P1 landing note](../../../prds/context-generation/research/p1-ambient-state-2026-09-15.md) records the live probes, measured allocations, exact remaining boundaries and pending orchestrator gate. Database metadata now participates in instrumentation and admission; no running read/admission fallback reconstructs the projection. This closes only the member's read/admission carriage defect, not adoption/lifecycle or the remaining explicitly supplied thread compatibility input.
