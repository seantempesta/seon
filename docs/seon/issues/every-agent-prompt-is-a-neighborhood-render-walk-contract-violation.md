---
type: issue
status: open
severity: blocker
tags: [issue, render, context, runtime, agent]
---

# Every agent prompt is a neighborhood render-walk contract violation

## Problem

On cluster `default` (pid 14148, booted 2026-08-08T11:49:31Z) every agent turn
receives, as its ENTIRE prompt, a `seon.render.walk/neighborhood`
`invalid-output` contract-violation error. The agent's REPL session,
instructions, and the human's message never reach the model — the render that
would assemble them fails its own output contract and collapses to the error
text.

`seon.render.walk/neighborhood`'s output contract requires each
`:seon.render/output` item to be tagged `{:seon.render/ai …}` or
`{:seon.render/html …}`. The producers hand it BARE values instead:
`seon.render.agent/agent-ai` (`src/seon/render/agent.clj:17-34`) returns a plain
`String` ("Agent root is running now."), and the session/transcript composition
that carries the real REPL preamble reaches the walk as a bare string too. The
validator wraps each bad item as `{:value "…" :message "should be either
:seon.render/ai or :seon.render/html"}`, the node fails `invalid-output`, and the
whole neighborhood collapses.

The effect is total: no agent can be given a task, so nothing downstream of
context assembly (authoring, message flow, receipts, the whole arc) can run. It
also drives a paid self-waking loop — each broken turn's failure re-wakes the
agent, which gets the same broken prompt and burns another ~14.5k-token
completion (see
[a-failed-turn-wakes-itself-through-its-own-fault-message.md](a-failed-turn-wakes-itself-through-its-own-fault-message.md)).

This regressed after the morning fix: the predecessor observer
([whole-system-arc-observer-2026-08-08.md](../../prds/sci-execution-runtime/research/whole-system-arc-observer-2026-08-08.md))
reported real REPL sessions reaching agents on pid 31475. Render-walk churn since
(`d4ac2ba40`, `88ecc7167`, `8872311d1`, `9fa48fa20`) reintroduced the collapse.

## Evidence

The durable prompt fact `:seon.context.capture/prompt` for every one of the four
captured runs is 931 characters (`:seon.ai.tokens/characters` = 931 for all four;
`captures-all-931? => true`). Verbatim, complete:

```text
;; (seon.render/walk) => error
Walk failed: seon.render.walk/neighborhood violated its contract (invalid-output): [#:seon.render{:output [{:value "Agent root is running now.\nmy.agents.root=> (help)\nYou are an agent in a Seon cluster — a real Clojure REPL, and it is\nyours. Your reply is read as forms and evaluated in order in your own\nnamespace; each form and its actual value come back as this session…", :message "should be either :seon.render/ai or :seon.render/html"}]} #:seon.render{:output [{:value "elided connections at the requested distance cap", :message "should be either :seon.render/ai or :seon.render/html"}]} #:seon.render{:output [{:value "elided connections at the requested distance cap", :message "should be either :seon.render/ai or :seon.render/html"}]} #:seon.render{:output [… 1 more subtree; requery refused: no stable identity was supplied at path [] offset 0 with :seon.render.profile/unspecified]}]
```

Reproduced live through the web route: `GET /ns/my.agents.root/debug` → 200,
3040 bytes, showing the identical `neighborhood violated its contract
(invalid-output)` text. So the failure is current, not a stale capture.

Downstream, observed 2026-08-08 11:52–11:58Z: root was sent a message to define
a contracted `word-count`; across five runs and four provider attempts it never
defined it — every reply is prose explaining/debugging the render error, because
that error is the only thing in its prompt.

## Wanted behavior

The neighborhood walk assembles the agent's context from its block producers
WITHOUT collapsing when a producer returns a bare string:

- either the producers/composition tag every `:seon.render/output` item as
  `{:seon.render/ai …}`/`{:seon.render/html …}` at the one point strings become
  output (so an untagged string is unrepresentable), OR the walk's own contract
  admits the producer's declared default shape;
- a single producer failure degrades ONLY its own block, never the whole
  neighborhood — the prompt still carries the instruction and the rest of the
  session;
- the agent's prompt is its REPL session + instructions + delivered messages,
  never a render-internal contract error.

## Acceptance

- Boot `default`, send an agent a one-line instruction, and assert its
  `:seon.context.capture/prompt` contains that instruction and the REPL preamble
  (not a `neighborhood … invalid-output` string).
- One regression that makes the class unrepresentable: a neighborhood render
  whose one producer returns a bare string still yields a well-formed prompt for
  the rest of the walk. A green test that constructs the bare-string producer and
  asserts the surviving blocks render.
</content>
