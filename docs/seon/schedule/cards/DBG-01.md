---
title: "Card DBG-01 One debug view: the context as sent"
type: reference
status: active
created: 2026-10-08
---

# DBG-01 One debug view: the context as sent

Ledger row DBG-01 (group D). Owner ruling round 10: one debug view, the context exactly as sent, produced by the same
assembly function, printed by the value printer's HTML face. Spec: agent-loop.md §3.4 "Debug view" (no second
assembler; the web never reads the proc's memory). Lane: Sonnet.

## What exists
`seon.render.web/sent-context` (`src/seon/render/web.clj:3180-3193`) already shows "the context as its next model
call is sent", but builds it with `seon.cluster.prompt/prompt` (deleted by S4-07b2). `sent-context-html` (`:3195`)
already prints through `seon.render.value/html` (`src/seon/render/value.clj:304`). Convert, do not add a view.

## Files
`src/seon/render/web.clj`, `src/seon/ai.clj` (one read function), `resources/seon/schemas/seon.ai.attempt.edn`,
`src/seon/turn.clj` (attempt write: one key), `test/seon/render/web_debug_test.clj`.

## Functions
- SCHEMA: `:seon.ai.attempt/basis-t :seon.db/basis-t` on the attempt (optional member), written with the attempt
  (`src/seon/turn.clj:4250-4273`) from the database value the call's context was read from (`observed-db`, `:4572`).
- ADD `seon.ai/sent-messages`
  `{:malli/schema [:=> [:cat :seon.db/database-value :seon.agent/id :seon.task/assignment] :seon.ai/messages]}`
  `[database agent-id assignment]`: `(messages :transcript (seon.bootstrap/static-prefix database agent-id)
  (mapv seon.render.transcript/exchange-text (seon.eval/exchanges database agent-id assignment))
  (block-text database agent-id))`, where `block-text` is the same printed current-state block the model call uses
  (CS-02's render of `seon.context/current-state`, through `seon.render.value/text`). The model call site in
  `seon.turn` uses this same function for its exchanges/prefix/block when the proc holds no exchanges, so there is
  one assembler.
- CHANGE `seon.render.web/sent-context`: `database` = `(db/as-of head basis)` where `basis` is the attempt's
  `:seon.ai.attempt/basis-t` for a chosen call (query param `"call"`, a `:seon.turn/id`), else the head's;
  `composed` = `(seon.ai/sent-messages database agent-id assignment)`; assignment from the call row's
  `:seon.turn/assignment` (S4-02a). `debug-turn-request` (`:689`) loses its prompt-only members if no other caller.

## Proof
`seon.render.web-debug-test/the-debug-view-is-the-assembled-request` (new): one fixture-model pass; the HTTP
fixture's received `"messages"` vector `=` `(seon.ai/sent-messages (db/as-of db basis-t) agent-id key)` for that
call's stored basis; the debug route's data (`::found` of `sent-context`) is that same vector.

## Other tests reaching it
`seon.render.web-debug-test`, `seon.render.web-context-test`, `seon.ai-test`.

## Cost
L: proportional to the history and block read at one basis (one exchanges read, one block render), only when the
page is opened.

## Deleted
The `seon.cluster.prompt/prompt` call in `sent-context`.

## Commit
`bin/lane commit <lane> -m "DBG-01: one debug view, the context as sent" --test seon.render.web-debug-test seon.ai-test src/seon/render/web.clj src/seon/ai.clj resources/seon/schemas/seon.ai.attempt.edn src/seon/turn.clj test/seon/render/web_debug_test.clj`

## Dependencies
CS-03a, S4-07a, S4-07b1. Must land BEFORE S4-07b2 (which deletes `prompt/prompt`).

## Reset
None.
