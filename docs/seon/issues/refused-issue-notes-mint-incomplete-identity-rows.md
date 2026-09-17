---
type: issue
status: open
severity: blocker
created: 2026-09-17
tags: [issue, schema, indexing, write-admission]
---

# Refused issue notes mint incomplete identity rows

The reset worktree's complete-publication regression exposed this at
`tmp/reset-edge-fast-11.log`: `seon.issue/index-tx` rejected a parsed note but
still primed its identity. Whole-entity admission then refused that identity-only
row for missing issue fields, aborting the publication rather than returning the
note's diagnostic. The observed input was
`the-default-clusters-effective-configuration-lost-every-required-fact.md`, whose
frontmatter declared `type: defect` rather than `type: issue`.

The pending reset implementation classifies each parsed note once. Only admitted
notes prime new identities or participate in class membership; every rejected
note retains its diagnostic. An already indexed invalid note is not silently
retracted. The canonical `seon.reset-edges-test/refused-notes-do-not-mint-incomplete-issue-identities`
regression passed in fast iterations 14, 16, 17 and 18. The source change is still
uncommitted; final landing and the orchestrator's cold proof remain outstanding.
