---
type: issue
status: resolved
severity: friction
tags: [issue, render, sci, wave/print-path]
---

# Structural record rendering erases the declared name

## Class

A structural walker that chooses the map face before distinguishing records
loses the declared type even though all fields remain present.

## Evidence

The September 15 first repair snapshot returned `{:a 1, :b 2}` for a real
SCI `ParityRecord`, where the standing parity B9 regression expects
`#user.ParityRecord{:a 1, :b 2}`. The admission owner already knows SCI's
declared record name; the structural renderer did not use it.

## Owner and repair

`seon.render.value/value-node*` selects the record face before the map face.
`seon.sci.admit/record-name` is the shared name derivation for both walks.
The existing real-SCI parity B9 regression now covers root and nested records.
The isolated gate result belongs in the
[landing note](../../../prds/context-generation/research/bisect-today-reds-2026-09-15.md).

## Verification, September 15

The four-namespace HEAD-plus-owned-paths gate passed **103 tests / 248
assertions, 0 failures / 0 errors**, exit 0, at 20:10:49Z. The snapshot
basis was `6dc70f30a`; the exact command and content digest are in the landing
note. This includes the armed missing-marker check, executable artifact
requery, nested-map elision coordinates, and root/nested SCI record rendering.
