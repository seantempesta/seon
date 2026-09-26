---
type: issue
status: open
severity: friction
created: 2026-09-26
tags: [issue, render, render-producers, help, bootstrap]
---

# `(help)` shows its `{:seon.help/lines …}` map because a macro call names no producer

`:seon.help/help` declares `seon.bootstrap/render-help-ai`, but the help value has no identity attribute, so its
pair is reachable only as the declared return of the root call's contracted function
(`seon.render/declared-producers`, `src/seon/render.clj:525`). `(help)` is a macro (`src/seon/bootstrap.clj:11`)
expanding to `(seon.bootstrap/help-value)`, and `seon.render/head-function` (`:424`) deliberately answers nil for a
macro head, so no producer is selected and the floor prints the map.

## Evidence

`loop-proof-test` :407 (landing 587704c19): the root opening's `(help)` shown text is the map. On default
(2026-09-26, armed): `(seon.render.value/prepare {:seon.render/value {:seon.help/lines ["line one" "line two"]} …})`
prints `{:seon.help/lines ["line one" "line two"]}` with a live SCI ctx.

## Options, simplest first

1. `help-value` returns `{:seon.help/lines … :seon.render/ai seon.bootstrap/render-help-ai}`: the value's own
   master-override key, no selection change (bootstrap.clj, seon.help.edn).
2. `'help` refers to the `help-value` Var itself (delete the macro; convert `agent_situation_test` :49 and the
   callers that macroexpand it), so the call head is the contracted function.
3. `head-function` follows a top-level macro call's expansion head once (runs the macro a second time).

Entries versus text: under the 2026-09-26 render law an AI render returns REPL entries; help's lines are prose,
so its entries are `;;` comment lines — which collides with ruling 45 (the same collision as the cut legend,
`the-cut-legend-inside-a-repl-reply-comments-out-the-reply-tail.md`). That decision belongs with the
REPL-entry render design.
