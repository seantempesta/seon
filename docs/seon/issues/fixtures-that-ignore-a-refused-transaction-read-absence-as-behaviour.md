---
type: issue
status: open
severity: friction
created: 2026-09-16
tags: [testing, fixture, class, absence-as-health]
---

# Fixtures that ignore a refused transaction read absence as behaviour

## Problem

`seon.db/transact!` returns a flat `:seon.error` value when write admission
refuses a row the current schema no longer admits (stricter admission since
`20d30a0bd`). Fixtures that call it without reading the answer seed nothing,
and their tests then fail several assertions away from the cause, or read
the empty result as a semantic change. Three independent triages hit the
same class on 2026-09-16: `seon.render.transcript-test` (seven survivors,
second pass), `seon.render.web-debug-test` (the panel fixture), and
`seon.config-test`/`seon.reconcile-test` (batch 36, refuted as a
regression by the config-apply-cost lane, `5e5aa6293`). Each triage added
its own local `transacted!` helper.

This is the project's named recurring class: a check that reads ABSENCE OF
SIGNAL as health.

## Fix shape (one choke point, one regression)

1. `seon.test-support` owns one fixture write helper that asserts the
   transaction report (`:db-after`) and fails the test with the refusal's
   diagnostic; the local helpers in the three namespaces are replaced by it.
2. A detector over the program graph: a test namespace whose functions call
   `seon.db/transact!` without consuming the result is a generated issue
   (the issue generator's detector contract), so the class cannot silently
   return.
3. One regression: a fixture write refused by admission fails the test at
   the write with the refusal named, never downstream.

Related: `write-admission-validated-partial-maps-against-every-schema`
(the admission change), the transcript second-pass landing note.
