---
type: issue
status: resolved
severity: friction
tags: [issue, schema, error]
---

# Refusal grammar describes composite schemas as unknown error

## Problem

The shared refusal grammar treats Malli's fallback text as an explanation
of a composite contract. It emits a grammatically complete but uninformative
instruction to supply “a value satisfying unknown error”.

## Evidence

Default pid 7595, 2026-09-16, seon.db/write-error against the carried projection:

```text
seon.db/transact! refused transaction data at [0 :seon.turn/agent]: expected the required key :seon.turn/agent with a value satisfying unknown error, got a map missing :seon.turn/agent. Fix: Supply :seon.turn/agent with a value satisfying unknown error.
```

After the write selection fix (`20d30a0bd`), an incomplete evaluation map
similarly reports:

```text
seon.db/transact! refused transaction data at [0 :seon.cluster.eval/run]: expected the required key :seon.cluster.eval/run with a value satisfying unknown error, got a map missing :seon.cluster.eval/run. Fix: Supply :seon.cluster.eval/run with a value satisfying unknown error.
```

The batch-20 triage also recorded the exact fragment “a value satisfying
invalid type”. That second fragment was not independently reproduced here.
The earlier [predicate-only fix](predicate-schema-violations-humanize-to-unknown-error.md)
does not cover this composite-schema path.

## Owner

seon.error/explain-problem, src/seon/error.clj:727. It uses me/error-message
and falls through to a prose concatenation for :and/:or and unknown schema
types. seon.db/invalid-write consumes that shared result; another formatter
there would duplicate the mechanism. error.clj was outside this lane's files.

## Acceptance

The shared constructor explains reference/composite schemas through their
declared forms, or reports an explicit typed unavailable expectation.
Neither “unknown error” nor “invalid type” is presented as what to supply.
A regression exercises this exact missing :seon.turn/agent case.

## Recurrence 2026-09-16 (batch 95, cold, root `tmp/test-runs/run.xvaxWP`)

`seon.db/transact! refused transaction data at [1 :seon.fn/ns]: expected the
required key :seon.fn/ns with a value satisfying unknown error, got a map
missing :seon.fn/ns.` — the same face on a registry-referenced key, seen by
seon.background-blob-test/background-binary-results-remain-exact-across-the-inline-threshold.

## Resolution — batch 107 followup

`seon.error/schema-expectation` (`src/seon/error.clj:732`) derives expectations
from the resolved schema's children and asks Malli for messages with
`:unknown false`. Failure categories are excluded from the expected-shape
description; they remain failure evidence. `explain-problem` (`:754`) uses that
one description for the diagnostic and fix. No string replacement or second
database formatter is involved.

`seon.db-test/missing-reference-diagnostics-describe-the-declared-value` checks
missing cluster/config, turn/agent and evaluation/run refs through the actual
writer, including unchanged basis.
`seon.refusal-grammar-test/reference-expectations-describe-the-schema-not-the-failure-category`
checks both missing-key and invalid-type categories. The final grammar fast run
passes **2 tests / 21 assertions**; the database/grammar rerun passes **49 tests /
372 assertions**, with no failures or errors. The expected ref value is now
“an integer or a string or a tuple with 2 entries”.
