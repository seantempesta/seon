---
type: issue
status: open
severity: high
created: 2026-09-26
tags: [issue, render, print, repl, dogfood]
---

# A plain map carrying an entity's identity key renders through that entity's renderer, and a render refusal fails the evaluation

Found by the standing dogfood lane (dogfood-repl-opus), working only through MCP.

## Observed (default archive 33ea69298, branch `dogfood-repl-opus-2`, 2026-09-26)

To see the attribute names and sizes of my own turn row:

```clojure
(let [t (seon.db/pull '[*] [:seon.turn/id "16787e7245dd"])]
  (into {} (map (fn [[k v]] [k (if (string? v) (count v) (if (map? v) (keys v) v))])) t))
```

The value is an ordinary map in which `:seon.turn/id` is `12`, the length of the id string. The
run was reported as an **exception** (7 KB):

```
seon.print/emit-text refused node at []: expected must be a print node (…), got a map
{:seon.print/face :seon.print/projected,
 :seon.render.call/selected-producer seon.render.transcript/render-run-ai,
 :seon.print/value {… :seon.error/operation seon.render.transcript/missing-selected-run,
   :seon.error/message "The selected run is unavailable because its run, agent, or database identity is missing." …}}
… Called from seon.render.value (value.clj:493). …
{…the same 3 KB map printed a second time…}
Contract finding: seon.print/emit-text returned this error, and none of its declared error alternatives matches it.
```

The evaluation itself succeeded. Only printing failed, and the agent was told its form threw.

## Which model is wrong

1. **Selection by key presence.** The renderer for a turn (`render-run-ai`) was selected because
   the value has the key `:seon.turn/id`. The value does not satisfy the turn schema
   (`:seon.turn/id` is `12`, not an id string). The law is that structural resemblance is not
   identity: a schema's declared pair applies to a value **that validates against that schema**
   (or whose entity identity resolves), not to any map that shares one key.
2. **A render refusal is treated as an evaluation failure.** The renderer's refusal, a
   `:seon.print/projected` wrapper carrying an error, is handed to `seon.print/emit-text`,
   which accepts only print nodes, and that refusal becomes the evaluation's error. The law is
   "failed rendering gives a diagnostic". The value should print at the floor, with a one-line
   diagnostic naming the refused renderer, and the evaluation stays a success.

## Expected

The map prints at the floor as data. If a renderer had been selected and refused, the shown text
would be the floor print plus a diagnostic line, never an exception, and never the value twice.

## Regression

`{:seon.turn/id 12 :x 1}` evaluated at the REPL prints `{:seon.turn/id 12, :x 1}`. A renderer
forced to refuse over a valid value yields the floor print, a diagnostic, and a successful
evaluation.
