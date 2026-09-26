---
type: issue
status: open
severity: friction
created: 2026-09-26
tags: [issue, program-graph, my-program, dogfood]
---

# `my.program/breaks` proposes `(my.program/launch! …)`, which does not exist, and `ns-unmap!`'s documented form refuses with "got nil nil"

Found by the standing dogfood lane (dogfood-repl-opus), working only through MCP.

## Observed (default archive 10d6fdff5, branch `dogfood-repl-opus-8`, 2026-09-26)

- `(my.program/breaks {:seon.program/subject 'user/helper})` answered a correct plan: caller
  `user/caller`, and an issue "user/caller must be repaired before retracting user/helper". Its
  `:seon.program/plan` ends with `:launch (my.program/launch! {:seon.program/subject (quote
  user/helper), :seon.issue/budget 8})`. But `(resolve 'my.program/launch!)` answered `nil`, and
  `(dir my.program)` lists no `launch!`. The same answer's `:launch-unavailable` gap says "Nothing
  was launched" in prose that an agent cannot act on ("generate reads actual detector subjects, not
  a refused deletion's proposed subjects").
- `(doc my.program/ns-unmap!)` has `:example ""`. Its example, `(my.program/ns-unmap!
  'seon.turn/open?)`, sits inside `:body` after the words "Until seam B lands", an internal term.
- Called as documented while a caller survived, `(my.program/ns-unmap! 'user/helper)` answered
  `my.program/refusal refused  at []: expected a map (:seon.program/change), got nil nil.` That
  says nothing about the caller. `breaks` had the answer.
- After redefining `caller` without `helper`, `(my.program/ns-unmap! 'user/helper)` worked. The row
  and the Var were gone and `(caller 1)` answered `3`. The deletion path itself is right.
- `(doc my.program/ns-unmap!)`'s `:in` shows internal members (`:seon.sci.eval/ctx`,
  `:my.program/base-ctx`, `:seon.store/store`, `:seon.agent/routing`) as the request map.

## Which model is wrong

The plan is prose and forms written by hand in the producer, not derived from the program. A
`:launch` form is emitted whether or not its function is installed. Every form a plan or a
docstring proposes is itself a program reference, and it must resolve at the producer or be
omitted. This is the same "references are decided at the one producer" law. The docstring parser
also missed the `Example:` line because it follows other body text on the same paragraph.

## Expected

`breaks` proposes only installed forms (or none). `ns-unmap!` refusing a severed deletion returns
the `breaks` answer, meaning the surviving callers, not "got nil nil". `doc`'s `:example` holds the
example, and `:in` shows the caller's arguments with supplied members marked.

## Regression

Every form that `breaks` proposes resolves in the agent's context, and a refused `ns-unmap!`
answer names `user/caller`.
