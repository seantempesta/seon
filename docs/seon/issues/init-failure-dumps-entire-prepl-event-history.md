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
