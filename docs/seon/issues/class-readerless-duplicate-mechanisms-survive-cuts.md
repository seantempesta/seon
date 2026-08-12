---
type: issue
status: open
severity: cleanup
tags: [issue, architecture, deletion, class-kill]
---

# Reject readerless rows and duplicate mechanisms at publication

## Problem

Deleted and unified paths leave behind rows with no reader, unused caches,
parallel codecs, hand-rolled substitutes, and duplicate registrations. Nothing
at publication proves that a public declaration or mechanism is reachable
through its surviving owner.

## Evidence

Nine open issues span 2026-07-27 through 2026-08-05:
[[context-capture-prompts-bypass-the-blob-splitter]],
[[datahike-allocates-a-konserve-cache-it-never-reads]],
[[error-class-catalog-and-renderers-disagree]],
[[flow-config-dials-have-two-registration-owners]],
[[flow-has-no-read-set-control-and-a-hand-rolled-egress]],
[[monitor-graph-command-proc-throws]],
[[schema-datahike-keeps-a-readerless-second-codec]],
[[schema-population-retains-five-readerless-rows]], and
[[value-floor-residue-duplicate-cursors-and-marker-hand-lists]].

The recent archive adds another duplicate-decision owner on 2026-08-08:
[[archive/schema-key-immutability-swallows-the-usage-guard]].

## Owner

Program/schema publication and the one owners of the duplicated mechanisms.

## Acceptance

- Publication derives reader closure from the program graph and refuses a
  public row or callable mechanism with no reader.
- A semantic operation has one declared owner; alternate codecs, caches,
  rosters, and bypass writes have no construction path.
- The deletion regression asserts absence of the superseded facts/functions,
  not behavior of the deleted path.
