---
type: issue
status: open
severity: friction
created: 2026-09-25
tags: [issue, reader, reply, one-repl-path]
---

# A trailing prose word after a fenced block becomes an evaluation

## Problem

`seon.cluster.reply/sources` plans `"Here is code:\n```clojure\n(+ 1 2)\n```\nDone."` as two sources,
`["(+ 1 2)" "Done."]`. The closing word `Done.` reads as a symbol alone on its line, and a reply that contains a
structured form admits such a line as code (`standalone-symbol?` and `code-event-indexes`,
`src/seon/cluster/reply.clj:97-107`, `:169-180`). The agent's closing prose then evaluates as `(Done.)`'s symbol and
fails, or resolves to something unintended.

## Evidence

Default pid 76020, 2026-09-25, the planner at 19fbb7901 and with P2a applied give the same answer (probe recorded in
`docs/research/agent-platform/one-repl-language-2026-09-25.md` §2.2). The regression
`seon.turn-test/a-reply-keeps-its-dispatch-macro-forms-as-code` asserts today's `["(+ 1 2)" "Done."]` so that a fix
must change it deliberately.

## Owner and acceptance

The reply splitter in `src/seon/cluster/reply.clj` (and the one-reader-collapse move of fences and prose into
`seon.sci.reader`). Prose after the last fenced block, or a standalone word outside any fence in a fenced reply, stays
prose; a bare symbol on its own line in an unfenced reply keeps today's code classification. Same class as
`inline-form-in-reply-prose-becomes-an-evaluation.md`: prose admitted as code.
