---
type: issue
status: open
severity: friction
created: 2026-09-24
tags: [issue, admission, instrumentation, bounded-presentation, cost]
---

# Bounded admission prints a whole leaf before its bound

Seen 2026-09-24 by lane fix-unbounded-failure-text, on a scratch root built from HEAD
`7b1d44c07` plus the lane's files (pid 17804).

## Problem

Building one contract refusal costs time proportional to the offending value. It costs
about 0.5 s per megabyte of a string argument:

| call | ms |
|---|---:|
| `(seon.error.problem/problem-sentence [one-MB-string] {} nil nil)` inside a test fixture, through `seon.test/bounded-result` | 1,160 / 1,170 / 1,422 |
| the same refusal with a 10 MB string, on default (archive `ce7384682`) | 905–967 per form (message only) |
| the same refusal with a 10 MB string on the scratch root, raised by a fixture | 1,944–2,352 (refusal alone), 4,689–6,912 (whole `bounded-result`) |

Samples of the body thread show every sample in
`seon.sci.admit/canonical-edn` (`src/seon/sci/admit.clj:85`, `pr-str` at `:95`). The
caller is `leaf!` (`:188`), from `open-node` (`:238`) and `project` (`:451`). The refusal
reaches this code through `seon.error/project-observation` (`src/seon/error.clj:398`),
which `seon.instrument/boundary-refusal` calls for each explanation item and location.
`leaf!` writes the leaf's complete canonical EDN, and only then does the bounded
appendable refuse it as over the bound. So the bound limits what is kept. It does not
limit the work.

The refusal's result is still bounded. Its ex-message is 162 characters, its explanations
carry an over-bound marker, and `seon.render.value/floor` renders the whole chain in
1,842 characters. Only the cost scales with the value.

## Wanted

The admission stops emitting once the byte bound is crossed, so that a leaf string is
never printed whole to learn that it is too big. For a string, the UTF-8 length is known
without printing (`utf8-length`, `src/seon/sci/admit.clj:97`). The owner is `seon.sci.admit`.
The regression is a contract refusal over a 10 MB argument that is built in well under
one second.
