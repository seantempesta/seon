---
type: issue
status: open
severity: cleanup
tags: [issue, render, database, wave/verification-audit]
---

# Changed-read summaries now have two EDN readers and two value-diff mechanisms

## Problem

The late commit `1f18b99fc` adds the owning `repl/shown-value` reader and `db/diff`/`apply-diff` values arity. The earlier debug implementation keeps `readable-shown` (complete EDN plus a second EOF read) and `changed-paths` (recursive map/vector comparison). `reread-summary` compares consecutive saved shown values as full values. New system shown values are delta envelopes, so it now compares deltas themselves rather than the reconstructed values. The existing recursion also has different collection semantics from the editscript-backed owner.

## Evidence

Audit-1 supplement at `1f18b99fc47f19c8a65a642b472b044b05dc1ea7`:

- `src/seon/render/transcript.clj:1302–1335`
- `src/seon/repl.clj:51–62 at 1f18b99fc`
- `src/seon/db.clj:2214–2264 at 1f18b99fc`
- `src/seon/turn.clj:1915–1931 at 1f18b99fc`

## Owner and deletion

Use one complete shown-value interpretation with explicit unavailable/terminal-text information, and consume the existing diff owner or already-declared changed paths. Delete the debug-specific recursive diff. Preserve the human summary as presentation of those changes.

Estimated change: 25–45 lines deleted/merged. Classes: 1, 4, 5.

## Acceptance

Opening → changed reread → changed reread yields the same changed-path facts in prompt and popover. A repeated identical delta applied to a changed base is not mistaken for an unchanged full value. Include maps, sequence length changes, sets, and terminal prose.

See [the audit](../../prds/context-generation/research/audit-1-2026-09-15.md).
