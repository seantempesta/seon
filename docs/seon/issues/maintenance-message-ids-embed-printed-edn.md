---
type: issue
status: open
severity: friction
tags: [issue, message, maintenance, render]
---

# Maintenance message ids embed printed EDN

## Problem

Maintenance-fault messages arrive with identities like
`maintenance-error/maintenance-receipt/["root/maintenance/compact" #inst "2026-08-09T03:00:00.000-00:00"]-your-run`
— a message id constructed by concatenating a printed EDN tuple into a
string. The floor-render work made the class visible: every inbox line
carrying one of these spends most of its width on a quoted,
backslash-escaped vector. An identity built by printing a composite
value into a string is a codec smell (the data was structured; the id
threw that away) and an agent-facing readability tax on every listing
that shows it.

## Evidence

2026-08-13 live `(my.message/inbox)` floor render (floor-render lane,
`0f1374d5c` summary): three of four inbox rows are maintenance errors
whose ids embed `["root/maintenance/…" #inst "…"]` printed forms.

## Owner

The maintenance fault→message seam (the constructor that derives a
message identity from a maintenance receipt). The id should be a plain
opaque identity (the receipt already IS the structured fact carrying
task and instant — the message can ref it), not a serialization.

## Acceptance

New maintenance messages carry short opaque ids; the structured
task/instant lives on the referenced receipt fact; one regression on
the constructor. Existing stored ids are disposable with the data.
