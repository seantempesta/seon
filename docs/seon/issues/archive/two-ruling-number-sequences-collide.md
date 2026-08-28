---
type: issue
status: resolved
severity: cleanup
tags: [issue, docs, plan]
---

# Two ruling-number sequences collide

## Problem

Two live ruling sequences share numbers with different meanings. The
sci-execution-runtime plan README numbers owner rulings past 49 (its
"ruling 46" is instruction self-fade; its "ruling 49" is my.plan). The
context-generation
[design ideas ledger](../../prds/context-generation/plan/design-ideas-ledger-2026-08-13.md)
runs its own sequence 17–46, whose "ruling 46" is the affordance
opening. The same ledger cites both sequences by bare number ("self-fade
via ruling 46" at line 35 beside its own ruling 46 entry), so a bare
citation of "ruling 44–46" is ambiguous everywhere, including AGENTS.md.

## Owner

The orchestrator/owner: pick one disambiguation convention (e.g. cite
the runtime sequence as "R<n> (runtime)" and the context-generation
sequence bare, or renumber the younger sequence with an offset), then
sweep existing bare citations in the two plan trees and AGENTS.md.

## Acceptance

A stated convention in both plan READMEs; no remaining bare cross-
sequence citation that a reader could bind to the wrong ledger.

## Resolution (2026-08-28, owner convention)

Bare "ruling N" means the context-generation ledger; the
sci-execution-runtime sequence is cited as "R<N> (runtime)"
everywhere outside its own directory. The convention is stated at the
top of both authorities; living documents (AGENTS.md, the ledger, the
active PRDs) were swept; dated research records are exempt as
point-in-time history.
