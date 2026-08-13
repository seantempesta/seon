---
type: issue
status: open
severity: friction
tags: [issue, operator, cluster, class/p2]
---

# Operator-root inference guesses from directory names and is silently wrong

## Problem

The upward operator-root inference matches the literal directory names
`clusters`/`data` (`src/seon/cluster.clj:595-601`;
`script/seon/fresh_operator.clj:556-561` walks two parents). That is a
naming-convention inference in production code — one of the three banned
substitutes (AGENTS.md §2.2) — and it is already inconsistent:
`src/seon/bootstrap_drive.clj:399` passes
`tmp/bootstrap-drives/<id>/clusters`, whose parent is not `data`, so the
inference silently returns the cluster root as the operator root — a
silent fallback that happens to be right until it isn't (§2.4).

## Owner

The boot value should carry the operator root explicitly (values carry
their world) instead of any consumer re-deriving it from path spelling.
Discovered during the R3 store-path archaeology
([options doc](../../prds/sci-execution-runtime/research/store-path-rename-options-2026-08-13.md));
option 3 there deletes both upward inferences, but the explicit-carry fix
does not need to wait for R3.

## Acceptance

No production code derives the operator root by matching directory names;
the bootstrap-drive path shape gets one regression proving the derived
root is the operator root, not the cluster root.
