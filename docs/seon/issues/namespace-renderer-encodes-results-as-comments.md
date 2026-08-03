---
type: issue
status: open
severity: friction
tags: [issue, render, context, architecture]
---

# Stop encoding namespace-render results as source comments

## Problem

The namespace AI renderer presents summaries, schemas, definitions, empty
states, and budget omissions with `;` or `;;` prefixes. That makes computed
render output look like source commentary. Owner decision 11 reserves comment
grammar for agent-written input and requires displayed forms to be followed by
their actual computed values.

## Evidence

`src/seon/render/ns.clj:329-445` constructs comment-prefixed omission, empty,
referenced-schema, compact-schema, and compact-function output. Its recurring
assertions in `test/seon/render/ns_test.clj:226-296` freeze several of those
comment markers. The superseding ruling is decision 11 in
[messaging, state, and reply-norm design](../../prds/sci-execution-runtime/research/messaging-state-design-notes-2026-08-03.md).

## Owner

`seon.render.ns` owns both projections of one namespace representation.

## Acceptance

Every namespace AI result is displayed as an ordinary computed value without
comment prefixes, decorative comment framing, or `;; =>` annotations. Empty
and elided states remain explicit values, and the HTML twin conveys the same
facts without embedding comment syntax as content.
