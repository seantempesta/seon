---
type: issue
status: open
severity: friction
tags: [issue, render, context, architecture]
---

# Make the rendered walk an ordinary REPL value

## Problem

The walk AI assembly places a `;;` header before every rendered unit and uses
more comments for the call description, branch metadata, elision guidance, and
volatile-context marker. The framing makes real computed outputs look like
source comments.

## Evidence

`seon.render.walk/prose` documents and implements the comment-per-unit contract
at `src/seon/render/walk.clj:541-640`. The exact `;; d`, call, branch, elision,
and metadata strings are built at `:593-635`. The superseding ruling is
decision 11 in
[messaging, state, and reply-norm design](../../prds/sci-execution-runtime/research/messaging-state-design-notes-2026-08-03.md).

## Owner

`seon.render.walk` owns assembly of the rendered neighbourhood value.

## Acceptance

Calling `seon.render/walk` displays the call as a form and the rendered
neighbourhood as its actual computed value. Unit identity, branch handles,
elision guidance, and volatile metadata remain queryable and visible without
comment prefixes or decorative comment framing.
