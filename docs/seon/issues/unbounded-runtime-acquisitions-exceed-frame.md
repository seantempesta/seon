---
type: issue
status: open
tags: [agent, database, issue]
severity: friction
---

# Unbounded runtime acquisitions exceed the negotiated frame

## Evidence

The 2026-07-22 64 KiB live checkpoint exposed three consumers that still
assume a multi-megabyte database response:

- execution-child program/config preparation returned `v must satisfy
  IVector` before any eval because it called `subvec` on the absent results of
  a `frame-too-large` response;
- the namespaces block discarded the same top-level error and rendered
  `Namespace selected member failed` with nil data; and
- the warnings block discarded the error and rendered
  `Warning acquisition failed. nil`.

The exact frozen responses are hundreds of kilobytes: about 422,059
characters for program/config, 683,063 characters for selected namespace
rows, and 550,399 Transit bytes for the first warnings acquisition. All three
succeed after restoring the 4 MiB writer, which masks rather than fixes the
defect.

Detailed evidence lives in [[live-turn-frame-defect-2026-07-22]],
[[live-namespaces-render-defect-2026-07-22]], and
[[live-warnings-render-defect-2026-07-22]].

## Expected owner

The q22 convergence boundary in the SCI execution-runtime program: reuse one
bounded `index-page` plus `pull-many` acquisition recipe across the existing
execution, namespaces-context, and warnings-context owners. Preserve one
frozen database value and each consumer's current final data shape.

Every consumer must also preserve the complete top-level database error before
reading member results.

## Acceptance

- Multi-page acquisitions equal the current successful 4 MiB result shapes.
- Every page uses the same immutable database value and stays below the
  supported 64 KiB floor.
- Top-level frame failures remain legible and never become nil or a secondary
  collection exception.
- A fresh 64 KiB `/agents/run` drive has evals greater than zero, no context
  render failures, and no turn error.

## Triage — 2026-07-23

DISSOLVES into P4 loop migration plus the cutover/U12 acceptance: the named
execution-child acquisition is deleted, and resumable database steps and
surviving context consumers must pass the bounded restart drive.
