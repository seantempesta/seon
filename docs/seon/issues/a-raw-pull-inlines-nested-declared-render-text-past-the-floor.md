---
type: issue
status: open
severity: high
created: 2026-09-26
tags: [issue, render, floor, print, provenance]
---

# A raw pull inlines nested declared render text past the floor budget

## Observed (MCP SCI run on a branch, archive 052552039, 2026-09-26)

`(seon.db/pull '[*] [:seon.agent/id "mcp/dre"])` from the agent itself was shown as
**78,918 characters**, after only four short runs. Each `:seon.runtime/turns` element was
replaced by that turn's whole declared AI render, as a string:

```
:seon.runtime/turns
  ["Run 5ea2ef45c4e9, opened #inst \"2026-09-26T02:30:14.914-00:00\". It completed.\n\nuser=> (+ 1 2)\n#:seon.repl{:value 3, :result result/e8f73204c2cb9, :ms 0}" …
```

Root's prompt shows the same class. `:seon.agent/namespace` inside an identity pull prints as
`";; Namespace my.agents.root\n(ns my.agents.root)\n;; public functions: none\n(dir my.agents.root)"`.

## Class

A raw value that reaches the floor charges its own structure to B, but a nested value with a
declared pair is replaced by that pair's text. Under the provenance rule (one-render-path §3d)
that text is not charged to B, so a raw read escapes the floor's budget in proportion to the
history it references. It also turns data into prose inside a value, so the agent can no
longer `get-in` it.

## Expected

Inside a raw value, nested entities keep their identity (for example `{:db/id 39875}` or a
lookup) under B. A declared render applies to a value the walk or a form selected as a node, not
to every nested member of a raw result.

## Regression

A pull of an agent row with 20 turns shows at most B characters, and each turn appears as its
identity.
