---
type: issue
status: resolved
severity: blocker
tags: [issue, render, test, wave/render-producers]
---

# Restore registered render producers before the generic map floor

## Problem

Registered `my.message` maps and collections render through the generic print
floor instead of their declared compact AI and HTML producers.

## Evidence

At HEAD on 2026-08-29, explicit `bin/test seon.render.value-test` reproduced
9 failures and 1 error in
`one-registered-map-renders-a-compact-attribute-listing` and
`registered-map-collections-render-one-concise-line-per-row`. AI output was a
namespaced map literal, HTML used `seon-print-map`, and the expected registered
producer markers were absent. The failures do not exercise the profile fixture
changed by the effective-config census sweep.

## Owner

The registered producer selection in `seon.render`.

## Acceptance

Both named tests select their declared AI and HTML producers and the complete
`seon.render.value-test` namespace is green.

## Resolution (2026-09-15 triage)

surface: context-generation

The obsolete two test cases were removed/restructured in `9eca070ed`; deletion alone is not the closure evidence. At triage HEAD `131fa2a56`, `src/seon/render.clj:489–515` selects declared schema pairs before the floor, and `src/seon/render/value.clj:274–284` applies the selected pair before structural traversal. The current `declared-pairs-render-inside-response-values` regression at `test/seon/render/value_test.clj:47–105` covers actual current transaction, directory and plan values through their declared pairs. Verified the selection and callers with `git show HEAD:<path>` and the old test removal with `git log -S one-registered-map-renders-a-compact-attribute-listing -- test/seon/render/value_test.clj`. This closes the old registered-message-floor incident; it does not assert every authored contract is coherent.
