---
type: issue
status: resolved
severity: friction
tags: [issue, operator, class/n1, wave/operator-status-face]
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

## N1 disposition — 2026-08-12

Still open in `resources/seon/operator/state.clj` and
`script/seon/fresh_operator.clj`. Reconcile stale managed-root claims away,
retain genuine failures as structured values, and print one counted summary
line with an explicit drill identity instead of one warning per file.

## Resolution — 2026-08-13

Resolved by `a073f7b51` at the existing claim-reader and status-render seams.
`resources/seon/operator/state.clj` derives an invalid claim's cause as either
an absent root or a malformed record and retains the path in structured
evidence. `script/seon/fresh_operator.clj` renders all invalid claims as one
counted line split by those causes, names `bin/seon reset --force`, and no
longer prints one full-path warning per file. The focused regression constructs
one absent-root and one malformed-present-root claim, asserts the exact
aggregate, and proves the absent root path is not rendered.

This note is the same repeated-status-face defect as the already archived
`status-prints-unexplained-invalid-claim-lines.md`. Its cleanup remainder is
not silently closed: absent-root reclamation and key-level malformed-record
diagnosis remain owned by
`pre-rename-root-claims-are-unreadable-noise-on-every-status.md`.
