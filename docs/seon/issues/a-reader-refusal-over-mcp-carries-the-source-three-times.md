---
type: issue
status: open
severity: friction
created: 2026-09-26
tags: [issue, mcp, reader, error, render, ugly-output]
---

# A reader refusal returned over MCP carries the source several times and the reader's container stack (51 KB)

## Evidence (default pid 94554, 2026-09-26 ~00:52Z)

An MCP `eval_clj` JVM-mode form of about 1.8 KB, unreadable because `'@)` reads as a deref of `)`, returned a windowed
value of **51,086 bytes** (blob `a04ac55b2182f6ea123da97fd001510796adcd2fed325c761333468b5098ed96`). The value holds:

- `:seon.error/offending`: the whole source;
- `:seon.error/data :seon.sci.reader/text`: the whole source again;
- `:seon.sci.reader/containers`: edamame's open-container stack, each level with its `:edamame/elements` (the read
  forms so far), elided in turn with requery forms;
- the MCP envelope's `seon.dev.mcp/form`: the source a third time.

The useful content fits in one line: "Unmatched delimiter: ], expected: ) to match ( at [14 12]", with offset 1862
and its row and column.

## Why it is a defect

An error is an ordinary value, and its producer declares what it carries (AGENTS "declare errors as flat data";
"store references, never copies"). A reader refusal that embeds its input twice, plus the reader's intermediate
parse state, spends the caller's window on copies, and the floor then has to elide them. Related window sizing:
[mcp-value-window-inherits-the-agent-token-budget](mcp-value-window-inherits-the-agent-token-budget.md). That issue is
the bound; this one is the producer's content.

## Owner and acceptance

The owner is `seon.sci.reader/error-value` (the operation named in the refusal).

Acceptance: the refusal names the message, offset, row/column, the opened delimiter and its position, and a bounded
excerpt around the offset. It references the submitted source by the evaluation or turn id, or by blob digest, instead
of copying it, and carries no edamame container elements. One regression reads a 2 KB malformed form and asserts that
the rendered refusal stays under a declared size.

Found by `docs/research/agent-platform/system-state-opinion-2026-09-26.md` §9.
