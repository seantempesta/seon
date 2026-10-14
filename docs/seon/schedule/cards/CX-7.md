---
title: "Card CX-7 One debug view: the context as sent, from the one assembler"
type: reference
status: done (55ede54a9)
created: 2026-10-08
---

# CX-7 One debug view: the context as sent, from the one assembler

Ledger row CX-7 (group D; replaces DBG-01). Owner ruling round 10; spec agent-loop.md §3.4 "Debug view" (no second
assembler; the web never reads the proc's memory). Lane: Sonnet.

## What becomes true

The web's sent-context view shows, for a chosen call, the messages produced by `seon.context/model-context` (CX-1) at
that call's stored basis, printed through the value printer's HTML face. It replaces the capture as the way an owner
reads what a model saw, so CX-10 can delete the capture.

## Functions

- SCHEMA: `:seon.ai.attempt/basis-t :seon.db/basis-t` (optional member of the attempt), written with the attempt from
  the database value `model-context` read (`observed-db`).
- CHANGE `seon.render.web/sent-context`: `database` = `(db/as-of head basis)` for the chosen call (query param
  `"call"`, a `:seon.turn/id`), else the head; composed = `seon.context/model-context` with a read-only shown-request.
  Its `seon.cluster.prompt/prompt` call is deleted.

## Deleted

The `prompt` call in `sent-context` and request members only it used. Net ≈ −10 source.

## Proof

`seon.render.web-debug-test/the-debug-view-is-the-assembled-request` (new): one fixture-model pass; the HTTP
fixture's received `"messages"` `=` the system and user texts `model-context` answers at the attempt's
`:seon.ai.attempt/basis-t`, and the debug route's data is that same value.

## Cost

L: one history read and one block render at one basis, only when the page is opened.

## Dependencies and order

CX-1. Must land before CX-9 (which deletes `prompt`) and CX-10 (which deletes the capture). Files:
`src/seon/render/web.clj`, `resources/seon/schemas/seon.ai.attempt.edn`, `src/seon/turn.clj` (attempt write: one
key), `test/seon/render/web_debug_test.clj`.
