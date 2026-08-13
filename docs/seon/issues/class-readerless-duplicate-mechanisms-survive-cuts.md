---
type: issue
status: open
severity: cleanup
tags: [issue, schema, database, flow, class/n11, class-kill, wave/class-kill-queue]
---

# Reject readerless rows and duplicate mechanisms at publication

## Problem

Deleted and unified paths leave behind rows with no reader, unused caches,
parallel codecs, hand-rolled substitutes, and duplicate registrations. Nothing
at publication proves that a public declaration or mechanism is reachable
through its surviving owner.

## Evidence

Current open members carry `class/n11` and are derived with
`bin/issues-index --class class/n11`.

## Owner

Program/schema publication and the one owners of the duplicated mechanisms.

## Acceptance

- Publication derives reader closure from the program graph and refuses a
  public row or callable mechanism with no reader.
- A semantic operation has one declared owner; alternate codecs, caches,
  rosters, and bypass writes have no construction path.
- The deletion regression asserts absence of the superseded facts/functions,
  not behavior of the deleted path.
