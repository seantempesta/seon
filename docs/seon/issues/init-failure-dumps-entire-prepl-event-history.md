---
type: issue
status: open
severity: friction
tags: [issue, operator, render, class/n1, wave/operator-status-face]
---

# A failed `bin/seon init` dumps the entire prepl event history instead of the cause

## Problem

When init's publication fails, the terminal face prints the complete prepl
event vector — now including every progress event since `b465b4613` — plus a
stack trace, burying the one line that names the cause. Reported by the
gate-fix-operator lane (2026-08-06, standing ugly-output order) while
reproducing the `canonical-definition` publication failure.

## Expected

The failure face leads with the flat error's kind/message (the actual cause),
then at most a bounded tail of recent events; the complete event history
belongs behind a verbose flag or a retained file, not on the default face.
Owner: `script/seon/fresh_operator.clj` init failure rendering
(`prepl-eval!` fail! sites and the init command's error printer).

## Acceptance

A deliberately failing publication prints a face whose first lines name the
cause; full event history remains reachable explicitly.

## N1 disposition — 2026-08-12

Still open in the operator publication leaf. Retain the complete prepl event
history by report identity and make `bin/seon init` print a declared bounded
failure face whose first fields are phase, cause, and source path.

## Edit-hook correction — 2026-09-06

The edit hook previously clipped the start of the entire event vector, removing
the cause and retaining only progress chatter. `publication-failure-message` in
`bin/seon-hook` now extracts the terminal exception's cause and data using EDN,
and names `logs/current-source-failure.log` before that summary. The log retains
the latest failed publication only, bounded by the existing hook log cap, with
an explicit omitted-character count if needed. It does not accumulate a file
per failure.

Root replayed the actual failed publication captured in
`tmp/identity-publication.log`. The resulting short advisory names
`:malli.core/invalid-schema` and
`:seon.cluster.loop/evaluate-sources-request`; both were previously hidden by
the clipped progress events. The operator's direct terminal failure face is
still outstanding, so this issue remains open.
