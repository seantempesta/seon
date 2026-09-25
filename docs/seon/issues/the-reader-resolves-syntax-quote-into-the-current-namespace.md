---
type: issue
status: open
severity: correctness
created: 2026-09-25
tags: [issue, reader, sci, syntax-quote]
---

# The reader resolves syntax-quote into the current namespace

Live in MCP SCI mode: `` `(inc String join) `` reads as `(user/inc user/String user/join)`; a macro that syntax-quotes `inc`
fails "Unable to resolve symbol". Cause: `syntax-quote-resolver` (src/seon/sci/reader.cljc:84-105) plus a fresh `(sci/init {})`
per read bypasses SCI's own `fully-qualify` against the real context (design-one-reader-opus,
one-reader-collapse-2026-09-25.md). Fix = slice S2 there: SCI owns resolution with the evaluating context; delete the resolver.
