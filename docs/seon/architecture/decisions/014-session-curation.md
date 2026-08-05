---
type: decision
status: active
date: 2026-08-04
tags: [decision, architecture, runtime, curation]
---

# ADR-014: Session curation adopts proved revisions

## Decision

An editor revises a run in its own candidate context and scratch branch. Its
deliverable is a revision: ordered form sources as data. The system proves that
revision mechanically on a fresh fork at the original opening commit, without
a model call and without crossing an external sink.

Adoption requires zero error receipts, a completed terminal result, declared
content, and equivalence. One append-only transaction records the proved run
and connects it to the original through `:seon.cluster.run/supersedes`.
Active-run projections exclude superseded originals while retaining both runs
for forensics. One original has one future; there is no merge.

## Consequences

- The editor's exploratory session never becomes the adopted artifact.
- Proof is reproducible and effect-free.
- Adoption preserves history instead of rewriting it.
- Revision, proof, and supersession are queryable facts.

## Related

- [[agent-runtime]] — editor, revision, proof, and adoption.
- [[observability]] — active and forensic run projections.
