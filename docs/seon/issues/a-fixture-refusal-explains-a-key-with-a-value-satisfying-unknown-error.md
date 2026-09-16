---
type: issue
status: open
severity: friction
tags: [error, diagnostic, face, schema, fixture]
---

# A fixture refusal explains a key "with a value satisfying unknown error"

## Observed

Batch 95 (cold, root `tmp/test-runs/run.xvaxWP`), 2026-09-16 10:40 CST,
seon.background-blob-test/background-binary-results-remain-exact-across-the-inline-threshold:

```
seon.db/transact! refused transaction data at [1 :seon.fn/ns]: expected the
required key :seon.fn/ns with a value satisfying unknown error, got a map
missing :seon.fn/ns. Fix: Supply :seon.fn/ns …
```

The refusal names the key correctly but describes its expected value as
"unknown error": the explanation seam has no rendering for the shape
`:seon.fn/ns` resolves to (a registered reference schema, presumably a ref
or namespaced symbol shape), so the human-readable face falls back to a
string that reads as a bug. UGLY OUTPUT IS A DEFECT (CLAUDE.md §2.4): the
refusal must name the expected shape (the registry key and its base type)
or say nothing about the value.

## Where

`seon.error/diagnostic` / the transact! refusal explanation
(`src/seon/db.clj`, `src/seon/schema.clj`'s explain path) — find the branch
that produces "satisfying <explanation>" and the schema form it could not
name. One regression: a refusal for a missing key whose schema is a
registry reference names that reference.
