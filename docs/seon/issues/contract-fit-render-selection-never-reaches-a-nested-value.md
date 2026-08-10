---
type: issue
status: open
severity: friction
tags: [issue, render, agent, live-drive]
---

# Decide whether contract fit selects a producer for a nested value

## Problem

Producer selection follows two different rules depending on where the value
sits, and an agent cannot tell which one applies to the function it just
wrote.

- **Top level** — `seon.render/render-ai`, `render-html`, and `render-call`
  reach `producer` (`src/seon/render.clj:203-213`), which tries an explicit
  producer and then **contract fit** via `candidates`
  (`src/seon/render.clj:110-137`): any public function in the value's owning
  namespace whose `:malli/schema` accepts the render argument and whose
  declared return is exactly the output schema.
- **Nested node** — `project-node*` (`src/seon/render.clj:~315`) selects only
  `(get value output)` or `schema-producer`, i.e. a producer **declared on a
  registered schema**. It never consults `candidates`.

An agent's returned value appears inside a print tree in its own session, so
it is rendered by the nested path. A producer written to the contract-fit rule
is therefore inert on the surface the agent can actually see.

Measured on cluster `default`, pid 91415, 2026-08-10. A DeepSeek reply authored
a correct producer for its own summary shape:

```clojure
;; my.agents.root/token-pressure-line, :seon.fn/spec as recorded
[:=> [:cat [:map [:seon.render/value [:map [:turns :int] [:prompt-total :int]
                                            [:completion-total :int] [:ratio :double]]]]]
     :seon.render/ai]
```

Handed the value and the namespace and nothing else, the top-level path selects
it automatically — no explicit producer, no schema declaration:

```clojure
(seon.render/render-ai
  {:seon.sci.eval/ctx ctx
   :seon.render/namespace 'my.agents.root
   :seon.render/value {:turns 2 :prompt-total 400 :completion-total 100 :ratio 0.25}
   :seon.db/db db :seon.render/output :seon.render/ai
   :seon.sci.admit/caps {…} :seon.sci.eval/time-limit-ms 30000
   :seon.config/on-core-error :record})
;; => "Across 2 turns, prompt tokens totaled 400, completion tokens totaled 100,
;;     and the completion/prompt ratio was 0.25."
```

The served page took the other path. `/ns/my.agents.root/debug` renders the same
summary as the bare map `{:turns 2, :prompt-total 400, :completion-total 100,
:ratio 0.25}`, and the sentence appears **zero times** anywhere on the page.
The database agrees: `:seon.render/ai` has 256 datoms, none naming
`token-pressure`, and no `:my.agents.root/*` schema key exists.

The restriction is deliberate, not an oversight — the comment above
`project-node*` records the measured 2026-08-07 render cycle
(`attempt-html → prepare → project-node`, 1024 frames, the proc's transform
never ending) that motivated making the cycle unconstructable. So this note
asks for a ruling, not a revert.

## Wanted

One of:

1. **Extend contract fit to nested nodes**, with the cycle kept
   unconstructable by the existing `:seon.render/rendering` guard — the guard
   already refuses a producer currently on the stack, which is the property
   the 08-07 incident needed;
2. **Keep the nested path schema-only and say so where an agent reads it.** The
   bootstrap instruction tells an agent that "a `defn` with a complete
   `:malli/schema` becomes a durable fact any agent can find and call"; nothing
   tells it that a render producer additionally needs a registered schema to be
   reached from its own session. If registration is the rule, the agent-facing
   guidance and the render documentation should state it and show the
   `schema/register!` that completes the job.

Either way the two paths should be describable in one sentence an agent can act
on, because an agent writing a producer cannot currently predict whether its
work will ever run.

## Acceptance

- a regression that authors a contract-fitting producer in an agent namespace,
  renders a value of that shape **through the path a session transcript uses**,
  and asserts the selected producer's output — not the floor;
- if option 2 is ruled, the same regression asserts the floor is used and the
  agent-facing instruction names the registration step.

## Evidence

- [model-authoring-observer-2026-08-10.md](../../prds/sci-execution-runtime/research/model-authoring-observer-2026-08-10.md)
  — verdict 3, with the selection probe and the page falsifier; raw dump
  `tmp/observer-0810-render.edn`.
