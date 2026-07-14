---
type: prd
status: planned
tags: [prd, web, agent]
---

# Agent canvas interaction roadmap

## Outcome

The one `my.canvas` path lets an agent present and update a persistent focal
value with discoverable, schema'd controls that behave correctly for the agent
and human across validation, focus, layout, and live updates.

## Current state

Canvas facts, AI/human twins, Hiccup controls, the reactive call gate, focus
derivation, pin/clear operations, and a generated canvas skill exist. The full
control matrix and browser behavior have not been audited or graduated as one
end-to-end mechanism.

## Ordered work

1. Inventory every public `my.canvas` schema/function, renderer, control, call
   envelope, database transition, test, and live route against dependencies.
2. Define the smallest complete control matrix: button, input, select, toggle,
   form, validation failure, structured runtime failure, and disabled/pending
   behavior.
3. Reconcile show, focus, pin, unpin, clear, reactive update, and session-local
   selection through one database-derived canvas transition.
4. Remove duplicate or implicit control/state paths and polish the schemas,
   names, argument data, errors, and generated skill for simple-model use.
5. Prove narrow/wide layouts and every transition through focused tests, live
   REPL calls, gzip frames, and real browser interaction.

## Graduation

- Function schemas alone make the canvas and every control discoverable and
  correctly callable by a simple model.
- Valid controls transact once and morph the right unit; invalid controls
  return structured errors without partial writes or a wedged feed.
- Focus/pin/clear and reactive updates converge across agent and human twins.
- No duplicate canvas/form/feed/state mechanism remains.
- The complete control matrix passes on narrow and wide real-browser layouts.
