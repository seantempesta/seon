---
title: "Card CX-1 The model sees help, its own exchanges, and its block last"
type: reference
status: active
created: 2026-10-08
---

# CX-1 The model sees help, its own exchanges, and its block last

Ledger row CX-1 (group B/D; first card of the context cut, §3.7). Spec: agent-loop.md §3.4 (static prefix, history,
current-state block; wire format A). Replaces S4-02b1, S4-03, the assembly half of CS-03a and S4-04's text join.
Lane: Opus (it changes what every model pass sends). About 15 minutes: one new function, one call site, one test.

## Evidence (live run, agent `live-flash`, branch `:agent-2246f5f5491f`, 2026-10-08)

The stored prompts (`:seon.context.capture/prompt`, 19 rows) are one user message with no system message, no block,
ending in `turns left: N of 5` (`seon.repl/frame`). The first run's prompt grew 2.9 K → 140 K characters in ten
passes. Between model passes the history carries forms the agent never wrote (identity, steps, messages, settings
pulls; `seon.error/for-agent`; `:seon.repl/changes` diffs from the refresh) and root's own MCP `settings!` calls made
under the agent's id. The model learned its proof was red only by evaluating `(seon.context/current-state)` itself.
On that branch 9 of 16 call rows carry a model attempt; the other 7 are system or outside rows.

## What becomes true

A model pass sends exactly: the system message = the shown text of `(help)`; the user message = the agent's own model
exchanges in agent order (call rows carrying `:seon.turn/attempts`, each evaluation through `seon.repl/text`, the one
generator), then the shown text of the current-state block, last. Nothing else: no `turns left` frame (the block's
`:seon.context/turns` section carries it), no system-turn or opening rows, no outside evaluations made under the
agent's id. The prompt-selection walk (`seon.cluster.prompt/prompt`) is no longer called on the model path.

## Functions

- ADD `seon.context/model-context`
  `{:malli/schema [:=> [:cat :seon.db/database-value :seon.sci.eval/shown-request :seon.agent/id] :seon.context/model-context]}`
  `[database request agent-id]` → `{:seon.ai/system s :seon.context/exchanges [s …] :seon.ai/prompt block-text}`:
  - `:seon.ai/system`: `(:seon.sci.eval/shown (seon.sci.eval/shown-result (seon.bootstrap/help-value database agent-id) request nil))`,
    the same text `(help)` shows at the REPL (help is literal: no cache until a measured miss costs over 1 ms).
  - exchanges: `(seon.eval/of-agent database agent-id selector)` with selector adding
    `{:seon.cluster.eval/run [:seon.turn/id {:seon.turn/attempts [:db/id]}]}`, kept where the run has attempts,
    `partition-by` run id, each run joined by `"\n"` over `(seon.repl/text (seon.repl/entity-emission (assoc e :seon.db/db database)))`.
  - block: the shown text of `(seon.context/current-state database agent-id)` through the same
    `seon.sci.eval/shown-result` (its declared AI render, then the value printer; one ordinary evaluation, no block
    budget, CS-02).
  It is the ONE assembler: the debug view (CX-7) calls it at a stored basis. `seon.context` must not require
  `seon.sci.eval` statically if that makes a cycle: resolve it like the existing delays in `seon.turn`.
- ADD schema `:seon.context/model-context` in `seon.context.edn`.
- CHANGE the model call site in `seon.turn` (the `rendered` binding around the `@cluster-prompt-prompt` call):
  call `model-context` with `observed-db`, a shown-request built from the cluster's `:seon.sci.eval/ctx`, caps and
  time limit (the same members the prompt request passes today); `text` = `(str/join "\n\n" (conj exchanges block))`;
  the request to `ai/complete` gets `:seon.ai/system` and `:seon.ai/prompt text`. `seon.ai/request-body` already sends
  `:seon.ai/system` as the system message; no change in `ai.clj`.
- KEEP, for now, the capture write with the new text (`:seon.cluster.prompt/rendered-context` with the new `text`,
  `[]` contributions, `observed-db`): it is how the NEXT live run's prompts are read until CX-7's debug view and
  CX-10 delete it. If `:seon.context/contributions` refuses an empty vector, pass the prompt with no contributions
  member only if the schema marks it optional; otherwise report and stop (do not widen the schema for a store being
  deleted).

## Deleted

Nothing yet: `prompt`, `frame` and the walk lose their model-path caller and are deleted in CX-9/CX-10. Net
≈ +70 lines (function ~35, schema ~5, test ~30).

## Proof

`seon.cluster.turn-test/a-model-request-is-help-own-exchanges-and-the-block-last` (new), fixture model over the HTTP
fixture: an assigned agent with a red proof; one outside `seon.mcp/call` under the agent's id (`(+ 1 2)`); two
fixture-model passes, the first replying `(defn …)`. Assert on the two captured request bodies (data):
- the system message `=` the `:seon.eval/shown` of `(help)` evaluated through `seon.mcp/call` at the agent's REPL;
- the second body's user content contains the first pass's form source and its shown reply, and does not contain
  `(+ 1 2)`, `seon.error/for-agent` or `turns left`;
- the second body's user content ends with the shown text of the block (`:seon.context/proofs` with the proof's
  status as recorded after pass 1's step);
- the first body's user content is a prefix of the second's up to the block (provider prefix caching holds).
Existing `seon.cluster.turn-test` and `seon.turn-loop-test` stay green or have their prompt-shape assertions converted
(not deleted) to the new shape.

## Cost (expected per model pass)

Proportional to the agent's evaluations read and the text sent. Measured on the live branch (JVM, 2026-10-08):
`of-agent` 3.3 ms for 38 evaluations; the block 12.8 ms; help 0.16 ms; the exchange text 27-40 ms for 9 exchanges
(17 K characters) — about 0.7 ms per evaluation through `seon.repl/text`, which is 10× what copying stored text
should cost. Report `seon.pass-probe/run-model` time and allocation before and after (expected: the pass loses the
prompt walk, the 350-405 MB owner). If 200 exchanges cost over 100 ms, file the `seon.repl/text` cost as a task on
its owner; do not cache around it.

## Dependencies and order

None. First card. Files: `src/seon/context.clj`, `resources/seon/schemas/seon.context.edn`, `src/seon/turn.clj`,
`test/seon/cluster/turn_test.clj`. Platform slice: `bin/lane commit LANE -m MSG --test seon.cluster.turn-test
--test seon.turn-loop-test --test seon.context-test PATHS`.

## Reset

None.
