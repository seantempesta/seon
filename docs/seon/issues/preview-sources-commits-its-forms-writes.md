---
type: issue
status: open
severity: high
created: 2026-09-26
tags: [issue, turn, preview, custody, render]
---

# `preview-sources` commits the writes of the forms it previews

`seon.turn/preview-sources` (`src/seon/turn.clj:4888`) documents that it persists nothing:
"nothing settles, no evaluation entity exists". It does not settle, but the previewed forms
still write through the agent's connection.

## Observed (JVM scratch namespace, branch `design-repl-entry-renders`, archive 052552039, 2026-09-26)

`preview-sources` was called on the branch agent's held context with:

```clojure
(seon.db/transact! [{:my.note/id "dre-preview-probe" :my.note/agent [:seon.agent/id "mcp/dre"] :my.note/content "x"}])
(my.note/add! {:my.note/id "dre-preview-2" :my.note/content "y"})
```

Both committed. Afterwards the branch held `["dre-preview-2" "dre-preview-probe"]`, and the first
form's shown text is a `#:seon.db{:tx 536874336 …}` report.

## Cause

The fork inherits two carriers of the connection:

1. `::custody :seon.db/connection`. `evaluate` reads it (`src/seon/sci/eval.clj:4059-4060`) and
   binds it with `db/with-branch` (`:4092`).
2. The environment's `:seon.db/connection`. Call preparation supplies it to `my.*` writers
   (`src/seon/call_preparation.clj:1201-1205`).

Removing (1) alone makes elided `seon.db/transact!` refuse, but `my.note/add!` still committed
`dre-ro2`.

## Expected, and the fix's owner

A preview, and the AI render evaluation that reuses it, runs on a read-only fork. The fork must
drop both carriers through one environment scope, and call preparation must supply
`:seon.db/db` from the scoped environment (see
`call-preparation-snapshot-answers-an-older-database-with-a-newer-program.md`, evidence
2026-09-26). The design is `docs/research/agent-platform/repl-entry-renders-design-2026-09-26.md`
§2.3.

## Regression

A preview of an elided `transact!`, a supplied `my.note/add!` and a `defn` leaves the branch's
datoms unchanged, and each form shows its refusal.
