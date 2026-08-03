---
type: research
status: complete
tags: [context, render, agent]
---

# Context quality fix proof

## Dependency ledger

- Datahike `9b3be9d59cb07d9c895af280e60eb074bb57a400`; the proof uses the real
  `d/transact`, `d/q`, and `d/pull` paths through `seon.test-support`'s fresh
  in-memory database.
- core.async `dc35f3e0d7bc2eef502e77982f48641f025c8051` is present in the indexed
  `seon.flow` program graph; no flow graph or channel behavior changes here.
- First-party owners are `seon.render.walk/units`,
  `seon.render.ns/render-ai`, `seon.cluster.prompt/prompt`, and
  `seon.context/capture-tx`. Recurring examples are
  `seon.render.walk-test`, `seon.render.ns-test`,
  `seon.cluster.instruction-test`, and `seon.context-capture-test`.

The new-cluster boot issue was still open in the current log, so the allowed
fallback was used: each measurement populated a fresh fixture database with
`seon.test-support/seed-cluster!`, created real agent/run/message facts, and
called the production prompt and render functions. This avoids claiming a
cluster-start proof while exercising the same indexed rows and pure context
path.

## Before and after

The before values are the complete captures in
`visual-qa-2026-07-31.md`. The after values are a single coherent fixture run
at commits `3ffaba05b` and `41cdbc65b`.

| Measure | Before | After |
|---|---:|---:|
| Nursery birth context | 31,307 characters | 5,152 characters / 1,288 estimated tokens |
| Nursery opening elision lines | 42 lines | 1 line |
| Own agent unit | line 101 of 213 | line 4 of 72 |
| `seon.flow` owner birth context | 92,434 characters | 99,495 characters / 24,873 estimated tokens |
| `seon.flow` function sources present | 0 of 48 | 48 of 48 |
| Consecutive capture sizes | 2,310 → 5,290 tokens | 1,288 → 1,288 estimated tokens |
| Previous prompt contained verbatim in next | yes | no |

The larger `seon.flow` result is intentional under inverse detail: the owner
now receives the namespace's complete source, including all 48 definitions.
The relevant quality measure is completeness, not minimizing the owner's own
code.

## Verbatim after excerpts

These excerpts are preserved measurement bytes from the pre-decision-11
renderer. Their `;;` headers and comment-framed instruction text are evidence
of what the proof observed, not current display guidance. Decision 11 requires
future display to show a form followed by its actual value, with notices as
ordinary values and no comment-prefixed results.

The nursery context now opens with one compact frontier followed immediately
by its own entity:

```text
;; (seon.render/walk {:root [:seon.cluster.agent/id "nursery-proof"], :depth 2}) => root=[:seon.cluster.agent/id "nursery-proof"] basis=536870926 depth=2
;; 6 branches elided · 73 tokens · inspect with (seon.render/walk {:root [:seon.cluster.agent/id "nursery-proof"], :depth 3})
;; d0 · seon.render.agent/agent-ai
Agent nursery-proof is running now.
;; d2 · seon.cluster.instruction/instruction-ai
This is a live Clojure REPL. Everything above is the output of `(seon.render/walk)` — run it yourself with `:depth`/`:root` to see more. Your reply is read as forms and evaluated in your namespace. A `defn` with `:malli/schema` becomes permanent; anything else is scratch. Talk to other agents with `(my.message/send "agent-id" "message")`. Prose lines are kept as `;;` comments.
```

The `seon.flow` owner now receives its stored namespace source and member
source in the same distance-1 unit:

```clojure
(ns seon.flow
  "Production-shaped core.async.flow launchers used by the standing testbed.

   This namespace deliberately does not own durable runtime state. Ordinary
   Flow processes retain only disposable counters and handles. Flow channels
   carry scheduling and wake signals."
```

```clojure
(defn- executor?
  [value]
  (instance? Executor value))
```

After committing the first rendered context as a real
`:seon.context.capture`, the second prompt begins:

```text
;; (seon.render/walk {:root [:seon.cluster.agent/id "nursery-proof"], :depth 2}) => root=[:seon.cluster.agent/id "nursery-proof"] basis=536870927 depth=2
;; 7 branches elided · 85 tokens · inspect with (seon.render/walk {:root [:seon.cluster.agent/id "nursery-proof"], :depth 3})
;; d0 · seon.render.agent/agent-ai
Agent nursery-proof is running now.
```

The getting-started sentence occurs once in each turn, and the complete first
prompt is not a substring of the second. The added capture regression also
proves that the one-walk contribution transacts without the retired semantic
band; the original pure call exposed an attempted stored nil there, and the
constructor/schema were corrected before this measurement was accepted.

## Recurring proof

- Namespace totality: 5 tests / 67 assertions.
- Instruction references and arities derived from code spans: 4 tests / 16
  assertions.
- Capture projection and transaction boundary: 2 tests / 9 assertions.
- Walk caps, compact markers, stable ordering, and real transcript-fact
  dedupe: 12 tests / 58 assertions.

After all source files were frozen, the namespace/instruction/capture/prompt
checkpoint passed 14 tests / 106 assertions and the seeded walk checkpoint
passed 12 tests / 58 assertions, both with zero failures and zero errors.
`bin/issues-index --check` reported 42 open notes and 802 archived notes with a
clean index.
