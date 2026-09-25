---
type: issue
status: open
severity: friction
created: 2026-09-25
tags: [issue, reader, sci, mcp]
---

# The one reader's refusals are worse than the MCP bridge pre-read they must replace

## Problem

Audit R7 deletes the MCP bridge's lexical pre-reader
(`script/seon/dev/mcp.clj` `require-single-clj-form!` and helpers, ~80 lines)
so `seon.sci.eval/evaluate`'s one reader (`seon.sci.reader/read`, edamame) owns
the single-form refusal. On the same inputs the owner answers worse, so the
deletion is blocked until the owner is fixed.

## Evidence

Default pid 37460, 2026-09-25 ~19:43Z, `seon.sci.eval/evaluate` called with the
bridge's exact request keys on branch `delete-mcp-bridge-opus`:

| Source | Bridge today | `evaluate` shown text |
|---|---|---|
| `(+ 1 2) (+ 3 4)` | `multiple-forms`, line 1 column 9, preview `(+ 3 4)`, "Wrap sequences in `(do ...)`" | "Evaluation requires exactly one reader event." (no position, no count shown, no remedy) |
| `1 2` / `1\n2` | `multiple-forms` with position | reader crash: "Range [2, 1) out of bounds for length 3" |
| `""` | `empty-form` | contract refusal "expected a string, got a string" |
| `(+ 1` | `invalid-form` + reader error | good: position [1 4] and the EOF message |

`seon.sci.reader/read` on `"1 2"` returns one event with form `()` and source
`"1 2"` (prose-span recovery); `evaluate` then fails inside the reader path.

## Owners

- `src/seon/sci/eval.clj` `one-event` (:957): the multi-event refusal should
  name the second event's `:seon.sci.reader/line`/`:column`/`:source` (the
  events already carry them) and the `(do ...)` remedy.
- `src/seon/sci/reader.cljc` prose recovery: numeric-only multi-token text.
- `:seon.cluster.eval/source` contract: an empty string should refuse with the
  reader's "no form" message, not "expected a string, got a string".
