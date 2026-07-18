---
type: issue
status: resolved
severity: bug
tags: [issue, agent, web]
---

# Plan render returned lazy sequences

## Problem

The plan renderer embedded lazy `map` results at its nested-step, root-card,
and recently-completed list boundaries. The compiled execution child correctly
rejected that hiccup because lazy sequences are not ordinary protocol values.
Agents with plan facts therefore displayed a render error.

## Resolution

The one plan renderer now realizes those bounded collections as vectors. No
rendering or protocol alternative was added.

## Evidence

Focused message and plan proof passes 35 tests and 142 assertions. The nested
plan fixture includes a completed step and asserts that the complete hiccup is
an ordinary protocol value.
