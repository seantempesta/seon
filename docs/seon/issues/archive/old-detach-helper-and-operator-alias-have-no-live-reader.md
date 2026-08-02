---
type: issue
status: resolved
severity: cleanup
tags: [issue, deletion, operator, tooling]
---

# Delete root operator files with no live reader

The deleted supervisor's process-tree helper
`script/seon/dev/detach.py` and the compatibility operator spelling
`bin/seon-fresh` survived without an operational caller.

## Resolution

- `34cd8a5e8` deleted both files.
- `0925286cd` replaced the evaluation source-admission fixture's arbitrary
  `detach.py` path with a neutral fixture path.
- `274694a2b` removed the deleted alias from the active Flow skill guidance.

The remaining skill sentence calls `bin/seon-fresh` old while directing the
reader to canonical `bin/seon`; it is negative provenance rather than an
instruction or executable reader. A post-fix search found no active invocation
of either deleted path outside dated research, quarry, archived issues, and
that explicit negative provenance.

## Acceptance

Fresh launch continues to use its own minimal embedded detach program;
repository guidance and automation expose one operator spelling, `bin/seon`.
