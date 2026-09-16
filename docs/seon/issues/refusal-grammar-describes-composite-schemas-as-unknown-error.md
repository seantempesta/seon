---
type: issue
status: open
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
The earlier [predicate-only fix](archive/predicate-schema-violations-humanize-to-unknown-error.md)
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
