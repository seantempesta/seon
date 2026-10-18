---
title: "mine-a: model turns to green on the kept chains of live runs 7 and 8"
type: evidence
status: "lane mine-a complete 2026-10-18: 7 of 8 corpus chains read in JVM mode on default; ea90 refused by the miner (reproduced); row-level waste is sampled, not exhaustive"
---

# Mine A: turns to green on the kept chains of live runs 7 and 8

Lane mine-a (Haiku), evidence only. No source edited, no task filed, no SCI request on default.

## Method and limits (read first)

- **Read path.** A first attempt opened a separate JVM on a clone of `data/store`. The coordinator stopped it
  (owner rule: no separate JVM unless the work is platform work). The clone was deleted, and every remaining read
  ran through `mcp__seon__eval_clj` in JVM mode on default, with `(seon.cluster.boot/connection "default")`, reading
  `(seon.db/db conn)` and `(seon.mine/situations main branch)`. Nothing was written.
- **Main branch.** The cluster main is the `:cluster-default` branch, not `:db`. `:db` is empty of program data.
- **Corpus.** Run 7: `fddd`, `ce9d`, `42e0`, `91a8`, `fd3b`. Run 8: `6451`, `ea90`, `8600`. All eight appear in
  `seon.mine/kept-branches`. Other kept chains (`8c4f`, `95da`, `ae96`, `d6c6`) belong to later runs and are out of scope.
- **Miner refusal.** `seon.mine/situations` on `:chain-ea90b1a9dc77` refuses with a contract error: `seon.mine/shown-entry`
  output refused at `[:seon.mine/causes 1 :seon.mine/producer]`, offending value `tokens-of`. This matches run 8's Broken 5
  and is the filed task `seon.mine/a-cause-producer-from-an-agent-definition-refuses-the-miner`. `ea90` is therefore
  **not counted** below. Its turn data was not read by hand.
- **Proxy classes.** A turn is counted *wasted* when any form it sent is classed `search`, `re-read`, `repeat`,
  `navigation`, `error`, `unresolved` or `contract-refusal`, or when the miner charges it waste. Reads, probes and
  progress count as productive. This is mechanical. It over-counts a multi-form turn that also moved forward, and a
  read of a new subject is never waste. Judgement per turn is marked "unsure" where it matters.
- **Turns to green.** `seon.task.attempt/end` is `:done` for `fddd` only. Every other chain ended capped at 40 calls.
  `seon.competency/grade` and `seon.mine/grade` did not resolve on default, so grade rows are not used.

## Per chain

| chain | task | turns (calls) | productive | wasted (proxy) | turns to green |
|---|---|---|---|---|---|
| `fddd9dcbfe66` | `seon.background/render-ai-returns-data` | 35 | 13 | 22 | **35** (done, merged; the proof green at call 35) |
| `ce9d664c1be0` | `seon.effect/render-ai-returns-data` | 40 | 11 | 29 | capped (proof green, done refused, a red reaching test) |
| `42e0e4c46b71` | `seon.error/elision-prose-returns-data` | 40 | 15 | 25 | capped |
| `91a8e6815ef2` | `seon.render.transcript/render-run-ai-returns-data` | 40 | 6 | 34 | capped, no render written |
| `fd3ba8395135` | `seon.repl/render-ai-returns-data` | 40 | 9 | 31 | capped |
| `6451a0172cc8` | fork point walked per request | 40 | 14 | 26 | capped (proof red) |
| `ea90b1a9dc77` | unresolved-symbol reply | n/a | n/a | n/a | not counted (miner refusal) |
| `8600111a2d59` | root seed from source | 40 | 16 | 24 | capped (nothing written) |

Corpus total (seven chains): 275 turns, 94 productive, 181 wasted by the proxy.

## Wasted turns: examples read by hand (sample, not exhaustive)

Each row is a turn I read in the dump. Excerpts are under 200 characters.

| chain | turn | form (first, truncated) | class | producer | evidence |
|---|---|---|---|---|---|
| fddd | 1 | `(in-ns 'seon.background)` | navigation, redundant after the current-state block already names the namespace (unsure) | `seon.context/current-state` | the block was shown at call 0 |
| fddd | 5-6 | `(text-render/with-ai-face ...)` then `(seon.text-render-support/with-ai-face ...)` | unresolved alias guess | a missing fact: the alias `text-render` is not in the context | unresolved symbol |
| fddd | 9 | `(require 'seon.text-render-support)` | contract refusal on its first call | a missing fact: which namespace owns `with-ai-face` (no reference) | refused call shape |
| fddd | 13 | `(seon.db/q '[:find ?e ?a ?v ...])` | whole-store scan, 1.2 s and 2.2 GB (run 7 Broken 7) | a tool reply that offers no narrower search; unsure whether the agent or the help line led it here | repeated in a later run |
| fddd | 14 | `(my.program/references 'seon.background/render-ai)` | unresolved, returns no references | a tool reply: `references` returns nothing for the render (unsure) | unresolved |
| ce9d | 5-9 | `(in-ns 'seon.effect)` repeated | redundant navigation after entering the namespace | `seon.context/current-state` (the namespace is shown) | 29 redundant `in-ns` turns in the chain |
| ce9d | 28-29 | `(my.program/search :seon.schema/definition [:or :nil ...])` | contract refusal of a multi-argument search | a tool reply: `my.program/search` arity not shown in help (unsure) | refused call shape |
| 42e0 | 10 | `(require '[seon.render-bench :as bench])` | four errors in one turn | a missing fact: `seon.render-bench` lives in `script/`, not on the agent's classpath (unsure) | four repeated errors |
| 42e0 | 27-30 | `(my.fs/section "test/seon/error_test.clj")` repeated | repeat of a read already shown | the read had already returned the section; the reply was not used (a tool reply cost) | identical forms in consecutive turns |
| 91a8 | 0-21 | `(my.program/source 'seon.render.transcript/...)` re-read 9 times | re-read of shown source | `my.program/render-source-ai` (the render that showed it; miner charges 36 re-reads over the corpus) | repeated reads of one namespace |
| 91a8 | 7 | `(my.fs/source "src/seon/render/transcript.clj")` | unresolved name | a guessed API (`my.fs/source` is not a verb; `my.fs/section` is) | unresolved |
| 91a8 | 34-38 | `(support/with-database!)`, then `proof-probe` | unresolved and repeated-error probes | a missing fact: `seon.test-support` is not required in `seon.render.transcript-test` (unsure) | three errors in two turns |
| fd3b | 0 | `(in-ns 'seon.repl)` | three errors on the first turn | a missing fact: the namespace is reached by `in-ns` without a require (unsure) | `E3` on call 0 |
| fd3b | 17-18 | a map literal sent as a form | unresolved, then error | a render's output pasted back as code (`:seon.repl/response <data ...>`), so the printed value was read as a form | the printer's elided form reached the reader |
| fd3b | 25-38 | `(in-ns 'seon.repl)` / `(in-ns 'user)` alternation | redundant navigation, about 14 turns | `seon.context/current-state` plus no step shows the namespace after a switch (unsure) | alternating `in-ns` |
| 6451 | 5, 11, 23 | `(in-ns 'seon.cluster.registry)` and redundant `in-ns` | redundant navigation | `seon.context/current-state` | consecutive identical `in-ns` |
| 6451 | 25-27 | `(my.program/ns-unmap! 'seon.cluster.registry/db)` then `fork-base-t` error | progress that repeats an error (guessing a helper's contract) | a missing fact: the nilable-return contract is not named (run 8 help: `nilable-return` fix now in help) | `E1` then unmapped again |
| 6451 | 35-39 | `(my.program/source 'seon.cluster.registry/fork-point)` repeated | re-read of the same source five times in the final turns | `my.program/render-source-ai` | identical forms |
| 8600 | 0-13 | `(my.program/source 'seon.cluster...)` chain | read-chain of 13 turns without a write | a missing fact: which function writes the `my.agents.root` namespace row (run 8 Broken 9; the plan now names it) | searches for `my.agents.root` at turns 4-8 |
| 8600 | 30 | `(my.program/search "namespace-seed-call")` | repeat search after reading the same name | `my.program/search` (unsure) | identical form at turns 14 and 31 |
| 8600 | 34-38 | `(seon.test-support/with-database ...)` and `(in-ns 'user)` | error then navigation, then `ns-unmap!` of its own probe | a guessed fixture entry; the `support` alias is not in the block | `E2` at turn 34 |

Rows for the remaining wasted turns of each chain are not written here. The mechanical list is reproducible in JVM mode
on default with `(seon.mine/situations main :chain-...)` and the proxy above.

## Producer ranking (summed over the seven chains)

Mechanical, from the miner's first waste row per wasted turn, plus the unattributed turns (`shown-class`):

| rank | producer | wasted turns | note |
|---|---|---|---|
| 1 | unattributed search, error, navigation and unresolved turns (no miner charge) | 118 | mostly the agent's own probes; a producer split needs the form-level read above (unsure) |
| 2 | `my.program/render-source-ai` (re-read of shown source) | 36 | re-reads of source the agent already had; the first source reply is the producer |
| 3 | `seon.context/current-state` (redundant `in-ns`) | 29 | the block already names the namespace; the agent re-enters it anyway (unsure whether the block shows the namespace clearly) |
| 4 | `seon.turn.batch/form-facts` (repeated error) | 4 | |
| 5 | `seon.render.value/show` (re-read) | 2 | |
| 6 | `:error` (repeats an error) | 2 | |

Top two are the sizeable levers: repeated source reads and redundant namespace entry. Both are context producers,
not model judgement. The top rank is unsure and needs a form-level split before anyone acts on it.

## Items for root to verify

- The `seon.mine` refusal on `ea90` reproduces on default today, so run 8's Broken 5 is still live.
- The `fddd` turn-35 green matches the run 7 note (done at call 35, merged).
- Not verified: whether `seon.context/current-state` shows the namespace clearly enough to stop `in-ns` repeats.
- Not verified: the `my.program/search` arity refusal (ce9d 29) against the help line.
