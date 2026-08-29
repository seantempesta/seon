---
type: issue
status: open
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
