---
type: issue
status: resolved
severity: friction
tags: [issue, schema, sci, performance, class/p1, wave/schema-projection-performance]
---

# Stop rebuilding gigabytes of schema state for one declaration

## Problem

A single valid agent schema declaration rebuilds the complete schema
projection inside the guarded evaluation. At the current registry size it
allocates about 4.65 GB and takes about 0.9 seconds even though the returned
value is one keyword.

Schema declaration is the first step of ordinary data work. Paying this cost
for every new attribute makes the taught schema-first workflow expensive and
amplifies one agent's declaration across the co-hosted process heap.

## Evidence

Scratch cluster `codex-repl-dogfood-0804`, MCP `eval_clj`, `sci` mode:

```clojure
(seon.schema/register! :my.dogfood/score [:int {:min 0 :max 100}])
```

returned the clean face `:my.dogfood/score`, but its evaluation record was:

```clojure
{:seon.eval/duration-ms 919
 :seon.eval/allocated-bytes 4652159248
 :seon.eval/outcome :ok}
```

An independent second declaration reproduced the same class:

```clojure
(seon.schema/register! :my.dogfood/label [:string {:min 1}])
;; duration-ms 907, allocated-bytes 4652146872
```

`src/seon/sci/eval.clj:1363-1411` builds an isolated registration delta and
validates it with `schema/projection-with-schema`.
`src/seon/schema.clj:1774-1786` implements that operation by calling the whole
`build-projection`. The contracted-function sibling is already tracked in
[[contracted-defn-rebuilds-the-whole-schema-projection]]; this note owns the
separately reproduced schema-declaration path.

## Owner

`seon.schema` owns incremental projection construction; `seon.sci.eval` owns
using it once inside the declaration boundary.

## Acceptance

- Adding one schema validates only the new declaration and the dependency
  closure it changes; unrelated registry forms are not rebuilt.
- Doubling unrelated registry forms does not double one declaration's work.
- The two exact SCI evaluation probes retain the same admitted keyword result and schema
  refusal semantics without gigabyte-scale allocation.

## Resolution — 2026-09-15 verification

`fba6bc4c1` already replaced complete projection reconstruction with
`projection-with-schema` dependency-closure validation. The existing
`seon.schema-test/one-declaration-validates-only-its-dependency-closure`
passed the September 15 isolated armed gate: zero whole-population
compilations with 1,024 unrelated declarations. No second repair was added.

The two exact original SCI forms succeeded in a private acquired context on
default through MCP JVM mode: score 108,685,472 thread-allocated bytes / 45 ms;
label 107,849,320 bytes / 44 ms. Their historical values were 4,652,159,248 /
919 ms and 4,652,146,872 / 907 ms. Candidate rows were not transacted; a
follow-up query found neither schema key in default. Pure candidate calls
allocated 819,984 and 806,288 bytes.

This closes the full-population rebuild root cause. Complete guarded
execution still exceeds its 64 MiB regression bound; that distinct measured
residual is [guarded-schema-declarations-still-exceed-the-allocation-regression-bound.md](../guarded-schema-declarations-still-exceed-the-allocation-regression-bound.md).
Exact forms and gate boundaries are in the
[P1 landing](../../../prds/context-generation/research/p1-ambient-state-2026-09-15.md).

## Final P1 carriage handoff — 2026-09-15

Implementation: `b80f78a7c`. The [P1 landing note](../../../prds/context-generation/research/p1-ambient-state-2026-09-15.md) records the live probes, measured allocations, exact remaining boundaries and pending orchestrator gate. Database metadata now participates in instrumentation and admission; no running read/admission fallback reconstructs the projection. This closes only the member's read/admission carriage defect, not adoption/lifecycle or the remaining explicitly supplied thread compatibility input.
