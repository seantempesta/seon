---
type: issue
status: open
severity: cleanup
tags: [issue, runtime, derived-state, tooling]
---

# Replace derivable local mutable accumulators

## Problem

Several invocation-local atoms and volatiles are used only as imperative
accumulators. They coordinate no callback, thread, resource, or protocol and
can be expressed as ordinary loop/reduce state. Their locality makes them
non-durable, but locality alone does not make mutation necessary.

## Evidence

- `script/seon/dev/markdown.clj:168-204,675-696,895-908` accumulates extracted
  links, output lines, and a fix count in three atoms.
- `src/seon/fs/jvm.clj:629-704` mutates one traversal map through recursive
  `glob-walk!` calls.
- `src/seon/fn.clj:1309-1331` mutates a progress count that is derivable from
  indexed batches.
- `src/seon/fn/schema_shape.clj:243-280` mutates a recursive `seen` set.
- `src/seon/render/walk.clj:305-377` mutates the remaining-node count and seen
  entity set through a recursive walk.
- `src/seon/flow.clj:335-343,632-640` allocates throwaway empty atoms only so a
  shared terminal helper can call `swap!` on a value nobody observes.

## Owner

Each named pure transformation owns immutable accumulator state. The Flow
terminal helper owns an interface that distinguishes tracked from untracked
active-work instead of manufacturing a mutable no-op argument.

## Acceptance

- Replace every listed accumulator with reduce/loop/recursive return state or
  an interface that requires no dummy mutable reference.
- Preserve exact ordering, caps, cleanup behavior, and result shapes.
- Focused tests prove each transformation while a structural census finds no
  mutable constructor at these sites.
