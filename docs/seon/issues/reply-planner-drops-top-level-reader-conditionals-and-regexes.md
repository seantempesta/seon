---
type: issue
status: open
severity: correctness
created: 2026-09-25
tags: [issue, reader, turn, one-repl-path]
---

# The reply planner drops top-level reader conditionals and regexes

`seon.turn/planned-sources` (the model-reply splitter) silently drops top-level `#?(…)` and `#"…"` forms that the one reader
reads, and treats `1 #?(:clj 2)` as prose (design-one-pipeline-opus, one-repl-language-2026-09-25.md rev 4). A form the agent
wrote never evaluates and nothing says so. Owner: the reply splitter must delegate form boundaries to the one reader
(`seon.sci.reader`), never classify by its own text rules; one-reader-collapse slice S4 moves fences/prose there.
