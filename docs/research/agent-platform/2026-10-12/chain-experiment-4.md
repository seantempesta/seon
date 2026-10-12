---
title: "Chain experiment 4: a single Implement step on a web-server task stops short of a merge request"
type: research
status: complete (lane chain-4, 2026-10-12); no merge request; the task is left :held on default
created: 2026-10-12
---

# Chain experiment 4

**Result.** The shortest chain, one Implement step (the subject task itself, which already carried its red proof), ran
on default (archive **46a1c4f06**, DeepSeek flash) on `seon.render.web/the-web-worker-executor-is-never-shut-down` and
**did not reach a merge request**. Five attempts, 69 model calls, 884k prompt tokens (661k cached, 75 %), 18k completion,
**16 hand acts after launch** (config excluded). The code was written: attempt 4 redefined `seon.render.web/start!` and
`seon.render.web/stop!` correctly in 3 calls, and that code turns the proof green in a compiled check (below). No
attempt ended `:done`, because each attempt ran into a different platform wall:

1. A schema form the writer admitted broke every later read on the branch, and attempt 1 hung with no end (J1).
2. A contract carrying a qualified property key was refused by the writer's digest check (J3).
3. A `:fn` schema over a predicate defined on the branch was refused (J4).
4. Registering the descriptor schema after redefining `stop!` was refused for `stop!`'s shipped nilable return (J5).
5. The proof's fixture cannot run interpreted, so no test step on a branch could turn it green (J8).

Each relaunch also needed root by hand (G6, G8). The task was a poor fit for the shortest chain: it needs a schema
change, and the schema change is what a skipped Design step decides.

Authorities: AGENTS.md "The goal", [TASK-CHAIN](../../../seon/schedule/cards/TASK-CHAIN.md) §2-§3,
[chain experiment 3](chain-experiment-3.md) (H1-H6, G6-G8), [chain experiment 2](chain-experiment-2.md) (G1-G12),
[chain queue](chain-queue.md) (candidate 1).

## For root: no merge request; the diff to review

| | |
|---|---|
| Merge request | **none**. No attempt ended `:done`, and `seon.task/request-merge!` was never reached. |
| The code | Attempt 4 (`:attempt-b279f729d49b`, end commit `6accaeae-9b8b-5e13-bdbd-c8e71200e49f`) wrote it. The patch is [chain-4-web-executor.patch](chain-4-web-executor.patch) against HEAD's `src/seon/render/web.clj`. `start!`'s answer gains `:seon.render.web/executor workers`. `stop!` destructures `executor :seon.render.web/executor`; after the server-thread and feed-writer joins, it calls `.shutdown` and awaits `.awaitTermination` under `backstop-ms` as a third exit fact (`::worker-executor`). Both contracts are unchanged. |
| Compiled proof | `my.test/check-platform {:seon.test/identities #{'seon.render.web-test/a-stopped-server-has-shut-down-its-worker-executor}}` ran from a fresh lane branch holding exactly attempt 4's two definitions: **green**, 3 assertions passed, 0 failed, check 65.3 s (68.2 s total), candidate git sha `663ac9bda`. On HEAD it is red: `bin/seon check --at HEAD` failed 2 of 3 assertions (descriptor `nil`, `isShutdown` false), 4.45 s test, 96 s total. |
| Not done | (a) The schema: `:seon.render.web/server` still lacks `[:seon.render.web/executor :seon.flow/executor]`. The task intent was refined to name that reuse (no new predicate). It is open-map safe today, but the intent asks for it. (b) No reaching test ran compiled. Interpreted, the 49 tests reaching `start!`/`stop!` took 100.5 s: 10 failed and 15 errored, all of them fixtures SCI cannot run (J8). Root should run `seon.render.web-test` through `bin/seon check` with the patch before merging. |
| Review notes | (1) The shutdown sits inside the `or` after the two joins, so a server-thread or feed-writer timeout returns before the executor is shut down. Moving it to `finally`, or before the `or`, shuts it down on every path. (2) `(future (.awaitTermination ...))` uses Clojure's agent pool to wait. Its `false` (not terminated within the bound) still reads as an exit to `await-exit!`. |

## Setup

- **Runtime.** Default runs archive 46a1c4f06. Health `:unknown` before the run (untested-hot warnings only), cache
  207/512 MB, heap 3.1/4 GB. Never stopped or reset. Other lanes (reach-incr among them) ran agent turns throughout: the
  JVM window allocated 30-150 GB per minute, with GC up to 78 s per window.
- **Proof verified first.** Compiled, red as stated (above). Interpreted on a probe branch, `my.test/check` **errors**
  instead: `Cannot interpret seon.render.web-test/with-server: class clojure.lang.Symbol cannot be cast to class
  clojure.lang.Compiler$LocalBinding` (7.3 s, 13 GB). `with-server` is `:seon.fn/host-bound? true`.
- **Tree.** No new task: the subject is a leaf with its proof. One transaction wrote `:seon.task/instructions` (770
  characters, kept under the H1 budget) and `:seon.task/execution :running`. The instructions named the change, said to
  redefine whole `defn`s, said to prove with `my.test/check-platform` (the fixture is host-bound), and said "root adds
  the schema key at merge".
- **Config** `seon.config/apply!`: `:seon.config.ai/no-provider false`, `:seon.config.run/max-episode-runs 15`. Restored
  to `true` and `100` afterwards (verified with `seon.config/effective`).

## Timeline (UTC)

| Time | Event |
|---|---|
| 09:39:5x | launch; db<->flow itself started attempt 1 `ee6cfaeb5c0b`; first form 09:40:17 |
| 09:40:30-53 | Attempt 1 tried `seon.schema/register! :seon.render.web/executor` four ways, all refused (fn objects in the form, J4), then registered `:seon.render.web/server` with the member `[:seon.render.web/executor seon.render.web/executor?]`, a bare symbol. **Admitted.** From then on every declared read on its branch refused `:malli.core/invalid-schema` (J1) |
| 09:40:53-09:44:41 | **wedged**: no open turn, no end, no recorded error; `seon.turn/next-agent-work` → nil (10 calls) |
| 09:44:41 | **hand act 2**: end → `:sent-back`, reason: the wedge, "register no schema". No relaunch after 5 s |
| 09:45:3x | **hand act 3**: hand db<->flow pass (263 ms) started attempt 2 `88ffa0a88cf2` |
| 09:45:41-09:46:45 | Attempt 2 spent 15 calls on schemas: guessed names, `register!` refused (J4), and two `defn executor?` refused by the digest check (J3); `:capped`, end left on its branch (G8) |
| 09:48:42-09:49:02 | **hand acts 4-7**: land pass; intent refined (reuse `:seon.flow/executor`); end → `:sent-back` with reason; pass started attempt 3 `4438ded31ef2` |
| 09:49:08 | Attempt 3's batch (redefine `stop!`, `start!` and register the server schema) returned `Not settled: this run's settlement was refused … seon.render.web/stop! has a bare nilable return` on every form (J5) |
| 09:50:38 | Attempt 3 admitted `start!` with the key, and a placeholder `stop!` `(defn stop! "..." {…} [ ... ] ...)`; `:capped` at 15 calls |
| 09:51:41-56 | **hand acts 8-10**: land pass; root's relaunch facts written (`:seon.task.attempt/relaunch`, `:relaunch-fork` = attempt 3's end commit); a pass did nothing, because no code at HEAD reads them (G6) |
| 09:52:08 | **hand acts 11-12**: end → `:sent-back` with reason; pass started attempt 4 `b279f729d49b` (forks main, so attempt 3's `start!` was lost) |
| 09:52:22 | Attempt 4 redefined both functions correctly (3 calls, both admitted) |
| 09:52:54-09:54:43 | its `my.test/check {:seon.test/changed [start! stop!]}`: 49 tests, 100.5 s, pass 282, fail 10, error 15 (J8) |
| 09:54:49-55:52 | then the schema: `register!` refused for `stop!`'s nilable return (J5), an arity error, two DSML replies (F7); `:stalled` at 14 calls |
| 09:57:27 | **hand acts 13-15**: land pass; end → `:sent-back` with a three-step reason (start!, stop!, check-platform; never `register!`); pass started attempt 5 `69691a73d375` |
| 09:57:34-58:11 | Attempt 5 redefined `start!`, registered the schema first and then `stop!`: all admitted (the order J5 allows) |
| 09:58:33-59:17 | It then called `(seon.test/run 'sym)` (refused), evaluated `:done` and `:ready` as forms and called the deftest directly. It registered three new schemas meant to replace `stop!`'s nilable return. It never called `check-platform`. `:capped` |
| 10:00:00 | **hand acts 16-17**: land pass; task `:held`; config restored |

## Per attempt

| Attempt | End (rewritten to) | Calls | Prompt / cached / completion | Wall | Evaluation errors by signature |
|---|---|---|---|---|---|
| ee6cfaeb5c0b | none, wedged (`:sent-back`) | 10 | 85,776 / 56,192 / 1,637 | 36 s working, 4 min wedged | unresolved test vars before `require` 3, unresolved `seon.schema/generator` 1, `register!` "got a vector" (fn objects) 4; then the admitted bad form |
| 88ffa0a88cf2 | `:capped` (`:sent-back` + reason) | 15 | 184,907 / 137,856 / 2,276 | ~65 s | guessed names 4 (`seon.schema/registered?`, `seon.schema/show` ×2, `source-fn`), `register!` refusal 1, digest refusal (J3) 2, DSML 1 |
| 4438ded31ef2 | `:capped` (`:sent-back` + reason) | 15 | 303,639 / 245,120 / 6,365 | ~110 s | settlement refused (J5) 5 forms, `malli.core/form` of a key `:malli.core/invalid-schema` 3, guessed names 3 (`clojure.core/run-test-var`, `my.program/find-definition`, `my.program/grep`), prose-only 1, `...` unresolved 1 |
| b279f729d49b | `:stalled` (`:sent-back` + reason) | 14 | 145,675 / 102,912 / 4,474 | ~245 s (100 s in one check) | `seon.schema/lookup` 1, `register!` arity 1, nilable-return refusal values 2 (J5), DSML 2 |
| 69691a73d375 | `:capped` | 15 | 164,191 / 119,040 / 3,261 | ~125 s | `seon.test/run` given a symbol 1 |
| **total** | | **69** | **884,188 / 661,120 (75 %) / 18,013** | | 37 |

Seams. db<->flow's own pass started the first attempt. A hand pass then started attempts 2-5 in 141-263 ms each. After a
hand-started attempt ended, nothing happened without root: its end stayed on its branch (G8, 4 of 4), and a send-back
rewrite started nothing (4 of 4). About 3-5 s per model call.

## Against experiments 2 and 3

| | Experiment 2 (c792b6aaf) | Experiment 3 (98720b4eb) | Experiment 4 (46a1c4f06) |
|---|---|---|---|
| Subject | `seon.render.value/classify`, one map branch | `seon.sci.forgive/scan`, 185-line scanner | `seon.render.web/start!` + `stop!`, one key and one shutdown, plus a schema member |
| Chain shape | Design+Prove → Implement | Design+Prove → Implement | **Implement only** (proof existed) |
| Attempts / model calls | 7 / 73 | 8 / 105 | 5 / 69 |
| Prompt / cached / completion tokens | 838k / 611k / 22.8k | 1,308k / 951k / 65.5k | 884k / 661k (75 %) / 18.0k |
| Merge request | b210caab20ba | none | **none** |
| Hand acts after launch (config counted once) | 8 | 23 | 17 |
| The code right on some branch | yes | no (property still red) | **yes** (attempt 4, compiled green) |
| Proof runnable on the agent's branch | no (printer errors interpreted) | yes | **no** (`with-server` uninterpretable) |
| Successor started by itself | once | once | once (the first attempt only) |
| Ends of hand-started attempts copied to main | no | no (4/4) | no (4/4) |
| Calls that did the work when nothing got in the way | 14 + 2 | 15 + not reached | 3 (attempt 4) + one `check-platform` call never made |

## Frictions

| # | Friction (observed fact, form and reply) | Producer | Task and proof |
|---|---|---|---|
| J1 | `(seon.schema/register! :seon.render.web/server '[:map … [:seon.render.web/executor seon.render.web/executor?]])`, a bare symbol where a schema goes, was **admitted** on attempt 1's branch. From then on `seon.db/q` with declarations refused `:malli.core/invalid-schema` (`seon.schema/direct-reference-keys-in`). On that branch `seon.task/agent-task` answers that read error, `seon.turn/continuing-reply?` throws `seon.turn/evaluated-form? refused argument 1 turn: expected an integer, got a map`, and `seon.turn/next-agent-work` answers nil. The attempt sat 4 min with no end and no recorded error. | `seon.schema/register!` admission (an uncompilable reference), and the turn path's silence on an invalid read | met once: no task. Recommended to root: the silent wedge breaks "make failure local and loud" |
| J2 | The context marks the intent's `:seon.render.web/executor` as `:problem-kind :unresolved`. Attempts 2, 3 and 4 each quoted that mark as their reason to register a schema, against the instructions. AGENTS.md asks intents to name what the work creates, and ingestion reports unresolved references as problems: the two rules collide on a key the work creates. | `seon.context` reference resolution | owner question, not filed |
| J3 | `(defn p2 {:malli/schema [:=> [:cat [:int {:gen/elements [1 2]}]] :boolean]} [x] …)` at an agent's REPL → diagnostic `The stored definition digest of [:seon.fn/sym …/p2] is not the one its definition produces`. Five probe contracts with a qualified property key were refused; `[:int {:min 0}]` and `[:any {:description "x"}]` were admitted. Attempt 2 met it twice when it copied the shipped `server?` contract shape. | `seon.db/definition-digest-of` vs the REPL path's entity producer | **new** `seon.db/a-contract-property-with-a-qualified-key-is-refused-by-its-digest`, proof `seon.mcp-entrance-test/a-defn-whose-contract-has-a-qualified-property-key-is-admitted` (red: `isError` true and no `:seon.fn/sym` entity, 2 failing assertions) |
| J4 | After `(defn seon.render.web/c4-probe-executor? …)` on a branch, `(seon.schema/malli-form? '[:fn seon.render.web/c4-probe-executor?])` → false, and `register!` refuses "got a vector". `'[:fn seon.render.web/server?]` (a compiled Var) → true. Attempts 1 and 2 met it five times. | `seon.schema/malli-form?` (resolves predicates as host Vars) | **new** `seon.schema/a-fn-schema-over-a-branch-predicate-is-refused`, proof `seon.mcp-entrance-test/a-fn-schema-over-a-predicate-the-agent-defined-is-registered` (red: no `:seon.schema/key` entity, 1 failing assertion) |
| J5 | With `stop!` redefined (contract unchanged) on a branch, `register!` of `:seon.render.web/server` answers `:seon.schema/nilable-return` for `stop!` (reproduced on lane branch `chain4-proof`). Registering first and redefining second admits both (lane probe, attempt 5). In attempt 3 the refusal un-settled every form of its eval request. Attempts 3 and 4 met it. | `seon.schema/register!` re-walking referencing contracts as agent-authored | **new** `seon.schema/registering-a-schema-refuses-a-redefined-core-contract-the-defn-admitted`, **no proof yet**: a test through `stop!` runs its 48 reaching web tests in the test step; the task says what a cheaper fixture needs |
| J8 | The proof's fixture `seon.render.web-test/with-server` cannot be interpreted, so the proof errors on every branch and `my.test/check` of `start!`/`stop!` runs 49 tests in 100.5 s with 25 red or errored. `my.test/check-platform` is the only path to green (68 s), and no attempt called it although the instructions and two reasons named it. Whether its branch evidence ends an attempt `:done` was not reached. | TASK-CHAIN for platform owners (the Implement step's proofs) | not filed: the chain queue's "not platform" judgment for this owner was wrong. A platform-owner task needs `check-platform` in its step, or a compiled test step |
| J6 | `(my.test/check-platform {:seon.test/identities #{'…}})` on an unchanged branch refuses: "Nothing I changed … Name tests with :seon.test/identities", although the request named them. | `my.test/check-platform` | met once |
| G12 | `(my.test/check {:seon.test/identities #{'…}})` on a reused red → `{:seon.test/passed? false}` and nothing else; the same test as `:seon.test/changed` answers its two failure reports. | `my.test/check` summary | met twice (experiment 2, here): recommended for filing with the R-series |
| G6 | Capped and stalled attempts have no relaunch. `:seon.task.attempt/relaunch` and `:relaunch-fork` exist in the schema, but no code reads them at HEAD. Four relaunches were end rewrites, and a self send-back forks main, so attempt 3's correct `start!` was lost. | `seon.task/ready` | existing M12 (met again) |
| G8 | Every hand-started attempt (4 of 4) left its end on its branch until a hand pass, and every send-back rewrite needed a hand pass to start the next attempt. | the branch's listen route | existing M5/M13 (met again) |
| F7 | DeepSeek DSML tool-call replies: attempt 2 once, attempt 4 twice (its stall). | `seon.turn/continuing-reply?` | existing `seon.ai/a-dsml-tool-call-reply-reads-as-prose` |
| G11 | Root's own eval requests on a branch: one `register!` of `:seon.render.web/server` took 12.8 s (test step 12.1 s, about 170 tests pending) and 24.1 s under load. `my.agent/retire-branch!` of the probe branch recorded a Seon error: `seon.error.refusal/diagnostic refused argument 0 at [:seon.error/message] … got nil`, called from `seon.test/run`. | test step; `seon.test/run` | existing F10 / cost queue; the nil message is a new signature, met once |

Of the frictions met twice, J3 and J4 have tasks with red proofs (run on lane chain-4's branch, red for the reason
quoted). J5 has a task whose proof is still open. G6, G8 and F7 were met again under their existing tasks.

## Hand acts (after launch)

1. Config on (provider, cap 15); restored at the end.
2. Attempt 1's open attempt given an end, `:sent-back` with the wedge as its reason (J1).
3. A hand db<->flow pass started attempt 2 (the rewrite started nothing).
4. A hand pass copied attempt 2's `:capped` end to main (G8).
5. The subject's intent refined: the member's schema is the existing `:seon.flow/executor`, no new predicate (J2, J4; the missing Design step's decision).
6. Attempt 2's end → `:sent-back` with a reason.
7. A hand pass started attempt 3.
8. A hand pass copied attempt 3's end (G8).
9. Relaunch facts written on attempt 3 (G6's designed path).
10. A hand pass: nothing started (no reader).
11. Attempt 3's end → `:sent-back` with a reason.
12. A hand pass started attempt 4.
13. A hand pass copied attempt 4's `:stalled` end (G8).
14. Attempt 4's end → `:sent-back` with a three-step reason.
15. A hand pass started attempt 5.
16. A hand pass copied attempt 5's end (G8).
17. Task `:held` (stop); config restored.

## What the next run changes

- Pick a subject whose owner SCI can run and whose proof's fixture is interpretable. Check by running the proof with
  `my.test/check` on a probe branch: an error (not a red) rules the subject out of an interpreted chain.
- A task that adds a schema member needs its schema decided before Implement (a Design step, or the intent names the
  reused schema as it does now). With J3, J4 and J5 open, avoid tasks whose Implement must call `seon.schema/register!`.
- Merge M5/M12/M13 first: 10 of the 17 hand acts were relaunches, end copies or no-op passes.

## Residue on default (disposable)

The subject task (`:held`, its intent refined, `:seon.task/instructions` set). Five `seon.task/attempt-ended-…`
problem tasks, if written. Attempt branches `:attempt-ee6cfaeb5c0b` (its projection is broken: do not fork it),
`-88ffa0a88cf2`, `-4438ded31ef2`, `-b279f729d49b` (the good code), `-69691a73d375` (stray schemas
`:seon.render.web/stoppage`, `stop-outcome`, `stopped`), all for db<->flow to settle. The lane's probe branches
`chain4-probe` and `chain4-proof` were retired.
