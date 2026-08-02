---
type: issue
status: open
severity: cleanup
tags: [issue, deletion, operator, tooling]
---

# Delete root operator files with no live reader

## Problem

Two root operator artifacts survive without an operational caller:
`script/seon/dev/detach.py` is the deleted supervisor's full process-tree
helper, and `bin/seon-fresh` is a compatibility spelling for the one current
operator. Both make the repository appear to have a second maintained
operator/process surface.

## Evidence

- Repository-wide reference search finds no runtime invocation of
  `script/seon/dev/detach.py`. Fresh launch embeds and executes a separate,
  minimal `detach-python` program at
  `script/seon/fresh_operator.clj:26-38,1424-1452`; it never opens the file.
- The only fresh-tree mention that uses the detach file as data is
  `src-inspect-ai/tests/test_source_admission.py:138-162`, where an arbitrary
  admitted path is created/modified to test dirty-file detection. Old process
  tests and packaging references are under `test-old/` and remain quarry.
- `bin/seon-fresh:1-6` is a pure alias to `bin/seon`. Its non-historical
  references are dated research that records the compatibility decision; all
  current operator guidance and automation use `bin/seon`.

## Current progress

Commit `34cd8a5e8` deleted both `script/seon/dev/detach.py` and
`bin/seon-fresh`. The evaluation deletion lane replaced the source-admission
fixture's `detach.py` path with a neutral fixture path in its current cut wave.

The issue remains open because two protected, active skill resources still
teach the deleted alias:

- `.agents/skills/seon-flow-architecture/SKILL.md:122-124`
- `.agents/skills/seon-flow-architecture/references/degraded-start.md:70-71`

The great-deletion build wave explicitly protects `.agents/skills/**`, so
those live reader references must be removed by their owner before this issue
can be archived. Dated research and archived issue evidence may retain the old
name as archaeology.

## Owner

The fresh operator/tooling boundary.

## Acceptance

- Delete `script/seon/dev/detach.py`; use a neutral temporary tracked path in
  the source-admission regression so that test does not become its reader.
- Delete `bin/seon-fresh` after confirming no external automation still calls
  the compatibility name; current guidance has one operator spelling,
  `bin/seon`.
- Repository-wide search finds no non-archaeological reader of either path,
  and the fresh operator launch/process tests remain green.
