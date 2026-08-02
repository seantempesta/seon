---
type: component
status: active
tags: [component, agent, repl]
---

# Agent-reply reader

Fresh Seon has one model-reply reading path. `seon.sci.reader/read` returns
ordered events with exact source spans, rejects unsafe or unknown reader tags,
represents malformed input as flat error values, and tracks the namespace in
effect while reading (`src/seon/sci/reader.cljc:28-116,296-405`).

`seon.cluster.reply/sources` projects those events into the ordered source
strings the run loop evaluates (`src/seon/cluster/reply.cljc:20-48,143-240,306-348`).
Structured top-level collections are code. Prose becomes single-`;` comments
attached to the next form, while trailing or prose-only replies become a
comment-only source. Markdown fence lines are stripped before reading. A bare
symbol is code only when it occupies its own line and the reply also contains
structured code.

Malformed Clojure is never repaired. It returns
`:seon.cluster.reply/unreadable`; an empty reply returns
`:seon.cluster.reply/no-forms`. Agents should emit balanced ordinary Clojure
and use single-`;` comments for prose they intend to preserve.

## Verification

The recurring proof is `bin/test seon.sci.reader-test seon.cluster.reply-test`.
The reader tests cover exact source, namespace tracking, unsafe tags, and
errors-as-values; the reply tests cover prose/code classification, Markdown
fences, bare symbols, unreadable replies, and empty replies.

The retired parser-specific runner, oracle server, and pod/self-host
evaluation bundle are not alternate proof surfaces.
