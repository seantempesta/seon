---
type: issue
status: open
severity: blocker
tags: [issue, runtime, observability, database]
---

# Store the model's raw reply as a durable fact even when it parses to no forms

Found by the independent ablation observer (2026-08-11 night,
[observer account](../../prds/sci-execution-runtime/research/minimum-context-ablation-observer-2026-08-11.md)):
the FLOOR variant's agent answered one turn in pure prose, then stopped. That
reply is recoverable NOWHERE — no `reply`-bearing attribute exists in any
cluster branch. The turn happened, was billed, and left no forensic trace
beyond its attempt usage row.

## Why this is a blocker

The transport law says anything recovery or forensics could need is a
database fact. A model reply that parses into forms leaves its forms; a reply
that parses into NO forms (pure prose, refusals, malformed output) is exactly
the case where the raw bytes are the ONLY diagnostic — and it is exactly the
case we currently drop. Every "why did the agent do nothing?" investigation
hits this hole. The observer could not determine what FLOOR's agent actually
said.

## Expected shape

The settled reply's raw text (or its blob digest above the threshold) is
committed with the run/attempt facts at settlement, for every attempt,
independent of parse outcome — one mechanism, not a prose-only special case.
Bulky payloads follow the existing blob rule (row carries identity, digest,
size).

## Acceptance

A drive turn that returns pure prose leaves a queryable fact carrying the
complete reply (or its blob digest); the observer's FLOOR query pattern
returns it; one regression proves a no-forms reply is stored.
