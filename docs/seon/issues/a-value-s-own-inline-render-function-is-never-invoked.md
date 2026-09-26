---
type: issue
status: open
severity: friction
created: 2026-09-26
tags: [issue, render, render-producers]
---

# A value's own inline render function is never invoked

AGENTS' render law lists three forms of a value's own `:seon.render/ai`/`:seon.render/html` key: "a renderer
symbol, an inline function, or rendered data". `seon.render/explicit-value` (`src/seon/render.clj:588`) and
`declared-projection` handle a qualified symbol (invoked through `invoke-selected`) and rendered data
(`inline-rendered`); a function object falls into `inline-rendered` as data and prints as
`#object[user/eval…/fn--…]`.

## Evidence (default a1b01c92a lineage, armed scratch copies of value.clj and render.clj, 2026-09-26)

`{:seon.render/ai (fn [_] "hello") :x 1}` shows `#object[user/eval1122883/fn--1122888]`; nested under `:a` the
same. `{:seon.render/ai "just text"}` shows `just text`; `{:seon.render/ai 'seon.error/render-ai …}` invokes its
producer.

## Owner question

Either implement the inline function as a bounded invocation through the one REPL path (a stage value, not a
second evaluator), or strike "an inline function" from the law.
