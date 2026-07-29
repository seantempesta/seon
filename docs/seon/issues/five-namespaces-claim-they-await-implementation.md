---
type: issue
status: open
severity: friction
tags: [issue, docs, render, context]
---

# Five namespaces claim they await implementation

## Problem

Five contract-era namespace docstrings still tell the reader that every body
throws `awaits implementation`. All five are fully implemented and under test.
Docstrings render into agent context, so an agent reading the render router —
the most central surface in the system — is told the router does not work.

`seon.cluster.registry` contradicts itself inside one sentence, recording both
its implementing commit and its own non-existence.

## Evidence

- `src/seon/render.clj:8` — "Nothing here is implemented: every body throws
  `awaits implementation`." `render` (line 117) and `kinds` (line 96) are
  implemented and total; `test/seon/render_test.clj` exercises them.
- `src/seon/render/block.clj:5` — "Every body throws `awaits implementation`."
  23 implemented `defn`s; `test/seon/render/block_test.clj` has 31 deftests.
- `src/seon/render/hiccup.clj:7` — same claim; 16 implemented `defn`s.
- `src/seon/cluster/registry.clj:6-9` — "(drafted + SEALED 2026-07-27,
  implemented green a35c95d0a […]) Nothing here is implemented: every body
  throws `awaits implementation`."
- `src/seon/cluster/export.clj:7` — same claim; 10 implemented `defn`s.

No body in any of the five throws `awaits implementation`; the string appears
nowhere outside these docstrings.

## Owner

Each namespace owns its own docstring.

## Acceptance

- No namespace docstring in `src/` claims a body throws `awaits implementation`
  unless a body does.
- Each of the five keeps its genuine design prose — the contract reasoning is
  valuable — and loses only the stale status sentence, along with any "once
  sealed the implementation lane fills the stubs" instruction to a lane that has
  already finished.
- `rg 'awaits implementation' src/` returns nothing.
