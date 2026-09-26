---
type: issue
status: open
severity: friction
created: 2026-09-26
tags: [issue, render, print-path, repl, legend]
---

# The cut legend inside a REPL reply comments out the reply's tail

The floor appends one legend line (`;; …N = N more, …+N = N more characters, …? = not yet realized. …`) to a
cut value's text (`seon.render.value/prepare`; one-render-path design §3d "Legend"). `seon.repl/response` then
splices that text as the `:value` entry of `#:seon.repl{:value <text>, :result …, :ms …}` on one line, so the
legend's `;;` turns `, :result result/e… :ms 0}` into comment text: the reply is no longer readable EDN, and the
reply carries a comment-shaped line the agent did not write (ruling 45).

## Evidence (verify-1510, HEAD 052552039)

`seon.render.transcript-test/one-reply-reads-identically-on-the-page-in-history-and-in-the-prompt` :1366:

```
#:seon.repl{:value [0 1 2 … 31 …8]
;; …N = N more, …+N = N more characters, …? = not yet realized. Not retrievable: the value has no result handle., :result result/e046d9f1e3133, :ms 0}
```

## Owner decision needed

The design places the legend "once, after the value"; the REPL reply is the printed unit. Options, simplest first:
1. The reply owner (`seon.repl/response`) prints the legend after the closing `}`: needs the legend as a stored
   evaluation fact beside `:seon.eval/shown` (one optional attribute), since replies are composed from stored text.
2. The legend line ends with a newline, so any composite embedding the value text stays readable (the reply tail
   moves to the next line); no schema change, but ruling 45 still sees a comment line.
3. No legend in REPL replies: the cut face alone (`…N`, `…+N`, `…?`) plus the continuation already on the tree.
