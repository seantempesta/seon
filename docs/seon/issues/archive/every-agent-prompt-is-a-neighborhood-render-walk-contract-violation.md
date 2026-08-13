---
type: issue
status: resolved
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

## Root cause pinned, and the proposed fix corrected (model-authoring re-drive, 2026-08-08)

Independent re-drive on the same cluster (`default`, pid 14148) reproduced this
and located the exact cause. It is NOT (only) that producers return bare strings —
it is a **shared globally-identified key with two meanings**, tightened on one side.

- Commit `102fdeac3` ("Make seon.render.edn editable…") retyped the shared key
  `:seon.render/output` from `:any` to `[:enum :seon.render/ai :seon.render/html]`,
  correctly, because it IS the projection **selector** in the request map
  (`resources/seon/schemas/seon.render.walk.edn:29-30`, the `:request` key).
- But `:seon.render.walk/unit` reuses that SAME key for each unit's rendered
  **output field** (`seon.render.walk.edn:55-57`), and that field holds the actual
  rendered value — a `String` for AI, Hiccup for HTML — assigned at
  `src/seon/render/walk.clj:462` (`:seon.render/output rendered-output`),
  `:455` (`failure-output`), and `:323` (elision bare string).
- So every unit's rendered output is now validated against a two-keyword enum and
  fails, because rendered text can never equal the literal keyword `:seon.render/ai`.

**The failure is universal, not producer-specific.** Falsified live: walking the
healthy cluster entity at distance 0 —

```clojure
(neighborhood {… :seon.render.walk/lookup [:seon.cluster/name "default"]
                 :seon.render/distance 0})
;; invalid-output: [#:seon.render{:output
;;   [{:value "Cluster default.\nConfiguration default and bootstrap plan :default;
;;             1 shared instruction and 7 toolkit namespaces.",
;;     :message "should be either :seon.render/ai or :seon.render/html"}]}]
```

A perfectly well-formed AI render string fails the contract. There is no producer
bug here — the value is correct; the field's declared type is wrong.

**Therefore the "Wanted behavior" option "producers tag every item as
`{:seon.render/ai …}`/`{:seon.render/html …}`" WILL NOT fix this** — a
`{:seon.render/ai "…"}` map fails `[:enum :seon.render/ai :seon.render/html]` exactly
as a bare string does. Only the literal keyword passes. The fix must **split the
shared key**: give the unit's rendered-output field its own value type (a
string-or-Hiccup rendered-value schema, e.g. what `:seon.render/ai` / `:seon.render/html`
already are: `[:or :qualified-symbol :string]` and `[:or :qualified-symbol
:seon.render/hiccup]`), distinct from the request's `:seon.render/output` selector.
This is a schema-EDN + re-registration change (not hot-reloadable) and a render-
contract design decision — owner/render-owner call, not a lane one-liner.

### Live impact this re-drive (facts only, cluster `default`, 11:52–12:07Z)

- A human message (`inbound-536871012-0`, 25876) asked root to define a contracted
  `word-count` and complete. It was claimed by run `fe68fac3` (25909),
  `:seon.cluster.run/trigger` = 25876 — but that run's provider prompt was
  **336 tokens** of the render-contract error, not the message, so root produced
  only render-fix prose.
- Six paid provider attempts across four root turns (25872/25891/25909/…), each a
  ~336-token error prompt, each burning 10–14k reasoning tokens; `word-count` never
  appears in `:seon.fn/sym`; **0 receipts** cluster-wide; still only the one agent.
- The prompt collapse also drives the paid self-waking loop of
  [a-failed-turn-wakes-itself-through-its-own-fault-message.md](a-failed-turn-wakes-itself-through-its-own-fault-message.md):
  `renderer-failure` (`src/seon/render.clj:479-519`) messages the namespace owner
  "A renderer in <ns> failed… repair its declared contract", and root owns
  `my.agents.root`, so each collapse re-wakes root with another broken prompt.
</content>

## Resolution

Resolved before this lane by `80ae69ad1`, which split the request selector
`:seon.render/output` from the walk unit's rendered value
`:seon.render/rendered`. The current schema declares the latter as text,
Hiccup, or form, and the focused render-neighborhood tests now pass producer
strings without the former enum violation. N1 re-verification found no
remaining occurrence of this overloaded field.
