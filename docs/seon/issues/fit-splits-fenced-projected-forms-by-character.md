---
type: issue
status: open
severity: blocker
tags: [issue, render, agent, wave/print-path]
---

# Elide fenced projected forms atomically at the print owner

## Problem

`seon.print/fit` treats a selected producer's terminal projected output as an
ordinary string budget. When that output contains a Markdown code fence, a
character prefix can retain the opening fence and only part of a Clojure form.
The HTML instruction card then teaches an unreadable, syntactically incomplete
reply example.

## Exact attribution — 2026-08-14

The producer did not hand-slice its instruction. The guilty path was:

1. `seon.cluster.instruction/instruction-html` returned Hiccup whose child was
   the complete string from `instruction-ai`.
2. `seon.render/fit-terminal` wrapped that Hiccup in a
   `:seon.print/projected` node and handed it to the one `seon.print/fit`
   owner.
3. `seon.print/projected-text` serialized the Hiccup with `pr-str`.
4. The pre-repair `fit-projected` used `subs` on that serialized text at
   `src/seon/print.cljc:835,850` in the observed checkout, cutting inside the
   fenced form before creating its elision node.

The clip-ripout lane's in-flight consolidation moved the same operation to
`bounded-text` (`src/seon/print.cljc:834-839`) and `fit-text`
(`:854-863`); that refactor changes the location, not the failure class. The
decision about whole fenced forms must live at this owner, not in the
instruction producer.

## Owner

`seon.print/fit`, owned by the clip-ripout lane for this repair.

## Acceptance

For every adversarial budget, fitting a projected fenced multi-form document
returns either the complete balanced fence with every form readable, or one
real subtree elision carrying omitted count, path, and requery identity. No
output contains an unmatched fence or a token/form prefix. A positive census
also proves production render producers contain no independent bounded-text
slicing mechanism outside `seon.print`.
