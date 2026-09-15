---
type: issue
status: open
severity: friction
tags: [issue, sci, agent, reader]
created: 2026-09-15
---

# A prose line without a comment marker becomes one error per word

## Problem

Run 11 (default, deepseek-flash, 2026-09-15 19:45Z): the model restated an
error in prose across several lines; only the first carried `;;`. The reader
evaluated every continuation line, so the ledger recorded six error
evaluations for one lapse — `Invalid symbol: Gate:`, `Invalid symbol:
arguments:`, `expected:`, `actual:`, `done-query:`, `subject:` — plus an
`Unmatched delimiter: )` from a parenthesis inside the prose. Asked afterwards,
the model said: "my multi-line expected:/actual: restatements of error output
blew up as Invalid symbol: expected:. I was effectively trying to quote the
error back at myself."

Six errors for one mistake is noise in the record, in the debug problems
panel, and in the next prompt; each one costs a turn of attention.

## Wanted

The REPL grammar stays: a `;` comment ends at the newline (Clojure's own
rule). Two things change at the reader seam, derived from the reader's own
data, never a regex: (1) consecutive unreadable text is ONE reader error
naming the span and the first offending token, with the fix "prose must
start with ; on every line"; (2) the opening teaches that rule once, in the
sentence that introduces comments. A regression asserts one error per
contiguous prose span.
