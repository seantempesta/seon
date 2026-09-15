---
type: issue
status: open
severity: friction
tags: [issue, render, schema]
---

# Fault resolution has no declared fact

## Problem

The 2026-09-14 HTML assignment asks for derived open/resolved fault status.
`resources/seon/schemas/seon.error.edn` declares occurrence, attribution,
and stewardship, but no resolution transaction or resolution relationship.
`src/seon/error.clj` records and routes faults; it has no resolution transition.
Absence of a steward is not proof of repair.

## Evidence

HTML-views read the schema and fault rendering owner on 2026-09-14.
Source searches for resolution/closed/status/fixed attributes found none.
The pair shows recorded kind, message, time, function, and turn; it does not
invent resolved status. No production schema change was made in this UI lane.

## Acceptance

Declare the resolution fact at its writer, then derive and test both states
from the same database value used by the renderer.
