---
type: research
status: active
tags: [research, live-test, turn, deepseek, context]
---

# Live run 2 — the §17 scenario on deepseek-flash, self-continuing loop (2026-09-10 15:22–15:27)

Recorded by the orchestrator from `default` (pid 23557) through MCP JVM
evaluation; raw captures in `tmp/live-run-2-facts.edn` (disposable).

## Setup

- Fixture `eaed5a41b` seeded with the provider ON:
  `(juniper-fixture-2026-09-06/install! "default" #(dissoc % :seon.config.ai/no-provider))`
  (settings after seed: `time-limit-ms 10000`, `max-episode-runs 30`, no
  `no-provider`); opening = 9 evaluations; root's message `9b81e22c` in the
  inbox. Loop continuation `57f1a8c23` adopted (schema `:seon.turn/disposition`
  installed, `continuing-reply?` resolvable). Two lanes were still landing
  notes; no source edits during the run.
- Model `deepseek-flash`, thinking `:disabled`, max-tokens 65,536.

## Numbers

| | |
|---|---|
| wall | 4 min 41 s (21:22:36 → 21:27:17 UTC) |
| turns | 61 (30 provider, 31 system) |
| evaluations stored | 137 |
| prompt at the end | 177,889 bytes; last attempt 63,514 prompt tokens |
| tokens | 954,877 prompt (890,752 cache hits) / 7,259 completion |
| steps completed | read, define, test, save (4/7); the order of 40 WAS added in the last provider turn but `add` was not marked; no report, no `(my.agent/done)` |
| durable facts made | `my.agents.juniper/largest-customer` with `:seon.fn/spec`; one `deftest` run 1/0/0; a `:my.note`; order `a3` Ada 40 |

## What worked

- The session continued by itself after each accepted reply (issue
  `the-loop-stops-after-one-accepted-reply` is dead).
- The model read the orders, inspected `(dir my.plan)` after guessing a
  wrong name, called `(my.plan/complete! {:my.plan.item/id "juniper/read"})`,
  defined the contracted function, found the right test macro, ran
  `(my.test/run)`, saved a note, added the order — every capability of §17
  was exercised once.
- Reader rule §18b refused a fabricated `#:seon.repl` response in turn 1
  of the first run; contract auto-check reported a violation on the defn
  and the model reasoned about it.

## What went wrong, ranked

1. **System since-diff turns re-read self-churning data after EVERY
   provider turn, and the model cannot tell them from its own forms.**
   `(seon.agent/effective-settings)` (turns-left changes every turn) and the
   runtime pull (the turns list changes every turn) were re-evaluated in 31
   system turns, 458 bytes each, printed as `my.agents.juniper=>` forms.
   The model read them as its own repeated actions: "I have been repeating
   the same introspection pulls every turn (effective-settings, runtime
   pull, dir) and burning turns without progress. I must stop that pattern"
   (turn `a51f8821e5be`, and six similar replies). Roughly a third of the
   thirty provider turns were spent on this confusion. One system turn
   (`8ed117b81926`, after a lane's adoption changed program facts) re-ran
   help, inbox, settings, dir, runtime, the orders query, and `(dir my.plan)`
   in one 819-byte block.
2. **Fenced code is read as prose.** Replies wrapped in ```` ```clojure ````
   (turns `94f7a885645b`, `e6628e29a42b`, `59cf96d21042`) produced no
   evaluations; the reader raised `:seon.cluster.reply/no-forms` — as a
   CORE fault to root ("reached 3 occurrences … will not message you"),
   not as a flat error the agent sees. The agent learned nothing from
   those turns.
3. **`(dir my.agents.juniper)` returns `:functions []` after the defn**,
   three times, while a query found the `:seon.fn` row with `:seon.fn/spec`.
   The model concluded "the defn did NOT persist" and spent turns
   re-verifying.
4. **Platform faults woke and interrupted turns.** Feed backstops
   (`:seon.await/backstop-fired`, twice) were attributed to Juniper and
   root; root's own virtual turn `0bd07d8e06f4` hit the 30 s
   turn-completion backstop; the first run's turn 2 was woken by a feed
   fault. Issue `debug-feed-backstop-fault-wakes-the-viewed-agent`.
5. **Prompt growth**: 9 → 137 evaluations, 63k tokens by turn 30. Additive
   context is the design; the churn in (1) is most of the growth.

## Exact bytes worth keeping

Turn 17 reply (prose + fence, zero evaluations):

```
I see two things happened. Let me re-examine the state:

1. My `largest-customer` defn appears to have evaluated (`#'my.agents.juniper/largest-customer`) but the auto-check reported a contract violation, and subsequent `(dir my.agents.juniper)` shows `:functions []` — so the defn did NOT persist. …
```clojure
;; I should verify whether largest-customer persisted …
(largest-customer [{:example/order "a1" :example/customer "Ada" :example/amount 60}])
```
```

The last provider turn:

```
;; I'm out of turns (turns-left showed 1 before that last query). The order was added.
;; Remaining steps (again, report) can't be completed, but the core task is done:
;; Ada now has 60+55+40=155, still the largest customer. But I can't call the fn
;; or send the message with no turns left.
```
