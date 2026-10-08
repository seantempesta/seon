---
title: "Recording cards: index and owner questions"
type: reference
status: active
created: 2026-10-08
---

# Recording cards (lane cards-recording)

The cards for the pass-and-recording rows (RP), the entrants rows (EN, S5-05, S5-06/EN-3), the S6 rows this lane owns
(S6-05, S6-07a, S6-07b) and the transaction rows (TX) the census fold-in adds. Design authority:
`docs/research/agent-platform/2026-10-08/redo/pass-and-recording.md` (§2.1 is the transaction-metadata fold-in) and
`redo/entrants.md`, both approved by the owner on 2026-10-08. Cited at HEAD `e7f120672` unless a card says otherwise.

## Answered alias question

**Q-R1, answered:** reloading an earlier definition reads its stored source under the new alias, as a Clojure
file reload would (owner-rulings §1; agent-loop.md §3.3).
Example: `(ns a (:require [clojure.string :as s])) (defn g [] (s/join [1]))`, then
`(ns-unalias *ns* 's)` and `(require '[clojure.set :as s])`. FK-SCI restores Clojure's refusal of a silent retarget.
Before reload, `g` keeps calling `clojure.string/join`. The stored source says `(s/join [1])` and the final
namespace row says `s` → `clojure.set`. After reload, calling `g` fails: `clojure.set/join` exists, but accepts
two or three arguments, not the one supplied here.
Clojure's rule applies. RP-8's `:alias-retargeted` finding warns the agent, and merge refuses the broken
definition. No extra stored aliases or rewriting of authored source. Name the reaching reload and merge
proofs before launching RP-8.

## Out-of-scope findings recorded while carding
- **Every agent branch forked before a new required dial is bricked.** Task
  `seon.config/an-agent-branch-forked-before-a-new-required-dial-refuses-every-eval-request`
  (`resources/seon/tasks/seon.config.edn`): after root applied `:seon.config.db/store-cache-size` and
  `:seon.config.operator/heap-max-bytes` at 07:02, agent `cards-recording`'s every MCP eval request (SCI and JVM)
  failed with "Effective configuration requires a matching cluster entity with every required dial", and the reply
  dropped the error's `:seon.error/data` naming the missing dials.
- **`register!` costs 384-855 ms and ~32,400 armed calls per key** (measured for RP-4): existing task
  `seon.schema/schema-registration-rechecks-the-whole-program-per-key`.
- **A malformed contract drops its function from the program** (P4 kept it callable only): new card RP-6e.
- **The SCI recording is named after the run-open intent** (`seon.db/absent-call`) and the JVM recording carries no
  agent: TX-4.
- **An uncalled deletion and a dangling deletion behave inversely** (RED-1, `4e307ec54`): folded into RP-1.

## Cards
| Row | Card | Ready when |
|---|---|---|
| TX-0 | [TX-0](TX-0.md) | now (db.clj free) |
| TX-1 | [TX-1](TX-1.md) | now |
| TX-2 | [TX-2](TX-2.md) | after TX-1a |
| TX-3 | [TX-3](TX-3.md) | TX-3a now; TX-3b after S5-05 |
| TX-4 | [TX-4](TX-4.md) | after TX-0 |
| TX-5 | [TX-5](TX-5.md) | now |
| TX-6 | [TX-6](TX-6.md) | TX-6a inside MA-11; TX-6b now |
| TX-7 | [TX-7](TX-7.md) | TX-7a after TX-5; TX-7b after TX-0 |
| TX-8 | [TX-8](TX-8.md) | TX-8a after TX-0, EN-1; TX-8b after RP-14, RP-15d; TX-8c after S4-01, S4-03 |
| RP-1w | [RP-1w](RP-1w.md) | now |
| RP-1 | [RP-1](RP-1.md) | after RP-1w |
| RP-2 | [RP-2](RP-2.md) | now (fn.clj free) |
| RP-3 | [RP-3](RP-3.md) | after RP-2 |
| RP-4 | [RP-4](RP-4.md) | after RP-3 |
| RP-5 | [RP-5](RP-5.md) | after RP-2, RP-3 |
| RP-6a | [RP-6a](RP-6a.md) | after RP-1, RP-4, RP-5, EN-8a |
| RP-6b | [RP-6b](RP-6b.md) | after RP-6a |
| RP-6c | [RP-6c](RP-6c.md) | after RP-6a, RP-1 |
| RP-6d | [RP-6d](RP-6d.md) | after RP-6a |
| RP-6e | [RP-6e](RP-6e.md) | after RP-6d |
| RP-7 | [RP-7](RP-7.md) | after RP-6a, RP-6b, RP-11c |
| RP-8 | [RP-8](RP-8.md) | after RP-6a; name the answered reload/merge proofs before launch |
| RP-9 | [RP-9](RP-9.md) | after RP-6a, RP-6d |
| RP-10 | [RP-10](RP-10.md) | now (TX-4 first makes its proof exact) |
| RP-11a | [RP-11a](RP-11a.md) | after RP-10, EN-8a |
| RP-11b | [RP-11b](RP-11b.md) | after RP-11a, RP-7 |
| RP-11c | [RP-11c](RP-11c.md) | after RP-11b, S4-02b |
| RP-12 | [RP-12](RP-12.md) | after RP-11c, RP-7, SR-H, SR-I |
| RP-13 | [RP-13](RP-13.md) | after RP-11a |
| RP-14 | [RP-14](RP-14.md) | after RP-10, TX-0, EN-1, EN-5, EN-8b; RP-14a also after S6-05 |
| RP-15a | [RP-15a](RP-15a.md) | after RP-14, EN-1 |
| RP-15b-d | [RP-15b](RP-15b.md), [RP-15c](RP-15c.md), [RP-15d](RP-15d.md) | in order after RP-15a |
| RP-16 | [RP-16](RP-16.md) | after RP-11b |
| RP-17a-b | [RP-17a](RP-17a.md), [RP-17b](RP-17b.md) | after RP-11c, RP-6a; RP-17b with UL-1d |
| EN-0 | [EN-0](EN-0.md) | now |
| EN-1 | [EN-1](EN-1.md) | after TX-0 |
| EN-2 | [EN-2](EN-2.md) | now |
| EN-3 (S5-06) | [EN-3](EN-3.md) | now |
| EN-4 | [EN-4](EN-4.md) | after EN-1 |
| EN-5 | [EN-5](EN-5.md) | after EN-4 |
| EN-6 | [EN-6](EN-6.md) | after EN-4, TX-0, EN-1 |
| EN-7 | [EN-7](EN-7.md) | after TX-5 |
| EN-8a | [EN-8a](EN-8a.md) | after EN-2, TX-4 |
| EN-8b | [EN-8b](EN-8b.md) | after EN-8a, RP-7, RP-10, RP-11b, S5-05 |
| EN-9 | [EN-9](EN-9.md) | after EN-6, EN-8b |
| S5-05 | [S5-05](S5-05.md) | after S5-01, E-SREADS |
| S6-05 | [S6-05](S6-05.md) | after TX-0, EN-2 (no longer after RP-15a) |
| S6-07a | [S6-07a](S6-07a.md) | after S6-05 |
| S6-07b | [S6-07b](S6-07b.md) | after S6-07a |

## Common to every card
- Proofs go through `seon.mcp/call` (`src/seon/mcp.clj:383`) in the `seon.repl-differential-p3-test` idiom (a private
  `reply` over `support/with-running-agent`, `test/seon/test_support.clj:1194`, read back with `support/reply`
  `:757`), or through the turn-test fixture (`support/code-request` + `seon.batch/evaluate-batch`) where one MCP call
  cannot arrange the state.
- `test/seon/repl_differential_p8_test.clj` is created by RP-3; later RP rows add deftests to it.
- External effects in tests (a refusing writer, a fixture provider, a blocked host call) are injected as values, never
  by redefining a Var.
- Before commit: `clj-kondo --lint src script test --cache false`, zero findings; `bin/lane commit` runs the check.
