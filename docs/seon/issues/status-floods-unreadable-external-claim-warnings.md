---
type: issue
status: open
severity: friction
tags: [issue, operator, process]
---

# `bin/seon status` floods eight unreadable-external-claim warnings

## Problem

Every `bin/seon status` run prints eight full-path lines of the form:

```text
record unreadable /Users/sean/src/seon/data/operator/claims/roots/1ff66f77-….edn: The external claim is invalid.
```

Observed 2026-08-06 by the orchestrator and independently reported by the
gate-fix-render lane (standing ugly-output order). The lines dominate the
status face while carrying near-zero information density — the reader cannot
tell whether these are dangerous, stale, or ignorable, and the same eight
repeat on every invocation.

## Expected

Either the claims are stale leftovers that reconciliation should discard (the
claims authority owns cleanup — resolve why they persist), or they are honest
degraded state that should render as ONE concise summary line (count + one
hint at the resolution verb), with full paths available behind an explicit
verbose ask. Eight repeated full-path warnings on the happy path is a defect
in the status face either way.

## Owner

`resources/seon/operator/state.clj` claims reading/reconciliation +
`script/seon/fresh_operator.clj` status rendering. Fits the
gate-fix-operator lane's owned paths (or a follow-up operator hygiene lane).

## Acceptance

A clean root prints no claim warnings; a root with genuinely invalid external
claims prints one summarizing line; the underlying stale claims here are
explained (and reconciled away if stale).
