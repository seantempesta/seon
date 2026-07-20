---
type: issue
status: superseded
severity: friction
tags: [issue, architecture, index]
---

# Reconcile issue frontmatter with the maintained lifecycle

## Problem

The derived issue index cannot be regenerated or validated because tracked
open and archived notes still use status and severity values rejected by the
maintained issue authority.

## Evidence

On 2026-07-16, both `bin/issues-index` and `bin/issues-index --check` refused
the authority before writing an index. Reported values included open status
`active`, severities `high`, `medium`, and `reliability`, and archived statuses
`closed`, `completed`, and `archived`. The accepted lifecycle and severity
vocabulary in [[README]] is `open`/`resolved`/`superseded` and
`blocker`/`friction`/`cleanup`.

## Owner

The issue notes named by `bin/issues-index` and the issue migration/validation
mechanism. Reconcile their historical metadata in one bounded authority repair;
do not weaken validation or hand-edit the generated index.

## Acceptance

- Every open and archived issue uses the maintained lifecycle and severity
  values and remains in the correct directory.
- `bin/issues-index` regenerates the projection.
- `bin/issues-index --check` succeeds immediately afterward.

## Closed 2026-07-20

Verified stale by the issues triage (docs/prds/source-cleanup/research/issues-triage-2026-07-20.md §STALE): the described code or behavior no longer exists at HEAD; rg evidence in the triage doc.
